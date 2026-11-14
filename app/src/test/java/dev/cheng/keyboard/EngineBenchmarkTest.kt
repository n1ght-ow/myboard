package dev.cheng.keyboard

import dev.cheng.keyboard.engine.Cmd
import dev.cheng.keyboard.engine.EngineAct
import dev.cheng.keyboard.engine.EngineState
import dev.cheng.keyboard.engine.InputEngine
import dev.cheng.keyboard.engine.LatinDecoder
import dev.cheng.keyboard.engine.PinyinDecoder
import dev.cheng.keyboard.ime.layout.placeKeys
import dev.cheng.keyboard.ime.layout.touchSampleOf
import dev.cheng.keyboard.model.EnterKind
import dev.cheng.keyboard.model.KeyboardPage
import dev.cheng.keyboard.model.Language
import dev.cheng.keyboard.model.LexRow
import dev.cheng.keyboard.model.Lexicon
import dev.cheng.keyboard.model.TouchSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream

class EngineBenchmarkTest {

    private data class FullEntry(
        val row: LexRow,
        val initials: String,
        val nsy: Int,
    )

    /**
     * Pure-JVM indexed Lexicon backed directly by app/src/main/assets/dict/lexicon.db (693,192 rows),
     * matching SqliteLexicon query semantics without requiring Android SQLite runtime or external tools.
     */
    private class IndexedSqliteLexicon(entries: List<FullEntry>) : Lexicon {
        private val byLettersExact = HashMap<String, MutableList<LexRow>>(262_144)
        private val bySpelling = HashMap<String, MutableList<LexRow>>(327_680)
        private val byInitialsExact = HashMap<String, MutableList<LexRow>>(131_072)
        private val maxFreqByText = HashMap<String, Int>(600_000)

        private val sortedByLetters: Array<LexRow>
        private val sortedByText: Array<LexRow>
        private val sortedByInitials: Array<FullEntry>
        private val singleSylBySyl: Array<LexRow>
        private val byNsySortedBySyl = HashMap<Int, Array<LexRow>>()

        val allEntries: List<FullEntry> = entries

        init {
            val singleList = ArrayList<LexRow>(20_000)
            val nsyBuckets = HashMap<Int, MutableList<LexRow>>()
            for (e in entries) {
                val r = e.row
                byLettersExact.getOrPut(r.letters) { ArrayList(2) }.add(r)
                val sylCount = r.syllables.count { it == ' ' } + 1
                if (sylCount == e.nsy) {
                    bySpelling.getOrPut(r.syllables) { ArrayList(2) }.add(r)
                }
                byInitialsExact.getOrPut(e.initials) { ArrayList(2) }.add(r)
                val prevMax = maxFreqByText[r.text] ?: Int.MIN_VALUE
                if (r.freq > prevMax) maxFreqByText[r.text] = r.freq
                if (e.nsy == 1) singleList += r
                nsyBuckets.getOrPut(e.nsy) { ArrayList() }.add(r)
            }
            val freqDesc = Comparator<LexRow> { a, b -> b.freq.compareTo(a.freq) }
            for (list in byLettersExact.values) if (list.size > 1) list.sortWith(freqDesc)
            for (list in bySpelling.values) if (list.size > 1) list.sortWith(freqDesc)
            for (list in byInitialsExact.values) if (list.size > 1) list.sortWith(freqDesc)

            sortedByLetters = Array(entries.size) { entries[it].row }
            sortedByLetters.sortWith { a, b ->
                val c = a.letters.compareTo(b.letters)
                if (c != 0) c else b.freq.compareTo(a.freq)
            }

            sortedByText = Array(entries.size) { entries[it].row }
            sortedByText.sortWith { a, b ->
                val c = a.text.compareTo(b.text)
                if (c != 0) c else b.freq.compareTo(a.freq)
            }

            sortedByInitials = entries.toTypedArray()
            sortedByInitials.sortWith { a, b ->
                val c = a.initials.compareTo(b.initials)
                if (c != 0) c else b.row.freq.compareTo(a.row.freq)
            }

            singleSylBySyl = singleList.toTypedArray()
            singleSylBySyl.sortWith { a, b ->
                val c = a.syllables.compareTo(b.syllables)
                if (c != 0) c else b.freq.compareTo(a.freq)
            }

            for ((nsy, list) in nsyBuckets) {
                val arr = list.toTypedArray()
                arr.sortWith { a, b ->
                    val c = a.syllables.compareTo(b.syllables)
                    if (c != 0) c else b.freq.compareTo(a.freq)
                }
                byNsySortedBySyl[nsy] = arr
            }
        }

        override fun exact(letters: String, limit: Int): List<LexRow> =
            byLettersExact[letters]?.take(limit).orEmpty()

        override fun prefixed(letters: String, limit: Int): List<LexRow> {
            if (letters.isEmpty() || limit <= 0) return emptyList()
            var lo = lowerBoundLetters(sortedByLetters, letters)
            val collected = ArrayList<LexRow>(64)
            while (lo < sortedByLetters.size) {
                val row = sortedByLetters[lo]
                if (!row.letters.startsWith(letters)) break
                if (row.letters.length > letters.length) collected += row
                lo++
            }
            collected.sortByDescending { it.freq }
            return collected.take(limit)
        }

        override fun charsStarting(syllablePrefix: String, limit: Int): List<LexRow> {
            if (syllablePrefix.isEmpty() || limit <= 0) return emptyList()
            var lo = lowerBoundSyl(singleSylBySyl, syllablePrefix)
            val collected = ArrayList<LexRow>(64)
            while (lo < singleSylBySyl.size) {
                val row = singleSylBySyl[lo]
                if (!row.syllables.startsWith(syllablePrefix)) break
                collected += row
                lo++
            }
            collected.sortByDescending { it.freq }
            return collected.take(limit)
        }

        override fun spelling(syllables: String, limit: Int): List<LexRow> {
            if (syllables.isBlank() || limit <= 0) return emptyList()
            return bySpelling[syllables]?.take(limit).orEmpty()
        }

        override fun spellingStarting(prefix: String, nsy: Int, limit: Int): List<LexRow> {
            if (prefix.isBlank() || nsy < 1 || limit <= 0) return emptyList()
            val arr = byNsySortedBySyl[nsy] ?: return emptyList()
            var lo = lowerBoundSyl(arr, prefix)
            val collected = ArrayList<LexRow>(64)
            while (lo < arr.size) {
                val row = arr[lo]
                if (!row.syllables.startsWith(prefix)) break
                collected += row
                lo++
            }
            collected.sortByDescending { it.freq }
            return collected.take(limit)
        }

        override fun byInitials(prefix: String, limit: Int, exactOnly: Boolean): List<LexRow> {
            if (prefix.isEmpty() || limit <= 0) return emptyList()
            val exact = byInitialsExact[prefix]?.take(limit).orEmpty().distinctBy { it.text }
            if (exactOnly || exact.size >= limit) return exact.take(limit)
            var lo = lowerBoundInit(sortedByInitials, prefix)
            val longer = ArrayList<LexRow>(128)
            while (lo < sortedByInitials.size) {
                val e = sortedByInitials[lo]
                if (!e.initials.startsWith(prefix)) break
                if (e.initials.length > prefix.length) longer += e.row
                lo++
            }
            longer.sortByDescending { it.freq }
            return (exact + longer.take(limit)).distinctBy { it.text }.take(limit)
        }

        override fun phrasesStarting(text: String, limit: Int): List<LexRow> {
            if (text.isEmpty() || limit <= 0) return emptyList()
            var lo = lowerBoundText(sortedByText, text)
            val collected = ArrayList<LexRow>(64)
            while (lo < sortedByText.size) {
                val row = sortedByText[lo]
                if (!row.text.startsWith(text)) break
                if (row.text.length > text.length) collected += row
                lo++
            }
            collected.sortByDescending { it.freq }
            return collected.take(limit)
        }

