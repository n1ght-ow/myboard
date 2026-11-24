package dev.cheng.keyboard

import dev.cheng.keyboard.engine.EngineAct
import dev.cheng.keyboard.ime.gesture.BackspaceHint
import dev.cheng.keyboard.ime.gesture.KeyGesture
import dev.cheng.keyboard.model.KeyKind
import dev.cheng.keyboard.model.KeyModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeletionGestureTest {
    private val backspace = KeyModel("backspace", KeyKind.Backspace, "")

    private fun gesture() = KeyGesture(
        slopPx = 16f,
        caseSlopPx = 34f,
        swipePx = 36f,
        verticalFlickPx = 46f,
    )

    @Test
    fun tapIsOneDeleteAndLongPressKeepsTheSameFixedRhythm() {
        val gesture = gesture()
        gesture.down(backspace, 100f, 100f, 0L)
        assertEquals(listOf(EngineAct.Backspace), gesture.up().actions)

        gesture.down(backspace, 100f, 100f, 0L)
        assertTrue(gesture.alarm(399L).actions.isEmpty())
        var now = 400L
        repeat(100) {
            val frame = gesture.alarm(now)
            assertEquals(listOf(EngineAct.Backspace), frame.actions)
            assertEquals(BackspaceHint.Repeat, frame.backspaceHint)
            assertEquals(now + 50L, gesture.nextAlarm(now))
            now += 50L
        }
        assertTrue(gesture.up().actions.isEmpty())
    }

    @Test
    fun earlySwipeCannotBecomeConfirmationFromHoldTimeoutOrReleaseAlone() {
        val gesture = gesture()
        gesture.down(backspace, 100f, 100f, 0L)
        assertEquals(BackspaceHint.None, gesture.move(100f, 10f, 100L, null).backspaceHint)
        val started = gesture.alarm(400L)
        assertEquals(listOf(EngineAct.Backspace), started.actions)
        assertEquals(BackspaceHint.Repeat, started.backspaceHint)

        val releaseMove = gesture.move(100f, 10f, 401L, null, released = true)
        assertEquals(BackspaceHint.Repeat, releaseMove.backspaceHint)
        assertTrue(gesture.up().actions.isEmpty())
    }

    @Test
    fun releaseAfterThresholdWithoutAlarmStillDoesNotConfirmAndDeletesOnce() {
        val gesture = gesture()
        gesture.down(backspace, 100f, 100f, 0L)
        assertEquals(BackspaceHint.None, gesture.move(100f, 10f, 401L, null, released = true).backspaceHint)
        assertEquals(listOf(EngineAct.Backspace), gesture.up().actions)
    }

    @Test
    fun longPressThenSwipeAndReleaseClearsOnce() {
        val gesture = gesture()
        gesture.down(backspace, 100f, 100f, 0L)
        assertEquals(listOf(EngineAct.Backspace), gesture.alarm(400L).actions)

        val armed = gesture.move(100f, 10f, 420L, null)
        assertEquals(BackspaceHint.ClearArmed, armed.backspaceHint)
        assertTrue(armed.actions.isEmpty())
        assertNull(gesture.nextAlarm(420L))
        assertTrue(gesture.alarm(5_000L).actions.isEmpty())

        val releaseMove = gesture.move(100f, 10f, 6_000L, null, released = true)
        assertEquals(BackspaceHint.ClearArmed, releaseMove.backspaceHint)
        assertEquals(listOf(EngineAct.ClearInput), gesture.up().actions)
        assertTrue(gesture.up().actions.isEmpty())
    }

    @Test
    fun swipeAfterLongPressThresholdDoesNotDependOnAlarmRunningFirst() {
        val gesture = gesture()
        gesture.down(backspace, 100f, 100f, 0L)

        val armed = gesture.move(100f, 10f, 401L, null)
        assertEquals(BackspaceHint.ClearArmed, armed.backspaceHint)
        assertNull(gesture.nextAlarm(401L))
        assertEquals(listOf(EngineAct.ClearInput), gesture.up().actions)
    }

    @Test
    fun leavingConfirmationStopsRepeatAndSlidingBackRestoresIt() {
        val gesture = gesture()
        gesture.down(backspace, 100f, 100f, 0L)
        gesture.alarm(400L)
        gesture.move(100f, 10f, 420L, null)

        val left = gesture.move(100f, 70f, 500L, null)
        assertEquals(BackspaceHint.Repeat, left.backspaceHint)
        assertEquals(550L, gesture.nextAlarm(500L))
        assertEquals(listOf(EngineAct.Backspace), gesture.alarm(550L).actions)

        val rearmed = gesture.move(100f, 10f, 560L, null)
        assertEquals(BackspaceHint.ClearArmed, rearmed.backspaceHint)
        assertNull(gesture.nextAlarm(560L))
        assertEquals(listOf(EngineAct.ClearInput), gesture.up().actions)
    }

    @Test
    fun releaseOutsideConfirmationDoesNotClear() {
        val gesture = gesture()
        gesture.down(backspace, 100f, 100f, 0L)
        gesture.alarm(400L)
        gesture.move(100f, 10f, 420L, null)
        gesture.move(100f, 70f, 440L, null)

        val releaseMove = gesture.move(100f, 70f, 460L, null, released = true)
        assertEquals(BackspaceHint.Repeat, releaseMove.backspaceHint)
        assertTrue(gesture.up().actions.isEmpty())
    }

    @Test
    fun smallJitterKeepsRepeatingButClearHorizontalAndDownwardExitStops() {
        val jitter = gesture()
        jitter.down(backspace, 100f, 100f, 0L)
        jitter.alarm(400L)
        assertEquals(BackspaceHint.Repeat, jitter.move(118f, 118f, 420L, null).backspaceHint)
        assertEquals(450L, jitter.nextAlarm(420L))

        val sideways = gesture()
        sideways.down(backspace, 100f, 100f, 0L)
        sideways.alarm(400L)
        assertEquals(BackspaceHint.None, sideways.move(20f, 100f, 420L, null).backspaceHint)
        assertNull(sideways.nextAlarm(420L))
        assertTrue(sideways.alarm(500L).actions.isEmpty())
        assertTrue(sideways.up().actions.isEmpty())

        val downward = gesture()
        downward.down(backspace, 100f, 100f, 0L)
        downward.alarm(400L)
        assertEquals(BackspaceHint.None, downward.move(100f, 160f, 420L, null).backspaceHint)
        assertNull(downward.nextAlarm(420L))
        assertTrue(downward.alarm(500L).actions.isEmpty())
        assertTrue(downward.up().actions.isEmpty())
    }

    @Test
    fun cancelDuringConfirmationNeverClearsOrLeavesAnAlarm() {
        val gesture = gesture()
        gesture.down(backspace, 100f, 100f, 0L)
        gesture.alarm(400L)
        assertEquals(BackspaceHint.ClearArmed, gesture.move(100f, 10f, 420L, null).backspaceHint)

        gesture.cancel()
        assertNull(gesture.nextAlarm(420L))
        assertTrue(gesture.alarm(1_000L).actions.isEmpty())
        assertTrue(gesture.up().actions.isEmpty())
    }

    @Test
    fun backspaceDoesNotRolloverIntoAnotherDeletionTarget() {
        val gesture = gesture()
        val letter = KeyModel("l-a", KeyKind.Letter, "a", hint = "1")
        gesture.down(backspace, 100f, 100f, 0L)
        assertFalse(gesture.canRollover())
        assertTrue(gesture.rollover(letter, 100f, 100f, 100L).isEmpty())
    }

    @Test
    fun cancelledBackspaceYieldsToNewPressWithoutDeletingOrClearing() {
        val gesture = gesture()
        val letter = KeyModel("l-a", KeyKind.Letter, "a", hint = "1")
        gesture.down(backspace, 100f, 100f, 0L)
        gesture.alarm(400L)
        assertEquals(BackspaceHint.ClearArmed, gesture.move(100f, 10f, 420L, null).backspaceHint)
        assertEquals(BackspaceHint.None, gesture.move(20f, 10f, 430L, null).backspaceHint)

        val frames = gesture.rollover(letter, 300f, 100f, 450L)
        assertEquals(2, frames.size)
        assertTrue(frames[0].actions.isEmpty())
        assertEquals("l-a", frames[1].pressedId)

        val released = gesture.up()
        assertEquals(1, released.actions.size)
        val action = released.actions.single() as EngineAct.Letter
        assertEquals('a', action.ch)
    }
}
