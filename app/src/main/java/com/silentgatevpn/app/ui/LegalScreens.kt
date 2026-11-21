package com.silentgatevpn.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import com.silentgatevpn.app.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.silentgatevpn.app.legal.LegalDoc
import com.silentgatevpn.app.legal.LegalDocs
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import com.silentgatevpn.app.vpn.NodePool

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LegalViewer(doc: LegalDoc, onBack: () -> Unit) {
    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = { Text(doc.title) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black, titleContentColor = Color.White, navigationIconContentColor = Color.White
                )
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
            doc.body.trim().lines().forEach { raw ->
                val line = raw.trimEnd()
                when {
                    line.isBlank() -> Spacer(Modifier.height(6.dp))
                    line.startsWith("# ") -> Text(
                        line.removePrefix("# "), style = MaterialTheme.typography.titleMedium, color = SgGreen,
                        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp)
                    )
                    line.startsWith("- ") -> Row(Modifier.padding(start = 6.dp, top = 2.dp)) {
                        Text("•  ", color = SgGreen)
                        Text(line.removePrefix("- "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
                    }
                    else -> Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(top = 2.dp))
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
fun DocLinkRow(doc: LegalDoc, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(doc.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
            Text(doc.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One-screen welcome: a single checkbox and a Continue button, so a first-time user reaches Connect in seconds. */
@Composable
fun ConsentScreen(onOpenDoc: (LegalDoc) -> Unit, onAccept: () -> Unit) {
    var agree by remember { mutableStateOf(false) }
    var showAll by remember { mutableStateOf(false) }
    val onBg = MaterialTheme.colorScheme.onBackground
    val dim = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Spacer(Modifier.height(24.dp))
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = "SilentGate VPN",
            modifier = Modifier.size(104.dp)
        )
        Spacer(Modifier.height(12.dp))
        Text("SilentGate VPN", style = MaterialTheme.typography.headlineMedium, color = onBg, textAlign = TextAlign.Center)
        Text(
            "Private, one-tap VPN. No ads, no accounts, no analytics.",
            style = MaterialTheme.typography.bodyMedium, color = dim, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 24.dp)
        )

        SgCardBox {
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { agree = !agree },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = agree, onCheckedChange = { agree = it },
                    colors = CheckboxDefaults.colors(checkedColor = SgGreen, checkmarkColor = Color.Black, uncheckedColor = Color(0xFFB0B0B0))
                )
                Text("I am 18+ and agree to the Terms & Privacy Policy", style = MaterialTheme.typography.bodyMedium, color = onBg)
            }
            Row(Modifier.padding(start = 12.dp, top = 4.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                LegalLink("Terms of Service") { onOpenDoc(LegalDocs.tos) }
                LegalLink("Privacy Policy") { onOpenDoc(LegalDocs.privacy) }
            }
            Row(Modifier.padding(start = 12.dp)) { LegalLink("All legal documents") { showAll = true } }
            Text(
                "Continuing also accepts the Acceptable Use Policy, Disclaimer and the other documents listed there.",
                style = MaterialTheme.typography.bodySmall, color = dim, modifier = Modifier.padding(start = 12.dp, top = 4.dp)
            )
        }

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = {
                // Download and filter the node list now, so it is ready before the first tap on Connect.
                NodePool.prewarm()
                onAccept()
            },
            enabled = agree, modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = SgGreen, contentColor = Color.Black,
                disabledContainerColor = Color(0xFF1C1C1C), disabledContentColor = Color(0xFF9A9A9A)
            )
        ) { Text("Continue", fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(24.dp))
    }

    if (showAll) {
        AlertDialog(
            onDismissRequest = { showAll = false },
            containerColor = Color(0xFF121212),
            title = { Text("Legal documents") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    LegalDocs.all.forEach { doc -> DocLinkRow(doc) { showAll = false; onOpenDoc(doc) } }
                }
            },
            confirmButton = { TextButton(onClick = { showAll = false }) { Text("Close", color = SgGreen) } }
        )
    }
}

/** Underlined green link with a 48 dp touch target. */
@Composable
private fun LegalLink(text: String, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.CenterStart) {
        Text(text, color = SgGreen, textDecoration = TextDecoration.Underline, style = MaterialTheme.typography.bodyMedium)
    }
}
