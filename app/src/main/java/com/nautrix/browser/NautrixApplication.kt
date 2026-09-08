package com.nautrix.browser

import android.app.Activity
import android.app.Application
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/** Applies edge-to-edge safe areas and compact visual chrome to the modern browser shell. */
class NautrixApplication : Application(), Application.ActivityLifecycleCallbacks {
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (activity !is ModernBrowserActivity) return
        activity.window.decorView.post { polishModernBrowser(activity) }
    }

    private fun polishModernBrowser(activity: ModernBrowserActivity) {
        val content = activity.findViewById<FrameLayout>(android.R.id.content) ?: return
        val root = content.getChildAt(0) as? LinearLayout ?: return
        if (root.childCount < 4) return

        val toolbar = root.getChildAt(0) as? LinearLayout ?: return
        val progress = root.getChildAt(1)
        val browserShell = root.getChildAt(2) as? FrameLayout ?: return
        val navigation = root.getChildAt(3) as? LinearLayout ?: return

        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        // Android 15+ enforces edge-to-edge for apps targeting modern SDKs. Keep the background
        // behind system bars, but move all interactive browser chrome inside the safe area.
        if (Build.VERSION.SDK_INT >= 35) {
            ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
                val safe = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
                )
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
                insets
            }
            ViewCompat.requestApplyInsets(root)
        }

        root.setBackgroundColor(color(BACKGROUND))

        toolbar.apply {
            background = rounded(activity, color(SURFACE), 18f, color(STROKE), 1)
            clipToOutline = true
            elevation = dp(activity, 3).toFloat()
            setPadding(dp(activity, 8), dp(activity, 3), dp(activity, 7), dp(activity, 3))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(activity, 54),
            ).apply {
                setMargins(dp(activity, 8), dp(activity, 6), dp(activity, 8), dp(activity, 3))
            }
        }

        progress.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(activity, 2),
        ).apply {
            setMargins(dp(activity, 18), 0, dp(activity, 18), dp(activity, 3))
        }

        browserShell.apply {
            background = rounded(activity, color(BACKGROUND), 18f, color(STROKE), 1)
            clipToOutline = true
            elevation = dp(activity, 1).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ).apply {
                setMargins(dp(activity, 8), dp(activity, 2), dp(activity, 8), dp(activity, 2))
            }
        }

        navigation.apply {
            background = rounded(activity, color(SURFACE), 18f, color(STROKE), 1)
            clipToOutline = true
            elevation = dp(activity, 5).toFloat()
            setPadding(dp(activity, 10), dp(activity, 2), dp(activity, 10), dp(activity, 2))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(activity, 50),
            ).apply {
                setMargins(dp(activity, 10), dp(activity, 3), dp(activity, 10), dp(activity, 7))
            }
        }

        // The home search already has a capsule. Add breathing room so it does not span nearly
        // the entire display width, and strengthen its outline for visual separation.
        findHomeSearch(root, toolbar)?.let { search ->
            val capsule = search.parent as? LinearLayout ?: return@let
            (capsule.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
                params.height = dp(activity, 58)
                params.setMargins(dp(activity, 12), 0, dp(activity, 12), 0)
                capsule.layoutParams = params
            }
            capsule.background = rounded(activity, color(FIELD), 28f, color(FIELD_STROKE), 1)
            capsule.clipToOutline = true
            capsule.elevation = dp(activity, 3).toFloat()
        }
    }

    private fun findHomeSearch(root: View, toolbar: View): EditText? {
        if (root is EditText && root.hint?.toString() == SEARCH_HINT && !isDescendantOf(root, toolbar)) {
            return root
        }
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                findHomeSearch(root.getChildAt(index), toolbar)?.let { return it }
            }
        }
        return null
    }

    private fun isDescendantOf(view: View, ancestor: View): Boolean {
        var parent = view.parent
        while (parent is View) {
            if (parent === ancestor) return true
            parent = parent.parent
        }
        return false
    }

    private fun rounded(
        activity: Activity,
        fill: Int,
        radiusDp: Float,
        stroke: Int,
        strokeDp: Int,
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = dp(activity, radiusDp).toFloat()
        setStroke(dp(activity, strokeDp), stroke)
    }

    private fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density + 0.5f).toInt()

    private fun dp(activity: Activity, value: Float): Int =
        (value * activity.resources.displayMetrics.density + 0.5f).toInt()

    private fun color(value: String): Int = Color.parseColor(value)

    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    companion object {
        private const val BACKGROUND = "#0B1117"
        private const val SURFACE = "#111A23"
        private const val STROKE = "#22303B"
        private const val FIELD = "#192530"
        private const val FIELD_STROKE = "#38505F"
        private const val SEARCH_HINT = "Pesquisar ou digitar endereço"
    }
}
