package com.pocketrocket.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.pocketrocket.health.Health
import com.pocketrocket.health.HealthFix
import com.pocketrocket.health.HealthItem
import com.pocketrocket.health.HealthReport
import com.pocketrocket.health.HealthState
import com.pocketrocket.health.LastStop
import com.pocketrocket.health.SystemScreen
import com.pocketrocket.health.SystemScreens
import com.pocketrocket.ui.components.MockIcons
import com.pocketrocket.ui.git.copyText
import com.pocketrocket.ui.theme.PrTheme
import com.pocketrocket.ui.theme.PrType

/**
 * Opens Settings › Health. Provided by the app's navigation (PocketRocketApp) so Settings can reach the
 * screen without a new parameter through Home.
 */
val LocalOpenHealth = staticCompositionLocalOf<() -> Unit> { {} }

/** The banner's border in the mockup (#5A4520); the light theme uses its amber border token. */
private val BannerBorderDark = Color(0xFF5A4520)

/**
 * Settings › Health (R15, decision 22), as docs/ux-mockup/Health.dc.html: the last-stop banner, then each
 * group of checks with a dot (green ok, amber needs attention, grey can't check), a note and the Fix or
 * Show me button. Check again re-runs everything off the main thread; coming back from a system screen
 * re-reads the local checks.
 */
@Composable
fun HealthScreen(onBack: () -> Unit, navigate: (String) -> Unit) {
    val context = LocalContext.current
    val c = PrTheme.colors
    val report by Health.report.collectAsState()
    val checking by Health.checking.collectAsState()
    var guide by remember { mutableStateOf<HealthFix.Guide?>(null) }

    LaunchedEffect(Unit) { Health.check(context, ifOlderThanMs = 20_000) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        var first = true
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (!first) Health.refreshLocal(context)
                first = false
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    CompositionLocalProvider(LocalContentColor provides c.text) {
        Column(Modifier.fillMaxSize().background(c.background).systemBarsPadding()) {
            TopBar(checking, onBack) { Health.check(context) }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 6.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                val r = report
                if (r == null) {
                    Text("Checking…", style = PrType.small.copy(color = c.textMuted))
                } else {
                    r.lastStop?.let { Banner(it, r) }
                    r.groups.forEach { group ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                group.title,
                                style = PrType.micro.copy(fontWeight = FontWeight.SemiBold, color = c.textMuted, letterSpacing = 0.5.sp),
                                modifier = Modifier.padding(bottom = 2.dp),
                            )
                            group.items.forEach { item ->
                                CheckRow(item) { fix -> runFix(context, fix, navigate) { guide = it } }
                            }
                        }
                    }
                    if (r.groups.none { it.title == "ACCOUNTS" }) {
                        Text("Checking setup and accounts…", style = PrType.caption.copy(color = c.textMuted))
                    }
                }
            }
        }
    }

    guide?.let { g -> GuideDialog(g, onOpen = { g.screen?.let { SystemScreens.open(context, it) } }, onDismiss = { guide = null }) }
}

/** A settings page's back button and title, with room for an action on the right. */
@Composable
internal fun SettingsTopBar(title: String, onBack: () -> Unit, action: @Composable () -> Unit = {}) {
    val c = PrTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 18.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
            Icon(MockIcons.Back, contentDescription = "Back", tint = c.text, modifier = Modifier.size(22.dp))
        }
        Text(title, style = PrType.title.copy(fontSize = 17.sp, color = c.text), modifier = Modifier.weight(1f))
        action()
    }
}

@Composable
private fun TopBar(checking: Boolean, onBack: () -> Unit, onCheck: () -> Unit) {
    val c = PrTheme.colors
    SettingsTopBar("Health", onBack) {
        Box(
            Modifier.padding(end = 8.dp).height(36.dp).clip(RoundedCornerShape(10.dp))
                .border(1.dp, c.border, RoundedCornerShape(10.dp))
                .clickable(enabled = !checking, onClick = onCheck).padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (checking) "Checking…" else "Check again", style = PrType.caption.copy(color = c.textSoft))
        }
    }
}

