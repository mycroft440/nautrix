package com.nautrix.browser;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Message;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.SafeBrowsingResponse;
import android.webkit.SslErrorHandler;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** A small, standalone Android browser. Chromium overlay experiments remain separate. */
public final class BrowserActivity extends Activity {
    private static final String HOME_URL = "https://duckduckgo.com/";
    private static final int FILE_CHOOSER_REQUEST = 4101;
    private static final int STORAGE_PERMISSION_REQUEST = 4102;
    private static final int WEB_PERMISSION_REQUEST = 4103;
    private static final int MAX_RESTORED_TABS = 12;

    private final ArrayList<BrowserTab> tabs = new ArrayList<>();
    private FrameLayout browserHost;
    private EditText addressBar;
    private ProgressBar progressBar;
    private TextView tabCounter;
    private TextView shieldCounter;
    private int currentIndex = -1;
    private ValueCallback<Uri[]> fileChooserCallback;
    private PermissionRequest pendingWebPermission;
    private String[] pendingWebResources;
    private PendingDownload pendingDownload;
    private SharedPreferences preferences;
    private AdBlockEngine adBlockEngine;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.parseColor("#0B1117"));
        getWindow().setNavigationBarColor(Color.parseColor("#0B1117"));
        preferences = getSharedPreferences("nautrix", MODE_PRIVATE);
        adBlockEngine = new AdBlockEngine(this);
        adBlockEngine.initialize();
        boolean debuggable = (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        WebView.setWebContentsDebuggingEnabled(debuggable);
        buildInterface();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            WebView.startSafeBrowsing(this, ignored -> { });
        }

        Uri requested = getIntent() == null ? null : getIntent().getData();
        if (requested != null && "https".equalsIgnoreCase(requested.getScheme())) {
            createTab(requested.toString(), true);
        } else if (!restoreSession()) {
            createTab(HOME_URL, true);
        }
    }

    private void buildInterface() {
        int background = Color.parseColor("#0B1117");
        int surface = Color.parseColor("#131C24");
        int field = Color.parseColor("#1D2A35");
        int text = Color.parseColor("#F3F7FA");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(background);

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(4), dp(4), dp(4), dp(4));
        toolbar.setBackgroundColor(surface);
        toolbar.addView(actionButton("‹", "Voltar", view -> navigateBack()));
        toolbar.addView(actionButton("›", "Avançar", view -> navigateForward()));
        toolbar.addView(actionButton("↻", "Recarregar", view -> reload()));

        addressBar = new EditText(this);
        addressBar.setSingleLine(true);
        addressBar.setTextColor(text);
        addressBar.setHintTextColor(Color.parseColor("#8FA3B3"));
        addressBar.setHint("Pesquisar ou digitar endereço");
        addressBar.setTextSize(15f);
        addressBar.setSelectAllOnFocus(true);
        addressBar.setBackgroundColor(field);
        addressBar.setPadding(dp(12), 0, dp(12), 0);
        addressBar.setImeOptions(EditorInfo.IME_ACTION_GO);
        addressBar.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        addressBar.setOnEditorActionListener((view, actionId, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_GO || enter) {
                loadAddressBar();
                return true;
            }
            return false;
        });
        LinearLayout.LayoutParams addressParams = new LinearLayout.LayoutParams(
                0, dp(44), 1f);
        addressParams.setMargins(dp(4), 0, dp(4), 0);
        toolbar.addView(addressBar, addressParams);
        toolbar.addView(actionButton("→", "Abrir", view -> loadAddressBar()));
        root.addView(toolbar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setVisibility(View.GONE);
        root.addView(progressBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        browserHost = new FrameLayout(this);
        root.addView(browserHost, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout navigation = new LinearLayout(this);
        navigation.setGravity(Gravity.CENTER);
        navigation.setBackgroundColor(surface);
        navigation.addView(bottomButton("⌂", "Início", view -> currentWebView().loadUrl(HOME_URL)));
        shieldCounter = bottomButton("🛡 0", "Bloqueador de anúncios", view -> showAdBlockPanel());
        navigation.addView(shieldCounter);
        navigation.addView(bottomButton("＋", "Nova aba", view -> createTab(HOME_URL, true)));
        tabCounter = bottomButton("1", "Abas", view -> showTabSwitcher());
        tabCounter.setOnLongClickListener(view -> {
            createTab(HOME_URL, true);
            return true;
        });
        navigation.addView(tabCounter);
        navigation.addView(bottomButton("⋮", "Menu", this::showMenu));
        root.addView(navigation, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        setContentView(root);
    }

    private Button actionButton(String label, String description, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(23f);
        button.setTextColor(Color.parseColor("#F3F7FA"));
        button.setContentDescription(description);
        button.setAllCaps(false);
        button.setPadding(0, 0, 0, 0);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setOnClickListener(listener);
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(42), dp(44)));
        return button;
    }

    private TextView bottomButton(String label, String description, View.OnClickListener listener) {
        TextView button = new TextView(this);
        button.setText(label);
        button.setTextSize(22f);
        button.setTextColor(Color.parseColor("#F3F7FA"));
        button.setContentDescription(description);
        button.setGravity(Gravity.CENTER);
        button.setOnClickListener(listener);
        button.setLayoutParams(new LinearLayout.LayoutParams(0, dp(52), 1f));
        return button;
    }

    private void createTab(String url, boolean select) {
        WebView webView = new WebView(this);
        BrowserTab tab = new BrowserTab(webView);
        configureWebView(tab);
        tabs.add(tab);
        if (select) selectTab(tabs.size() - 1);
        webView.loadUrl(UrlResolver.resolve(url));
        updateTabCounter();
    }

    private void configureWebView(BrowserTab tab) {
        WebView webView = tab.webView;
        webView.setBackgroundColor(Color.parseColor("#0B1117"));
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setSupportZoom(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            settings.setAlgorithmicDarkeningAllowed(true);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            settings.setForceDark(WebSettings.FORCE_DARK_AUTO);
        }

        webView.removeJavascriptInterface("searchBoxJavaBridge_");
        webView.removeJavascriptInterface("accessibility");
        webView.removeJavascriptInterface("accessibilityTraversal");
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false);
        webView.setWebViewClient(new NautrixWebViewClient(tab));
        webView.setWebChromeClient(new NautrixChromeClient(tab));
        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, length) ->
                beginDownload(new PendingDownload(url, userAgent, contentDisposition, mimeType)));
    }

    private void selectTab(int index) {
        if (index < 0 || index >= tabs.size()) return;
        currentIndex = index;
        browserHost.removeAllViews();
        WebView webView = tabs.get(index).webView;
        if (webView.getParent() instanceof ViewGroup) {
            ((ViewGroup) webView.getParent()).removeView(webView);
        }
        browserHost.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        updateAddress(webView.getUrl());
        updateTabCounter();
        updateShieldCounter(tabs.get(index));
    }

    private void closeCurrentTab() {
        if (tabs.isEmpty()) return;
        BrowserTab removed = tabs.remove(currentIndex);
        browserHost.removeView(removed.webView);
        removed.webView.stopLoading();
        removed.webView.destroy();
        if (tabs.isEmpty()) {
            currentIndex = -1;
            createTab(HOME_URL, true);
        } else {
            selectTab(Math.min(currentIndex, tabs.size() - 1));
        }
    }

    private void showTabSwitcher() {
        if (tabs.isEmpty()) return;
        String[] labels = new String[tabs.size()];
        for (int i = 0; i < tabs.size(); i++) {
            String title = tabs.get(i).title;
            labels[i] = (i == currentIndex ? "• " : "")
                    + ((title == null || title.trim().isEmpty()) ? "Nova aba" : title);
        }
        new AlertDialog.Builder(this)
                .setTitle("Abas abertas")
                .setItems(labels, (dialog, which) -> selectTab(which))
                .setPositiveButton("Nova aba", (dialog, which) -> createTab(HOME_URL, true))
                .setNegativeButton("Fechar atual", (dialog, which) -> closeCurrentTab())
                .show();
    }

    private void showMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("Nova aba");
        menu.getMenu().add("Fechar aba");
        menu.getMenu().add("Adicionar favorito");
        menu.getMenu().add("Favoritos");
        menu.getMenu().add("Compartilhar");
        menu.getMenu().add(currentTab().desktop ? "Usar versão móvel" : "Versão para computador");
        menu.getMenu().add("Abrir no aplicativo externo");
        menu.getMenu().add("Limpar dados de navegação");
        menu.setOnMenuItemClickListener(item -> {
            String title = item.getTitle().toString();
            if ("Nova aba".equals(title)) createTab(HOME_URL, true);
            else if ("Fechar aba".equals(title)) closeCurrentTab();
            else if ("Adicionar favorito".equals(title)) addBookmark();
            else if ("Favoritos".equals(title)) showBookmarks();
            else if ("Compartilhar".equals(title)) sharePage();
            else if (title.contains("versão")) toggleDesktopMode();
            else if ("Abrir no aplicativo externo".equals(title)) openExternally();
            else if ("Limpar dados de navegação".equals(title)) confirmClearData();
            return true;
        });
        menu.show();
    }

    private void showAdBlockPanel() {
        BrowserTab tab = currentTab();
        String url = tab.webView.getUrl();
        boolean enabled = adBlockEngine.isEnabledForUrl(url);
        String engineState = adBlockEngine.isNativeReady()
                ? "motor do Brave carregado" : "proteção básica ativa; listas carregando";
        new AlertDialog.Builder(this)
                .setTitle("Bloqueador de anúncios")
                .setMessage((enabled ? "Ativo" : "Desativado") + " neste site\n"
                        + tab.blockedRequests.get() + " solicitações bloqueadas nesta aba\n"
                        + engineState)
                .setPositiveButton(enabled ? "Desativar neste site" : "Ativar neste site",
                        (dialog, which) -> {
                            adBlockEngine.setEnabledForUrl(url, !enabled);
                            tab.blockedRequests.set(0);
                            updateShieldCounter(tab);
                            tab.webView.reload();
                        })
                .setNegativeButton("Fechar", null)
                .show();
    }

    private void loadAddressBar() {
        currentWebView().loadUrl(UrlResolver.resolve(addressBar.getText().toString()));
        addressBar.clearFocus();
        InputMethodManager input = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (input != null) input.hideSoftInputFromWindow(addressBar.getWindowToken(), 0);
    }

    private void navigateBack() {
        WebView webView = currentWebView();
        if (webView.canGoBack()) webView.goBack();
    }

    private void navigateForward() {
        WebView webView = currentWebView();
        if (webView.canGoForward()) webView.goForward();
    }

    private void reload() {
        currentWebView().reload();
    }

    private void toggleDesktopMode() {
        BrowserTab tab = currentTab();
        tab.desktop = !tab.desktop;
        WebSettings settings = tab.webView.getSettings();
        if (tab.desktop) {
            settings.setUserAgentString("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36");
            settings.setUseWideViewPort(true);
            settings.setLoadWithOverviewMode(true);
        } else {
            settings.setUserAgentString(null);
        }
        tab.webView.reload();
    }

    private void addBookmark() {
        String url = currentWebView().getUrl();
        if (url == null || !url.startsWith("https://")) return;
        Set<String> bookmarks = new HashSet<>(preferences.getStringSet(
                "bookmarks", Collections.emptySet()));
        bookmarks.add(url);
        preferences.edit().putStringSet("bookmarks", bookmarks).apply();
        Toast.makeText(this, "Favorito adicionado", Toast.LENGTH_SHORT).show();
    }

    private void showBookmarks() {
        Set<String> stored = preferences.getStringSet("bookmarks", Collections.emptySet());
        if (stored.isEmpty()) {
            Toast.makeText(this, "Nenhum favorito salvo", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] bookmarks = stored.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle("Favoritos")
                .setItems(bookmarks, (dialog, which) -> currentWebView().loadUrl(bookmarks[which]))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void sharePage() {
        String url = currentWebView().getUrl();
        if (url == null) return;
        Intent share = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, url);
        startActivity(Intent.createChooser(share, "Compartilhar página"));
    }

    private void openExternally() {
        String url = currentWebView().getUrl();
        if (url == null) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception error) {
            Toast.makeText(this, "Nenhum aplicativo disponível", Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmClearData() {
        new AlertDialog.Builder(this)
                .setTitle("Limpar dados de navegação?")
                .setMessage("Cookies, cache, histórico das abas e sessão serão apagados. Favoritos serão mantidos.")
                .setPositiveButton("Limpar", (dialog, which) -> clearBrowsingData())
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void clearBrowsingData() {
        CookieManager.getInstance().removeAllCookies(null);
        CookieManager.getInstance().flush();
        WebStorage.getInstance().deleteAllData();
        for (BrowserTab tab : tabs) {
            tab.webView.clearCache(true);
            tab.webView.clearHistory();
            tab.webView.clearFormData();
        }
        preferences.edit().remove("session_tabs").apply();
        Toast.makeText(this, "Dados apagados", Toast.LENGTH_SHORT).show();
    }

    private void beginDownload(PendingDownload download) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            pendingDownload = download;
            requestPermissions(new String[] {Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    STORAGE_PERMISSION_REQUEST);
            return;
        }
        enqueueDownload(download);
    }

    private void enqueueDownload(PendingDownload download) {
        try {
            Uri uri = Uri.parse(download.url);
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalArgumentException("Only HTTPS downloads are accepted");
            }
            String name = URLUtil.guessFileName(download.url, download.contentDisposition,
                    download.mimeType);
            DownloadManager.Request request = new DownloadManager.Request(uri)
                    .setTitle(name)
                    .setMimeType(download.mimeType)
                    .setNotificationVisibility(
                            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setAllowedOverMetered(true)
                    .setAllowedOverRoaming(false)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            if (download.userAgent != null) request.addRequestHeader("User-Agent", download.userAgent);
            String cookie = CookieManager.getInstance().getCookie(download.url);
            if (cookie != null) request.addRequestHeader("Cookie", cookie);
            DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (manager == null) throw new IllegalStateException("DownloadManager unavailable");
            manager.enqueue(request);
            Toast.makeText(this, "Download iniciado", Toast.LENGTH_SHORT).show();
        } catch (Exception error) {
            Toast.makeText(this, "Não foi possível iniciar o download", Toast.LENGTH_LONG).show();
        }
    }

    private void showFileChooser(ValueCallback<Uri[]> callback,
                                 WebChromeClient.FileChooserParams params) {
        if (fileChooserCallback != null) fileChooserCallback.onReceiveValue(null);
        fileChooserCallback = callback;
        Intent chooser;
        try {
            chooser = params.createIntent();
            chooser.addCategory(Intent.CATEGORY_OPENABLE);
        } catch (Exception ignored) {
            chooser = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*");
        }
        try {
            startActivityForResult(Intent.createChooser(chooser, "Escolher arquivo"),
                    FILE_CHOOSER_REQUEST);
        } catch (Exception error) {
            fileChooserCallback.onReceiveValue(null);
            fileChooserCallback = null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != FILE_CHOOSER_REQUEST || fileChooserCallback == null) return;
        Uri[] result = null;
        if (resultCode == RESULT_OK && data != null) {
            ClipData clip = data.getClipData();
            if (clip != null) {
                result = new Uri[clip.getItemCount()];
                for (int i = 0; i < clip.getItemCount(); i++) result[i] = clip.getItemAt(i).getUri();
            } else if (data.getData() != null) {
                result = new Uri[] {data.getData()};
            }
        }
        fileChooserCallback.onReceiveValue(result);
        fileChooserCallback = null;
    }

    private void requestWebPermission(PermissionRequest request) {
        if (pendingWebPermission != null) pendingWebPermission.deny();
        ArrayList<String> androidPermissions = new ArrayList<>();
        ArrayList<String> resources = new ArrayList<>();
        for (String resource : request.getResources()) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) {
                resources.add(resource);
                if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    androidPermissions.add(Manifest.permission.CAMERA);
                }
            } else if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
                resources.add(resource);
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED) {
                    androidPermissions.add(Manifest.permission.RECORD_AUDIO);
                }
            }
        }
        if (resources.isEmpty()) {
            request.deny();
            return;
        }
        pendingWebPermission = request;
        pendingWebResources = resources.toArray(new String[0]);
        if (!androidPermissions.isEmpty()) {
            requestPermissions(androidPermissions.toArray(new String[0]), WEB_PERMISSION_REQUEST);
        } else {
            confirmWebPermission();
        }
    }

    private void confirmWebPermission() {
        PermissionRequest request = pendingWebPermission;
        String[] resources = pendingWebResources;
        if (request == null || resources == null) return;
        String host = request.getOrigin() == null ? "este site" : request.getOrigin().getHost();
        new AlertDialog.Builder(this)
                .setTitle("Permissão do site")
                .setMessage("Permitir que " + host + " use câmera ou microfone?")
                .setPositiveButton("Permitir", (dialog, which) -> {
                    request.grant(resources);
                    clearPendingWebPermission();
                })
                .setNegativeButton("Bloquear", (dialog, which) -> {
                    request.deny();
                    clearPendingWebPermission();
                })
                .setOnCancelListener(dialog -> {
                    request.deny();
                    clearPendingWebPermission();
                })
                .show();
    }

    private void clearPendingWebPermission() {
        pendingWebPermission = null;
        pendingWebResources = null;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        boolean granted = results.length > 0;
        for (int result : results) granted &= result == PackageManager.PERMISSION_GRANTED;
        if (requestCode == STORAGE_PERMISSION_REQUEST) {
            PendingDownload download = pendingDownload;
            pendingDownload = null;
            if (granted && download != null) enqueueDownload(download);
        } else if (requestCode == WEB_PERMISSION_REQUEST) {
            if (granted) confirmWebPermission();
            else if (pendingWebPermission != null) {
                pendingWebPermission.deny();
                clearPendingWebPermission();
            }
        }
    }

    private boolean restoreSession() {
        String raw = preferences.getString("session_tabs", null);
        if (raw == null) return false;
        try {
            JSONArray saved = new JSONArray(raw);
            int count = Math.min(saved.length(), MAX_RESTORED_TABS);
            for (int i = 0; i < count; i++) {
                JSONObject value = saved.getJSONObject(i);
                String url = value.optString("url", HOME_URL);
                if (url.startsWith("https://") || "about:blank".equals(url)) {
                    createTab(url, false);
                    tabs.get(tabs.size() - 1).desktop = value.optBoolean("desktop", false);
                    if (tabs.get(tabs.size() - 1).desktop) {
                        tabs.get(tabs.size() - 1).webView.getSettings().setUserAgentString(
                                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
                                        + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36");
                    }
                }
            }
            if (tabs.isEmpty()) return false;
            selectTab(Math.max(0, Math.min(
                    preferences.getInt("session_index", 0), tabs.size() - 1)));
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void saveSession() {
        JSONArray saved = new JSONArray();
        for (BrowserTab tab : tabs) {
            String url = tab.webView.getUrl();
            if (url == null || !(url.startsWith("https://") || "about:blank".equals(url))) continue;
            JSONObject value = new JSONObject();
            try {
                value.put("url", url);
                value.put("desktop", tab.desktop);
                saved.put(value);
            } catch (Exception ignored) {
            }
        }
        preferences.edit()
                .putString("session_tabs", saved.toString())
                .putInt("session_index", Math.max(0, currentIndex))
                .apply();
    }

    private void updateAddress(String url) {
        if (url != null && !addressBar.hasFocus()) addressBar.setText(url);
    }

    private void updateTabCounter() {
        if (tabCounter != null) tabCounter.setText(String.valueOf(Math.max(1, tabs.size())));
    }

    private void updateShieldCounter(BrowserTab tab) {
        if (shieldCounter == null || !isCurrent(tab)) return;
        String state = adBlockEngine.isEnabledForUrl(tab.pageUrl) ? "🛡 " : "○ ";
        shieldCounter.setText(state + tab.blockedRequests.get());
    }

    private boolean isCurrent(BrowserTab tab) {
        return currentIndex >= 0 && currentIndex < tabs.size() && tabs.get(currentIndex) == tab;
    }

    private BrowserTab currentTab() {
        if (currentIndex < 0 || currentIndex >= tabs.size()) throw new IllegalStateException("No tab");
        return tabs.get(currentIndex);
    }

    private WebView currentWebView() {
        return currentTab().webView;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        Uri uri = intent == null ? null : intent.getData();
        if (uri != null && "https".equalsIgnoreCase(uri.getScheme())) {
            createTab(uri.toString(), true);
        }
    }

    @Override
    protected void onPause() {
        saveSession();
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        if (!tabs.isEmpty() && currentWebView().canGoBack()) currentWebView().goBack();
        else if (tabs.size() > 1) closeCurrentTab();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (fileChooserCallback != null) fileChooserCallback.onReceiveValue(null);
        if (pendingWebPermission != null) pendingWebPermission.deny();
        browserHost.removeAllViews();
        for (BrowserTab tab : tabs) {
            tab.webView.stopLoading();
            tab.webView.destroy();
        }
        tabs.clear();
        adBlockEngine.close();
        super.onDestroy();
    }

    private final class NautrixWebViewClient extends WebViewClient {
        private final BrowserTab tab;

        NautrixWebViewClient(BrowserTab tab) {
            this.tab = tab;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            String scheme = uri.getScheme();
            if ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme)) return false;
            if ("intent".equalsIgnoreCase(scheme)) {
                try {
                    Intent intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME);
                    if (intent.resolveActivity(getPackageManager()) != null) startActivity(intent);
                    else {
                        String fallback = intent.getStringExtra("browser_fallback_url");
                        if (fallback != null && fallback.startsWith("https://")) view.loadUrl(fallback);
                    }
                } catch (Exception ignored) {
                }
                return true;
            }
            if ("mailto".equalsIgnoreCase(scheme) || "tel".equalsIgnoreCase(scheme)
                    || "market".equalsIgnoreCase(scheme) || "magnet".equalsIgnoreCase(scheme)) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Exception error) {
                    Toast.makeText(BrowserActivity.this, "Nenhum aplicativo disponível",
                            Toast.LENGTH_SHORT).show();
                }
                return true;
            }
            return true;
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            if (adBlockEngine.shouldBlock(request, tab.pageUrl)) {
                tab.blockedRequests.incrementAndGet();
                runOnUiThread(() -> updateShieldCounter(tab));
                return new WebResourceResponse("text/plain", "UTF-8",
                        new ByteArrayInputStream(new byte[0]));
            }
            return super.shouldInterceptRequest(view, request);
        }

        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            tab.pageUrl = url;
            tab.blockedRequests.set(0);
            if (isCurrent(tab)) {
                updateAddress(url);
                progressBar.setVisibility(View.VISIBLE);
                updateShieldCounter(tab);
            }
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            tab.pageUrl = url;
            String cosmeticScript = adBlockEngine.cosmeticScriptFor(url);
            if (cosmeticScript != null) view.evaluateJavascript(cosmeticScript, null);
            if (isCurrent(tab)) {
                updateAddress(url);
                updateShieldCounter(tab);
            }
            saveSession();
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler,
                                       android.net.http.SslError error) {
            handler.cancel();
            if (isCurrent(tab)) {
                Toast.makeText(BrowserActivity.this,
                        "Conexão insegura bloqueada", Toast.LENGTH_LONG).show();
            }
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request,
                                    WebResourceError error) {
            if (request.isForMainFrame() && isCurrent(tab)) {
                Toast.makeText(BrowserActivity.this, "Não foi possível abrir a página",
                        Toast.LENGTH_SHORT).show();
            }
        }

        @Override
        public void onSafeBrowsingHit(WebView view, WebResourceRequest request, int threatType,
                                      SafeBrowsingResponse callback) {
            callback.backToSafety(true);
            if (isCurrent(tab)) {
                Toast.makeText(BrowserActivity.this, "Página perigosa bloqueada",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    private final class NautrixChromeClient extends WebChromeClient {
        private final BrowserTab tab;

        NautrixChromeClient(BrowserTab tab) {
            this.tab = tab;
        }

        @Override
        public void onProgressChanged(WebView view, int progress) {
            if (!isCurrent(tab)) return;
            progressBar.setProgress(progress);
            progressBar.setVisibility(progress >= 100 ? View.GONE : View.VISIBLE);
        }

        @Override
        public void onReceivedTitle(WebView view, String title) {
            tab.title = title;
        }

        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture,
                                      Message resultMsg) {
            if (!isUserGesture || !(resultMsg.obj instanceof WebView.WebViewTransport)) return false;
            createTab("about:blank", true);
            WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
            transport.setWebView(currentWebView());
            resultMsg.sendToTarget();
            return true;
        }

        @Override
        public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> callback,
                                         FileChooserParams params) {
            showFileChooser(callback, params);
            return true;
        }

        @Override
        public void onPermissionRequest(PermissionRequest request) {
            runOnUiThread(() -> requestWebPermission(request));
        }

        @Override
        public void onPermissionRequestCanceled(PermissionRequest request) {
            if (request == pendingWebPermission) clearPendingWebPermission();
        }
    }

    private static final class BrowserTab {
        final WebView webView;
        String title = "Nova aba";
        boolean desktop;
        volatile String pageUrl;
        final AtomicInteger blockedRequests = new AtomicInteger();

        BrowserTab(WebView webView) {
            this.webView = webView;
        }
    }

    private static final class PendingDownload {
        final String url;
        final String userAgent;
        final String contentDisposition;
        final String mimeType;

        PendingDownload(String url, String userAgent, String contentDisposition, String mimeType) {
            this.url = url;
            this.userAgent = userAgent;
            this.contentDisposition = contentDisposition;
            this.mimeType = mimeType;
        }
    }
}
