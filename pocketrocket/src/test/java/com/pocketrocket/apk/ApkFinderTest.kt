package com.pocketrocket.apk

import com.pocketrocket.files.FileFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Decision 99: which APKs a finished command wrote. */
class ApkFinderTest {
    @get:Rule val tmp = TemporaryFolder()

    private val start = 1_800_000_000_000L
    private val end = start + 60_000

    private fun apk(root: File, path: String, modified: Long = start + 30_000, bytes: Int = 2048): File =
        File(root, path).apply { parentFile!!.mkdirs(); writeBytes(ByteArray(bytes)); setLastModified(modified) }

    private fun find(folder: File, command: String, output: String = "") =
        ApkFinder.find(folder, tmp.root, command, output, start, end).map { File(it.path).relativeTo(folder.canonicalFile).path }

    @Test fun gradleOutputInAModule() {
        val repo = tmp.newFolder("repo")
        apk(repo, "app/build/outputs/apk/debug/app-debug.apk")
        assertEquals(listOf("app/build/outputs/apk/debug/app-debug.apk"), find(repo, "./gradlew :app:assembleDebug"))
    }

    @Test fun flutterOutputsAndTheAndroidFolder() {
        val repo = tmp.newFolder("repo")
        apk(repo, "build/app/outputs/flutter-apk/app-release.apk")
        apk(repo, "android/app/build/outputs/apk/release/app-release.apk")
        assertEquals(
            listOf("android/app/build/outputs/apk/release/app-release.apk", "build/app/outputs/flutter-apk/app-release.apk"),
            find(repo, "flutter build apk --release"),
        )
    }

    @Test fun oldApksAreLeftOut() {
        val repo = tmp.newFolder("repo")
        apk(repo, "app/build/outputs/apk/debug/app-debug.apk", modified = start - 3_600_000)
        assertEquals(emptyList<String>(), find(repo, "./gradlew assembleDebug"))
    }

    @Test fun aFewSecondsOfSlackEitherSide() {
        val repo = tmp.newFolder("repo")
        apk(repo, "app/build/outputs/apk/debug/a.apk", modified = start - 2_000)
        apk(repo, "app/build/outputs/apk/debug/b.apk", modified = end + 2_000)
        apk(repo, "app/build/outputs/apk/debug/c.apk", modified = end + 60_000)
        assertEquals(2, find(repo, "./gradlew assembleDebug").size)
    }

    @Test fun outputFoldersOnlySearchedForABuild() {
        val repo = tmp.newFolder("repo")
        apk(repo, "app/build/outputs/apk/debug/app-debug.apk")
        assertEquals(emptyList<String>(), find(repo, "git status"))
    }

    @Test fun intermediatesAreNotOutputs() {
        val repo = tmp.newFolder("repo")
        apk(repo, "app/build/intermediates/apk/debug/app-debug.apk")
        assertEquals(emptyList<String>(), find(repo, "./gradlew assembleDebug"))
    }

    @Test fun aNamedPathCountsWhereverItIs() {
        val repo = tmp.newFolder("repo")
        apk(repo, "dist/my app.apk")
        apk(repo, "out/release.apk")
        assertEquals(listOf("out/release.apk"), find(repo, "cp x.apk out/release.apk"))
        assertEquals(listOf("out/release.apk"), find(repo, "./build.sh", "✓ Built out/release.apk (20.1MB)."))
    }

    @Test fun relativePathsAfterCd() {
        val repo = tmp.newFolder("repo")
        apk(repo, "mobile/build/app/outputs/flutter-apk/app-debug.apk")
        assertEquals(
            listOf("mobile/build/app/outputs/flutter-apk/app-debug.apk"),
            find(repo, "cd mobile && flutter build apk --debug", "✓ Built build/app/outputs/flutter-apk/app-debug.apk"),
        )
    }

    @Test fun absoluteAndHomePaths() {
        val repo = tmp.newFolder("repo")
        val out = apk(tmp.root, "out/x.apk")
        assertEquals(1, ApkFinder.find(repo, tmp.root, "ls", "wrote ${out.path}", start, end).size)
        assertEquals(1, ApkFinder.find(repo, tmp.root, "cp a ~/out/x.apk", "", start, end).size)
    }

    @Test fun oneCardPerFileAndAtMostAFew() {
        val repo = tmp.newFolder("repo")
        (1..6).forEach { apk(repo, "app/build/outputs/apk/debug/app-$it.apk") }
        val found = ApkFinder.find(repo, tmp.root, "./gradlew assembleDebug && ls app/build/outputs/apk/debug/app-1.apk", "", start, end)
        assertEquals(ApkFinder.MAX_PER_RUN, found.size)
        assertEquals(found.map { it.path }.distinct(), found.map { it.path })
    }

    @Test fun mentionedPaths() {
        assertEquals(
            listOf("build/app/outputs/flutter-apk/app-release.apk", "/sdcard/Download/a.apk"),
            ApkFinder.mentioned("✓ Built build/app/outputs/flutter-apk/app-release.apk (20.1MB).\ncopied to '/sdcard/Download/a.apk'"),
        )
        assertEquals(emptyList<String>(), ApkFinder.mentioned("bundletool makes app.apks; see .apk files"))
    }

    @Test fun cdTargets() {
        assertEquals(listOf("android", "my app"), ApkFinder.cdTargets("cd android && ./gradlew assembleDebug; cd \"my app\""))
        assertEquals(emptyList<String>(), ApkFinder.cdTargets("./gradlew assembleDebug --abcd"))
    }

    @Test fun buildCommands() {
        assertTrue(ApkFinder.looksLikeBuild("./gradlew :app:assembleDebug 2>&1 | tail -20"))
        assertTrue(ApkFinder.looksLikeBuild("fvm flutter build apk"))
        assertTrue(ApkFinder.looksLikeBuild("make release"))
        assertFalse(ApkFinder.looksLikeBuild("git status && ls -la"))
    }

    @Test fun sizesReadAsInTheFileManager() {
        assertEquals("7.3 MB", FileFormat.size((7.3 * 1024 * 1024).toLong()))
        assertEquals("48 MB", FileFormat.size(48L * 1024 * 1024 + 5000))
    }
}
