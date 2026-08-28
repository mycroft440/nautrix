package com.nautrix.browser;

import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.webkit.CookieManager;
import android.webkit.URLUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Keeps the Android DownloadManager jobs created by Nautrix visible in its own manager. */
final class DownloadRegistry {
    private static final String PREFS = "nautrix_downloads";
    private static final String KEY_ENTRIES = "entries";
    private static final int MAX_ENTRIES = 100;

    private DownloadRegistry() { }

    static long enqueue(Context context, String url, String userAgent,
                        String contentDisposition, String mimeType, String referer) {
        Uri uri = Uri.parse(url);
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Only HTTPS downloads are accepted");
        }
        String guessed = URLUtil.guessFileName(url, contentDisposition, mimeType);
        String safeName = sanitizeFileName(guessed);
        String destination = "Nautrix/" + System.currentTimeMillis() + "-" + safeName;
        DownloadManager.Request request = new DownloadManager.Request(uri)
                .setTitle(safeName)
                .setDescription(uri.getHost() == null ? "Download do Nautrix" : uri.getHost())
                .setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, destination);
        if (mimeType != null && !mimeType.trim().isEmpty()) request.setMimeType(mimeType);
        if (userAgent != null && !userAgent.trim().isEmpty()) {
            request.addRequestHeader("User-Agent", userAgent);
        }
        if (referer != null && referer.startsWith("https://")) {
            request.addRequestHeader("Referer", referer);
        }
        String cookie = CookieManager.getInstance().getCookie(url);
        if ((cookie == null || cookie.isEmpty()) && referer != null) {
            cookie = CookieManager.getInstance().getCookie(referer);
        }
        if (cookie != null && !cookie.isEmpty()) request.addRequestHeader("Cookie", cookie);

        DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (manager == null) throw new IllegalStateException("DownloadManager unavailable");
        long id = manager.enqueue(request);
        remember(context, new Entry(id, url, safeName, mimeType, userAgent,
                contentDisposition, referer, System.currentTimeMillis()));
        return id;
    }

    static long retry(Context context, Entry entry) {
        return enqueue(context, entry.url, entry.userAgent, entry.contentDisposition,
                entry.mimeType, entry.referer);
    }

    static synchronized List<Entry> list(Context context) {
        String raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_ENTRIES, "[]");
        ArrayList<Entry> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(raw);
            for (int index = 0; index < array.length(); index++) {
                JSONObject item = array.optJSONObject(index);
                if (item == null) continue;
                long id = item.optLong("id", -1L);
                String url = item.optString("url", "");
                if (id < 0 || !url.startsWith("https://")) continue;
                result.add(new Entry(id, url, item.optString("name", "Download"),
                        emptyToNull(item.optString("mime", "")),
                        emptyToNull(item.optString("agent", "")),
                        emptyToNull(item.optString("disposition", "")),
                        emptyToNull(item.optString("referer", "")),
                        item.optLong("created", 0L)));
            }
        } catch (Exception ignored) {
        }
        return Collections.unmodifiableList(result);
    }

    static synchronized void remove(Context context, long id) {
        ArrayList<Entry> entries = new ArrayList<>(list(context));
        entries.removeIf(entry -> entry.id == id);
        save(context, entries);
    }

    private static synchronized void remember(Context context, Entry entry) {
        ArrayList<Entry> entries = new ArrayList<>(list(context));
        entries.removeIf(item -> item.id == entry.id);
        entries.add(0, entry);
        if (entries.size() > MAX_ENTRIES) {
            entries.subList(MAX_ENTRIES, entries.size()).clear();
        }
        save(context, entries);
    }

    private static void save(Context context, List<Entry> entries) {
        JSONArray array = new JSONArray();
        for (Entry entry : entries) {
            try {
                JSONObject item = new JSONObject();
                item.put("id", entry.id);
                item.put("url", entry.url);
                item.put("name", entry.name);
                item.put("mime", value(entry.mimeType));
                item.put("agent", value(entry.userAgent));
                item.put("disposition", value(entry.contentDisposition));
                item.put("referer", value(entry.referer));
                item.put("created", entry.createdAt);
                array.put(item);
            } catch (Exception ignored) {
            }
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_ENTRIES, array.toString()).apply();
    }

    private static String sanitizeFileName(String name) {
        String cleaned = name == null ? "download" : name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return cleaned.isEmpty() ? "download" : cleaned;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    static final class Entry {
        final long id;
        final String url;
        final String name;
        final String mimeType;
        final String userAgent;
        final String contentDisposition;
        final String referer;
        final long createdAt;

        Entry(long id, String url, String name, String mimeType, String userAgent,
              String contentDisposition, String referer, long createdAt) {
            this.id = id;
            this.url = url;
            this.name = name;
            this.mimeType = mimeType;
            this.userAgent = userAgent;
            this.contentDisposition = contentDisposition;
            this.referer = referer;
            this.createdAt = createdAt;
        }
    }
}
