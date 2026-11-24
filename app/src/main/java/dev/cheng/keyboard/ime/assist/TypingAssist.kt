package dev.cheng.keyboard.ime.assist

import java.time.LocalDateTime

enum class TypingAssistKind {
    Email,
    Url,
    DateTime,
    Calculator,
}

data class TypingSuggestion(
    val id: String,
    val label: String,
    val replacement: String,
    val replaceBefore: Int,
    val replaceAfter: Int = 0,
    val expectedBefore: String,
    val expectedAfter: String = "",
    val kind: TypingAssistKind,
)

data class TypingAssistContext(
    val before: String,
    val after: String = "",
    val composing: String = "",
    val emailField: Boolean = false,
    val uriField: Boolean = false,
    val password: Boolean = false,
    val noLearn: Boolean = false,
    val numeric: Boolean = false,
    val hasSelection: Boolean = false,
)

private val emailDomains = listOf(
    "qq.com",
    "163.com",
    "126.com",
    "gmail.com",
    "outlook.com",
    "hotmail.com",
    "icloud.com",
    "foxmail.com",
    "yeah.net",
)

private val urlSuffixes = listOf(
    ".com",
    ".cn",
    ".net",
    ".org",
)

private val explicitUrlPrefix = Regex("(?i)https?://|www\\.")
private val datePattern = Regex("""^\d{4}([-/.])\d{1,2}\1\d{1,2}$""")
private val shortDatePattern = Regex("""^\d{1,2}[-/.]\d{1,2}[-/.]\d{2,4}$""")
private val phonePatterns = listOf(
    Regex("""^\d{3,4}-\d{3,4}-\d{4}$"""),
    Regex("""^0\d{2,3}-\d{7,8}$"""),
    Regex("""^\+86-1[3-9]\d(?:-?\d{4}){2}$"""),
)

private data class DateTimeTrigger(
    val token: String,
    val kind: String,
)

private val dateTimeTriggers = listOf(
    DateTimeTrigger("/date", "date"),
    DateTimeTrigger("/time", "time"),
    DateTimeTrigger("/weekday", "weekday"),
    DateTimeTrigger("日期", "date"),
    DateTimeTrigger("时间", "time"),
    DateTimeTrigger("星期", "weekday"),
    DateTimeTrigger("rq", "date"),
    DateTimeTrigger("sj", "time"),
    DateTimeTrigger("xq", "weekday"),
)

fun typingSuggestions(
    context: TypingAssistContext,
    now: LocalDateTime = LocalDateTime.now(),
): List<TypingSuggestion> {
    if (context.password || context.noLearn || context.hasSelection) return emptyList()
    if (!cursorEndsToken(context.after)) return emptyList()
    if (
        context.composing.isNotEmpty() &&
        !context.before.endsWith(context.composing, ignoreCase = true)
    ) {
        return emptyList()
    }

    val calculator = calculatorSuggestions(context)
    if (calculator.isNotEmpty()) return calculator
    if (context.numeric) return emptyList()

    if (context.emailField) return emailSuggestions(context)
    if (context.uriField) return urlSuggestions(context)

    return buildList {
        addAll(emailSuggestions(context))
        addAll(urlSuggestions(context))
        addAll(dateTimeSuggestions(context, now))
    }
}

private fun emailSuggestions(context: TypingAssistContext): List<TypingSuggestion> {
    val before = context.before
    val at = before.lastIndexOf('@')
    if (at < 0) return emptyList()

    var localStart = at
    while (localStart > 0 && isEmailLocalChar(before[localStart - 1])) {
        localStart--
    }

    val local = before.substring(localStart, at)
    if (!isClearEmailLocalPart(local)) return emptyList()
    if (localStart > 0 && before[localStart - 1] in "/:?&#=@") return emptyList()

    val domainPrefix = before.substring(at + 1)
    if (!isSafeEmailDomainPrefix(domainPrefix)) return emptyList()

    return emailDomains.mapIndexedNotNull { index, domain ->
        if (!domain.startsWith(domainPrefix, ignoreCase = true)) return@mapIndexedNotNull null
        if (domain.equals(domainPrefix, ignoreCase = true)) return@mapIndexedNotNull null

        TypingSuggestion(
            id = "email:$index:$domain",
            label = "@$domain",
            replacement = domain,
            replaceBefore = domainPrefix.length,
            expectedBefore = before,
            expectedAfter = context.after,
            kind = TypingAssistKind.Email,
        )
    }
}

private fun isEmailLocalChar(ch: Char): Boolean =
    ch in 'a'..'z' ||
        ch in 'A'..'Z' ||
        ch in '0'..'9' ||
        ch == '.' ||
        ch == '_' ||
        ch == '+' ||
        ch == '-' ||
        ch == '%'

private fun isClearEmailLocalPart(local: String): Boolean {
    if (local.isEmpty() || local.length > 64) return false
    if (local.first() == '.' || local.last() == '.') return false
    if (".." in local) return false
    return local.all(::isEmailLocalChar)
}