/** "Sessions were stopped last night" and the sentence under it. */
@Composable
private fun Banner(stop: LastStop.Stop, report: HealthReport) {
    val context = LocalContext.current
    val c = PrTheme.colors
    val shape = RoundedCornerShape(14.dp)
    val fixes = report.keepAliveAttention
    val tail = when {
        stop.byUser -> ""
        fixes == 1 -> " Fix the item below to stop this."
        fixes > 1 -> " Fix the ${listOf("two", "three", "four", "five", "six").getOrElse(fixes - 2) { "$fixes" }} items below to stop this."
        else -> ""
    }
    Column(
        Modifier.fillMaxWidth().clip(shape).background(c.amberSurface, shape)
            .border(1.dp, if (c.isDark) BannerBorderDark else c.amberBorder, shape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        val text = PrType.small.copy(lineHeight = (13 * 1.45).sp)
        Text(LastStop.title(stop), style = text.copy(fontWeight = FontWeight.SemiBold, color = c.amberText))
        Text(LastStop.sentence(context, stop) + tail, style = text.copy(color = c.textSoft))
    }
}

@Composable
internal fun CheckRow(item: HealthItem, onFix: (HealthFix) -> Unit) {
    val c = PrTheme.colors
    val shape = RoundedCornerShape(12.dp)
    val dot = when (item.state) {
        HealthState.Ok -> c.green
        HealthState.NeedsAttention -> c.amber
        HealthState.CantCheck, HealthState.Optional -> c.textMuted
    }
    Row(
        Modifier.fillMaxWidth().clip(shape).background(c.surface, shape).border(1.dp, c.iconTile, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(10.dp).background(dot, CircleShape))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.name, style = PrType.body.copy(fontWeight = FontWeight.Medium, color = c.text, lineHeight = 18.sp))
            Text(item.note, style = PrType.caption.copy(color = c.textMuted))
        }
        // Ok rows have no button, as in the mockup.
        val fix = item.fix
        if (fix != null && item.state != HealthState.Ok) {
            Box(
                Modifier.height(34.dp).clip(RoundedCornerShape(9.dp)).background(c.amber).clickable { onFix(fix) }.padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(fix.label, style = PrType.caption.copy(fontWeight = FontWeight.SemiBold, color = c.onAmber))
            }
        }
    }
}

@Composable
internal fun GuideDialog(guide: HealthFix.Guide, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val c = PrTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(guide.title, style = PrType.title.copy(color = c.text)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                guide.steps.forEach { step ->
                    if (step.startsWith("adb ")) CommandRow(step)
                    else Text(step, style = PrType.small.copy(color = c.textSoft, lineHeight = 19.sp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = guide.screen?.let { { TextButton(onClick = { onOpen(); onDismiss() }) { Text(guide.screenLabel) } } },
        containerColor = c.card,
        titleContentColor = c.text,
    )
}

/** A command from a guide; tapping it copies it, to paste where it runs. */
@Composable
private fun CommandRow(command: String) {
    val c = PrTheme.colors
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c.raised)
            .clickable { copyText(context, command, "command") }.padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(command, style = PrType.codeSmall.copy(color = c.text), modifier = Modifier.weight(1f))
        Icon(MockIcons.Copy, contentDescription = "Copy", tint = c.textMuted, modifier = Modifier.size(18.dp))
    }
}

private const val PREFS = "pocketrocket_health"

private fun runFix(context: Context, fix: HealthFix, navigate: (String) -> Unit, showGuide: (HealthFix.Guide) -> Unit) {
    when (fix) {
        is HealthFix.Open -> SystemScreens.open(context, fix.screen)
        is HealthFix.Navigate -> navigate(fix.route)
        is HealthFix.Guide -> showGuide(fix)
        is HealthFix.Request -> {
            // Ask the first time; once Android stops showing its prompt, go to the app's permissions instead.
            val activity = context.findActivity()
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val key = "asked_${fix.permission}"
            val asked = prefs.getBoolean(key, false)
            if (activity != null && (!asked || activity.shouldShowRequestPermissionRationale(fix.permission))) {
                prefs.edit().putBoolean(key, true).apply()
                activity.requestPermissions(arrayOf(fix.permission), 0x4EA1)
            } else {
                SystemScreens.open(context, SystemScreen.AppDetails)
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
