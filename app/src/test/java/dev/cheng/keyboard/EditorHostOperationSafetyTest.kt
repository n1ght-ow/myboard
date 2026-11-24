package dev.cheng.keyboard

import android.view.inputmethod.ExtractedText
import android.view.inputmethod.InputConnection
import dev.cheng.keyboard.engine.Cmd
import dev.cheng.keyboard.ime.ClearInputResult
import dev.cheng.keyboard.ime.EditorBridge
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorHostOperationSafetyTest {
    @Test
    fun acknowledgedButPartialFullSelectionNeverDeletesThePrefix() {
        val input = HostInput("alpha beta", cursor = 5).apply {
            partialFullSelectionEnd = 5
        }

        val result = EditorBridge { input.connection }.clearInput()

        assertEquals(ClearInputResult.Unchanged, result)
        assertEquals("alpha beta", input.text)
        assertEquals(5, input.start)
        assertEquals(5, input.end)
        assertEquals(0, input.commitCalls)
    }

    @Test
    fun composeStyleBufferedSelectionStillClearsWithoutInBatchReads() {
        val input = HostInput("alpha beta", cursor = 5).apply {
            bufferEditsDuringBatch = true
        }

        val result = EditorBridge { input.connection }.clearInput()

        assertEquals(ClearInputResult.Applied, result)
        assertEquals("", input.text)
        assertEquals(1, input.commitCalls)
        assertTrue("selection:0..10" in input.edits)
        assertFalse(input.edits.any { it.startsWith("begin") || it == "end" })
    }

    @Test
    fun refusedBatchStartDoesNotEmitAnUnbalancedEnd() {
        val input = HostInput("", cursor = 0).apply {
            rejectBatchStart = true
        }

        EditorBridge { input.connection }.apply(
            listOf(Cmd.Commit("有"), Cmd.Compose("yu")),
            smartCommit = false,
        )

        assertEquals(listOf("begin:false", "commit:有", "compose:yu"), input.edits)
        assertFalse("end" in input.edits)
    }

    private class HostInput(
        var text: String,
        cursor: Int,
    ) {
        var start = cursor
        var end = cursor
        var partialFullSelectionEnd: Int? = null
        var bufferEditsDuringBatch = false
        var rejectBatchStart = false
        var commitCalls = 0
        var batchDepth = 0
        var pendingSelection: Pair<Int, Int>? = null
        val edits = mutableListOf<String>()

        val connection: InputConnection = Proxy.newProxyInstance(
            InputConnection::class.java.classLoader,
            arrayOf(InputConnection::class.java),
        ) { _, method, args ->
            when (method.name) {
                "beginBatchEdit" -> {
                    edits += if (rejectBatchStart) "begin:false" else "begin:true"
                    if (!rejectBatchStart) batchDepth++
                    !rejectBatchStart
                }
                "endBatchEdit" -> {
                    edits += "end"
                    if (batchDepth == 0) return@newProxyInstance false
                    batchDepth--
                    pendingSelection?.let { (pendingStart, pendingEnd) ->
                        start = pendingStart
                        end = pendingEnd
                        pendingSelection = null
                    }
                    true
                }
                "getExtractedText" -> ExtractedText().apply {
                    text = this@HostInput.text
                    startOffset = 0
                    partialStartOffset = -1
                    partialEndOffset = -1
                    selectionStart = this@HostInput.start
                    selectionEnd = this@HostInput.end
                }
                "getSelectedText" -> selectedText()
                "getTextBeforeCursor" -> {
                    val count = (args!![0] as Int).coerceAtLeast(0)
                    text.take(minOf(start, end)).takeLast(count)
                }
                "getTextAfterCursor" -> {
                    val count = (args!![0] as Int).coerceAtLeast(0)
                    text.drop(maxOf(start, end)).take(count)
                }
                "setSelection" -> {
                    val requestedStart = args!![0] as Int
                    val requestedEnd = args[1] as Int
                    edits += "selection:$requestedStart..$requestedEnd"
                    val actualEnd = partialFullSelectionEnd
                        ?.takeIf { requestedStart == 0 && requestedEnd == text.length }
                        ?: requestedEnd
                    if (bufferEditsDuringBatch && batchDepth > 0) {
                        pendingSelection = requestedStart to actualEnd
                    } else {
                        start = requestedStart
                        end = actualEnd
                    }
                    true
                }
                "commitText" -> {
                    commitCalls++
                    val replacement = args!![0].toString()
                    edits += "commit:$replacement"
                    replaceSelection(replacement)
                    true
                }
                "setComposingText" -> {
                    edits += "compose:${args!![0]}"
                    true
                }
                else -> error("Unexpected editor call: ${method.name}")
            }
        } as InputConnection

        private fun selectedText(): String? {
            val from = minOf(start, end)
            val to = maxOf(start, end)
            return if (from == to) null else text.substring(from, to)
        }

        private fun replaceSelection(replacement: String) {
            val from = minOf(start, end)
            val to = maxOf(start, end)
            text = text.substring(0, from) + replacement + text.substring(to)
            start = from + replacement.length
            end = start
        }
    }
}
