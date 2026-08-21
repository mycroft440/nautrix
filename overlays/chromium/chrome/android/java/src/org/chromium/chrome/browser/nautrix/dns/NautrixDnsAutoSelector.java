package org.chromium.chrome.browser.nautrix.dns;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.content.SharedPreferences;
import android.os.SystemClock;

import org.chromium.chrome.browser.nautrix.policy.DnsScorePolicy;
import org.chromium.chrome.browser.nautrix.policy.NautrixConstants;
import org.chromium.chrome.browser.privacy.secure_dns.NautrixSecureDnsApi;

import java.io.OutputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.HttpsURLConnection;

/** Benchmarks actual DoH queries and switches only when a candidate is materially better. */
public final class NautrixDnsAutoSelector {
    public static final String MODE_SYSTEM = "system";
    public static final String MODE_AUTOMATIC = "automatic";
    public static final String MODE_SECURE_CUSTOM = "secure_custom";
    private static final String PREFS = "nautrix_dns";
    private static final String KEY_MODE = "mode";
    private static final String KEY_CUSTOM = "custom_template";

    public static final class Provider {
        public final String id, endpoint, template;
        Provider(String id, String endpoint, String template) {
            this.id = id; this.endpoint = endpoint; this.template = template;
        }
    }

    private static final List<Provider> PROVIDERS = Arrays.asList(
            new Provider("cloudflare", "https://cloudflare-dns.com/dns-query", "https://chrome.cloudflare-dns.com/dns-query"),
            new Provider("google", "https://dns.google/dns-query", "https://dns.google/dns-query{?dns}"),
            new Provider("quad9", "https://dns.quad9.net/dns-query", "https://dns.quad9.net/dns-query"),
            new Provider("adguard", "https://dns.adguard-dns.com/dns-query", "https://dns.adguard-dns.com/dns-query"));

    private final ConnectivityManager connectivity;
    private final SharedPreferences prefs;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private final ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network network) { if (isAutomatic()) scheduleBenchmark(); }
        @Override public void onLost(Network network) { if (isAutomatic()) scheduleBenchmark(); }
    };
    private volatile Provider current = PROVIDERS.get(0);
    private volatile boolean started;

    public NautrixDnsAutoSelector(Context context) {
        connectivity = context.getSystemService(ConnectivityManager.class);
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!prefs.contains(KEY_MODE)) {
            String existing = NautrixSecureDnsApi.currentConfig();
            String initial = (NautrixSecureDnsApi.currentMode() == org.chromium.net.SecureDnsMode.SECURE
                    && existing != null && !existing.isEmpty())
                    ? MODE_SECURE_CUSTOM : MODE_AUTOMATIC;
            prefs.edit().putString(KEY_MODE, initial).apply();
        }
    }

    public void start() {
        if (started) return;
        started = true;
        applyConfiguredMode();
    }

    public void setModeSystem() {
        prefs.edit().putString(KEY_MODE, MODE_SYSTEM).remove(KEY_CUSTOM).apply();
        NautrixSecureDnsApi.applySystemResolver();
    }

    public void setModeAutomatic() {
        prefs.edit().putString(KEY_MODE, MODE_AUTOMATIC).remove(KEY_CUSTOM).apply();
        ensureNetworkCallback();
        scheduleBenchmark();
    }

    public boolean setModeSecureCustom(String template) {
        if (template == null || template.isBlank() || !NautrixSecureDnsApi.applySecureConfig(template)) return false;
        prefs.edit().putString(KEY_MODE, MODE_SECURE_CUSTOM).putString(KEY_CUSTOM, template).apply();
        return true;
    }

    public String mode() { return prefs.getString(KEY_MODE, MODE_AUTOMATIC); }

    private boolean isAutomatic() { return MODE_AUTOMATIC.equals(mode()); }

    private void applyConfiguredMode() {
        String mode = mode();
        if (MODE_SYSTEM.equals(mode)) {
            NautrixSecureDnsApi.applySystemResolver();
        } else if (MODE_SECURE_CUSTOM.equals(mode)) {
            String custom = prefs.getString(KEY_CUSTOM, NautrixSecureDnsApi.currentConfig());
            if (custom != null && !custom.isBlank()) NautrixSecureDnsApi.applySecureConfig(custom);
        } else {
            ensureNetworkCallback();
            scheduleBenchmark();
        }
    }

    private void ensureNetworkCallback() {
        if (connectivity == null) return;
        try { connectivity.registerDefaultNetworkCallback(callback); }
        catch (IllegalArgumentException ignored) { /* already registered */ }
    }

    public void stop() {
        if (!started) return;
        started = false;
        if (connectivity != null) {
            try { connectivity.unregisterNetworkCallback(callback); } catch (IllegalArgumentException ignored) {}
        }
        executor.shutdownNow();
    }

    private void scheduleBenchmark() {
        if (!started) return;
        executor.schedule(this::benchmarkAndApply, 750, TimeUnit.MILLISECONDS);
    }

    private void benchmarkAndApply() {
        if (!isAutomatic() || NautrixSecureDnsApi.isManaged()) return;
        List<DnsScorePolicy.Result> results = new ArrayList<>();
        DnsScorePolicy.Result currentResult = null;
        for (Provider provider : PROVIDERS) {
            DnsScorePolicy.Result result = benchmark(provider);
            results.add(result);
            if (provider.id.equals(current.id)) currentResult = result;
        }
        if (currentResult == null) currentResult = results.get(0);
        DnsScorePolicy.Result chosen = DnsScorePolicy.choose(currentResult, results);
        for (int i = 0; i < PROVIDERS.size(); i++) {
            if (PROVIDERS.get(i).id.equals(chosen.id)) {
                Provider next = PROVIDERS.get(i);
                if (!next.id.equals(current.id) && NautrixSecureDnsApi.applySecureConfig(next.template)) current = next;
                return;
            }
        }
    }

    private DnsScorePolicy.Result benchmark(Provider provider) {
        List<Long> ok = new ArrayList<>();
        int failures = 0;
        for (int i = 0; i < NautrixConstants.DNS_SAMPLES; i++) {
            long elapsed = query(provider.endpoint, 0x4E58 + i);
            if (elapsed < 0) failures++; else ok.add(elapsed);
        }
        return new DnsScorePolicy.Result(provider.id, ok, failures, NautrixConstants.DNS_SAMPLES);
    }

    private static long query(String endpoint, int id) {
        HttpsURLConnection connection = null;
        try {
            byte[] packet = dnsQuery(id);
            connection = (HttpsURLConnection) new URL(endpoint).openConnection();
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(2000);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/dns-message");
            connection.setRequestProperty("Accept", "application/dns-message");
            connection.setDoOutput(true);
            long start = SystemClock.elapsedRealtimeNanos();
            try (OutputStream out = connection.getOutputStream()) { out.write(packet); }
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) return -1;
            try (var in = connection.getInputStream()) { if (in.read() < 0) return -1; }
            return TimeUnit.NANOSECONDS.toMillis(SystemClock.elapsedRealtimeNanos() - start);
        } catch (Exception e) {
            return -1;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static byte[] dnsQuery(int id) {
        // Standard recursive A query for example.com, sufficient to benchmark resolver latency.
        return new byte[] {
            (byte)(id >>> 8), (byte)id, 0x01, 0x00, 0x00, 0x01, 0,0, 0,0, 0,0,
            7,'e','x','a','m','p','l','e', 3,'c','o','m', 0, 0,1, 0,1
        };
    }
}
