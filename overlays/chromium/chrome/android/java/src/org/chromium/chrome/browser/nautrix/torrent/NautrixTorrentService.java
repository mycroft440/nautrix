package org.chromium.chrome.browser.nautrix.torrent;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Binder;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.chromium.chrome.browser.nautrix.policy.TorrentPolicy;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Foreground BitTorrent service.
 *
 * <p>The browser activity may disappear while transfers continue. Transfer state lives in
 * libtorrent fast-resume files under app-private storage. Policy-paused torrent IDs are persisted
 * separately so Wi-Fi recovery never resumes a torrent that the user paused manually.
 */
public final class NautrixTorrentService extends Service {
    private static final String CHANNEL = "nautrix_torrent";
    private static final int NOTIFICATION_ID = 0x4E58;

    private static final String ACTION_RUN = "nautrix.torrent.RUN";
    private static final String ACTION_MAGNET = "nautrix.torrent.ADD_MAGNET";
    private static final String ACTION_TORRENT = "nautrix.torrent.ADD_FILE";
    private static final String EXTRA_SOURCE = "source";

    private static final String PREFS = "nautrix_torrent";
    private static final String PREF_WIFI_ONLY = "wifi_only";
    private static final String PREF_SEEDING = "seeding";
    private static final String PREF_DOWNLOAD_LIMIT = "download_limit";
    private static final String PREF_UPLOAD_LIMIT = "upload_limit";
    private static final String PREF_CONNECTION_LIMIT = "connection_limit";
    private static final String PREF_NETWORK_PAUSED = "network_paused";
    private static final String PREF_COMPLETION_PAUSED = "completion_paused";

    private static final int DEFAULT_CONNECTION_LIMIT = 200;
    private static final long STATUS_INTERVAL_MS = 1500L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final LocalBinder binder = new LocalBinder();
    private final Object policyLock = new Object();

    private SharedPreferences prefs;
    private volatile long nativeHandle;
    private boolean foreground;
    private int boundClients;

    private boolean wifiOnly;
    private boolean seedingEnabled;
    private int downloadLimit;
    private int uploadLimit;
    private int connectionLimit;
    private Set<String> networkPaused = new HashSet<>();
    private Set<String> completionPaused = new HashSet<>();

    /** Ensures the service exists so the manager can bind even before a torrent is added. */
    public static void ensureRunning(Context context) {
        Intent intent = new Intent(context, NautrixTorrentService.class).setAction(ACTION_RUN);
        ContextCompat.startForegroundService(context, intent);
    }

    public static boolean enqueueMagnet(Context context, String magnet) {
        TorrentPolicy.Magnet parsed = TorrentPolicy.parseMagnet(magnet);
        if (parsed == null) return false;
        Intent intent = new Intent(context, NautrixTorrentService.class)
                .setAction(ACTION_MAGNET)
                .putExtra(EXTRA_SOURCE, magnet);
        ContextCompat.startForegroundService(context, intent);
        return true;
    }

