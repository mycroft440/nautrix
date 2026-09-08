package com.nautrix.browser;

import android.os.Bundle;

/**
 * Entry point used by pinned site shortcuts.
 *
 * This activity is exported so Android launchers can start pinned shortcuts. Because exported
 * activities can also be started explicitly by other apps, never trust the web-app-mode extra
 * supplied by the incoming Intent. Until installed sites are bound to an app-owned shortcut token,
 * shortcuts open with normal browser chrome so an arbitrary external caller cannot create a
 * convincing chromeless phishing surface.
 */
public final class InstalledSiteActivity extends BrowserActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if (getIntent() != null) {
            getIntent().removeExtra(BrowserActivity.EXTRA_WEB_APP_MODE);
        }
        super.onCreate(savedInstanceState);
    }
}