private fun isSafeEmailDomainPrefix(domain: String): Boolean {
    if (domain.length > 255) return false
    if (domain.isEmpty()) return true
    if (domain.first() == '.' || domain.first() == '-') return false
    if (".." in domain) return false
    if (domain.any { !it.isAsciiLetterOrDigit() && it != '.' && it != '-' }) return false

    val completeLabels = domain.removeSuffix(".").split('.')
    return completeLabels.all {
        it.isEmpty() || (it.first() != '-' && it.last() != '-')
    }
}

private fun urlSuggestions(context: TypingAssistContext): List<TypingSuggestion> {
    val before = context.before
    val token = urlToken(before, context.uriField) ?: return emptyList()
    val authority = when {
        token.startsWith("https://", ignoreCase = true) -> token.substring(8)
        token.startsWith("http://", ignoreCase = true) -> token.substring(7)
        else -> token
    }

    if (authority.isEmpty() || authority.length > 255) return emptyList()
    if (authority.any { it.isWhitespace() || it in "@/?#'[]\\" }) return emptyList()
    if (authority.contains(':')) return emptyList()
    if (!isSafeUrlHostPrefix(authority)) return emptyList()

    val workingHost = if (authority.startsWith("www.", ignoreCase = true)) {
        authority.substring(4)
    } else {
        authority
    }
    if (workingHost.isEmpty() || looksLikeIpv4(workingHost)) return emptyList()

    if (workingHost.endsWith('.')) {
        val completedHost = workingHost.dropLast(1)
        val completedLabel = completedHost.substringAfterLast('.')
        if (
            '.' in completedHost ||
            urlSuffixes.any { it.substring(1).equals(completedLabel, ignoreCase = true) }
        ) {
            return emptyList()
        }

        return urlSuffixes.mapIndexed { index, suffix ->
            TypingSuggestion(
                id = "url:trailing-dot:$index:$suffix",
                label = suffix,
                replacement = suffix,
                replaceBefore = 1,
                expectedBefore = before,
                expectedAfter = context.after,
                kind = TypingAssistKind.Url,
            )
        }
    }

    val lastDot = workingHost.lastIndexOf('.')
    if (lastDot < 0) {
        return urlSuffixes.mapIndexed { index, suffix ->
            TypingSuggestion(
                id = "url:insert:$index:$suffix",
                label = suffix,
                replacement = suffix,
                replaceBefore = 0,
                expectedBefore = before,
                expectedAfter = context.after,
                kind = TypingAssistKind.Url,
            )
        }
    }

    val typedSuffix = workingHost.substring(lastDot + 1)
    if (typedSuffix.isEmpty() || typedSuffix.any { !it.isAsciiLetter() }) return emptyList()

    return urlSuffixes.mapIndexedNotNull { index, suffix ->
        val suffixTail = suffix.substring(1)
        if (!suffixTail.startsWith(typedSuffix, ignoreCase = true)) {
            return@mapIndexedNotNull null
        }
        if (suffixTail.equals(typedSuffix, ignoreCase = true)) {
            return@mapIndexedNotNull null
        }

        TypingSuggestion(
            id = "url:suffix:$index:$suffix",
            label = suffix,
            replacement = suffix,
            replaceBefore = typedSuffix.length + 1,
            expectedBefore = before,
            expectedAfter = context.after,
            kind = TypingAssistKind.Url,
        )
    }
}

private fun urlToken(before: String, uriField: Boolean): String? {
    if (before.isEmpty()) return null
    if (uriField) {
        if (before.any(Char::isWhitespace)) return null
        return before
    }

    val matches = explicitUrlPrefix.findAll(before).filter {
        it.range.first == 0 || before[it.range.first - 1].isExplicitUrlPrefixBoundary()
    }.toList()
    val match = matches.lastOrNull() ?: return null
    var tokenStart = match.range.first
    while (tokenStart > 0 && before[tokenStart - 1].isUriTokenChar()) tokenStart--
    if (matches.any { it.range.first in tokenStart until match.range.first }) return null
    return before.substring(match.range.first)
}

private fun isSafeUrlHostPrefix(host: String): Boolean {
    if (host.startsWith('.') || host.startsWith('-') || host.endsWith('-')) return false
    if (".." in host) return false
    val labels = host.removeSuffix(".").split('.')
    return labels.all {
        it.isEmpty() || (
            it.first() != '-' &&
                it.last() != '-' &&
                it.all { ch -> ch.isAsciiLetterOrDigit() || ch == '-' }
            )
    }
}

private fun looksLikeIpv4(host: String): Boolean {
    val labels = host.removeSuffix(".").split('.')
    return labels.size >= 2 && labels.all { label ->
        label.isNotEmpty() && label.all { it in '0'..'9' }
    }
}

private fun dateTimeSuggestions(
    context: TypingAssistContext,
    now: LocalDateTime,
): List<TypingSuggestion> {
    val trigger = dateTimeTriggers.firstOrNull {
        context.before.endsWith(it.token, ignoreCase = true)
    } ?: return emptyList()

    val start = context.before.length - trigger.token.length
    if (start > 0 && !context.before[start - 1].isStandaloneTokenChar()) return emptyList()

    val (labelPrefix, replacement) = when (trigger.kind) {
        "date" -> "日期 " to formatDate(now)
        "time" -> "时间 " to formatTime(now)
        else -> "星期 " to formatWeekday(now)
    }

    return listOf(
        TypingSuggestion(
            id = "datetime:${trigger.kind}:${trigger.token}",
            label = labelPrefix + replacement,
            replacement = replacement,
            replaceBefore = trigger.token.length,
            expectedBefore = context.before,
            expectedAfter = context.after,
            kind = TypingAssistKind.DateTime,
        ),
    )
}