    /** Adds a .torrent file copied into Nautrix's private torrent_metainfo directory. */
    public static boolean enqueueTorrentFile(Context context, String path) {
        if (!isPrivateTorrentMetadataPath(context, path)) return false;
        Intent intent = new Intent(context, NautrixTorrentService.class)
                .setAction(ACTION_TORRENT)
                .putExtra(EXTRA_SOURCE, path);
        ContextCompat.startForegroundService(context, intent);
        return true;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        loadPolicyState();

        File root = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (root == null) root = new File(getFilesDir(), "torrents");
        File payloadDir = new File(root, "Nautrix");
        File resumeDir = new File(getFilesDir(), "torrent_fastresume");
        if (!payloadDir.isDirectory()) payloadDir.mkdirs();
        if (!resumeDir.isDirectory()) resumeDir.mkdirs();

        nativeHandle = nativeCreate(payloadDir.getAbsolutePath(), resumeDir.getAbsolutePath());
        enterForeground(buildNotification("Preparando torrents", 0, 0));

        if (nativeHandle == 0) {
            handler.post(() -> {
                leaveForeground();
                stopSelf();
            });
            return;
        }

        nativeSetRateLimits(nativeHandle, downloadLimit, uploadLimit);
        nativeSetConnectionLimit(nativeHandle, connectionLimit);
        handler.post(statusTick);
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        if (nativeHandle == 0) return START_NOT_STICKY;
        if (intent == null) return START_STICKY;

        String action = intent.getAction();
        if (ACTION_MAGNET.equals(action)) {
            enterForeground(buildNotification("Adicionando magnet", 0, 0));
            String source = intent.getStringExtra(EXTRA_SOURCE);
            if (source != null && TorrentPolicy.parseMagnet(source) != null) {
                String id = nativeAddMagnet(nativeHandle, source);
                enforceNetworkPolicyForNewTorrent(id);
            }
        } else if (ACTION_TORRENT.equals(action)) {
            enterForeground(buildNotification("Importando .torrent", 0, 0));
            String source = intent.getStringExtra(EXTRA_SOURCE);
            if (source != null && isPrivateTorrentMetadataPath(this, source)) {
                try {
                    String id = nativeAddTorrentFile(nativeHandle, source);
                    enforceNetworkPolicyForNewTorrent(id);
                } finally {
                    // torrent_info is parsed synchronously by the JNI. The temporary metainfo file
                    // is no longer needed afterwards, regardless of success or failure.
                    new File(source).delete();
                }
            }
        } else if (ACTION_RUN.equals(action)) {
            enterForeground(buildNotification("Gerenciador de torrents", 0, 0));
        }
        return START_STICKY;
    }

    @Override
    public @Nullable IBinder onBind(Intent intent) {
        boundClients++;
        return binder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        boundClients = Math.max(0, boundClients - 1);
        handler.post(statusTick);
        return true;
    }

    @Override
    public void onRebind(Intent intent) {
        boundClients++;
        super.onRebind(intent);
    }

    /** Android 15+ limits dataSync foreground services; persist and exit cleanly on timeout. */
    @Override
    public void onTimeout(int startId, int fgsType) {
        if (Build.VERSION.SDK_INT < 35) return;
        long handle = nativeHandle;
        if (handle != 0) nativeSaveResumeState(handle);
        leaveForeground();
        stopSelf(startId);
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(statusTick);
        long handle = nativeHandle;
        nativeHandle = 0;
        if (handle != 0) {
            nativeSaveResumeState(handle);
            nativeDestroy(handle);
        }
        leaveForeground();
        super.onDestroy();
    }

    /** Local in-process API used by the native torrent manager Activity. */
    public final class LocalBinder extends Binder {
        public String statusJson() {
            long handle = nativeHandle;
            return handle == 0 ? "[]" : safeJson(nativeStatusJson(handle));
        }

        public String filesJson(String id) {
            long handle = nativeHandle;
            return handle == 0 ? "[]" : safeJson(nativeFilesJson(handle, id));
        }

        public boolean pause(String id) {
            long handle = nativeHandle;
            if (handle == 0 || id == null) return false;
            synchronized (policyLock) {
                networkPaused.remove(id);
                completionPaused.remove(id);
                persistPolicyPausedLocked();
            }
            return nativePause(handle, id);
        }

        public boolean resume(String id) {
            long handle = nativeHandle;
            if (handle == 0 || id == null) return false;
            synchronized (policyLock) {
                if (wifiOnly && !hasUsableWifi()) return false;
                networkPaused.remove(id);
                completionPaused.remove(id);
                persistPolicyPausedLocked();
            }
            enterForeground(buildNotification("Retomando torrent", 0, 0));
            return nativeResume(handle, id);
        }

        public boolean setSequential(String id, boolean enabled) {
            long handle = nativeHandle;
            return handle != 0 && nativeSetSequential(handle, id, enabled);
        }

        public boolean setFilePriority(String id, int fileIndex, int priority) {
            long handle = nativeHandle;
            return handle != 0 && nativeSetFilePriority(handle, id, fileIndex, priority);
        }

        public boolean remove(String id, boolean deleteData) {
            long handle = nativeHandle;
            if (handle == 0 || id == null) return false;
            synchronized (policyLock) {
                networkPaused.remove(id);
                completionPaused.remove(id);
                persistPolicyPausedLocked();
            }
            return nativeRemove(handle, id, deleteData);
        }

        public boolean isWifiOnly() {
            synchronized (policyLock) {
                return wifiOnly;
            }
        }

