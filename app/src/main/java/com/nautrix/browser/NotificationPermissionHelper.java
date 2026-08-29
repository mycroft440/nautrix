package com.nautrix.browser;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

/** Requests notification access once, and only after a user starts a long-running transfer. */
final class NotificationPermissionHelper {
    private static final String PREFS = "nautrix_notifications";
    private static final String KEY_REQUESTED = "requested_v1";
    private static final int REQUEST_CODE = 4701;

    private NotificationPermissionHelper() { }

    static void requestForTransfer(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        boolean requested = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_REQUESTED, false);
        if (requested) return;
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_REQUESTED, true).apply();
        activity.requestPermissions(
                new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_CODE);
    }
}
