package com.pocketrocket.ui.chat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.pocketrocket.sessions.attachments.Attachment
import com.pocketrocket.sessions.attachments.AttachmentFiles
import com.pocketrocket.sessions.attachments.Attachments
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The [AttachSheet] while [open], and the Photos, Files and Camera it launches. Picked files are copied into
 * [sessionId]'s folder and handed to [onAdded]; [onImporting] says while a copy is still running. Keep this in the
 * composition while closed, since it holds the launchers. Null [onCommands] leaves out the Commands tile.
 */
@Composable
fun AttachmentPicker(
    sessionId: String,
    open: Boolean,
    onDismiss: () -> Unit,
    onAdded: (List<Attachment>) -> Unit,
    onHint: (String) -> Unit,
    onCommands: (() -> Unit)? = null,
    onImporting: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableIntStateOf(0) }
    fun importing(block: suspend () -> Unit) {
        running++
        onImporting(true)
        scope.launch {
            try { block() } finally { running--; onImporting(running > 0) }
        }
    }
    fun add(uris: List<Uri>) {
        if (uris.isEmpty()) return
        importing {
            val added = withContext(Dispatchers.IO) {
                uris.mapNotNull { runCatching { Attachments.import(context, it, sessionId) }.getOrNull() }
            }
            onAdded(added)
            if (added.size < uris.size) onHint("Couldn't add ${uris.size - added.size} of ${uris.size}")
        }
    }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK)) { add(it) }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { add(it) }
    // The camera writes into a file we name first; kept by path so it survives the activity being recreated.
    var cameraPath by rememberSaveable(sessionId) { mutableStateOf<String?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val file = cameraPath?.let { File(it) }
        cameraPath = null
        if (file == null) return@rememberLauncherForActivityResult
        if (!taken || file.length() == 0L) { file.delete(); return@rememberLauncherForActivityResult }
        importing {
            withContext(Dispatchers.IO) { runCatching { Attachments.adoptPhoto(file) }.getOrNull() }?.let { onAdded(listOf(it)) }
        }
    }

    if (open) {
        AttachSheet(
            onDismiss = onDismiss,
            onPhotos = { photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onFiles = { files.launch(arrayOf("*/*")) },
            onCamera = {
                val target = AttachmentFiles.cameraTarget(sessionId)
                val uri = runCatching { AttachmentFiles.uriFor(context, target) }.getOrNull()
                if (uri == null) {
                    onHint("Couldn't prepare a file for the camera")
                } else {
                    cameraPath = target.path
                    runCatching { camera.launch(uri) }.onFailure { cameraPath = null; onHint("No camera app found") }
                }
            },
            onUris = { add(it) },
            onCommands = onCommands,
        )
    }
}

/** Most photos one pick can add. */
private const val MAX_PICK = 10
