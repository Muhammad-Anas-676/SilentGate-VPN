package com.silentgatevpn.app.vpn

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** One proxy server, parsed from a share link, a Clash/sing-box entry or raw sing-box JSON. */
class ParsedProxy(
    val name: String,
    val protocol: String,          // vless, vmess, trojan, hysteria2, tuic, shadowsocks, wireguard, other
    val server: String,
    val port: Int,
    val transport: String,         // tcp, ws, grpc, httpupgrade, xhttp, http, udp
    val security: String,          // reality, tls, none
    val udpNative: Boolean,        // true when UDP (and so QUIC/HTTP3) travels as real UDP through the server
    val insecureCert: Boolean,     // certificate verification disabled by the link
    val isEndpoint: Boolean,       // sing-box "endpoint" (WireGuard) instead of "outbound"
    private val base: JSONObject,
    val key: String
) {
    /** TLS fragmentation only makes sense for TCP + normal TLS (not Reality, not QUIC). */
    val fragmentable: Boolean
        get() = !isEndpoint && security == "tls" && protocol in setOf("vless", "vmess", "trojan")

    /** Preferred TUN MTU for this transport (the service lowers it further when the carrier path is smaller). */
    val mtuHint: Int
        get() = when {
            isEndpoint -> 1280
            protocol == "hysteria2" || protocol == "tuic" -> 1350
            else -> 1450
        }

    val isIpServer: Boolean get() = ProxyParser.isIpLiteral(server)

    val subtitle: String
        get() = buildString {
            append(protocol.uppercase())
            if (security != "none") append(" · ").append(security)
            if (transport != "tcp" && transport != "udp") append(" · ").append(transport)
        }

    /** Builds the sing-box JSON. [serverIp] replaces the hostname (pre-resolved over DoH), SNI/Host stay untouched. */
    fun toJson(tag: String, fragment: JSONObject?, resolver: String?, serverIp: String?): JSONObject {
        val o = JSONObject(base.toString())
        o.put("tag", tag)
        if (isEndpoint) {
            if (serverIp != null) {
                val peers = o.optJSONArray("peers")
                peers?.optJSONObject(0)?.put("address", serverIp)
            }
            return o
        }
        if (serverIp != null) o.put("server", serverIp)
        if (resolver != null && serverIp == null && !o.has("domain_resolver") && !isIpServer) {
            o.put("domain_resolver", resolver)
        }
        if (fragment != null && fragmentable) o.put("tls_fragment", fragment)
        return o
    }
}

private class Link(
    val scheme: String, val user: String, val host: String, val port: Int,
    val q: Map<String, String>, val frag: String, val authority: String, val body: String
)

/** TLS / transport options shared between share links and Clash entries. */
private class Opt(
    val net: String, val security: String, val sni: String, val fp: String, val alpn: List<String>,
    val insecure: Boolean, val path: String, val host: String, val service: String, val mode: String,
    val pbk: String, val sid: String, val flow: String
)

object B64 {
    private const val TABLE = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /** Tolerant decoder: standard or URL-safe alphabet, with or without padding, whitespace ignored. */
    fun decode(input: String): ByteArray? {
        val s = input.filter { !it.isWhitespace() }.replace('-', '+').replace('_', '/').trimEnd('=')
        if (s.isEmpty()) return ByteArray(0)
        val out = ByteArrayOutputStream(s.length * 3 / 4 + 3)
        var buf = 0
        var bits = 0
        for (ch in s) {
            val v = TABLE.indexOf(ch)
            if (v < 0) return null
            buf = (buf shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buf shr bits) and 0xFF)
                buf = buf and ((1 shl bits) - 1)
            }
        }
        return out.toByteArray()
    }

    fun decodeToString(input: String): String? = decode(input)?.toString(Charsets.UTF_8)
}

object ProxyParser {

    /**
     * Shadowsocks UDP relay is NOT assumed: most free public Shadowsocks nodes disable it, and Chrome's HTTP/3 (QUIC)
     * then hangs instead of falling back to TCP. With false, the QUIC policy rejects UDP/443 for these nodes.
     */
    private const val SS_UDP_RELAY = false

    private val LINK_SCHEMES = setOf("vless", "vmess", "trojan", "hysteria2", "hy2", "tuic", "ss", "wg", "wireguard")

    fun isLinkScheme(s: String): Boolean = LINK_SCHEMES.contains(s.lowercase())

