package dev.cheng.keyboard

import dev.cheng.keyboard.ime.fieldSourceFromExtractedText
import dev.cheng.keyboard.ime.smartCommitTextAfterDelete
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EditorRound3RegressionTest {
    @Test
    fun smartCommitKeepsLiteralTextWhenRequiredDeleteIsRejected() {
        assertEquals(
            "14",
            smartCommitTextAfterDelete(
                originalText = "14",
                resolvedText = ".14",
                requiresDelete = true,
                deleteApplied = false,
            ),
        )
        assertEquals(
            ".14",
            smartCommitTextAfterDelete(
                originalText = "14",
                resolvedText = ".14",
                requiresDelete = true,
                deleteApplied = true,
            ),
        )
        assertEquals(
            "()",
            smartCommitTextAfterDelete(
                originalText = "()",
                resolvedText = "()",
                requiresDelete = false,
                deleteApplied = false,
            ),
        )
    }

    @Test
    fun oversizedPartialSelectionDoesNotExposeMismatchedReplacementRange() {
        assertNull(
            fieldSourceFromExtractedText(
                text = "x".repeat(4001),
                selectionStart = 0,
                selectionEnd = 4001,
                startOffset = 100,
            ),
        )
    }
}
