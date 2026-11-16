package dev.cheng.keyboard

import dev.cheng.keyboard.engine.PinyinDecoder
import dev.cheng.keyboard.model.LexRow
import dev.cheng.keyboard.model.MemoryLexicon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PinyinRecoveryRegressionTest {
    @Test
    fun standardExactSpellingsSurviveInsertionFallback() {
        data class Guard(
            val raw: String,
            val wanted: String,
            val wantedSyllables: String,
            val omittedPinyin: String,
            val wrong: String,
            val wrongSyllables: String,
            val wantedFreq: Int,
            val wrongFreq: Int,
        )

        val guards = listOf(
            Guard("guge", "谷歌", "gu ge", "guige", "规格", "gui ge", 29_145, 500_588),
            Guard("zongzi", "粽子", "zong zi", "zhongzi", "种子", "zhong zi", 28_255, 500_257),
            Guard("xiaxue", "下雪", "xia xue", "xiaoxue", "小学", "xiao xue", 29_425, 500_407),
            Guard("haiou", "海鸥", "hai ou", "haiyou", "还有", "hai you", 29_505, 501_908),
            Guard("zhinen", "稚嫩", "zhi nen", "zhineng", "只能", "zhi neng", 29_535, 500_749),
        )
        val syllables = guards.flatMap { it.wantedSyllables.split(' ') + it.wrongSyllables.split(' ') }.toSet()
        val rows = guards.flatMap { guard ->
            listOf(
                LexRow(guard.wanted, guard.wantedSyllables, guard.raw, guard.wantedFreq),
                LexRow(guard.wrong, guard.wrongSyllables, guard.omittedPinyin, guard.wrongFreq),
            )
        }
        val decoder = PinyinDecoder(syllables).also { it.lexicon = MemoryLexicon(rows) }

        for (guard in guards) {
            assertEquals(guard.raw, guard.wanted, decoder.decode(guard.raw, fuzzy = false).candidates.first().text)
        }
    }

    @Test
    fun rareExactSpellingStillUsesOldInsertionFallback() {
        val decoder = PinyinDecoder(setOf("yu", "bu", "you")).also {
            it.lexicon = MemoryLexicon(
                listOf(
                    LexRow("余部", "yu bu", "yubu", 15_575),
                    LexRow("有不", "you bu", "youbu", 999_999),
                ),
            )
        }

        assertEquals("有不", decoder.decode("yubu", fuzzy = false).candidates.first().text)
    }

    @Test
    fun highFrequencySingleCharacterCompositionOnlyExposesInitialRepairAtSecondPlace() {
        val decoder = PinyinDecoder(setOf("wo", "en", "men")).also {
            it.lexicon = MemoryLexicon(
                listOf(
                    LexRow("我", "wo", "wo", 29_569_261),
                    LexRow("嗯", "en", "en", 9_179_528),
                    LexRow("我们", "wo men", "women", 509_405),
                ),
            )
        }

        val candidates = decoder.decode("woen", fuzzy = false).candidates
        assertNotEquals("我们", candidates.first().text)
        assertEquals("我们", candidates[1].text)
    }
}
