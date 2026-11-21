package com.silentgatevpn.app.vpn

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/** A proxy node kept in the pool. Stats are updated after every connection attempt. */
data class Node(
    val id: String,
    val raw: String,
    val name: String,
    val protocol: String,
    val server: String,
    val port: Int,
    val transport: String,
    val security: String,
    val udp: Boolean,
    val ccHint: String,
    val source: String,
    val exitCc: String = "",
    val exitRc: String = "",
    val lastOkAt: Long = 0L,
    val lastFailAt: Long = 0L,
    val fails: Int = 0,
    val latencyMs: Long = -1L,
    /** Median time of the last parallel media test in ms (-1 = never measured). Slow nodes are ranked down. */
    val mediaMs: Long = -1L
) {
    /** Best known country: what the node was measured to exit in, else what its name claims. */
    val cc: String get() = exitCc.ifEmpty { ccHint }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("raw", raw).put("name", name).put("protocol", protocol).put("server", server)
        .put("port", port).put("transport", transport).put("security", security).put("udp", udp)
        .put("ccHint", ccHint).put("source", source).put("exitCc", exitCc).put("exitRc", exitRc)
        .put("lastOkAt", lastOkAt).put("lastFailAt", lastFailAt).put("fails", fails).put("latencyMs", latencyMs).put("mediaMs", mediaMs)

    companion object {
        fun fromJson(o: JSONObject): Node = Node(
            o.getString("id"), o.getString("raw"), o.optString("name"), o.optString("protocol"), o.optString("server"),
            o.optInt("port"), o.optString("transport"), o.optString("security"), o.optBoolean("udp"),
            o.optString("ccHint"), o.optString("source"), o.optString("exitCc"), o.optString("exitRc"),
            o.optLong("lastOkAt"), o.optLong("lastFailAt"), o.optInt("fails"), o.optLong("latencyMs", -1L),
            o.optLong("mediaMs", -1L)
        )
    }
}

data class Subscription(val url: String, val name: String, val enabled: Boolean, val builtin: Boolean, val count: Int = 0, val lastOkAt: Long = 0L)

/** Where the user is physically (the ISP side), measured before the tunnel starts. */
data class HomeInfo(val ip: String, val cc: String)

/**
 * Zero-config pool of community nodes: fetches subscription feeds, keeps the best few hundred, ranks them and learns
 * from every connection attempt. Pure storage and ranking logic, networking is plain HttpURLConnection.
 */
object NodePool {

    private const val MAX_NODES = 360
    private const val PER_SUB_CAP = 160
    private const val MAX_BODY_BYTES = 3_500_000
    const val STALE_MS = 6L * 3600_000L

    /** Nodes whose parallel media test is slower than this are ranked far down; above [SKIP_MEDIA_MS] they cool down. */
    private const val SLOW_MEDIA_MS = 2_000L
    private const val SKIP_MEDIA_MS = 3_500L
    private const val SLOW_LATENCY_MS = 1_500L

    private val warmScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Public, community-maintained feeds that publish Base64 / plain link lists. They are third-party data: the app
    // drops insecure nodes (no TLS, certificate checks off) and verifies every exit before showing "Protected".
    // Editable in Advanced > Custom profiles; remove anything you do not trust.
    private val DEFAULT_SUBS = listOf(
        "https://github.com/Au1rxx/free-vpn-subscriptions/raw/main/output/v2ray-base64.txt" to "Free VPN Subscriptions (health-checked)",
        "https://raw.githubusercontent.com/ebrasha/free-v2ray-public-list/refs/heads/main/vless_configs.txt" to "Free V2Ray Public List (VLESS)",
        "https://raw.githubusercontent.com/barry-far/V2ray-Configs/main/Sub1.txt" to "V2Ray-Configs Sub1"
    )

    private var dir: File? = null
    private val lock = Any()

    private val _nodes = MutableStateFlow<List<Node>>(emptyList())
    val nodes: StateFlow<List<Node>> = _nodes.asStateFlow()
    private val _subs = MutableStateFlow<List<Subscription>>(emptyList())
    val subs: StateFlow<List<Subscription>> = _subs.asStateFlow()
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()
    private val _updatedAt = MutableStateFlow(0L)
    val updatedAt: StateFlow<Long> = _updatedAt.asStateFlow()
    val lastRefreshMessage = MutableStateFlow("")

