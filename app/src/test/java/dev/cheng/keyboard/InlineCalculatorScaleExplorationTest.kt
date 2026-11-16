package dev.cheng.keyboard

import dev.cheng.keyboard.ime.assist.calculateInline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InlineCalculatorScaleExplorationTest {
    @Test
    fun smallIntermediateValuesCanRecoverToAnExactlyRepresentableResult() {
        assertEquals("1", calculateInline("1/9999999999999999*9999999999999999"))
        assertEquals("1", calculateInline("1/9999999999999999/9999999999999999*9999999999999999*9999999999999999"))
    }

    @Test
    fun unrepresentableFinalValuesStayHiddenAndResourceBoundsRemain() {
        assertNull(calculateInline("1/9999999999999999/9999999999999999"))
        assertNull(calculateInline("-1/9999999999999999/9999999999999999"))
        assertNull(calculateInline("9999999999999999*9999999999999999"))
        assertNull(calculateInline("1/" + "9".repeat(100)))
    }
}
