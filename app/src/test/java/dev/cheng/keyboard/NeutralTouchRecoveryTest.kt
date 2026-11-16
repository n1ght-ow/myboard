package dev.cheng.keyboard

import dev.cheng.keyboard.engine.PinyinDecoder
import dev.cheng.keyboard.model.KeyLikelihood
import dev.cheng.keyboard.model.LexRow
import dev.cheng.keyboard.model.MemoryLexicon
import dev.cheng.keyboard.model.TouchSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NeutralTouchRecoveryTest {
    @Test
    fun neutralTouchMetadataMatchesPlainInput() {
        val decoder = decoder()
        val plain = decoder.decode("moen", fuzzy = false)
        val centers = "moen".map { TouchSample(primary = it) }
        val variants = listOf(
            decoder.decode("moen", fuzzy = false, touchHints = List(4) { "" }, touchSamples = List(4) { null }),
            decoder.decode("moen", fuzzy = false, touchHints = List(4) { "" }, touchSamples = centers),
            decoder.decode("moen", fuzzy = false, touchSamples = centers),
        )

        for (decoded in variants) {
            assertEquals(plain.preedit, decoded.preedit)
            assertEquals(plain.candidates.take(5), decoded.candidates.take(5))
        }
        assertEquals("莫门", plain.candidates.first().text)
    }

    @Test
    fun informativeLegacyHintKeepsSpatialCandidateFirst() {
        val decoded = decoder().decode(
            "moen",
            fuzzy = false,
            touchHints = listOf("", "a", "", ""),
        )

        assertEquals("马恩", decoded.candidates.first().text)
    }

    @Test
    fun informativeNeighborAlternativeKeepsSpatialCandidateFirst() {
        val samples = "moen".mapIndexed { index, typed ->
            if (index == 1) {
                TouchSample(
                    primary = typed,
                    alternatives = listOf(KeyLikelihood('a', 0.1f)),
                )
            } else {
                TouchSample(primary = typed)
            }
        }
        val decoded = decoder().decode("moen", fuzzy = false, touchSamples = samples)

        assertEquals("马恩", decoded.candidates.first().text)
    }

    @Test
    fun manualApostropheAndCleanExactKeepTheirOwnPath() {
        val decoder = decoder()
        val written = decoder.decode("mo'en", fuzzy = false, touchSamples = "moen".map { TouchSample(primary = it) })
        assertTrue(written.preedit.contains('\''))
        assertFalse(written.candidates.any { it.text == "莫门" })

        val exact = decoder.decode("momen", fuzzy = false, touchSamples = "momen".map { TouchSample(primary = it) })
        assertEquals("莫门", exact.candidates.first().text)
    }

    private fun decoder(): PinyinDecoder {
        val decoder = PinyinDecoder(setOf("mo", "ma", "en", "men"))
        decoder.lexicon = MemoryLexicon(
            listOf(
                LexRow("莫", "mo", "mo", 4_000_000),
                LexRow("嗯", "en", "en", 4_000_000),
                LexRow("莫门", "mo men", "momen", 500_000),
                LexRow("马恩", "ma en", "maen", 5_000_000),
            ),
        )
        return decoder
    }
}
