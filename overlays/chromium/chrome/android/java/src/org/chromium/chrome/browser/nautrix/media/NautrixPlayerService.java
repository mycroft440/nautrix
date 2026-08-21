package org.chromium.chrome.browser.nautrix.media;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

/** Foreground-capable MediaSession player; audio continues when browser UI closes. */
@UnstableApi
public final class NautrixPlayerService extends MediaSessionService {
    private ExoPlayer player;
    private MediaSession session;

    @Override
    public void onCreate() {
        super.onCreate();
        NautrixMediaCache smartCache = NautrixMediaCache.get(this);
        NautrixOfflineMediaStore offline = NautrixOfflineMediaStore.get(this);
        DefaultMediaSourceFactory mediaSources =
                new DefaultMediaSourceFactory(offline.playbackFactory(smartCache.dataSourceFactory()));
        player = new ExoPlayer.Builder(this).setMediaSourceFactory(mediaSources).build();
        player.addListener(new Player.Listener() {
            @Override public void onMediaItemTransition(@Nullable MediaItem item, int reason) {
                smartCache.setActiveMediaKey(item != null && item.localConfiguration != null
                        ? item.localConfiguration.uri.toString() : null);
                touch(item);
            }
            @Override public void onIsPlayingChanged(boolean isPlaying) {
                if (isPlaying) touch(player.getCurrentMediaItem());
            }
            private void touch(@Nullable MediaItem item) {
                if (item == null || item.localConfiguration == null) return;
                smartCache.touch(item.localConfiguration.uri.toString());
            }
        });
        session = new MediaSession.Builder(this, player).build();
    }

    @Override
    public @Nullable MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return session;
    }

    @Override
    public void onDestroy() {
        NautrixMediaCache.get(this).setActiveMediaKey(null);
        if (session != null) session.release();
        if (player != null) player.release();
        session = null;
        player = null;
        super.onDestroy();
    }
}
