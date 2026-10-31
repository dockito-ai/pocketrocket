package com.pocketrocket.backup

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributes

/** One thing in the backup. [item] is what the progress line calls it (a repo's name, "Claude config"). */
data class PlanEntry(val name: String, val path: Path, val kind: Kind, val size: Long, val item: String, val linkTarget: String? = null) {
    enum class Kind { File, Directory, Link }
}

data class BackupPlan(
    val entries: List<PlanEntry>,
    val parts: List<BackupPart>,
    val workspaces: List<ManifestWorkspace>,
    val workspaceRoots: List<String>,
    val repos: List<String>,
    val leftOut: List<String>,
    val claude: List<ClaudeSide>,
    val github: List<GitHubSide>,
    val dockito: List<String>,
) {
    val totalBytes: Long get() = parts.sumOf { it.bytes }
    val totalFiles: Int get() = parts.sumOf { it.files }

    fun manifest(createdAt: Long, appVersion: String) = BackupManifest(
        createdAt, appVersion, totalBytes, totalFiles, parts, workspaces, workspaceRoots, repos, leftOut.take(MAX_LEFT_OUT), claude, github, dockito,
    )

    private companion object { const val MAX_LEFT_OUT = 50 }
}

/** What git knows about one repo's folders, as paths relative to the repo with no trailing slash. */
data class RepoFacts(
    /** Folders git ignores entirely. */
    val ignored: Set<String>,
    /** Folders named in [RepoRules.ALWAYS] that hold tracked files. */
    val tracked: Set<String>,
)

/**
 * Which folders in a repo stay out. Inside a repo, nothing leaves unless git shows it can be rebuilt:
 *
 * - Dependency and tool caches ([ALWAYS]) go, unless git tracks files in them (a vendored node_modules).
 * - Folders with ambiguous names ([BUILD]) go only if git ignores them entirely. A tracked `build/` of source, or
 *   an untracked one nobody ignored, might be real work, so it stays.
 * - Outside a repo git can't tell, so only [ALWAYS] goes.
 * - Nothing inside `.git` is touched by name: a branch called "build" is a folder in refs/heads.
 * - If git couldn't answer ([RepoFacts] null in a repo), [BUILD] folders stay: too big beats missing work.
 */
internal object RepoRules {
    val ALWAYS = setOf("node_modules", ".gradle", ".dart_tool", ".next", ".turbo", "__pycache__", ".pytest_cache", ".mypy_cache", ".venv")
    val BUILD = setOf("build", "dist", "target", "out", ".build", "DerivedData")

    fun skip(dirName: String, relative: String?, facts: RepoFacts?): Boolean = when {
        dirName in ALWAYS -> relative == null || facts == null || relative !in facts.tracked
        dirName in BUILD -> relative != null && facts != null && coveredBy(relative, facts.ignored)
        else -> false
    }

    private fun coveredBy(relative: String, ignored: Set<String>): Boolean {
        var at = relative
        while (true) {
            if (at in ignored) return true
            val slash = at.lastIndexOf('/')
            if (slash < 0) return false
            at = at.substring(0, slash)
        }
    }
}

/**
 * Works out what goes in: the named paths in [BackupAllowlist], walked without following any link. [repoFacts]
 * asks git about a repo; it answers null when git can't. A link leading outside the known paths would make
 * a restore refuse the file, so it is left out here and listed.
 */
