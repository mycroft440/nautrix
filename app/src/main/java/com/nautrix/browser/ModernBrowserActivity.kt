package com.nautrix.browser

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * Modern visual shell for the standalone Nautrix browser.
 *
 * BrowserActivity owns browser behaviour. This activity only rearranges and styles the
 * existing controls so navigation logic, downloads, ad blocking and tabs keep the exact
 * same listeners and state while the UI becomes compact and responsive.
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
        private const val RIPPLE = "#33FFFFFF"
    }

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
        val browserHost = root.getChildAt(2)
        val navigation = root.getChildAt(3) as? LinearLayout ?: return
        if (toolbar.childCount < 6 || navigation.childCount < 5) return

        // Keep references before removing them from their original parents. All listeners are
        // already attached by BrowserActivity and remain attached after the move.
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

        toolbar.removeAllViews()
        navigation.removeAllViews()
        redundantGo.visibility = View.GONE

        root.setBackgroundColor(color(BACKGROUND))
        toolbar.apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(6), dp(6), dp(6))
            setBackgroundColor(color(SURFACE))
            elevation = dp(3).toFloat()
            minimumHeight = 0
        }

        styleIcon(back, 27f)
        toolbar.addView(back, fixedParams(44, 48))

        val addressCapsule = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(color(FIELD), 22f, color(FIELD_STROKE), 1)
            clipToOutline = true
            isFocusable = false
        }

        shield.apply {
            textSize = 12.5f
            gravity = Gravity.CENTER
            setTextColor(color(ACCENT))
            setPadding(dp(5), 0, dp(3), 0)
            background = ripple(Color.TRANSPARENT, 20f)
            minWidth = 0
            minHeight = 0
        }
        addressCapsule.addView(shield, LinearLayout.LayoutParams(dp(46), dp(44)))

        address.apply {
            setSingleLine(true)
            textSize = 14.5f
            setTextColor(color(TEXT))
            setHintTextColor(color(MUTED))
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(2), 0, dp(4), 0)
            minWidth = 0
            minHeight = 0
            selectAllOnFocus = true
            setHorizontallyScrolling(true)
        }
        addressCapsule.addView(address, LinearLayout.LayoutParams(0, dp(44), 1f))

        styleIcon(reload, 23f)
        addressCapsule.addView(reload, LinearLayout.LayoutParams(dp(42), dp(44)))

        toolbar.addView(
            addressCapsule,
            LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                setMargins(dp(3), 0, dp(5), 0)
            },
        )

        styleTabCounter(tabCount)
        toolbar.addView(tabCount, fixedParams(42, 46))

        styleIcon(menu, 26f)
        toolbar.addView(menu, fixedParams(42, 46))

        // A modern mobile browser does not need six cramped actions above the page. Less-used
        // navigation stays on the bottom bar where it remains one tap away.
        navigation.apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(2), dp(8), dp(4))
            setBackgroundColor(color(SURFACE))
            elevation = dp(8).toFloat()
            minimumHeight = 0
        }

        styleBottomAction(home, 23f)
        styleBottomAction(forward, 29f)
        styleBottomAction(newTab, 27f, emphasized = true)
        styleBottomAction(mediaDownload, 23f)

        navigation.addView(home, weightedBottomParams())
        navigation.addView(forward, weightedBottomParams())
        navigation.addView(newTab, weightedBottomParams())
        navigation.addView(mediaDownload, weightedBottomParams())

        root.getChildAt(0).layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(60),
        )
        root.getChildAt(1).layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(2),
        )
        root.getChildAt(3).layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(56),
        )

        progress.apply {
            progressTintList = ColorStateList.valueOf(color(ACCENT))
            progressBackgroundTintList = ColorStateList.valueOf(color(FIELD_STROKE))
        }

        browserHost.setBackgroundColor(color(BACKGROUND))

        // Small entrance transition makes the chrome feel deliberate without delaying input.
        toolbar.alpha = 0f
        toolbar.translationY = -dp(6).toFloat()
        toolbar.animate().alpha(1f).translationY(0f).setDuration(160L).start()
        navigation.alpha = 0f
        navigation.translationY = dp(5).toFloat()
        navigation.animate().alpha(1f).translationY(0f).setDuration(180L).start()

        window.statusBarColor = color(BACKGROUND)
        window.navigationBarColor = color(BACKGROUND)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
    }

    private fun styleIcon(view: TextView, size: Float) {
        view.apply {
            textSize = size
            gravity = Gravity.CENTER
            setTextColor(color(TEXT))
            setPadding(0, 0, 0, 0)
            background = ripple(Color.TRANSPARENT, 22f)
            minWidth = 0
            minHeight = 0
            minimumWidth = 0
            minimumHeight = 0
        }
    }

    private fun styleTabCounter(view: TextView) {
        view.apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(color(TEXT))
            setPadding(0, 0, 0, 0)
            background = rippleWithStroke(color(FIELD), 13f, color(MUTED), 1)
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
                ripple(color("#17352F"), 20f)
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
        LinearLayout.LayoutParams(0, dp(50), 1f).apply {
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