    fun looksLikeLink(line: String): Boolean {
        val i = line.indexOf("://")
        return i in 2..12 && LINK_SCHEMES.contains(line.substring(0, i).lowercase())
    }

    fun isIpLiteral(h: String): Boolean =
        h.contains(':') || (h.isNotEmpty() && h.all { it.isDigit() || it == '.' } && h.count { it == '.' } == 3)

    /** Link (vless://, trojan://, ...) or a raw sing-box outbound/endpoint JSON object. */
    fun parseAny(raw: String): ParsedProxy {
        val t = raw.trim()
        return if (t.startsWith("{")) fromJson(t) else parseLink(t)
    }

    // ------------------------------------------------------------------ helpers

    private fun pct(s: String): String {
        if (!s.contains('%')) return s
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val h = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (h != null) { out.write(h); i += 3; continue }
            }
            val cp = s.codePointAt(i)
            out.write(String(Character.toChars(cp)).toByteArray(Charsets.UTF_8))
            i += Character.charCount(cp)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private fun splitLink(raw: String): Link {
        val t = raw.trim()
        val si = t.indexOf("://")
        require(si > 0) { "not a link" }
        val scheme = t.substring(0, si).lowercase()
        var rest = t.substring(si + 3)
        var frag = ""
        val hash = rest.indexOf('#')
        if (hash >= 0) { frag = pct(rest.substring(hash + 1)).trim(); rest = rest.substring(0, hash) }
        var query = ""
        val qi = rest.indexOf('?')
        if (qi >= 0) { query = rest.substring(qi + 1); rest = rest.substring(0, qi) }
        val slash = rest.indexOf('/')
        val authority = if (slash >= 0) rest.substring(0, slash) else rest
        val at = authority.lastIndexOf('@')
        val user = if (at >= 0) pct(authority.substring(0, at)) else ""
        val hostPort = authority.substring(at + 1)
        var host = hostPort
        var port = -1
        if (hostPort.startsWith("[")) {
            val end = hostPort.indexOf(']')
            if (end > 0) {
                host = hostPort.substring(1, end)
                port = hostPort.substring(end + 1).removePrefix(":").toIntOrNull() ?: -1
            }
        } else {
            val c = hostPort.lastIndexOf(':')
            if (c >= 0) { host = hostPort.substring(0, c); port = hostPort.substring(c + 1).toIntOrNull() ?: -1 }
        }
        val q = HashMap<String, String>()
        if (query.isNotEmpty()) for (kv in query.split('&')) {
            val e = kv.indexOf('=')
            if (e > 0) q[kv.substring(0, e)] = pct(kv.substring(e + 1))
        }
        return Link(scheme, user, host, port, q, frag, authority, rest)
    }

    private fun normNet(n: String): String = when (n.lowercase()) {
        "", "tcp", "raw" -> "tcp"
        "h2", "http" -> "http"
        "splithttp", "xhttp" -> "xhttp"
        else -> n.lowercase()
    }

    private fun optFrom(q: Map<String, String>, defSecurity: String): Opt {
        fun g(vararg k: String): String { for (x in k) q[x]?.let { if (it.isNotEmpty()) return it }; return "" }
        val sec = g("security").ifEmpty { defSecurity }.lowercase()
        return Opt(
            net = normNet(g("type", "network")),
            security = if (sec == "xtls") "tls" else sec,
            sni = g("sni", "peer", "servername"),
            fp = g("fp", "fingerprint").ifEmpty { "chrome" },
            alpn = g("alpn").split(',').map { it.trim() }.filter { it.isNotEmpty() },
            insecure = g("allowInsecure", "insecure", "allow_insecure") == "1" || g("allowInsecure", "insecure") == "true",
            path = g("path"), host = g("host"), service = g("serviceName"), mode = g("mode"),
            pbk = g("pbk"), sid = g("sid"), flow = g("flow")
        )
    }

    private fun applyTls(o: JSONObject, p: Opt, host: String) {
        if (p.security != "tls" && p.security != "reality") return
        val sni = p.sni.ifEmpty { p.host.ifEmpty { host } }
        val tls = JSONObject().put("enabled", true).put("server_name", sni)
        if (p.insecure) tls.put("insecure", true)
        if (p.alpn.isNotEmpty()) tls.put("alpn", JSONArray(p.alpn))
        if (p.fp.isNotEmpty()) tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", p.fp))
        if (p.security == "reality") {
            require(p.pbk.isNotEmpty()) { "Reality link has no public key (pbk)" }
            tls.put("reality", JSONObject().put("enabled", true).put("public_key", p.pbk).put("short_id", p.sid))
        }
        o.put("tls", tls)
    }

