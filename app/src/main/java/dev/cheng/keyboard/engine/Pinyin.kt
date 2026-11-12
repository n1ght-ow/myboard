package dev.cheng.keyboard.engine

import dev.cheng.keyboard.model.Candidate
import dev.cheng.keyboard.model.LexRow
import dev.cheng.keyboard.model.Lexicon
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.util.ArrayDeque
import java.util.Locale
import kotlin.math.ln

data class Seg(val parts: List<String>, val partial: Boolean)

class SylTrie(words: Collection<String>) {
    private class Node {
        val next = arrayOfNulls<Node>(26)
        var canonical: String? = null
    }

    private val root = Node()

    init {
        val cleaned = words.filter { word -> word.isNotEmpty() && word.all { it in 'a'..'z' } }.toSet()
        cleaned.forEach { insert(it, it) }
        for ((alias, target) in buildSyllableAliases(cleaned)) {
            insert(alias, target)
        }
    }

    private fun insert(word: String, canonicalTarget: String) {
        var node = root
        for (ch in word) {
            if (ch !in 'a'..'z') return
            val index = ch - 'a'
            val child = node.next[index] ?: Node().also { node.next[index] = it }
            node = child
        }
        if (word.isNotEmpty() && node.canonical == null) {
            node.canonical = canonicalTarget
        }
    }

    fun endings(text: String, start: Int): List<Int> {
        val out = ArrayList<Int>(4)
        var node = root
        for (index in start until text.length) {
            val ch = text[index]
            if (ch !in 'a'..'z') break
            node = node.next[ch - 'a'] ?: break
            if (node.canonical != null) out += index + 1
        }
        return out
    }

    fun canonicalEndings(text: String, start: Int): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>(4)
        var node = root
        for (index in start until text.length) {
            val ch = text[index]
            if (ch !in 'a'..'z') break
            node = node.next[ch - 'a'] ?: break
            val target = node.canonical
            if (target != null) out += (index + 1) to target
        }
        return out
    }

    fun hasExtension(text: String): Boolean {
        var node = root
        for (ch in text) {
            if (ch !in 'a'..'z') return false
            node = node.next[ch - 'a'] ?: return false
        }
        return node.next.any { it != null }
    }

    fun hasPrefix(text: String, start: Int): Boolean {
        if (start >= text.length) return false
        var node = root
        for (index in start until text.length) {
            val ch = text[index]
            if (ch !in 'a'..'z') return false
            node = node.next[ch - 'a'] ?: return false
        }
        return true
    }

    fun isWord(text: String): Boolean = canonicalOf(text) != null

    fun canonicalOf(text: String): String? {
        var node = root
        if (text.isEmpty()) return null
        for (ch in text) {
            if (ch !in 'a'..'z') return null
            node = node.next[ch - 'a'] ?: return null
        }
        return node.canonical
    }
}

private fun buildSyllableAliases(words: Set<String>): Map<String, String> {
    val candidates = listOf(
        "nue" to "nve",
        "lue" to "lve",
        "nve" to "nue",
        "lve" to "lue",
        "jv" to "ju",
        "jve" to "jue",
        "jvan" to "juan",
        "jvn" to "jun",
        "qv" to "qu",
        "qve" to "que",
        "qvan" to "quan",
        "qvn" to "qun",
        "xv" to "xu",
        "xve" to "xue",
        "xvan" to "xuan",
        "xvn" to "xun",
        "yv" to "yu",
        "yve" to "yue",
        "yvan" to "yuan",
        "yvn" to "yun",
    )
    val out = LinkedHashMap<String, String>()
    for ((alias, target) in candidates) {
        if (alias.length == target.length && alias !in words && target in words) {
            out[alias] = target
        }
    }
    return out
}

private data class LatticePath(val parts: List<String>, val score: Int)

private fun buildLattice(text: String, trie: SylTrie): Array<ArrayList<LatticePath>> {
    val size = text.length
    val paths = Array(size + 1) { ArrayList<LatticePath>(4) }
    paths[0].add(LatticePath(emptyList(), 0))
    for (start in 0 until size) {
        val from = paths[start]
        if (from.isEmpty()) continue
        val endings = ArrayList<Pair<Int, String>>(5)
        endings.addAll(trie.canonicalEndings(text, start))
        // Mid-sentence erhua ('r' followed by non-vowel consonant, e.g. "niqunarle", "yidianrdoubu")
        if (start > 0 && start + 1 < size && text[start] == 'r' && text[start + 1] !in "aeiouv" && endings.none { it.first == start + 1 }) {
            endings += (start + 1) to "r"
        }
        for ((end, syllable) in endings) {
            val len = end - start
            // Single-letter nasal/erhua pseudo-syllables ("m", "n", "r") get minimal weight so normal syllables win
            val bonus = if (len == 1 && (syllable == "m" || syllable == "n" || syllable == "r")) 0 else len * len
            val dest = paths[end]
            for (path in from) dest.add(LatticePath(path.parts + syllable, path.score + bonus))
            if (dest.size > 32) {
                dest.sortByDescending { it.score }
                val best = dest.first().score
                val kept = ArrayList<LatticePath>(18)
                val tails = HashSet<String>()
                for (option in dest) {
                    if (kept.size >= 18) break
                    val tail = option.parts.last()
                    val close = option.score + 12 >= best
                    if (!close && tail in tails && kept.size >= 8) continue
                    kept += option
                    tails += tail
                }
                dest.clear()
                dest.addAll(kept)
            }
        }
    }
    return paths
}

private fun segmentChunk(chunk: String, trie: SylTrie, allowPartial: Boolean): List<Seg> {
    val text = chunk.filter { it in 'a'..'z' }
    if (text.isEmpty()) return emptyList()
    val size = text.length
    val paths = buildLattice(text, trie)
    val full = paths[size].map { Seg(it.parts, partial = false) }
    val partials = ArrayList<Seg>()
    if (allowPartial) {
        for (start in maxOf(0, size - 6) until size) {
            if (paths[start].isEmpty()) continue
            val rest = text.substring(start)
            if (rest.isEmpty() || !trie.hasPrefix(text, start)) continue
            val canonicalRest = trie.canonicalOf(rest) ?: rest
            val ranked = paths[start].sortedByDescending { it.score }.take(3)
            for (path in ranked) partials.add(Seg(path.parts + canonicalRest, partial = true))
        }
    }
    val topFull = full.distinctBy { it.parts }.sortedByDescending { segScore(it) }.take(16)
    val topPartials = partials.distinctBy { it.parts }.sortedByDescending { segScore(it) }.take(8)
    return topFull + topPartials
}

fun segmentPinyin(raw: String, trie: SylTrie): List<Seg> {
    if ('\'' in raw) {
        val trimmed = raw.lowercase().filter { it in 'a'..'z' || it == '\'' }.trim('\'')
        if (trimmed.isNotEmpty() && '\'' in trimmed) {
            val segmented = segmentSeparated(trimmed, trie)
            if (segmented.isNotEmpty()) return segmented
        }
    }
    val text = raw.lowercase().filter { it in 'a'..'z' }
    return segmentChunk(text, trie, allowPartial = true)
}

private fun segmentSeparated(trimmed: String, trie: SylTrie): List<Seg> {
    val chunks = trimmed.split('\'').filter { it.isNotEmpty() }
    if (chunks.isEmpty()) return emptyList()
    var combined = listOf(Seg(emptyList(), partial = false))
    for ((index, chunk) in chunks.withIndex()) {
        val isLast = index == chunks.lastIndex
        val options = segmentChunk(chunk, trie, allowPartial = isLast).let { segs ->
            if (isLast) segs else segs.filter { !it.partial }
        }.take(4)
        if (options.isEmpty()) return emptyList()
        val next = ArrayList<Seg>(combined.size * options.size)
        for (prefix in combined) {
            for (opt in options) {
                next += Seg(prefix.parts + opt.parts, partial = opt.partial)
            }
        }
        combined = next.sortedByDescending { segScore(it) }.take(12)
    }
    return combined
}

