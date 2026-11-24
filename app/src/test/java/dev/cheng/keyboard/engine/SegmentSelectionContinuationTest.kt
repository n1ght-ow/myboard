package dev.cheng.keyboard.engine

import dev.cheng.keyboard.model.Candidate
import dev.cheng.keyboard.model.EnterKind
import dev.cheng.keyboard.model.Pending
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentSelectionContinuationTest {
    private val engine = InputEngine(LatinDecoder())

    @Test
    fun backspaceInsideCorrectedResidualSpellingKeepsTheCommittedSegment() {
        val state = EngineState(
            raw = "yuoyu",
            candidates = listOf(
                Candidate("有", "you", 100, consumed = 3),
                Candidate("有雨", "you yu", 200, consumed = 5),
            ),
            lastChineseContext = "我",
            touchHints = listOf("y", "o", "u", "y", "u"),
            touchSamples = List(5) { null },
        )
        val partial = engine.reduce(state, EngineAct.Pick(0), EnterKind.Newline)
        assertEquals("有", partial.state.partialText)
        assertEquals("you", partial.state.partialSyllables)
        assertEquals("yu", partial.state.raw)

        val edited = engine.reduce(partial.state, EngineAct.Backspace, EnterKind.Newline)
        assertEquals("y", edited.state.raw)
        assertEquals("有", edited.state.partialText)
        assertEquals("you", edited.state.partialSyllables)
        assertEquals(listOf("y"), edited.state.touchHints)
        assertEquals(Pending.Pinyin, edited.pending)

        val retyped = engine.reduce(edited.state, EngineAct.Letter('u', null, ""), EnterKind.Newline)
        assertEquals("yu", retyped.state.raw)
        assertEquals("有", retyped.state.partialText)

        val completed = engine.reduce(
            retyped.state.copy(candidates = listOf(Candidate("雨", "yu", 300, consumed = 2))),
            EngineAct.Pick(0),
            EnterKind.Newline,
        )
        assertEquals(listOf(Cmd.Commit("雨"), Cmd.LearnZh("有雨", "you yu", "我")), completed.cmds)
        assertEquals("", completed.state.partialText)
        assertEquals("", completed.state.partialSyllables)
    }

    @Test
    fun deletingTheResidualSpellingLeavesThePrefixEditable() {
        val state = EngineState(
            raw = "youyu",
            candidates = listOf(Candidate("有", "you", 100, consumed = 3)),
            lastChineseContext = "我",
        )
        val partial = engine.reduce(state, EngineAct.Pick(0), EnterKind.Newline)
        val noResidual = engine.reduce(
            engine.reduce(partial.state, EngineAct.Backspace, EnterKind.Newline).state,
            EngineAct.Backspace,
            EnterKind.Newline,
        )

        assertEquals("", noResidual.state.raw)
        assertEquals("有", noResidual.state.partialText)
        assertEquals("you", noResidual.state.partialSyllables)
        assertEquals(listOf(Cmd.ClearCompose), noResidual.cmds)

        val deletingPrefix = engine.reduce(
            noResidual.state.copy(partialText = "你好", partialSyllables = "ni hao"),
            EngineAct.Backspace,
            EnterKind.Newline,
        )
        assertEquals("你", deletingPrefix.state.partialText)
        assertEquals("ni", deletingPrefix.state.partialSyllables)
        assertEquals(listOf(Cmd.DeleteOne), deletingPrefix.cmds)
    }

    @Test
    fun boundariesClearAnOrphanedSelectedPrefixWithoutImplicitLearning() {
        val orphaned = EngineState(
            raw = "",
            partialText = "你好",
            partialSyllables = "ni hao",
            lastChineseContext = "我",
        )

        val spaced = engine.reduce(orphaned, EngineAct.Space, EnterKind.Newline)
        assertEquals(listOf(Cmd.Commit(" ")), spaced.cmds)
        assertEquals("", spaced.state.partialText)
        assertEquals("", spaced.state.partialSyllables)

        val punctuated = engine.reduce(orphaned, EngineAct.Direct("，"), EnterKind.Newline)
        assertEquals(listOf(Cmd.Commit("，")), punctuated.cmds)
        assertEquals("", punctuated.state.lastChineseContext)
        assertEquals("", punctuated.state.partialText)
    }

    @Test
    fun cursorMoveLeavesAnUnfinishedSegmentCompositionUntouched() {
        val state = EngineState(
            raw = "qsr",
            candidates = listOf(Candidate("其实人", "qi shi ren", 500_000, consumed = 3)),
            partialText = "你好",
            partialSyllables = "ni hao",
            lastChineseContext = "之前",
        )

        val moved = engine.reduce(state, EngineAct.MoveCursor(-1), EnterKind.Newline)
        assertEquals(emptyList<Cmd>(), moved.cmds)
        assertEquals(state, moved.state)
        assertEquals(Pending.None, moved.pending)
    }

    @Test
    fun erhuaMetadataTrimsOneSyllablePerCharacterWhenTheyMapOneToOne() {
        val trimmed = engine.reduce(
            EngineState(raw = "", partialText = "玩儿", partialSyllables = "wan er"),
            EngineAct.Backspace,
            EnterKind.Newline,
        )
        assertEquals("玩", trimmed.state.partialText)
        assertEquals("wan", trimmed.state.partialSyllables)
        assertEquals(listOf(Cmd.DeleteOne), trimmed.cmds)
    }

    @Test
    fun unmappableSegmentMetadataNeverLearnsAConcatenatedPhrase() {
        val trimmed = engine.reduce(
            EngineState(
                raw = "",
                partialText = "玩儿",
                partialSyllables = "wanr",
                lastChineseContext = "我",
            ),
            EngineAct.Backspace,
            EnterKind.Newline,
        )
        assertEquals("玩", trimmed.state.partialText)
        assertEquals("", trimmed.state.partialSyllables)
        assertEquals(listOf(Cmd.DeleteOne), trimmed.cmds)

        val completed = engine.reduce(
            trimmed.state.copy(
                raw = "er",
                candidates = listOf(Candidate("儿", "er", 100, consumed = 2)),
            ),
            EngineAct.Pick(0),
            EnterKind.Newline,
        )
        val learned = completed.cmds.filterIsInstance<Cmd.LearnZh>()
        assertEquals(listOf(Cmd.LearnZh("儿", "er", "我")), learned)
        assertTrue(learned.none { it.text == "玩儿" })
    }

    @Test
    fun repeatedSelectionKeepsSeparatorBoundariesAcrossBackspaceAndRetype() {
        val first = engine.reduce(
            EngineState(
                raw = "ni'hao'ma",
                candidates = listOf(Candidate("你", "ni", 100, consumed = 2)),
            ),
            EngineAct.Pick(0),
            EnterKind.Newline,
        )
        assertEquals("hao'ma", first.state.raw)
        assertEquals("你", first.state.partialText)
        assertEquals("ni", first.state.partialSyllables)

        val second = engine.reduce(
            first.state.copy(candidates = listOf(Candidate("好", "hao", 100, consumed = 3))),
            EngineAct.Pick(0),
            EnterKind.Newline,
        )
        assertEquals("ma", second.state.raw)
        assertEquals("你好", second.state.partialText)
        assertEquals("ni hao", second.state.partialSyllables)
        assertEquals(listOf(Cmd.Commit("好"), Cmd.Compose("ma")), second.cmds)

        val edited = engine.reduce(second.state, EngineAct.Backspace, EnterKind.Newline)
        assertEquals("m", edited.state.raw)
        assertEquals("你好", edited.state.partialText)
        assertEquals("ni hao", edited.state.partialSyllables)
        val retyped = engine.reduce(edited.state, EngineAct.Letter('a', null, ""), EnterKind.Newline)

        val completed = engine.reduce(
            retyped.state.copy(candidates = listOf(Candidate("吗", "ma", 100, consumed = 2))),
            EngineAct.Pick(0),
            EnterKind.Newline,
        )
        assertEquals("", completed.state.raw)
        assertEquals("", completed.state.partialText)
        assertEquals("", completed.state.partialSyllables)
        assertEquals(
            listOf(Cmd.Commit("吗"), Cmd.LearnZh("你好吗", "ni hao ma", "")),
            completed.cmds,
        )
    }
}