    private fun applyTransport(o: JSONObject, p: Opt, host: String) {
        val hostHdr = p.host.ifEmpty { p.sni.ifEmpty { host } }
        when (p.net) {
            "ws" -> {
                var path = p.path.ifEmpty { "/" }
                val ws = JSONObject().put("type", "ws")
                val ed = Regex("[?&]ed=(\\d+)").find(path)
                if (ed != null) {
                    ws.put("max_early_data", ed.groupValues[1].toInt()).put("early_data_header_name", "Sec-WebSocket-Protocol")
                    path = path.replace(Regex("[?&]ed=\\d+"), "").ifEmpty { "/" }
                }
                ws.put("path", path).put("headers", JSONObject().put("Host", hostHdr))
                o.put("transport", ws)
            }
            "grpc" -> o.put("transport", JSONObject().put("type", "grpc").put("service_name", p.service))
            "httpupgrade" -> o.put("transport", JSONObject().put("type", "httpupgrade").put("host", hostHdr).put("path", p.path.ifEmpty { "/" }))
            "xhttp" -> {
                // Needs a core build with XHTTP support; a core that lacks it rejects the config and the node is skipped.
                val t = JSONObject().put("type", "xhttp").put("host", hostHdr).put("path", p.path.ifEmpty { "/" })
                if (p.mode.isNotEmpty()) t.put("mode", p.mode)
                o.put("transport", t)
            }
            "http" -> o.put("transport", JSONObject().put("type", "http").put("host", JSONArray().put(hostHdr)).put("path", p.path.ifEmpty { "/" }))
        }
    }

    private fun need(cond: Boolean, msg: String) { if (!cond) throw IllegalArgumentException(msg) }

    // ------------------------------------------------------------------ share links

    fun parseLink(raw: String): ParsedProxy {
        val t = raw.trim()
        val scheme = t.substringBefore("://", "").lowercase()
        return when (scheme) {
            "vless" -> vless(splitLink(t))
            "trojan" -> trojan(splitLink(t))
            "vmess" -> vmess(t)
            "ss" -> shadowsocks(t)
            "hysteria2", "hy2" -> hysteria2(splitLink(t))
            "tuic" -> tuic(splitLink(t))
            "wg", "wireguard" -> wireguard(splitLink(t))
            else -> throw IllegalArgumentException("unsupported link type: $scheme")
        }
    }

    private fun vless(l: Link): ParsedProxy {
        need(l.user.isNotEmpty(), "missing UUID")
        need(l.host.isNotEmpty(), "missing host")
        val port = if (l.port > 0) l.port else 443
        val p = optFrom(l.q, "none")
        need(p.security in setOf("tls", "reality", "none"), "unsupported security: ${p.security}")
        need(p.net in setOf("tcp", "ws", "grpc", "httpupgrade", "xhttp", "http"), "unsupported transport: ${p.net}")
        return buildVless(l.frag.ifEmpty { l.host }, l.user, l.host, port, p)
    }

    private fun buildVless(name: String, uuid: String, host: String, port: Int, p: Opt): ParsedProxy {
        val o = JSONObject().put("type", "vless").put("server", host).put("server_port", port).put("uuid", uuid)
        if (p.flow.isNotEmpty()) o.put("flow", p.flow)
        o.put("packet_encoding", "xudp")
        applyTls(o, p, host)
        applyTransport(o, p, host)
        return ParsedProxy(
            name, "vless", host, port, p.net, p.security, false, p.insecure, false, o,
            "vless|$host|$port|$uuid|${p.net}|${p.path}|${p.sni}|${p.pbk}|${p.service}"
        )
    }

    private fun trojan(l: Link): ParsedProxy {
        need(l.user.isNotEmpty(), "missing password")
        need(l.host.isNotEmpty(), "missing host")
        val port = if (l.port > 0) l.port else 443
        val p = optFrom(l.q, "tls")
        return buildTrojan(l.frag.ifEmpty { l.host }, l.user, l.host, port, p)
    }