private fun longestPrefixSegments(text: String, trie: SylTrie): List<Seg> {
    if (text.isEmpty()) return emptyList()
    val paths = buildLattice(text, trie)
    val out = ArrayList<Seg>(6)
    // Check longest reachable complete prefix + optional 1..5 char valid syllable prefix right after it
    for (start in text.length - 1 downTo 0) {
        val base = paths[start]
        if (base.isEmpty()) continue
        val ranked = base.sortedByDescending { it.score }.take(2)
        val maxTail = minOf(5, text.length - start)
        for (tailLen in maxTail downTo 1) {
            val sub = text.substring(start, start + tailLen)
            if (trie.hasPrefix(sub, 0)) {
                val canonicalSub = trie.canonicalOf(sub) ?: sub
                for (path in ranked) {
                    out += Seg(path.parts + canonicalSub, partial = !trie.isWord(canonicalSub))
                }
                break
            }
        }
        if (start > 0) {
            for (path in ranked) {
                out += Seg(path.parts, partial = false)
            }
        }
        if (out.size >= 6) break
    }
    return out.distinctBy { it.parts to it.partial }.take(6)
}

fun expandSyllable(input: String): List<String> {
    val seen = LinkedHashSet<String>()
    val queue = ArrayDeque<String>()
    queue.add(input)
    while (queue.isNotEmpty() && seen.size < 16) {
        val current = queue.removeFirst()
        if (!seen.add(current)) continue
        swapInitials(current).forEach(queue::add)
        swapFinals(current).forEach(queue::add)
    }
    return seen.toList()
}

private fun swapInitials(syllable: String): List<String> = when {
    syllable.startsWith("zh") -> listOf("z" + syllable.removePrefix("zh"))
    syllable.startsWith("ch") -> listOf("c" + syllable.removePrefix("ch"))
    syllable.startsWith("sh") -> listOf("s" + syllable.removePrefix("sh"))
    syllable.startsWith("z") -> listOf("zh" + syllable.removePrefix("z"))
    syllable.startsWith("c") -> listOf("ch" + syllable.removePrefix("c"))
    syllable.startsWith("s") -> listOf("sh" + syllable.removePrefix("s"))
    syllable.startsWith("n") -> listOf("l" + syllable.removePrefix("n"))
    syllable.startsWith("l") -> listOf("n" + syllable.removePrefix("l"), "r" + syllable.removePrefix("l"))
    syllable.startsWith("r") -> listOf("l" + syllable.removePrefix("r"))
    syllable.startsWith("f") -> listOf("h" + syllable.removePrefix("f"))
    syllable.startsWith("h") -> listOf("f" + syllable.removePrefix("h"))
    else -> emptyList()
}

private fun swapFinals(syllable: String): List<String> {
    val out = ArrayList<String>(2)
    val pairs = listOf(
        "iang" to "ian",
        "uang" to "uan",
        "ian" to "iang",
        "uan" to "uang",
        "ang" to "an",
        "eng" to "en",
        "ing" to "in",
        "an" to "ang",
        "en" to "eng",
        "in" to "ing",
    )
    for ((from, to) in pairs) {
        if (syllable.endsWith(from)) {
            out += syllable.dropLast(from.length) + to
            break
        }
    }
    if (syllable.endsWith("eng")) {
        out += syllable.dropLast(3) + "ong"
    } else if (syllable.endsWith("ong") && !syllable.endsWith("iong")) {
        out += syllable.dropLast(3) + "eng"
    }
    return out
}

data class Decode(val preedit: String, val candidates: List<Candidate>)

class PinyinDecoder(syllables: Set<String>) {
    private val trie = SylTrie(syllables)
    private val syllableList = syllables.filter { syllable -> syllable.isNotEmpty() && syllable.all { it in 'a'..'z' } }

    @Volatile
    var lexicon: Lexicon = dev.cheng.keyboard.model.MemoryLexicon(emptyList())

    @Volatile
    var latin: LatinDecoder? = null

    var nowProvider: () -> LocalDateTime = { LocalDateTime.now() }

    private val user = mutableListOf<LexRow>()
    private val userBigrams = HashMap<String, MutableMap<String, Int>>()

