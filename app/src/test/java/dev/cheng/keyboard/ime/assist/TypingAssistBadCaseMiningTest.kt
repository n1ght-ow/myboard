package dev.cheng.keyboard.ime.assist

import dev.cheng.keyboard.ime.EditorTextSpan
import dev.cheng.keyboard.ime.TypingEditorSnapshot
import dev.cheng.keyboard.ime.typingEditorSnapshot
import dev.cheng.keyboard.ime.typingReplacementRange
import java.time.LocalDateTime
import java.util.Random
import org.junit.Assert.assertTrue
import org.junit.Test

private const val MINING_SEED = 20_261_117L
private const val MINIMUM_GENERATED_SCENARIOS = 5_000

private val miningNow = LocalDateTime.of(2026, 11, 17, 15, 4, 5)
private val commonEmailDomains = listOf(
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
private val emailUsers = listOf(
    "alice",
    "a.b",
    "mail+tag",
    "user_42",
    "first-last",
    "team%",
    "test.2026",
)
private val safeFollowingTexts = listOf(
    "",
    " ",
    "，下一段",
    "。",
    ")",
    "]",
    "！",
    "😀继续",
)
private val unicodeNoise = listOf(
    "用户",
    "é",
    "😀",
    "🙂",
    "！",
    "，",
    "。",
    "【】",
    "—",
)

class TypingAssistBadCaseMiningTest {
    @Test
    fun deterministicMiningCoversCorpusSafetyAndReplacementProperties() {
        val random = Random(MINING_SEED)
        val miner = TypingAssistMiner(miningNow)

        mineEveryCommonDomainPrefix(miner)
        mineRandomEmailCompletions(random, miner)
        mineSafetySuppressions(random, miner)
        mineDateAndUrlRanges(random, miner)
        mineBadContexts(miner)
        mineIntegerAndDecimalCalculations(random, miner)
        mineInputBounds(miner)

        miner.requireAtLeast(MINIMUM_GENERATED_SCENARIOS)
        miner.assertNoFailures()
    }

    private fun mineEveryCommonDomainPrefix(miner: TypingAssistMiner) {
        val username = "alice+q.test"
        for ((domainIndex, domain) in commonEmailDomains.withIndex()) {
            for (prefixLength in 0 until domain.length) {
                val prefix = domain.substring(0, prefixLength)
                for (uppercase in listOf(false, true)) {
                    val typedPrefix = if (uppercase) prefix.uppercase() else prefix
                    val context = TypingAssistContext(
                        before = "$username@$typedPrefix",
                        after = safeFollowingTexts[(domainIndex + prefixLength) % safeFollowingTexts.size],
                        emailField = true,
                    )
                    miner.expectEmailCompletion(context, domain, "common-domain-prefix")
                }
            }
            for (following in safeFollowingTexts) {
                miner.expectEmailCompletion(
                    TypingAssistContext(
                        before = "$username@${domain.substring(0, domain.length / 2)}",
                        after = following,
                        emailField = true,
                    ),
                    domain,
                    "common-domain-following-text",
                )
            }
        }
    }

    private fun mineRandomEmailCompletions(
        random: Random,
        miner: TypingAssistMiner,
    ) {
        miner.expectEmailCompletion(
            context = TypingAssistContext(
                before = "联系12345@",
                emailField = true,
            ),
            domain = "qq.com",
            category = "numeric-qq-local-part",
        )

        repeat(1_000) { index ->
            val domain = commonEmailDomains[random.nextInt(commonEmailDomains.size)]
            val prefixLength = random.nextInt(domain.length)
            val typedPrefix = domain.substring(0, prefixLength)
                .map { character ->
                    if (random.nextBoolean()) character.uppercaseChar() else character
                }
                .joinToString("")
            val username = emailUsers[random.nextInt(emailUsers.size)] + index
            val context = TypingAssistContext(
                before = "$username@$typedPrefix",
                after = safeFollowingTexts[random.nextInt(safeFollowingTexts.size)],
                emailField = true,
            )
            miner.expectEmailCompletion(context, domain, "random-email-prefix")
        }
    }

    private fun mineSafetySuppressions(
        random: Random,
        miner: TypingAssistMiner,
    ) {
        repeat(250) { index ->
            val suffix = random.nextInt(1_000)
            val baseContexts = listOf(
                TypingAssistContext(
                    before = "user$index$suffix@g",
                    after = safeFollowingTexts[index % safeFollowingTexts.size],
                    emailField = true,
                ),
                TypingAssistContext(
                    before = "https://host$index$suffix.c",
                    after = safeFollowingTexts[index % safeFollowingTexts.size],
                    uriField = true,
                ),
                TypingAssistContext(
                    before = "第$index${suffix}项 rq",
                    after = safeFollowingTexts[index % safeFollowingTexts.size],
                ),
                TypingAssistContext(
                    before = "${index + suffix}+2=",
                    after = if (index % 2 == 0) "" else " 元",
                    numeric = index % 2 == 0,
                ),
            )

            for (context in baseContexts) {
                miner.expectEmpty(context.copy(password = true), "password-suppression")
                miner.expectEmpty(context.copy(noLearn = true), "no-learn-suppression")
                miner.expectEmpty(context.copy(hasSelection = true), "selection-suppression")
                if (index % 10 == 0) {
                    miner.expectEmpty(
                        context.copy(
                            password = true,
                            noLearn = true,
                            hasSelection = true,
                        ),
                        "combined-safety-suppression",
                    )
                }
            }
        }
    }

    private fun mineDateAndUrlRanges(
        random: Random,
        miner: TypingAssistMiner,
    ) {
        val dateTokens = listOf("/date", "/time", "/weekday", "rq", "sj", "xq", "日期", "时间", "星期")
        repeat(120) { index ->
            val token = dateTokens[index % dateTokens.size]
            val context = TypingAssistContext(
                before = "提醒 $token",
                after = safeFollowingTexts[random.nextInt(safeFollowingTexts.size)],
                composing = token,
            )
            miner.expectSingleKind(context, TypingAssistKind.DateTime, "date-time-range")

            val urlContext = TypingAssistContext(
                before = if (index % 2 == 0) {
                    "https://site$index.c"
                } else {
                    "www.site$index.c"
                },
                after = safeFollowingTexts[random.nextInt(safeFollowingTexts.size)],
                uriField = true,
            )
            miner.expectAllKind(urlContext, TypingAssistKind.Url, "url-range")
        }
    }

    private fun mineBadContexts(miner: TypingAssistMiner) {
        val explicitBadContexts = listOf(
            "chat-mention" to TypingAssistContext(before = "@alice"),
            "chat-mention" to TypingAssistContext(before = "hello @team", emailField = true),
            "double-at" to TypingAssistContext(before = "alice@@g", emailField = true),
            "mention-then-email" to TypingAssistContext(before = "@alice@g", emailField = true),
            "unicode-local" to TypingAssistContext(before = "用户@g", emailField = true),
            "emoji-local" to TypingAssistContext(before = "alice😀@g", emailField = true),
            "emoji-domain" to TypingAssistContext(before = "alice@😀", emailField = true),
            "punctuation-domain" to TypingAssistContext(before = "alice@g！", emailField = true),
            "url-query-email" to TypingAssistContext(
                before = "https://example.com?email=alice@g",
                emailField = true,
            ),
            "email-query" to TypingAssistContext(before = "alice@g?subject=hello", emailField = true),
            "email-path" to TypingAssistContext(before = "alice@g/path", emailField = true),
            "existing-email" to TypingAssistContext(before = "alice@gmail.com", emailField = true),
            "ambiguous-email" to TypingAssistContext(before = "alice@company.c", emailField = true),
            "typo-email" to TypingAssistContext(before = "alice@gmial.com", emailField = true),
            "url-query" to TypingAssistContext(
                before = "https://example.com?q=1",
                uriField = true,
            ),
            "url-fragment" to TypingAssistContext(before = "https://example.com#top", uriField = true),
            "url-path" to TypingAssistContext(before = "https://example.com/path", uriField = true),
            "url-ip" to TypingAssistContext(before = "https://192.168.1.1", uriField = true),
            "url-userinfo" to TypingAssistContext(before = "https://alice@gmail.com", uriField = true),
            "existing-url" to TypingAssistContext(before = "https://example.org", uriField = true),
            "stale-composing" to TypingAssistContext(
                before = "alice@g",
                composing = "old",
                emailField = true,
            ),
            "middle-email" to TypingAssistContext(
                before = "alice@g",
                after = "mail.com",
                composing = "g",
                emailField = true,
            ),
            "middle-url" to TypingAssistContext(
                before = "https://example.c",
                after = "om",
                uriField = true,
            ),
            "middle-date" to TypingAssistContext(before = "/date", after = "x"),
            "middle-calculator" to TypingAssistContext(before = "1+2=", after = "3"),
        )
        for ((category, context) in explicitBadContexts) {
            miner.expectEmpty(context, category)
        }

        for ((noiseIndex, noise) in unicodeNoise.withIndex()) {
            miner.expectEmpty(
                TypingAssistContext(before = "alice@$noise", emailField = true),
                "unicode-noise-domain-$noiseIndex",
            )
            miner.expectEmpty(
                TypingAssistContext(before = "alice$noise@g", emailField = true),
                "unicode-noise-local-$noiseIndex",
            )
            miner.expectEmpty(
                TypingAssistContext(before = "https://example$noise", uriField = true),
                "unicode-noise-url-$noiseIndex",
            )
            miner.expectEmpty(
                TypingAssistContext(before = "@team$noise"),
                "unicode-noise-mention-$noiseIndex",
            )
        }

        repeat(300) { index ->
            val domain = commonEmailDomains[index % commonEmailDomains.size]
            miner.expectEmpty(
                TypingAssistContext(before = "@mention$index"),
                "generated-mention",
            )
            miner.expectEmpty(
                TypingAssistContext(before = "用户${index}界@g", emailField = true),
                "generated-unicode-local",
            )
            miner.expectEmpty(
                TypingAssistContext(before = "alice$index😀@g", emailField = true),
                "generated-emoji-local",
            )
            miner.expectEmpty(
                TypingAssistContext(before = "alice$index@$domain", emailField = true),
                "generated-existing-domain",
            )
            miner.expectEmpty(
                TypingAssistContext(
                    before = "https://example.com?email=alice$index@g",
                    emailField = true,
                ),
                "generated-url-query-email",
            )
            miner.expectEmpty(
                TypingAssistContext(
                    before = "https://example$index.com/path",
                    uriField = true,
                ),
                "generated-url-path",
            )
            miner.expectEmpty(
                TypingAssistContext(
                    before = "example$index.c",
                    after = "om",
                    uriField = true,
                ),
                "generated-middle-url",
            )
        }
    }

    private fun mineIntegerAndDecimalCalculations(
        random: Random,
        miner: TypingAssistMiner,
    ) {
        val prefixes = listOf("", "算：", "结果 ", "小计 ")
        val suffixes = listOf("", "。", " 元", "！", "，完成")
        repeat(2_200) { index ->
            val calculation = randomIntegerCalculation(random)
            val composing = if (index % 3 == 0) "${calculation.expression}=" else ""
            val context = TypingAssistContext(
                before = prefixes[index % prefixes.size] + calculation.expression + "=",
                after = suffixes[random.nextInt(suffixes.size)],
                composing = composing,
                numeric = index % 2 == 0,
            )
            miner.expectCalculator(context, calculation.expected.toString(), "generated-integer-calculation")
        }

        val decimals = listOf(
            "0.1+0.2" to "0.3",
            "0.1*0.2" to "0.02",
            "10/4" to "2.5",
            "(0.1+0.2)*10" to "3",
            "1/3" to "0.3333333333",
            "2/3" to "0.6666666667",
        )
        for ((expression, expected) in decimals) {
            miner.expectCalculator(
                TypingAssistContext(
                    before = "计算 $expression=",
                    after = "，结果",
                    numeric = true,
                ),
                expected,
                "decimal-calculation",
            )
        }

        val filteredNumbers = listOf(
            "电话 13800138000=",
            "电话 138-0013-8000=",
            "+86-138-0013-8000=",
            "+86 138-0013-8000=",
            "日期 2026-11-17=",
            "日期 17-11-2026=",
            "400-800-1234=",
        )
        for (before in filteredNumbers) {
            miner.expectEmpty(
                TypingAssistContext(before = before, numeric = true),
                "phone-or-date-filter",
            )
        }

        miner.expectCalculatorPreservingPrefix(
            context = TypingAssistContext(
                before = "上一行  \n2+3=",
                after = "\n下一行",
                numeric = true,
            ),
            expected = "5",
            preservedPrefix = "上一行  \n",
            replacedSuffix = "2+3=",
            category = "multilineCalculatorPrefix",
        )

        for (before in listOf(
            "1/1000000000000000=",
            "-1/1000000000000000=",
        )) {
            miner.expectEmpty(
                TypingAssistContext(before = before, numeric = true),
                "tiny-nonzero-calculation",
            )
        }
    }

    private fun mineInputBounds(miner: TypingAssistMiner) {
        repeat(40) { index ->
            val termCount = 50 + index % 10
            val expression = (0..termCount)
                .joinToString("+") { term -> ((term + index) % 9 + 1).toString() }
            miner.expectEmpty(
                TypingAssistContext(before = "$expression=", numeric = true),
                "overlong-calculation",
            )
        }

        repeat(16) { index ->
            val depth = 13 + index % 8
            val expression = "(".repeat(depth) + "1+2" + ")".repeat(depth)
            miner.expectEmpty(
                TypingAssistContext(before = "$expression="),
                "overdeep-calculation",
            )
        }

        repeat(20) { index ->
            miner.expectEmpty(
                TypingAssistContext(before = "${"9".repeat(17 + index % 4)}+1="),
                "overlarge-literal",
            )
            miner.expectEmpty(
                TypingAssistContext(before = "${index + 1}+1/0=", numeric = true),
                "generated-division-by-zero",
            )
            miner.expectEmpty(
                TypingAssistContext(
                    before = "alice@${"a".repeat(256 + index)}",
                    emailField = true,
                ),
                "overlong-email-domain",
            )
            miner.expectEmpty(
                TypingAssistContext(
                    before = "https://${"a".repeat(256 + index)}",
                    uriField = true,
                ),
                "overlong-url-authority",
            )
        }

        val maximumDepth = "(".repeat(12) + "1+2" + ")".repeat(12)
        miner.expectCalculator(
            TypingAssistContext(before = "$maximumDepth="),
            "3",
            "maximum-supported-depth",
        )
    }

    private fun randomIntegerCalculation(random: Random): IntegerCalculation {
        val first = random.nextInt(90) + 10
        val second = random.nextInt(90) + 10
        val third = random.nextInt(90) + 10
        return when (random.nextInt(6)) {
            0 -> IntegerCalculation("$first + $second", first.toLong() + second)
            1 -> IntegerCalculation("$first * $second", first.toLong() * second)
            2 -> IntegerCalculation(
                "($first + $second) * $third",
                (first.toLong() + second) * third,
            )
            3 -> IntegerCalculation(
                "$first * ($second + $third)",
                first.toLong() * (second + third),
            )
            4 -> IntegerCalculation(
                "($first * $second) + $third",
                first.toLong() * second + third,
            )
            else -> IntegerCalculation(
                "$first + ($second * $third)",
                first.toLong() + second.toLong() * third,
            )
        }
    }
}

private data class IntegerCalculation(
    val expression: String,
    val expected: Long,
)

private class TypingAssistMiner(
    private val now: LocalDateTime,
) {
    private val failures = mutableListOf<String>()
    private var failureCount = 0
    private var generatedScenarioCount = 0
    private var emptyResultCount = 0
    private var acceptedSuggestionCount = 0

    fun expectEmailCompletion(
        context: TypingAssistContext,
        domain: String,
        category: String,
    ) {
        val suggestions = query(context, category)
        val matching = suggestions.filter {
            it.kind == TypingAssistKind.Email && it.replacement.equals(domain, ignoreCase = true)
        }
        if (matching.size != 1) {
            recordFailure(
                "$category: expected one completion for $domain, got ${suggestions.map { it.replacement }}",
                context,
            )
            return
        }

        val suggestion = matching.single()
        if (
            suggestion.replaceBefore !in 0..context.before.length ||
            suggestion.replaceAfter !in 0..context.after.length
        ) {
            recordFailure("$category: email completion replacement range is invalid", context)
            return
        }
        val at = context.before.lastIndexOf('@')
        if (at < 0) {
            recordFailure("$category: missing @ in email context", context)
            return
        }
        val preservedPrefix = context.before.substring(0, at + 1)
        val replacementStart = context.before.length - suggestion.replaceBefore
        val correctedBefore = context.before.substring(0, replacementStart) + suggestion.replacement
        val expectedBefore = preservedPrefix + domain
        val preservedAfter = context.after.drop(suggestion.replaceAfter)
        val corrected = correctedBefore + preservedAfter

        if (!correctedBefore.equals(expectedBefore, ignoreCase = true)) {
            recordFailure("$category: username or domain was not preserved", context)
        }
        if (!corrected.startsWith(preservedPrefix, ignoreCase = false)) {
            recordFailure("$category: username prefix changed", context)
        }
        if (correctedBefore.count { it == '@' } != 1) {
            recordFailure("$category: replacement produced a repeated @", context)
        }
        if (correctedBefore.any(Char::isWhitespace)) {
            recordFailure("$category: replacement produced whitespace inside an email", context)
        }
        if (!corrected.endsWith(preservedAfter)) {
            recordFailure("$category: following text was not preserved", context)
        }
    }

    fun expectCalculator(
        context: TypingAssistContext,
        expected: String,
        category: String,
    ) {
        val suggestions = query(context, category)
        if (suggestions.size != 1 || suggestions.single().kind != TypingAssistKind.Calculator) {
            recordFailure(
                "$category: expected one calculator suggestion, got ${suggestions.map { it.kind to it.replacement }}",
                context,
            )
            return
        }
        val suggestion = suggestions.single()
        if (suggestion.replacement != expected) {
            recordFailure(
                "$category: expected $expected, got ${suggestion.replacement}",
                context,
            )
        }
    }

    fun expectCalculatorPreservingPrefix(
        context: TypingAssistContext,
        expected: String,
        preservedPrefix: String,
        replacedSuffix: String,
        category: String,
    ) {
        val suggestions = query(context, category)
        if (suggestions.size != 1 || suggestions.single().kind != TypingAssistKind.Calculator) {
            recordFailure(
                "$category: expected one calculator suggestion, got ${suggestions.map { it.kind to it.replacement }}",
                context,
            )
            return
        }

        val suggestion = suggestions.single()
        if (suggestion.replacement != expected) {
            recordFailure(
                "$category: expected $expected, got ${suggestion.replacement}",
                context,
            )
        }
        if (suggestion.replaceBefore != replacedSuffix.length) {
            recordFailure(
                "$category: expected to replace ${replacedSuffix.length} chars, got ${suggestion.replaceBefore}",
                context,
            )
            return
        }
        if (suggestion.replaceAfter !in 0..context.after.length) {
            recordFailure("$category: replaceAfter is outside the following text", context)
            return
        }

        val replacementStart = context.before.length - suggestion.replaceBefore
        val actualPrefix = context.before.substring(0, replacementStart)
        if (actualPrefix != preservedPrefix) {
            recordFailure("$category: previous-line trailing whitespace would be deleted", context)
            return
        }

        val preservedAfter = context.after.drop(suggestion.replaceAfter)
        val corrected = actualPrefix + suggestion.replacement + preservedAfter
        val expectedCorrected = preservedPrefix + expected + preservedAfter
        if (corrected != expectedCorrected) {
            recordFailure("$category: surrounding multiline text was not preserved", context)
        }
    }

    fun expectSingleKind(
        context: TypingAssistContext,
        kind: TypingAssistKind,
        category: String,
    ) {
        val suggestions = query(context, category)
        if (suggestions.size != 1 || suggestions.single().kind != kind) {
            recordFailure(
                "$category: expected one $kind, got ${suggestions.map { it.kind }}",
                context,
            )
        }
    }

    fun expectAllKind(
        context: TypingAssistContext,
        kind: TypingAssistKind,
        category: String,
    ) {
        val suggestions = query(context, category)
        if (suggestions.isEmpty() || suggestions.any { it.kind != kind }) {
            recordFailure(
                "$category: expected only $kind suggestions, got ${suggestions.map { it.kind }}",
                context,
            )
        }
    }

    fun expectEmpty(
        context: TypingAssistContext,
        category: String,
    ) {
        val suggestions = query(context, category)
        if (suggestions.isNotEmpty()) {
            recordFailure(
                "$category: expected empty, got ${suggestions.map { it.kind to it.replacement }}",
                context,
            )
        }
    }

    fun requireAtLeast(minimum: Int) {
        assertTrue(
            "Mining generated only $generatedScenarioCount scenarios; expected at least $minimum",
            generatedScenarioCount >= minimum,
        )
    }

    fun assertNoFailures() {
        val examples = failures.joinToString(separator = "\n")
        val message = buildString {
            append("Deterministic typing assist mining failed: ")
            append("seed=$MINING_SEED generated=$generatedScenarioCount ")
            append("empty=$emptyResultCount acceptedSuggestions=$acceptedSuggestionCount ")
            append("failures=$failureCount")
            if (examples.isNotEmpty()) {
                append("\nexamples:\n")
                append(examples)
            }
        }
        assertTrue(message, failureCount == 0)
        println(
            "TypingAssistMining seed=$MINING_SEED generated=$generatedScenarioCount " +
                "rejected/empty=$emptyResultCount acceptedSuggestions=$acceptedSuggestionCount failures=0",
        )
    }

    private fun query(
        context: TypingAssistContext,
        category: String,
    ): List<TypingSuggestion> {
        generatedScenarioCount++
        val suggestions = try {
            typingSuggestions(context, now)
        } catch (exception: RuntimeException) {
            recordFailure("$category: helper threw ${exception.javaClass.simpleName}", context)
            emptyList()
        }

        if (suggestions.isEmpty()) emptyResultCount++
        acceptedSuggestionCount += suggestions.size

        if (suggestions.map { it.id }.toSet().size != suggestions.size) {
            recordFailure("$category: duplicate suggestion ids", context)
        }
        for (suggestion in suggestions) {
            validateSuggestion(context, category, suggestion)
        }
        return suggestions
    }

    private fun validateSuggestion(
        context: TypingAssistContext,
        category: String,
        suggestion: TypingSuggestion,
    ) {
        if (suggestion.id.isBlank() || suggestion.label.isBlank()) {
            recordFailure("$category: blank id or label", context)
        }
        if (suggestion.replacement.isBlank() || suggestion.replacement.length > 256) {
            recordFailure("$category: replacement is blank or too long", context)
        }
        if ('\n' in suggestion.replacement || '\r' in suggestion.replacement) {
            recordFailure("$category: replacement contains a line break", context)
        }
        if (suggestion.expectedBefore != context.before) {
            recordFailure("$category: expectedBefore does not match the captured before text", context)
        }
        if (suggestion.expectedAfter != context.after) {
            recordFailure("$category: expectedAfter does not match the captured after text", context)
        }
        if (suggestion.replaceBefore !in 0..context.before.length) {
            recordFailure("$category: replaceBefore is outside the before text", context)
        }
        if (suggestion.replaceAfter !in 0..context.after.length) {
            recordFailure("$category: replaceAfter is outside the after text", context)
        }
        if (
            suggestion.replaceBefore !in 0..context.before.length ||
            suggestion.replaceAfter !in 0..context.after.length
        ) {
            return
        }

        val replacementStart = context.before.length - suggestion.replaceBefore
        val corrected = context.before.substring(0, replacementStart) +
            suggestion.replacement +
            context.after.drop(suggestion.replaceAfter)
        if (
            !corrected.startsWith(context.before.substring(0, replacementStart)) ||
            !corrected.endsWith(context.after.drop(suggestion.replaceAfter))
        ) {
            recordFailure("$category: replacement does not preserve surrounding text", context)
        }

        validateEditorRange(context, category, suggestion)
    }

    private fun validateEditorRange(
        context: TypingAssistContext,
        category: String,
        suggestion: TypingSuggestion,
    ) {
        if (context.before.length > 160 || context.after.length > 80) return

        val selection = context.before.length
        val startOffset = (generatedScenarioCount * 37) % 1_000
        val snapshot: TypingEditorSnapshot? = typingEditorSnapshot(
            text = context.before + context.after,
            selectionStart = selection,
            selectionEnd = selection,
            startOffset = startOffset,
        )
        if (snapshot == null) {
            recordFailure("$category: editor snapshot extraction failed", context)
            return
        }

        val expectedRange = EditorTextSpan(
            start = startOffset + selection - suggestion.replaceBefore,
            end = startOffset + selection + suggestion.replaceAfter,
        )
        val actualRange = typingReplacementRange(snapshot, suggestion)
        if (actualRange != expectedRange) {
            recordFailure(
                "$category: expected editor range $expectedRange, got $actualRange",
                context,
            )
        }
    }

    private fun recordFailure(
        message: String,
        context: TypingAssistContext,
    ) {
        failureCount++
        if (failures.size >= 10) return
        failures += buildString {
            append(message)
            append(" before=\"")
            append(preview(context.before))
            append("\" after=\"")
            append(preview(context.after))
            append('"')
        }
    }

    private fun preview(value: String): String =
        value
            .replace("\r", "\\r")
            .replace("\n", "\\n")
            .take(100)
}
