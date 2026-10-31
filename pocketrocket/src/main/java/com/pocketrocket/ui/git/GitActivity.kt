package com.pocketrocket.ui.git

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import com.pocketrocket.ui.theme.PocketRocketTheme
import com.pocketrocket.ui.theme.rememberDarkTheme

/**
 * Hosts the git viewer for one repo: the panel, commit details, diffs and file browsing, as a small
 * back stack. Its own activity so any screen (a session today, a workspace in Phase B) can open it
 * through [GitScreens.open]. The panel refreshes each time the activity comes back to the front.
 */
class GitActivity : ComponentActivity() {

    private val model: GitPanelModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_REPO)
        if (path == null) { finish(); return }
        model.open(path)
        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_BRANCHES, false)) model.showBranches = true

        setContent {
            val dark = rememberDarkTheme()
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            val stack = remember { mutableStateListOf<GitRoute>(GitRoute.Panel).apply { if (intent.getBooleanExtra(EXTRA_BROWSE, false)) add(GitRoute.Browse("")) } }
            val push: (GitRoute) -> Unit = { stack.add(it) }
            val back: () -> Unit = { if (stack.size > 1) stack.removeAt(stack.lastIndex) else finish() }
            BackHandler(enabled = stack.size > 1) { back() }

            PocketRocketTheme(darkTheme = dark) {
                when (val route = stack.last()) {
                    GitRoute.Panel -> GitPanelScreen(model, onBack = back, onOpen = push)
                    is GitRoute.CommitDetail -> CommitScreen(model, route.commit, onBack = back, onOpen = push)
                    is GitRoute.Diff -> DiffScreen(model, route.source, route.title, onBack = back)
                    is GitRoute.Browse -> BrowseScreen(model, route.dir, onBack = back, onOpen = push)
                    is GitRoute.File -> FileScreen(model, route.path, onBack = back)
                    is GitRoute.Pr -> PrScreen(model, route.number, onBack = back, onOpen = push)
                    is GitRoute.PrDiff -> PrDiffScreen(model, route.number, route.path, onBack = back)
                }
            }
        }
    }

    override fun onRestart() {
        super.onRestart()
        model.refresh()
    }

    companion object {
        const val EXTRA_REPO = "repo_path"
        /** Open on the file browser (over the panel) instead of the panel. */
        const val EXTRA_BROWSE = "browse"
        /** Open with the branch sheet up. */
        const val EXTRA_BRANCHES = "branches"
    }
}