    fun decode(
        raw: String,
        fuzzy: Boolean,
        context: String = "",
        touchHints: List<String> = emptyList(),
    ): Decode {
        val rawLettersOnly = raw.filter { it in 'a'..'z' || it in 'A'..'Z' }
        val text = raw.lowercase().filter { it in 'a'..'z' || it == '\'' }
        if (text.isEmpty()) return Decode("", emptyList())
        val letters = text.filter { it in 'a'..'z' }
        if (letters.isEmpty()) return Decode("'", emptyList())

        val segs = segmentPinyin(text, trie)
        val book = Book()
        val full = segs.filter { !it.partial }
        // Keep partial segments both when last token is incomplete AND when last token is a 1-letter extensible syllable
        val open = segs.filter { seg ->
            seg.partial && seg.parts.isNotEmpty() &&
                (!trie.isWord(seg.parts.last()) || (seg.parts.last().length == 1 && trie.hasExtension(seg.parts.last())))
        }

        val chosen = ArrayList<Seg>(6)
        for (seg in full) {
            if (chosen.size >= 4) break
            chosen += seg
        }
        var partials = 0
        for (seg in open) {
            if (partials >= 2) break
            chosen += seg
            partials++
        }

        val pinyin = ArrayList<Scored>()
        val ordered = ArrayList<Scored>()
        val seen = HashSet<String>()
        for ((index, seg) in chosen.withIndex()) {
            val block = book.rank(book.candidates(seg))
            val leading = if (index == 0) book.rank(book.prefixes(seg)) else emptyList()
            for (item in block + leading) {
                pinyin += item
                if (seen.add(item.text)) ordered += item
            }
        }

        val solid = full.firstOrNull()?.parts?.all { it.length >= 2 } == true && ordered.isNotEmpty()
        val wholeAbbrev = if (solid || letters.length !in 2..64) {
            emptyList()
        } else {
            book.abbreviations(letters)
        }
        val mixedAbbrev = if (solid || letters.length !in 2..64) {
            emptyList()
        } else {
            book.mixedAbbreviations(letters)
        }

        var body = if (solid) ordered else book.rank(pinyin + wholeAbbrev + mixedAbbrev)
        val primary = full.firstOrNull() ?: open.firstOrNull()

        if (fuzzy && primary != null) {
            val fuzzyTarget = if (!primary.partial) {
                primary
            } else if (primary.parts.size >= 2) {
                Seg(primary.parts.dropLast(1), partial = false)
            } else {
                null
            }
            if (fuzzyTarget != null) {
                val extra = book.fuzzy(fuzzyTarget).filter { item -> body.none { it.text == item.text } }
                body = if (body.isEmpty()) extra else body + extra
            }
        }

        val hadDirectFullOrAbbrevChinese = body.isNotEmpty()

        // Typo & transposition recovery when input is not a clean multi-letter syllable string or multi-initial abbreviation
        val shouldTryTypos = when {
            letters.length !in 2..18 -> false
            body.isEmpty() -> true
            pinyin.isEmpty() && wholeAbbrev.isEmpty() -> true
            !solid && letters.length >= 3 && !book.hasMultiAbbrevInitials(letters, wholeAbbrev) -> true
            else -> false
        }
        if (shouldTryTypos) {
            val typoItems = book.typos(letters, touchHints)
            if (typoItems.isNotEmpty()) {
                body = if (body.isEmpty()) typoItems else book.rank(body + typoItems)
            }
        }

        // Longest valid Chinese prefix guarantee: never return empty when leading syllables are decodable!
        var fallbackPrefixSeg: Seg? = null
        if (body.isEmpty()) {
            val prefixSegs = longestPrefixSegments(letters, trie)
            fallbackPrefixSeg = prefixSegs.firstOrNull()
            val prefixCandidates = ArrayList<Scored>()
            for (seg in prefixSegs) {
                prefixCandidates += book.candidates(seg)
                prefixCandidates += book.prefixes(seg)
            }
            body = book.rank(prefixCandidates)
        }

        val cjkContext = extractTrailingCjk(context, maxChars = 4)
        if (cjkContext.isNotEmpty() && body.size > 1) {
            body = book.applyContext(body, cjkContext)
        }

        val single = primary != null && !primary.partial && primary.parts.size == 1 && body.isNotEmpty()
        val anchorSeg = primary ?: fallbackPrefixSeg
        val chars = if (single) {
            emptyList()
        } else {
            val anchored = book.anchor(anchorSeg).ifEmpty {
                book.firstLetterAnchor(letters)
            }
            anchored.filter { item -> body.none { it.text == item.text } }.take(8)
        }

        val items = (if (single) body.take(200) else (body.take(40) + chars).take(48)).toMutableList()

        if ('\'' !in text) {
            val quick = quickExpressions(letters)
            if (quick.isNotEmpty()) {
                val insertAt = minOf(1, items.size)
                val deduped = quick.filter { expr -> items.none { it.text == expr } }
                    .map { expr -> Scored(expr, "", 500_000, 80.0, items.firstOrNull()?.preedit ?: letters, false, letters.length) }
                items.addAll(insertAt, deduped)
            }
        }

        // Inline English blending when LatinDecoder is attached (e.g., "wifi", "hello", "kotlin", "app")
        val en = latin
        if (en != null && '\'' !in text && letters.length >= 2) {
            val typedCased = rawLettersOnly.ifEmpty { letters }
            val exactEnFreq = en.exactFreq(letters)
            if (!hadDirectFullOrAbbrevChinese && (exactEnFreq > 0 || (full.isEmpty() && open.isEmpty()))) {
                val enSource = if (exactEnFreq > 0) {
                    en.suggest(letters).take(3)
                } else {
                    en.suggest(letters).filter { it.text.startsWith(letters) }.take(2)
                }
                val enItems = enSource.map { cand ->
                    val cased = applyCase(cand.text, typedCased)
                    Scored(cased, "", cand.freq, 200.0, typedCased, false, letters.length)
                }.filter { cand -> items.none { it.text.equals(cand.text, ignoreCase = true) } }
                if (enItems.isNotEmpty()) {
                    items.addAll(0, enItems)
                }
            } else if (letters.length >= 3 && exactEnFreq > 0) {
                val cased = applyCase(letters, typedCased)
                if (items.none { it.text.equals(cased, ignoreCase = true) }) {
                    val insertAt = minOf(2, items.size)
                    items.add(insertAt, Scored(cased, "", exactEnFreq, 50.0, typedCased, false, letters.length))
                }
            }
        }

        val best = items.firstOrNull()
        val preedit = when {
            best == null -> chosen.firstOrNull()?.parts?.joinToString("'") ?: letters
            best.typo -> letters
            best.consumed in 1 until letters.length -> {
                val tail = letters.substring(best.consumed)
                "${best.preedit}'$tail"
            }
            else -> best.preedit
        }
        val shown = if (text.endsWith("'") && !preedit.endsWith("'")) "$preedit'" else preedit
        return Decode(shown, items.map { Candidate(it.text, it.syllables, it.freq, it.consumed) })
    }

    fun associate(text: String): List<Candidate> {
        if (text.isBlank()) return emptyList()
        val seen = LinkedHashSet<String>()
        val out = ArrayList<Candidate>(24)

        val ctx = extractTrailingCjk(text, maxChars = 4)
        for (len in minOf(4, ctx.length) downTo 1) {
            val suffix = ctx.takeLast(len)
            userBigrams[suffix]?.entries
                ?.sortedByDescending { it.value }
                ?.take(6)
                ?.forEach { (nextText, count) ->
                    if (nextText.isNotEmpty() && seen.add(nextText)) {
                        out += Candidate(nextText, "", 1_000_000 + count * 1000)
                    }
                }
        }

        user.asSequence()
            .filter { it.text.startsWith(text) && it.text.length > text.length }
            .sortedByDescending { it.freq }
            .take(8)
            .forEach { row ->
                val tail = row.text.removePrefix(text)
                if (tail.isNotEmpty() && seen.add(tail)) out += Candidate(tail, row.syllables, row.freq)
            }

        lexicon.phrasesStarting(text, 20).forEach { row ->
            val tail = row.text.removePrefix(text)
            if (tail.isNotEmpty() && seen.add(tail)) out += Candidate(tail, row.syllables, row.freq)
        }

        if (out.size < 8) {
            val hints = commonContinuations[text] ?: commonContinuations[text.takeLast(1)].orEmpty()
            for (hint in hints) {
                if (hint != text && seen.add(hint)) {
                    out += Candidate(hint, "", 100)
                    if (out.size >= 12) break
                }
            }
        }
        return out
    }

    fun learn(text: String, syllables: String, prevContext: String = ""): Int {
        val spelling = syllables.trim().replace("'", " ")
        val letters = spelling.replace(" ", "")
        if (text.isBlank() || letters.isBlank()) return 0
        val prevFreq = user.firstOrNull { it.text == text && it.letters == letters }?.freq
        val nextFreq = if (prevFreq == null) {
            1_200_000_000
        } else {
            minOf(1_950_000_000, prevFreq + 2_000_000)
        }
        user.removeAll { it.text == text && it.letters == letters }
        user.add(0, LexRow(text, spelling, letters, nextFreq))
        val ctx = extractTrailingCjk(prevContext, maxChars = 4)
        if (ctx.isNotEmpty()) learnBigram(ctx, text)
        return nextFreq
    }

    fun learnBigram(prevContext: String, text: String): List<Triple<String, String, Int>> {
        val ctx = extractTrailingCjk(prevContext, maxChars = 4)
        if (ctx.isEmpty() || text.isBlank() || extractTrailingCjk(text, maxChars = 8) != text) return emptyList()
        val keys = buildSet {
            add(ctx)
            if (ctx.length > 2) add(ctx.takeLast(2))
        }
        val updated = ArrayList<Triple<String, String, Int>>(keys.size)
        for (k in keys) {
            val bucket = userBigrams.getOrPut(k) { HashMap() }
            val nextCount = (bucket[text] ?: 0) + 1
            bucket[text] = nextCount
            updated += Triple(k, text, nextCount)
        }
        return updated
    }

    fun bigramEntriesFor(prevContext: String, text: String): List<Triple<String, String, Int>> {
        val ctx = extractTrailingCjk(prevContext, maxChars = 4)
        if (ctx.isEmpty()) return emptyList()
        val keys = buildSet {
            add(ctx)
            if (ctx.length > 2) add(ctx.takeLast(2))
        }
        return keys.mapNotNull { k ->
            val count = userBigrams[k]?.get(text) ?: return@mapNotNull null
            Triple(k, text, count)
        }
    }