        public void setWifiOnly(boolean enabled) {
            synchronized (policyLock) {
                wifiOnly = enabled;
                prefs.edit().putBoolean(PREF_WIFI_ONLY, enabled).apply();
            }
            applyPoliciesNow();
        }

        public boolean isSeedingEnabled() {
            synchronized (policyLock) {
                return seedingEnabled;
            }
        }

        public void setSeedingEnabled(boolean enabled) {
            Set<String> resumeAfterCompletion = new HashSet<>();
            synchronized (policyLock) {
                seedingEnabled = enabled;
                prefs.edit().putBoolean(PREF_SEEDING, enabled).apply();
                if (enabled) {
                    resumeAfterCompletion.addAll(completionPaused);
                    completionPaused.clear();
                    persistPolicyPausedLocked();
                }
            }
            if (enabled) resumePolicyIds(resumeAfterCompletion);
            applyPoliciesNow();
        }

        public int getDownloadLimit() {
            synchronized (policyLock) {
                return downloadLimit;
            }
        }

        public int getUploadLimit() {
            synchronized (policyLock) {
                return uploadLimit;
            }
        }

        public int getConnectionLimit() {
            synchronized (policyLock) {
                return connectionLimit;
            }
        }

        public void setRateLimits(int down, int up) {
            down = Math.max(0, down);
            up = Math.max(0, up);
            synchronized (policyLock) {
                downloadLimit = down;
                uploadLimit = up;
                prefs.edit()
                        .putInt(PREF_DOWNLOAD_LIMIT, down)
                        .putInt(PREF_UPLOAD_LIMIT, up)
                        .apply();
            }
            long handle = nativeHandle;
            if (handle != 0) nativeSetRateLimits(handle, down, up);
        }

        public void setConnectionLimit(int maxConnections) {
            int clamped = Math.max(20, Math.min(2000, maxConnections));
            synchronized (policyLock) {
                connectionLimit = clamped;
                prefs.edit().putInt(PREF_CONNECTION_LIMIT, clamped).apply();
            }
            long handle = nativeHandle;
            if (handle != 0) nativeSetConnectionLimit(handle, clamped);
        }
    }

    private final Runnable statusTick = new Runnable() {
        @Override
        public void run() {
            long handle = nativeHandle;
            if (handle == 0) return;

            boolean policyChanged = false;
            JSONArray torrents;
            try {
                torrents = new JSONArray(safeJson(nativeStatusJson(handle)));
                Set<String> completionChanged = applyCompletionPolicy(torrents);
                policyChanged = !completionChanged.isEmpty();
                policyChanged |= applyNetworkPolicy(torrents, completionChanged);
            } catch (Exception e) {
                handler.postDelayed(this, STATUS_INTERVAL_MS);
                return;
            }

            // Re-read after a pause/resume transition. This avoids treating a stale pre-policy
            // snapshot as authoritative (for example stopping the service immediately after Wi-Fi
            // recovery resumed transfers).
            if (policyChanged) {
                try {
                    torrents = new JSONArray(safeJson(nativeStatusJson(handle)));
                } catch (Exception ignored) {
                }
            }

            long down = 0;
            int bestProgress = 0;
            int activeTransfers = 0;
            for (int i = 0; i < torrents.length(); i++) {
                JSONObject t = torrents.optJSONObject(i);
                if (t == null) continue;
                down += t.optLong("downloadRate", 0L);
                bestProgress = Math.max(bestProgress, t.optInt("progressPpm", 0) / 10_000);
                if (!t.optBoolean("paused", false)) activeTransfers++;
            }

            if (torrents.length() == 0) {
                nativeSaveResumeState(handle);
                leaveForeground();
                if (boundClients == 0) {
                    stopSelf();
                    return;
                }
            } else {
                enterForeground(buildNotification(
                        activeTransfers + " ativo(s) • " + torrents.length() + " torrent(s)",
                        bestProgress,
                        down));
            }
            handler.postDelayed(this, STATUS_INTERVAL_MS);
        }
    };

