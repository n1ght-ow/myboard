package dev.cheng.keyboard

import android.view.inputmethod.ExtractedText
import android.view.inputmethod.InputConnection
import dev.cheng.keyboard.ime.EditorBridge
import dev.cheng.keyboard.ime.EditorTextSpan
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Test

class EditorRound4DetachSafetyTest {
    @Test
    fun detachDoesNotInsertCandidateAtTapWhenSelectionIsRefused() {
        val input = FakeInput("ni tail", cursor = 7).apply {
            rejectSelection = true
        }

        EditorBridge { input.connection }.detachComposition(
            composition = EditorTextSpan(0, 2),
            selection = EditorTextSpan(7, 7),
            composedText = "ni",
            candidate = "你",
        )

        assertEquals("ni tail", input.text)
        assertEquals(7, input.start)
        assertEquals(7, input.end)
        assertEquals(0, input.commitCalls)
        assertEquals(1, input.finishCalls)
    }

    @Test
    fun detachKeepsTapCaretWhenReplacementCommitIsRefused() {
        val input = FakeInput("ni tail", cursor = 7).apply {
            rejectCommit = true
        }

        EditorBridge { input.connection }.detachComposition(
            composition = EditorTextSpan(0, 2),
            selection = EditorTextSpan(7, 7),
            composedText = "ni",
            candidate = "你",
        )

        assertEquals("ni tail", input.text)
        assertEquals(7, input.start)
        assertEquals(7, input.end)
        assertEquals(1, input.commitCalls)
        assertEquals(1, input.finishCalls)
    }

    @Test
    fun detachFallsBackWhenDocumentReadThrows() {
        val input = FakeInput("ni tail", cursor = 7).apply {
            throwExtracted = true
        }

        EditorBridge { input.connection }.detachComposition(
            composition = EditorTextSpan(0, 2),
            selection = EditorTextSpan(7, 7),
            composedText = "ni",
            candidate = "你",
        )

        assertEquals("ni tail", input.text)
        assertEquals(7, input.start)
        assertEquals(7, input.end)
        assertEquals(0, input.commitCalls)
        assertEquals(1, input.finishCalls)
    }

    @Test
    fun acceptedDetachReplacesCompositionAndRemapsTapCaret() {
        val input = FakeInput("ni tail", cursor = 7)

        EditorBridge { input.connection }.detachComposition(
            composition = EditorTextSpan(0, 2),
            selection = EditorTextSpan(7, 7),
            composedText = "ni",
            candidate = "你",
        )

        assertEquals("你 tail", input.text)
        assertEquals(6, input.start)
        assertEquals(6, input.end)
        assertEquals(1, input.commitCalls)
        assertEquals(0, input.finishCalls)
    }

    private class FakeInput(
        var text: String,
        cursor: Int,
    ) {
        var start = cursor
        var end = cursor
        var rejectSelection = false
        var rejectCommit = false
        var throwExtracted = false
        var commitCalls = 0
        var finishCalls = 0

        val connection: InputConnection = Proxy.newProxyInstance(
            InputConnection::class.java.classLoader,
            arrayOf(InputConnection::class.java),
            InvocationHandler(::invoke),
        ) as InputConnection

        private fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? {
            return when (method.name) {
                "getExtractedText" -> {
                    if (throwExtracted) throw IllegalStateException("synthetic extraction failure")
                    ExtractedText().apply {
                        text = this@FakeInput.text
                        startOffset = 0
                        partialStartOffset = -1
                        partialEndOffset = -1
                        selectionStart = this@FakeInput.start
                        selectionEnd = this@FakeInput.end
                    }
                }
                "getSelectedText" -> selectedText()
                "getTextBeforeCursor" -> text.substring(0, minOf(start, end))
                "getTextAfterCursor" -> text.substring(maxOf(start, end))
                "finishComposingText" -> {
                    finishCalls++
                    true
                }
                "setSelection" -> {
                    if (rejectSelection) return false
                    start = args!![0] as Int
                    end = args[1] as Int
                    true
                }
                "commitText" -> {
                    commitCalls++
                    if (rejectCommit) return false
                    val from = minOf(start, end)
                    val to = maxOf(start, end)
                    val replacement = args!![0].toString()
                    text = text.substring(0, from) + replacement + text.substring(to)
                    start = from + replacement.length
                    end = start
                    true
                }
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> true
                    Integer.TYPE -> 0
                    else -> null
                }
            }
        }

        private fun selectedText(): String? {
            val from = minOf(start, end)
            val to = maxOf(start, end)
            return if (from == to) null else text.substring(from, to)
        }
    }
}
