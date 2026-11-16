package dev.cheng.keyboard

import dev.cheng.keyboard.data.MAX_RECENT_CLIPS
import dev.cheng.keyboard.data.clipboardPreviewText
import dev.cheng.keyboard.data.decodeRecentClips
import dev.cheng.keyboard.data.encodeRecentClips
import dev.cheng.keyboard.data.mergeRecentClipLists
import dev.cheng.keyboard.data.mergeRecentClips
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardHistoryTest {
    @Test
    fun keepsTwelveRecentClips() {
        var clips = emptyList<String>()
        for (index in 1..MAX_RECENT_CLIPS) {
            clips = mergeRecentClips("clip-$index", clips)
        }

        assertEquals(12, MAX_RECENT_CLIPS)
        assertEquals((12 downTo 1).map { "clip-$it" }, clips)
    }

    @Test
    fun thirteenthClipEvictsOldest() {
        var clips = emptyList<String>()
        for (index in 1..13) {
            clips = mergeRecentClips("clip-$index", clips)
        }

        assertEquals((13 downTo 2).map { "clip-$it" }, clips)
    }

    @Test
    fun duplicateClipMovesToFrontWithoutGrowingHistory() {
        val clips = (1..12).map { "clip-$it" }

        val promoted = mergeRecentClips("clip-5", clips)

        assertEquals(listOf("clip-5", "clip-1", "clip-2", "clip-3", "clip-4", "clip-6", "clip-7", "clip-8", "clip-9", "clip-10", "clip-11", "clip-12"), promoted)
        assertEquals(listOf("new", "clip-1", "clip-2"), mergeRecentClips("new", listOf("clip-1", "clip-1", "clip-2")))
    }

    @Test
    fun twelveClipsRoundTripThroughStorageEncoding() {
        val clips = (1..12).map { "第$it 条\nclip" }

        assertEquals(clips, decodeRecentClips(encodeRecentClips(clips)))
    }

    @Test
    fun legacyFiveClipPayloadLoadsAndAcceptsNewEntries() {
        val legacy = listOf("旧一", "旧二", "旧三", "旧四", "旧五")
        val legacyPayload = encodeRecentClips(legacy, limit = 5)

        val loaded = decodeRecentClips(legacyPayload)
        val merged = mergeRecentClipLists(listOf("新内容"), loaded)

        assertEquals(legacy, loaded)
        assertEquals(listOf("新内容") + legacy, merged)
    }

    @Test
    fun emptyLimitsProduceEmptyHistory() {
        val primary = listOf("clip-1", "clip-2")
        val secondary = listOf("clip-3")

        assertTrue(mergeRecentClipLists(primary, secondary, limit = 0).isEmpty())
        assertTrue(mergeRecentClipLists(primary, secondary, limit = -1).isEmpty())
    }

    @Test
    fun previewsFiveChineseCharactersAndMarksRemainder() {
        assertEquals("这是五个字…", clipboardPreviewText("这是五个字符"))
    }

    @Test
    fun previewsLongEnglishWordWithSingleEllipsis() {
        assertEquals("antig…", clipboardPreviewText("antigravity"))
    }

    @Test
    fun leavesShortAndExactLengthPreviewUnchanged() {
        assertEquals("短内容", clipboardPreviewText("短内容"))
        assertEquals("一二三四五", clipboardPreviewText("一二三四五"))
    }

    @Test
    fun collapsesPreviewWhitespaceOnly() {
        assertEquals("第一行 第二行", clipboardPreviewText(" \n第一行\t第二行\r\n ", maxCharacters = 20))
        assertEquals("a b", clipboardPreviewText("\na\t b\n"))
    }

    @Test
    fun previewDoesNotSplitEmojiOrCombiningSequences() {
        val text = "👨‍👩‍👧‍👦🇨🇳e\u0301中A文"

        assertEquals("👨‍👩‍👧‍👦🇨🇳e\u0301中A…", clipboardPreviewText(text))
    }

    @Test
    fun storesFullClipWithoutChangingOriginalText() {
        val original = "第一行\n" + "长".repeat(4092)
        val before = original
        val stored = mergeRecentClips(original, emptyList()).single()

        assertEquals(before, original)
        assertEquals(4096, stored.length)
        assertEquals(original, stored)
        assertEquals("第一行 长…", clipboardPreviewText(stored))
        assertNotEquals(stored, clipboardPreviewText(stored))
    }
}