    /** Returns IDs newly paused because seeding is disabled. */
    private Set<String> applyCompletionPolicy(JSONArray torrents) {
        Set<String> changed = new HashSet<>();
        boolean seed;
        synchronized (policyLock) {
            seed = seedingEnabled;
        }
        if (seed) return changed;

        long handle = nativeHandle;
        if (handle == 0) return changed;
        for (int i = 0; i < torrents.length(); i++) {
            JSONObject t = torrents.optJSONObject(i);
            if (t == null || !t.optBoolean("finished", false) || t.optBoolean("paused", false)) {
                continue;
            }
            String id = t.optString("id", "");
            if (id.isEmpty()) continue;
            if (nativePause(handle, id)) {
                changed.add(id);
                synchronized (policyLock) {
                    networkPaused.remove(id);
                    completionPaused.add(id);
                    persistPolicyPausedLocked();
                }
            }
        }
        return changed;
    }

    /** Applies Wi-Fi-only without ever resuming a user-paused transfer. */
    private boolean applyNetworkPolicy(JSONArray torrents, Set<String> skipIds) {
        long handle = nativeHandle;
        if (handle == 0) return false;

        boolean onlyWifi;
        boolean seed;
        Set<String> pausedByNetwork;
        synchronized (policyLock) {
            onlyWifi = wifiOnly;
            seed = seedingEnabled;
            pausedByNetwork = new HashSet<>(networkPaused);
        }

        boolean wifi = hasUsableWifi();
        boolean changed = false;
        if (onlyWifi && !wifi) {
            for (int i = 0; i < torrents.length(); i++) {
                JSONObject t = torrents.optJSONObject(i);
                if (t == null || t.optBoolean("paused", false)) continue;
                String id = t.optString("id", "");
                if (id.isEmpty() || skipIds.contains(id)) continue;
                if (nativePause(handle, id)) {
                    pausedByNetwork.add(id);
                    changed = true;
                }
            }
        } else if (!pausedByNetwork.isEmpty()) {
            Set<String> finished = finishedIds(torrents);
            Set<String> keepCompletionPaused = new HashSet<>();
            for (String id : pausedByNetwork) {
                if (!seed && finished.contains(id)) {
                    keepCompletionPaused.add(id);
                    continue;
                }
                if (nativeResume(handle, id)) changed = true;
            }
            pausedByNetwork.clear();
            synchronized (policyLock) {
                completionPaused.addAll(keepCompletionPaused);
            }
        }

        synchronized (policyLock) {
            if (!networkPaused.equals(pausedByNetwork)) {
                networkPaused = pausedByNetwork;
                persistPolicyPausedLocked();
            } else if (changed) {
                persistPolicyPausedLocked();
            }
        }
        return changed;
    }

    private void applyPoliciesNow() {
        long handle = nativeHandle;
        if (handle == 0) return;
        try {
            JSONArray torrents = new JSONArray(safeJson(nativeStatusJson(handle)));
            Set<String> completionChanged = applyCompletionPolicy(torrents);
            applyNetworkPolicy(torrents, completionChanged);
        } catch (Exception ignored) {
        }
        handler.removeCallbacks(statusTick);
        handler.post(statusTick);
    }

    private void resumePolicyIds(Set<String> ids) {
        if (ids.isEmpty()) return;
        long handle = nativeHandle;
        if (handle == 0) return;
        boolean canUseNetwork;
        synchronized (policyLock) {
            canUseNetwork = !wifiOnly || hasUsableWifi();
        }
        for (String id : ids) {
            if (canUseNetwork) {
                nativeResume(handle, id);
            } else {
                synchronized (policyLock) {
                    networkPaused.add(id);
                }
            }
        }
        synchronized (policyLock) {
            persistPolicyPausedLocked();
        }
        if (canUseNetwork) enterForeground(buildNotification("Retomando seeding", 100, 0));
    }

    private void enforceNetworkPolicyForNewTorrent(@Nullable String id) {
        if (id == null || id.isEmpty()) return;
        boolean shouldPause;
        synchronized (policyLock) {
            shouldPause = wifiOnly && !hasUsableWifi();
        }
        if (shouldPause && nativePause(nativeHandle, id)) {
            synchronized (policyLock) {
                networkPaused.add(id);
                persistPolicyPausedLocked();
            }
        }
    }

