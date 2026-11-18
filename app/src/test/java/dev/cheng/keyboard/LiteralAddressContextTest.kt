package dev.cheng.keyboard

import dev.cheng.keyboard.ime.assist.isLiteralAddressContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteralAddressContextTest {
    @Test
    fun manualDomainTypingRemainsLiteralBeforeAndAfterEveryDot() {
        for (address in listOf("alice@qq.com", "alice@company.example", "user+tag@sub.example.cn", "12345@163.com")) {
            val at = address.indexOf('@')
            for (end in at + 1..address.length) {
                assertTrue(address.take(end), isLiteralAddressContext(address.take(end)))
            }
        }
    }

    @Test
    fun proseAndPunctuationBoundariesKeepOnlyTheCurrentAddressLiteral() {
        assertTrue(isLiteralAddressContext("联系alice@"))
        assertTrue(isLiteralAddressContext("邮箱：alice@qq.com"))
        assertTrue(isLiteralAddressContext("(alice@g"))
        for (text in listOf("alice@qq.com ", "alice@qq.com，", "alice@qq.com\n", "alice@qq.com。下一句", "下一句你好")) {
            assertFalse(text, isLiteralAddressContext(text))
        }
    }

    @Test
    fun mentionsMalformedAddressesAndUrlQueriesDoNotEnterEmailMode() {
        for (text in listOf("@", "@alice", "alice@@", "alice@@bob@g", "alice..bob@g", "alice.@g", ".alice@g", "https://user@g", "user/path/alice@g", "a=" + "alice@g")) {
            assertFalse(text, isLiteralAddressContext(text))
        }
    }

    @Test
    fun explicitUrlsStayLiteralThroughPathQueryAndFragment() {
        for (text in listOf("https://", "http://example", "https://example.com:8080", "www.example.", "访问：https://example.c")) {
            assertTrue(text, isLiteralAddressContext(text))
        }
        for ((host, suffix) in listOf(
            "https://example.com" to "/path/a%20b?mail=alice@g#part-2",
            "https://example.com" to "?q=hello+world",
            "www.example.com" to "#Section_2",
        )) {
            for (length in 1..suffix.length) {
                val prefix = host + suffix.take(length)
                assertTrue(prefix, isLiteralAddressContext(prefix))
            }
        }
        for (text in listOf("你好", "company", "rq", "evilwww.example", "https:///path", "https://example.com/path next", "https://example.com/path，next")) {
            assertFalse(text, isLiteralAddressContext(text))
        }
    }

    @Test
    fun unboundedOrNonAsciiDomainsAreConservativelyExcluded() {
        assertFalse(isLiteralAddressContext("a".repeat(65) + "@q"))
        assertFalse(isLiteralAddressContext("alice@" + "a".repeat(254)))
        assertFalse(isLiteralAddressContext("alice@例子"))
        assertFalse(isLiteralAddressContext("alice@😀"))
    }
}
