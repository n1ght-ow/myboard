package dev.cheng.keyboard.ime

import android.os.Build
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
import dev.cheng.keyboard.ime.assist.TypingSuggestion
import kotlin.math.abs

data class FieldTraits(
    val password: Boolean,
    val noLearn: Boolean,
    val numeric: Boolean,
    val uriOrEmail: Boolean,
)

data class EditorSessionKey(
    val packageName: String?,
    val fieldId: Int,
    val inputType: Int,
    val imeOptions: Int,
)

fun editorSessionKeyOf(info: EditorInfo): EditorSessionKey = EditorSessionKey(
    packageName = info.packageName,
    fieldId = info.fieldId,
    inputType = info.inputType,
    imeOptions = info.imeOptions,
)

fun shouldResetEditorSession(
    restarting: Boolean,
    previous: EditorSessionKey?,
    current: EditorSessionKey,
): Boolean = !restarting || previous == null || previous != current

fun fieldTraitsOf(info: EditorInfo): FieldTraits {
    return fieldTraitsOf(info.inputType, info.imeOptions)
}

fun fieldTraitsOf(inputType: Int, imeOptions: Int): FieldTraits {
    val password = isPasswordInputType(inputType)
    val noLearn = imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0
    val inputClass = inputType and EditorInfo.TYPE_MASK_CLASS
    val variation = inputType and EditorInfo.TYPE_MASK_VARIATION
    val numeric =
        inputClass == EditorInfo.TYPE_CLASS_NUMBER ||
            inputClass == EditorInfo.TYPE_CLASS_PHONE ||
            inputClass == EditorInfo.TYPE_CLASS_DATETIME
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
    return isPasswordInputType(info.inputType)
}

