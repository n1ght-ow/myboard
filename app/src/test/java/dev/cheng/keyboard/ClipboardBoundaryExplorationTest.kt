package dev.cheng.keyboard

import dev.cheng.keyboard.data.clipboardItemsForDisplay
import dev.cheng.keyboard.data.decodeRecentClips
import dev.cheng.keyboard.data.encodeRecentClips
import dev.cheng.keyboard.data.mergeRecentClipLists
import dev.cheng.keyboard.data.mergeRecentClips
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardBoundaryExplorationTest {
    @Test
    fun clipEndingAtDanglingHighSurrogateDropsWholeCodePoint() {
        val original = "a".repeat(4095) + "😀"

        val stored = mergeRecentClips(original, emptyList()).single()

        assertEquals("a".repeat(4095), stored)
        assertTrue(stored.hasOnlyPairedSurrogates())
    }

    @Test
    fun exactUtf16LimitStillKeepsFullText() {
        val original = "a".repeat(4096)
        val emojiEndingAtLimit = "a".repeat(4094) + "😀"

        val stored = mergeRecentClips(original, emptyList()).single()
        val storedEmoji = mergeRecentClips(emojiEndingAtLimit, emptyList()).single()

        assertEquals(4096, stored.length)
        assertEquals(original, stored)
        assertEquals(emojiEndingAtLimit, storedEmoji)
    }

    @Test
    fun boundaryDoesNotSplitCombiningMark() {
        val original = "a".repeat(4095) + "e\u0301"

        val stored = mergeRecentClips(original, emptyList()).single()

        assertEquals("a".repeat(4095), stored)
    }

    @Test
    fun boundaryKeepsWholeLongCombiningClusterFromOriginalStart() {
        val original = "A" + "e" + "\u0301".repeat(5000)

        val stored = mergeRecentClips(original, emptyList()).single()

        assertEquals("A", stored)
    }

    @Test
    fun boundaryDoesNotLeaveHalfRegionalIndicatorPair() {
        val original = "a" + "🇦🇧".repeat(1200)

        val stored = mergeRecentClips(original, emptyList()).single()

        assertEquals("a" + "🇦🇧".repeat(1023), stored)
        assertEquals(4093, stored.length)
        assertTrue(stored.hasOnlyPairedSurrogates())
    }

    @Test
    fun boundaryDoesNotLeaveHalfRegionalIndicatorPairAfterTwoAsciiChars() {
        val original = "aa" + "🇦🇧".repeat(1200)

        val stored = mergeRecentClips(original, emptyList()).single()

        assertEquals("aa" + "🇦🇧".repeat(1023), stored)
        assertEquals(4094, stored.length)
        assertTrue(stored.hasOnlyPairedSurrogates())
    }

    @Test
    fun boundaryDoesNotSplitEmojiVariationSequence() {
        val original = "a".repeat(4095) + "❤\uFE0F"

        val stored = mergeRecentClips(original, emptyList()).single()

        assertEquals("a".repeat(4095), stored)
    }

    @Test
    fun boundaryDoesNotSplitEmojiModifierSequence() {
        val original = "a".repeat(4094) + "👍🏽"

        val stored = mergeRecentClips(original, emptyList()).single()

        assertEquals("a".repeat(4094), stored)
    }

    @Test
    fun boundaryDoesNotSplitZeroWidthJoinedSequence() {
        val original = "a".repeat(4094) + "👩‍💻"

        val stored = mergeRecentClips(original, emptyList()).single()

        assertEquals("a".repeat(4094), stored)
    }

    @Test
    fun normalizedClipMergeIsIdempotentAndDeduplicated() {
        val original = "a".repeat(4095) + "😀"
        val first = mergeRecentClips(original, listOf(original, original))
        val second = mergeRecentClips(first.single(), first)

        assertEquals(listOf("a".repeat(4095)), first)
        assertEquals(first, second)
    }

    @Test
    fun listMergeDeduplicatesAfterSafeNormalization() {
        val damaged = "a".repeat(4095) + "😀"

        val merged = mergeRecentClipLists(
            listOf(damaged, "a".repeat(4095)),
            listOf(damaged),
        )

        assertEquals(listOf("a".repeat(4095)), merged)
    }

    @Test
    fun storageEncodingRoundTripKeepsWellFormedUnicode() {
        val original = "前".repeat(4095) + "👨‍👩‍👧‍👦"

        val encoded = encodeRecentClips(listOf(original))
        val decoded = decodeRecentClips(encoded).single()

        assertEquals("前".repeat(4095), decoded)
        assertTrue(encoded.hasOnlyPairedSurrogates())
        assertTrue(decoded.hasOnlyPairedSurrogates())
    }

    @Test
    fun zeroLimitsNeverAddNormalizedClips() {
        val original = "a".repeat(4095) + "😀"

        assertTrue(mergeRecentClips(original, listOf(original), limit = 0).isEmpty())
        assertTrue(mergeRecentClipLists(listOf(original), listOf(original), limit = 0).isEmpty())
        assertTrue(decodeRecentClips(encodeRecentClips(listOf(original), limit = 0), limit = 0).isEmpty())
        assertTrue(clipboardItemsForDisplay(original, listOf(original), limit = 0).isEmpty())
    }

    @Test
    fun clipboardDisplayUsesFullCurrentButKeepsHistoryBounded() {
        val current = "前".repeat(5998) + "😀"
        val older = "旧".repeat(5998) + "😀"
        val history = mergeRecentClips(current, listOf(older))

        val displayed = clipboardItemsForDisplay(current, history)

        assertEquals(6000, current.length)
        assertEquals(current, displayed.first())
        assertEquals("前".repeat(4096), displayed.first().take(4096))
        assertEquals(history.size, displayed.size)
        assertEquals(4096, displayed[1].length)
        assertTrue(displayed[1].hasOnlyPairedSurrogates())
    }

    @Test
    fun clipboardDisplayShowsFullCurrentWhenHistoryIsEmpty() {
        val current = "a".repeat(5900) + "👨‍👩‍👧‍👦"

        assertEquals(listOf(current), clipboardItemsForDisplay(current, emptyList()))
    }

    @Test
    fun clipboardDisplayIgnoresNullOrBlankCurrentItem() {
        val history = listOf("first", "second")

        assertEquals(history, clipboardItemsForDisplay(null, history))
        assertEquals(history, clipboardItemsForDisplay(" \n\t ", history))
        assertTrue(clipboardItemsForDisplay(null, emptyList()).isEmpty())
        assertTrue(clipboardItemsForDisplay(" ", emptyList()).isEmpty())
    }

    @Test
    fun clipboardDisplayDoesNotReplaceUnrelatedHistoryWithStaleCurrent() {
        val history = listOf("first", "second")

        val displayed = clipboardItemsForDisplay("stale-current", history)

        assertEquals(history, displayed)
        assertTrue("stale-current" !in displayed)
    }

    @Test
    fun clipboardDisplayPreservesOrderDeduplicationAndLimit() {
        val current = "a".repeat(6000)
        val history = listOf("older", current, "older", "third")

        val displayed = clipboardItemsForDisplay(current, history, limit = 2)

        assertEquals(listOf("older", current), displayed)
    }

    @Test
    fun clipboardDisplayKeepsFullCurrentWellFormedAtSurrogateBoundary() {
        val current = "a".repeat(5998) + "😀"
        val stored = mergeRecentClips(current, emptyList()).single()

        val displayed = clipboardItemsForDisplay(current, listOf(stored))

        assertEquals(listOf(current), displayed)
        assertTrue(displayed.single().hasOnlyPairedSurrogates())
    }

    @Test
    fun decodeLegacyJsonDropsStoredTrailingHighSurrogate() {
        val safePrefix = "a".repeat(4095)
        val legacyPayload = "[\"$safePrefix\\uD83D\"]"

        val decoded = decodeRecentClips(legacyPayload)

        assertEquals(listOf(safePrefix), decoded)
        assertTrue(decoded.single().hasOnlyPairedSurrogates())
    }

    @Test
    fun normalizeDropsLegacyTrailingHighSurrogateWithoutKeepingEmptyItem() {
        val safePrefix = "a".repeat(4095)
        val damaged = safePrefix + "\uD83D"

        assertEquals(listOf(safePrefix), mergeRecentClips(damaged, listOf(damaged)))
        assertTrue(mergeRecentClips("\uD83D", emptyList()).isEmpty())
    }

    private fun String.hasOnlyPairedSurrogates(): Boolean {
        var index = 0
        while (index < length) {
            val char = this[index]
            when {
                char.isHighSurrogate() -> {
                    if (index + 1 >= length || !this[index + 1].isLowSurrogate()) return false
                    index += 2
                }
                char.isLowSurrogate() -> return false
                else -> index++
            }
        }
        return true
    }
}