    private fun buildTrojan(name: String, password: String, host: String, port: Int, p: Opt): ParsedProxy {
        need(p.security in setOf("tls", "none"), "unsupported security: ${p.security}")
        val o = JSONObject().put("type", "trojan").put("server", host).put("server_port", port).put("password", password)
        applyTls(o, p, host)
        applyTransport(o, p, host)
        return ParsedProxy(
            name, "trojan", host, port, p.net, p.security, false, p.insecure, false, o,
            "trojan|$host|$port|$password|${p.net}|${p.path}|${p.sni}"
        )
    }

    private fun vmess(t: String): ParsedProxy {
        val body = t.substring(t.indexOf("://") + 3).substringBefore('#')
        val json = B64.decodeToString(body) ?: throw IllegalArgumentException("bad vmess base64")
        val j = JSONObject(json)
        val host = j.optString("add")
        need(host.isNotEmpty(), "missing host")
        val port = j.optString("port").toIntOrNull() ?: 443
        val id = j.optString("id")
        need(id.isNotEmpty(), "missing UUID")
        val q = HashMap<String, String>()
        q["type"] = j.optString("net")
        q["security"] = if (j.optString("tls").equals("tls", true)) "tls" else "none"
        q["sni"] = j.optString("sni"); q["host"] = j.optString("host"); q["path"] = j.optString("path")
        q["alpn"] = j.optString("alpn"); q["fp"] = j.optString("fp"); q["serviceName"] = j.optString("path")
        val p = optFrom(q, "none")
        need(p.net in setOf("tcp", "ws", "grpc", "httpupgrade", "http"), "unsupported transport: ${p.net}")
        val o = JSONObject().put("type", "vmess").put("server", host).put("server_port", port).put("uuid", id)
            .put("security", j.optString("scy").ifEmpty { "auto" })
            .put("alter_id", j.optString("aid").toIntOrNull() ?: 0)
        applyTls(o, p, host)
        applyTransport(o, p, host)
        return ParsedProxy(
            j.optString("ps").ifEmpty { host }, "vmess", host, port, p.net, p.security, false, p.insecure, false, o,
            "vmess|$host|$port|$id|${p.net}|${p.path}|${p.sni}"
        )
    }

    private fun shadowsocks(t: String): ParsedProxy {
        var rest = t.substring(t.indexOf("://") + 3)
        var name = ""
        val hash = rest.indexOf('#')
        if (hash >= 0) { name = pct(rest.substring(hash + 1)).trim(); rest = rest.substring(0, hash) }
        var query = ""
        val qi = rest.indexOf('?')
        if (qi >= 0) { query = rest.substring(qi + 1); rest = rest.substring(0, qi) }
        rest = rest.trimEnd('/')
        val method: String
        val password: String
        val host: String
        val port: Int
        val at = rest.lastIndexOf('@')
        if (at >= 0) {
            val ui = rest.substring(0, at)
            val cred = if (ui.contains(':')) pct(ui) else (B64.decodeToString(pct(ui)) ?: throw IllegalArgumentException("bad ss userinfo"))
            val c = cred.indexOf(':')
            need(c > 0, "bad ss credentials")
            method = cred.substring(0, c); password = cred.substring(c + 1)
            val hp = rest.substring(at + 1)
            val cp = hp.lastIndexOf(':')
            need(cp > 0, "missing port")
            host = hp.substring(0, cp).trim('[', ']'); port = hp.substring(cp + 1).toIntOrNull() ?: throw IllegalArgumentException("bad port")
        } else {
            val dec = B64.decodeToString(rest) ?: throw IllegalArgumentException("bad ss link")
            val a2 = dec.lastIndexOf('@')
            need(a2 > 0, "bad ss link")
            val cred = dec.substring(0, a2)
            val c = cred.indexOf(':')
            need(c > 0, "bad ss credentials")
            method = cred.substring(0, c); password = cred.substring(c + 1)
            val hp = dec.substring(a2 + 1)
            val cp = hp.lastIndexOf(':')
            need(cp > 0, "missing port")
            host = hp.substring(0, cp).trim('[', ']'); port = hp.substring(cp + 1).toIntOrNull() ?: throw IllegalArgumentException("bad port")
        }
        need(host.isNotEmpty(), "missing host")
        val o = JSONObject().put("type", "shadowsocks").put("server", host).put("server_port", port)
            .put("method", method).put("password", password)
        var plugin = ""
        if (query.isNotEmpty()) for (kv in query.split('&')) {
            val e = kv.indexOf('=')
            if (e > 0 && kv.substring(0, e) == "plugin") plugin = pct(kv.substring(e + 1))
        }
        if (plugin.isNotEmpty()) {
            val pn = plugin.substringBefore(';').let { if (it == "simple-obfs") "obfs-local" else it }
            o.put("plugin", pn).put("plugin_opts", plugin.substringAfter(';', ""))
        }
        return ParsedProxy(
            name.ifEmpty { host }, "shadowsocks", host, port, "tcp", "none", SS_UDP_RELAY, false, false, o,
            "ss|$host|$port|$method|$password|$plugin"
        )
    }

