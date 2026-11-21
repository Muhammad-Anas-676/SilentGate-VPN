package com.silentgatevpn.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.silentgatevpn.app.vpn.ConnectMode
import com.silentgatevpn.app.vpn.DnsProvider
import com.silentgatevpn.app.vpn.Ipv6Mode
import com.silentgatevpn.app.vpn.PrimaryServer
import com.silentgatevpn.app.vpn.ProxyParser
import com.silentgatevpn.app.vpn.QuicMode
import com.silentgatevpn.app.vpn.SettingsStore
import com.silentgatevpn.app.vpn.VpnState
import com.silentgatevpn.app.vpn.WarpTunnel

private data class FragPreset(val label: String, val size: String, val sleep: String)

/** Header used by every sub-screen: back arrow, title and an optional one-line explanation. */
@Composable
fun ScreenHeader(title: String, onBack: () -> Unit, subtitle: String? = null) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onSurface)
            }
            Text(title, style = MaterialTheme.typography.headlineMedium)
        }
        if (subtitle != null) {
            Text(
                subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, top = 2.dp)
            )
        }
    }
}

/** Everything technical, tucked away from the 1-tap experience. */
@Composable
fun AdvancedScreen(
    onBack: () -> Unit,
    onOpenProfiles: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenLog: () -> Unit
) {
    val s by SettingsStore.flow.collectAsState()
    val userLinks by PrimaryServer.userLinks.collectAsState()
    var showPrimary by remember { mutableStateOf(false) }
    val presets = listOf(
        FragPreset("Light", "20-60", "1-4"),
        FragPreset("Balanced", "10-30", "2-8"),
        FragPreset("Strong", "1-10", "5-15")
    )
    val currentPreset = presets.firstOrNull { it.size == s.fragSize && it.sleep == s.fragSleep } ?: presets[1]
    val dim = MaterialTheme.colorScheme.onSurfaceVariant

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 12.dp)) {
        ScreenHeader("Advanced", onBack, "For power users. Changes apply the next time you connect.")

        SectionTitle("Connection mode")
        SgCardBox {
            Text("Smart Auto is the normal 1-tap mode. Pick another only if you know why.", style = MaterialTheme.typography.bodySmall, color = dim,
                modifier = Modifier.padding(bottom = 10.dp))
            ChoiceChips(ConnectMode.entries, s.mode, { it.label }) { m -> SettingsStore.update { it.copy(mode = m) } }
            Text(s.mode.hint, style = MaterialTheme.typography.bodySmall, color = dim, modifier = Modifier.padding(top = 8.dp))
            if (s.mode == ConnectMode.DPI_ONLY) {
                Text("This mode does not hide your IP address or country.", style = MaterialTheme.typography.bodySmall,
                    color = SgOrange, modifier = Modifier.padding(top = 6.dp))
            }
        }

        SectionTitle("Private server")
        SgCardBox {
            Text(
                "Your own server (for example VLESS Reality on a VPS). Smart Auto tries it before any community node. Links are stored encrypted on this phone.",
                style = MaterialTheme.typography.bodySmall, color = dim, modifier = Modifier.padding(bottom = 8.dp)
            )
            ToggleRow("Use it first", "Only in Smart Auto with the location on Auto.", s.primaryFirst) { v -> SettingsStore.update { it.copy(primaryFirst = v) } }
            Spacer(Modifier.height(4.dp))
            InfoLine("Built into this app", if (PrimaryServer.builtInLinks.isEmpty()) "none" else "${PrimaryServer.builtInLinks.size} server(s)")
            if (userLinks.isEmpty()) {
                InfoLine("Added by you", "none")
            } else {
                userLinks.forEach { link ->
                    // Only protocol and host are shown, never the credentials inside the link.
                    val label = runCatching { ProxyParser.parseLink(link).let { "${it.protocol.uppercase()} · ${it.server}" } }.getOrDefault("server")
                    InfoLine("Added by you", label)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showPrimary = true }) { Text(if (userLinks.isEmpty()) "Add server" else "Replace") }
                if (userLinks.isNotEmpty()) OutlinedButton(onClick = { PrimaryServer.clearUser() }) { Text("Remove", color = SgDanger) }
            }
        }

        SectionTitle("Profiles and diagnostics")
        SgCardBox {
            SettingsRow("Custom profiles & subscriptions", "Your own links, community node lists, Cloudflare Worker guide.", onOpenProfiles)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingsRow("Network diagnostics & site test", "Leak check, site test, Cloudflare scanner.", onOpenDiagnostics)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingsRow("Event log", "What the app did during the last connection attempts.", onOpenLog)
        }

        SectionTitle("DNS provider")
        SgCardBox {
            Text("All DNS goes inside the tunnel over encrypted DoH. Family and SafeSearch resolvers are never used.",
                style = MaterialTheme.typography.bodySmall, color = dim, modifier = Modifier.padding(bottom = 10.dp))
            ChoiceChips(DnsProvider.entries, s.dns, { it.label }) { d -> SettingsStore.update { it.copy(dns = d) } }
        }

        SectionTitle("QUIC & MTU")
        SgCardBox {
            Text(
                "Block QUIC is the default: HTTP/3 (UDP 443) is rejected inside the tunnel so browsers use normal TCP, which loads thumbnails reliably. " +
                    "Smart blocks it only for nodes that cannot carry UDP. Allow QUIC lets HTTP/3 through.",
                style = MaterialTheme.typography.bodySmall, color = dim, modifier = Modifier.padding(bottom = 10.dp)
            )
            ChoiceChips(listOf(QuicMode.BLOCK, QuicMode.AUTO, QuicMode.ALLOW), s.quic, { if (it == QuicMode.BLOCK) "Block (recommended)" else it.label }) { m -> SettingsStore.update { it.copy(quic = m) } }
            Spacer(Modifier.height(12.dp))
            Text("Tunnel MTU", style = MaterialTheme.typography.titleMedium)
            Text("Auto picks 1280 for WARP/WireGuard, about 1350 for QUIC protocols and up to 1450 for TCP protocols, and lowers it to fit your carrier.",
                style = MaterialTheme.typography.bodySmall, color = dim, modifier = Modifier.padding(top = 2.dp, bottom = 10.dp))
            ChoiceChips(listOf(0, 1280, 1400, 1500), s.mtuOverride, { if (it == 0) "Auto" else it.toString() }) { m -> SettingsStore.update { it.copy(mtuOverride = m) } }
        }

        SectionTitle("TLS fragmentation")
        SgCardBox {
            Text(
                "Off by default. Splitting the TLS hello can get past SNI-only filters, but on image-heavy sites it makes many parallel connections slow or reset. " +
                    "Turn it on only if a site is blocked by your network. The DPI-only mode always uses it.",
                style = MaterialTheme.typography.bodySmall, color = dim, modifier = Modifier.padding(bottom = 10.dp)
            )
            ToggleRow("Fragment node and profile TLS", "Also fragment the TLS hello of TLS-based nodes and your own profiles.", s.fragmentStealth) { v -> SettingsStore.update { it.copy(fragmentStealth = v) } }
            Spacer(Modifier.height(8.dp))
            ChoiceChips(presets, currentPreset, { it.label }) { p -> SettingsStore.update { it.copy(fragSize = p.size, fragSleep = p.sleep) } }
        }

        SectionTitle("WARP settings")
        SgCardBox {
            Text("Tunnel type", style = MaterialTheme.typography.titleMedium)
            Text("Auto races WireGuard, AmneziaWG and MASQUE and keeps the fastest one that answers.",
                style = MaterialTheme.typography.bodySmall, color = dim, modifier = Modifier.padding(top = 2.dp, bottom = 10.dp))
            ChoiceChips(WarpTunnel.entries, s.warpTunnel, { it.label }) { t -> SettingsStore.update { it.copy(warpTunnel = t) } }
            Spacer(Modifier.height(12.dp))
            ToggleRow("Obfuscation noise", "Sends fake QUIC-looking packets before each WireGuard handshake.", s.noise) { v -> SettingsStore.update { it.copy(noise = v) } }
            ToggleRow("Endpoint scanner", "Finds a Cloudflare address and port that work on your current network.", s.scanner) { v -> SettingsStore.update { it.copy(scanner = v) } }
            ToggleRow("Search for noise preset", "If handshakes fail, tries several junk-packet presets and remembers the one that works.", s.searchNoise) { v -> SettingsStore.update { it.copy(searchNoise = v) } }
        }

        SectionTitle("Safe exit and IPv6")
        SgCardBox {
            ToggleRow("Avoid age-verification regions", "Skips exits in countries and US states that force age checks (UK, France, Germany, Texas and others).", s.avoidRestricted) { v -> SettingsStore.update { it.copy(avoidRestricted = v) } }
            ToggleRow("Allow exit in my own country", "Off by default: an exit in your home country defeats the point of a VPN for local-content and geo flags.", s.allowHomeExit) { v -> SettingsStore.update { it.copy(allowHomeExit = v) } }
            Spacer(Modifier.height(8.dp))
            Text("IPv6 always enters the tunnel so your real IPv6 address cannot leak. Block rejects it; Tunnel forwards it.",
                style = MaterialTheme.typography.bodySmall, color = dim, modifier = Modifier.padding(bottom = 10.dp))
            ChoiceChips(Ipv6Mode.entries, s.ipv6, { it.label }) { m -> SettingsStore.update { it.copy(ipv6 = m) } }
        }
        Spacer(Modifier.height(32.dp))
    }

    if (showPrimary) PrimaryServerDialog(onDismiss = { showPrimary = false })
}

