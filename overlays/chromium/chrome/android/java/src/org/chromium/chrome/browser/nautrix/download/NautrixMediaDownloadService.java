package org.chromium.chrome.browser.nautrix.download;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.net.Uri;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.offline.Download;
import androidx.media3.exoplayer.offline.DownloadManager;
import androidx.media3.exoplayer.offline.DownloadRequest;
import androidx.media3.exoplayer.offline.DownloadService;
import androidx.media3.exoplayer.scheduler.Requirements;

import org.chromium.chrome.browser.nautrix.media.NautrixMediaCache;
import org.chromium.chrome.browser.nautrix.media.NautrixOfflineMediaStore;
import org.chromium.chrome.browser.nautrix.policy.DownloadPolicy;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Permanent Media3 downloads for HLS/DASH/direct media. DRM is not bypassed. */
@UnstableApi
public final class NautrixMediaDownloadService extends DownloadService {
    private static final int NOTIFICATION_ID = 0x4E59;
    private static final String CHANNEL = "nautrix_media_download";
    private static final ExecutorService DOWNLOAD_EXECUTOR = Executors.newFixedThreadPool(3);
    private static DownloadManager manager;

    public NautrixMediaDownloadService() { super(NOTIFICATION_ID); }

    public static boolean enqueue(Context context, String rawUrl) {
        DownloadPolicy.Kind kind = DownloadPolicy.classify(rawUrl);
        if (kind != DownloadPolicy.Kind.HLS && kind != DownloadPolicy.Kind.DASH
                && kind != DownloadPolicy.Kind.DIRECT_MEDIA) return false;
        Uri uri = Uri.parse(rawUrl);
        DownloadRequest.Builder builder = new DownloadRequest.Builder("nautrix:" + rawUrl, uri);
        if (kind == DownloadPolicy.Kind.HLS) builder.setMimeType(MimeTypes.APPLICATION_M3U8);
        if (kind == DownloadPolicy.Kind.DASH) builder.setMimeType(MimeTypes.APPLICATION_MPD);
        sendAddDownload(context, NautrixMediaDownloadService.class, builder.build(), true);
        return true;
    }

    @Override public void onCreate() {
        createChannel();
        super.onCreate();
    }

    @Override
    protected DownloadManager getDownloadManager() {
        synchronized (NautrixMediaDownloadService.class) {
            if (manager == null) {
                NautrixOfflineMediaStore offline = NautrixOfflineMediaStore.get(this);
                // Reuse Smart Cache as upstream so promotion can copy already-cached bytes locally.
                manager = new DownloadManager(this, offline.databaseProvider(), offline.cache(),
                        NautrixMediaCache.get(this).promotionUpstreamFactory(), DOWNLOAD_EXECUTOR);
                manager.setMaxParallelDownloads(3);
                boolean wifiOnly = getSharedPreferences("nautrix_downloads", MODE_PRIVATE)
                        .getBoolean("wifi_only", false);
                manager.setRequirements(new Requirements(wifiOnly
                        ? Requirements.NETWORK_UNMETERED : Requirements.NETWORK));
                manager.resumeDownloads();
            }
            return manager;
        }
    }

    @Override
    protected Notification getForegroundNotification(List<Download> downloads,
            @Requirements.RequirementFlags int notMetRequirements) {
        int active = 0;
        float best = -1f;
        for (Download download : downloads) {
            if (download.state == Download.STATE_DOWNLOADING || download.state == Download.STATE_QUEUED
                    || download.state == Download.STATE_RESTARTING) active++;
            best = Math.max(best, download.getPercentDownloaded());
        }
        boolean waiting = notMetRequirements != 0;
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOnlyAlertOnce(true).setOngoing(active > 0)
                .setContentTitle("Nautrix • mídia offline")
                .setContentText(waiting ? "Aguardando rede permitida" : active + " download(s)");
        if (best >= 0f) builder.setProgress(100, Math.min(100, Math.round(best)), false);
        else builder.setProgress(0, 0, active > 0);
        return builder.build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL, "Mídia offline", NotificationManager.IMPORTANCE_LOW));
    }
}
