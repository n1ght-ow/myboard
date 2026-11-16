package dev.cheng.keyboard

import dev.cheng.keyboard.ime.EditorTextSpan
import dev.cheng.keyboard.ime.codePointDeleteCount
import dev.cheng.keyboard.ime.compositionMissingFromEditor
import dev.cheng.keyboard.ime.cursorPositionAfterMove
import dev.cheng.keyboard.ime.fieldSourceFromExtractedText
import dev.cheng.keyboard.ime.lastCompleteGraphemeBeforeCursor
import dev.cheng.keyboard.ime.remapSelectionAfterReplacement
import dev.cheng.keyboard.ime.safeUtf16Suffix
import dev.cheng.keyboard.ime.textAtDocumentRange
import dev.cheng.keyboard.ime.typingEditorSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorBoundaryExplorationTest {
    @Test
    fun partialExtractedTextUsesAbsoluteParagraphAndSelectionOffsets() {
        val text = "first\nsecond\nthird"
        val paragraph = fieldSourceFromExtractedText(
            text = text,
            selectionStart = 9,
            selectionEnd = 9,
            startOffset = 100,
        )!!
        assertEquals("second", paragraph.text)
        assertEquals(106, paragraph.start)
        assertEquals(112, paragraph.end)
        assertFalse(paragraph.selection)

        val reverseSelection = fieldSourceFromExtractedText(
            text = text,
            selectionStart = 12,
            selectionEnd = 6,
            startOffset = 100,
        )!!
        assertEquals("second", reverseSelection.text)
        assertEquals(106, reverseSelection.start)
        assertEquals(112, reverseSelection.end)
        assertTrue(reverseSelection.selection)
    }

    @Test
    fun partialExtractionRejectsOverflowedAbsoluteRanges() {
        assertNull(
            fieldSourceFromExtractedText(
                text = "ab",
                selectionStart = 2,
                selectionEnd = 2,
                startOffset = Int.MAX_VALUE,
            ),
        )
        assertNull(
            fieldSourceFromExtractedText(
                text = "abc",
                selectionStart = 1,
                selectionEnd = 1,
                startOffset = -1,
            ),
        )
        assertNull(textAtDocumentRange("abc", Int.MAX_VALUE, Int.MAX_VALUE - 1, Int.MAX_VALUE))
        assertNull(
            fieldSourceFromExtractedText(
                text = "\uDE00abc",
                selectionStart = 2,
                selectionEnd = 2,
                startOffset = 100,
            ),
        )
    }

    @Test
    fun invalidExtractionSelectionNeverBecomesPositionable() {
        val invalidSelections = listOf(
            -1 to 1,
            1 to -1,
            -1 to -1,
            4 to 4,
            0 to 4,
        )
        for ((selectionStart, selectionEnd) in invalidSelections) {
            assertNull(
                fieldSourceFromExtractedText(
                    text = "abc",
                    selectionStart = selectionStart,
                    selectionEnd = selectionEnd,
                    startOffset = 100,
                ),
            )
        }
    }

    @Test
    fun controllerRangeHelperMapsAbsoluteRangesInsidePartialText() {
        assertEquals("3456", textAtDocumentRange("0123456789", 100, 103, 107))
        assertEquals("3456", textAtDocumentRange("0123456789", 100, 107, 103))
        assertNull(textAtDocumentRange("56789", 100, 103, 107))
        assertNull(textAtDocumentRange("0123456789", -1, 3, 7))
        assertNull(textAtDocumentRange("A😀B", 100, 100, 102))
    }

    @Test
    fun compositionFallbackStaysRelativeWhenRememberedOffsetIsUnavailable() {
        val document = "abc nihao"
        assertFalse(compositionMissingFromEditor("nihao", null, document, 500, 9))
        assertTrue(compositionMissingFromEditor("nihao", null, "abc nihx", 500, 9))
        assertFalse(
            compositionMissingFromEditor(
                raw = "nihao",
                remembered = EditorTextSpan(504, 509),
                document = document,
                startOffset = 500,
                selectionEnd = 9,
            ),
        )
        assertFalse(
            compositionMissingFromEditor(
                raw = "nihao",
                remembered = EditorTextSpan(499, 504),
                document = document,
                startOffset = 500,
                selectionEnd = 9,
            ),
        )
    }

    @Test
    fun cursorMovementAddsPartialOffsetAndMovesByCodePoint() {
        assertEquals(101, cursorPositionAfterMove("A😀B", 3, 100, -1))
        assertEquals(104, cursorPositionAfterMove("A😀B", 3, 100, 1))
        assertNull(cursorPositionAfterMove("A😀B", 2, 100, 1))
        assertNull(cursorPositionAfterMove("A😀B", 3, Int.MAX_VALUE, 1))
        assertNull(cursorPositionAfterMove("A😀B", 0, 100, -1))
    }

    @Test
    fun deletionSuffixNeverStartsAtALowSurrogate() {
        val splitAtProbeBoundary = "a😀" + "b".repeat(79)
        assertEquals("b".repeat(79), safeUtf16Suffix(splitAtProbeBoundary, 80))

        val emojiSuffix = "x" + "😀".repeat(40)
        val safeEmojiSuffix = safeUtf16Suffix(emojiSuffix, 80)!!
        assertEquals(80, safeEmojiSuffix.length)
        assertFalse(safeEmojiSuffix.first().isLowSurrogate())
    }

    @Test
    fun codePointDeletionCountTreatsOneEmojiAsOneDeletion() {
        assertEquals(1, codePointDeleteCount("A😀", 2))
        assertEquals(40, codePointDeleteCount("x" + "😀".repeat(40), 80))
        assertNull(codePointDeleteCount("a\uD83D", 2))
        assertNull(codePointDeleteCount("😀", -1))
    }

    @Test
    fun deleteOneFindsWholeVisibleGraphemeClusters() {
        assertEquals("👨‍👩‍👧‍👦", lastCompleteGraphemeBeforeCursor("A👨‍👩‍👧‍👦", 256))
        assertEquals("🇨🇳", lastCompleteGraphemeBeforeCursor("A🇨🇳", 256))
        assertEquals("👍🏽", lastCompleteGraphemeBeforeCursor("A👍🏽", 256))
        assertEquals("e\u0301", lastCompleteGraphemeBeforeCursor("Ae\u0301", 256))
        assertEquals("\r\n", lastCompleteGraphemeBeforeCursor("A\r\n", 256))
        assertEquals("B", lastCompleteGraphemeBeforeCursor("AB", 256))
        assertEquals("😀", lastCompleteGraphemeBeforeCursor("A😀", 256))
    }

    @Test
    fun deleteOneWindowIsBoundedAndConservativeForOneOversizedCluster() {
        assertEquals("B", lastCompleteGraphemeBeforeCursor("A" + "x".repeat(300) + "B", 256))

        val oversizedCluster = "a" + "\u0301".repeat(255)
        assertEquals(256, oversizedCluster.length)
        assertNull(lastCompleteGraphemeBeforeCursor(oversizedCluster, 256))
    }

    @Test
    fun deleteOneWindowIgnoresTruncatedLeftSurrogateButRejectsMalformedSuffix() {
        assertEquals("B", lastCompleteGraphemeBeforeCursor("\uDE00AB", 256))
        assertEquals(
            "B",
            lastCompleteGraphemeBeforeCursor("\uDE00" + "x".repeat(254) + "B", 256),
        )
        assertNull(lastCompleteGraphemeBeforeCursor("AB\uD83D", 256))
    }

    @Test
    fun typingWindowsExpandInsteadOfSplittingSurrogatePairs() {
        val beforeCursor = "x".repeat(160) + "😀" + "b".repeat(159)
        val beforeSelection = 160 + 2 + 159
        val before = typingEditorSnapshot(
            text = beforeCursor,
            selectionStart = beforeSelection,
            selectionEnd = beforeSelection,
            startOffset = 0,
        )!!
        assertEquals(161, before.before.length)
        assertTrue(before.before.first().isHighSurrogate())
        assertTrue(before.before.startsWith("😀"))

        val afterCursor = "x".repeat(321) + "y".repeat(79) + "😀z"
        val after = typingEditorSnapshot(
            text = afterCursor,
            selectionStart = 321,
            selectionEnd = 321,
            startOffset = 0,
        )!!
        assertEquals(81, after.after.length)
        assertTrue(after.after.last().isLowSurrogate())
        assertNull(typingEditorSnapshot("\uDE00abc", selectionStart = 2, selectionEnd = 2, startOffset = 100))
    }

    @Test
    fun reverseSelectionIsRejectedForInsertOnlySuggestions() {
        assertNull(typingEditorSnapshot("a😀b", selectionStart = 3, selectionEnd = 1, startOffset = 500))
        assertEquals(
            EditorTextSpan(10, 4),
            remapSelectionAfterReplacement(
                selection = EditorTextSpan(10, 4),
                replaced = EditorTextSpan(6, 8),
                replacementLength = 2,
            ),
        )
    }
}
