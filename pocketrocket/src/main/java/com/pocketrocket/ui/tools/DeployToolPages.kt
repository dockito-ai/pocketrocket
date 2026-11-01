package com.pocketrocket.ui.tools

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.pocketrocket.clilogin.CliLogins
import com.pocketrocket.clitools.NpmTool
import com.pocketrocket.clitools.NpmTools
import com.pocketrocket.updates.UpdateComponent
import com.pocketrocket.updates.Updates

/*
 * The deploy CLIs' pages (decision 98): Vercel, Netlify and Firebase from npm, the AWS CLI and flyctl from Termux's
 * packages. Each has Log in and Log out on its page, through the shared login flow ([CliAccountCard]).
 */

@Composable
fun VercelToolPage(onBack: () -> Unit) = NpmToolPage(
    tool = Tool.Vercel,
    purpose = "Deploy sites and apps to Vercel.",
    service = "Vercel",
    why = "Deploying needs it. Log in opens Vercel in the browser; allow it there and come back.",
    removeNote = "You're signed out of Vercel on this phone. Your projects stay, and so do your deployments.",
    onBack = onBack,
) {
    ToolCard("HOW TO USE") {
        ToolText("In a terminal or a Claude session, run vercel in a project to deploy a preview, and vercel --prod for production. vercel dev runs it on the phone.")
    }
}

@Composable
fun NetlifyToolPage(onBack: () -> Unit) = NpmToolPage(
    tool = Tool.Netlify,
    purpose = "Deploy sites and functions to Netlify.",
    service = "Netlify",
    why = "Deploying needs it. Log in opens Netlify in the browser; allow it there and come back.",
    removeNote = "You're signed out of Netlify on this phone. Your projects stay, and so do your sites.",
    onBack = onBack,
) {
    ToolCard("HOW TO USE") {
        ToolText("In a terminal or a Claude session, run netlify deploy in a project for a draft, and netlify deploy --prod to publish it.")
    }
}

@Composable
fun FirebaseToolPage(onBack: () -> Unit) = NpmToolPage(
    tool = Tool.Firebase,
    purpose = "Deploy to Firebase Hosting, Functions and the rest.",
    service = "Firebase",
    why = "Deploying needs it. Log in opens Google in the browser; it shows a code to paste back here.",
    removeNote = "You're signed out of Firebase on this phone. Your projects stay, and so does everything on Firebase.",
    onBack = onBack,
) {
    ToolCard("HOW TO USE") {
        ToolText("In a terminal or a Claude session, run firebase init in a project, then firebase deploy. The emulators that need Java aren't set up for the phone.")
    }
}

@Composable
fun AwsToolPage(onBack: () -> Unit) {
    val login = CliLogins.of(requireNotNull(Tool.Aws.login))
    PackageToolPage(
        tool = Tool.Aws,
        purpose = "Work with AWS from the command line.",
        onBack = onBack,
        removeNote = "You're signed out, and its settings in ~/.aws go too. Your AWS resources stay.",
        accounts = { s, busy ->
            CliAccountCard(
                "AWS", login, s, blocked = busy,
                why = "Log in uses your AWS console sign-in (aws login): it opens AWS in the browser and shows a code to paste back here. " +
                    "Without a region set it uses us-east-1; change it with aws configure set region.",
            )
        },
    ) {
        ToolCard("WHAT IT DOES") {
            ToolBullets(listOf("aws s3, aws lambda and the rest of the AWS CLI v2.", "Works in terminals and Claude sessions."))
        }
        ToolCard("WHAT IT IS") {
            ToolText("Termux's awscli package, updated from Settings › Updates. Access keys and SSO set up with aws configure work too.")
        }
    }
}

@Composable
fun FlyToolPage(onBack: () -> Unit) {
    val login = CliLogins.of(requireNotNull(Tool.Fly.login))
    PackageToolPage(
        tool = Tool.Fly,
        purpose = "Deploy and run apps on Fly.io.",
        onBack = onBack,
        removeNote = "You're signed out of Fly.io on this phone, and ~/.fly goes. Your apps on Fly.io stay.",
        accounts = { s, busy ->
            CliAccountCard("Fly.io", login, s, blocked = busy, why = "Deploying needs it. Log in opens Fly.io in the browser; allow it there and come back.")
        },
    ) {
        ToolCard("WHAT IT DOES") {
            ToolBullets(listOf("fly launch, fly deploy, fly logs and the rest of flyctl.", "Works in terminals and Claude sessions."))
        }
        ToolCard("WHAT IT IS") {
            ToolText("Termux's flyctl package, updated from Settings › Updates. Builds run on Fly's remote builders, not on the phone.")
        }
    }
}

/**
 * An npm deploy CLI's page (decision 98): the shared [ToolPage] with Install or Uninstall through [NpmTools], its version
 * from npm's folder, Repair when its command is missing, and its login.
 */
@Composable
private fun NpmToolPage(
    tool: Tool,
    purpose: String,
    service: String,
    why: String,
    removeNote: String,
    onBack: () -> Unit,
    howTo: @Composable ColumnScope.() -> Unit,
) {
    val npm = requireNotNull(tool.npm) { "${tool.name} isn't an npm tool" }
    val login = CliLogins.of(npm.login)
    val context = LocalContext.current
    val s by NpmTools.state.collectAsState()
    val ls by login.state.collectAsState()
    val updates by Updates.state.collectAsState()
    val component = UpdateComponent.of(npm)
    LaunchedEffect(Unit) {
        NpmTools.load(context)
        Updates.onAppOpen(context, checkAfterMs = 10 * 60_000L)
    }
    val installed = s.installed(npm)
    LaunchedEffect(installed) { login.refreshAccount(context, installed) }
    val mine = s.isFor(npm)
    // Its update runs as its Version section's job (the Updates flow), not as an install.
    val installJob = mine && !s.updating

    ToolPage(
        spec = tool.spec,
        state = ToolStates.npmTool(npm, s, ls, updates.rows.firstOrNull { it.component == component }),
        icon = tool.icon,
        purpose = purpose,
        onBack = onBack,
        install = ToolInstall(
            job = if (installJob) s.job else null, outcome = s.outcome, removing = s.removing,
            installSteps = npm.installSteps, removeSteps = npm.removeSteps,
            failedLine = if (s.removing) "Some of it may still be on the phone." else "Retry starts it again.",
            onInstall = { NpmTools.install(context, npm) },
            onRetry = { NpmTools.retry(context) },
            onUninstall = { NpmTools.remove(context, npm) },
            confirmTitle = "Uninstall ${tool.spec.name}?",
            confirmBody = "${npm.npm} and its folder are removed. $removeNote",
            blocked = when {
                s.running && mine && s.updating -> ({ BusyButton("Updating…") })
                s.running && !mine -> ({ BusyButton("Another install is running…") })
                else -> null
            },
            holdUninstall = ls.busy,
        ),
        update = toolUpdate(context, updates, component),
        accounts ={ CliAccountCard(service, login, ls, why = why, blocked = s.running && mine) },
    ) {
        if (installed) {
            howTo()
        } else {
            ToolCard("WHAT IT IS") {
                ToolText(
                    "${npm.npm} from npm, the newest release. About ${npm.downloadMb} MB to download and ${npm.installMinutes} to " +
                        "${npm.installMinutes + 1} minutes to install. Settings › Updates keeps it current.",
                )
            }
        }
    }
}