    fun init(storageDir: File) {
        dir = storageDir
        runCatching {
            val f = File(storageDir, "nodes.json")
            if (f.exists()) {
                val root = JSONObject(f.readText())
                _updatedAt.value = root.optLong("updatedAt")
                val arr = root.optJSONArray("nodes") ?: JSONArray()
                _nodes.value = (0 until arr.length()).mapNotNull { runCatching { Node.fromJson(arr.getJSONObject(it)) }.getOrNull() }
                    .map { enrich(it) }
            }
        }
        var loaded: List<Subscription> = emptyList()
        runCatching {
            val f = File(storageDir, "subs.json")
            if (f.exists()) {
                val arr = JSONArray(f.readText())
                loaded = (0 until arr.length()).map {
                    val o = arr.getJSONObject(it)
                    Subscription(o.getString("url"), o.optString("name"), o.optBoolean("enabled", true), o.optBoolean("builtin"), o.optInt("count"), o.optLong("lastOkAt"))
                }
            }
        }
        // Make sure new built-in feeds show up after an update, without touching the user's choices.
        val merged = loaded.toMutableList()
        for ((u, n) in DEFAULT_SUBS) if (merged.none { it.url == u }) merged.add(Subscription(u, n, true, true))
        _subs.value = merged
        saveSubs()
    }

    // ------------------------------------------------------------------ persistence

    private fun saveNodes() {
        val d = dir ?: return
        runCatching {
            val arr = JSONArray()
            _nodes.value.forEach { arr.put(it.toJson()) }
            File(d, "nodes.json.tmp").also { tmp ->
                tmp.writeText(JSONObject().put("updatedAt", _updatedAt.value).put("nodes", arr).toString())
                tmp.renameTo(File(d, "nodes.json"))
            }
        }
    }

    private fun saveSubs() {
        val d = dir ?: return
        runCatching {
            val arr = JSONArray()
            _subs.value.forEach {
                arr.put(JSONObject().put("url", it.url).put("name", it.name).put("enabled", it.enabled)
                    .put("builtin", it.builtin).put("count", it.count).put("lastOkAt", it.lastOkAt))
            }
            File(d, "subs.json").writeText(arr.toString())
        }
    }

    // ------------------------------------------------------------------ subscriptions

    fun addSubscription(url: String, name: String = ""): String? {
        val u = url.trim()
        if (!(u.startsWith("https://", true))) return "Use an https:// subscription URL"
        if (_subs.value.any { it.url == u }) return "That subscription is already added"
        _subs.value = _subs.value + Subscription(u, name.ifEmpty { u.substringAfter("://").substringBefore('/') }, true, false)
        saveSubs()
        return null
    }

    fun removeSubscription(url: String) {
        _subs.value = _subs.value.filterNot { it.url == url }
        saveSubs()
    }

    fun setSubscriptionEnabled(url: String, enabled: Boolean) {
        _subs.value = _subs.value.map { if (it.url == url) it.copy(enabled = enabled) else it }
        saveSubs()
    }

    // ------------------------------------------------------------------ importing

    private fun protocolWeight(p: ParsedProxy): Int = when {
        p.security == "reality" -> 100
        p.protocol == "trojan" && p.security == "tls" -> 84
        p.protocol == "vless" && p.security == "tls" && p.transport in setOf("ws", "grpc", "httpupgrade") -> 82
        p.protocol == "vless" && p.security == "tls" -> 78
        p.protocol == "vmess" && p.security == "tls" -> 66
        p.protocol == "hysteria2" -> 62
        p.protocol == "tuic" -> 56
        p.protocol == "shadowsocks" -> 38
        else -> 10
    }

    /** Community nodes must be encrypted end to end and verify their certificate, otherwise they are dropped. */
    private fun acceptable(p: ParsedProxy): Boolean {
        if (p.server.isEmpty() || p.port !in 1..65535) return false
        if (p.insecureCert) return false
        if (p.isEndpoint) return false
        if (p.security == "none" && p.protocol != "shadowsocks") return false
        if (p.protocol == "shadowsocks") {
            val m = runCatching { JSONObject(p.toJson("x", null, null, null).toString()).optString("method") }.getOrDefault("")
            if (m.isEmpty() || m == "none" || m == "plain" || m.startsWith("rc4")) return false
        }
        return true
    }

