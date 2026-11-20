package app.zapret.aris;

import android.content.Context;
import android.content.SharedPreferences;

import java.net.IDN;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.regex.Pattern;

/** Private, local settings for the optional SOCKS5 route. Credentials are never written to logs. */
final class Socks5Settings {
    private static final String KEY_HOST = "socks5_host";
    private static final String KEY_PORT = "socks5_port";
    private static final String KEY_USERNAME = "socks5_username";
    private static final String KEY_PASSWORD = "socks5_password";
    private static final String KEY_EXCLUDED_PACKAGE = "socks5_excluded_package";
    private static volatile boolean changed;

    private static final Pattern PACKAGE_NAME = Pattern.compile(
            "[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+");
    private static final Pattern IPV6_CHARS = Pattern.compile("[0-9A-Fa-f:.]+");

    static final class Endpoint {
        final String host;
        final int port;
        final String username;
        final String password;
        final String excludedPackage;

        Endpoint(String host, int port, String username, String password, String excludedPackage) {
            this.host = host;
            this.port = port;
            this.username = username;
            this.password = password;
            this.excludedPackage = excludedPackage;
        }

        boolean hasAuthentication() {
            return !username.isEmpty();
        }
    }

    private Socks5Settings() {
    }

    static Endpoint load(Context context) {
        SharedPreferences prefs = BypassVpnService.prefs(context);
        return new Endpoint(
                prefs.getString(KEY_HOST, ""),
                prefs.getInt(KEY_PORT, 0),
                prefs.getString(KEY_USERNAME, ""),
                prefs.getString(KEY_PASSWORD, ""),
                prefs.getString(KEY_EXCLUDED_PACKAGE, ""));
    }

    static Endpoint fromInput(Context context, String host, String port, String username,
                              String password, String excludedPackage) {
        String normalizedHost = normalizeHost(host);
        if (normalizedHost == null) {
            throw new IllegalArgumentException(context.getString(R.string.socks_invalid_host));
        }

        int parsedPort;
        try {
            parsedPort = Integer.parseInt(port.trim());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(context.getString(R.string.socks_invalid_port));
        }
        if (parsedPort < 1 || parsedPort > 65535) {
            throw new IllegalArgumentException(context.getString(R.string.socks_invalid_port));
        }

        String user = username.trim();
        String pass = password;
        if (user.isEmpty() != pass.isEmpty()) {
            throw new IllegalArgumentException(context.getString(R.string.socks_auth_pair));
        }
        if (containsControl(user) || containsControl(pass)
                || user.getBytes(StandardCharsets.UTF_8).length > 255
                || pass.getBytes(StandardCharsets.UTF_8).length > 255) {
            throw new IllegalArgumentException(context.getString(R.string.socks_auth_too_long));
        }

        String excluded = excludedPackage.trim();
        if (!excluded.isEmpty()) {
            if (!PACKAGE_NAME.matcher(excluded).matches()
                    || context.getPackageName().equals(excluded)
                    || !isInstalled(context, excluded)) {
                throw new IllegalArgumentException(context.getString(R.string.socks_invalid_package));
            }
        }
        return new Endpoint(normalizedHost, parsedPort, user, pass, excluded);
    }

    static void save(Context context, Endpoint endpoint) {
        SharedPreferences prefs = BypassVpnService.prefs(context);
        prefs.edit()
                .putString(KEY_HOST, endpoint.host)
                .putInt(KEY_PORT, endpoint.port)
                .putString(KEY_USERNAME, endpoint.username)
                .putString(KEY_PASSWORD, endpoint.password)
                .putString(KEY_EXCLUDED_PACKAGE, endpoint.excludedPackage)
                .apply();
        changed = true;
    }

    static boolean isConfigured(Context context) {
        Endpoint endpoint = load(context);
        return normalizeHost(endpoint.host) != null && endpoint.port >= 1 && endpoint.port <= 65535;
    }

    static boolean consumeChanged() {
        boolean value = changed;
        changed = false;
        return value;
    }

    static String summary(Context context) {
        Endpoint endpoint = load(context);
        if (endpoint.host.isEmpty() || endpoint.port < 1 || endpoint.port > 65535) {
            return context.getString(R.string.socks_not_configured);
        }
        String host = endpoint.host.indexOf(':') >= 0 ? "[" + endpoint.host + "]" : endpoint.host;
        return host + ":" + endpoint.port + (endpoint.hasAuthentication() ? " · "
                + context.getString(R.string.socks_auth_enabled) : "");
    }

    static String routeFingerprint(String mode, Endpoint endpoint, int localPort) {
        String value = mode + "\u0000" + localPort + "\u0000" + endpoint.host + "\u0000"
                + endpoint.port + "\u0000" + endpoint.username + "\u0000" + endpoint.password
                + "\u0000" + endpoint.excludedPackage;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : digest) {
                out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException impossible) {
            return Integer.toHexString(value.hashCode());
        }
    }

    static String yamlQuote(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static String normalizeHost(String raw) {
        if (raw == null) {
            return null;
        }
        String host = raw.trim();
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        if (host.isEmpty() || host.length() > 253 || containsControl(host)
                || host.indexOf('\n') >= 0 || host.indexOf('\r') >= 0) {
            return null;
        }
        if (host.indexOf(':') >= 0) {
            if (!IPV6_CHARS.matcher(host).matches()) {
                return null;
            }
            try {
                return InetAddress.getByName(host) instanceof Inet6Address ? host : null;
            } catch (Exception e) {
                return null;
            }
        }
        if (host.matches("[0-9.]+")) {
            try {
                return InetAddress.getByName(host) instanceof Inet4Address ? host : null;
            } catch (Exception e) {
                return null;
            }
        }
        try {
            String ascii = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES);
            if (ascii.length() > 253 || ascii.isEmpty()) {
                return null;
            }
            for (String label : ascii.split("\\.", -1)) {
                if (label.isEmpty() || label.length() > 63 || label.startsWith("-") || label.endsWith("-")) {
                    return null;
                }
            }
            return ascii;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean containsControl(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c)) {
                return true;
            }
        }
        return false;
    }

    static boolean isInstalled(Context context, String packageName) {
        try {
            context.getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
