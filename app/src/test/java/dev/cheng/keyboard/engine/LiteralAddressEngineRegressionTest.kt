package dev.cheng.keyboard.engine

import dev.cheng.keyboard.model.Candidate
import dev.cheng.keyboard.model.Choice
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

class LiteralAddressEngineRegressionTest {
    private val engine = InputEngine(
        LatinDecoder().apply {
            load(
                sequenceOf(
                    "company" to 900_000,
                    "come" to 800_000,
                    "qq" to 600_000,
                ),
            )
        },
    )

    private val wrongChineseCandidate = Candidate("全球", "quan qiu", 999_000)
    private val wrongEnglishCandidate = Candidate("come", "come", 999_000)

    @Test
    fun chineseAndEnglishTypeQqAndCompanyAddressesLiterally() {
        for (language in Language.entries) {
            for (address in listOf("qq.com", "company.com")) {
                val initial = EngineState(
                    language = language,
                    uriOrEmail = true,
                    literalContext = true,
                )
                val typed = typeAddress(initial, address)

                assertEquals(address, committedText(typed.cmds))
                assertEquals(language, typed.state.language)
                assertTrue(typed.state.literalContext)
                assertLiteralStateCleared(typed)

                val spaced = engine.reduce(typed.state, EngineAct.Space, EnterKind.Go)
                assertEquals(listOf<Cmd>(Cmd.Commit(" ")), spaced.cmds)
                assertEquals(language, spaced.state.language)
                assertTrue(spaced.state.literalContext)
                assertLiteralStateCleared(spaced)

                val searched = engine.reduce(typed.state, EngineAct.Enter, EnterKind.Search)
                assertEquals(listOf<Cmd>(Cmd.EditorAction(EnterKind.Search)), searched.cmds)
                assertEquals(language, searched.state.language)
                assertTrue(searched.state.literalContext)
                assertLiteralStateCleared(searched)
            }
        }
    }

    @Test
    fun staleCandidatesAreNeverSelectedByLiteralActions() {
        val dirty = dirtyAddressState()

        val spaced = engine.reduce(dirty, EngineAct.Space, EnterKind.Go)
        assertEquals(listOf<Cmd>(Cmd.Commit("qq"), Cmd.Commit(" ")), spaced.cmds)
        assertLiteralStateCleared(spaced)

        val dotted = engine.reduce(dirty, EngineAct.Direct("。"), EnterKind.Go)
        assertEquals(listOf<Cmd>(Cmd.Commit("qq"), Cmd.Commit(".")), dotted.cmds)
        assertLiteralStateCleared(dotted)

        val lettered = engine.reduce(dirty, EngineAct.Letter('c', null, ""), EnterKind.Go)
        assertEquals(listOf<Cmd>(Cmd.Commit("qq"), Cmd.Commit("C")), lettered.cmds)
        assertEquals(ShiftState.Off, lettered.state.shift)
        assertLiteralStateCleared(lettered)

        val entered = engine.reduce(dirty, EngineAct.Enter, EnterKind.Go)
        assertEquals(listOf<Cmd>(Cmd.Commit("qq"), Cmd.EditorAction(EnterKind.Go)), entered.cmds)
        assertLiteralStateCleared(entered)

        val moved = engine.reduce(dirty, EngineAct.MoveCursor(-1), EnterKind.Go)
        assertEquals(listOf<Cmd>(Cmd.Commit("qq"), Cmd.MoveCursor(-1)), moved.cmds)
        assertLiteralStateCleared(moved)
    }

    @Test
    fun literalPunctuationChangesOnlyTheChinesePeriodAndKeepsAtProtection() {
        val literal = EngineState(language = Language.Chinese, literalContext = true)
        for ((typed, expected) in listOf("。" to ".", "，" to "，", "！" to "！")) {
            val direct = engine.reduce(literal, EngineAct.Direct(typed), EnterKind.Newline)
            assertEquals(listOf<Cmd>(Cmd.Commit(expected)), direct.cmds)

            val key = engine.reduce(
                literal,
                EngineAct.Letter('p', Choice.Symbol, typed),
                EnterKind.Newline,
            )
            assertEquals(listOf<Cmd>(Cmd.Commit(expected)), key.cmds)
        }

        val address = EngineState(
            language = Language.Chinese,
            raw = "alice",
            preedit = "alice",
            candidates = listOf(wrongChineseCandidate),
            literalContext = true,
        )
        val at = engine.reduce(address, EngineAct.Direct("@"), EnterKind.Newline)
        assertEquals(listOf<Cmd>(Cmd.Commit("alice"), Cmd.Commit("@")), at.cmds)
        assertTrue(at.state.literalContext)
        assertLiteralStateCleared(at)
    }