internal class BackupScanner(
    private val home: File,
    private val repoFacts: (File) -> RepoFacts?,
    private val isCancelled: () -> Boolean = { false },
) {
    private val files = AccountFiles(home)
    private val filesRoot = home.parentFile!!.path
    private val entries = mutableListOf<PlanEntry>()
    private val bytes = mutableMapOf<String, Long>()
    private val counts = mutableMapOf<String, Int>()
    private val leftOut = mutableListOf<String>()
    private val repos = mutableListOf<String>()
    private var roots: List<String> = emptyList()

    fun scan(): BackupPlan {
        val workspaces = files.workspaceList()
        roots = workspaceRoots(workspaces)
        val claude = files.claudeAccounts()
        val github = files.githubAccounts()

        val lists = BackupManifest.PART_LISTS
        add(files.workspaces, lists, "Workspace list")
        add(files.claudeList, lists, "Workspace list")
        add(files.githubList, lists, "Workspace list")
        add(files.dockitoList, lists, "Workspace list")
        add(files.bindings, lists, "Workspace list")

        claude.forEach { account ->
            val dir = files.claudeDir(account.id)
            val part = BackupManifest.PART_CLAUDE
            val item = "Claude config"
            listOf("settings.json", "CLAUDE.md", ".credentials.json").forEach { add(File(dir, it), part, item) }
            add(files.claudeJson(account.id), part, item)
            listOf("skills", "plugins", "agents", "commands").forEach { add(File(dir, it), part, item) }
            File(dir, "projects").listFiles().orEmpty().sortedBy { it.name }.forEach { add(File(it, "memory"), part, item) }
        }
        github.forEach { account ->
            val dir = files.githubDir(account.id)
            listOf("gitconfig", "id_ed25519", "id_ed25519.pub", "gh").forEach { add(File(dir, it), BackupManifest.PART_GITHUB, "GitHub") }
        }
        add(File(home, ".pocketrocket/accounts/github/known_hosts"), BackupManifest.PART_GITHUB, "GitHub")
        add(files.dockitoDir, BackupManifest.PART_DOCKITO, "Dockito")

        val projects = File(home, "projects")
        if (Files.isDirectory(projects.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            walkFolder(projects, BackupAllowlist.PROJECTS, null, null, null)
        }
        roots.forEach { root ->
            val dir = File(home.parentFile, root)
            if (Files.isDirectory(dir.toPath(), LinkOption.NOFOLLOW_LINKS)) walkFolder(dir, root, dir.name, null, null)
        }

        val order = listOf(BackupManifest.PART_REPOS, BackupManifest.PART_CLAUDE, BackupManifest.PART_GITHUB, BackupManifest.PART_DOCKITO, BackupManifest.PART_LISTS)
        return BackupPlan(
            entries, order.map { BackupPart(it, bytes[it] ?: 0, counts[it] ?: 0) }, workspaces, roots, repos, leftOut,
            claude, github, files.dockitoEmails(),
        )
    }

    /** Workspaces outside ~/projects that can go in, without ones inside another. The rest are listed as left out. */
    private fun workspaceRoots(workspaces: List<ManifestWorkspace>): List<String> {
        val prefix = home.parentFile!!.path + "/"
        val named = workspaces.mapNotNull { ws ->
            val name = ws.path.removePrefix(prefix)
            when {
                !ws.path.startsWith(prefix) -> { leftOut += "${ws.name}: outside the app, its files stay where they are"; null }
                name == BackupAllowlist.PROJECTS || name.startsWith(BackupAllowlist.PROJECTS + "/") -> null
                ws.path == home.path -> { leftOut += "${ws.name}: it is the whole home folder, only ~/projects goes in"; null }
                File(home, "storage").path.let { ws.path == it || ws.path.startsWith("$it/") } -> { leftOut += "${ws.name}: in shared storage, its files stay where they are"; null }
                !BackupAllowlist.isWorkspaceRoot(name) -> { leftOut += "${ws.name}: a hidden or system folder"; null }
                else -> name
            }
        }.distinct().sorted()
        return named.filter { n -> named.none { other -> other != n && n.startsWith("$other/") } }
    }

    /** Adds [file], a file, link or folder, if it exists. A folder here is an allowlisted tree: no repo rules. */
    private fun add(file: File, part: String, item: String) {
        val attrs = attrsOrNull(file.toPath()) ?: return
        val name = nameOf(file)
        when {
            attrs.isDirectory -> walkTree(file, name, part, item)
            else -> addLeaf(file.toPath(), name, attrs, part, item)
        }
    }

    private fun walkTree(dir: File, name: String, part: String, item: String) {
        checkCancel()
        entries += PlanEntry(name, dir.toPath(), PlanEntry.Kind.Directory, 0, item)
        children(dir).forEach { child ->
            val attrs = attrsOrNull(child.toPath()) ?: return@forEach
            val childName = "$name/${child.name}"
            if (attrs.isDirectory) walkTree(child, childName, part, item) else addLeaf(child.toPath(), childName, attrs, part, item)
        }
    }

    /** A folder under ~/projects or a workspace. [repo] is the repo it is in, [item] what the progress calls it. */
    private fun walkFolder(dir: File, name: String, item: String?, repo: File?, facts: RepoFacts?) {
        checkCancel()
        val startsRepo = File(dir, ".git").let { Files.exists(it.toPath(), LinkOption.NOFOLLOW_LINKS) }
        val thisRepo = if (startsRepo) dir else repo
        val thisFacts = if (startsRepo) repoFacts(dir) else facts
        if (startsRepo && repo == null) repos += name
        val thisItem = if (startsRepo && repo == null) dir.name else item ?: dir.name
        entries += PlanEntry(name, dir.toPath(), PlanEntry.Kind.Directory, 0, thisItem)
        val part = BackupManifest.PART_REPOS
        children(dir).forEach { child ->
            val attrs = attrsOrNull(child.toPath()) ?: return@forEach
            val childName = "$name/${child.name}"
            when {
                !attrs.isDirectory -> addLeaf(child.toPath(), childName, attrs, part, thisItem)
                child.name == ".git" -> walkGit(child, childName, part, thisItem)
                RepoRules.skip(child.name, thisRepo?.let { child.relativeTo(it).path }, thisFacts) -> Unit
                else -> walkFolder(child, childName, if (name == BackupAllowlist.PROJECTS) null else thisItem, thisRepo, thisFacts)
            }
        }
    }

    /** `.git` whole, except lock files: a lock left by a running git would stop git after a restore. */
    private fun walkGit(dir: File, name: String, part: String, item: String) {
        checkCancel()
        entries += PlanEntry(name, dir.toPath(), PlanEntry.Kind.Directory, 0, item)
        children(dir).forEach { child ->
            val attrs = attrsOrNull(child.toPath()) ?: return@forEach
            val childName = "$name/${child.name}"
            when {
                attrs.isDirectory -> walkGit(child, childName, part, item)
                child.name.endsWith(".lock") -> Unit
                else -> addLeaf(child.toPath(), childName, attrs, part, item)
            }
        }
    }

    private fun addLeaf(path: Path, name: String, attrs: PosixFileAttributes, part: String, item: String) {
        when {
            attrs.isRegularFile -> {
                entries += PlanEntry(name, path, PlanEntry.Kind.File, attrs.size(), item)
                bytes.merge(part, attrs.size(), Long::plus)
                counts.merge(part, 1, Int::plus)
            }
            attrs.isSymbolicLink -> {
                val target = Files.readSymbolicLink(path).toString()
                if (BackupAllowlist.linkStaysInside(name, target, filesRoot, roots)) {
                    entries += PlanEntry(name, path, PlanEntry.Kind.Link, 0, item, target)
                    counts.merge(part, 1, Int::plus)
                } else {
                    leftOut += "${name.removePrefix("home/")}: a link leading outside the backup"
                }
            }
            // Sockets and the like mean nothing in a backup.
        }
    }

    private fun nameOf(file: File) = file.path.removePrefix(home.parentFile!!.path + "/")

    private fun attrsOrNull(path: Path): PosixFileAttributes? = try {
        Files.readAttributes(path, PosixFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (_: NoSuchFileException) {
        null
    }

    private fun children(dir: File): List<File> =
        (dir.listFiles() ?: throw IOException("Couldn't read ${nameOf(dir).removePrefix("home/")}.")).sortedBy { it.name }

    private fun checkCancel() {
        if (isCancelled()) throw BackupCancelled()
    }
}
