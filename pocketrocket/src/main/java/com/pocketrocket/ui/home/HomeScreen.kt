package com.pocketrocket.ui.home

import com.pocketrocket.ui.Routes
import com.pocketrocket.ui.theme.PrTheme
import androidx.compose.animation.Crossfade
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketrocket.ui.components.MockIcons
import com.pocketrocket.ui.rocket.RocketScreen
import com.pocketrocket.ui.settings.SettingsScreen
import com.pocketrocket.ui.components.mockText
import com.pocketrocket.ui.workspaces.WorkspacesScreen

/** The app's main destinations, shown in the bottom bar (decision 18, mockup Main / Workspaces / Settings). Rocket is first, while the assistant is on (decision 52). */
enum class HomeTab(val label: String, val icon: ImageVector) {
    Rocket("Rocket", MockIcons.Rocket),
    Sessions("Sessions", MockIcons.Grid),
    Workspaces("Workspaces", MockIcons.Folder),
    Settings("Settings", MockIcons.Gear),
}

/**
 * Home: the tabs and the mockup's bottom bar. The bar stays put; only the content area changes,
 * with a short cross-fade. Each tab keeps its own saved state (scroll position, open group, selected
 * project tab) across switches; tapping the current tab again resets it to its root (top, fresh state).
 * [tab] is held by the caller, outside the route, so it survives going into a screen and back.
 * [navigate] pushes a route (see `Routes`). [rocket] says whether the assistant's tab is shown; the caller moves [tab]
 * off it when it goes. The keyboard only opens on Rocket, whose box rides above it in place of the bar.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    tab: HomeTab,
    onTab: (HomeTab) -> Unit,
    navigate: (String) -> Unit,
    onOpenTerminal: () -> Unit,
    onClaudeAccounts: () -> Unit,
    onGitHubAccounts: () -> Unit,
    onTools: () -> Unit,
    onUpdates: () -> Unit = {},
    rocket: Boolean = true,
    onBackup: () -> Unit = {},
) {
    val c = PrTheme.colors
    val tabStates = rememberSaveableStateHolder()
    // Bumped when the current tab is tapped again: a new key gives that tab a fresh, root state.
    var generations by rememberSaveable { mutableStateOf(mapOf<String, Int>()) }
    fun key(t: HomeTab) = "${t.name}#${generations[t.name] ?: 0}"
    // The content colour a Scaffold/Surface would set; without it Text falls back to black on the dark page.
    CompositionLocalProvider(LocalContentColor provides c.text) {
    val typing = tab == HomeTab.Rocket && WindowInsets.isImeVisible
    Column(Modifier.fillMaxSize().background(c.background).then(if (tab == HomeTab.Rocket) Modifier.imePadding() else Modifier)) {
        Crossfade(tab, animationSpec = tween(120), modifier = Modifier.weight(1f).statusBarsPadding(), label = "tab") { shown ->
            tabStates.SaveableStateProvider(key(shown)) {
                when (shown) {
                    HomeTab.Rocket -> RocketScreen(navigate)
                    HomeTab.Sessions -> SessionsScreen(navigate, onOpenTerminal)
                    HomeTab.Workspaces -> WorkspacesScreen(navigate)
                    HomeTab.Settings -> SettingsScreen(onClaudeAccounts, onGitHubAccounts, onTools, onUpdates, onBackup, onAssistant = { navigate(Routes.ASSISTANT) })
                }
            }
        }
        if (!typing) BottomNav(tab, HomeTab.entries.filter { rocket || it != HomeTab.Rocket }) { picked ->
            if (picked == tab) {
                tabStates.removeState(key(picked))
                generations = generations + (picked.name to (generations[picked.name] ?: 0) + 1)
            } else onTab(picked)
        }
    }
    }
}

/** 76 dp, #141821, a top border, one equal column per tab: 20 dp icon over an 11 sp label, orange when selected. */
@Composable
private fun BottomNav(tab: HomeTab, tabs: List<HomeTab>, onTab: (HomeTab) -> Unit) {
    val c = PrTheme.colors
    Column(Modifier.fillMaxWidth().background(c.bar).navigationBarsPadding()) {
        HorizontalDivider(thickness = 1.dp, color = c.border)
        Row(Modifier.fillMaxWidth().height(75.dp)) {
            tabs.forEach { item ->
                val color = if (item == tab) c.accent else c.textMuted
                Column(
                    Modifier.weight(1f).fillMaxHeight().clickable(
                        interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = { onTab(item) },
                    ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                ) {
                    Icon(item.icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
                    Text(item.label, style = mockText(11.sp, color = color))
                }
            }
        }
    }
}