    private fun hysteria2(l: Link): ParsedProxy {
        need(l.user.isNotEmpty(), "missing auth")
        need(l.host.isNotEmpty(), "missing host")
        val port = if (l.port > 0) l.port else 443
        return buildHysteria2(l.frag.ifEmpty { l.host }, l.user, l.host, port, l.q["sni"] ?: l.q["peer"] ?: "",
            (l.q["insecure"] ?: l.q["allowInsecure"]) == "1", l.q["obfs"] ?: "", l.q["obfs-password"] ?: "", l.q["alpn"] ?: "")
    }

    private fun buildHysteria2(
        name: String, auth: String, host: String, port: Int, sni: String, insecure: Boolean,
        obfs: String, obfsPw: String, alpn: String
    ): ParsedProxy {
        val tls = JSONObject().put("enabled", true).put("server_name", sni.ifEmpty { host })
            .put("alpn", JSONArray(alpn.split(',').map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf("h3") }))
        if (insecure) tls.put("insecure", true)
        val o = JSONObject().put("type", "hysteria2").put("server", host).put("server_port", port).put("password", auth).put("tls", tls)
        if (obfs.equals("salamander", true) && obfsPw.isNotEmpty()) {
            o.put("obfs", JSONObject().put("type", "salamander").put("password", obfsPw))
        }
        return ParsedProxy(name, "hysteria2", host, port, "udp", "tls", true, insecure, false, o, "hy2|$host|$port|$auth|$obfsPw|$sni")
    }

    private fun tuic(l: Link): ParsedProxy {
        need(l.user.isNotEmpty(), "missing uuid:password")
        need(l.host.isNotEmpty(), "missing host")
        val port = if (l.port > 0) l.port else 443
        val uuid = l.user.substringBefore(':')
        val pw = l.user.substringAfter(':', "")
        return buildTuic(l.frag.ifEmpty { l.host }, uuid, pw, l.host, port, l.q["sni"] ?: "", l.q["alpn"] ?: "",
            (l.q["allow_insecure"] ?: l.q["allowInsecure"] ?: l.q["insecure"]) == "1",
            l.q["congestion_control"] ?: "bbr", l.q["udp_relay_mode"] ?: "native")
    }

    private fun buildTuic(
        name: String, uuid: String, pw: String, host: String, port: Int, sni: String, alpn: String,
        insecure: Boolean, cc: String, relay: String
    ): ParsedProxy {
        val tls = JSONObject().put("enabled", true).put("server_name", sni.ifEmpty { host })
            .put("alpn", JSONArray(alpn.split(',').map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf("h3") }))
        if (insecure) tls.put("insecure", true)
        val o = JSONObject().put("type", "tuic").put("server", host).put("server_port", port).put("uuid", uuid)
            .put("password", pw).put("congestion_control", cc).put("udp_relay_mode", relay).put("tls", tls)
        return ParsedProxy(name, "tuic", host, port, "udp", "tls", true, insecure, false, o, "tuic|$host|$port|$uuid|$sni")
    }

    /** wg://PRIVATE_KEY@host:port?publickey=..&address=10.0.0.2/32[,fd00::2/128]&mtu=1280&reserved=1,2,3&presharedkey=..#name */
    private fun wireguard(l: Link): ParsedProxy {
        need(l.user.isNotEmpty(), "missing private key")
        need(l.host.isNotEmpty(), "missing host")
        val pub = l.q["publickey"] ?: l.q["public_key"] ?: ""
        need(pub.isNotEmpty(), "missing publickey")
        val port = if (l.port > 0) l.port else 51820
        val addr = (l.q["address"] ?: "10.0.0.2/32").split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val peer = JSONObject().put("address", l.host).put("port", port).put("public_key", pub)
            .put("allowed_ips", JSONArray().put("0.0.0.0/0").put("::/0"))
        l.q["presharedkey"]?.let { if (it.isNotEmpty()) peer.put("pre_shared_key", it) }
        l.q["reserved"]?.let { r ->
            val nums = r.split(',').mapNotNull { it.trim().toIntOrNull() }
            if (nums.size == 3) peer.put("reserved", JSONArray(nums))
        }
        val o = JSONObject().put("type", "wireguard").put("address", JSONArray(addr)).put("private_key", l.user)
            .put("peers", JSONArray().put(peer)).put("mtu", l.q["mtu"]?.toIntOrNull() ?: 1280)
        return ParsedProxy(l.frag.ifEmpty { l.host }, "wireguard", l.host, port, "udp", "none", true, false, true, o, "wg|${l.host}|$port|$pub")
    }

    // ------------------------------------------------------------------ raw sing-box JSON

    private val ENDPOINT_TYPES = setOf("wireguard", "awg", "warp")

    fun fromJson(text: String): ParsedProxy {
        val src = JSONObject(text)
        val type = src.optString("type")
        need(type.isNotEmpty(), "JSON needs a \"type\" field (vless, wireguard, awg ...)")
        val name = src.optString("tag").ifEmpty { "Custom $type" }
        val isEndpoint = type in ENDPOINT_TYPES
        val base = JSONObject(text)
        base.remove("tag")
        var server = src.optString("server")
        var port = src.optInt("server_port", 0)
        if (isEndpoint) {
            val peer = src.optJSONArray("peers")?.optJSONObject(0)
            server = peer?.optString("address") ?: ""
            port = peer?.optInt("port", 0) ?: 0
        }
        val tls = src.optJSONObject("tls")
        val security = when {
            tls?.optJSONObject("reality")?.optBoolean("enabled", false) == true -> "reality"
            tls?.optBoolean("enabled", false) == true -> "tls"
            else -> "none"
        }
        val transport = src.optJSONObject("transport")?.optString("type")?.ifEmpty { null } ?: if (type in setOf("hysteria2", "tuic", "wireguard", "awg", "warp")) "udp" else "tcp"
        val protocol = when (type) { "shadowsocks" -> "shadowsocks"; "awg" -> "wireguard"; else -> type }
        val udp = type in setOf("hysteria2", "hysteria", "tuic", "wireguard", "awg", "warp") || (type == "shadowsocks" && SS_UDP_RELAY)
        return ParsedProxy(
            name, protocol, server, port, transport, security, udp, tls?.optBoolean("insecure", false) == true, isEndpoint, base,
            "json|" + base.toString().hashCode()
        )
    }

    // ------------------------------------------------------------------ Clash entries (flattened "a.b.c" keys)

    fun fromClash(m: Map<String, String>): ParsedProxy {
        fun g(vararg k: String): String { for (x in k) m[x]?.let { if (it.isNotEmpty()) return it }; return "" }
        val type = g("type").lowercase()
        val host = g("server")
        val port = g("port").toIntOrNull() ?: 443
        val name = g("name").ifEmpty { host }
        need(host.isNotEmpty(), "missing server")
        val net = g("network").ifEmpty { "tcp" }
        val pbk = g("reality-opts.public-key")
        val tlsOn = g("tls").equals("true", true)
        val sec = when { pbk.isNotEmpty() -> "reality"; tlsOn || type == "trojan" -> "tls"; else -> "none" }
        val q = hashMapOf(
            "type" to net, "security" to sec,
            "sni" to g("servername", "sni"), "fp" to g("client-fingerprint", "fingerprint"),
            "pbk" to pbk, "sid" to g("reality-opts.short-id"), "flow" to g("flow"),
            "path" to g("ws-opts.path", "h2-opts.path", "httpupgrade-opts.path", "xhttp-opts.path"),
            "host" to g("ws-opts.headers.Host", "ws-opts.headers.host", "h2-opts.host", "httpupgrade-opts.host", "xhttp-opts.host"),
            "serviceName" to g("grpc-opts.grpc-service-name"),
            "alpn" to g("alpn"),
            "allowInsecure" to if (g("skip-cert-verify").equals("true", true)) "1" else "0"
        )
        return when (type) {
            "vless" -> {
                val p = optFrom(q, "none")
                need(p.net in setOf("tcp", "ws", "grpc", "httpupgrade", "xhttp", "http"), "unsupported transport: ${p.net}")
                buildVless(name, g("uuid"), host, port, p)
            }
            "trojan" -> buildTrojan(name, g("password"), host, port, optFrom(q, "tls"))
            "ss" -> {
                val o = JSONObject().put("type", "shadowsocks").put("server", host).put("server_port", port)
                    .put("method", g("cipher")).put("password", g("password"))
                val plugin = g("plugin")
                if (plugin == "obfs" || plugin == "simple-obfs") {
                    o.put("plugin", "obfs-local").put("plugin_opts", "obfs=${g("plugin-opts.mode")};obfs-host=${g("plugin-opts.host")}")
                }
                ParsedProxy(name, "shadowsocks", host, port, "tcp", "none", SS_UDP_RELAY, false, false, o, "ss|$host|$port|${g("cipher")}|${g("password")}|$plugin")
            }
            "hysteria2", "hy2" -> buildHysteria2(name, g("password"), host, port, g("sni", "servername"),
                g("skip-cert-verify").equals("true", true), g("obfs"), g("obfs-password"), g("alpn"))
            "tuic" -> buildTuic(name, g("uuid"), g("password"), host, port, g("sni", "servername"), g("alpn"),
                g("skip-cert-verify").equals("true", true), g("congestion-controller").ifEmpty { "bbr" }, g("udp-relay-mode").ifEmpty { "native" })
            else -> throw IllegalArgumentException("unsupported clash type: $type")
        }
    }
}

