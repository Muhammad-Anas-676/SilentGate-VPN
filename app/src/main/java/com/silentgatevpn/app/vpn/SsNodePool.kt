package com.silentgatevpn.app.vpn

import com.silentgatevpn.app.SilentGateApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Free, open-source Shadowsocks servers for the "Shadowsocks" engine on Home. Kept apart from [NodePool] so the
 * default Smart Auto flow is not affected in any way.
 *
 * Third-party public lists: anyone can publish a node there, so the operator of a free server can see unencrypted
 * traffic. The app only uses AEAD / 2022 ciphers, verifies that the IP really changed before it says "Connected",
 * and never uses these servers for anything but the Shadowsocks engine.
 */
object SsNodePool {

    private val FEEDS = listOf(
        "https://raw.githubusercontent.com/zengfr/free-vpn-subscribe/main/vpn_sub_shadowsocks.txt" to "free-vpn-subscribe (SS)",
        "https://raw.githubusercontent.com/wiki/gfpcom/free-proxy-list/lists/ss.txt" to "free-proxy-list (SS)"
    )

    private val MAX_NODES: Int get() = if (SilentGateApp.isLowEndDevice) 120 else 240
    private val PER_FEED_CAP: Int get() = if (SilentGateApp.isLowEndDevice) 70 else 140
    private const val STALE_MS = 3L * 3600_000L

    /** Netherlands first, then close European exits, then anything else that is not restricted. */
    private val NEAR_NL = listOf("DE", "BE", "FR", "GB", "LU", "CH", "AT", "SE", "FI", "NO", "DK", "PL", "CZ", "IS", "RO")

    private var dir: File? = null
    private val lock = Any()
    private val _nodes = MutableStateFlow<List<Node>>(emptyList())
    val nodes: StateFlow<List<Node>> = _nodes.asStateFlow()
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()
    private var updatedAt = 0L
    val lastMessage = MutableStateFlow("")

    fun init(storageDir: File) {
        dir = storageDir
        runCatching {
            val f = File(storageDir, "ss_nodes.json")
            if (f.exists()) {
                val root = JSONObject(f.readText())
                updatedAt = root.optLong("updatedAt")
                val arr = root.optJSONArray("nodes") ?: JSONArray()
                _nodes.value = (0 until arr.length()).mapNotNull { runCatching { Node.fromJson(arr.getJSONObject(it)) }.getOrNull() }
            }
        }
    }

    fun isStale(): Boolean = System.currentTimeMillis() - updatedAt > STALE_MS || _nodes.value.size < 8

    private fun save() {
        val d = dir ?: return
        runCatching {
            val arr = JSONArray()
            _nodes.value.forEach { arr.put(it.toJson()) }
            File(d, "ss_nodes.json.tmp").also { tmp ->
                tmp.writeText(JSONObject().put("updatedAt", updatedAt).put("nodes", arr).toString())
                tmp.renameTo(File(d, "ss_nodes.json"))
            }
        }
    }

    private fun methodOf(p: ParsedProxy): String = p.key.split("|").getOrNull(3).orEmpty().lowercase()
    private fun pluginOf(p: ParsedProxy): String = p.key.split("|").getOrNull(5).orEmpty()

    private val ALLOWED = setOf(
        "aes-128-gcm", "aes-192-gcm", "aes-256-gcm", "chacha20-ietf-poly1305", "xchacha20-ietf-poly1305",
        "2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm", "2022-blake3-chacha20-poly1305"
    )

    private fun acceptable(p: ParsedProxy): Boolean =
        p.protocol == "shadowsocks" && p.server.isNotEmpty() && p.port in 1..65535 &&
            methodOf(p) in ALLOWED && pluginOf(p).isEmpty()

