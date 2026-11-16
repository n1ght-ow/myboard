package dev.cheng.keyboard

import dev.cheng.keyboard.engine.LatinDecoder
import dev.cheng.keyboard.model.KeyLikelihood
import dev.cheng.keyboard.model.TouchSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LatinRecoveryRegressionTest {
    @Test
    fun recoversOneEditSlipsBehindAmbiguousCompletions() {
        val cases = listOf(
            Triple("surre", "sure", "surrender"),
            Triple("madde", "made", "madden"),
            Triple("gril", "girl", "grill"),
            Triple("finne", "fine", "finney"),
            Triple("minne", "mine", "minnesota"),
            Triple("thre", "there", "three"),
            Triple("thir", "their", "third"),
            Triple("beng", "being", "bengal"),
            Triple("numer", "number", "numerous"),
            Triple("albe", "able", "albert"),
            Triple("knig", "king", "knight"),
        )
        val latin = decoder(
            "sure" to 709_390,
            "surrender" to 13_513,
            "surreal" to 1_117,
            "surrey" to 477,
            "surrendered" to 2_113,
            "made" to 433_900,
            "madden" to 892,
            "madder" to 501,
            "maddening" to 335,
            "girl" to 402_557,
            "grill" to 4_265,
            "grilled" to 2_395,
            "grille" to 326,
            "grilling" to 708,
            "fine" to 400_530,
            "finney" to 479,
            "finnegan" to 604,
            "finnerty" to 238,
            "mine" to 170_636,
            "minnesota" to 1_895,
            "minnelli" to 208,
            "minneapolis" to 763,
            "there" to 3_148_528,
            "three" to 366_626,
            "threw" to 29_223,
            "threat" to 20_913,
            "thread" to 4_985,
            "fiddle" to 2_248,
            "fidel" to 1_247,
            "fide" to 624,
            "fido" to 496,
            "their" to 601_171,
            "third" to 55_750,
            "thirty" to 10_699,
            "thirsty" to 10_053,
            "thirst" to 3_690,
            "being" to 386_528,
            "bengal" to 810,
            "bengt" to 276,
            "bengali" to 736,
            "benghazi" to 248,
            "number" to 163_259,
            "numerous" to 3_315,
            "numero" to 401,
            "numerical" to 386,
            "numerals" to 218,
            "able" to 147_205,
            "albert" to 14_140,
            "alberto" to 2_927,
            "albeit" to 667,
            "alberta" to 610,
            "king" to 118_230,
            "knight" to 11_542,
            "knights" to 4_791,
            "knighted" to 244,
            "knightley" to 268,
        )

        for ((raw, target, completion) in cases) {
            val top = latin.suggest(raw).take(3).map { it.text }
            assertEquals("$raw should keep its ambiguous completion first: $top", completion, top[0])
            assertEquals("$raw should recover $target after the raw prefix: $top", target, top[2])
        }

        latin.learn("fidel", previousWord = "name")
        val contextual = latin.suggest("fid", prevWord = "name").take(3).map { it.text }
        assertEquals("context-ranked completion must remain first: $contextual", "fidel", contextual[0])
    }

    @Test
    fun keepsTouchPriorityForAnExistingRecoveryCandidate() {
        val latin = decoder(
            "girl" to 402_557,
            "grill" to 4_265,
            "grilled" to 2_395,
            "grille" to 326,
            "grilling" to 708,
        )
        val samples = listOf(
            TouchSample(primary = 'g'),
            TouchSample(
                primary = 'r',
                alternatives = listOf(KeyLikelihood('i', 0f)),
            ),
            TouchSample(primary = 'i'),
            TouchSample(primary = 'l'),
        )

        val top = latin.suggest("gril", touchSamples = samples).take(3).map { it.text }

        assertEquals("touch-ranked recovery must remain first: $top", "girl", top[0])
    }

    @Test
    fun preservesExactAndShortPrefixOrdering() {
        val latin = decoder(
            "be" to 4_210_868,
            "hel" to 1_376,
            "hello" to 405_534,
            "help" to 666_286,
            "helmet" to 7_341,
            "thi" to 850,
            "this" to 5_739_788,
            "thing" to 697_528,
            "thick" to 12_143,
            "thin" to 15_296,
            "bec" to 297,
            "because" to 880_112,
            "become" to 128_716,
            "beck" to 3_601,
            "morn" to 1_410,
            "morning" to 303_550,
            "mornings" to 2_463,
            "mornin" to 2_256,
            "mourn" to 2_453,
        )

        val hel = latin.suggest("hel").take(5).map { it.text }
        val thi = latin.suggest("thi").take(5).map { it.text }
        val bec = latin.suggest("bec").take(5).map { it.text }
        val morn = latin.suggest("morn").take(5).map { it.text }

        assertEquals("help", hel.first())
        assertFalse("short prefix must not insert 'he': $hel", "he" in hel)
        assertEquals("this", thi.first())
        assertEquals("because", bec.first())
        assertEquals("morning", morn.first())
        assertFalse("exact 'morn' must not gain 'mourn' from recovery: $morn", "mourn" in morn)
    }

    private fun decoder(vararg words: Pair<String, Int>): LatinDecoder =
        LatinDecoder().apply { load(words.asSequence()) }
}
