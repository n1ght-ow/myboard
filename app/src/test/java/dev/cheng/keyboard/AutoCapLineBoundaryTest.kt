package dev.cheng.keyboard

import dev.cheng.keyboard.engine.shouldAutoCap
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoCapLineBoundaryTest {
    @Test
    fun capsAfterTrailingLineBreaksAndIndentation() {
        assertTrue(shouldAutoCap("hello\n"))
        assertTrue(shouldAutoCap("hello\r"))
        assertTrue(shouldAutoCap("hello\r\n  \t"))
    }

    @Test
    fun capsAfterExplicitUnicodeLineSeparators() {
        assertTrue(shouldAutoCap("hello\u2028"))
        assertTrue(shouldAutoCap("hello\u2029 \t"))
    }

    @Test
    fun horizontalWhitespaceWithoutALineBreakDoesNotCap() {
        assertFalse(shouldAutoCap("hello \t"))
        assertFalse(shouldAutoCap("hello \t "))
    }

    @Test
    fun preservesSentenceEndPunctuationBehavior() {
        assertTrue(shouldAutoCap("hello."))
        assertTrue(shouldAutoCap("hello! \t"))
        assertTrue(shouldAutoCap("hello?"))
        assertTrue(shouldAutoCap("你好。 "))
        assertTrue(shouldAutoCap("你好！"))
        assertTrue(shouldAutoCap("真的？\t"))
    }

    @Test
    fun doesNotCapInTheMiddleOfANonEmptyLine() {
        assertFalse(shouldAutoCap("hello\nworld "))
        assertFalse(shouldAutoCap("\nhello"))
        assertFalse(shouldAutoCap("hello"))
    }

    @Test
    fun preservesStartOfInputBehavior() {
        assertTrue(shouldAutoCap(""))
        assertTrue(shouldAutoCap(" \t"))
    }
}
