package com.silentgatevpn.app.vpn

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

data class Trace(val ip: String, val loc: String, val colo: String, val warp: Boolean, val rttMs: Long)

/** Result of the real-exit check that decides whether the app may say "Protected". */
data class Verify(
    val ok: Boolean,
    val reason: String,
    val ip: String = "",
    val cc: String = "",
    val rc: String = "",
    val rttMs: Long = -1,
    val ipChanged: Boolean = true,
    val geoLocal: Boolean = false
)

object Net {

    /** Measured before the tunnel starts; refreshed on every connect. The country default is a last resort. */
    @Volatile var home: HomeInfo = HomeInfo("", "PK")
    @Volatile var defaultHomeCc: String = "PK"

    fun isRestricted(countryCode: String, regionCode: String): Boolean = Geo.isRestricted(countryCode, regionCode)

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Fire-and-forget work that must not hold up its caller when cancelled (blocking sockets ignore cancellation). */
    fun launchIo(block: suspend CoroutineScope.() -> Unit) = ioScope.launch(block = block)

    suspend fun http(url: String, timeoutMs: Int = 8000): String = withContext(Dispatchers.IO) {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.requestMethod = "GET"
            c.setRequestProperty("User-Agent", "SilentGateVPN/1.1")
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    /** Fetches the first KB of a URL, which is enough to prove a CDN delivers assets through the tunnel. */
    private suspend fun fetchOk(url: String, timeoutMs: Int): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = timeoutMs
                c.readTimeout = timeoutMs
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36")
                c.setRequestProperty("Range", "bytes=0-1023")
                val code = c.responseCode
                if (code in 200..299) {
                    val buf = ByteArray(1024)
                    runCatching { c.inputStream.use { it.read(buf) } }
                    true
                } else false
            } finally {
                c.disconnect()
            }
        }.getOrDefault(false)
    }

    suspend fun cfTrace(timeoutMs: Int = 8000): Trace {
        val t0 = System.currentTimeMillis()
        val body = http("https://1.1.1.1/cdn-cgi/trace", timeoutMs)
        val rtt = System.currentTimeMillis() - t0
        val map = body.lineSequence().mapNotNull {
            val i = it.indexOf('=')
            if (i > 0) it.substring(0, i) to it.substring(i + 1) else null
        }.toMap()
        val ip = map["ip"] ?: error("no ip in trace")
        return Trace(ip, map["loc"] ?: "", map["colo"] ?: "", (map["warp"] ?: "off") != "off", rtt)
    }

    // ------------------------------------------------------------------ home (ISP side) info

    /** Real IP and country as the internet sees them WITHOUT the tunnel. Call before the VPN starts. */
    suspend fun homeInfo(): HomeInfo {
        val got = runCatching { cfTrace(3500) }.getOrNull()
        val h = if (got != null && got.ip.isNotEmpty()) {
            HomeInfo(got.ip, got.loc.ifEmpty { defaultHomeCc })
        } else {
            val ip = runCatching { http("https://api.ipify.org", 3500).trim() }.getOrDefault("")
            HomeInfo(ip.ifEmpty { home.ip }, home.cc.ifEmpty { defaultHomeCc })
        }
        home = h
        return h
    }

    // ------------------------------------------------------------------ DNS over HTTPS bootstrap (IP literals only)

    private fun dnsQuery(host: String): ByteArray {
        val out = ByteArrayOutputStream()
        val id = Random.nextInt(0, 65535)
        out.write(id shr 8); out.write(id and 0xFF)
        out.write(0x01); out.write(0x00)           // recursion desired
        out.write(0); out.write(1)                  // QDCOUNT
        repeat(6) { out.write(0) }                  // AN/NS/AR counts
        for (label in host.trim('.').split('.')) {
            val b = label.toByteArray(Charsets.US_ASCII)
            out.write(b.size); out.write(b)
        }
        out.write(0)
        out.write(0); out.write(1)                  // type A
        out.write(0); out.write(1)                  // class IN
        return out.toByteArray()
    }

    private fun parseA(r: ByteArray): String? {
        if (r.size < 12) return null
        fun u16(i: Int) = ((r[i].toInt() and 0xFF) shl 8) or (r[i + 1].toInt() and 0xFF)
        val qd = u16(4)
        val an = u16(6)
        var i = 12
        fun skipName() {
            while (i < r.size) {
                val l = r[i].toInt() and 0xFF
                if (l == 0) { i++; return }
                if (l and 0xC0 == 0xC0) { i += 2; return }
                i += l + 1
            }
        }
        repeat(qd) { skipName(); i += 4 }
        repeat(an) {
            if (i >= r.size) return null
            skipName()
            if (i + 10 > r.size) return null
            val type = u16(i)
            val len = u16(i + 8)
            i += 10
            if (type == 1 && len == 4 && i + 4 <= r.size) {
                return "${r[i].toInt() and 0xFF}.${r[i + 1].toInt() and 0xFF}.${r[i + 2].toInt() and 0xFF}.${r[i + 3].toInt() and 0xFF}"
            }
            i += len
        }
        return null
    }

    private suspend fun dohA(host: String, p: DnsProvider, timeoutMs: Int): String? = withContext(Dispatchers.IO) {
        val c = URL("https://${p.server}/dns-query").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/dns-message")
            c.setRequestProperty("Accept", "application/dns-message")
            c.outputStream.use { it.write(dnsQuery(host)) }
            if (c.responseCode != 200) return@withContext null
            parseA(c.inputStream.use { it.readBytes() })
        } finally {
            c.disconnect()
        }
    }

    /** Runs all tasks in parallel and returns the first non-null result without waiting for the slow ones. */
    private suspend fun <T : Any> firstSuccess(tasks: List<suspend () -> T?>): T? {
        val ch = Channel<T?>(tasks.size)
        val jobs = tasks.map { t -> ioScope.launch { ch.send(runCatching { t() }.getOrNull()) } }
        var result: T? = null
        var left = tasks.size
        while (left > 0 && result == null) { result = ch.receive(); left-- }
        jobs.forEach { it.cancel() }
        return result
    }

    @Volatile private var bootstrap: List<DnsProvider> = DnsProvider.entries
    private val dnsCache = ConcurrentHashMap<String, Pair<String, Long>>()

    /**
     * Probes every bootstrap resolver (Cloudflare, Quad9, AdGuard, Google, all IP literals, outside the tunnel on the
     * physical network) and returns them fastest first, so a blocked 1.1.1.1 never stops the tunnel from starting.
     */
    suspend fun bootstrapOrder(preferred: DnsProvider): List<DnsProvider> {
        val order = ArrayList<DnsProvider>()
        val ch = Channel<DnsProvider?>(DnsProvider.entries.size)
        val jobs = DnsProvider.entries.map { p ->
            ioScope.launch { ch.send(runCatching { dohA("www.gstatic.com", p, 2500) }.getOrNull()?.let { p }) }
        }
        withTimeoutOrNull(2800) {
            var left = DnsProvider.entries.size
            while (left > 0) { ch.receive()?.let { order.add(it) }; left-- }
        }
        jobs.forEach { it.cancel() }
        val result = if (order.isEmpty()) {
            listOf(preferred) + DnsProvider.entries.filter { it != preferred }
        } else {
            order + DnsProvider.entries.filter { it !in order }
        }
        bootstrap = result
        VpnState.log("Bootstrap DNS: " + (if (order.isEmpty()) "none answered" else order.joinToString { it.server }))
        return result
    }

    /** Resolves a server name to IPv4 over DoH (racing all bootstrap resolvers); falls back to the system resolver. */
    suspend fun resolveHost(host: String): String? {
        if (ProxyParser.isIpLiteral(host)) return host
        val now = System.currentTimeMillis()
        dnsCache[host]?.let { if (it.second > now) return it.first }
        val tasks: List<suspend () -> String?> = bootstrap.take(4).map { p -> { dohA(host, p, 2500) } }
        var ip = firstSuccess(tasks)
        if (ip == null) {
            ip = withTimeoutOrNull(2500) {
                withContext(Dispatchers.IO) {
                    runCatching { InetAddress.getAllByName(host).firstOrNull { it is Inet4Address }?.hostAddress }.getOrNull()
                }
            }
        }
        if (ip != null) dnsCache[host] = ip to (now + 10 * 60_000L)
        return ip
    }

    // ------------------------------------------------------------------ tunnel readiness and real-exit verification

    // Hostname probes: succeed only if DNS works through the tunnel AND traffic flows.
    private val HOST_PROBES = listOf(
        "https://cp.cloudflare.com/generate_204",
        "https://www.gstatic.com/generate_204",
        "https://connectivitycheck.gstatic.com/generate_204"
    )
    // IP probes: need no DNS. If these pass while host probes fail, the tunnel is fine but DNS is broken.
    private val IP_PROBES = listOf(
        "https://1.1.1.1/cdn-cgi/trace",
        "https://8.8.8.8/resolve?name=example.com&type=A"
    )
    // Image/script CDNs, so "connected" means thumbnails and assets load, not just generate_204.
    private val CDN_PROBES = listOf(
        "https://www.gstatic.com/generate_204",
        "https://www.google.com/favicon.ico",
        "https://cdn.jsdelivr.net/npm/jquery@3.7.1/dist/jquery.min.js",
        "https://upload.wikimedia.org/wikipedia/commons/4/47/PNG_transparency_demonstration_1.png"
    )

    private suspend fun anyOk(urls: List<String>, timeoutMs: Int): Boolean = coroutineScope {
        urls.map { u -> async(Dispatchers.IO) { runCatching { http(u, timeoutMs) }.isSuccess } }.awaitAll().any { it }
    }

    /** True when real websites (by name) are reachable through the tunnel. */
    suspend fun probe(timeoutMs: Int = 6000): Boolean = anyOk(HOST_PROBES, timeoutMs)

    enum class TunnelResult { OK, DNS_BROKEN, NO_TRAFFIC }

    /** Polls until names resolve and traffic flows, DNS is detected as broken, or the time runs out. */
    suspend fun waitForTunnel(timeoutMs: Long): TunnelResult {
        val end = System.currentTimeMillis() + timeoutMs
        delay(500)
        var dnsOnly = 0
        while (System.currentTimeMillis() < end) {
            if (probe(4000)) return TunnelResult.OK
            if (anyOk(IP_PROBES, 3500)) { if (++dnsOnly >= 2) return TunnelResult.DNS_BROKEN } else dnsOnly = 0
            delay(600)
        }
        return TunnelResult.NO_TRAFFIC
    }

    /**
     * Pre-flight for "Protected": the exit IP must differ from the ISP IP, the exit region must be allowed, and at
     * least two independent CDNs must deliver real assets. [enforce] = false only records the result.
     */
    suspend fun verifyExit(home: HomeInfo, s: AppSettings, expectMasked: Boolean, enforce: Boolean, wantCc: String): Verify = coroutineScope {
        val traceJob = async(Dispatchers.IO) { runCatching { cfTrace(4500) }.getOrNull() ?: runCatching { cfTrace(4500) }.getOrNull() }
        val cdnJob = async(Dispatchers.IO) {
            CDN_PROBES.map { u -> async(Dispatchers.IO) { fetchOk(u, 5000) } }.awaitAll().count { it }
        }
        val t = traceJob.await() ?: return@coroutineScope Verify(false, "the exit did not answer the location check")
        val changed = home.ip.isEmpty() || t.ip != home.ip
        if (expectMasked && !changed) return@coroutineScope Verify(false, "your IP did not change, traffic is not going through the tunnel", t.ip, t.loc, "", t.rttMs, false)
        val cc = t.loc
        var rc = ""
        if (cc.equals("US", true)) {
            rc = runCatching { JSONObject(http("https://ipwho.is/", 4500)).optString("region_code") }.getOrDefault("")
        }
        val reason = Geo.excludedReason(cc, rc, home.cc, s.avoidRestricted, s.allowHomeExit)
        val geoLocal = cc.isNotEmpty() && cc.equals(home.cc, true)
        if (enforce && reason != null) return@coroutineScope Verify(false, reason, t.ip, cc, rc, t.rttMs, changed, geoLocal)
        if (enforce && wantCc != "AUTO" && cc.isNotEmpty() && !cc.equals(wantCc, true)) {
            return@coroutineScope Verify(false, "exit is ${Geo.name(cc)}, you asked for ${Geo.name(wantCc)}", t.ip, cc, rc, t.rttMs, changed, geoLocal)
        }
        val cdn = cdnJob.await()
        if (enforce && cdn < 2) return@coroutineScope Verify(false, "image/CDN hosts do not load through this node ($cdn of ${CDN_PROBES.size})", t.ip, cc, rc, t.rttMs, changed, geoLocal)
        Verify(true, "", t.ip, cc, rc, t.rttMs, changed, geoLocal)
    }

    suspend fun pingMedian(count: Int = 3): Long {
        val list = mutableListOf<Long>()
        repeat(count) { runCatching { list += cfTrace(5000).rttMs } }
        return if (list.isEmpty()) -1 else list.sorted()[list.size / 2]
    }

    suspend fun exitInfo(): ExitInfo {
        val trace = cfTrace()
        var country = ""; var cc = trace.loc; var region = ""; var rc = ""; var city = ""
        runCatching {
            val j = JSONObject(http("https://ipwho.is/"))
            if (j.optBoolean("success", true)) {
                country = j.optString("country"); cc = j.optString("country_code", cc)
                region = j.optString("region"); rc = j.optString("region_code"); city = j.optString("city")
            }
        }
        val ping = pingMedian(2)
        val h = home
        return ExitInfo(
            trace.ip, country.ifEmpty { cc }, cc, region, rc, city, trace.warp, trace.colo, ping, isRestricted(cc, rc),
            geoLocal = cc.isNotEmpty() && cc.equals(h.cc, true), ipChanged = h.ip.isEmpty() || trace.ip != h.ip
        )
    }

    // ------------------------------------------------------------------ leak checks

    data class DnsLeak(
        val resolverIp: String,
        val resolverGeo: String,
        val leaking: Boolean,
        val matchesProvider: Boolean = false,
        val verdict: String = ""
    )

    private val LOCAL_ISP_WORDS = listOf(
        "pakistan", "ptcl", "jazz", "mobilink", "zong", "cmpak", "telenor", "ufone", "nayatel",
        "stormfiber", "wateen", "cybernet", "transworld", "worldcall", "multinet", "nexlinx", "sitara"
    )

    /**
     * Compares the resolver the internet sees with the encrypted resolver we selected. A local ISP resolver is a leak;
     * a different public resolver is reported as a warning because the tunnel's own DNS did not answer.
     */
    suspend fun dnsLeak(expected: DnsProvider = SettingsStore.value.dns, homeCc: String = home.cc): DnsLeak {
        val label = (1..12).map { "abcdefghijklmnopqrstuvwxyz0123456789"[Random.nextInt(36)] }.joinToString("")
        val j = JSONObject(http("https://$label.edns.ip-api.com/json", 10000))
        val d = j.optJSONObject("dns") ?: error("no dns data")
        val geo = d.optString("geo")
        val ip = d.optString("ip")
        val low = geo.lowercase()
        val matches = expected.keywords.any { low.contains(it) }
        val anyDoh = DnsProvider.entries.any { p -> p.keywords.any { low.contains(it) } }
        val local = low.contains(Geo.name(homeCc).lowercase()) || LOCAL_ISP_WORDS.any { low.contains(it) }
        val verdict = when {
            local -> "A resolver in your home country answered: DNS is leaking outside the tunnel."
            matches -> "The selected encrypted resolver (${expected.label}) answered through the tunnel."
            anyDoh -> "A public encrypted resolver answered, but not the one selected (${expected.label})."
            else -> "An unrecognised resolver answered. It is not in your home country."
        }
        return DnsLeak(ip, geo, local, matches, verdict)
    }

    /** Returns the IPv6 address the internet sees, or null when IPv6 is unreachable (expected while IPv6 is blocked). */
    suspend fun ipv6Probe(): String? = runCatching {
        JSONObject(http("https://api6.ipify.org?format=json", 5000)).optString("ip").ifEmpty { null }
    }.getOrNull()

    data class Candidate(val ip: String, val ms: Long)

    /** TCP/443 reachability scan of Cloudflare edge ranges, useful to pick a clean address for a Worker profile. */
    suspend fun scanCloudflare(samplesPerRange: Int = 14, onProgress: (Int, Int) -> Unit = { _, _ -> }): List<Candidate> {
        val ranges = listOf("188.114.96", "188.114.97", "188.114.98", "188.114.99", "162.159.192", "162.159.195", "104.16.0", "172.67.0")
        val ips = ranges.flatMap { r -> (1..254).shuffled().take(samplesPerRange).map { "$r.$it" } }
        val sem = Semaphore(24)
        var done = 0
        return coroutineScope {
            ips.map { ip ->
                async(Dispatchers.IO) {
                    sem.withPermit {
                        val t0 = System.currentTimeMillis()
                        val ok = runCatching {
                            Socket().use { it.connect(InetSocketAddress(ip, 443), 1500) }
                        }.isSuccess
                        synchronized(this@Net) { done++; onProgress(done, ips.size) }
                        if (ok) Candidate(ip, System.currentTimeMillis() - t0) else null
                    }
                }
            }.awaitAll().filterNotNull().sortedBy { it.ms }.take(8)
        }
    }

    data class SiteStep(val title: String, val ok: Boolean?, val detail: String)

    /** Step-by-step reachability test of one site through the current network path (DNS, TCP 443, HTTPS). */
    suspend fun siteTest(input: String): List<SiteStep> = withContext(Dispatchers.IO) {
        val out = mutableListOf<SiteStep>()
        val host = input.trim().removePrefix("https://").removePrefix("http://")
            .substringBefore('/').substringBefore('?').trim()
        if (host.isEmpty() || host.any { it.isWhitespace() }) {
            out += SiteStep("Input", false, "Enter a domain like example.com")
            return@withContext out
        }

        // 1. DNS
        val d0 = System.currentTimeMillis()
        val addrs = try {
            InetAddress.getAllByName(host).toList()
        } catch (e: Exception) {
            out += SiteStep("DNS", false, "Could not resolve $host. The domain is down or DNS is blocked.")
            return@withContext out
        }
        val bogus = addrs.any { it.isAnyLocalAddress || it.isLoopbackAddress || it.isSiteLocalAddress || it.isLinkLocalAddress }
        out += SiteStep(
            "DNS", !bogus,
            (if (bogus) "Answer is a fake/private address (DNS poisoned): " else "Resolved in ${System.currentTimeMillis() - d0} ms: ") +
                addrs.take(3).joinToString { it.hostAddress ?: "" }
        )
        if (bogus) return@withContext out

        // 2. TCP 443
        val target = addrs.firstOrNull { it is Inet4Address } ?: addrs.first()
        val t0 = System.currentTimeMillis()
        try {
            Socket().use { it.connect(InetSocketAddress(target, 443), 7000) }
            out += SiteStep("TCP 443", true, "Connected to ${target.hostAddress} in ${System.currentTimeMillis() - t0} ms")
        } catch (e: SocketTimeoutException) {
            out += SiteStep("TCP 443", false, "Timed out. Packets to ${target.hostAddress} are dropped (IP blocked on this path).")
            return@withContext out
        } catch (e: Exception) {
            out += SiteStep("TCP 443", false, "Connection refused or reset: ${e.message ?: e.javaClass.simpleName}")
            return@withContext out
        }

        // 3. HTTPS request with a browser-like user agent
        val c = URL("https://$host/").openConnection() as HttpURLConnection
        try {
            c.instanceFollowRedirects = false
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
            )
            c.setRequestProperty("Accept", "text/html,*/*")
            val code = c.responseCode
            val server = c.getHeaderField("Server") ?: ""
            val mitigated = c.getHeaderField("cf-mitigated") ?: ""
            val loc = c.getHeaderField("Location") ?: ""
            val stream = if (code >= 400) c.errorStream else c.inputStream
            val body = runCatching {
                val buf = ByteArray(4096)
                val n = stream?.use { it.read(buf) } ?: 0
                String(buf, 0, n.coerceAtLeast(0)).lowercase()
            }.getOrDefault("")
            val info = "HTTP $code" + (if (server.isNotEmpty()) " · server: $server" else "") +
                (if (loc.isNotEmpty()) " · redirect to ${loc.take(60)}" else "")
            val verdict: Pair<Boolean?, String> = when {
                mitigated.equals("challenge", true) || "just a moment" in body || "captcha" in body ->
                    false to "$info. The site shows a Cloudflare/captcha challenge to this IP. It often blocks VPN/WARP addresses, so the page may look blank."
                body.contains("pakistan telecommunication") || body.contains("pta") && body.contains("block") ->
                    false to "$info. This is an ISP/PTA block page. Traffic is not going through the tunnel."
                code == 451 -> false to "$info. Blocked for legal reasons (451)."
                code == 403 -> false to "$info. The site refuses this IP address (it blocks the VPN exit), not your ISP."
                code in 200..399 -> true to "$info. The site answers normally. If the browser still shows nothing, try Incognito or turn off QUIC in Chrome."
                else -> null to info
            }
            out += SiteStep("HTTPS", verdict.first, verdict.second)
        } catch (e: SocketTimeoutException) {
            out += SiteStep("HTTPS", false, "TCP connects but no reply: the connection is silently dropped after the handshake (DPI or MTU problem).")
        } catch (e: SSLException) {
            out += SiteStep("HTTPS", false, "TLS handshake failed (${e.message?.take(80)}). The connection is being reset or tampered with.")
        } catch (e: Exception) {
            out += SiteStep("HTTPS", false, "Request failed: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            c.disconnect()
        }
        out
    }
}
