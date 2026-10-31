package com.pocketrocket.ui.backup

import android.Manifest
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketrocket.backup.BackupException
import com.pocketrocket.backup.BackupJob
import com.pocketrocket.backup.BackupKind
import com.pocketrocket.backup.BackupManager
import com.pocketrocket.backup.BackupManifest
import com.pocketrocket.backup.BackupPart
import com.pocketrocket.backup.BackupRunnerSteps
import com.pocketrocket.backup.RestorePreview
import com.pocketrocket.backup.WrongPassphrase
import com.pocketrocket.health.SystemScreen
import com.pocketrocket.health.SystemScreens
import com.pocketrocket.storage.SharedStorage
import com.pocketrocket.ui.components.AccentButton
import com.pocketrocket.ui.components.Caps
import com.pocketrocket.ui.components.MockIcons
import com.pocketrocket.ui.components.MockPage
import com.pocketrocket.ui.components.MockTextField
import com.pocketrocket.ui.components.MockTopBar
import com.pocketrocket.ui.components.OutlineButton
import com.pocketrocket.ui.components.mockText
import com.pocketrocket.ui.setup.StepList
import com.pocketrocket.ui.setup.StepListStyle
import com.pocketrocket.ui.theme.PrTheme
import com.pocketrocket.ui.tools.BusyButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** The Settings row's value: "Not backed up ›", "Today, 14:02 ›", "Backing up · 42% ›" in blue. */
object BackupRow {
    fun value(job: BackupJob?): String {
        if (job?.running == true) {
            val verb = if (job.kind == BackupKind.Restore) "Restoring" else "Backing up"
            return "$verb${job.percent?.let { " · $it%" }.orEmpty()} ›"
        }
        val file = BackupManager.file
        return if (file.isFile) "${shortDate(file.lastModified())} ›" else "Not backed up ›"
    }

    @Composable
    fun color(job: BackupJob?): Color = if (job?.running == true) PrTheme.colors.blue else PrTheme.colors.textMuted
}

private fun shortDate(millis: Long): String {
    val day = Calendar.getInstance().apply { timeInMillis = millis }
    val now = Calendar.getInstance()
    val today = day.get(Calendar.YEAR) == now.get(Calendar.YEAR) && day.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
    return if (today) "Today, " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))
    else SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(millis))
}