private fun formatDate(now: LocalDateTime): String =
    buildString {
        append(now.year)
        append('-')
        append(twoDigits(now.monthValue))
        append('-')
        append(twoDigits(now.dayOfMonth))
    }

private fun formatTime(now: LocalDateTime): String =
    "${twoDigits(now.hour)}:${twoDigits(now.minute)}"

private fun formatWeekday(now: LocalDateTime): String = when (now.dayOfWeek.value) {
    1 -> "星期一"
    2 -> "星期二"
    3 -> "星期三"
    4 -> "星期四"
    5 -> "星期五"
    6 -> "星期六"
    else -> "星期日"
}

private fun twoDigits(value: Int): String = value.toString().padStart(2, '0')

private fun calculatorSuggestions(context: TypingAssistContext): List<TypingSuggestion> {
    val before = context.before
    if (!before.endsWith('=')) return emptyList()

    var start = before.lastIndex
    while (start > 0 && isExpressionChar(before[start - 1])) {
        start--
    }
    while (start < before.lastIndex && before[start].isExpressionSpacing()) {
        start++
    }

    val expression = before.substring(start)
    if (expression.count { it == '=' } != 1) return emptyList()
    if (start > 0 && !isExpressionBoundary(before[start - 1])) return emptyList()

    val body = expression.dropLast(1).trim(' ', '\t')
    if (body.isEmpty() || body.none { it in '0'..'9' }) return emptyList()
    if (body.any { !isExpressionChar(it) }) return emptyList()
    if (!hasBalancedParentheses(body)) return emptyList()
    if (looksLikeDateOrPhone(body)) return emptyList()

    val result = calculateInline(body)?.trim().orEmpty()
    if (result.isEmpty() || result.length > 256) return emptyList()
    if (result.any { it == '\n' || it == '\r' }) return emptyList()

    return listOf(
        TypingSuggestion(
            id = "calculator:${expression.hashCode()}:${expression.length}",
            label = result,
            replacement = result,
            replaceBefore = expression.length,
            expectedBefore = before,
            expectedAfter = context.after,
            kind = TypingAssistKind.Calculator,
        ),
    )
}

private fun isExpressionChar(ch: Char): Boolean =
    ch.isExpressionSpacing() ||
        ch in '0'..'9' ||
        ch in "+-*/×÷.()"

private fun Char.isExpressionSpacing(): Boolean =
    this == ' ' || this == '\t'

private fun isExpressionBoundary(ch: Char): Boolean =
    !(
        ch.isLetterOrDigit() ||
            ch == '_' ||
            ch == '.' ||
            ch == '@' ||
            ch == ':' ||
            ch == '/' ||
            ch == '?' ||
            ch == '#' ||
            ch == '&' ||
            ch == '='
        )

private fun hasBalancedParentheses(expression: String): Boolean {
    var depth = 0
    for (ch in expression) {
        when (ch) {
            '(' -> depth++
            ')' -> {
                depth--
                if (depth < 0) return false
            }
        }
    }
    return depth == 0
}

private fun looksLikeDateOrPhone(expression: String): Boolean {
    val compact = expression.filterNot(Char::isWhitespace)
    if (
        datePattern.matches(compact) ||
        shortDatePattern.matches(compact)
    ) {
        return true
    }

    return phonePatterns.any { it.matches(compact) }
}

private fun cursorEndsToken(after: String): Boolean =
    after.isEmpty() || !after.first().isTokenContinuation()

private fun Char.isTokenContinuation(): Boolean =
    isLetterOrDigit() ||
        this == '_' ||
        this == '.' ||
        this == '-' ||
        this == '@' ||
        this == '/' ||
        this == '?' ||
        this == '#' ||
        this == '&' ||
        this == '=' ||
        this in "+*×÷%^"

private fun Char.isExplicitUrlPrefixBoundary(): Boolean =
    !(
        isAsciiLetterOrDigit() ||
            this == '_' ||
            this == '-' ||
            this == '.' ||
            this == '@' ||
            this == ':' ||
            this == '/' ||
            this == '\\' ||
            this == '+' ||
            this == '%' ||
            this == '?' ||
            this == '#' ||
            this == '&' ||
            this == '='
        )

private fun Char.isUriTokenChar(): Boolean =
    isAsciiLetterOrDigit() ||
        this in "-._~:/?#[]@!$&'()*+,;=%"

private fun Char.isStandaloneTokenChar(): Boolean =
    isWhitespace() ||
        this in "，。！？；：、（）【】《》「」『』〈〉〔〕“”‘’…—～·"

private fun Char.isAsciiLetterOrDigit(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

private fun Char.isAsciiLetter(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z'