fun isPasswordInputType(inputType: Int): Boolean {
    val variation = inputType and EditorInfo.TYPE_MASK_VARIATION
    return when (inputType and EditorInfo.TYPE_MASK_CLASS) {
        EditorInfo.TYPE_CLASS_TEXT -> variation == EditorInfo.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == EditorInfo.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            variation == EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        EditorInfo.TYPE_CLASS_NUMBER -> variation == EditorInfo.TYPE_NUMBER_VARIATION_PASSWORD
        else -> false
    }
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

data class EditorTextSpan(val start: Int, val end: Int) {
    fun ordered(): EditorTextSpan = if (start <= end) this else EditorTextSpan(end, start)
}

data class TypingEditorSnapshot(
    val before: String,
    val after: String,
    val selection: EditorTextSpan,
)

enum class TypingSuggestionEditResult {
    Applied,
    Unchanged,
    CompositionFinalizedWithoutReplacement,
}

enum class ClearInputResult {
    Applied,
    Unchanged,
    CompositionFinalizedWithoutClear,
}

fun isUtf16Boundary(text: String, index: Int): Boolean {
    if (index !in 0..text.length) return false
    return index == 0 ||
        index == text.length ||
        !(text[index - 1].isHighSurrogate() && text[index].isLowSurrogate())
}

fun typingEditorSnapshot(
    text: String,
    selectionStart: Int,
    selectionEnd: Int,
    startOffset: Int,
): TypingEditorSnapshot? {
    if (startOffset < 0 || selectionStart !in 0..text.length || selectionEnd !in selectionStart..text.length) return null
    if (hasSplitUtf16Boundary(text, startOffset)) return null
    if (!isUtf16Boundary(text, selectionStart) || !isUtf16Boundary(text, selectionEnd)) return null
    val absoluteEnd = absoluteEditorPosition(selectionEnd, startOffset) ?: return null
    var beforeStart = (selectionStart - 160).coerceAtLeast(0)
    if (!isUtf16Boundary(text, beforeStart)) beforeStart--
    var afterEnd = (selectionEnd + 80).coerceAtMost(text.length)
    if (!isUtf16Boundary(text, afterEnd)) afterEnd++
    return TypingEditorSnapshot(
        before = text.substring(beforeStart, selectionStart),
        after = text.substring(selectionEnd, afterEnd),
        selection = EditorTextSpan(absoluteEnd - (selectionEnd - selectionStart), absoluteEnd),
    )
}

fun typingReplacementRange(snapshot: TypingEditorSnapshot, suggestion: TypingSuggestion): EditorTextSpan? {
    if (snapshot.selection.start < 0 || snapshot.selection.start != snapshot.selection.end) return null
    if (suggestion.expectedBefore != snapshot.before || suggestion.expectedAfter != snapshot.after) return null
    if (suggestion.replaceBefore !in 0..snapshot.before.length || suggestion.replaceAfter !in 0..snapshot.after.length) return null
    if (suggestion.replacement.isEmpty()) return null
    val start = snapshot.selection.start - suggestion.replaceBefore
    if (start < 0 || snapshot.selection.end > Int.MAX_VALUE - suggestion.replaceAfter) return null
    return EditorTextSpan(start, snapshot.selection.end + suggestion.replaceAfter)
}

fun compositionMissingFromEditor(
    raw: String,
    remembered: EditorTextSpan?,
    document: String?,
    startOffset: Int,
    selectionEnd: Int,
): Boolean {
    if (raw.isEmpty() || document == null || startOffset < 0 || selectionEnd < 0) return false
    if (document.isEmpty() && startOffset == 0) return true
    val relativeStart = if (remembered != null) {
        if (remembered.start < startOffset) return false
        remembered.start - startOffset
    } else {
        selectionEnd - raw.length
    }
    if (relativeStart < 0) return false
    return relativeStart > document.length ||
        raw.length > document.length - relativeStart ||
        !document.regionMatches(relativeStart, raw, 0, raw.length)
}

fun fieldSourceFromExtractedText(
    text: String,
    selectionStart: Int,
    selectionEnd: Int,
    startOffset: Int,
): FieldSource? {
    if (startOffset < 0) return null
    if (selectionStart !in 0..text.length || selectionEnd !in 0..text.length) return null
    val start = selectionStart
    val end = selectionEnd
    if (hasSplitUtf16Boundary(text, startOffset)) return null
    if (!isUtf16Boundary(text, start) || !isUtf16Boundary(text, end)) return null
    if (start != end) {
        val from = minOf(start, end)
        val to = maxOf(start, end)
        if (to - from > MAX_FIELD_SOURCE_UNITS) return null
        return FieldSource(
            text = text.substring(from, to),
            selection = true,
            start = absoluteEditorPosition(from, startOffset) ?: return null,
            end = absoluteEditorPosition(to, startOffset) ?: return null,
            contextBefore = contextBeforeOf(text, from),
            contextAfter = contextAfterOf(text, to),
        )
    }
    return paragraphSource(text, start, startOffset)
}

fun textAtDocumentRange(
    text: String,
    startOffset: Int,
    start: Int,
    end: Int,
): String? {
    if (startOffset < 0) return null
    val range = EditorTextSpan(start, end).ordered()
    if (range.start < startOffset || range.end < startOffset) return null
    val localStart = range.start - startOffset
    val localEnd = range.end - startOffset
    if (localEnd > text.length) return null
    if ((localStart == 0 && startOffset > 0 && text.firstOrNull()?.isLowSurrogate() == true) ||
        (localEnd == text.length && text.lastOrNull()?.isHighSurrogate() == true)
    ) {
        return null
    }
    if (!isUtf16Boundary(text, localStart) || !isUtf16Boundary(text, localEnd)) return null
    return text.substring(localStart, localEnd)
}

fun compositionDetachTarget(
    compositionLength: Int,
    remembered: EditorTextSpan?,
    candidates: EditorTextSpan?,
    oldSelection: EditorTextSpan,
    newSelection: EditorTextSpan,
): EditorTextSpan? {
    if (compositionLength <= 0) return null
    val old = oldSelection.ordered()
    val next = newSelection.ordered()
    if (next.start < 0 || next.end < 0) return null
    if (old == next) return null
    val candidate = candidates
        ?.takeIf { it.start >= 0 && it.end > it.start }
        ?.ordered()
    val appendedSuffix = candidate != null &&
        candidate.end - candidate.start != compositionLength &&
        old.end == candidate.end &&
        next.start == next.end &&
        next.start >= candidate.end &&
        next.start - candidate.end == compositionLength
    if (appendedSuffix) return null
    val composition = candidate
        ?: remembered?.takeIf {
            it.start >= 0 && it.end > it.start && it.end - it.start == compositionLength
        }
        ?: run {
            if (old.start == old.end && old.end >= compositionLength) {
                EditorTextSpan(old.end - compositionLength, old.end)
            } else {
                null
            }
        }
        ?: return null
    val staysInside = if (next.start == next.end) {
        next.start > composition.start && next.start <= composition.end
    } else {
        next.start >= composition.start && next.end <= composition.end
    }
    return composition.takeUnless { staysInside }
}

fun remapSelectionAfterReplacement(
    selection: EditorTextSpan,
    replaced: EditorTextSpan,
    replacementLength: Int,
): EditorTextSpan {
    val source = selection.ordered()
    val range = replaced.ordered()
    fun map(position: Int): Int = when {
        position <= range.start -> position
        position >= range.end -> position - (range.end - range.start) + replacementLength
        else -> range.start + replacementLength
    }
    val start = map(source.start)
    val end = map(source.end)
    return if (selection.start <= selection.end) EditorTextSpan(start, end) else EditorTextSpan(end, start)
}

fun smartCommitNeedsContext(
    hasSelection: Boolean,
    composing: Boolean,
    text: String,
): Boolean = !hasSelection && !composing && (
    (text.isNotEmpty() && text[0] in '0'..'9') ||
        text == "“" ||
        text == "‘" ||
        text.length == 2
    )

fun shouldUseSmartCommit(password: Boolean, uriOrEmail: Boolean): Boolean =
    !password && !uriOrEmail

fun smartCommitTextAfterDelete(
    originalText: String,
    resolvedText: String,
    requiresDelete: Boolean,
    deleteApplied: Boolean,
): String = if (requiresDelete && !deleteApplied) originalText else resolvedText

fun cursorPositionAfterMove(
    text: String,
    selectionEnd: Int,
    startOffset: Int,
    delta: Int,
): Int? {
    if (startOffset < 0 || selectionEnd !in 0..text.length) return null
    if (hasSplitUtf16Boundary(text, startOffset)) return null
    if (!isUtf16Boundary(text, selectionEnd)) return null
    if (delta == Int.MIN_VALUE) return null
    if (delta == 0) return absoluteEditorPosition(selectionEnd, startOffset)
    val steps = abs(delta)
    if (steps > text.length) return null

    val asciiTarget = selectionEnd.toLong() + delta
    if (asciiTarget in 0L..text.length.toLong()) {
        val target = asciiTarget.toInt()
        val left = (minOf(selectionEnd, target) - 1).coerceAtLeast(0)
        val right = maxOf(selectionEnd, target).coerceAtMost(text.lastIndex)
        if ((left..right).all { index -> text[index] in ' '..'~' }) {
            return absoluteEditorPosition(target, startOffset)
        }
    }

    val clusters = GRAPHEME_CLUSTER.findAll(text)
    val target = if (delta > 0) {
        clusters.map { it.range.last + 1 }.filter { it > selectionEnd }.drop(steps - 1).firstOrNull()
    } else {
        val previous = IntArray(steps)
        var count = 0
        for (cluster in clusters) {
            val start = cluster.range.first
            if (start >= selectionEnd) break
            previous[count % steps] = start
            count++
        }
        if (count < steps) null else previous[(count - steps) % steps]
    }
    return target?.let { absoluteEditorPosition(it, startOffset) }
}

fun safeUtf16Suffix(text: String, maxUnits: Int): String? {
    if (maxUnits < 0) return null
    if (maxUnits == 0) return ""
    if (text.length <= maxUnits) return text.takeIf { !hasUnpairedSurrogate(it) }
    var start = text.length - maxUnits
    if (!isUtf16Boundary(text, start)) start++
    return text.substring(start).takeIf { !hasUnpairedSurrogate(it) }
}

fun codePointDeleteCount(text: String, maxUtf16Units: Int): Int? {
    val suffix = safeUtf16Suffix(text, maxUtf16Units) ?: return null
    return suffix.codePointCount(0, suffix.length)
}

fun lastCompleteGraphemeBeforeCursor(text: String, maxUtf16Units: Int): String? {
    if (text.isEmpty() || maxUtf16Units <= 0) return null
    val window = text.takeLast(maxUtf16Units)
    val mayBeTruncated = window.length == maxUtf16Units
    val analysis = if (window.first().isLowSurrogate()) window.substring(1) else window
    if (analysis.isEmpty()) return null
    var last: MatchResult? = null
    for (match in GRAPHEME_CLUSTER.findAll(analysis)) {
        last = match
    }
    val match = last ?: return null
    if (match.range.last != analysis.lastIndex) return null
    if (mayBeTruncated && match.range.first == 0) return null
    return match.value.takeUnless(::hasUnpairedSurrogate)
}

fun readField(connection: InputConnection): FieldSource? {
    val extracted = runCatching {
        connection.getExtractedText(extractedRequest(), 0)
    }.getOrNull()
    if (extracted?.text != null && extracted.startOffset >= 0) {
        val source = fieldSourceFromExtractedText(
            text = extracted.text.toString(),
            selectionStart = extracted.selectionStart,
            selectionEnd = extracted.selectionEnd,
            startOffset = extracted.startOffset,
        )
        if (source != null) return source
    }
    val selected = runCatching {
        connection.getSelectedText(0)?.toString()
    }.getOrNull()
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
    val before = runCatching {
        connection.getTextBeforeCursor(4000, 0)?.toString()
    }.getOrNull()
    val after = runCatching {
        connection.getTextAfterCursor(4000, 0)?.toString()
    }.getOrNull()
    if (before == null && after == null) return null
    return paragraphSource(before.orEmpty() + after.orEmpty(), before.orEmpty().length, 0)
        ?.copy(start = -1, end = -1)
}

private fun absoluteEditorPosition(local: Int, startOffset: Int): Int? {
    if (local < 0 || startOffset < 0 || local > Int.MAX_VALUE - startOffset) return null
    return startOffset + local
}

private fun hasUnpairedSurrogate(text: String): Boolean {
    var index = 0
    while (index < text.length) {
        val character = text[index]
        if (character.isHighSurrogate()) {
            if (index + 1 >= text.length || !text[index + 1].isLowSurrogate()) return true
            index += 2
        } else if (character.isLowSurrogate()) {
            return true
        } else {
            index++
        }
    }
    return false
}

private fun hasSplitUtf16Boundary(text: String, startOffset: Int): Boolean =
    (startOffset > 0 && text.firstOrNull()?.isLowSurrogate() == true) ||
        text.lastOrNull()?.isHighSurrogate() == true

private val GRAPHEME_CLUSTER = Regex("\\X")

private fun contextBeforeOf(all: String, start: Int, limit: Int = 200): String =
    if (start <= 0) "" else all.substring(maxOf(0, start - limit), start)

private fun contextAfterOf(all: String, end: Int, limit: Int = 120): String =
    if (end < 0 || end >= all.length) "" else all.substring(end, minOf(all.length, end + limit))

private fun extractedRequest() = ExtractedTextRequest().apply {
    hintMaxChars = 10000
    hintMaxLines = 200
}

private fun paragraphSource(all: String, cursor: Int, startOffset: Int): FieldSource? {
    val safeCursor = cursor.coerceIn(0, all.length)
    val head = all.substring(0, safeCursor)
    val tail = all.substring(safeCursor)
    val paragraphHead = head.substring(head.lastIndexOf('\n') + 1)
    val breakAt = tail.indexOf('\n')
    val paragraphTail = if (breakAt < 0) tail else tail.substring(0, breakAt)
    val paragraph = paragraphHead + paragraphTail
    val useAll = paragraph.isBlank()
    val text = (if (useAll) all else paragraph).take(4000)
    val localStart = if (useAll) 0 else safeCursor - paragraphHead.length
    val localEnd = (localStart + text.length).coerceAtMost(all.length)
    val start = absoluteEditorPosition(localStart, startOffset) ?: return null
    val end = absoluteEditorPosition(localEnd, startOffset) ?: return null
    val contextBefore = if (useAll) "" else contextBeforeOf(all, localStart)
    val contextAfter = if (useAll) "" else contextAfterOf(all, localEnd)
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

    fun readTypingSnapshot(): TypingEditorSnapshot? {
        val input = connection() ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val surrounding = runCatching { input.getSurroundingText(160, 80, 0) }.getOrNull()
            if (surrounding != null && surrounding.offset >= 0) {
                return typingEditorSnapshot(
                    surrounding.text.toString(),
                    surrounding.selectionStart,
                    surrounding.selectionEnd,
                    surrounding.offset,
                )
            }
        }
        return runCatching {
            val extracted = input.getExtractedText(
                ExtractedTextRequest().apply { hintMaxChars = 240 },
                0,
            ) ?: return@runCatching null
            if (extracted.partialStartOffset >= 0) return@runCatching null
            if (extracted.startOffset < 0) return@runCatching null
            typingEditorSnapshot(
                extracted.text?.toString() ?: return@runCatching null,
                extracted.selectionStart,
                extracted.selectionEnd,
                extracted.startOffset,
            )
        }.getOrNull()
    }

    fun replaceTypingSuggestion(
        expected: TypingEditorSnapshot,
        suggestion: TypingSuggestion,
    ): Boolean = replaceTypingSuggestionWithResult(expected, suggestion) == TypingSuggestionEditResult.Applied

    fun replaceTypingSuggestionWithResult(
        expected: TypingEditorSnapshot,
        suggestion: TypingSuggestion,
    ): TypingSuggestionEditResult {
        val input = connection() ?: return TypingSuggestionEditResult.Unchanged
        val current = runCatching { readTypingSnapshot() }.getOrNull()
            ?: return TypingSuggestionEditResult.Unchanged
        if (current != expected) return TypingSuggestionEditResult.Unchanged
        val target = typingReplacementRange(current, suggestion)
            ?: return TypingSuggestionEditResult.Unchanged
        if (runCatching { input.beginBatchEdit() }.isFailure) {
            return TypingSuggestionEditResult.Unchanged
        }

        try {
            val finished = runCatching { input.finishComposingText() }.getOrDefault(false)
            if (!finished) {
                val after = runCatching { readTypingSnapshot() }.getOrNull()
                if (after == null || after == current) return TypingSuggestionEditResult.Unchanged
                composing = false
                restoreTypingSelection(input, current.selection)
                return TypingSuggestionEditResult.CompositionFinalizedWithoutReplacement
            }
            composing = false

            val selected = runCatching {
                input.setSelection(target.start, target.end)
            }.getOrDefault(false)
            if (!selected) {
                restoreTypingSelection(input, current.selection)
                return TypingSuggestionEditResult.CompositionFinalizedWithoutReplacement
            }

            val committed = runCatching {
                input.commitText(suggestion.replacement, 1)
            }.getOrDefault(false)
            if (!committed) {
                restoreTypingSelection(input, current.selection)
                return TypingSuggestionEditResult.CompositionFinalizedWithoutReplacement
            }
            return TypingSuggestionEditResult.Applied
        } finally {
            runCatching { input.endBatchEdit() }
        }
    }

    fun detachComposition(
        composition: EditorTextSpan,
        selection: EditorTextSpan,
        composedText: String,
        candidate: String?,
    ) {
        val input = connection() ?: return
        val range = composition.ordered()
        val extractedResult = runCatching {
            input.getExtractedText(extractedRequest(), 0)
        }
        val extracted = extractedResult.getOrNull()
        val document = extracted?.text?.toString()
        val rangeMatches = extractedResult.isSuccess &&
            range.start >= 0 &&
            range.end > range.start &&
            (
                extracted == null ||
                    (
                        extracted.startOffset >= 0 &&
                            textAtDocumentRange(
                                text = document.orEmpty(),
                                startOffset = extracted.startOffset,
                                start = range.start,
                                end = range.end,
                            ) == composedText
                        )
                )
        var replacementLength = range.end - range.start
        val replacement = candidate?.takeIf { rangeMatches }
        var replacementApplied = false
        if (replacement != null) {
            val selected = runCatching {
                input.setSelection(range.start, range.end)
            }.getOrDefault(false)
            if (selected) {
                val committed = runCatching {
                    input.commitText(replacement, 1)
                }.getOrDefault(false)
                if (committed) {
                    replacementApplied = true
                    replacementLength = replacement.length
                }
            }
        }
        if (!replacementApplied) {
            runCatching { input.finishComposingText() }
        }
        composing = false
        val restored = if (replacementApplied) {
            remapSelectionAfterReplacement(selection, range, replacementLength)
        } else {
            selection
        }
        runCatching { input.setSelection(restored.start, restored.end) }
    }

    fun commitPaste(text: String): Boolean {
        val input = connection() ?: return false
        if (!runCatching { input.commitText(text, 1) }.getOrDefault(false)) return false
        composing = false
        return true
    }

    fun clearInput(privateField: Boolean = false): ClearInputResult {
        val input = connection() ?: return ClearInputResult.Unchanged
        var finalizedWithoutClear = false
        if (composing) {
            if (!runCatching { input.finishComposingText() }.getOrDefault(false)) {
                return ClearInputResult.Unchanged
            }
            composing = false
            finalizedWithoutClear = true
        }
        val snapshot = if (privateField) null else readClearSnapshot(input)
        val cleared = runCatching {
            val fullLength = snapshot?.fullLength
            val selected = if (fullLength != null) {
                input.setSelection(0, fullLength) &&
                    selectedTextMatches(input, snapshot?.text.orEmpty())
            } else {
                input.performContextMenuAction(android.R.id.selectAll) &&
                    input.getTextBeforeCursor(1, 0)?.isEmpty() == true &&
                    input.getTextAfterCursor(1, 0)?.isEmpty() == true
            }
            selected && input.commitText("", 1)
        }.getOrDefault(false)
        if (cleared) return ClearInputResult.Applied
        snapshot?.selection?.let { restoreTypingSelection(input, it) }
        return if (finalizedWithoutClear) {
            ClearInputResult.CompositionFinalizedWithoutClear
        } else {
            ClearInputResult.Unchanged
        }
    }

    private data class ClearSnapshot(
        val selection: EditorTextSpan,
        val text: String,
        val fullLength: Int?,
    )

    private fun readClearSnapshot(input: InputConnection): ClearSnapshot? = runCatching {
        val extracted = input.getExtractedText(extractedRequest(), 0)
            ?: return@runCatching null
        val text = extracted.text?.toString() ?: return@runCatching null
        if (extracted.partialStartOffset >= 0 ||
            !isUtf16Boundary(text, extracted.selectionStart) || !isUtf16Boundary(text, extracted.selectionEnd)
        ) return@runCatching null
        val start = absoluteEditorPosition(extracted.selectionStart, extracted.startOffset) ?: return@runCatching null
        val end = absoluteEditorPosition(extracted.selectionEnd, extracted.startOffset) ?: return@runCatching null
        val selection = EditorTextSpan(start, end)
        val range = selection.ordered()
        val fullLength = text.length.takeIf {
            extracted.startOffset == 0 && it <= 16_384 &&
                input.getTextBeforeCursor(range.start + 1, 0)?.length == range.start &&
                input.getTextAfterCursor(it - range.end + 1, 0)?.length == it - range.end
        }
        ClearSnapshot(selection, text, fullLength)
    }.getOrNull()

    fun apply(cmds: List<Cmd>, smartCommit: Boolean = true) {
        val input = connection() ?: return
        val commitThenCompose = cmds.size >= 2 && cmds[0] is Cmd.Commit && cmds[1] is Cmd.Compose
        val batchStarted = commitThenCompose &&
            runCatching { input.beginBatchEdit() }.getOrDefault(false)
        if (!batchStarted) {
            applyCommands(input, cmds, smartCommit)
            return
        }
        try {
            applyCommands(input, cmds, smartCommit)
        } finally {
            runCatching { input.endBatchEdit() }
        }
    }

    private fun applyCommands(input: InputConnection, cmds: List<Cmd>, smartCommit: Boolean) {
        for (cmd in cmds) {
            when (cmd) {
                is Cmd.Commit -> {
                    if (smartCommit) commitSmart(input, cmd.text) else input.commitText(cmd.text, 1)
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
                is Cmd.DeleteCount -> deleteBefore(input, cmd.count)
                Cmd.DeleteWord -> deleteWord(input)
                Cmd.ClearInput -> clearInput()
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
        val mayNeedContext = smartCommitNeedsContext(
            hasSelection = false,
            composing = composing,
            text = text,
        )
        val hasSelection = mayNeedContext && hasEditorSelection(input)
        val needContext = smartCommitNeedsContext(hasSelection, composing, text)
        val before = if (needContext) {
            runCatching {
                input.getTextBeforeCursor(120, 0)?.toString().orEmpty()
            }.getOrDefault("")
        } else {
            ""
        }
        val smart = resolveSmartCommit(before, text, composing)
        val deleteApplied = if (smart.deleteBefore > 0) {
            runCatching {
                input.deleteSurroundingText(smart.deleteBefore, 0)
            }.getOrNull() ?: return
        } else {
            true
        }
        val commitText = smartCommitTextAfterDelete(
            originalText = text,
            resolvedText = smart.text,
            requiresDelete = smart.deleteBefore > 0,
            deleteApplied = deleteApplied,
        )
        val committed = runCatching {
            input.commitText(commitText, 1)
        }.getOrDefault(false)
        if (!committed) return
        if (smart.cursorOffsetBack > 0) {
            moveCursor(input, -smart.cursorOffsetBack)
        }
    }

    private fun moveCursor(input: InputConnection, delta: Int) {
        if (delta == 0) return
        if (delta == Int.MIN_VALUE) return
        val extracted = runCatching {
            input.getExtractedText(ExtractedTextRequest(), 0)
        }.getOrNull()
        if (extracted?.text != null && extracted.selectionEnd >= 0) {
            val content = extracted.text.toString()
            val position = cursorPositionAfterMove(
                text = content,
                selectionEnd = extracted.selectionEnd,
                startOffset = extracted.startOffset,
                delta = delta,
            )
            if (position != null) {
                val moved = runCatching {
                    input.setSelection(position, position)
                }.getOrDefault(false)
                if (moved) return
            }
        }
        val code = if (delta < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        repeat(abs(delta)) {
            runCatching {
                input.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                input.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            }
        }
    }

    private fun deleteOne(input: InputConnection) {
        if (deleteSelectionIfPresent(input)) return
        val before = runCatching {
            input.getTextBeforeCursor(DELETE_ONE_WINDOW_UNITS, 0)?.toString().orEmpty()
        }.getOrDefault("")
        val grapheme = lastCompleteGraphemeBeforeCursor(before, DELETE_ONE_WINDOW_UNITS) ?: return
        deleteValidatedSuffixBefore(input, grapheme)
    }

    private fun deleteWord(input: InputConnection) {
        if (deleteSelectionIfPresent(input)) return
        val before = runCatching {
            input.getTextBeforeCursor(81, 0)?.toString().orEmpty()
        }.getOrDefault("")
        val safeBefore = safeUtf16Suffix(before, 80) ?: return
        val units = previousWordUnits(safeBefore)
        val suffix = safeUtf16Suffix(safeBefore, units) ?: return
        deleteValidatedSuffixBefore(input, suffix)
    }

    private fun deleteBefore(input: InputConnection, units: Int) {
        if (units <= 0 || deleteSelectionIfPresent(input)) return
        val probeSize = if (units == Int.MAX_VALUE) units else units + 1
        val before = runCatching {
            input.getTextBeforeCursor(probeSize, 0)?.toString().orEmpty()
        }.getOrDefault("")
        val suffix = safeUtf16Suffix(before, units) ?: return
        deleteValidatedSuffixBefore(input, suffix)
    }

    private fun deleteValidatedSuffixBefore(input: InputConnection, suffix: String) {
        if (suffix.isEmpty() || hasUnpairedSurrogate(suffix)) return
        val codePointCount = suffix.codePointCount(0, suffix.length)
        val codePointResult = runCatching {
            input.deleteSurroundingTextInCodePoints(codePointCount, 0)
        }
        if (codePointResult.getOrNull() == true || codePointResult.isFailure) return
        runCatching { input.deleteSurroundingText(suffix.length, 0) }
    }

    private fun deleteSelectionIfPresent(input: InputConnection): Boolean {
        if (hasEditorSelection(input)) {
            runCatching { input.commitText("", 1) }
            return true
        }
        return false
    }

    private fun hasEditorSelection(input: InputConnection): Boolean {
        val selected = runCatching { input.getSelectedText(0) }.getOrNull()
        if (!selected.isNullOrEmpty()) return true
        val extracted = runCatching { input.getExtractedText(ExtractedTextRequest(), 0) }.getOrNull()
        return extracted != null &&
            extracted.selectionStart >= 0 &&
            extracted.selectionEnd >= 0 &&
            extracted.selectionStart != extracted.selectionEnd
    }

    private fun restoreTypingSelection(input: InputConnection, selection: EditorTextSpan) {
        runCatching { input.setSelection(selection.start, selection.end) }
    }

    private fun selectedTextMatches(input: InputConnection, expected: String): Boolean {
        val selected = runCatching { input.getSelectedText(0)?.toString() }.getOrNull()
        if (selected != null) return selected == expected
        val extracted = runCatching { input.getExtractedText(extractedRequest(), 0) }.getOrNull()
        if (extracted?.text != null && extracted.startOffset >= 0) {
            val text = extracted.text.toString()
            val start = minOf(extracted.selectionStart, extracted.selectionEnd)
            val end = maxOf(extracted.selectionStart, extracted.selectionEnd)
            return start == 0 && end == text.length && text == expected
        }
        return false
    }
}

private const val MAX_FIELD_SOURCE_UNITS = 4000

fun extractHostScene(info: EditorInfo?, enter: EnterKind): HostScene {
    val traits = info?.let(::fieldTraitsOf)
    val hint = if (info == null || traits?.password == true || traits?.noLearn == true) {
        null
    } else {
        info.hintText?.toString()
    }
    return hostSceneOf(info?.packageName, hint, enter)
}

private const val DELETE_ONE_WINDOW_UNITS = 256
