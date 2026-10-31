package com.pocketrocket.ui.git

import com.pocketrocket.ui.theme.PrTheme
import com.pocketrocket.ui.theme.PrType
import com.pocketrocket.ui.theme.JetBrainsMono
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import com.pocketrocket.ui.components.MockIcons
import com.pocketrocket.ui.components.mockText

/**
 * The frame every git screen shares (mockup Git, Diff, Browse): a 44 dp back button and the title at 18/12/4 padding,
 * a mono subtitle, optional actions, a bottom border when [divider] (Diff), and a bottom bar.
 */
@Composable
fun GitScaffold(
    title: String,
    subtitle: @Composable (() -> Unit)? = null,
    onBack: () -> Unit,
    titleStyle: TextStyle = mockText(20.sp, FontWeight.Bold),
    divider: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    content: @Composable (PaddingValues) -> Unit,
) {
    val c = PrTheme.colors
    Scaffold(
        containerColor = c.background,
        contentColor = c.text,
        topBar = {
            Column(Modifier.statusBarsPadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 18.dp, bottom = if (divider) 8.dp else 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                        Icon(MockIcons.Back, contentDescription = "Back", tint = c.text, modifier = Modifier.size(22.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(title, style = titleStyle.copy(color = c.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        subtitle?.invoke()
                    }
                    actions()
                }
                if (divider) HorizontalDivider(thickness = 1.dp, color = c.border)
            }
        },
        bottomBar = bottomBar,
        snackbarHost = { SnackbarHost(snackbar) },
        content = content,
    )
}

/** A title in the mono face, for file names (Diff, Browse). */
val MonoTitle get() = mockText(14.sp, mono = true)

@Composable
fun MonoSubtitle(text: String) {
    Text(
        text,
        style = mockText(12.sp, color = PrTheme.colors.textMuted, mono = true),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Centered spinner, or a message when [error] is set. */
@Composable
fun LoadingOrError(error: String?, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        if (error != null) Text(error, color = PrTheme.colors.textMuted, style = mockText(13.sp))
        else CircularProgressIndicator(color = PrTheme.colors.accent)
    }
}

/** Something loaded in the background: [value] once there, or [error] if loading failed. */
class Loaded<T>(val value: T?, val error: String?)

/** Loads with [load] whenever [keys] change; git's message becomes [Loaded.error]. */
@Composable
fun <T> loadOrError(vararg keys: Any?, load: suspend () -> T): Loaded<T> =
    produceState(Loaded<T>(null, null), *keys) {
        value = try { Loaded(load(), null) } catch (e: Exception) { Loaded(null, e.message ?: "Failed") }
    }.value

/** The small branch glyph from the mockup: two commits on a line and one branching off. */
@Composable
fun BranchIcon(color: Color, size: Dp = 16.dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.width / 24f
        val stroke = Stroke(width = 2f * s)
        drawCircle(color, 2.5f * s, Offset(6 * s, 6 * s), style = stroke)
        drawCircle(color, 2.5f * s, Offset(6 * s, 18 * s), style = stroke)
        drawCircle(color, 2.5f * s, Offset(18 * s, 9 * s), style = stroke)
        drawLine(color, Offset(6 * s, 8.5f * s), Offset(6 * s, 15.5f * s), strokeWidth = 2f * s)
        drawLine(color, Offset(18 * s, 11.5f * s), Offset(8.5f * s, 16.5f * s), strokeWidth = 2f * s)
    }
}
