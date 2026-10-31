package com.pocketrocket.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.pocketrocket.assistant.AssistantPrefs
import com.pocketrocket.auth.ClaudeStatuses
import com.pocketrocket.auth.SignInStatus
import com.pocketrocket.profiles.ClaudeAccounts
import com.pocketrocket.ui.accounts.ClaudeAccountScreen
import com.pocketrocket.ui.claudeconfig.AccountMemoryScreen
import com.pocketrocket.ui.claudeconfig.FolderMemoryScreen
import com.pocketrocket.ui.accounts.ClaudeAccountsScreen
import com.pocketrocket.ui.accounts.GitHubAccountsScreen
import com.pocketrocket.ui.accounts.GitHubConnectScreen
import com.pocketrocket.ui.auth.SignInScreen
import com.pocketrocket.ui.files.FileViewerScreen
import com.pocketrocket.ui.files.FilesScreen
import com.pocketrocket.ui.home.HomeScreen
import com.pocketrocket.ui.home.HomeTab
import com.pocketrocket.ui.sessions.NewSessionScreen
import com.pocketrocket.ui.setup.SetupScreen
import com.pocketrocket.ui.settings.AssistantActivityScreen
import com.pocketrocket.ui.settings.AssistantMemoryScreen
import com.pocketrocket.ui.settings.AssistantProjectsScreen
import com.pocketrocket.ui.settings.AssistantScreen
import com.pocketrocket.ui.settings.SensitiveActionsScreen
import com.pocketrocket.ui.settings.ClaudeCodeScreen
import com.pocketrocket.ui.settings.HealthScreen
import com.pocketrocket.ui.settings.LocalOpenHealth
import com.pocketrocket.ui.settings.LocalOpenUsage
import com.pocketrocket.ui.settings.UsageScreen
import androidx.compose.runtime.CompositionLocalProvider
import com.pocketrocket.ui.tools.AndroidBuildToolPage
import com.pocketrocket.ui.tools.AndroidNdkToolPage
import com.pocketrocket.ui.tools.FlutterToolPage
import com.pocketrocket.ui.tools.WranglerToolPage
import com.pocketrocket.ui.tools.ChromiumToolPage
import com.pocketrocket.ui.tools.DockitoToolPage
import com.pocketrocket.ui.tools.NodeToolPage
import com.pocketrocket.ui.tools.ToolsPage
import com.pocketrocket.ui.backup.BackupScreen
import com.pocketrocket.ui.backup.RestoreScreen
import com.pocketrocket.ui.updates.UpdatesScreen
import com.pocketrocket.ui.welcome.WelcomeScreen
import com.pocketrocket.ui.workspaces.CloneScreen
import com.pocketrocket.workspaces.CloneManager
import com.pocketrocket.workspaces.CloneProgress
import com.pocketrocket.ui.workspaces.NewWorkspaceScreen
import com.pocketrocket.ui.workspaces.WorkspaceScreen
import com.pocketrocket.ui.workspaces.WorkspaceSettingsScreen

/** Where the app opens. First run goes Welcome, Setup, SignIn, Home; later launches open Home. */
enum class AppScreen { Welcome, Setup, SignIn, Home }

/**
 * Top-level screens as a small back stack of routes (saved across rotation as one string):
 * `welcome`, `setup`, `signin`, `home` (its tab is kept outside the stack), `claude`, `claude/<id>`, `claude/<id>/signin`,
 * `github`, `github/connect`, `tools`, and the workspace routes in [Routes] (`ws|<path>` and so on).
 * Screens move as [RouteStack] describes: in from the right, back out to the right, predictive back.
 */
