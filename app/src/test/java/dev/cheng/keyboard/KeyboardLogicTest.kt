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
import dev.cheng.keyboard.engine.applyCase
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

    @Test
    fun pinyinNormalizesNueLueAndUmlautAliases() {
        val decoder = PinyinDecoder(setOf("zhan", "lve", "nve", "dai", "jue", "ding", "xue", "xi"))
        decoder.lexicon = MemoryLexicon(
            listOf(
                LexRow("战略", "zhan lve", "zhanlve", 500_000),
                LexRow("虐待", "nve dai", "nvedai", 300_000),
                LexRow("决定", "jue ding", "jueding", 600_000),
                LexRow("学习", "xue xi", "xuexi", 800_000),
            ),
        )
        assertEquals("战略", decoder.decode("zhanlue", fuzzy = false).candidates.first().text)
        assertEquals("虐待", decoder.decode("nuedai", fuzzy = false).candidates.first().text)
        assertEquals("决定", decoder.decode("jveding", fuzzy = false).candidates.first().text)
        assertEquals("学习", decoder.decode("xvexi", fuzzy = false).candidates.first().text)
    }

    @Test
    fun expandedFuzzyPinyinCoversFHAndOngEng() {
        assertTrue("fu" in expandSyllable("hu"))
        assertTrue("dong" in expandSyllable("deng"))
        val decoder = PinyinDecoder(setOf("fu", "hu", "jian", "dong", "deng", "xi"))
        decoder.lexicon = MemoryLexicon(
            listOf(
                LexRow("福建", "fu jian", "fujian", 500_000),
                LexRow("东西", "dong xi", "dongxi", 700_000),
            ),
        )
        assertTrue(decoder.decode("hujian", fuzzy = true).candidates.any { it.text == "福建" })
        assertTrue(decoder.decode("dengxi", fuzzy = true).candidates.any { it.text == "东西" })
    }

    @Test
    fun progressiveSelectionLearnsComposedChinesePhrase() {
        val decoder = PinyinDecoder(setOf("ren", "gong", "zhi", "neng", "ti"))
        decoder.lexicon = MemoryLexicon(
            listOf(
                LexRow("人工", "ren gong", "rengong", 400_000),
                LexRow("智能", "zhi neng", "zhineng", 500_000),
                LexRow("人工智能", "ren gong zhi neng", "rengongzhineng", 600_000),
                LexRow("体", "ti", "ti", 300_000),
            ),
        )
        val engine = InputEngine(LatinDecoder())
        val firstDecode = decoder.decode("rengongzhinengti", fuzzy = false)
        val prefixIndex = firstDecode.candidates.indexOfFirst { it.text == "人工智能" }
        assertTrue(prefixIndex >= 0)

        var state = EngineState(raw = "rengongzhinengti", candidates = firstDecode.candidates)
        val step1 = engine.reduce(state, EngineAct.Pick(prefixIndex), EnterKind.Newline)
        assertEquals("ti", step1.state.raw)
        assertEquals("人工智能", step1.state.partialText)

        val secondDecode = decoder.decode(step1.state.raw, fuzzy = false)
        state = step1.state.copy(candidates = secondDecode.candidates)
        val step2 = engine.reduce(state, EngineAct.Pick(0), EnterKind.Newline)
        val learned = step2.cmds.filterIsInstance<Cmd.LearnZh>().single()
        assertEquals("人工智能体", learned.text)
        assertEquals("ren gong zhi neng ti", learned.syllables)

        decoder.learn(learned.text, learned.syllables)
        assertEquals("人工智能体", decoder.decode("rengongzhinengti", fuzzy = false).candidates.first().text)
    }

    @Test
    fun chineseInlineEnglishAndMidWordLanguageSwitchKeepInput() {
        val latin = LatinDecoder().apply {
            load(sequenceOf("wifi" to 500_000, "hello" to 800_000, "app" to 600_000))
        }
        val decoder = PinyinDecoder(setOf("a", "ni", "hao")).apply {
            this.latin = latin
            lexicon = MemoryLexicon(listOf(LexRow("阿", "a", "a", 500_000)))
        }
        assertEquals("wifi", decoder.decode("wifi", fuzzy = false).candidates.first().text)
        assertEquals("hello", decoder.decode("hello", fuzzy = false).candidates.first().text)

        val engine = InputEngine(latin)
        var state = EngineState(language = Language.Chinese, raw = "hel", preedit = "hel")
        val switched = engine.reduce(state, EngineAct.Language, EnterKind.Newline)
        assertEquals(Language.English, switched.state.language)
        assertEquals("hel", switched.state.raw)
        assertEquals("hello", switched.state.candidates.first().text)
    }

    @Test
    fun englishContractionsPronounAndExactWordPriority() {
        val latin = LatinDecoder().apply {
            load(sequenceOf("app" to 50_000, "application" to 80_000, "don't" to 400_000, "ill" to 90_000))
        }
        val engine = InputEngine(latin)

        // Complete valid word "app" is not hijacked by higher-frequency longer word "application"
        assertEquals("app", latin.suggest("app").first().text)

        // Contraction auto-apostrophe and standalone "i" -> "I"
        assertEquals("don't", latin.suggest("dont").first().text)
        assertEquals("I'm", applyCase(latin.suggest("im").first().text, "im"))
        assertTrue(latin.suggest("ill").map { it.text }.containsAll(listOf("ill", "i'll")))

        var state = EngineState(language = Language.English)
        state = engine.reduce(state, EngineAct.Letter('i', null, ""), EnterKind.Newline).state
        val step = engine.reduce(state, EngineAct.Space, EnterKind.Newline)
        assertEquals("I ", (step.cmds.first() as Cmd.Commit).text)
    }

    @Test
    fun englishTypoAutocorrectUndoAndSmartPunctuation() {
        val latin = LatinDecoder().apply {
            load(sequenceOf("the" to 2_000_000, "hello" to 800_000, "thanks" to 600_000, "world" to 700_000))
        }
        assertEquals("the", latin.suggest("teh").first().text)
        assertEquals("hello", latin.suggest("hellp").first().text)
        assertEquals("thanks", latin.suggest("thnks").first().text)

        val engine = InputEngine(latin)
        var state = EngineState(language = Language.English)
        for (ch in "teh") {
            state = engine.reduce(state, EngineAct.Letter(ch, null, ""), EnterKind.Newline).state
        }
        // Space autocorrects "teh" -> "the "
        val spaced = engine.reduce(state, EngineAct.Space, EnterKind.Newline)
        assertEquals("the ", (spaced.cmds.first() as Cmd.Commit).text)

        // Immediate Backspace undoes autocorrect back to "teh" and preserves verbatim on next Space
        val undone = engine.reduce(spaced.state, EngineAct.Backspace, EnterKind.Newline)
        assertEquals("teh", undone.state.raw)
        assertTrue(undone.cmds.any { it is Cmd.DeleteCount && it.count == 4 })
        val kept = engine.reduce(undone.state, EngineAct.Space, EnterKind.Newline)
        assertEquals("teh ", (kept.cmds.first() as Cmd.Commit).text)

        // Smart punctuation swallows trailing auto-space ("teh " + "." -> "teh. ") and arms Shift
        val punctuated = engine.reduce(kept.state, EngineAct.Direct("."), EnterKind.Newline)
        assertTrue(punctuated.cmds.first() is Cmd.DeleteOne)
        assertEquals(". ", (punctuated.cmds[1] as Cmd.Commit).text)
        assertEquals(ShiftState.Once, punctuated.state.shift)

        // Double-space after word commit inserts ". " and arms Shift
        val wordStep = engine.reduce(
            engine.reduce(EngineState(language = Language.English), EngineAct.Letter('h', null, ""), EnterKind.Newline).state,
            EngineAct.Space,
            EnterKind.Newline,
        )
        val doubleSpaced = engine.reduce(wordStep.state, EngineAct.Space, EnterKind.Newline)
        assertTrue(doubleSpaced.cmds.first() is Cmd.DeleteOne)
        assertEquals(". ", (doubleSpaced.cmds[1] as Cmd.Commit).text)
        assertEquals(ShiftState.Once, doubleSpaced.state.shift)
    }

    @Test
    fun englishBigramPredictionAndFlickAndSpaceCursorGestures() {
        val latin = LatinDecoder().apply {
            load(sequenceOf("thank" to 500_000, "you" to 1_000_000))
        }
        val engine = InputEngine(latin)
        var state = EngineState(language = Language.English)
        for (ch in "thank") {
            state = engine.reduce(state, EngineAct.Letter(ch, null, ""), EnterKind.Newline).state
        }
        val committed = engine.reduce(state, EngineAct.Space, EnterKind.Newline)
        assertTrue(committed.state.associating)
        assertEquals("you", committed.state.candidates.first().text)

        // Flick up on letter -> Choice.Symbol; flick down -> Choice.Upper
        val gesture = KeyGesture(slopPx = 16f, caseSlopPx = 24f, swipePx = 36f)
        val qKey = KeyModel("l-q", KeyKind.Letter, "q", hint = "1")
        gesture.down(qKey, 50f, 50f, 0L)
        val flickUp = gesture.move(50f, 5f, 60L, null)
        assertEquals(Choice.Symbol, flickUp.popup?.choice)
        assertEquals(Choice.Symbol, (gesture.up().actions.single() as EngineAct.Letter).force)

        gesture.down(qKey, 50f, 50f, 0L)
        val flickDown = gesture.move(50f, 95f, 60L, null)
        assertEquals(Choice.Upper, flickDown.popup?.choice)
        assertEquals(Choice.Upper, (gesture.up().actions.single() as EngineAct.Letter).force)

        // Spacebar horizontal slide moves cursor without committing a space on release
        val spaceKey = KeyModel("space", KeyKind.Space, "")
        gesture.down(spaceKey, 100f, 50f, 0L)
        val slide = gesture.move(140f, 50f, 50L, spaceKey)
        assertTrue(slide.actions.single() is EngineAct.MoveCursor)
        assertTrue(gesture.up().actions.isEmpty())
    }

    @Test
    fun multiWordMixedAndPureAbbreviationsNeverReturnEmpty() {
        val decoder = PinyinDecoder(
            setOf(
                "a", "e", "o", "m", "n",
                "jin", "tian", "qi", "zhe", "shi", "shen", "me",
                "wo", "men", "meng", "gong", "si", "zuo", "bu", "zhi", "dao",
                "zhong", "guo", "ren", "ni", "hao", "ma", "you", "kong",
            ),
        )
        decoder.lexicon = MemoryLexicon(
            listOf(
                LexRow("今天", "jin tian", "jintian", 600_000),
                LexRow("天气", "tian qi", "tianqi", 500_000),
                LexRow("这是", "zhe shi", "zheshi", 550_000),
                LexRow("什么", "shen me", "shenme", 700_000),
                LexRow("我", "wo", "wo", 2_000_000),
                LexRow("我们", "wo men", "women", 900_000),
                LexRow("梦", "meng", "meng", 150_000),
                LexRow("死", "si", "si", 100_000),
                LexRow("公司", "gong si", "gongsi", 650_000),
                LexRow("工作", "gong zuo", "gongzuo", 700_000),
                LexRow("不知道", "bu zhi dao", "buzhidao", 500_000),
                LexRow("我是", "wo shi", "woshi", 600_000),
                LexRow("中国人", "zhong guo ren", "zhongguoren", 450_000),
                LexRow("你好", "ni hao", "nihao", 800_000),
                LexRow("吗", "ma", "ma", 1_200_000),
                LexRow("有空", "you kong", "youkong", 350_000),
            ),
        )

        assertEquals("今天天气", decoder.decode("jintiantq", fuzzy = false).candidates.first().text)
        assertEquals("这是什么", decoder.decode("zheshism", fuzzy = false).candidates.first().text)
        assertEquals("我不知道", decoder.decode("wobzd", fuzzy = false).candidates.first().text)
        assertEquals("我不知道", decoder.decode("wbzd", fuzzy = false).candidates.first().text)
        assertEquals("我是中国人", decoder.decode("wszgr", fuzzy = false).candidates.first().text)
        assertEquals("我是中国人", decoder.decode("woshzgr", fuzzy = false).candidates.first().text)
        assertEquals("你好吗", decoder.decode("nhm", fuzzy = false).candidates.first().text)
        assertEquals("我们公司", decoder.decode("womengs", fuzzy = false).candidates.first().text)
        assertEquals("我们工作", decoder.decode("womengz", fuzzy = false).candidates.first().text)
        assertEquals("今天有空吗", decoder.decode("jintianyoukma", fuzzy = false).candidates.first().text)
    }

    @Test
    fun manualSeparatorErhuaAndDeadEndSyllablesStayDecodableInSentences() {
        val decoder = PinyinDecoder(
            setOf(
                "a", "e", "o", "m", "n",
                "wo", "de", "fang", "fan", "gan", "an", "hen", "hao",
                "ni", "qu", "na", "er", "le", "wan", "yi", "dian", "dou", "bu",
                "zhei", "zhe", "ge", "shi", "shen", "me", "en", "ma",
            ),
        )
        decoder.lexicon = MemoryLexicon(
            listOf(
                LexRow("我", "wo", "wo", 2_000_000),
                LexRow("的", "de", "de", 3_000_000),
                LexRow("我的", "wo de", "wode", 800_000),
                LexRow("方案", "fang an", "fangan", 450_000),
                LexRow("很好", "hen hao", "henhao", 500_000),
                LexRow("你", "ni", "ni", 1_500_000),
                LexRow("去", "qu", "qu", 900_000),
                LexRow("你去", "ni qu", "niqu", 300_000),
                LexRow("哪儿", "na er", "naer", 450_000),
                LexRow("了", "le", "le", 2_500_000),
                LexRow("玩儿", "wan er", "waner", 250_000),
                LexRow("一点儿", "yi dian er", "yidianer", 350_000),
                LexRow("都不", "dou bu", "doubu", 300_000),
                LexRow("这", "zhe", "zhe", 1_800_000),
                LexRow("这个", "zhei ge", "zheige", 200_000),
                LexRow("是", "shi", "shi", 2_200_000),
                LexRow("什么", "shen me", "shenme", 700_000),
                LexRow("嗯", "en", "en", 400_000),
                LexRow("你好", "ni hao", "nihao", 800_000),
                LexRow("吗", "ma", "ma", 1_100_000),
            ),
        )

        // Multi-syllable manual apostrophe separator inside a longer phrase
        assertEquals("我的方案很好", decoder.decode("wodefang'anhenhao", fuzzy = false).candidates.first().text)

        // Trailing and mid-sentence erhua with exact consumed length for partial selection
        assertEquals("玩儿", decoder.decode("wanr", fuzzy = false).candidates.first().text)
        assertEquals("你去哪儿", decoder.decode("niqunar", fuzzy = false).candidates.first().text)
        val erhuaSentence = decoder.decode("niqunarle", fuzzy = false)
        assertEquals("你去哪儿了", erhuaSentence.candidates.first().text)
        assertEquals(9, erhuaSentence.candidates.first().consumed)
        assertEquals("一点儿都不", decoder.decode("yidianrdoubu", fuzzy = false).candidates.first().text)

        // Colloquial "zhei" and single-letter "n"/"m" missing standalone nsy=1 entries never collapse multi-word DP
        val zheiResult = decoder.decode("zheishishenme", fuzzy = false)
        assertEquals("这是什么", zheiResult.candidates.first().text)
        assertTrue(decoder.decode("nihaon", fuzzy = false).candidates.isNotEmpty())
        assertTrue(decoder.decode("nihaom", fuzzy = false).candidates.any { it.text == "你好吗" })
    }

    @Test
    fun longSentencesUnparseableTailsAndRichDictTyposAlwaysProduceCandidates() {
        val decoder = PinyinDecoder(
            setOf(
                "jin", "tian", "wan", "shang", "wo", "men", "dou", "qu", "chi", "fan",
                "ni", "hao", "he", "shi", "o", "zai", "zi",
            ),
        )
        decoder.lexicon = MemoryLexicon(
            listOf(
                LexRow("今", "jin", "jin", 400_000),
                LexRow("今天", "jin tian", "jintian", 800_000),
                LexRow("晚上", "wan shang", "wanshang", 700_000),
                LexRow("我", "wo", "wo", 2_000_000),
                LexRow("我们", "wo men", "women", 900_000),
                LexRow("都", "dou", "dou", 1_000_000),
                LexRow("去", "qu", "qu", 1_100_000),
                LexRow("吃饭", "chi fan", "chifan", 650_000),
                LexRow("你", "ni", "ni", 1_800_000),
                LexRow("好", "hao", "hao", 1_600_000),
                LexRow("你好", "ni hao", "nihao", 900_000),
                LexRow("合适", "he shi", "heshi", 350_000),
                LexRow("哦", "o", "o", 500_000),
                LexRow("在", "zai", "zai", 2_100_000),
                LexRow("子", "zi", "zi", 400_000),
            ),
        )

        // 10-character full sentence decodes completely AND keeps leading word "今天" for left-to-right progressive confirm
        val longSentence = decoder.decode("jintianwanshangwomendouquchifan", fuzzy = false)
        assertEquals("今天晚上我们都去吃饭", longSentence.candidates.first().text)
        assertTrue(longSentence.candidates.any { it.text == "今天" && it.consumed == 7 })

        // Unparseable trailing letters ("xyz" / "i") never blank out candidates; picking prefix keeps the tail
        val withBadTail = decoder.decode("womenjintianxyz", fuzzy = false)
        assertTrue(withBadTail.candidates.isNotEmpty())
        assertEquals("我们今天", withBadTail.candidates.first().text)
        assertEquals(12, withBadTail.candidates.first().consumed)
        assertEquals("wo'men'jin'tian'xyz", withBadTail.preedit)

        val engine = InputEngine(LatinDecoder())
        val picked = engine.reduce(
            EngineState(raw = "womenjintianxyz", candidates = withBadTail.candidates),
            EngineAct.Pick(0),
            EnterKind.Newline,
        )
        assertEquals("我们今天", (picked.cmds.first() as Cmd.Commit).text)
        assertEquals("xyz", picked.state.raw)

        assertTrue(decoder.decode("womeni", fuzzy = false).candidates.isNotEmpty())
        assertTrue(decoder.decode("womenii", fuzzy = false).candidates.isNotEmpty())
        val trailingTripleI = decoder.decode("womeniii", fuzzy = false)
        assertEquals("我们", trailingTripleI.candidates.first().text)
        assertEquals(5, trailingTripleI.candidates.first().consumed)

        // Even when "合适" (hs), "哦" (o), and "子" (zi) exist in the dictionary, typos still offer intended words
        assertTrue(decoder.decode("nihso", fuzzy = false).candidates.any { it.text == "你好" })
        assertTrue(decoder.decode("nihsow", fuzzy = false).candidates.any { it.text == "你好我" })
        assertTrue(decoder.decode("nihsoz", fuzzy = false).candidates.any { it.text == "你好在" })
    }
}


