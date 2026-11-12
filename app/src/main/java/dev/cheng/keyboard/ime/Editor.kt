package dev.cheng.keyboard.ime

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import dev.cheng.keyboard.engine.Cmd
import dev.cheng.keyboard.engine.previousWordUnits
import dev.cheng.keyboard.model.EnterKind
import kotlin.math.abs

fun isPasswordField(info: EditorInfo): Boolean {
    val variation = info.inputType and EditorInfo.TYPE_MASK_VARIATION
    return variation == EditorInfo.TYPE_TEXT_VARIATION_PASSWORD ||
        variation == EditorInfo.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
        variation == EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
        variation == EditorInfo.TYPE_NUMBER_VARIATION_PASSWORD
}

fun enterKindOf(info: EditorInfo): EnterKind {
    val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
    val noEnter = info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
    if (noEnter || action == EditorInfo.IME_ACTION_NONE || action == EditorInfo.IME_ACTION_UNSPECIFIED) {
        return EnterKind.Newline
    }
    return when (action) {
        EditorInfo.IME_ACTION_GO -> EnterKind.Go
        EditorInfo.IME_ACTION_SEARCH -> EnterKind.Search
        EditorInfo.IME_ACTION_SEND -> EnterKind.Send
        EditorInfo.IME_ACTION_NEXT -> EnterKind.Next
        EditorInfo.IME_ACTION_DONE -> EnterKind.Done
        else -> EnterKind.Newline
    }
}

fun EnterKind.imeAction(): Int = when (this) {
    EnterKind.Go -> EditorInfo.IME_ACTION_GO
    EnterKind.Search -> EditorInfo.IME_ACTION_SEARCH
    EnterKind.Send -> EditorInfo.IME_ACTION_SEND
    EnterKind.Next -> EditorInfo.IME_ACTION_NEXT
    EnterKind.Done -> EditorInfo.IME_ACTION_DONE
    EnterKind.Newline -> EditorInfo.IME_ACTION_NONE
}

data class FieldSource(
    val text: String,
    val selection: Boolean,
    val start: Int,
    val end: Int,
)

fun readField(connection: InputConnection): FieldSource? {
    val extracted = connection.getExtractedText(extractedRequest(), 0)
    if (extracted?.text != null) {
        val all = extracted.text.toString()
        val start = if (extracted.selectionStart < 0) all.length else extracted.selectionStart.coerceIn(0, all.length)
        val end = if (extracted.selectionEnd < 0) start else extracted.selectionEnd.coerceIn(0, all.length)
        if (start != end) {
            val from = minOf(start, end)
            val to = maxOf(start, end)
            return FieldSource(all.substring(from, to).take(4000), selection = true, start = from, end = to)
        }
        return paragraphSource(all, start)
    }
    val selected = connection.getSelectedText(0)?.toString()
    if (!selected.isNullOrEmpty()) return FieldSource(selected.take(4000), selection = true, start = -1, end = -1)
    val before = connection.getTextBeforeCursor(4000, 0)?.toString()
    val after = connection.getTextAfterCursor(4000, 0)?.toString()
    if (before == null && after == null) return null
    return paragraphSource(before.orEmpty() + after.orEmpty(), before.orEmpty().length).copy(start = -1, end = -1)
}

private fun extractedRequest() = ExtractedTextRequest().apply {
    hintMaxChars = 10000
    hintMaxLines = 200
}

private fun paragraphSource(all: String, cursor: Int): FieldSource {
    val safeCursor = cursor.coerceIn(0, all.length)
    val head = all.substring(0, safeCursor)
    val tail = all.substring(safeCursor)
    val paragraphHead = head.substring(head.lastIndexOf('\n') + 1)
    val breakAt = tail.indexOf('\n')
    val paragraphTail = if (breakAt < 0) tail else tail.substring(0, breakAt)
    val paragraph = paragraphHead + paragraphTail
    val useAll = paragraph.isBlank()
    val text = (if (useAll) all else paragraph).take(4000)
    val start = if (useAll) 0 else safeCursor - paragraphHead.length
    val end = (start + text.length).coerceAtMost(all.length)
    return FieldSource(text, selection = false, start = start, end = end)
}

class EditorBridge(private val connection: () -> InputConnection?) {
    private var composing = false

    fun apply(cmds: List<Cmd>) {
        val input = connection() ?: return
        for (cmd in cmds) {
            when (cmd) {
                is Cmd.Commit -> {
                    commitSmart(input, cmd.text)
                    composing = false
                }
                is Cmd.Compose -> {
                    input.setComposingText(cmd.text, 1)
                    composing = true
                }
                Cmd.ClearCompose -> if (composing) {
                    input.commitText("", 1)
                    composing = false
                }
                Cmd.DeleteOne -> deleteOne(input)
                is Cmd.DeleteCount -> if (cmd.count > 0) input.deleteSurroundingText(cmd.count, 0)
                Cmd.DeleteWord -> deleteWord(input)
                is Cmd.MoveCursor -> moveCursor(input, cmd.delta)
                is Cmd.EditorAction -> input.performEditorAction(cmd.kind.imeAction())
                is Cmd.LearnZh, is Cmd.LearnEn -> Unit
            }
        }
    }

    fun reset() {
        composing = false
    }

    private fun commitSmart(input: InputConnection, text: String) {
        // Convert <digit>。<digit> -> <digit>.<digit> and <digit>：<digit> -> <digit>:<digit> automatically
        if (!composing && text.isNotEmpty() && text[0] in '0'..'9') {
            val before = input.getTextBeforeCursor(2, 0)?.toString().orEmpty()
            if (before.length == 2 && before[0] in '0'..'9') {
                when (before[1]) {
                    '。' -> {
                        input.deleteSurroundingText(1, 0)
                        input.commitText(".$text", 1)
                        return
                    }
                    '：' -> {
                        input.deleteSurroundingText(1, 0)
                        input.commitText(":$text", 1)
                        return
                    }
                }
            }
        }
        input.commitText(text, 1)
    }

    private fun moveCursor(input: InputConnection, delta: Int) {
        if (delta == 0) return
        val extracted = input.getExtractedText(ExtractedTextRequest(), 0)
        if (extracted?.text != null && extracted.selectionEnd >= 0) {
            val content = extracted.text.toString()
            var pos = extracted.selectionEnd.coerceIn(0, content.length)
            val direction = if (delta > 0) 1 else -1
            repeat(abs(delta)) {
                pos = runCatching {
                    content.offsetByCodePoints(pos, direction)
                }.getOrDefault((pos + direction).coerceIn(0, content.length))
            }
            input.setSelection(pos, pos)
            return
        }
        val code = if (delta < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        repeat(abs(delta)) {
            input.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            input.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
    }

    private fun deleteOne(input: InputConnection) {
        val selected = input.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            input.commitText("", 1)
            return
        }
        val before = input.getTextBeforeCursor(2, 0)?.toString().orEmpty()
        if (before.isEmpty()) return
        val count = if (
            before.length >= 2 &&
            Character.isSurrogatePair(before[before.length - 2], before[before.length - 1])
        ) {
            2
        } else {
            1
        }
        input.deleteSurroundingText(count, 0)
    }

    private fun deleteWord(input: InputConnection) {
        val selected = input.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            input.commitText("", 1)
            return
        }
        val before = input.getTextBeforeCursor(80, 0)?.toString().orEmpty()
        val count = previousWordUnits(before)
        if (count > 0) input.deleteSurroundingText(count, 0)
    }
}