@Composable
fun PocketRocketApp(startScreen: AppScreen, onOpenTerminal: () -> Unit, showSessions: Int = 0, showClone: Pair<Int, Int>? = null) {
    var saved by rememberSaveable { mutableStateOf(startScreen.name.lowercase()) }
    val stack = saved.split('\n')
    val scope = rememberCoroutineScope()
    val motion = remember { StackMotion() }
    // Each entry keeps its own saved state (scroll, tab, fields) while it is in the stack; a popped one's is dropped.
    val states = rememberSaveableStateHolder()
    // The Home tab lives outside the route: switching tabs never moves the whole screen.
    val context = LocalContext.current
    val assistant by remember { AssistantPrefs.settings(context) }.collectAsState()
    var pickedTab by rememberSaveable { mutableStateOf(if (assistant.enabled) HomeTab.Rocket else HomeTab.Sessions) }
    // Turning the assistant off while on Rocket lands on Sessions.
    val tab = if (pickedTab == HomeTab.Rocket && !assistant.enabled) HomeTab.Sessions else pickedTab

    fun setStack(routes: List<String>) { saved = routes.ifEmpty { listOf("home") }.joinToString("\n") }
    // Callbacks read the stack when they run, never a copy from an older composition.
    fun current() = saved.split('\n')
    fun push(r: String) {
        val now = current()
        motion.entering = entryKey(now.size, r)
        setStack(now + r)
    }
    fun replace(r: String) = setStack(current().dropLast(1) + r)
    /** Drops the top entry now (the slide out already happened). */
    fun dropTop() {
        val now = current()
        if (now.size < 2) return
        states.removeState(entryKey(now.lastIndex, now.last()))
        setStack(now.dropLast(1))
    }
    /** Back: the top screen slides out to the right, then leaves the stack. */
    fun pop() { if (stack.size > 1) scope.launch { motion.popWith(::dropTop) } }

    // Each bump of [showSessions] (a tap on the service notification) goes back to Home's Sessions tab.
    LaunchedEffect(showSessions) {
        if (showSessions == 0 || current().first() != "home") return@LaunchedEffect
        current().indices.drop(1).forEach { states.removeState(entryKey(it, current()[it])) }
        setStack(listOf("home"))
        pickedTab = HomeTab.Sessions
    }

    // A tap on a clone notification (job id, tap count): the repo's workspace once it landed, else the clone screen.
    LaunchedEffect(showClone) {
        val id = showClone?.first ?: return@LaunchedEffect
        if (current().first() != "home") return@LaunchedEffect
        val job = CloneManager.jobs.value.firstOrNull { it.id == id }
        val done = job?.progress as? CloneProgress.Done
        val route = when {
            done?.workspace != null -> Routes.workspace(done.workspace).also { CloneManager.dismiss(id) }
            job != null -> Routes.clone(job.request.parent)
            else -> null
        } ?: return@LaunchedEffect
        val now = current().filterNot { it.startsWith("clone|") }
        if (now.last() != route) setStack(now + route) else setStack(now)
    }

    CompositionLocalProvider(LocalOpenHealth provides { push(Routes.HEALTH) }, LocalOpenUsage provides { push(Routes.USAGE) }) {
    RouteStack(stack, motion, states, onGestureBack = ::dropTop) { r ->
        val parts = r.split('/')
        val args = r.split('|')
        when {
            r == "welcome" -> WelcomeScreen(onStart = { replace("setup") })
            r == "setup" -> SetupScreen(onFinished = { replace("signin") })
            r == "signin" -> SignInScreen(onDone = { replace("home") }, onDismiss = { replace("home") }, dismissLabel = "Not now")
            args[0] == "ws" -> WorkspaceScreen(args[1], onBack = ::pop, navigate = ::push)
            // The rest of the route is the path, whatever it holds.
            args[0] == "files" -> FilesScreen(
                r.substringAfter('|'),
                onBack = ::pop,
                navigate = ::push,
                // Screens below follow a folder renamed or deleted here; this one keeps its place.
                onMoved = { from, to -> current().let { now -> setStack(now.dropLast(1).map { Routes.moved(it, from, to) } + now.last()) } },
                onDeleted = { gone, here ->
                    val below = current().dropLast(1)
                    val kept = below.filterNot { Routes.inside(it, gone) }
                    // Dropping a screen below re-keys this one, so it reopens where it was.
                    if (kept.size != below.size) setStack(kept + Routes.files(here))
                },
            )
            args[0] == "file" -> FileViewerScreen(r.substringAfter('|'), onBack = ::pop)
            args[0] == "memory-account" -> AccountMemoryScreen(args[1], onBack = ::pop)
            args[0] == "memory-folder" -> FolderMemoryScreen(args[1], onBack = ::pop)
            args[0] == "ws-settings" -> WorkspaceSettingsScreen(
                args[1],
                onBack = ::pop,
                onOpenMemory = { push(Routes.folderMemory(args[1])) },
                onRemoved = { pickedTab = HomeTab.Workspaces; setStack(stack.filterNot { it.endsWith("|" + args[1]) }) },
                onMoved = { to -> setStack(stack.map { it.replace("|" + args[1], "|$to") }) },
            )
            args[0] == Routes.NEW_WORKSPACE -> NewWorkspaceScreen(
                initialFolder = args.getOrNull(1),
                onClose = ::pop,
                onCreated = { ws, clone -> setStack(stack.dropLast(1) + Routes.workspace(ws) + listOfNotNull(if (clone) Routes.clone(ws) else null)) },
            )
            args[0] == "clone" -> CloneScreen(args[1], onBack = ::pop, onCloned = { ws ->
                val below = stack.dropLast(1)
                setStack(if (below.lastOrNull() == Routes.workspace(ws)) below else below + Routes.workspace(ws))
            })
            args[0] == "session-new" -> NewSessionScreen(args.getOrElse(1) { "" }, resume = args.getOrNull(2) == "resume", onClose = ::pop, onStarted = ::pop)
            r == Routes.TOOLS -> ToolsPage(onBack = ::pop, onOpen = { push(it.route) })
            r == Routes.TOOL_NODE -> NodeToolPage(onBack = ::pop)
            r == Routes.TOOL_DOCKITO -> DockitoToolPage(onBack = ::pop)
            r == Routes.TOOL_CHROMIUM -> ChromiumToolPage(onBack = ::pop)
            r == Routes.TOOL_ANDROID_BUILD -> AndroidBuildToolPage(onBack = ::pop)
            r == Routes.TOOL_ANDROID_NDK -> AndroidNdkToolPage(onBack = ::pop, onOpenAndroid = { push(Routes.TOOL_ANDROID_BUILD) })
            r == Routes.TOOL_WRANGLER -> WranglerToolPage(onBack = ::pop)
            r == Routes.TOOL_FLUTTER -> FlutterToolPage(onBack = ::pop)
            r == Routes.CLAUDE_CODE -> ClaudeCodeScreen(onBack = ::pop)
            r == Routes.UPDATES -> UpdatesScreen(onBack = ::pop, onClaudeCode = { push(Routes.CLAUDE_CODE) })
            r == Routes.BACKUP -> BackupScreen(onBack = ::pop, onRestore = { push(Routes.RESTORE) })
            r == Routes.RESTORE -> RestoreScreen(onBack = ::pop)
            r == Routes.ASSISTANT -> AssistantScreen(onBack = ::pop, navigate = ::push)
            r == Routes.ASSISTANT_ACTIVITY -> AssistantActivityScreen(onBack = ::pop)
            r == Routes.ASSISTANT_SENSITIVE -> SensitiveActionsScreen(onBack = ::pop)
            r == Routes.ASSISTANT_MEMORY -> AssistantMemoryScreen(onBack = ::pop)
            r == Routes.ASSISTANT_PROJECTS -> AssistantProjectsScreen(onBack = ::pop)
            r == Routes.HEALTH -> HealthScreen(onBack = ::pop, navigate = ::push)
            r == Routes.USAGE -> UsageScreen(onBack = ::pop)
            parts[0] == "home" -> HomeScreen(
                tab = tab,
                onTab = { pickedTab = it },
                navigate = ::push,
                onOpenTerminal = onOpenTerminal,
                onClaudeAccounts = { push("claude") },
                onGitHubAccounts = { push("github") },
                onTools = { push(Routes.TOOLS) },
                onUpdates = { push(Routes.UPDATES) },
                rocket = assistant.enabled,
                onBackup = { push(Routes.BACKUP) },
            )
            r == "claude" -> ClaudeAccountsScreen(
                onBack = ::pop,
                onOpen = { push("claude/$it") },
                onAdded = { saved = (stack + "claude/$it" + "claude/$it/signin").joinToString("\n") },
            )
            parts[0] == "claude" && parts.size == 2 -> ClaudeAccountScreen(parts[1], onBack = ::pop, onSignIn = { push("claude/$it/signin") }, onOpenMemory = { push(Routes.accountMemory(parts[1])) })
            parts[0] == "claude" && parts.size == 3 -> {
                val account = ClaudeAccounts.get(parts[1])
                SignInScreen(
                    onDone = {
                        ClaudeStatuses.set(parts[1], SignInStatus.Checking)
                        pop()
                    },
                    onDismiss = ::pop,
                    dismissLabel = "Back",
                    configDir = account?.configDir,
                )
            }
            r == "github" -> GitHubAccountsScreen(onBack = ::pop, onConnect = { push("github/connect") })
            r == "github/connect" -> GitHubConnectScreen(onBack = ::pop, onDone = ::pop)
            else -> HomeScreen(tab, { pickedTab = it }, ::push, onOpenTerminal, { push("claude") }, { push("github") }, { push(Routes.TOOLS) }, { push(Routes.UPDATES) }, assistant.enabled)
        }
    }
    }
}
