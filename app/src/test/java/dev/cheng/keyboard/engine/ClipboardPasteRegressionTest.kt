package dev.cheng.keyboard.engine

import dev.cheng.keyboard.model.Candidate
import dev.cheng.keyboard.model.EnterKind
import dev.cheng.keyboard.model.KeyboardPage
import dev.cheng.keyboard.model.Language
import dev.cheng.keyboard.model.Pending
import dev.cheng.keyboard.model.ShiftState
import dev.cheng.keyboard.model.TouchSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardPasteRegressionTest {
    private val engine = InputEngine(LatinDecoder())

    @Test
    fun pasteCommitsPunctuationQuotesMultilineAndEmojiVerbatim() {
        val payloads = listOf(
            ",",
            "2",
            "hello",
            "()",
            "\"quoted\"",
            "'quoted'",
            "“引号”",
            "。",
            "@",
            "第一行\n第二行",
            "😀🧪",
        )

        for (payload in payloads) {
            val before = EngineState(
                language = Language.Chinese,
                shift = ShiftState.Caps,
                page = KeyboardPage.Symbols,
                candidates = listOf(Candidate("旧候选", "jiu", 100)),
                associating = true,
                partialText = "旧",
                partialSyllables = "jiu",
                autoSpace = true,
                lastEnglishWord = "old",
                undoAutocorrect = "ol" to 3,
                verbatimFirst = true,
                lastChineseContext = "旧文",
                touchHints = listOf("j"),
                touchSamples = listOf(TouchSample(primary = 'j')),
            )

            val step = paste(before, payload)

            assertEquals("payload=$payload", listOf<Cmd>(Cmd.Commit(payload)), step.cmds)
            assertNoPasteEdits(step)
            assertPasteStateCleared(step)
            assertPreferencesPreserved(before, step.state)
        }
    }

    @Test
    fun pasteCommaAfterEnglishAutoSpaceDoesNotDeleteOrAppendSpace() {
        val before = EngineState(
            language = Language.English,
            shift = ShiftState.Caps,
            page = KeyboardPage.Number,
            autoSpace = true,
            lastEnglishWord = "hello",
            undoAutocorrect = "helo" to 5,
        )

        val step = paste(before, ",")

        assertEquals(listOf<Cmd>(Cmd.Commit(",")), step.cmds)
        assertNoPasteEdits(step)
        assertPasteStateCleared(step)
        assertPreferencesPreserved(before, step.state)
    }

    @Test
    fun pasteConfirmsOrdinaryCompositionBeforeExactClipboardText() {
        val payload = "hello\n🍎"
        val chinese = EngineState(
            language = Language.Chinese,
            shift = ShiftState.Once,
            page = KeyboardPage.Letters,
            raw = "ni",
            preedit = "ni",
            candidates = listOf(Candidate("你", "ni", 1_000, consumed = 2)),
            lastChineseContext = "前",
            autoSpace = true,
            lastEnglishWord = "hello",
            undoAutocorrect = "teh" to 4,
            verbatimFirst = true,
        )

        val chineseStep = paste(chinese, payload)

        assertEquals(
            listOf<Cmd>(
                Cmd.Commit("你"),
                Cmd.LearnZh("你", "ni", prevContext = "前"),
                Cmd.Commit(payload),
            ),
            chineseStep.cmds,
        )
        assertNoPasteEdits(chineseStep)
        assertPasteStateCleared(chineseStep)
        assertPreferencesPreserved(chinese, chineseStep.state)

        val english = EngineState(
            language = Language.English,
            shift = ShiftState.Caps,
            page = KeyboardPage.Symbols,
            raw = "dont",
            preedit = "dont",
            candidates = listOf(Candidate("don't", "don't", 500_000)),
            associating = true,
            partialText = "先",
            partialSyllables = "xian",
            autoSpace = true,
            lastEnglishWord = "before",
            undoAutocorrect = "dnot" to 5,
            verbatimFirst = true,
            lastChineseContext = "先",
        )

        val englishStep = paste(english, payload)

        assertEquals(
            listOf<Cmd>(Cmd.Commit("don't"), Cmd.Commit(payload)),
            englishStep.cmds,
        )
        assertNoPasteEdits(englishStep)
        assertPasteStateCleared(englishStep)
        assertPreferencesPreserved(english, englishStep.state)
    }

    @Test
    fun pasteConfirmsPrivateContextRawLiterally() {
        val guards = listOf(
            "password" to EngineState(
                language = Language.English,
                password = true,
                raw = "dont",
                preedit = "dont",
                candidates = listOf(Candidate("don't", "don't", 500_000)),
            ),
            "uri" to EngineState(
                language = Language.English,
                raw = "dont",
                preedit = "dont",
                candidates = listOf(Candidate("don't", "don't", 500_000)),
                uriOrEmail = true,
            ),
            "literal" to EngineState(
                language = Language.Chinese,
                raw = "dont",
                preedit = "dont",
                candidates = listOf(Candidate("东土", "dong tu", 500_000)),
                literalContext = true,
            ),
        )

        for ((name, before) in guards) {
            for (payload in listOf("。", "@")) {
                val step = paste(before, payload)

                assertEquals(
                    "$name payload=$payload",
                    listOf<Cmd>(Cmd.Commit("dont"), Cmd.Commit(payload)),
                    step.cmds,
                )
                assertNoPasteEdits(step)
                assertPasteStateCleared(step)
                assertPreferencesPreserved(before, step.state)
            }
        }
    }

    @Test
    fun noLearnConfirmsNormalPendingButStillNeverLearnsClipboardText() {
        val before = EngineState(
            language = Language.Chinese,
            noLearn = true,
            raw = "ni",
            preedit = "ni",
            candidates = listOf(Candidate("你", "ni", 1_000, consumed = 2)),
            partialText = "前",
            partialSyllables = "qian",
            lastChineseContext = "前",
            autoSpace = true,
        )

        val step = paste(before, "hello")

        assertEquals(listOf<Cmd>(Cmd.Commit("你"), Cmd.Commit("hello")), step.cmds)
        assertNoPasteEdits(step)
        assertPasteStateCleared(step)
        assertPreferencesPreserved(before, step.state)
    }

    @Test
    fun emptyPasteHasNoSideEffects() {
        val before = EngineState(
            language = Language.Chinese,
            raw = "ni",
            preedit = "ni",
            candidates = listOf(Candidate("你", "ni", 1_000, consumed = 2)),
            associating = true,
            partialText = "前",
            partialSyllables = "qian",
            autoSpace = true,
            lastEnglishWord = "before",
            undoAutocorrect = "befor" to 6,
            verbatimFirst = true,
            lastChineseContext = "前",
            touchHints = listOf("n", "i"),
            touchSamples = listOf(TouchSample(primary = 'n'), TouchSample(primary = 'i')),
        )

        val step = paste(before, "")

        assertEquals(before, step.state)
        assertTrue(step.cmds.isEmpty())
        assertEquals(Pending.None, step.pending)
        assertNull(step.associateFrom)
    }

    @Test
    fun directActionRetainsExistingSmartPunctuationBehavior() {
        val comma = engine.reduce(
            EngineState(language = Language.English, autoSpace = true),
            EngineAct.Direct(","),
            EnterKind.Done,
        )
        assertEquals(listOf<Cmd>(Cmd.DeleteOne, Cmd.Commit(", ")), comma.cmds)

        val period = engine.reduce(
            EngineState(language = Language.Chinese, literalContext = true),
            EngineAct.Direct("。"),
            EnterKind.Done,
        )
        assertEquals(listOf<Cmd>(Cmd.Commit(".")), period.cmds)
    }

    private fun paste(state: EngineState, text: String): EngineStep =
        engine.reduce(state, EngineAct.Paste(text), EnterKind.Done)

    private fun assertPasteStateCleared(step: EngineStep) {
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

    private fun assertNoPasteEdits(step: EngineStep) {
        assertFalse(step.cmds.contains(Cmd.ClearCompose))
        assertFalse(step.cmds.contains(Cmd.DeleteOne))
        assertFalse(step.cmds.contains(Cmd.DeleteWord))
        assertTrue(
            step.cmds.none {
                it is Cmd.Compose ||
                    it is Cmd.DeleteCount ||
                    it is Cmd.MoveCursor ||
                    it is Cmd.EditorAction
            },
        )
    }

    private fun assertPreferencesPreserved(before: EngineState, after: EngineState) {
        assertEquals(before.language, after.language)
        assertEquals(before.shift, after.shift)
        assertEquals(before.password, after.password)
        assertEquals(before.noLearn, after.noLearn)
        assertEquals(before.page, after.page)
        assertEquals(before.uriOrEmail, after.uriOrEmail)
        assertEquals(before.literalContext, after.literalContext)
    }
}