        override fun phraseFreqs(texts: Collection<String>): Map<String, Int> {
            if (texts.isEmpty()) return emptyMap()
            val out = HashMap<String, Int>(texts.size)
            for (t in texts) {
                if (t.isEmpty()) continue
                val f = maxFreqByText[t] ?: continue
                out[t] = f
            }
            return out
        }

        private fun lowerBoundLetters(arr: Array<LexRow>, key: String): Int {
            var l = 0
            var r = arr.size
            while (l < r) {
                val m = (l + r) ushr 1
                if (arr[m].letters < key) l = m + 1 else r = m
            }
            return l
        }

        private fun lowerBoundSyl(arr: Array<LexRow>, key: String): Int {
            var l = 0
            var r = arr.size
            while (l < r) {
                val m = (l + r) ushr 1
                if (arr[m].syllables < key) l = m + 1 else r = m
            }
            return l
        }

        private fun lowerBoundText(arr: Array<LexRow>, key: String): Int {
            var l = 0
            var r = arr.size
            while (l < r) {
                val m = (l + r) ushr 1
                if (arr[m].text < key) l = m + 1 else r = m
            }
            return l
        }

        private fun lowerBoundInit(arr: Array<FullEntry>, key: String): Int {
            var l = 0
            var r = arr.size
            while (l < r) {
                val m = (l + r) ushr 1
                if (arr[m].initials < key) l = m + 1 else r = m
            }
            return l
        }
    }

    companion object {
        private fun resolveAsset(relPath: String): File {
            val direct = File("src/main/assets/$relPath")
            if (direct.isFile) return direct
            val fromRoot = File("app/src/main/assets/$relPath")
            if (fromRoot.isFile) return fromRoot
            error("Asset not found: $relPath (cwd=${File(".").absolutePath})")
        }

        private fun readSqliteEntries(dbFile: File): List<FullEntry> {
            val data = dbFile.readBytes()
            val rawPageSize = ((data[16].toInt() and 0xFF) shl 8) or (data[17].toInt() and 0xFF)
            val pageSize = if (rawPageSize == 1) 65536 else rawPageSize
            val out = ArrayList<FullEntry>(700_000)

            var varintNextPos = 0
            fun readVarint(pos: Int): Long {
                var v = 0L
                for (i in 0 until 8) {
                    val b = data[pos + i].toInt() and 0xFF
                    v = (v shl 7) or (b and 0x7F).toLong()
                    if ((b and 0x80) == 0) {
                        varintNextPos = pos + i + 1
                        return v
                    }
                }
                val b = data[pos + 8].toInt() and 0xFF
                varintNextPos = pos + 9
                return (v shl 8) or b.toLong()
            }

            fun u16(pos: Int): Int = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
            fun u32(pos: Int): Int =
                ((data[pos].toInt() and 0xFF) shl 24) or
                    ((data[pos + 1].toInt() and 0xFF) shl 16) or
                    ((data[pos + 2].toInt() and 0xFF) shl 8) or
                    (data[pos + 3].toInt() and 0xFF)

            fun readInt(pos: Int, serialType: Int): Pair<Int, Int> = when (serialType) {
                0, 8 -> 0 to pos
                9 -> 1 to pos
                1 -> data[pos].toInt() to (pos + 1)
                2 -> ((data[pos].toInt() shl 8) or (data[pos + 1].toInt() and 0xFF)) to (pos + 2)
                3 -> (
                    (data[pos].toInt() shl 16) or
                        ((data[pos + 1].toInt() and 0xFF) shl 8) or
                        (data[pos + 2].toInt() and 0xFF)
                    ) to (pos + 3)
                4 -> u32(pos) to (pos + 4)
                else -> error("Unsupported int serialType=$serialType")
            }

            fun visit(pageNo: Int) {
                val base = (pageNo - 1) * pageSize
                val hdr = if (pageNo == 1) base + 100 else base
                val pageType = data[hdr].toInt() and 0xFF
                val nCells = u16(hdr + 3)
                if (pageType == 0x05) {
                    val ptrBase = hdr + 12
                    for (i in 0 until nCells) {
                        val cellOff = base + u16(ptrBase + i * 2)
                        visit(u32(cellOff))
                    }
                    visit(u32(hdr + 8))
                } else if (pageType == 0x0D) {
                    val ptrBase = hdr + 8
                    val st = IntArray(6)
                    for (i in 0 until nCells) {
                        var p = base + u16(ptrBase + i * 2)
                        readVarint(p); p = varintNextPos // payload_size
                        readVarint(p); p = varintNextPos // rowid
                        val hdrStart = p
                        val hdrLen = readVarint(p).toInt(); p = varintNextPos
                        val hdrEnd = hdrStart + hdrLen
                        var col = 0
                        while (p < hdrEnd && col < 6) {
                            st[col++] = readVarint(p).toInt()
                            p = varintNextPos
                        }
                        val textLen = (st[0] - 13) shr 1
                        val text = String(data, p, textLen, StandardCharsets.UTF_8); p += textLen
                        val sylLen = (st[1] - 13) shr 1
                        val syl = String(data, p, sylLen, StandardCharsets.US_ASCII); p += sylLen
                        val letLen = (st[2] - 13) shr 1
                        val letters = String(data, p, letLen, StandardCharsets.US_ASCII); p += letLen
                        val initLen = (st[3] - 13) shr 1
                        val initials = String(data, p, initLen, StandardCharsets.US_ASCII); p += initLen
                        val (freq, pAfterFreq) = readInt(p, st[4])
                        val (nsy, _) = readInt(pAfterFreq, st[5])
                        out += FullEntry(LexRow(text, syl, letters, freq), initials, nsy)
                    }
                } else {
                    error("Unexpected B-tree page type: $pageType")
                }
            }

            visit(2)
            return out
        }

        private val sharedEnv by lazy {
            val syllables = resolveAsset("dict/syllables.txt").readLines(StandardCharsets.UTF_8)
                .map { it.replace("\uFEFF", "").trim().lowercase() }
                .filter { it.isNotEmpty() && it.all { c -> c in 'a'..'z' } }
                .toSet()
            val entries = readSqliteEntries(resolveAsset("dict/lexicon.db"))
            val lexicon = IndexedSqliteLexicon(entries)
            val latin = LatinDecoder()
            GZIPInputStream(resolveAsset("dict/english.tsv.gz").inputStream()).bufferedReader(StandardCharsets.UTF_8).use { reader ->
                latin.load(
                    reader.lineSequence().mapNotNull { line ->
                        val tab = line.indexOf('\t')
                        if (tab <= 0) return@mapNotNull null
                        val freq = line.substring(tab + 1).trim().toIntOrNull() ?: return@mapNotNull null
                        line.substring(0, tab) to freq
                    },
                )
            }
            Triple(syllables, lexicon, latin)
        }
    }

    private data class BenchCase(
        val category: String,
        val raw: String,
        val expectedAny: Set<String>,
        val context: String = "",
        val fuzzy: Boolean = false,
    )

