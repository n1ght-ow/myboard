package dev.cheng.keyboard.ime.layout

import dev.cheng.keyboard.model.KeyKind
import dev.cheng.keyboard.model.KeyModel
import dev.cheng.keyboard.model.KeyboardPage
import dev.cheng.keyboard.model.Language

private val hints = mapOf(
    "q" to "1", "w" to "2", "e" to "3", "r" to "4", "t" to "5",
    "y" to "6", "u" to "7", "i" to "8", "o" to "9", "p" to "0",
    "a" to "~", "s" to "!", "d" to "@", "f" to "#", "g" to "%",
    "h" to "\u201C", "j" to "\u201D", "k" to "*", "l" to "?",
    "z" to "(", "x" to ")", "c" to "-", "v" to "_", "b" to ":",
    "n" to ";", "m" to "\u3001",
)

data class KeyRow(val keys: List<KeyModel>, val inset: Boolean = false)

data class PlacedKey(val model: KeyModel, val x: Float, val y: Float, val w: Float, val h: Float)

data class KeyPlacement(val keys: List<PlacedKey>, val width: Float, val height: Float)

data class PopupBox(val x: Float, val y: Float, val cell: Float, val height: Float, val gap: Float)

fun keyboardRows(page: KeyboardPage, language: Language): List<KeyRow> = when (page) {
    KeyboardPage.Letters -> letterRows(language)
    KeyboardPage.Number -> numberRows(language)
    KeyboardPage.Symbols -> symbolRows(language)
    KeyboardPage.More -> moreRows(language)
}

fun placeKeys(
    page: KeyboardPage,
    language: Language,
    widthPx: Float,
    keyHeightPx: Float,
    gapX: Float,
    gapY: Float,
    sidePx: Float,
): KeyPlacement {
    val rows = keyboardRows(page, language)
    val inner = widthPx - sidePx * 2f
    val reference = (inner - gapX * 9f) / 10f
    val letterInset = (reference + gapX) / 2f
    val placed = ArrayList<PlacedKey>()
    var y = 0f
    for (row in rows) {
        val inset = if (row.inset) letterInset else 0f
        val gaps = gapX * (row.keys.size - 1).coerceAtLeast(0)
        val available = (inner - inset * 2f - gaps).coerceAtLeast(0f)
        val weight = row.keys.sumOf { it.weight.toDouble() }.toFloat().coerceAtLeast(0.01f)
        var x = sidePx + inset
        for (key in row.keys) {
            val width = available * (key.weight / weight)
            placed += PlacedKey(key, x, y, width, keyHeightPx)
            x += width + gapX
        }
        y += keyHeightPx + gapY
    }
    val height = if (rows.isEmpty()) 0f else y - gapY
    return KeyPlacement(placed, widthPx, height)
}

fun popupBox(key: PlacedKey, boardWidth: Float, sidePx: Float, gapX: Float): PopupBox {
    val cell = key.w
    val width = cell * 3f + gapX * 2f
    val maxX = (boardWidth - sidePx - width).coerceAtLeast(sidePx)
    val x = (key.x + key.w / 2f - width / 2f).coerceIn(sidePx, maxX)
    val y = (key.y - gapX - key.h).coerceAtLeast(0f)
    return PopupBox(x, y, cell, key.h, gapX)
}

fun hitKey(keys: List<PlacedKey>, x: Float, y: Float): PlacedKey? {
    keys.firstOrNull { x >= it.x && y >= it.y && x < it.x + it.w && y < it.y + it.h }?.let { return it }
    var best: PlacedKey? = null
    var bestDistance = Float.MAX_VALUE
    for (key in keys) {
        val dx = x - (key.x + key.w / 2f)
        val dy = y - (key.y + key.h / 2f)
        val distance = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
        val limit = maxOf(key.w, key.h) * 0.65f
        if (distance <= limit && distance < bestDistance) {
            bestDistance = distance
            best = key
        }
    }
    return best
}

private fun letterRows(language: Language): List<KeyRow> {
    fun letters(text: String) = text.map { ch ->
        val label = ch.toString()
        KeyModel("l-$label", KeyKind.Letter, label, hint = hints.getValue(label))
    }
    return listOf(
        KeyRow(letters("qwertyuiop")),
        KeyRow(letters("asdfghjkl"), inset = true),
        KeyRow(
            listOf(KeyModel("shift", KeyKind.Shift, "", weight = 1.5f)) +
                letters("zxcvbnm") +
                listOf(KeyModel("backspace", KeyKind.Backspace, "", weight = 1.5f)),
        ),
        KeyRow(
            listOf(
                KeyModel("to-symbols", KeyKind.PageSymbols, "符号", weight = 1.55f),
                KeyModel("to-number", KeyKind.PageNumber, "123", weight = 1.15f),
                KeyModel("comma", KeyKind.Char, comma(language), insert = comma(language)),
                KeyModel("space", KeyKind.Space, "", weight = 4.4f),
                KeyModel("period", KeyKind.Char, period(language), insert = period(language)),
                KeyModel("lang", KeyKind.Language, "", weight = 1.35f),
                KeyModel("enter", KeyKind.Enter, "", weight = 1.75f, accent = true),
            ),
        ),
    )
}