    private fun toNode(e: SubscriptionParser.Entry, source: String): Node {
        val p = e.proxy
        return Node(
            id = p.key, raw = e.raw, name = p.name.take(60), protocol = p.protocol, server = p.server, port = p.port,
            transport = p.transport, security = p.security, udp = p.udpNative, ccHint = hintFor(p.name, p.server), source = source
        )
    }

    /** Country guess from the node's label first, then from its host name (nl1.example.com, ams02.host.net ...). */
    private fun hintFor(name: String, server: String): String =
        Geo.ccFromText(name).ifEmpty { Geo.ccFromHost(server) }

    /** Fills a missing country hint with the upgraded matcher (also applied to nodes saved by older versions). */
    private fun enrich(n: Node): Node =
        if (n.exitCc.isNotEmpty() || n.ccHint.isNotEmpty()) n
        else hintFor(n.name, n.server).let { if (it.isEmpty()) n else n.copy(ccHint = it) }

    private fun mergeIn(fresh: List<Node>): Int {
        synchronized(lock) {
            val old = _nodes.value.associateBy { it.id }
            val seen = HashSet<String>()
            val merged = ArrayList<Node>()
            for (n in fresh) {
                if (!seen.add(n.id)) continue
                val prev = old[n.id]
                merged.add(if (prev != null) n.copy(
                    exitCc = prev.exitCc, exitRc = prev.exitRc, lastOkAt = prev.lastOkAt,
                    lastFailAt = prev.lastFailAt, fails = prev.fails, latencyMs = prev.latencyMs, mediaMs = prev.mediaMs
                ) else n)
            }
            // Keep proven nodes that dropped out of the feeds for a while, they may still work.
            val keepBefore = System.currentTimeMillis() - 48 * 3600_000L
            for (o in old.values) if (o.id !in seen && o.lastOkAt > keepBefore) { seen.add(o.id); merged.add(o) }
            val trimmed = if (merged.size > MAX_NODES) {
                merged.sortedByDescending { (if (it.lastOkAt > 0) 1000 else 0) - it.fails }.take(MAX_NODES)
            } else merged
            _nodes.value = trimmed
            return fresh.size
        }
    }

    /** Pasted text: one or many links, a Base64 blob, sing-box JSON or Clash YAML. Returns how many nodes were added. */
    fun importText(text: String, source: String = "Imported"): Int {
        val entries = SubscriptionParser.parse(text)
        if (entries.isEmpty()) return 0
        val nodes = entries.map { toNode(it, source) }
        val before = _nodes.value.size
        mergeIn(nodes + _nodes.value)
        saveNodes()
        return (_nodes.value.size - before).coerceAtLeast(0)
    }

    // ------------------------------------------------------------------ refresh

    /** One download source: [delayMs] staggers the start so cheap mirrors are only used when the first ones are slow. */
    private class Source(val url: String, val delayMs: Long)

    /**
     * The original URL plus public CDN mirrors of the same GitHub file. On ISPs that throttle github.com /
     * raw.githubusercontent.com the CDN copies answer instead. They are started staggered (original + first mirror at
     * once, the rest only if nothing has answered yet) to keep sockets and RAM low on budget phones.
     */
    private fun sources(url: String): List<Source> {
        val out = arrayListOf(Source(url, 0))
        val m = Regex("^https://raw\\.githubusercontent\\.com/([^/]+)/([^/]+)/(?:refs/heads/)?([^/]+)/(.+)$").find(url)
            ?: Regex("^https://github\\.com/([^/]+)/([^/]+)/raw/(?:refs/heads/)?([^/]+)/(.+)$").find(url)
        if (m != null) {
            val (u, r, b, p) = m.destructured
            out.add(Source("https://cdn.jsdelivr.net/gh/$u/$r@$b/$p", 0))
            out.add(Source("https://fastly.jsdelivr.net/gh/$u/$r@$b/$p", 1_500))
            out.add(Source("https://gcore.jsdelivr.net/gh/$u/$r@$b/$p", 2_500))
            out.add(Source("https://cdn.statically.io/gh/$u/$r/$b/$p", 3_500))
            out.add(Source("https://rawcdn.githack.com/$u/$r/$b/$p", 4_500))
        }
        return out
    }

