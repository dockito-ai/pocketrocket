package com.pocketrocket.ui.welcome

import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import com.pocketrocket.ui.theme.PrTheme
import com.pocketrocket.ui.components.mockText
import com.pocketrocket.ui.components.AccentButton
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.pocketrocket.ui.components.IconBadge
import com.pocketrocket.ui.components.RocketMark

@Composable
fun WelcomeScreen(onStart: () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }

    Surface(Modifier.fillMaxSize(), color = PrTheme.colors.background, contentColor = PrTheme.colors.text) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).widthIn(max = 480.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(56.dp))
                Reveal(shown, delay = 0) { RocketMark(size = 104.dp) }
                Spacer(Modifier.height(28.dp))
                Reveal(shown, delay = 80) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("PocketRocket", style = mockText(30.sp, FontWeight.Bold, PrTheme.colors.text, letterSpacing = (-0.5).sp))
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Claude Code on your phone, with a real Linux shell underneath.",
                            style = mockText(15.sp, color = PrTheme.colors.textMuted).copy(lineHeight = 21.sp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
                Spacer(Modifier.height(40.dp))
                Reveal(shown, delay = 160) {
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(PrTheme.colors.surface)
                            .border(1.dp, PrTheme.colors.iconTile, RoundedCornerShape(16.dp)).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        Feature(Icons.Filled.Build, "Sets itself up", "Installs Linux packages, git, Node and Claude Code in one go.")
                        Feature(Icons.Filled.Lock, "Straight from Anthropic", "Claude Code is downloaded at setup and checked against Anthropic's manifest.")
                        Feature(Icons.Filled.Info, "About 400 MB", "Use Wi-Fi if you can. You can leave the app while it works.")
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
            Reveal(shown, delay = 240) {
                AccentButton("Set up PocketRocket", onClick = onStart, modifier = Modifier.widthIn(max = 480.dp))
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun Feature(icon: ImageVector, title: String, body: String) {
    Row(verticalAlignment = Alignment.Top) {
        IconBadge(icon)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = mockText(14.sp, FontWeight.SemiBold, PrTheme.colors.text))
            Text(body, style = mockText(12.sp, color = PrTheme.colors.textMuted).copy(lineHeight = 17.4.sp))
        }
    }
}

@Composable
private fun Reveal(visible: Boolean, delay: Int, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(450, delay)) + slideInVertically(tween(450, delay)) { it / 6 },
    ) { content() }
}
