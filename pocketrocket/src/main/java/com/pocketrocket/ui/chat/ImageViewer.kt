package com.pocketrocket.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pocketrocket.sessions.attachments.Attachment
import com.pocketrocket.sessions.attachments.AttachmentFiles
import com.pocketrocket.ui.theme.PrType

private const val MaxZoom = 5f
private const val DoubleTapZoom = 2.5f

/**
 * Full-screen viewer for a message's images, so they open without leaving the app. Swipe between [images],
 * pinch or double-tap to zoom, drag to pan while zoomed; a tap hides the bar. Open in… still hands one to
 * another app.
 */
@Composable
fun ImageViewer(images: List<Attachment>, start: Int, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val pager = rememberPagerState(initialPage = start) { images.size }
    var chrome by remember { mutableStateOf(true) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(pager, Modifier.fillMaxSize(), key = { images[it].path }) { page ->
                ZoomableImage(images[page], onTap = { chrome = !chrome })
            }
            if (chrome) {
                Row(
                    Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.45f)).statusBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = "Close", onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(ChatIcons.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                    val title = images[pager.currentPage].name + if (images.size > 1) "  ·  ${pager.currentPage + 1} / ${images.size}" else ""
                    Text(title, style = PrType.small, color = Color.White, maxLines = 1, modifier = Modifier.weight(1f))
                    Text(
                        "Open in…",
                        style = PrType.small,
                        color = Color.White,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp))
                            .clickable(role = Role.Button) { AttachmentFiles.open(context, images[pager.currentPage]) }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

/**
 * One image, fitted to the screen. Two fingers zoom; one finger pans only while zoomed in, so at 1× a swipe
 * still turns the pager's page.
 */
@Composable
private fun ZoomableImage(image: Attachment, onTap: () -> Unit) {
    // Decoded so its short side covers the screen's long side: sharp when fitted and when zoomed in a little.
    val screen = LocalConfiguration.current.run { maxOf(screenWidthDp, screenHeightDp) }.dp
    val bitmap = rememberDecodedImage(image.path, screen)
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        fun clamp(o: Offset, s: Float) = Offset(
            o.x.coerceIn(-width * (s - 1) / 2, width * (s - 1) / 2),
            o.y.coerceIn(-height * (s - 1) / 2, height * (s - 1) / 2),
        )
        Box(
            Modifier.fillMaxSize()
                .pointerInput(image.path) {
                    detectTapGestures(
                        onTap = { onTap() },
                        onDoubleTap = { at ->
                            if (scale > 1f) {
                                scale = 1f; offset = Offset.Zero
                            } else {
                                // Zoom in around the tapped point.
                                scale = DoubleTapZoom
                                offset = clamp((Offset(width / 2, height / 2) - at) * (DoubleTapZoom - 1), DoubleTapZoom)
                            }
                        },
                    )
                }
                .pointerInput(image.path) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            if (event.changes.size > 1 || scale > 1f) {
                                scale = (scale * event.calculateZoom()).coerceIn(1f, MaxZoom)
                                offset = if (scale == 1f) Offset.Zero else clamp(offset + event.calculatePan(), scale)
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                },
        ) {
            bitmap?.let {
                Image(
                    it,
                    contentDescription = image.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        scaleX = scale; scaleY = scale
                        translationX = offset.x; translationY = offset.y
                    },
                )
            }
        }
    }
}
