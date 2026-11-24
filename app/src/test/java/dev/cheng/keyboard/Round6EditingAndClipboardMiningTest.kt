package dev.cheng.keyboard

import android.view.inputmethod.ExtractedText
import android.view.inputmethod.InputConnection
import dev.cheng.keyboard.data.MAX_PINNED_CLIPS
import dev.cheng.keyboard.data.clipboardDisplayEntries
import dev.cheng.keyboard.data.removeClip
import dev.cheng.keyboard.data.togglePinnedClip
import dev.cheng.keyboard.engine.Cmd
import dev.cheng.keyboard.engine.applyAskDraft
import dev.cheng.keyboard.engine.dropLastGrapheme
import dev.cheng.keyboard.engine.isPairedSymbol
import dev.cheng.keyboard.engine.shouldAutoCap
import dev.cheng.keyboard.engine.wrapSelectedWithPair
import dev.cheng.keyboard.ime.ClearInputResult
import dev.cheng.keyboard.ime.EditorBridge
import dev.cheng.keyboard.ime.EditorTextSpan
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Round6EditingAndClipboardMiningTest {
    @Test
    fun autoCapDetectsSentenceEndingsInsideClosingQuotesAndBrackets() {
        assertTrue(shouldAutoCap("He said \"Hi.\" "))
        assertTrue(shouldAutoCap("He said 'Hi!' "))
        assertTrue(shouldAutoCap("(Done.) "))
        assertTrue(shouldAutoCap("“好了。” "))
        assertTrue(shouldAutoCap("（完成！）\t"))
        assertFalse(shouldAutoCap("He said \"Hi.\""))
        assertFalse(shouldAutoCap("foo(bar) "))
        assertFalse(shouldAutoCap("just \"quotes\" "))
        assertFalse(shouldAutoCap("a.b "))
    }

    @Test
    fun dropLastGraphemePreservesComplexEmojiAndCombiningClusters() {
        assertEquals("", dropLastGrapheme(""))
        assertEquals("a", dropLastGrapheme("ab"))
        assertEquals("hi ", dropLastGrapheme("hi 😀"))
        assertEquals("hi ", dropLastGrapheme("hi 👨‍👩‍👧‍👦"))
        assertEquals("go ", dropLastGrapheme("go 🇨🇳"))
        assertEquals("ok ", dropLastGrapheme("ok 👍🏽"))
        assertEquals("a", dropLastGrapheme("ae\u0301"))

        val longPrefix = "x".repeat(300)
        assertEquals(longPrefix, dropLastGrapheme(longPrefix + "👩‍💻"))
        assertEquals("hi ", applyAskDraft("hi 👨‍👩‍👧‍👦", listOf(Cmd.DeleteOne)))
    }

    @Test
    fun pairedSymbolHelperWrapsSelectionSafelyAndRespectsBounds() {
        assertTrue(isPairedSymbol("（）"))
        assertTrue(isPairedSymbol("“”"))
        assertTrue(isPairedSymbol("()"))
        assertFalse(isPairedSymbol("("))
        assertFalse(isPairedSymbol("ab"))

        assertEquals("（你好）", wrapSelectedWithPair("你好", "（）"))
        assertEquals("“hello”", wrapSelectedWithPair("hello", "“”"))
        assertEquals("(x)", wrapSelectedWithPair("x", "()"))
        assertNull(wrapSelectedWithPair("", "()"))
        assertNull(wrapSelectedWithPair("hello", "ab"))
        assertNull(wrapSelectedWithPair("a".repeat(2001), "()"))
        assertNull(wrapSelectedWithPair("\uD83D", "()"))
    }

    @Test
    fun editorBridgeWrapsSelectedTextWhenSmartCommitEnabledAndSkipsWhenDisabled() {
        val smartEditor = SelectionFakeEditor("hello world", start = 6, end = 11)
        val smartBridge = EditorBridge { smartEditor.connection }
        smartBridge.apply(listOf(Cmd.Commit("（）")), smartCommit = true)
        assertEquals("hello （world）", smartEditor.text)
        assertEquals(13 to 13, smartEditor.start to smartEditor.end)

        val privateEditor = SelectionFakeEditor("hello world", start = 6, end = 11)
        val privateBridge = EditorBridge { privateEditor.connection }
        privateBridge.apply(listOf(Cmd.Commit("（）")), smartCommit = false)
        assertEquals("hello （）", privateEditor.text)
    }

    @Test
    fun clearInputWithOutcomeAndRestoreRoundTripSafely() {
        val editor = SelectionFakeEditor("第一行\n第二行", start = 4, end = 6)
        val bridge = EditorBridge { editor.connection }

        val outcome = bridge.clearInputWithOutcome(privateField = false)
        assertEquals(ClearInputResult.Applied, outcome.result)
        assertEquals("第一行\n第二行", outcome.clearedText)
        assertEquals(EditorTextSpan(4, 6), outcome.clearedSelection)
        assertEquals("", editor.text)

        assertTrue(bridge.restoreClearedInput(outcome.clearedText!!, outcome.clearedSelection!!))
        assertEquals("第一行\n第二行", editor.text)
        assertEquals(4 to 6, editor.start to editor.end)

        // Refuses to restore if the editor already contains text
        assertFalse(bridge.restoreClearedInput("other", EditorTextSpan(5, 5)))
        assertEquals("第一行\n第二行", editor.text)
    }

    @Test
    fun clearInputWithOutcomeNeverRetainsTextInPrivateFields() {
        val editor = SelectionFakeEditor("top-secret", start = 10, end = 10)
        val bridge = EditorBridge { editor.connection }

        val outcome = bridge.clearInputWithOutcome(privateField = true)
        assertEquals(ClearInputResult.Applied, outcome.result)
        assertNull(outcome.clearedText)
        assertNull(outcome.clearedSelection)
        assertEquals("", editor.text)
    }

    @Test
    fun clipboardPinRemoveAndDisplayEntriesPreserveOrderAndFullCurrentClip() {
        val pinned1 = togglePinnedClip("重要地址", emptyList())
        assertEquals(listOf("重要地址"), pinned1)

        val pinned2 = togglePinnedClip("常用邮箱", pinned1)
        assertEquals(listOf("常用邮箱", "重要地址"), pinned2)

        // Toggling again unpins it
        assertEquals(listOf("重要地址"), togglePinnedClip("常用邮箱", pinned2))

        // Display puts pinned first and deduplicates against recent
        val entries = clipboardDisplayEntries(
            current = "最新内容",
            recent = listOf("最新内容", "重要地址", "旧内容"),
            pinned = pinned1,
            limit = 12,
        )
        assertEquals(
            listOf("重要地址" to true, "最新内容" to false, "旧内容" to false),
            entries.map { it.text to it.pinned },
        )

        // Full current clip is preserved when its truncated copy is pinned
        val huge = "a".repeat(3000)
        val pinnedHuge = togglePinnedClip(huge, emptyList())
        val hugeEntries = clipboardDisplayEntries(
            current = huge,
            recent = listOf(pinnedHuge.single()),
            pinned = pinnedHuge,
            limit = 12,
        )
        assertEquals(1, hugeEntries.size)
        assertTrue(hugeEntries.single().pinned)
        assertEquals(huge, hugeEntries.single().text)

        // Remove matches both full and truncated variants
        assertTrue(removeClip(huge, pinnedHuge).isEmpty())

        // Respects MAX_PINNED_CLIPS bound
        var bounded = emptyList<String>()
        for (i in 1..(MAX_PINNED_CLIPS + 4)) {
            bounded = togglePinnedClip("clip-$i", bounded, MAX_PINNED_CLIPS)
        }
        assertEquals(MAX_PINNED_CLIPS, bounded.size)
        assertEquals("clip-${MAX_PINNED_CLIPS + 4}", bounded.first())
    }

    private class SelectionFakeEditor(initial: String, var start: Int, var end: Int) {
        private val buffer = StringBuilder(initial)
        val text: String get() = buffer.toString()

        val connection: InputConnection = Proxy.newProxyInstance(
            InputConnection::class.java.classLoader,
            arrayOf(InputConnection::class.java),
        ) { _, method, args ->
            when (method.name) {
                "beginBatchEdit", "endBatchEdit", "finishComposingText" -> true
                "getSelectedText" -> {
                    val lo = minOf(start, end).coerceIn(0, buffer.length)
                    val hi = maxOf(start, end).coerceIn(0, buffer.length)
                    if (lo == hi) null else buffer.substring(lo, hi)
                }
                "getTextBeforeCursor" -> {
                    val count = (args[0] as Int).coerceAtLeast(0)
                    val lo = minOf(start, end).coerceIn(0, buffer.length)
                    buffer.substring((lo - count).coerceAtLeast(0), lo)
                }
                "getTextAfterCursor" -> {
                    val count = (args[0] as Int).coerceAtLeast(0)
                    val hi = maxOf(start, end).coerceIn(0, buffer.length)
                    buffer.substring(hi, (hi + count).coerceAtMost(buffer.length))
                }
                "getExtractedText" -> ExtractedText().apply {
                    text = buffer.toString()
                    startOffset = 0
                    selectionStart = start
                    selectionEnd = end
                    partialStartOffset = -1
                }
                "setSelection" -> {
                    start = (args[0] as Int).coerceIn(0, buffer.length)
                    end = (args[1] as Int).coerceIn(0, buffer.length)
                    true
                }
                "performContextMenuAction" -> {
                    if (args[0] as Int == android.R.id.selectAll) {
                        start = 0
                        end = buffer.length
                        true
                    } else {
                        false
                    }
                }
                "commitText" -> {
                    val committed = args[0]?.toString().orEmpty()
                    val lo = minOf(start, end).coerceIn(0, buffer.length)
                    val hi = maxOf(start, end).coerceIn(0, buffer.length)
                    buffer.replace(lo, hi, committed)
                    val pos = lo + committed.length
                    start = pos
                    end = pos
                    true
                }
                else -> false
            }
        } as InputConnection
    }
}
