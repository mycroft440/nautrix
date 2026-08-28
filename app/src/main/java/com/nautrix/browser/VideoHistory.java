package com.nautrix.browser;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Private index of recently played media so cached portions can be reopened while offline. */
public final class VideoHistory {
    private static final String PREFS = "nautrix_video_history";
    private static final String KEY = "entries";
    private static final int MAX_ENTRIES = 24;
    private static final long MAX_AGE_MS = 30L * 24L * 60L * 60L * 1_000L;

    private VideoHistory() {
    }

    public static synchronized void remember(Context context, String url, String referer,
                                             String userAgent, String title) {
        if (url == null || !url.startsWith("https://")) return;
        JSONArray next = new JSONArray();
        JSONObject newest = new JSONObject();
        try {
            newest.put("url", url);
            newest.put("referer", referer == null ? "" : referer);
            newest.put("userAgent", userAgent == null ? "" : userAgent);
            newest.put("title", title == null ? "Vídeo" : title);
            newest.put("savedAt", System.currentTimeMillis());
            next.put(newest);
            for (Entry entry : list(context)) {
                if (url.equals(entry.url) || next.length() >= MAX_ENTRIES) continue;
                JSONObject value = new JSONObject();
                value.put("url", entry.url);
                value.put("referer", entry.referer);
                value.put("userAgent", entry.userAgent);
                value.put("title", entry.title);
                value.put("savedAt", entry.savedAt);
                next.put(value);
            }
        } catch (Exception ignored) {
        }
        preferences(context).edit().putString(KEY, next.toString()).apply();
    }

    public static synchronized List<Entry> list(Context context) {
        ArrayList<Entry> result = new ArrayList<>();
        String raw = preferences(context).getString(KEY, "[]");
        long oldest = System.currentTimeMillis() - MAX_AGE_MS;
        try {
            JSONArray values = new JSONArray(raw);
            for (int index = 0; index < values.length() && result.size() < MAX_ENTRIES; index++) {
                JSONObject value = values.optJSONObject(index);
                if (value == null) continue;
                String url = value.optString("url", "");
                long savedAt = value.optLong("savedAt", 0L);
                if (!url.startsWith("https://") || savedAt < oldest) continue;
                result.add(new Entry(url, value.optString("referer", ""),
                        value.optString("userAgent", ""),
                        value.optString("title", "Vídeo"), savedAt));
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    public static void clear(Context context) {
        preferences(context).edit().remove(KEY).apply();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static final class Entry {
        public final String url;
        public final String referer;
        public final String userAgent;
        public final String title;
        public final long savedAt;

        Entry(String url, String referer, String userAgent, String title, long savedAt) {
            this.url = url;
            this.referer = referer;
            this.userAgent = userAgent;
            this.title = title;
            this.savedAt = savedAt;
        }
    }
}