/** Very small YAML reader, just enough for the `proxies:` list of Clash subscriptions. */
object MiniYaml {

    fun proxies(text: String): List<Map<String, String>> {
        val lines = text.lines()
        var i = 0
        while (i < lines.size && !lines[i].startsWith("proxies:")) i++
        if (i >= lines.size) return emptyList()
        i++
        val out = ArrayList<Map<String, String>>()
        var base = -1
        var cur: MutableMap<String, String>? = null
        val stack = ArrayList<Pair<Int, String>>()
        while (i < lines.size) {
            val raw = lines[i++]
            if (raw.isBlank() || raw.trimStart().startsWith("#")) continue
            val trimmed = raw.trimStart()
            val indent = raw.length - trimmed.length
            if (indent == 0 && !trimmed.startsWith("-")) break
            var content = trimmed.trimEnd()
            if (content.startsWith("- ") || content == "-") {
                if (base < 0) base = indent
                if (indent <= base) {
                    val m = HashMap<String, String>()
                    cur = m
                    out.add(m)
                    stack.clear()
                    content = content.removePrefix("-").trim()
                    if (content.isEmpty()) continue
                    if (content.startsWith("{")) { flowMap(Flow(content), m, ""); continue }
                    kv(content, indent + 2, m, stack)
                } else {
                    val item = unquote(content.removePrefix("-").trim())
                    val key = stack.lastOrNull()?.second
                    val m = cur
                    if (key != null && m != null) m[key] = if (m[key].isNullOrEmpty()) item else m[key] + "," + item
                }
                continue
            }
            val m = cur ?: continue
            kv(content, indent, m, stack)
        }
        return out
    }

