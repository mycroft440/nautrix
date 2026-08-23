package com.nautrix.browser;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Handler;
import android.os.Looper;

import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewFeature;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/** Benchmarks public DNS resolvers and supplies the winner to WebView and media traffic. */
public final class AutoDnsManager {
    private static final long BENCHMARK_VALID_MS = 6L * 60L * 60L * 1_000L;
    private static final long DNS_CACHE_MS = 60_000L;
    private static final int DNS_TIMEOUT_MS = 900;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static AutoDnsManager instance;

    private static final Candidate[] CANDIDATES = {
            new Candidate("Cloudflare", "1.1.1.1"),
            new Candidate("Cloudflare 2", "1.0.0.1"),
            new Candidate("Google", "8.8.8.8"),
            new Candidate("Google 2", "8.8.4.4"),
            new Candidate("Quad9", "9.9.9.9"),
            new Candidate("Quad9 2", "149.112.112.112"),
            new Candidate("AdGuard", "94.140.14.14"),
            new Candidate("AdGuard 2", "94.140.15.15"),
            new Candidate("OpenDNS", "208.67.222.222"),
            new Candidate("OpenDNS 2", "208.67.220.220"),
            new Candidate("Control D", "76.76.2.0"),
            new Candidate("Control D 2", "76.76.10.0"),
            new Candidate("CleanBrowsing", "185.228.168.9"),
            new Candidate("CleanBrowsing 2", "185.228.169.9"),
            new Candidate("Mullvad", "194.242.2.2"),
            new Candidate("DNS0", "193.110.81.0"),
            new Candidate("DNS0 2", "185.253.5.0"),
            new Candidate("Verisign", "64.6.64.6"),
            new Candidate("Verisign 2", "64.6.65.6"),
            new Candidate("Hurricane Electric", "74.82.42.42")
    };

    private final Context context;
    private final SharedPreferences preferences;
    private final ExecutorService workers = Executors.newFixedThreadPool(8);
    private final ExecutorService coordinator = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean benchmarking = new AtomicBoolean();
    private final Map<String, CachedAddresses> addressCache = new ConcurrentHashMap<>();
    private final LocalHttpProxy localProxy;
    private volatile Candidate selected;
    private volatile int selectedLatencyMs = -1;
    private volatile int selectedSuccesses;
    private volatile boolean externalDnsAvailable;
    private volatile String state = "Preparando teste automático…";

    public static synchronized AutoDnsManager get(Context context) {
        if (instance == null) instance = new AutoDnsManager(context.getApplicationContext());
        return instance;
    }

    private AutoDnsManager(Context context) {
        this.context = context;
        preferences = context.getSharedPreferences("nautrix_dns", Context.MODE_PRIVATE);
        String savedIp = preferences.getString("selected_ip", CANDIDATES[0].ip);
        selected = findCandidate(savedIp);
        if (selected == null) selected = CANDIDATES[0];
        selectedLatencyMs = preferences.getInt("selected_ms", -1);
        selectedSuccesses = preferences.getInt("selected_successes", 0);
        externalDnsAvailable = selectedLatencyMs >= 0;
        state = selectedLatencyMs >= 0 ? statusForSelected() : "DNS inicial: " + selected.name;
        localProxy = new LocalHttpProxy(this);
        registerNetworkChanges();
    }

