package dev.cheng.keyboard

import dev.cheng.keyboard.ime.EditorTextSpan
import dev.cheng.keyboard.ime.compositionDetachTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EditorCompositionDetachSafetyTest {
    @Test
    fun staleShorterCandidatesDoNotDetachANewlyAppendedSuffix() {
        val stale = EditorTextSpan(10, 13)

        assertNull(
            compositionDetachTarget(
                compositionLength = 2,
                remembered = stale,
                candidates = stale,
                oldSelection = EditorTextSpan(13, 13),
                newSelection = EditorTextSpan(15, 15),
            ),
        )
    }

    @Test
    fun realCursorMoveOutsideMismatchedCandidatesStillDetaches() {
        val stale = EditorTextSpan(10, 13)

        assertEquals(
            stale,
            compositionDetachTarget(
                compositionLength = 2,
                remembered = stale,
                candidates = stale,
                oldSelection = EditorTextSpan(13, 13),
                newSelection = EditorTextSpan(5, 5),
            ),
        )
    }

    @Test
    fun matchingCandidatesStillDetachOnAnOrdinaryTap() {
        val current = EditorTextSpan(13, 15)

        assertEquals(
            current,
            compositionDetachTarget(
                compositionLength = 2,
                remembered = current,
                candidates = current,
                oldSelection = EditorTextSpan(15, 15),
                newSelection = EditorTextSpan(5, 5),
            ),
        )
    }
}
