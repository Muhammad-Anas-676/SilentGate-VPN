package com.silentgatevpn.app.vpn

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class ConnectMode(val label: String, val hint: String) {
    AUTO("Smart Auto", "Private server first, then community nodes in safe countries, then WARP as a backup. It never falls back to an unmasked connection."),
    NODES("Nodes", "Community nodes only, with automatic failover and safe exit countries."),
    WARP("WARP", "Obfuscated Cloudflare WARP only. Websites may see an exit near your own country."),
    WARP_NODE("WARP + Node", "WARP chained through a node, so the exit follows the node instead of your ISP's country (experimental)."),
    MY_SERVER("My server", "Your own VLESS / Trojan / Hysteria2 / WireGuard profile from Advanced > Custom profiles."),
    DPI_ONLY("DPI only", "Unblocks filtered sites with TLS fragmentation. It does NOT hide your IP address or country.")
}

enum class QuicMode(val label: String) { AUTO("Smart"), BLOCK("Block QUIC"), ALLOW("Allow QUIC") }

enum class WarpTunnel(val label: String, val core: String) {
    AUTO("Auto", "auto"),
    WG("WireGuard", "wg"),
    AWG("AmneziaWG", "awg"),
    MASQUE("MASQUE", "masque")
}

enum class DnsProvider(val label: String, val server: String, val sni: String?, val keywords: List<String>) {
    CLOUDFLARE("Cloudflare 1.1.1.1", "1.1.1.1", null, listOf("cloudflare")),
    QUAD9("Quad9 unfiltered", "9.9.9.10", "dns10.quad9.net", listOf("quad9", "pch", "woodynet")),
    ADGUARD("AdGuard unfiltered", "94.140.14.140", "unfiltered.adguard-dns.com", listOf("adguard")),
    GOOGLE("Google 8.8.8.8", "8.8.8.8", "dns.google", listOf("google"))
}

enum class SplitMode(val label: String) { OFF("Off"), EXCLUDE("Bypass selected"), ONLY("Only selected") }
enum class Ipv6Mode(val label: String) { BLOCK("Block (safest)"), TUNNEL("Tunnel") }

const val CONSENT_VERSION = 2

/** Bumped when defaults change; older saved settings are migrated once (see [AppSettings.fromJson]). */
const val SETTINGS_SCHEMA = 2

data class AppSettings(
    val consentVersion: Int = 0,
    val consentTime: Long = 0L,
    val mode: ConnectMode = ConnectMode.AUTO,
    val warpTunnel: WarpTunnel = WarpTunnel.AUTO,
    val noise: Boolean = true,
    val scanner: Boolean = true,
    val searchNoise: Boolean = true,
    val dns: DnsProvider = DnsProvider.CLOUDFLARE,
    val ipv6: Ipv6Mode = Ipv6Mode.BLOCK,
    val fragSize: String = "10-30",
    val fragSleep: String = "2-8",
    /** Off by default: fragmenting every proxy TLS handshake stalls media sites that open many parallel connections. */
    val fragmentStealth: Boolean = false,
    val splitMode: SplitMode = SplitMode.OFF,
    val splitPackages: Set<String> = emptySet(),
    val activeProfileId: String? = null,
    /** 0 = automatic (per protocol and per carrier path). */
    val mtuOverride: Int = 0,
    val communityNodes: Boolean = true,
    /** "AUTO" or an ISO country code chosen in the location picker. */
    val preferredCountry: String = "AUTO",
    val avoidRestricted: Boolean = true,
    val allowHomeExit: Boolean = false,
    /** Block by default: UDP/443 is rejected inside the tunnel so browsers use TCP, which carries thumbnails reliably. */
    val quic: QuicMode = QuicMode.BLOCK,
    /** Try the private (built-in or user supplied) primary server before anything else in Smart Auto. */
    val primaryFirst: Boolean = true,
    val schema: Int = SETTINGS_SCHEMA
) {
    val consented: Boolean get() = consentVersion >= CONSENT_VERSION

    fun toJson(): JSONObject = JSONObject().apply {
        put("consentVersion", consentVersion)
        put("consentTime", consentTime)
        put("mode", mode.name)
        put("warpTunnel", warpTunnel.name)
        put("noise", noise)
        put("scanner", scanner)
        put("searchNoise", searchNoise)
        put("dns", dns.name)
        put("ipv6", ipv6.name)
        put("fragSize", fragSize)
        put("fragSleep", fragSleep)
        put("fragmentStealth", fragmentStealth)
        put("splitMode", splitMode.name)
        put("splitPackages", JSONArray(splitPackages.toList()))
        put("activeProfileId", activeProfileId ?: JSONObject.NULL)
        put("mtuOverride", mtuOverride)
        put("communityNodes", communityNodes)
        put("preferredCountry", preferredCountry)
        put("avoidRestricted", avoidRestricted)
        put("allowHomeExit", allowHomeExit)
        put("quic", quic.name)
        put("primaryFirst", primaryFirst)
        put("schema", schema)
    }

    companion object {
        private inline fun <reified E : Enum<E>> JSONObject.enumOr(key: String, def: E): E =
            runCatching { enumValueOf<E>(getString(key)) }.getOrDefault(def)

        fun fromJson(o: JSONObject): AppSettings {
            val d = AppSettings()
            val pk = o.optJSONArray("splitPackages")
            val pkgs = buildSet { if (pk != null) for (i in 0 until pk.length()) add(pk.getString(i)) }
            // Settings written by v1.1.x: the old defaults (fragmentation on, QUIC "Smart") caused stalled thumbnails,
            // and the connection mode is no longer visible on Home, so a stale mode would be a hidden surprise.
            val migrate = o.optInt("schema", 1) < SETTINGS_SCHEMA
            val savedQuic = o.enumOr("quic", d.quic)
            return AppSettings(
                consentVersion = o.optInt("consentVersion", 0),
                consentTime = o.optLong("consentTime", 0L),
                mode = if (migrate) d.mode else o.enumOr("mode", d.mode),
                warpTunnel = o.enumOr("warpTunnel", d.warpTunnel),
                noise = o.optBoolean("noise", d.noise),
                scanner = o.optBoolean("scanner", d.scanner),
                searchNoise = o.optBoolean("searchNoise", d.searchNoise),
                dns = o.enumOr("dns", d.dns),
                ipv6 = o.enumOr("ipv6", d.ipv6),
                fragSize = o.optString("fragSize", d.fragSize),
                fragSleep = o.optString("fragSleep", d.fragSleep),
                fragmentStealth = if (migrate) d.fragmentStealth else o.optBoolean("fragmentStealth", d.fragmentStealth),
                splitMode = o.enumOr("splitMode", d.splitMode),
                splitPackages = pkgs,
                activeProfileId = if (o.isNull("activeProfileId")) null else o.optString("activeProfileId"),
                mtuOverride = o.optInt("mtuOverride", d.mtuOverride),
                communityNodes = o.optBoolean("communityNodes", d.communityNodes),
                preferredCountry = o.optString("preferredCountry", d.preferredCountry).ifEmpty { "AUTO" },
                avoidRestricted = o.optBoolean("avoidRestricted", d.avoidRestricted),
                allowHomeExit = o.optBoolean("allowHomeExit", d.allowHomeExit),
                quic = if (migrate) (if (savedQuic == QuicMode.ALLOW) QuicMode.ALLOW else QuicMode.BLOCK) else savedQuic,
                primaryFirst = o.optBoolean("primaryFirst", d.primaryFirst),
                schema = SETTINGS_SCHEMA
            )
        }
    }
}

