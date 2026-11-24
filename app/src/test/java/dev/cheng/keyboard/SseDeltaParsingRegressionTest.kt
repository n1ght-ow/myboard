package dev.cheng.keyboard

import dev.cheng.keyboard.ai.parseSseLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SseDeltaParsingRegressionTest {
    @Test
    fun ignoresContentStringValuesBeforeTheRealDeltaKey() {
        val line = """data: {"type":"content","choices":[{"delta":{"content":"hello"}}]}"""

        assertEquals("hello", parseSseLine(line)?.content)
    }

    @Test
    fun separatesReasoningValueFromTheFollowingContentKey() {
        val line = """data: {"choices":[{"delta":{"reasoning_content":"content","content":"hello"}}]}"""

        val delta = parseSseLine(line)
        assertEquals("content", delta?.reasoning)
        assertEquals("hello", delta?.content)
    }

    @Test
    fun ignoresFieldNamesInsideEscapedStringValues() {
        val line = """data: {"note":"\"content\":\"fake\"","content":"real"}"""

        assertEquals("real", parseSseLine(line)?.content)
    }

    @Test
    fun decodesAllLegalStringEscapes() {
        val line = """data: {"content":"a\b b\f c\n\t\r\"\\\/"}"""

        assertEquals("a\u0008 b\u000C c\n\t\r\"\\/", parseSseLine(line)?.content)
    }

    @Test
    fun decodesUnicodeEscapesAndSurrogatePairs() {
        val line = """data: {"content":"\u4f60\u597d \ud83d\ude00"}"""

        assertEquals("你好 😀", parseSseLine(line)?.content)
    }

    @Test
    fun preservesNullDoneNonDataAndFlatContentBehavior() {
        val nullContent = parseSseLine("""data: {"content":null,"reasoning_content":"why"}""")
        assertNull(nullContent?.content)
        assertEquals("why", nullContent?.reasoning)

        assertTrue(parseSseLine("data:[DONE]")?.done == true)
        assertNull(parseSseLine("event: message"))
        assertEquals("hello", parseSseLine("""data: {"content":"hello"}""")?.content)
    }
}
