package dev.cheng.keyboard

import dev.cheng.keyboard.engine.EngineAct
import dev.cheng.keyboard.ime.gesture.HapticKind
import dev.cheng.keyboard.ime.gesture.KeyGesture
import dev.cheng.keyboard.ime.layout.HitContext
import dev.cheng.keyboard.ime.layout.hitKey
import dev.cheng.keyboard.ime.layout.placeKeys
import dev.cheng.keyboard.ime.layout.touchSampleOf
import dev.cheng.keyboard.model.Choice
import dev.cheng.keyboard.model.KeyKind
import dev.cheng.keyboard.model.KeyModel
import dev.cheng.keyboard.model.KeyboardPage
import dev.cheng.keyboard.model.Language
import dev.cheng.keyboard.model.TouchBias
import dev.cheng.keyboard.model.TouchSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureStabilityTest {
    private val fKey = KeyModel("l-f", KeyKind.Letter, "f", hint = "#")
    private val gKey = KeyModel("l-g", KeyKind.Letter, "g", hint = "%")
    private val placement = placeKeys(
        page = KeyboardPage.Letters,
        language = Language.English,
        widthPx = 1080f,
        keyHeightPx = 144f,
        gapX = 18f,
        gapY = 30f,
        sidePx = 12f,
    )

    private fun gesture() = KeyGesture(
        slopPx = 16f,
        caseSlopPx = 34f,
        swipePx = 36f,
        verticalFlickPx = 46f,
    )

    private fun hitContext(bias: TouchBias) = HitContext(
        bias = bias,
        boardWidthPx = placement.width,
        refKeyWidthPx = placement.keys.first { it.model.kind == KeyKind.Letter }.w,
        refKeyHeightPx = 144f,
    )

    @Test
    fun deliberateRetargetPreservesOriginalTimerAndReplacesSample() {
        val gesture = gesture()
        val original = TouchSample(primary = 'f', residualX = 0.05f, residualY = 0.08f)
        val moved = TouchSample(primary = 'g', residualX = 0.10f, residualY = 0.12f)

        assertEquals(HapticKind.Down, gesture.down(fKey, 100f, 100f, 0L, touchSample = original).haptic)

        val retarget = gesture.move(140f, 100f, 90L, gKey, moved.neighborText, moved)
        assertEquals("l-g", retarget.pressedId)
        assertEquals("l-g", retarget.tapPreview?.keyId)
        assertEquals(HapticKind.Step, retarget.haptic)
        assertTrue(retarget.actions.isEmpty())

        val sameTarget = gesture.move(142f, 101f, 120L, gKey, moved.neighborText, moved)
        assertNull(sameTarget.haptic)
        assertNull(sameTarget.tapPreview)

        assertEquals(350L, gesture.nextAlarm(120L))
        assertNull(gesture.alarm(349L).haptic)
        val armed = gesture.alarm(350L)
        assertEquals(HapticKind.Arm, armed.haptic)
        assertEquals("l-g", armed.popup?.keyId)

        val action = gesture.up().actions.single() as EngineAct.Letter
        assertEquals('g', action.ch)
        assertEquals(moved, action.sample)
    }

    @Test
    fun retargetRequiresTargetCoreAndSupportsReversal() {
        val gesture = gesture()
        gesture.down(fKey, 100f, 100f, 0L)

        val unstableX = TouchSample(primary = 'g', residualX = 0.281f, residualY = 0.10f)
        val outsideX = gesture.move(140f, 100f, 90L, gKey, "", unstableX)
        assertEquals("l-f", outsideX.pressedId)
        assertNull(outsideX.haptic)

        val unstableY = TouchSample(primary = 'g', residualX = 0.10f, residualY = 0.301f)
        val outsideY = gesture.move(140f, 100f, 100L, gKey, "", unstableY)
        assertEquals("l-f", outsideY.pressedId)
        assertNull(outsideY.haptic)

        val inside = TouchSample(primary = 'g', residualX = 0.28f, residualY = 0.30f)
        val entered = gesture.move(140f, 100f, 110L, gKey, "", inside)
        assertEquals("l-g", entered.pressedId)
        assertEquals(HapticKind.Step, entered.haptic)

        val backSample = TouchSample(primary = 'f', residualX = -0.27f, residualY = 0.29f)
        val reversed = gesture.move(100f, 100f, 120L, fKey, "", backSample)
        assertEquals("l-f", reversed.pressedId)
        assertEquals("l-f", reversed.tapPreview?.keyId)
        assertEquals(HapticKind.Step, reversed.haptic)

        val action = gesture.up().actions.single() as EngineAct.Letter
        assertEquals('f', action.ch)
        assertEquals(backSample, action.sample)
    }

    @Test
    fun nullSampleLegacySlideStillRetargets() {
        val gesture = gesture()
        gesture.down(fKey, 100f, 100f, 0L)

        val retarget = gesture.move(140f, 100f, 90L, gKey)
        assertEquals("l-g", retarget.pressedId)
        assertEquals(HapticKind.Step, retarget.haptic)

        val action = gesture.up().actions.single() as EngineAct.Letter
        assertEquals('g', action.ch)
        assertNull(action.sample)
    }

    @Test
    fun retargetKeepsFlickAngleRelativeToOriginalDown() {
        val gesture = gesture()
        val moved = TouchSample(primary = 'g', residualX = 0.10f, residualY = 0.10f)
        gesture.down(fKey, 100f, 100f, 0L)

        val retarget = gesture.move(140f, 100f, 90L, gKey, "", moved)
        assertEquals("l-g", retarget.pressedId)

        val shallowFromOriginal = gesture.move(140f, 50f, 100L, null)
        assertNull(shallowFromOriginal.popup)

        val steepFromOriginal = gesture.move(100f, 50f, 110L, null)
        assertEquals("l-g", steepFromOriginal.popup?.keyId)

        val action = gesture.up().actions.single() as EngineAct.Letter
        assertEquals('g', action.ch)
        assertEquals(Choice.Symbol, action.force)
    }

    @Test
    fun positiveYCalibrationUsesHitResidualForRetarget() {
        val context = hitContext(TouchBias(dyRatio = 0.15f))
        val start = placement.keys.first { it.model.id == "l-s" }
        val target = placement.keys.first { it.model.id == "l-d" }
        val rawX = target.x + target.w * 0.5f
        val rawY = target.y + target.h * 0.81f
        val hit = requireNotNull(hitKey(placement.keys, rawX, rawY, context))
        assertEquals("l-d", hit.model.id)
        val sample = requireNotNull(touchSampleOf(placement.keys, rawX, rawY, hit.model, context))
        assertEquals('d', sample.primary)
        assertEquals(0.31f, sample.residualY, 1e-4f)
        assertEquals(0.16f, sample.hitResidualY, 1e-4f)

        val gesture = gesture()
        gesture.down(start.model, start.x + start.w * 0.5f, start.y + start.h * 0.5f, 0L)
        val retarget = gesture.move(rawX, rawY, 90L, hit.model, sample.neighborText, sample)
        assertEquals("l-d", retarget.pressedId)
        assertEquals(HapticKind.Step, retarget.haptic)
    }

    @Test
    fun negativeXCalibrationRejectsRetargetOutsideHitCore() {
        val context = hitContext(TouchBias(leftDxRatio = -0.06f, rightDxRatio = -0.06f))
        val start = placement.keys.first { it.model.id == "l-s" }
        val target = placement.keys.first { it.model.id == "l-d" }
        val rawX = target.x + target.w * 0.77f
        val rawY = target.y + target.h * 0.5f
        val hit = requireNotNull(hitKey(placement.keys, rawX, rawY, context))
        assertEquals("l-d", hit.model.id)
        val sample = requireNotNull(touchSampleOf(placement.keys, rawX, rawY, hit.model, context))
        assertEquals('d', sample.primary)
        assertEquals(0.27f, sample.residualX, 1e-4f)
        assertEquals(0.33f, sample.hitResidualX, 1e-4f)

        val gesture = gesture()
        gesture.down(start.model, start.x + start.w * 0.5f, start.y + start.h * 0.5f, 0L)
        val blocked = gesture.move(rawX, rawY, 90L, hit.model, sample.neighborText, sample)
        assertEquals("l-s", blocked.pressedId)
        assertNull(blocked.haptic)
    }

    @Test
    fun cancelledGestureCannotRolloverOrArm() {
        val gesture = gesture()
        gesture.down(fKey, 100f, 100f, 0L)
        val cancelled = gesture.move(200f, 200f, 90L, null)
        assertEquals("l-f", cancelled.pressedId)

        assertFalse(gesture.canRollover())
        assertTrue(gesture.rollover(gKey, 140f, 100f, 120L).isEmpty())
        assertNull(gesture.alarm(350L).haptic)

        val released = gesture.up()
        assertTrue(released.actions.isEmpty())
    }
}
