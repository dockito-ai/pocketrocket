package com.pocketrocket.ui.chat

import android.Manifest
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.pocketrocket.sessions.attachments.AttachmentFiles
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketrocket.sessions.attachments.Attachment
import com.pocketrocket.ui.theme.JetBrainsMono
import com.pocketrocket.ui.theme.PrTheme
import com.pocketrocket.ui.theme.PrType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The composer's "+" (Attachments.html, option A): opens [AttachSheet]. Replaces the old "/" button. */
@Composable
fun PlusButton(onClick: () -> Unit, size: Dp = 44.dp) {
    val c = PrTheme.colors
    Box(
        Modifier.size(size).clip(CircleShape).border(1.dp, c.border, CircleShape)
            .clickable(role = Role.Button, onClickLabel = "Attach or run a command", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(ChatIcons.Plus, contentDescription = "Attach", tint = c.textSoft, modifier = Modifier.size(size / 2))
    }
}

/**
 * Photos, Files, Camera and Commands in one row of tiles, Paste when the clipboard holds a file, then the latest
 * screenshots (one tap attaches one). Each action closes the sheet and then acts. No Commands tile without [onCommands].
 */
@Composable
fun AttachSheet(
    onDismiss: () -> Unit,
    onPhotos: () -> Unit,
    onFiles: () -> Unit,
    onCamera: () -> Unit,
    onCommands: (() -> Unit)?,
    onUris: (List<Uri>) -> Unit,
) {
    val c = PrTheme.colors
    val context = LocalContext.current
    val clipboard = remember { context.getSystemService(ClipboardManager::class.java) }
    // The description says whether there is a file without reading the clip (reading it shows Android's paste toast).
    val canPaste = remember {
        val description = clipboard?.primaryClipDescription
        description != null && (0 until description.mimeTypeCount).any { !description.getMimeType(it).startsWith("text/") }
    }
    ChatSheet(onDismiss) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            SheetAction("Photos", ChatIcons.Photo) { onDismiss(); onPhotos() }
            SheetAction("Files", ChatIcons.File) { onDismiss(); onFiles() }
            SheetAction("Camera", ChatIcons.Camera) { onDismiss(); onCamera() }
            if (onCommands != null) SheetAction("Commands", null) { onDismiss(); onCommands() }
        }
        if (canPaste) {
            val shape = RoundedCornerShape(12.dp)
            Row(
                Modifier.fillMaxWidth().height(44.dp).clip(shape).border(1.dp, c.border, shape)
                    .clickable(role = Role.Button) {
                        val clip = clipboard?.primaryClip
                        val uris = (0 until (clip?.itemCount ?: 0)).mapNotNull { clip?.getItemAt(it)?.uri }
                        onDismiss(); onUris(uris)
                    }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(ChatIcons.File, contentDescription = null, tint = c.textSoft, modifier = Modifier.size(18.dp))
                Text("Paste from clipboard", style = PrType.body, color = c.text)
            }
        }
        RecentScreenshots(onPick = { onDismiss(); onUris(listOf(it)) })
    }
}

/**
 * The latest screenshots, newest first. Needs photo access; until it is given, a quiet button asks for it (on
 * Android 14+ the user may allow only some photos, and then only those show).
 */
@Composable
private fun RecentScreenshots(onPick: (Uri) -> Unit) {
    val c = PrTheme.colors
    val context = LocalContext.current
    val permissions = remember {
        when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
    fun allowed() = permissions.any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    var granted by remember { mutableStateOf(allowed()) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = allowed() }
    val shots by produceState(emptyList<Uri>(), granted) {
        value = if (granted) withContext(Dispatchers.IO) { runCatching { latestScreenshots(context, 8) }.getOrDefault(emptyList()) } else emptyList()
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("RECENT SCREENSHOTS", style = PrType.section, color = c.textMuted)
        when {
            !granted -> Text(
                "Show my recent screenshots here",
                style = PrType.body,
                color = c.accent,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { ask.launch(permissions) }.padding(vertical = 6.dp),
            )
            shots.isEmpty() -> Text("No screenshots yet.", style = PrType.caption, color = c.textMuted)
            else -> Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                shots.forEach { uri ->
                    val image = rememberMediaThumbnail(uri, 64.dp)
                    val shape = RoundedCornerShape(10.dp)
                    Box(
                        Modifier.size(64.dp).clip(shape).background(c.raised).border(1.dp, c.border, shape)
                            .clickable(role = Role.Button, onClickLabel = "Attach") { onPick(uri) },
                    ) {
                        image?.let { Image(it, contentDescription = "Screenshot", contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize()) }
                    }
                }
            }
        }
    }
}