    private fun kv(content: String, indent: Int, m: MutableMap<String, String>, stack: ArrayList<Pair<Int, String>>) {
        val c = content.indexOf(':')
        if (c <= 0) return
        val key = unquote(content.substring(0, c).trim())
        val v = content.substring(c + 1).trim()
        while (stack.isNotEmpty() && stack.last().first >= indent) stack.removeAt(stack.size - 1)
        val full = (if (stack.isEmpty()) "" else stack.last().second + ".") + key
        when {
            v.isEmpty() -> stack.add(indent to full)
            v.startsWith("{") -> flowMap(Flow(v), m, "$full.")
            v.startsWith("[") -> m[full] = flowList(Flow(v)).joinToString(",")
            else -> m[full] = unquote(v)
        }
    }

    private fun unquote(s: String): String {
        val t = s.trim()
        return if (t.length >= 2 && ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("'") && t.endsWith("'")))) t.substring(1, t.length - 1) else t
    }

    private class Flow(val s: String, var i: Int = 0) {
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
    }

    private fun scalar(f: Flow): String {
        f.ws()
        if (f.i < f.s.length && (f.s[f.i] == '"' || f.s[f.i] == '\'')) {
            val q = f.s[f.i++]
            val st = f.i
            while (f.i < f.s.length && f.s[f.i] != q) f.i++
            val r = f.s.substring(st, minOf(f.i, f.s.length))
            f.i++
            return r
        }
        val st = f.i
        while (f.i < f.s.length && f.s[f.i] != ',' && f.s[f.i] != '}' && f.s[f.i] != ']') f.i++
        return f.s.substring(st, f.i).trim()
    }

    private fun flowList(f: Flow): List<String> {
        val out = ArrayList<String>()
        f.i++ // [
        while (f.i < f.s.length) {
            f.ws()
            if (f.i >= f.s.length) break
            when (f.s[f.i]) {
                ']' -> { f.i++; return out }
                ',' -> f.i++
                else -> out.add(scalar(f))
            }
        }
        return out
    }

    private fun flowMap(f: Flow, out: MutableMap<String, String>, prefix: String) {
        f.i++ // {
        while (f.i < f.s.length) {
            f.ws()
            if (f.i >= f.s.length) return
            val ch = f.s[f.i]
            if (ch == '}') { f.i++; return }
            if (ch == ',') { f.i++; continue }
            // key
            val key: String
            if (ch == '"' || ch == '\'') {
                key = scalar(f)
            } else {
                val st = f.i
                while (f.i < f.s.length && f.s[f.i] != ':' && f.s[f.i] != ',' && f.s[f.i] != '}') f.i++
                key = f.s.substring(st, f.i).trim()
            }
            f.ws()
            if (f.i < f.s.length && f.s[f.i] == ':') f.i++
            f.ws()
            if (f.i >= f.s.length) return
            when (f.s[f.i]) {
                '{' -> flowMap(f, out, "$prefix$key.")
                '[' -> out[prefix + key] = flowList(f).joinToString(",")
                else -> out[prefix + key] = scalar(f)
            }
        }
    }
}

