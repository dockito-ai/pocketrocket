package com.pocketrocket.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.pocketrocket.health.HealthFix
import com.pocketrocket.health.HealthItem
import com.pocketrocket.health.HealthState
import com.pocketrocket.health.SystemScreen
import com.pocketrocket.health.SystemScreens
import com.pocketrocket.ui.theme.PrTheme
import com.pocketrocket.ui.theme.PrType
import com.pocketrocket.usage.BatteryReading
import com.pocketrocket.usage.BatteryUsage
import com.pocketrocket.usage.PhoneShare
import com.pocketrocket.usage.RamGroup
import com.pocketrocket.usage.RamReport
import com.pocketrocket.usage.RamUsage
import com.pocketrocket.usage.StorageBreakdown
import com.pocketrocket.usage.StorageKind
import com.pocketrocket.usage.StorageUsage
import com.pocketrocket.usage.UsageText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Opens Settings › Usage, provided by the app's navigation like [LocalOpenHealth]. */
val LocalOpenUsage = staticCompositionLocalOf<() -> Unit> { {} }

private const val RAM_REFRESH_MS = 5_000L

/**
 * Settings › Usage: the app's storage, memory and battery. Storage is walked once per visit, and its rows open in
 * place to what's inside ([StorageRow]). Memory is read
 * every few seconds and battery once, only while the screen is resumed, so nothing runs in the background
 * and coming back from granting or revoking shows the new battery state.
 */
@Composable
fun UsageScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val c = PrTheme.colors
    var total by remember { mutableStateOf<Long?>(null) }
    var storage by remember { mutableStateOf<StorageBreakdown?>(null) }
    var phoneStorage by remember { mutableStateOf<PhoneShare?>(null) }
    var ram by remember { mutableStateOf<RamReport?>(null) }
    var battery by remember { mutableStateOf<BatteryReading?>(null) }
    var guide by remember { mutableStateOf<HealthFix.Guide?>(null) }
    val scope = rememberCoroutineScope()
    // Keyed on the one walk per visit, so RAM and battery refreshes leave open rows alone.
    val treeState = remember(storage) { storage?.let { StorageTreeState(StorageUsage.tree(context, it), scope) } }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            phoneStorage = StorageUsage.phone(context)
            total = StorageUsage.totalBytes(context)
            storage = StorageUsage.breakdown(context)
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            battery = BatteryUsage.read(context)
            while (true) {
                ram = withContext(Dispatchers.IO) { RamUsage.read(context) }
                delay(RAM_REFRESH_MS)
            }
        }
    }

    CompositionLocalProvider(LocalContentColor provides c.text) {
        Column(Modifier.fillMaxSize().background(c.background).systemBarsPadding()) {
            SettingsTopBar("Usage", onBack)
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 6.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item(key = "storage-title") { GroupTitle("STORAGE") }
                phoneStorage?.let { phone ->
                    item(key = "storage-phone") { PhoneBar(phone, storage?.totalBytes ?: total, UsageText::storage) }
                }
                item(key = "storage-total") { ValueRow("Total", (storage?.totalBytes ?: total)?.let(UsageText::storage) ?: "Counting…", "Everything Android counts for this app", trailing = { Chevron(shown = false, open = false) }) }
                val state = treeState
                if (state == null) {
                    items(StorageKind.entries, key = { it.name }) { kind -> ValueRow(kind.label, "Counting…", kind.note, trailing = { Chevron(shown = false, open = false) }) }
                } else {
                    items(state.rows(), key = { it.node.id }) { row -> StorageRow(row, state) }
                }
                if ((storage?.unreadableFolders ?: 0) > 0) {
                    item(key = "storage-unreadable") {
                        Text("Some folders couldn’t be read, so the split may be a little low.", style = PrType.caption.copy(color = c.textMuted))
                    }
                }
                item(key = "ram") { RamGroupView(ram, Modifier.padding(top = 14.dp)) }
                item(key = "battery") { BatteryGroup(battery, ram, Modifier.padding(top = 14.dp)) { guide = it } }
            }
        }
    }

    guide?.let { g -> GuideDialog(g, onOpen = { g.screen?.let { SystemScreens.open(context, it) } }, onDismiss = { guide = null }) }
}

