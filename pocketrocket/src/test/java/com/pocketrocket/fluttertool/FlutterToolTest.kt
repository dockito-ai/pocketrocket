package com.pocketrocket.fluttertool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FlutterToolTest {
    private val script = File("../setup/pocketrocket-setup.sh")
    private val launcher = File("../setup/flutter")

    /** Flutter's releases_linux.json, cut down: stable, beta and an older stable listed twice. */
    private val releases = """
        {"base_url": "https://storage.googleapis.com/flutter_infra_release/releases",
         "current_release": {"beta": "b1", "dev": "d1", "stable": "s3"},
         "releases": [
           {"hash": "s3", "channel": "stable", "version": "3.47.9", "release_date": "2026-10-29T19:22:44.517205Z"},
           {"hash": "b1", "channel": "beta", "version": "3.50.0-0.3.pre", "release_date": "2026-10-29T17:31:39Z"},
           {"hash": "s2", "channel": "stable", "version": "3.47.10", "release_date": "2026-10-20T10:00:00Z"},
           {"hash": "s1", "channel": "stable", "version": "3.44.1", "release_date": "2026-08-01T10:00:00Z"},
           {"hash": "s1", "channel": "stable", "version": "3.44.1", "release_date": "2026-08-01T10:00:00Z"},
           {"hash": "s0", "channel": "stable", "version": "v1.0.0", "release_date": "2018-12-04T10:00:00Z"}
         ]}
    """.trimIndent()

    /** Runs code with a file of the repo's setup folder sourced. */
    private fun shell(file: File, sourceOnly: String, code: String, vararg args: String): Pair<Int, String> {
        val p = ProcessBuilder(
            listOf("bash", "-c", "export PREFIX=/tmp/pr-none; $sourceOnly=1 . \"$1\"; shift; $code", "_", file.path) + args,
        ).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        return p.waitFor() to out
    }

    private fun hasJq() = runCatching { ProcessBuilder("jq", "--version").start().waitFor() == 0 }.getOrDefault(false)

    @Test fun releaseListGivesStablesNewestFirstOnce() {
        val list = FlutterTool.stableReleases(releases)
        assertEquals(listOf("3.47.10", "3.47.9", "3.44.1"), list.map { it.version })
        assertEquals("2026-10-29", list[1].date)
        assertEquals(listOf("3.47.10"), FlutterTool.stableReleases(releases, limit = 1).map { it.version })
        assertTrue(FlutterTool.stableReleases("not json").isEmpty())
    }

    @Test fun latestStableIsTheOneTheListNames() {
        assertEquals("3.47.9", FlutterTool.latestStable(releases))
        assertNull(FlutterTool.latestStable("""{"releases": []}"""))
    }

    @Test fun versionsOrderByNumberAndPreReleasesComeFirst() {
        assertTrue(FlutterTool.compareVersions("3.47.10", "3.47.9") > 0)
        assertTrue(FlutterTool.compareVersions("3.50.0-0.3.pre", "3.50.0") < 0)
        assertTrue(FlutterTool.compareVersions("3.50.0-0.3.pre", "3.47.9") > 0)
        assertEquals(0, FlutterTool.compareVersions("3.44.1", "3.44.1"))
        assertTrue(FlutterTool.compareVersions("stable", "3.44.1") > 0)
        assertEquals(listOf("3.47.10", "3.47.9", "3.44.1", "stable"), FlutterTool.installedVersions(listOf("3.44.1", "stable", ".staging-3.47.9", "3.47.10", "3.47.9")))
    }

    @Test fun updatesOfferOnlyANewerStableForAStableDefault() {
        assertTrue(FlutterTool.newerStable("3.44.1", "3.47.9"))
        assertFalse(FlutterTool.newerStable("3.47.9", "3.47.9"))
        assertFalse(FlutterTool.newerStable("3.47.10", "3.47.9"))
        assertFalse(FlutterTool.newerStable("stable", "3.47.9"))
        assertFalse(FlutterTool.newerStable(null, "3.47.9"))
        assertFalse(FlutterTool.newerStable("3.44.1", null))
    }

    @Test fun sizesReadAsAndroidShowsThem() {
        assertEquals("1.1 GB", FlutterTool.size(1_120_000_000))
        assertEquals("590 MB", FlutterTool.size(590_400_000))
        assertEquals("Under 1 MB", FlutterTool.size(4_000))
        assertEquals("1.1 GB", FlutterTool.sizeMb(FlutterTool.FLUTTER_INSTALLED_MB))
    }

    @Test fun flutterAndRustJobsBelongToTheirOwnTool() {
        val flutter = FlutterAction.entries.filter { it.part == FlutterPart.Flutter }
        val rust = FlutterAction.entries.filter { it.part == FlutterPart.Rust }
        assertEquals(setOf(FlutterAction.InstallFlutter, FlutterAction.UninstallFlutter), flutter.filter { it.whole }.toSet())
        assertEquals(setOf(FlutterAction.InstallRust, FlutterAction.RemoveRust), rust.toSet())
        assertTrue(rust.all { it.whole })
        assertTrue(FlutterRustState(action = FlutterAction.RemoveRust).isFor(FlutterPart.Rust))
        assertFalse(FlutterRustState(action = FlutterAction.RemoveRust).isFor(FlutterPart.Flutter))
        assertFalse(FlutterRustState().isFor(FlutterPart.Flutter))
        assertEquals(5, FlutterTool.flutterSteps(null).size)
        assertEquals("Downloading Flutter 3.47.9", FlutterTool.flutterSteps("3.47.9")[2])
        assertTrue(FlutterTool.FLUTTER_REMOVE_STEPS.none { "Rust" in it })
    }

    @Test fun rustBridgeAppsAreFlutterAppsWithARustCrate() {
        val root = java.nio.file.Files.createTempDirectory("ws").toFile()
        try {
            fun app(name: String, pubspec: Boolean, crate: Boolean) = File(root, name).apply {
                mkdirs()
                if (pubspec) File(this, "pubspec.yaml").writeText("name: $name\n")
                if (crate) File(this, "rust").apply { mkdirs(); File(this, "Cargo.toml").writeText("[package]\n") }
            }
            val bridge = app("bridge", pubspec = true, crate = true)
            val plain = app("plain", pubspec = true, crate = false)
            val crateOnly = app("crate", pubspec = false, crate = true)
            assertEquals(listOf(bridge), FlutterTool.rustBridgeProjects(listOf(plain, bridge, crateOnly, File(root, "missing"))))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun theScriptReadsTheSameReleaseList() {
        if (!script.exists() || !hasJq()) return
        val json = File.createTempFile("releases", ".json").apply { writeText(releases); deleteOnExit() }
        assertEquals("3.47.9 s3\n", shell(script, "POCKETROCKET_SETUP_SOURCE_ONLY", "flutter_release \"$1\"", json.path).second)
        assertEquals("3.44.1 s1\n", shell(script, "POCKETROCKET_SETUP_SOURCE_ONLY", "flutter_release \"$1\" 3.44.1", json.path).second)
        assertEquals("9.9.9 \n", shell(script, "POCKETROCKET_SETUP_SOURCE_ONLY", "flutter_release \"$1\" 9.9.9", json.path).second)
        assertEquals(1, shell(script, "POCKETROCKET_SETUP_SOURCE_ONLY", "flutter_release \"$1\" '../x'", json.path).first)
    }

    @Test fun uninstallRemovesFvmTheLaunchersAndTheCaches() {
        if (!script.exists()) return
        val paths = shell(script, "POCKETROCKET_SETUP_SOURCE_ONLY", "flutter_remove_paths").second.lines().filter { it.isNotBlank() }
        val home = System.getProperty("user.home")
        listOf("/fvm", "/.pub-cache", "/.dart-tool", "/.dartServer", "/.flutter", "/.config/flutter", "/.config/fvm").forEach { assertTrue(it, "$home$it" in paths) }
        listOf("/tmp/pr-none/opt/fvm", "/tmp/pr-none/bin/flutter", "/tmp/pr-none/bin/dart", "/tmp/pr-none/bin/fvm").forEach { assertTrue(it, it in paths) }
        assertTrue(paths.none { it == home || it == "$home/" || it == "/tmp/pr-none" })
        assertEquals(0, shell(script, "POCKETROCKET_SETUP_SOURCE_ONLY", "flutter_version_name_ok 3.47.9").first)
        listOf("", "..", "../x", "a/b").forEach { assertEquals(it, 1, shell(script, "POCKETROCKET_SETUP_SOURCE_ONLY", "flutter_version_name_ok \"$1\"", it).first) }
    }

    @Test fun removalTakesOnlyThePackagesTheInstallAdded() {
        if (!script.exists()) return
        val dir = File.createTempFile("pkgs", "").apply { delete(); mkdirs() }
        try {
            File(dir, "before").writeText("bash\ngit\nunzip\n")
            File(dir, "after").writeText("bash\nrust\ngit\nrust-std-aarch64-linux-android\nunzip\n")
            val out = shell(script, "POCKETROCKET_SETUP_SOURCE_ONLY", "android_new_packages \"$1/before\" \"$1/after\"", dir.path).second
            assertEquals(listOf("rust", "rust-std-aarch64-linux-android"), out.lines().filter { it.isNotBlank() })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun theScriptOrdersVersionsAsTheApp() {
        if (!script.exists()) return
        val names = listOf("3.44.1", "stable", "3.50.0", "3.47.10", "3.50.0-0.3.pre", "3.47.9")
        val out = shell(script, "POCKETROCKET_SETUP_SOURCE_ONLY", "printf '%s\\n' \"$@\" | flutter_version_order", *names.toTypedArray())
        assertEquals(FlutterTool.installedVersions(names), out.second.lines().filter { it.isNotBlank() })
        assertEquals(listOf("3.50.0", "3.50.0-0.3.pre", "3.47.10", "3.47.9", "3.44.1", "stable"), FlutterTool.installedVersions(names))
        val dir = File.createTempFile("fvm", "").apply { delete(); mkdirs() }
        try {
            names.forEach { File(dir, "versions/$it/bin").mkdirs(); File(dir, "versions/$it/bin/flutter").writeText("") }
            val newest = "FLUTTER_VERSIONS=\"$1/versions\"; flutter_newest_installed \"$2\""
            assertEquals("3.50.0\n", shell(script, "POCKETROCKET_SETUP_SOURCE_ONLY", newest, dir.path, "").second)
            assertEquals("3.50.0-0.3.pre\n", shell(script, "POCKETROCKET_SETUP_SOURCE_ONLY", newest, dir.path, "3.50.0").second)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun theTileShowsNoFlutterSizeWithoutVersions() {
        assertEquals(0, FlutterTool.flutterSizeMb(installed = true, versions = 0, measuredBytes = null))
        assertEquals(0, FlutterTool.flutterSizeMb(installed = false, versions = 0, measuredBytes = null))
        assertEquals(FlutterTool.FLUTTER_INSTALLED_MB * 2, FlutterTool.flutterSizeMb(installed = true, versions = 2, measuredBytes = null))
        assertEquals(1234, FlutterTool.flutterSizeMb(installed = true, versions = 1, measuredBytes = 1_234_500_000))
    }

    @Test fun aLaterRunRecordsOnlyWhatItAddedAndSharedPackagesChangeHands() {
        if (!script.exists()) return
        val dir = File.createTempFile("pkgs", "").apply { delete(); mkdirs() }
        try {
            fun run(code: String) = shell(script, "POCKETROCKET_SETUP_SOURCE_ONLY", "D=\"$1\"; TMPDIR=\"$1\"; info() { :; }; android_installed_packages() { cat \"\$D/now\"; }; $code", dir.path)
            fun lines(name: String) = File(dir, name).takeIf { it.exists() }?.readLines()?.filter { it.isNotBlank() }
            // The install: which was added.
            File(dir, "now").writeText("bash\nunzip\n")
            run("packages_baseline \"$1/base\"")
            File(dir, "now").writeText("bash\nunzip\nwhich\n")
            run("packages_record \"$1/base\" \"$1/flutter\"")
            assertEquals(listOf("which"), lines("flutter"))
            assertFalse(File(dir, "base").exists())
            // The Android tool comes next, then Add version: nothing new, and Android's packages are not counted.
            File(dir, "now").writeText("bash\nunzip\nwhich\nopenjdk-17\ngradle\n")
            run("packages_baseline \"$1/base\"; packages_record \"$1/base\" \"$1/flutter\"")
            assertEquals(listOf("which"), lines("flutter"))
            // Removing the Android tool while Flutter is there hands unzip over.
            File(dir, "android").writeText("openjdk-17\ngradle\nunzip\n")
            run("packages_hand_over \"$1/android\" \"$1/flutter\" which unzip")
            assertEquals(listOf("openjdk-17", "gradle"), lines("android"))
            assertEquals(listOf("unzip", "which"), lines("flutter"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun theLauncherFindsTheNearestPin() {
        if (!launcher.exists()) return
        val root = File.createTempFile("pin", "").apply { delete(); mkdirs() }
        try {
            val deep = File(root, "app/lib/src").apply { mkdirs() }
            File(root, "app/.fvmrc").writeText("{\n  \"flutter\": \"3.44.1\",\n  \"flavors\": {\"prod\": \"3.47.9\"}\n}\n")
            File(root, ".fvmrc").writeText("{\"flutter\": \"3.47.9\"}")
            val found = shell(launcher, "POCKETROCKET_FLUTTER_SOURCE_ONLY", "pr_find_pin \"$1\" && printf '%s %s' \"\$PR_PIN\" \"\$PR_PIN_FILE\"", deep.path)
            assertEquals("3.44.1 ${root.path}/app/.fvmrc", found.second)

            File(root, "app/.fvmrc").delete()
            File(root, "app/.fvm").mkdirs()
            File(root, "app/.fvm/fvm_config.json").writeText("{\"flutterSdkVersion\": \"2.10.5\", \"flavors\": {}}")
            assertEquals("2.10.5", shell(launcher, "POCKETROCKET_FLUTTER_SOURCE_ONLY", "pr_find_pin \"$1\" && printf %s \"\$PR_PIN\"", deep.path).second)

            File(root, "app/.fvm/fvm_config.json").writeText("{}")
            assertEquals("3.47.9", shell(launcher, "POCKETROCKET_FLUTTER_SOURCE_ONLY", "pr_find_pin \"$1\" && printf %s \"\$PR_PIN\"", deep.path).second)
            File(root, ".fvmrc").delete()
            assertEquals(1, shell(launcher, "POCKETROCKET_FLUTTER_SOURCE_ONLY", "pr_find_pin \"$1\"", deep.path).first)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun theLauncherBuildsDebugApksForArm64AndRefusesReleaseOnes() {
        if (!launcher.exists()) return
        fun apk(vararg args: String) = shell(launcher, "POCKETROCKET_FLUTTER_SOURCE_ONLY", "pr_apk_build \"$@\"", *args).first == 0
        assertTrue(apk("build", "apk", "--debug", "--flavor", "dev"))
        assertTrue(apk("-v", "build", "appbundle"))
        assertFalse(apk("build", "web"))
        assertFalse(apk("analyze"))
        assertFalse(apk("run", "apk"))

        val setup = "PR_EXEC_DIR=\"$1\"; shift; PR_EXTRA_ARGS=(); pr_apk_setup \"$@\" && " +
            "printf '%s|%s|%s' \"\${PR_EXTRA_ARGS[*]}\" \"\${PR_RUN_ENV[*]}\" \"\$POCKETROCKET_GLIBC_PRELOAD\""
        val dir = File.createTempFile("exec", "").apply { delete(); mkdirs() }
        try {
            val release = shell(launcher, "POCKETROCKET_FLUTTER_SOURCE_ONLY", setup, dir.path, "build", "apk")
            assertEquals(1, release.first)
            assertTrue(release.second, "gen_snapshot" in release.second)
            val noHelper = shell(launcher, "POCKETROCKET_FLUTTER_SOURCE_ONLY", setup, dir.path, "build", "apk", "--debug")
            assertEquals(1, noHelper.first)
            assertTrue(noHelper.second, "C and C++" in noHelper.second)

            File(dir, "aarch64").mkdirs(); File(dir, "aarch64/2.so").writeText("")
            File(dir, "\$PLATFORM").mkdirs(); File(dir, "\$PLATFORM/1.so").writeText("")
            val preload = "${dir.path}/\$PLATFORM/1.so:${dir.path}/\$PLATFORM/2.so"
            val debug = shell(launcher, "POCKETROCKET_FLUTTER_SOURCE_ONLY", setup, dir.path, "build", "apk", "--debug")
            assertEquals(0 to "--target-platform android-arm64|ORG_GRADLE_PROJECT_disable-abi-filtering=true|$preload", debug)
            val chosen = shell(launcher, "POCKETROCKET_FLUTTER_SOURCE_ONLY", setup, dir.path, "build", "apk", "--debug", "--target-platform=android-arm")
            assertEquals(0 to "|ORG_GRADLE_PROJECT_disable-abi-filtering=true|$preload", chosen)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun theApkPreflightNamesTheAddOnsAProjectNeeds() {
        if (!launcher.exists()) return
        val dir = File.createTempFile("preflight", "").apply { delete(); mkdirs() }
        try {
            fun write(path: String, text: String = "") = File(dir, path).apply { parentFile!!.mkdirs(); writeText(text) }
            // An app with three plugins: Rust through its repository's cargokit, a CMake build, and one that only names
            // externalNativeBuild tasks (as Rive does), which needs nothing.
            write("repo/cargokit/gradle/plugin.gradle")
            write("repo/rusty/android/build.gradle", "apply from: \"../../cargokit/gradle/plugin.gradle\"\ncargokit {\n}\n")
            write("cmake_plugin/android/build.gradle", "android {\n  externalNativeBuild {\n    cmake { path \"CMakeLists.txt\" }\n  }\n}\n")
            write("named/android/build.gradle", "// externalNativeBuild tasks\ntasks.matching { it.name.startsWith(\"externalNativeBuild\") }\n")
            write("app/pubspec.yaml", "name: app\n")
            write("app/android/app/build.gradle", "android {\n}\n")
            write("app/lib/main.dart")
            fun plugin(name: String, path: String) = "{\"name\":\"$name\",\"path\":\"${dir.path}/$path/\",\"native_build\":true,\"dependencies\":[],\"dev_dependency\":false}"
            fun deps(vararg plugins: String) = write(
                "app/.flutter-plugins-dependencies",
                "{\"info\":\"x\",\"plugins\":{\"ios\":[${plugin("ios_only", "named")}],\"android\":[${plugins.joinToString(",")}]," +
                    "\"macos\":[]},\"dependencyGraph\":[{\"name\":\"rusty\",\"dependencies\":[]}]}",
            )
            // The phone: the SDK, the NDK's ndk-build, and cargo and rustup on PATH, each one taken away below.
            write("sdk/platforms/android-36/package.xml"); write("sdk/build-tools/36.0.0/package.xml")
            write("java/bin/java").setExecutable(true)
            write("ndk-build").setExecutable(true)
            listOf("cargo", "rustup").forEach { write("bin/$it", "#!/bin/sh\n").setExecutable(true) }
            fun preflight(setup: String = "") = shell(
                launcher, "POCKETROCKET_FLUTTER_SOURCE_ONLY",
                "D=\"$1\"; for c in sed awk; do ln -sf \"\$(command -v \$c)\" \"\$D/bin/\$c\"; done; PATH=\"\$D/bin\"; " +
                    "PR_SDK_DIR=\"\$D/sdk\"; PR_SDK_JAVA=\"\$D/java/bin/java\"; PR_NDK_BUILD=\"\$D/ndk-build\"; cd \"\$D/app/lib\"; $setup pr_apk_preflight",
                dir.path,
            )

            assertEquals(0 to "", preflight()) // no plugin list yet: pub get comes first
            deps(plugin("named", "named"))
            assertEquals(0 to "", preflight())
            assertEquals(1, preflight("PR_SDK_DIR=/none;").first)
            assertTrue(preflight("PR_SDK_DIR=/none;").second.contains("needs Android: add it from Settings → Tools"))

            deps(plugin("named", "named"), plugin("cmake_plugin", "cmake_plugin"))
            assertEquals(0 to "", preflight())
            val noNdk = preflight("PR_NDK_BUILD=/none;")
            assertEquals(1, noNdk.first)
            assertTrue(noNdk.second, noNdk.second.contains("native code (cmake_plugin): add Android NDK from Settings → Tools"))

            deps(plugin("named", "named"), plugin("rusty", "repo/rusty"))
            assertEquals(0 to "", preflight())
            File(dir, "bin/cargo").delete()
            val noRust = preflight("PR_NDK_BUILD=/none;")
            assertEquals(1, noRust.first)
            assertTrue(noRust.second, noRust.second.contains("plugin with Rust code (rusty): add Rust from Settings → Tools"))
            assertTrue(noRust.second, noRust.second.contains("native code (rusty): add Android NDK"))
            assertTrue(noRust.second, noRust.second.contains("POCKETROCKET_FLUTTER_PREFLIGHT=0"))
            assertEquals(0 to "", preflight("POCKETROCKET_FLUTTER_PREFLIGHT=0;"))

            // A native build in the app's own Gradle file counts too.
            deps(plugin("named", "named"))
            write("app/android/app/build.gradle.kts", "android {\n    externalNativeBuild { cmake { path = file(\"x\") } }\n}\n")
            assertTrue(preflight("PR_NDK_BUILD=/none;").second.contains("native code (the app)"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun theLauncherRepreparesOnlyWhenFlutterDownloadedSomething() {
        if (!launcher.exists()) return
        val root = File.createTempFile("flutter", "").apply { delete(); mkdirs() }
        try {
            val cache = File(root, "bin/cache").apply { mkdirs() }
            File(cache, "engine-dart-sdk.stamp").writeText("abc\n")
            fun needs() = shell(launcher, "POCKETROCKET_FLUTTER_SOURCE_ONLY", "pr_needs_prepare \"$1\"", root.path).first == 0
            assertTrue(needs()) // never prepared
            shell(launcher, "POCKETROCKET_FLUTTER_SOURCE_ONLY", "pr_signature \"$1\"; printf '%s\\n' \"\$PR_SIG\" >\"$1/bin/cache/.pocketrocket-prepared\"", root.path)
            assertFalse(needs())
            File(cache, "linux-sdk.stamp").writeText("def\n") // Flutter fetched engine tools
            assertTrue(needs())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun theLauncherPointsFlutterScriptsAndPutsThemBackForGit() {
        if (!launcher.exists()) return
        val dir = File.createTempFile("flutter", "").apply { delete(); mkdirs() }
        try {
            val prefix = File(dir, "usr").apply { File(this, "bin").mkdirs() }
            File(prefix, "bin/bash").apply { writeText("#!/bin/sh\n"); setExecutable(true) }
            val root = File(dir, "flutter")
            fun git(vararg args: String) = ProcessBuilder(listOf("git", "-C", root.path, "-c", "user.name=t", "-c", "user.email=t@t") + args)
                .redirectErrorStream(true).start().let { it.inputStream.bufferedReader().readText(); it.waitFor() }
            File(root, "bin/internal").mkdirs()
            File(root, "bin/flutter").writeText("#!/usr/bin/env bash\necho flutter\n")
            File(root, "bin/internal/shared.sh").writeText("#!/usr/bin/env bash\necho one\n")
            File(root, "bin/internal/engine.version").writeText("abc\n")
            assertEquals(0, git("init", "-q"))
            git("add", "."); git("commit", "-qm", "one"); git("tag", "one")
            File(root, "bin/internal/shared.sh").writeText("#!/usr/bin/env bash\necho two\n")
            git("commit", "-qam", "two"); git("tag", "two")
            assertEquals(0, git("checkout", "-q", "one"))

            fun launcher(code: String) = shell(launcher, "POCKETROCKET_FLUTTER_SOURCE_ONLY", "PREFIX=\"$2\"; $code", root.path, prefix.path)
            fun status() = ProcessBuilder("git", "-C", root.path, "status", "--porcelain").start().inputStream.bufferedReader().readText()
            val pointed = "#!${prefix.path}/bin/bash"
            assertEquals(0, launcher("pr_fix_scripts \"$1\"").first)
            assertEquals(pointed, File(root, "bin/flutter").readLines().first())
            assertEquals(pointed, File(root, "bin/internal/shared.sh").readLines().first())
            assertEquals("abc", File(root, "bin/internal/engine.version").readText().trim())
            assertEquals("", status()) // git ignores the change
            val once = File(root, "bin/internal/shared.sh").readText()
            launcher("pr_fix_scripts \"$1\"")
            assertEquals(once, File(root, "bin/internal/shared.sh").readText()) // again changes nothing
            assertEquals(0, launcher("pr_scripts_ready \"$1\"").first) // ready

            // Put back, git moves the checkout over a changed script, and the scripts are pointed again.
            assertEquals(0, launcher("pr_restore_scripts \"$1\"").first)
            assertEquals("#!/usr/bin/env bash", File(root, "bin/flutter").readLines().first())
            assertEquals(0, git("reset", "-q", "--hard", "two"))
            assertEquals("echo two", File(root, "bin/internal/shared.sh").readLines()[1])
            assertEquals(1, launcher("pr_scripts_ready \"$1\"").first)
            launcher("pr_fix_scripts \"$1\"")
            assertEquals(pointed, File(root, "bin/internal/shared.sh").readLines().first())
            assertEquals("", status())

            fun moves(args: String) = launcher("pr_moves_checkout $args").first == 0
            assertTrue(moves("upgrade")); assertTrue(moves("-v upgrade")); assertTrue(moves("downgrade")); assertTrue(moves("channel beta"))
            assertFalse(moves("channel")); assertFalse(moves("analyze")); assertFalse(moves("--version")); assertFalse(moves("pub upgrade"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