/** Turns a downloaded subscription body (Base64 list, plain links, sing-box JSON, Clash YAML) into proxies. */
object SubscriptionParser {

    class Entry(val raw: String, val proxy: ParsedProxy)

    private const val MAX_LINES = 8000

    fun parse(body: String): List<Entry> {
        val t = body.trim().removePrefix("\uFEFF")
        if (t.isEmpty()) return emptyList()
        if (t.startsWith("{") || t.startsWith("[")) return fromSingboxJson(t)
        if (Regex("(?m)^proxies:").containsMatchIn(t)) return fromClash(t)
        val text = if (t.contains("://")) t else (B64.decodeToString(t) ?: t)
        if (text.trimStart().startsWith("{")) return fromSingboxJson(text.trim())
        val out = ArrayList<Entry>()
        var n = 0
        for (line in text.lineSequence()) {
            if (++n > MAX_LINES) break
            val l = line.trim()
            if (l.isEmpty() || !ProxyParser.looksLikeLink(l)) continue
            runCatching { ProxyParser.parseLink(l) }.onSuccess { out.add(Entry(l, it)) }
        }
        return out
    }

    private val SUPPORTED = setOf("vless", "vmess", "trojan", "hysteria2", "tuic", "shadowsocks")

    private fun fromSingboxJson(t: String): List<Entry> {
        val out = ArrayList<Entry>()
        val root: Any = runCatching { if (t.startsWith("[")) JSONArray(t) else JSONObject(t) }.getOrNull() ?: return out
        val arr: JSONArray = if (root is JSONArray) root else (root as JSONObject).optJSONArray("outbounds") ?: return out
        for (i in 0 until minOf(arr.length(), MAX_LINES)) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("type") !in SUPPORTED) continue
            runCatching { ProxyParser.fromJson(o.toString()) }.onSuccess { out.add(Entry(o.toString(), it)) }
        }
        return out
    }

    private fun fromClash(t: String): List<Entry> {
        val out = ArrayList<Entry>()
        for (m in MiniYaml.proxies(t).take(MAX_LINES)) {
            val p = runCatching { ProxyParser.fromClash(m) }.getOrNull() ?: continue
            // Re-encode as sing-box JSON so the pool can store it uniformly.
            val raw = runCatching { p.toJson(p.name, null, null, null).toString() }.getOrNull() ?: continue
            out.add(Entry(raw, p))
        }
        return out
    }
}
