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
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Color;
import android.graphics.drawable.Icon;
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
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** A small, standalone Android browser. Chromium overlay experiments remain separate. */
public class BrowserActivity extends Activity {
    private static final String HOME_URL = "https://duckduckgo.com/";
    private static final int FILE_CHOOSER_REQUEST = 4101;
    private static final int STORAGE_PERMISSION_REQUEST = 4102;
    private static final int WEB_PERMISSION_REQUEST = 4103;
    private static final int MAX_RESTORED_TABS = 12;
    static final String EXTRA_WEB_APP_MODE = "web_app_mode";

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
    private AutoDnsManager autoDnsManager;
    private boolean webAppMode;
    private boolean initialTabsOpened;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.parseColor("#0B1117"));
        getWindow().setNavigationBarColor(Color.parseColor("#0B1117"));
        webAppMode = getIntent() != null && getIntent().getBooleanExtra(EXTRA_WEB_APP_MODE, false);
        preferences = getSharedPreferences("nautrix", MODE_PRIVATE);
        adBlockEngine = new AdBlockEngine(this);
        adBlockEngine.initialize();
        autoDnsManager = AutoDnsManager.get(this);
        boolean debuggable = (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        WebView.setWebContentsDebuggingEnabled(debuggable);
        buildInterface();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            WebView.startSafeBrowsing(this, ignored -> { });
        }

        autoDnsManager.installWebViewProxy(() -> openInitialTabs(savedInstanceState));
        autoDnsManager.benchmarkAsync(false, null);
    }

    private void openInitialTabs(Bundle savedInstanceState) {
        if (initialTabsOpened || isFinishing() || isDestroyed()) return;
        initialTabsOpened = true;
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
        toolbar.addView(actionButton("⇩", "Baixar mídia detectada",
                view -> showMediaDownloadPicker()));
        root.addView(toolbar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));
        if (webAppMode) toolbar.setVisibility(View.GONE);

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
        if (webAppMode) navigation.setVisibility(View.GONE);

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
        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, length) -> {
            String referer = webView.getUrl();
            if (isTorrentSource(url, mimeType)) {
                confirmRemoteTorrent(url, userAgent, referer);
            } else {
                beginDownload(new PendingDownload(
                        url, userAgent, contentDisposition, mimeType, referer));
            }
        });
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
        menu.getMenu().add("Downloads");
        menu.getMenu().add("Abrir vídeo no player");
        menu.getMenu().add("Vídeos em cache");
        menu.getMenu().add("Instalar página como app");
        menu.getMenu().add("DNS automático");
        menu.getMenu().add("Limpar cache de vídeos");
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
            else if ("Downloads".equals(title)) openDownloadManager();
            else if ("Abrir vídeo no player".equals(title)) openVideoPlayer();
            else if ("Vídeos em cache".equals(title)) showCachedVideos();
            else if ("Instalar página como app".equals(title)) installCurrentSite();
            else if ("DNS automático".equals(title)) showAutoDnsPanel();
            else if ("Limpar cache de vídeos".equals(title)) clearVideoCache();
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

    private void openDownloadManager() {
        startActivity(new Intent(this, DownloadManagerActivity.class));
    }

    private void showMediaDownloadPicker() {
        BrowserTab tab = currentTab();
        WebView webView = tab.webView;
        String script = "(function(){var out=[];var add=function(u,explicit){try{"
                + "var a=new URL(u,location.href).href;if(/^https:\\/\\//i.test(a)"
                + "&&(explicit||/\\.(mp4|webm|m4v|mov|m3u8|mpd)([?#]|$)/i.test(a)))out.push(a);"
                + "}catch(e){}};document.querySelectorAll('video,video source').forEach(function(v){"
                + "add(v.currentSrc,true);add(v.src,true);"
                + "add(v.getAttribute&&v.getAttribute('src'),true);});"
                + "document.querySelectorAll(\"meta[property='og:video'],meta[property='og:video:url'],"
                + "meta[name='twitter:player:stream'],a[href]\").forEach(function(v){"
                + "add(v.content||v.href,!!v.content);});try{performance.getEntriesByType('resource')"
                + ".forEach(function(v){add(v.name,false);});}catch(e){}"
                + "return JSON.stringify(Array.from(new Set(out)).slice(0,30));})()";
        webView.evaluateJavascript(script, rawResult -> {
            String encoded = decodeJavascriptResult(rawResult);
            try {
                JSONArray found = new JSONArray(encoded.isEmpty() ? "[]" : encoded);
                for (int index = 0; index < found.length(); index++) {
                    tab.rememberMedia(found.optString(index, ""));
                }
            } catch (Exception ignored) {
            }
            List<String> candidates = tab.mediaSnapshot();
            if (candidates.isEmpty()) {
                new AlertDialog.Builder(this)
                        .setTitle("Baixar mídia")
                        .setMessage("Nenhuma fonte direta de vídeo foi detectada. Inicie o vídeo "
                                + "na página e tente novamente. DRM e fontes blob protegidas não "
                                + "podem ser extraídos pelo Nautrix.")
                        .setPositiveButton("Abrir downloads",
                                (dialog, which) -> openDownloadManager())
                        .setNegativeButton("Fechar", null)
                        .show();
                return;
            }
            String[] labels = new String[candidates.size()];
            for (int index = 0; index < candidates.size(); index++) {
                labels[index] = mediaLabel(candidates.get(index));
            }
            new AlertDialog.Builder(this)
                    .setTitle("Mídia detectada")
                    .setMessage("Escolha uma fonte exposta pela página. Conteúdo protegido não é contornado.")
                    .setItems(labels, (dialog, which) -> handleMediaCandidate(candidates.get(which)))
                    .setPositiveButton("Downloads", (dialog, which) -> openDownloadManager())
                    .setNegativeButton("Cancelar", null)
                    .show();
        });
    }

    private String mediaLabel(String url) {
        Uri uri = Uri.parse(url);
        String host = uri.getHost() == null ? "mídia" : uri.getHost();
        String path = uri.getLastPathSegment();
        if (path == null || path.isEmpty()) path = "stream";
        if (path.length() > 54) path = path.substring(0, 54) + "…";
        return (isAdaptiveVideoUrl(url) ? "Stream HLS/DASH" : "Vídeo direto")
                + "\n" + host + " • " + path;
    }

    private void handleMediaCandidate(String url) {
        WebView webView = currentWebView();
        String referer = webView.getUrl();
        String userAgent = webView.getSettings().getUserAgentString();
        if (isAdaptiveVideoUrl(url)) {
            new AlertDialog.Builder(this)
                    .setTitle("Stream adaptativo")
                    .setMessage("HLS/DASH usa muitos segmentos e pode ter áudio e vídeo separados. "
                            + "O player do Nautrix armazenará no cache offline os trechos recebidos; "
                            + "não será criado um arquivo incompleto fingindo ser vídeo.")
                    .setPositiveButton("Abrir no player",
                            (dialog, which) -> launchVideoPlayer(url, referer, userAgent))
                    .setNegativeButton("Cancelar", null)
                    .show();
            return;
        }
        String mime = mimeForVideoUrl(url);
        new AlertDialog.Builder(this)
                .setTitle("Baixar vídeo?")
                .setMessage(mediaLabel(url) + "\n\nO arquivo será salvo em Downloads/Nautrix.")
                .setPositiveButton("Baixar", (dialog, which) -> beginDownload(
                        new PendingDownload(url, userAgent, null, mime, referer)))
                .setNeutralButton("Abrir no player",
                        (dialog, which) -> launchVideoPlayer(url, referer, userAgent))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void confirmRemoteTorrent(String url, String userAgent, String referer) {
        new AlertDialog.Builder(this)
                .setTitle("Adicionar torrent?")
                .setMessage("O arquivo .torrent será aberto no gerenciador interno. Baixe somente "
                        + "conteúdo que você tem autorização para usar.")
                .setPositiveButton("Adicionar", (dialog, which) -> {
                    String cookie = CookieManager.getInstance().getCookie(url);
                    TorrentService.addTorrentUrl(this, url, userAgent, cookie, referer);
                    openDownloadManager();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void confirmMagnet(String magnet) {
        new AlertDialog.Builder(this)
                .setTitle("Adicionar magnet?")
                .setMessage("O torrent será baixado pelo gerenciador interno. Use somente conteúdo autorizado.")
                .setPositiveButton("Adicionar", (dialog, which) -> {
                    try {
                        TorrentService.addMagnet(this, magnet);
                        openDownloadManager();
                    } catch (Exception error) {
                        Toast.makeText(this, "Magnet inválido", Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void openVideoPlayer() {
        BrowserTab tab = currentTab();
        WebView webView = tab.webView;
        String pageUrl = webView.getUrl();
        if (isLikelyVideoUrl(pageUrl)) {
            launchVideoPlayer(pageUrl, pageUrl, webView.getSettings().getUserAgentString());
            return;
        }

        String script = "(function(){var v=document.querySelector('video');"
                + "if(!v)return '';var u=v.currentSrc||v.src||'';"
                + "if(!u){var s=v.querySelector('source[src]');u=s?s.src:'';}"
                + "return u||'';})()";
        webView.evaluateJavascript(script, rawResult -> {
            String discovered = decodeJavascriptResult(rawResult);
            if (discovered == null || discovered.isEmpty() || discovered.startsWith("blob:")) {
                discovered = tab.lastMediaUrl();
            }
            if (discovered == null || discovered.isEmpty()) {
                Toast.makeText(this,
                        "Nenhum vídeo compatível foi encontrado nesta página",
                        Toast.LENGTH_LONG).show();
                return;
            }
            launchVideoPlayer(discovered, pageUrl, webView.getSettings().getUserAgentString());
        });
    }

    private void launchVideoPlayer(String videoUrl, String referer, String userAgent) {
        Uri videoUri;
        try {
            videoUri = Uri.parse(videoUrl);
        } catch (Exception error) {
            videoUri = null;
        }
        if (videoUri == null || !"https".equalsIgnoreCase(videoUri.getScheme())) {
            Toast.makeText(this, "Este vídeo não oferece uma fonte HTTPS compatível",
                    Toast.LENGTH_LONG).show();
            return;
        }
        String cookie = CookieManager.getInstance().getCookie(videoUrl);
        if ((cookie == null || cookie.isEmpty()) && referer != null) {
            cookie = CookieManager.getInstance().getCookie(referer);
        }
        String title = currentIndex >= 0 && currentIndex < tabs.size()
                ? currentTab().title : "Vídeo";
        VideoHistory.remember(this, videoUrl, referer, userAgent, title);
        startActivity(VideoPlayerActivity.createIntent(
                this, videoUrl, referer, userAgent, cookie));
    }

    private void showCachedVideos() {
        java.util.List<VideoHistory.Entry> entries = VideoHistory.list(this);
        if (entries.isEmpty()) {
            Toast.makeText(this, "Nenhum vídeo passou pelo cache ainda",
                    Toast.LENGTH_LONG).show();
            return;
        }
        String[] labels = new String[entries.size()];
        for (int index = 0; index < entries.size(); index++) {
            VideoHistory.Entry entry = entries.get(index);
            labels[index] = entry.title + "\n" + Uri.parse(entry.url).getHost();
        }
        new AlertDialog.Builder(this)
                .setTitle("Vídeos em cache")
                .setMessage("Offline, somente os trechos que já foram carregados podem tocar.")
                .setItems(labels, (dialog, which) -> {
                    VideoHistory.Entry entry = entries.get(which);
                    String storedCookie = CookieManager.getInstance().getCookie(entry.url);
                    startActivity(VideoPlayerActivity.createIntent(this, entry.url,
                            entry.referer, entry.userAgent, storedCookie));
                })
                .setNegativeButton("Fechar", null)
                .show();
    }

    private void installCurrentSite() {
        String url = currentWebView().getUrl();
        if (url == null || !url.startsWith("https://")) {
            Toast.makeText(this, "Somente páginas HTTPS podem ser instaladas",
                    Toast.LENGTH_LONG).show();
            return;
        }
        ShortcutManager manager = getSystemService(ShortcutManager.class);
        if (manager == null || !manager.isRequestPinShortcutSupported()) {
            Toast.makeText(this, "A tela inicial deste aparelho não aceita instalação de sites",
                    Toast.LENGTH_LONG).show();
            return;
        }
        String pageTitle = currentTab().title;
        if (pageTitle == null || pageTitle.trim().isEmpty()) {
            String host = Uri.parse(url).getHost();
            pageTitle = host == null ? "Site Nautrix" : host;
        }
        pageTitle = pageTitle.trim();
        if (pageTitle.length() > 36) pageTitle = pageTitle.substring(0, 36);
        Intent launch = new Intent(this, InstalledSiteActivity.class)
                .setAction(Intent.ACTION_VIEW)
                .setData(Uri.parse(url))
                .putExtra(EXTRA_WEB_APP_MODE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        ShortcutInfo shortcut = new ShortcutInfo.Builder(
                this, "nautrix_site_" + Integer.toHexString(url.hashCode()))
                .setShortLabel(pageTitle)
                .setLongLabel("Abrir " + pageTitle + " no Nautrix")
                .setIcon(Icon.createWithResource(this, R.drawable.ic_installed_site))
                .setIntent(launch)
                .build();
        boolean requested = manager.requestPinShortcut(shortcut, null);
        Toast.makeText(this, requested
                        ? "Confirme a instalação na tela inicial"
                        : "Não foi possível solicitar a instalação",
                Toast.LENGTH_LONG).show();
    }

    private void clearVideoCache() {
        VideoHistory.clear(this);
        VideoCache.get(this).clearAsync(() -> runOnUiThread(() ->
                Toast.makeText(this, "Cache de vídeos apagado", Toast.LENGTH_SHORT).show()));
        Toast.makeText(this, "Limpando cache de vídeos…", Toast.LENGTH_SHORT).show();
    }

    private void showAutoDnsPanel() {
        new AlertDialog.Builder(this)
                .setTitle("DNS automático")
                .setMessage(autoDnsManager.status() + "\n\n"
                        + "O Nautrix testa 20 servidores com três consultas e combina "
                        + "latência, variação e falhas. O DNS do sistema é usado como fallback.")
                .setPositiveButton("Otimizar agora", (dialog, which) -> {
                    Toast.makeText(this, "Testando servidores DNS…", Toast.LENGTH_SHORT).show();
                    autoDnsManager.benchmarkAsync(true, () -> Toast.makeText(
                            this, autoDnsManager.status(), Toast.LENGTH_LONG).show());
                })
                .setNegativeButton("Fechar", null)
                .show();
    }

    private static String decodeJavascriptResult(String rawResult) {
        if (rawResult == null || "null".equals(rawResult) || "undefined".equals(rawResult)) return "";
        try {
            return new JSONArray("[" + rawResult + "]").optString(0, "");
        } catch (Exception ignored) {
            return "";
        }
    }

    private static boolean isLikelyVideoUrl(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("https://") && (lower.contains(".m3u8")
                || lower.contains(".mpd") || lower.contains(".mp4")
                || lower.contains(".webm") || lower.contains(".m4v")
                || lower.contains(".mov"));
    }

    private static boolean isAdaptiveVideoUrl(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        return lower.contains(".m3u8") || lower.contains(".mpd");
    }

    private static boolean isTorrentSource(String url, String mimeType) {
        String lowerUrl = url == null ? "" : url.toLowerCase(java.util.Locale.ROOT);
        String lowerMime = mimeType == null ? "" : mimeType.toLowerCase(java.util.Locale.ROOT);
        return lowerUrl.contains(".torrent") || lowerMime.contains("bittorrent");
    }

    private static String mimeForVideoUrl(String url) {
        String lower = url == null ? "" : url.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains(".webm")) return "video/webm";
        if (lower.contains(".mov")) return "video/quicktime";
        if (lower.contains(".m4v")) return "video/x-m4v";
        if (lower.contains(".mp4")) return "video/mp4";
        return null;
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
            DownloadRegistry.enqueue(this, download.url, download.userAgent,
                    download.contentDisposition, download.mimeType, download.referer);
            Toast.makeText(this, "Download iniciado • acompanhe em Downloads",
                    Toast.LENGTH_SHORT).show();
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
            if ("magnet".equalsIgnoreCase(scheme)) {
                confirmMagnet(uri.toString());
                return true;
            }
            if ("mailto".equalsIgnoreCase(scheme) || "tel".equalsIgnoreCase(scheme)
                    || "market".equalsIgnoreCase(scheme)) {
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
            String requestUrl = request.getUrl().toString();
            if (isLikelyVideoUrl(requestUrl)) tab.rememberMedia(requestUrl);
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
            tab.clearMedia();
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
            if (webAppMode && isCurrent(tab) && title != null) setTitle(title);
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
        final LinkedHashSet<String> mediaUrls = new LinkedHashSet<>();
        final AtomicInteger blockedRequests = new AtomicInteger();

        BrowserTab(WebView webView) {
            this.webView = webView;
        }

        synchronized void rememberMedia(String url) {
            if (url == null || !url.startsWith("https://") || url.startsWith("blob:")) return;
            mediaUrls.remove(url);
            mediaUrls.add(url);
            while (mediaUrls.size() > 30) {
                mediaUrls.remove(mediaUrls.iterator().next());
            }
        }

        synchronized void clearMedia() {
            mediaUrls.clear();
        }

        synchronized List<String> mediaSnapshot() {
            ArrayList<String> result = new ArrayList<>(mediaUrls);
            Collections.reverse(result);
            return result;
        }

        synchronized String lastMediaUrl() {
            String last = null;
            for (String value : mediaUrls) last = value;
            return last;
        }
    }

    private static final class PendingDownload {
        final String url;
        final String userAgent;
        final String contentDisposition;
        final String mimeType;
        final String referer;

        PendingDownload(String url, String userAgent, String contentDisposition, String mimeType,
                        String referer) {
            this.url = url;
            this.userAgent = userAgent;
            this.contentDisposition = contentDisposition;
            this.mimeType = mimeType;
            this.referer = referer;
        }
    }
}
