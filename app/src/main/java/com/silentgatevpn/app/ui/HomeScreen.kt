package com.silentgatevpn.app.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import android.content.Intent
import android.provider.Settings
import androidx.compose.ui.platform.LocalContext
import com.silentgatevpn.app.R
import com.silentgatevpn.app.vpn.ConnectMode
import com.silentgatevpn.app.vpn.Engine
import com.silentgatevpn.app.vpn.ShadowsocksServer
import com.silentgatevpn.app.vpn.Geo
import com.silentgatevpn.app.vpn.Net
import com.silentgatevpn.app.vpn.NodePool
import com.silentgatevpn.app.vpn.Protection
import com.silentgatevpn.app.vpn.SettingsStore
import com.silentgatevpn.app.vpn.Status
import com.silentgatevpn.app.vpn.VpnState
import com.silentgatevpn.app.vpn.formatBytes

/**
 * SuperVPN-style Home: one power button, one location card, one speed card. No modes, no node names, no diagnostics.
 * (Connection mode, nodes and tools live in Settings > Advanced.)
 */
@Composable
fun HomeScreen(onConnect: () -> Unit, onDisconnect: () -> Unit) {
    val status by VpnState.status.collectAsState()
    val protection by VpnState.protection.collectAsState()
    val backup by VpnState.backup.collectAsState()
    val connectedCc by VpnState.nodeCc.collectAsState()
    val rx by VpnState.rxSpeed.collectAsState()
    val tx by VpnState.txSpeed.collectAsState()
    val sRx by VpnState.sessionRx.collectAsState()
    val sTx by VpnState.sessionTx.collectAsState()
    val settings by SettingsStore.flow.collectAsState()
    val poolBusy by NodePool.refreshing.collectAsState()
    val poolNodes by NodePool.nodes.collectAsState()
    val alwaysOnHint by VpnState.alwaysOnHint.collectAsState()
    val context = LocalContext.current
    var showPicker by remember { mutableStateOf(false) }
    val ssSummary by ShadowsocksServer.summary.collectAsState()
    var ssHint by remember { mutableStateOf(false) }
    val ssEngine = settings.engine == Engine.SHADOWSOCKS

    val connected = status == Status.CONNECTED
    val idle = status == Status.DISCONNECTED || status == Status.ERROR
    val warn = connected && (protection != Protection.FULL || backup)

    // A saved country that must not be used (home country, or a restricted one while "avoid restricted" is on)
    // falls back to Auto. Any other country, including ones outside the safe list, stays selectable.
    LaunchedEffect(settings.preferredCountry, settings.avoidRestricted, settings.allowHomeExit) {
        val pc = settings.preferredCountry
        if (pc != "AUTO" && pc != Geo.OTHER &&
            Geo.excludedReason(pc, "", Net.home.cc, settings.avoidRestricted, settings.allowHomeExit) != null
        ) SettingsStore.update { it.copy(preferredCountry = "AUTO") }
    }

    // Opening the picker with no servers loaded yet starts a download in the background.
    LaunchedEffect(showPicker) {
        if (showPicker && settings.communityNodes && NodePool.nodes.value.isEmpty() && !NodePool.refreshing.value) {
            runCatching { NodePool.refresh() }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // The real brand mark (green Wi-Fi shield), cropped to its artwork so it is not shrunk by the adaptive-icon padding.
            Image(painterResource(R.drawable.ic_brand_logo), contentDescription = "SilentGate VPN", modifier = Modifier.size(34.dp))
            Spacer(Modifier.size(10.dp))
            Text("SilentGate VPN", style = MaterialTheme.typography.titleLarge)
        }

        Spacer(Modifier.height(16.dp))
        EngineSelector(settings.engine, enabled = idle) { e ->
            ssHint = false
            SettingsStore.update { it.copy(engine = e) }
        }

        Spacer(Modifier.height(20.dp))
        ConnectButton(status = status, warn = warn, onClick = {
            when (status) {
                Status.DISCONNECTED, Status.ERROR ->
                    if (ssEngine && ssSummary == null) {
                        ssHint = true
                    } else {
                        ssHint = false
                        onConnect()
                    }
                Status.CONNECTED, Status.CONNECTING -> onDisconnect()
                Status.DISCONNECTING -> Unit
            }
        })
        Spacer(Modifier.height(20.dp))

        Text(
            when (status) {
                Status.DISCONNECTED -> "Not Connected"
                Status.CONNECTING -> "Connecting…"
                Status.CONNECTED -> "Connected"
                Status.DISCONNECTING -> "Disconnecting…"
                Status.ERROR -> "Couldn’t connect"
            },
            style = MaterialTheme.typography.headlineMedium,
            color = when {
                status == Status.ERROR -> SgDanger
                connected && protection == Protection.NONE -> SgDanger
                connected && warn -> SgOrange
                connected -> SgGreen
                else -> MaterialTheme.colorScheme.onSurface
            }
        )
        Text(
            when (status) {
                Status.DISCONNECTED -> if (ssEngine) "Tap the button to connect to your server" else "Tap the button to connect"
                Status.CONNECTING -> if (ssEngine) "Connecting to your Shadowsocks server…" else "Finding the fastest server…"
                Status.CONNECTED -> "Your connection is private"
                Status.DISCONNECTING -> ""
                Status.ERROR -> "Check your internet and try again"
            },
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp)
        )

        if (status == Status.ERROR) {
            val log by VpnState.log.collectAsState()
            val clipboard = LocalClipboardManager.current
            TextButton(onClick = { clipboard.setText(AnnotatedString(log.takeLast(40).joinToString("\n"))) }) {
                Text("Copy details", color = SgGreen)
            }
        }

        // Honest one-line notices: only when something is not the normal, fully private case.
        val notice: Pair<String, String>? = when {
            connected && protection == Protection.NONE -> "Not protected" to "Your IP address did not change. Disconnect and try again."
            connected && backup -> "Backup connection" to "No server was available, so a backup route is used. Your IP is hidden, but sites blocked by your ISP need a remote server. If a site shows a block page, try again once servers load, and open it in Incognito (Chrome may have saved the old block page)."
            connected && protection == Protection.DPI_ONLY -> "Unblock-only mode" to "Your IP address is not hidden in this mode. You can change it in Settings › Advanced."
            connected && protection == Protection.GEO_LOCAL -> "Nearby location" to "Your IP is hidden, but this location is close to yours, so sites may show local content."
            idle && alwaysOnHint -> "Always-on VPN is on" to "Android may keep the VPN icon or reconnect by itself. Turn it off in Android Settings › Network › VPN."
            idle && !ssEngine && settings.mode != ConnectMode.AUTO -> "Advanced mode on" to "${settings.mode.label} is selected in Settings › Advanced. Switch back to Smart Auto for the normal 1-tap experience."
            else -> null
        }
        if (notice != null) {
            Spacer(Modifier.height(16.dp))
            val danger = connected && protection == Protection.NONE
            SgCardBox(
                container = if (danger) SgDangerBg else SgWarnBg,
                border = if (danger) SgDanger else SgOrange
            ) {
                Text(notice.first, style = MaterialTheme.typography.titleMedium, color = if (danger) Color(0xFFFFB4AB) else SgOrange)
                Text(
                    notice.second, style = MaterialTheme.typography.bodySmall,
                    color = if (danger) Color(0xFFFFB4AB) else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        if (idle && alwaysOnHint) {
            TextButton(onClick = {
                runCatching { context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }) { Text("Open VPN settings", color = SgGreen) }
        }

        Spacer(Modifier.height(24.dp))
        if (ssEngine) {
            ShadowsocksCard(ssSummary, idle, showHint = ssHint, onSaved = { ssHint = false })
            if (connected && connectedCc.isNotEmpty()) {
                Text(
                    "Connected: ${Geo.flag(connectedCc)} ${Geo.name(connectedCc)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        } else SgCardBox(Modifier.clickable(enabled = idle, role = Role.Button, onClickLabel = "Choose location") { showPicker = true }) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Location", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        if (settings.preferredCountry == "AUTO") "⚡ Fastest Location (Auto)"
                        else "${Geo.flag(settings.preferredCountry)} ${Geo.name(settings.preferredCountry)}",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                if (idle) Text("Change", color = SgGreen, style = MaterialTheme.typography.labelLarge)
            }
            val sub = when {
                connected && connectedCc.isNotEmpty() -> "Connected: ${Geo.flag(connectedCc)} ${Geo.name(connectedCc)}"
                poolBusy -> "Updating servers…"
                else -> ""
            }
            if (sub.isNotEmpty()) {
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        }

        if (connected) {
            Spacer(Modifier.height(16.dp))
            SgCardBox {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Stat(Icons.Rounded.ArrowDownward, "Download", "${formatBytes(rx)}/s", formatBytes(sRx))
                    Stat(Icons.Rounded.ArrowUpward, "Upload", "${formatBytes(tx)}/s", formatBytes(sTx))
                }
            }
        }
        MadeByAnas()
    }

    if (showPicker) {
        val data = remember(poolNodes, settings.avoidRestricted, settings.allowHomeExit) { NodePool.pickerData(settings, Net.home) }
        fun choose(cc: String) { SettingsStore.update { it.copy(preferredCountry = cc) }; showPicker = false }
        AlertDialog(
            onDismissRequest = { showPicker = false },
            containerColor = Color(0xFF121212),
            title = { Text("Choose location") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    PickerRow("⚡ Fastest Location (Auto)", "Picks the best available server for you", settings.preferredCountry == "AUTO") { choose("AUTO") }
                    if (data.isEmpty) {
                        Text(
                            if (poolBusy) "Servers are being downloaded. Locations appear here as soon as the first list arrives."
                            else "No servers loaded yet. Updating now; if nothing appears, connect once with Auto.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 10.dp)
                        )
                    }
                    if (data.safe.isNotEmpty()) PickerSection("Safe Locations")
                    data.safe.forEach { (cc, n) -> CountryRow(cc, n, settings.preferredCountry == cc, null) { choose(cc) } }
                    if (data.other.isNotEmpty()) PickerSection("Other Locations")
                    data.other.forEach { (cc, n) ->
                        CountryRow(cc, n, settings.preferredCountry == cc, if (cc == "US") "Some states require age check" else null) { choose(cc) }
                    }
                    if (data.ageCheck.isNotEmpty()) PickerSection("May require age check")
                    data.ageCheck.forEach { (cc, n) -> CountryRow(cc, n, settings.preferredCountry == cc, "May require age check") { choose(cc) } }
                    if (data.unknown > 0) {
                        PickerSection("Global / Other")
                        PickerRow(
                            "\uD83C\uDF10 Global / Other", "${data.unknown} server${if (data.unknown == 1) "" else "s"}, location checked after connecting",
                            settings.preferredCountry == Geo.OTHER
                        ) { choose(Geo.OTHER) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showPicker = false }) { Text("Close", color = SgGreen) } }
        )
    }
}

@Composable
private fun EngineSelector(selected: Engine, enabled: Boolean, onSelect: (Engine) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Engine.entries.forEach { e ->
            val sel = e == selected
            val shape = RoundedCornerShape(16.dp)
            Column(
                Modifier.weight(1f)
                    .alpha(if (enabled) 1f else 0.55f)
                    .clip(shape)
                    .background(if (sel) Color(0xFF0F2A1B) else SgCard)
                    .border(1.5.dp, if (sel) SgGreen else Color(0xFF3A3A3A), shape)
                    .clickable(enabled = enabled, role = Role.RadioButton) { onSelect(e) }
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        e.label, style = MaterialTheme.typography.titleMedium,
                        color = if (sel) SgGreen else Color.White, modifier = Modifier.weight(1f)
                    )
                    if (sel) Text("✓", color = SgGreen, style = MaterialTheme.typography.titleMedium)
                }
                Text(e.hint, style = MaterialTheme.typography.bodySmall, color = SgTextDim, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

@Composable
private fun ShadowsocksCard(summary: String?, idle: Boolean, showHint: Boolean, onSaved: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var editing by remember(summary) { mutableStateOf(summary == null) }
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    SgCardBox(border = if (showHint && summary == null) SgOrange else MaterialTheme.colorScheme.outlineVariant) {
        Text("Shadowsocks server", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (summary != null && !editing) {
            Text(summary, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 2.dp))
            Row {
                TextButton(enabled = idle, onClick = { editing = true }) { Text("Change", color = if (idle) SgGreen else SgTextDim) }
                TextButton(enabled = idle, onClick = { ShadowsocksServer.clear() }) { Text("Remove", color = if (idle) SgOrange else SgTextDim) }
            }
        } else {
            Text(
                if (showHint && summary == null) "Add your ss:// link first, then tap the power button."
                else "Paste your ss:// link. It is saved encrypted on this phone.",
                style = MaterialTheme.typography.bodySmall,
                color = if (showHint && summary == null) SgOrange else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
            OutlinedTextField(
                value = text, onValueChange = { text = it; error = null }, minLines = 2, maxLines = 4,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                placeholder = { Text("ss://…", color = Color(0xFF8A8A8A)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                isError = error != null,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = SgGreen, unfocusedBorderColor = Color(0xFF6A6A6A),
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = SgGreen
                )
            )
            error?.let { Text(it, color = Color(0xFFFFB4AB), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { clipboard.getText()?.text?.let { text = it; error = null } }) {
                    Icon(Icons.Rounded.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp), tint = SgGreen)
                    Spacer(Modifier.size(6.dp))
                    Text("Paste", color = SgGreen)
                }
                TextButton(enabled = idle && text.isNotBlank(), onClick = {
                    val e = ShadowsocksServer.set(text)
                    if (e == null) { text = ""; error = null; editing = false; onSaved() } else error = e
                }) { Text("Save", color = if (idle && text.isNotBlank()) SgGreen else SgTextDim) }
                if (summary != null) TextButton(onClick = { editing = false; text = ""; error = null }) { Text("Cancel", color = SgTextDim) }
            }
        }
    }
}

@Composable
private fun PickerSection(title: String) {
    Text(
        title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 14.dp, bottom = 2.dp)
    )
}

@Composable
private fun CountryRow(cc: String, n: Int, selected: Boolean, tag: String?, onClick: () -> Unit) {
    val count = "$n server${if (n == 1) "" else "s"}"
    PickerRow("${Geo.flag(cc)} ${Geo.name(cc)}", if (tag != null) "$count · $tag" else count, selected, onClick)
}

@Composable
private fun PickerRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.RadioButton, onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (selected) SgGreen else Color.White)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (selected) Text("✓", color = SgGreen, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun ConnectButton(status: Status, warn: Boolean, onClick: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val pulse by transition.animateFloat(
        initialValue = 0.25f, targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse"
    )
    val connected = status == Status.CONNECTED
    val connecting = status == Status.CONNECTING
    val accent = if (warn) SgOrange else SgGreen
    // Idle: light-grey icon on near-black with a visible ring (contrast above 3:1), never a dark-on-dark icon.
    val ring = when (status) {
        Status.CONNECTED -> accent
        Status.ERROR -> SgDanger
        Status.CONNECTING -> SgGreenSoft
        else -> Color(0xFF6E6E6E)
    }
    val iconTint = when {
        connected -> Color.Black
        status == Status.ERROR -> SgDanger
        connecting -> SgGreenSoft
        else -> Color(0xFFE0E0E0)
    }
    val label = if (status == Status.DISCONNECTED || status == Status.ERROR) "Connect" else "Disconnect"
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(220.dp)) {
        Box(
            Modifier.size(220.dp).alpha(if (connecting) pulse else if (connected) 0.25f else 0f)
                .clip(CircleShape).background(accent)
        )
        Box(
            Modifier.size(176.dp).clip(CircleShape)
                .background(if (connected) accent else Color(0xFF0E0E0E))
                .border(3.dp, ring, CircleShape)
                .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.PowerSettingsNew, contentDescription = label, tint = iconTint, modifier = Modifier.size(76.dp))
        }
    }
}

@Composable
private fun Stat(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, speed: String, total: String) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = SgGreen, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(speed, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("Session $total", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 16.dp))
    }
}