    fun loadUserBigrams(entries: List<Triple<String, String, Int>>) {
        userBigrams.clear()
        for ((prev, next, count) in entries) {
            if (prev.isBlank() || next.isBlank() || count <= 0) continue
            val bucket = userBigrams.getOrPut(prev) { HashMap() }
            bucket[next] = maxOf(bucket[next] ?: 0, count)
        }
    }

    fun forget(text: String): Boolean {
        if (text.isBlank()) return false
        val removedWord = user.removeAll { it.text == text }
        var removedBigram = userBigrams.remove(text) != null
        for (bucket in userBigrams.values) {
            if (bucket.remove(text) != null) removedBigram = true
        }
        return removedWord || removedBigram
    }

    fun loadUser(rows: List<LexRow>) {
        user.clear()
        user.addAll(rows.sortedByDescending { it.freq })
    }

    fun clearUser() {
        user.clear()
        userBigrams.clear()
    }

    private fun quickExpressions(letters: String): List<String> {
        return when (letters) {
            "rq", "riqi" -> {
                val now = nowProvider()
                listOf(
                    String.format(Locale.ROOT, "%04d-%02d-%02d", now.year, now.monthValue, now.dayOfMonth),
                    "${now.year}年${now.monthValue}月${now.dayOfMonth}日",
                    String.format(Locale.ROOT, "%04d/%02d/%02d", now.year, now.monthValue, now.dayOfMonth),
                )
            }
            "sj", "shijian" -> {
                val now = nowProvider()
                listOf(
                    String.format(Locale.ROOT, "%02d:%02d", now.hour, now.minute),
                    String.format(Locale.ROOT, "%02d:%02d:%02d", now.hour, now.minute, now.second),
                )
            }
            "xq", "xingqi" -> {
                val day = when (nowProvider().dayOfWeek) {
                    DayOfWeek.MONDAY -> "一"
                    DayOfWeek.TUESDAY -> "二"
                    DayOfWeek.WEDNESDAY -> "三"
                    DayOfWeek.THURSDAY -> "四"
                    DayOfWeek.FRIDAY -> "五"
                    DayOfWeek.SATURDAY -> "六"
                    DayOfWeek.SUNDAY -> "日"
                }
                val zhou = if (day == "日") "天" else day
                listOf("星期$day", "周$zhou")
            }
            else -> emptyList()
        }
    }

    private data class Scored(
        val text: String,
        val syllables: String,
        val freq: Int,
        val score: Double,
        val preedit: String,
        val typo: Boolean,
        val consumed: Int,
    )

    private data class Piece(val text: String, val syllables: String, val freq: Int, val score: Double)

