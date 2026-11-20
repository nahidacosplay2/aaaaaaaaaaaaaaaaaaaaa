package app.zapret.aris;

/**
 * hev-socks5-tunnel: принимает пакеты VPN и отправляет соединения в ByeDPI либо на настроенный SOCKS5-сервер.
 * Имя класса задано при сборке библиотеки (PKGNAME и CLSNAME в native/build-native.sh),
 * а функции она регистрирует сама при загрузке.
 */
final class TunBridge {
    static {
        System.loadLibrary("hev-socks5-tunnel");
    }

    private TunBridge() {
    }

    static native boolean TProxyStartService(String configPath, int fd);

    static native boolean TProxyStopService();

    static native boolean TProxyIsRunning();

    static native long[] TProxyGetStats();
}
