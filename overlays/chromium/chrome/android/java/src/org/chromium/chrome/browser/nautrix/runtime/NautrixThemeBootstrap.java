package org.chromium.chrome.browser.nautrix.runtime;

import static org.chromium.chrome.browser.preferences.ChromePreferenceKeys.UI_THEME_SETTING;

import android.app.Application;

import org.chromium.base.shared_preferences.SharedPreferencesManager;
import org.chromium.chrome.browser.night_mode.ThemeType;
import org.chromium.chrome.browser.preferences.ChromeSharedPreferences;

/** Applies the Nautrix dark default only when the user has never chosen a theme. */
public final class NautrixThemeBootstrap {
    private NautrixThemeBootstrap() {}

    public static void applyDarkDefault(Application application) {
        SharedPreferencesManager prefs = ChromeSharedPreferences.getInstance();
        if (!prefs.contains(UI_THEME_SETTING)) prefs.writeInt(UI_THEME_SETTING, ThemeType.DARK);
    }
}
