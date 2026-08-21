package org.chromium.chrome.browser.nautrix.download;

import android.content.Context;

import org.chromium.chrome.browser.nautrix.media.NautrixMediaRouter;
import org.chromium.chrome.browser.nautrix.policy.DownloadPolicy;
import org.chromium.chrome.browser.nautrix.torrent.NautrixTorrentService;

/** Central protocol router; normal HTTP downloads remain owned by Chromium DownloadManager. */
public final class NautrixDownloadRouter {
    public enum Action { CHROMIUM_DOWNLOAD, MEDIA3_DOWNLOAD, NATIVE_PLAYER, TORRENT, UNSUPPORTED }

    public static Action route(Context context, String url, boolean incognito, boolean playMedia) {
        DownloadPolicy.Kind kind = DownloadPolicy.classify(url);
        if (kind == DownloadPolicy.Kind.MAGNET) {
            return NautrixTorrentService.enqueueMagnet(context, url) ? Action.TORRENT : Action.UNSUPPORTED;
        }
        if (kind == DownloadPolicy.Kind.TORRENT) return Action.TORRENT;
        if (playMedia && NautrixMediaRouter.open(context, url, incognito)) return Action.NATIVE_PLAYER;
        if (!incognito && (kind == DownloadPolicy.Kind.HLS || kind == DownloadPolicy.Kind.DASH)
                && NautrixMediaDownloadService.enqueue(context, url)) return Action.MEDIA3_DOWNLOAD;
        if (kind == DownloadPolicy.Kind.HTTP || kind == DownloadPolicy.Kind.DIRECT_MEDIA) {
            return Action.CHROMIUM_DOWNLOAD;
        }
        return Action.UNSUPPORTED;
    }

    private NautrixDownloadRouter() {}
}