    @Test
    fun literalEnterPreservesAllEditorActionsAndNewlineBehavior() {
        for (language in Language.entries) {
            for (kind in EnterKind.entries) {
                val step = engine.reduce(dirtyAddressState(language), EngineAct.Enter, kind)
                val action = if (kind == EnterKind.Newline) {
                    Cmd.Commit("\n")
                } else {
                    Cmd.EditorAction(kind)
                }

                assertEquals(listOf<Cmd>(Cmd.Commit("qq"), action), step.cmds)
                assertEquals(language, step.state.language)
                assertTrue(step.state.literalContext)
                assertLiteralStateCleared(step)
            }
        }
    }

    @Test
    fun literalMoveCursorFinishesRawAndStillMovesTheCursor() {
        for (language in Language.entries) {
            for (delta in listOf(-2, -1, 1, 3)) {
                val step = engine.reduce(
                    dirtyAddressState(language),
                    EngineAct.MoveCursor(delta),
                    EnterKind.Go,
                )

                assertEquals(listOf<Cmd>(Cmd.Commit("qq"), Cmd.MoveCursor(delta)), step.cmds)
                assertEquals(language, step.state.language)
                assertTrue(step.state.literalContext)
                assertLiteralStateCleared(step)
            }
        }
    }

    @Test
    fun manualLanguageAndPageChangesRemainImmediateInLiteralMode() {
        for (language in Language.entries) {
            val literal = EngineState(language = language, literalContext = true)
            val switched = engine.reduce(literal, EngineAct.Language, EnterKind.Go)
            val expectedLanguage = if (language == Language.Chinese) Language.English else Language.Chinese

            assertTrue(switched.cmds.isEmpty())
            assertEquals(expectedLanguage, switched.state.language)
            assertTrue(switched.state.literalContext)
            assertLiteralStateCleared(switched)

            val nextLetter = engine.reduce(switched.state, EngineAct.Letter('a', null, ""), EnterKind.Go)
            assertEquals(listOf<Cmd>(Cmd.Commit("a")), nextLetter.cmds)
            assertTrue(nextLetter.state.literalContext)
            assertLiteralStateCleared(nextLetter)

            val paged = engine.reduce(switched.state, EngineAct.Page(KeyboardPage.Symbols), EnterKind.Go)
            assertTrue(paged.cmds.isEmpty())
            assertEquals(KeyboardPage.Symbols, paged.state.page)
            assertTrue(paged.state.literalContext)
            assertLiteralStateCleared(paged)
        }
    }

    @Test
    fun literalShiftTypesAsciiAndConsumesOnceWhilePreservingCaps() {
        for (language in Language.entries) {
            val once = engine.reduce(
                EngineState(
                    language = language,
                    shift = ShiftState.Once,
                    literalContext = true,
                ),
                EngineAct.Letter('q', null, ""),
                EnterKind.Go,
            )
            assertEquals(listOf<Cmd>(Cmd.Commit("Q")), once.cmds)
            assertEquals(ShiftState.Off, once.state.shift)
            assertLiteralStateCleared(once)

            val lowerAfterOnce = engine.reduce(once.state, EngineAct.Letter('q', null, ""), EnterKind.Go)
            assertEquals(listOf<Cmd>(Cmd.Commit("q")), lowerAfterOnce.cmds)
            assertEquals(ShiftState.Off, lowerAfterOnce.state.shift)

            val caps = engine.reduce(
                EngineState(
                    language = language,
                    shift = ShiftState.Caps,
                    literalContext = true,
                ),
                EngineAct.Letter('q', null, ""),
                EnterKind.Go,
            )
            assertEquals(listOf<Cmd>(Cmd.Commit("Q")), caps.cmds)
            assertEquals(ShiftState.Caps, caps.state.shift)
            assertLiteralStateCleared(caps)
        }
    }

