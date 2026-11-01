package com.pocketrocket.ui.chat

import com.pocketrocket.ui.chat.MarkdownBlocks.Block
import com.pocketrocket.ui.chat.MarkdownTableBlock.Align
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownTableTest {
    @Test fun tableRightAfterAParagraphIsATable() {
        val blocks = MarkdownBlocks.parse(
            """
            Here's where my work stands:
            | What | Status |
            |---|---|
            | Test results docs (#33) | on main |
            | **Tool updates** | still being coded |
            Done.
            """.trimIndent(),
        )
        assertEquals(Block.Paragraph("Here's where my work stands:"), blocks[0])
        assertEquals(
            Block.Table(
                MarkdownTableBlock(
                    listOf("What", "Status"),
                    listOf(Align.Start, Align.Start),
                    listOf(listOf("Test results docs (#33)", "on main"), listOf("**Tool updates**", "still being coded")),
                ),
            ),
            blocks[1],
        )
        assertEquals(Block.Paragraph("Done."), blocks[2])
    }

    @Test fun alignmentComesFromTheSeparator() {
        assertEquals(listOf(Align.Start, Align.Center, Align.End), MarkdownTableBlock.separator("|:--|:-:|--:|", 3))
    }

    @Test fun separatorMustMatchTheHeaderWidth() {
        assertEquals(null, MarkdownTableBlock.separator("|---|---|", 3))
    }

    @Test fun pipesWithoutASeparatorStayText() {
        assertEquals(listOf(Block.Paragraph("a | b\nc | d")), MarkdownBlocks.parse("a | b\nc | d"))
    }

    @Test fun pipesInCodeAndEscapedPipesStayInTheCell() {
        assertEquals(listOf("`a|b`", "c|d", ""), MarkdownTableBlock.cells("| `a|b` | c\\|d | |"))
    }

    @Test fun tableInsideACodeFenceStaysCode() {
        val blocks = MarkdownBlocks.parse("```\n| a | b |\n|---|---|\n```")
        assertEquals(listOf(Block.Code("| a | b |\n|---|---|")), blocks)
    }

    @Test fun columnsKeepNaturalWidthWhenTheyFit() {
        assertArrayEquals(intArrayOf(100, 200), columnWidths(intArrayOf(100, 200), intArrayOf(50, 60), 400))
    }

    @Test fun columnsShareTheSpareRoomByWhatTheyWant() {
        // Narrowest 50 + 60 leaves 90 spare; the columns want 50 and 400 more.
        assertArrayEquals(intArrayOf(60, 140), columnWidths(intArrayOf(100, 460), intArrayOf(50, 60), 200))
    }

    @Test fun tooNarrowFallsBackToNarrowestAndScrolls() {
        assertArrayEquals(intArrayOf(50, 60), columnWidths(intArrayOf(100, 200), intArrayOf(50, 60), 80))
    }
}
