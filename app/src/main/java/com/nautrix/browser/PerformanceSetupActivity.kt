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
 * User-initiated access to Android background execution settings.
 *
 * This screen is never shown during first launch. It opens only from the browser menu and never
 * requests a direct battery-optimization exemption, which keeps the decision in Android's UI.
 */
class PerformanceSetupActivity : Activity() {
    private var waitingForBatterySettings = false
    private var waitingForBackgroundDataSettings = false

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
        window.decorView.post { showPerformanceConsent() }
    }

    override fun onResume() {
        super.onResume()
        when {
            waitingForBatterySettings -> {
                waitingForBatterySettings = false
                if (!backgroundDataIsAllowed()) openBackgroundDataSettings() else finish()
            }
            waitingForBackgroundDataSettings -> {
                waitingForBackgroundDataSettings = false
                finish()
            }
        }
    }

    private fun showPerformanceConsent() {
        if (isFinishing || isDestroyed) return
        val batteryState = if (batteryIsUnrestricted()) "sem restrição" else "otimizada pelo Android"
        val dataState = if (backgroundDataIsAllowed()) "permitidos" else "restritos"
        AlertDialog.Builder(this)
            .setTitle("Execução em segundo plano")
            .setMessage(
                "Bateria: $batteryState\nDados em segundo plano: $dataState\n\n" +
                    "Alterar essas opções pode ajudar torrents e transferências longas, mas aumenta " +
                    "o consumo. O Nautrix funciona sem essas permissões.",
            )
            .setPositiveButton("Abrir configurações") { _, _ -> startPerformanceSetup() }
            .setNegativeButton("Voltar") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun startPerformanceSetup() {
        if (!batteryIsUnrestricted()) {
            openBatterySettings()
        } else if (!backgroundDataIsAllowed()) {
            openBackgroundDataSettings()
        } else {
            finish()
        }
    }

    private fun openBatterySettings() {
        waitingForBatterySettings = true
        if (launchSettingsIntent(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) return
        if (!launchSettingsIntent(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
        )) finish()
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
        if (!launchSettingsIntent(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri))) finish()
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
}
