package dev.cheng.keyboard

import dev.cheng.keyboard.ai.HostCategory
import dev.cheng.keyboard.ai.PolishTone
import dev.cheng.keyboard.ai.appCategoryOf
import dev.cheng.keyboard.ai.buildPolishUserPrompt
import dev.cheng.keyboard.ai.hostSceneOf
import dev.cheng.keyboard.ai.parsePolishStream
import dev.cheng.keyboard.ai.parseSseLine
import dev.cheng.keyboard.data.MAX_RECENT_CLIPS
import dev.cheng.keyboard.data.decodeRecentClips
import dev.cheng.keyboard.data.encodeRecentClips
import dev.cheng.keyboard.data.mergeRecentClipLists
import dev.cheng.keyboard.data.mergeRecentClips
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
import dev.cheng.keyboard.engine.resolveSmartCommit
import dev.cheng.keyboard.engine.segmentPinyin
import dev.cheng.keyboard.engine.shouldAutoCap
import dev.cheng.keyboard.engine.stripWrapped
import dev.cheng.keyboard.ime.gesture.KeyGesture
import dev.cheng.keyboard.ime.layout.hitKey
import dev.cheng.keyboard.ime.layout.nearbyLetters
import dev.cheng.keyboard.ime.layout.placeKeys
import dev.cheng.keyboard.ime.symbol.symbolCategories
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
import dev.cheng.keyboard.update.RemoteRelease
import dev.cheng.keyboard.update.isNewerRelease
import dev.cheng.keyboard.update.isNewerVersion
import dev.cheng.keyboard.update.parseLatestRelease
import dev.cheng.keyboard.update.parseLatestRedirectTag
import dev.cheng.keyboard.update.sha256Hex
import dev.cheng.keyboard.update.verifyDownloadedFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import java.util.zip.GZIPInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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
        val gesture = KeyGesture(slopPx = 16f, caseSlopPx = 24f, swipePx = 36f, verticalFlickPx = 45f)
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

    @Test
    fun chineseLeftContextDisambiguatesHomophonesAndSupportsForget() {
        val decoder = PinyinDecoder(setOf("gong", "shi", "de", "xiang", "mu"))
        decoder.lexicon = MemoryLexicon(
            listOf(
                LexRow("公示", "gong shi", "gongshi", 242_500),
                LexRow("公式", "gong shi", "gongshi", 206_515),
                LexRow("共识", "gong shi", "gongshi", 161_800),
                LexRow("数学公式", "shu xue gong shi", "shuxuegongshi", 10_073),
                LexRow("达成共识", "da cheng gong shi", "dachenggongshi", 34_460),
                LexRow("的", "de", "de", 30_000_000),
                LexRow("地", "de", "de", 2_000_000),
                LexRow("得", "de", "de", 1_800_000),
                LexRow("觉得", "jue de", "juede", 600_000),
                LexRow("向目", "xiang mu", "xiangmu", 500_000),
                LexRow("项目", "xiang mu", "xiangmu", 400_000),
            ),
        )

        assertEquals("公示", decoder.decode("gongshi", fuzzy = false).candidates.first().text)
        assertEquals("公式", decoder.decode("gongshi", fuzzy = false, context = "数学").candidates.first().text)
        assertEquals("共识", decoder.decode("gongshi", fuzzy = false, context = "达成").candidates.first().text)
        assertEquals("得", decoder.decode("de", fuzzy = false, context = "觉").candidates.first().text)
        assertEquals("地", decoder.decode("de", fuzzy = false, context = "慢慢").candidates.first().text)

        // User bigram + forget learned word
        decoder.learn("项目", "xiang mu", prevContext = "核心")
        assertTrue(decoder.associate("核心").any { it.text == "项目" })
        assertEquals("项目", decoder.decode("xiangmu", fuzzy = false, context = "核心").candidates.first().text)
        assertTrue(decoder.forget("项目"))
        assertEquals("向目", decoder.decode("xiangmu", fuzzy = false).candidates.first().text)
    }

    @Test
    fun transpositionsPseudoSyllableTyposAndSpatialTouchHintsRecoverIntendedWords() {
        val decoder = PinyinDecoder(
            setOf("a", "e", "o", "m", "n", "wo", "men", "shen", "me", "ni", "ha", "hao", "ping"),
        )
        decoder.lexicon = MemoryLexicon(
            listOf(
                LexRow("我", "wo", "wo", 2_000_000),
                LexRow("鹅", "e", "e", 50_000),
                LexRow("嗯", "en", "en", 400_000),
                LexRow("我们", "wo men", "women", 900_000),
                LexRow("深", "shen", "shen", 500_000),
                LexRow("什么", "shen me", "shenme", 700_000),
                LexRow("你", "ni", "ni", 1_800_000),
                LexRow("哈", "ha", "ha", 200_000),
                LexRow("平", "ping", "ping", 500_000),
                LexRow("你好", "ni hao", "nihao", 850_000),
            ),
        )

        assertEquals("我们", decoder.decode("woemn", fuzzy = false).candidates.first().text)
        assertEquals("什么", decoder.decode("shenem", fuzzy = false).candidates.first().text)
        assertEquals("你好", decoder.decode("nihoa", fuzzy = false).candidates.first().text)
        assertEquals("你好", decoder.decode("nhiao", fuzzy = false).candidates.first().text)
        assertEquals(
            "你好",
            decoder.decode("nihap", fuzzy = false, touchHints = listOf("", "", "", "", "o")).candidates.first().text,
        )

        val placed = placeKeys(KeyboardPage.Letters, Language.Chinese, 1000f, 48f, 6f, 10f, 4f)
        val pKey = placed.keys.first { it.model.id == "l-p" }
        val nearLeftEdgeOfP = nearbyLetters(placed.keys, pKey.x + 2f, pKey.y + pKey.h / 2f, pKey.model)
        assertTrue('o' in nearLeftEdgeOfP)
    }

    @Test
    fun quickDateTimeAndWeekdayCandidatesFollowLiteralWord() {
        val decoder = PinyinDecoder(setOf("ri", "qi", "shi", "jian", "xing"))
        decoder.nowProvider = { LocalDateTime.of(2026, 11, 12, 14, 35, 9) }
        decoder.lexicon = MemoryLexicon(
            listOf(
                LexRow("日期", "ri qi", "riqi", 500_000),
                LexRow("时间", "shi jian", "shijian", 600_000),
                LexRow("星期", "xing qi", "xingqi", 450_000),
            ),
        )

        val riqi = decoder.decode("riqi", fuzzy = false).candidates.map { it.text }
        assertEquals("日期", riqi.first())
        assertTrue("2026-11-12" in riqi.take(4))
        assertTrue("2026年11月12日" in riqi.take(4))

        val sj = decoder.decode("sj", fuzzy = false).candidates.map { it.text }
        assertTrue("14:35" in sj.take(3))

        val xq = decoder.decode("xq", fuzzy = false).candidates.map { it.text }
        assertTrue("星期四" in xq.take(3))
        assertTrue("周四" in xq.take(3))
    }

    @Test
    fun englishContextPrefixBoostForgetAndUriEmailMode() {
        val latin = LatinDecoder().apply {
            load(sequenceOf("man" to 900_000, "morning" to 120_000, "good" to 1_000_000, "gmail" to 400_000))
        }
        assertEquals("man", latin.suggest("m").first().text)
        assertEquals("morning", latin.suggest("m", prevWord = "good").first().text)

        latin.learn("mycustomword")
        assertEquals("mycustomword", latin.suggest("mycustom").first().text)
        assertTrue(latin.forget("mycustomword"))
        assertTrue(latin.suggest("mycustom").none { it.text == "mycustomword" })

        // URI / Email mode suppresses auto-space and post-dot auto-capitalization
        val engine = InputEngine(latin)
        var state = EngineState(language = Language.English, uriOrEmail = true)
        for (ch in "john") state = engine.reduce(state, EngineAct.Letter(ch, null, ""), EnterKind.Go).state
        val dotStep = engine.reduce(state, EngineAct.Direct("."), EnterKind.Go)
        assertEquals(ShiftState.Off, dotStep.state.shift)
        assertEquals(listOf("john", "."), dotStep.cmds.filterIsInstance<Cmd.Commit>().map { it.text })

        state = dotStep.state
        for (ch in "gm") state = engine.reduce(state, EngineAct.Letter(ch, null, ""), EnterKind.Go).state
        val picked = engine.reduce(state, EngineAct.Pick(0), EnterKind.Go)
        assertEquals("gmail", (picked.cmds.first() as Cmd.Commit).text)
        assertFalse(picked.state.autoSpace)
    }

    @Test
    fun smartAlternatingQuotesPairedBracketsAndBackspaceAcceleration() {
        assertEquals("“", resolveSmartCommit("", "“", composing = false).text)
        assertEquals("”", resolveSmartCommit("他说“你好", "“", composing = false).text)
        assertEquals("“", resolveSmartCommit("他说“你好”，又说", "“", composing = false).text)
        assertEquals("’", resolveSmartCommit("‘单引号", "‘", composing = false).text)

        val paired = resolveSmartCommit("", "《》", composing = false)
        assertEquals("《》", paired.text)
        assertEquals(1, paired.cursorOffsetBack)

        val decimal = resolveSmartCommit("3。", "14", composing = false)
        assertEquals(1, decimal.deleteBefore)
        assertEquals(".14", decimal.text)

        val gesture = KeyGesture(slopPx = 16f, caseSlopPx = 24f, swipePx = 36f)
        val bksp = KeyModel("backspace", KeyKind.Backspace, "")
        gesture.down(bksp, 50f, 50f, 0L)
        var now = 350L
        var sawWordDelete = false
        repeat(45) {
            val frame = gesture.alarm(now)
            if (frame.actions.any { it == EngineAct.BackspaceWord }) sawWordDelete = true
            now += 85L
        }
        assertTrue(sawWordDelete)
    }

    @Test
    fun wholeSyllableBeatsGluedSingleCharacters() {
        val dictDir = sequenceOf(
            File("src/main/assets/dict"),
            File("app/src/main/assets/dict"),
        ).firstOrNull { it.isDirectory } ?: return
        val realSyllables = File(dictDir, "syllables.txt").readLines().filter { it.isNotBlank() }.toSet()
        val decoder = PinyinDecoder(realSyllables)
        decoder.lexicon = MemoryLexicon(
            listOf(
                LexRow("小", "xiao", "xiao", 6_383_822),
                LexRow("先", "xian", "xian", 5_900_000),
                LexRow("当", "dang", "dang", 3_437_226),
                LexRow("用", "yong", "yong", 3_100_000),
                LexRow("蛋糕", "dan gao", "dangao", 815_478),
                LexRow("西安", "xi an", "xian", 300_000),
                LexRow("西奥", "xi ao", "xiao", 100_000),
                LexRow("洗", "xi", "xi", 2_290_306),
                LexRow("啊", "a", "a", 5_366_043),
                LexRow("哦", "o", "o", 3_563_330),
                LexRow("呢", "ne", "ne", 2_000_000),
                LexRow("上", "shang", "shang", 5_075_000),
                LexRow("见", "jian", "jian", 4_030_000),
                LexRow("年", "nian", "nian", 3_520_000),
                LexRow("冒犯", "mao fan", "maofan", 200_000),
                LexRow("你好吗", "ni hao ma", "nihaoma", 90_000),
            ),
        )

        // A syllable typed in full must stay one syllable: no "xi'a'o" split, no 洗啊哦 chain.
        val xiao = decoder.decode("xiao", fuzzy = false)
        assertEquals("小", xiao.candidates.first().text)
        assertEquals("xiao", xiao.preedit)
        assertTrue(xiao.candidates.take(3).none { it.text == "洗啊哦" })
        assertTrue(xiao.candidates.any { it.text == "西奥" })

        val xian = decoder.decode("xian", fuzzy = false)
        assertEquals("先", xian.candidates.first().text)
        assertEquals("xian", xian.preedit)

        // One letter can never be eaten by a neighbouring syllable of the same length, so
        // "dang" is 当 and not the initials 蛋(dan)+糕(gao).
        assertEquals("当", decoder.decode("dang", fuzzy = false).candidates.first().text)
        assertEquals("dang", decoder.decode("dang", fuzzy = false).preedit)
        assertEquals("用", decoder.decode("yong", fuzzy = false).candidates.first().text)

        // Multi-syllable input keeps preferring the split that spells real words.
        val dangao = decoder.decode("dangao", fuzzy = false)
        assertEquals("蛋糕", dangao.candidates.first().text)
        assertEquals("dan gao", dangao.candidates.first().syllables)

        // The composing text never shows a separator that the glue would hide.
        assertEquals("shang", decoder.decode("shang", fuzzy = false).preedit)
        assertEquals("jian", decoder.decode("jian", fuzzy = false).preedit)
        assertEquals("nian", decoder.decode("nian", fuzzy = false).preedit)
        assertEquals("mao'fan", decoder.decode("maofan", fuzzy = false).preedit)
        assertEquals("ni'hao'ma", decoder.decode("nihaoma", fuzzy = false).preedit)
        // A separator the typist asked for is kept as written.
        assertEquals("xi'an", decoder.decode("xi'an", fuzzy = false).preedit)
        assertEquals("zhi'hua", decoder.decode("zhi'hua", fuzzy = false).preedit)
    }

    @Test
    fun realBundledSyllablesAndEnglishLexiconDecodeAccurately() {
        val dictDir = sequenceOf(
            File("src/main/assets/dict"),
            File("app/src/main/assets/dict"),
        ).firstOrNull { it.isDirectory } ?: return

        val realSyllables = File(dictDir, "syllables.txt")
            .readLines()
            .filter { it.isNotBlank() }
            .toSet()
        assertTrue(realSyllables.size >= 400)

        val latin = LatinDecoder()
        GZIPInputStream(File(dictDir, "english.tsv.gz").inputStream()).bufferedReader().use { reader ->
            latin.load(
                reader.lineSequence().mapNotNull { line ->
                    val bits = line.split('\t')
                    val freq = bits.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
                    bits[0] to freq
                },
            )
        }
        assertEquals("morning", latin.suggest("morn", prevWord = "good").first().text)
        assertEquals("forward", latin.suggest("forw", prevWord = "looking").first().text)
        assertEquals("receive", latin.suggest("recieve").first().text)
        assertTrue(latin.suggest("hel").first().text in setOf("help", "hello"))
        assertEquals("this", latin.suggest("thi").first().text)
        assertEquals("because", latin.suggest("bec").first().text)
        assertEquals("separate", latin.suggest("seperate").first().text)
        assertEquals("definitely", latin.suggest("definately").first().text)
        assertEquals("grammar", latin.suggest("grammer").first().text)
        assertEquals("app", latin.suggest("app").first().text)
    }

    @Test
    fun polishStreamSplitsMultipleCandidates() {
        val raw = "【对老师·请假】\n王老师您好，\n明天早八我有冲突，想向您请假。\n\n【简短版】\n王老师好，明天早八想跟您请个假。"
        val items = parsePolishStream(raw)
        assertEquals(2, items.size)
        assertEquals("对老师·请假", items[0].tag)
        assertEquals("王老师您好，\n明天早八我有冲突，想向您请假。", items[0].text)
        assertEquals("简短版", items[1].tag)
        assertEquals("王老师好，明天早八想跟您请个假。", items[1].text)
    }

    @Test
    fun polishStreamHidesHalfWrittenTag() {
        assertTrue(parsePolishStream("【对老师").isEmpty())
        val items = parsePolishStream("【对老师·请假】\n王老师您好\n【简短")
        assertEquals(1, items.size)
        assertEquals("对老师·请假", items[0].tag)
        assertEquals("王老师您好", items[0].text)
        assertFalse(items[0].text.contains("【简短"))
    }

    @Test
    fun polishStreamFallsBackToPlainText() {
        val items = parsePolishStream("润色：今天去开会路上堵车，稍晚到。")
        assertEquals(1, items.size)
        assertEquals("智能润色", items[0].tag)
        assertEquals("今天去开会路上堵车，稍晚到。", items[0].text)
        assertFalse(items[0].text.contains("润色："))
        assertFalse(items[0].text.contains("\"") || items[0].text.contains("“") || items[0].text.contains("”"))

        val quoted = parsePolishStream("“润色：今天去开会路上堵车，稍晚到。”")
        assertEquals(1, quoted.size)
        assertEquals("智能润色", quoted[0].tag)
        assertEquals("今天去开会路上堵车，稍晚到。", quoted[0].text)
    }

    @Test
    fun polishStreamDropsPreamble() {
        val items = parsePolishStream("好的，以下是润色结果：\n【长辈·温和】\n妈，我这周末回不去啦")
        assertEquals(1, items.size)
        assertEquals("长辈·温和", items[0].tag)
        assertEquals("妈，我这周末回不去啦", items[0].text)

        val withEmptyTag = parsePolishStream("【空块】\n【长辈·温和】\n妈，我这周末回不去啦")
        assertEquals(1, withEmptyTag.size)
        assertEquals("长辈·温和", withEmptyTag[0].tag)
    }

    @Test
    fun polishPromptIncludesContextOnlyWhenPresent() {
        val emptyScene = hostSceneOf("com.tencent.mm", null, EnterKind.Send)
        val withoutCtx = buildPolishUserPrompt(
            original = "明天早八我有课去不了了",
            contextBefore = "   ",
            contextAfter = "",
            scene = emptyScene,
            tone = PolishTone.Auto,
        )
        assertFalse(withoutCtx.contains("【前文参考"))
        assertFalse(withoutCtx.contains("【后文参考"))
        assertFalse(withoutCtx.contains("输入框提示"))
        assertTrue(withoutCtx.endsWith("【待润色原文】\n明天早八我有课去不了了"))

        val richScene = hostSceneOf("com.tencent.mm", "回复 @张老师", EnterKind.Send)
        val withCtx = buildPolishUserPrompt(
            original = "明天早八我有课去不了了",
            contextBefore = "王老师您好：",
            contextAfter = "谢谢老师",
            scene = richScene,
            tone = PolishTone.Auto,
        )
        assertTrue(withCtx.contains("输入框提示：回复 @张老师"))
        assertTrue(withCtx.contains("【前文参考（仅用于判断对象，勿润色、勿重复）】\n王老师您好："))
        assertTrue(withCtx.contains("【后文参考（勿润色、勿重复）】\n谢谢老师"))
        assertTrue(withCtx.endsWith("【待润色原文】\n明天早八我有课去不了了"))
        assertTrue(withCtx.indexOf("【前文参考") < withCtx.indexOf("【后文参考"))
        assertTrue(withCtx.indexOf("【后文参考") < withCtx.indexOf("【待润色原文】"))
    }

    @Test
    fun polishSceneClassifiesPackages() {
        val wechat = hostSceneOf("com.tencent.mm", null, EnterKind.Newline)
        assertEquals(HostCategory.Im, wechat.category)
        assertTrue(wechat.chatBubble)

        val wework = hostSceneOf("com.tencent.wework", null, EnterKind.Newline)
        assertEquals(HostCategory.Work, wework.category)
        assertFalse(wework.chatBubble)
        assertTrue(hostSceneOf("com.tencent.wework", null, EnterKind.Send).chatBubble)
        assertEquals(HostCategory.Work, appCategoryOf("com.Slack"))
        assertEquals(HostCategory.Work, appCategoryOf("com.slack.intune"))

        assertEquals(HostCategory.MailDoc, hostSceneOf("com.tencent.androidqqmail", null, EnterKind.Newline).category)
        assertEquals(HostCategory.MailDoc, appCategoryOf("com.microsoft.office.outlook"))
        assertEquals(HostCategory.Social, hostSceneOf("com.xingin.xhs", null, EnterKind.Newline).category)
        assertEquals(HostCategory.General, hostSceneOf(null, null, EnterKind.Newline).category)
    }

    @Test
    fun polishToneAutoUsesAutoLabel() {
        assertEquals("自动判断", PolishTone.Auto.promptLabel)
        assertEquals("老师", PolishTone.Teacher.promptLabel)
    }

    @Test
    fun entireSyllableInventoryResistsParasiticCodaAndVowelSplits() {
        val dictDir = sequenceOf(
            File("src/main/assets/dict"),
            File("app/src/main/assets/dict"),
        ).firstOrNull { it.isDirectory } ?: return
        val realSyllables = File(dictDir, "syllables.txt")
            .readLines()
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()
        val fullSyllables = realSyllables.filter { it.length >= 2 || it in setOf("a", "e", "o") }
        assertEquals(414, fullSyllables.size)

        val rows = ArrayList<LexRow>(fullSyllables.size + 64)
        // Give every real syllable a normal 1-character entry
        for ((idx, syl) in fullSyllables.withIndex()) {
            val ch = (0x4E00 + idx).toChar().toString()
            rows += LexRow(ch, syl, syl, 250_000)
        }
        // Add real-world adversarial multi-syllable traps that previously caused shan'g / jia'n / ni'a'n / mao'fa'n
        rows += listOf(
            LexRow("啊", "a", "a", 5_366_043),
            LexRow("哦", "o", "o", 3_563_330),
            LexRow("饿", "e", "e", 606_677),
            LexRow("你", "ni", "ni", 22_000_000),
            LexRow("那", "na", "na", 12_000_000),
            LexRow("个", "ge", "ge", 15_000_000),
            LexRow("吗", "ma", "ma", 8_000_000),
            LexRow("上", "shang", "shang", 5_075_000),
            LexRow("闪光", "shan guang", "shanguang", 107_815),
            LexRow("山谷", "shan gu", "shangu", 82_365),
            LexRow("见", "jian", "jian", 4_030_000),
            LexRow("佳能", "jia neng", "jianeng", 133_305),
            LexRow("加你", "jia ni", "jiani", 8_595),
            LexRow("年", "nian", "nian", 3_520_000),
            LexRow("你安", "ni an", "nian", 150_000),
            LexRow("你啊", "ni a", "nia", 120_000),
            LexRow("边", "bian", "bian", 3_200_000),
            LexRow("比啊", "bi a", "bia", 110_000),
            LexRow("猫", "mao", "mao", 600_000),
            LexRow("毛发", "mao fa", "maofa", 180_000),
            LexRow("冒犯", "mao fan", "maofan", 22_700),
            LexRow("看", "kan", "kan", 15_000_000),
            LexRow("写", "xie", "xie", 5_000_000),
            LexRow("看戏", "kan xi", "kanxi", 500_000),
            LexRow("过", "guo", "guo", 10_000_000),
            LexRow("毛骨", "mao gu", "maogu", 80_000),
            LexRow("上面", "shang mian", "shangmian", 650_000),
            LexRow("仙女", "xian nv", "xiannv", 220_000),
            LexRow("穷", "qiong", "qiong", 900_000),
            LexRow("窘", "jiong", "jiong", 400_000),
            LexRow("熊", "xiong", "xiong", 1_100_000),
            LexRow("为什么", "wei shen me", "weishenme", 500_907),
            LexRow("为什么要", "wei shen me yao", "weishenmeyao", 180_815),
            LexRow("你会", "ni hui", "nihui", 449_705),
            LexRow("您还没有", "nin hai mei you", "ninhaimeiyou", 410_090),
            LexRow("西安", "xi an", "xian", 300_000),
        )
        // Inject generic coda-stealing opened phrases for every syllable whose prefix is also a syllable (e.g. ban/bang, fan/fang, xian/xiang)
        for (syl in fullSyllables) {
            if (syl.length >= 3) {
                val stem = syl.dropLast(1)
                val tail = syl.last()
                if (stem in realSyllables) {
                    val ext = when (tail) {
                        'g' -> "guang"
                        'n' -> "neng"
                        'o' -> "ou"
                        'e' -> "en"
                        'a' -> "an"
                        else -> null
                    }
                    if (ext != null && ext in realSyllables) {
                        rows += LexRow("假词", "$stem $ext", stem + ext, 140_000)
                    }
                }
            }
        }

        val decoder = PinyinDecoder(realSyllables)
        decoder.lexicon = MemoryLexicon(rows)

        // 1. Every single valid syllable in Mandarin stays 1 syllable with zero apostrophes
        for (syl in fullSyllables) {
            val res = decoder.decode(syl, fuzzy = false)
            assertFalse("Unexpected split in preedit for '$syl': ${res.preedit}", '\'' in res.preedit)
            assertTrue("Missing candidates for '$syl'", res.candidates.isNotEmpty())
            val topSyl = res.candidates.first().syllables
            assertFalse(
                "Expected 1-syllable top candidate for '$syl', got '${res.candidates.first().text}' ($topSyl)",
                ' ' in topSyl,
            )
        }

        // 2. Canonical bug reports + sibling multi-syllable cases check BOTH preedit AND #1 candidate
        val shang = decoder.decode("shang", fuzzy = false)
        assertEquals("shang", shang.preedit)
        assertEquals("上", shang.candidates.first().text)

        val jian = decoder.decode("jian", fuzzy = false)
        assertEquals("jian", jian.preedit)
        assertEquals("见", jian.candidates.first().text)

        val nian = decoder.decode("nian", fuzzy = false)
        assertEquals("nian", nian.preedit)
        assertEquals("年", nian.candidates.first().text)

        val maofan = decoder.decode("maofan", fuzzy = false)
        assertEquals("mao'fan", maofan.preedit)
        assertEquals("冒犯", maofan.candidates.first().text)

        val kanxie = decoder.decode("kanxie", fuzzy = false)
        assertEquals("kan'xie", kanxie.preedit)
        assertEquals("看写", kanxie.candidates.first().text)

        val maoguo = decoder.decode("maoguo", fuzzy = false)
        assertEquals("mao'guo", maoguo.preedit)
        assertEquals("猫过", maoguo.candidates.first().text)

        // 3. Mid-typing incomplete syllable prefixes never shatter into trailing 'a'/'o' splits
        for (incomplete in listOf("nia", "bia", "pia", "mia", "tia", "dua", "tua", "nua", "lua", "zua", "cua", "sua", "jua", "qua", "xua", "yua", "jio", "qio", "xio")) {
            val mid = decoder.decode(incomplete, fuzzy = false)
            assertEquals("Unexpected split while mid-typing '$incomplete'", incomplete, mid.preedit)
        }
        val maonia = decoder.decode("maonia", fuzzy = false)
        assertEquals("mao'nia", maonia.preedit)
        assertEquals("猫年", maonia.candidates.first().text)

        val maobia = decoder.decode("maobia", fuzzy = false)
        assertEquals("mao'bia", maobia.preedit)
        assertEquals("猫边", maobia.candidates.first().text)

        assertEquals("shang'm", decoder.decode("shangm", fuzzy = false).preedit)
        assertEquals("上面", decoder.decode("shangm", fuzzy = false).candidates.first().text)
        assertEquals("xian'n", decoder.decode("xiann", fuzzy = false).preedit)
        assertEquals("仙女", decoder.decode("xiann", fuzzy = false).candidates.first().text)

        // 4. Exact-length abbreviations outrank unrequested longer phrases
        assertEquals("为什么", decoder.decode("wsm", fuzzy = false).candidates.first().text)
        assertEquals("你会", decoder.decode("nh", fuzzy = false).candidates.first().text)

        // 5. Explicit user apostrophe is always preserved
        assertEquals("xi'an", decoder.decode("xi'an", fuzzy = false).preedit)
        assertEquals("西安", decoder.decode("xi'an", fuzzy = false).candidates.first().text)
        assertEquals("ni'a", decoder.decode("ni'a", fuzzy = false).preedit)
    }

    @Test
    fun updateMetadataFormatsRedirectMirrorsAndApkVerification() {
        // 1. GitHub Releases API JSON with browser_download_url and sha256 digest
        val ghApiJson = """
            {
              "tag_name": "v0.1.11",
              "body": "修复拼音切分与内置多源自动更新",
              "assets": [
                {
                  "name": "myboard-0.1.11.apk",
                  "content_type": "application/vnd.android.package-archive",
                  "size": 53633512,
                  "digest": "sha256:1697f8fca157ae0f9a2fa14bed51c5354b112ff5ed2e1a12544b0745fd513f49",
                  "browser_download_url": "https://github.com/n1ght-ow/myboard/releases/download/v0.1.11/myboard-0.1.11.apk",
                  "url": "https://api.github.com/repos/n1ght-ow/myboard/releases/assets/123"
                }
              ]
            }
        """.trimIndent()
        val parsedGh = parseLatestRelease(ghApiJson)
        assertNotNull(parsedGh)
        assertEquals("0.1.11", parsedGh!!.version)
        assertEquals("https://github.com/n1ght-ow/myboard/releases/download/v0.1.11/myboard-0.1.11.apk", parsedGh.assetUrl)
        assertEquals(53633512L, parsedGh.size)
        assertEquals("1697f8fca157ae0f9a2fa14bed51c5354b112ff5ed2e1a12544b0745fd513f49", parsedGh.sha256)

        // 2. Lightweight update.json manifest
        val manifestJson = """
            {
              "version": "0.1.12",
              "versionCode": 15,
              "apkUrl": "https://github.com/n1ght-ow/myboard/releases/download/v0.1.12/myboard-0.1.12.apk",
              "size": 54000000,
              "sha256": "sha256:abcdef0123456789",
              "notes": "直连与代理双通"
            }
        """.trimIndent()
        val parsedManifest = parseLatestRelease(manifestJson)
        assertNotNull(parsedManifest)
        assertEquals("0.1.12", parsedManifest!!.version)
        assertEquals(15, parsedManifest.versionCode)
        assertEquals("abcdef0123456789", parsedManifest.sha256)

        // 3. jsDelivr GitHub package versions JSON
        val jsDelivrJson = """{"tags":{},"versions":["0.1.8","0.1.10","0.1.9"]}"""
        val parsedCdn = parseLatestRelease(jsDelivrJson)
        assertNotNull(parsedCdn)
        assertEquals("0.1.10", parsedCdn!!.version)
        assertEquals("https://github.com/n1ght-ow/myboard/releases/download/v0.1.10/myboard-0.1.10.apk", parsedCdn.assetUrl)

        // 4. Redirect tag parsing for both GitHub direct and domestic mirror Location headers
        val directTag = parseLatestRedirectTag("https://github.com/n1ght-ow/myboard/releases/tag/v0.1.10")
        val mirrorTag = parseLatestRedirectTag("/https://github.com/n1ght-ow/myboard/releases/tag/v0.1.10?foo=1")
        assertEquals("0.1.10", directTag?.version)
        assertEquals("0.1.10", mirrorTag?.version)
        assertEquals("https://github.com/n1ght-ow/myboard/releases/download/v0.1.10/myboard-0.1.10.apk", mirrorTag?.assetUrl)

        // 5. Version & versionCode comparison
        assertTrue(isNewerVersion("0.1.10", "0.1.9"))
        assertTrue(isNewerRelease(RemoteRelease("0.1.10", "", "https://example.com/a.apk", 0L, versionCode = 14), "0.1.10", 13))
        assertFalse(isNewerRelease(RemoteRelease("0.1.10", "", "https://example.com/a.apk", 0L, versionCode = 13), "0.1.10", 13))

        // 6. APK ZIP header + EOCD + size + SHA-256 verification (rejects truncated/HTML payloads)
        val tempDir = Files.createTempDirectory("n1ghtkey-upd-test").toFile()
        try {
            val validApk = File(tempDir, "valid.apk")
            var rng = 0x13579BDF
            val payload = ByteArray(4096) {
                rng = rng * 1103515245 + 12345
                (rng ushr 16).toByte()
            }
            ZipOutputStream(validApk.outputStream()).use { zos ->
                zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
                zos.write(payload)
                zos.closeEntry()
            }
            val digest = sha256Hex(validApk)
            val strictRelease = RemoteRelease("0.1.11", "", "https://example.com/a.apk", validApk.length(), sha256 = digest)
            val unknownSizeRelease = RemoteRelease("0.1.11", "", "https://example.com/a.apk", 0L)
            assertTrue(verifyDownloadedFile(validApk, strictRelease))
            assertTrue(verifyDownloadedFile(validApk, unknownSizeRelease))
            assertFalse(verifyDownloadedFile(validApk, strictRelease.copy(sha256 = "0000")))

            // Truncated ZIP (starts with PK\x03\x04 and >1KB, but cut off before EOCD) MUST fail even when size=0 & sha256=""
            val truncatedApk = File(tempDir, "truncated.apk")
            truncatedApk.writeBytes(validApk.readBytes().copyOf(validApk.length().toInt() - 18))
            assertTrue(truncatedApk.length() > 1024L)
            assertFalse(verifyDownloadedFile(truncatedApk, unknownSizeRelease))

            // HTML mirror error page disguised as .apk MUST fail
            val htmlFile = File(tempDir, "error.apk")
            htmlFile.writeText("<!DOCTYPE html><html><body>Rate limit exceeded" + " ".repeat(2048) + "</body></html>")
            assertFalse(verifyDownloadedFile(htmlFile, unknownSizeRelease))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun recentClipboardHistoryKeepsFiveItemsAndRoundTrips() {
        assertEquals(5, MAX_RECENT_CLIPS)
        var clips = emptyList<String>()
        for (index in 1..7) {
            clips = mergeRecentClips("条目-$index", clips)
        }
        assertEquals(listOf("条目-7", "条目-6", "条目-5", "条目-4", "条目-3"), clips)

        val promoted = mergeRecentClips("条目-4", clips)
        assertEquals(listOf("条目-4", "条目-7", "条目-6", "条目-5", "条目-3"), promoted)
        assertEquals(promoted, mergeRecentClips("   ", promoted))

        val multiline = "第一行\n\"第二行\""
        val mergedWithStored = mergeRecentClipLists(listOf(multiline), promoted)
        assertEquals(listOf(multiline, "条目-4", "条目-7", "条目-6", "条目-5"), mergedWithStored)

        val encoded = encodeRecentClips(mergedWithStored)
        assertEquals(mergedWithStored, decodeRecentClips(encoded))
        assertEquals(emptyList<String>(), decodeRecentClips("not-json"))
    }

    @Test
    fun bottomLeftCornerTogglesBetweenLettersAndNineKeyNumberPage() {
        val letters = placeKeys(KeyboardPage.Letters, Language.Chinese, 1080f, 132f, 18f, 30f, 12f)
        val numbers = placeKeys(KeyboardPage.Number, Language.Chinese, 1080f, 132f, 18f, 30f, 12f)
        assertEquals(letters.height, numbers.height, 0.01f)

        val toNumber = letters.keys.first { it.model.id == "to-number" }
        val letterSymbols = letters.keys.first { it.model.id == "to-symbols" }
        assertEquals("123", toNumber.model.label)
        assertEquals(KeyKind.PageNumber, toNumber.model.kind)
        assertTrue("123 must be placed to the left of 符号 on Letters page", toNumber.x < letterSymbols.x)

        val toLetters = numbers.keys.first { it.model.id == "to-letters" }
        val numberSymbols = numbers.keys.first { it.model.id == "to-symbols" }
        assertEquals("ABC", toLetters.model.label)
        assertEquals(KeyKind.PageLetters, toLetters.model.kind)
        assertEquals(toNumber.x, toLetters.x, 0.01f)
        assertEquals(toNumber.y, toLetters.y, 0.01f)
        assertTrue(numberSymbols.x > toLetters.x)

        // Tapping the same bottom-left spot switches cleanly between 123 and ABC
        val tapX = toNumber.x + toNumber.w * 0.5f
        val tapY = toNumber.y + toNumber.h * 0.5f
        assertEquals("to-number", hitKey(letters.keys, tapX, tapY)?.model?.id)
        assertEquals("to-letters", hitKey(numbers.keys, tapX, tapY)?.model?.id)

        // Center 9-key + 0 grid alignment
        val digits = (0..9).associateWith { d -> numbers.keys.first { it.model.id == "num-$d" } }
        assertEquals(digits.getValue(1).x, digits.getValue(4).x, 0.01f)
        assertEquals(digits.getValue(4).x, digits.getValue(7).x, 0.01f)
        assertEquals(digits.getValue(2).x, digits.getValue(5).x, 0.01f)
        assertEquals(digits.getValue(5).x, digits.getValue(8).x, 0.01f)
        assertEquals(digits.getValue(8).x, digits.getValue(0).x, 0.01f)
        assertEquals(digits.getValue(3).x, digits.getValue(6).x, 0.01f)
        assertEquals(digits.getValue(6).x, digits.getValue(9).x, 0.01f)

        // Flanking common punctuation on left and right sides
        val leftLabelsZh = numbers.keys.filter { it.x < digits.getValue(1).x && it.model.kind == KeyKind.Char }.map { it.model.label }
        val rightLabelsZh = numbers.keys.filter { it.x > digits.getValue(3).x && it.model.kind == KeyKind.Char }.map { it.model.label }
        assertEquals(listOf("，", "。", "？", "！", "、", "@"), leftLabelsZh)
        assertEquals(listOf("+", "-", "/", ":", "%"), rightLabelsZh)

        val numbersEn = placeKeys(KeyboardPage.Number, Language.English, 1080f, 132f, 18f, 30f, 12f)
        val leftLabelsEn = numbersEn.keys.filter { it.x < digits.getValue(1).x && it.model.kind == KeyKind.Char }.map { it.model.label }
        assertEquals(listOf("?", "!", "(", ")", "@", "#"), leftLabelsEn)
    }

    @Test
    fun symbolCatalogHasOrderedCategoriesWithoutDuplicates() {
        assertEquals(12, symbolCategories.size)
        assertEquals("常用", symbolCategories.first().name)
        for (category in symbolCategories) {
            assertTrue(category.symbols.isNotEmpty())
            assertEquals("Duplicate symbol in ${category.name}", category.symbols.distinct(), category.symbols)
        }
    }
}