/** Settings › Backup (docs/ux-mockup/Backup.dc.html): the file, what goes in, Back up and Restore. Full screen, no bottom nav. */
@Composable
fun BackupScreen(onBack: () -> Unit, onRestore: () -> Unit) {
    val context = LocalContext.current
    val job by BackupManager.job.collectAsState()
    var plan by remember { mutableStateOf<BackupSummary?>(null) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var settingPassphrase by remember { mutableStateOf(false) }
    var granted by remember { mutableStateOf(SharedStorage.granted(context)) }
    var refresh by remember { mutableIntStateOf(0) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        granted = SharedStorage.granted(context)
        if (!allowed && !granted) SystemScreens.open(context, SystemScreen.AppDetails)
    }
    LaunchedEffect(refresh) {
        plan = null
        scanError = null
        withContext(Dispatchers.IO) {
            runCatching { BackupManager.scan(context) }
                .onSuccess { p -> plan = BackupSummary(p.parts, p.claude.size, p.github.size, p.dockito.size, p.totalBytes, p.leftOut) }
                .onFailure { scanError = it.message ?: "Couldn't work out what goes in." }
        }
    }

    val shown = job?.takeIf { it.kind == BackupKind.Backup }
    if (settingPassphrase && shown == null) {
        SetPassphrasePage(
            onBack = { settingPassphrase = false },
            onStart = { BackupManager.backup(context, it); settingPassphrase = false },
        )
        return
    }

    fun leave() {
        if (shown?.running != true) BackupManager.dismiss()
        onBack()
    }
    BackHandler { leave() }
    BackupPage("Backup", ::leave, bottom = {
        val other = job?.takeIf { it.kind == BackupKind.Restore && it.running }
        when {
            shown?.running == true -> {
                Foot("You can leave this page. The backup keeps going.")
                OutlineButton("Cancel", BackupManager::cancel, Modifier.fillMaxWidth().height(48.dp))
            }
            shown?.done == true -> OutlineButton("Done", { BackupManager.dismiss(); refresh++ }, Modifier.fillMaxWidth().height(48.dp))
            shown?.failed == true -> AccentButton("Try again", { BackupManager.dismiss(); refresh++ }, height = 48.dp, radius = 12.dp)
            else -> {
                val summary = plan
                val tooBig = summary != null && BackupManager.freeSpace() < BackupManager.neededSpace(summary.totalBytes)
                when {
                    other != null -> BusyButton("Restoring…")
                    !granted -> AccentButton("Allow access", { ask.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) }, height = 48.dp, radius = 12.dp)
                    summary == null -> BusyButton(if (scanError != null) "Back up" else "Working out sizes…")
                    tooBig -> BusyButton("Back up")
                    else -> {
                        if (BackupManager.file.isFile) Foot("Backing up again replaces this file once the new one is written and checked.")
                        AccentButton("Back up", { settingPassphrase = true }, height = 48.dp, radius = 12.dp)
                    }
                }
                OutlineButton("Restore", onRestore, Modifier.fillMaxWidth().height(48.dp))
            }
        }
    }) {
        when {
            shown?.running == true -> JobTile(shown)
            else -> {
                FileTile(if (shown?.done == true) "Just now" else null)
                when {
                    shown?.done == true -> {
                        Message("Backed up and checked with your passphrase.", Tone.Ok)
                        Foot("Saved in Downloads/PocketRocket. Keep your passphrase safe: it isn't stored anywhere.")
                    }
                    shown?.failed == true -> {
                        StepsTile(shown)
                    }
                    else -> {
                        val summary = plan
                        if (!granted) Message("Saving to Downloads needs access to shared storage.", Tone.Warn)
                        scanError?.let { Message("Couldn't work out what goes in: $it", Tone.Error) }
                        if (summary != null && BackupManager.freeSpace() < BackupManager.neededSpace(summary.totalBytes)) {
                            val needed = BackupManager.sizeText(BackupManager.neededSpace(summary.totalBytes))
                            val free = BackupManager.sizeText(BackupManager.freeSpace())
                            Message("Not enough space. The new backup needs up to $needed and shared storage has $free free. Your old backup is untouched.", Tone.Error)
                        }
                        Caps("WHAT GOES IN")
                        WhatGoesIn(summary)
                        Foot("Encrypted with your passphrase. Chats and tools stay out. node_modules and build folders are skipped.")
                        if (!summary?.leftOut.isNullOrEmpty()) {
                            Caps("LEFT OUT")
                            Foot(summary!!.leftOut.take(6).joinToString("\n") + if (summary.leftOut.size > 6) "\nand ${summary.leftOut.size - 6} more" else "")
                        }
                    }
                }
            }
        }
    }
}

private data class BackupSummary(val parts: List<BackupPart>, val claude: Int, val github: Int, val dockito: Int, val totalBytes: Long, val leftOut: List<String>)

@Composable
private fun WhatGoesIn(s: BackupSummary?) {
    if (s == null) {
        Tile { Text("Working out sizes…", style = mockText(13.sp), color = PrTheme.colors.textMuted) }
        return
    }
    fun size(key: String) = BackupManager.sizeText(s.parts.firstOrNull { it.key == key }?.bytes ?: 0)
    fun count(n: Int) = "$n ${if (n == 1) "account" else "accounts"}"
    val repos = s.parts.firstOrNull { it.key == BackupManifest.PART_REPOS }?.files ?: 0
    Tile(gap = 0.dp) {
        Kv("Repos and folders", "$repos files · ${size(BackupManifest.PART_REPOS)}")
        Kv("Claude config and logins", "${count(s.claude)} · ${size(BackupManifest.PART_CLAUDE)}")
        Kv("Git and GitHub logins", "${count(s.github)} · ${size(BackupManifest.PART_GITHUB)}")
        Kv("Dockito config and logins", "${count(s.dockito)} · ${size(BackupManifest.PART_DOCKITO)}")
        Kv("Workspace list and accounts in use", size(BackupManifest.PART_LISTS))
    }
}