private fun latestScreenshots(context: Context, limit: Int): List<Uri> {
    val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    val column = if (Build.VERSION.SDK_INT >= 29) MediaStore.Images.Media.RELATIVE_PATH else MediaStore.Images.Media.DATA
    val uris = mutableListOf<Uri>()
    context.contentResolver.query(
        collection, arrayOf(MediaStore.Images.Media._ID), "$column LIKE ?", arrayOf("%Screenshots%"),
        "${MediaStore.Images.Media.DATE_ADDED} DESC",
    )?.use { cursor ->
        while (cursor.moveToNext() && uris.size < limit) uris += ContentUris.withAppendedId(collection, cursor.getLong(0))
    }
    return uris
}

@Composable
private fun rememberMediaThumbnail(uri: Uri, size: Dp): ImageBitmap? {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val image by produceState<ImageBitmap?>(null, uri, px) {
        value = if (Build.VERSION.SDK_INT < 29) null else withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.loadThumbnail(uri, android.util.Size(px, px), null).asImageBitmap() }.getOrNull()
        }
    }
    return image
}

@Composable
private fun SheetAction(label: String, icon: ImageVector?, onClick: () -> Unit) {
    val c = PrTheme.colors
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onClick).padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(c.background).border(1.dp, c.border, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) Icon(icon, contentDescription = null, tint = c.text, modifier = Modifier.size(24.dp))
            else Text("/", fontFamily = JetBrainsMono, fontSize = 20.sp, color = c.text)
        }
        Text(label, style = PrType.caption, color = c.textSoft)
    }
}

/** What will go with the next message, above the chat box: thumbnails and file chips, each with ✕. */
@Composable
fun AttachmentTray(attachments: List<Attachment>, onRemove: (Attachment) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 6.dp, end = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        attachments.forEach { a ->
            Box {
                if (a.isImage) Thumbnail(a, Modifier.size(56.dp)) else FileChip(a, Modifier.height(56.dp))
                RemoveBadge(Modifier.align(Alignment.TopEnd).padding(0.dp)) { onRemove(a) }
            }
        }
    }
}

/**
 * Attachments inside a sent message: images in a two-column grid, then file chips. A tap on an image opens the
 * [ImageViewer] in the app; a file opens in another app.
 */
@Composable
fun BubbleAttachments(attachments: List<Attachment>) {
    val context = LocalContext.current
    val images = attachments.filter { it.isImage }
    var viewing by remember { mutableStateOf<Int?>(null) }
    fun Modifier.opens(a: Attachment) = clickable(role = Role.Button, onClickLabel = "Open") { AttachmentFiles.open(context, a) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        images.withIndex().chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                pair.forEach { (index, a) ->
                    Thumbnail(a, Modifier.size(if (pair.size == 1) 200.dp else 110.dp).clickable(role = Role.Button, onClickLabel = "View") { viewing = index })
                }
            }
        }
        attachments.filterNot { it.isImage }.forEach { FileChip(it, Modifier.height(48.dp).opens(it)) }
    }
    viewing?.let { ImageViewer(images, it) { viewing = null } }
}

@Composable
private fun Thumbnail(a: Attachment, modifier: Modifier) {
    val c = PrTheme.colors
    val shape = RoundedCornerShape(12.dp)
    val image = rememberDecodedImage(a.path, 220.dp)
    Box(modifier.clip(shape).background(c.raised).border(1.dp, c.border, shape)) {
        image?.let { Image(it, contentDescription = a.name, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize()) }
    }
}

@Composable
private fun FileChip(a: Attachment, modifier: Modifier) {
    val c = PrTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier.widthIn(max = 200.dp).clip(shape).background(c.card).border(1.dp, c.border, shape).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val ext = a.name.substringAfterLast('.', "").uppercase().take(4).ifEmpty { "FILE" }
        Box(
            Modifier.size(width = 30.dp, height = 34.dp).clip(RoundedCornerShape(6.dp)).background(if (a.isPdf) c.redBorder else c.raised),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Text(ext, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = c.textSoft, modifier = Modifier.padding(bottom = 4.dp))
        }
        Column {
            Text(a.name, fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sizeLabel(a.size), style = PrType.micro, color = c.textMuted)
        }
    }
}

@Composable
private fun RemoveBadge(modifier: Modifier, onClick: () -> Unit) {
    val c = PrTheme.colors
    Box(
        modifier.size(22.dp).clip(CircleShape).background(c.border).border(1.5.dp, c.bar, CircleShape)
            .clickable(role = Role.Button, onClickLabel = "Remove", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(ChatIcons.Close, contentDescription = "Remove", tint = c.text, modifier = Modifier.size(12.dp))
    }
}

/**
 * A downsampled bitmap of the image at [path] (its short side at least [size]), decoded off the main thread;
 * null until ready or if unreadable.
 */
@Composable
internal fun rememberDecodedImage(path: String, size: Dp): ImageBitmap? {
    val px = with(LocalDensity.current) { size.roundToPx() }
    val image by produceState<ImageBitmap?>(null, path, px) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                var sample = 1
                while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= px) sample *= 2
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }.getOrNull()
        }
    }
    return image
}

private fun sizeLabel(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}
