package com.silentgatevpn.app.vpn

import android.os.Build
import com.silentgatevpn.app.SilentGateApp
import org.json.JSONArray
import org.json.JSONObject

object ConfigBuilder {

    /** [masksIp] decides whether the app may ever show "Protected" for this layer. */
    enum class Layer(val title: String, val timeoutMs: Long, val masksIp: Boolean) {
        PRIMARY("Private server", 12_000, true),
        SHADOWSOCKS("Shadowsocks", 12_000, true),
        NODE("Smart Node", 9_500, true),
        MY_SERVER("My server", 15_000, true),
        WARP("Obfuscated WARP", 30_000, true),
        WARP_NODE("WARP via Node", 25_000, true),
        DPI_ONLY("DPI Bypass Only (No IP Change)", 10_000, false)
    }

    /** Per-connection inputs computed by the service before each attempt. */
    class Ctx(
        val bootstrap: List<DnsProvider>,
        val underlyingMtu: Int,
        val serverIp: String? = null
    )

    private val ENDPOINT_TYPES = setOf("wireguard", "awg", "warp")

    private fun obj(vararg p: Pair<String, Any?>) = JSONObject().apply { p.forEach { (k, v) -> put(k, v) } }
    private fun arr(vararg v: Any) = JSONArray().apply { v.forEach { put(it) } }

    private fun fragmentJson(s: AppSettings) = obj("enabled" to true, "size" to s.fragSize, "sleep" to s.fragSleep)

    /** How many progressively simpler configs exist for a layer (used when the core rejects an option). */
    fun variants(layer: Layer): Int = when (layer) {
        Layer.WARP, Layer.WARP_NODE -> 3
        Layer.PRIMARY, Layer.SHADOWSOCKS, Layer.NODE, Layer.MY_SERVER, Layer.DPI_ONLY -> 2
    }

    /**
     * True when UDP/443 (QUIC / HTTP3) should be rejected so browsers fall back to TCP, which carries thumbnails reliably.
     * The default is [QuicMode.BLOCK]; "Smart" (per-node decision) and "Allow" are only reachable from Advanced settings.
     */
    fun blockQuic(s: AppSettings, layer: Layer, proxy: ParsedProxy?): Boolean = when (s.quic) {
        QuicMode.BLOCK -> true
        QuicMode.ALLOW -> false
        QuicMode.AUTO -> when (layer) {
            Layer.DPI_ONLY -> true                          // QUIC would bypass the fragmented TLS path entirely
            Layer.WARP -> false
            else -> proxy != null && !proxy.udpNative       // UDP inside a TCP-based proxy makes QUIC stall
        }
    }

    fun tunMtu(s: AppSettings, layer: Layer, proxy: ParsedProxy?, underlying: Int): Int {
        if (s.mtuOverride > 0) return s.mtuOverride.coerceIn(1200, 1500)
        val base = when (layer) {
            Layer.WARP, Layer.WARP_NODE -> 1280
            Layer.DPI_ONLY -> 1400
            else -> proxy?.mtuHint ?: 1400
        }
        val path = if (underlying in 1000..1600) underlying - 80 else base
        val auto = minOf(base, path)
        // Old / budget phones: a smaller MTU avoids carrier fragmentation drops on 2016 to 2018 mobile chipsets.
        val capped = if (SilentGateApp.isLowEndDevice) minOf(auto, LOW_END_MTU) else auto
        return capped.coerceIn(1200, 1500)
    }

    private const val LOW_END_MTU = 1340

