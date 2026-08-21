package org.chromium.chrome.browser.nautrix.media;

import android.content.Context;
import android.net.Uri;

import org.chromium.chrome.browser.nautrix.policy.DownloadPolicy;

/** Opens unprotected direct/HLS/DASH media in the native player. */
public final class NautrixMediaRouter {
    public static boolean open(Context context, String rawUrl, boolean incognito) {
        // Persistent smart cache is intentionally disabled for private browsing by not handing
        // incognito media to the persistent native player.
        if (incognito) return false;
        DownloadPolicy.Kind kind = DownloadPolicy.classify(rawUrl);
        if (kind != DownloadPolicy.Kind.DIRECT_MEDIA
                && kind != DownloadPolicy.Kind.HLS
                && kind != DownloadPolicy.Kind.DASH) return false;
        context.startActivity(NautrixPlayerActivity.createIntent(context, Uri.parse(rawUrl)));
        return true;
    }

    private NautrixMediaRouter() {}
}
