package com.pocketrocket.ui.rocket

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketrocket.assistant.Approval
import com.pocketrocket.assistant.GateVoiceState
import com.pocketrocket.assistant.Protection
import com.pocketrocket.ui.components.AccentButton
import com.pocketrocket.ui.components.MockPage
import com.pocketrocket.ui.components.mockText
import com.pocketrocket.ui.theme.PrTheme

private const val SHORT = 24
private const val MEDIUM = 70

/** Mockup A-Approve: the small orb, who's asking, the real command large, and what answers it. A longer command gets smaller and scrolls, and is never cut. */
@Composable
fun ApprovalScreen(
    approval: Approval,
    voice: GateVoiceState,
    notice: String?,
    canListen: Boolean,
    onDeny: () -> Unit,
    onAllow: () -> Unit,
    onListenAgain: () -> Unit,
) {
    val c = PrTheme.colors
    val fingerprint = approval.protection == Protection.Fingerprint
    val spoken = approval.protection == Protection.SpokenYes && canListen
    MockPage {
        Column(Modifier.fillMaxSize().padding(start = 28.dp, end = 28.dp, top = 24.dp, bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RocketOrb(44.dp, if (voice == GateVoiceState.Idle) OrbMotion.Still else OrbMotion.Pulse)
                Text("Waiting for your answer", style = mockText(14.sp, FontWeight.Medium, c.textSoft))
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 22.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterVertically),
            ) {
                Text(approval.headline, style = mockText(18.sp, color = c.textSoft))
                val size = when {
                    approval.detail.length <= SHORT -> 38.sp
                    approval.detail.length <= MEDIUM -> 26.sp
                    else -> 16.sp
                }
                Text(
                    approval.detail,
                    style = mockText(size, FontWeight.Medium, c.accent, mono = approval.mono).copy(lineHeight = size * 1.15f),
                )
                approval.where?.let { Text(it, style = mockText(15.sp, color = c.textSoft).copy(lineHeight = 21.sp)) }
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val hint = notice ?: when {
                    fingerprint -> "Needs your fingerprint, every time. Ignoring it means no."
                    spoken -> "Say \"allow\" or \"deny\""
                    else -> "Check it, then choose. Ignoring it means no."
                }
                val again = spoken && voice == GateVoiceState.Idle && notice != null
                Text(
                    if (again) "$hint Tap here to answer again." else hint,
                    style = mockText(14.sp, color = c.textSoft).copy(textAlign = TextAlign.Center),
                    modifier = Modifier.fillMaxWidth().let { if (again) it.clickable(role = Role.Button, onClick = onListenAgain) else it },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AccentButton("Deny", onClick = onDeny, modifier = Modifier.weight(1f), height = 64.dp, radius = 20.dp, fontSize = 17.sp, background = c.card, content = c.text)
                    AccentButton(if (fingerprint) "Use fingerprint" else "Allow", onClick = onAllow, modifier = Modifier.weight(1f), height = 64.dp, radius = 20.dp, fontSize = 17.sp)
                }
            }
        }
    }
}
