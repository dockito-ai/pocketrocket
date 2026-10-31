package com.pocketrocket.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsageParsingTest {

    @Test fun `status reads real uid, parent and rss`() {
        val s = ProcText.status("Name:\tnode\nPPid:\t412\nUid:\t10221\t10221\t10221\t10221\nVmRSS:\t  20480 kB\n")
        assertEquals(10221, s.uid)
        assertEquals(412, s.ppid)
        assertEquals(20480L, s.rssKb)
    }

    @Test fun `status without VmRSS is zero`() {
        assertEquals(0L, ProcText.status("PPid:\t1\nUid:\t1\t1\t1\t1\n").rssKb)
    }

    @Test fun `pss takes the Pss line, not its parts`() {
        val text = "7000-8000 ---p 00000000 00:00 0 [rollup]\nRss:  500 kB\nPss:  123 kB\nPss_Anon:  90 kB\n"
        assertEquals(123L, ProcText.pssKb(text))
        assertNull(ProcText.pssKb("Rss: 500 kB\n"))
    }

    @Test fun `cpu counts fields after the name, even with spaces and brackets in it`() {
        val stat = "123 (my (odd) name) S 1 123 123 0 -1 4194304 10 0 0 0 7 5 3 2 20 0 1 0 100"
        assertEquals(17L, ProcText.cpuTicks(stat))
        assertNull(ProcText.cpuTicks("123 (short) S 1"))
    }

    @Test fun `children belong to the session whose command line has the conversation id`() {
        val procs = listOf(
            Proc(10, 1, 0, "app.pocket"),
            Proc(20, 10, 0, "bash"),
            Proc(30, 20, 0, "claude --resume conv-a"),
            Proc(31, 30, 0, "node mcp"),
            Proc(32, 31, 0, "chromium --headless"),
            Proc(40, 20, 0, "claude --resume conv-b"),
        )
        val owners = SessionTrees.attribute(procs, mapOf("a" to "conv-a", "b" to "conv-b"))
        assertEquals(mapOf(30 to "a", 31 to "a", 32 to "a", 40 to "b"), owners)
    }

    @Test fun `a blank conversation id owns nothing`() {
        assertEquals(emptyMap<Int, String>(), SessionTrees.attribute(listOf(Proc(10, 1, 0, "bash")), mapOf("a" to "")))
    }

    @Test fun `a parent loop ends`() {
        assertEquals(emptyMap<Int, String>(), SessionTrees.attribute(listOf(Proc(1, 2, 0, "x"), Proc(2, 1, 0, "y")), mapOf("a" to "conv")))
    }

    @Test fun `app processes are the package name, with or without a process suffix`() {
        assertEquals(true, RamUsage.isAppProcess(Proc(1, 0, 0, "app.pocket"), "app.pocket"))
        assertEquals(true, RamUsage.isAppProcess(Proc(1, 0, 0, "app.pocket:worker"), "app.pocket"))
        assertEquals(false, RamUsage.isAppProcess(Proc(1, 0, 0, "app.pocketfoo"), "app.pocket"))
    }

    private val realOutput = """
        Statistics since last charge:
          System starts: 0, currently on battery: true
          Estimated battery capacity: 1000 mAh
          Time on battery: 2m 16s 378ms (100.0%) realtime, 2m 16s 378ms (100.0%) uptime
          Estimated power use (mAh):
            Capacity: 1000, Computed drain: 0, actual drain: 0
            Global
            screen: 0.00243 apps: 0.00243
          UID u0a185: 0.00132 fg: 0 (892ms) bg: 0 (27s 628ms)
          UID u0a221: 0.00557 fg: 0.000907 (1m 45s 700ms) bg: 0.000260 cached: 0.00000200 (353ms)
          UID 1000: 0.000535
    """.trimIndent().lineSequence()

    @Test fun `battery takes this app's line with its capacity and period`() {
        assertEquals(BatteryReading.Figure(0.00557, 1000, "2m 16s"), BatteryUsage.parse(realOutput, "u0a221"))
    }

    @Test fun `battery with no line for this app recorded nothing, rather than guessing`() {
        assertEquals(BatteryReading.NothingRecorded("2m 16s"), BatteryUsage.parse(realOutput, "u0a999"))
    }

    @Test fun `a similar uid is not this app`() {
        assertEquals(BatteryReading.NothingRecorded("2m 16s"), BatteryUsage.parse(realOutput, "u0a22"))
    }

    @Test fun `each denial asks for the grant`() {
        val dump = "Permission Denial: can't dump BatteryStatsService from from pid=1, uid=10221 due to missing android.permission.DUMP permission"
        val usage = "Permission Denial: can't dump BatteryStatsService from from pid=1, uid=10221 due to missing android.permission.PACKAGE_USAGE_STATS permission"
        assertEquals(BatteryReading.NeedsGrant, BatteryUsage.parse(sequenceOf(dump), "u0a221"))
        assertEquals(BatteryReading.NeedsGrant, BatteryUsage.parse(sequenceOf(usage), "u0a221"))
    }

    @Test fun `empty or unknown output shows no figure`() {
        assertEquals(BatteryReading.Unreadable, BatteryUsage.parse(emptySequence(), "u0a221"))
        assertEquals(BatteryReading.Unreadable, BatteryUsage.parse(sequenceOf("Can't find service: batterystats"), "u0a221"))
    }

    @Test fun `uid label matches dumpsys`() {
        assertEquals("u0a221", BatteryUsage.uidLabel(10221))
        assertEquals("u10a221", BatteryUsage.uidLabel(1010221))
    }

    @Test fun `text reads as agreed`() {
        assertEquals("2.4 GB · 640 MB RAM", UsageText.summary(2_400_000_000, 640L * 1024))
        assertEquals("640 MB RAM", UsageText.summary(null, 640L * 1024))
        assertEquals("1.5 GB", UsageText.ram(1536L * 1024))
        assertEquals("500 MB", UsageText.storage(500_000_000))
        assertEquals("2h 14m", UsageText.duration(8_040_000))
        assertEquals("3m 12s", UsageText.duration(192_000))
        assertEquals("Under 0.1 mAh", UsageText.mah(0.005))
        assertEquals(
            "Android’s estimate since the last charge (2h on battery). About 2.5% of a 5000 mAh battery.",
            UsageText.batteryNote(BatteryReading.Figure(125.0, 5000, "2h")),
        )
    }
}
