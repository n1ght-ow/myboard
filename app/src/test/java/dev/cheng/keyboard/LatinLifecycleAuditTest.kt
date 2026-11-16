package dev.cheng.keyboard

import dev.cheng.keyboard.engine.LatinDecoder
import org.junit.Assert.assertEquals
import org.junit.Test

class LatinLifecycleAuditTest {
    private val base = listOf(
        "alpha" to 900_000, "alphabet" to 400_000, "alpine" to 280_000,
        "beta" to 800_000, "better" to 320_000, "hello" to 750_000,
        "help" to 350_000, "neutron" to 220_000, "nebulous" to 210_000,
    )
    private val custom = (0 until 200).map { index ->
        val prefix = listOf("alpha", "hello", "nebula", "zebra")[index % 4]
        prefix + "user" + ('a' + index / 26) + ('a' + index % 26)
    }
    private val probes = (base.map { it.first } + custom).flatMap { word ->
        (1..word.length).map { word.take(it) }
    }.distinct()

    @Test
    fun batchClearMatchesFreshDecoderAcrossSharedPrefixes() {
        val changed = fresh()
        for (word in custom) changed.learn(word, previousWord = "hello")
        repeat(12) { changed.learn("alpha") }

        changed.clearUser()

        assertEquivalent(fresh(), changed)
        assertEquals(emptyList<Pair<String, Int>>(), changed.userWords())
        assertEquals(fresh().predictNext("hello"), changed.predictNext("hello"))
        assertEquals(fresh().predictNext("good"), changed.predictNext("good"))
    }

    @Test
    fun batchReplacementMatchesFreshDecoderWithOnlyNewUserWords() {
        val changed = fresh().apply {
            loadUser(custom.mapIndexed { index, word -> word to (110_000 + index) } + ("alpha" to 1_300_000))
        }
        val replacement = custom.filterIndexed { index, _ -> index % 3 == 0 }
            .mapIndexed { index, word -> word to (70_000 + index) }

        changed.loadUser(replacement)

        assertEquivalent(fresh().apply { loadUser(replacement) }, changed)
        changed.loadUser(emptyList())
        assertEquivalent(fresh(), changed)
    }

    private fun fresh() = LatinDecoder().apply { load(base.asSequence()) }

    private fun assertEquivalent(expected: LatinDecoder, actual: LatinDecoder) {
        for (probe in probes) {
            assertEquals("exact lookup after lifecycle update: $probe", expected.exactFreq(probe), actual.exactFreq(probe))
            assertEquals("suggestions after lifecycle update: $probe", expected.suggest(probe), actual.suggest(probe))
            assertEquals("prefix priors after lifecycle update: $probe", expected.nextLetterPriors(probe).toList(), actual.nextLetterPriors(probe).toList())
        }
    }
}
