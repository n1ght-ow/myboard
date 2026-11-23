package dev.cheng.keyboard

import dev.cheng.keyboard.ime.EditorSessionKey
import dev.cheng.keyboard.ime.InputViewRestoreGate
import dev.cheng.keyboard.ime.shouldResetEditorSession
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImeLifecycleStateTest {
    @Test
    fun sameEditorRestartKeepsCompositionSession() {
        val session = EditorSessionKey(
            packageName = "qa.host",
            fieldId = 42,
            inputType = android.view.inputmethod.EditorInfo.TYPE_CLASS_TEXT,
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE,
        )

        assertFalse(shouldResetEditorSession(restarting = true, previous = session, current = session))
        assertTrue(
            shouldResetEditorSession(
                restarting = true,
                previous = session,
                current = session.copy(fieldId = 43),
            ),
        )
        assertTrue(shouldResetEditorSession(restarting = false, previous = session, current = session))
    }

    @Test
    fun windowShownRestoresOnlyOnceAfterInputViewFinished() {
        val gate = InputViewRestoreGate()

        assertFalse(gate.shouldRestoreOnWindowShown(inputStarted = true))

        gate.onInputViewFinished()
        assertTrue(gate.shouldRestoreOnWindowShown(inputStarted = true))
        assertFalse(gate.shouldRestoreOnWindowShown(inputStarted = true))

        gate.onInputViewFinished()
        assertFalse(gate.shouldRestoreOnWindowShown(inputStarted = false))
        assertFalse(gate.shouldRestoreOnWindowShown(inputStarted = true))
    }
}