    private inner class Book {
        private val cache = HashMap<String, List<LexRow>>()
        private val abbrevCache = HashMap<String, List<LexRow>>()

        fun candidates(seg: Seg): List<Scored> {
            if (seg.partial && seg.parts.isNotEmpty() && !trie.isWord(seg.parts.last())) {
                return opened(seg)
            }
            val preedit = seg.parts.joinToString("'")
            val used = typedLength(seg.parts)
            val items = ArrayList<Scored>()
            val fetch = if (seg.parts.size == 1) 200 else 40
            for (row in spell(seg.parts, boostUser = true, limit = fetch)) {
                val whole = if (!seg.partial && seg.parts.size >= 3 && row.text.length >= 3) FULL_WORD_BONUS else 0.0
                items += scored(row, wordScore(row.text, row.freq) + whole, preedit, false, used)
            }
            for (piece in cover(seg.parts)) {
                if (piece.text.isEmpty()) continue
                items += Scored(piece.text, piece.syllables, piece.freq, piece.score, preedit, false, used)
            }
            if ((seg.partial || seg.parts.size >= 2) && seg.parts.isNotEmpty() && trie.hasExtension(seg.parts.last())) {
                items += opened(seg)
            }
            return items
        }

        fun prefixes(seg: Seg): List<Scored> {
            val completeParts = if (seg.partial) seg.parts.dropLast(1) else seg.parts
            if (completeParts.isEmpty()) return emptyList()
            val maxEnd = if (seg.partial) completeParts.size else completeParts.size - 1
            if (maxEnd < 1) return emptyList()
            val items = ArrayList<Scored>()
            for (end in maxEnd downTo 1) {
                val head = completeParts.subList(0, end)
                val direct = spell(head, boostUser = true).firstOrNull() ?: continue
                items += scored(
                    direct,
                    wordScore(direct.text, direct.freq),
                    head.joinToString("'"),
                    false,
                    typedLength(head),
                )
                if (items.size >= 3) break
            }
            if (seg.partial && completeParts.size >= 2) {
                val fullHeadLen = typedLength(completeParts)
                if (items.none { it.consumed == fullHeadLen }) {
                    val composed = cover(completeParts).firstOrNull()
                    if (composed != null && composed.text.isNotEmpty()) {
                        items.add(
                            0,
                            Scored(
                                composed.text,
                                composed.syllables,
                                composed.freq,
                                composed.score - 2.0,
                                completeParts.joinToString("'"),
                                false,
                                fullHeadLen,
                            ),
                        )
                    }
                }
            }
            return items
        }

        fun abbreviations(letters: String): List<Scored> {
            val items = ArrayList<Scored>()
            val paths = tokenPaths(letters)
            for (token in paths) {
                if (token.size > 12) continue
                val shortened = token.count { it.length < 2 || !trie.isWord(it) }
                if (shortened == 0) continue
                val key = buildString { for (part in token) append(part[0]) }
                val rows = initialsQuery(key, 64, exactOnly = false)
                for (row in rows) {
                    val parts = row.syllables.split(' ')
                    if (parts.size < token.size || parts.size > token.size + 2) continue
                    if (!fits(parts, token)) continue
                    val extra = parts.size - token.size
                    var penalty = 8.0 * shortened + 12.0 * extra
                    for (index in token.indices) {
                        val syllable = parts[index]
                        if ('v' !in token[index] && syllable.length >= 2 && syllable[1] == 'v') penalty += 18.0
                    }
                    items += scored(
                        row,
                        wordScore(row.text, row.freq) - penalty,
                        token.joinToString("'"),
                        false,
                        letters.length,
                    )
                }
            }
            return items
        }

        // Multi-word DP combining full syllables and abbreviated initials (e.g. "jintiantq" -> "今天天气", "wszgr" -> "我是中国人")
        fun mixedAbbreviations(letters: String): List<Scored> {
            data class AbbrevPiece(val text: String, val syllables: String, val freq: Int, val score: Double, val hasMulti: Boolean)

            val items = ArrayList<Scored>()
            val paths = tokenPaths(letters)
            for (token in paths) {
                if (token.size !in 2..20) continue
                val shortenedCount = token.count { it.length < 2 || !trie.isWord(it) }
                if (shortenedCount == 0) continue

                val dp = Array(token.size + 1) { ArrayList<AbbrevPiece>() }
                dp[0].add(AbbrevPiece("", "", 0, 0.0, false))
                val maxSpan = minOf(5, token.size)

                for (end in 1..token.size) {
                    val found = ArrayList<AbbrevPiece>()
                    for (start in maxOf(0, end - maxSpan) until end) {
                        if (dp[start].isEmpty()) continue
                        val span = token.subList(start, end)
                        val spanShortened = span.count { it.length < 2 || !trie.isWord(it) }
                        val allowSingleShort = span.size == 1 && (
                            (start == 0 && token.size >= 3) ||
                                (end == token.size && start >= 2)
                            )
                        val candidatesForSpan: List<Pair<LexRow, Double>> = when {
                            spanShortened == 0 -> {
                                spell(span, boostUser = (start == 0 && end == token.size)).take(3).map { row ->
                                    row to wordScore(row.text, row.freq)
                                }
                            }
                            span.size == 1 && allowSingleShort -> {
                                charsOf(span[0]).take(3).map { row ->
                                    val umlautPen = if ('v' !in span[0] && row.syllables.length >= 2 && row.syllables[1] == 'v') 18.0 else 0.0
                                    row to (wordScore(row.text, row.freq) - 10.0 - umlautPen)
                                }
                            }
                            span.size >= 2 -> {
                                abbrevSpan(span, allowOneExtra = (end == token.size)).take(3)
                            }
                            else -> emptyList()
                        }
                        if (candidatesForSpan.isEmpty()) continue
                        val isMultiSpan = span.size >= 2
                        for (prev in dp[start]) {
                            for ((row, spanScore) in candidatesForSpan) {
                                found += AbbrevPiece(
                                    prev.text + row.text,
                                    joinSyl(prev.syllables, row.syllables),
                                    if (prev.text.isEmpty()) row.freq else minOf(prev.freq, row.freq),
                                    prev.score + spanScore,
                                    prev.hasMulti || isMultiSpan,
                                )
                            }
                        }
                    }
                    val dedup = LinkedHashMap<String, AbbrevPiece>()
                    for (cand in found) {
                        if (cand.text.isEmpty()) continue
                        val existing = dedup[cand.text]
                        if (existing == null || cand.score > existing.score) dedup[cand.text] = cand
                    }
                    dp[end] = ArrayList(dedup.values.sortedByDescending { it.score }.take(3))
                }

                val preedit = token.joinToString("'")
                for (piece in dp[token.size]) {
                    if (piece.text.isNotEmpty() && piece.hasMulti) {
                        items += Scored(piece.text, piece.syllables, piece.freq, piece.score, preedit, false, letters.length)
                    }
                }
            }
            return items
        }

        private fun abbrevSpan(span: List<String>, allowOneExtra: Boolean): List<Pair<LexRow, Double>> {
            val key = buildString { for (part in span) append(normalizePrefix(part)[0]) }
            val shortened = span.count { it.length < 2 || !trie.isWord(it) }
            val maxParts = if (allowOneExtra) span.size + 1 else span.size
            val out = ArrayList<Pair<LexRow, Double>>()
            for (row in initialsQuery(key, 64, exactOnly = !allowOneExtra)) {
                val parts = row.syllables.split(' ')
                if (parts.size < span.size || parts.size > maxParts) continue
                if (!fits(parts, span)) continue
                val extra = parts.size - span.size
                var penalty = 8.0 * shortened + 12.0 * extra
                for (index in span.indices) {
                    val syllable = parts[index]
                    if ('v' !in span[index] && syllable.length >= 2 && syllable[1] == 'v') penalty += 18.0
                }
                out += row to (wordScore(row.text, row.freq) - penalty)
            }
            return out.sortedByDescending { it.second }
        }

        private fun initialsQuery(key: String, limit: Int, exactOnly: Boolean): List<LexRow> {
            return abbrevCache.getOrPut("$exactOnly:$limit:$key") {
                val fromUser = user.filter {
                    val init = dev.cheng.keyboard.model.initialsOf(it.syllables)
                    if (exactOnly) init == key else init.startsWith(key)
                }
                (fromUser + lexicon.byInitials(key, limit, exactOnly = exactOnly)).distinctBy { it.text to it.syllables }
            }
        }

        fun fuzzy(seg: Seg): List<Scored> {
            if (seg.parts.isEmpty() || seg.parts.size > 6) return emptyList()
            val used = typedLength(seg.parts)
            val items = ArrayList<Scored>()
            for (index in seg.parts.indices) {
                val variants = expandSyllable(seg.parts[index])
                    .drop(1)
                    .mapNotNull { trie.canonicalOf(it) }
                    .distinct()
                    .take(4)
                for (alt in variants) {
                    val parts = seg.parts.toMutableList()
                    parts[index] = alt
                    val preedit = parts.joinToString("'")
                    val matched = spell(parts, boostUser = true).take(2)
                    if (matched.isNotEmpty()) {
                        for (row in matched) {
                            items += scored(row, wordScore(row.text, row.freq) - 32.0, preedit, false, used)
                        }
                    } else if (parts.size >= 2) {
                        for (piece in cover(parts).take(1)) {
                            if (piece.text.isNotEmpty()) {
                                items += Scored(piece.text, piece.syllables, piece.freq, piece.score - 36.0, preedit, false, used)
                            }
                        }
                    }
                }
            }
            return rank(items)
        }

        fun hasMultiAbbrevInitials(letters: String, wholeAbbrev: List<Scored>): Boolean {
            if (wholeAbbrev.isEmpty()) return false
            return tokenPaths(letters).any { path ->
                path.count { part -> part.length == 1 && part[0] !in "aeiouv" } >= 2
            }
        }

        fun applyContext(items: List<Scored>, cjkContext: String): List<Scored> {
            val maxConsumed = items.maxOfOrNull { it.consumed } ?: return items
            val suffixLengths = (1..minOf(3, cjkContext.length)).toList()
            val checkWindow = items.take(24)
            val compoundQueries = ArrayList<String>(checkWindow.size * suffixLengths.size)
            for (cand in checkWindow) {
                if (cand.consumed < maxConsumed || cand.text.length !in 1..4) continue
                for (len in suffixLengths) {
                    compoundQueries += cjkContext.takeLast(len) + cand.text
                }
            }
            val compoundFreqs = if (compoundQueries.isEmpty()) emptyMap() else lexicon.phraseFreqs(compoundQueries)
            val reduplicatedAdverb = cjkContext.length >= 2 && cjkContext.last() == cjkContext[cjkContext.length - 2]

            val rescored = items.mapIndexed { idx, item ->
                if (idx >= 24 || item.consumed < maxConsumed) return@mapIndexed item
                var bonus = 0.0
                for (len in minOf(4, cjkContext.length) downTo 1) {
                    val count = userBigrams[cjkContext.takeLast(len)]?.get(item.text) ?: 0
                    if (count > 0) {
                        val uBonus = (if (len >= 2) 48.0 else 28.0) + 12.0 * ln((count + 1).toDouble())
                        if (uBonus > bonus) bonus = uBonus
                    }
                }
                if (item.text.length in 1..4) {
                    for (len in suffixLengths) {
                        val freq = compoundFreqs[cjkContext.takeLast(len) + item.text] ?: 0
                        if (freq >= 100) {
                            val cBonus = if (len >= 2) {
                                3.2 * ln(freq.toDouble())
                            } else if (freq >= 500) {
                                minOf(16.0, 1.35 * ln(freq.toDouble()))
                            } else {
                                0.0
                            }
                            if (cBonus > bonus) bonus = cBonus
                        }
                    }
                }
                if (reduplicatedAdverb && item.text == "地" && item.syllables == "de") {
                    bonus = maxOf(bonus, 18.0)
                }
                if (bonus > 0.0) item.copy(score = item.score + bonus) else item
            }
            return rank(rescored)
        }

        fun typos(letters: String, touchHints: List<String> = emptyList()): List<Scored> {
            val items = ArrayList<Scored>()
            for ((seg, cost, spatialHit) in correctedSegments(letters, touchHints)) {
                val directPenalty = when {
                    spatialHit && cost == 1 -> 12.0
                    cost == 1 -> 18.0
                    else -> 36.0 * cost
                }
                val directRows = spell(seg.parts, boostUser = true).take(2)
                if (directRows.isNotEmpty()) {
                    for (row in directRows) {
                        items += scored(row, wordScore(row.text, row.freq) - directPenalty, letters, true, letters.length)
                    }
                } else if (seg.parts.size >= 2) {
                    for (piece in cover(seg.parts).take(1)) {
                        if (piece.text.isNotEmpty()) {
                            items += Scored(piece.text, piece.syllables, piece.freq, piece.score - 40.0 * cost, letters, true, letters.length)
                        }
                    }
                }
            }
            // Cross-syllable adjacent transposition (e.g. "nhiao" -> "nihao", "wmoen" -> "women", "zohngguo" -> "zhongguo")
            if (letters.length in 3..14) {
                for (idx in 0 until letters.length - 1) {
                    if (letters[idx] == letters[idx + 1]) continue
                    val chars = letters.toCharArray()
                    val tmp = chars[idx]
                    chars[idx] = chars[idx + 1]
                    chars[idx + 1] = tmp
                    val swapped = String(chars)
                    val seg = segmentChunk(swapped, trie, allowPartial = false)
                        .firstOrNull { !it.partial && it.parts.size >= 2 && it.parts.all { p -> p.length >= 2 } }
                        ?: continue
                    val direct = spell(seg.parts, boostUser = true).take(2)
                    if (direct.isNotEmpty()) {
                        for (row in direct) {
                            items += scored(row, wordScore(row.text, row.freq) - 18.0, letters, true, letters.length)
                        }
                    } else {
                        for (piece in cover(seg.parts).take(1)) {
                            if (piece.text.isNotEmpty()) {
                                items += Scored(piece.text, piece.syllables, piece.freq, piece.score - 38.0, letters, true, letters.length)
                            }
                        }
                    }
                }
            }
            // Also support 1-typo head + 1..2 char partial syllable at the end (e.g. "nihsow" -> "你好我", "nihsoz" -> "你好在")
            if (letters.length >= 4) {
                for (tailLen in 1..minOf(2, letters.length - 2)) {
                    val headText = letters.dropLast(tailLen)
                    val tailText = letters.takeLast(tailLen)
                    val tailChars = charsOf(tailText)
                    if (tailChars.isEmpty()) continue
                    for ((seg, cost, _) in correctedSegments(headText, touchHints.take(headText.length)).filter { it.second == 1 }.take(2)) {
                        val headPieces = spell(seg.parts, boostUser = true).take(1).map {
                            Piece(it.text, it.syllables, it.freq, wordScore(it.text, it.freq))
                        }.ifEmpty { cover(seg.parts).take(1) }
                        for (hp in headPieces) {
                            for (tc in tailChars.take(2)) {
                                items += Scored(
                                    hp.text + tc.text,
                                    joinSyl(hp.syllables, tc.syllables),
                                    minOf(hp.freq.coerceAtLeast(1), tc.freq),
                                    hp.score + wordScore(tc.text, tc.freq) - 44.0 * cost - 10.0,
                                    letters,
                                    true,
                                    letters.length,
                                )
                            }
                        }
                    }
                    if (items.isNotEmpty()) break
                }
            }
            return rank(items)
        }

        fun anchor(seg: Seg?): List<Scored> {
            if (seg == null || seg.parts.isEmpty()) return emptyList()
            val syllable = seg.parts.first()
            val rows = if (seg.partial && seg.parts.size == 1) {
                charsOf(syllable)
            } else {
                spell(listOf(syllable), boostUser = true).filter { it.text.length == 1 }.take(8).ifEmpty {
                    charsOf(syllable)
                }
            }
            val preedit = syllable
            return rows.map { scored(it, wordScore(it.text, it.freq), preedit, false, syllable.length) }
        }

        fun firstLetterAnchor(letters: String): List<Scored> {
            for (index in letters.indices) {
                val ch = letters[index].toString()
                val rows = charsOf(ch)
                if (rows.isNotEmpty()) {
                    val consumed = index + 1
                    return rows.map { scored(it, wordScore(it.text, it.freq) - 20.0, letters.substring(0, consumed), false, consumed) }
                }
            }
            return emptyList()
        }

        fun rank(items: List<Scored>): List<Scored> {
            val best = LinkedHashMap<String, Scored>()
            for (item in items) {
                if (item.text.isEmpty()) continue
                val previous = best[item.text]
                if (previous == null || item.score > previous.score) best[item.text] = item
            }
            return best.values.sortedByDescending { it.score }
        }

        private fun opened(seg: Seg): List<Scored> {
            val preedit = seg.parts.joinToString("'")
            val used = typedLength(seg.parts)
            val items = ArrayList<Scored>()
            val head = seg.parts.dropLast(1)
            val tail = normalizePrefix(seg.parts.last())
            val canonicalHead = head.map { if (it == "r") "er" else (trie.canonicalOf(it) ?: it) }
            val normalizedParts = canonicalHead + tail
            val prefix = normalizedParts.joinToString(" ")
            val longer = lexicon.spellingStarting(prefix, normalizedParts.size, 8) + user.filter { extends(it, normalizedParts) }
            for (row in longer) {
                if (!extends(row, normalizedParts)) continue
                items += scored(row, wordScore(row.text, row.freq), preedit, false, used)
            }
            // Trailing erhua support (e.g. "wanr" -> "玩儿", "niqunar" -> "你去哪儿", "yidianr" -> "一点儿")
            if (tail == "r" && canonicalHead.isNotEmpty()) {
                for (takeCount in 1..minOf(2, canonicalHead.size)) {
                    val erhuaParts = canonicalHead.takeLast(takeCount) + "er"
                    val erhuaRows = spell(erhuaParts, boostUser = true).take(2)
                    if (erhuaRows.isEmpty()) continue
                    val prefixHeads = headPieces(canonicalHead.dropLast(takeCount))
                    for (hp in prefixHeads) {
                        for (erRow in erhuaRows) {
                            val combined = hp.text + erRow.text
                            if (combined.isEmpty()) continue
                            items += Scored(
                                combined,
                                joinSyl(hp.syllables, erRow.syllables),
                                if (hp.text.isEmpty()) erRow.freq else minOf(hp.freq, erRow.freq),
                                hp.score + wordScore(erRow.text, erRow.freq),
                                preedit,
                                false,
                                used,
                            )
                        }
                    }
                }
            }
            val heads = headPieces(canonicalHead)
            for (piece in heads) {
                for (row in charsOf(tail)) {
                    val text = piece.text + row.text
                    if (text.isEmpty()) continue
                    items += Scored(
                        text,
                        joinSyl(piece.syllables, row.syllables),
                        if (piece.text.isEmpty()) row.freq else minOf(piece.freq, row.freq),
                        piece.score + wordScore(row.text, row.freq),
                        preedit,
                        false,
                        used,
                    )
                }
            }
            return items
        }

        private fun headPieces(head: List<String>): List<Piece> {
            if (head.isEmpty()) return listOf(Piece("", "", 0, 0.0))
            val words = spell(head, boostUser = true).take(3).map { Piece(it.text, it.syllables, it.freq, wordScore(it.text, it.freq)) }
            val built = if (head.size >= 2) cover(head) else emptyList()
            val merged = (words + built).distinctBy { it.text }.sortedByDescending { it.score }.take(3)
            return merged.ifEmpty { listOf(Piece("", "", 0, 0.0)) }
        }

        private fun cover(parts: List<String>): List<Piece> {
            if (parts.isEmpty()) return emptyList()
            val dp = Array(parts.size + 1) { ArrayList<Piece>() }
            dp[0].add(Piece("", "", 0, 0.0))
            val width = minOf(6, parts.size)
            for (end in 1..parts.size) {
                val found = ArrayList<Piece>()
                for (start in maxOf(0, end - width) until end) {
                    if (dp[start].isEmpty()) continue
                    val spanLen = end - start
                    val rows = spell(parts.subList(start, end), boostUser = (spanLen >= 2)).take(if (spanLen == 1) 4 else 3)
                    for (prev in dp[start]) {
                        for (row in rows) {
                            found += Piece(
                                prev.text + row.text,
                                joinSyl(prev.syllables, row.syllables),
                                if (prev.text.isEmpty()) row.freq else minOf(prev.freq, row.freq),
                                prev.score + wordScore(row.text, row.freq),
                            )
                        }
                    }
                }
                // Bridge dead-end syllables (e.g., "n", "m", colloquial "zhei", or rare syllables missing nsy=1) so multi-word DP never collapses
                if (found.isEmpty() && dp[end - 1].isNotEmpty()) {
                    val bridgeRows = fallbackCharsForSyllable(parts[end - 1])
                    for (prev in dp[end - 1]) {
                        for (row in bridgeRows) {
                            found += Piece(
                                prev.text + row.text,
                                joinSyl(prev.syllables, row.syllables),
                                if (prev.text.isEmpty()) row.freq else minOf(prev.freq, row.freq),
                                prev.score + wordScore(row.text, row.freq) - 24.0,
                            )
                        }
                    }
                }
                dp[end] = ArrayList(rankPieces(found).take(3))
            }
            return dp[parts.size]
        }

        private fun fallbackCharsForSyllable(syllable: String): List<LexRow> {
            val normalized = normalizePrefix(syllable)
            when (normalized) {
                "n", "eng" -> {
                    val enRows = spell(listOf("en")).take(1)
                    if (enRows.isNotEmpty()) return enRows
                }
                "zhei" -> {
                    val zheRows = spell(listOf("zhe")).take(1)
                    if (zheRows.isNotEmpty()) return zheRows
                }
                "r" -> {
                    val erRows = spell(listOf("er")).take(1)
                    if (erRows.isNotEmpty()) return erRows
                }
            }
            // Extract authentic leading character from multi-character lexicon entries starting with this exact syllable
            val fromPhrases = lexicon.prefixed(normalized, 8).mapNotNull { row ->
                val sylParts = row.syllables.split(' ')
                if (sylParts.firstOrNull() == normalized && row.text.isNotEmpty()) {
                    val cp = row.text.codePointAt(0)
                    val firstChar = String(Character.toChars(cp))
                    LexRow(firstChar, normalized, normalized, row.freq)
                } else {
                    null
                }
            }.distinctBy { it.text }.take(2)
            if (fromPhrases.isNotEmpty()) return fromPhrases

            for (len in normalized.length downTo 1) {
                val found = charsOf(normalized.substring(0, len))
                if (found.isNotEmpty()) return found.take(2)
            }
            return emptyList()
        }

        private fun spell(parts: List<String>, boostUser: Boolean = false, limit: Int = 12): List<LexRow> {
            if (parts.isEmpty()) return emptyList()
            val canonicalParts = parts.map { if (it == "r") "er" else (trie.canonicalOf(it) ?: it) }
            val key = canonicalParts.joinToString(" ")
            val cacheKey = "${if (boostUser) "u" else "d"}:$limit:$key"
            return cache.getOrPut(cacheKey) {
                val dict = lexicon.spelling(key, limit).ifEmpty {
                    if (canonicalParts.size == 1) {
                        lexicon.charsStarting(key, limit).filter { it.syllables == key && it.text.length == 1 }
                    } else {
                        emptyList()
                    }
                }
                if (!boostUser && dict.isNotEmpty()) {
                    dict
                } else {
                    val extra = user.filter { it.syllables == key }
                    (extra + dict).distinctBy { it.text }.sortedByDescending { it.freq }
                }
            }
        }

        private fun charsOf(prefix: String): List<LexRow> {
            val normalized = normalizePrefix(prefix)
            if (normalized.isEmpty()) return emptyList()
            val fromUser = user.asSequence()
                .filter { it.text.length == 1 && it.syllables.indexOf(' ') < 0 && it.syllables.startsWith(normalized) }
                .toList()
            val fromDict = lexicon.charsStarting(normalized, 10)
                .filter { it.text.length == 1 && it.syllables.startsWith(normalized) }
            return (fromUser + fromDict)
                .distinctBy { it.text }
                .sortedByDescending { it.freq }
                .take(6)
        }

        private fun normalizePrefix(prefix: String): String = when {
            prefix == "jv" -> "ju"
            prefix == "qv" -> "qu"
            prefix == "xv" -> "xu"
            prefix == "yv" -> "yu"
            prefix == "nue" && trie.canonicalOf("nue") != null -> trie.canonicalOf("nue")!!
            prefix == "lue" && trie.canonicalOf("lue") != null -> trie.canonicalOf("lue")!!
            else -> trie.canonicalOf(prefix) ?: prefix
        }

        private fun tokenPaths(text: String): List<List<String>> = listOf(
            tokens(text, mergeShengmu = true, splitTrailingNg = false),
            tokens(text, mergeShengmu = false, splitTrailingNg = false),
            tokens(text, mergeShengmu = true, splitTrailingNg = true),
        ).distinct()

        private fun tokens(text: String, mergeShengmu: Boolean, splitTrailingNg: Boolean = false): List<String> {
            val out = ArrayList<String>()
            var index = 0
            while (index < text.length) {
                var end = -1
                var canonicalMatch: String? = null
                for ((cut, canonical) in trie.canonicalEndings(text, index)) {
                    if (cut - index >= 2 && cut > end) {
                        end = cut
                        canonicalMatch = canonical
                    }
                }
                if (
                    splitTrailingNg &&
                    canonicalMatch != null &&
                    canonicalMatch.endsWith("ng") &&
                    end < text.length &&
                    text[end] !in "aeiouv"
                ) {
                    val shorterEnd = end - 1
                    val shorterSyl = trie.canonicalOf(text.substring(index, shorterEnd))
                    if (shorterSyl != null && shorterEnd - index >= 2) {
                        end = shorterEnd
                        canonicalMatch = shorterSyl
                    }
                }
                if (end > index && canonicalMatch != null) {
                    out += canonicalMatch
                    index = end
                    continue
                }
                if (mergeShengmu && index + 2 <= text.length && text.substring(index, index + 2) in shengmu) {
                    out += text.substring(index, index + 2)
                    index += 2
                    continue
                }
                out += text.substring(index, index + 1)
                index += 1
            }
            return out
        }

        private fun fits(parts: List<String>, token: List<String>): Boolean {
            for (index in token.indices) {
                val typed = token[index]
                val syllable = parts[index]
                val canonicalTyped = trie.canonicalOf(typed)
                if (typed.length >= 2 && canonicalTyped != null) {
                    if (syllable != canonicalTyped) return false
                } else {
                    val prefix = normalizePrefix(typed)
                    if (!syllable.startsWith(prefix)) return false
                }
            }
            return true
        }

        private fun extends(row: LexRow, parts: List<String>): Boolean {
            val syllables = row.syllables.split(' ')
            if (syllables.size != parts.size || parts.isEmpty()) return false
            for (index in 0 until parts.lastIndex) {
                val target = trie.canonicalOf(parts[index]) ?: parts[index]
                if (syllables[index] != target) return false
            }
            val typed = normalizePrefix(parts.last())
            val last = syllables.last()
            return last.startsWith(typed) && last.length > typed.length
        }

        private fun scored(row: LexRow, score: Double, preedit: String, typo: Boolean, consumed: Int) =
            Scored(row.text, row.syllables, row.freq, score, preedit, typo, consumed)

        private fun typedLength(parts: List<String>) = parts.sumOf { it.length }

        private fun rankPieces(items: List<Piece>): List<Piece> {
            val best = LinkedHashMap<String, Piece>()
            for (item in items) {
                if (item.text.isEmpty()) continue
                val previous = best[item.text]
                if (previous == null || item.score > previous.score) best[item.text] = item
            }
            return best.values.sortedByDescending { it.score }
        }

        private fun joinSyl(left: String, right: String): String = when {
            left.isEmpty() -> right
            right.isEmpty() -> left
            else -> "$left $right"
        }
    }

