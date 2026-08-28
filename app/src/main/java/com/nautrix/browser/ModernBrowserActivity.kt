package com.nautrix.browser

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/**
 * Modern visual shell for the standalone Nautrix browser.
 *
 * BrowserActivity still owns tabs, downloads, ad blocking and the WebView lifecycle. This
 * activity adds a native offline home above the WebView and rearranges the existing controls.
 * The home never needs DNS or a network request, so a new tab remains immediately usable even
 * while the browser network stack is still warming up.
 */
class ModernBrowserActivity : BrowserActivity() {
    companion object {
        private const val BACKGROUND = "#0B1117"
        private const val SURFACE = "#111A23"
        private const val FIELD = "#192530"
        private const val FIELD_STROKE = "#273847"
        private const val TEXT = "#F2F6F8"
        private const val MUTED = "#9AAAB6"
        private const val ACCENT = "#43C6AC"
        private const val ACCENT_SURFACE = "#17352F"
        private const val RIPPLE = "#33FFFFFF"
        private const val VOICE_SEARCH_REQUEST = 7301
    }

    private lateinit var webContainer: FrameLayout
    private lateinit var browserShell: FrameLayout
    private lateinit var homeOverlay: View
    private var chromeAddress: EditText? = null
    private var homeSearchInput: EditText? = null
    private var homeOwnerWebView: WebView? = null
    private var expectHomeOnNextWebView = false
    private var pendingSearch: String? = null
    private var homeVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyModernChrome()
    }

    private fun applyModernChrome() {
        val content = findViewById<FrameLayout>(android.R.id.content) ?: return
        val root = content.getChildAt(0) as? LinearLayout ?: return
        if (root.childCount < 4) return

        val toolbar = root.getChildAt(0) as? LinearLayout ?: return
        val progress = root.getChildAt(1) as? ProgressBar ?: return
        webContainer = root.getChildAt(2) as? FrameLayout ?: return
        val navigation = root.getChildAt(3) as? LinearLayout ?: return
        if (toolbar.childCount < 6 || navigation.childCount < 5) return

        // Keep references before moving them. BrowserActivity already attached the behaviour.
        val back = toolbar.getChildAt(0) as TextView
        val forward = toolbar.getChildAt(1) as TextView
        val reload = toolbar.getChildAt(2) as TextView
        val address = toolbar.getChildAt(3) as EditText
        val redundantGo = toolbar.getChildAt(4)
        val mediaDownload = toolbar.getChildAt(5) as TextView

        val home = navigation.getChildAt(0) as TextView
        val shield = navigation.getChildAt(1) as TextView
        val newTab = navigation.getChildAt(2) as TextView
        val tabCount = navigation.getChildAt(3) as TextView
        val menu = navigation.getChildAt(4) as TextView

        chromeAddress = address
        toolbar.removeAllViews()
        navigation.removeAllViews()
        redundantGo.visibility = View.GONE

        root.setBackgroundColor(color(BACKGROUND))
        toolbar.apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), dp(5), dp(5), dp(5))
            setBackgroundColor(color(SURFACE))
            elevation = dp(3).toFloat()
            minimumHeight = 0
        }

        styleIcon(back, 26f)
        toolbar.addView(back, fixedParams(40, 46))

        val addressCapsule = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(color(FIELD), 22f, color(FIELD_STROKE), 1)
            clipToOutline = true
            isFocusable = false
        }

        shield.apply {
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(color(ACCENT))
            setPadding(0, 0, 0, 0)
            background = ripple(Color.TRANSPARENT, 19f)
            minWidth = 0
            minHeight = 0
            minimumWidth = 0
            minimumHeight = 0
            maxLines = 1
        }
        addressCapsule.addView(shield, LinearLayout.LayoutParams(dp(40), dp(42)))

        address.apply {
            setSingleLine(true)
            textSize = 14.5f
            setTextColor(color(TEXT))
            setHintTextColor(color(MUTED))
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(1), 0, dp(2), 0)
            minWidth = 0
            minHeight = 0
            setSelectAllOnFocus(true)
            setHorizontallyScrolling(true)
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, actionId, event ->
                val enter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER &&
                    event.action == KeyEvent.ACTION_DOWN
                if (actionId == EditorInfo.IME_ACTION_GO || enter) {
                    submitSearch(address.text.toString())
                    true
                } else {
                    false
                }
            }
        }
        addressCapsule.addView(address, LinearLayout.LayoutParams(0, dp(42), 1f))

        styleIcon(reload, 22f)
        reload.setOnClickListener {
            if (!homeVisible) currentWebView()?.reload()
        }
        addressCapsule.addView(reload, LinearLayout.LayoutParams(dp(38), dp(42)))

        toolbar.addView(
            addressCapsule,
            LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                setMargins(dp(2), 0, dp(4), 0)
            },
        )

        styleTabCounter(tabCount)
        toolbar.addView(tabCount, fixedParams(36, 40))

        styleIcon(menu, 25f)
        toolbar.addView(menu, fixedParams(38, 44))

        navigation.apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(2), dp(8), dp(3))
            setBackgroundColor(color(SURFACE))
            elevation = dp(8).toFloat()
            minimumHeight = 0
        }

        styleBottomAction(home, 22f)
        styleBottomAction(forward, 28f)
        styleBottomAction(newTab, 27f, emphasized = true)
        styleBottomAction(mediaDownload, 22f)

        navigation.addView(home, weightedBottomParams())
        navigation.addView(forward, weightedBottomParams())
        navigation.addView(
            newTab,
            LinearLayout.LayoutParams(dp(58), dp(44)).apply {
                setMargins(dp(4), 0, dp(4), 0)
            },
        )
        navigation.addView(mediaDownload, weightedBottomParams())

        toolbar.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(56),
        )
        progress.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(2),
        )
        navigation.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(52),
        )

        progress.apply {
            progressTintList = ColorStateList.valueOf(color(ACCENT))
            progressBackgroundTintList = ColorStateList.valueOf(color(FIELD_STROKE))
        }

        wrapBrowserWithOfflineHome(root)

        // Home is a native layer. It does not navigate to DuckDuckGo or any other remote page.
        home.setOnClickListener {
            currentWebView()?.stopLoading()
            homeOwnerWebView = currentWebView()
            expectHomeOnNextWebView = false
            pendingSearch = null
            showOfflineHome(clearAddress = true)
        }

        // BrowserActivity still creates the actual tab. We display the native home immediately,
        // then stop the legacy remote-home request as soon as the new WebView becomes current.
        newTab.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                homeOwnerWebView = null
                expectHomeOnNextWebView = true
                pendingSearch = null
                showOfflineHome(clearAddress = true, animate = false)
            }
            false
        }

        webContainer.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
            override fun onChildViewAdded(parent: View?, child: View?) {
                val webView = child as? WebView ?: return
                val queued = pendingSearch
                if (queued != null) {
                    pendingSearch = null
                    expectHomeOnNextWebView = false
                    homeOwnerWebView = webView
                    hideOfflineHome()
                    webView.post { webView.loadUrl(UrlResolver.resolve(queued)) }
                    return
                }

                if (expectHomeOnNextWebView || (homeVisible && homeOwnerWebView == null)) {
                    expectHomeOnNextWebView = false
                    homeOwnerWebView = webView
                    webView.post {
                        if (homeVisible && homeOwnerWebView === webView) {
                            webView.stopLoading()
                            chromeAddress?.setText("")
                        }
                    }
                } else if (homeVisible && homeOwnerWebView !== webView) {
                    // Switching to another existing tab should reveal that tab, not keep the
                    // previous tab's offline-home layer over it.
                    hideOfflineHome()
                }
            }

            override fun onChildViewRemoved(parent: View?, child: View?) = Unit
        })

        // Normal launches start on the offline home immediately. HTTPS deep links still open
        // directly into the requested site.
        if (intent?.data?.scheme.equals("https", ignoreCase = true)) {
            hideOfflineHome()
        } else {
            expectHomeOnNextWebView = true
            showOfflineHome(clearAddress = true, animate = false)
        }

        toolbar.alpha = 0f
        toolbar.translationY = -dp(5).toFloat()
        toolbar.animate().alpha(1f).translationY(0f).setDuration(140L).start()
        navigation.alpha = 0f
        navigation.translationY = dp(4).toFloat()
        navigation.animate().alpha(1f).translationY(0f).setDuration(160L).start()

        window.statusBarColor = color(BACKGROUND)
        window.navigationBarColor = color(BACKGROUND)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
    }

    private fun wrapBrowserWithOfflineHome(root: LinearLayout) {
        root.removeView(webContainer)
        browserShell = FrameLayout(this).apply {
            setBackgroundColor(color(BACKGROUND))
        }
        browserShell.addView(
            webContainer,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        homeOverlay = createOfflineHomeView()
        browserShell.addView(
            homeOverlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        root.addView(
            browserShell,
            2,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
    }

    private fun createOfflineHomeView(): View {
        val frame = FrameLayout(this).apply {
            setBackgroundColor(color(BACKGROUND))
            isClickable = true
        }

        val vertical = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), 0, dp(22), 0)
        }
        frame.addView(
            vertical,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        // The 39/61 split places the search surface a little above the visual centre on phones
        // of different aspect ratios without hard-coding a pixel offset.
        vertical.addView(
            Space(this),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.39f),
        )

        val searchSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val wordmark = TextView(this).apply {
            text = "Nautrix"
            textSize = 31f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(color(TEXT))
            gravity = Gravity.CENTER
            letterSpacing = 0.015f
        }
        searchSection.addView(
            wordmark,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(22) },
        )

        val searchSurface = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(color(FIELD), 29f, color(FIELD_STROKE), 1)
            elevation = dp(2).toFloat()
        }

        val searchIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_search_nautrix)
            imageTintList = ColorStateList.valueOf(color(MUTED))
            setPadding(dp(13), dp(13), dp(9), dp(13))
            contentDescription = "Pesquisar"
        }
        searchSurface.addView(searchIcon, LinearLayout.LayoutParams(dp(48), dp(58)))

        val search = EditText(this).apply {
            setSingleLine(true)
            hint = "Pesquisar ou digitar endereço"
            textSize = 16f
            setTextColor(color(TEXT))
            setHintTextColor(color(MUTED))
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(0, 0, dp(6), 0)
            minWidth = 0
            minHeight = 0
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
            setOnEditorActionListener { _, actionId, event ->
                val enter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER &&
                    event.action == KeyEvent.ACTION_DOWN
                if (actionId == EditorInfo.IME_ACTION_SEARCH || enter) {
                    submitSearch(text.toString())
                    true
                } else {
                    false
                }
            }
        }
        homeSearchInput = search
        searchSurface.addView(search, LinearLayout.LayoutParams(0, dp(58), 1f))

        val microphone = ImageButton(this).apply {
            setImageResource(R.drawable.ic_mic_nautrix)
            imageTintList = ColorStateList.valueOf(color(TEXT))
            background = ripple(color(ACCENT_SURFACE), 22f)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            contentDescription = "Pesquisar por voz"
            setOnClickListener { launchVoiceSearch() }
        }
        searchSurface.addView(
            microphone,
            LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                setMargins(0, 0, dp(5), 0)
            },
        )

        searchIcon.setOnClickListener {
            search.requestFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT)
        }

        searchSection.addView(
            searchSurface,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)),
        )

        val offlineLabel = TextView(this).apply {
            text = "Página inicial disponível offline"
            textSize = 11.5f
            setTextColor(color(MUTED))
            gravity = Gravity.CENTER
        }
        searchSection.addView(
            offlineLabel,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(13) },
        )

        vertical.addView(
            searchSection,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        vertical.addView(
            Space(this),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.61f),
        )
        return frame
    }

    private fun submitSearch(raw: String) {
        val query = raw.trim()
        if (query.isEmpty()) {
            val input = homeSearchInput ?: chromeAddress ?: return
            input.requestFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            return
        }

        val input = homeSearchInput ?: chromeAddress
        if (input != null) {
            (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(input.windowToken, 0)
            input.clearFocus()
        }

        val webView = currentWebView()
        if (webView == null) {
            // Network/proxy setup can still be starting. Keep the offline home visible and run
            // the search as soon as BrowserActivity attaches the first WebView.
            pendingSearch = query
            expectHomeOnNextWebView = true
            return
        }

        pendingSearch = null
        expectHomeOnNextWebView = false
        homeOwnerWebView = webView
        hideOfflineHome()
        webView.loadUrl(UrlResolver.resolve(query))
    }

    private fun launchVoiceSearch() {
        val speech = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Pesquisar no Nautrix")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        try {
            startActivityForResult(speech, VOICE_SEARCH_REQUEST)
        } catch (_: Exception) {
            Toast.makeText(
                this,
                "O reconhecimento de voz não está disponível neste aparelho",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    @Deprecated("Deprecated in Android framework; retained for Android 8+ compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == VOICE_SEARCH_REQUEST) {
            if (resultCode == RESULT_OK) {
                val spoken = data
                    ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()
                if (spoken.isNotEmpty()) {
                    homeSearchInput?.setText(spoken)
                    homeSearchInput?.setSelection(spoken.length)
                    submitSearch(spoken)
                }
            }
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun currentWebView(): WebView? {
        for (index in webContainer.childCount - 1 downTo 0) {
            val child = webContainer.getChildAt(index)
            if (child is WebView) return child
        }
        return null
    }

    private fun showOfflineHome(clearAddress: Boolean, animate: Boolean = true) {
        if (!::homeOverlay.isInitialized) return
        homeVisible = true
        if (clearAddress) {
            chromeAddress?.setText("")
            chromeAddress?.clearFocus()
            homeSearchInput?.setText("")
        }
        homeOverlay.visibility = View.VISIBLE
        homeOverlay.bringToFront()
        if (animate) {
            homeOverlay.alpha = 0f
            homeOverlay.translationY = dp(5).toFloat()
            homeOverlay.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(140L)
                .start()
        } else {
            homeOverlay.animate().cancel()
            homeOverlay.alpha = 1f
            homeOverlay.translationY = 0f
        }
    }

    private fun hideOfflineHome() {
        if (!::homeOverlay.isInitialized) return
        homeVisible = false
        homeOverlay.animate().cancel()
        homeOverlay.alpha = 1f
        homeOverlay.translationY = 0f
        homeOverlay.visibility = View.GONE
    }

    private fun styleIcon(view: TextView, size: Float) {
        view.apply {
            textSize = size
            gravity = Gravity.CENTER
            setTextColor(color(TEXT))
            setPadding(0, 0, 0, 0)
            background = ripple(Color.TRANSPARENT, 21f)
            minWidth = 0
            minHeight = 0
            minimumWidth = 0
            minimumHeight = 0
        }
    }

    private fun styleTabCounter(view: TextView) {
        view.apply {
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(color(TEXT))
            setPadding(0, 0, 0, 0)
            background = rippleWithStroke(color(FIELD), 11f, color(FIELD_STROKE), 1)
            minWidth = 0
            minHeight = 0
            minimumWidth = 0
            minimumHeight = 0
        }
    }

    private fun styleBottomAction(view: TextView, size: Float, emphasized: Boolean = false) {
        view.apply {
            textSize = size
            gravity = Gravity.CENTER
            setTextColor(if (emphasized) color(ACCENT) else color(TEXT))
            setPadding(0, 0, 0, 0)
            background = if (emphasized) {
                ripple(color(ACCENT_SURFACE), 20f)
            } else {
                ripple(Color.TRANSPARENT, 20f)
            }
            minWidth = 0
            minHeight = 0
            minimumWidth = 0
            minimumHeight = 0
        }
    }

    private fun fixedParams(width: Int, height: Int) =
        LinearLayout.LayoutParams(dp(width), dp(height))

    private fun weightedBottomParams() =
        LinearLayout.LayoutParams(0, dp(46), 1f).apply {
            setMargins(dp(2), 0, dp(2), 0)
        }

    private fun rounded(fill: Int, radiusDp: Float, stroke: Int? = null, strokeDp: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = dp(radiusDp).toFloat()
            if (stroke != null && strokeDp > 0) setStroke(dp(strokeDp), stroke)
        }

    private fun ripple(fill: Int, radiusDp: Float): RippleDrawable =
        RippleDrawable(
            ColorStateList.valueOf(color(RIPPLE)),
            rounded(fill, radiusDp),
            null,
        )

    private fun rippleWithStroke(fill: Int, radiusDp: Float, stroke: Int, strokeDp: Int): RippleDrawable =
        RippleDrawable(
            ColorStateList.valueOf(color(RIPPLE)),
            rounded(fill, radiusDp, stroke, strokeDp),
            null,
        )

    private fun color(value: String): Int = Color.parseColor(value)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}
