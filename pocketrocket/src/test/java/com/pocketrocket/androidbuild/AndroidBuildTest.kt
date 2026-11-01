package com.pocketrocket.androidbuild

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class AndroidBuildTest {
    private val script = File("../setup/pocketrocket-setup.sh")

    /** Runs a function from the real setup script. */
    private fun shell(code: String, vararg args: String): Pair<Int, String> {
        val p = ProcessBuilder(
            listOf("bash", "-c", "PREFIX=/tmp/pr-none POCKETROCKET_SETUP_SOURCE_ONLY=1 . \"$1\"; shift; $code", "_", script.path) + args,
        ).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        return p.waitFor() to out
    }

    @Test fun licenseIsReadFromTheManifest() {
        val manifest = """<sdk><license id="android-sdk-preview-license" type="text">preview</license>
            <license id="android-sdk-license" type="text">Terms &amp; Conditions
            January 16, 2019
            </license><remotePackage/></sdk>"""
        val text = SdkLicense.parse(manifest)!!
        assertTrue(text.startsWith("Terms & Conditions"))
        assertTrue(text.endsWith("January 16, 2019"))
    }

    @Test fun aManifestWithoutTheLicenseCannotBeAccepted() {
        assertNull(SdkLicense.parse("<sdk><license id=\"android-sdk-preview-license\">x</license></sdk>"))
        assertNull(SdkLicense.parse("<sdk><license id=\"android-sdk-license\"></license></sdk>"))
        assertNull(SdkLicense.parse("<license id=\"android-sdk-license\">never closed"))
    }

    @Test fun sessionsGetTheSamePathsTheScriptUses() {
        val env = AndroidBuildTool.environment("/p/usr")
        assertEquals("/p/usr/opt/android-sdk", env["ANDROID_HOME"])
        assertEquals("/p/usr/lib/jvm/java-17-openjdk", env["JAVA_HOME"])
        if (!script.exists()) return
        assertEquals("/tmp/pr-none/opt/android-sdk", shell("printf %s \"\$ANDROID_SDK_DIR\"").second)
        assertEquals("/tmp/pr-none/lib/jvm/java-17-openjdk", shell("printf %s \"\$ANDROID_JAVA_HOME\"").second)
    }

    @Test fun gradlePropertiesKeepTheUsersOwnLines() {
        if (!script.exists()) return
        val file = File.createTempFile("gradle", ".properties").apply { writeText("org.gradle.parallel=true\n"); deleteOnExit() }
        assertEquals(0, shell("android_gradle_props_add \"$1\" /p/bin/aapt2", file.path).first)
        assertEquals(0, shell("android_gradle_props_add \"$1\" /p/bin/aapt2", file.path).first) // Retry adds nothing twice
        val added = file.readText()
        assertEquals(1, Regex("aapt2FromMavenOverride=/p/bin/aapt2").findAll(added).count())
        assertTrue(added.startsWith("org.gradle.parallel=true"))
        assertEquals(0, shell("android_gradle_props_remove \"$1\"", file.path).first)
        assertEquals("org.gradle.parallel=true\n", file.readText())
    }

    @Test fun gradlePropertiesGoWhenOnlyOurLinesWereThere() {
        if (!script.exists()) return
        val dir = File.createTempFile("gradle", "").apply { delete(); mkdirs(); deleteOnExit() }
        val file = File(dir, "gradle.properties")
        shell("android_gradle_props_add \"$1\" /p/bin/aapt2", file.path)
        assertTrue(file.exists())
        shell("android_gradle_props_remove \"$1\"", file.path)
        assertFalse(file.exists())
        dir.delete()
    }

    @Test fun packageXmlIsWellFormedForBothKinds() {
        if (!script.exists()) return
        val dir = File.createTempFile("pkg", "").apply { delete(); mkdirs() }
        try {
            shell("android_package_xml \"$1\" 'build-tools;36.0.0' 36 0 0 'Android SDK Build-Tools 36'", dir.path)
            var doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(dir, "package.xml"))
            assertEquals("build-tools;36.0.0", doc.getElementsByTagName("localPackage").item(0).attributes.getNamedItem("path").nodeValue)

            shell("android_package_xml \"$1\" 'platforms;android-37.0' 2 0 0 'Android SDK Platform 37.0' 37.0 22", dir.path)
            doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(dir, "package.xml"))
            assertEquals("37.0", doc.getElementsByTagName("api-level").item(0).textContent)
            assertEquals("2", doc.getElementsByTagName("major").item(0).textContent)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun uninstallKeepsPackagesOthersNeed() {
        if (!script.exists()) return
        fun removable(sim: String, vararg allowed: String) = shell("android_removable \"$1\" \"\${@:2}\"", sim, *allowed).first == 0
        val onlyOurs = "Remv gradle [1]\nRemv openjdk-17 [17]\n"
        assertTrue(removable(onlyOurs, "gradle", "openjdk-17", "aapt2"))
        assertFalse(removable("Remv maven [3]\nRemv openjdk-17 [17]\n", "gradle", "openjdk-17"))
        assertTrue(removable("Nothing to do\n", "gradle"))
        assertTrue(removable("Purg gradle [1]\n", "gradle"))
        assertFalse(removable("Purg maven [3]\nPurg gradle [1]\n", "gradle"))
    }

    @Test fun uninstallListHoldsOnlyWhatTheInstallBroughtIn() {
        if (!script.exists()) return
        val before = File.createTempFile("before", "").apply { writeText("git\ncurl\nbash\n"); deleteOnExit() }
        val now = File.createTempFile("now", "").apply { writeText("bash\nopenjdk-17\ngit\ngradle\ncurl\nopenjdk-17-x\n"); deleteOnExit() }
        val (code, out) = shell("android_new_packages \"$1\" \"$2\"", before.path, now.path)
        assertEquals(0, code)
        assertEquals(listOf("gradle", "openjdk-17", "openjdk-17-x"), out.trim().lines())
    }

    @Test fun sdkZipsMustMatchTheirChecksum() {
        if (!script.exists()) return
        val file = File.createTempFile("zip", "").apply { writeText("x"); deleteOnExit() }
        val right = java.security.MessageDigest.getInstance("SHA-1").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals(0, shell("verify_sha1 \"$1\" \"$2\"", file.path, right).first)
        assertNotEquals(0, shell("verify_sha1 \"$1\" \"$2\"", file.path, "0".repeat(40)).first)
        assertNotEquals(0, shell("verify_sha1 \"$1\" \"$2\"", "/no/such/file", right).first)
    }

    @Test fun scriptRefusesTheSdkWithoutAcceptance() {
        if (!script.exists()) return
        val (code, out) = shell("PR_LOG=/dev/null TOTAL_STEPS=4; unset POCKETROCKET_SDK_LICENSE_HASH; step_android_sdk")
        assertNotEquals(0, code)
        assertTrue(out, out.contains("license was not accepted"))
    }
}
