package app.zapret.aris;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** Small SOCKS5 RFC 1928/1929 TCP probe used by the settings screen. */
final class Socks5Probe {
    private static final int TIMEOUT_MS = 7000;
    private static final String TEST_HOST = "example.com";

    private Socks5Probe() {
    }

    static void check(Socks5Settings.Endpoint endpoint) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(endpoint.host, endpoint.port), TIMEOUT_MS);
            socket.setSoTimeout(TIMEOUT_MS);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            if (endpoint.hasAuthentication()) {
                out.write(new byte[]{5, 2, 0, 2});
            } else {
                out.write(new byte[]{5, 1, 0});
            }
            out.flush();
            int version = read(in);
            int method = read(in);
            if (version != 5 || method == 0xff) {
                throw new IOException("сервер не согласовал SOCKS5-аутентификацию");
            }
            if (method == 2) {
                if (!endpoint.hasAuthentication()) {
                    throw new IOException("сервер требует логин и пароль");
                }
                byte[] username = endpoint.username.getBytes(StandardCharsets.UTF_8);
                byte[] password = endpoint.password.getBytes(StandardCharsets.UTF_8);
                out.write(1);
                out.write(username.length);
                out.write(username);
                out.write(password.length);
                out.write(password);
                out.flush();
                int authVersion = read(in);
                int authStatus = read(in);
                if (authVersion != 1 || authStatus != 0) {
                    throw new IOException("SOCKS5 отклонил логин или пароль");
                }
            } else if (method != 0) {
                throw new IOException("сервер выбрал неподдерживаемый метод SOCKS5");
            }

            byte[] host = TEST_HOST.getBytes(StandardCharsets.US_ASCII);
            out.write(new byte[]{5, 1, 0, 3, (byte) host.length});
            out.write(host);
            out.write(new byte[]{1, (byte) 187}); // example.com:443
            out.flush();

            int responseVersion = read(in);
            int status = read(in);
            int reserved = read(in);
            int addressType = read(in);
            if (responseVersion != 5 || reserved != 0) {
                throw new IOException("неверный ответ SOCKS5");
            }
            if (status != 0) {
                throw new IOException("SOCKS5 не открыл example.com:443 (код " + status + ")");
            }
            switch (addressType) {
                case 1:
                    skip(in, 4);
                    break;
                case 3:
                    skip(in, read(in));
                    break;
                case 4:
                    skip(in, 16);
                    break;
                default:
                    throw new IOException("неверный адрес в ответе SOCKS5");
            }
            skip(in, 2);
        }
    }

    private static int read(InputStream in) throws IOException {
        int value = in.read();
        if (value < 0) {
            throw new IOException("сервер закрыл соединение во время проверки");
        }
        return value;
    }

    private static void skip(InputStream in, int count) throws IOException {
        while (count > 0) {
            long skipped = in.skip(count);
            if (skipped > 0) {
                count -= (int) skipped;
            } else {
                read(in);
                count--;
            }
        }
    }
}
