package app.zapret.aris;

import android.net.Network;

import org.zapretcap.tgproxy.Dialer;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLSocket;

/** A small, read-only TLS check through the selected local strategy. */
final class StrategyHealthProbe {
    private static final String[] TARGETS = {
            "www.microsoft.com", "www.cloudflare.com", "www.youtube.com"
    };
    private static final int CONNECT_TIMEOUT_MS = 3500;
    private static final int READ_TIMEOUT_MS = 2500;
    private static final long TOTAL_TIMEOUT_MS = 7000;

    private StrategyHealthProbe() {
    }

    /** At least two independent HTTPS endpoints must answer to avoid switching on a single site outage. */
    static int successes(Network network, int socksPort) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(TARGETS.length, runnable -> {
            Thread thread = new Thread(runnable, "strategy-health-probe");
            thread.setDaemon(true);
            return thread;
        });
        try {
            List<Callable<Boolean>> checks = new ArrayList<>();
            for (String host : TARGETS) {
                checks.add(() -> reachable(network, socksPort, host));
            }
            List<Future<Boolean>> results = pool.invokeAll(checks, TOTAL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            int successes = 0;
            for (Future<Boolean> result : results) {
                if (!result.isCancelled()) {
                    try {
                        if (Boolean.TRUE.equals(result.get())) {
                            successes++;
                        }
                    } catch (ExecutionException | CancellationException ignored) {
                        // an endpoint timeout is one negative sample, not a service failure
                    }
                }
            }
            return successes;
        } finally {
            pool.shutdownNow();
        }
    }

    private static boolean reachable(Network network, int socksPort, String host) {
        Socket plain = null;
        Socket secure = null;
        try {
            InetAddress[] addresses = network != null ? network.getAllByName(host) : InetAddress.getAllByName(host);
            List<InetAddress> ordered = new ArrayList<>(Arrays.asList(addresses));
            Collections.sort(ordered, (a, b) -> Boolean.compare(!(a instanceof Inet4Address), !(b instanceof Inet4Address)));
            IOException last = null;
            for (int i = 0; i < Math.min(2, ordered.size()); i++) {
                try {
                    plain = Dialer.tcp(ordered.get(i).getHostAddress(), 443, CONNECT_TIMEOUT_MS, socksPort);
                    SSLSocket tls = Dialer.tls(plain, host, 443, true, CONNECT_TIMEOUT_MS);
                    secure = tls;
                    tls.setSoTimeout(READ_TIMEOUT_MS);
                    OutputStream out = tls.getOutputStream();
                    out.write(("GET /generate_204 HTTP/1.1\r\nHost: " + host
                            + "\r\nUser-Agent: ZapretAris-Health-Check\r\nConnection: close\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    BufferedInputStream in = new BufferedInputStream(tls.getInputStream());
                    StringBuilder status = new StringBuilder();
                    int value;
                    while ((value = in.read()) >= 0 && value != '\n' && status.length() < 256) {
                        if (value != '\r') {
                            status.append((char) value);
                        }
                    }
                    return status.toString().startsWith("HTTP/");
                } catch (IOException e) {
                    last = e;
                    Dialer.closeQuietly(secure);
                    Dialer.closeQuietly(plain);
                    secure = null;
                    plain = null;
                }
            }
            return false;
        } catch (IOException | RuntimeException e) {
            return false;
        } finally {
            Dialer.closeQuietly(secure);
            Dialer.closeQuietly(plain);
        }
    }
}
