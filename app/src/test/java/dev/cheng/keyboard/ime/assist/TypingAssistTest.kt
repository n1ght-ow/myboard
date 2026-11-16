package dev.cheng.keyboard.ime.assist

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TypingAssistTest {
    private val now = LocalDateTime.of(2026, 11, 17, 15, 4)

    @Test
    fun emailSuggestionReplacesOnlyPrefixAfterAt() {
        val suggestion = typingSuggestions(
            TypingAssistContext(
                before = "联系alice@g",
                after = "，",
                composing = "g",
            ),
            now,
        ).single()

        assertEquals(TypingAssistKind.Email, suggestion.kind)
        assertEquals("@gmail.com", suggestion.label)
        assertEquals("gmail.com", suggestion.replacement)
        assertEquals(1, suggestion.replaceBefore)
        assertEquals(0, suggestion.replaceAfter)
        assertEquals("联系alice@g", suggestion.expectedBefore)
        assertEquals("，", suggestion.expectedAfter)

        val underscored = typingSuggestions(
            TypingAssistContext(before = "_alice+g@g", emailField = true),
            now,
        ).single()
        assertEquals("gmail.com", underscored.replacement)
    }

    @Test
    fun emptyEmailDomainOffersStableStaticDomainList() {
        val suggestions = typingSuggestions(
            TypingAssistContext(before = "alice@", emailField = true),
            now,
        )

        assertEquals(
            listOf(
                "@qq.com",
                "@163.com",
                "@126.com",
                "@gmail.com",
                "@outlook.com",
                "@hotmail.com",
                "@icloud.com",
                "@foxmail.com",
                "@yeah.net",
            ),
            suggestions.map { it.label },
        )
        assertTrue(suggestions.all { it.replaceBefore == 0 })
    }

    @Test
    fun emailDomainPrefixAndPartialSuffixAreFiltered() {
        val outlook = typingSuggestions(
            TypingAssistContext(before = "alice@out", emailField = true),
            now,
        )
        assertEquals(listOf("@outlook.com"), outlook.map { it.label })

        val gmail = typingSuggestions(
            TypingAssistContext(before = "alice@gmail.c", emailField = true),
            now,
        ).single()
        assertEquals("gmail.com", gmail.replacement)
        assertEquals("gmail.c".length, gmail.replaceBefore)
    }

    @Test
    fun completeOrEnterpriseEmailIsNotRewritten() {
        assertTrue(
            typingSuggestions(
                TypingAssistContext(before = "alice@gmail.com", emailField = true),
                now,
            ).isEmpty(),
        )
        assertTrue(
            typingSuggestions(
                TypingAssistContext(before = "alice@company.c", emailField = true),
                now,
            ).isEmpty(),
        )
    }

    @Test
    fun emailRejectsChatSyntaxAndUrlUserinfo() {
        val badInputs = listOf(
            "联系我们 @",
            "alice@@g",
            "alice@@bob@g",
            "https://user@g",
            "https://foo/user+alice@g",
            "https://example.com?email=alice@g",
            "alice@g/path",
            "alice@g?subject=hello",
        )

        for (before in badInputs) {
            assertTrue(
                "email assist must reject $before",
                typingSuggestions(
                    TypingAssistContext(before = before, emailField = true),
                    now,
                ).isEmpty(),
            )
        }
    }

    @Test
    fun emailDoesNotTriggerWhileCursorIsInsideDomain() {
        assertTrue(
            typingSuggestions(
                TypingAssistContext(
                    before = "alice@g",
                    after = "mail.com",
                    composing = "g",
                    emailField = true,
                ),
                now,
            ).isEmpty(),
        )
    }

    @Test
    fun urlFieldInsertsFilteredTldAfterHostPrefix() {
        val all = typingSuggestions(
            TypingAssistContext(before = "example", uriField = true),
            now,
        )
        assertEquals(listOf(".com", ".cn", ".net", ".org"), all.map { it.label })
        assertTrue(all.all { it.replaceBefore == 0 && it.replacement == it.label })

        val partial = typingSuggestions(
            TypingAssistContext(before = "example.c", uriField = true),
            now,
        )
        assertEquals(listOf(".com", ".cn"), partial.map { it.label })
        assertEquals(2, partial.first().replaceBefore)
    }

    @Test
    fun explicitUrlAndWwwAlsoOfferSuffixes() {
        val https = typingSuggestions(
            TypingAssistContext(before = "访问：https://exa"),
            now,
        )
        assertEquals(listOf(".com", ".cn", ".net", ".org"), https.map { it.label })

        val www = typingSuggestions(
            TypingAssistContext(before = "www.exa"),
            now,
        )
        assertEquals(listOf(".com", ".cn", ".net", ".org"), www.map { it.label })
    }

    @Test
    fun trailingUrlDotReplacesDotInsteadOfDuplicatingIt() {
        val suggestions = typingSuggestions(
            TypingAssistContext(before = "https://example.", uriField = true),
            now,
        )
        assertEquals(listOf(".com", ".cn", ".net", ".org"), suggestions.map { it.label })
        assertTrue(suggestions.all { it.replaceBefore == 1 })

        val www = typingSuggestions(
            TypingAssistContext(before = "www.example."),
            now,
        )
        assertEquals(listOf(".com", ".cn", ".net", ".org"), www.map { it.label })
    }

    @Test
    fun completedHostWithTrailingDotDoesNotReceiveSecondSuffix() {
        val badContexts = listOf(
            TypingAssistContext(before = "example.com.", uriField = true),
            TypingAssistContext(before = "https://example.com."),
            TypingAssistContext(before = "www.example.com."),
            TypingAssistContext(before = "example.net.", uriField = true),
            TypingAssistContext(before = "example.io.", uriField = true),
            TypingAssistContext(before = "https://example.io."),
            TypingAssistContext(before = "https://www.example.io."),
        )

        for (context in badContexts) {
            assertTrue(
                "completed host must not get another suffix: ${context.before}",
                typingSuggestions(context, now).isEmpty(),
            )
        }
    }

    @Test
    fun internalWwwAndUrlUserinfoAreNeverTreatedAsHost() {
        val badContexts = listOf(
            TypingAssistContext(before = "evilwww.example"),
            TypingAssistContext(before = "foo.www.exa"),
            TypingAssistContext(before = "user@www.exa"),
            TypingAssistContext(before = "evilwww.com.", uriField = true),
            TypingAssistContext(before = "https://user@example.c", uriField = true),
            TypingAssistContext(before = "user@example.c", uriField = true),
            TypingAssistContext(before = "https://user@www.c"),
        )

        for (context in badContexts) {
            assertTrue(
                "internal www or userinfo must not become host: ${context.before}",
                typingSuggestions(context, now).isEmpty(),
            )
        }
    }

    @Test
    fun urlRejectsPathsQueriesIpsAndCompleteDomains() {
        val badInputs = listOf(
            "https://example.com",
            "https://example.io",
            "https://example.com/path",
            "https://example.com?q=1",
            "https://example.com#top",
            "https://192.168.1.1",
            "https://127.0.0",
            "https://[::1]",
            "mailto:alice@g",
            "https://alice@gmail.com",
        )

        for (before in badInputs) {
            assertTrue(
                "URL assist must reject $before",
                typingSuggestions(
                    TypingAssistContext(before = before, uriField = true),
                    now,
                ).isEmpty(),
            )
        }
    }

    @Test
    fun textEmailDoesNotLeakUrlCandidates() {
        val suggestions = typingSuggestions(
            TypingAssistContext(before = "alice@g"),
            now,
        )
        assertTrue(suggestions.isNotEmpty())
        assertTrue(suggestions.all { it.kind == TypingAssistKind.Email })
    }

    @Test
    fun dateTimeAliasesReplaceOnlyTheirTrigger() {
        val date = typingSuggestions(TypingAssistContext(before = "日期 rq"), now).single()
        assertEquals("2026-11-17", date.replacement)
        assertEquals(2, date.replaceBefore)
        assertEquals("日期 2026-11-17", date.label)

        val time = typingSuggestions(TypingAssistContext(before = "/time"), now).single()
        assertEquals("15:04", time.replacement)
        assertEquals("/time".length, time.replaceBefore)

        val weekday = typingSuggestions(TypingAssistContext(before = "xq"), now).single()
        assertEquals("星期二", weekday.replacement)
    }

    @Test
    fun dateTimeRequiresStandaloneTokenAndCursorAtTokenEnd() {
        val invalidContexts = listOf(
            TypingAssistContext(before = "abrq"),
            TypingAssistContext(before = "2026rq"),
            TypingAssistContext(before = "rqx"),
            TypingAssistContext(before = "rq", after = "x"),
            TypingAssistContext(before = "x/time"),
            TypingAssistContext(before = "https://host/rq"),
            TypingAssistContext(before = "https://host/date"),
            TypingAssistContext(before = "gmail.com/rq"),
            TypingAssistContext(before = "alice+rq"),
            TypingAssistContext(before = "alice@rq"),
            TypingAssistContext(before = "C:\\Users\\rq"),
            TypingAssistContext(before = "~/rq"),
        )

        for (context in invalidContexts) {
            assertTrue(
                "date/time assist must not trigger for ${context.before}",
                typingSuggestions(context, now).isEmpty(),
            )
        }

        val punctuation = typingSuggestions(
            TypingAssistContext(before = "提醒，rq"),
            now,
        ).single()
        assertEquals("2026-11-17", punctuation.replacement)

        val parenthesized = typingSuggestions(
            TypingAssistContext(before = "（sj", after = "）"),
            now,
        ).single()
        assertEquals("15:04", parenthesized.replacement)
    }

    @Test
    fun safetyFlagsDisableEverySuggestionKind() {
        val email = TypingAssistContext(before = "alice@g", emailField = true)
        val url = TypingAssistContext(before = "example", uriField = true)
        val date = TypingAssistContext(before = "rq")
        val calculator = TypingAssistContext(before = "2+3=", numeric = true)

        assertTrue(typingSuggestions(email.copy(password = true), now).isEmpty())
        assertTrue(typingSuggestions(url.copy(noLearn = true), now).isEmpty())
        assertTrue(typingSuggestions(date.copy(hasSelection = true), now).isEmpty())
        assertTrue(typingSuggestions(calculator.copy(password = true), now).isEmpty())
    }

    @Test
    fun numericModeAllowsCalculatorButRejectsOtherAssistKinds() {
        assertTrue(
            typingSuggestions(
                TypingAssistContext(before = "alice@g", emailField = true, numeric = true),
                now,
            ).isEmpty(),
        )
        assertEquals(
            TypingAssistKind.Calculator,
            typingSuggestions(
                TypingAssistContext(before = "2+3=", numeric = true),
                now,
            ).single().kind,
        )
    }

    @Test
    fun staleComposingStateSuppressesSuggestions() {
        assertTrue(
            typingSuggestions(
                TypingAssistContext(
                    before = "alice@g",
                    composing = "old",
                    emailField = true,
                ),
                now,
            ).isEmpty(),
        )
        assertTrue(
            typingSuggestions(
                TypingAssistContext(before = "rq", composing = "sj"),
                now,
            ).isEmpty(),
        )
    }

    @Test
    fun calculatorReplacesWholeTerminatedExpression() {
        val suggestion = typingSuggestions(
            TypingAssistContext(
                before = "总计 2+3=",
                after = " 元",
                composing = "2+3=",
                numeric = true,
            ),
            now,
        ).single()

        assertEquals(TypingAssistKind.Calculator, suggestion.kind)
        assertEquals("5", suggestion.replacement)
        assertEquals("2+3=".length, suggestion.replaceBefore)
        assertEquals("总计 2+3=", suggestion.expectedBefore)
        assertEquals(" 元", suggestion.expectedAfter)
    }

    @Test
    fun calculatorSupportsParenthesesAndUnicodeOperators() {
        val parentheses = typingSuggestions(
            TypingAssistContext(before = "值 (2+3)*4="),
            now,
        ).single()
        assertEquals("20", parentheses.replacement)
        assertEquals("(2+3)*4=".length, parentheses.replaceBefore)

        val unicode = typingSuggestions(
            TypingAssistContext(before = "8÷2×3="),
            now,
        ).single()
        assertEquals("12", unicode.replacement)

        val subtraction = typingSuggestions(
            TypingAssistContext(before = "2000-3000="),
            now,
        ).single()
        assertEquals("-1000", subtraction.replacement)
    }

    @Test
    fun calculatorRejectsPhonesDatesTimesAndUrlQueries() {
        val badInputs = listOf(
            "电话13800138000=",
            "电话138-0013-8000=",
            "电话+86-138-0013-8000=",
            "2026-11-17=",
            "17-11-2026=",
            "400-800-1234=",
            "12:30=",
            "https://example.com/?a=1+2=",
        )

        for (before in badInputs) {
            assertTrue(
                "calculator must reject $before",
                typingSuggestions(
                    TypingAssistContext(before = before, numeric = true),
                    now,
                ).isEmpty(),
            )
        }
    }

    @Test
    fun calculatorAllowsTwoPartSubtraction() {
        val monthDay = typingSuggestions(
            TypingAssistContext(before = "11-17="),
            now,
        ).single()
        assertEquals("-6", monthDay.replacement)

        val smallSubtraction = typingSuggestions(
            TypingAssistContext(before = "9-4="),
            now,
        ).single()
        assertEquals("5", smallSubtraction.replacement)

        val yearSubtraction = typingSuggestions(
            TypingAssistContext(before = "2026-2="),
            now,
        ).single()
        assertEquals("2024", yearSubtraction.replacement)

        val yearDivision = typingSuggestions(
            TypingAssistContext(before = "2026/2="),
            now,
        ).single()
        assertEquals("1013", yearDivision.replacement)
    }

    @Test
    fun calculatorNeverCrossesLineBreaks() {
        val lineFeed = typingSuggestions(
            TypingAssistContext(
                before = "100\n2+3=",
                composing = "2+3=",
                numeric = true,
            ),
            now,
        ).single()
        assertEquals("5", lineFeed.replacement)
        assertEquals("2+3=".length, lineFeed.replaceBefore)
        assertEquals("100\n2+3=", lineFeed.expectedBefore)

        val carriageReturnLineFeed = typingSuggestions(
            TypingAssistContext(
                before = "100\r\n9-4=",
                composing = "9-4=",
                numeric = true,
            ),
            now,
        ).single()
        assertEquals("5", carriageReturnLineFeed.replacement)
        assertEquals("9-4=".length, carriageReturnLineFeed.replaceBefore)
        assertEquals("100\r\n9-4=", carriageReturnLineFeed.expectedBefore)
    }

    @Test
    fun calculatorRequiresTokenBoundariesAndValidExpression() {
        val badContexts = listOf(
            TypingAssistContext(before = "cmd1+2="),
            TypingAssistContext(before = "1+2=abc", after = "abc"),
            TypingAssistContext(before = "1++2="),
            TypingAssistContext(before = "1/0="),
            TypingAssistContext(before = "1+2=="),
            TypingAssistContext(before = "42="),
        )

        for (context in badContexts) {
            assertTrue(
                "calculator must not trigger for ${context.before}",
                typingSuggestions(context, now).isEmpty(),
            )
        }
    }
}
