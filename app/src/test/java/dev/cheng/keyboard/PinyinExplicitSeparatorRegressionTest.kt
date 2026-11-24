package dev.cheng.keyboard

import dev.cheng.keyboard.engine.Cmd
import dev.cheng.keyboard.engine.EngineAct
import dev.cheng.keyboard.engine.EngineState
import dev.cheng.keyboard.engine.InputEngine
import dev.cheng.keyboard.engine.LatinDecoder
import dev.cheng.keyboard.engine.PinyinDecoder
import dev.cheng.keyboard.model.EnterKind
import dev.cheng.keyboard.model.KeyLikelihood
import dev.cheng.keyboard.model.LexRow
import dev.cheng.keyboard.model.MemoryLexicon
import dev.cheng.keyboard.model.TouchSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinyinExplicitSeparatorRegressionTest {
    private val decoder = PinyinDecoder(
        setOf("xi", "an", "xian", "na", "xin", "ni", "qi", "neng", "xing", "in", "hao"),
    ).also {
        it.lexicon = MemoryLexicon(
            listOf(
                LexRow("西", "xi", "xi", 4_000_000),
                LexRow("西安", "xi an", "xian", 800_000),
                LexRow("先", "xian", "xian", 5_000_000),
                LexRow("新", "xin", "xin", 8_000_000),
                LexRow("西娜", "xi na", "xina", 8_000),
                LexRow("行", "xing", "xing", 8_000_000),
                LexRow("你", "ni", "ni", 9_000_000),
                LexRow("你好", "ni hao", "nihao", 5_000_000),
            ),
        )
    }

    @Test
    fun failedExplicitSplitKeepsOnlyTheValidatedHead() {
        for (raw in listOf("xi'ng", "xi'nq")) {
            val decoded = decoder.decode(raw, fuzzy = false)
            assertEquals(raw, decoded.preedit)
            assertTrue("$raw must not decode across the separator", decoded.candidates.none { it.text == "行" })
            val west = decoded.candidates.first { it.text == "西" }
            assertEquals("$raw must consume only xi", 2, west.consumed)
        }
    }

    @Test
    fun successfulSplitStillBlocksWholeStringRepairs() {
        val decoded = decoder.decode("xi'na", fuzzy = false)
        assertEquals("xi'na", decoded.preedit)
        assertTrue(decoded.candidates.none { it.text == "先" || it.text == "新" })
        assertEquals(2, decoded.candidates.first { it.text == "西" }.consumed)
        assertTrue(decoded.candidates.any { it.text == "西娜" && it.consumed == 4 })
    }

    @Test
    fun doubleLeadAndTouchRepairsCannotCrossInternalSeparator() {
        val doubled = decoder.decode("n'nihao", fuzzy = false)
        assertEquals("n'nihao", doubled.preedit)
        assertTrue(doubled.candidates.none { it.text == "你好" })

        val touch = decoder.decode(
            "xi'nq",
            fuzzy = false,
            touchSamples = listOf(
                null,
                null,
                null,
                TouchSample(primary = 'q', alternatives = listOf(KeyLikelihood('g', 0.1f))),
            ),
        )
        assertEquals("xi'nq", touch.preedit)
        assertTrue(touch.candidates.none { it.text == "行" })
        assertEquals(2, touch.candidates.first { it.text == "西" }.consumed)
    }

    @Test
    fun explicitInitialChunksKeepAbbreviationSupport() {
        for (raw in listOf("n'h", "n'h'")) {
            val decoded = decoder.decode(raw, fuzzy = false)
            assertEquals(raw, decoded.preedit)
            val hello = decoded.candidates.first { it.text == "你好" }
            assertEquals(raw, 2, hello.consumed)
        }
    }

    @Test
    fun successfulExplicitSplitDoesNotConsumeTheNextPartialChunk() {
        val decoded = decoder.decode("ni'q", fuzzy = false)
        assertEquals("ni'q", decoded.preedit)
        val youIndex = decoded.candidates.indexOfFirst { it.text == "你" && it.consumed == 2 }
        assertTrue("missing 你 consumed=2 in ${decoded.candidates}", youIndex >= 0)

        val engine = InputEngine(LatinDecoder())
        val step = engine.reduce(
            EngineState(
                raw = "ni'q",
                preedit = decoded.preedit,
                candidates = decoded.candidates,
                noLearn = true,
            ),
            EngineAct.Pick(youIndex),
            EnterKind.Newline,
        )

        assertEquals("q", step.state.raw)
        assertEquals(listOf(Cmd.Commit("你"), Cmd.Compose("q")), step.cmds)
    }

    @Test
    fun explicitAndPlainXianKeepTheirPaths() {
        val explicit = decoder.decode("xi'an", fuzzy = false)
        assertEquals("xi'an", explicit.preedit)
        assertEquals("西安", explicit.candidates.first().text)
        assertEquals(4, explicit.candidates.first().consumed)

        val plain = decoder.decode("xian", fuzzy = false)
        assertEquals("xian", plain.preedit)
        assertEquals("先", plain.candidates.first().text)
        assertEquals(4, plain.candidates.first().consumed)

        for (raw in listOf("'xian", "xian'")) {
            val edged = decoder.decode(raw, fuzzy = false)
            assertEquals(raw, edged.preedit)
            assertEquals("先", edged.candidates.first().text)
            assertEquals(4, edged.candidates.first().consumed)
        }
    }
}
