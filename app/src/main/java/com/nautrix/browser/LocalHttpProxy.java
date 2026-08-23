package com.nautrix.browser;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Loopback CONNECT proxy. TLS stays end-to-end between WebView and the destination site. */
final class LocalHttpProxy {
    private static final int MAX_HEADER_BYTES = 32 * 1_024;
    private final AutoDnsManager dnsManager;
    private final ExecutorService connections = Executors.newCachedThreadPool();
    private volatile ServerSocket server;

    LocalHttpProxy(AutoDnsManager dnsManager) {
        this.dnsManager = dnsManager;
    }

    synchronized int start() throws Exception {
        if (server != null && !server.isClosed()) return server.getLocalPort();
        ServerSocket created = new ServerSocket();
        created.setReuseAddress(true);
        created.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
        server = created;
        Thread acceptor = new Thread(this::acceptLoop, "nautrix-dns-proxy");
        acceptor.setDaemon(true);
        acceptor.start();
        return created.getLocalPort();
    }

    private void acceptLoop() {
        while (server != null && !server.isClosed()) {
            try {
                Socket client = server.accept();
                client.setTcpNoDelay(true);
                connections.execute(() -> handle(client));
            } catch (Exception error) {
                if (server == null || server.isClosed()) return;
            }
        }
    }

    private void handle(Socket client) {
        Socket upstream = null;
        try {
            client.setSoTimeout(15_000);
            String header = readHeader(client.getInputStream());
            String firstLine = header.substring(0, header.indexOf("\r\n"));
            String[] request = firstLine.split(" ", 3);
            if (request.length < 2 || !"CONNECT".equalsIgnoreCase(request[0])) {
                writeResponse(client, "HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n");
                return;
            }
            HostPort target = parseAuthority(request[1]);
            List<InetAddress> addresses = dnsManager.resolveAll(target.host);
            Exception lastError = null;
            for (InetAddress address : addresses) {
                try {
                    Socket attempt = new Socket();
                    attempt.connect(new InetSocketAddress(address, target.port), 10_000);
                    attempt.setTcpNoDelay(true);
                    attempt.setSoTimeout(0);
                    upstream = attempt;
                    break;
                } catch (Exception error) {
                    lastError = error;
                }
            }
            if (upstream == null) throw lastError == null ? new java.io.IOException("connect") : lastError;
            client.setSoTimeout(0);
            writeResponse(client, "HTTP/1.1 200 Connection Established\r\n"
                    + "Proxy-Agent: Nautrix\r\n\r\n");
            Socket tunnel = upstream;
            connections.execute(() -> {
                try {
                    copy(client.getInputStream(), tunnel.getOutputStream());
                } catch (Exception ignored) {
                } finally {
                    closeQuietly(tunnel);
                }
            });
            copy(tunnel.getInputStream(), client.getOutputStream());
        } catch (Exception error) {
            try {
                writeResponse(client, "HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n");
            } catch (Exception ignored) {
            }
        } finally {
            closeQuietly(upstream);
            closeQuietly(client);
        }
    }

    private static String readHeader(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int state = 0;
        while (output.size() < MAX_HEADER_BYTES) {
            int value = input.read();
            if (value < 0) throw new java.io.IOException("closed proxy request");
            output.write(value);
            if ((state == 0 || state == 2) && value == '\r') state++;
            else if ((state == 1 || state == 3) && value == '\n') state++;
            else state = value == '\r' ? 1 : 0;
            if (state == 4) return output.toString(StandardCharsets.ISO_8859_1.name());
        }
        throw new java.io.IOException("proxy header too large");
    }

    private static HostPort parseAuthority(String authority) {
        String host;
        int port = 443;
        if (authority.startsWith("[")) {
            int end = authority.indexOf(']');
            if (end < 0) throw new IllegalArgumentException("IPv6 authority");
            host = authority.substring(1, end);
            if (end + 2 < authority.length()) port = Integer.parseInt(authority.substring(end + 2));
        } else {
            int colon = authority.lastIndexOf(':');
            if (colon > 0 && authority.indexOf(':') == colon) {
                host = authority.substring(0, colon);
                port = Integer.parseInt(authority.substring(colon + 1));
            } else {
                host = authority;
            }
        }
        if (host.isEmpty() || port < 1 || port > 65_535) throw new IllegalArgumentException("target");
        return new HostPort(host, port);
    }

    private static void copy(InputStream input, OutputStream output) throws Exception {
        byte[] buffer = new byte[32 * 1_024];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            if (count == 0) continue;
            output.write(buffer, 0, count);
            output.flush();
        }
    }

    private static void writeResponse(Socket socket, String value) throws Exception {
        socket.getOutputStream().write(value.getBytes(StandardCharsets.ISO_8859_1));
        socket.getOutputStream().flush();
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) return;
        try {
            socket.close();
        } catch (Exception ignored) {
        }
    }

    private static final class HostPort {
        final String host;
        final int port;

        HostPort(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }
}
