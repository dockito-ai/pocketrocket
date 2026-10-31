package com.pocketrocket.ui.chat

import com.pocketrocket.sessions.PermissionMode
import org.junit.Assert.assertEquals
import org.junit.Test

/** The one-row status line and how it shortens when narrow (decision 89). Width is counted in characters here. */
class StatusRowTest {
    private val levels = statusLevels(PermissionMode.Auto, "Opus 5.5", "medium", "160k/1M", 0, 0)
    private fun fitting(chars: Int) = statusText(fitStatus(levels) { statusText(it).length <= chars })

    @Test fun fullRow() {
        assertEquals("▸▸ auto mode │ Opus 5.5 · medium │ 160k/1M │ 0 agents │ 0 shells", statusText(levels.first()))
        assertEquals("1 agent", statusLevels(PermissionMode.Auto, null, null, null, 1, 2)[0][1].text)
    }

    @Test fun shortensInOrder() {
        assertEquals("▸▸ auto mode │ Opus 5.5 · medium │ 160k/1M │ 0 agents │ 0 shells", fitting(100))
        assertEquals("▸▸ auto mode │ Opus 5.5 · medium │ 160k/1M │ 0 A │ 0 S", fitting(60))
        assertEquals("▸▸ auto │ Opus 5.5 · medium │ 160k/1M │ 0 A │ 0 S", fitting(50))
        assertEquals("▸▸ auto │ Opus 5.5 │ 160k/1M │ 0 A │ 0 S", fitting(40))
        assertEquals("▸▸ auto │ Opus │ 160k/1M │ 0 A │ 0 S", fitting(36))
        // Nothing fits: the shortest is drawn (and clipped).
        assertEquals("▸▸ auto │ Opus │ 160k/1M │ 0 A │ 0 S", fitting(5))
    }

    @Test fun missingPiecesAndModes() {
        assertEquals("‖ plan mode │ 0 agents │ 0 shells", statusText(statusLevels(PermissionMode.Plan, null, null, null, 0, 0).first()))
        assertEquals("▸▸ accept edits │ 2 A │ 1 S", statusText(statusLevels(PermissionMode.AcceptEdits, null, null, null, 2, 1).last()))
        assertEquals("Opus", shortModelName("Claude Opus 5.5 (1M context)"))
        assertEquals("Sonnet", shortModelName("Sonnet"))
    }
}
