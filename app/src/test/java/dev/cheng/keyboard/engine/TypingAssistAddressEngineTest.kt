package dev.cheng.keyboard.engine

import dev.cheng.keyboard.model.Candidate
import dev.cheng.keyboard.model.Choice
import dev.cheng.keyboard.model.EnterKind
import dev.cheng.keyboard.model.Language
import dev.cheng.keyboard.model.Pending
import dev.cheng.keyboard.model.ShiftState
import dev.cheng.keyboard.model.TouchSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TypingAssistAddressEngineTest {
    private val engine = InputEngine(
        LatinDecoder().apply {
            load(sequenceOf("hello" to 800_000, "the" to 2_000_000, "don't" to 400_000))
        },
    )
    private val atActions = listOf(EngineAct.Direct("@"), EngineAct.Letter('a', Choice.Symbol, "@"))

    @Test
    fun englishTextPrefixesCommitLiterallyThroughBothAtActions() {
        for (raw in listOf("qsr", "abc", "hellp", "teh", "i", "im", "dont", "Qsr")) {
            for (act in atActions) {
                var state = EngineState(language = Language.English)
                for (ch in raw) {
                    val typed = engine.reduce(
                        state,
                        EngineAct.Letter(ch, if (ch.isUpperCase()) Choice.Upper else null, ""),
                        EnterKind.Newline,
                    )
                    assertEquals(listOf(Cmd.Compose(state.raw + ch)), typed.cmds)
                    state = typed.state
                }
                assertEquals(raw, state.raw)
                if (raw == "hellp") assertEquals("hello", state.candidates.first().text)
                if (raw == "dont") assertEquals("don't", state.candidates.first().text)

                val step = engine.reduce(state, act, EnterKind.Newline)

                assertEquals("$raw / $act", listOf(Cmd.Commit(raw), Cmd.Commit("@")), step.cmds)
                assertAddressSessionCleared(step)
            }
        }
    }

    @Test
    fun atClearsPendingPredictionUndoAndTouchStateWithoutLearning() {
        val state = EngineState(
            language = Language.English,
            shift = ShiftState.Once,
            raw = "im",
            preedit = "im",
            candidates = listOf(Candidate("i'm", "i'm", 500_000)),
            associating = true,
            partialText = "先",
            partialSyllables = "xian",
            autoSpace = true,
            lastEnglishWord = "hello",
            undoAutocorrect = "teh" to 4,
            verbatimFirst = true,
            lastChineseContext = "先",
            touchHints = listOf("o", "n"),
            touchSamples = listOf(TouchSample(primary = 'i'), TouchSample(primary = 'm')),
        )
        for (act in atActions) {
            val step = engine.reduce(state, act, EnterKind.Newline)

            assertEquals(listOf(Cmd.Commit("im"), Cmd.Commit("@")), step.cmds)
            assertAddressSessionCleared(step)
            assertEquals(ShiftState.Off, step.state.shift)
            val backspace = engine.reduce(step.state, EngineAct.Backspace, EnterKind.Newline)
            assertEquals(listOf(Cmd.DeleteOne), backspace.cmds)

            val next = engine.reduce(step.state, EngineAct.Letter('g', null, ""), EnterKind.Newline)
            assertEquals("g", next.state.raw)
            assertEquals(listOf(Cmd.Compose("g")), next.cmds)
            assertEquals(listOf(""), next.state.touchHints)
            assertEquals(listOf<TouchSample?>(null), next.state.touchSamples)
        }
    }

    @Test
    fun literalAtPreservesCapsAndPrivateAddressFlags() {
        for (uriOrEmail in listOf(false, true)) {
            for (noLearn in listOf(false, true)) {
                for (act in atActions) {
                    val state = EngineState(
                        language = Language.English,
                        shift = ShiftState.Caps,
                        raw = "IM",
                        preedit = "IM",
                        candidates = listOf(Candidate("i'm", "i'm", 500_000)),
                        uriOrEmail = uriOrEmail,
                        noLearn = noLearn,
                    )
                    val step = engine.reduce(state, act, EnterKind.Go)

                    assertEquals(listOf(Cmd.Commit("IM"), Cmd.Commit("@")), step.cmds)
                    assertAddressSessionCleared(step)
                    assertEquals(ShiftState.Caps, step.state.shift)
                    assertEquals(uriOrEmail, step.state.uriOrEmail)
                    assertEquals(noLearn, step.state.noLearn)
                }
            }
        }
    }

    @Test
    fun atAfterCandidatePickKeepsTheCommittedSpace() {
        val picked = engine.reduce(
            EngineState(language = Language.English, raw = "qsr", candidates = listOf(Candidate("qsr", "qsr", 1))),
            EngineAct.Pick(0),
            EnterKind.Newline,
        )
        assertEquals(Cmd.Commit("qsr "), picked.cmds.first())
        assertTrue(picked.state.autoSpace)
        for (act in atActions) {
            val step = engine.reduce(picked.state, act, EnterKind.Newline)

            assertEquals(listOf(Cmd.Commit("@")), step.cmds)
            assertEquals("qsr @", applyAskDraft("qsr ", step.cmds))
            assertAddressSessionCleared(step)
        }
    }

    @Test
    fun atAfterSpaceAutocorrectionDoesNotUndoTheCommittedWordOrSpace() {
        val spaced = engine.reduce(
            EngineState(language = Language.English, raw = "teh", candidates = listOf(Candidate("the", "the", 2_000_000))),
            EngineAct.Space,
            EnterKind.Newline,
        )
        assertEquals(Cmd.Commit("the "), spaced.cmds.first())
        assertEquals("teh" to 4, spaced.state.undoAutocorrect)
        for (act in atActions) {
            val step = engine.reduce(spaced.state, act, EnterKind.Newline)

            assertEquals(listOf(Cmd.Commit("@")), step.cmds)
            assertEquals("the @", applyAskDraft("the ", step.cmds))
            assertAddressSessionCleared(step)
            assertEquals(listOf(Cmd.DeleteOne), engine.reduce(step.state, EngineAct.Backspace, EnterKind.Newline).cmds)
        }
    }

    @Test
    fun atWithoutRawOnlyCommitsTheSeparator() {
        for (language in Language.entries) {
            for (act in atActions) {
                val state = EngineState(
                    language = language,
                    candidates = listOf(Candidate("人", "ren", 100)),
                    associating = true,
                    lastChineseContext = "其实",
                    lastEnglishWord = "hello",
                    autoSpace = true,
                )
                val step = engine.reduce(state, act, EnterKind.Newline)

                assertEquals(listOf(Cmd.Commit("@")), step.cmds)
                assertAddressSessionCleared(step)
            }
        }
    }

    @Test
    fun otherEnglishSymbolsKeepPendingResolutionAndPunctuationSpacing() {
        for ((raw, expected) in listOf("dont" to "don't", "i" to "I")) {
            val state = EngineState(
                language = Language.English,
                raw = raw,
                candidates = listOf(Candidate(expected, expected, 500_000)),
            )
            for (act in listOf(EngineAct.Direct("!"), EngineAct.Letter('h', Choice.Symbol, "!"))) {
                val step = engine.reduce(state, act, EnterKind.Newline)

                assertEquals(listOf(Cmd.Commit(expected), Cmd.Commit("!")), step.cmds)
                assertEquals(ShiftState.Once, step.state.shift)
            }
        }
        val period = engine.reduce(
            EngineState(language = Language.English, autoSpace = true),
            EngineAct.Direct("."),
            EnterKind.Newline,
        )
        assertEquals(listOf(Cmd.DeleteOne, Cmd.Commit(". ")), period.cmds)
        assertEquals(ShiftState.Once, period.state.shift)
    }

    @Test
    fun chineseAtCommitsRawLiterallyEvenWithChineseOrCorrectedEnglishCandidates() {
        for (act in atActions) {
            for ((raw, candidate) in listOf(
                "qsr" to Candidate("其实人", "qi shi ren", 500_000),
                "ni'hao" to Candidate("你", "ni", 80, consumed = 2),
                "hellp" to Candidate("hello", "", 800_000),
            )) {
                val state = EngineState(raw = raw, preedit = raw, candidates = listOf(candidate))
                val step = engine.reduce(state, act, EnterKind.Newline)

                assertEquals(listOf(Cmd.Commit(raw), Cmd.Commit("@")), step.cmds)
                assertAddressSessionCleared(step)
            }
        }
    }

    @Test
    fun chineseAtAfterPartialPickDoesNotRepeatOrLearnAlreadyCommittedChinese() {
        val state = EngineState(
            raw = "nihaoqsr",
            preedit = "ni'hao'qsr",
            candidates = listOf(Candidate("你好", "ni hao", 500_000, consumed = 5)),
            lastChineseContext = "之前",
            touchHints = List(8) { "a" },
            touchSamples = List(8) { TouchSample(primary = 'q') },
        )
        val picked = engine.reduce(state, EngineAct.Pick(0), EnterKind.Newline)
        assertEquals(listOf(Cmd.Commit("你好"), Cmd.Compose("qsr")), picked.cmds)
        assertEquals("你好", picked.state.partialText)
        assertEquals("ni hao", picked.state.partialSyllables)
        assertEquals("qsr", picked.state.raw)
        assertEquals(Pending.Pinyin, picked.pending)
        val remaining = picked.state.copy(candidates = listOf(Candidate("其实人", "qi shi ren", 500_000)))
        for (act in atActions) {
            val step = engine.reduce(remaining, act, EnterKind.Newline)

            assertEquals(listOf(Cmd.Commit("qsr"), Cmd.Commit("@")), step.cmds)
            assertEquals("你好qsr@", applyAskDraft("你好", step.cmds))
            assertAddressSessionCleared(step)
        }
    }

    @Test
    fun chineseAtWithoutCandidatesCommitsRawLiterally() {
        for (act in atActions) {
            val step = engine.reduce(EngineState(raw = "qsr", preedit = "qsr"), act, EnterKind.Newline)

            assertEquals(listOf(Cmd.Commit("qsr"), Cmd.Commit("@")), step.cmds)
            assertAddressSessionCleared(step)
        }
    }

    @Test
    fun otherChineseSymbolsKeepCandidateSelectionAndLearning() {
        val state = EngineState(raw = "ni'hao", candidates = listOf(Candidate("你", "ni", 80, consumed = 2)))
        for (act in listOf(EngineAct.Direct("，"), EngineAct.Letter('h', Choice.Symbol, "，"))) {
            val step = engine.reduce(state, act, EnterKind.Newline)

            assertEquals(listOf(Cmd.Commit("你hao"), Cmd.LearnZh("你", "ni"), Cmd.Commit("，")), step.cmds)
        }
    }

    @Test
    fun passwordInputsContinueToCommitDirectlyWithoutCompositionOrLearning() {
        for (language in Language.entries) {
            for (at in atActions) {
                var state = EngineState(language = language, password = true)
                for (ch in "im") {
                    val typed = engine.reduce(state, EngineAct.Letter(ch, null, ""), EnterKind.Newline)
                    assertEquals(listOf(Cmd.Commit(ch.toString())), typed.cmds)
                    assertEquals(state, typed.state)
                    state = typed.state
                }
                val step = engine.reduce(state, at, EnterKind.Newline)

                assertEquals(listOf(Cmd.Commit("@")), step.cmds)
                assertEquals(state, step.state)
                assertEquals(Pending.None, step.pending)
            }
        }
    }

    private fun assertAddressSessionCleared(step: EngineStep) {
        val state = step.state
        assertEquals("", state.raw)
        assertEquals("", state.preedit)
        assertTrue(state.candidates.isEmpty())
        assertFalse(state.associating)
        assertEquals("", state.partialText)
        assertEquals("", state.partialSyllables)
        assertFalse(state.autoSpace)
        assertNull(state.lastEnglishWord)
        assertNull(state.undoAutocorrect)
        assertFalse(state.verbatimFirst)
        assertEquals("", state.lastChineseContext)
        assertTrue(state.touchHints.isEmpty())
        assertTrue(state.touchSamples.isEmpty())
        assertEquals(Pending.None, step.pending)
        assertNull(step.associateFrom)
    }
}
