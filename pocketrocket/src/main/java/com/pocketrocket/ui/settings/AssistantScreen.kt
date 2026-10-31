package com.pocketrocket.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pocketrocket.assistant.AndroidAssist
import com.pocketrocket.assistant.AssistantPrefs
import com.pocketrocket.assistant.AssistantSettings
import com.pocketrocket.assistant.LockedPhoneRule
import com.pocketrocket.assistant.Speaker
import com.pocketrocket.assistant.Spoken
import com.pocketrocket.assistant.TtsSpeaker
import com.pocketrocket.assistant.VoiceOption
import com.pocketrocket.assistant.Voices
import com.pocketrocket.assistant.WakeGain
import com.pocketrocket.assistant.WakeWord
import com.pocketrocket.assistant.WakeWordService
import com.pocketrocket.profiles.ClaudeAccounts
import com.pocketrocket.sessions.ModelCatalog
import com.pocketrocket.ui.Routes
import com.pocketrocket.ui.components.AccentSwitch
import com.pocketrocket.ui.components.Caps
import com.pocketrocket.ui.components.MockIcons
import com.pocketrocket.ui.components.MockPage
import com.pocketrocket.ui.components.MockTopBar
import com.pocketrocket.ui.components.OutlineButton
import com.pocketrocket.ui.components.rememberNotificationAsk
import com.pocketrocket.ui.components.RadioDot
import com.pocketrocket.ui.components.mockText
import com.pocketrocket.ui.rocket.rememberWakeWordSwitch
import com.pocketrocket.ui.theme.PrTheme

/**
 * Settings › Assistant (mockup In-Assistant, In-Off). The on/off switch, Voice and the ways to wake it work now; the rest is stored for the
 * steps that use it and says so in its section heading. Projects, Memory and battery arrive with what they show.
 */
@Composable
fun AssistantScreen(onBack: () -> Unit, navigate: (String) -> Unit) {
    val context = LocalContext.current
    val c = PrTheme.colors
    val settings by remember { AssistantPrefs.settings(context) }.collectAsState()
    fun change(edit: (AssistantSettings) -> AssistantSettings) = AssistantPrefs.update(context, edit)
    val askNotifications = rememberNotificationAsk("Rocket uses a notification when it can't open over another app: for \"Hey Rocket\", and when an action needs your fingerprint.")

    MockPage {
        MockTopBar("Assistant", MockIcons.Back, onBack)
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            MasterSwitch(settings.enabled) { on -> if (on) askNotifications { change { it.copy(enabled = true) } } else change { it.copy(enabled = false) } }
            if (!settings.enabled) {
                Text(
                    "Rocket won't listen or speak, its tab goes away and the app opens on Sessions. No Claude session runs for it. Session notifications and your Allow and Deny buttons still work.",
                    style = mockText(14.sp, color = c.textSoft).copy(lineHeight = 21.sp),
                )
                return@Column
            }
            ClaudeSection(settings, ::change)
            VoiceSection(settings, ::change)
            WakeSection()
            LockedSection(settings, ::change)
            Column {
                Caps("SAFETY AND HISTORY", modifier = Modifier.padding(bottom = 4.dp))
                AssistantValueRow("Projects", "What Rocket can do in each", "›") { navigate(Routes.ASSISTANT_PROJECTS) }
                AssistantValueRow("Memory", "Notes and nicknames it has learned", "›") { navigate(Routes.ASSISTANT_MEMORY) }
                AssistantValueRow("Sensitive actions", "Always need your fingerprint", "›") { navigate(Routes.ASSISTANT_SENSITIVE) }
                AssistantValueRow("Activity", "What Rocket did and approved", "›", last = true) { navigate(Routes.ASSISTANT_ACTIVITY) }
            }
        }
    }
}

/**
 * Ways to call Rocket from outside the app. Android decides what reaches a sideloaded app, so these say what they ask for and
 * promise no more: the power button and the earbud long-press depend on the phone.
 */
