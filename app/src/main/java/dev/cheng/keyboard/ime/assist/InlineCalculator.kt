package dev.cheng.keyboard.ime.assist

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

private const val MAX_INPUT_LENGTH = 80
private const val MAX_PARENTHESIS_DEPTH = 12
private const val MAX_LITERAL_DIGITS = 16
private const val MAX_BINARY_OPERATIONS = 40
private const val MAX_WORKING_SCALE = MAX_BINARY_OPERATIONS * MAX_LITERAL_DIGITS
private const val MAX_RESULT_INTEGER_DIGITS = 24
private const val MAX_WORKING_PRECISION = 34
private const val MAX_EXACT_PRECISION = MAX_RESULT_INTEGER_DIGITS + MAX_WORKING_SCALE
private const val DISPLAY_SCALE = 10

private val workingContext = MathContext(MAX_WORKING_PRECISION, RoundingMode.HALF_UP)

/**
 * Calculates a finite, bounded arithmetic expression for an inline suggestion.
 *
 * The expression must contain at least one binary operation and uses [BigDecimal]
 * arithmetic, so appending `=` or evaluating the result is left to the caller.
 */
fun calculateInline(expression: String): String? {
    if (expression.length > MAX_INPUT_LENGTH || expression.isBlank()) return null
    if (expression.any { !isSupportedCharacter(it) }) return null

    return try {
        InlineExpressionParser(expression).parse()
    } catch (_: ArithmeticException) {
        null
    } catch (_: NumberFormatException) {
        null
    }
}

private fun isSupportedCharacter(character: Char): Boolean =
    character in '0'..'9' ||
        character == '.' ||
        character == '+' ||
        character == '-' ||
        character == '*' ||
        character == '/' ||
        character == '×' ||
        character == '÷' ||
        character == '%' ||
        character == '(' ||
        character == ')' ||
        character.isWhitespace()

private data class ParsedValue(
    val value: BigDecimal,
    val directPercent: Boolean = false,
)

private val HUNDRED = BigDecimal(100)

private class InlineExpressionParser(
    private val source: String,
) {
    private var index = 0
    private var parenthesisDepth = 0
    private var operationCount = 0

    fun parse(): String? {
        val parsed = parseExpression() ?: return null
        skipWhitespace()
        if (index != source.length || operationCount == 0) return null
        return render(parsed.value)
    }

    private fun parseExpression(): ParsedValue? {
        var left = parseTerm() ?: return null

        while (true) {
            skipWhitespace()
            val operator = peek()
            if (operator != '+' && operator != '-') return left

            index++
            skipWhitespace()
            if (peek() == operator) return null

            val right = parseTerm() ?: return null
            if (++operationCount > MAX_BINARY_OPERATIONS) return null
            val effectiveRight = if (right.directPercent && !left.directPercent) {
                multiplyBounded(left.value, right.value) ?: return null
            } else {
                right.value
            }
            val next = when (operator) {
                '+' -> addBounded(left.value, effectiveRight)
                else -> subtractBounded(left.value, effectiveRight)
            } ?: return null
            left = ParsedValue(next, directPercent = left.directPercent && right.directPercent)
        }
    }

    private fun parseTerm(): ParsedValue? {
        var left = parseUnary() ?: return null

        while (true) {
            skipWhitespace()
            val operator = peek()
            if (operator != '*' && operator != '×' && operator != '/' && operator != '÷') {
                return left
            }

            index++
            val right = parseUnary() ?: return null
            if (++operationCount > MAX_BINARY_OPERATIONS) return null
            val next = when (operator) {
                '*', '×' -> multiplyBounded(left.value, right.value)
                else -> divideBounded(left.value, right.value)
            } ?: return null
            left = ParsedValue(next, directPercent = false)
        }
    }

    private fun parseUnary(): ParsedValue? {
        skipWhitespace()
        val sign = when (peek()) {
            '+', '-' -> source[index++]
            else -> null
        }
        skipWhitespace()
        val parsed = parsePostfix() ?: return null
        val signed = if (sign == '-') parsed.value.negate() else parsed.value
        return ParsedValue(signed, directPercent = parsed.directPercent)
    }

    private fun parsePostfix(): ParsedValue? {
        val value = parsePrimary() ?: return null
        skipWhitespace()
        if (peek() != '%') return ParsedValue(value, directPercent = false)
        index++
        skipWhitespace()
        if (peek() == '%') return null
        if (++operationCount > MAX_BINARY_OPERATIONS) return null
        val percent = divideBounded(value, HUNDRED) ?: return null
        return ParsedValue(percent, directPercent = true)
    }

    private fun parsePrimary(): BigDecimal? {
        skipWhitespace()
        if (peek() != '(') return parseNumber()

        index++
        parenthesisDepth++
        if (parenthesisDepth > MAX_PARENTHESIS_DEPTH) return null

        val parsed = parseExpression() ?: return null
        skipWhitespace()
        if (peek() != ')') return null
        index++
        parenthesisDepth--
        return parsed.value
    }

    private fun parseNumber(): BigDecimal? {
        skipWhitespace()
        val start = index
        var digitCount = 0
        var hasDecimalPoint = false

        while (index < source.length) {
            val character = source[index]
            when {
                character in '0'..'9' -> {
                    digitCount++
                    if (digitCount > MAX_LITERAL_DIGITS) return null
                    index++
                }

                character == '.' && !hasDecimalPoint -> {
                    hasDecimalPoint = true
                    index++
                }

                else -> break
            }
        }

        if (digitCount == 0) return null
        var literal = source.substring(start, index)
        if (literal.startsWith('.')) literal = "0$literal"
        if (literal.endsWith('.')) literal = "${literal}0"
        return BigDecimal(literal)
    }

    private fun skipWhitespace() {
        while (index < source.length && source[index].isWhitespace()) index++
    }

    private fun peek(): Char? = source.getOrNull(index)

    private fun addBounded(left: BigDecimal, right: BigDecimal): BigDecimal? =
        bound(left.add(right))

    private fun subtractBounded(left: BigDecimal, right: BigDecimal): BigDecimal? =
        bound(left.subtract(right))

    private fun multiplyBounded(left: BigDecimal, right: BigDecimal): BigDecimal? =
        bound(left.multiply(right))

    private fun divideBounded(left: BigDecimal, right: BigDecimal): BigDecimal? {
        if (right.signum() == 0) return null
        val quotient = try {
            left.divide(right)
        } catch (_: ArithmeticException) {
            left.divide(right, workingContext)
        }
        return bound(quotient)
    }

    private fun bound(value: BigDecimal): BigDecimal? {
        if (value.precision() > MAX_EXACT_PRECISION) return null
        if (value.scale() > MAX_WORKING_SCALE) return null
        if (integerDigitCount(value) > MAX_RESULT_INTEGER_DIGITS) return null
        return value
    }

    private fun render(value: BigDecimal): String? {
        val displayValue = value.setScale(DISPLAY_SCALE, RoundingMode.HALF_UP)
        if (displayValue.precision() > MAX_WORKING_PRECISION) return null
        if (integerDigitCount(displayValue) > MAX_RESULT_INTEGER_DIGITS) return null
        if (value.signum() != 0 && displayValue.signum() == 0) return null
        if (displayValue.signum() == 0) return "0"
        return displayValue.stripTrailingZeros().toPlainString()
    }

    private fun integerDigitCount(value: BigDecimal): Int =
        (value.precision() - value.scale()).coerceAtLeast(0)
}