@Composable
private fun SetPassphrasePage(onBack: () -> Unit, onStart: (CharArray) -> Unit) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    BackHandler(onBack = onBack)
    val ok = first.length >= MIN_PASSPHRASE && first == second
    BackupPage("Set a passphrase", onBack, bottom = {
        AccentButton("Back up", { onStart(first.toCharArray()) }, height = 48.dp, radius = 12.dp, enabled = ok)
    }) {
        Text("The whole file is encrypted with this passphrase, because it holds your logins and sits in shared storage.", style = mockText(13.sp).copy(lineHeight = 19.sp), color = PrTheme.colors.textMuted)
        Caps("PASSPHRASE")
        PassphraseField(first, "At least $MIN_PASSPHRASE characters") { first = it }
        Caps("SAME AGAIN")
        PassphraseField(second, "Type it again") { second = it }
        if (second.isNotEmpty() && first != second) Text("They don't match yet.", style = mockText(12.sp), color = PrTheme.colors.redSoft)
        Message("If you forget it, this backup can't be opened. Nobody can recover it.", Tone.Warn)
    }
}

/** Settings › Backup › Restore: pick a file, its passphrase, what's in it and what it does, then the job. */
@Composable
fun RestoreScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val job by BackupManager.job.collectAsState()
    val scope = rememberCoroutineScope()
    var uri by remember { mutableStateOf<Uri?>(null) }
    var fileName by remember { mutableStateOf("") }
    var fileSize by remember { mutableStateOf(0L) }
    var passphrase by remember { mutableStateOf("") }
    var opening by remember { mutableStateOf(false) }
    var wrong by remember { mutableStateOf(false) }
    var damage by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<RestorePreview?>(null) }
    var running by remember { mutableIntStateOf(BackupManager.sessionsRunning()) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        if (picked != null) {
            uri = picked
            passphrase = ""
            wrong = false
            damage = null
            preview = null
            context.contentResolver.query(picked, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    fileName = c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let(c::getString).orEmpty()
                    fileSize = c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let(c::getLong) ?: 0
                }
            }
        }
    }
    LaunchedEffect(preview) { running = BackupManager.sessionsRunning() }

    val shown = job?.takeIf { it.kind == BackupKind.Restore }
    fun back() {
        when {
            shown?.running == true -> onBack()
            shown != null -> { BackupManager.dismiss(); preview = null }
            preview != null -> preview = null
            uri != null -> uri = null
            else -> onBack()
        }
    }
    BackHandler { back() }

    fun chooseFile() = pick.launch(arrayOf("*/*"))
    fun open(u: Uri) {
        opening = true
        wrong = false
        val chars = passphrase.toCharArray()
        scope.launch {
            try {
                preview = withContext(Dispatchers.IO) { BackupManager.preview(context, u, chars) }
            } catch (_: WrongPassphrase) {
                wrong = true
            } catch (e: BackupException) {
                damage = e.message
            } catch (e: Exception) {
                damage = "This backup is damaged and can't be restored."
            } finally {
                chars.fill('\u0000')
                opening = false
            }
        }
    }

    val current = uri
    val look = preview
    BackupPage("Restore", ::back, bottom = {
        when {
            shown?.running == true -> {
                val moving = (shown.steps.steps.size) >= BackupRunnerSteps.RESTORE_POINT_OF_NO_RETURN
                if (!moving) {
                    Foot("You can leave this page. The restore keeps going.")
                    OutlineButton("Cancel", BackupManager::cancel, Modifier.fillMaxWidth().height(48.dp))
                } else BusyButton("Moving into place…")
            }
            shown?.done == true -> Unit
            shown?.failed == true -> AccentButton("Try again", { BackupManager.dismiss() }, height = 48.dp, radius = 12.dp)
            look != null && current != null ->
                if (running > 0) BusyButton("Restore")
                else AccentButton("Restore", { BackupManager.restore(context, current, passphrase.toCharArray()) }, height = 48.dp, radius = 12.dp)
            damage != null -> AccentButton("Choose another file", { damage = null; chooseFile() }, height = 48.dp, radius = 12.dp)
            current != null ->
                if (opening) BusyButton("Opening…")
                else AccentButton("Open backup", { open(current) }, height = 48.dp, radius = 12.dp, enabled = passphrase.isNotEmpty())
            else -> AccentButton("Choose backup file", ::chooseFile, height = 48.dp, radius = 12.dp)
        }
    }) {
        when {
            shown?.running == true -> JobTile(shown)
            shown?.done == true -> {
                StepsTile(shown, showError = false)
                Message("Restored. Restarting PocketRocket…", Tone.Ok)
            }
            shown?.failed == true -> StepsTile(shown)
            look != null -> PreviewBody(look, running)
            damage != null -> {
                PickedFile(fileName, fileSize)
                Message("${damage} Nothing has changed.", Tone.Error)
            }
            current != null -> {
                PickedFile(fileName, fileSize)
                Caps("PASSPHRASE")
                PassphraseField(passphrase, "The passphrase for this backup") { passphrase = it; wrong = false }
                if (wrong) Text("That passphrase doesn't open this backup. Nothing has changed.", style = mockText(12.sp), color = PrTheme.colors.redSoft)
            }
            else -> {
                Text("Restore brings back a backup over this install. Finish first-run setup and sign in first, if you want to: logins on this phone are kept.", style = mockText(13.sp).copy(lineHeight = 19.sp), color = PrTheme.colors.textMuted)
                Tile {
                    IconLine("Choose the file", "Usually Downloads/PocketRocket/pocketrocket.backup")
                }
            }
        }
    }
}