    @Test
    fun mineBadCasesAndBenchmarkOnProductionLexicon() {
        val (syllables, lexicon, latin) = sharedEnv
        assertEquals(693_192, lexicon.allEntries.size)
        val decoder = PinyinDecoder(syllables).also {
            it.lexicon = lexicon
            it.latin = latin
        }

        // 1. Automated corpus mining: top 2,000 multi-syllable phrases in lexicon.db
        //    where this row is the strictly highest-frequency entry for its `letters`.
        val bestByLetters = LinkedHashMap<String, FullEntry>()
        val sortedPhrases = lexicon.allEntries
            .filter { it.nsy in 2..5 && it.row.letters.all { c -> c in 'a'..'z' } && 'v' !in it.row.letters }
            .sortedByDescending { it.row.freq }
        for (e in sortedPhrases) {
            if (bestByLetters.size >= 2000) break
            bestByLetters.putIfAbsent(e.row.letters, e)
        }

        var corpusTop1 = 0
        var corpusTop3 = 0
        val corpusMisses = ArrayList<String>()
        for ((letters, entry) in bestByLetters) {
            val dec = decoder.decode(letters, fuzzy = false)
            val top3 = dec.candidates.take(3).map { it.text }
            if (top3.firstOrNull() == entry.row.text) {
                corpusTop1++
                corpusTop3++
            } else if (entry.row.text in top3) {
                corpusTop3++
                if (corpusMisses.size < 60) {
                    corpusMisses += "[Rank2-3] $letters -> expected '${entry.row.text}' (${entry.row.syllables}, f=${entry.row.freq}), got $top3 (preedit=${dec.preedit})"
                }
            } else {
                if (corpusMisses.size < 60) {
                    corpusMisses += "[MISS]    $letters -> expected '${entry.row.text}' (${entry.row.syllables}, f=${entry.row.freq}), got $top3 (preedit=${dec.preedit})"
                }
            }
        }

        // 2. Automated 26-key u-for-v (ü) mining: top 60 phrases containing lv / nv typed with u!
        val uvEntries = lexicon.allEntries
            .filter { it.nsy in 2..4 && ("lv" in it.row.syllables.split(' ') || "nv" in it.row.syllables.split(' ')) }
            .sortedByDescending { it.row.freq }
            .distinctBy { it.row.letters }
            .take(60)
        var uvTop1 = 0
        var uvTop3 = 0
        val uvMisses = ArrayList<String>()
        for (e in uvEntries) {
            val uTyped = e.row.letters.replace('v', 'u')
            // Skip if an actual non-umlaut word with uTyped has HIGHER freq in lexicon.db (e.g. jilu 记录 > 纪律, nuli 努力, luyou 路由/旅游)
            val competingU = lexicon.exact(uTyped, 1).firstOrNull()
            val expectTop1 = competingU == null || e.row.freq > competingU.freq * 2
            val dec = decoder.decode(uTyped, fuzzy = false)
            val top5 = dec.candidates.take(5).map { it.text }
            if (top5.firstOrNull() == e.row.text) {
                uvTop1++
                uvTop3++
            } else if (e.row.text in top5.take(3)) {
                uvTop3++
                if (expectTop1) {
                    uvMisses += "[UV-Rank2-3] $uTyped -> expected '${e.row.text}' (${e.row.syllables}, f=${e.row.freq}), competing=${competingU?.text}(${competingU?.freq}), got $top5"
                }
            } else {
                uvMisses += "[UV-MISS]    $uTyped -> expected '${e.row.text}' (${e.row.syllables}, f=${e.row.freq}), competing=${competingU?.text}(${competingU?.freq}), got $top5"
            }
        }

        // 3. Curated multi-category benchmark across real mobile typing scenarios
        val suite = listOf(
            // Category A: 26-key u/v umlaut typing
            BenchCase("uv", "nvsheng", setOf("女生")),
            BenchCase("uv", "nusheng", setOf("女生")),
            BenchCase("uv", "nuhai", setOf("女孩")),
            BenchCase("uv", "nuer", setOf("女儿")),
            BenchCase("uv", "nupengyou", setOf("女朋友")),
            BenchCase("uv", "nushi", setOf("女士")),
            BenchCase("uv", "nuxing", setOf("女性")),
            BenchCase("uv", "nushen", setOf("女神")),
            BenchCase("uv", "luse", setOf("绿色")),
            BenchCase("uv", "kaolu", setOf("考虑")),
            BenchCase("uv", "falu", setOf("法律")),
            BenchCase("uv", "xiaolu", setOf("效率")),
            BenchCase("uv", "lushi", setOf("律师")),
            BenchCase("uv", "gailu", setOf("概率")),
            BenchCase("uv", "pinlu", setOf("频率")),
            BenchCase("uv", "guilu", setOf("规律")),
            BenchCase("uv", "huilu", setOf("汇率")),
            BenchCase("uv", "jiaolu", setOf("焦虑")),
            BenchCase("uv", "guolu", setOf("过滤")),
            BenchCase("uv", "banlu", setOf("伴侣", "半路")),
            BenchCase("uv", "celue", setOf("策略")),
            BenchCase("uv", "hulue", setOf("忽略")),
            BenchCase("uv", "qinlue", setOf("侵略")),
            BenchCase("uv", "nuedai", setOf("虐待")),

            // Category B: Zero-apostrophe syllable boundary ambiguity
            BenchCase("boundary", "fangan", setOf("方案")),
            BenchCase("boundary", "wanan", setOf("晚安")),
            BenchCase("boundary", "zaoan", setOf("早安")),
            BenchCase("boundary", "ganen", setOf("感恩")),
            BenchCase("boundary", "qinai", setOf("亲爱")),
            BenchCase("boundary", "keai", setOf("可爱")),
            BenchCase("boundary", "lianai", setOf("恋爱")),
            BenchCase("boundary", "jine", setOf("金额")),
            BenchCase("boundary", "minge", setOf("名额")),
            BenchCase("boundary", "fene", setOf("份额")),
            BenchCase("boundary", "xiane", setOf("限额")),
            BenchCase("boundary", "chaoe", setOf("超额")),
            BenchCase("boundary", "yue", setOf("月")),
            BenchCase("boundary", "changan", setOf("长安")),
            BenchCase("boundary", "tiananmen", setOf("天安门")),
            BenchCase("boundary", "zongeryanzhi", setOf("总而言之")),
            BenchCase("boundary", "youeryuan", setOf("幼儿园")),
            BenchCase("boundary", "dier", setOf("第二")),
            BenchCase("boundary", "shier", setOf("十二")),
            BenchCase("boundary", "ouer", setOf("偶尔")),
            BenchCase("boundary", "erqie", setOf("而且")),
            BenchCase("boundary", "faner", setOf("反而")),
            BenchCase("boundary", "conger", setOf("从而")),
            BenchCase("boundary", "fanyi", setOf("翻译")),
            BenchCase("boundary", "jianyi", setOf("建议")),
            BenchCase("boundary", "zhuyi", setOf("注意", "主意")),
            BenchCase("boundary", "tongyi", setOf("同意", "统一")),

            // Category C: Long daily & work sentences (multi-word DP)
            BenchCase("sentence", "jintianxiawukaihui", setOf("今天下午开会")),
            BenchCase("sentence", "womenxianzaiquchifanba", setOf("我们现在去吃饭吧")),
            BenchCase("sentence", "mingtianzaoshangjiudianjian", setOf("明天早上九点见")),
            BenchCase("sentence", "buhaoyisirangnijiudengle", setOf("不好意思让你久等了")),
            BenchCase("sentence", "qingwenxianzaijidianle", setOf("请问现在几点了")),
            BenchCase("sentence", "woshaohougeinihuifu", setOf("我稍后给你回复")),
            BenchCase("sentence", "niwanshangyoukongma", setOf("你晚上有空吗")),
            BenchCase("sentence", "zhegeshiqinghenzhongyao", setOf("这个事情很重要")),
            BenchCase("sentence", "xiexienidebangzhu", setOf("谢谢你的帮助")),
            BenchCase("sentence", "meiyourenhewenti", setOf("没有任何问题")),
            BenchCase("sentence", "yijingfageinile", setOf("已经发给你了")),
            BenchCase("sentence", "keyibangwokanyixiama", setOf("可以帮我看一下吗")),
            BenchCase("sentence", "xianzaihaimeiyouxiaoxi", setOf("现在还没有消息")),
            BenchCase("sentence", "woganggangdaojia", setOf("我刚刚到家")),
            BenchCase("sentence", "shenmeshihoukaishi", setOf("什么时候开始")),
            BenchCase("sentence", "xinkulezaodianxiuxi", setOf("辛苦了早点休息")),
            BenchCase("sentence", "zhegeruanjianhenhaoyong", setOf("这个软件很好用")),
            BenchCase("sentence", "zhegewentizenmejiejue", setOf("这个问题怎么解决")),
            BenchCase("sentence", "womingbaile", setOf("我明白了")),
            BenchCase("sentence", "shangwushidiankaihui", setOf("上午十点开会")),

            // Category D: Colloquialisms, erhua, particles
            BenchCase("colloquial", "yidianr", setOf("一点儿")),
            BenchCase("colloquial", "wanr", setOf("玩儿")),
            BenchCase("colloquial", "haowanr", setOf("好玩儿")),
            BenchCase("colloquial", "liaotianr", setOf("聊天儿")),
            BenchCase("colloquial", "niqunarle", setOf("你去哪儿了")),
            BenchCase("colloquial", "meishir", setOf("没事儿")),
            BenchCase("colloquial", "yihuir", setOf("一会儿")),
            BenchCase("colloquial", "zheiyang", setOf("这样")),
            BenchCase("colloquial", "neige", setOf("那个", "内个")),
            BenchCase("colloquial", "zheige", setOf("这个")),

            // Category E: Pure & mixed abbreviations
            BenchCase("abbrev", "wsm", setOf("为什么")),
            BenchCase("abbrev", "zmy", setOf("怎么样")),
            BenchCase("abbrev", "bzd", setOf("不知道")),
            BenchCase("abbrev", "bhys", setOf("不好意思")),
            BenchCase("abbrev", "mywt", setOf("没有问题")),
            BenchCase("abbrev", "pyq", setOf("朋友圈")),
            BenchCase("abbrev", "sfz", setOf("身份证")),
            BenchCase("abbrev", "ewm", setOf("二维码")),
            BenchCase("abbrev", "rgzn", setOf("人工智能")),
            BenchCase("abbrev", "srf", setOf("输入法")),
            BenchCase("abbrev", "jintiantq", setOf("今天天气")),
            BenchCase("abbrev", "mingtianj", setOf("明天见")),
            BenchCase("abbrev", "meiyouwt", setOf("没有问题")),
            BenchCase("abbrev", "wszgr", setOf("我是中国人")),
            BenchCase("abbrev", "buhaoys", setOf("不好意思")),
            BenchCase("abbrev", "zenmeb", setOf("怎么办")),

            // Category F: QWERTY typos, transpositions, missing/extra letters
            BenchCase("typo", "nihap", setOf("你好")),
            BenchCase("typo", "womrn", setOf("我们")),
            BenchCase("typo", "xianzsi", setOf("现在")),
            BenchCase("typo", "shenmr", setOf("什么")),
            BenchCase("typo", "zenmr", setOf("怎么")),
            BenchCase("typo", "nhiao", setOf("你好")),
            BenchCase("typo", "wmoen", setOf("我们")),
            BenchCase("typo", "shneme", setOf("什么")),
            BenchCase("typo", "xainzai", setOf("现在")),
            BenchCase("typo", "jintain", setOf("今天")),
            BenchCase("typo", "zohngguo", setOf("中国")),
            BenchCase("typo", "gognzuo", setOf("工作")),
            BenchCase("typo", "weishemne", setOf("为什么")),
            BenchCase("typo", "mingtain", setOf("明天")),
            BenchCase("typo", "diannoa", setOf("电脑")),
            BenchCase("typo", "shouij", setOf("手机")),
            BenchCase("typo", "pengyuo", setOf("朋友")),
            BenchCase("typo", "xiexir", setOf("谢谢")),

            // Category G: Inline English & tech terms in Chinese mode
            BenchCase("inline_en", "wifi", setOf("wifi")),
            BenchCase("inline_en", "hello", setOf("hello")),
            BenchCase("inline_en", "python", setOf("python")),
            BenchCase("inline_en", "android", setOf("android")),
            BenchCase("inline_en", "kotlin", setOf("kotlin")),
            BenchCase("inline_en", "github", setOf("github")),
            BenchCase("inline_en", "google", setOf("google")),
            BenchCase("inline_en", "email", setOf("email")),
            BenchCase("inline_en", "ok", setOf("ok")),
            BenchCase("inline_en", "app", setOf("app")),
            BenchCase("inline_en", "api", setOf("api", "API")),
            BenchCase("inline_en", "apk", setOf("apk", "APK")),
            BenchCase("inline_en", "bug", setOf("bug")),
            BenchCase("inline_en", "git", setOf("git")),
            BenchCase("inline_en", "usb", setOf("usb", "USB")),
            BenchCase("inline_en", "cpu", setOf("cpu", "CPU")),
            BenchCase("inline_en", "gpu", setOf("gpu", "GPU")),
            BenchCase("inline_en", "pdf", setOf("pdf", "PDF")),
            BenchCase("inline_en", "ppt", setOf("ppt", "PPT")),
            BenchCase("inline_en", "ios", setOf("ios", "iOS")),
            BenchCase("inline_en", "json", setOf("json", "JSON")),
            BenchCase("inline_en", "http", setOf("http")),
            BenchCase("inline_en", "https", setOf("https")),

            // Category H: Context-aware homophone disambiguation
            BenchCase("context", "de", setOf("地"), context = "慢慢"),
            BenchCase("context", "shi", setOf("事"), context = "有"),
            BenchCase("context", "shi", setOf("试"), context = "考"),
            BenchCase("context", "kai", setOf("开"), context = "打"),
            BenchCase("context", "hui", setOf("会"), context = "开"),
            BenchCase("context", "dian", setOf("点"), context = "快"),
            BenchCase("context", "zai", setOf("再"), context = "一"),
            BenchCase("context", "yi", setOf("意"), context = "同"),
        )

        var suiteTop1 = 0
        var suiteTop3 = 0
        val catStats = LinkedHashMap<String, IntArray>() // [total, top1, top3]
        val suiteFailures = ArrayList<String>()
        for (tc in suite) {
            val st = catStats.getOrPut(tc.category) { IntArray(3) }
            st[0]++
            val dec = decoder.decode(tc.raw, fuzzy = tc.fuzzy, context = tc.context)
            val top3 = dec.candidates.take(3).map { it.text }
            val top1Hit = top3.firstOrNull() != null && tc.expectedAny.any { it.equals(top3.first(), ignoreCase = false) }
            val top3Hit = top3.any { cand -> tc.expectedAny.any { it == cand } }
            if (top1Hit) {
                suiteTop1++
                st[1]++
            }
            if (top3Hit) {
                suiteTop3++
                st[2]++
                if (!top1Hit) {
                    suiteFailures += "[${tc.category} Rank2-3] '${tc.raw}' (ctx='${tc.context}') -> expected ${tc.expectedAny}, got $top3 (preedit=${dec.preedit})"
                }
            } else {
                suiteFailures += "[${tc.category} MISS]    '${tc.raw}' (ctx='${tc.context}') -> expected ${tc.expectedAny}, got $top3 (preedit=${dec.preedit})"
            }
        }

        // 4. Probe InputEngine partial-consumed + punctuation edge case
        val engine = InputEngine(latin)
        var st = EngineState(language = Language.Chinese)
        for (ch in "nihaovvv") {
            st = engine.reduce(st, EngineAct.Letter(ch, null, ""), EnterKind.Done).state
        }
        val decodedPartial = decoder.decode(st.raw, fuzzy = false)
        st = engine.reduce(st, EngineAct.Candidates(decodedPartial.preedit, decodedPartial.candidates), EnterKind.Done).state
        val punctStep = engine.reduce(st, EngineAct.Direct("，"), EnterKind.Done)
        val committedOnPunct = punctStep.cmds.filterIsInstance<Cmd.Commit>().joinToString("") { it.text }

        // 5. Mid-typing prefix probe on top 100 everyday phrases
        val midTypingProbes = listOf(
            "pengyo" to "朋友",
            "dianna" to "电脑",
            "xianza" to "现在",
            "gongzu" to "工作",
            "shenm" to "什么",
            "zenm" to "怎么",
            "kaish" to "开始",
            "wom" to "我们",
            "went" to "问题",
            "mingtia" to "明天",
            "jintia" to "今天",
            "xiexi" to "谢谢",
            "buhaoyis" to "不好意思",
            "meiyouw" to "没有问题",
            "shuruf" to "输入法",
        )
        val midMisses = ArrayList<String>()
        for ((raw, target) in midTypingProbes) {
            val d = decoder.decode(raw, fuzzy = false)
            val top3 = d.candidates.take(3).map { it.text }
            if (target !in top3) {
                midMisses += "[MID-MISS] '$raw' -> expected '$target' in top3, got $top3 (preedit=${d.preedit})"
            } else if (top3.firstOrNull() != target) {
                midMisses += "[MID-Rank2-3] '$raw' -> expected '$target' #1, got $top3 (preedit=${d.preedit})"
            }
        }

        // 6. English mode (LatinDecoder) typo & tech vocabulary probe
        val enProbes = listOf(
            "wifi" to "wifi",
            "usb" to "usb",
            "cpu" to "cpu",
            "linux" to "linux",
            "kotlin" to "kotlin",
            "github" to "github",
            "api" to "api",
            "json" to "json",
            "teh" to "the",
            "recieve" to "receive",
            "thnks" to "thanks",
            "becuase" to "because",
            "wierd" to "weird",
            "untill" to "until",
            "adress" to "address",
            "seperate" to "separate",
            "definately" to "definitely",
            "tommorow" to "tomorrow",
            "dont" to "don't",
            "im" to "i'm",
        )
        val enMisses = ArrayList<String>()
        for ((raw, target) in enProbes) {
            val cands = latin.suggest(raw).take(3).map { it.text }
            if (cands.firstOrNull() != target) {
                enMisses += "[EN] '$raw' -> expected '$target' #1, got $cands"
            }
        }

        println("==========================================================")
        println(String.format("CORPUS TOP-2000 PHRASES : Top1=%d/%d (%.2f%%), Top3=%d/%d (%.2f%%)",
            corpusTop1, bestByLetters.size, 100.0 * corpusTop1 / bestByLetters.size,
            corpusTop3, bestByLetters.size, 100.0 * corpusTop3 / bestByLetters.size))
        corpusMisses.forEach { println("  $it") }
        println("----------------------------------------------------------")
        println(String.format("UV (u-for-v) TOP-60     : Top1=%d/%d (%.2f%%), Top3=%d/%d (%.2f%%)",
            uvTop1, uvEntries.size, 100.0 * uvTop1 / uvEntries.size,
            uvTop3, uvEntries.size, 100.0 * uvTop3 / uvEntries.size))
        uvMisses.take(30).forEach { println("  $it") }
        println("----------------------------------------------------------")
        println(String.format("CURATED SUITE (%d)     : Top1=%d/%d (%.2f%%), Top3=%d/%d (%.2f%%)",
            suite.size, suiteTop1, suite.size, 100.0 * suiteTop1 / suite.size,
            suiteTop3, suite.size, 100.0 * suiteTop3 / suite.size))
        for ((cat, arr) in catStats) {
            println(String.format("  %-12s : Top1=%2d/%2d, Top3=%2d/%2d", cat, arr[1], arr[0], arr[2], arr[0]))
        }
        suiteFailures.forEach { println("  $it") }
        println("----------------------------------------------------------")
        println("InputEngine 'nihaovvv' + '，' -> committed='$committedOnPunct' (preedit was '${decodedPartial.preedit}', top1='${decodedPartial.candidates.firstOrNull()?.text}', consumed=${decodedPartial.candidates.firstOrNull()?.consumed})")
        midMisses.forEach { println("  $it") }
        enMisses.forEach { println("  $it") }
        println("==========================================================")

        assertEquals("你好vvv，", committedOnPunct)
        assertTrue("Expected >=1998/2000 corpus top1, got $corpusTop1", corpusTop1 >= 1998)
        assertTrue("Expected >=54/60 u-for-v top1, got $uvTop1", uvTop1 >= 54)
        assertEquals("Expected 60/60 u-for-v top3", 60, uvTop3)
        assertEquals("Expected 100% curated suite top3 ($suiteFailures)", suite.size, suiteTop3)
        assertTrue("Expected >=94% curated suite top1, got $suiteTop1/${suite.size}", suiteTop1 >= 138)
        assertTrue("Expected 0 English misses, got $enMisses", enMisses.isEmpty())
    }

