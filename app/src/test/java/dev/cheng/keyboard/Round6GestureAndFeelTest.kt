package dev.cheng.keyboard

import dev.cheng.keyboard.engine.EngineAct
import dev.cheng.keyboard.ime.gesture.HapticKind
import dev.cheng.keyboard.ime.gesture.KeyGesture
import dev.cheng.keyboard.ime.layout.keyboardRows
import dev.cheng.keyboard.model.KeyKind
import dev.cheng.keyboard.model.KeyModel
import dev.cheng.keyboard.model.KeyboardPage
import dev.cheng.keyboard.model.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Round6GestureAndFeelTest {
    private fun gesture() = KeyGesture(
        slopPx = 16f,
        caseSlopPx = 34f,
        swipePx = 36f,
        verticalFlickPx = 46f,
    )

    private fun key(page: KeyboardPage, language: Language, id: String): KeyModel =
        keyboardRows(page, language).flatMap { it.keys }.first { it.id == id }

    @Test
    fun quickPunctuationFlickUpAndPullBackWorkOnEveryLetterPagePunctuationKey() {
        val g = gesture()

        for (case in letterPunctuationCases) {
            val punctuation = key(KeyboardPage.Letters, case.language, case.id)
            assertEquals(case.primary, punctuation.insert)
            assertEquals(case.quickChars, punctuation.quickChars)

            g.down(punctuation, 100f, 100f, 0L)
            val flick = g.move(100f, 40f, 60L, punctuation)
            assertEquals(HapticKind.Step, flick.haptic)
            assertEquals(case.quickChars[0], flick.popup?.upper)
            assertEquals(case.quickChars[1], flick.popup?.symbol)
            assertEquals(case.quickChars[2], flick.popup?.lower)
            assertEquals(listOf(EngineAct.Direct(case.quickChars[1])), g.up().actions)

            g.down(punctuation, 100f, 100f, 0L)
            g.move(100f, 40f, 60L, punctuation)
            val pulledBack = g.move(100f, 85f, 90L, punctuation)
            assertNull(pulledBack.popup)
            assertEquals(HapticKind.Step, pulledBack.haptic)
            assertEquals(listOf(EngineAct.Direct(case.primary)), g.up().actions)
        }
    }

    @Test
    fun quickPunctuationNeverArmsOrSelectsOnLongHoldOrHorizontalMovement() {
        val g = gesture()

        for (case in letterPunctuationCases) {
            val punctuation = key(KeyboardPage.Letters, case.language, case.id)

            g.down(punctuation, 100f, 100f, 0L)
            assertEquals(listOf(EngineAct.Direct(case.primary)), g.up().actions)

            g.down(punctuation, 100f, 100f, 0L)
            assertNull(g.nextAlarm(10L))

            val held = g.alarm(5_000L)
            assertNull(held.haptic)
            assertNull(held.popup)
            assertTrue(held.actions.isEmpty())
            assertEquals(listOf(EngineAct.Direct(case.primary)), g.up().actions)

            g.down(punctuation, 100f, 100f, 0L)
            g.alarm(5_000L)
            val smallHorizontal = g.move(76f, 100f, 5_001L, punctuation)
            assertNull(smallHorizontal.popup)
            assertTrue(smallHorizontal.actions.isEmpty())
            assertEquals(listOf(EngineAct.Direct(case.primary)), g.up().actions)

            g.down(punctuation, 100f, 100f, 0L)
            g.alarm(5_000L)
            val formerSelectionDistance = g.move(60f, 100f, 5_001L, punctuation)
            assertNull(formerSelectionDistance.popup)
            assertTrue(formerSelectionDistance.actions.isEmpty())
            assertTrue(g.up().actions.isEmpty())
        }
    }

    @Test
    fun punctuationOnOtherPagesNeverExposesLetterPageQuickChars() {
        val g = gesture()
        val pages = listOf(KeyboardPage.Number, KeyboardPage.Symbols, KeyboardPage.More)
        for (language in Language.entries) {
            for (page in pages) {
                for (id in listOf("comma", "period")) {
                    val punctuation = key(page, language, id)
                    assertTrue(punctuation.quickChars.isEmpty())

                    g.down(punctuation, 100f, 100f, 0L)
                    assertEquals(
                        listOf(EngineAct.Direct(punctuation.insert ?: punctuation.label)),
                        g.up().actions,
                    )

                    g.down(punctuation, 100f, 100f, 0L)
                    assertNull(g.nextAlarm(10L))
                    val held = g.alarm(5_000L)
                    assertNull(held.popup)
                    assertTrue(held.actions.isEmpty())
                    assertEquals(
                        listOf(EngineAct.Direct(punctuation.insert ?: punctuation.label)),
                        g.up().actions,
                    )

                    g.down(punctuation, 100f, 100f, 0L)
                    val flickPast = g.move(100f, 40f, 5_001L, punctuation)
                    assertNull(flickPast.popup)
                    assertTrue(flickPast.actions.isEmpty())
                    assertTrue(g.up().actions.isEmpty())
                }
            }
        }
    }

    @Test
    fun spacebarCursorSlidingEmitsVisualStateAndClearsOnRelease() {
        val space = key(KeyboardPage.Letters, Language.Chinese, "space")
        val g = gesture()

        val down = g.down(space, 200f, 100f, 0L)
        assertEquals(HapticKind.Action, down.haptic)
        assertFalse(down.spaceSliding)

        val startSlide = g.move(230f, 100f, 60L, space)
        assertTrue(startSlide.spaceSliding)
        assertEquals(listOf(EngineAct.MoveCursor(1)), startSlide.actions)
        assertEquals(HapticKind.Step, startSlide.haptic)

        val holdSlide = g.move(235f, 100f, 80L, space)
        assertTrue(holdSlide.spaceSliding)
        assertTrue(holdSlide.actions.isEmpty())

        val reverse = g.move(205f, 100f, 110L, space)
        assertTrue(reverse.spaceSliding)
        assertEquals(listOf(EngineAct.MoveCursor(-1)), reverse.actions)

        val up = g.up()
        assertFalse(up.spaceSliding)
        assertTrue(up.actions.isEmpty())
    }

    @Test
    fun roleDifferentiatedHapticsDistinguishLettersActionsAndBackspace() {
        val g = gesture()
        val letter = key(KeyboardPage.Letters, Language.English, "l-a")
        val comma = key(KeyboardPage.Letters, Language.English, "comma")
        val space = key(KeyboardPage.Letters, Language.English, "space")
        val enter = key(KeyboardPage.Letters, Language.English, "enter")
        val shift = key(KeyboardPage.Letters, Language.English, "shift")
        val backspace = key(KeyboardPage.Letters, Language.English, "backspace")

        assertEquals(HapticKind.Down, g.down(letter, 10f, 10f, 0L).haptic)
        g.cancel()
        assertEquals(HapticKind.Down, g.down(comma, 10f, 10f, 0L).haptic)
        g.cancel()
        assertEquals(HapticKind.Action, g.down(space, 10f, 10f, 0L).haptic)
        g.cancel()
        assertEquals(HapticKind.Action, g.down(enter, 10f, 10f, 0L).haptic)
        g.cancel()
        assertEquals(HapticKind.Action, g.down(shift, 10f, 10f, 0L).haptic)
        g.cancel()
        assertNull(g.down(backspace, 10f, 10f, 0L).haptic)
        assertEquals(HapticKind.Delete, g.up().haptic)
    }
}

private data class PunctuationCase(
    val language: Language,
    val id: String,
    val primary: String,
    val quickChars: List<String>,
)

private val letterPunctuationCases = listOf(
    PunctuationCase(Language.Chinese, "comma", "，", listOf("：", "、", "；")),
    PunctuationCase(Language.Chinese, "period", "。", listOf("！", "？", "…")),
    PunctuationCase(Language.English, "comma", ",", listOf(";", ":", "/")),
    PunctuationCase(Language.English, "period", ".", listOf("!", "?", "'")),
)
