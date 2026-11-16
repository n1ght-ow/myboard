package dev.cheng.keyboard

import dev.cheng.keyboard.ime.EditorTextSpan
import dev.cheng.keyboard.ime.TypingEditorSnapshot
import dev.cheng.keyboard.ime.assist.TypingAssistKind
import dev.cheng.keyboard.ime.assist.TypingSuggestion
import dev.cheng.keyboard.ime.typingEditorSnapshot
import dev.cheng.keyboard.ime.typingReplacementRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TypingAssistEditingTest {
    @Test
    fun domainReplacementLeavesUsernameAndFollowingTextIntact() {
        val snapshot = typingEditorSnapshot("alice@g next", 7, 7, 0)!!
        val suggestion = email(snapshot)
        assertEquals(EditorTextSpan(6, 7), typingReplacementRange(snapshot, suggestion))
        val target = typingReplacementRange(snapshot, suggestion)!!
        assertEquals("alice@gmail.com next", "alice@g next".replaceRange(target.start, target.end, suggestion.replacement))
    }

    @Test
    fun extractionOffsetsAndBoundedWindowsKeepAbsoluteSelection() {
        val snapshot = typingEditorSnapshot("alice@g next", 7, 7, 500)!!
        assertEquals(EditorTextSpan(506, 507), typingReplacementRange(snapshot, email(snapshot)))
        val longText = "x".repeat(600) + "alice@g" + " tail".repeat(100)
        val bounded = typingEditorSnapshot(longText, 607, 607, 10)!!
        assertEquals(160, bounded.before.length)
        assertEquals(80, bounded.after.length)
        assertEquals(EditorTextSpan(616, 617), typingReplacementRange(bounded, email(bounded)))
    }

    @Test
    fun staleTextSelectionsAndOutOfRangeReplacementsAreRejected() {
        val snapshot = typingEditorSnapshot("alice@g", 7, 7, 0)!!
        val suggestion = email(snapshot)
        assertNull(typingReplacementRange(snapshot.copy(before = "bob@g"), suggestion))
        assertNull(typingReplacementRange(snapshot.copy(after = ".com"), suggestion))
        assertNull(typingReplacementRange(snapshot.copy(selection = EditorTextSpan(6, 7)), suggestion))
        assertNull(typingReplacementRange(snapshot, suggestion.copy(replaceBefore = 80)))
        assertNull(typingReplacementRange(snapshot, suggestion.copy(replaceBefore = -1)))
        assertNull(typingReplacementRange(snapshot, suggestion.copy(replaceAfter = 1)))
        assertNull(typingReplacementRange(snapshot, suggestion.copy(replacement = "")))
        assertNull(typingReplacementRange(snapshot.copy(selection = EditorTextSpan(0, 0)), suggestion))
    }

    @Test
    fun malformedExtractionNeverProducesReplacementCoordinates() {
        assertNull(typingEditorSnapshot("abc", -1, 0, 0))
        assertNull(typingEditorSnapshot("abc", 2, 1, 0))
        assertNull(typingEditorSnapshot("abc", 4, 4, 0))
        assertNull(typingEditorSnapshot("abc", 1, 1, -1))
        assertNull(typingEditorSnapshot("abc", 2, 2, Int.MAX_VALUE))
    }

    private fun email(snapshot: TypingEditorSnapshot) = TypingSuggestion(
        id = "email:gmail.com",
        label = "gmail.com",
        replacement = "gmail.com",
        replaceBefore = 1,
        expectedBefore = snapshot.before,
        expectedAfter = snapshot.after,
        kind = TypingAssistKind.Email,
    )
}