    /** variant 0 = full config, higher = fewer optional core features (noise, scanner, fragmentation). */
    fun build(layer: Layer, s: AppSettings, proxy: ParsedProxy?, variant: Int, ctx: Ctx): String {
        val endpoints = JSONArray()
        val outbounds = JSONArray().put(obj("type" to "direct", "tag" to "direct"))
        val primary = ctx.bootstrap.firstOrNull() ?: s.dns

        fun addProxy(tag: String) {
            val p = proxy ?: error("No proxy selected")
            // tls_fragment is applied to proxy handshakes ONLY when the user switched it on in Advanced settings.
            // (DPI_ONLY fragments its own "frag" outbound below.) It is off by default because splitting every
            // proxy TLS hello makes dozens of parallel media connections slow or get reset.
            val frag = if (s.fragmentStealth && variant == 0) fragmentJson(s) else null
            val json = p.toJson(tag, frag, "dns-bootstrap", ctx.serverIp)
            if (p.isEndpoint || json.optString("type") in ENDPOINT_TYPES) endpoints.put(json) else outbounds.put(json)
        }

        val mainTag: String = when (layer) {
            Layer.WARP, Layer.WARP_NODE -> {
                if (layer == Layer.WARP_NODE) {
                    if (proxy?.isEndpoint == true) error("WARP via Node needs a TCP/QUIC proxy, not WireGuard")
                    addProxy("proxy")
                }
                val warp = obj("type" to "warp", "tag" to "warp")
                if (layer == Layer.WARP_NODE) warp.put("detour", "proxy")
                if (variant <= 1) warp.put("mode", s.warpTunnel.core)
                if (variant == 0 && s.noise && (s.warpTunnel == WarpTunnel.AUTO || s.warpTunnel == WarpTunnel.WG)) {
                    warp.put("noise", obj("fake_packet" to obj("enabled" to true, "mode" to "quic", "count" to "2-4", "delay" to "10-30")))
                }
                if (variant <= 1 && s.scanner && layer == Layer.WARP) warp.put("scanner", obj("enabled" to true, "search_noise" to s.searchNoise))
                endpoints.put(warp)
                "warp"
            }
            Layer.NODE, Layer.MY_SERVER, Layer.PRIMARY, Layer.SHADOWSOCKS -> {
                addProxy("proxy")
                "proxy"
            }
            Layer.DPI_ONLY -> {
                outbounds.put(
                    if (variant == 0) obj("type" to "direct", "tag" to "frag", "tls_fragment" to fragmentJson(s))
                    else obj("type" to "direct", "tag" to "frag")
                )
                "frag"
            }
        }

        // DNS: apps' queries are answered over encrypted DNS through the active tunnel. The bootstrap resolver (used
        // only to find server names) is the fastest working IP-literal DoH server, chosen app-side from the chain
        // Cloudflare / Quad9 / AdGuard / Google, so a blocked 1.1.1.1 never prevents start-up.
        val remote = obj(
            "type" to "https", "tag" to "dns-remote", "server" to s.dns.server,
            "server_port" to 443, "path" to "/dns-query", "detour" to mainTag
        )
        s.dns.sni?.let { remote.put("tls", obj("server_name" to it)) }
        val bootstrap = obj(
            "type" to "https", "tag" to "dns-bootstrap", "server" to primary.server,
            "server_port" to 443, "path" to "/dns-query"
        )
        primary.sni?.let { bootstrap.put("tls", obj("server_name" to it)) }
        val dns = obj(
            "servers" to JSONArray().put(remote).put(bootstrap),
            "final" to "dns-remote",
            "strategy" to if (s.ipv6 == Ipv6Mode.BLOCK) "ipv4_only" else "prefer_ipv4"
        )

        val rules = JSONArray()
        rules.put(obj("action" to "sniff"))
        rules.put(obj("protocol" to "dns", "action" to "hijack-dns"))
        rules.put(obj("port" to 53, "action" to "hijack-dns"))
        if (blockQuic(s, layer, proxy)) rules.put(obj("network" to "udp", "port" to 443, "action" to "reject"))
        if (s.ipv6 == Ipv6Mode.BLOCK) rules.put(obj("ip_version" to 6, "action" to "reject"))
        rules.put(obj("ip_is_private" to true, "outbound" to "direct"))

        val route = obj(
            "rules" to rules,
            "final" to mainTag,
            "auto_detect_interface" to true,
            "default_domain_resolver" to obj("server" to "dns-bootstrap", "strategy" to "ipv4_only")
        )

        // IPv6 address + ::/0 route always go into the TUN so native IPv6 can never bypass the VPN.
        val tun = obj(
            "type" to "tun", "tag" to "tun-in",
            "address" to arr("172.19.0.1/30", "fdfe:dcba:9876::1/126"),
            "mtu" to tunMtu(s, layer, proxy, ctx.underlyingMtu),
            "auto_route" to true,
            // strict_route is ALWAYS on (API 24+). With it off, libbox hands openTun() an EMPTY DNS server list, the
            // VpnService interface ends up with no resolver and every in-tunnel probe dies with "no traffic".
            "strict_route" to true,
            // "mixed" is proven to pass VLESS traffic on Android 7 to 8 (SilentGateApp enables fixAndroidStack for
            // API 24..28). Pure gVisor is only a fallback, reached when variant 0 was rejected by the core.
            "stack" to if (variant >= 1 && Build.VERSION.SDK_INT < 29) "gvisor" else "mixed"
        )
        if (s.splitPackages.isNotEmpty()) {
            when (s.splitMode) {
                SplitMode.EXCLUDE -> tun.put("exclude_package", JSONArray(s.splitPackages.toList()))
                SplitMode.ONLY -> tun.put("include_package", JSONArray(s.splitPackages.toList()))
                SplitMode.OFF -> Unit
            }
        }

        val root = obj(
            "log" to obj("level" to "warn", "timestamp" to true),
            "experimental" to obj("cache_file" to obj("enabled" to true, "store_warp_config" to true)),
            "dns" to dns,
            "inbounds" to JSONArray().put(tun),
            "outbounds" to outbounds,
            "route" to route
        )
        if (endpoints.length() > 0) root.put("endpoints", endpoints)
        return root.toString()
    }
}
