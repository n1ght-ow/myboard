package dev.cheng.keyboard

import dev.cheng.keyboard.engine.LatinDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatinUserStateTest {
    @Test
    fun clearingLearningRemovesLiveCustomWordsAndRestoresBaseFrequency() {
        val decoder = LatinDecoder().apply {
            load(sequenceOf("hello" to 100_000, "help" to 90_000, "world" to 80_000))
        }
        decoder.learn("nebulacustom", previousWord = "hello")
        decoder.learn("hello")
        assertTrue(decoder.exactFreq("hello") > 100_000)
        assertTrue(decoder.exactFreq("nebulacustom") > 0)

        decoder.clearUser()

        assertEquals("cleared custom word must disappear from live trie", 0, decoder.exactFreq("nebulacustom"))
        assertEquals("base word must lose its learned frequency", 100_000, decoder.exactFreq("hello"))
        assertFalse(decoder.suggest("nebulac").any { it.text == "nebulacustom" })
        assertFalse(decoder.predictNext("hello").any { it.text == "nebulacustom" })
        assertTrue(decoder.userWords().isEmpty())
    }

    @Test
    fun replacingUserDictionaryRemovesOldWordsAndBoosts() {
        val decoder = LatinDecoder().apply {
            load(sequenceOf("hello" to 100_000, "help" to 90_000))
            loadUser(listOf("nebulacustom" to 120_000, "hello" to 140_000))
        }

        decoder.loadUser(listOf("galaxycustom" to 130_000))

        assertEquals("replaced dictionary must not leave removed words", 0, decoder.exactFreq("nebulacustom"))
        assertEquals("replaced dictionary must remove old base-word boost", 100_000, decoder.exactFreq("hello"))
        assertEquals(130_000, decoder.exactFreq("galaxycustom"))
        assertFalse(decoder.suggest("nebulac").any { it.text == "nebulacustom" })
    }

    @Test
    fun clearingRestoresPrefixPriorsAndAllowsRelearning() {
        val decoder = LatinDecoder().apply {
            load(sequenceOf("hello" to 100_000))
            learn("nebulacustom", previousWord = "hello")
            loadUserBigrams(listOf(Triple("hello", "galaxycustom", 80)))
        }
        assertTrue(decoder.nextLetterPriors("nebula")['c' - 'a'] > 0.9f)

        decoder.clearUser()

        assertEquals(0, decoder.exactFreq("nebulacustom"))
        assertEquals(0f, decoder.nextLetterPriors("nebula")['c' - 'a'], 1e-4f)
        assertFalse(decoder.predictNext("hello").any { it.text == "galaxycustom" })
        assertTrue(decoder.predictNext("good").any { it.text == "morning" })

        decoder.learn("nebulacustom")

        assertEquals(20_000, decoder.exactFreq("nebulacustom"))
        assertTrue(decoder.nextLetterPriors("nebula")['c' - 'a'] > 0.9f)
    }

    @Test
    fun emptyUserDictionaryRemovesBoostsAndAllowsRelearning() {
        val decoder = LatinDecoder().apply {
            load(sequenceOf("hello" to 100_000, "help" to 90_000))
            loadUser(listOf("nebulacustom" to 120_000, "hello" to 140_000))
            loadUser(emptyList())
        }

        assertEquals(0, decoder.exactFreq("nebulacustom"))
        assertEquals(100_000, decoder.exactFreq("hello"))
        assertTrue(decoder.userWords().isEmpty())

        decoder.learn("hello")

        assertEquals(120_000, decoder.exactFreq("hello"))
    }

    @Test
    fun forgetReclaimsSharedPrefixBestWithoutRemovingBaseWord() {
        val decoder = LatinDecoder().apply {
            load(sequenceOf("nebulous" to 80_000))
            learn("nebulous")
            learn("nebulacustom")
        }

        assertTrue(decoder.forget("nebulacustom"))

        assertEquals(0, decoder.exactFreq("nebulacustom"))
        assertEquals(-0.20f, decoder.nextLetterPriors("nebul")['a' - 'a'], 1e-4f)
        assertEquals(1f, decoder.nextLetterPriors("nebul")['o' - 'a'], 1e-4f)
        assertEquals(100_000, decoder.exactFreq("nebulous"))

        assertTrue(decoder.forget("nebulous"))

        assertEquals(80_000, decoder.exactFreq("nebulous"))
        assertTrue(decoder.userWords().isEmpty())
    }

    @Test
    fun reloadingBaseDictionaryStacksCurrentExtraAndRestoresItsNewBaseFloor() {
        val decoder = LatinDecoder().apply {
            load(sequenceOf("hello" to 100_000))
            learn("nebulacustom")
            load(sequenceOf("hello" to 100_000, "nebulacustom" to 10_000))
        }

        assertEquals(20_000, decoder.exactFreq("nebulacustom"))
        assertTrue(decoder.suggest("nebulac").any { it.text == "nebulacustom" })

        decoder.clearUser()

        assertEquals(10_000, decoder.exactFreq("nebulacustom"))
        assertEquals(100_000, decoder.exactFreq("hello"))
    }
}
