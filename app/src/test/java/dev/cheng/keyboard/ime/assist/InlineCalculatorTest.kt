package dev.cheng.keyboard.ime.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InlineCalculatorTest {
    @Test
    fun respectsPrecedenceAssociativityAndParentheses() {
        assertEquals("14", calculateInline("2+3*4"))
        assertEquals("20", calculateInline("(2+3)*4"))
        assertEquals("5", calculateInline("10-3-2"))
        assertEquals("1", calculateInline("8/4/2"))
    }

    @Test
    fun supportsUnicodeOperatorsAndDecimalArithmetic() {
        assertEquals("12", calculateInline("8÷2×3"))
        assertEquals("0.3", calculateInline("0.1+0.2"))
        assertEquals("0.02", calculateInline("0.1*0.2"))
        assertEquals("2", calculateInline("1.2500+0.7500"))
        assertEquals("0.75", calculateInline(" .5 + .25 "))
    }

    @Test
    fun supportsUnarySignsAndNormalizesNegativeZero() {
        assertEquals("-3", calculateInline("-5+2"))
        assertEquals("-10", calculateInline("(+5)*(-2)"))
        assertEquals("-7", calculateInline("2*-3-1"))
        assertEquals("-1", calculateInline("1+-2"))
        assertEquals("-2.5", calculateInline("5/-2"))
        assertEquals("0", calculateInline("1.5-1.5"))
        assertEquals("0", calculateInline("-0.00+0"))
    }

    @Test
    fun roundsRepeatingDecimalsToAStableDisplayScale() {
        assertEquals("0.3333333333", calculateInline("1/3"))
        assertEquals("0.6666666667", calculateInline("2/3"))
        assertEquals("3.3333333333", calculateInline("10/3"))
        assertEquals("0.1666666667", calculateInline("1/6"))
    }

    @Test
    fun returnsPlainDecimalWithoutTrailingZeros() {
        assertEquals("1000", calculateInline("500*2"))
        assertEquals("2.5", calculateInline("10/4"))
        assertEquals("0.0000000001", calculateInline("1/10000000000"))
    }

    @Test
    fun tinyPositiveAndNegativeAreNotAdvertisedAsZero() {
        assertNull(calculateInline("1/1000000000000000"))
        assertNull(calculateInline("-1/1000000000000000"))
        assertEquals("0", calculateInline("0/1000000000000000"))
    }

    @Test
    fun rejectsLoneNumbersAndIncompleteExpressions() {
        assertNull(calculateInline(""))
        assertNull(calculateInline("   "))
        assertNull(calculateInline("42"))
        assertNull(calculateInline("-42"))
        assertNull(calculateInline("(+42)"))
        assertNull(calculateInline("1+"))
        assertNull(calculateInline("*2"))
        assertNull(calculateInline("1+*2"))
        assertNull(calculateInline("1//2"))
    }

    @Test
    fun rejectsMalformedConsecutiveOperatorsAndParentheses() {
        assertNull(calculateInline("1++2"))
        assertNull(calculateInline("1--2"))
        assertNull(calculateInline("1/ /2"))
        assertNull(calculateInline("(1+2"))
        assertNull(calculateInline("1+2)"))
        assertNull(calculateInline("1+(2*)"))
        assertNull(calculateInline("()"))
        assertNull(calculateInline("1 2+3"))
    }

    @Test
    fun rejectsInvalidCharactersExponentsAndNonFiniteSyntax() {
        assertNull(calculateInline("abc+1"))
        assertNull(calculateInline("1e3+2"))
        assertNull(calculateInline("1E-3+2"))
        assertNull(calculateInline("1,000+2"))
        assertNull(calculateInline("1−2"))
        assertNull(calculateInline("NaN+1"))
        assertNull(calculateInline("Infinity+1"))
    }

    @Test
    fun rejectsDivisionByZero() {
        assertNull(calculateInline("1/0"))
        assertNull(calculateInline("1/0.00"))
        assertNull(calculateInline("1/(2-2)"))
        assertNull(calculateInline("1/(-0)"))
    }

    @Test
    fun enforcesLengthNestingAndLiteralDigitBounds() {
        val maximumNesting = "(".repeat(12) + "1+2" + ")".repeat(12)
        val excessiveNesting = "(".repeat(13) + "1+2" + ")".repeat(13)

        assertEquals("3", calculateInline(maximumNesting))
        assertNull(calculateInline(excessiveNesting))
        assertNull(calculateInline("99999999999999999+0"))
        assertEquals("2", calculateInline("0000000000000001+1"))

        val overlong = "1+" + "0".repeat(79)
        assertNull(calculateInline(overlong))
    }

    @Test
    fun rejectsOversizedResultsButAllowsBoundedLargeResults() {
        assertNull(calculateInline("9999999999999999*9999999999999999"))
        assertEquals("10000000000000000", calculateInline("9999999999999999+1"))
    }
}