@Composable
private fun PrimaryServerDialog(onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF121212),
        title = { Text("Private server") },
        text = {
            Column {
                Text(
                    "Paste one to three links of your own server: vless:// (Reality), trojan://, hy2://, tuic://, ss:// or wg://, separated by spaces or new lines. They are stored encrypted.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = text, onValueChange = { text = it; error = null }, minLines = 4, maxLines = 8,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    placeholder = { Text("vless://…", color = Color(0xFF8A8A8A)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    isError = error != null,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SgGreen, unfocusedBorderColor = Color(0xFF6A6A6A),
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = SgGreen
                    )
                )
                error?.let { Text(it, color = Color(0xFFFFB4AB), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
                TextButton(onClick = { clipboard.getText()?.text?.let { text = it } }) {
                    Icon(Icons.Rounded.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp), tint = SgGreen)
                    Spacer(Modifier.size(6.dp))
                    Text("Paste from clipboard", color = SgGreen)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val e = PrimaryServer.setUser(text)
                if (e == null) onDismiss() else error = e
            }) { Text("Save", color = SgGreen) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Color.White) } }
    )
}

/** Last connection events, newest at the bottom. Useful when a connection fails and you need to see why. */
@Composable
fun EventLogScreen(onBack: () -> Unit) {
    val log by VpnState.log.collectAsState()
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 12.dp)) {
        ScreenHeader("Event log", onBack, "Kept in memory only and cleared when the app closes.")
        Spacer(Modifier.height(12.dp))
        SgCardBox {
            if (log.isEmpty()) Text("Nothing yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            log.takeLast(60).forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 2.dp))
            }
        }
        if (log.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(log.joinToString("\n"))) }) { Text("Copy log") }
        }
        Spacer(Modifier.height(32.dp))
    }
}