    @Test
    fun round2DeepMiningAndBenchmark() {
        val (syllables, lexicon, latin) = sharedEnv
        val decoder = PinyinDecoder(syllables).also {
            it.lexicon = lexicon
            it.latin = latin
        }

        // 1. Automated Mid-Typing Prefix Sweep on top 200 2..4-char phrases (last-letter-dropped & last-syllable-initial)
        val topPhrases = lexicon.allEntries
            .filter { it.nsy in 2..4 && it.row.letters.all { c -> c in 'a'..'z' } && 'v' !in it.row.letters }
            .sortedByDescending { it.row.freq }
            .distinctBy { it.row.letters }
            .take(200)

        var drop1Top1 = 0
        var drop1Top3 = 0
        val drop1Misses = ArrayList<String>()
        var lastInitTop1 = 0
        var lastInitTop3 = 0
        val lastInitMisses = ArrayList<String>()

        for (e in topPhrases) {
            val syls = e.row.syllables.split(' ')
            val lastSyl = syls.last()
            if (lastSyl.length >= 2) {
                val prefixDrop1 = e.row.letters.dropLast(1)
                val competingExact = lexicon.exact(prefixDrop1, 1).firstOrNull()
                if (competingExact == null || e.row.freq > competingExact.freq * 3) {
                    val d1 = decoder.decode(prefixDrop1, fuzzy = false).candidates.take(3).map { it.text }
                    if (d1.firstOrNull() == e.row.text) {
                        drop1Top1++; drop1Top3++
                    } else if (e.row.text in d1) {
                        drop1Top3++
                        if (drop1Misses.size < 20) drop1Misses += "[DROP1-R23] '$prefixDrop1' -> want '${e.row.text}'(${e.row.freq}), exact=${competingExact?.text}(${competingExact?.freq}), got $d1"
                    } else {
                        if (drop1Misses.size < 20) drop1Misses += "[DROP1-MISS] '$prefixDrop1' -> want '${e.row.text}'(${e.row.freq}), exact=${competingExact?.text}(${competingExact?.freq}), got $d1"
                    }
                }
            }
            val headSyls = syls.dropLast(1)
            val lastInitial = if (lastSyl.length >= 2 && lastSyl.substring(0, 2) in setOf("zh", "ch", "sh")) lastSyl.substring(0, 2) else lastSyl.substring(0, 1)
            val prefixInit = headSyls.joinToString("") + lastInitial
            val dInit = decoder.decode(prefixInit, fuzzy = false).candidates.take(5).map { it.text }
            if (dInit.firstOrNull() == e.row.text) {
                lastInitTop1++; lastInitTop3++
            } else if (e.row.text in dInit.take(3)) {
                lastInitTop3++
            } else if (lastInitMisses.size < 20) {
                lastInitMisses += "[INIT-MISS] '$prefixInit' -> want '${e.row.text}'(${e.row.freq}), got ${dInit.take(3)}"
            }
        }

        // 2. Multi-syllable (3..6 syl) phrase & sentence thumb typos
        val longTypos = listOf(
            "buhaoyisu" to setOf("不好意思"),
            "meiyouwrnti" to setOf("没有问题"),
            "shenmrshihou" to setOf("什么时候"),
            "zenmryang" to setOf("怎么样"),
            "jintianwanshamg" to setOf("今天晚上"),
            "mingtianzaoshamg" to setOf("明天早上"),
            "rengongzhinenh" to setOf("人工智能"),
            "pengyiuquan" to setOf("朋友圈"),
            "xiexienidr" to setOf("谢谢你的"),
            "womrnquchifan" to setOf("我们去吃饭"),
            "zhunbeishuijiso" to setOf("准备睡觉"),
            "zhegedongxu" to setOf("这个东西"),
            "shenmeyisu" to setOf("什么意思"),
            "zenmebsn" to setOf("怎么办"),
            "yijingfageinilr" to setOf("已经发给你了"),
            "kuaididaolr" to setOf("快递到了"),
            "shoujimeidianlr" to setOf("手机没电了"),
            "womingbailr" to setOf("我明白了"),
            "xinkulr" to setOf("辛苦了"),
            "zaoshanghap" to setOf("早上好"),
        )

        // 3. Southern / Double-Fuzzy Pinyin (fuzzy = true)
        val fuzzyCases = listOf(
            "zongguo" to setOf("中国"),
            "sanghai" to setOf("上海"),
            "hujian" to setOf("福建"),
            "lanjin" to setOf("南京"),
            "senzen" to setOf("深圳"),
            "zici" to setOf("支持"),
            "sisi" to setOf("事实", "试试", "四十"),
            "zenzen" to setOf("真正"),
            "liulai" to setOf("牛奶", "流泪"),
            "cangceng" to setOf("长城"),
            "sangcang" to setOf("商场"),
            "cengzang" to setOf("成长"),
            "cenren" to setOf("承认"),
            "zizao" to setOf("制造"),
            "zizu" to setOf("知足", "自主"),
            "zidao" to setOf("知道"),
            "sijian" to setOf("时间", "事件"),
            "zengzai" to setOf("正在"),
            "cifan" to setOf("吃饭"),
            "faguo" to setOf("法国"),
        )

        // 4. Spatial TouchSample border taps at ANY position (pos >= 0) where wrong key forms a valid syllable!
        val board = placeKeys(KeyboardPage.Letters, Language.Chinese, 1080f, 144f, 18f, 30f, 12f)
        fun borderSample(pressed: Char, intended: Char): TouchSample {
            val pKey = board.keys.first { it.model.id == "l-$pressed" }
            val iKey = board.keys.first { it.model.id == "l-$intended" }
            val midX = (pKey.x + pKey.w * 0.5f) * 0.48f + (iKey.x + iKey.w * 0.5f) * 0.52f
            val midY = (pKey.y + pKey.h * 0.5f) * 0.48f + (iKey.y + iKey.h * 0.5f) * 0.52f
            // Pick point just inside pressed key edge facing intended key
            val bx = (pKey.x + pKey.w * 0.5f) + ((iKey.x + iKey.w * 0.5f) - (pKey.x + pKey.w * 0.5f)) * 0.42f
            val by = (pKey.y + pKey.h * 0.5f) + ((iKey.y + iKey.h * 0.5f) - (pKey.y + pKey.h * 0.5f)) * 0.42f
            return touchSampleOf(board.keys, bx, by, pKey.model) ?: TouchSample(pressed)
        }

        data class SpatialCase(val raw: String, val pos: Int, val intendedChar: Char, val expected: String)
        val spatialCases = listOf(
            SpatialCase("bihao", 0, 'n', "你好"),
            SpatialCase("nuhao", 1, 'i', "你好"),
            SpatialCase("zhehe", 3, 'g', "这个"),
            SpatialCase("shenne", 4, 'm', "什么"),
            SpatialCase("dianbao", 4, 'n', "电脑"),
            SpatialCase("pengtou", 4, 'y', "朋友"),
            SpatialCase("keti", 2, 'y', "可以"),
            SpatialCase("zhisao", 3, 'd', "知道"),
            SpatialCase("gongzui", 6, 'o', "工作"),
            SpatialCase("jintuan", 4, 'i', "今天"),
            SpatialCase("wenri", 3, 't', "问题"),
            SpatialCase("kaiahi", 3, 's', "开始"),
        )

        // 5. Shorthand multi-word & trailing jianpin
        val shorthandCases = listOf(
            "shenmesh" to setOf("什么时候"),
            "meiyouwt" to setOf("没有问题"),
            "buhaoys" to setOf("不好意思"),
            "zenmeb" to setOf("怎么办"),
            "zhidaol" to setOf("知道了"),
            "xinkul" to setOf("辛苦了"),
            "chifanl" to setOf("吃饭了"),
            "xiexien" to setOf("谢谢你"),
            "keyim" to setOf("可以吗"),
            "daojial" to setOf("到家了"),
            "shuijiaol" to setOf("睡觉了"),
            "jintianxw" to setOf("今天下午"),
            "mingtianzs" to setOf("明天早上"),
            "womenxz" to setOf("我们现在"),
            "zhegewt" to setOf("这个问题"),
            "haodeb" to setOf("好的吧"),
        )

        // 6. Rich context disambiguation
        val ctxCases = listOf(
            Triple("吃", "fan", setOf("饭")),
            Triple("上", "ban", setOf("班")),
            Triple("下", "ban", setOf("班")),
            Triple("知", "dao", setOf("道")),
            Triple("喜", "huan", setOf("欢")),
            Triple("觉", "de", setOf("得")),
            Triple("慢慢", "de", setOf("地")),
            Triple("高高兴兴", "de", setOf("地")),
            Triple("在", "jia", setOf("家")),
            Triple("参", "jia", setOf("加")),
            Triple("开", "shi", setOf("始")),
            Triple("城", "shi", setOf("市")),
            Triple("考", "shi", setOf("试")),
            Triple("同", "yi", setOf("意")),
            Triple("容", "yi", setOf("易")),
            Triple("建", "yi", setOf("议")),
            Triple("打", "kai", setOf("开")),
            Triple("手", "ji", setOf("机")),
            Triple("提交", "daima", setOf("代码")),
            Triple("解决", "wenti", setOf("问题")),
        )

        // 7. English long-word misspellings & spatial border taps
        val enLongTypos = listOf(
            "enviroment" to "environment",
            "goverment" to "government",
            "accomodate" to "accommodate",
            "occured" to "occurred",
            "recomend" to "recommend",
            "necesary" to "necessary",
            "arguement" to "argument",
            "beleive" to "believe",
            "acheive" to "achieve",
            "calender" to "calendar",
            "independant" to "independent",
            "wnvironment" to "environment",
            "tahnks" to "thanks",
            "kotlni" to "kotlin",
            "andrpid" to "android",
        )
        val enSpatialCases = listOf(
            SpatialCase("thr", 2, 'e', "the"),
            SpatialCase("abd", 1, 'n', "and"),
            SpatialCase("woth", 1, 'i', "with"),
            SpatialCase("fot", 2, 'r', "for"),
            SpatialCase("yiu", 1, 'o', "you"),
            SpatialCase("hava", 3, 'e', "have"),
            SpatialCase("goid", 2, 'o', "good"),
            SpatialCase("thst", 2, 'a', "that"),
        )

        fun evalPairs(tag: String, list: List<Pair<String, Set<String>>>, fuzzy: Boolean = false): Triple<Int, Int, List<String>> {
            var t1 = 0; var t3 = 0; val misses = ArrayList<String>()
            for ((raw, exp) in list) {
                val cands = decoder.decode(raw, fuzzy = fuzzy).candidates.take(3).map { it.text }
                val h1 = cands.firstOrNull() in exp
                val h3 = cands.any { it in exp }
                if (h1) t1++
                if (h3) t3++
                if (!h1) misses += "[$tag ${if (h3) "R23" else "MISS"}] '$raw' -> want $exp, got $cands"
            }
            return Triple(t1, t3, misses)
        }

        val (lt1, lt3, ltMiss) = evalPairs("LONG_TYPO", longTypos, fuzzy = false)
        val (fz1, fz3, fzMiss) = evalPairs("FUZZY", fuzzyCases, fuzzy = true)
        val (sh1, sh3, shMiss) = evalPairs("SHORTHAND", shorthandCases, fuzzy = false)

        var sp1 = 0; var sp3 = 0; val spMiss = ArrayList<String>()
        for (sc in spatialCases) {
            val samples = MutableList<TouchSample?>(sc.raw.length) { null }
            samples[sc.pos] = borderSample(sc.raw[sc.pos], sc.intendedChar)
            val cands = decoder.decode(sc.raw, fuzzy = false, touchSamples = samples).candidates.take(3).map { it.text }
            if (cands.firstOrNull() == sc.expected) sp1++
            if (sc.expected in cands) sp3++ else spMiss += "[SPATIAL MISS] '${sc.raw}'(pos=${sc.pos}->${sc.intendedChar}, pen=${samples[sc.pos]?.penaltyOf(sc.intendedChar)}) -> want '${sc.expected}', got $cands"
            if (sc.expected in cands && cands.firstOrNull() != sc.expected) {
                spMiss += "[SPATIAL R23]  '${sc.raw}'(pos=${sc.pos}->${sc.intendedChar}, pen=${samples[sc.pos]?.penaltyOf(sc.intendedChar)}) -> want '${sc.expected}', got $cands"
            }
        }

        var cx1 = 0; var cx3 = 0; val cxMiss = ArrayList<String>()
        for ((ctx, raw, exp) in ctxCases) {
            val cands = decoder.decode(raw, fuzzy = false, context = ctx).candidates.take(3).map { it.text }
            if (cands.firstOrNull() in exp) cx1++
            if (cands.any { it in exp }) cx3++
            if (cands.firstOrNull() !in exp) cxMiss += "[CTX] '$ctx'+'$raw' -> want $exp, got $cands"
        }

        var enLt1 = 0; var enLt3 = 0; val enLtMiss = ArrayList<String>()
        for ((raw, exp) in enLongTypos) {
            val cands = latin.suggest(raw).take(3).map { it.text }
            if (cands.firstOrNull() == exp) enLt1++
            if (exp in cands) enLt3++
            if (cands.firstOrNull() != exp) enLtMiss += "[EN_TYPO ${if (exp in cands) "R23" else "MISS"}] '$raw' -> want '$exp', got $cands"
        }

        var enSp1 = 0; var enSp3 = 0; val enSpMiss = ArrayList<String>()
        for (sc in enSpatialCases) {
            val samples = MutableList<TouchSample?>(sc.raw.length) { null }
            samples[sc.pos] = borderSample(sc.raw[sc.pos], sc.intendedChar)
            val cands = latin.suggest(sc.raw, touchSamples = samples).take(3).map { it.text }
            if (cands.firstOrNull() == sc.expected) enSp1++
            if (sc.expected in cands) enSp3++
            if (cands.firstOrNull() != sc.expected) enSpMiss += "[EN_SPATIAL ${if (sc.expected in cands) "R23" else "MISS"}] '${sc.raw}'(pos=${sc.pos}->${sc.intendedChar}) -> want '${sc.expected}', got $cands"
        }

        val drop1Total = topPhrases.count { e ->
            val lastSyl = e.row.syllables.substringAfterLast(' ')
            if (lastSyl.length < 2) false else {
                val comp = lexicon.exact(e.row.letters.dropLast(1), 1).firstOrNull()
                comp == null || e.row.freq > comp.freq * 3
            }
        }

        println("================ ROUND 2 DEEP MINING ================")
        println("MID_DROP1     : Top1=$drop1Top1/$drop1Total, Top3=$drop1Top3/$drop1Total")
        drop1Misses.forEach { println("  $it") }
        println("MID_LAST_INIT : Top1=$lastInitTop1/${topPhrases.size}, Top3=$lastInitTop3/${topPhrases.size}")
        lastInitMisses.take(12).forEach { println("  $it") }
        println("LONG_TYPO     : Top1=$lt1/${longTypos.size}, Top3=$lt3/${longTypos.size}")
        ltMiss.forEach { println("  $it") }
        println("FUZZY         : Top1=$fz1/${fuzzyCases.size}, Top3=$fz3/${fuzzyCases.size}")
        fzMiss.forEach { println("  $it") }
        println("SPATIAL       : Top1=$sp1/${spatialCases.size}, Top3=$sp3/${spatialCases.size}")
        spMiss.forEach { println("  $it") }
        println("SHORTHAND     : Top1=$sh1/${shorthandCases.size}, Top3=$sh3/${shorthandCases.size}")
        shMiss.forEach { println("  $it") }
        println("CONTEXT       : Top1=$cx1/${ctxCases.size}, Top3=$cx3/${ctxCases.size}")
        cxMiss.forEach { println("  $it") }
        println("EN_LONG_TYPO  : Top1=$enLt1/${enLongTypos.size}, Top3=$enLt3/${enLongTypos.size}")
        enLtMiss.forEach { println("  $it") }
        println("EN_SPATIAL    : Top1=$enSp1/${enSpatialCases.size}, Top3=$enSp3/${enSpatialCases.size}")
        enSpMiss.forEach { println("  $it") }
        println("=====================================================")

        assertTrue("Expected MID_DROP1 Top3 >= 180/$drop1Total, got $drop1Top3", drop1Top3 >= 180)
        assertTrue("Expected MID_LAST_INIT Top1 >= 190/200, got $lastInitTop1", lastInitTop1 >= 190)
        assertTrue("Expected MID_LAST_INIT Top3 >= 198/200, got $lastInitTop3", lastInitTop3 >= 198)
        assertEquals("Expected LONG_TYPO Top1 == 20/20 ($ltMiss)", longTypos.size, lt1)
        assertEquals("Expected LONG_TYPO Top3 == 20/20 ($ltMiss)", longTypos.size, lt3)
        assertTrue("Expected FUZZY Top3 >= 19/20, got $fz3 ($fzMiss)", fz3 >= 19)
        assertEquals("Expected SPATIAL Top3 == 12/12 ($spMiss)", spatialCases.size, sp3)
        assertTrue("Expected SPATIAL Top1 >= 11/12 ($spMiss)", sp1 >= 11)
        assertEquals("Expected SHORTHAND Top3 == 16/16 ($shMiss)", shorthandCases.size, sh3)
        assertEquals("Expected CONTEXT Top1 == 20/20 ($cxMiss)", ctxCases.size, cx1)
        assertEquals("Expected EN_LONG_TYPO Top1 == 15/15 ($enLtMiss)", enLongTypos.size, enLt1)
        assertEquals("Expected EN_SPATIAL Top3 == 8/8 ($enSpMiss)", enSpatialCases.size, enSp3)
    }

