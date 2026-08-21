package org.chromium.chrome.browser.nautrix.media;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.os.Build;
import android.util.Rational;

/** Native Android PiP helper with Android-compatible aspect-ratio bounds. */
public final class NautrixPictureInPictureController {
    private static final double MAX_ASPECT = 2.39;
    private static final double MIN_ASPECT = 1.0 / MAX_ASPECT;

    public static boolean enter(Activity activity, int width, int height) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || width <= 0 || height <= 0) return false;
        double aspect = (double) width / (double) height;
        int safeWidth = width;
        int safeHeight = height;
        if (aspect > MAX_ASPECT) safeWidth = Math.max(1, (int) Math.round(height * MAX_ASPECT));
        else if (aspect < MIN_ASPECT) safeHeight = Math.max(1, (int) Math.round(width / MIN_ASPECT));
        try {
            Rational ratio = new Rational(Math.max(1, safeWidth), Math.max(1, safeHeight));
            PictureInPictureParams params = new PictureInPictureParams.Builder()
                    .setAspectRatio(ratio).build();
            return activity.enterPictureInPictureMode(params);
        } catch (IllegalArgumentException | IllegalStateException ignored) {
            return false;
        }
    }

    private NautrixPictureInPictureController() {}
}
