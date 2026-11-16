package dev.cheng.keyboard

import android.view.inputmethod.EditorInfo
import dev.cheng.keyboard.ime.EditorTextSpan
import dev.cheng.keyboard.ime.EditorSessionKey
import dev.cheng.keyboard.ime.compositionDetachTarget
import dev.cheng.keyboard.ime.compositionMissingFromEditor
import dev.cheng.keyboard.ime.fieldTraitsOf
import dev.cheng.keyboard.ime.shouldUseSmartCommit
import dev.cheng.keyboard.ime.shouldResetEditorSession
import dev.cheng.keyboard.ime.smartCommitNeedsContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorLifecycleSafetyTest {
    @Test
    fun silentHostClearAndReplacementInvalidateOldComposition() {
        val span = EditorTextSpan(4, 9)
        assertTrue(compositionMissingFromEditor("nihao", span, "", 0, 0))
        assertTrue(compositionMissingFromEditor("nihao", span, "prefix changed", 0, 2))
        assertFalse(compositionMissingFromEditor("nihao", span, "abc nihao", 0, 7))
        assertFalse(compositionMissingFromEditor("nihao", EditorTextSpan(4, 8), "abc nihao", 0, 9))
        assertFalse(compositionMissingFromEditor("nihao", span, null, 0, 0))
        assertFalse(compositionMissingFromEditor("nihao", span, "partial", 20, 22))
        assertTrue(compositionMissingFromEditor("nihao", null, "", 0, 0))
    }

    @Test
    fun caretAtCompositionStartDetachesAfterExternalClear() {
        val composition = EditorTextSpan(0, 5)

        assertEquals(
            composition,
            compositionDetachTarget(
                compositionLength = 5,
                remembered = composition,
                candidates = null,
                oldSelection = EditorTextSpan(5, 5),
                newSelection = EditorTextSpan(0, 0),
            ),
        )
        assertEquals(
            composition,
            compositionDetachTarget(
                compositionLength = 5,
                remembered = composition,
                candidates = composition,
                oldSelection = EditorTextSpan(5, 5),
                newSelection = EditorTextSpan(0, 0),
            ),
        )
        assertEquals(
            composition,
            compositionDetachTarget(
                compositionLength = 5,
                remembered = composition,
                candidates = null,
                oldSelection = EditorTextSpan(-1, -1),
                newSelection = EditorTextSpan(0, 0),
            ),
        )
    }

    @Test
    fun caretAtCompositionEndStillContinuesComposition() {
        val composition = EditorTextSpan(7, 12)

        assertEquals(
            null,
            compositionDetachTarget(
                compositionLength = 5,
                remembered = composition,
                candidates = composition,
                oldSelection = EditorTextSpan(3, 3),
                newSelection = EditorTextSpan(12, 12),
            ),
        )
    }

    @Test
    fun numberPasswordRemainsNumericAndProtected() {
        val traits = fieldTraitsOf(
            EditorInfo.TYPE_CLASS_NUMBER or EditorInfo.TYPE_NUMBER_VARIATION_PASSWORD,
            EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING,
        )

        assertTrue(traits.password)
        assertTrue(traits.noLearn)
        assertTrue(traits.numeric)
        assertFalse(traits.uriOrEmail)
    }

    @Test
    fun uriAndDateAreNotMisclassifiedAsNumberPasswords() {
        val uri = fieldTraitsOf(EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_VARIATION_URI, 0)
        assertFalse(uri.password)
        assertTrue(uri.uriOrEmail)
        val date = fieldTraitsOf(EditorInfo.TYPE_CLASS_DATETIME or android.text.InputType.TYPE_DATETIME_VARIATION_DATE, 0)
        assertFalse(date.password)
        assertTrue(date.numeric)
    }

    @Test
    fun restartStartsWithNewSessionWhenFieldConfigurationChanges() {
        val text = EditorSessionKey("qa.host", 42, EditorInfo.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_DONE)
        val email = text.copy(inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val privateInput = text.copy(imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        val number = text.copy(inputType = EditorInfo.TYPE_CLASS_NUMBER)
        val numberPassword = number.copy(inputType = EditorInfo.TYPE_CLASS_NUMBER or EditorInfo.TYPE_NUMBER_VARIATION_PASSWORD)

        assertFalse(shouldResetEditorSession(restarting = true, previous = text, current = text))
        assertTrue(shouldResetEditorSession(restarting = true, previous = text, current = email))
        assertTrue(shouldResetEditorSession(restarting = true, previous = text, current = privateInput))
        assertTrue(shouldResetEditorSession(restarting = true, previous = number, current = numberPassword))
        assertTrue(shouldResetEditorSession(restarting = false, previous = text, current = text))
        assertTrue(shouldResetEditorSession(restarting = true, previous = null, current = text))
    }

    @Test
    fun passwordAndUriFieldsCommitLiteralText() {
        assertFalse(shouldUseSmartCommit(password = true, uriOrEmail = false))
        assertFalse(shouldUseSmartCommit(password = false, uriOrEmail = true))
        assertTrue(shouldUseSmartCommit(password = false, uriOrEmail = false))
    }

    @Test
    fun selectedTextDoesNotPullInReplacementContext() {
        for (text in listOf("1", "“", "‘", "()")) {
            assertFalse(smartCommitNeedsContext(hasSelection = true, composing = false, text = text))
            assertTrue(smartCommitNeedsContext(hasSelection = false, composing = false, text = text))
        }
    }
}
