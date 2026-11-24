package dev.cheng.keyboard

import dev.cheng.keyboard.engine.Cmd
import dev.cheng.keyboard.engine.EngineAct
import dev.cheng.keyboard.engine.EngineState
import dev.cheng.keyboard.engine.InputEngine
import dev.cheng.keyboard.engine.LatinDecoder
import dev.cheng.keyboard.engine.applyCase
import dev.cheng.keyboard.model.Candidate
import dev.cheng.keyboard.model.EnterKind
import dev.cheng.keyboard.model.Language
import dev.cheng.keyboard.model.ShiftState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LatinVerbatimCandidateRegressionTest {
    @Test
    fun emptyDictionaryStillOffersTheCompleteTypedSpelling() {
        val candidates = LatinDecoder().suggest("zzqvxx", verbatimFirst = true)

        assertEquals(listOf(Candidate("zzqvxx", "zzqvxx", 1)), candidates)
        assertEquals(Candidate("zzqvxx", "zzqvxx", 1), LatinDecoder().suggest("ZZQVXX", verbatimFirst = true).single())
    }

    @Test
    fun knownWordKeepsOneClickableVerbatimCandidate() {
        val decoder = decoder("zzqvxx" to 100)

        val verbatim = decoder.suggest("zzqvxx", verbatimFirst = true)
        assertEquals("zzqvxx", verbatim.first().text)
        assertEquals(1, verbatim.count { it.text == "zzqvxx" })
        assertEquals("zzqvxx", decoder.suggest("zzqvxx").first().text)
    }

    @Test
    fun verbatimCandidateStaysFirstWhenOtherCandidatesExist() {
        val decoder = decoder(
            "zzqvxx" to 100,
            "zzqvxxyy" to 10_000,
        )

        val candidates = decoder.suggest("zzqvxx", verbatimFirst = true)

        assertEquals("zzqvxx", candidates.first().text)
        assertTrue(candidates.any { it.text == "zzqvxxyy" })
    }

    @Test
    fun emptyInvalidAndNormalSuggestionBehaviorStayUnchanged() {
        val decoder = LatinDecoder()

        assertTrue(decoder.suggest("", verbatimFirst = true).isEmpty())
        assertTrue(decoder.suggest("zz qvxx", verbatimFirst = true).isEmpty())
        assertTrue(decoder.suggest("zzqvxx!", verbatimFirst = true).isEmpty())
        assertTrue(decoder.suggest("zzqvxx", verbatimFirst = false).isEmpty())
    }

    @Test
    fun keepsIPrefixAndCaseSpecialRules() {
        val candidates = LatinDecoder().suggest("i", verbatimFirst = true)

        assertEquals(Candidate("i", "i", 2_000_000), candidates.first())
        assertEquals("I", applyCase("i", "i"))
        assertEquals("I'm", applyCase("i'm", "im"))
    }

    @Test
    fun engineSpaceCorrectionThenBackspaceRestoresTheVerbatimCandidate() {
        val decoder = decoder("paaaa" to 1_000_000)
        val engine = InputEngine(decoder)
        var typed = EngineState(language = Language.English, shift = ShiftState.Off)
        for ((index, ch) in "zaaaa".withIndex()) {
            typed = engine.reduce(
                typed,
                EngineAct.Letter(ch, null, "", neighbors = if (index == 0) "p" else ""),
                EnterKind.Newline,
            ).state
        }
        assertEquals(ShiftState.Off, typed.shift)
        assertEquals("paaaa", typed.candidates.first().text)

        val spaced = engine.reduce(typed, EngineAct.Space, EnterKind.Newline)
        assertEquals("paaaa ", (spaced.cmds.first() as Cmd.Commit).text)
        assertEquals("zaaaa" to 6, spaced.state.undoAutocorrect)
        assertEquals(
            listOf(Candidate("zaaaa", "zaaaa", 1)),
            decoder.suggest("zaaaa", verbatimFirst = true),
        )

        val recovered = engine.reduce(spaced.state, EngineAct.Backspace, EnterKind.Newline)

        assertEquals("zaaaa", recovered.state.raw)
        assertTrue(recovered.state.verbatimFirst)
        assertEquals(listOf("zaaaa"), recovered.state.candidates.map { it.text })
        assertTrue(recovered.cmds.any { it is Cmd.DeleteCount && it.count == 6 })
        assertTrue(recovered.cmds.any { it is Cmd.Compose && it.text == "zaaaa" })
    }

    private fun decoder(vararg words: Pair<String, Int>): LatinDecoder =
        LatinDecoder().apply { load(words.asSequence()) }
}