@Composable
private fun RamGroupView(ram: RamReport?, modifier: Modifier = Modifier) {
    val c = PrTheme.colors
    Group("RAM", modifier) {
        if (ram == null) {
            ValueRow("Total", "Reading…", "")
            return@Group
        }
        ram.phone?.let { PhoneBar(it, ram.totalKb * 1024) { bytes -> UsageText.ram(bytes / 1024) } }
        val rssNote = when (ram.rssProcesses) {
            0 -> "Right now, sharing counted fairly (PSS)"
            else -> "Right now. ${ram.rssProcesses} measured by RSS, which reads high"
        }
        ValueRow("Total", UsageText.ram(ram.totalKb), rssNote)
        ram.sessions.forEach { s -> ValueRow(s.title.ifBlank { "Session" }, UsageText.ram(s.ram.kb), processes(s.ram) + ", child tools included") }
        ValueRow("PocketRocket", UsageText.ram(ram.app.kb), processes(ram.app))
        if (ram.other.processes > 0) ValueRow("Terminals and other tools", UsageText.ram(ram.other.kb), processes(ram.other))
        if (ram.sessions.isEmpty()) Text("No sessions running.", style = PrType.caption.copy(color = c.textMuted))
    }
}

private fun processes(group: RamGroup) = "${group.processes} ${if (group.processes == 1) "process" else "processes"}"

@Composable
private fun BatteryGroup(battery: BatteryReading?, ram: RamReport?, modifier: Modifier = Modifier, showGuide: (HealthFix.Guide) -> Unit) {
    val context = LocalContext.current
    val c = PrTheme.colors
    Group("BATTERY", modifier) {
        when (battery) {
            null -> ValueRow("Battery use", "Reading…", "")
            is BatteryReading.Figure -> ValueRow("Battery use", UsageText.mah(battery.mah), UsageText.batteryNote(battery))
            is BatteryReading.NothingRecorded -> ValueRow("Battery use", "None recorded", "Android has nothing for this app ${UsageText.period(battery.onBattery)}")
            BatteryReading.Unreadable -> ValueRow("Battery use", "Not available", "Android’s battery report couldn’t be read on this phone")
            BatteryReading.NeedsGrant -> CheckRow(
                HealthItem("Battery use", HealthState.Optional, "Android shares this only after a one-time grant over adb", BatteryUsage.guide(context)),
            ) { fix -> (fix as? HealthFix.Guide)?.let(showGuide) }
        }
        ValueRow("CPU time", ram?.let { UsageText.duration(it.cpuMs) } ?: "Reading…", "Live processes, since sessions started. Not a battery figure")
        Box(
            Modifier.padding(top = 4.dp).height(36.dp).clip(RoundedCornerShape(10.dp))
                .border(1.dp, c.border, RoundedCornerShape(10.dp))
                .clickable { SystemScreens.open(context, SystemScreen.AppDetails) }.padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("App details", style = PrType.caption.copy(color = c.textSoft))
        }
    }
}

@Composable
private fun Group(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        GroupTitle(title)
        content()
    }
}

/** The phone's figures and the app's part of them: a thin bar, used in a quiet tone with the app's part in the accent. */
@Composable
private fun PhoneBar(phone: PhoneShare, mine: Long?, format: (Long) -> String) {
    val c = PrTheme.colors
    Column(Modifier.padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(UsageText.phoneLine(phone, mine, format), style = PrType.caption.copy(color = c.textSoft))
        Canvas(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))) {
            drawRect(c.iconTile)
            drawRect(c.textFaint, size = Size(size.width * phone.usedFraction(), size.height))
            // Always a sliver, so a small share still shows.
            if (mine != null) drawRect(c.accent, size = Size((size.width * phone.partFraction(mine)).coerceAtLeast(2.dp.toPx()), size.height))
        }
    }
}

@Composable
private fun GroupTitle(title: String) {
    Text(
        title,
        style = PrType.micro.copy(fontWeight = FontWeight.SemiBold, color = PrTheme.colors.textMuted, letterSpacing = 0.5.sp),
        modifier = Modifier.padding(bottom = 2.dp),
    )
}

/** A Health-style row with a figure on the right instead of a dot and a button. Tappable with [onClick]; [trailing] sits after the figure. */
@Composable
internal fun ValueRow(
    name: String,
    value: String,
    note: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = PrTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier.fillMaxWidth().clip(shape).background(c.surface, shape).border(1.dp, c.iconTile, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, style = PrType.body.copy(fontWeight = FontWeight.Medium, color = c.text, lineHeight = 18.sp), maxLines = 2)
            if (note.isNotEmpty()) Text(note, style = PrType.caption.copy(color = c.textMuted))
        }
        Text(value, style = PrType.body.copy(fontWeight = FontWeight.Medium, color = c.textSoft, lineHeight = 18.sp))
        trailing?.invoke()
    }
}
