package com.silentgatevpn.app.vpn

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.silentgatevpn.app.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The "primary server" slot: your own self-hosted server (for example VLESS + Reality on a VPS) that Smart Auto
 * tries BEFORE any community node. Two sources, the user's wins:
 *
 *  1. Entered in Advanced > Private server. Stored encrypted (AES-256-GCM, key in the Android Keystore), so the
 *     link is never kept in plain text on the phone.
 *  2. Built in at build time: Gradle property `sgPrimaryServer` or env var `SG_PRIMARY_SERVER` (CI secret), one or
 *     more share links separated by spaces. Nothing is hardcoded in the source. Note: anything inside an APK can be
 *     extracted by someone who works at it, so for a public release use a server that tolerates shared credentials.
 *
 * Only the link text is stored; the exit is verified like every other node before the app says "Connected".
 */
object PrimaryServer {

    private const val PREFS = "silentgate_primary"
    private const val KEY_BLOB = "blob"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "silentgate_primary_aes"
    private const val MAX_LINKS = 3

    private lateinit var prefs: SharedPreferences
    private val _user = MutableStateFlow<List<String>>(emptyList())

    /** Decrypted user links (kept in memory only). */
    val userLinks: StateFlow<List<String>> = _user.asStateFlow()

    val builtInLinks: List<String> by lazy { splitLinks(BuildConfig.PRIMARY_SERVER) }

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val blob = prefs.getString(KEY_BLOB, null) ?: return
        val plain = runCatching { decrypt(blob) }.getOrNull()
        if (plain == null) {
            // Key lost or storage corrupted (for example app data restored on another phone): start clean.
            prefs.edit().remove(KEY_BLOB).apply()
            return
        }
        _user.value = splitLinks(String(plain, Charsets.UTF_8))
    }

    /** Parsed servers in priority order: the user's own first, then the built-in ones. */
    fun proxies(): List<ParsedProxy> =
        (_user.value + builtInLinks).distinct().mapNotNull { link ->
            runCatching { ProxyParser.parseLink(link) }.getOrNull()?.takeIf { acceptable(it) }
        }.take(MAX_LINKS)

    fun isConfigured(): Boolean = _user.value.isNotEmpty() || builtInLinks.isNotEmpty()

    /** Validates and stores the links (encrypted). Returns an error message, or null on success. */
    fun setUser(text: String): String? {
        val links = splitLinks(text)
        if (links.isEmpty()) return "Paste at least one vless:// trojan:// hy2:// tuic:// ss:// or wg:// link"
        for (l in links.take(MAX_LINKS)) {
            val p = runCatching { ProxyParser.parseLink(l) }.getOrElse { return "Invalid link: ${it.message}" }
            if (!acceptable(p)) return "Rejected: the link must use encryption and must not disable certificate checks"
        }
        val stored = links.take(MAX_LINKS)
        val blob = runCatching { encrypt(stored.joinToString("\n").toByteArray(Charsets.UTF_8)) }.getOrNull()
            ?: return "Secure storage is not available on this phone, nothing was saved"
        prefs.edit().putString(KEY_BLOB, blob).apply()
        _user.value = stored
        return null
    }

    fun clearUser() {
        prefs.edit().remove(KEY_BLOB).apply()
        _user.value = emptyList()
    }

    private fun splitLinks(text: String): List<String> =
        text.split(Regex("\\s+")).map { it.trim() }.filter { it.isNotEmpty() && ProxyParser.looksLikeLink(it) }.distinct()

    private fun acceptable(p: ParsedProxy): Boolean {
        if (p.server.isEmpty() || p.port !in 1..65535) return false
        if (p.insecureCert) return false
        return p.isEndpoint || p.security != "none" || p.protocol == "shadowsocks"
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
