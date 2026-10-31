package com.pocketrocket.updates

import com.pocketrocket.appupdate.AppUpdateState
import com.pocketrocket.setup.SetupPhase
import com.pocketrocket.setup.StepStatus
import com.pocketrocket.updater.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatesTest {
    @Test fun aptPolicyReadsInstalledAndCandidate() {
        val p = AptPolicy.parse(
            listOf("git:", "  Installed: 2.51.0", "  Candidate: 2.51.1", "  Version table:", "     2.51.1 500", "*** 2.51.0 100"),
        )
        assertEquals("2.51.0", p.installed)
        assertEquals("2.51.1", p.candidate)
        assertTrue(p.updateAvailable)
    }

    @Test fun aptPolicyUpToDateAndNotInstalled() {
        assertFalse(AptPolicy.parse(listOf("  Installed: 2.51.0", "  Candidate: 2.51.0")).updateAvailable)
        val none = AptPolicy.parse(listOf("  Installed: (none)", "  Candidate: 2.51.0"))
        assertNull(none.installed)
        assertFalse(none.updateAvailable)
        assertEquals("2.51.0", AptPolicy.display("1:2.51.0"))
    }

    @Test fun pocketRocketGoesLast() {
        assertEquals(
            listOf(UpdateComponent.ClaudeCode, UpdateComponent.Git, UpdateComponent.Dockito, UpdateComponent.PocketRocket),
            updateOrder(listOf(UpdateComponent.PocketRocket, UpdateComponent.Dockito, UpdateComponent.Git, UpdateComponent.ClaudeCode, UpdateComponent.Git)),
        )
    }

    @Test fun appDownloadShowsAsARunningStepWithProgress() {
        val job = AppUpdateJob.of(AppUpdateState(installed = "0.2.0", latest = "0.3.0", step = AppUpdateState.Step.Downloading, downloaded = 50L shl 20, total = 100L shl 20))!!
        assertEquals(SetupPhase.Running, job.phase)
        assertEquals(listOf("Downloading 0.3.0"), job.steps.map { it.title })
        assertEquals(50, job.progress)
        assertEquals("50 of 100 MB", job.detail)
    }

    @Test fun appFailureMarksTheStepItFailedAt() {
        val s = AppUpdateState(latest = "0.3.0", outcome = Outcome("bad hash", failed = true), failedAt = AppUpdateState.Step.Verifying)
        val job = AppUpdateJob.of(s)!!
        assertEquals(SetupPhase.Failed, job.phase)
        assertEquals(listOf(StepStatus.Done, StepStatus.Failed), job.steps.map { it.status })
        assertEquals("bad hash", job.error)
        assertNull(AppUpdateJob.of(AppUpdateState(latest = "0.3.0")))
    }

    @Test fun stateCountsAndBatchProgress() {
        fun row(c: UpdateComponent, available: Boolean, latest: String? = "1") = UpdateRow(c, "0", latest, available, checking = false)
        val s = UpdatesState(
            rows = listOf(row(UpdateComponent.PocketRocket, false), row(UpdateComponent.ClaudeCode, true), row(UpdateComponent.Git, true), row(UpdateComponent.Dockito, false)),
            current = UpdateComponent.ClaudeCode, queued = listOf(UpdateComponent.Git),
        )
        assertEquals(2, s.available)
        assertEquals(2, s.total)
        assertTrue(s.allKnown)
        assertFalse(s.copy(rows = s.rows + row(UpdateComponent.Git, false, latest = null)).allKnown)
    }
}
