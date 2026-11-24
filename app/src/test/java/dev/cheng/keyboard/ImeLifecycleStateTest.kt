package dev.cheng.keyboard

import dev.cheng.keyboard.ime.EditorSessionKey
import dev.cheng.keyboard.ime.InputViewRestoreGate
import dev.cheng.keyboard.ime.afterInputViewFinished
import dev.cheng.keyboard.ime.shouldResetEditorSession
import dev.cheng.keyboard.engine.Cmd
import dev.cheng.keyboard.engine.EngineAct
import dev.cheng.keyboard.engine.EngineState
import dev.cheng.keyboard.engine.InputEngine
import dev.cheng.keyboard.engine.LatinDecoder
import dev.cheng.keyboard.model.Candidate
import dev.cheng.keyboard.model.EnterKind
import dev.cheng.keyboard.model.KeyboardPage
import dev.cheng.keyboard.model.TouchSample
import org.junit.Assert.assertEquals
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

    @Test
    fun aRealInputStartSatisfiesPendingWindowRestore() {
        val gate = InputViewRestoreGate()
        gate.onInputViewFinished()
        gate.onInputStarted()

        assertFalse(gate.shouldRestoreOnWindowShown(inputStarted = true))
    }

    @Test
    fun typingAfterHostFinishesCompositionDoesNotComposeTheOldLettersAgain() {
        val initial = EngineState(
            raw = "ni",
            preedit = "ni",
            candidates = listOf(Candidate("你", "ni", 10, consumed = 2)),
            partialText = "你好",
            partialSyllables = "ni hao",
            touchHints = listOf("", ""),
            touchSamples = listOf(TouchSample(primary = 'n'), TouchSample(primary = 'i')),
            page = KeyboardPage.Letters,
            noLearn = true,
        )

        val reopened = initial.afterInputViewFinished()
        val next = InputEngine(LatinDecoder()).reduce(reopened, EngineAct.Letter('h', null, ""), EnterKind.Newline)

        assertEquals(EngineState(page = initial.page, noLearn = initial.noLearn), reopened)
        assertEquals("h", next.state.raw)
        assertEquals(listOf(Cmd.Compose("h")), next.cmds)
    }
}