@Composable
private fun WakeSection() {
    val context = LocalContext.current
    val c = PrTheme.colors
    val wakeWord = rememberWakeWordSwitch()
    // Both are changed in Android's settings, so look again on coming back.
    var looked by remember { mutableStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) looked++ }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    val isAssistant = remember(looked) { AndroidAssist.isDefault(context) }
    val overApps = remember(looked) { WakeWordService.canOpenPopupDirectly(context) }
    Column {
        Caps("WAKE IT", modifier = Modifier.padding(bottom = 4.dp))
        ToggleRow("\"Hey Rocket\"", wakeWord.view.note, wakeWord.view.on) { wakeWord.toggle() }
        if (wakeWord.view.on) WakeReadout()
        if (!overApps) {
            AssistantValueRow("Open over other apps", "Without this, \"Hey Rocket\" can only show a notification when the screen's on", "Not allowed ›") { AndroidAssist.openOverlaySetting(context) }
        }
        AssistantValueRow("Android's assistant", "Where the phone allows it, picking PocketRocket lets the power button open Rocket", when (isAssistant) { true -> "PocketRocket ›"; false -> "Not set ›"; null -> "Open ›" }, last = true) { AndroidAssist.openSetting(context) }
        Text(
            "Holding an earbud's button opens Rocket only on earbuds and phones that pass it to Android, which may ask you to choose an app. Music controls stay as they are.",
            style = mockText(12.sp, color = c.textMuted).copy(lineHeight = 17.sp),
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/**
 * What the detector gets, live, so a test on the phone shows where it stops (decision 62): no level means no audio arrives,
 * a level that doesn't move when he speaks means the wrong source, and "heard" with no popup means Android blocked the screen.
 */
@Composable
private fun WakeReadout() {
    val c = PrTheme.colors
    val debug by WakeWord.debug.collectAsState()
    val time = remember(debug.lastHeardAt) { if (debug.lastHeardAt == 0L) "" else java.text.DateFormat.getTimeInstance().format(java.util.Date(debug.lastHeardAt)) }
    val lines = buildList {
        add(if (debug.levelDb <= WakeGain.SILENT_DB) "Mic: no audio yet" else "Mic: %.0f dB now, loudest %.0f dB, raised %.0fx".format(debug.levelDb, debug.peakDb, debug.gain))
        debug.source?.let { add("Source: $it" + if (debug.sourceWasSilent) " (voice recognition gave only silence)" else "") }
        add(if (debug.heardCount == 0) "Not heard yet" else "Heard ${debug.heardCount} time${if (debug.heardCount == 1) "" else "s"}, last at $time")
        if (android.os.Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)) {
            add("On Xiaomi, also allow \"Display pop-up windows while running in the background\" in the app's permissions, or it can only show a notification.")
        }
    }
    Text(lines.joinToString("\n"), style = mockText(12.sp, color = c.textMuted).copy(lineHeight = 17.sp), modifier = Modifier.padding(vertical = 6.dp))
}

/** The big switch, a plain card on or off. */
@Composable
private fun MasterSwitch(on: Boolean, onChange: (Boolean) -> Unit) {
    val c = PrTheme.colors
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().background(c.surface, shape)
            .border(BorderStroke(1.dp, c.iconTile), shape).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Voice assistant", style = mockText(15.sp, FontWeight.SemiBold, c.text))
            Text(if (on) "On" else "Off", style = mockText(12.sp, color = if (on) c.textSoft else c.textMuted))
        }
        AccentSwitch(on, onChange)
    }
}

@Composable
private fun ClaudeSection(settings: AssistantSettings, change: ((AssistantSettings) -> AssistantSettings) -> Unit) {
    val context = LocalContext.current
    val accounts by ClaudeAccounts.state.collectAsState()
    var picking by rememberSaveable { mutableStateOf<String?>(null) }
    val models = remember { ModelCatalog.options(context) }
    val model = models.firstOrNull { it.value == settings.model }?.displayName ?: settings.model.replaceFirstChar { it.uppercase() }
    Column {
        Caps("CLAUDE", modifier = Modifier.padding(bottom = 4.dp))
        AssistantValueRow("Login", "Status, Allow and Deny don't use it", "${settings.loginLabel(accounts)} ›") { picking = "login" }
        AssistantValueRow("Model", null, "$model ›", last = true) { picking = "model" }
    }
    when (picking) {
        "login" -> PickerDialog("Login", settings.loginChoices(accounts), accounts.get(settings.loginId)?.id, { picking = null }) { id -> change { it.copy(loginId = id) } }
        "model" -> PickerDialog("Model", ModelCatalog.forRocket(context).map { it.value to it.displayName }, settings.model, { picking = null }) { value -> change { it.copy(model = value) } }
    }
}

private const val BEST = "Best on this phone"

/** The voice Rocket speaks in and where it may be heard. Quiet holds back the sample too; Speaker doesn't, since you asked to hear it. */
@Composable
private fun VoiceSection(settings: AssistantSettings, change: ((AssistantSettings) -> AssistantSettings) -> Unit) {
    val context = LocalContext.current
    val c = PrTheme.colors
    var sampleVoice by remember { mutableStateOf<String?>(null) }
    val sample = remember { TtsSpeaker(context) { sampleVoice } }
    var note by remember { mutableStateOf<String?>(null) }
    var picking by rememberSaveable { mutableStateOf(false) }
    DisposableEffect(sample) { onDispose { sample.release() } }

    fun play(voice: String?) {
        if (settings.isQuiet()) {
            note = "Quiet is on, so nothing plays."
            return
        }
        sampleVoice = voice
        note = "Playing…"
        sample.speak(Spoken.SAMPLE, object : Speaker.Listener {
            override fun onStarted() {}
            override fun onDone() { note = null }
            override fun onFailed(reason: String) { note = reason }
        })
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column {
            Caps("VOICE", modifier = Modifier.padding(bottom = 4.dp))
            AssistantValueRow("Voice", "Voices on this phone, never a cloud one", "${Voices.nameOf(settings.voice) ?: BEST} ›", last = true) { picking = true }
        }
        OutlineButton("Play sample", onClick = { play(settings.voice) }, modifier = Modifier.fillMaxWidth())
        note?.takeIf { !picking }?.let { Text(it, style = mockText(12.sp, color = c.textMuted)) }
        Caps("SPOKEN REPLIES GO TO", modifier = Modifier.padding(top = 8.dp))
        Segmented(listOf(false, true), settings.speaker, { if (it) "Speaker too" else "Headphones only" }) { pick -> change { it.copy(speaker = pick) } }
    }
    if (picking) {
        VoicePicker(
            current = settings.voice,
            note = note,
            onPlay = ::play,
            onPick = { voice -> change { it.copy(voice = voice) } },
            onDismiss = { sample.stop(); note = null; picking = false },
        )
    }
}

