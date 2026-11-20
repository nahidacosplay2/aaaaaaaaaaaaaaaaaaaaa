package app.zapret.aris;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns the Android TUN route. Zapret mode sends it through HEV to the local ByeDPI engine;
 * SOCKS5 mode sends it to the configured upstream proxy. The app UID is excluded so these
 * upstream connections do not loop back into the TUN.
 */
public class BypassVpnService extends VpnService {
    private static final String TAG = "ZapretAris";
    static final String ACTION_START = "app.zapret.aris.START";
    static final String ACTION_STOP = "app.zapret.aris.STOP";

    private static final String TUN_IPV4 = "198.18.0.1";
    private static final String TUN_IPV6 = "fc00::1";
    /** DNS target assigned to apps. Zapret mode forwards it through ciadpi; SOCKS5 mode uses HEV mapdns. */
    private static final String TUN_DNS = "198.18.0.2";
    private static final int TUN_MTU = 8500;
    /**
     * Сколько туннель держит соединение без данных. Пуши Google проверяют связь раз в 9-29 минут (до 1730 с),
     * поэтому нужен запас сверх получаса: иначе их соединение рвалось бы перед самой проверкой.
     */
    private static final int TCP_IDLE_TIMEOUT_MS = 60 * 60 * 1000;
    private static final int NOTIFICATION_ID = 1;
    private static final int FAILURE_NOTIFICATION_ID = 4;

    /** Запуск, смена стратегии и остановка идут строго по очереди. */
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService probeWorker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean probeInFlight = new AtomicBoolean();

    /** Сеть меняется рывками, особенно при переходе Wi-Fi - мобильная: реагируем, когда она успокоилась. */
    private static final long NETWORK_SETTLE_MS = 1500;
    /** Несколько падений в пределах двух минут означают, что стратегию лучше сменить. */
    private static final int MAX_QUICK_RESTARTS = 3;
    private static final long QUICK_RESTART_MS = 2 * 60_000L;
    private static final long HEALTH_INTERVAL_MS = 30_000;
    private static final long STRATEGY_PROBE_INTERVAL_MS = 60_000L;
    private static final long STRATEGY_PROBE_INITIAL_DELAY_MS = 45_000L;
    private static final int STRATEGY_PROBE_FAILURE_LIMIT = 3;
    private static final int MAX_AUTOMATIC_SWITCHES = 5;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable networkSettled = () -> runOnWorker(this::onNetworkSettled);
    private final Runnable healthTick = new Runnable() {
        @Override
        public void run() {
            runOnWorker(BypassVpnService.this::checkHealth);
            main.postDelayed(this, HEALTH_INTERVAL_MS);
        }
    };

    /** События только будят проверку: какая сеть настоящая, решает NetKind.physical. */
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(Network network) {
            onNetworkEvent();
        }