    private fun wordScore(text: String, freq: Int): Double {
        val count = text.length.coerceAtLeast(1)
        return count * count * ln(freq.coerceAtLeast(1).toDouble())
    }

    private companion object {
        const val FULL_WORD_BONUS = 28.0
        val shengmu = setOf("zh", "ch", "sh")
        val commonContinuations = mapOf(
            "我" to listOf("们", "的", "是", "在", "有", "也", "觉得", "想"),
            "你" to listOf("们", "的", "好", "是", "在", "有"),
            "他" to listOf("们", "的", "是", "在"),
            "她" to listOf("们", "的", "是", "在"),
            "我们" to listOf("的", "是", "在", "都", "一起", "可以"),
            "你们" to listOf("的", "好", "是", "在"),
            "谢谢" to listOf("你", "大家", "啦"),
            "不好" to listOf("意思", "看"),
            "没有" to listOf("问题", "办法", "关系"),
            "没" to listOf("关系", "问题", "事", "有"),
            "可以" to listOf("的", "吗", "了"),
            "什么" to listOf("时候", "意思", "样"),
            "怎么" to listOf("样", "办", "回事"),
        )
    }

    private fun correctedSegments(text: String, touchHints: List<String> = emptyList()): List<Triple<Seg, Int, Boolean>> {
        val paths = Array(text.length + 1) { ArrayList<Node>(4) }
        paths[0].add(Node(emptyList(), 0, 0, false))
        for (start in 0 until text.length) {
            val from = paths[start]
            if (from.isEmpty()) continue
            for (edge in syllableEdges(text, start, touchHints)) {
                val dest = paths[edge.end]
                for (node in from) {
                    val cost = node.cost + edge.cost
                    if (cost > 2) continue
                    dest += Node(
                        node.parts + edge.syllable,
                        cost,
                        node.score + edge.syllable.length * edge.syllable.length - edge.cost * 5 + (if (edge.spatial) 2 else 0),
                        node.spatial || edge.spatial,
                    )
                }
                prune(dest)
            }
        }
        return paths[text.length]
            .filter { it.cost > 0 && it.parts.isNotEmpty() }
            .map { Triple(Seg(it.parts, partial = false), it.cost, it.spatial) }
            .distinctBy { it.first.parts to it.second }
            .take(8)
    }

