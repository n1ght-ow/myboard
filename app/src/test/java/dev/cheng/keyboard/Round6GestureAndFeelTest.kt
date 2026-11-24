package dev.cheng.keyboard

import dev.cheng.keyboard.engine.EngineAct
import dev.cheng.keyboard.ime.gesture.HapticKind
import dev.cheng.keyboard.ime.gesture.KeyGesture
import dev.cheng.keyboard.ime.layout.keyboardRows
import dev.cheng.keyboard.model.Choice
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
    fun quickPunctuationFlickUpAndCancelWorkOnChineseAndEnglishCommaPeriod() {
        val zhPeriod = key(KeyboardPage.Letters, Language.Chinese, "period")
        assertEquals("？", zhPeriod.hint)
        assertEquals(listOf("！", "？", "…"), zhPeriod.quickChars)

        val g = gesture()
        assertEquals(HapticKind.Down, g.down(zhPeriod, 100f, 100f, 0L).haptic)
        val flick = g.move(100f, 40f, 60L, zhPeriod)
        assertEquals(HapticKind.Step, flick.haptic)
        assertEquals("！", flick.popup?.upper)
        assertEquals("？", flick.popup?.symbol)
        assertEquals("…", flick.popup?.lower)
        assertEquals(Choice.Symbol, flick.popup?.choice)
        assertEquals(listOf(EngineAct.Direct("？")), g.up().actions)

        // Pulling back cancels flick and commits primary punctuation
        g.down(zhPeriod, 100f, 100f, 0L)
        g.move(100f, 40f, 60L, zhPeriod)
        val pulledBack = g.move(100f, 85f, 90L, zhPeriod)
        assertNull(pulledBack.popup)
        assertEquals(HapticKind.Step, pulledBack.haptic)
        assertEquals(listOf(EngineAct.Direct("。")), g.up().actions)

        val enComma = key(KeyboardPage.Letters, Language.English, "comma")
        assertEquals(":", enComma.hint)
        g.down(enComma, 100f, 100f, 0L)
        g.move(100f, 40f, 60L, enComma)
        assertEquals(listOf(EngineAct.Direct(":")), g.up().actions)
    }

    @Test
    fun quickPunctuationLongPressSupportsLeftCenterRightSelection() {
        val zhComma = key(KeyboardPage.Letters, Language.Chinese, "comma")
        val g = gesture()

        // Left -> ：
        g.down(zhComma, 100f, 100f, 0L)
        assertEquals(350L, g.nextAlarm(10L))
        val armed = g.alarm(350L)
        assertEquals(HapticKind.Arm, armed.haptic)
        assertEquals(Choice.Symbol, armed.popup?.choice)
        val left = g.move(60f, 100f, 380L, zhComma)
        assertEquals(Choice.Upper, left.popup?.choice)
        assertEquals(HapticKind.Step, left.haptic)
        assertEquals(listOf(EngineAct.Direct("：")), g.up().actions)

        // Right -> ；
        g.down(zhComma, 100f, 100f, 0L)
        g.alarm(350L)
        val right = g.move(140f, 100f, 380L, zhComma)
        assertEquals(Choice.Lower, right.popup?.choice)
        assertEquals(listOf(EngineAct.Direct("；")), g.up().actions)

        // Number page comma stays plain without quick popup
        val numComma = key(KeyboardPage.Number, Language.Chinese, "comma")
        assertTrue(numComma.quickChars.isEmpty())
        g.down(numComma, 100f, 100f, 0L)
        assertNull(g.nextAlarm(10L))
        assertEquals(listOf(EngineAct.Direct(",")), g.up().actions)
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
