package dev.cheng.keyboard

import dev.cheng.keyboard.ai.parseSseLine
import dev.cheng.keyboard.engine.Cmd
import dev.cheng.keyboard.engine.EngineAct
import dev.cheng.keyboard.engine.EngineState
import dev.cheng.keyboard.engine.InputEngine
import dev.cheng.keyboard.engine.LatinDecoder
import dev.cheng.keyboard.engine.PinyinDecoder
import dev.cheng.keyboard.engine.expandSyllable
import dev.cheng.keyboard.engine.applyAskDraft
import dev.cheng.keyboard.engine.previousWordUnits
import dev.cheng.keyboard.engine.segmentPinyin
import dev.cheng.keyboard.engine.shouldAutoCap
import dev.cheng.keyboard.engine.stripWrapped
import dev.cheng.keyboard.ime.gesture.KeyGesture
import dev.cheng.keyboard.ime.layout.placeKeys
import dev.cheng.keyboard.model.Choice
import dev.cheng.keyboard.model.EnterKind
import dev.cheng.keyboard.model.KeyKind
import dev.cheng.keyboard.model.KeyModel
import dev.cheng.keyboard.model.KeyboardPage
import dev.cheng.keyboard.model.Language
import dev.cheng.keyboard.model.LexRow
import dev.cheng.keyboard.model.MemoryLexicon
import dev.cheng.keyboard.model.ShiftState
import dev.cheng.keyboard.engine.SylTrie
import dev.cheng.keyboard.update.isNewerVersion
import dev.cheng.keyboard.update.parseLatestRelease
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardLogicTest {
    private val syllables = setOf(
        "a", "e", "o", "m", "n", "ni", "hao", "hai", "ha", "han", "hang",
        "zhong", "guo", "zong", "ren", "xi", "xian", "an", "de", "wo", "men", "shi",
    )

    @Test
    fun segmentsAmbiguousPinyin() {
        val trie = SylTrie(syllables)
        val xian = segmentPinyin("xian", trie).map { it.parts }
        assertTrue(xian.contains(listOf("xian")))
        assertTrue(xian.contains(listOf("xi", "an")))
        val partial = segmentPinyin("nih", trie)
        assertTrue(partial.any { it.partial && it.parts == listOf("ni", "h") })
    }

    @Test
    fun fuzzyInitialAndFinal() {
        assertTrue("zhong" in expandSyllable("zong"))
        assertTrue("zhan" in expandSyllable("zhang"))
        assertTrue("gang" in expandSyllable("gan"))
    }

    @Test
    fun thirdCharacterStaysInCandidates() {
        val decoder = PinyinDecoder(setOf("wo", "men", "de", "da", "ni", "hao", "ma", "m", "d"))
        decoder.lexicon = MemoryLexicon(
            listOf(
                LexRow("我", "wo", "wo", 100),
                LexRow("们", "men", "men", 90),
                LexRow("我们", "wo men", "women", 200),
                LexRow("的", "de", "de", 300),
                LexRow("大", "da", "da", 80),
                LexRow("你好", "ni hao", "nihao", 150),
                LexRow("吗", "ma", "ma", 70),
            ),
        )
        assertTrue(decoder.decode("womend", fuzzy = false).candidates.any { it.text == "我们的" })
        assertTrue(decoder.decode("womende", fuzzy = false).candidates.any { it.text == "我们的" })
        assertTrue(decoder.decode("nihaom", fuzzy = false).candidates.any { it.text.startsWith("你好") })
    }

    @Test
    fun commonPhrasesBeatFrequentCharacters() {
        val decoder = PinyinDecoder(
            setOf("ji", "xu", "jia", "you", "wo", "mei", "sha", "yi", "si"),
        )
        decoder.lexicon = MemoryLexicon(
            listOf(
                LexRow("级", "ji", "ji", 5_992_040),
                LexRow("许", "xu", "xu", 1_299_512),
                LexRow("家", "jia", "jia", 4_546_765),
                LexRow("有", "you", "you", 17_847_013),
                LexRow("又", "you", "you", 3_710_252),
                LexRow("继续", "ji xu", "jixu", 501_276),
                LexRow("加油", "jia you", "jiayou", 308_090),
                LexRow("家有", "jia you", "jiayou", 56_625),
                LexRow("我", "wo", "wo", 29_569_261),
                LexRow("没", "mei", "mei", 7_560_391),
                LexRow("啥", "sha", "sha", 976_190),
                LexRow("一", "yi", "yi", 35_278_860),
                LexRow("死", "si", "si", 1_979_349),
                LexRow("四", "si", "si", 1_598_102),
                LexRow("思", "si", "si", 1_438_567),
                LexRow("意思", "yi si", "yisi", 500_314),
                LexRow("一死", "yi si", "yisi", 23_175),
                LexRow("没啥", "mei sha", "meisha", 38_090),
                LexRow("我没", "wo mei", "womei", 205_370),
                LexRow("啥意思", "sha yi si", "shayisi", 4_815),
            ),
        )
        assertEquals("继续加油", decoder.decode("jixujiayou", fuzzy = false).candidates.first().text)
        assertEquals("我没啥意思", decoder.decode("womeishayisi", fuzzy = false).candidates.first().text)
    }

    @Test
    fun longPinyinIsJoinedFromWords() {
        val decoder = PinyinDecoder(setOf("wo", "men", "dou", "shi", "ren"))
        decoder.lexicon = MemoryLexicon(
            listOf(
                LexRow("我", "wo", "wo", 100),
                LexRow("们", "men", "men", 90),
                LexRow("我们", "wo men", "women", 200),
                LexRow("都", "dou", "dou", 80),
                LexRow("是", "shi", "shi", 300),
                LexRow("都是", "dou shi", "doushi", 250),
                LexRow("人", "ren", "ren", 120),
            ),
        )
        val result = decoder.decode("womendoushiren", fuzzy = false)
        assertTrue(result.candidates.any { it.text == "我们都是人" })
    }

    @Test
    fun initialsFindCommonWords() {
        val decoder = PinyinDecoder(setOf("n", "h", "ni", "hao"))
        decoder.lexicon = MemoryLexicon(
            listOf(LexRow("你好", "ni hao", "nihao", 500)),
        )
        assertTrue(decoder.decode("nh", fuzzy = false).candidates.any { it.text == "你好" })
    }

    @Test
    fun pinyinFindsWordAndFuzzyVariant() {
        val decoder = PinyinDecoder(syllables)
        decoder.lexicon = MemoryLexicon(
            listOf(
                LexRow("你", "ni", "ni", 50),
                LexRow("好", "hao", "hao", 40),
                LexRow("你好", "ni hao", "nihao", 80),
                LexRow("中国", "zhong guo", "zhongguo", 100),
                LexRow("先", "xian", "xian", 20),
                LexRow("西安", "xi an", "xian", 30),
            ),
        )
        val hello = decoder.decode("nihao", fuzzy = false)
        assertEquals("你好", hello.candidates.first().text)
        val china = decoder.decode("zongguo", fuzzy = true)
        assertEquals("中国", china.candidates.first().text)
        val both = decoder.decode("xian", fuzzy = false).candidates.map { it.text }
        assertTrue(both.contains("先"))
        assertTrue(both.contains("西安"))
    }

    @Test
    fun exactReadingOutranksFuzzy() {
        val decoder = PinyinDecoder(setOf("zong", "guo", "zhong"))
        decoder.lexicon = MemoryLexicon(
            listOf(
                LexRow("总国", "zong guo", "zongguo", 10),
                LexRow("中国", "zhong guo", "zhongguo", 1000),
            ),
        )
        val result = decoder.decode("zongguo", fuzzy = true)
        assertEquals("总国", result.candidates.first().text)
        assertTrue(result.candidates.any { it.text == "中国" })
    }

    @Test
    fun typosStillOfferTheIntendedWord() {
        val decoder = PinyinDecoder(setOf("ni", "hao"))
        decoder.lexicon = MemoryLexicon(
            listOf(
                LexRow("你", "ni", "ni", 50),
                LexRow("好", "hao", "hao", 40),
                LexRow("你好", "ni hao", "nihao", 80),
            ),
        )
        assertTrue(decoder.decode("nihso", fuzzy = false).candidates.any { it.text == "你好" })
        assertTrue(decoder.decode("niho", fuzzy = false).candidates.any { it.text == "你好" })
        assertTrue(decoder.decode("nihaao", fuzzy = false).candidates.any { it.text == "你好" })
        assertEquals("你好", decoder.decode("nihao", fuzzy = false).candidates.first().text)
    }

    @Test
    fun longPressChoosesSymbolOrCase() {
        val gesture = KeyGesture(slopPx = 16f, caseSlopPx = 24f, swipePx = 36f)
        val key = KeyModel("l-q", KeyKind.Letter, "q", hint = "1")
        gesture.down(key, 0f, 0f, 0L)
        val armed = gesture.alarm(350L)
        assertEquals(Choice.Symbol, armed.popup?.choice)
        val upper = gesture.move(-30f, 0f, 400L, key)
        assertEquals(Choice.Upper, upper.popup?.choice)
        val committed = gesture.up()
        val action = committed.actions.single() as EngineAct.Letter
        assertEquals(Choice.Upper, action.force)
        gesture.down(key, 0f, 0f, 0L)
        gesture.alarm(350L)
        gesture.move(40f, 0f, 400L, key)
        val lower = gesture.up().actions.single() as EngineAct.Letter
        assertEquals(Choice.Lower, lower.force)
        gesture.down(key, 0f, 0f, 0L)
        val tap = gesture.up().actions.single() as EngineAct.Letter
        assertEquals(null, tap.force)
    }

    @Test
    fun chineseSpaceCommitsCandidateAndSymbolConfirmsFirst() {
        val engine = InputEngine(LatinDecoder())
        var state = EngineState(
            candidates = listOf(dev.cheng.keyboard.model.Candidate("你", "ni", 10)),
            raw = "ni",
        )
        val space = engine.reduce(state, EngineAct.Space, EnterKind.Newline)
        assertEquals("你", (space.cmds.first() as Cmd.Commit).text)
        assertTrue(space.state.raw.isEmpty())
        state = EngineState(
            raw = "ni",
            candidates = listOf(dev.cheng.keyboard.model.Candidate("你", "ni", 10)),
        )
        val symbol = engine.reduce(state, EngineAct.Letter('h', Choice.Symbol, "!"), EnterKind.Newline)
        assertEquals(listOf("你", "!"), symbol.cmds.filterIsInstance<Cmd.Commit>().map { it.text })
    }

    @Test
    fun englishSpaceUsesSuggestion() {
        val latin = LatinDecoder()
        latin.load(sequenceOf("hello" to 100, "help" to 50))
        val engine = InputEngine(latin)
        val typed = engine.reduce(EngineState(language = Language.English), EngineAct.Letter('h', null, ""), EnterKind.Newline)
        val spaced = engine.reduce(typed.state, EngineAct.Space, EnterKind.Newline)
        val text = (spaced.cmds.first() as Cmd.Commit).text
        assertTrue(text.startsWith("hel"))
        assertTrue(text.endsWith(" "))
    }

    @Test
    fun frequentPhraseStillBeatsRareHomophone() {
        val decoder = PinyinDecoder(setOf("wei", "shen", "me"))
        decoder.lexicon = MemoryLexicon(
            listOf(
                LexRow("为什么", "wei shen me", "weishenme", 500_000),
                LexRow("喂什么", "wei shen me", "weishenme", 100),
                LexRow("为", "wei", "wei", 2_000_000),
                LexRow("什", "shen", "shen", 800_000),
                LexRow("么", "me", "me", 1_500_000),
            ),
        )
        assertEquals("为什么", decoder.decode("weishenme", fuzzy = false).candidates.first().text)
    }

    @Test
    fun abbreviationBeatsAdjacentCorrection() {
        val decoder = PinyinDecoder(setOf("bi", "da", "bei", "jing", "an"))
        decoder.lexicon = MemoryLexicon(
            listOf(
                LexRow("比大", "bi da", "bida", 80_000),
                LexRow("北京答案", "bei jing da an", "beijingdaan", 120_000),
            ),
        )
        val result = decoder.decode("bjda", fuzzy = false)
        assertEquals("北京答案", result.candidates.first().text)
        assertFalse(result.candidates.any { it.text == "比大" })
    }

    @Test
    fun mixedInitialAndSyllableFindsTheCommonWord() {
        val decoder = PinyinDecoder(setOf("n", "hao", "ni", "ha"))
        decoder.lexicon = MemoryLexicon(
            listOf(
                LexRow("嗯好", "n hao", "nhao", 30),
                LexRow("你", "ni", "ni", 2_000_000),
                LexRow("哈", "ha", "ha", 2_000),
                LexRow("好", "hao", "hao", 1_500_000),
                LexRow("你好", "ni hao", "nihao", 400_000),
            ),
        )
        assertEquals("你好", decoder.decode("nhao", fuzzy = false).candidates.first().text)
        assertEquals("你好", decoder.decode("niha", fuzzy = false).candidates.first().text)
    }

    @Test
    fun plainInitialDoesNotPreferUmlaut() {
        val decoder = PinyinDecoder(setOf("n", "h", "ni", "hao", "nv", "hai"))
        decoder.lexicon = MemoryLexicon(
            listOf(
                LexRow("女孩", "nv hai", "nvhai", 500_000),
                LexRow("你好", "ni hao", "nihao", 300_000),
            ),
        )
        assertEquals("你好", decoder.decode("nh", fuzzy = false).candidates.first().text)
        assertEquals("女孩", decoder.decode("nvh", fuzzy = false).candidates.first().text)
    }

    @Test
    fun lowFrequencyNameBeatsCommonCharacters() {
        val decoder = PinyinDecoder(setOf("te", "lang", "pu"))
        decoder.lexicon = MemoryLexicon(
            listOf(
                LexRow("特", "te", "te", 1_314_148),
                LexRow("狼", "lang", "lang", 360_398),
                LexRow("朗", "lang", "lang", 149_504),
                LexRow("普", "pu", "pu", 317_637),
                LexRow("特朗普", "te lang pu", "telangpu", 100),
            ),
        )
        assertEquals("特朗普", decoder.decode("telangpu", fuzzy = false).candidates.first().text)
    }

    @Test
    fun nameBeatsAFrequentPartialWord() {
        val decoder = PinyinDecoder(setOf("ma", "si", "ke", "zhang", "yi", "ming"))
        decoder.lexicon = MemoryLexicon(
            listOf(
                LexRow("吗", "ma", "ma", 5_000_000),
                LexRow("死磕", "si ke", "sike", 80_000),
                LexRow("马斯克", "ma si ke", "masike", 100),
                LexRow("长", "zhang", "zhang", 2_000_000),
                LexRow("一名", "yi ming", "yiming", 90_000),
                LexRow("张一鸣", "zhang yi ming", "zhangyiming", 100),
            ),
        )
        assertEquals("马斯克", decoder.decode("masike", fuzzy = false).candidates.first().text)
        assertEquals("张一鸣", decoder.decode("zhangyiming", fuzzy = false).candidates.first().text)
    }

    @Test
    fun learnedCharacterDoesNotTakeOverLongerPhrases() {
        val decoder = PinyinDecoder(setOf("wo", "men", "hao"))
        decoder.lexicon = MemoryLexicon(
            listOf(
                LexRow("我", "wo", "wo", 2_000_000),
                LexRow("们", "men", "men", 800_000),
                LexRow("我们", "wo men", "women", 500_000),
                LexRow("好", "hao", "hao", 7_000_000),
                LexRow("号", "hao", "hao", 500_000),
            ),
        )
        decoder.learn("号", "hao")
        val phrase = decoder.decode("womenhao", fuzzy = false)
        assertEquals("我们好", phrase.candidates.first().text)
        val syllable = decoder.decode("hao", fuzzy = false)
        assertEquals("号", syllable.candidates.first().text)
    }

    @Test
    fun prefixCandidateKeepsTheRemainingPinyin() {
        val decoder = PinyinDecoder(setOf("wo", "men", "hao"))
        decoder.lexicon = MemoryLexicon(
            listOf(
                LexRow("我", "wo", "wo", 2_000_000),
                LexRow("们", "men", "men", 800_000),
                LexRow("我们", "wo men", "women", 500_000),
                LexRow("好", "hao", "hao", 1_500_000),
            ),
        )
        val result = decoder.decode("womenhao", fuzzy = false)
        assertEquals("我们好", result.candidates.first().text)
        assertEquals(8, result.candidates.first().consumed)
        val prefix = result.candidates.first { it.text == "我们" }
        assertEquals(5, prefix.consumed)
        val engine = InputEngine(LatinDecoder())
        val state = EngineState(raw = "wo'men'hao", candidates = result.candidates)
        val picked = engine.reduce(state, EngineAct.Pick(result.candidates.indexOf(prefix)), EnterKind.Newline)
        assertEquals("我们", (picked.cmds.first() as Cmd.Commit).text)
        assertEquals("hao", picked.cmds.filterIsInstance<Cmd.Compose>().single().text)
        assertEquals("hao", picked.state.raw)
        val spaced = engine.reduce(state, EngineAct.Space, EnterKind.Newline)
        assertEquals("我们好", (spaced.cmds.first() as Cmd.Commit).text)
        assertTrue(spaced.state.raw.isEmpty())
        assertTrue(spaced.cmds.none { it is Cmd.Compose })
    }

    @Test
    fun homophoneListsAreNotCutAtTwelve() {
        val decoder = PinyinDecoder(setOf("yi", "zhi"))
        val chars = (0 until 20).map { index ->
            LexRow(Character.toString(0x4E00 + index), "yi", "yi", 2_000 - index)
        }
        val phrases = (0 until 10).map { index ->
            LexRow("词" + Character.toString(0x4E00 + index), "yi zhi", "yizhi", 2_000 - index)
        }
        decoder.lexicon = MemoryLexicon(chars + phrases)
        val syllable = decoder.decode("yi", fuzzy = false)
        assertEquals(20, syllable.candidates.size)
        assertEquals("一", syllable.candidates.first().text)
        val phrase = decoder.decode("yizhi", fuzzy = false)
        assertEquals(10, phrase.candidates.count { it.text.startsWith("词") })
        assertEquals("词一", phrase.candidates.first().text)
    }

    @Test
    fun singleSyllableStaysAheadOfTheSplit() {
        val decoder = PinyinDecoder(setOf("xi", "an", "xian"))
        decoder.lexicon = MemoryLexicon(
            listOf(
                LexRow("先", "xian", "xian", 20),
                LexRow("西安", "xi an", "xian", 30),
            ),
        )
        assertEquals("先", decoder.decode("xian", fuzzy = false).candidates.first().text)
    }

    @Test
    fun chineseEnterCommitsUnsegmentedPinyin() {
        val engine = InputEngine(LatinDecoder())
        val state = EngineState(
            raw = "ni'hao",
            preedit = "ni'hao",
            candidates = listOf(dev.cheng.keyboard.model.Candidate("你好", "ni hao", 80)),
        )
        val step = engine.reduce(state, EngineAct.Enter, EnterKind.Send)
        assertEquals(listOf("nihao"), step.cmds.filterIsInstance<Cmd.Commit>().map { it.text })
        assertTrue(step.cmds.none { it is Cmd.EditorAction })
        assertTrue(step.state.raw.isEmpty())
        val idle = engine.reduce(EngineState(), EngineAct.Enter, EnterKind.Newline)
        assertEquals("\n", (idle.cmds.single() as Cmd.Commit).text)
    }

    @Test
    fun separatorSplitsXian() {
        val decoder = PinyinDecoder(setOf("xi", "an", "xian", "a"))
        decoder.lexicon = MemoryLexicon(
            listOf(
                LexRow("先", "xian", "xian", 50),
                LexRow("西安", "xi an", "xian", 80),
                LexRow("西", "xi", "xi", 40),
            ),
        )
        assertTrue(decoder.decode("xi'an", fuzzy = false).candidates.any { it.text == "西安" })
        val engine = InputEngine(LatinDecoder())
        val typed = engine.reduce(
            EngineState(raw = "xi"),
            EngineAct.Letter('a', null, ""),
            EnterKind.Newline,
        )
        val split = engine.reduce(EngineState(raw = "xi"), EngineAct.Shift, EnterKind.Newline)
        assertEquals("xi'", split.state.raw)
        assertEquals(ShiftState.Off, split.state.shift)
        val idle = engine.reduce(EngineState(), EngineAct.Shift, EnterKind.Newline)
        assertEquals(ShiftState.Once, idle.state.shift)
        assertTrue(typed.state.raw == "xia")
    }

    @Test
    fun shiftCyclesAndPasswordCommitsDirectly() {
        val engine = InputEngine(LatinDecoder())
        val once = engine.reduce(EngineState(), EngineAct.Shift, EnterKind.Newline).state
        assertEquals(ShiftState.Once, once.shift)
        val caps = engine.reduce(once, EngineAct.Shift, EnterKind.Newline).state
        assertEquals(ShiftState.Caps, caps.shift)
        val password = engine.reduce(
            EngineState(password = true),
            EngineAct.Letter('a', null, "~"),
            EnterKind.Newline,
        )
        assertEquals("a", (password.cmds.single() as Cmd.Commit).text)
        assertTrue(password.state.raw.isEmpty())
    }

    @Test
    fun layoutInsetsTheHomeRow() {
        val placed = placeKeys(KeyboardPage.Letters, Language.Chinese, 1000f, 48f, 6f, 10f, 4f)
        val q = placed.keys.first { it.model.id == "l-q" }
        val a = placed.keys.first { it.model.id == "l-a" }
        assertTrue(a.x > q.x)
        assertEquals(26, placed.keys.count { it.model.kind == KeyKind.Letter })
        assertTrue(placed.height > 48f * 4)
    }

    @Test
    fun textHelpers() {
        assertTrue(shouldAutoCap(""))
        assertTrue(shouldAutoCap("Hi. "))
        assertFalse(shouldAutoCap("Hi"))
        assertEquals(6, previousWordUnits("hello world"))
        assertEquals("你好", stripWrapped("“你好”"))
        assertEquals("你好", applyAskDraft("你", listOf(Cmd.Compose("hao"), Cmd.Commit("好"), Cmd.Commit("\n"))))
        assertEquals("你", applyAskDraft("你好", listOf(Cmd.DeleteOne)))
        assertEquals("你好", applyAskDraft("你好 世界", listOf(Cmd.DeleteWord)))
        assertEquals("hi", parseSseLine("data: {\"content\":\"hi\"}")?.content)
        assertEquals(true, parseSseLine("data: [DONE]")?.done)
    }

    @Test
    fun newerReleaseIsChosenFromTheVersionNumbers() {
        assertTrue(isNewerVersion("0.1.2", "0.1.1"))
        assertTrue(isNewerVersion("v0.1.10", "0.1.9"))
        assertTrue(isNewerVersion("0.2", "0.1.9"))
        assertFalse(isNewerVersion("v0.1.1", "0.1.1"))
        assertFalse(isNewerVersion("0.1.1", "0.1.2"))
        assertFalse(isNewerVersion("0.1", "0.1.0"))
    }

    @Test
    fun latestReleaseJsonPicksTheApk() {
        val body = """
            {"tag_name":"v0.1.2","body":"修复闪烁","assets":[
              {"name":"notes.txt","url":"https://example.test/notes","size":10,"content_type":"text/plain"},
              {"name":"myboard-0.1.2.apk","url":"https://example.test/apk","size":20,"content_type":"application/vnd.android.package-archive"}
            ]}
        """.trimIndent()
        val release = parseLatestRelease(body)
        assertEquals("0.1.2", release?.version)
        assertEquals("https://example.test/apk", release?.assetUrl)
        assertEquals("修复闪烁", release?.notes)
        assertEquals(null, parseLatestRelease("{}"))
    }
}
