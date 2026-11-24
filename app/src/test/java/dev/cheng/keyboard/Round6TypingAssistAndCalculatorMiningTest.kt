package dev.cheng.keyboard

import dev.cheng.keyboard.ime.assist.TypingAssistContext
import dev.cheng.keyboard.ime.assist.TypingAssistKind
import dev.cheng.keyboard.ime.assist.calculateInline
import dev.cheng.keyboard.ime.assist.typingSuggestions
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Round6TypingAssistAndCalculatorMiningTest {
    private val fixedNow = LocalDateTime.of(2026, 11, 24, 9, 5)

    @Test
    fun percentageArithmeticCoversDiscountMarkupAndDirectPercentages() {
        assertEquals("0.5", calculateInline("50%"))
        assertEquals("0", calculateInline("0%"))
        assertEquals("-0.5", calculateInline("-50%"))
        assertEquals("30", calculateInline("200*15%"))
        assertEquals("110", calculateInline("100+10%"))
        assertEquals("80", calculateInline("100-20%"))
        assertEquals("0.75", calculateInline("50%+25%"))
        assertEquals("0.25", calculateInline("50%-25%"))
        assertEquals("12", calculateInline("(100+20)*10%"))
        assertEquals("200", calculateInline("100/50%"))
        assertEquals("99", calculateInline("100-10%+10%"))
    }

    @Test
    fun percentageArithmeticRejectsMalformedAndUnsafeInputs() {
        assertNull(calculateInline("100%%"))
        assertNull(calculateInline("%5"))
        assertNull(calculateInline("100+%"))
        assertNull(calculateInline("100/0%"))
        assertNull(calculateInline("100/(5-5)%"))
        assertNull(calculateInline("1−2"))
        assertNull(calculateInline("1++2"))
        assertNull(calculateInline("1--2"))
        assertNull(calculateInline("42"))
    }

    @Test
    fun fullWidthChineseKeyboardMathTriggersCalculatorSuggestions() {
        val fullWidth = typingSuggestions(
            TypingAssistContext(before = "总计（12＋8）×3＝"),
            fixedNow,
        ).single()
        assertEquals(TypingAssistKind.Calculator, fullWidth.kind)
        assertEquals("60", fullWidth.replacement)
        assertEquals("（12＋8）×3＝".length, fullWidth.replaceBefore)

        val percent = typingSuggestions(
            TypingAssistContext(before = "折后 200×15％＝"),
            fixedNow,
        ).single()
        assertEquals(TypingAssistKind.Calculator, percent.kind)
        assertEquals("30", percent.replacement)
        assertEquals("200×15％＝".length, percent.replaceBefore)

        val discount = typingSuggestions(
            TypingAssistContext(before = "100－20％="),
            fixedNow,
        ).single()
        assertEquals("80", discount.replacement)

        val noSpaceCjk = typingSuggestions(
            TypingAssistContext(before = "折后价100+12.5%="),
            fixedNow,
        ).single()
        assertEquals("112.5", noSpaceCjk.replacement)
        assertEquals("100+12.5%=".length, noSpaceCjk.replaceBefore)

        assertTrue(typingSuggestions(TypingAssistContext(before = "1+2＝="), fixedNow).isEmpty())
        assertTrue(typingSuggestions(TypingAssistContext(before = "1+2=＝"), fixedNow).isEmpty())
        assertTrue(typingSuggestions(TypingAssistContext(before = "1+2=", after = "＝"), fixedNow).isEmpty())
        assertTrue(typingSuggestions(TypingAssistContext(before = "1+2=", after = "＋3"), fixedNow).isEmpty())
        assertTrue(typingSuggestions(TypingAssistContext(before = "1−2="), fixedNow).isEmpty())
        assertTrue(typingSuggestions(TypingAssistContext(before = "v2+3="), fixedNow).isEmpty())
    }

    @Test
    fun expandedDateTimeTriggersWorkWithExactFormattingAndIds() {
        val now = typingSuggestions(TypingAssistContext(before = "/now"), fixedNow).single()
        assertEquals(TypingAssistKind.DateTime, now.kind)
        assertEquals("2026-11-24 09:05", now.replacement)
        assertEquals(4, now.replaceBefore)
        assertTrue(now.id.startsWith("datetime:now:"))

        val ymd = typingSuggestions(TypingAssistContext(before = "/ymd"), fixedNow).single()
        assertEquals("2026年11月24日", ymd.replacement)
        assertEquals(4, ymd.replaceBefore)

        val tomorrow = typingSuggestions(TypingAssistContext(before = "/tomorrow"), fixedNow).single()
        assertEquals("2026-11-25", tomorrow.replacement)
        assertEquals(9, tomorrow.replaceBefore)
        assertTrue(tomorrow.id.startsWith("datetime:tomorrow:"))

        val yesterday = typingSuggestions(TypingAssistContext(before = "/yesterday"), fixedNow).single()
        assertEquals("2026-11-23", yesterday.replacement)
        assertEquals(10, yesterday.replaceBefore)
        assertTrue(yesterday.id.startsWith("datetime:yesterday:"))
    }

    @Test
    fun dateTimeTriggersAcceptAsciiPunctuationBoundariesWhileBlockingUrlAndEmailContexts() {
        assertEquals("2026-11-24", typingSuggestions(TypingAssistContext(before = "(rq"), fixedNow).single().replacement)
        assertEquals("09:05", typingSuggestions(TypingAssistContext(before = ",sj"), fixedNow).single().replacement)
        assertEquals("2026-11-24", typingSuggestions(TypingAssistContext(before = "\"/date"), fixedNow).single().replacement)
        assertEquals("2026-11-24 09:05", typingSuggestions(TypingAssistContext(before = "[/now"), fixedNow).single().replacement)

        assertTrue(typingSuggestions(TypingAssistContext(before = "https://example.com/a(rq"), fixedNow).isEmpty())
        assertTrue(typingSuggestions(TypingAssistContext(before = "https://example.com/(/now"), fixedNow).isEmpty())
        assertTrue(typingSuggestions(TypingAssistContext(before = "alice@example.com/rq"), fixedNow).isEmpty())
        assertTrue(typingSuggestions(TypingAssistContext(before = "alice@rq"), fixedNow).none { it.kind == TypingAssistKind.DateTime })
        assertTrue(typingSuggestions(TypingAssistContext(before = "path/rq"), fixedNow).isEmpty())
    }
}