    private const val ATTEMPT_TIMEOUT_MS = 4_500
    private const val DOWNLOAD_TOTAL_MS = 20_000L

    private fun fetchOne(url: String, conns: ConcurrentLinkedQueue<HttpURLConnection>): String? {
        val c = runCatching { URL(url).openConnection() as HttpURLConnection }.getOrNull() ?: return null
        conns.add(c)
        return try {
            c.connectTimeout = ATTEMPT_TIMEOUT_MS
            c.readTimeout = ATTEMPT_TIMEOUT_MS
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "SilentGateVPN/1.2")
            if (c.responseCode !in 200..299) return null
            val buf = java.io.ByteArrayOutputStream()
            c.inputStream.use { ins ->
                val chunk = ByteArray(16 * 1024)
                while (true) {
                    val n = ins.read(chunk)
                    if (n < 0) break
                    buf.write(chunk, 0, n)
                    if (buf.size() > MAX_BODY_BYTES) break
                }
            }
            val body = buf.toString(Charsets.UTF_8.name())
            // A CDN error page or a captive-portal page is not a feed.
            if (body.length < 40 || body.trimStart().startsWith("<")) null else body
        } catch (_: Exception) {
            null
        } finally {
            runCatching { c.disconnect() }
        }
    }

    /** Races the original URL against its mirrors; the first valid body wins and the losers are cancelled at once. */
    private suspend fun download(url: String): String? {
        val list = sources(url)
        val done = CompletableDeferred<String?>()
        val left = AtomicInteger(list.size)
        val conns = ConcurrentLinkedQueue<HttpURLConnection>()
        val jobs = list.map { src ->
            // Own scope: a blocking socket is not cancellable, so waiting for it would stall the caller.
            warmScope.launch {
                if (src.delayMs > 0) delay(src.delayMs)
                if (done.isCompleted) { left.decrementAndGet(); return@launch }
                val body = fetchOne(src.url, conns)
                if (body != null) done.complete(body)
                else if (left.decrementAndGet() == 0) done.complete(null)
            }
        }
        return try {
            withTimeoutOrNull(DOWNLOAD_TOTAL_MS) { done.await() }
        } finally {
            jobs.forEach { it.cancel() }
            conns.forEach { runCatching { it.disconnect() } }
        }
    }

    fun isStale(): Boolean = System.currentTimeMillis() - _updatedAt.value > STALE_MS || _nodes.value.size < 8

    /**
     * Fire-and-forget refresh used right after the user accepts the welcome screen (and at app start), so the nodes
     * are already downloaded and filtered before the first tap on Connect. Safe to call repeatedly: [refresh] joins
     * a refresh that is already running instead of starting another one. It deliberately does NOT measure the home
     * IP: that must only happen right before the tunnel starts, never in the background (it could be mistaken for
     * the VPN exit IP).
     */
    fun prewarm() {
        warmScope.launch {
            if (SettingsStore.value.communityNodes && isStale()) runCatching { refresh() }
        }
    }

    private val refreshLock = Any()
    private var inFlight: CompletableDeferred<Int>? = null

    /**
     * Downloads every enabled feed and merges the result. A caller that arrives while a refresh is already running
     * (prewarm, then the first tap on Connect) AWAITS that same refresh and gets its real node count, instead of an
     * immediate empty answer. The work runs in its own scope, so a caller that times out and stops waiting does not
     * abort the download: the nodes still land in the pool for the next attempt.
     */
    suspend fun refresh(): Int {
        val d = synchronized(refreshLock) { inFlight ?: startRefresh().also { inFlight = it } }
        return d.await()
    }

    private fun startRefresh(): CompletableDeferred<Int> {
        val d = CompletableDeferred<Int>()
        _refreshing.value = true
        warmScope.launch {
            val n = try { doRefresh() } catch (_: Throwable) { _nodes.value.size }
            synchronized(refreshLock) { inFlight = null }
            _refreshing.value = false
            d.complete(n)
        }
        return d
    }

    /** Feeds are merged as each one arrives, so the first fast feed already gives Connect something to work with. */
    private suspend fun doRefresh(): Int {
        val active = _subs.value.filter { it.enabled }
        if (active.isEmpty()) { lastRefreshMessage.value = "No subscriptions enabled"; return _nodes.value.size }
        val sem = Semaphore(2)
        val freshLock = Any()
        val fresh = ArrayList<Node>()
        val okUrls = HashMap<String, Int>()
        coroutineScope {
            active.map { sub ->
                async(Dispatchers.IO) {
                    sem.withPermit {
                        val body = download(sub.url) ?: return@withPermit
                        val entries = SubscriptionParser.parse(body).filter { acceptable(it.proxy) }
                        val top = entries.map { it to protocolWeight(it.proxy) }
                            .shuffled().sortedByDescending { it.second }.take(PER_SUB_CAP)
                        val nodes = top.map { toNode(it.first, sub.name) }
                        synchronized(freshLock) {
                            fresh.addAll(nodes)
                            okUrls[sub.url] = nodes.size
                            mergeIn(fresh.toList())
                            _updatedAt.value = System.currentTimeMillis()
                        }
                    }
                }
            }.awaitAll()
        }
        val now = System.currentTimeMillis()
        _subs.value = _subs.value.map { s ->
            okUrls[s.url]?.let { c -> s.copy(count = c, lastOkAt = now) } ?: s
        }
        saveSubs()
        if (okUrls.isEmpty()) {
            lastRefreshMessage.value = "Could not download any subscription"
            return _nodes.value.size
        }
        saveNodes()
        lastRefreshMessage.value = "Updated: ${_nodes.value.size} nodes from ${okUrls.size} feed(s)"
        return _nodes.value.size
    }

    // ------------------------------------------------------------------ ranking

    private fun score(n: Node, now: Long): Double {
        var s = when {
            n.security == "reality" -> 100.0
            n.protocol == "trojan" -> 84.0
            n.protocol == "vless" && n.security == "tls" -> 80.0
            n.protocol == "vmess" -> 66.0
            n.protocol == "hysteria2" -> 62.0
            n.protocol == "tuic" -> 56.0
            n.protocol == "shadowsocks" -> 38.0
            else -> 20.0
        }
        if (n.lastOkAt > 0 && now - n.lastOkAt < STALE_MS) s += 70.0
        else if (n.lastOkAt > 0 && now - n.lastOkAt < 48 * 3600_000L) s += 25.0
        val idx = Geo.SAFE_PRIORITY.indexOf(n.cc)
        if (idx >= 0) s += (20 - idx).coerceAtLeast(2).toDouble()
        if (n.latencyMs in 1..4000) s -= n.latencyMs / 60.0
        if (n.latencyMs > SLOW_LATENCY_MS) s -= 25.0
        if (n.mediaMs > 0) s -= (n.mediaMs / 40.0).coerceAtMost(70.0)   // 2 s median = -50, 3.5 s = -70
        s -= n.fails * 30.0
        return s + Math.random() * 6.0
    }

    /** Is this node excluded by what we already know about it (name hint or measured exit)? */
    private fun knownBad(n: Node, s: AppSettings, home: HomeInfo): Boolean =
        Geo.excludedReason(n.cc, n.exitRc, home.cc, s.avoidRestricted, s.allowHomeExit) != null

    /**
     * Ranked candidates for the next connection attempt: safe countries first, proven nodes first, recent failures last.
     * When the user picked a country, only nodes that are (or may be) there are returned.
     */
    fun candidates(s: AppSettings, home: HomeInfo, avoid: Set<String>): List<Node> {
        val now = System.currentTimeMillis()
        var list = _nodes.value.filter { it.id !in avoid && !knownBad(it, s, home) }
        list = list.filter { !(it.fails >= 3 && now - it.lastFailAt < 30 * 60_000L) }
        // Overloaded: slow in the parallel media test AND failed recently, so give it a 30 minute rest.
        list = list.filter { !(it.mediaMs > SKIP_MEDIA_MS && it.fails >= 1 && now - it.lastFailAt < 30 * 60_000L) }
        val pref = s.preferredCountry
        if (pref == Geo.OTHER) {
            list = list.filter { it.cc.isEmpty() }
        } else if (pref != "AUTO") {
            val matching = list.filter { it.cc == pref }
            val unknown = list.filter { it.cc.isEmpty() }
            list = if (matching.isNotEmpty()) matching + unknown else unknown
        }
        return list.map { it to score(it, now) }.sortedByDescending { it.second }.map { it.first }
    }

    /** Countries that currently have usable nodes, for the location picker: code -> node count. */
    fun availableCountries(s: AppSettings, home: HomeInfo): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        val counts = _nodes.value.filter { !knownBad(it, s, home) && it.cc.isNotEmpty() }.groupingBy { it.cc }.eachCount()
        for (cc in Geo.SAFE_PRIORITY) counts[cc]?.let { out[cc] = it }
        for ((cc, n) in counts.entries.sortedByDescending { it.value }) if (cc !in out) out[cc] = n
        return out
    }

    /**
     * What the location picker shows. [safe] = preferred unrestricted countries, [other] = any other usable country
     * (including the US, whose exit state is checked after connecting), [ageCheck] = restricted countries, only filled
     * when "avoid restricted" is off, [unknown] = nodes without a known country ("Global / Other").
     * The home country is never offered unless the user allowed home exits.
     */
    class PickerData(
        val safe: Map<String, Int>, val other: Map<String, Int>, val ageCheck: Map<String, Int>, val unknown: Int
    ) {
        val isEmpty: Boolean get() = safe.isEmpty() && other.isEmpty() && ageCheck.isEmpty() && unknown == 0
    }

    fun pickerData(s: AppSettings, home: HomeInfo): PickerData {
        val now = System.currentTimeMillis()
        val usable = _nodes.value.filter { !(it.fails >= 3 && now - it.lastFailAt < 30 * 60_000L) }
        val counts = usable.filter { it.cc.isNotEmpty() }.groupingBy { it.cc }.eachCount()
        val safe = LinkedHashMap<String, Int>()
        val other = LinkedHashMap<String, Int>()
        val age = LinkedHashMap<String, Int>()
        fun place(cc: String, n: Int) {
            if (!s.allowHomeExit && home.cc.isNotEmpty() && cc.equals(home.cc, true)) return
            val restricted = cc in Geo.RESTRICTED_COUNTRIES
            when {
                restricted && s.avoidRestricted -> Unit
                restricted -> age[cc] = n
                cc in Geo.PICKER_COUNTRIES -> safe[cc] = n
                else -> other[cc] = n
            }
        }
        for (cc in Geo.PICKER_COUNTRIES) counts[cc]?.let { place(cc, it) }
        for ((cc, n) in counts.entries.sortedByDescending { it.value }) if (cc !in safe) place(cc, n)
        return PickerData(safe, other, age, usable.count { it.cc.isEmpty() })
    }

    fun get(id: String): Node? = _nodes.value.firstOrNull { it.id == id }

    fun markOk(id: String, exitCc: String, exitRc: String, latencyMs: Long, mediaMs: Long = -1L) {
        update(id) {
            it.copy(
                exitCc = exitCc.ifEmpty { it.exitCc }, exitRc = exitRc.ifEmpty { it.exitRc },
                lastOkAt = System.currentTimeMillis(), fails = 0, latencyMs = latencyMs,
                mediaMs = if (mediaMs > 0) mediaMs else it.mediaMs
            )
        }
    }

    /** Remembers a slow pre-flight so the node is ranked down next time (rare, so the extra disk write is fine). */
    fun markLatency(id: String, latencyMs: Long) {
        update(id) { it.copy(latencyMs = latencyMs) }
    }

    /** Records the parallel media median (also for rejected nodes) so overloaded nodes sink in the ranking. */
    fun markMedia(id: String, mediaMs: Long) {
        if (mediaMs > 0) update(id) { it.copy(mediaMs = mediaMs) }
    }

    fun markExit(id: String, exitCc: String, exitRc: String) {
        update(id) { it.copy(exitCc = exitCc, exitRc = exitRc) }
    }

    fun markFail(id: String) {
        update(id) { it.copy(fails = it.fails + 1, lastFailAt = System.currentTimeMillis()) }
    }

    private fun update(id: String, f: (Node) -> Node) {
        synchronized(lock) { _nodes.value = _nodes.value.map { if (it.id == id) f(it) else it } }
        saveNodes()
    }

    fun clear() {
        _nodes.value = emptyList()
        saveNodes()
    }
}
