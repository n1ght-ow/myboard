package dev.cheng.keyboard

import dev.cheng.keyboard.engine.EngineAct
import dev.cheng.keyboard.engine.EngineState
import dev.cheng.keyboard.engine.InputEngine
import dev.cheng.keyboard.engine.LatinDecoder
import dev.cheng.keyboard.ime.displayedCandidateIndex
import dev.cheng.keyboard.ime.wholeCompositionCandidate
import dev.cheng.keyboard.model.Candidate
import dev.cheng.keyboard.model.EnterKind
import dev.cheng.keyboard.model.Pending
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CandidateInteractionRegressionTest {
    private val head = Candidate("有", "you", 10, consumed = 3)
    private val phrase = Candidate("由于", "you yu", 20, consumed = 5)
    private val shown = EngineState(raw = "you'yu", candidates = listOf(phrase, head))

    @Test
    fun tapPicksTheVisiblePrefixEvenIfDecoderReordersCandidates() {
        val refreshed = shown.copy(candidates = listOf(head.copy(freq = 30), phrase))
        val index = displayedCandidateIndex(refreshed, shown, 1) ?: error("Visible prefix should still match")
        val result = InputEngine(LatinDecoder()).reduce(refreshed, EngineAct.Pick(index), EnterKind.Newline)

        assertEquals(0, index)
        assertEquals("有", result.state.partialText)
        assertEquals("yu", result.state.raw)
        assertEquals(Pending.Pinyin, result.pending)
    }

    @Test
    fun staleCandidateNeverBecomesAnUnrelatedWordOrConsumesMoreLetters() {
        assertNull(displayedCandidateIndex(shown.copy(candidates = listOf(phrase)), shown, 1))
        assertNull(displayedCandidateIndex(shown.copy(candidates = emptyList()), shown, 1))
        assertNull(displayedCandidateIndex(shown.copy(candidates = listOf(head.copy(consumed = 5))), shown, 1))
        assertNull(displayedCandidateIndex(shown.copy(raw = "youyue"), shown, 1))
        assertNull(displayedCandidateIndex(shown.copy(partialText = "又"), shown, 1))
        assertNull(displayedCandidateIndex(shown.copy(associating = true), shown, 1))
    }

    @Test
    fun caretMoveNeverReplacesTheWholeSpellingWithOnlyItsFirstSegment() {
        val prefixFirst = shown.copy(candidates = listOf(head, phrase))

        assertNull(wholeCompositionCandidate(prefixFirst, shown.raw))
        assertNull(wholeCompositionCandidate(shown, "youy"))
        assertEquals("由于", wholeCompositionCandidate(shown, shown.raw))
    }
}
