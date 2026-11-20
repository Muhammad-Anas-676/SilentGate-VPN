package com.silentgatevpn.app.vpn

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Cheap reachability test run BEFORE the tunnel starts: DoH-resolve, TCP connect, and a TLS handshake where possible. */
object Prober {

    const val UNTESTED = 0L

    /** Returns latency in ms, [UNTESTED] for UDP based nodes (cannot be tested without the core), or -1 when unreachable. */
    suspend fun preflight(n: Node, timeoutMs: Int = 2500): Long = withContext(Dispatchers.IO) {
        if (n.udp && n.transport == "udp") return@withContext UNTESTED
        val ip = Net.resolveHost(n.server) ?: return@withContext -1L
        val t0 = System.currentTimeMillis()
        runCatching {
            val plainTls = n.security == "tls" && n.transport != "udp"
            Socket().use { s ->
                s.connect(InetSocketAddress(ip, n.port), timeoutMs)
                if (plainTls) {
                    val sni = sniOf(n)
                    val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(s, sni, n.port, false) as SSLSocket
                    ssl.soTimeout = timeoutMs
                    val params = ssl.sslParameters
                    params.serverNames = listOf(SNIHostName(sni))
                    ssl.sslParameters = params
                    ssl.startHandshake()
                    val ok = HttpsURLConnection.getDefaultHostnameVerifier().verify(sni, ssl.session)
                    if (!ok) return@withContext -1L
                }
            }
            (System.currentTimeMillis() - t0).coerceAtLeast(1)
        }.getOrDefault(-1L)
    }

    private fun sniOf(n: Node): String {
        val j = runCatching { org.json.JSONObject(n.raw) }.getOrNull()
        val fromJson = j?.optJSONObject("tls")?.optString("server_name").orEmpty()
        if (fromJson.isNotEmpty()) return fromJson
        val m = Regex("[?&](?:sni|peer)=([^&#]+)").find(n.raw)
        return m?.groupValues?.get(1)?.takeIf { it.isNotEmpty() } ?: n.server
    }
}
