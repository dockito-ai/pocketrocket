package com.pocketrocket.pkgtools

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Decision 102: uninstalling a tool removes everything it put on the phone, its config and credentials included,
 * whoever wrote them (keys set up by hand in ~/.aws too). Your own files stay.
 */
class UninstallLeftoversTest {
    private val script = File("../setup/pocketrocket-setup.sh")
    private val root = File.createTempFile("leftovers", "").apply { delete(); mkdirs() }
    private val prefix = File(root, "usr")
    private val home = File(root, "home")

    @After fun cleanUp() {
        root.deleteRecursively()
    }

    private fun file(relative: String, base: File = home) = File(base, relative).apply { parentFile?.mkdirs(); writeText("x") }

    /** Runs [code] with the script sourced, its PREFIX and HOME in the temp folder and no package installed.
     *  XDG_* is cleared: the script follows it, and GitHub's runner sets XDG_CONFIG_HOME outside this home. */
    private fun shell(code: String): Pair<Int, String> {
        val pb = ProcessBuilder(listOf("bash", "-c", ". \"$1\"; pkg_installed() { return 1; }; PR_LOG=/dev/null; $code", "_", script.path))
            .redirectErrorStream(true)
        pb.environment().apply {
            putAll(mapOf("PREFIX" to prefix.path, "HOME" to home.path, "POCKETROCKET_SETUP_SOURCE_ONLY" to "1"))
            remove("XDG_CONFIG_HOME")
            remove("XDG_CACHE_HOME")
            remove("XDG_DATA_HOME")
            remove("XDG_STATE_HOME")
        }
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        return p.waitFor() to out
    }

    private fun cleanup(tool: String, vararg paths: File) {
        val (exit, out) = shell("pkg_tool_cleanup $tool")
        assertEquals(out, 0, exit)
        paths.forEach { assertFalse("$tool left ${it.relativeTo(root)}", it.exists()) }
    }

    @Test fun pythonTakesPipUvAndTheirCredentials() {
        if (!script.exists()) return
        cleanup(
            "python",
            file(".cache/pip/http/a"), file(".config/pip/pip.conf"), file(".pypirc"), file(".python_history"),
            file(".config/uv/uv.toml"), file(".cache/uv/a"), file(".local/share/uv/tools/ruff/a"), file(".local/lib/python3.12/site-packages/a"),
        )
    }

    @Test fun uvsFoldersFollowXdgLikeTheRest() {
        if (!script.exists()) return
        val cache = file("xdg-cache/uv/a")
        val data = file("xdg-data/uv/tools/ruff/a")
        val (exit, out) = shell("XDG_CACHE_HOME=\"${'$'}HOME/xdg-cache\" XDG_DATA_HOME=\"${'$'}HOME/xdg-data\"; pkg_tool_cleanup python")
        assertEquals(out, 0, exit)
        assertFalse(cache.exists())
        assertFalse(data.exists())
    }

    @Test fun bunDenoAndPhpTakeTheirFolders() {
        if (!script.exists()) return
        cleanup("bun", file(".bun/install/cache/a"), file(".bun/bin/cowsay"), file(".bunfig.toml"))
        cleanup("deno", file(".cache/deno/a"), file(".deno/bin/hello"))
        cleanup("php", file("etc/php/php.ini", prefix), file("lib/php/extensions/redis.so", prefix), file(".pearrc"))
    }

    @Test fun rubyTakesGemCredentialsAndBundlerConfig() {
        if (!script.exists()) return
        cleanup("ruby", file(".gem/credentials"), file(".gemrc"), file(".bundle/config"), file(".irb_history"))
    }

    @Test fun awsGoesWhollyKeysSetUpByHandIncluded() {
        if (!script.exists()) return
        cleanup("aws", file(".aws/credentials"), file(".aws/config"), file(".aws/sso/cache/a.json"))
    }

    @Test fun yourOwnFilesStay() {
        if (!script.exists()) return
        val mine = listOf(file("projects/app/main.py"), file(".config/git/config"), file(".bashrc"))
        listOf("python", "bun", "go", "deno", "ruby", "php", "aws", "flyctl").forEach { assertEquals(0, shell("pkg_tool_cleanup $it").first) }
        mine.forEach { assertTrue("${it.relativeTo(root)} went", it.exists()) }
    }

    @Test fun theNpmClisTakeTheirConfigCachesAndUpdateChecks() {
        if (!script.exists()) return
        val (_, netlify) = shell("npm_tool_config netlify")
        listOf(".config/netlify", ".local/share/netlify", ".cache/netlify", ".netlify", ".config/configstore/update-notifier-netlify-cli.json")
            .forEach { assertTrue(it, netlify.split(" ").contains("$home/$it")) }
        listOf("vercel" to "vercel", "firebase" to "firebase-tools").forEach { (name, pkg) ->
            assertTrue(name, shell("npm_tool_config $name").second.contains("$home/.config/configstore/update-notifier-$pkg.json"))
        }
    }

    @Test fun headlessChromeTakesItsProfilesAndTheServersState() {
        if (!script.exists()) return
        val paths = listOf(file(".config/chromium/Default/Cookies"), file(".cache/chromium/a"), file(".cache/chrome-devtools-mcp/a"))
        assertEquals(0, shell("chromium_remove_leftovers").first)
        paths.forEach { assertFalse(it.path, it.exists()) }
        assertFalse(File(home, ".config").exists())
    }
}
