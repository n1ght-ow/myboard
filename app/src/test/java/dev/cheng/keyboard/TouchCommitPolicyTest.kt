package dev.cheng.keyboard

import dev.cheng.keyboard.engine.EngineAct
import dev.cheng.keyboard.engine.EngineState
import dev.cheng.keyboard.ime.touch.TouchCalibrator
import dev.cheng.keyboard.ime.touch.matchesTouchSpelling
import dev.cheng.keyboard.model.Candidate
import dev.cheng.keyboard.model.Language
import dev.cheng.keyboard.model.TouchSample
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchCommitPolicyTest {
    @Test
    fun englishAutocorrectionAndCompletionCannotTrainTouchCentres() {
        val state = EngineState(language = Language.English, raw = "hellp", candidates = listOf(Candidate("hello", "hello", 1)))
        assertFalse(matchesTouchSpelling(state, EngineAct.Space))
        assertFalse(matchesTouchSpelling(state, EngineAct.Pick(0)))
        assertFalse(matchesTouchSpelling(state.copy(raw = "hel"), EngineAct.Space))
        assertTrue(matchesTouchSpelling(state.copy(raw = "Hello"), EngineAct.Space))
        assertTrue(matchesTouchSpelling(state.copy(verbatimFirst = true), EngineAct.Space))
    }

    @Test
    fun chineseExactSpellingAcceptsSeparatorsButRejectsTyposAndInitials() {
        val state = EngineState(raw = "ni'hao", candidates = listOf(Candidate("你好", "ni hao", 1)))
        assertTrue(matchesTouchSpelling(state, EngineAct.Space))
        assertTrue(matchesTouchSpelling(state, EngineAct.Pick(0)))
        assertFalse(matchesTouchSpelling(state.copy(raw = "bihao"), EngineAct.Space))
        assertFalse(matchesTouchSpelling(state.copy(raw = "nh"), EngineAct.Space))
        assertFalse(matchesTouchSpelling(state.copy(raw = "niha"), EngineAct.Space))
    }

    @Test
    fun choosingAnExactAlternateSpellingIsAllowed() {
        val state = EngineState(raw = "bihao", candidates = listOf(
            Candidate("你好", "ni hao", 2), Candidate("比号", "bi hao", 1),
        ))
        assertFalse(matchesTouchSpelling(state, EngineAct.Pick(0)))
        assertTrue(matchesTouchSpelling(state, EngineAct.Pick(1)))
        assertFalse(matchesTouchSpelling(state, EngineAct.Pick(5)))
        assertFalse(matchesTouchSpelling(state, EngineAct.Pick(-1)))
    }

    @Test
    fun literalCommitWithoutCandidatesIsAllowedButPredictionsAreNotSamples() {
        val state = EngineState(language = Language.English, raw = "customname")
        assertTrue(matchesTouchSpelling(state, EngineAct.Space))
        assertTrue(matchesTouchSpelling(state, EngineAct.Enter))
        assertFalse(matchesTouchSpelling(state, EngineAct.Pick(0)))
        assertFalse(matchesTouchSpelling(state.copy(raw = ""), EngineAct.Enter))
        assertFalse(matchesTouchSpelling(state.copy(raw = "", associating = true), EngineAct.Space))
    }

    @Test
    fun privateAndAddressFieldsNeverTrainEvenForExactCommits() {
        val state = EngineState(language = Language.English, raw = "hello", candidates = listOf(Candidate("hello", "hello", 1)))
        for (restricted in listOf(state.copy(password = true), state.copy(noLearn = true), state.copy(uriOrEmail = true))) {
            assertFalse(matchesTouchSpelling(restricted, EngineAct.Space))
            assertFalse(matchesTouchSpelling(restricted, EngineAct.Enter))
            assertFalse(matchesTouchSpelling(restricted, EngineAct.Pick(0)))
        }
    }

    @Test
    fun rejectedCorrectionCannotPoisonNextCleanWord() {
        val calibrator = TouchCalibrator()
        fun touches() = repeat(4) {
            calibrator.record(TouchSample(primary = 'a', residualX = .05f, residualY = .20f, leftSide = true))
        }
        touches()
        val corrected = EngineState(language = Language.English, raw = "hellp", candidates = listOf(Candidate("hello", "hello", 1)))
        if (!matchesTouchSpelling(corrected, EngineAct.Space)) calibrator.markDirty()
        assertFalse(calibrator.commitIfClean())
        touches()
        assertTrue(matchesTouchSpelling(corrected.copy(raw = "hello"), EngineAct.Space))
        assertTrue(calibrator.commitIfClean())
    }
}