    private data class Edge(val syllable: String, val end: Int, val cost: Int, val spatial: Boolean = false)

    private fun syllableEdges(text: String, start: Int, touchHints: List<String>): List<Edge> {
        val exact = trie.canonicalEndings(text, start).map { (end, canonical) ->
            Edge(canonical, end, 0)
        }
        val typos = ArrayList<Edge>()
        val remain = text.length - start
        for (syllable in syllableList) {
            if (syllable.length < 2 || syllable.length > remain + 1) continue
            val edge = typoEdge(text, start, syllable, touchHints) ?: continue
            typos += edge
        }
        typos.sortWith(compareBy<Edge> { it.cost }.thenByDescending { it.spatial }.thenByDescending { it.syllable.length })
        return exact + typos.take(6)
    }

    private fun typoEdge(text: String, start: Int, syllable: String, touchHints: List<String>): Edge? {
        val remain = text.length - start
        var best: Edge? = null
        for (take in intArrayOf(syllable.length, syllable.length - 1, syllable.length + 1)) {
            if (take < 1 || take > remain) continue
            val sub = text.substring(start, start + take)
            val (cost, spatial) = editCost(sub, syllable, touchHints, start) ?: continue
            if (cost != 1) continue
            val edge = Edge(syllable, start + take, cost, spatial)
            if (best == null || take > best.end - start) best = edge
        }
        return best
    }

