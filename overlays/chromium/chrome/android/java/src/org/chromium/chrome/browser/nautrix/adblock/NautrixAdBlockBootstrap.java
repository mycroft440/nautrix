package org.chromium.chrome.browser.nautrix.adblock;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileOutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.HttpsURLConnection;

/** Loads cached rules immediately and refreshes reputable public filter lists once per day. */
public final class NautrixAdBlockBootstrap {
    private static final long UPDATE_MS = 24L * 60L * 60L * 1000L;
    private static final int MAX_LIST_BYTES = 8 * 1024 * 1024;
    private static final List<String> LISTS = List.of(
            "https://easylist.to/easylist/easylist.txt",
            "https://easylist.to/easylist/easyprivacy.txt",
            "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/filters.txt",
            "https://easylist-downloads.adblockplus.org/easylistportuguese.txt");
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static boolean started;

    public static synchronized void start(Context context) {
        if (started) return;
        started = true;
        Context app = context.getApplicationContext();
        File dir = new File(app.getFilesDir(), "nautrix_filters");
        File rules = new File(dir, "combined.txt");
        if (rules.isFile()) {
            try { NautrixAdBlockBridge.replaceRules(Files.readString(rules.toPath())); }
            catch (Exception ignored) {}
        }
        SharedPreferences prefs = app.getSharedPreferences("nautrix_adblock", Context.MODE_PRIVATE);
        long last = prefs.getLong("updated", 0L);
        if (System.currentTimeMillis() - last >= UPDATE_MS) {
            EXECUTOR.execute(() -> update(dir, rules, prefs));
        }
    }

    private static void update(File dir, File target, SharedPreferences prefs) {
        StringBuilder combined = new StringBuilder("! Nautrix combined filters\n");
        int success = 0;
        for (String source : LISTS) {
            String text = download(source);
            if (text == null || text.length() < 1024) continue;
            combined.append("\n! source: ").append(source).append('\n').append(text).append('\n');
            success++;
        }
        // Never replace a known-good cache with a partial/empty network failure.
        if (success < 2 || combined.length() < 50_000) return;
        dir.mkdirs();
        File tmp = new File(dir, "combined.tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(combined.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) { return; }
        if (!tmp.renameTo(target)) {
            try { Files.move(tmp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
            catch (Exception e) { return; }
        }
        if (NautrixAdBlockBridge.replaceRules(combined.toString())) {
            prefs.edit().putLong("updated", System.currentTimeMillis()).apply();
        }
    }

    private static String download(String rawUrl) {
        HttpsURLConnection connection = null;
        try {
            connection = (HttpsURLConnection) new URL(rawUrl).openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(12_000);
            connection.setRequestProperty("User-Agent", "Nautrix filter updater");
            if (connection.getResponseCode() != 200) return null;
            int announced = connection.getContentLength();
            if (announced > MAX_LIST_BYTES) return null;
            try (var in = connection.getInputStream()) {
                byte[] data = in.readNBytes(MAX_LIST_BYTES + 1);
                if (data.length > MAX_LIST_BYTES) return null;
                return new String(data, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private NautrixAdBlockBootstrap() {}
}
