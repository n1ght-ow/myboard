package dev.cheng.keyboard.ime

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import dev.cheng.keyboard.ai.HostScene
import dev.cheng.keyboard.ai.hostSceneOf
import dev.cheng.keyboard.engine.Cmd
import dev.cheng.keyboard.engine.previousWordUnits
import dev.cheng.keyboard.engine.resolveSmartCommit
import dev.cheng.keyboard.engine.shouldAutoCap
import dev.cheng.keyboard.model.EnterKind
import dev.cheng.keyboard.model.ShiftState
import kotlin.math.abs

data class FieldTraits(
    val password: Boolean,
    val noLearn: Boolean,
    val numeric: Boolean,
    val uriOrEmail: Boolean,
)

fun fieldTraitsOf(info: EditorInfo): FieldTraits {
    val password = isPasswordField(info)
    val noLearn = info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0
    val inputClass = info.inputType and EditorInfo.TYPE_MASK_CLASS
    val variation = info.inputType and EditorInfo.TYPE_MASK_VARIATION
    val numeric = !password && (
        inputClass == EditorInfo.TYPE_CLASS_NUMBER ||
            inputClass == EditorInfo.TYPE_CLASS_PHONE ||
            inputClass == EditorInfo.TYPE_CLASS_DATETIME
        )
    val uriOrEmail = !password && inputClass == EditorInfo.TYPE_CLASS_TEXT && (
        variation == EditorInfo.TYPE_TEXT_VARIATION_URI ||
            variation == EditorInfo.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
            variation == EditorInfo.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
        )
    return FieldTraits(
        password = password,
        noLearn = noLearn,
        numeric = numeric,
        uriOrEmail = uriOrEmail,
    )
}

fun resolveAutoShift(info: EditorInfo, before: String): ShiftState {
    val traits = fieldTraitsOf(info)
    if (traits.password || traits.numeric || traits.uriOrEmail) return ShiftState.Off
    val flags = info.inputType
    if (flags and EditorInfo.TYPE_TEXT_FLAG_CAP_CHARACTERS != 0) return ShiftState.Caps
    if (flags and EditorInfo.TYPE_TEXT_FLAG_CAP_WORDS != 0) {
        return if (before.isEmpty() || before.last().isWhitespace()) ShiftState.Once else ShiftState.Off
    }
    val variation = flags and EditorInfo.TYPE_MASK_VARIATION
    if (variation == EditorInfo.TYPE_TEXT_VARIATION_FILTER || variation == EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) {
        return ShiftState.Off
    }
    return if (shouldAutoCap(before)) ShiftState.Once else ShiftState.Off
}

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
    val contextBefore: String = "",
    val contextAfter: String = "",
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
            return FieldSource(
                text = all.substring(from, to).take(4000),
                selection = true,
                start = from,
                end = to,
                contextBefore = contextBeforeOf(all, from),
                contextAfter = contextAfterOf(all, to),
            )
        }
        return paragraphSource(all, start)
    }
    val selected = connection.getSelectedText(0)?.toString()
    if (!selected.isNullOrEmpty()) {
        return FieldSource(
            text = selected.take(4000),
            selection = true,
            start = -1,
            end = -1,
            contextBefore = "",
            contextAfter = "",
        )
    }
    val before = connection.getTextBeforeCursor(4000, 0)?.toString()
    val after = connection.getTextAfterCursor(4000, 0)?.toString()
    if (before == null && after == null) return null
    return paragraphSource(before.orEmpty() + after.orEmpty(), before.orEmpty().length).copy(start = -1, end = -1)
}

private fun contextBeforeOf(all: String, start: Int, limit: Int = 200): String =
    if (start <= 0) "" else all.substring(maxOf(0, start - limit), start)

private fun contextAfterOf(all: String, end: Int, limit: Int = 120): String =
    if (end < 0 || end >= all.length) "" else all.substring(end, minOf(all.length, end + limit))

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
    val contextBefore = if (useAll) "" else contextBeforeOf(all, start)
    val contextAfter = if (useAll) "" else contextAfterOf(all, end)
    return FieldSource(
        text = text,
        selection = false,
        start = start,
        end = end,
        contextBefore = contextBefore,
        contextAfter = contextAfter,
    )
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
                is Cmd.LearnZh, is Cmd.LearnZhBigram, is Cmd.LearnEn -> Unit
            }
        }
    }

    fun reset() {
        composing = false
    }

    private fun commitSmart(input: InputConnection, text: String) {
        val needContext = !composing && (
            (text.isNotEmpty() && text[0] in '0'..'9') ||
                text == "“" ||
                text == "‘" ||
                text.length == 2
            )
        val before = if (needContext) input.getTextBeforeCursor(120, 0)?.toString().orEmpty() else ""
        val smart = resolveSmartCommit(before, text, composing)
        if (smart.deleteBefore > 0) {
            input.deleteSurroundingText(smart.deleteBefore, 0)
        }
        input.commitText(smart.text, 1)
        if (smart.cursorOffsetBack > 0) {
            moveCursor(input, -smart.cursorOffsetBack)
        }
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

fun extractHostScene(info: EditorInfo?, enter: EnterKind): HostScene {
    val traits = info?.let(::fieldTraitsOf)
    val hint = if (info == null || traits?.password == true || traits?.noLearn == true) {
        null
    } else {
        info.hintText?.toString()
    }
    return hostSceneOf(info?.packageName, hint, enter)
}
