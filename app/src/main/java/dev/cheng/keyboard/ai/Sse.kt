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
    val key = "\"$field\""
    var search = 0
    while (search < json.length) {
        val found = json.indexOf(key, search)
        if (found < 0) return null
        val before = if (found == 0) ' ' else json[found - 1]
        if (before == '_' || before.isLetterOrDigit()) {
            search = found + key.length
            continue
        }
        var cursor = found + key.length
        while (cursor < json.length && json[cursor].isWhitespace()) cursor++
        if (cursor >= json.length || json[cursor] != ':') return null
        cursor++
        while (cursor < json.length && json[cursor].isWhitespace()) cursor++
        if (cursor >= json.length) return null
        if (json.startsWith("null", cursor)) return null
        if (json[cursor] != '"') return null
        cursor++
        val out = StringBuilder()
        while (cursor < json.length) {
            val ch = json[cursor]
            if (ch == '\\' && cursor + 1 < json.length) {
                when (val next = json[cursor + 1]) {
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    'r' -> out.append('\r')
                    '"' -> out.append('"')
                    '\\' -> out.append('\\')
                    '/' -> out.append('/')
                    'u' -> if (cursor + 5 < json.length) {
                        val hex = json.substring(cursor + 2, cursor + 6)
                        out.append(hex.toIntOrNull(16)?.toChar() ?: '?')
                        cursor += 6
                        continue
                    } else {
                        out.append(next)
                    }
                    else -> out.append(next)
                }
                cursor += 2
                continue
            }
            if (ch == '"') return out.toString()
            out.append(ch)
            cursor++
        }
        return null
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
