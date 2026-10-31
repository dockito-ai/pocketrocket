package com.pocketrocket.backup

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.zip.GZIPOutputStream

class BackupRestoreTest {
    private lateinit var base: File
    private lateinit var home: File
    private lateinit var phone: File
    private lateinit var dest: File
    private val pass get() = "correct horse".toCharArray()

    private val quiet = object : BackupEvents {
        override fun step(index: Int) = Unit
        override fun progress(bytes: Long, total: Long, item: String) = Unit
    }

    @Before fun setUp() {
        base = Files.createTempDirectory("backup-test").toFile()
        // The same absolute names in backup and restore would hide mistakes, so they are two separate trees.
        home = File(base, "a/files/home").apply { mkdirs() }
        phone = File(base, "b/files/home").apply { mkdirs() }
        dest = File(base, "downloads/PocketRocket/pocketrocket.backup")
    }

    @After fun tearDown() {
        // Never follow links: chmod only real directories and files, delete links as links.
        Files.walkFileTree(base.toPath(), object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
            override fun preVisitDirectory(dir: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                dir.toFile().setWritable(true, true)
                return java.nio.file.FileVisitResult.CONTINUE
            }
            override fun visitFile(file: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                if (attrs.isRegularFile) file.toFile().setWritable(true, true) else if (attrs.isSymbolicLink) Files.delete(file)
                return java.nio.file.FileVisitResult.CONTINUE
            }
        })
        base.deleteRecursively()
    }

    private fun File.put(path: String, text: String = "x") = File(this, path).apply { parentFile!!.mkdirs(); writeText(text) }

    private fun fullHome() {
        home.put(".pocketrocket/workspaces.json", """{"workspaces":[{"path":"${home.path}/projects/api","name":"API"},{"path":"${home.path}/code/side","name":"Side"}]}""")
        home.put(".pocketrocket/accounts/claude.json", """{"default":"main","accounts":[{"id":"main","name":"personal"},{"id":"work","name":"Work"}]}""")
        home.put(".pocketrocket/accounts/github.json", """{"default":"sam","accounts":[{"id":"sam","login":"sam","userName":"Sam B","userEmail":"sam@backup","keyTitle":"old"}]}""")
        home.put(".pocketrocket/accounts/dockito.json", """{"default":"d@x.com","accounts":[{"email":"d@x.com","name":"D","username":"d"}]}""")
        home.put(".pocketrocket/accounts/bindings.json", """{"bindings":[{"folder":"${home.path}/projects/api","claude":"work","github":"sam","dockito":null}]}""")
        home.put(".pocketrocket/accounts/env.sh", "generated")
        home.put(".pocketrocket/sessions.json", "chats")
        home.put(".pocketrocket/tools.txt", "tools")
        home.put(".pocketrocket/attachments/s/pic.png", "chat attachment")
        home.put(".pocketrocket/claude/2.1/claude", "binary")
        home.put(".pocketrocket/dockito/1/dockito", "binary")
        home.put(".gitconfig", "generated")
        home.put(".bashrc", "mine")

        home.put(".claude/settings.json", """{"autoUpdates":true,"theme":"dark","env":{"LD_PRELOAD":"bad","X":"1"}}""")
        home.put(".claude/CLAUDE.md", "memory")
        home.put(".claude/.credentials.json", "token-main")
        home.put(".claude/skills/one/SKILL.md", "skill")
        home.put(".claude/plugins/p/plugin.json", "plugin")
        home.put(".claude/agents/reviewer.md", "agent")
        home.put(".claude/commands/ship.md", "command")
        home.put(".claude/projects/-x/memory/note.md", "note")
        home.put(".claude/projects/-x/abc.jsonl", "TRANSCRIPT")
        home.put(".claude/history.jsonl", "HISTORY")
        home.put(".claude/todos/t.json", "TODO")
        home.put(".claude/shell-snapshots/s.sh", "SNAP")
        home.put(".claude/cache/c", "CACHE")
        home.put(".claude.json", """{"mcpServers":{"mine":{"command":"node"}},"oauthAccount":{"emailAddress":"a@x.com"},"userID":"backup-user"}""")

        home.put(".pocketrocket/accounts/claude/work/settings.json", "{}")
        home.put(".pocketrocket/accounts/claude/work/.credentials.json", "token-work")
        home.put(".pocketrocket/accounts/claude/work/.claude.json", """{"oauthAccount":{"emailAddress":"w@x.com"}}""")
        home.put(".pocketrocket/accounts/claude/work/projects/-y/z.jsonl", "TRANSCRIPT")

        val gh = ".pocketrocket/accounts/github/sam"
        home.put("$gh/gitconfig", "[user]\n\tname = \"Sam B\"\n")
        home.put("$gh/id_ed25519", "BACKUP KEY")
        home.put("$gh/id_ed25519.pub", "pub")
        home.put("$gh/gh/hosts.yml", "BACKUP GH LOGIN")
        home.put(".pocketrocket/accounts/github/known_hosts", "github.com key")
        home.put(".pocketrocket/accounts/dockito/accounts.json", """{"dockito.ai":{"active":"d@x.com","accounts":[{"email":"d@x.com","name":"D","username":"d"}]}}""")
        home.put(".pocketrocket/accounts/dockito/tokens/dockito.ai_d@x.com", "BACKUP DOCKITO TOKEN")

        repo("projects/api")
        home.put("code/side/notes.txt", "outside projects")
        home.put("projects/loose/a.txt", "not a repo")
        home.put("projects/loose/node_modules/x.js", "dep")
        home.put("projects/loose/build/keep.txt", "not a repo, so build stays")
    }

    private fun repo(path: String) {
        home.put("$path/.git/HEAD", "ref: refs/heads/main")
        home.put("$path/.git/refs/heads/build/feature", "abc") // a branch called build/feature
        home.put("$path/.git/index.lock", "stale")
        home.put("$path/src/main.kt", "code")
        home.put("$path/.env", "SECRET=1")
        home.put("$path/node_modules/dep/index.js", "dep")
        home.put("$path/build/out.bin", "ignored build")
        home.put("$path/src/build/tracked.kt", "tracked source in a folder called build")
        home.put("$path/untracked-build/build/wip.txt", "neither tracked nor ignored")
        val script = home.put("$path/run.sh", "#!/bin/sh")
        Files.setPosixFilePermissions(script.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"))
        Files.createSymbolicLink(File(home, "$path/link-to-src").toPath(), File("src").toPath())
    }

    private val facts: (File) -> RepoFacts? = { RepoFacts(ignored = setOf("build"), tracked = emptySet()) }

    private fun backUp(cancel: () -> Boolean = { false }, replace: (File, File) -> Unit = BackupRunner::replaceFile) =
        BackupRunner.backup(dest, home, pass, "1.0", facts, quiet, cancel, now = 1000, iterations = 1000, replace = replace)

    private fun restore(file: File = dest, into: File = phone, running: Int = 0, cancel: () -> Boolean = { false }, move: (File, File) -> Unit = RestoreInstaller::rename, passphrase: CharArray = pass) =
        BackupRunner.restore({ file.inputStream() }, passphrase, into, { running }, quiet, cancel, move)

    private fun names(): Set<String> = BackupScanner(home, facts).scan().entries.map { it.name.removePrefix("home/") }.toSet()

    /** Every file under a tree with its text, for proving nothing changed. */
    private fun snapshot(dir: File) = dir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(dir).path to it.readText() }

    // ---- What goes in ----

    @Test fun allowlist_takes_only_what_the_spec_names() {
        fullHome()
        val n = names()
        listOf(
            ".pocketrocket/workspaces.json", ".pocketrocket/accounts/claude.json", ".pocketrocket/accounts/github.json",
            ".pocketrocket/accounts/dockito.json", ".pocketrocket/accounts/bindings.json",
            ".claude/settings.json", ".claude/CLAUDE.md", ".claude/.credentials.json", ".claude/skills/one/SKILL.md",
            ".claude/plugins/p/plugin.json", ".claude/agents/reviewer.md", ".claude/commands/ship.md", ".claude/projects/-x/memory/note.md", ".claude.json",
            ".pocketrocket/accounts/claude/work/.claude.json", ".pocketrocket/accounts/claude/work/.credentials.json",
            ".pocketrocket/accounts/github/sam/gitconfig", ".pocketrocket/accounts/github/sam/id_ed25519",
            ".pocketrocket/accounts/github/sam/gh/hosts.yml", ".pocketrocket/accounts/github/known_hosts",
            ".pocketrocket/accounts/dockito/accounts.json", ".pocketrocket/accounts/dockito/tokens/dockito.ai_d@x.com",
            "projects/api/src/main.kt", "projects/api/.env", "projects/api/.git/HEAD", "code/side/notes.txt", "projects/loose/a.txt",
        ).forEach { assertTrue(it, it in n) }
    }

    @Test fun chats_tools_and_generated_files_stay_out() {
        fullHome()
        val n = names()
        listOf(
            ".claude/projects/-x/abc.jsonl", ".claude/history.jsonl", ".claude/todos/t.json", ".claude/shell-snapshots/s.sh", ".claude/cache/c",
            ".pocketrocket/accounts/claude/work/projects/-y/z.jsonl", ".pocketrocket/sessions.json", ".pocketrocket/attachments/s/pic.png",
            ".pocketrocket/tools.txt", ".pocketrocket/claude/2.1/claude", ".pocketrocket/dockito/1/dockito",
            ".pocketrocket/accounts/env.sh", ".gitconfig", ".bashrc",
        ).forEach { assertFalse(it, it in n) }
    }

    @Test fun repo_rules_keep_work_and_drop_what_rebuilds() {
        fullHome()
        val n = names()
        assertFalse("projects/api/node_modules/dep/index.js" in n)
        assertFalse("ignored build", "projects/api/build/out.bin" in n)
        assertTrue("tracked build stays", "projects/api/src/build/tracked.kt" in n)
        assertTrue("unignored build stays", "projects/api/untracked-build/build/wip.txt" in n)
        assertTrue("a branch named build stays", "projects/api/.git/refs/heads/build/feature" in n)
        assertFalse("a stale lock stays out", "projects/api/.git/index.lock" in n)
        assertFalse("projects/loose/node_modules/x.js" in n)
        assertTrue("outside a repo git can't tell, so build stays", "projects/loose/build/keep.txt" in n)
    }

    @Test fun rules_for_tracked_node_modules_and_unknown_git() {
        val f = RepoFacts(setOf("a/build"), setOf("vendor/node_modules"))
        assertFalse(RepoRules.skip("node_modules", "vendor/node_modules", f))
        assertTrue(RepoRules.skip("node_modules", "node_modules", f))
        assertTrue(RepoRules.skip("build", "a/build", f))
        assertFalse(RepoRules.skip("build", "b/build", f))
        assertFalse("git didn't answer: build stays", RepoRules.skip("build", "a/build", null))
        assertTrue("git didn't answer: node_modules still goes", RepoRules.skip("node_modules", "node_modules", null))
    }

    @Test fun a_link_leading_outside_is_left_out_and_listed() {
        fullHome()
        Files.createSymbolicLink(File(home, "projects/api/escape").toPath(), File("../../../outside").toPath())
        Files.createSymbolicLink(File(home, "projects/api/abs").toPath(), File("/etc/passwd").toPath())
        val plan = BackupScanner(home, facts).scan()
        assertFalse(plan.entries.any { it.name.endsWith("/escape") || it.name.endsWith("/abs") })
        assertEquals(2, plan.leftOut.count { it.contains("a link leading outside") })
        backUp()
        restore()
        assertTrue(File(phone, "projects/api/src/main.kt").exists())
    }

    @Test fun workspace_special_cases() {
        home.put(".pocketrocket/workspaces.json", """{"workspaces":[
            {"path":"${home.path}","name":"Home"},
            {"path":"${home.path}/.hidden","name":"Hidden"},
            {"path":"${home.path}/storage/shared/x","name":"Shared"},
            {"path":"/elsewhere/y","name":"Far"},
            {"path":"${home.path}/code/a","name":"A"},
            {"path":"${home.path}/code/a/b","name":"Inner"}]}""")
        home.put("code/a/b/c.txt")
        home.put(".hidden/secret")
        val plan = BackupScanner(home, facts).scan()
        assertEquals(listOf("home/code/a"), plan.workspaceRoots)
        assertEquals(4, plan.leftOut.size)
        assertFalse(plan.entries.any { it.name.contains(".hidden") })
    }

    // ---- The round trip ----

    @Test fun round_trip_keeps_files_links_modes_and_applies_defaults() {
        fullHome()
        backUp()
        assertTrue(dest.exists())
        restore()
        assertEquals("code", File(phone, "projects/api/src/main.kt").readText())
        assertEquals("src", Files.readSymbolicLink(File(phone, "projects/api/link-to-src").toPath()).toString())
        assertEquals("rwxr-xr-x", PosixFilePermissions.toString(Files.getPosixFilePermissions(File(phone, "projects/api/run.sh").toPath())))
        assertEquals("outside projects", File(phone, "code/side/notes.txt").readText())
        assertEquals("memory", File(phone, ".claude/CLAUDE.md").readText())
        assertEquals("note", File(phone, ".claude/projects/-x/memory/note.md").readText())
        val settings = JSONObject(File(phone, ".claude/settings.json").readText())
        assertEquals(false, settings.getBoolean("autoUpdates"))
        assertEquals("dark", settings.getString("theme"))
        val env = settings.getJSONObject("env")
        assertEquals("0", env.getString("USE_BUILTIN_RIPGREP"))
        assertEquals("1", env.getString("X"))
        assertFalse(env.has("LD_PRELOAD"))
        assertFalse(File(phone, ".claude/projects/-x/abc.jsonl").exists())
        assertFalse(File(phone, ".pocketrocket/sessions.json").exists())
        assertFalse("work folder is cleaned", File(phone.parentFile, BackupRunner.WORK_DIR).exists())
    }

    @Test fun nothing_in_the_file_is_readable_without_the_passphrase() {
        fullHome()
        backUp()
        val raw = String(dest.readBytes(), Charsets.ISO_8859_1)
        listOf("token-main", "BACKUP KEY", "SECRET=1", "projects/api").forEach { assertFalse(it, raw.contains(it)) }
    }

    @Test fun an_older_phone_tree_keeps_what_is_not_in_the_backup() {
        fullHome()
        backUp()
        phone.put(".pocketrocket/workspaces.json", """{"workspaces":[{"path":"${phone.path}/projects/phone-only","name":"Phone"}]}""")
        phone.put("projects/phone-only/f.txt", "phone")
        phone.put("projects/api/stale.txt", "replaced with the repo")
        phone.put(".claude/projects/-x/phone.jsonl", "phone transcript")
        restore()
        assertEquals("phone", File(phone, "projects/phone-only/f.txt").readText())
        assertFalse(File(phone, "projects/api/stale.txt").exists())
        assertTrue(File(phone, ".claude/projects/-x/phone.jsonl").exists())
        val list = AccountFiles(phone).workspaceList().map { it.name }.toSet()
        assertEquals(setOf("API", "Side", "Phone"), list)
    }

    // ---- The account merge, on files ----

    private fun signedInPhone() {
        phone.put(".pocketrocket/accounts/claude.json", """{"default":"main","accounts":[{"id":"main","name":"personal"}]}""")
        phone.put(".claude/.credentials.json", "NEW TOKEN")
        phone.put(".claude.json", """{"oauthAccount":{"emailAddress":"W@x.com"},"userID":"phone-user","mcpServers":{"phoneonly":{"command":"x"}}}""")
        phone.put(".pocketrocket/accounts/github.json", """{"default":"sam","accounts":[{"id":"sam","login":"Sam","userName":"Phone","userEmail":"phone@x","keyTitle":"new"},{"id":"phoneonly","login":"phoneonly","userName":"","userEmail":"","keyTitle":""}]}""")
        phone.put(".pocketrocket/accounts/github/sam/gh/hosts.yml", "NEW GH LOGIN")
        phone.put(".pocketrocket/accounts/github/sam/id_ed25519", "NEW KEY")
        phone.put(".pocketrocket/accounts/github/phoneonly/gh/hosts.yml", "PHONE ONLY LOGIN")
        phone.put(".pocketrocket/accounts/dockito.json", """{"default":"p@x.com","accounts":[{"email":"p@x.com","name":"P","username":"p"},{"email":"d@x.com","name":"D","username":"d"}]}""")
        phone.put(".pocketrocket/accounts/dockito/accounts.json", """{"dockito.ai":{"active":"p@x.com","accounts":[{"email":"p@x.com","name":"P","username":"p"},{"email":"d@x.com","name":"D","username":"d"}]}}""")
        phone.put(".pocketrocket/accounts/dockito/tokens/dockito.ai_p@x.com", "PHONE P TOKEN")
        phone.put(".pocketrocket/accounts/dockito/tokens/dockito.ai_d@x.com", "NEW DOCKITO TOKEN")
    }

    @Test fun restoring_over_a_signed_in_phone_merges_by_identity() {
        fullHome()
        backUp()
        signedInPhone()
        restore()
        val after = AccountFiles(phone)

        // The backup's "work" (w@x.com) is the phone's "main": no duplicate, config back, phone's login stays.
        val claude = after.claudeAccounts()
        assertEquals(setOf("main", "main-2"), claude.map { it.id }.toSet())
        assertEquals("w@x.com", claude.first { it.id == "main" }.email)
        assertEquals("NEW TOKEN", File(phone, ".claude/.credentials.json").readText())
        val json = JSONObject(File(phone, ".claude.json").readText())
        assertEquals("phone-user", json.getString("userID"))
        assertTrue(json.getJSONObject("mcpServers").has("phoneonly"))
        assertEquals("main", after.defaultOf(after.claudeList))

        // The backup's "main" (a@x.com) is someone new: added under a free id, with its login and its config.
        assertEquals("a@x.com", claude.first { it.id == "main-2" }.email)
        assertEquals("token-main", File(phone, ".pocketrocket/accounts/claude/main-2/.credentials.json").readText())
        assertEquals("memory", File(phone, ".pocketrocket/accounts/claude/main-2/CLAUDE.md").readText())
        assertTrue(JSONObject(File(phone, ".pocketrocket/accounts/claude/main-2/.claude.json").readText()).getJSONObject("mcpServers").has("mine"))

        // GitHub: same login once; the phone's login and key win, the commit identity comes back; phone-only stays.
        val github = after.githubAccounts()
        assertEquals(listOf("sam", "phoneonly"), github.map { it.id })
        assertEquals("NEW GH LOGIN", File(phone, ".pocketrocket/accounts/github/sam/gh/hosts.yml").readText())
        assertEquals("NEW KEY", File(phone, ".pocketrocket/accounts/github/sam/id_ed25519").readText())
        assertTrue(File(phone, ".pocketrocket/accounts/github/sam/gitconfig").readText().contains("Sam B"))
        assertEquals("PHONE ONLY LOGIN", File(phone, ".pocketrocket/accounts/github/phoneonly/gh/hosts.yml").readText())
        val entry = JSONObject(after.githubList.readText()).getJSONArray("accounts").getJSONObject(0)
        assertEquals("sam@backup", entry.getString("userEmail"))
        assertEquals("new", entry.getString("keyTitle"))
        assertEquals("sam", after.defaultOf(after.githubList))

        // Dockito: tokens by email, the phone's login stays active, no duplicate.
        assertEquals(setOf("p@x.com", "d@x.com"), after.dockitoEmails().toSet())
        assertEquals("NEW DOCKITO TOKEN", File(phone, ".pocketrocket/accounts/dockito/tokens/dockito.ai_d@x.com").readText())
        assertEquals("PHONE P TOKEN", File(phone, ".pocketrocket/accounts/dockito/tokens/dockito.ai_p@x.com").readText())
        assertEquals("p@x.com", JSONObject(File(phone, ".pocketrocket/accounts/dockito/accounts.json").readText()).getJSONObject("dockito.ai").getString("active"))

        // Bindings: api was on "work", which survives as "main".
        val bindings = JSONObject(after.bindings.readText()).getJSONArray("bindings")
        val api = (0 until bindings.length()).map { bindings.getJSONObject(it) }.first { it.getString("folder").endsWith("projects/api") }
        assertEquals("main", api.getString("claude"))
        assertEquals("sam", api.getString("github"))
    }

    @Test fun a_signed_out_fresh_phone_takes_the_backup_as_it_is() {
        fullHome()
        backUp()
        phone.put(".claude/settings.json", "{}")
        restore()
        val after = AccountFiles(phone)
        assertEquals(setOf("main", "work"), after.claudeAccounts().map { it.id }.toSet())
        assertEquals("token-main", File(phone, ".claude/.credentials.json").readText())
        assertEquals("BACKUP KEY", File(phone, ".pocketrocket/accounts/github/sam/id_ed25519").readText())
        assertEquals("BACKUP DOCKITO TOKEN", File(phone, ".pocketrocket/accounts/dockito/tokens/dockito.ai_d@x.com").readText())
    }

    @Test fun the_preview_says_what_will_merge_and_replace() {
        fullHome()
        backUp()
        signedInPhone()
        phone.put("projects/api/.git/HEAD", "phone")
        val p = BackupRunner.preview({ dest.inputStream() }, pass, phone)
        assertEquals(listOf("home/projects/api"), p.replacedRepos)
        assertTrue(p.merges.first { it.who.startsWith("w@x.com") }.what.startsWith("Same account"))
        assertTrue(p.merges.first { it.who.startsWith("a@x.com") }.what.startsWith("Added"))
        assertTrue(p.merges.first { it.who.startsWith("sam") }.what.startsWith("Same account"))
    }

    // ---- The old backup survives ----

    @Test fun a_failed_or_cancelled_backup_keeps_the_old_file() {
        fullHome()
        backUp()
        val old = dest.readBytes()
        home.put("projects/api/src/new.kt", "more")

        assertThrows(BackupCancelled::class.java) { var n = 0; backUp(cancel = { n++ > 20 }) }
        assertTrue(old.contentEquals(dest.readBytes()))
        assertFalse(BackupRunner.tempFor(dest).exists())

        assertThrows(IOException::class.java) { backUp(replace = { _, _ -> throw IOException("no") }) }
        assertTrue(old.contentEquals(dest.readBytes()))
        assertFalse(BackupRunner.tempFor(dest).exists())

        backUp()
        assertFalse(old.contentEquals(dest.readBytes()))
    }

    @Test fun an_unreadable_folder_fails_the_backup_up_front() {
        fullHome()
        backUp()
        val old = dest.readBytes()
        val locked = File(home, "projects/api/src")
        locked.setReadable(false, true)
        try {
            assertThrows(IOException::class.java) { backUp() }
        } finally {
            locked.setReadable(true, true)
        }
        assertTrue(old.contentEquals(dest.readBytes()))
    }

    // ---- Restore leaves things alone when it doesn't happen ----

    private fun assertUntouched(run: () -> Unit, type: Class<out Throwable>) {
        val before = snapshot(phone)
        assertThrows(type) { run() }
        assertEquals(before, snapshot(phone))
    }

    @Test fun wrong_passphrase_damage_and_cancel_change_nothing() {
        fullHome()
        backUp()
        signedInPhone()
        assertUntouched({ restore(passphrase = "nope nope".toCharArray()) }, WrongPassphrase::class.java)

        val bad = File(base, "bad.backup")
        bad.writeBytes(dest.readBytes().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() })
        assertUntouched({ restore(file = bad) }, DamagedBackup::class.java)

        bad.writeBytes(dest.readBytes().copyOf(dest.length().toInt() - 50))
        assertUntouched({ restore(file = bad) }, DamagedBackup::class.java)

        var n = 0
        assertUntouched({ restore(cancel = { n++ > 30 }) }, BackupCancelled::class.java)
        assertFalse(File(phone.parentFile, BackupRunner.WORK_DIR).exists())
    }

    @Test fun running_sessions_refuse_it() {
        fullHome()
        backUp()
        assertUntouched({ restore(running = 2) }, SessionsRunning::class.java)
    }

    @Test fun a_failure_while_moving_rolls_everything_back() {
        fullHome()
        backUp()
        signedInPhone()
        phone.put("projects/api/.git/HEAD", "phone repo")
        phone.put("projects/api/phone.txt", "phone")
        var moves = 0
        assertUntouched({
            restore(move = { from, to ->
                if (++moves == 9) throw IOException("disk went away")
                RestoreInstaller.rename(from, to)
            })
        }, IOException::class.java)
        assertTrue(moves >= 9)
    }

    @Test fun a_crash_while_moving_is_rolled_back_on_the_next_start() {
        val root = phone.parentFile!!
        val work = File(root, BackupRunner.WORK_DIR)
        phone.put("projects/a/f.txt", "old a")
        File(work, "staged").let { s -> s.put("home/projects/a/f.txt", "new a"); s.put("home/projects/b/g.txt", "new b") }
        // Where the process died: the journal is written, one move done, the next not begun.
        File(work, "journal").writeText("home/projects/a/f.txt\nhome/projects/b\n")
        RestoreInstaller.rename(File(phone, "projects/a/f.txt"), File(work, "old/home/projects/a/f.txt").apply { parentFile!!.mkdirs() })
        RestoreInstaller.rename(File(work, "staged/home/projects/a/f.txt"), File(phone, "projects/a/f.txt"))
        assertEquals("new a", File(phone, "projects/a/f.txt").readText())
        assertTrue(RestoreInstaller(work, root).recover())
        assertEquals("old a", File(phone, "projects/a/f.txt").readText())
        assertFalse(File(phone, "projects/b").exists())
    }

    // ---- A crafted file ----

    private fun manifestJson(roots: List<String> = emptyList()) = BackupManifest(
        1, "1", 0, 0, emptyList(), emptyList(), roots, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
    ).toJson().toByteArray()

    /** A correctly encrypted backup holding whatever [entries] writes. */
    private fun crafted(roots: List<String> = emptyList(), entries: TarArchiveOutputStream.() -> Unit): File {
        val bytes = ByteArrayOutputStream()
        EncryptingOutput(bytes, pass, iterations = 1000).use { sealed ->
            TarArchiveOutputStream(GZIPOutputStream(sealed), "UTF-8").use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                val json = manifestJson(roots)
                tar.putArchiveEntry(TarArchiveEntry(BackupManifest.ENTRY).apply { size = json.size.toLong() })
                tar.write(json)
                tar.closeArchiveEntry()
                tar.entries()
            }
        }
        return File(base, "crafted.backup").apply { writeBytes(bytes.toByteArray()) }
    }

    private fun TarArchiveOutputStream.file(name: String, text: String = "evil") {
        val data = text.toByteArray()
        putArchiveEntry(TarArchiveEntry(name, true).apply { size = data.size.toLong() })
        write(data)
        closeArchiveEntry()
    }

    private fun TarArchiveOutputStream.link(name: String, target: String) {
        putArchiveEntry(TarArchiveEntry(name, TarConstants.LF_SYMLINK).apply { linkName = target })
        closeArchiveEntry()
    }

    private fun assertRefused(roots: List<String> = emptyList(), entries: TarArchiveOutputStream.() -> Unit) {
        phone.put("projects/existing.txt", "before")
        val file = crafted(roots, entries)
        assertUntouched({ restore(file = file) }, UnsafeBackup::class.java)
        assertFalse(File(base, "b/files/evil").exists())
        assertFalse(File(base, "evil").exists())
    }

    @Test fun dot_dot_is_refused() = assertRefused { file("home/projects/../../evil") }
    @Test fun dot_dot_to_a_known_place_is_refused() = assertRefused { file("home/projects/../.bashrc") }
    @Test fun an_unknown_path_is_refused() = assertRefused { file("home/.bashrc") }
    @Test fun the_prefix_is_refused() = assertRefused { file("usr/bin/git") }
    @Test fun a_transcript_is_refused() = assertRefused { file("home/.claude/projects/-x/abc.jsonl") }
    @Test fun a_link_out_is_refused() = assertRefused { link("home/projects/out", "../../../evil") }
    @Test fun an_absolute_link_is_refused() = assertRefused { link("home/projects/out", "/etc") }
    @Test fun a_link_to_an_unknown_home_path_is_refused() = assertRefused { link("home/projects/out", "../.ssh") }
    @Test fun a_sneaky_link_with_dots_after_a_name_is_refused() = assertRefused { link("home/projects/a/b", "c/../../../..") }

    @Test fun writing_through_a_link_is_refused() = assertRefused {
        link("home/projects/in", "other")
        file("home/projects/in/x.txt")
    }

    @Test fun writing_over_a_link_is_refused() = assertRefused {
        link("home/projects/in", "other")
        file("home/projects/in")
    }

    @Test fun a_device_file_is_refused() = assertRefused {
        putArchiveEntry(TarArchiveEntry("home/projects/dev", TarConstants.LF_CHR))
        closeArchiveEntry()
    }

    @Test fun a_hard_link_is_refused() = assertRefused {
        putArchiveEntry(TarArchiveEntry("home/projects/hard", TarConstants.LF_LINK).apply { linkName = "home/.claude/.credentials.json" })
        closeArchiveEntry()
    }

    @Test fun a_crafted_workspace_root_is_refused() {
        listOf("home/.ssh", "home", "usr", "home/storage/shared", "home/x/../../usr").forEach { root ->
            assertRefused(listOf(root)) { file("$root/authorized_keys") }
        }
    }

    @Test fun setuid_bits_do_not_survive() {
        val file = crafted {
            val data = "x".toByteArray()
            putArchiveEntry(TarArchiveEntry("home/projects/s").apply { size = 1; mode = 0x8000 or 2541 })
            write(data)
            closeArchiveEntry()
        }
        restore(file = file)
        val mode = Files.getAttribute(File(phone, "projects/s").toPath(), "unix:mode") as Int
        assertEquals(0, mode and 3584)
        assertEquals(493, mode and 511)
    }

    @Test fun a_file_with_no_manifest_is_damage() {
        val bytes = ByteArrayOutputStream()
        EncryptingOutput(bytes, pass, iterations = 1000).use { sealed ->
            TarArchiveOutputStream(GZIPOutputStream(sealed), "UTF-8").use { it.file("home/projects/x") }
        }
        val file = File(base, "nomanifest.backup").apply { writeBytes(bytes.toByteArray()) }
        assertUntouched({ restore(file = file) }, DamagedBackup::class.java)
    }

    @Test fun long_names_survive() {
        val long = "projects/" + "d".repeat(150) + "/" + "f".repeat(150) + ".txt"
        home.put(long, "deep")
        backUp()
        restore()
        assertEquals("deep", File(phone, long).readText())
    }

    @Test fun plain_gzip_input_is_not_trusted() {
        assertThrows(DamagedBackup::class.java) {
            BackupRunner.preview({ ByteArrayInputStream("not encrypted at all, not even close to it".toByteArray()) }, pass, phone)
        }
    }
}
