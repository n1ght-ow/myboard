package dev.cheng.keyboard

import dev.cheng.keyboard.ime.EditorTextSpan
import dev.cheng.keyboard.ime.TypingEditorSnapshot
import dev.cheng.keyboard.ime.englishAutoSpacePosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EnglishAutoSpacePositionTest {
    @Test
    fun returnsAbsoluteCaretOnlyAfterTheCommittedWordAndSpace() {
        val afterWord = TypingEditorSnapshot("prefix Hello ", "", EditorTextSpan(73, 73))
        assertEquals(73, englishAutoSpacePosition(afterWord, "hello"))
        assertNull(englishAutoSpacePosition(afterWord.copy(before = "prefix Hel", after = "lo "), "hello"))
        assertNull(englishAutoSpacePosition(afterWord.copy(before = "prefix Hello"), "hello"))
        assertNull(englishAutoSpacePosition(afterWord.copy(before = "prefix World "), "hello"))
    }

    @Test
    fun doesNotReuseAnAutomaticSpaceWhenSelectionIsUnknownOrNonEmpty() {
        val beforeSelection = TypingEditorSnapshot("hello ", "", EditorTextSpan(6, 11))
        assertNull(englishAutoSpacePosition(beforeSelection, "hello"))
        assertNull(englishAutoSpacePosition(beforeSelection.copy(selection = EditorTextSpan(-1, -1)), "hello"))
        assertNull(englishAutoSpacePosition(null, "hello"))
        assertNull(englishAutoSpacePosition(beforeSelection.copy(selection = EditorTextSpan(6, 6)), null))
    }
}
