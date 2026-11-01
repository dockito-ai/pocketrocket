package com.pocketrocket.updates

import android.content.Context
import com.pocketrocket.clitools.NpmTool
import com.pocketrocket.clitools.NpmTools
import com.pocketrocket.updater.Outcome
import com.pocketrocket.wrangler.Wrangler
import com.pocketrocket.wrangler.WranglerTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/*
 * The Updates rows of the tools that are global npm packages (decision 102): the deploy CLIs against npm's newest
 * release, and Wrangler against the version this PocketRocket pins. Both versions on the phone come from npm's folder
 * (package.json), and both updates run the tool's own job, with the same steps and progress as its install.
 */

/**
 * Vercel, Netlify or Firebase: `npm view <package> version` when Updates checks (only while it is installed; the answer
 * is kept across restarts), and `npm install -g <package>@latest` through [NpmTools], one npm job at a time.
 */
class NpmSource(private val tool: NpmTool) : UpdateSource {
    override val component = UpdateComponent.of(tool)
    override val row = NpmTools.state.map { s ->
        val job = s.updateJob(tool)
        val latest = s.latest[tool]
        UpdateRow(
            component, installed = s.version(tool), latest = latest, available = s.updateAvailable(tool), checking = tool in s.checking,
            job = job, jobTitle = latest?.let { "Updating to $it" },
            outcome = if (job != null && s.failed) Outcome(s.outcome ?: "Stopped.", failed = true) else null,
            steps = tool.updateSteps,
        )
    }

    override fun load(context: Context) = NpmTools.load(context)

    override suspend fun check(context: Context) = withContext(Dispatchers.IO) { NpmTools.checkBlocking(context, tool) }

    override suspend fun update(context: Context) {
        // Another npm tool's install or update has npm's global folder; this one goes after it.
        NpmTools.state.first { !it.running }
        if (!NpmTools.state.value.updateAvailable(tool)) return
        NpmTools.update(context, tool)
        NpmTools.state.first { !it.running }
    }
}

val NpmSources: List<NpmSource> = NpmTool.entries.map(::NpmSource)

/**
 * Wrangler (decisions 49 and 82): pinned, because each release needs a workerd built for Android. Its row offers the
 * pinned version once an app update has moved the pin past what is on the phone; the update is the install again. The
 * check asks nothing: the pin comes with the app.
 */
object WranglerSource : UpdateSource {
    override val component = UpdateComponent.Wrangler
    override val row = Wrangler.state.map { s ->
        val job = s.job?.takeIf { s.updating }
        UpdateRow(
            component, installed = s.version?.takeIf { s.installed }, latest = WranglerTool.VERSION.takeIf { s.installed },
            available = s.updateAvailable, checking = false,
            job = job, jobTitle = "Updating to ${WranglerTool.VERSION}",
            outcome = if (job != null && s.failed) Outcome(s.outcome ?: "Stopped.", failed = true) else null,
            steps = WranglerTool.INSTALL_STEPS,
        )
    }

    override fun load(context: Context) = Wrangler.refresh()

    override suspend fun check(context: Context) = Wrangler.refresh()

    override suspend fun update(context: Context) {
        Wrangler.state.first { !it.running }
        if (!Wrangler.state.value.updateAvailable) return
        Wrangler.update(context)
        Wrangler.state.first { !it.running }
    }
}
