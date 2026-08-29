package com.nautrix.browser

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Color
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.SafeBrowsingResponse
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.LinkedHashSet
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** A small, standalone Android browser. Chromium overlay experiments remain separate. */
open class BrowserActivity : Activity() {
    companion object {
        private const val HOME_URL = "https://duckduckgo.com/"
        private const val FILE_CHOOSER_REQUEST = 4101
        private const val STORAGE_PERMISSION_REQUEST = 4102
        private const val WEB_PERMISSION_REQUEST = 4103
        private const val MAX_RESTORED_TABS = 12
        const val EXTRA_WEB_APP_MODE = "web_app_mode"

        private fun decodeJavascriptResult(rawResult: String?): String {
            if (rawResult == null || rawResult == "null" || rawResult == "undefined") return ""
            return try {
                JSONArray("[$rawResult]").optString(0, "")
            } catch (_: Exception) {
                ""
            }
        }

        private fun isLikelyVideoUrl(url: String?): Boolean {
            if (url == null) return false
            val lower = url.lowercase(Locale.ROOT)
            return lower.startsWith("https://") && (
                lower.contains(".m3u8") || lower.contains(".mpd") || lower.contains(".mp4") ||
                    lower.contains(".webm") || lower.contains(".m4v") || lower.contains(".mov")
                )
        }

        private fun isAdaptiveVideoUrl(url: String?): Boolean {
            if (url == null) return false
            val lower = url.lowercase(Locale.ROOT)
            return lower.contains(".m3u8") || lower.contains(".mpd")
        }

        private fun isTorrentSource(url: String?, mimeType: String?): Boolean {
            val lowerUrl = url?.lowercase(Locale.ROOT).orEmpty()
            val lowerMime = mimeType?.lowercase(Locale.ROOT).orEmpty()
            return lowerUrl.contains(".torrent") || lowerMime.contains("bittorrent")
        }

        private fun mimeForVideoUrl(url: String?): String? {
            val lower = url?.lowercase(Locale.ROOT).orEmpty()
            return when {
                lower.contains(".webm") -> "video/webm"
                lower.contains(".mov") -> "video/quicktime"
                lower.contains(".m4v") -> "video/x-m4v"
                lower.contains(".mp4") -> "video/mp4"
                else -> null
            }
        }
    }

