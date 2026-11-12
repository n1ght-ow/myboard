package dev.cheng.keyboard.ime.layout

import dev.cheng.keyboard.ime.touch.TouchCalibrator
import dev.cheng.keyboard.model.KeyKind
import dev.cheng.keyboard.model.KeyLikelihood
import dev.cheng.keyboard.model.KeyModel
import dev.cheng.keyboard.model.KeyboardPage
import dev.cheng.keyboard.model.Language
import dev.cheng.keyboard.model.TouchBias
import dev.cheng.keyboard.model.TouchSample
import kotlin.math.abs

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

val NoPriors: List<Float> = List(26) { 0f }

data class HitContext(
    val priors: List<Float> = NoPriors,
    val composing: Boolean = false,
    val hasCandidates: Boolean = false,
    val password: Boolean = false,
    val noLearn: Boolean = false,
    val bias: TouchBias = TouchBias(),
    val boardWidthPx: Float = 0f,
    val refKeyWidthPx: Float = 0f,
    val refKeyHeightPx: Float = 0f,
)

val HitContext.effectivePriors: List<Float>
    get() = if (password || noLearn) {
        NoPriors
    } else {
        List(26) { index -> priors.getOrElse(index) { 0f }.coerceIn(-0.35f, 1.0f) }
    }

private const val CoreInsetX = 0.22f
private const val CoreInsetY = 0.20f
private const val PriorLambda = 2.0f
private const val MaxNormalizedDistance = 2.5f
private const val GaussianSigmaX = 0.42f
private const val GaussianSigmaY = 0.45f
private const val EnergyRange = 3.2f
private const val TouchPenaltyCutoff = 0.85f
private const val CoreBoxEpsilon = 1e-3f

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
    return hitKey(keys, x, y, HitContext())
}

fun hitKey(keys: List<PlacedKey>, x: Float, y: Float, context: HitContext): PlacedKey? {
    if (keys.isEmpty()) return null
    val (xCorr, yCorr) = correctTouchPoint(keys, x, y, context)

    var coreBest: PlacedKey? = null
    var coreBestDistSq = Float.POSITIVE_INFINITY
    for (key in keys) {
        val left = key.x + CoreInsetX * key.w - CoreBoxEpsilon
        val right = key.x + (1f - CoreInsetX) * key.w + CoreBoxEpsilon
        val top = key.y + CoreInsetY * key.h - CoreBoxEpsilon
        val bottom = key.y + (1f - CoreInsetY) * key.h + CoreBoxEpsilon
        if (xCorr in left..right && yCorr in top..bottom) {
            val cx = key.x + key.w * 0.5f
            val cy = key.y + key.h * 0.5f
            val dx = xCorr - cx
            val dy = yCorr - cy
            val distSq = dx * dx + dy * dy
            if (distSq < coreBestDistSq) {
                coreBestDistSq = distSq
                coreBest = key
            }
        }
    }
    if (coreBest != null) return coreBest

    var best: PlacedKey? = null
    var bestScore = Float.POSITIVE_INFINITY
    for (key in keys) {
        val halfW = (key.w * 0.5f).coerceAtLeast(0.5f)
        val halfH = (key.h * 0.5f).coerceAtLeast(0.5f)
        val cx = key.x + key.w * 0.5f
        val cy = key.y + key.h * 0.5f
        val nx = (xCorr - cx) / halfW
        val ny = (yCorr - cy) / halfH
        if (abs(nx) <= MaxNormalizedDistance && abs(ny) <= MaxNormalizedDistance) {
            val score = nx * nx + ny * ny - PriorLambda * priorOf(key.model, context)
            if (score < bestScore) {
                bestScore = score
                best = key
            }
        }
    }
    if (best != null) return best

    var nearest = keys[0]
    var nearestDistSq = Float.POSITIVE_INFINITY
    for (key in keys) {
        val cx = key.x + key.w * 0.5f
        val cy = key.y + key.h * 0.5f
        val dx = xCorr - cx
        val dy = yCorr - cy
        val distSq = dx * dx + dy * dy
        if (distSq < nearestDistSq) {
            nearestDistSq = distSq
            nearest = key
        }
    }
    return nearest
}

