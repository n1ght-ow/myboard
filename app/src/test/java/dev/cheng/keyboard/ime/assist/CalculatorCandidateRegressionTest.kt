package dev.cheng.keyboard.ime.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalculatorCandidateRegressionTest {
    @Test
    fun acceptsTrailingDotBeforeBinaryOperatorAndReplacesWholeExpression() {
        val cases = listOf(
            "1.+2=" to "3",
            "1.*2=" to "2",
            "1. / 2=" to "0.5",
        )

        for ((before, expected) in cases) {
            val suggestion = typingSuggestions(
                TypingAssistContext(before = before),
            ).single()
            assertEquals(TypingAssistKind.Calculator, suggestion.kind)
            assertEquals(expected, suggestion.replacement)
            assertEquals(before.length, suggestion.replaceBefore)
            assertEquals(before, suggestion.expectedBefore)
        }

        val prefixed = typingSuggestions(
            TypingAssistContext(
                before = "总计 1.+2=",
                after = " 元",
            ),
        ).single()
        assertEquals("3", prefixed.replacement)
        assertEquals("1.+2=".length, prefixed.replaceBefore)
        assertEquals("总计 1.+2=", prefixed.expectedBefore)
        assertEquals(" 元", prefixed.expectedAfter)
    }

    @Test
    fun preservesCalculatorSuppressionRules() {
        val rejected = listOf(
            "42=",
            "1.=",
            "1++2=",
            "1.+=",
            "1.*=",
            "https://example.com/?q=1.+2=",
            "2026.11.17=",
            "138-0013-8000=",
            "1.+\n2=",
            "1." + "+1".repeat(40) + "=",
        )

        for (before in rejected) {
            assertTrue(
                "calculator must reject $before",
                typingSuggestions(
                    TypingAssistContext(before = before, numeric = true),
                ).isEmpty(),
            )
        }

        for (context in listOf(
            TypingAssistContext(before = "1.+2=", password = true),
            TypingAssistContext(before = "1.+2=", noLearn = true),
            TypingAssistContext(before = "1.+2=", hasSelection = true),
        )) {
            assertTrue(typingSuggestions(context).isEmpty())
        }
    }

    @Test
    fun calculatorStopsAtLineBoundaryInsteadOfCrossingIt() {
        val suggestion = typingSuggestions(
            TypingAssistContext(
                before = "100\n1.+2=",
                numeric = true,
            ),
        ).single()

        assertEquals("3", suggestion.replacement)
        assertEquals("1.+2=".length, suggestion.replaceBefore)
        assertEquals("100\n1.+2=", suggestion.expectedBefore)
    }
}
