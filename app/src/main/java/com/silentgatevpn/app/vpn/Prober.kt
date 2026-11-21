package com.silentgatevpn.app.vpn

import com.silentgatevpn.app.SilentGateApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Cheap reachability and quality test run BEFORE the tunnel starts: DoH-resolve, TCP connect, a TLS handshake where
 * possible, then a small burst of parallel connections to catch nodes that reset or throttle concurrent connections
 * (a browser opens 20 to 30 of them for video thumbnails).
 */
object Prober {

    const val UNTESTED = 0L

    /** The node is reachable but drops or resets parallel connections. */
    const val THROTTLED = -2L

    /** A single handshake slower than this marks the node as overloaded (it is tried last, not first). */
    const val SLOW_MS = 1_800L

    private const val BURST_EXTRA = 4      // parallel handshakes after the first one succeeded
    private const val BURST_MIN_OK = 3     // of BURST_EXTRA

    // Budget phones / Android 7.0 to 9: fewer parallel sockets and a more forgiving time limit, so a slow CPU does
    // not mark a reachable node as THROTTLED.
    private const val LOW_BURST_EXTRA = 2
    private const val LOW_BURST_MIN_OK = 2
    private const val LOW_TIMEOUT_MS = 3_000

    /**
     * Returns latency in ms of the first handshake, [UNTESTED] for UDP based nodes (cannot be tested without the
     * core), -1 when unreachable, or [THROTTLED] when the parallel burst fails.
     * Every socket takes a permit from [gate], so the whole pool of probes never exceeds the caller's cap
     * (6 on budget phones) even though each node opens several connections.
     */
    suspend fun preflight(n: Node, timeoutMs: Int = 2500, gate: Semaphore? = null): Long = withContext(Dispatchers.IO) {
        if (n.udp && n.transport == "udp") return@withContext UNTESTED
        val ip = Net.resolveHost(n.server) ?: return@withContext -1L

        val low = SilentGateApp.isLowEndDevice
        val t = if (low) maxOf(timeoutMs, LOW_TIMEOUT_MS) else timeoutMs
        val extra = if (low) LOW_BURST_EXTRA else BURST_EXTRA
        val minOk = if (low) LOW_BURST_MIN_OK else BURST_MIN_OK

        val first = gated(gate) { handshake(n, ip, t) }
        if (first < 0) return@withContext -1L

        val burst = coroutineScope {
            (1..extra).map { async(Dispatchers.IO) { gated(gate) { handshake(n, ip, t) } } }.awaitAll()
        }
        if (burst.count { it >= 0 } < minOk) return@withContext THROTTLED
        first
    }

    private suspend fun gated(gate: Semaphore?, block: suspend () -> Long): Long =
        if (gate == null) block() else gate.withPermit { block() }

    /** One connection: TCP connect (+ TLS handshake for plain TLS nodes). Returns elapsed ms, or -1 on failure. */
    private fun handshake(n: Node, ip: String, timeoutMs: Int): Long {
        val t0 = System.currentTimeMillis()
        // TLS is verified on every Android version (API 24+). SilentGateApp.installModernTls() installs Conscrypt and a
        // composite trust store through SSLContext.setDefault(), so we take the factory from SSLContext.getDefault()
        // (SSLSocketFactory.getDefault() can return the stale platform factory on Android 7.0 to 8.1).
        val plainTls = n.security == "tls" && n.transport != "udp"
        return runCatching {
            Socket().use { s ->
                s.connect(InetSocketAddress(ip, n.port), timeoutMs)
                if (plainTls) {
                    val sni = sniOf(n)
                    val ssl = socketFactory().createSocket(s, sni, n.port, false) as SSLSocket
                    ssl.soTimeout = timeoutMs
                    val params = ssl.sslParameters
                    params.serverNames = listOf(SNIHostName(sni))
                    ssl.sslParameters = params
                    ssl.startHandshake()
                    if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(sni, ssl.session)) return@runCatching -1L
                }
            }
            (System.currentTimeMillis() - t0).coerceAtLeast(1)
        }.getOrDefault(-1L)
    }

    private fun socketFactory(): SSLSocketFactory =
        runCatching { SSLContext.getDefault().socketFactory }.getOrNull()
            ?: (SSLSocketFactory.getDefault() as SSLSocketFactory)

    private fun sniOf(n: Node): String {
        val j = runCatching { org.json.JSONObject(n.raw) }.getOrNull()
        val fromJson = j?.optJSONObject("tls")?.optString("server_name").orEmpty()
        if (fromJson.isNotEmpty()) return fromJson
        // Same order as ProxyParser.applyTls(): sni -> peer -> host -> server.
        for (key in listOf("sni", "peer", "host")) {
            val v = Regex("[?&]$key=([^&#]*)").find(n.raw)?.groupValues?.get(1).orEmpty()
            val d = runCatching { java.net.URLDecoder.decode(v, "UTF-8") }.getOrDefault(v).trim()
            if (d.isNotEmpty()) return d
        }
        return n.server
    }
}