    private void loadPolicyState() {
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        synchronized (policyLock) {
            wifiOnly = prefs.getBoolean(PREF_WIFI_ONLY, false);
            seedingEnabled = prefs.getBoolean(PREF_SEEDING, false);
            downloadLimit = Math.max(0, prefs.getInt(PREF_DOWNLOAD_LIMIT, 0));
            uploadLimit = Math.max(0, prefs.getInt(PREF_UPLOAD_LIMIT, 0));
            connectionLimit = Math.max(20, Math.min(2000,
                    prefs.getInt(PREF_CONNECTION_LIMIT, DEFAULT_CONNECTION_LIMIT)));
            networkPaused = new HashSet<>(prefs.getStringSet(PREF_NETWORK_PAUSED, Set.of()));
            completionPaused = new HashSet<>(prefs.getStringSet(PREF_COMPLETION_PAUSED, Set.of()));
        }
    }

    private void persistPolicyPausedLocked() {
        prefs.edit()
                .putStringSet(PREF_NETWORK_PAUSED, new HashSet<>(networkPaused))
                .putStringSet(PREF_COMPLETION_PAUSED, new HashSet<>(completionPaused))
                .apply();
    }

    private boolean hasUsableWifi() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm == null) return false;
        Network network = cm.getActiveNetwork();
        if (network == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(network);
        return caps != null
                && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private static Set<String> finishedIds(JSONArray torrents) {
        Set<String> out = new HashSet<>();
        for (int i = 0; i < torrents.length(); i++) {
            JSONObject t = torrents.optJSONObject(i);
            if (t != null && t.optBoolean("finished", false)) {
                String id = t.optString("id", "");
                if (!id.isEmpty()) out.add(id);
            }
        }
        return out;
    }

    private void enterForeground(Notification notification) {
        startForeground(NOTIFICATION_ID, notification);
        foreground = true;
    }

    private void leaveForeground() {
        if (!foreground) return;
        stopForeground(STOP_FOREGROUND_REMOVE);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(NOTIFICATION_ID);
        foreground = false;
    }

    private Notification buildNotification(String title, int progress, long bytesPerSecond) {
        Intent launch = new Intent(this, NautrixTorrentManagerActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending = PendingIntent.getActivity(
                this,
                0,
                launch,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String speed = bytesPerSecond <= 0 ? "" : " • ↓ " + humanSpeed(bytesPerSecond);
        return new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Nautrix • Torrents")
                .setContentText(title + speed)
                .setContentIntent(pending)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setProgress(100, Math.max(0, Math.min(100, progress)), progress <= 0)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL, "Downloads torrent", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Downloads BitTorrent em segundo plano do Nautrix");
        manager.createNotificationChannel(channel);
    }

    private static boolean isPrivateTorrentMetadataPath(Context context, @Nullable String raw) {
        if (raw == null || raw.isEmpty()) return false;
        try {
            File root = new File(context.getFilesDir(), "torrent_metainfo").getCanonicalFile();
            File candidate = new File(raw).getCanonicalFile();
            return candidate.getPath().startsWith(root.getPath() + File.separator)
                    && candidate.getName().endsWith(".torrent")
                    && candidate.isFile();
        } catch (Exception e) {
            return false;
        }
    }

    private static String safeJson(@Nullable String raw) {
        return raw == null || raw.isEmpty() ? "[]" : raw;
    }

    private static String humanSpeed(long value) {
        if (value >= 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.1f MB/s", value / 1048576.0);
        }
        if (value >= 1024L) {
            return String.format(Locale.ROOT, "%.0f KB/s", value / 1024.0);
        }
        return value + " B/s";
    }

    private static native long nativeCreate(String savePath, String resumePath);
    private static native void nativeDestroy(long handle);
    private static native String nativeAddMagnet(long handle, String magnet);
    private static native String nativeAddTorrentFile(long handle, String path);
    private static native boolean nativePause(long handle, String id);
    private static native boolean nativeResume(long handle, String id);
    private static native boolean nativeSetSequential(long handle, String id, boolean enabled);
    private static native void nativeSetRateLimits(long handle, int down, int up);
    private static native void nativeSetConnectionLimit(long handle, int maxConnections);
    private static native String nativeFilesJson(long handle, String id);
    private static native boolean nativeSetFilePriority(long handle, String id, int fileIndex, int priority);
    private static native boolean nativeRemove(long handle, String id, boolean deleteData);
    private static native void nativeSaveResumeState(long handle);
    private static native String nativeStatusJson(long handle);
}