    @Test
    fun round3SelfMine() {
        val (syllables, lexicon, latin) = sharedEnv
        val decoder = PinyinDecoder(syllables).also {
            it.lexicon = lexicon
            it.latin = latin
        }
        val bestExact = HashMap<String, FullEntry>(180_000)
        val bestInitial = HashMap<String, FullEntry>(120_000)
        val bestSingle = HashMap<String, LexRow>(8_192)
        for (entry in lexicon.allEntries) {
            val row = entry.row
            if (entry.nsy == 1 && ' ' !in row.syllables && row.syllables.all { it in 'a'..'z' }) {
                val prev = bestSingle[row.syllables]
                if (prev == null || row.freq > prev.freq) bestSingle[row.syllables] = row
            }
            if (entry.nsy !in 2..4) continue
            if ('v' in row.letters || row.letters.any { it !in 'a'..'z' }) continue
            val exactPrev = bestExact[row.letters]
            if (exactPrev == null || row.freq > exactPrev.row.freq) bestExact[row.letters] = entry
            val initials = entry.initials
            if (initials.length in 2..6 && initials != row.letters && initials.all { it in 'a'..'z' }) {
                val initPrev = bestInitial[initials]
                if (initPrev == null || row.freq > initPrev.row.freq) bestInitial[initials] = entry
            }
        }
        val byFreq = bestExact.values.sortedByDescending { it.row.freq }

        val lines = ArrayList<String>(400)
        val band = byFreq.drop(2_000).take(700)
        var bandTop1 = 0
        var bandTop3 = 0
        var bandShown = 0
        for (entry in band) {
            val decoded = decoder.decode(entry.row.letters, fuzzy = false)
            val top = decoded.candidates.take(3).map { it.text }
            if (top.firstOrNull() == entry.row.text) bandTop1++
            if (entry.row.text in top) bandTop3++
            else if (bandShown < 24) {
                bandShown++
                lines += "[EXACT_BAND] '${entry.row.letters}' -> want '${entry.row.text}' (${entry.row.syllables}, f=${entry.row.freq}), got $top preedit=${decoded.preedit}"
            }
        }
        lines += "EXACT_BAND : Top1=$bandTop1/${band.size}, Top3=$bandTop3/${band.size}"

        val singles = bestSingle.values.filter { it.freq >= 40_000 }.sortedByDescending { it.freq }
        var singleTop1 = 0
        var singleShown = 0
        for (row in singles) {
            val decoded = decoder.decode(row.syllables, fuzzy = false)
            val top = decoded.candidates.take(3).map { it.text }
            if (top.firstOrNull() == row.text) {
                singleTop1++
            } else if (singleShown < 20) {
                singleShown++
                lines += "[SINGLE] '${row.syllables}' -> want '${row.text}' (f=${row.freq}), got $top preedit=${decoded.preedit}"
            }
        }
        lines += "SINGLE : Top1=$singleTop1/${singles.size}"

        val jianpin = bestInitial.values
            .sortedByDescending { it.row.freq }
            .filter { entry ->
                val rival = lexicon.exact(entry.initials, 1).firstOrNull()
                rival == null || entry.row.freq > rival.freq * 4
            }
            .take(280)
        var jp1 = 0
        var jp3 = 0
        var jpShown = 0
        for (entry in jianpin) {
            val decoded = decoder.decode(entry.initials, fuzzy = false)
            val top = decoded.candidates.take(3).map { it.text }
            if (top.firstOrNull() == entry.row.text) jp1++
            if (entry.row.text in top) jp3++
            else if (jpShown < 24) {
                jpShown++
                val rival = lexicon.exact(entry.initials, 1).firstOrNull()
                lines += "[JIANPIN] '${entry.initials}' -> want '${entry.row.text}' (${entry.row.syllables}, f=${entry.row.freq}), rival=${rival?.text}(${rival?.freq}), got $top preedit=${decoded.preedit}"
            }
        }
        lines += "JIANPIN : Top1=$jp1/${jianpin.size}, Top3=$jp3/${jianpin.size}"

        val prefixes = byFreq.drop(200).take(500).filter { entry ->
            val last = entry.row.syllables.substringAfterLast(' ')
            if (last.length < 2) return@filter false
            val prefix = entry.row.letters.dropLast(1)
            val rival = lexicon.exact(prefix, 1).firstOrNull()
            rival == null || entry.row.freq > rival.freq * 8
        }
        var pre1 = 0
        var pre3 = 0
        var preShown = 0
        for (entry in prefixes) {
            val prefix = entry.row.letters.dropLast(1)
            val decoded = decoder.decode(prefix, fuzzy = false)
            val top = decoded.candidates.take(3).map { it.text }
            if (top.firstOrNull() == entry.row.text) pre1++
            if (entry.row.text in top) pre3++
            else if (preShown < 20) {
                preShown++
                lines += "[PREFIX] '$prefix' -> want '${entry.row.text}' (f=${entry.row.freq}), got $top preedit=${decoded.preedit}"
            }
        }
        lines += "PREFIX : Top1=$pre1/${prefixes.size}, Top3=$pre3/${prefixes.size}"

        val fuzzyPool = byFreq.take(160)
        var fzTried = 0
        var fz1 = 0
        var fz3 = 0
        var fzShown = 0
        for (entry in fuzzyPool) {
            if (fzTried >= 90) break
            val parts = entry.row.syllables.split(' ')
            val changed = parts.map { fuzzyShrink(it) }
            if (changed == parts) continue
            val raw = changed.joinToString("")
            if (raw == entry.row.letters) continue
            val rival = lexicon.exact(raw, 1).firstOrNull()
            if (rival != null && rival.freq * 3 >= entry.row.freq) continue
            fzTried++
            val decoded = decoder.decode(raw, fuzzy = true)
            val top = decoded.candidates.take(3).map { it.text }
            if (top.firstOrNull() == entry.row.text) fz1++
            if (entry.row.text in top) fz3++
            else if (fzShown < 20) {
                fzShown++
                lines += "[FUZZY] '$raw' -> want '${entry.row.text}' (${entry.row.syllables}, f=${entry.row.freq}), rival=${rival?.text}(${rival?.freq}), got $top"
            }
        }
        lines += "FUZZY_AUTO : Top1=$fz1/$fzTried, Top3=$fz3/$fzTried"

        val typos = byFreq.take(80).filter { it.row.letters.length >= 6 && it.nsy == 2 }
        var typoTried = 0
        var typo3 = 0
        var typoShown = 0
        for (entry in typos) {
            if (typoTried >= 36) break
            val letters = entry.row.letters
            val cut = entry.row.syllables.substringBefore(' ').length
            if (cut <= 0 || cut >= letters.length) continue
            val chars = letters.toCharArray()
            val tmp = chars[cut - 1]
            chars[cut - 1] = chars[cut]
            chars[cut] = tmp
            val swapped = String(chars)
            val rival = lexicon.exact(swapped, 1).firstOrNull()
            if (rival != null && rival.freq * 8 >= entry.row.freq) continue
            typoTried++
            val decoded = decoder.decode(swapped, fuzzy = false)
            val top = decoded.candidates.take(5).map { it.text }
            if (entry.row.text in top) typo3++
            else if (typoShown < 16) {
                typoShown++
                lines += "[BOUNDARY_SWAP] '$swapped' -> want '${entry.row.text}' (${entry.row.letters}, f=${entry.row.freq}), rival=${rival?.text}(${rival?.freq}), got $top preedit=${decoded.preedit}"
            }
        }
        lines += "BOUNDARY_SWAP : Top5=$typo3/$typoTried"

        val probes = listOf(
            "nia", "nima", "niba", "woa", "taa", "dama", "mama",
            "dang", "dan", "shang", "xiang", "jian", "nian", "qian",
            "zhidao", "keyi", "meiyou", "yixia", "women", "shenme", "zenme",
            "haode", "haoba", "xiexie", "buneng", "app", "wifi", "hello",
        )
        for (raw in probes) {
            val decoded = decoder.decode(raw, fuzzy = false)
            val top = decoded.candidates.take(5).map { it.text }
            lines += "[PROBE] '$raw' -> $top preedit=${decoded.preedit}"
        }

        val niaDecoded = decoder.decode("nia", fuzzy = false)
        val niaTop = niaDecoded.candidates.take(3).map { it.text }
        lines += "[CHECK] nia -> $niaTop preedit=${niaDecoded.preedit}"
        val denji = decoder.decode("denji", fuzzy = true).candidates.take(3).map { it.text }
        lines += "[CHECK] denji -> $denji"
        val jisu = decoder.decode("jisu", fuzzy = true).candidates.take(3).map { it.text }
        lines += "[CHECK] jisu -> $jisu"
        val yijin = decoder.decode("yijin", fuzzy = true).candidates.take(3).map { it.text }
        lines += "[CHECK] yijin -> $yijin"

        val report = File("build/reports/round3-mine.txt")
        report.parentFile?.mkdirs()
        report.writeText(lines.joinToString("\n"), StandardCharsets.UTF_8)
        lines.forEach { println(it) }
        assertTrue("round3 evaluated an empty exact band", band.size >= 500)
        assertEquals("common syllables should keep their highest-frequency character first", singles.size, singleTop1)
        assertEquals("nia", niaDecoded.preedit)
        assertTrue("nia should rank 你啊 in top3, got $niaTop", "你啊" in niaTop)
        assertEquals("年", niaTop.first())
        assertEquals("当", decoder.decode("dang", fuzzy = false).candidates.first().text)
        assertTrue("denji fuzzy should rank 等级 in top3, got $denji", "等级" in denji)
        assertEquals("激素", jisu.first())
        assertTrue("jisu fuzzy should keep 技术 in top3, got $jisu", "技术" in jisu)
        assertEquals("一斤", yijin.first())
        assertTrue("yijin fuzzy should keep 已经 in top3, got $yijin", "已经" in yijin)
        assertTrue(report.isFile)
    }

    private fun fuzzyShrink(syllable: String): String = when {
        syllable.startsWith("zh") -> "z" + syllable.removePrefix("zh")
        syllable.startsWith("ch") -> "c" + syllable.removePrefix("ch")
        syllable.startsWith("sh") -> "s" + syllable.removePrefix("sh")
        syllable.endsWith("iang") -> syllable.dropLast(1)
        syllable.endsWith("uang") -> syllable.dropLast(1)
        syllable.endsWith("ang") -> syllable.dropLast(1)
        syllable.endsWith("eng") -> syllable.dropLast(1)
        syllable.endsWith("ing") -> syllable.dropLast(1)
        else -> syllable
    }
}