object SettingsStore {
    private lateinit var prefs: SharedPreferences
    private val _flow = MutableStateFlow(AppSettings())
    val flow: StateFlow<AppSettings> = _flow.asStateFlow()
    val value: AppSettings get() = _flow.value

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("silentgate_settings", Context.MODE_PRIVATE)
        val raw = prefs.getString("settings", null)
        if (raw != null) runCatching {
            val loaded = AppSettings.fromJson(JSONObject(raw))
            _flow.value = loaded
            prefs.edit().putString("settings", loaded.toJson().toString()).apply()
        }
    }

    fun update(block: (AppSettings) -> AppSettings) {
        val next = block(_flow.value)
        _flow.value = next
        prefs.edit().putString("settings", next.toJson().toString()).apply()
    }
}

/** A user supplied profile: any share link (vless/trojan/hysteria2/tuic/ss/wg ...) or a raw sing-box outbound/endpoint JSON. */
data class Profile(val id: String, val name: String, val kind: String, val data: String) {
    val subtitle: String
        get() = runCatching { ProxyParser.parseAny(data).subtitle }.getOrDefault(if (kind == "json") "Custom JSON" else "Share link")
}

object ProfileStore {
    private lateinit var prefs: SharedPreferences
    private val _flow = MutableStateFlow<List<Profile>>(emptyList())
    val flow: StateFlow<List<Profile>> = _flow.asStateFlow()

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("silentgate_profiles", Context.MODE_PRIVATE)
        val raw = prefs.getString("profiles", null) ?: return
        runCatching {
            val arr = JSONArray(raw)
            _flow.value = (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Profile(o.getString("id"), o.getString("name"), o.getString("kind"), o.getString("data"))
            }
        }
    }

    private fun save() {
        val arr = JSONArray()
        _flow.value.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("kind", it.kind).put("data", it.data))
        }
        prefs.edit().putString("profiles", arr.toString()).apply()
    }

    /** Returns an error message or null when the profile was added. */
    fun import(text: String): String? {
        val t = text.trim()
        if (t.isEmpty()) return "Nothing to import"
        if (t.startsWith("http://", true) || t.startsWith("https://", true)) {
            return "That is a subscription URL. Use \"Add subscription\" in the Community nodes section."
        }
        val parsed = runCatching { ProxyParser.parseAny(t) }.getOrElse { return "Invalid profile: ${it.message}" }
        val kind = if (t.startsWith("{")) "json" else "link"
        val profile = Profile(UUID.randomUUID().toString(), parsed.name, kind, t)
        _flow.value = _flow.value + profile
        save()
        if (SettingsStore.value.activeProfileId == null) SettingsStore.update { it.copy(activeProfileId = profile.id) }
        return null
    }

    fun remove(id: String) {
        _flow.value = _flow.value.filterNot { it.id == id }
        save()
        if (SettingsStore.value.activeProfileId == id) {
            SettingsStore.update { it.copy(activeProfileId = _flow.value.firstOrNull()?.id) }
        }
    }

    fun active(): Profile? {
        val id = SettingsStore.value.activeProfileId
        return _flow.value.firstOrNull { it.id == id } ?: _flow.value.firstOrNull()
    }
}