    public void installWebViewProxy(Runnable ready) {
        Runnable completion = ready == null ? () -> { } : ready;
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            state = "WebView sem suporte ao DNS automático; usando DNS do sistema";
            mainHandler.post(completion);
            return;
        }
        try {
            int port = localProxy.start();
            ProxyConfig config = new ProxyConfig.Builder()
                    .addProxyRule("127.0.0.1:" + port)
                    .build();
            ProxyController.getInstance().setProxyOverride(
                    config, command -> mainHandler.post(command), completion);
        } catch (Exception error) {
            state = "Proxy DNS indisponível; usando DNS do sistema";
            mainHandler.post(completion);
        }
    }

    public void benchmarkAsync(boolean force, Runnable completion) {
        long last = preferences.getLong("last_benchmark", 0L);
        if (!force && System.currentTimeMillis() - last < BENCHMARK_VALID_MS) {
            if (completion != null) mainHandler.post(completion);
            return;
        }
        if (!benchmarking.compareAndSet(false, true)) return;
        state = "Testando 20 servidores DNS…";
        coordinator.execute(() -> {
            try {
                List<Future<Benchmark>> futures = new ArrayList<>();
                for (Candidate candidate : CANDIDATES) {
                    futures.add(workers.submit(() -> benchmark(candidate)));
                }
                Benchmark winner = null;
                for (Future<Benchmark> future : futures) {
                    try {
                        Benchmark result = future.get();
                        if (!DnsScorePolicy.isStable(result.successes)) continue;
                        if (winner == null || result.score < winner.score) winner = result;
                    } catch (Exception ignored) {
                    }
                }
                if (winner != null) {
                    selected = winner.candidate;
                    selectedLatencyMs = winner.averageMs;
                    selectedSuccesses = winner.successes;
                    externalDnsAvailable = true;
                    addressCache.clear();
                    preferences.edit()
                            .putString("selected_ip", selected.ip)
                            .putInt("selected_ms", selectedLatencyMs)
                            .putInt("selected_successes", selectedSuccesses)
                            .putLong("last_benchmark", System.currentTimeMillis())
                            .apply();
                    state = statusForSelected();
                } else {
                    externalDnsAvailable = false;
                    state = "DNS externos bloqueados; fallback automático para o sistema";
                }
            } finally {
                benchmarking.set(false);
                if (completion != null) mainHandler.post(completion);
            }
        });
    }

    public String status() {
        return state;
    }

    public List<InetAddress> resolveAll(String hostname) throws java.net.UnknownHostException {
        if (hostname == null || hostname.trim().isEmpty()) {
            throw new java.net.UnknownHostException("empty hostname");
        }
        if (hostname.indexOf(':') >= 0 || hostname.matches("[0-9.]+")) {
            return Collections.singletonList(InetAddress.getByName(hostname));
        }
        CachedAddresses cached = addressCache.get(hostname);
        if (cached != null && cached.expiresAt > System.currentTimeMillis()) return cached.addresses;
        List<InetAddress> addresses = new ArrayList<>();
        Candidate resolver = selected;
        if (resolver != null && externalDnsAvailable) {
            try {
                addresses.addAll(query(resolver.ip, hostname, 1, DNS_TIMEOUT_MS));
                addresses.addAll(query(resolver.ip, hostname, 28, DNS_TIMEOUT_MS));
            } catch (Exception ignored) {
            }
        }
        if (addresses.isEmpty()) addresses.addAll(Arrays.asList(InetAddress.getAllByName(hostname)));
        List<InetAddress> immutable = Collections.unmodifiableList(new ArrayList<>(addresses));
        addressCache.put(hostname, new CachedAddresses(immutable,
                System.currentTimeMillis() + DNS_CACHE_MS));
        return immutable;
    }

    private Benchmark benchmark(Candidate candidate) {
        long total = 0L;
        long minimum = Long.MAX_VALUE;
        long maximum = 0L;
        int successes = 0;
        for (int attempt = 0; attempt < 3; attempt++) {
            long started = System.nanoTime();
            try {
                List<InetAddress> answer = query(candidate.ip, "example.com", 1, DNS_TIMEOUT_MS);
                if (answer.isEmpty()) continue;
                long elapsed = Math.max(1L, (System.nanoTime() - started) / 1_000_000L);
                total += elapsed;
                minimum = Math.min(minimum, elapsed);
                maximum = Math.max(maximum, elapsed);
                successes++;
            } catch (Exception ignored) {
            }
        }
        int average = successes == 0 ? Integer.MAX_VALUE : (int) (total / successes);
        long score = DnsScorePolicy.score(average, minimum, maximum, successes, DNS_TIMEOUT_MS);
        return new Benchmark(candidate, successes, average, score);
    }

    private static List<InetAddress> query(String resolverIp, String hostname, int type,
                                           int timeoutMs) throws Exception {
        int id = RANDOM.nextInt(65_536);
        byte[] request = buildQuery(id, hostname, type);
        byte[] buffer = new byte[2_048];
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(timeoutMs);
            InetAddress resolver = InetAddress.getByName(resolverIp);
            socket.send(new DatagramPacket(request, request.length, resolver, 53));
            DatagramPacket response = new DatagramPacket(buffer, buffer.length);
            socket.receive(response);
            return parseResponse(Arrays.copyOf(buffer, response.getLength()), id, type);
        }
    }

    private static byte[] buildQuery(int id, String hostname, int type) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write((id >>> 8) & 0xff);
        output.write(id & 0xff);
        output.write(0x01);
        output.write(0x00);
        output.write(0x00);
        output.write(0x01);
        output.write(new byte[6]);
        for (String label : hostname.split("\\.")) {
            byte[] encoded = label.getBytes(StandardCharsets.US_ASCII);
            if (encoded.length == 0 || encoded.length > 63) throw new IllegalArgumentException("host");
            output.write(encoded.length);
            output.write(encoded);
        }
        output.write(0);
        output.write((type >>> 8) & 0xff);
        output.write(type & 0xff);
        output.write(0);
        output.write(1);
        return output.toByteArray();
    }

    private static List<InetAddress> parseResponse(byte[] data, int expectedId, int type)
            throws Exception {
        if (data.length < 12 || readU16(data, 0) != expectedId || (data[3] & 0x0f) != 0) {
            return Collections.emptyList();
        }
        int questions = readU16(data, 4);
        int answers = readU16(data, 6);
        int offset = 12;
        for (int i = 0; i < questions; i++) {
            offset = skipName(data, offset) + 4;
            if (offset > data.length) return Collections.emptyList();
        }
        List<InetAddress> result = new ArrayList<>();
        for (int i = 0; i < answers && offset < data.length; i++) {
            offset = skipName(data, offset);
            if (offset + 10 > data.length) break;
            int answerType = readU16(data, offset);
            int answerClass = readU16(data, offset + 2);
            int length = readU16(data, offset + 8);
            offset += 10;
            if (offset + length > data.length) break;
            if (answerType == type && answerClass == 1
                    && ((type == 1 && length == 4) || (type == 28 && length == 16))) {
                result.add(InetAddress.getByAddress(Arrays.copyOfRange(data, offset, offset + length)));
            }
            offset += length;
        }
        return result;
    }

    private static int skipName(byte[] data, int offset) {
        while (offset < data.length) {
            int length = data[offset] & 0xff;
            if ((length & 0xc0) == 0xc0) return Math.min(data.length, offset + 2);
            offset++;
            if (length == 0) return offset;
            if (length > 63 || offset + length > data.length) return data.length;
            offset += length;
        }
        return data.length;
    }

    private static int readU16(byte[] data, int offset) {
        return ((data[offset] & 0xff) << 8) | (data[offset + 1] & 0xff);
    }

    private String statusForSelected() {
        return "Automático: " + selected.name + " (" + selected.ip + ") • "
                + selectedLatencyMs + " ms • estabilidade " + selectedSuccesses + "/3";
    }

    private static Candidate findCandidate(String ip) {
        for (Candidate candidate : CANDIDATES) if (candidate.ip.equals(ip)) return candidate;
        return null;
    }

    private void registerNetworkChanges() {
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(
                Context.CONNECTIVITY_SERVICE);
        if (manager == null) return;
        try {
            manager.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    benchmarkAsync(true, null);
                }
            });
        } catch (Exception ignored) {
        }
    }

    private static final class Candidate {
        final String name;
        final String ip;

        Candidate(String name, String ip) {
            this.name = name;
            this.ip = ip;
        }
    }

    private static final class Benchmark {
        final Candidate candidate;
        final int successes;
        final int averageMs;
        final long score;

        Benchmark(Candidate candidate, int successes, int averageMs, long score) {
            this.candidate = candidate;
            this.successes = successes;
            this.averageMs = averageMs;
            this.score = score;
        }
    }

    private static final class CachedAddresses {
        final List<InetAddress> addresses;
        final long expiresAt;

        CachedAddresses(List<InetAddress> addresses, long expiresAt) {
            this.addresses = addresses;
            this.expiresAt = expiresAt;
        }
    }
}
