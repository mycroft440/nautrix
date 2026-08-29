package com.nautrix.browser

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

/**
 * Safe DNS bridge used by the WebView prototype.
 *
 * Android's resolver honors the user's Private DNS configuration. The former direct UDP/53
 * benchmark was intentionally removed because it exposed hostnames and could be spoofed. The
 * Chromium build will later provide browser-managed DNS-over-HTTPS selection.
 */
class AutoDnsManager private constructor(context: Context) {
    companion object {
        private const val DNS_CACHE_MS = 60_000L

        @Volatile
        private var instance: AutoDnsManager? = null

        @JvmStatic
        fun get(context: Context): AutoDnsManager =
            instance ?: synchronized(this) {
                instance ?: AutoDnsManager(context.applicationContext).also { instance = it }
            }
    }

    private val context = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val addressCache = ConcurrentHashMap<String, CachedAddresses>()

    @Volatile
    private var state = "DNS do Android • DNS privado respeitado quando configurado"

    init {
        registerNetworkChanges()
    }

    fun installWebViewProxy(ready: Runnable?) {
        val completion = ready ?: Runnable { }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            mainHandler.post(completion)
            return
        }
        try {
            ProxyController.getInstance().clearProxyOverride(
                Executor { command -> mainHandler.post(command) },
                completion,
            )
        } catch (_: Exception) {
            mainHandler.post(completion)
        }
    }

    fun benchmarkAsync(force: Boolean, completion: Runnable?) {
        state = "DNS do Android • seleção UDP externa desativada por segurança"
        completion?.let(mainHandler::post)
    }

    fun status(): String = state

    @Throws(java.net.UnknownHostException::class)
    fun resolveAll(hostname: String?): List<InetAddress> {
        if (hostname.isNullOrBlank()) throw java.net.UnknownHostException("empty hostname")
        val now = System.currentTimeMillis()
        addressCache[hostname]?.takeIf { it.expiresAt > now }?.let { return it.addresses }
        val addresses = InetAddress.getAllByName(hostname).toList()
        addressCache[hostname] = CachedAddresses(addresses, now + DNS_CACHE_MS)
        return addresses
    }

    private fun registerNetworkChanges() {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        try {
            manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    addressCache.clear()
                    state = "DNS do Android • rede atualizada"
                }

                override fun onLost(network: Network) {
                    addressCache.clear()
                }
            })
        } catch (_: Exception) {
        }
    }

    private data class CachedAddresses(val addresses: List<InetAddress>, val expiresAt: Long)
}