    private fun prune(nodes: MutableList<Node>) {
        if (nodes.size <= 8) return
        nodes.sortWith(compareBy<Node>({ it.cost }, { -it.score }))
        val kept = ArrayList<Node>(8)
        var plain = 0
        var edited = 0
        for (node in nodes) {
            if (node.cost == 0 && plain < 4) {
                kept += node
                plain++
            } else if (node.cost > 0 && edited < 4) {
                kept += node
                edited++
            }
        }
        nodes.clear()
        nodes.addAll(kept)
    }

    private data class Node(val parts: List<String>, val cost: Int, val score: Int, val spatial: Boolean)
}

private fun editCost(
    typed: String,
    target: String,
    touchHints: List<String> = emptyList(),
    offset: Int = 0,
): Pair<Int, Boolean>? {
    if (typed == target) return 0 to false
    val gap = typed.length - target.length
    if (gap > 1 || gap < -1) return null
    if (gap == 0) {
        var diff = -1
        for (i in typed.indices) {
            if (typed[i] == target[i]) continue
            if (diff < 0) {
                diff = i
            } else if (diff == i - 1 && typed[i] == target[diff] && typed[diff] == target[i]) {
                for (cursor in i + 1 until typed.length) {
                    if (typed[cursor] != target[cursor]) return null
                }
                return 1 to false
            } else {
                return null
            }
        }
        if (diff < 0) return 0 to false
        val spatialHit = target[diff] in touchHints.getOrNull(offset + diff).orEmpty()
        return if (spatialHit || adjacentKey(typed[diff], target[diff])) 1 to spatialHit else null
    }
    val longer = if (gap > 0) typed else target
    val shorter = if (gap > 0) target else typed
    var i = 0
    var j = 0
    var skipped = false
    while (i < longer.length && j < shorter.length) {
        if (longer[i] == shorter[j]) {
            i++
            j++
        } else if (!skipped) {
            skipped = true
            i++
        } else {
            return null
        }
    }
    if (j != shorter.length) return null
    val leftover = longer.length - i
    if (!skipped && leftover == 1) return 1 to false
    if (skipped && leftover == 0) return 1 to false
    return null
}

private fun adjacentKey(left: Char, right: Char): Boolean {
    val neighbors = when (left) {
        'q' -> "wa"
        'w' -> "qase"
        'e' -> "wsdr"
        'r' -> "edft"
        't' -> "rfgy"
        'y' -> "tghu"
        'u' -> "yhji"
        'i' -> "ujko"
        'o' -> "iklp"
        'p' -> "ol"
        'a' -> "qwsz"
        's' -> "weadzx"
        'd' -> "ersfcx"
        'f' -> "rtgvcd"
        'g' -> "tyhbvf"
        'h' -> "yujnbg"
        'j' -> "uikmnh"
        'k' -> "iolmj"
        'l' -> "opk"
        'z' -> "asx"
        'x' -> "zsdc"
        'c' -> "xdfv"
        'v' -> "cfgb"
        'b' -> "vghn"
        'n' -> "bhjm"
        'm' -> "njk"
        else -> ""
    }
    return right in neighbors
}

private fun segScore(seg: Seg): Int {
    return seg.parts.withIndex().sumOf { (index, part) ->
        val complete = !(seg.partial && index == seg.parts.lastIndex)
        if (complete) part.length * part.length else part.length
    }
}
