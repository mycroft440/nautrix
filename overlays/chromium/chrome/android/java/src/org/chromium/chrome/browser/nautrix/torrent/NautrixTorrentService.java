package org.chromium.chrome.browser.nautrix.torrent;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
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

/** Foreground BitTorrent service. It survives browser Activity closure while transfers are active. */
public final class NautrixTorrentService extends Service {
    private static final String CHANNEL = "nautrix_torrent";
    private static final int NOTIFICATION_ID = 0x4E58;
    private static final String ACTION_MAGNET = "nautrix.torrent.ADD_MAGNET";
    private static final String EXTRA_SOURCE = "source";
    private static final long STATUS_INTERVAL_MS = 1500L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private long nativeHandle;

    public static boolean enqueueMagnet(Context context, String magnet) {
        TorrentPolicy.Magnet parsed = TorrentPolicy.parseMagnet(magnet);
        if (parsed == null) return false;
        Intent intent = new Intent(context, NautrixTorrentService.class)
                .setAction(ACTION_MAGNET)
                .putExtra(EXTRA_SOURCE, magnet);
        ContextCompat.startForegroundService(context, intent);
        return true;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        File root = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (root == null) root = new File(getFilesDir(), "torrents");
        File dir = new File(root, "Nautrix");
        dir.mkdirs();
        nativeHandle = nativeCreate(dir.getAbsolutePath());
        startForeground(NOTIFICATION_ID, buildNotification("Torrent pronto", 0, 0));
        handler.post(statusTick);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_MAGNET.equals(intent.getAction()) && nativeHandle != 0) {
            String source = intent.getStringExtra(EXTRA_SOURCE);
            if (source != null && TorrentPolicy.parseMagnet(source) != null) {
                String id = nativeAddMagnet(nativeHandle, source);
                if (id != null) nativeSetSequential(nativeHandle, id, true);
            }
        }
        return START_STICKY;
    }

    @Override
    public @Nullable IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(statusTick);
        if (nativeHandle != 0) nativeDestroy(nativeHandle);
        nativeHandle = 0;
        super.onDestroy();
    }

    private final Runnable statusTick = new Runnable() {
        @Override public void run() {
            if (nativeHandle == 0) return;
            try {
                JSONArray torrents = new JSONArray(nativeStatusJson(nativeHandle));
                long down = 0;
                int active = torrents.length();
                int bestProgress = 0;
                for (int i = 0; i < torrents.length(); i++) {
                    JSONObject t = torrents.getJSONObject(i);
                    down += t.optLong("downloadRate", 0L);
                    bestProgress = Math.max(bestProgress, t.optInt("progressPpm", 0) / 10_000);
                }
                NotificationManager nm = getSystemService(NotificationManager.class);
                if (nm != null) nm.notify(NOTIFICATION_ID,
                        buildNotification(active + " torrent(s)", bestProgress, down));
            } catch (Exception ignored) {
            }
            handler.postDelayed(this, STATUS_INTERVAL_MS);
        }
    };

    private Notification buildNotification(String title, int progress, long bytesPerSecond) {
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent pending = launch == null ? null : PendingIntent.getActivity(
                this, 0, launch, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String speed = bytesPerSecond <= 0 ? "" : " • ↓ " + humanSpeed(bytesPerSecond);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Nautrix downloads")
                .setContentText(title + speed)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setProgress(100, Math.max(0, Math.min(100, progress)), progress <= 0);
        if (pending != null) builder.setContentIntent(pending);
        return builder.build();
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

    private static String humanSpeed(long value) {
        if (value >= 1024L * 1024L) return String.format(java.util.Locale.ROOT, "%.1f MB/s", value / 1048576.0);
        if (value >= 1024L) return String.format(java.util.Locale.ROOT, "%.0f KB/s", value / 1024.0);
        return value + " B/s";
    }

    private static native long nativeCreate(String savePath);
    private static native void nativeDestroy(long handle);
    private static native String nativeAddMagnet(long handle, String magnet);
    private static native String nativeAddTorrentFile(long handle, String path);
    private static native boolean nativePause(long handle, String id);
    private static native boolean nativeResume(long handle, String id);
    private static native boolean nativeSetSequential(long handle, String id, boolean enabled);
    private static native String nativeStatusJson(long handle);
}