@Composable
private fun PreviewBody(p: RestorePreview, running: Int) {
    val m = p.manifest
    Caps("IN THIS BACKUP · ${SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(m.createdAt)).uppercase()}")
    Tile(gap = 0.dp) {
        Kv("Workspaces", "${m.workspaces.size}")
        Kv("Repos and folders", "${m.repos.size} · ${BackupManager.sizeText(m.parts.firstOrNull { it.key == BackupManifest.PART_REPOS }?.bytes ?: 0)}")
        Kv("Claude accounts", "${m.claude.size}")
        Kv("GitHub accounts", "${m.github.size}")
        Kv("Dockito accounts", "${m.dockito.size}")
    }
    if (p.merges.isNotEmpty()) {
        Caps("WILL MERGE")
        Tile(gap = 0.dp) { p.merges.forEach { Kv(it.who, it.what) } }
    }
    val repos = p.replacedRepos
    val replaced = if (repos.isEmpty()) "No repos on this phone are replaced."
    else "Replaces ${repos.size} ${if (repos.size == 1) "repo" else "repos"} on this phone: ${repos.joinToString(", ") { it.substringAfterLast('/') }}."
    Message("$replaced Setup's Claude settings are reapplied. Anything else stays, including workspaces and accounts that aren't in this backup.", Tone.Warn)
    if (running > 0) Message("${if (running == 1) "1 session is" else "$running sessions are"} running. End them first: restoring changes the files they are working on.", Tone.Error)
}

// ---- Shared pieces ----

private const val MIN_PASSPHRASE = 8

@Composable
private fun BackupPage(title: String, onBack: () -> Unit, bottom: @Composable ColumnScope.() -> Unit, content: @Composable ColumnScope.() -> Unit) {
    MockPage {
        MockTopBar(title, MockIcons.Back, onBack, titleSize = 17.sp)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = bottom)
    }
}

