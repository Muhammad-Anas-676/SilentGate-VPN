package com.silentgatevpn.app.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.silentgatevpn.app.vpn.ConnectMode
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
    var showPicker by remember { mutableStateOf(false) }

    val connected = status == Status.CONNECTED
    val idle = status == Status.DISCONNECTED || status == Status.ERROR
    val warn = connected && (protection != Protection.FULL || backup)

    // A saved country that is no longer offered (for example a restricted one from an older version) falls back to Auto.
    LaunchedEffect(settings.preferredCountry) {
        val pc = settings.preferredCountry
        if (pc != "AUTO" && pc !in Geo.PICKER_COUNTRIES) SettingsStore.update { it.copy(preferredCountry = "AUTO") }
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Shield, contentDescription = null, tint = SgGreen, modifier = Modifier.size(28.dp))
            Spacer(Modifier.size(10.dp))
            Text("SilentGate VPN", style = MaterialTheme.typography.titleLarge)
        }

        Spacer(Modifier.height(32.dp))
        ConnectButton(status = status, warn = warn, onClick = {
            when (status) {
                Status.DISCONNECTED, Status.ERROR -> onConnect()
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
                Status.DISCONNECTED -> "Tap the button to connect"
                Status.CONNECTING -> "Finding the fastest server…"
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
            connected && backup -> "Backup connection" to "No server was available, so a backup route is used. Your IP is hidden, but sites may still treat you as being in your own country."
            connected && protection == Protection.DPI_ONLY -> "Unblock-only mode" to "Your IP address is not hidden in this mode. You can change it in Settings › Advanced."
            connected && protection == Protection.GEO_LOCAL -> "Nearby location" to "Your IP is hidden, but this location is close to yours, so sites may show local content."
            idle && settings.mode != ConnectMode.AUTO -> "Advanced mode on" to "${settings.mode.label} is selected in Settings › Advanced. Switch back to Smart Auto for the normal 1-tap experience."
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

        Spacer(Modifier.height(24.dp))
        SgCardBox(Modifier.clickable(enabled = idle, role = Role.Button, onClickLabel = "Choose location") { showPicker = true }) {
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
        val countries = NodePool.availableCountries(settings, Net.home).filterKeys { it in Geo.PICKER_COUNTRIES }
        AlertDialog(
            onDismissRequest = { showPicker = false },
            containerColor = Color(0xFF121212),
            title = { Text("Choose location") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    PickerRow("⚡ Fastest Location (Auto)", "Picks the best available server for you", settings.preferredCountry == "AUTO") {
                        SettingsStore.update { it.copy(preferredCountry = "AUTO") }; showPicker = false
                    }
                    if (countries.isEmpty()) {
                        Text(
                            if (poolBusy) "Servers are being downloaded. Countries appear here in a few seconds."
                            else "Countries appear after the first server update. Connect once with Auto.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 10.dp)
                        )
                    }
                    countries.forEach { (cc, n) ->
                        PickerRow("${Geo.flag(cc)} ${Geo.name(cc)}", "$n server${if (n == 1) "" else "s"}", settings.preferredCountry == cc) {
                            SettingsStore.update { it.copy(preferredCountry = cc) }; showPicker = false
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showPicker = false }) { Text("Close", color = SgGreen) } }
        )
    }
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