    private val tabs = ArrayList<BrowserTab>()
    private lateinit var browserHost: FrameLayout
    private lateinit var addressBar: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var tabCounter: TextView
    private lateinit var shieldCounter: TextView
    private var currentIndex = -1
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var pendingWebPermission: PermissionRequest? = null
    private var pendingWebResources: Array<String>? = null
    private var pendingDownload: PendingDownload? = null
    private lateinit var preferences: SharedPreferences
    private lateinit var adBlockEngine: AdBlockEngine
    private lateinit var autoDnsManager: AutoDnsManager
    private var webAppMode = false
    private var initialTabsOpened = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#0B1117")
        window.navigationBarColor = Color.parseColor("#0B1117")
        webAppMode = intent?.getBooleanExtra(EXTRA_WEB_APP_MODE, false) == true
        preferences = getSharedPreferences("nautrix", MODE_PRIVATE)
        adBlockEngine = AdBlockEngine(this).also { it.initialize() }
        autoDnsManager = AutoDnsManager.get(this)
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        WebView.setWebContentsDebuggingEnabled(debuggable)
        buildInterface()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            WebView.startSafeBrowsing(this) { }
        }

        autoDnsManager.installWebViewProxy { openInitialTabs() }
    }

    private fun openInitialTabs() {
        if (initialTabsOpened || isFinishing || isDestroyed) return
        initialTabsOpened = true
        val requested = intent?.data?.toString()?.let {
            NavigationSecurityPolicy.upgradeHttpToHttps(it)
        }
        if (requested != null) {
            createTab(requested, true)
        } else if (!restoreSession()) {
            createTab(HOME_URL, true)
        }
    }

    private fun buildInterface() {
        val background = Color.parseColor("#0B1117")
        val surface = Color.parseColor("#131C24")
        val field = Color.parseColor("#1D2A35")
        val text = Color.parseColor("#F3F7FA")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(background)
        }

        val toolbar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            setBackgroundColor(surface)
            addView(actionButton("‹", "Voltar") { navigateBack() })
            addView(actionButton("›", "Avançar") { navigateForward() })
            addView(actionButton("↻", "Recarregar") { reload() })
        }

        addressBar = EditText(this).apply {
            setSingleLine(true)
            setTextColor(text)
            setHintTextColor(Color.parseColor("#8FA3B3"))
            hint = "Pesquisar ou digitar endereço"
            textSize = 15f
            setSelectAllOnFocus(true)
            setBackgroundColor(field)
            setPadding(dp(12), 0, dp(12), 0)
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
            setOnEditorActionListener { _, actionId, event ->
                val enter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER &&
                    event.action == KeyEvent.ACTION_DOWN
                if (actionId == EditorInfo.IME_ACTION_GO || enter) {
                    loadAddressBar()
                    true
                } else {
                    false
                }
            }
        }
        val addressParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply {
            setMargins(dp(4), 0, dp(4), 0)
        }
        toolbar.addView(addressBar, addressParams)
        toolbar.addView(actionButton("→", "Abrir") { loadAddressBar() })
        toolbar.addView(actionButton("⇩", "Baixar mídia detectada") { showMediaDownloadPicker() })
        root.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)))
        if (webAppMode) toolbar.visibility = View.GONE

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }
        root.addView(progressBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))

        browserHost = FrameLayout(this)
        root.addView(
            browserHost,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )

        val navigation = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(surface)
            addView(bottomButton("⌂", "Início") { currentWebView().loadUrl(HOME_URL) })
        }
        shieldCounter = bottomButton("🛡 0", "Bloqueador de anúncios") { showAdBlockPanel() }
        navigation.addView(shieldCounter)
        navigation.addView(bottomButton("＋", "Nova aba") { createTab(HOME_URL, true) })
        tabCounter = bottomButton("1", "Abas") { showTabSwitcher() }.apply {
            setOnLongClickListener {
                createTab(HOME_URL, true)
                true
            }
        }
        navigation.addView(tabCounter)
        navigation.addView(bottomButton("⋮", "Menu", ::showMenu))
        root.addView(navigation, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        if (webAppMode) navigation.visibility = View.GONE

        setContentView(root)
    }

    private fun actionButton(
        label: String,
        description: String,
        listener: (View) -> Unit,
    ): Button = Button(this).apply {
        text = label
        textSize = 23f
        setTextColor(Color.parseColor("#F3F7FA"))
        contentDescription = description
        isAllCaps = false
        setPadding(0, 0, 0, 0)
        setBackgroundColor(Color.TRANSPARENT)
        setOnClickListener(listener)
        layoutParams = LinearLayout.LayoutParams(dp(42), dp(44))
    }

    private fun bottomButton(
        label: String,
        description: String,
        listener: (View) -> Unit,
    ): TextView = TextView(this).apply {
        text = label
        textSize = 22f
        setTextColor(Color.parseColor("#F3F7FA"))
        contentDescription = description
        gravity = Gravity.CENTER
        setOnClickListener(listener)
        layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f)
    }

    private fun createTab(url: String, select: Boolean) {
        val webView = WebView(this)
        val tab = BrowserTab(webView)
        configureWebView(tab)
        tabs.add(tab)
        if (select) selectTab(tabs.lastIndex)
        webView.loadUrl(UrlResolver.resolve(url))
        updateTabCounter()
    }

    @Suppress("DEPRECATION")
    private fun configureWebView(tab: BrowserTab) {
        val webView = tab.webView
        webView.setBackgroundColor(Color.parseColor("#0B1117"))
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            allowFileAccess = false
            allowContentAccess = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            useWideViewPort = true
            loadWithOverviewMode = true
            mediaPlaybackRequiresUserGesture = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                isAlgorithmicDarkeningAllowed = true
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                forceDark = WebSettings.FORCE_DARK_AUTO
            }
        }

        webView.removeJavascriptInterface("searchBoxJavaBridge_")
        webView.removeJavascriptInterface("accessibility")
        webView.removeJavascriptInterface("accessibilityTraversal")
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)
        webView.webViewClient = NautrixWebViewClient(tab)
        webView.webChromeClient = NautrixChromeClient(tab)
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            val referer = webView.url
            if (isTorrentSource(url, mimeType)) {
                confirmRemoteTorrent(url, userAgent, referer)
            } else {
                beginDownload(PendingDownload(url, userAgent, contentDisposition, mimeType, referer))
            }
        }
    }

    private fun selectTab(index: Int) {
        if (index !in tabs.indices) return
        currentIndex = index
        browserHost.removeAllViews()
        val webView = tabs[index].webView
        (webView.parent as? ViewGroup)?.removeView(webView)
        browserHost.addView(
            webView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        updateAddress(webView.url)
        updateTabCounter()
        updateShieldCounter(tabs[index])
    }

    private fun closeCurrentTab() {
        if (tabs.isEmpty()) return
        val removed = tabs.removeAt(currentIndex)
        browserHost.removeView(removed.webView)
        removed.webView.stopLoading()
        removed.webView.destroy()
        if (tabs.isEmpty()) {
            currentIndex = -1
            createTab(HOME_URL, true)
        } else {
            selectTab(currentIndex.coerceAtMost(tabs.lastIndex))
        }
    }

    private fun showTabSwitcher() {
        if (tabs.isEmpty()) return
        val labels = Array(tabs.size) { index ->
            val title = tabs[index].title
            (if (index == currentIndex) "• " else "") +
                (if (title.isBlank()) "Nova aba" else title)
        }
        AlertDialog.Builder(this)
            .setTitle("Abas abertas")
            .setItems(labels) { _, which -> selectTab(which) }
            .setPositiveButton("Nova aba") { _, _ -> createTab(HOME_URL, true) }
            .setNegativeButton("Fechar atual") { _, _ -> closeCurrentTab() }
            .show()
    }

    private fun showMenu(anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add("Nova aba")
        menu.menu.add("Fechar aba")
        menu.menu.add("Adicionar favorito")
        menu.menu.add("Favoritos")
        menu.menu.add("Compartilhar")
        menu.menu.add("Downloads")
        menu.menu.add("Abrir vídeo no player")
        menu.menu.add("Vídeos em cache")
        menu.menu.add("Instalar página como app")
        menu.menu.add("DNS seguro do Android")
        menu.menu.add("Execução em segundo plano")
        menu.menu.add("Limpar cache de vídeos")
        menu.menu.add(if (currentTab().desktop) "Usar versão móvel" else "Versão para computador")
        menu.menu.add("Abrir no aplicativo externo")
        menu.menu.add("Limpar dados de navegação")
        menu.setOnMenuItemClickListener { item ->
            when (val title = item.title.toString()) {
                "Nova aba" -> createTab(HOME_URL, true)
                "Fechar aba" -> closeCurrentTab()
                "Adicionar favorito" -> addBookmark()
                "Favoritos" -> showBookmarks()
                "Compartilhar" -> sharePage()
                "Downloads" -> openDownloadManager()
                "Abrir vídeo no player" -> openVideoPlayer()
                "Vídeos em cache" -> showCachedVideos()
                "Instalar página como app" -> installCurrentSite()
                "DNS seguro do Android" -> showAutoDnsPanel()
                "Execução em segundo plano" -> {
                    startActivity(Intent(this, PerformanceSetupActivity::class.java))
                }
                "Limpar cache de vídeos" -> clearVideoCache()
                "Abrir no aplicativo externo" -> openExternally()
                "Limpar dados de navegação" -> confirmClearData()
                else -> if (title.contains("versão")) toggleDesktopMode()
            }
            true
        }
        menu.show()
    }

    private fun showAdBlockPanel() {
        val tab = currentTab()
        val url = tab.webView.url
        val enabled = adBlockEngine.isEnabledForUrl(url)
        val engineState = if (adBlockEngine.isNativeReady) {
            "motor do Brave carregado"
        } else {
            "proteção básica ativa; listas carregando"
        }
        AlertDialog.Builder(this)
            .setTitle("Bloqueador de anúncios")
            .setMessage(
                (if (enabled) "Ativo" else "Desativado") + " neste site\n" +
                    "${tab.blockedRequests.get()} solicitações bloqueadas nesta aba\n$engineState",
            )
            .setPositiveButton(if (enabled) "Desativar neste site" else "Ativar neste site") { _, _ ->
                adBlockEngine.setEnabledForUrl(url, !enabled)
                tab.blockedRequests.set(0)
                updateShieldCounter(tab)
                tab.webView.reload()
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun loadAddressBar() {
        currentWebView().loadUrl(UrlResolver.resolve(addressBar.text.toString()))
        addressBar.clearFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(
            addressBar.windowToken,
            0,
        )
    }

    private fun navigateBack() {
        currentWebView().takeIf { it.canGoBack() }?.goBack()
    }

    private fun navigateForward() {
        currentWebView().takeIf { it.canGoForward() }?.goForward()
    }

    private fun reload() = currentWebView().reload()

    private fun toggleDesktopMode() {
        val tab = currentTab()
        tab.desktop = !tab.desktop
        tab.webView.settings.apply {
            if (tab.desktop) {
                userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                useWideViewPort = true
                loadWithOverviewMode = true
            } else {
                userAgentString = null
            }
        }
        tab.webView.reload()
    }

    private fun addBookmark() {
        val url = currentWebView().url ?: return
        if (!url.startsWith("https://")) return
        val bookmarks = HashSet(preferences.getStringSet("bookmarks", emptySet()).orEmpty())
        bookmarks.add(url)
        preferences.edit().putStringSet("bookmarks", bookmarks).apply()
        Toast.makeText(this, "Favorito adicionado", Toast.LENGTH_SHORT).show()
    }

    private fun showBookmarks() {
        val stored = preferences.getStringSet("bookmarks", emptySet()).orEmpty()
        if (stored.isEmpty()) {
            Toast.makeText(this, "Nenhum favorito salvo", Toast.LENGTH_SHORT).show()
            return
        }
        val bookmarks = stored.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Favoritos")
            .setItems(bookmarks) { _, which -> currentWebView().loadUrl(bookmarks[which]) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun sharePage() {
        val url = currentWebView().url ?: return
        val share = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, url)
        startActivity(Intent.createChooser(share, "Compartilhar página"))
    }

    private fun openDownloadManager() {
        startActivity(Intent(this, DownloadManagerActivity::class.java))
    }

    private fun showMediaDownloadPicker() {
        val tab = currentTab()
        val webView = tab.webView
        val script = "(function(){var out=[];var add=function(u,explicit){try{" +
            "var a=new URL(u,location.href).href;if(/^https:\\/\\//i.test(a)" +
            "&&(explicit||/\\.(mp4|webm|m4v|mov|m3u8|mpd)([?#]|$)/i.test(a)))out.push(a);" +
            "}catch(e){}};document.querySelectorAll('video,video source').forEach(function(v){" +
            "add(v.currentSrc,true);add(v.src,true);" +
            "add(v.getAttribute&&v.getAttribute('src'),true);});" +
            "document.querySelectorAll(\"meta[property='og:video'],meta[property='og:video:url']," +
            "meta[name='twitter:player:stream'],a[href]\").forEach(function(v){" +
            "add(v.content||v.href,!!v.content);});try{performance.getEntriesByType('resource')" +
            ".forEach(function(v){add(v.name,false);});}catch(e){}" +
            "return JSON.stringify(Array.from(new Set(out)).slice(0,30));})()"
        webView.evaluateJavascript(script) { rawResult ->
            val encoded = decodeJavascriptResult(rawResult)
            try {
                val found = JSONArray(if (encoded.isEmpty()) "[]" else encoded)
                for (index in 0 until found.length()) {
                    tab.rememberMedia(found.optString(index, ""))
                }
            } catch (_: Exception) {
            }
            val candidates = tab.mediaSnapshot()
            if (candidates.isEmpty()) {
                AlertDialog.Builder(this)
                    .setTitle("Baixar mídia")
                    .setMessage(
                        "Nenhuma fonte direta de vídeo foi detectada. Inicie o vídeo " +
                            "na página e tente novamente. DRM e fontes blob protegidas não " +
                            "podem ser extraídos pelo Nautrix.",
                    )
                    .setPositiveButton("Abrir downloads") { _, _ -> openDownloadManager() }
                    .setNegativeButton("Fechar", null)
                    .show()
                return@evaluateJavascript
            }
            val labels = Array(candidates.size) { index -> mediaLabel(candidates[index]) }
            AlertDialog.Builder(this)
                .setTitle("Mídia detectada")
                .setMessage("Escolha uma fonte exposta pela página. Conteúdo protegido não é contornado.")
                .setItems(labels) { _, which -> handleMediaCandidate(candidates[which]) }
                .setPositiveButton("Downloads") { _, _ -> openDownloadManager() }
                .setNegativeButton("Cancelar", null)
                .show()
        }
    }

    private fun mediaLabel(url: String): String {
        val uri = Uri.parse(url)
        val host = uri.host ?: "mídia"
        var path = uri.lastPathSegment
        if (path.isNullOrEmpty()) path = "stream"
        if (path.length > 54) path = path.substring(0, 54) + "…"
        return (if (isAdaptiveVideoUrl(url)) "Stream HLS/DASH" else "Vídeo direto") +
            "\n$host • $path"
    }

    private fun handleMediaCandidate(url: String) {
        val webView = currentWebView()
        val referer = webView.url
        val userAgent = webView.settings.userAgentString
        if (isAdaptiveVideoUrl(url)) {
            AlertDialog.Builder(this)
                .setTitle("Stream adaptativo")
                .setMessage(
                    "HLS/DASH usa muitos segmentos e pode ter áudio e vídeo separados. " +
                        "O player do Nautrix armazenará no cache offline os trechos recebidos; " +
                        "não será criado um arquivo incompleto fingindo ser vídeo.",
                )
                .setPositiveButton("Abrir no player") { _, _ ->
                    launchVideoPlayer(url, referer, userAgent)
                }
                .setNegativeButton("Cancelar", null)
                .show()
            return
        }
        val mime = mimeForVideoUrl(url)
        AlertDialog.Builder(this)
            .setTitle("Baixar vídeo?")
            .setMessage(mediaLabel(url) + "\n\nO arquivo será salvo em Downloads/Nautrix.")
            .setPositiveButton("Baixar") { _, _ ->
                beginDownload(PendingDownload(url, userAgent, null, mime, referer))
            }
            .setNeutralButton("Abrir no player") { _, _ -> launchVideoPlayer(url, referer, userAgent) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun confirmRemoteTorrent(url: String, userAgent: String?, referer: String?) {
        AlertDialog.Builder(this)
            .setTitle("Adicionar torrent?")
            .setMessage(
                "O arquivo .torrent será aberto no gerenciador interno. Baixe somente " +
                    "conteúdo que você tem autorização para usar.",
            )
            .setPositiveButton("Adicionar") { _, _ ->
                val cookie = CookieManager.getInstance().getCookie(url)
                NotificationPermissionHelper.requestForTransfer(this)
                TorrentService.addTorrentUrl(this, url, userAgent, cookie, referer)
                openDownloadManager()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun confirmMagnet(magnet: String) {
        AlertDialog.Builder(this)
            .setTitle("Adicionar magnet?")
            .setMessage("O torrent será baixado pelo gerenciador interno. Use somente conteúdo autorizado.")
            .setPositiveButton("Adicionar") { _, _ ->
                try {
                    NotificationPermissionHelper.requestForTransfer(this)
                    TorrentService.addMagnet(this, magnet)
                    openDownloadManager()
                } catch (_: Exception) {
                    Toast.makeText(this, "Magnet inválido", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun openVideoPlayer() {
        val tab = currentTab()
        val webView = tab.webView
        val pageUrl = webView.url
        if (isLikelyVideoUrl(pageUrl)) {
            launchVideoPlayer(pageUrl!!, pageUrl, webView.settings.userAgentString)
            return
        }

        val script = "(function(){var v=document.querySelector('video');" +
            "if(!v)return '';var u=v.currentSrc||v.src||'';" +
            "if(!u){var s=v.querySelector('source[src]');u=s?s.src:'';}" +
            "return u||'';})()"
        webView.evaluateJavascript(script) { rawResult ->
            var discovered = decodeJavascriptResult(rawResult)
            if (discovered.isEmpty() || discovered.startsWith("blob:")) {
                discovered = tab.lastMediaUrl().orEmpty()
            }
            if (discovered.isEmpty()) {
                Toast.makeText(
                    this,
                    "Nenhum vídeo compatível foi encontrado nesta página",
                    Toast.LENGTH_LONG,
                ).show()
                return@evaluateJavascript
            }
            launchVideoPlayer(discovered, pageUrl, webView.settings.userAgentString)
        }
    }

    private fun launchVideoPlayer(videoUrl: String, referer: String?, userAgent: String?) {
        val videoUri = try {
            Uri.parse(videoUrl)
        } catch (_: Exception) {
            null
        }
        if (videoUri == null || !videoUri.scheme.equals("https", ignoreCase = true)) {
            Toast.makeText(this, "Este vídeo não oferece uma fonte HTTPS compatível", Toast.LENGTH_LONG).show()
            return
        }
        val cookie = CookieManager.getInstance().getCookie(videoUrl)
        val safeReferer = NavigationSecurityPolicy.originOnly(referer)
        val title = if (currentIndex in tabs.indices) currentTab().title else "Vídeo"
        VideoHistory.remember(this, videoUrl, safeReferer, userAgent, title)
        startActivity(VideoPlayerActivity.createIntent(this, videoUrl, safeReferer, userAgent, cookie))
    }

    private fun showCachedVideos() {
        val entries = VideoHistory.list(this)
        if (entries.isEmpty()) {
            Toast.makeText(this, "Nenhum vídeo passou pelo cache ainda", Toast.LENGTH_LONG).show()
            return
        }
        val labels = Array(entries.size) { index ->
            val entry = entries[index]
            "${entry.title}\n${Uri.parse(entry.url).host}"
        }
        AlertDialog.Builder(this)
            .setTitle("Vídeos em cache")
            .setMessage("Offline, somente os trechos que já foram carregados podem tocar.")
            .setItems(labels) { _, which ->
                val entry = entries[which]
                val storedCookie = CookieManager.getInstance().getCookie(entry.url)
                startActivity(
                    VideoPlayerActivity.createIntent(
                        this,
                        entry.url,
                        entry.referer,
                        entry.userAgent,
                        storedCookie,
                    ),
                )
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun installCurrentSite() {
        val url = currentWebView().url
        if (url == null || !url.startsWith("https://")) {
            Toast.makeText(this, "Somente páginas HTTPS podem ser instaladas", Toast.LENGTH_LONG).show()
            return
        }
        val manager = getSystemService(ShortcutManager::class.java)
        if (manager == null || !manager.isRequestPinShortcutSupported) {
            Toast.makeText(
                this,
                "A tela inicial deste aparelho não aceita instalação de sites",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        var pageTitle = currentTab().title
        if (pageTitle.isBlank()) {
            pageTitle = Uri.parse(url).host ?: "Site Nautrix"
        }
        pageTitle = pageTitle.trim().take(36)
        val launch = Intent(this, InstalledSiteActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse(url))
            .putExtra(EXTRA_WEB_APP_MODE, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val shortcut = ShortcutInfo.Builder(this, "nautrix_site_${Integer.toHexString(url.hashCode())}")
            .setShortLabel(pageTitle)
            .setLongLabel("Abrir $pageTitle no Nautrix")
            .setIcon(Icon.createWithResource(this, R.drawable.ic_installed_site))
            .setIntent(launch)
            .build()
        val requested = manager.requestPinShortcut(shortcut, null)
        Toast.makeText(
            this,
            if (requested) "Confirme a instalação na tela inicial" else "Não foi possível solicitar a instalação",
            Toast.LENGTH_LONG,
        ).show()
    }

    private fun clearVideoCache() {
        VideoHistory.clear(this)
        VideoCache.get(this).clearAsync {
            runOnUiThread {
                Toast.makeText(this, "Cache de vídeos apagado", Toast.LENGTH_SHORT).show()
            }
        }
        Toast.makeText(this, "Limpando cache de vídeos…", Toast.LENGTH_SHORT).show()
    }

    private fun showAutoDnsPanel() {
        AlertDialog.Builder(this)
            .setTitle("DNS seguro do Android")
            .setMessage(
                autoDnsManager.status() + "\n\n" +
                    "A versão WebView agora usa o resolvedor do Android e respeita o DNS privado " +
                    "configurado no aparelho. A seleção automática com DoH será integrada ao Chromium.",
            )
            .setPositiveButton("Fechar", null)
            .show()
    }

    private fun openExternally() {
        val url = currentWebView().url ?: return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(this, "Nenhum aplicativo disponível", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmClearData() {
        AlertDialog.Builder(this)
            .setTitle("Limpar dados de navegação?")
            .setMessage("Cookies, cache, histórico das abas e sessão serão apagados. Favoritos serão mantidos.")
            .setPositiveButton("Limpar") { _, _ -> clearBrowsingData() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun clearBrowsingData() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        tabs.forEach { tab ->
            tab.webView.clearCache(true)
            tab.webView.clearHistory()
            tab.webView.clearFormData()
        }
        preferences.edit().remove("session_tabs").apply()
        Toast.makeText(this, "Dados apagados", Toast.LENGTH_SHORT).show()
    }

    private fun beginDownload(download: PendingDownload) {
        if (
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownload = download
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), STORAGE_PERMISSION_REQUEST)
            return
        }
        enqueueDownload(download)
    }

    private fun enqueueDownload(download: PendingDownload) {
        try {
            val uri = Uri.parse(download.url)
            if (!uri.scheme.equals("https", ignoreCase = true)) {
                throw IllegalArgumentException("Only HTTPS downloads are accepted")
            }
            URLUtil.guessFileName(download.url, download.contentDisposition, download.mimeType)
            DownloadRegistry.enqueue(
                this,
                download.url,
                download.userAgent,
                download.contentDisposition,
                download.mimeType,
                download.referer,
            )
            Toast.makeText(this, "Download iniciado • acompanhe em Downloads", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, "Não foi possível iniciar o download", Toast.LENGTH_LONG).show()
        }
    }

    private fun showFileChooser(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams) {
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = callback
        val chooser = try {
            params.createIntent().apply { addCategory(Intent.CATEGORY_OPENABLE) }
        } catch (_: Exception) {
            Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
        }
        try {
            startActivityForResult(Intent.createChooser(chooser, "Escolher arquivo"), FILE_CHOOSER_REQUEST)
        } catch (_: Exception) {
            fileChooserCallback?.onReceiveValue(null)
            fileChooserCallback = null
        }
    }

    @Deprecated("Deprecated in Android framework; kept for minSdk compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val callback = fileChooserCallback
        if (requestCode != FILE_CHOOSER_REQUEST || callback == null) return
        var result: Array<Uri>? = null
        if (resultCode == RESULT_OK && data != null) {
            val clip: ClipData? = data.clipData
            result = if (clip != null) {
                Array(clip.itemCount) { index -> clip.getItemAt(index).uri }
            } else {
                data.data?.let { arrayOf(it) }
            }
        }
        callback.onReceiveValue(result)
        fileChooserCallback = null
    }

    private fun requestWebPermission(request: PermissionRequest) {
        pendingWebPermission?.deny()
        val androidPermissions = ArrayList<String>()
        val resources = ArrayList<String>()
        request.resources.forEach { resource ->
            when (resource) {
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> {
                    resources.add(resource)
                    if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                        androidPermissions.add(Manifest.permission.CAMERA)
                    }
                }
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> {
                    resources.add(resource)
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        androidPermissions.add(Manifest.permission.RECORD_AUDIO)
                    }
                }
            }
        }
        if (resources.isEmpty()) {
            request.deny()
            return
        }
        pendingWebPermission = request
        pendingWebResources = resources.toTypedArray()
        if (androidPermissions.isNotEmpty()) {
            requestPermissions(androidPermissions.toTypedArray(), WEB_PERMISSION_REQUEST)
        } else {
            confirmWebPermission()
        }
    }

    private fun confirmWebPermission() {
        val request = pendingWebPermission ?: return
        val resources = pendingWebResources ?: return
        val host = request.origin?.host ?: "este site"
        AlertDialog.Builder(this)
            .setTitle("Permissão do site")
            .setMessage("Permitir que $host use câmera ou microfone?")
            .setPositiveButton("Permitir") { _, _ ->
                request.grant(resources)
                clearPendingWebPermission()
            }
            .setNegativeButton("Bloquear") { _, _ ->
                request.deny()
                clearPendingWebPermission()
            }
            .setOnCancelListener {
                request.deny()
                clearPendingWebPermission()
            }
            .show()
    }

    private fun clearPendingWebPermission() {
        pendingWebPermission = null
        pendingWebResources = null
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        val granted = results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }
        when (requestCode) {
            STORAGE_PERMISSION_REQUEST -> {
                val download = pendingDownload
                pendingDownload = null
                if (granted && download != null) enqueueDownload(download)
            }
            WEB_PERMISSION_REQUEST -> {
                if (granted) {
                    confirmWebPermission()
                } else {
                    pendingWebPermission?.deny()
                    clearPendingWebPermission()
                }
            }
        }
    }

    private fun restoreSession(): Boolean {
        val raw = preferences.getString("session_tabs", null) ?: return false
        return try {
            val saved = JSONArray(raw)
            val count = saved.length().coerceAtMost(MAX_RESTORED_TABS)
            for (index in 0 until count) {
                val value = saved.getJSONObject(index)
                val url = value.optString("url", HOME_URL)
                if (url.startsWith("https://") || url == "about:blank") {
                    createTab(url, false)
                    tabs.last().desktop = value.optBoolean("desktop", false)
                    if (tabs.last().desktop) {
                        tabs.last().webView.settings.userAgentString =
                            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                    }
                }
            }
            if (tabs.isEmpty()) return false
            selectTab(preferences.getInt("session_index", 0).coerceIn(0, tabs.lastIndex))
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun saveSession() {
        val saved = JSONArray()
        tabs.forEach { tab ->
            val url = tab.webView.url
            if (url == null || !(url.startsWith("https://") || url == "about:blank")) return@forEach
            try {
                saved.put(
                    JSONObject()
                        .put("url", url)
                        .put("desktop", tab.desktop),
                )
            } catch (_: Exception) {
            }
        }
        preferences.edit()
            .putString("session_tabs", saved.toString())
            .putInt("session_index", currentIndex.coerceAtLeast(0))
            .apply()
    }

    private fun updateAddress(url: String?) {
        if (url != null && !addressBar.hasFocus()) addressBar.setText(url)
    }

    private fun updateTabCounter() {
        tabCounter.text = tabs.size.coerceAtLeast(1).toString()
    }

    private fun updateShieldCounter(tab: BrowserTab) {
        if (!::shieldCounter.isInitialized || !isCurrent(tab)) return
        val state = if (adBlockEngine.isEnabledForUrl(tab.pageUrl)) "🛡 " else "○ "
        shieldCounter.text = state + tab.blockedRequests.get()
    }

    private fun isCurrent(tab: BrowserTab): Boolean =
        currentIndex in tabs.indices && tabs[currentIndex] === tab

    private fun currentTab(): BrowserTab = tabs.getOrNull(currentIndex) ?: error("No tab")

    private fun currentWebView(): WebView = currentTab().webView

    private fun dp(value: Int): Int = Math.round(value * resources.displayMetrics.density)

    private fun openExternalIntent(view: WebView, rawIntent: Intent, fallback: String?) {
        val scheme = rawIntent.data?.scheme?.lowercase(Locale.ROOT)
        if (
            scheme.isNullOrBlank() || scheme == "file" || scheme == "content" ||
            scheme == "javascript" || scheme == "data"
        ) {
            fallback?.let(view::loadUrl)
            return
        }

        val sanitized = Intent(rawIntent).apply {
            action = Intent.ACTION_VIEW
            component = null
            selector = null
            clipData = null
            flags = 0
            categories?.toList()?.forEach { removeCategory(it) }
            addCategory(Intent.CATEGORY_BROWSABLE)
            replaceExtras(Bundle())
        }
        val resolved = packageManager.resolveActivity(sanitized, PackageManager.MATCH_DEFAULT_ONLY)
        val activityInfo = resolved?.activityInfo
        if (activityInfo == null || !activityInfo.exported) {
            if (fallback != null) {
                view.loadUrl(fallback)
            } else {
                Toast.makeText(this, "Nenhum aplicativo disponível", Toast.LENGTH_SHORT).show()
            }
            return
        }

        val explicit = Intent(sanitized).setClassName(activityInfo.packageName, activityInfo.name)
        val appLabel = try {
            activityInfo.loadLabel(packageManager).toString()
        } catch (_: Exception) {
            activityInfo.packageName
        }
        AlertDialog.Builder(this)
            .setTitle("Abrir aplicativo externo?")
            .setMessage("Este site quer abrir $appLabel.")
            .setPositiveButton("Abrir") { _, _ ->
                try {
                    startActivity(explicit)
                } catch (_: Exception) {
                    Toast.makeText(this, "Não foi possível abrir o aplicativo", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        val safeUrl = intent?.data?.toString()?.let {
            NavigationSecurityPolicy.upgradeHttpToHttps(it)
        }
        if (safeUrl != null) createTab(safeUrl, true)
    }

    override fun onPause() {
        saveSession()
        super.onPause()
    }

    @Deprecated("Deprecated in Android framework; retained for Android 8+ compatibility")
    override fun onBackPressed() {
        when {
            tabs.isNotEmpty() && currentWebView().canGoBack() -> currentWebView().goBack()
            tabs.size > 1 -> closeCurrentTab()
            else -> super.onBackPressed()
        }
    }

    override fun onDestroy() {
        fileChooserCallback?.onReceiveValue(null)
        pendingWebPermission?.deny()
        if (::browserHost.isInitialized) browserHost.removeAllViews()
        tabs.forEach { tab ->
            tab.webView.stopLoading()
            tab.webView.destroy()
        }
        tabs.clear()
        if (::adBlockEngine.isInitialized) adBlockEngine.close()
        super.onDestroy()
    }

    private inner class NautrixWebViewClient(private val tab: BrowserTab) : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            val scheme = uri.scheme
            if (scheme.equals("https", ignoreCase = true)) return false
            if (scheme.equals("http", ignoreCase = true)) {
                NavigationSecurityPolicy.upgradeHttpToHttps(uri.toString())?.let(view::loadUrl)
                return true
            }
            if (scheme.equals("intent", ignoreCase = true)) {
                if (!NavigationSecurityPolicy.mayLaunchExternal(request.isForMainFrame, request.hasGesture())) {
                    return true
                }
                try {
                    val parsed = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                    val fallback = NavigationSecurityPolicy.safeHttpsUrl(
                        parsed.getStringExtra("browser_fallback_url"),
                    )
                    openExternalIntent(view, parsed, fallback)
                } catch (_: Exception) {
                }
                return true
            }
            if (scheme.equals("magnet", ignoreCase = true)) {
                confirmMagnet(uri.toString())
                return true
            }
            if (
                scheme.equals("mailto", ignoreCase = true) ||
                scheme.equals("tel", ignoreCase = true) ||
                scheme.equals("market", ignoreCase = true)
            ) {
                if (!NavigationSecurityPolicy.mayLaunchExternal(request.isForMainFrame, request.hasGesture())) {
                    return true
                }
                openExternalIntent(view, Intent(Intent.ACTION_VIEW, uri), null)
                return true
            }
            return true
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val requestUrl = request.url.toString()
            if (isLikelyVideoUrl(requestUrl)) tab.rememberMedia(requestUrl)
            if (adBlockEngine.shouldBlock(request, tab.pageUrl)) {
                tab.blockedRequests.incrementAndGet()
                runOnUiThread { updateShieldCounter(tab) }
                return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
            }
            return super.shouldInterceptRequest(view, request)
        }

        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
            tab.pageUrl = url
            tab.blockedRequests.set(0)
            tab.clearMedia()
            if (isCurrent(tab)) {
                updateAddress(url)
                progressBar.visibility = View.VISIBLE
                updateShieldCounter(tab)
            }
        }

        override fun onPageFinished(view: WebView, url: String) {
            tab.pageUrl = url
            adBlockEngine.cosmeticScriptFor(url)?.let { view.evaluateJavascript(it, null) }
            if (isCurrent(tab)) {
                updateAddress(url)
                updateShieldCounter(tab)
            }
            saveSession()
        }

        override fun onReceivedSslError(
            view: WebView,
            handler: SslErrorHandler,
            error: android.net.http.SslError,
        ) {
            handler.cancel()
            if (isCurrent(tab)) {
                Toast.makeText(this@BrowserActivity, "Conexão insegura bloqueada", Toast.LENGTH_LONG).show()
            }
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame && isCurrent(tab)) {
                Toast.makeText(this@BrowserActivity, "Não foi possível abrir a página", Toast.LENGTH_SHORT).show()
            }
        }

        override fun onSafeBrowsingHit(
            view: WebView,
            request: WebResourceRequest,
            threatType: Int,
            callback: SafeBrowsingResponse,
        ) {
            callback.backToSafety(true)
            if (isCurrent(tab)) {
                Toast.makeText(this@BrowserActivity, "Página perigosa bloqueada", Toast.LENGTH_LONG).show()
            }
        }
    }

    private inner class NautrixChromeClient(private val tab: BrowserTab) : WebChromeClient() {
        override fun onProgressChanged(view: WebView, progress: Int) {
            if (!isCurrent(tab)) return
            progressBar.progress = progress
            progressBar.visibility = if (progress >= 100) View.GONE else View.VISIBLE
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            tab.title = title ?: "Nova aba"
            if (webAppMode && isCurrent(tab) && title != null) setTitle(title)
        }

        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message,
        ): Boolean {
            if (!isUserGesture || resultMsg.obj !is WebView.WebViewTransport) return false
            createTab("about:blank", true)
            (resultMsg.obj as WebView.WebViewTransport).webView = currentWebView()
            resultMsg.sendToTarget()
            return true
        }

        override fun onShowFileChooser(
            webView: WebView,
            callback: ValueCallback<Array<Uri>>,
            params: FileChooserParams,
        ): Boolean {
            showFileChooser(callback, params)
            return true
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            runOnUiThread { requestWebPermission(request) }
        }

        override fun onPermissionRequestCanceled(request: PermissionRequest) {
            if (request === pendingWebPermission) clearPendingWebPermission()
        }
    }

    private class BrowserTab(val webView: WebView) {
        var title: String = "Nova aba"
        var desktop: Boolean = false
        @Volatile var pageUrl: String? = null
        private val mediaUrls = LinkedHashSet<String>()
        val blockedRequests = AtomicInteger()

        @Synchronized
        fun rememberMedia(url: String?) {
            if (url == null || !url.startsWith("https://") || url.startsWith("blob:")) return
            mediaUrls.remove(url)
            mediaUrls.add(url)
            while (mediaUrls.size > 30) {
                mediaUrls.remove(mediaUrls.iterator().next())
            }
        }

        @Synchronized
        fun clearMedia() {
            mediaUrls.clear()
        }

        @Synchronized
        fun mediaSnapshot(): List<String> = ArrayList(mediaUrls).apply { reverse() }

        @Synchronized
        fun lastMediaUrl(): String? = mediaUrls.lastOrNull()
    }

    private data class PendingDownload(
        val url: String,
        val userAgent: String?,
        val contentDisposition: String?,
        val mimeType: String?,
        val referer: String?,
    )
}
