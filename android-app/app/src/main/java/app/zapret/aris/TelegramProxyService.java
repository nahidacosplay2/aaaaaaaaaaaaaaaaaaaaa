package app.zapret.aris;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import org.zapretcap.tgproxy.ProxyConfig;
import org.zapretcap.tgproxy.ProxyLog;
import org.zapretcap.tgproxy.TgWsProxy;

import java.io.IOException;
import java.net.BindException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** Держит прокси Telegram запущенным, пока он включён, независимо от обхода. */
public class TelegramProxyService extends Service {
    private static final String TAG = "ZapretArisTg";
    static final String ACTION_START = "app.zapret.aris.TG_START";
    static final String ACTION_RESTART = "app.zapret.aris.TG_RESTART";
    static final String ACTION_STOP = "app.zapret.aris.TG_STOP";
    private static final int NOTIFICATION_ID = 2;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile TgWsProxy proxy;
    private final TgEngine engine = new TgEngine();
    private Network lastNetwork;
    private final Runnable networkSettled = this::onNetworkSettled;

    /** Соединения привязаны к сети, поэтому при её смене нужно дождаться перехода Wi-Fi ↔ мобильная. */
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(Network network) {
            onNetworkEvent();
        }

        @Override
        public void onCapabilitiesChanged(Network network, android.net.NetworkCapabilities capabilities) {
            onNetworkEvent();
        }

        @Override
        public void onLinkPropertiesChanged(Network network, android.net.LinkProperties properties) {
            onNetworkEvent();
        }

        @Override
        public void onLost(Network network) {
            onNetworkEvent();
        }
    };

    private void onNetworkEvent() {
        main.removeCallbacks(networkSettled);
        main.postDelayed(networkSettled, 1500);
    }

    private void onNetworkSettled() {
        Network physical = NetKind.physical(this);
        if (physical == null || (lastNetwork != null && lastNetwork.equals(physical))) {
            return;
        }
        boolean changed = lastNetwork != null;
        lastNetwork = physical;
        if (!changed) {
            return;
        }
        TgWsProxy current = proxy;
        if (current != null) {
            current.networkChanged();
            // Соединения движка привязаны к старой сети, TTL и стратегия — к новой.
            try {
                worker.execute(() -> {
                    if (proxy != null) {
                        engine.start(TelegramProxyService.this);
                    }
                });
            } catch (RejectedExecutionException stopped) {
                Log.i(TAG, "network changed while the proxy service was stopping");
            }
        }
    }

    static void start(Context context) {
        launch(context, ACTION_START);
    }

    /** Применяет новые настройки. Обрывает все соединения, поэтому только для этого случая. */
    static void restart(Context context) {
        launch(context, ACTION_RESTART);
    }

    private static void launch(Context context, String action) {
        Intent intent = new Intent(context, TelegramProxyService.class).setAction(action);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    static void stop(Context context) {
        context.startService(new Intent(context, TelegramProxyService.class).setAction(ACTION_STOP));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        CrashLog.install(this);
        getSystemService(ConnectivityManager.class).registerDefaultNetworkCallback(networkCallback);
        ProxyLog.setSink((level, message) -> Log.println(
                "W".equals(level) ? Log.WARN : "D".equals(level) ? Log.DEBUG : Log.INFO, TAG, message));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            TgProxySettings.prefs(this).edit().putBoolean(TgProxySettings.ENABLED, false).apply();
            worker.execute(() -> {
                stopProxy();
                AppState.setTg(AppState.Phase.OFF, null, null);
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            });
            return START_NOT_STICKY;
        }
        // без intent служба поднята системой после сбоя: прокси должен работать, иначе Telegram останется без связи
        TgProxySettings.prefs(this).edit().putBoolean(TgProxySettings.ENABLED, true).apply();
        showNotification();
        boolean restart = intent != null && ACTION_RESTART.equals(intent.getAction());
        worker.execute(() -> startProxy(restart));
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        getSystemService(ConnectivityManager.class).unregisterNetworkCallback(networkCallback);
        worker.execute(this::stopProxy);
        worker.shutdown();
        super.onDestroy();
    }

    private void startProxy(boolean restart) {
        // повторная команда запуска не должна рвать работающие соединения Telegram
        if (proxy != null && !restart) {
            return;
        }
        stopProxy();
        AppState.setTg(AppState.Phase.STARTING, null, null);
        ProxyConfig config = TgProxySettings.load(this);
        TgWsProxy next = new TgWsProxy(config);
        try {
            engine.start(this);
            next.start();
            proxy = next;
            AppState.setTg(AppState.Phase.ON, next, TgEngine.notice());
        } catch (BindException e) {
            next.stop();
            fail(getString(R.string.tg_error_port, config.port));
        } catch (IOException | RuntimeException e) {
            next.stop();
            fail(String.valueOf(e.getMessage()));
        }
    }

    private void fail(String message) {
        Log.e(TAG, message);
        TgProxySettings.prefs(this).edit().putBoolean(TgProxySettings.ENABLED, false).apply();
        AppState.setTg(AppState.Phase.OFF, null, message);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void stopProxy() {
        engine.stop();
        if (proxy != null) {
            proxy.stop();
            proxy = null;
        }
    }

    private void showNotification() {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            StatusChannels.ensure(this, StatusChannels.TELEGRAM, R.string.tg_channel_name);
            builder = new Notification.Builder(this, StatusChannels.TELEGRAM);
        } else {
            builder = new Notification.Builder(this);
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, TelegramProxyService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        Notification notification = builder
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle(getString(R.string.tg_notification))
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
        StatusChannels.dropOld(this, StatusChannels.TELEGRAM);
    }
}
