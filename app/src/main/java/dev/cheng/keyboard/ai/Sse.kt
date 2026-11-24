package dev.cheng.keyboard.ai

data class SseDelta(
    val content: String? = null,
    val reasoning: String? = null,
    val done: Boolean = false,
)

fun parseSseLine(line: String): SseDelta? {
    val trimmed = line.trim()
    if (!trimmed.startsWith("data:")) return null
    val payload = trimmed.removePrefix("data:").trim()
    if (payload.isEmpty()) return null
    if (payload == "[DONE]") return SseDelta(done = true)
    return SseDelta(
        content = jsonString(payload, "content"),
        reasoning = jsonString(payload, "reasoning_content"),
    )
}

internal fun jsonString(json: String, field: String): String? {
    var cursor = 0
    while (cursor < json.length) {
        if (json[cursor] != '"') {
            cursor++
            continue
        }
        val tokenEnd = jsonStringEnd(json, cursor)
        if (tokenEnd < 0) return null
        var colon = tokenEnd + 1
        while (colon < json.length && json[colon].isWhitespace()) colon++
        if (colon >= json.length || json[colon] != ':') {
            cursor = tokenEnd + 1
            continue
        }
        if (json.substring(cursor + 1, tokenEnd) != field) {
            cursor = tokenEnd + 1
            continue
        }
        var valueStart = colon + 1
        while (valueStart < json.length && json[valueStart].isWhitespace()) valueStart++
        if (valueStart >= json.length) return null
        if (json.startsWith("null", valueStart)) return null
        if (json[valueStart] != '"') return null
        return jsonStringValue(json, valueStart)
    }
    return null
}

private fun jsonStringEnd(json: String, quote: Int): Int {
    var cursor = quote + 1
    while (cursor < json.length) {
        when (json[cursor]) {
            '\\' -> {
                if (cursor + 1 >= json.length) return -1
                cursor += if (json[cursor + 1] == 'u') {
                    if (cursor + 5 >= json.length) return -1
                    6
                } else {
                    2
                }
            }
            '"' -> return cursor
            else -> cursor++
        }
    }
    return -1
}

private fun jsonStringValue(json: String, quote: Int): String? {
    val out = StringBuilder()
    var cursor = quote + 1
    while (cursor < json.length) {
        val ch = json[cursor]
        if (ch == '"') return out.toString()
        if (ch != '\\') {
            out.append(ch)
            cursor++
            continue
        }
        if (cursor + 1 >= json.length) return null
        when (val escaped = json[cursor + 1]) {
            '"' -> out.append('"')
            '\\' -> out.append('\\')
            '/' -> out.append('/')
            'b' -> out.append('\b')
            'f' -> out.append('\u000C')
            'n' -> out.append('\n')
            'r' -> out.append('\r')
            't' -> out.append('\t')
            'u' -> {
                if (cursor + 5 >= json.length) return null
                val hex = json.substring(cursor + 2, cursor + 6)
                out.append(hex.toIntOrNull(16)?.toChar() ?: '?')
                cursor += 6
                continue
            }
            else -> out.append(escaped)
        }
        cursor += 2
    }
    return null
}

const val MODEL_FLASH = "deepseek-flash"
const val MODEL_PRO = "deepseek-v4-pro"

const val TRANSLATE_PROMPT =
    "你是翻译。判断用户文字主要是中文还是英文。中文就译成自然的英文，英文就译成自然的简体中文。只输出译文，不要引号，不要解释。保留换行、人名、数字和代码。"

const val POLISH_PROMPT =
    "你是文字编辑。在不改变事实和原语言的前提下，把这段话改得更通顺、简洁、自然。中文保持简体中文，英文保持英文。只输出润色后的正文，不要引号和解释。"

const val ASK_PROMPT = "你是写作和表达助手。回答简洁、可以直接使用。不要用多余的客套。"
