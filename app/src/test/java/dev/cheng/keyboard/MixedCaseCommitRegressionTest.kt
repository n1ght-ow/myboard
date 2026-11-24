package dev.cheng.keyboard

import dev.cheng.keyboard.engine.Cmd
import dev.cheng.keyboard.engine.EngineAct
import dev.cheng.keyboard.engine.EngineState
import dev.cheng.keyboard.engine.EngineStep
import dev.cheng.keyboard.engine.InputEngine
import dev.cheng.keyboard.engine.LatinDecoder
import dev.cheng.keyboard.engine.applyCase
import dev.cheng.keyboard.model.Candidate
import dev.cheng.keyboard.model.EnterKind
import dev.cheng.keyboard.model.Language
import org.junit.Assert.assertEquals
import org.junit.Test

class MixedCaseCommitRegressionTest {
    private val engine = InputEngine(LatinDecoder())

    @Test
    fun preservesExplicitInternalMixedCaseForSameSpelling() {
        assertEquals("iPhone", applyCase("iphone", "iPhone"))
        assertEquals("eBay", applyCase("ebay", "eBay"))
        assertEquals("OpenAI", applyCase("openai", "OpenAI"))
    }

    @Test
    fun keepsCandidateCasingWithoutExplicitInternalMixedInput() {
        assertEquals("NASA", applyCase("NASA", "nasa"))
        assertEquals("iPhone", applyCase("iPhone", "iphone"))
        assertEquals("eBay", applyCase("eBay", "ebay"))
        assertEquals("OpenAI", applyCase("OpenAI", "openai"))
    }

    @Test
    fun keepsPronounAndContractionSpecialCases() {
        assertEquals("I", applyCase("i", "i"))
        assertEquals("I", applyCase("i", "I"))
        assertEquals("I'm", applyCase("i'm", "im"))
        assertEquals("I'm", applyCase("i'm", "I'm"))
        assertEquals("I'LL", applyCase("i'll", "I'LL"))
    }

    @Test
    fun keepsNormalFirstLetterAndUppercaseRulesForOtherInput() {
        assertEquals("Hello", applyCase("hello", "Hello"))
        assertEquals("HELLO", applyCase("hello", "HELLO"))
        assertEquals("Hello", applyCase("hello", "IPhone"))
        assertEquals("World", applyCase("world", "EBay"))
    }

    @Test
    fun engineSpaceAndCandidatePickCommitPreservedMixedCase() {
        assertEquals("iPhone ", commitOnSpace("iPhone", "iphone"))
        assertEquals("eBay ", commitOnSpace("eBay", "ebay"))
        assertEquals("OpenAI ", commitOnSpace("OpenAI", "openai"))
        assertEquals("iPhone ", commitOnPick("iPhone", "iphone"))
    }

    private fun commitOnSpace(raw: String, candidate: String): String =
        firstCommit(engineStep(raw, candidate, EngineAct.Space))

    private fun commitOnPick(raw: String, candidate: String): String =
        firstCommit(engineStep(raw, candidate, EngineAct.Pick(0)))

    private fun engineStep(raw: String, candidate: String, act: EngineAct): EngineStep {
        val state = EngineState(
            language = Language.English,
            raw = raw,
            preedit = raw,
            candidates = listOf(Candidate(candidate, candidate.lowercase(), 100)),
        )
        return engine.reduce(state, act, EnterKind.Newline)
    }

    private fun firstCommit(step: EngineStep): String =
        (step.cmds.first() as Cmd.Commit).text
}