    @Test
    fun passwordGuardKeepsExistingLiteralInputBehavior() {
        val password = EngineState(
            language = Language.Chinese,
            password = true,
            literalContext = true,
        )

        val letter = engine.reduce(password, EngineAct.Letter('a', null, ""), EnterKind.Go)
        assertEquals(listOf<Cmd>(Cmd.Commit("a")), letter.cmds)
        assertEquals(password, letter.state)

        val period = engine.reduce(password, EngineAct.Direct("。"), EnterKind.Go)
        assertEquals(listOf<Cmd>(Cmd.Commit("。")), period.cmds)
        assertEquals(password, period.state)

        val space = engine.reduce(password, EngineAct.Space, EnterKind.Go)
        assertEquals(listOf<Cmd>(Cmd.Commit(" ")), space.cmds)
        assertEquals(password, space.state)

        for (kind in EnterKind.entries) {
            val entered = engine.reduce(password, EngineAct.Enter, kind)
            val expected = if (kind == EnterKind.Newline) {
                listOf<Cmd>(Cmd.Commit("\n"))
            } else {
                listOf<Cmd>(Cmd.EditorAction(kind))
            }
            assertEquals(expected, entered.cmds)
            assertEquals(password, entered.state)
        }
    }

    @Test
    fun disabledLiteralContextRestoresTheOrdinaryPinyinAndBackspacePaths() {
        for (language in Language.entries) {
            val ordinary = EngineState(language = language, literalContext = false)
            val typed = engine.reduce(ordinary, EngineAct.Letter('h', null, ""), EnterKind.Go)

            assertEquals(listOf<Cmd>(Cmd.Compose("h")), typed.cmds)
            assertEquals("h", typed.state.raw)
            assertFalse(typed.state.literalContext)
            assertEquals(
                if (language == Language.Chinese) Pending.Pinyin else Pending.None,
                typed.pending,
            )

            val backed = engine.reduce(typed.state, EngineAct.Backspace, EnterKind.Go)
            assertEquals(listOf<Cmd>(Cmd.ClearCompose), backed.cmds)
            assertEquals("", backed.state.raw)
            assertFalse(backed.state.literalContext)

            val moved = engine.reduce(typed.state, EngineAct.MoveCursor(1), EnterKind.Go)
            assertTrue(moved.cmds.isEmpty())
            assertEquals("h", moved.state.raw)
            assertFalse(moved.state.literalContext)
        }
    }

    private fun dirtyAddressState(language: Language = Language.Chinese): EngineState {
        val candidate = if (language == Language.Chinese) {
            wrongChineseCandidate
        } else {
            wrongEnglishCandidate
        }
        return EngineState(
            language = language,
            shift = ShiftState.Once,
            raw = "qq",
            preedit = "qq",
            candidates = listOf(candidate),
            associating = true,
            password = false,
            noLearn = true,
            partialText = "先",
            partialSyllables = "xian",
            autoSpace = true,
            lastEnglishWord = "hello",
            undoAutocorrect = "teh" to 4,
            verbatimFirst = true,
            lastChineseContext = "先",
            touchHints = listOf("q", "q"),
            touchSamples = listOf(TouchSample(primary = 'q'), TouchSample(primary = 'q')),
            uriOrEmail = true,
            literalContext = true,
        )
    }

    private fun typeAddress(initial: EngineState, address: String): EngineStep {
        var state = initial
        val cmds = mutableListOf<Cmd>()
        for (character in address) {
            val act = when (character) {
                '.' -> EngineAct.Direct(".")
                else -> EngineAct.Letter(
                    character,
                    if (character.isUpperCase()) Choice.Upper else null,
                    "",
                )
            }
            val step = engine.reduce(state, act, EnterKind.Go)
            cmds += step.cmds
            state = step.state
        }
        return EngineStep(state, cmds)
    }

    private fun committedText(cmds: List<Cmd>): String =
        cmds.filterIsInstance<Cmd.Commit>().joinToString("") { it.text }

    private fun assertLiteralStateCleared(step: EngineStep) {
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
        assertTrue(step.cmds.none { it is Cmd.LearnZh || it is Cmd.LearnZhBigram || it is Cmd.LearnEn })
    }
}
