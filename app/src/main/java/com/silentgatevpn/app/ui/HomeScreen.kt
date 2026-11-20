package com.silentgatevpn.app.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.silentgatevpn.app.vpn.ConnectMode
import com.silentgatevpn.app.vpn.Geo
import com.silentgatevpn.app.vpn.Net
import com.silentgatevpn.app.vpn.NodePool
import com.silentgatevpn.app.vpn.Protection
import com.silentgatevpn.app.vpn.SettingsStore
import com.silentgatevpn.app.vpn.Status
import com.silentgatevpn.app.vpn.VpnState
import com.silentgatevpn.app.vpn.formatBytes

@Composable
fun HomeScreen(onConnect: () -> Unit, onDisconnect: () -> Unit) {
    val status by VpnState.status.collectAsState()
    val layer by VpnState.layer.collectAsState()
    val message by VpnState.message.collectAsState()
    val exit by VpnState.exit.collectAsState()
    val rx by VpnState.rxSpeed.collectAsState()
    val tx by VpnState.txSpeed.collectAsState()
    val sRx by VpnState.sessionRx.collectAsState()
    val sTx by VpnState.sessionTx.collectAsState()
    val settings by SettingsStore.flow.collectAsState()
    val alwaysOn by VpnState.alwaysOn.collectAsState()
    val lockdown by VpnState.lockdown.collectAsState()
    val protection by VpnState.protection.collectAsState()
    val nodeName by VpnState.node.collectAsState()
    val pool by NodePool.nodes.collectAsState()
    val poolBusy by NodePool.refreshing.collectAsState()
    var showPicker by remember { mutableStateOf(false) }

    val connected = status == Status.CONNECTED
    val busy = status == Status.CONNECTING || status == Status.DISCONNECTING

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Shield, contentDescription = null, tint = SgGreen, modifier = Modifier.size(28.dp))
            Spacer(Modifier.size(10.dp))
            Text("SilentGate VPN", style = MaterialTheme.typography.titleLarge)
        }

        Spacer(Modifier.height(28.dp))
        ConnectButton(status = status, warn = connected && protection != Protection.FULL, onClick = {
            when (status) {
                Status.DISCONNECTED, Status.ERROR -> onConnect()
                Status.CONNECTED, Status.CONNECTING -> onDisconnect()
                else -> Unit
            }
        })
        Spacer(Modifier.height(20.dp))

        val headline = when (status) {
            Status.DISCONNECTED -> "Not protected"
            Status.CONNECTING -> "Connecting…"
            Status.CONNECTED -> when (protection) {
                Protection.FULL -> "Protected"
                Protection.GEO_LOCAL -> "IP hidden"
                Protection.DPI_ONLY -> "DPI bypass only"
                Protection.NONE -> "Not protected"
            }
            Status.DISCONNECTING -> "Disconnecting…"
            Status.ERROR -> "Connection failed"
        }
        Text(
            headline, style = MaterialTheme.typography.headlineMedium,
            color = when (status) {
                Status.CONNECTED -> when (protection) {
                    Protection.FULL -> SgGreen
                    Protection.NONE -> SgDanger
                    else -> SgOrange
                }
                Status.ERROR -> SgDanger
                else -> MaterialTheme.colorScheme.onSurface
            }
        )
        val sub = when {
            message.isNotEmpty() -> message
            status == Status.DISCONNECTED -> "Tap the button to connect"
            else -> ""
        }
        if (sub.isNotEmpty()) {
            Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
        }

        if (status == Status.ERROR) {
            val reasons by VpnState.lastFailure.collectAsState()
            val log by VpnState.log.collectAsState()
            val clipboard = LocalClipboardManager.current
            Spacer(Modifier.height(14.dp))
            SgCardBox(container = SgDangerBg, border = SgDanger) {
                Text("Why it failed", style = MaterialTheme.typography.titleMedium, color = Color(0xFFFFB4AB))
                (if (reasons.isEmpty()) log.takeLast(4) else reasons).forEach {
                    Text("• $it", style = MaterialTheme.typography.bodySmall, color = Color(0xFFFFB4AB), modifier = Modifier.padding(top = 4.dp))
                }
                OutlinedButton(
                    onClick = { clipboard.setText(AnnotatedString(log.joinToString("\n"))) },
                    modifier = Modifier.padding(top = 10.dp),
                    border = BorderStroke(1.dp, SgDanger)
                ) { Text("Copy full log", color = Color(0xFFFFB4AB)) }
            }
        }

        Spacer(Modifier.height(20.dp))
        ChoiceChips(
            options = ConnectMode.entries, selected = settings.mode, label = { it.label },
            enabled = status == Status.DISCONNECTED || status == Status.ERROR
        ) { m -> SettingsStore.update { it.copy(mode = m) } }
        Text(settings.mode.hint, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))

        if (settings.mode == ConnectMode.DPI_ONLY) {
            Spacer(Modifier.height(12.dp))
            SgCardBox(container = SgWarnBg, border = SgOrange) {
                Text("This mode does not hide your IP", style = MaterialTheme.typography.titleMedium, color = SgOrange)
                Text(
                    "Websites will still see your real IP address and country. It only helps open filtered sites. Use Smart Auto for privacy.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        } else {
            Spacer(Modifier.height(12.dp))
            val idle = status == Status.DISCONNECTED || status == Status.ERROR
            SgCardBox(Modifier.clickable(enabled = idle) { showPicker = true }) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Location", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            if (settings.preferredCountry == "AUTO") "\u26A1 Fastest Safe Location (Auto)"
                            else "${Geo.flag(settings.preferredCountry)} ${Geo.name(settings.preferredCountry)}",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    Text(if (idle) "Change" else "", color = SgGreen, style = MaterialTheme.typography.labelLarge)
                }
                Text(
                    (if (poolBusy) "Updating nodes…" else "${pool.size} nodes ready") +
                        " · skips ${Geo.name(Net.home.cc)}" + if (settings.avoidRestricted) " and age-verification regions" else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        SgCardBox {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat(Icons.Rounded.ArrowDownward, "Download", "${formatBytes(rx)}/s", formatBytes(sRx))
                Stat(Icons.Rounded.ArrowUpward, "Upload", "${formatBytes(tx)}/s", formatBytes(sTx))
            }
            if (connected && layer != null) {
                Spacer(Modifier.height(12.dp))
                Text("Active: ${layer!!.title}", style = MaterialTheme.typography.bodyMedium, color = SgGreenSoft)
            }
        }

        if (connected) {
            Spacer(Modifier.height(12.dp))
            val e = exit
            SgCardBox {
                Text("Exit", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                if (e == null) {
                    Text("Checking exit location…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    InfoLine("IP", e.ip)
                    InfoLine("Location", listOf(e.city, e.region, e.country).filter { it.isNotEmpty() }.joinToString(", "))
                    if (nodeName.isNotEmpty()) InfoLine("Node", nodeName)
                    if (e.colo.isNotEmpty()) InfoLine("Cloudflare edge", e.colo)
                    if (e.pingMs >= 0) InfoLine("Latency", "${e.pingMs} ms")
                    e.warpOn?.let { InfoLine("WARP", if (it) "On" else "Off (other layer)") }
                }
            }
            if (e != null && protection == Protection.GEO_LOCAL && !e.restricted) {
                Spacer(Modifier.height(12.dp))
                SgCardBox(container = SgWarnBg, border = SgOrange) {
                    Text("Exit looks like your own country", style = MaterialTheme.typography.titleMedium, color = SgOrange)
                    Text(
                        "Your IP is hidden, but websites may still show local content because this exit (${Geo.name(e.countryCode)}) is near you. Smart Auto with community nodes picks a different country.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            if (e?.restricted == true) {
                Spacer(Modifier.height(12.dp))
                SgCardBox(container = SgDangerBg, border = SgDanger) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Warning, contentDescription = null, tint = SgDanger)
                        Spacer(Modifier.size(10.dp))
                        Text("Exit is in a region with age-verification rules", style = MaterialTheme.typography.titleMedium, color = Color(0xFFFFB4AB))
                    }
                    Text(
                        "Your exit location (${e.region.ifEmpty { e.country }}) is on the restricted list. Reconnect, or switch layer in Profiles/Settings.",
                        style = MaterialTheme.typography.bodySmall, color = Color(0xFFFFB4AB), modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            SgCardBox(container = if (alwaysOn && lockdown) SgCard else SgWarnBg, border = if (alwaysOn && lockdown) MaterialTheme.colorScheme.outlineVariant else SgOrange) {
                Text("Kill switch", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (alwaysOn && lockdown) "Always-on VPN and Block connections without VPN are active."
                    else "For a real kill switch, turn on Always-on VPN and Block connections without VPN in Android settings (Settings tab has a shortcut).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        SgCardBox {
            Text("Tip", style = MaterialTheme.typography.titleMedium)
            Text(
                "If a site already showed an age-verification banner on another connection, clear its cookies or use Incognito after connecting.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        MadeByAnas()
    }

    if (showPicker) {
        val countries = NodePool.availableCountries(settings, Net.home)
        AlertDialog(
            onDismissRequest = { showPicker = false },
            containerColor = Color(0xFF121212),
            title = { Text("Choose location") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    PickerRow("\u26A1 Fastest Safe Location (Auto)", "Best verified node, never your home country", settings.preferredCountry == "AUTO") {
                        SettingsStore.update { it.copy(preferredCountry = "AUTO") }; showPicker = false
                    }
                    if (countries.isEmpty()) {
                        Text("Countries appear after the first node update. Connect once in Auto mode.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 10.dp))
                    }
                    countries.forEach { (cc, n) ->
                        PickerRow("${Geo.flag(cc)} ${Geo.name(cc)}", "$n node${if (n == 1) "" else "s"}", settings.preferredCountry == cc) {
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
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (selected) SgGreen else Color.White)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
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
    val ringColor = when (status) {
        Status.CONNECTED -> if (warn) SgOrange else SgGreen
        Status.ERROR -> SgDanger
        Status.CONNECTING -> SgGreenSoft
        else -> Color(0xFF3A3A3A)
    }
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(210.dp)) {
        Box(
            Modifier.size(210.dp).alpha(if (connecting) pulse else if (connected) 0.25f else 0f)
                .clip(CircleShape).background(if (warn) SgOrange else SgGreen)
        )
        Box(
            Modifier.size(172.dp).clip(CircleShape)
                .background(if (connected) (if (warn) SgOrange else SgGreen) else Color(0xFF0E0E0E))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.PowerSettingsNew, contentDescription = "Connect",
                tint = if (connected) Color.Black else ringColor, modifier = Modifier.size(76.dp)
            )
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
