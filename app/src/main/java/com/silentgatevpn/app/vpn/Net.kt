package com.silentgatevpn.app.vpn

import com.silentgatevpn.app.SilentGateApp
import kotlinx.coroutines.CancellationException
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
    val geoLocal: Boolean = false,
    /** Median time of the parallel media fetches in ms, or -1 when the media test did not run. */
    val mediaMs: Long = -1
)

/** Outcome of N simultaneous small fetches across different CDN hosts. */
data class MediaResult(val ok: Int, val total: Int, val medianMs: Long, val p90Ms: Long)

/** Outcome of the content-filter / WAF check. [ok] = false means this exit blocks mainstream uncensored sites. */
data class ContentResult(val ok: Boolean, val detail: String)

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

    private const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    /**
     * Fetches up to [maxBytes] of a URL and returns how long it took in ms, or -1 on any failure (non-2xx, reset,
     * timeout). Reading real bytes (not just the status line) is what exposes a node that throttles or drops data.
     */
    private suspend fun timedFetch(url: String, timeoutMs: Int, maxBytes: Int): Long = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        runCatching {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = timeoutMs
                c.readTimeout = timeoutMs
                c.setRequestProperty("User-Agent", BROWSER_UA)
                c.setRequestProperty("Range", "bytes=0-${maxBytes - 1}")
                val code = c.responseCode
                if (code in 200..299) {
                    val buf = ByteArray(4096)
                    var total = 0
                    c.inputStream.use { ins ->
                        while (total < maxBytes) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            total += n
                        }
                    }
                    System.currentTimeMillis() - t0
                } else -1L
            } finally {
                c.disconnect()
            }
        }.getOrDefault(-1L)
    }

    /** Parses Cloudflare's key=value `/cdn-cgi/trace` body; null when it carries no IP (captive page, error text). */
    private fun parseCfTraceBody(body: String, rtt: Long): Trace? {
        val map = body.lineSequence().mapNotNull {
            val i = it.indexOf('=')
            if (i > 0) it.substring(0, i).trim() to it.substring(i + 1).trim() else null
        }.toMap()
        val ip = map["ip"].orEmpty()
        if (ip.isEmpty()) return null
        return Trace(ip, map["loc"].orEmpty().uppercase(), map["colo"].orEmpty(), (map["warp"] ?: "off") != "off", rtt)
    }

    /** Parses the JSON of ipwho.is / ipapi.co (`ip` + `country_code`); null on an error reply or a missing IP. */
    private fun parseGeoJsonBody(body: String, rtt: Long): Trace? {
        val j = JSONObject(body)
        if (!j.optBoolean("success", true) || j.optBoolean("error", false)) return null
        val ip = j.optString("ip").trim()
        if (ip.isEmpty()) return null
        var cc = j.optString("country_code").trim()
        if (cc.isEmpty()) cc = j.optString("country").trim().takeIf { it.length == 2 }.orEmpty()
        return Trace(ip = ip, loc = cc.uppercase(), colo = "", warp = false, rttMs = rtt)
    }

    /** One trace endpoint. The round trip is measured for this request alone, so earlier failures never inflate it. */
    private suspend fun traceAttempt(url: String, timeoutMs: Int, geoJson: Boolean): Trace? {
        val t0 = System.currentTimeMillis()
        return try {
            val body = http(url, timeoutMs)
            val rtt = System.currentTimeMillis() - t0
            if (geoJson) parseGeoJsonBody(body, rtt) else parseCfTraceBody(body, rtt)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Exit IP + country with a fallback chain, so one unreachable endpoint never rejects a working tunnel:
     * 1.1.1.1 (IP literal, no DNS) -> www.cloudflare.com (needs the modern roots / DNS, not 1.1.1.1) -> ipwho.is ->
     * ipapi.co. [timeoutMs] is the budget for the whole chain; each attempt gets a share of what is left, so a fast
     * failure (TLS error on old Android) costs almost nothing and a silent drop cannot eat the whole budget.
     */
    suspend fun cfTrace(timeoutMs: Int = 8000): Trace {
        val deadline = System.currentTimeMillis() + timeoutMs
        val chain = listOf(
            Triple("https://1.1.1.1/cdn-cgi/trace", false, 0.40),
            Triple("https://www.cloudflare.com/cdn-cgi/trace", false, 0.50),
            Triple("https://ipwho.is/", true, 0.70),
            Triple("https://ipapi.co/json/", true, 1.00)
        )
        for ((url, geoJson, share) in chain) {
            val left = deadline - System.currentTimeMillis()
            if (left < 600) break
            traceAttempt(url, (left * share).toInt().coerceAtLeast(600), geoJson)?.let { return it }
        }
        error("no location answer from any trace endpoint")
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

    /** Resolvers that really answered the last bootstrap probe, fastest first (empty when none did). */
    @Volatile var answeredDns: List<DnsProvider> = emptyList()
        private set
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
        answeredDns = order.toList()
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
    // Eight different CDN / asset hosts (two paths on one host, so same-host concurrency is exercised too). Fetched
    // all at once: a node that throttles, resets or drops parallel connections fails here, which is exactly what
    // makes only 1 of 10 thumbnails load on media sites.
    private val MEDIA_PROBES = listOf(
        "https://www.gstatic.com/generate_204",
        "https://www.google.com/favicon.ico",
        "https://cdn.jsdelivr.net/npm/jquery@3.7.1/dist/jquery.min.js",
        "https://cdnjs.cloudflare.com/ajax/libs/jquery/3.7.1/jquery.min.js",
        "https://unpkg.com/jquery@3.7.1/dist/jquery.min.js",
        "https://ajax.googleapis.com/ajax/libs/jquery/3.7.1/jquery.min.js",
        "https://www.cloudflare.com/favicon.ico",
        "https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css"
    )

    // Four small, diverse hosts for old / low-RAM phones (Android 7.0 to 9, 2 GB): the same idea with a third of the
    // sockets, so the CPU and the radio are not what fails the test.
    private val LOW_END_MEDIA_PROBES = listOf(
        "https://www.gstatic.com/generate_204",
        "https://www.google.com/favicon.ico",
        "https://cdnjs.cloudflare.com/ajax/libs/jquery/3.7.1/jquery.min.js",
        "https://cdn.jsdelivr.net/npm/jquery@3.7.1/dist/jquery.min.js"
    )

    private val lowEnd: Boolean get() = SilentGateApp.isLowEndDevice

    // Thresholds of the concurrent media test (tuned for budget phones on mobile data).
    private val mediaProbes: List<String> get() = if (lowEnd) LOW_END_MEDIA_PROBES else MEDIA_PROBES
    private val mediaMinOk: Int get() = if (lowEnd) 3 else 6                         // of 4 / of 8 parallel fetches
    private val mediaMaxMedianMs: Long get() = if (lowEnd) 5_000L else 3_500L        // slower = overloaded node
    private val exitMaxRttMs: Long get() = if (lowEnd) 5_000L else 3_500L            // trace round trip through the node
    private val mediaTimeoutMs: Int get() = if (lowEnd) 8_000 else 6_000

    /** In-session watchdog: seconds between checks, and how many of its probes must answer for "healthy". */
    val healthEverySecs: Int get() = if (lowEnd) 15 else 10
    val healthMinOk: Int get() = if (lowEnd) 1 else 2

    // Mainstream uncensored endpoints that family-filter DNS, cloud WAFs and ISP blocklists commonly hit. Only
    // /robots.txt is requested (a few hundred bytes of text); a node is rejected only when ALL of them are blocked
    // or when DNS for any of them is poisoned.
    private val CONTENT_HOSTS = listOf("www.pornhub.com", "xhamster.com", "zooporn.show")

    // Text that Pakistani ISP / PTA block pages (and similar national filters) contain. A node whose exit, or whose
    // resolver, returns one of these for a probe host is rejected immediately, however many other hosts load.
    private val BLOCK_PAGE_SIGNS = listOf(
        "page cannot be displayed", "contact your service provider", "pakistan telecommunication",
        "pta.gov.pk", "blocked as per", "has been blocked", "access to this site is blocked",
        "this website is blocked", "blocked by order"
    )
    private val BLOCK_PAGE_WORD = Regex("\\bpta\\b")
    private val BLOCK_HOST_HINTS = listOf("pta.gov.pk", "ptcl", "blocked", "blockpage", "block-page", "warning.", "filter.")

    private fun looksLikeBlockPage(text: String): Boolean {
        val low = text.lowercase()
        return BLOCK_PAGE_SIGNS.any { low.contains(it) } || BLOCK_PAGE_WORD.containsMatchIn(low)
    }

    /** A redirect that leaves the probed site for an ISP / regulator host is a block page, not the site. */
    private fun blockRedirect(host: String, location: String): Boolean {
        if (location.isEmpty()) return false
        val target = runCatching { URL(URL("https://$host/"), location).host.lowercase() }.getOrDefault("")
        val base = host.lowercase().removePrefix("www.")
        if (target.isEmpty() || target == host.lowercase() || target.endsWith(base)) return false
        return looksLikeBlockPage(location) || BLOCK_HOST_HINTS.any { target.contains(it) }
    }

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
     * Runs [urls] simultaneously (default: the 8 media probes) and reports how many delivered real bytes and how fast.
     * Failures count as the full timeout so the percentile reflects a stalled node honestly.
     */
    suspend fun mediaBurst(
        urls: List<String> = mediaProbes, timeoutMs: Int = mediaTimeoutMs, maxBytes: Int = 16 * 1024
    ): MediaResult = coroutineScope {
        val times = urls.map { u -> async(Dispatchers.IO) { timedFetch(u, timeoutMs, maxBytes) } }.awaitAll()
        val okTimes = times.filter { it >= 0 }
        val all = times.map { if (it >= 0) it else timeoutMs.toLong() }.sorted()
        MediaResult(
            ok = okTimes.size, total = times.size,
            medianMs = if (all.isEmpty()) -1 else all[all.size / 2],
            p90Ms = if (all.isEmpty()) -1 else all[((all.size - 1) * 9) / 10]
        )
    }

    /** Small, cheap version for the in-session watchdog: 4 parallel fetches of 2 KB (2 on low-end phones). */
    suspend fun health(timeoutMs: Int = 5000): MediaResult =
        mediaBurst(mediaProbes.shuffled().take(if (lowEnd) 2 else 4), timeoutMs, 2048)

    private fun looksPoisoned(a: InetAddress): Boolean =
        a.isAnyLocalAddress || a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress

    /**
     * 1 = reachable, 0 = soft (challenge / rate limit / site error: a real browser can still pass), -1 = blocked,
     * -2 = DNS poisoned, -3 = ISP / PTA block page (body or redirect target).
     */
    private suspend fun contentProbe(host: String, timeoutMs: Int): Pair<Int, String> = withContext(Dispatchers.IO) {
        val addrs = try {
            InetAddress.getAllByName(host).toList()
        } catch (e: Exception) {
            return@withContext -1 to "$host: DNS failed"
        }
        if (addrs.any { looksPoisoned(it) }) return@withContext -2 to "$host: DNS poisoned (${addrs.first().hostAddress})"
        val c = try { URL("https://$host/robots.txt").openConnection() as HttpURLConnection } catch (e: Exception) { return@withContext -1 to "$host: bad url" }
        try {
            c.instanceFollowRedirects = false
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.setRequestProperty("User-Agent", BROWSER_UA)
            c.setRequestProperty("Accept", "*/*")
            val code = c.responseCode
            val mitigated = c.getHeaderField("cf-mitigated").orEmpty()
            val location = c.getHeaderField("Location").orEmpty()
            val body = runCatching {
                (if (code >= 400) c.errorStream else c.inputStream)?.use { ins ->
                    val b = ByteArray(4096)
                    val n = ins.read(b)
                    if (n > 0) String(b, 0, n, Charsets.UTF_8) else ""
                }.orEmpty()
            }.getOrDefault("")
            when {
                code in 300..399 && blockRedirect(host, location) -> -3 to "$host: redirected to an ISP block page"
                code != 451 && looksLikeBlockPage(body) -> -3 to "$host: ISP block page (HTTP $code)"
                code in 200..399 -> 1 to "$host: HTTP $code"
                code == 451 -> -1 to "$host: HTTP 451 (blocked)"
                code == 403 && mitigated.equals("challenge", true) -> 0 to "$host: challenge page"
                code == 403 -> -1 to "$host: HTTP 403 (IP blocked by the site)"
                code == 429 || code >= 500 -> 0 to "$host: HTTP $code"
                else -> 0 to "$host: HTTP $code"
            }
        } catch (e: Exception) {
            -1 to "$host: ${e.javaClass.simpleName} (connection reset or timed out)"
        } finally {
            c.disconnect()
        }
    }

    /**
     * Content-filter / WAF check through the tunnel: DNS must not be poisoned for any probe host, and at least one of
     * them must answer (blocked = 403, 451, reset, timeout on every host means the exit IP or its resolver filters content).
     */
    suspend fun contentCheck(timeoutMs: Int = 6000): ContentResult = coroutineScope {
        val r = CONTENT_HOSTS.map { h -> async(Dispatchers.IO) { contentProbe(h, timeoutMs) } }.awaitAll()
        val blockPage = r.firstOrNull { it.first == -3 }
        if (blockPage != null) return@coroutineScope ContentResult(false, "ISP block page: ${blockPage.second}")
        val poisoned = r.firstOrNull { it.first == -2 }
        if (poisoned != null) return@coroutineScope ContentResult(false, "content filter: ${poisoned.second}")
        if (r.none { it.first >= 0 }) {
            return@coroutineScope ContentResult(false, "content filter: " + r.joinToString("; ") { it.second })
        }
        ContentResult(true, "")
    }

    /**
     * Gate for "Connected". The exit IP must differ from the ISP IP, the region must be allowed, and, when [enforce]
     * is true, the exit must also survive three real-world tests: 6 of 8 parallel CDN fetches (3 of 4 on low-end phones) with a sane median time
     * (no throttling of concurrent connections), a content-filter / WAF check, and a latency ceiling.
     * [enforce] = false only records the result (used for WARP, which is Cloudflare itself).
     */
    suspend fun verifyExit(home: HomeInfo, s: AppSettings, expectMasked: Boolean, enforce: Boolean, wantCc: String): Verify {
        val traceJob = ioScope.async { runCatching { cfTrace(4500) }.getOrNull() ?: runCatching { cfTrace(4500) }.getOrNull() }
        // Started outside this coroutine's scope so an early rejection never waits for blocking sockets.
        val mediaJob = ioScope.async { if (enforce) mediaBurst() else null }
        val contentJob = ioScope.async { if (enforce) contentCheck() else null }
        try {
            val t = traceJob.await() ?: return Verify(false, "the exit did not answer the location check")
            val changed = home.ip.isEmpty() || t.ip != home.ip
            if (expectMasked && !changed) return Verify(false, "your IP did not change, traffic is not going through the tunnel", t.ip, t.loc, "", t.rttMs, false)
            val cc = t.loc
            var rc = ""
            if (cc.equals("US", true)) {
                rc = runCatching { JSONObject(http("https://ipwho.is/", 4500)).optString("region_code") }.getOrDefault("")
            }
            val reason = Geo.excludedReason(cc, rc, home.cc, s.avoidRestricted, s.allowHomeExit)
            val geoLocal = cc.isNotEmpty() && cc.equals(home.cc, true)
            fun v(ok: Boolean, why: String, ms: Long = -1) = Verify(ok, why, t.ip, cc, rc, t.rttMs, changed, geoLocal, ms)
            if (enforce && reason != null) return v(false, reason)
            if (enforce && wantCc != "AUTO" && cc.isNotEmpty() && !cc.equals(wantCc, true)) {
                return v(false, "exit is ${Geo.name(cc)}, you asked for ${Geo.name(wantCc)}")
            }
            if (!enforce) return v(true, "")
            if (t.rttMs > exitMaxRttMs) return v(false, "exit is too slow (${t.rttMs} ms to reach the location check)")
            val m = mediaJob.await() ?: return v(false, "media test did not run")
            if (m.ok < mediaMinOk) {
                return v(false, "parallel media test failed: only ${m.ok} of ${m.total} CDN hosts loaded (node drops concurrent connections)", m.medianMs)
            }
            if (m.medianMs > mediaMaxMedianMs) {
                return v(false, "node is overloaded: median ${m.medianMs} ms for small CDN files", m.medianMs)
            }
            val c = contentJob.await() ?: ContentResult(true, "")
            if (!c.ok) return v(false, c.detail, m.medianMs)
            return v(true, "", m.medianMs)
        } finally {
            traceJob.cancel(); mediaJob.cancel(); contentJob.cancel()
        }
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
