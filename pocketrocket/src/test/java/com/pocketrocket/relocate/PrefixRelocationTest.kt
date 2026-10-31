package com.pocketrocket.relocate

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefixRelocationTest {

    @Test
    fun swapsPathsInBinaryContentKeepingLength() {
        val input = "\u0000#!/data/data/com.termux/files/usr/bin/sh\u0000data/data/com.termux/x\u0001".toByteArray()
        val bytes = input.copyOf()
        assertTrue(PrefixRelocation.rewrite(bytes, bytes.size))
        assertEquals(input.size, bytes.size)
        assertArrayEquals("\u0000#!/data/data/app.pocket/files/usr/bin/sh\u0000data/data/app.pocket/x\u0001".toByteArray(), bytes)
    }

    @Test
    fun swapsAppComponentPackageNameAndAmSocketButNotClassNames() {
        assertEquals("-n \"app.pocket/com.termux.app.TermuxOpenReceiver\"",
            PrefixRelocation.rewrite("-n \"com.termux/com.termux.app.TermuxOpenReceiver\""))
        assertEquals("-a \"com.termux.app.reload_style\" \"app.pocket\"",
            PrefixRelocation.rewrite("-a \"com.termux.app.reload_style\" \"com.termux\""))
        assertEquals("/data/data/app.pocket/files/apps/app.pocket/termux-am/am.sock",
            PrefixRelocation.rewrite("/data/data/com.termux/files/apps/com.termux/termux-am/am.sock"))
        assertEquals("com.termux.termuxam.Am", PrefixRelocation.rewrite("com.termux.termuxam.Am"))
    }

    @Test
    fun hookOnlyRewritesLinksThatNameAPathRunsInParallelAndReportsProgress() {
        val hook = PrefixRelocation.hookScript("/data/data/app.pocket/files/usr")
        java.io.File("build/relocate-debs").writeText(hook) // for running by hand on a device
        assertTrue(hook.startsWith("#!/data/data/app.pocket/files/usr/bin/bash\n"))
        listOf("data/data/com.termux", "user/0/com.termux", "files/apps/com.termux", "com.termux/com.termux.", "\"com.termux\"")
            .forEach { assertTrue(it, hook.contains("-lname '*$it*'")) }
        assertTrue(hook.contains("xargs -0 -r -n 1 -P \"\$jobs\" \"\$0\" --one"))
        assertTrue(hook.contains("\${${PrefixRelocation.PROGRESS_ENV}:-}"))
        assertTrue(hook.contains("report \"start \${#debs[@]}\""))
    }

    @Test
    fun leavesOtherBytesAlone() {
        val bytes = "nothing to see /data/data/com.termu".toByteArray()
        assertFalse(PrefixRelocation.rewrite(bytes, bytes.size))
    }
}