    /** Downloads the feeds one after the other (light on RAM and sockets) and merges them into the pool. */
    suspend fun refresh(): Int {
        if (_refreshing.value) return _nodes.value.size
        _refreshing.value = true
        try {
            val fresh = ArrayList<Node>()
            var okFeeds = 0
            for ((url, name) in FEEDS) {
                val body = NodePool.download(url) ?: continue
                val picked = withContext(Dispatchers.Default) {
                    SubscriptionParser.parse(body).map { it.proxy to it.raw }
                        .filter { acceptable(it.first) }
                        .shuffled().take(PER_FEED_CAP)
                }
                okFeeds++
                for ((p, raw) in picked) {
                    fresh.add(
                        Node(
                            id = p.key, raw = raw, name = p.name.take(60), protocol = p.protocol, server = p.server,
                            port = p.port, transport = p.transport, security = p.security, udp = false,
                            ccHint = NodePool.hintFor(p.name, p.server), source = name
                        )
                    )
                }
                // Merge as each feed arrives, so Connect can start with the first one.
                merge(fresh)
            }
            if (okFeeds == 0) { lastMessage.value = "Could not download the free server lists"; return _nodes.value.size }
            updatedAt = System.currentTimeMillis()
            save()
            lastMessage.value = "Updated: ${_nodes.value.size} free servers"
        } finally {
            _refreshing.value = false
        }
        return _nodes.value.size
    }

    private fun merge(fresh: List<Node>) {
        synchronized(lock) {
            val old = _nodes.value.associateBy { it.id }
            val seen = HashSet<String>()
            val merged = ArrayList<Node>()
            for (n in fresh) {
                if (!seen.add(n.id)) continue
                val prev = old[n.id]
                merged.add(
                    if (prev != null) n.copy(
                        exitCc = prev.exitCc, exitRc = prev.exitRc, lastOkAt = prev.lastOkAt,
                        lastFailAt = prev.lastFailAt, fails = prev.fails, latencyMs = prev.latencyMs
                    ) else n
                )
            }
            // Servers that worked before stay in the pool even when a feed dropped them.
            for (n in _nodes.value) if (n.lastOkAt > 0 && seen.add(n.id)) merged.add(n)
            _nodes.value = if (merged.size > MAX_NODES) {
                merged.sortedByDescending { (if (it.lastOkAt > 0) 1000 else 0) - it.fails }.take(MAX_NODES)
            } else merged
        }
    }

    private fun score(n: Node, now: Long): Double {
        var s = 0.0
        if (n.lastOkAt > 0 && now - n.lastOkAt < 6 * 3600_000L) s += 200.0
        else if (n.lastOkAt > 0 && now - n.lastOkAt < 48 * 3600_000L) s += 80.0
        val cc = n.cc
        s += when {
            cc == "NL" -> 60.0
            cc in NEAR_NL -> 40.0 - NEAR_NL.indexOf(cc)
            cc.isEmpty() -> 15.0
            else -> 5.0
        }
        if (n.latencyMs in 1..4000) s -= n.latencyMs / 60.0
        s -= n.fails * 30.0
        return s + Math.random() * 8.0
    }

    /** Ranked servers for the next attempt: proven ones first, Netherlands and nearby Europe before the rest. */
    fun candidates(s: AppSettings, home: HomeInfo, avoid: Set<String>): List<Node> {
        val now = System.currentTimeMillis()
        return _nodes.value
            .filter { it.id !in avoid }
            .filter { Geo.excludedReason(it.cc, it.exitRc, home.cc, s.avoidRestricted, s.allowHomeExit) == null }
            .filter { !(it.fails >= 3 && now - it.lastFailAt < 30 * 60_000L) }
            .map { it to score(it, now) }.sortedByDescending { it.second }.map { it.first }
    }

    fun markOk(id: String, cc: String, ms: Long) = update(id) {
        it.copy(lastOkAt = System.currentTimeMillis(), fails = 0, exitCc = cc.ifEmpty { it.exitCc }, latencyMs = ms)
    }

    fun markFail(id: String) = update(id) { it.copy(fails = it.fails + 1, lastFailAt = System.currentTimeMillis()) }

    private fun update(id: String, f: (Node) -> Node) {
        synchronized(lock) { _nodes.value = _nodes.value.map { if (it.id == id) f(it) else it } }
        save()
    }
}
