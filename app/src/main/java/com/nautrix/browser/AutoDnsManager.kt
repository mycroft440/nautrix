package com.nautrix.browser

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch

/** Benchmarks public DNS resolvers and supplies the winner to WebView and media traffic. */
class AutoDnsManager private constructor(context: Context) {
    companion object {
        private const val BENCHMARK_VALID_MS = 6L * 60L * 60L * 1_000L
        private const val DNS_CACHE_MS = 60_000L
        private const val DNS_TIMEOUT_MS = 900
        private val RANDOM = SecureRandom()

        private val CANDIDATES = arrayOf(
            Candidate("Cloudflare", "1.1.1.1"),
            Candidate("Cloudflare 2", "1.0.0.1"),
            Candidate("Google", "8.8.8.8"),
            Candidate("Google 2", "8.8.4.4"),
            Candidate("Quad9", "9.9.9.9"),
            Candidate("Quad9 2", "149.112.112.112"),
            Candidate("AdGuard", "94.140.14.14"),
            Candidate("AdGuard 2", "94.140.15.15"),
            Candidate("OpenDNS", "208.67.222.222"),
            Candidate("OpenDNS 2", "208.67.220.220"),
            Candidate("Control D", "76.76.2.0"),
            Candidate("Control D 2", "76.76.10.0"),
            Candidate("CleanBrowsing", "185.228.168.9"),
            Candidate("CleanBrowsing 2", "185.228.169.9"),
            Candidate("Mullvad", "194.242.2.2"),
            Candidate("DNS0", "193.110.81.0"),
            Candidate("DNS0 2", "185.253.5.0"),
            Candidate("Verisign", "64.6.64.6"),
            Candidate("Verisign 2", "64.6.65.6"),
            Candidate("Hurricane Electric", "74.82.42.42"),
        )

        @Volatile
        private var instance: AutoDnsManager? = null

        @JvmStatic
        fun get(context: Context): AutoDnsManager =
            instance ?: synchronized(this) {
                instance ?: AutoDnsManager(context.applicationContext).also { instance = it }
            }

        private fun query(
            resolverIp: String,
            hostname: String,
            type: Int,
            timeoutMs: Int,
        ): List<InetAddress> {
            val id = RANDOM.nextInt(65_536)
            val request = buildQuery(id, hostname, type)
            val buffer = ByteArray(2_048)
            DatagramSocket().use { socket ->
                socket.soTimeout = timeoutMs
                val resolver = InetAddress.getByName(resolverIp)
                socket.send(DatagramPacket(request, request.size, resolver, 53))
                val response = DatagramPacket(buffer, buffer.size)
                socket.receive(response)
                return parseResponse(buffer.copyOf(response.length), id, type)
            }
        }

        private fun buildQuery(id: Int, hostname: String, type: Int): ByteArray {
            val output = ByteArrayOutputStream()
            output.write((id ushr 8) and 0xff)
            output.write(id and 0xff)
            output.write(0x01)
            output.write(0x00)
            output.write(0x00)
            output.write(0x01)
            output.write(ByteArray(6))
            hostname.split('.').forEach { label ->
                val encoded = label.toByteArray(StandardCharsets.US_ASCII)
                require(encoded.isNotEmpty() && encoded.size <= 63) { "host" }
                output.write(encoded.size)
                output.write(encoded)
            }
            output.write(0)
            output.write((type ushr 8) and 0xff)
            output.write(type and 0xff)
            output.write(0)
            output.write(1)
            return output.toByteArray()
        }

        private fun parseResponse(data: ByteArray, expectedId: Int, type: Int): List<InetAddress> {
            if (data.size < 12 || readU16(data, 0) != expectedId || (data[3].toInt() and 0x0f) != 0) {
                return emptyList()
            }
            val questions = readU16(data, 4)
            val answers = readU16(data, 6)
            var offset = 12
            repeat(questions) {
                offset = skipName(data, offset) + 4
                if (offset > data.size) return emptyList()
            }
            val result = ArrayList<InetAddress>()
            repeat(answers) {
                if (offset >= data.size) return@repeat
                offset = skipName(data, offset)
                if (offset + 10 > data.size) return@repeat
                val answerType = readU16(data, offset)
                val answerClass = readU16(data, offset + 2)
                val length = readU16(data, offset + 8)
                offset += 10
                if (offset + length > data.size) return@repeat
                if (
                    answerType == type && answerClass == 1 &&
                    ((type == 1 && length == 4) || (type == 28 && length == 16))
                ) {
                    result.add(InetAddress.getByAddress(data.copyOfRange(offset, offset + length)))
                }
                offset += length
            }
            return result
        }

        private fun skipName(data: ByteArray, start: Int): Int {
            var offset = start
            while (offset < data.size) {
                val length = data[offset].toInt() and 0xff
                if ((length and 0xc0) == 0xc0) return minOf(data.size, offset + 2)
                offset++
                if (length == 0) return offset
                if (length > 63 || offset + length > data.size) return data.size
                offset += length
            }
            return data.size
        }

        private fun readU16(data: ByteArray, offset: Int): Int =
            ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)
    }

    private val context = context.applicationContext
    private val preferences: SharedPreferences =
        this.context.getSharedPreferences("nautrix_dns", Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val benchmarking = AtomicBoolean()
    private val addressCache = ConcurrentHashMap<String, CachedAddresses>()

    // Uses the shared coroutine IO scheduler rather than owning nine long-lived executor threads.
    private val benchmarkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(8))

    @Volatile
    private var selected: Candidate = findCandidate(
        preferences.getString("selected_ip", CANDIDATES[0].ip),
    ) ?: CANDIDATES[0]

    @Volatile
    private var selectedLatencyMs: Int = preferences.getInt("selected_ms", -1)

    @Volatile
    private var selectedSuccesses: Int = preferences.getInt("selected_successes", 0)

    @Volatile
    private var externalDnsAvailable: Boolean = selectedLatencyMs >= 0

    @Volatile
    private var state: String = if (selectedLatencyMs >= 0) {
        statusForSelected()
    } else {
        "DNS inicial: ${selected.name}"
    }

    private val localProxy = LocalHttpProxy(this)

    init {
        registerNetworkChanges()
    }

    fun installWebViewProxy(ready: Runnable?) {
        val completion = ready ?: Runnable { }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            state = "WebView sem suporte ao DNS automático; usando DNS do sistema"
            mainHandler.post(completion)
            return
        }
        try {
            val port = localProxy.start()
            val config = ProxyConfig.Builder()
                .addProxyRule("127.0.0.1:$port")
                .build()
            ProxyController.getInstance().setProxyOverride(
                config,
                Executor { command -> mainHandler.post(command) },
                completion,
            )
        } catch (_: Exception) {
            state = "Proxy DNS indisponível; usando DNS do sistema"
            mainHandler.post(completion)
        }
    }

    fun benchmarkAsync(force: Boolean, completion: Runnable?) {
        val last = preferences.getLong("last_benchmark", 0L)
        if (!force && System.currentTimeMillis() - last < BENCHMARK_VALID_MS) {
            completion?.let(mainHandler::post)
            return
        }
        if (!benchmarking.compareAndSet(false, true)) return
        state = "Testando 20 servidores DNS…"

        benchmarkScope.launch {
            try {
                val winner = CANDIDATES
                    .map { candidate -> async { benchmark(candidate) } }
                    .awaitAll()
                    .asSequence()
                    .filter { DnsScorePolicy.isStable(it.successes) }
                    .minByOrNull { it.score }

                if (winner != null) {
                    selected = winner.candidate
                    selectedLatencyMs = winner.averageMs
                    selectedSuccesses = winner.successes
                    externalDnsAvailable = true
                    addressCache.clear()
                    preferences.edit()
                        .putString("selected_ip", selected.ip)
                        .putInt("selected_ms", selectedLatencyMs)
                        .putInt("selected_successes", selectedSuccesses)
                        .putLong("last_benchmark", System.currentTimeMillis())
                        .apply()
                    state = statusForSelected()
                } else {
                    externalDnsAvailable = false
                    state = "DNS externos bloqueados; fallback automático para o sistema"
                }
            } finally {
                benchmarking.set(false)
                completion?.let(mainHandler::post)
            }
        }
    }

    fun status(): String = state

    @Throws(java.net.UnknownHostException::class)
    fun resolveAll(hostname: String?): List<InetAddress> {
        if (hostname.isNullOrBlank()) throw java.net.UnknownHostException("empty hostname")
        if (hostname.indexOf(':') >= 0 || hostname.matches(Regex("[0-9.]+"))) {
            return listOf(InetAddress.getByName(hostname))
        }

        val now = System.currentTimeMillis()
        addressCache[hostname]?.takeIf { it.expiresAt > now }?.let { return it.addresses }

        val addresses = ArrayList<InetAddress>()
        val resolver = selected
        if (externalDnsAvailable) {
            try {
                addresses.addAll(query(resolver.ip, hostname, 1, DNS_TIMEOUT_MS))
                addresses.addAll(query(resolver.ip, hostname, 28, DNS_TIMEOUT_MS))
            } catch (_: Exception) {
            }
        }
        if (addresses.isEmpty()) addresses.addAll(InetAddress.getAllByName(hostname))

        val immutable = addresses.toList()
        addressCache[hostname] = CachedAddresses(immutable, now + DNS_CACHE_MS)
        return immutable
    }

    private fun benchmark(candidate: Candidate): Benchmark {
        var total = 0L
        var minimum = Long.MAX_VALUE
        var maximum = 0L
        var successes = 0
        repeat(3) {
            val started = System.nanoTime()
            try {
                val answer = query(candidate.ip, "example.com", 1, DNS_TIMEOUT_MS)
                if (answer.isEmpty()) return@repeat
                val elapsed = maxOf(1L, (System.nanoTime() - started) / 1_000_000L)
                total += elapsed
                minimum = minOf(minimum, elapsed)
                maximum = maxOf(maximum, elapsed)
                successes++
            } catch (_: Exception) {
            }
        }
        val average = if (successes == 0) Int.MAX_VALUE else (total / successes).toInt()
        val score = DnsScorePolicy.score(average, minimum, maximum, successes, DNS_TIMEOUT_MS)
        return Benchmark(candidate, successes, average, score)
    }

    private fun statusForSelected(): String =
        "Automático: ${selected.name} (${selected.ip}) • $selectedLatencyMs ms • " +
            "estabilidade $selectedSuccesses/3"

    private fun findCandidate(ip: String?): Candidate? = CANDIDATES.firstOrNull { it.ip == ip }

    private fun registerNetworkChanges() {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        try {
            manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    benchmarkAsync(true, null)
                }
            })
        } catch (_: Exception) {
        }
    }

    private data class Candidate(val name: String, val ip: String)
    private data class Benchmark(
        val candidate: Candidate,
        val successes: Int,
        val averageMs: Int,
        val score: Long,
    )
    private data class CachedAddresses(val addresses: List<InetAddress>, val expiresAt: Long)
}
