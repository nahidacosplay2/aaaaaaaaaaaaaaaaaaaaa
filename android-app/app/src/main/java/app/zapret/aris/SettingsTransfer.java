package app.zapret.aris;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.zapretcap.tgproxy.ProxyConfig;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.regex.Pattern;

/** Portable JSON backup of app preferences and editable domain lists. */
final class SettingsTransfer {
    private static final int SCHEMA = 1;
    private static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
    private static final Pattern SCOPE = Pattern.compile("(?:wifi|cell|wifi[#@][0-9a-f]{12})");
    private static final Pattern PACKAGE_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+");

    private SettingsTransfer() {
    }

    static byte[] export(Context context) throws IOException, JSONException {
        JSONObject root = new JSONObject();
        root.put("app", "app.zapret.aris");
        root.put("schema", SCHEMA);
        root.put("createdAt", System.currentTimeMillis());

        JSONObject settings = new JSONObject();
        Set<String> strategies = new LinkedHashSet<>(Data.strategies(context));
        for (Map.Entry<String, ?> entry : BypassVpnService.prefs(context).getAll().entrySet()) {
            String key = entry.getKey();
            // Runtime/device-local state is not portable; current app/device state is preserved on import.
            if (isIgnoredKey(key) || !isPortableKey(key, strategies)) {
                continue;
            }
            Object value = portableValue(key, entry.getValue(), strategies);
            if (value == null) {
                continue;
            }
            validateSetting(context, key, value, strategies);
            settings.put(key, encode(value));
        }
        root.put("settings", settings);

        JSONObject lists = new JSONObject();
        for (UserLists.Kind kind : UserLists.Kind.values()) {
            JSONArray domains = new JSONArray();
            for (String domain : UserLists.load(context, kind)) {
                domains.put(domain);
            }
            lists.put(kind.id, domains);
        }
        root.put("domainLists", lists);
        byte[] bytes = (root.toString(2) + "\n").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FILE_BYTES) {
            throw new IOException("созданный файл настроек больше предела 2 МиБ");
        }
        return bytes;
    }

    static void importFrom(Context context, InputStream input) throws IOException, JSONException {
        JSONObject root = new JSONObject(readLimited(input));
        if (!"app.zapret.aris".equals(root.optString("app")) || root.optInt("schema", -1) != SCHEMA) {
            throw new IOException("неподдерживаемый формат файла настроек");
        }
        JSONObject settings = root.optJSONObject("settings");
        JSONObject lists = root.optJSONObject("domainLists");
        if (settings == null || lists == null) {
            throw new IOException("в файле нет настроек или списков доменов");
        }

        List<String> strategies = Data.strategies(context);
        Set<String> strategyNames = new LinkedHashSet<>(strategies);
        Map<String, Object> values = new LinkedHashMap<>();
        Iterator<String> keys = settings.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            // Service state, health cooldowns, old score rows and device-specific prompts are deliberately ignored.
            if (isIgnoredKey(key)) {
                continue;
            }
            if (key.length() > 512 || !isPortableKey(key, strategyNames)) {
                throw new IOException("неизвестная или недопустимая настройка: " + key);
            }
            Object encoded = settings.get(key);
            if (!(encoded instanceof JSONObject)) {
                throw new IOException("неверный формат настройки: " + key);
            }
            Object value = decode((JSONObject) encoded, key);
            validateSetting(context, key, value, strategyNames);
            values.put(key, value);
        }
        validateSocksCredentials(values);

        Map<UserLists.Kind, List<String>> restoredLists = new java.util.EnumMap<>(UserLists.Kind.class);
        int totalDomains = 0;
        for (UserLists.Kind kind : UserLists.Kind.values()) {
            JSONArray entries = lists.optJSONArray(kind.id);
            if (entries == null) {
                throw new IOException("нет списка доменов " + kind.id);
            }
            LinkedHashSet<String> domains = new LinkedHashSet<>();
            for (int i = 0; i < entries.length(); i++) {
                Object value = entries.get(i);
                if (!(value instanceof String)) {
                    throw new IOException("неверное имя домена в списке " + kind.id);
                }
                String domain = UserLists.normalize((String) value);
                if (domain == null || !domain.equals(value)) {
                    throw new IOException("неверное имя домена: " + value);
                }
                domains.add(domain);
                if (++totalDomains > 20000) {
                    throw new IOException("слишком много доменов в файле");
                }
            }
            restoredLists.put(kind, new ArrayList<>(domains));
        }

        // Сначала проверены все значения; пользовательские файлы стадируются и заменяются до изменения настроек.
        UserLists.replaceAll(context, restoredLists);
        SharedPreferences prefs = BypassVpnService.prefs(context);
        boolean telegramWasEnabled = prefs.getBoolean(TgProxySettings.ENABLED, false);
        boolean batteryAsked = prefs.getBoolean("battery_asked", false);
        boolean oemConfirmed = prefs.getBoolean("oem_background_confirmed", false);
        boolean miuiConfirmed = prefs.getBoolean("miui_activity_confirmed", false);
        SharedPreferences.Editor editor = prefs.edit().clear()
                .putBoolean(TgProxySettings.ENABLED, telegramWasEnabled)
                .putBoolean("battery_asked", batteryAsked)
                .putBoolean("oem_background_confirmed", oemConfirmed)
                .putBoolean("miui_activity_confirmed", miuiConfirmed);
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            put(editor, entry.getKey(), entry.getValue());
        }
        editor.apply();
    }

    private static JSONObject encode(Object value) throws JSONException, IOException {
        JSONObject encoded = new JSONObject();
        if (value instanceof String) {
            encoded.put("type", "string").put("value", value);
        } else if (value instanceof Boolean) {
            encoded.put("type", "boolean").put("value", value);
        } else if (value instanceof Integer) {
            encoded.put("type", "int").put("value", value);
        } else if (value instanceof Long) {
            encoded.put("type", "long").put("value", value);
        } else if (value instanceof Float) {
            encoded.put("type", "float").put("value", value);
        } else if (value instanceof Set) {
            JSONArray values = new JSONArray();
            for (Object item : (Set<?>) value) {
                if (!(item instanceof String)) {
                    throw new IOException("неподдерживаемый тип в настройках");
                }
                values.put(item);
            }
            encoded.put("type", "string-set").put("value", values);
        } else {
            throw new IOException("неподдерживаемый тип настройки");
        }
        return encoded;
    }

    private static Object decode(JSONObject encoded, String key) throws JSONException, IOException {
        String type = encoded.optString("type", "");
        Object value = encoded.opt("value");
        switch (type) {
            case "string":
                if (!(value instanceof String) || ((String) value).length() > 256 * 1024) {
                    throw new IOException("неверное текстовое значение: " + key);
                }
                return value;
            case "boolean":
                if (!(value instanceof Boolean)) {
                    throw new IOException("неверное логическое значение: " + key);
                }
                return value;
            case "int":
                if (!(value instanceof Integer)
                        && (!(value instanceof Long) || (Long) value < Integer.MIN_VALUE || (Long) value > Integer.MAX_VALUE)) {
                    throw new IOException("неверное целое число: " + key);
                }
                return ((Number) value).intValue();
            case "long":
                if (!(value instanceof Integer) && !(value instanceof Long)) {
                    throw new IOException("неверное целое число: " + key);
                }
                return ((Number) value).longValue();
            case "float":
                if (!(value instanceof Number) || !Float.isFinite(((Number) value).floatValue())) {
                    throw new IOException("неверное дробное число: " + key);
                }
                return ((Number) value).floatValue();
            case "string-set":
                JSONArray array = encoded.getJSONArray("value");
                if (array.length() > 10000) {
                    throw new IOException("слишком большой список настройки: " + key);
                }
                Set<String> strings = new LinkedHashSet<>();
                for (int i = 0; i < array.length(); i++) {
                    Object item = array.get(i);
                    if (!(item instanceof String) || ((String) item).length() > 4096) {
                        throw new IOException("неверный элемент списка: " + key);
                    }
                    strings.add((String) item);
                }
                return strings;
            default:
                throw new IOException("неизвестный тип настройки: " + key);
        }
    }

    private static boolean isIgnoredKey(String key) {
        return TgProxySettings.ENABLED.equals(key)
                || key.startsWith(Data.FAILED_STRATEGY_PREFIX)
                || key.startsWith(Data.SCORE_KEY_PREFIX)
                || key.startsWith("freeze:")
                || "battery_asked".equals(key)
                || "oem_background_confirmed".equals(key)
                || "miui_activity_confirmed".equals(key);
    }

    private static Object portableValue(String key, Object value, Set<String> strategies) {
        if (key.startsWith(Data.STRATEGY_KEY_PREFIX) || key.startsWith(Data.LAST_WORKING_PREFIX)) {
            return value instanceof String && strategies.contains(value) ? value : null;
        }
        if (Data.FAVORITES_KEY.equals(key) && value instanceof Set) {
            Set<String> valid = new LinkedHashSet<>();
            for (Object favorite : (Set<?>) value) {
                if (favorite instanceof String && strategies.contains(favorite)) {
                    valid.add((String) favorite);
                }
            }
            return valid;
        }
        if (TgProxySettings.STRATEGY.equals(key)
                && (!(value instanceof String) || (!((String) value).isEmpty() && !strategies.contains(value)))) {
            return null;
        }
        return value;
    }

    private static boolean isPortableKey(String key, Set<String> strategies) {
        if (key.startsWith(Data.STRATEGY_KEY_PREFIX)) {
            return isScope(key.substring(Data.STRATEGY_KEY_PREFIX.length()));
        }
        if (key.startsWith(Data.LAST_WORKING_PREFIX)) {
            return isScope(key.substring(Data.LAST_WORKING_PREFIX.length()));
        }
        if (key.startsWith(Data.TTL_KEY_PREFIX)) {
            return scopedStrategy(key.substring(Data.TTL_KEY_PREFIX.length()), strategies) != null;
        }
        if (key.startsWith("fake_ttl:")) {
            String kind = key.substring("fake_ttl:".length());
            return NetKind.WIFI.equals(kind) || NetKind.CELL.equals(kind);
        }
        return Data.FAVORITES_KEY.equals(key)
                || Data.AUTO_FAILOVER_KEY.equals(key)
                || Data.FAILURE_ACTION_KEY.equals(key)
                || "connection_mode".equals(key)
                || "ipset_mode".equals(key)
                || "discord_fake".equals(key)
                || "hosts_enabled".equals(key)
                || "socks5_host".equals(key)
                || "socks5_port".equals(key)
                || "socks5_username".equals(key)
                || "socks5_password".equals(key)
                || "socks5_excluded_package".equals(key)
                || TgProxySettings.PORT.equals(key)
                || TgProxySettings.SECRET.equals(key)
                || TgProxySettings.DC_IPS.equals(key)
                || TgProxySettings.CFPROXY.equals(key)
                || TgProxySettings.CF_DOMAINS.equals(key)
                || TgProxySettings.H2.equals(key)
                || TgProxySettings.WORKER_DOMAINS.equals(key)
                || TgProxySettings.NO_SECURE.equals(key)
                || TgProxySettings.BUF_KB.equals(key)
                || TgProxySettings.POOL_SIZE.equals(key)
                || TgProxySettings.STRATEGY.equals(key);
    }

    private static boolean isScope(String scope) {
        return scope != null && SCOPE.matcher(scope).matches();
    }

    /** The part after a valid network scope in a ttl:<scope>:<strategy> preference key. */
    private static String scopedStrategy(String value, Set<String> strategies) {
        int separator = value.indexOf(':');
        if (separator <= 0 || separator == value.length() - 1) {
            return null;
        }
        String scope = value.substring(0, separator);
        String strategy = value.substring(separator + 1);
        return isScope(scope) && strategies.contains(strategy) ? strategy : null;
    }

    private static void validateSetting(Context context, String key, Object value, Set<String> strategies)
            throws IOException {
        if (key.startsWith(Data.STRATEGY_KEY_PREFIX)) {
            requireStrategy(value, strategies, key);
        } else if (key.startsWith(Data.LAST_WORKING_PREFIX)) {
            requireStrategy(value, strategies, key);
        } else if (key.startsWith(Data.TTL_KEY_PREFIX)) {
            if (scopedStrategy(key.substring(Data.TTL_KEY_PREFIX.length()), strategies) == null) {
                throw new IOException("неизвестная стратегия в настройке " + key);
            }
            requireInteger(value, 0, 12, key);
        } else if (Data.FAVORITES_KEY.equals(key)) {
            if (!(value instanceof Set)) {
                throw new IOException("неверный список избранного");
            }
            for (Object favorite : (Set<?>) value) {
                requireStrategy(favorite, strategies, key);
            }
        } else if (Data.AUTO_FAILOVER_KEY.equals(key) || "hosts_enabled".equals(key)
                || TgProxySettings.CFPROXY.equals(key) || TgProxySettings.H2.equals(key)
                || TgProxySettings.NO_SECURE.equals(key)) {
            requireBoolean(value, key);
        } else if (TgProxySettings.STRATEGY.equals(key)) {
            if (!(value instanceof String) || (!((String) value).isEmpty() && !strategies.contains(value))) {
                throw new IOException("неизвестная стратегия Telegram");
            }
        } else if ("connection_mode".equals(key)) {
            if (!(ConnectionMode.ZAPRET.equals(value) || ConnectionMode.SOCKS5.equals(value))) {
                throw new IOException("неизвестный режим маршрута");
            }
        } else if (Data.FAILURE_ACTION_KEY.equals(key)) {
            if (!(Data.FAILURE_ACTION_DIRECT.equals(value) || Data.FAILURE_ACTION_STOP.equals(value))) {
                throw new IOException("неизвестное действие при отказе");
            }
        } else if ("ipset_mode".equals(key)) {
            try {
                if (!(value instanceof String)) {
                    throw new IllegalArgumentException();
                }
                BypassSettings.IpsetMode.valueOf((String) value);
            } catch (IllegalArgumentException e) {
                throw new IOException("неизвестный режим IPSet");
            }
        } else if ("discord_fake".equals(key)) {
            if (!(value instanceof String) || !BypassSettings.fakeChoices(context).contains(value)) {
                throw new IOException("неизвестный файл фейка Discord");
            }
        } else if (key.startsWith("fake_ttl:")) {
            requireInteger(value, 0, 12, key);
            int ttl = (Integer) value;
            if (ttl != 0 && !contains(BypassSettings.FAKE_TTL_CHOICES, ttl)) {
                throw new IOException("недопустимый TTL фейка");
            }
        } else if (TgProxySettings.SECRET.equals(key)) {
            if (!(value instanceof String) || !ProxyConfig.isValidSecret((String) value)) {
                throw new IOException("неверный секрет MTProto");
            }
        } else if (TgProxySettings.PORT.equals(key)) {
            requireInteger(value, 1, 65535, key);
        } else if (TgProxySettings.BUF_KB.equals(key)) {
            requireInteger(value, 4, 16384, key);
        } else if (TgProxySettings.POOL_SIZE.equals(key)) {
            requireInteger(value, 0, 32, key);
        } else if (TgProxySettings.DC_IPS.equals(key)) {
            String text = requireString(value, key);
            try {
                ProxyConfig.parseDcIpList(TgProxySettings.dcLines(text));
            } catch (IllegalArgumentException e) {
                throw new IOException("неверный список адресов датацентров Telegram");
            }
        } else if (TgProxySettings.CF_DOMAINS.equals(key) || TgProxySettings.WORKER_DOMAINS.equals(key)) {
            requireString(value, key);
        } else if ("socks5_port".equals(key)) {
            requireInteger(value, 0, 65535, key);
        } else if ("socks5_host".equals(key)) {
            String host = requireString(value, key);
            if (!host.isEmpty() && !host.equals(Socks5Settings.normalizeHost(host))) {
                throw new IOException("неверный адрес SOCKS5");
            }
        } else if ("socks5_username".equals(key) || "socks5_password".equals(key)) {
            if (!validCredential(requireString(value, key))) {
                throw new IOException("неверные учётные данные SOCKS5");
            }
        } else if ("socks5_excluded_package".equals(key)) {
            String packageName = requireString(value, key);
            if (!packageName.isEmpty() && (!PACKAGE_NAME.matcher(packageName).matches()
                    || context.getPackageName().equals(packageName))) {
                throw new IOException("неверное исключаемое приложение SOCKS5");
            }
        } else {
            throw new IOException("неизвестная настройка: " + key);
        }
    }

    private static boolean contains(int[] values, int value) {
        for (int candidate : values) {
            if (candidate == value) {
                return true;
            }
        }
        return false;
    }

    private static String requireString(Object value, String key) throws IOException {
        if (!(value instanceof String)) {
            throw new IOException("неверный текст в настройке " + key);
        }
        return (String) value;
    }

    private static void requireBoolean(Object value, String key) throws IOException {
        if (!(value instanceof Boolean)) {
            throw new IOException("неверное логическое значение: " + key);
        }
    }

    private static boolean validCredential(String value) {
        if (value.getBytes(StandardCharsets.UTF_8).length > 255) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static void validateSocksCredentials(Map<String, Object> settings) throws IOException {
        String username = settings.get("socks5_username") instanceof String
                ? (String) settings.get("socks5_username") : "";
        String password = settings.get("socks5_password") instanceof String
                ? (String) settings.get("socks5_password") : "";
        if (username.isEmpty() != password.isEmpty()) {
            throw new IOException("логин и пароль SOCKS5 должны быть указаны вместе");
        }
    }

    private static void requireStrategy(Object value, Set<String> strategies, String key) throws IOException {
        if (!(value instanceof String) || !strategies.contains(value)) {
            throw new IOException("неизвестная стратегия в настройке " + key);
        }
    }

    private static void requireInteger(Object value, int min, int max, String key) throws IOException {
        if (!(value instanceof Integer) || (Integer) value < min || (Integer) value > max) {
            throw new IOException("недопустимое число в настройке " + key);
        }
    }

    @SuppressWarnings("unchecked")
    private static void put(SharedPreferences.Editor editor, String key, Object value) {
        if (value instanceof String) {
            editor.putString(key, (String) value);
        } else if (value instanceof Boolean) {
            editor.putBoolean(key, (Boolean) value);
        } else if (value instanceof Integer) {
            editor.putInt(key, (Integer) value);
        } else if (value instanceof Long) {
            editor.putLong(key, (Long) value);
        } else if (value instanceof Float) {
            editor.putFloat(key, (Float) value);
        } else if (value instanceof Set) {
            editor.putStringSet(key, (Set<String>) value);
        }
    }

    private static String readLimited(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (read == 0) {
                int one = input.read();
                if (one == -1) {
                    break;
                }
                if (++total > MAX_FILE_BYTES) {
                    throw new IOException("файл настроек слишком большой");
                }
                output.write(one);
                continue;
            }
            total += read;
            if (total > MAX_FILE_BYTES) {
                throw new IOException("файл настроек слишком большой");
            }
            output.write(buffer, 0, read);
        }
        return output.toString("UTF-8");
    }
}
