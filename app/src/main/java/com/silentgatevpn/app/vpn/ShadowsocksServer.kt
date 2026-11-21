package com.silentgatevpn.app.vpn

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The user's own Shadowsocks server, used by the "Shadowsocks" engine on Home.
 *
 * One `ss://` link, stored encrypted (AES-256-GCM, key in the Android Keystore) so the password is never kept in
 * plain text on the phone. Only modern AEAD / 2022 ciphers are accepted; legacy stream ciphers and "none" are
 * rejected. The link itself is never shown again after saving: the UI only sees [summary] (host:port and cipher).
 */
object ShadowsocksServer {

    private const val PREFS = "silentgate_shadowsocks"
    private const val KEY_BLOB = "blob"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "silentgate_ss_aes"

    private val ALLOWED_METHODS = setOf(
        "aes-128-gcm", "aes-192-gcm", "aes-256-gcm",
        "chacha20-ietf-poly1305", "xchacha20-ietf-poly1305",
        "2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm", "2022-blake3-chacha20-poly1305"
    )

    private lateinit var prefs: SharedPreferences
    private var link: String? = null
    private val _summary = MutableStateFlow<String?>(null)

    /** "host:port · cipher" of the saved server, or null when none is saved. Never contains the password. */
    val summary: StateFlow<String?> = _summary.asStateFlow()

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val blob = prefs.getString(KEY_BLOB, null) ?: return
        val plain = runCatching { decrypt(blob) }.getOrNull()
        if (plain == null) {
            // Key lost or storage corrupted (for example app data restored on another phone): start clean.
            prefs.edit().remove(KEY_BLOB).apply()
            return
        }
        apply(String(plain, Charsets.UTF_8))
    }

    fun isConfigured(): Boolean = link != null

    /** The parsed server for the connect flow, or null when none is saved. */
    fun proxy(): ParsedProxy? = link?.let { runCatching { ProxyParser.parseLink(it) }.getOrNull() }

    /** Validates and stores the link (encrypted). Returns an error message, or null on success. */
    fun set(text: String): String? {
        val t = text.trim().lines().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (t.isEmpty()) return "Paste your ss:// link first"
        if (!t.startsWith("ss://", ignoreCase = true)) return "This must be a Shadowsocks link that starts with ss://"
        val p = runCatching { ProxyParser.parseLink(t) }.getOrElse { return "Invalid link: ${it.message}" }
        if (p.protocol != "shadowsocks") return "This must be a Shadowsocks link that starts with ss://"
        if (p.server.isEmpty() || p.port !in 1..65535) return "The link has no valid server or port"
        val parts = p.key.split("|")          // ss|host|port|method|password|plugin
        val method = parts.getOrNull(3).orEmpty().lowercase()
        val plugin = parts.getOrNull(5).orEmpty()
        if (method !in ALLOWED_METHODS) {
            return "Cipher \"$method\" is not supported. Use aes-256-gcm, chacha20-ietf-poly1305 or a 2022-blake3 cipher"
        }
        if (plugin.isNotEmpty()) return "Plugins (obfs, v2ray-plugin) are not supported. Use a plain ss:// link"
        val blob = runCatching { encrypt(t.toByteArray(Charsets.UTF_8)) }.getOrNull()
            ?: return "Secure storage is not available on this phone, nothing was saved"
        prefs.edit().putString(KEY_BLOB, blob).apply()
        apply(t)
        return null
    }

    fun clear() {
        prefs.edit().remove(KEY_BLOB).apply()
        link = null
        _summary.value = null
    }

    private fun apply(text: String) {
        val p = runCatching { ProxyParser.parseLink(text) }.getOrNull()
        if (p == null) { link = null; _summary.value = null; return }
        link = text
        val method = p.key.split("|").getOrNull(3).orEmpty()
        _summary.value = "${p.server}:${p.port} · $method"
    }

    // ------------------------------------------------------------------ AES-256-GCM with a Keystore key (API 23+)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    /** Output is Base64(iv || ciphertext+tag). A fresh random IV is generated by the Keystore for every call. */
    private fun encrypt(plain: ByteArray): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(plain)
        return Base64.encodeToString(c.iv + ct, Base64.NO_WRAP)
    }

    private fun decrypt(blob: String): ByteArray {
        val all = Base64.decode(blob, Base64.NO_WRAP)
        require(all.size > 12 + 16) { "blob too short" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all.copyOfRange(0, 12)))
        return c.doFinal(all, 12, all.size - 12)
    }
}
