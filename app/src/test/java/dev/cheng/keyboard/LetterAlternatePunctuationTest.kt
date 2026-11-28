package dev.cheng.keyboard

import dev.cheng.keyboard.engine.Cmd
import dev.cheng.keyboard.engine.EngineAct
import dev.cheng.keyboard.engine.EngineState
import dev.cheng.keyboard.engine.InputEngine
import dev.cheng.keyboard.engine.LatinDecoder
import dev.cheng.keyboard.ime.gesture.KeyGesture
import dev.cheng.keyboard.ime.layout.keyboardRows
import dev.cheng.keyboard.model.Candidate
import dev.cheng.keyboard.model.Choice
import dev.cheng.keyboard.model.EnterKind
import dev.cheng.keyboard.model.KeyKind
import dev.cheng.keyboard.model.KeyModel
import dev.cheng.keyboard.model.KeyboardPage
import dev.cheng.keyboard.model.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LetterAlternatePunctuationTest {
    private val engine = InputEngine(LatinDecoder())
    private val letters = "qwertyuiopasdfghjklzxcvbnm"
    private val englishHints =
        "1234567890~!@#%\"\"*?" +
            "()-_:;,"
    private val chineseHints =
        "1234567890\uFF5E\uFF01\uFF20\uFF03\uFF05\u201C\u201D\uFF0A\uFF1F" +
            "\uFF08\uFF09\uFF0D\uFF3F\uFF1A\uFF1B\u3001"

    private fun gesture() = KeyGesture(
        slopPx = 16f,
        caseSlopPx = 34f,
        swipePx = 36f,
        verticalFlickPx = 46f,
    )

    private fun key(language: Language, id: String): KeyModel =
        keyboardRows(KeyboardPage.Letters, language).flatMap { it.keys }.first { it.id == id }

    private fun longPress(key: KeyModel): EngineAct.Letter {
        val gesture = gesture()
        gesture.down(key, 100f, 100f, 0L)
        gesture.alarm(350L)
        return gesture.up().actions.single() as EngineAct.Letter
    }

    private fun commits(step: dev.cheng.keyboard.engine.EngineStep): List<String> =
        step.cmds.filterIsInstance<Cmd.Commit>().map { it.text }

    @Test
    fun everyLetterPopupAndEngineActionMatchesTheActiveLanguageHint() {
        assertEquals(26, letters.length)
        assertEquals(letters.length, englishHints.length)
        assertEquals(letters.length, chineseHints.length)

        var state = EngineState(language = Language.Chinese)
        for ((language, hints) in listOf(
            Language.Chinese to chineseHints,
            Language.English to englishHints,
        )) {
            if (state.language != language) {
                state = engine.reduce(state, EngineAct.Language, EnterKind.Newline).state
            }
            assertEquals(language, state.language)

            val keys = keyboardRows(KeyboardPage.Letters, language)
                .flatMap { it.keys }
                .filter { it.kind == KeyKind.Letter }
            assertEquals(letters.toList(), keys.map { it.label.single() })

            keys.forEachIndexed { index, letterKey ->
                val expected = hints[index].toString()
                assertEquals("$language ${letterKey.label}", expected, letterKey.hint)

                val gesture = gesture()
                gesture.down(letterKey, 100f, 100f, 0L)
                val armed = gesture.alarm(350L)
                assertEquals("$language ${letterKey.label}", expected, armed.popup?.symbol)
                assertEquals(Choice.Symbol, armed.popup?.choice)

                val action = gesture.up().actions.single() as EngineAct.Letter
                assertEquals(expected, action.symbol)
                assertEquals(
                    listOf(expected),
                    commits(engine.reduce(state, action, EnterKind.Newline)),
                )
            }
        }

        assertEquals(
            "\uFF0D",
            key(Language.Chinese, "l-c").hint,
        )
        assertEquals("\"", key(Language.English, "l-h").hint)
        assertEquals("\"", key(Language.English, "l-j").hint)
    }

    @Test
    fun verticalAndHorizontalLetterGesturesKeepPopupAndActionInSync() {
        val chineseD = key(Language.Chinese, "l-d")
        var gesture = gesture()
        gesture.down(chineseD, 100f, 100f, 0L)
        val upFlick = gesture.move(100f, 40f, 60L, chineseD)
        assertEquals("\uFF20", upFlick.popup?.symbol)
        val upAction = gesture.up().actions.single() as EngineAct.Letter
        assertEquals(Choice.Symbol, upAction.force)
        assertEquals("\uFF20", upAction.symbol)

        gesture = gesture()
        gesture.down(chineseD, 100f, 100f, 0L)
        gesture.alarm(350L)
        val upper = gesture.move(60f, 100f, 380L, chineseD)
        assertEquals(Choice.Upper, upper.popup?.choice)
        assertEquals("D", upper.popup?.upper)
        val upperAction = gesture.up().actions.single() as EngineAct.Letter
        assertEquals(Choice.Upper, upperAction.force)
        assertEquals(
            "D",
            commits(
                engine.reduce(
                    EngineState(language = Language.Chinese),
                    upperAction,
                    EnterKind.Newline,
                ),
            ).single(),
        )

        val englishE = key(Language.English, "l-e")
        gesture = gesture()
        gesture.down(englishE, 100f, 100f, 0L)
        gesture.alarm(350L)
        val lower = gesture.move(140f, 100f, 380L, englishE)
        assertEquals(Choice.Lower, lower.popup?.choice)
        assertEquals("e", lower.popup?.lower)
        val lowerAction = gesture.up().actions.single() as EngineAct.Letter
        assertEquals(Choice.Lower, lowerAction.force)
        assertEquals(
            "e",
            engine.reduce(
                EngineState(language = Language.English),
                lowerAction,
                EnterKind.Newline,
            ).state.raw,
        )
    }

    @Test
    fun realLongPressActionsResolveCandidatesBeforeAppendingLanguagePunctuation() {
        val chineseD = longPress(key(Language.Chinese, "l-d"))
        assertEquals("\uFF20", chineseD.symbol)
        val chineseState = EngineState(
            language = Language.Chinese,
            raw = "ni",
            preedit = "ni",
            candidates = listOf(Candidate("你", "ni", 100, consumed = 2)),
        )
        assertEquals(
            listOf("你", "\uFF20"),
            commits(engine.reduce(chineseState, chineseD, EnterKind.Newline)),
        )
        assertEquals(
            listOf("ni", "@"),
            commits(
                engine.reduce(
                    chineseState,
                    EngineAct.Letter('d', Choice.Symbol, "@"),
                    EnterKind.Newline,
                ),
            ),
        )
        assertNotEquals(chineseD.symbol, "@")

        val englishBang = longPress(key(Language.English, "l-s"))
        assertEquals("!", englishBang.symbol)
        val englishState = EngineState(
            language = Language.English,
            raw = "dont",
            preedit = "dont",
            candidates = listOf(Candidate("don't", "don't", 100)),
        )
        assertEquals(
            listOf("don't", "!"),
            commits(engine.reduce(englishState, englishBang, EnterKind.Newline)),
        )

        val englishAt = longPress(key(Language.English, "l-d"))
        assertEquals("@", englishAt.symbol)
        assertEquals(
            listOf("dont", "@"),
            commits(engine.reduce(englishState, englishAt, EnterKind.Newline)),
        )
        assertEquals(
            listOf("dont", "@"),
            commits(engine.reduce(englishState.copy(uriOrEmail = true), englishAt, EnterKind.Newline)),
        )

        val gesture = gesture()
        gesture.down(key(Language.English, "l-d"), 100f, 100f, 0L)
        gesture.alarm(350L)
        val released = gesture.up()
        assertEquals(1, released.actions.size)
        assertNull(released.popup)
    }
}