/** Every installed voice, each with its own Play so they can be compared by ear. Picking doesn't close it, for the same reason. */
@Composable
private fun VoicePicker(current: String?, note: String?, onPlay: (String?) -> Unit, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val c = PrTheme.colors
    var voices by remember { mutableStateOf<List<VoiceOption>?>(null) }
    LaunchedEffect(Unit) { voices = Voices.installed(context) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Voice") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                note?.let { Text(it, style = mockText(12.sp, color = c.textMuted)) }
                val loaded = voices
                when {
                    loaded == null -> Text("Looking for voices…", style = mockText(14.sp, color = c.textSoft))
                    else -> LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        item { VoiceRow(BEST, "The highest-quality on-device voice in your language", current == null, { onPick(null) }, { onPlay(null) }) }
                        items(loaded, key = { it.id }) { voice ->
                            VoiceRow(voice.label, voice.note, voice.id == current, { onPick(voice.id) }, { onPlay(voice.id) })
                        }
                        if (loaded.isEmpty()) item { Text("No on-device voices are installed. Android's text-to-speech settings can add them.", style = mockText(13.sp, color = c.textSoft)) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        containerColor = c.card,
        titleContentColor = c.text,
    )
}

@Composable
private fun VoiceRow(title: String, note: String, on: Boolean, onPick: () -> Unit, onPlay: () -> Unit) {
    val c = PrTheme.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onPick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RadioDot(on)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = mockText(14.sp, color = c.text))
            Text(note, style = mockText(11.sp, color = c.textMuted))
        }
        OutlineButton("Play", onClick = onPlay, fontSize = 13.sp)
    }
}

@Composable
private fun LockedSection(settings: AssistantSettings, change: ((AssistantSettings) -> AssistantSettings) -> Unit) {
    var picking by rememberSaveable { mutableStateOf(false) }
    Column {
        Caps("ON A LOCKED PHONE", modifier = Modifier.padding(bottom = 4.dp))
        AssistantValueRow("Allow, push, Auto mode", "For now it always asks you to unlock first: a spoken yes can't be told apart from anyone else's", "${settings.lockedPhone.label} ›", last = true) { picking = true }
    }
    if (picking) {
        PickerDialog("On a locked phone", LockedPhoneRule.entries.map { it to it.label }, settings.lockedPhone, { picking = false }) { rule -> change { it.copy(lockedPhone = rule) } }
    }
}

/** A title (and a muted line), with content on the right and a hairline under all but the last row. */
@Composable
private fun SettingRow(title: String, note: String?, last: Boolean, onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit) {
    val c = PrTheme.colors
    Column {
        Row(
            Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(role = Role.Button, onClick = onClick) else it }.padding(horizontal = 2.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = mockText(14.sp, color = c.text))
                note?.let { Text(it, style = mockText(12.sp, color = c.textMuted)) }
            }
            trailing()
        }
        if (!last) HorizontalDivider(thickness = 1.dp, color = c.divider)
    }
}

@Composable
private fun ToggleRow(title: String, note: String?, on: Boolean, last: Boolean = false, onChange: (Boolean) -> Unit) =
    SettingRow(title, note, last) { AccentSwitch(on, onChange) }

@Composable
private fun AssistantValueRow(title: String, note: String?, value: String, last: Boolean = false, onClick: () -> Unit) =
    SettingRow(title, note, last, onClick) {
        Text(value, style = mockText(13.sp, color = PrTheme.colors.textMuted), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp))
    }

/** Two choices side by side in a rounded track; the chosen one filled with the text colour. */
@Composable
private fun <T> Segmented(options: List<T>, current: T, label: (T) -> String, onPick: (T) -> Unit) {
    val c = PrTheme.colors
    val track = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().background(c.surface, track).border(1.dp, c.iconTile, track).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { option ->
            val on = option == current
            Box(
                Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(9.dp)).background(if (on) c.text else Color.Transparent)
                    .clickable(role = Role.Button) { onPick(option) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label(option), style = mockText(13.sp, if (on) FontWeight.SemiBold else FontWeight.Normal, if (on) c.background else c.textSoft))
            }
        }
    }
}

/** A short list of choices in a dialog, like Settings' Theme. Picking closes it. */
@Composable
private fun <T> PickerDialog(title: String, options: List<Pair<T, String>>, current: T, onDismiss: () -> Unit, onPick: (T) -> Unit) {
    val c = PrTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (value, label) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(value); onDismiss() }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadioDot(value == current)
                        Text(label, style = mockText(14.sp, color = c.text))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        containerColor = c.card,
        titleContentColor = c.text,
    )
}
