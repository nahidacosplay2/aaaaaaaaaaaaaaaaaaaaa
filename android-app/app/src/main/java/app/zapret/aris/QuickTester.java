package app.zapret.aris;

import android.content.Context;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** A short comparison of the selected strategy with the ordinary, unproxied route. */
final class QuickTester {
    private static final String[] TARGETS = {"www.youtube.com", "discord.com", "www.cloudflare.com"};

    interface Progress {
        void update(String message);
    }

    static final class Result {
        final String strategy;
        final List<String> targets;
        final List<Boolean> viaStrategy;
        final List<Boolean> direct;

        Result(String strategy, List<String> targets, List<Boolean> viaStrategy, List<Boolean> direct) {
            this.strategy = strategy;
            this.targets = targets;
            this.viaStrategy = viaStrategy;
            this.direct = direct;
        }

        int strategyCount() {
            return count(viaStrategy);
        }

        int directCount() {
            return count(direct);
        }

        private static int count(List<Boolean> values) {
            int count = 0;
            for (Boolean value : values) {
                if (Boolean.TRUE.equals(value)) {
                    count++;
                }
            }
            return count;
        }
    }

    private final Context context;
    private final AtomicBoolean cancelled = new AtomicBoolean();

    QuickTester(Context context) {
        this.context = context.getApplicationContext();
    }

    void cancel() {
        cancelled.set(true);
    }

    boolean isCancelled() {
        return cancelled.get();
    }

    Result run(String kind, String strategy, int activePort, Progress progress)
            throws IOException, InterruptedException {
        Data.extract(context);
        Data.applyLists(context);
        checkCancelled();
        progress.update("Запускаем выбранную стратегию и проверяем DNS…");

        ConfigTester.Engine engine = null;
        int socksPort = activePort;
        boolean usingActiveEngine = activePort > 0 && AppState.phase == AppState.Phase.ON
                && !ConnectionMode.isSocks5(context) && strategy.equals(AppState.strategy);
        try {
            if (!usingActiveEngine) {
                engine = ConfigTester.Engine.start(context, Data.vpnArgs(context, kind, strategy));
                if (engine == null) {
                    throw new IOException("Движок не запустился с выбранной стратегией " + strategy);
                }
                socksPort = engine.port;
            }
            progress.update("Проверяем HTTPS через стратегию и напрямую…");
            List<InetAddress> addresses = resolveTargets();
            checkCancelled();
            return measure(strategy, addresses, socksPort);
        } finally {
            if (engine != null) {
                engine.stop();
            }
        }
    }

    private List<InetAddress> resolveTargets() throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(TARGETS.length, runnable -> {
            Thread thread = new Thread(runnable, "quick-test-dns");
            thread.setDaemon(true);
            return thread;
        });
        try {
            List<java.util.concurrent.Callable<InetAddress>> lookups = new ArrayList<>();
            for (String host : TARGETS) {
                lookups.add(() -> {
                    try {
                        InetAddress[] all = InetAddress.getAllByName(host);
                        InetAddress chosen = all.length == 0 ? null : all[0];
                        for (InetAddress candidate : all) {
                            if (candidate instanceof Inet4Address) {
                                return candidate;
                            }
                        }
                        return chosen;
                    } catch (IOException e) {
                        return null;
                    }
                });
            }
            List<Future<InetAddress>> outcomes = pool.invokeAll(lookups, 5, TimeUnit.SECONDS);
            List<InetAddress> addresses = new ArrayList<>();
            for (Future<InetAddress> outcome : outcomes) {
                checkCancelled();
                if (outcome.isCancelled()) {
                    addresses.add(null);
                    continue;
                }
                try {
                    addresses.add(outcome.get());
                } catch (ExecutionException ignored) {
                    addresses.add(null);
                }
            }
            return addresses;
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(1, TimeUnit.SECONDS);
        }
    }

    private Result measure(String strategy, List<InetAddress> addresses, int strategyPort)
            throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(TARGETS.length * 2);
        List<java.util.concurrent.Callable<Boolean>> checks = new ArrayList<>();
        try {
            for (int i = 0; i < TARGETS.length; i++) {
                final String host = TARGETS[i];
                final InetAddress address = addresses.get(i);
                checks.add(() -> !cancelled.get() && address != null
                        && ConfigTester.opens(host, address, strategyPort));
                checks.add(() -> !cancelled.get() && address != null
                        && ConfigTester.opens(host, address, 0));
            }
            List<Future<Boolean>> outcomes = pool.invokeAll(checks, 12, TimeUnit.SECONDS);
            List<Boolean> viaResults = new ArrayList<>();
            List<Boolean> directResults = new ArrayList<>();
            for (int i = 0; i < outcomes.size(); i++) {
                boolean succeeded = false;
                Future<Boolean> outcome = outcomes.get(i);
                if (!outcome.isCancelled()) {
                    try {
                        succeeded = Boolean.TRUE.equals(outcome.get());
                    } catch (ExecutionException ignored) {
                        // A site timeout is shown as a failed sample.
                    }
                }
                (i % 2 == 0 ? viaResults : directResults).add(succeeded);
            }
            checkCancelled();
            return new Result(strategy, new ArrayList<>(Arrays.asList(TARGETS)), viaResults, directResults);
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(1, TimeUnit.SECONDS);
        }
    }

    private void checkCancelled() throws InterruptedException {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("cancelled");
        }
    }
}
