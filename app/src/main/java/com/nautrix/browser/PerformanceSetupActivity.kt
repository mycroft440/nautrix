package com.nautrix.browser

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * One-time, opt-in setup for Android background execution restrictions.
 *
 * The browser continues to work when the user declines. Granting these settings mainly helps
 * long-running downloads/torrents, cache/network work and other tasks that must survive while
 * Nautrix is not the foreground app; it does not bypass Android security or connectivity rules.
 */
class PerformanceSetupActivity : Activity() {
    companion object {
        private const val PREFS = "nautrix"
        private const val KEY_PROMPTED = "performance_setup_prompted_v1"
    }

    private var waitingForBatterySettings = false
    private var waitingForBackgroundDataSettings = false
    private var browserLaunched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#0B1117")
        window.navigationBarColor = Color.parseColor("#0B1117")
        setContentView(
            FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#0B1117")) },
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_PROMPTED, false) || (batteryIsUnrestricted() && backgroundDataIsAllowed())) {
            prefs.edit().putBoolean(KEY_PROMPTED, true).apply()
            launchBrowser()
            return
        }

        window.decorView.post { showPerformanceConsent() }
    }

    override fun onResume() {
        super.onResume()
        when {
            waitingForBatterySettings -> {
                waitingForBatterySettings = false
                if (!backgroundDataIsAllowed()) {
                    openBackgroundDataSettings()
                } else {
                    finishSetupAndLaunch()
                }
            }
            waitingForBackgroundDataSettings -> {
                waitingForBackgroundDataSettings = false
                finishSetupAndLaunch()
            }
        }
    }

    private fun showPerformanceConsent() {
        if (isFinishing || isDestroyed || browserLaunched) return
        AlertDialog.Builder(this)
            .setTitle("Nautrix em segundo plano")
            .setMessage(
                "Para manter downloads, torrents, cache e tarefas de rede mais estáveis quando " +
                    "o Nautrix não estiver na tela, você pode permitir bateria sem restrição e " +
                    "dados em segundo plano.\n\nIsso pode aumentar o consumo de bateria e de " +
                    "dados móveis. O navegador continua funcionando mesmo se você não permitir.",
            )
            .setPositiveButton("Configurar") { _, _ -> startPerformanceSetup() }
            .setNegativeButton("Agora não") { _, _ -> finishSetupAndLaunch() }
            .setOnCancelListener { finishSetupAndLaunch() }
            .show()
    }

    private fun startPerformanceSetup() {
        if (!batteryIsUnrestricted()) {
            openBatterySettings()
        } else if (!backgroundDataIsAllowed()) {
            openBackgroundDataSettings()
        } else {
            finishSetupAndLaunch()
        }
    }

    private fun openBatterySettings() {
        waitingForBatterySettings = true
        val packageUri = Uri.parse("package:$packageName")
        val directRequest = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri)
        if (launchSettingsIntent(directRequest)) return

        val optimizationList = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        if (launchSettingsIntent(optimizationList)) return

        launchSettingsIntent(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri))
    }

    private fun openBackgroundDataSettings() {
        waitingForBackgroundDataSettings = true
        val packageUri = Uri.parse("package:$packageName")
        val dataIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Intent(Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS, packageUri)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
        }
        if (launchSettingsIntent(dataIntent)) return
        launchSettingsIntent(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri))
    }

    private fun launchSettingsIntent(intent: Intent): Boolean = try {
        if (intent.resolveActivity(packageManager) == null) {
            false
        } else {
            startActivity(intent)
            true
        }
    } catch (_: Exception) {
        false
    }

    private fun batteryIsUnrestricted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val manager = getSystemService(POWER_SERVICE) as? PowerManager ?: return false
        return manager.isIgnoringBatteryOptimizations(packageName)
    }

    private fun backgroundDataIsAllowed(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return true
        val manager = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        return manager.restrictBackgroundStatus != ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
    }

    private fun finishSetupAndLaunch() {
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PROMPTED, true)
            .apply()
        launchBrowser()
    }

    private fun launchBrowser() {
        if (browserLaunched || isFinishing) return
        browserLaunched = true
        startActivity(
            Intent(this, ModernBrowserActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }
}
