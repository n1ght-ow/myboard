package dev.cheng.keyboard.engine

import dev.cheng.keyboard.model.ShiftState

data class SmartCommit(
    val deleteBefore: Int = 0,
    val text: String,
    val cursorOffsetBack: Int = 0,
)

private val pairedSymbols = setOf(
    "（）", "《》", "【】", "「」", "『』", "“”", "‘’", "()", "[]", "{}",
)

fun resolveSmartCommit(before: String, text: String, composing: Boolean): SmartCommit {
    if (!composing && text.isNotEmpty() && text[0] in '0'..'9') {
        if (before.length >= 2 && before[before.length - 2] in '0'..'9') {
            when (before.last()) {
                '。' -> return SmartCommit(deleteBefore = 1, text = ".$text")
                '：' -> return SmartCommit(deleteBefore = 1, text = ":$text")
            }
        }
    }
    if (!composing) {
        if (text == "“") {
            val line = before.substringAfterLast('\n')
            if (line.lastIndexOf('“') > line.lastIndexOf('”')) {
                return SmartCommit(text = "”")
            }
        } else if (text == "‘") {
            val line = before.substringAfterLast('\n')
            if (line.lastIndexOf('‘') > line.lastIndexOf('’')) {
                return SmartCommit(text = "’")
            }
        } else if (text in pairedSymbols && text.length == 2) {
            return SmartCommit(text = text, cursorOffsetBack = 1)
        }
    }
    return SmartCommit(text = text)
}

fun extractTrailingCjk(text: String, maxChars: Int = 4): String {
    if (text.isEmpty() || maxChars <= 0) return ""
    val buf = StringBuilder()
    var idx = text.length - 1
    while (idx >= 0 && buf.length < maxChars) {
        val ch = text[idx]
        if (isCjkChar(ch)) {
            buf.append(ch)
            idx--
        } else {
            break
        }
    }
    return buf.reverse().toString()
}

fun isCjkChar(ch: Char): Boolean = ch.code in 0x4E00..0x9FFF || ch.code in 0x3400..0x4DBF

fun applyCase(suggestion: String, typed: String): String {
    if (suggestion.equals("i", ignoreCase = true)) return "I"
    if (suggestion.length >= 3 && suggestion.startsWith("i'", ignoreCase = true)) {
        return if (typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() }) {
            suggestion.uppercase()
        } else {
            "I'" + suggestion.substring(2)
        }
    }
    if (typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() } && typed.any { it.isUpperCase() }) {
        return suggestion.uppercase()
    }
    if (typed.firstOrNull()?.isUpperCase() == true) {
        return suggestion.replaceFirstChar { char ->
            if (char.isLowerCase()) char.uppercaseChar() else char
        }
    }
    return suggestion
}

fun shiftAfter(current: ShiftState, committed: String): ShiftState {
    if (current == ShiftState.Caps) return ShiftState.Caps
    val last = committed.trimEnd().lastOrNull() ?: return ShiftState.Off
    return if (last == '.' || last == '!' || last == '?' || last == '。' || last == '！' || last == '？') {
        ShiftState.Once
    } else {
        ShiftState.Off
    }
}

fun shouldAutoCap(before: String): Boolean {
    var index = before.length
    while (index > 0 && before[index - 1].isWhitespace()) index--
    if (index == 0) return true
    return before[index - 1] in ".!?。！？\n"
}

fun previousWordUnits(before: String): Int {
    if (before.isEmpty()) return 0
    var index = before.length
    while (index > 0 && before[index - 1].isWhitespace()) index--
    while (index > 0 && !before[index - 1].isWhitespace()) index--
    if (index > 0 && before[index - 1].isWhitespace()) index--
    return before.length - index
}

fun applyAskDraft(draft: String, cmds: List<Cmd>): String {
    var text = draft
    for (cmd in cmds) {
        when (cmd) {
            is Cmd.Commit -> text += cmd.text.replace("\n", "")
            Cmd.DeleteOne -> text = dropLastCodePoint(text)
            is Cmd.DeleteCount -> {
                if (cmd.count > 0) text = text.dropLast(cmd.count.coerceAtMost(text.length))
            }
            Cmd.DeleteWord -> {
                val count = previousWordUnits(text)
                if (count > 0) text = text.dropLast(count)
            }
            else -> Unit
        }
    }
    return text
}

private fun dropLastCodePoint(text: String): String {
    if (text.isEmpty()) return text
    val last = text.last()
    return if (text.length >= 2 && last.isLowSurrogate() && text[text.lastIndex - 1].isHighSurrogate()) {
        text.dropLast(2)
    } else {
        text.dropLast(1)
    }
}

fun stripWrapped(text: String): String {
    var value = text.trim()
    val pairs = listOf("\"" to "\"", "“" to "”", "「" to "」")
    for ((open, close) in pairs) {
        if (value.length >= open.length + close.length && value.startsWith(open) && value.endsWith(close)) {
            value = value.substring(open.length, value.length - close.length).trim()
            break
        }
    }
    val prefixes = listOf("译文：", "翻译：", "润色：", "Translation:", "Polished:")
    for (prefix in prefixes) {
        if (value.startsWith(prefix)) value = value.removePrefix(prefix).trim()
    }
    return value
}