fun touchSampleOf(
    keys: List<PlacedKey>,
    x: Float,
    y: Float,
    primary: KeyModel,
    context: HitContext = HitContext(),
): TouchSample? {
    if (primary.kind != KeyKind.Letter || keys.isEmpty()) return null
    val primaryPlaced = keys.firstOrNull { it.model.id == primary.id } ?: return null
    val primaryChar = primary.label.firstOrNull()?.lowercaseChar()?.takeIf { it in 'a'..'z' } ?: return null
    val boardWidth = resolveBoardWidth(keys, context)
    val (px, py) = correctTouchPoint(keys, x, y, context)
    val sx = (GaussianSigmaX * primaryPlaced.w).coerceAtLeast(1f)
    val sy = (GaussianSigmaY * primaryPlaced.h).coerceAtLeast(1f)
    val primaryCx = primaryPlaced.x + primaryPlaced.w * 0.5f
    val primaryCy = primaryPlaced.y + primaryPlaced.h * 0.5f
    val pDx = (px - primaryCx) / sx
    val pDy = (py - primaryCy) / sy
    val primaryEnergy = 0.5f * (pDx * pDx + pDy * pDy)

    val candidates = ArrayList<KeyLikelihood>(6)
    for (key in keys) {
        if (key.model.kind != KeyKind.Letter || key.model.id == primary.id) continue
        val ch = key.model.label.firstOrNull()?.lowercaseChar()?.takeIf { it in 'a'..'z' } ?: continue
        val cx = key.x + key.w * 0.5f
        val cy = key.y + key.h * 0.5f
        val dx = (px - cx) / sx
        val dy = (py - cy) / sy
        val energy = 0.5f * (dx * dx + dy * dy)
        val penalty = ((energy - primaryEnergy) / EnergyRange).coerceIn(0f, 1f)
        if (penalty <= TouchPenaltyCutoff) {
            candidates += KeyLikelihood(ch, penalty)
        }
    }
    candidates.sortBy { it.penalty }
    val residualX = (x - primaryCx) / primaryPlaced.w.coerceAtLeast(1f)
    val residualY = (y - primaryCy) / primaryPlaced.h.coerceAtLeast(1f)
    return TouchSample(
        primary = primaryChar,
        alternatives = candidates.take(4),
        residualX = residualX,
        residualY = residualY,
        leftSide = x < boardWidth * 0.5f,
    )
}

fun nearbyLetters(keys: List<PlacedKey>, x: Float, y: Float, primary: KeyModel): String =
    touchSampleOf(keys, x, y, primary)
        ?.alternatives
        ?.sortedBy { it.penalty }
        ?.take(3)
        ?.joinToString("") { it.ch.toString() }
        .orEmpty()

internal fun priorOf(key: KeyModel, context: HitContext): Float {
    if (context.password || context.noLearn) return 0f
    return when {
        key.kind == KeyKind.Letter -> {
            val ch = key.label.firstOrNull()?.lowercaseChar() ?: return 0f
            if (ch !in 'a'..'z') 0f else context.priors.getOrElse(ch - 'a') { 0f }.coerceIn(-0.35f, 1.0f)
        }
        key.kind == KeyKind.Backspace && context.composing -> 1.20f.coerceIn(-0.60f, 1.4f)
        key.kind == KeyKind.Space && context.hasCandidates -> 0.90f.coerceIn(-0.60f, 1.4f)
        key.kind == KeyKind.Shift && context.composing -> -0.50f
        (key.id == "to-symbols" || key.id == "to-number" || key.id == "lang") && context.composing -> -0.60f
        else -> 0f
    }
}

private fun resolveBoardWidth(keys: List<PlacedKey>, context: HitContext): Float =
    if (context.boardWidthPx > 0f) {
        context.boardWidthPx
    } else {
        val maxRight = keys.maxOfOrNull { it.x + it.w } ?: 0f
        val minLeft = (keys.minOfOrNull { it.x } ?: 0f).coerceAtLeast(0f)
        maxRight + minLeft
    }

private fun correctTouchPoint(
    keys: List<PlacedKey>,
    x: Float,
    y: Float,
    context: HitContext,
): Pair<Float, Float> {
    val boardWidth = resolveBoardWidth(keys, context).coerceAtLeast(0f)
    val boardHeight = (keys.maxOfOrNull { it.y + it.h } ?: 0f).coerceAtLeast(0f)
    val clampedX = x.coerceIn(0f, boardWidth)
    val clampedY = y.coerceIn(0f, boardHeight)
    val isPrivate = context.password || context.noLearn
    val refW = if (context.refKeyWidthPx > 0f) {
        context.refKeyWidthPx
    } else {
        keys.firstOrNull { it.model.kind == KeyKind.Letter }?.w ?: keys.first().w
    }
    val refH = if (context.refKeyHeightPx > 0f) context.refKeyHeightPx else keys.first().h
    val leftDx = if (isPrivate) 0f else context.bias.leftDxRatio
    val rightDx = if (isPrivate) 0f else context.bias.rightDxRatio
    val dyRatio = if (isPrivate) {
        if (context.refKeyHeightPx > 0f || context.bias.dyRatio != 0f) TouchCalibrator.BASE_DY else 0f
    } else {
        context.bias.dyRatio
    }
    val dxRatio = if (clampedX < boardWidth * 0.5f) leftDx else rightDx
    return (clampedX - dxRatio * refW) to (clampedY - dyRatio * refH)
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
                KeyModel("comma", KeyKind.Char, ",", insert = ","),
                KeyModel("space", KeyKind.Space, "", weight = 4f),
                KeyModel("period", KeyKind.Char, ".", insert = "."),
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