        @Override
        public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
            onNetworkEvent();
        }

        @Override
        public void onLinkPropertiesChanged(Network network, LinkProperties properties) {
            onNetworkEvent();
        }

        @Override
        public void onLost(Network network) {
            onNetworkEvent();
        }
    };

    private ParcelFileDescriptor tun;
    private volatile Process ciadpi;
    private int ciadpiPort;
    private String strategy;
    private String tunnelRouteFingerprint;
    /** Сеть, для которой запущен движок: соединения движка привязаны к ней и умирают вместе с ней. */
    private Network network;
    private boolean tunHasIpv6;
    private String tunExcludedPackage = "";
    private boolean networkCallbackRegistered;
    /** Порт работающего движка обхода. Нужен самому приложению, например чтобы скачать списки, когда GitHub закрыт. */
    static volatile int enginePort;
    private DnsForwarder dns;
    private int dnsPort;
    private int quickRestarts;
    private long lastEngineStart;
    private long nextStrategyProbeAt;
    private int consecutiveProbeFailures;
    private int failoverAttempts;
    private String failoverFrom;
    private String failoverReason;

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("settings", MODE_PRIVATE);
    }

    private String routeName() {
        return ConnectionMode.isSocks5(this) ? getString(R.string.mode_socks5)
                : Data.strategyFor(this, NetKind.current(this));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        CrashLog.install(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            runOnWorker(() -> stop(null));
            return START_NOT_STICKY;
        }
        // Без intent и без ACTION_START служба поднята системой: постоянная VPN или перезапуск после сбоя
        String name = routeName();
        boolean manualStart = intent != null && ACTION_START.equals(intent.getAction());
        if (manualStart && !ConnectionMode.isSocks5(this)) {
            Data.clearFailure(this, NetKind.current(this), name);
        }
        showNotification(name);
        runOnWorker(() -> start(name, manualStart));
        return START_STICKY;
    }

    private void runOnWorker(Runnable task) {
        try {
            worker.execute(task);
        } catch (RejectedExecutionException serviceAlreadyDestroyed) {
            Log.i(TAG, "worker stopped");
        }
    }

    private void onNetworkEvent() {
        main.removeCallbacks(networkSettled);
        main.postDelayed(networkSettled, NETWORK_SETTLE_MS);
    }

    /**
     * Без этого соединения остаются привязаны к пропавшей сети: приложения ждут ответа минутами. После перезапуска
     * движка они сразу получают обрыв и переподключаются, а выбор конфига и кэш движка соответствуют новой сети.
     */
    private void onNetworkSettled() {
        Network next = NetKind.physical(this);
        if (tun == null || next == null || next.equals(network)) {
            return;
        }
        Log.i(TAG, "network changed: " + network + " -> " + next);
        failoverAttempts = 0;
        consecutiveProbeFailures = 0;
        quickRestarts = 0;
        start(routeName(), false);
    }

    /** Both the SOCKS engine and the TUN bridge must be alive for routed traffic to make progress. */
    private void checkHealth() {
        BackgroundChecks.refreshNotification(this);
        if (tun != null && !TunBridge.TProxyIsRunning()) {
            Log.e(TAG, "hev-socks5-tunnel stopped, restarting");
            try {
                boolean proxyMode = ConnectionMode.isSocks5(this);
                restartTunnel(tunHasIpv6, proxyMode, proxyMode ? Socks5Settings.load(this) : null);
            } catch (Exception e) {
                stop("Туннель остановился: " + e.getMessage());
                return;
            }
        }
        if (ConnectionMode.isSocks5(this)) {
            return;
        }
        Process process = ciadpi;
        if (process == null || ciadpiPort == 0) {
            return;
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", ciadpiPort), 3000);
        } catch (IOException e) {
            if (process == ciadpi) {
                Log.e(TAG, "ciadpi not responding, restarting");
                restartAfterFailure("Движок не отвечает");
            }
            return;
        }
        scheduleStrategyProbe();
    }

    private void scheduleStrategyProbe() {
        long now = System.currentTimeMillis();
        String sampledStrategy = strategy;
        int sampledPort = ciadpiPort;
        Process sampledEngine = ciadpi;
        Network sampledNetwork = network;
        if (sampledStrategy == null || sampledEngine == null || sampledNetwork == null
                || !sampledNetwork.equals(NetKind.physical(this))
                || now < nextStrategyProbeAt || !probeInFlight.compareAndSet(false, true)) {
            return;
        }
        nextStrategyProbeAt = now + STRATEGY_PROBE_INTERVAL_MS;
        try {
            probeWorker.execute(() -> {
                int successes;
                try {
                    successes = StrategyHealthProbe.successes(sampledNetwork, sampledPort);
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                    probeInFlight.set(false);
                    return;
                } catch (RuntimeException failedProbe) {
                    Log.w(TAG, "strategy health probe failed", failedProbe);
                    successes = 0;
                }
                probeInFlight.set(false);
                runOnWorker(() -> finishStrategyProbe(sampledStrategy, sampledPort, sampledEngine, sampledNetwork, successes));
            });
        } catch (RejectedExecutionException serviceAlreadyDestroyed) {
            probeInFlight.set(false);
        }
    }

    private void finishStrategyProbe(String sampledStrategy, int sampledPort, Process sampledEngine,
                                      Network sampledNetwork, int successes) {
        Network physicalNow = NetKind.physical(this);
        boolean sameNetwork = sampledNetwork.equals(network) && sampledNetwork.equals(physicalNow);
        if (tun == null || AppState.phase != AppState.Phase.ON || ConnectionMode.isSocks5(this)
                || !sampledStrategy.equals(strategy) || sampledPort != ciadpiPort
                || sampledEngine != ciadpi || !Data.isAlive(sampledEngine) || !sameNetwork) {
            return;
        }
        if (successes >= 2) {
            if (consecutiveProbeFailures > 0) {
                Log.i(TAG, "strategy health recovered: " + sampledStrategy + " (" + successes + "/3)");
            }
            consecutiveProbeFailures = 0;
            failoverAttempts = 0;
            quickRestarts = 0;
            Data.markWorking(this, NetKind.current(this), sampledStrategy);
            return;
        }
        consecutiveProbeFailures++;
        Log.w(TAG, "strategy probe failed: " + sampledStrategy + " (" + successes + "/3, "
                + consecutiveProbeFailures + "/" + STRATEGY_PROBE_FAILURE_LIMIT + ")");
        if (consecutiveProbeFailures >= STRATEGY_PROBE_FAILURE_LIMIT) {
            handleStrategyFailure(sampledStrategy, getString(R.string.failover_health_failed, successes));
        }
    }

    private void restartAfterFailure(String reason) {
        long now = System.currentTimeMillis();
        quickRestarts = now - lastEngineStart < QUICK_RESTART_MS ? quickRestarts + 1 : 1;
        if (quickRestarts >= MAX_QUICK_RESTARTS) {
            if (!ConnectionMode.isSocks5(this) && strategy != null) {
                handleStrategyFailure(strategy, reason);
            } else {
                stop(reason);
            }
            return;
        }
        start(strategy != null ? strategy : routeName(), false);
    }

    @Override
    public void onRevoke() {
        // VPN включили у другого приложения или отозвали разрешение
        runOnWorker(() -> stop(null));
    }

    @Override
    public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        unregisterNetwork();
        probeWorker.shutdownNow();
        worker.execute(this::releaseEngines);
        worker.shutdown();
        super.onDestroy();
    }

    private void start(String name, boolean manualStart) {
        main.removeCallbacks(healthTick);
        if (failoverFrom == null) {
            AppState.setRouteNotice(null);
        }
        consecutiveProbeFailures = 0;
        nextStrategyProbeAt = System.currentTimeMillis() + STRATEGY_PROBE_INITIAL_DELAY_MS;
        if (manualStart && !ConnectionMode.isSocks5(this)) {
            Data.clearFailure(this, NetKind.current(this), name);
            failoverAttempts = 0;
            quickRestarts = 0;
        }
        boolean strategyStartAttempted = false;
        boolean strategyEngineReady = false;
        boolean proxyMode = ConnectionMode.isSocks5(this);
        AppState.set(AppState.Phase.STARTING, name, null);
        try {
            registerNetwork();
            ConnectivityManager manager = getSystemService(ConnectivityManager.class);
            Network active = NetKind.physical(this);
            String kind = NetKind.current(this);
            proxyMode = ConnectionMode.isSocks5(this);
            Socks5Settings.Endpoint endpoint = proxyMode ? Socks5Settings.load(this) : null;

            if (proxyMode) {
                if (endpoint.host.isEmpty() || endpoint.port < 1 || endpoint.port > 65535) {
                    throw new IOException(getString(R.string.socks_settings_required));
                }
                stopDns();
                stopCiadpi();
                enginePort = 0;
            } else {
                Data.extract(this);
                Data.applyLists(this);
                startDns(manager, active);
                strategyStartAttempted = true;
                startCiadpi(name, kind);
                strategyEngineReady = true;
                lastEngineStart = System.currentTimeMillis();
                enginePort = ciadpiPort;
            }

            boolean ipv6 = hasGlobalIpv6(manager, active);
            String excludedPackage = proxyMode && endpoint != null ? endpoint.excludedPackage : "";
            boolean tunConfigChanged = tun != null && (ipv6 != tunHasIpv6
                    || !excludedPackage.equals(tunExcludedPackage));
            boolean networkChanged = network != null && active != null && !active.equals(network);
            if (tunConfigChanged) {
                // Address families and per-app exclusions belong to the Android TUN builder.
                closeTunnel();
            }
            String fingerprint = tunnelFingerprint(proxyMode, endpoint);
            if (tun == null) {
                tun = establish(ipv6, endpoint);
                tunHasIpv6 = ipv6;
                tunExcludedPackage = excludedPackage;
                writeTunnelConfig(ipv6, proxyMode, endpoint);
                if (!TunBridge.TProxyStartService(tunnelConfig().getPath(), tun.getFd())) {
                    throw new IOException("Не запустился hev-socks5-tunnel");
                }
                tunnelRouteFingerprint = fingerprint;
            } else if (networkChanged || !fingerprint.equals(tunnelRouteFingerprint)) {
                // Re-read the SOCKS endpoint after a mode, credential or physical-network change.
                restartTunnel(ipv6, proxyMode, endpoint);
            }

            setUnderlyingNetworks(active == null ? null : new Network[]{active});
            network = active;
            strategy = name;
            getSystemService(NotificationManager.class).cancel(FAILURE_NOTIFICATION_ID);
            BackgroundChecks.refreshNotification(this);
            showNotification(name);
            main.removeCallbacks(healthTick);
            main.postDelayed(healthTick, HEALTH_INTERVAL_MS);
            AppState.set(AppState.Phase.ON, name, null);
            if (failoverFrom != null) {
                String previous = failoverFrom;
                String why = failoverReason;
                failoverFrom = null;
                failoverReason = null;
                showFailoverNotification(previous, name, why);
            }
        } catch (Exception e) {
            Log.e(TAG, "start failed", e);
            if (!proxyMode && strategyStartAttempted && !strategyEngineReady) {
                handleStrategyFailure(name, getString(R.string.failover_start_error, name,
                        String.valueOf(e.getMessage())));
            } else {
                stop(e.getMessage());
            }
        }
    }

    /** Try known-good and favourite strategies first; cooldown prevents an endless fallback loop. */
    private String nextStrategy(String kind, String current) {
        List<String> names;
        try {
            names = Data.strategies(this);
        } catch (IOException e) {
            Log.e(TAG, "cannot list strategies for failover", e);
            return null;
        }
        long now = System.currentTimeMillis();
        List<String> ordered = new ArrayList<>();
        String lastWorking = Data.lastWorking(this, kind);
        if (lastWorking != null) {
            ordered.add(lastWorking);
        }
        List<String> favorites = new ArrayList<>(Data.favorites(this));
        Collections.sort(favorites, Data::compareNatural);
        ordered.addAll(favorites);
        ordered.add(Data.recommended(kind));
        ordered.addAll(names);
        for (String name : ordered) {
            if (!name.equals(current) && names.contains(name) && !Data.recentlyFailed(this, kind, name, now)) {
                return name;
            }
        }
        return null;
    }

    private void handleStrategyFailure(String failed, String reason) {
        String kind = NetKind.current(this);
        Data.markFailed(this, kind, failed);
        consecutiveProbeFailures = 0;
        quickRestarts = 0;
        boolean autoFailover = Data.autoFailoverEnabled(this);
        String candidate = autoFailover && failoverAttempts < MAX_AUTOMATIC_SWITCHES
                ? nextStrategy(kind, failed) : null;
        if (candidate != null) {
            failoverAttempts++;
            Log.w(TAG, "strategy failed: " + failed + "; switching to " + candidate + ": " + reason);
            main.removeCallbacks(healthTick);
            releaseEngines();
            strategy = null;
            network = null;
            failoverFrom = failed;
            failoverReason = reason;
            Data.setFailoverStrategyFor(this, kind, candidate);
            String notice = getString(R.string.failover_switched, failed, candidate);
            AppState.setRouteNotice(notice);
            AppState.set(AppState.Phase.STARTING, candidate, notice);
            showNotification(candidate);
            runOnWorker(() -> start(candidate, false));
            return;
        }

        boolean direct = Data.failureAction(this).equals(Data.FAILURE_ACTION_DIRECT);
        String terminal = direct
                ? getString(R.string.failover_direct_message, failed, reason)
                : getString(R.string.failover_stop_message, failed, reason);
        Log.e(TAG, "strategy failover exhausted: " + terminal);
        showFailureNotification(terminal);
        stop(terminal);
    }

    private String tunnelFingerprint(boolean proxyMode, Socks5Settings.Endpoint endpoint) {
        if (proxyMode) {
            return Socks5Settings.routeFingerprint(ConnectionMode.SOCKS5, endpoint, 0);
        }
        Socks5Settings.Endpoint local = new Socks5Settings.Endpoint("127.0.0.1", ciadpiPort, "", "", "");
        return Socks5Settings.routeFingerprint(ConnectionMode.ZAPRET, local, ciadpiPort);
    }

    private void restartTunnel(boolean ipv6, boolean proxyMode, Socks5Settings.Endpoint endpoint) throws IOException {
        if (tun == null) {
            throw new IOException("Android TUN is not established");
        }
        TunBridge.TProxyStopService();
        writeTunnelConfig(ipv6, proxyMode, endpoint);
        if (!TunBridge.TProxyStartService(tunnelConfig().getPath(), tun.getFd())) {
            throw new IOException("Не удалось перезапустить hev-socks5-tunnel");
        }
        tunnelRouteFingerprint = tunnelFingerprint(proxyMode, endpoint);
    }

    private void stopDns() {
        if (dns != null) {
            dns.stop();
            dns = null;
            dnsPort = 0;
        }
    }

    private void startDns(ConnectivityManager manager, Network active) throws IOException {
        if (dns == null) {
            dns = new DnsForwarder();
            dnsPort = dns.start();
        }
        boolean changedNetwork = active == null || !active.equals(network);
        if (changedNetwork) {
            dns.onNetworkChanged();
        }
        LinkProperties properties = active == null ? null : manager.getLinkProperties(active);
        dns.setNetworkServers(properties == null ? java.util.Collections.emptyList() : properties.getDnsServers());
        dns.setHosts(BypassSettings.hosts(this));
        if (tun == null || changedNetwork) {
            dns.warmUp();
        }
    }

    private void registerNetwork() {
        if (!networkCallbackRegistered) {
            getSystemService(ConnectivityManager.class).registerDefaultNetworkCallback(networkCallback);
            networkCallbackRegistered = true;
        }
    }

    private void unregisterNetwork() {
        if (networkCallbackRegistered) {
            getSystemService(ConnectivityManager.class).unregisterNetworkCallback(networkCallback);
            networkCallbackRegistered = false;
        }
    }

    /**
     * Маршрут IPv6 в туннель нужен, только если он есть у настоящей сети. Иначе приложения ждут от каждого
     * адреса IPv6 отказа через туннель, прежде чем перейти на IPv4. Без адреса IPv6 в туннеле система сразу
     * отвечает, что такой сети нет, и утечки нет: VPN блокирует семейство адресов, которого у него нет.
     */
    private static boolean hasGlobalIpv6(ConnectivityManager manager, Network active) {
        LinkProperties properties = active == null ? null : manager.getLinkProperties(active);
        if (properties == null) {
            return false;
        }
        for (LinkAddress address : properties.getLinkAddresses()) {
            java.net.InetAddress ip = address.getAddress();
            // fc00::/7 ходит только внутри локальной сети, в интернет через него не выйти
            boolean uniqueLocal = ip instanceof Inet6Address && (ip.getAddress()[0] & 0xfe) == 0xfc;
            if (ip instanceof Inet6Address && !uniqueLocal && !ip.isLinkLocalAddress()
                    && !ip.isLoopbackAddress() && !ip.isSiteLocalAddress()) {
                return true;
            }
        }
        return false;
    }

    private void stop(String error) {
        main.removeCallbacksAndMessages(null);
        unregisterNetwork();
        releaseEngines();
        strategy = null;
        network = null;
        failoverFrom = null;
        failoverReason = null;
        failoverAttempts = 0;
        consecutiveProbeFailures = 0;
        quickRestarts = 0;
        AppState.setRouteNotice(null);
        AppState.set(AppState.Phase.OFF, null, error);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void releaseEngines() {
        enginePort = 0;
        closeTunnel();
        stopCiadpi();
        stopDns();
        File config = tunnelConfig();
        if (config.exists() && !config.delete()) {
            Log.w(TAG, "could not remove the private tunnel config");
        }
    }

    private void closeTunnel() {
        if (tun != null) {
            TunBridge.TProxyStopService();
            try {
                tun.close();
            } catch (IOException e) {
                Log.w(TAG, "tun close", e);
            }
            tun = null;
        }
        tunHasIpv6 = false;
        tunExcludedPackage = "";
        tunnelRouteFingerprint = null;
    }

    private void stopCiadpi() {
        Process process = ciadpi;
        ciadpi = null;
        if (process != null) {
            process.destroy();
            try {
                process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void startCiadpi(String name, String kind) throws IOException {
        stopCiadpi();
        // Keep the local endpoint stable across strategy changes so the TUN bridge does not needlessly restart.
        if (ciadpiPort == 0) {
            ciadpiPort = freePort();
        }
        Process process = new ProcessBuilder(Data.ciadpiCommand(this, ciadpiPort, dnsPort, Data.vpnArgs(this, kind, name)))
                .redirectErrorStream(true).start();
        ciadpi = process;
        StringBuffer output = new StringBuffer();
        watchCiadpi(process, output);
        waitForPort(process, output);
    }

    /** Если движок упал, VPN без него оставил бы телефон без интернета: запускаем заново, а при повторах выключаем всё. */
    private void watchCiadpi(Process process, StringBuffer output) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Log.i(TAG, "ciadpi: " + line);
                    if (output.length() < 2000) {
                        output.append(line).append('\n');
                    }
                }
            } catch (IOException ignored) {
                // поток закрылся вместе с процессом
            }
            int code = -1;
            try {
                code = process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            final int exit = code;
            try {
                worker.execute(() -> {
                    if (process == ciadpi) {
                        Log.e(TAG, "ciadpi exited: " + exit);
                        restartAfterFailure("Движок завершился с кодом " + exit + ". " + output.toString().trim());
                    }
                });
            } catch (RejectedExecutionException serviceAlreadyDestroyed) {
                Log.i(TAG, "ciadpi exited after the service");
            }
        }, "ciadpi-watch");
        thread.setDaemon(true);
        thread.start();
    }

    private void waitForPort(Process process, StringBuffer output) throws IOException {
        for (int attempt = 0; attempt < 100; attempt++) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", ciadpiPort), 100);
                return;
            } catch (IOException notYet) {
                if (!Data.isAlive(process)) {
                    throw new IOException("Движок не запустился. " + output.toString().trim());
                }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted");
                }
            }
        }
        throw new IOException("Движок не отвечает");
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private ParcelFileDescriptor establish(boolean ipv6, Socks5Settings.Endpoint endpoint) throws Exception {
        Builder builder = new Builder()
                .setSession(getString(R.string.app_name))
                .setMtu(TUN_MTU)
                .addAddress(TUN_IPV4, 32)
                .addRoute("0.0.0.0", 0);
        if (ipv6) {
            builder.addAddress(TUN_IPV6, 128).addRoute("::", 0);
        }
        builder.addDnsServer(TUN_DNS)
                .addDisallowedApplication(getPackageName());
        if (endpoint != null && !endpoint.excludedPackage.isEmpty()) {
            if (Socks5Settings.isInstalled(this, endpoint.excludedPackage)) {
                builder.addDisallowedApplication(endpoint.excludedPackage);
            } else {
                Log.w(TAG, "configured local SOCKS app is no longer installed; ignoring exclusion");
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false);
        }
        ParcelFileDescriptor descriptor = builder.establish();
        if (descriptor == null) {
            throw new IOException(getString(R.string.error_vpn_denied));
        }
        return descriptor;
    }

    private File tunnelConfig() {
        return new File(getCacheDir(), "tunnel.yml");
    }

    private void writeTunnelConfig(boolean ipv6, boolean proxyMode, Socks5Settings.Endpoint endpoint)
            throws IOException {
        if (proxyMode && !Socks5Settings.isConfigured(this)) {
            throw new IOException(getString(R.string.socks_settings_required));
        }
        String address = proxyMode ? endpoint.host : "127.0.0.1";
        int upstreamPort = proxyMode ? endpoint.port : ciadpiPort;
        String auth = proxyMode && endpoint.hasAuthentication()
                ? "  username: " + Socks5Settings.yamlQuote(endpoint.username) + "\n"
                    + "  password: " + Socks5Settings.yamlQuote(endpoint.password) + "\n"
                : "";
        String mapDns = proxyMode
                ? "mapdns:\n"
                    + "  address: " + TUN_DNS + "\n"
                    + "  port: 53\n"
                    + "  network: 100.64.0.0\n"
                    + "  netmask: 255.192.0.0\n"
                    + "  cache-size: 10000\n"
                : "";
        String config = "tunnel:\n"
                + "  mtu: " + TUN_MTU + "\n"
                + "  ipv4: " + TUN_IPV4 + "\n"
                + (ipv6 ? "  ipv6: '" + TUN_IPV6 + "'\n" : "")
                + "socks5:\n"
                + "  port: " + upstreamPort + "\n"
                + "  address: " + Socks5Settings.yamlQuote(address) + "\n"
                + "  udp: 'udp'\n"
                + auth
                + mapDns
                + "misc:\n"
                // Preserve long-lived push connections that can be idle for more than five minutes.
                + "  tcp-read-write-timeout: " + TCP_IDLE_TIMEOUT_MS + "\n"
                + "  log-level: error\n";
        try (FileOutputStream out = new FileOutputStream(tunnelConfig())) {
            out.write(config.getBytes(StandardCharsets.UTF_8));
        }
    }

    private void showFailoverNotification(String previous, String current, String reason) {
        String text = getString(R.string.failover_switched, previous, current);
        if (reason != null && !reason.isEmpty()) {
            text += " · " + reason;
        }
        notifyFailure(text);
    }

    private void showFailureNotification(String message) {
        notifyFailure(message);
    }

    private void notifyFailure(String message) {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            StatusChannels.ensure(this, StatusChannels.BYPASS, R.string.channel_name);
            builder = new Notification.Builder(this, StatusChannels.BYPASS);
        } else {
            builder = new Notification.Builder(this);
        }
        PendingIntent open = PendingIntent.getActivity(this, FAILURE_NOTIFICATION_ID,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = builder
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle(getString(R.string.failover_notice_title))
                .setContentText(message)
                .setStyle(new Notification.BigTextStyle().bigText(message))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build();
        getSystemService(NotificationManager.class).notify(FAILURE_NOTIFICATION_ID, notification);
    }

    private void showNotification(String name) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            StatusChannels.ensure(this, StatusChannels.BYPASS, R.string.channel_name);
            builder = new Notification.Builder(this, StatusChannels.BYPASS);
        } else {
            builder = new Notification.Builder(this);
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, BypassVpnService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        Notification notification = builder
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle(getString(R.string.status_on, name))
                .setContentText(getString(NetKind.CELL.equals(NetKind.current(this)) ? R.string.net_cell : R.string.net_wifi))
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, getString(R.string.notification_stop), stop).build())
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        // уведомление уже на новом канале: старый с низкой важностью можно убрать
        StatusChannels.dropOld(this, StatusChannels.BYPASS);
    }
}