@Composable
private fun PassphraseField(value: String, placeholder: String, onChange: (String) -> Unit) {
    MockTextField(
        value, onChange, placeholder = placeholder,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        visualTransformation = PasswordVisualTransformation(),
    )
}

@Composable
private fun Tile(gap: androidx.compose.ui.unit.Dp = 12.dp, border: Color = PrTheme.colors.iconTile, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(PrTheme.colors.surface)
            .border(1.dp, border, RoundedCornerShape(14.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(gap),
        content = content,
    )
}

@Composable
private fun IconLine(name: String, sub: String, subColor: Color = PrTheme.colors.textMuted, tint: Color = PrTheme.colors.accent, pill: String? = null) {
    val c = PrTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Text("B", style = mockText(15.sp, FontWeight.Bold), color = tint)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, style = mockText(14.sp, FontWeight.SemiBold), color = c.text)
            Text(sub, style = mockText(12.sp), color = subColor)
        }
        pill?.let {
            Text(it, style = mockText(11.sp, FontWeight.SemiBold), color = c.green, maxLines = 1,
                modifier = Modifier.clip(CircleShape).background(c.green.copy(alpha = 0.14f)).padding(horizontal = 9.dp, vertical = 4.dp))
        }
    }
}

/** The one backup file: its date and size, or that there isn't one. */
@Composable
private fun FileTile(whenText: String?) {
    val file = BackupManager.file
    Tile {
        if (file.isFile) IconLine(file.name, "${whenText ?: shortDate(file.lastModified())} · ${BackupManager.sizeText(file.length())}", pill = "Saved")
        else IconLine("No backup yet", "Downloads/PocketRocket/pocketrocket.backup")
    }
}

@Composable
private fun PickedFile(name: String, size: Long) {
    Tile { IconLine(name.ifEmpty { "Backup file" }, BackupManager.sizeText(size), tint = PrTheme.colors.text) }
}

@Composable
private fun JobTile(job: BackupJob) {
    val verb = if (job.kind == BackupKind.Restore) "Restoring" else "Backing up"
    val title = listOfNotNull("$verb${if (job.item.isNotEmpty()) " ${job.item}" else ""}", job.percent?.let { "$it%" }).joinToString(" · ")
    val sub = if (job.total > 0) "${BackupManager.sizeText(job.bytes.coerceAtMost(job.total))} of ${BackupManager.sizeText(job.total)}" else "Working…"
    Tile(border = PrTheme.colors.blue.copy(alpha = 0.3f)) {
        IconLine(title, sub, subColor = PrTheme.colors.blue, tint = PrTheme.colors.blue)
        Box(Modifier.fillMaxWidth().height(1.dp).background(PrTheme.colors.divider))
        StepList(job.steps, style = StepListStyle.Compact, upcoming = if (job.kind == BackupKind.Restore) BackupRunnerSteps.restore else BackupRunnerSteps.backup)
    }
}

/** The steps of a job that ended. The failed step's message sits under it. */
@Composable
private fun StepsTile(job: BackupJob, showError: Boolean = true) {
    Tile { StepList(job.steps.let { if (showError) it else it.copy(error = null) }, style = StepListStyle.Compact) }
}

private enum class Tone { Warn, Error, Ok }

@Composable
private fun Message(text: String, tone: Tone) {
    val c = PrTheme.colors
    val color = when (tone) {
        Tone.Warn -> c.accent
        Tone.Error -> c.redSoft
        Tone.Ok -> c.green
    }
    Text(text, style = mockText(13.sp).copy(lineHeight = 19.sp), color = color,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.10f)).padding(horizontal = 14.dp, vertical = 12.dp))
}

@Composable
private fun Foot(text: String) {
    Text(text, style = mockText(11.sp).copy(lineHeight = 16.sp), color = PrTheme.colors.textMuted)
}

@Composable
private fun Kv(left: String, right: String) {
    val c = PrTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(left, style = mockText(13.sp), color = c.text, modifier = Modifier.weight(1f))
        Text(right, style = mockText(13.sp), color = c.textMuted, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}
