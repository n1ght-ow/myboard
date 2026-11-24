package dev.cheng.keyboard

import android.view.inputmethod.ExtractedText
import android.view.inputmethod.InputConnection
import dev.cheng.keyboard.engine.Cmd
import dev.cheng.keyboard.engine.EngineAct
import dev.cheng.keyboard.engine.EngineState
import dev.cheng.keyboard.engine.InputEngine
import dev.cheng.keyboard.engine.LatinDecoder
import dev.cheng.keyboard.engine.applyAskDraft
import dev.cheng.keyboard.ime.ClearInputResult
import dev.cheng.keyboard.ime.EditorBridge
import dev.cheng.keyboard.model.Candidate
import dev.cheng.keyboard.model.EnterKind
import dev.cheng.keyboard.model.KeyboardPage
import dev.cheng.keyboard.model.Language
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class EditorClearInputTest {
    @Test
    fun partialPickPublishesHeadCommitAndTailCompositionInOneBatch() {
        val edits = mutableListOf<String>()
        val input = Proxy.newProxyInstance(
            InputConnection::class.java.classLoader,
            arrayOf(InputConnection::class.java),
        ) { _, method, args ->
            edits += when (method.name) {
                "commitText", "setComposingText" -> "${method.name}:${args[0]}"
                else -> method.name
            }
            true
        } as InputConnection

        EditorBridge { input }.apply(listOf(Cmd.Commit("有"), Cmd.Compose("yu")), smartCommit = false)

        assertEquals(listOf("beginBatchEdit", "commitText:有", "setComposingText:yu", "endBatchEdit"), edits)
    }

    @Test
    fun clearRemovesTheWholeMultilineFieldWithAnExistingSelectionAndComposition() {
        val input = FakeInput("前👩‍💻\nni后", start = 8, end = 6)
        val bridge = EditorBridge { input.connection }
        bridge.apply(listOf(Cmd.Compose("ni")))

        assertEquals(ClearInputResult.Applied, bridge.clearInput())

        assertEquals("", input.text)
        assertEquals(listOf("finish", "selection:0..10", "clear"), input.edits)
    }

    @Test
    fun acknowledgedButPartialSelectionNeverDeletesAndRestoresOriginalSelection() {
        val input = FakeInput("before and after", start = 7, end = 3).apply {
            onlySelectPrefix = true
            extractedLength = 9
        }

        assertEquals(ClearInputResult.Unchanged, EditorBridge { input.connection }.clearInput())

        assertEquals("before and after", input.text)
        assertEquals(7 to 3, input.start to input.end)
        assertEquals(listOf("selectAll", "selection:7..3"), input.edits)
    }

    @Test
    fun rejectedCommitLeavesTextIntactButReportsFinishedComposition() {
        val input = FakeInput("前ni后", start = 3, end = 3).apply { rejectCommit = true }
        val bridge = EditorBridge { input.connection }
        bridge.apply(listOf(Cmd.Compose("ni")))

        assertEquals(ClearInputResult.CompositionFinalizedWithoutClear, bridge.clearInput())
        bridge.apply(listOf(Cmd.ClearCompose))

        assertEquals("前ni后", input.text)
        assertEquals(3 to 3, input.start to input.end)
        assertEquals(listOf("finish", "selection:0..4", "clear", "selection:3..3"), input.edits)
    }

    @Test
    fun unreadableBoundaryOrDisconnectedEditorNeverFallsBackToBlindDeletion() {
        val input = FakeInput("secret", start = 3, end = 3).apply { hideBoundaries = true }

        assertEquals(ClearInputResult.Unchanged, EditorBridge { input.connection }.clearInput(privateField = true))
        assertEquals(ClearInputResult.Unchanged, EditorBridge { null }.clearInput())

        assertEquals("secret", input.text)
        assertEquals(0, input.extractionCalls)
        assertEquals(listOf("selectAll"), input.edits)
    }

    @Test
    fun clearResetsInputAndDraftWithoutCommittingPinyinOrLearning() {
        val start = EngineState(
            language = Language.Chinese,
            page = KeyboardPage.Symbols,
            raw = "yu",
            preedit = "yu",
            candidates = listOf(Candidate("余", "yu", 100)),
            partialText = "有",
            partialSyllables = "you",
            lastChineseContext = "前文",
            lastEnglishWord = "prior",
            autoSpace = true,
            literalContext = true,
            noLearn = true,
        )
        val step = InputEngine(LatinDecoder()).reduce(start, EngineAct.ClearInput, EnterKind.Newline)

        assertEquals(listOf(Cmd.ClearInput), step.cmds)
        assertEquals("", applyAskDraft("draft😀", step.cmds))
        assertEquals("", step.state.raw)
        assertEquals("", step.state.partialText)
        assertEquals("", step.state.lastChineseContext)
        assertEquals(null, step.state.lastEnglishWord)
        assertEquals(emptyList<Candidate>(), step.state.candidates)
        assertEquals(start.page, step.state.page)
        assertEquals(start.noLearn, step.state.noLearn)
        assertFalse(step.state.literalContext)
        assertFalse(step.state.autoSpace)
    }

    private class FakeInput(var text: String, var start: Int, var end: Int) {
        var onlySelectPrefix = false
        var rejectCommit = false
        var hideBoundaries = false
        var extractedLength: Int? = null
        var extractionCalls = 0
        val edits = mutableListOf<String>()

        val connection = Proxy.newProxyInstance(
            InputConnection::class.java.classLoader,
            arrayOf(InputConnection::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getExtractedText" -> {
                    extractionCalls++
                    ExtractedText().apply {
                        text = this@FakeInput.text.let { it.take(extractedLength ?: it.length) }
                        selectionStart = start
                        selectionEnd = end
                        partialStartOffset = -1
                    }
                }
                "getTextBeforeCursor" -> if (hideBoundaries) null else text.take(minOf(start, end)).takeLast(args[0] as Int)
                "getTextAfterCursor" -> if (hideBoundaries) null else text.drop(maxOf(start, end)).take(args[0] as Int)
                "beginBatchEdit" -> edits.add("begin")
                "endBatchEdit" -> edits.add("end")
                "finishComposingText" -> edits.add("finish")
                "setComposingText" -> true
                "performContextMenuAction" -> {
                    assertEquals(android.R.id.selectAll, args[0])
                    edits += "selectAll"
                    start = 0
                    end = if (onlySelectPrefix) 1 else text.length
                    true
                }
                "commitText" -> {
                    edits += "clear"
                    if (!rejectCommit) {
                        text = text.replaceRange(minOf(start, end), maxOf(start, end), args[0].toString())
                        end = start
                    }
                    !rejectCommit
                }
                "setSelection" -> {
                    start = args[0] as Int
                    end = args[1] as Int
                    edits += "selection:$start..$end"
                    true
                }
                else -> error("Unexpected editor call: ${method.name}")
            }
        } as InputConnection
    }
}
