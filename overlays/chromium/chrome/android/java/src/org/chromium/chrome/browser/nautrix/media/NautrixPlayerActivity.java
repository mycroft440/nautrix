package org.chromium.chrome.browser.nautrix.media;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import androidx.media3.ui.PlayerView;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.concurrent.Executor;

/** Full-screen Nautrix player backed by {@link NautrixPlayerService}. */
public final class NautrixPlayerActivity extends Activity {
    public static final String EXTRA_URI = "nautrix.media.uri";
    private static final long DOUBLE_TAP_SEEK_MS = 10_000L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Executor mainExecutor = command -> mainHandler.post(command);
    private PlayerView playerView;
    private MediaController controller;
    private ListenableFuture<MediaController> controllerFuture;
    private GestureDetector gestureDetector;
    private AudioManager audioManager;
    private float gestureStartBrightness;
    private int gestureStartVolume;

    public static Intent createIntent(Context context, Uri uri) {
        return new Intent(context, NautrixPlayerActivity.class)
                .setData(uri)
                .putExtra(EXTRA_URI, uri.toString())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        window.setStatusBarColor(android.graphics.Color.BLACK);
        window.setNavigationBarColor(android.graphics.Color.BLACK);

        playerView = new PlayerView(this);
        playerView.setBackgroundColor(android.graphics.Color.BLACK);
        playerView.setUseController(true);
        playerView.setControllerAutoShow(true);
        setContentView(playerView);

        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        gestureDetector = new GestureDetector(this, new PlayerGestures());
        playerView.setOnTouchListener(this::onPlayerTouch);

        SessionToken token = new SessionToken(
                this, new ComponentName(this, NautrixPlayerService.class));
        controllerFuture = new MediaController.Builder(this, token).buildAsync();
        controllerFuture.addListener(this::onControllerReady, mainExecutor);
    }

    private void onControllerReady() {
        try {
            controller = controllerFuture.get();
        } catch (Exception e) {
            finish();
            return;
        }
        playerView.setPlayer(controller);
        Uri uri = getIntent().getData();
        if (uri == null) {
            String raw = getIntent().getStringExtra(EXTRA_URI);
            if (raw != null && !raw.isEmpty()) uri = Uri.parse(raw);
        }
        if (uri != null && (controller.getMediaItemCount() == 0
                || controller.getCurrentMediaItem() == null
                || controller.getCurrentMediaItem().localConfiguration == null
                || !uri.equals(controller.getCurrentMediaItem().localConfiguration.uri))) {
            controller.setMediaItem(MediaItem.fromUri(uri));
            controller.prepare();
            controller.play();
        }
    }

    private boolean onPlayerTouch(View view, MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            WindowManager.LayoutParams attrs = getWindow().getAttributes();
            gestureStartBrightness = attrs.screenBrightness < 0 ? 0.5f : attrs.screenBrightness;
            gestureStartVolume = audioManager == null ? 0
                    : audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        }
        boolean handled = gestureDetector.onTouchEvent(event);
        if (event.getActionMasked() == MotionEvent.ACTION_UP
                || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            view.performClick();
        }
        return handled;
    }

    @Override
    public void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (controller == null || !controller.isPlaying()) return;
        VideoSize size = controller.getVideoSize();
        if (size.width <= 0 || size.height <= 0) return;
        NautrixPictureInPictureController.enter(this, size.width, size.height);
    }

    @Override
    protected void onDestroy() {
        playerView.setPlayer(null);
        if (controllerFuture != null) MediaController.releaseFuture(controllerFuture);
        controller = null;
        super.onDestroy();
    }

    private final class PlayerGestures extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDown(MotionEvent e) {
            return true;
        }

        @Override
        public boolean onDoubleTap(MotionEvent e) {
            if (controller == null) return false;
            long delta = e.getX() < playerView.getWidth() / 2f
                    ? -DOUBLE_TAP_SEEK_MS : DOUBLE_TAP_SEEK_MS;
            long duration = controller.getDuration();
            long target = Math.max(0L, controller.getCurrentPosition() + delta);
            if (duration > 0) target = Math.min(duration, target);
            controller.seekTo(target);
            return true;
        }

        @Override
        public boolean onScroll(MotionEvent first, MotionEvent current, float distanceX, float distanceY) {
            if (first == null || current == null || controller == null) return false;
            float dx = current.getX() - first.getX();
            float dy = current.getY() - first.getY();
            if (Math.abs(dx) > Math.abs(dy)) {
                long duration = controller.getDuration();
                if (duration <= 0) return true;
                long delta = (long) ((dx / Math.max(1f, playerView.getWidth())) * 120_000L);
                controller.seekTo(Math.max(0L, Math.min(duration, controller.getCurrentPosition() + delta)));
                return true;
            }
            float fraction = -dy / Math.max(1f, playerView.getHeight());
            if (first.getX() < playerView.getWidth() / 2f) {
                WindowManager.LayoutParams attrs = getWindow().getAttributes();
                attrs.screenBrightness = clamp01(gestureStartBrightness + fraction);
                getWindow().setAttributes(attrs);
            } else if (audioManager != null) {
                int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                int target = Math.max(0, Math.min(max, gestureStartVolume + Math.round(fraction * max)));
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
            }
            return true;
        }
    }

    private static float clamp01(float value) {
        return Math.max(0.01f, Math.min(1f, value));
    }
}