private fun numberRows(language: Language): List<KeyRow> {
    fun chars(values: String, prefix: String) = values.mapIndexed { index, ch ->
        val text = ch.toString()
        KeyModel("$prefix-$index", KeyKind.Char, text, insert = text)
    }
    return listOf(
        KeyRow(chars("1234567890", "d")),
        KeyRow(
            listOf("@", "#", "¥", "&", "*", "-", "+", "(", ")", "/").mapIndexed { index, text ->
                KeyModel("p-$index", KeyKind.Char, text, insert = text)
            },
        ),
        KeyRow(
            listOf(KeyModel("to-symbols", KeyKind.PageSymbols, "符号", weight = 1.5f)) +
                listOf("\"", "'", ":", ";", "!", "?").mapIndexed { index, text ->
                    KeyModel("m-$index", KeyKind.Char, text, insert = text)
                } +
                listOf(KeyModel("backspace", KeyKind.Backspace, "", weight = 1.5f)),
        ),
        KeyRow(
            listOf(
                KeyModel("to-letters", KeyKind.PageLetters, "ABC", weight = 1.6f),
                KeyModel("comma", KeyKind.Char, comma(language), insert = comma(language)),
                KeyModel("space", KeyKind.Space, "", weight = 4f),
                KeyModel("period", KeyKind.Char, period(language), insert = period(language)),
                KeyModel("enter", KeyKind.Enter, "", weight = 1.8f, accent = true),
            ),
        ),
    )
}

private fun symbolRows(language: Language): List<KeyRow> {
    val chinese = language == Language.Chinese
    val first = if (chinese) {
        listOf("，", "。", "？", "！", "…", "、", "：", "；", "“", "”")
    } else {
        listOf(",", ".", "?", "!", "'", "\"", ":", ";", "(", ")")
    }
    val second = if (chinese) {
        listOf("‘", "’", "（", "）", "【", "】", "《", "》", "—", "·")
    } else {
        listOf("@", "#", "$", "%", "&", "*", "-", "+", "=", "/")
    }
    val third = if (chinese) {
        listOf("@", "#", "￥", "%", "&", "*", "~")
    } else {
        listOf("~", "`", "|", "\\", "_", "^", "<", ">")
    }
    return listOf(
        KeyRow(first.mapIndexed { index, text -> KeyModel("s1-$index", KeyKind.Char, text, insert = text) }),
        KeyRow(second.mapIndexed { index, text -> KeyModel("s2-$index", KeyKind.Char, text, insert = text) }),
        KeyRow(
            third.mapIndexed { index, text -> KeyModel("s3-$index", KeyKind.Char, text, insert = text) } +
                listOf(
                    KeyModel("more", KeyKind.PageMore, "更多"),
                    KeyModel("backspace", KeyKind.Backspace, "", weight = 1.3f),
                ),
        ),
        KeyRow(
            listOf(
                KeyModel("to-letters", KeyKind.PageLetters, "返回", weight = 1.5f),
                KeyModel("comma", KeyKind.Char, comma(language), insert = comma(language)),
                KeyModel("space", KeyKind.Space, "", weight = 4f),
                KeyModel("period", KeyKind.Char, period(language), insert = period(language)),
                KeyModel("enter", KeyKind.Enter, "", weight = 1.7f, accent = true),
            ),
        ),
    )
}

private fun moreRows(language: Language): List<KeyRow> {
    val rows = listOf(
        listOf("·", "～", "`", "|", "\\", "/", "<", ">", "^", "°"),
        listOf("+", "=", "{", "}", "[", "]", "«", "»", "￥", "€"),
        listOf("_", "#", "&", "℃", "×", "÷", "±", "≠", "≈"),
    )
    return listOf(
        KeyRow(rows[0].mapIndexed { index, text -> KeyModel("e1-$index", KeyKind.Char, text, insert = text) }),
        KeyRow(rows[1].mapIndexed { index, text -> KeyModel("e2-$index", KeyKind.Char, text, insert = text) }),
        KeyRow(
            rows[2].mapIndexed { index, text -> KeyModel("e3-$index", KeyKind.Char, text, insert = text) } +
                KeyModel("backspace", KeyKind.Backspace, "", weight = 1.3f),
        ),
        KeyRow(
            listOf(
                KeyModel("to-letters", KeyKind.PageLetters, "返回", weight = 1.5f),
                KeyModel("comma", KeyKind.Char, comma(language), insert = comma(language)),
                KeyModel("space", KeyKind.Space, "", weight = 4f),
                KeyModel("period", KeyKind.Char, period(language), insert = period(language)),
                KeyModel("enter", KeyKind.Enter, "", weight = 1.7f, accent = true),
            ),
        ),
    )
}

fun comma(language: Language) = if (language == Language.Chinese) "，" else ","

fun period(language: Language) = if (language == Language.Chinese) "。" else "."
