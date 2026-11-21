package com.silentgatevpn.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.silentgatevpn.app.BuildConfig
import com.silentgatevpn.app.legal.LegalDoc
import com.silentgatevpn.app.legal.LegalDocs
import com.silentgatevpn.app.vpn.VpnState

/** Opens a system settings page; falls back to the main Settings app when the phone maker removed that page. */
internal fun Context.openSystemSettings(action: String) {
    val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        runCatching { startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

/** One tappable row: title, one-line explanation and a chevron. */
@Composable
fun SettingsRow(title: String, subtitle: String, onClick: () -> Unit, trailing: @Composable () -> Unit = {
    Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
}) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
    }
}

/** Plain Settings: four everyday items plus one door to everything technical. */
@Composable
fun SettingsScreen(onOpenDoc: (LegalDoc) -> Unit, onOpenAdvanced: () -> Unit) {
    val ctx = LocalContext.current
    val alwaysOn by VpnState.alwaysOn.collectAsState()
    val lockdown by VpnState.lockdown.collectAsState()
    var showLegal by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 12.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(bottom = 12.dp))

        SgCardBox {
            SettingsRow(
                "Kill Switch",
                if (alwaysOn && lockdown) "Active: no internet is allowed if the VPN drops."
                else "Block internet if the VPN drops. Opens the Android VPN settings.",
                onClick = { ctx.openSystemSettings(Settings.ACTION_VPN_SETTINGS) }
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingsRow(
                "Battery Optimization",
                "Keeps the VPN running in the background on Infinix, Tecno and Itel phones. Choose Unrestricted.",
                onClick = { ctx.openSystemSettings(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) }
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingsRow(
                "Legal & Privacy",
                "Terms, privacy policy and licences.",
                onClick = { showLegal = !showLegal },
                trailing = {
                    Icon(
                        if (showLegal) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = if (showLegal) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            )
            if (showLegal) {
                LegalDocs.all.forEach { doc -> DocLinkRow(doc) { onOpenDoc(doc) } }
            }
        }

        SectionTitle("About")
        SgCardBox {
            InfoLine("Version", BuildConfig.VERSION_NAME)
            InfoLine("Package", "com.silentgatevpn.app")
            InfoLine("Core", "Hiddify sing-box (libbox)")
            InfoLine("Licence", "GPL-3.0, source public")
        }

        Spacer(Modifier.height(16.dp))
        SgCardBox {
            SettingsRow(
                "Advanced Settings",
                "Connection mode, private server, profiles, DNS, QUIC, TLS fragmentation, WARP and diagnostics.",
                onClick = onOpenAdvanced
            )
        }
        MadeByAnas()
    }
}
