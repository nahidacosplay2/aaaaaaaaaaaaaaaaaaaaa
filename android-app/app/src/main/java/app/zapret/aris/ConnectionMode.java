package app.zapret.aris;

import android.content.Context;
import android.content.SharedPreferences;

/** The active route for the Android VpnService. */
final class ConnectionMode {
    static final String ZAPRET = "zapret";
    static final String SOCKS5 = "socks5";

    private static final String KEY_MODE = "connection_mode";
    private static volatile boolean changed;

    private ConnectionMode() {
    }

    static String get(Context context) {
        String value = BypassVpnService.prefs(context).getString(KEY_MODE, ZAPRET);
        return SOCKS5.equals(value) ? SOCKS5 : ZAPRET;
    }

    static boolean isSocks5(Context context) {
        return SOCKS5.equals(get(context));
    }

    static void set(Context context, String mode) {
        if (!ZAPRET.equals(mode) && !SOCKS5.equals(mode)) {
            throw new IllegalArgumentException("unknown connection mode");
        }
        SharedPreferences prefs = BypassVpnService.prefs(context);
        if (!mode.equals(prefs.getString(KEY_MODE, ZAPRET))) {
            prefs.edit().putString(KEY_MODE, mode).apply();
            changed = true;
        }
    }

    static boolean consumeChanged() {
        boolean value = changed;
        changed = false;
        return value;
    }
}
