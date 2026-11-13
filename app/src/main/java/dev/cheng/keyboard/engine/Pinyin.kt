package dev.cheng.keyboard.engine

import dev.cheng.keyboard.model.Candidate
import dev.cheng.keyboard.model.LexRow
import dev.cheng.keyboard.model.Lexicon
import dev.cheng.keyboard.model.TouchSample
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.util.ArrayDeque
import java.util.Locale
import kotlin.math.ln

data class Seg(val parts: List<String>, val partial: Boolean)

class SylTrie(
    words: Collection<String>,
    extraAliases: List<Pair<String, String>> = emptyList(),
) {
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
        for ((alias, target) in extraAliases) {
            if (alias !in cleaned && target in cleaned) {
                insert(alias, target)
            }
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

    fun continuationLetters(prefix: String): Set<Char> {
        if (prefix.isEmpty()) return emptySet()
        var node = root
        for (ch in prefix) {
            if (ch !in 'a'..'z') return emptySet()
            node = node.next[ch - 'a'] ?: return emptySet()
        }
        return buildSet {
            for (i in 0..25) if (node.next[i] != null) add('a' + i)
        }
    }

    fun initialLetters(): Set<Char> = buildSet {
        for (i in 0..25) {
            if (root.next[i] != null) {
                val ch = 'a' + i
                if (ch !in "iuv") add(ch)
            }
        }
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

private fun isFullLatticeSyllable(syllable: String): Boolean {
    return syllable.length >= 2 || (syllable.length == 1 && syllable[0] in "aeo")
}

private fun buildLattice(text: String, trie: SylTrie): Array<ArrayList<LatticePath>> {
    val size = text.length
    val paths = Array(size + 1) { ArrayList<LatticePath>(4) }
    paths[0].add(LatticePath(emptyList(), 0))
    for (start in 0 until size) {
        val from = paths[start]
        if (from.isEmpty()) continue
        val endings = ArrayList<Pair<Int, String>>(5)
        for ((end, canonical) in trie.canonicalEndings(text, start)) {
            if (isFullLatticeSyllable(canonical)) {
                endings += end to canonical
            }
        }
        // Mid-sentence erhua ('r' followed by non-vowel consonant, e.g. "niqunarle", "yidianrdoubu")
        if (start > 0 && start + 1 < size && text[start] == 'r' && text[start + 1] !in "aeiouv" && endings.none { it.first == start + 1 }) {
            endings += (start + 1) to "r"
        }
        for ((end, syllable) in endings) {
            val len = end - start
            val dest = paths[end]
            for (path in from) {
                val prev = path.parts.lastOrNull()
                // Avoid shattering a diphthong into two adjacent 1-letter vowels inside multi-letter strings (e.g. "xiao" -> "xi'a'o")
                if (size > 2 && len == 1 && prev != null && prev.length == 1 && trie.canonicalOf(prev + syllable) != null) {
                    continue
                }
                val stealsCoda = len == 1 && (
                    syllable == "r" ||
                        (prev != null && trie.canonicalOf(prev + syllable) != null)
                    )
                val bonus = if (stealsCoda) 0 else len * len
                dest.add(LatticePath(path.parts + syllable, path.score + bonus))
            }
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

private fun isParasiticPartialTail(headParts: List<String>, tail: String, trie: SylTrie): Boolean {
    if (headParts.isEmpty()) return false
    val merged1 = headParts.last() + tail
    if (trie.canonicalOf(merged1) != null) return true
    if (tail.length == 1 && trie.hasPrefix(merged1, 0)) return true
    if (headParts.size >= 2) {
        val merged2 = headParts[headParts.lastIndex - 1] + merged1
        if (trie.canonicalOf(merged2) != null) return true
    }
    return false
}

private fun shattersIncompleteTail(parts: List<String>, trie: SylTrie): Boolean {
    if (parts.size < 2) return false
    val tail = parts.last()
    if (tail.length != 1) return false
    val merged = parts[parts.lastIndex - 1] + tail
    return trie.canonicalOf(merged) == null && trie.hasPrefix(merged, 0)
}

private fun segmentChunk(chunk: String, trie: SylTrie, allowPartial: Boolean): List<Seg> {
    val text = chunk.filter { it in 'a'..'z' }
    if (text.isEmpty()) return emptyList()
    val size = text.length
    val paths = buildLattice(text, trie)
    val full = paths[size].map { Seg(it.parts, partial = false) }
    val hasComplete = full.isNotEmpty()
    val partials = ArrayList<Seg>()
    if (allowPartial) {
        for (start in maxOf(0, size - 6) until size) {
            if (paths[start].isEmpty()) continue
            val rest = text.substring(start)
            if (rest.isEmpty() || !trie.hasPrefix(text, start)) continue
            val canonicalRest = trie.canonicalOf(rest) ?: rest
            val ranked = paths[start].sortedByDescending { it.score }.take(3)
            for (path in ranked) {
                // Never rip the coda off an already-valid complete syllable (e.g. "shang" -> "shan'g", "maofan" -> "mao'fa'n")
                if (hasComplete && isParasiticPartialTail(path.parts, canonicalRest, trie)) continue
                partials.add(Seg(path.parts + canonicalRest, partial = true))
            }
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

private fun expandSyllableWithCost(input: String): List<Pair<String, Int>> {
    val bestCost = LinkedHashMap<String, Int>()
    val queue = ArrayDeque<Pair<String, Int>>()
    bestCost[input] = 0
    queue.add(input to 0)
    while (queue.isNotEmpty() && bestCost.size < 16) {
        val (current, dist) = queue.removeFirst()
        if (dist >= 2) continue
        for (next in swapInitials(current) + swapFinals(current)) {
            if (next !in bestCost && bestCost.size < 16) {
                bestCost[next] = dist + 1
                queue.add(next to (dist + 1))
            }
        }
    }
    return bestCost.entries.map { it.key to it.value }
}

data class Decode(val preedit: String, val candidates: List<Candidate>)

class PinyinDecoder(syllables: Set<String>) {
    private val trie = SylTrie(syllables)
    private val fuzzyTrie by lazy {
        SylTrie(
            syllables,
            extraAliases = listOf(
                "tin" to "ting",
                "din" to "ding",
                "len" to "leng",
                "suang" to "shuang",
                "zuang" to "zhuang",
                "cuang" to "chuang",
            ),
        )
    }
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
        touchSamples: List<TouchSample?> = emptyList(),
    ): Decode {
        val rawLettersOnly = raw.filter { it in 'a'..'z' || it in 'A'..'Z' }
        val text = raw.lowercase().filter { it in 'a'..'z' || it == '\'' }
        if (text.isEmpty()) return Decode("", emptyList())
        val letters = text.filter { it in 'a'..'z' }
        if (letters.isEmpty()) return Decode("'", emptyList())

        val activeTrie = if (fuzzy) fuzzyTrie else trie
        val segs = segmentPinyin(text, activeTrie)
        val full = segs.filter { !it.partial }
        val keepWritten = '\'' in text
        // Keep partial segments both when last token is incomplete AND when last token is a 1-letter extensible syllable
        val open = segs.filter { seg ->
            seg.partial && seg.parts.isNotEmpty() &&
                (!activeTrie.isWord(seg.parts.last()) || (seg.parts.last().length == 1 && activeTrie.hasExtension(seg.parts.last())))
        }
        val minFullSyllables = full
            .filter { keepWritten || !shattersIncompleteTail(it.parts, activeTrie) }
            .minOfOrNull { it.parts.size }
            ?: if (open.isEmpty()) letters.length.coerceAtLeast(1) else Int.MAX_VALUE
        val book = Book(minFullSyllables = minFullSyllables, fullLength = letters.length, keepWritten = keepWritten)

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
        val blocks = chosen.map { seg -> book.rank(book.candidates(seg)) }
        val primaryIndex = blocks.indices.maxByOrNull { index ->
            blocks[index].maxOfOrNull { book.effective(it) } ?: Double.NEGATIVE_INFINITY
        } ?: -1
        val order = if (primaryIndex >= 0) {
            listOf(primaryIndex) + blocks.indices.filter { it != primaryIndex }
        } else {
            blocks.indices.toList()
        }
        for ((position, index) in order.withIndex()) {
            val seg = chosen[index]
            val leading = if (position == 0) book.rank(book.prefixes(seg)) else emptyList()
            pinyin += blocks[index]
            pinyin += leading
        }
        val rankedPinyin = book.rank(pinyin)

        val primarySeg = chosen.getOrNull(primaryIndex)
        val solid = primarySeg != null &&
            !primarySeg.partial &&
            primarySeg.parts.all { it.length >= 2 } &&
            rankedPinyin.isNotEmpty()
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

        var body = if (solid) rankedPinyin else book.rank(pinyin + wholeAbbrev + mixedAbbrev)
        val primary = primarySeg ?: full.firstOrNull() ?: open.firstOrNull()

        if (fuzzy && primary != null) {
            val fuzzyTarget = if (!primary.partial) {
                primary
            } else if (primary.parts.size >= 2) {
                Seg(primary.parts.dropLast(1), partial = false)
            } else {
                null
            }
            if (fuzzyTarget != null) {
                val extra = book.fuzzy(fuzzyTarget)
                if (extra.isNotEmpty()) {
                    body = if (body.isEmpty()) extra else book.rank(body + extra)
                }
            }
        }

        val hadDirectFullOrAbbrevChinese = body.isNotEmpty()

        // Typo & transposition recovery when input is not a clean multi-letter syllable string or multi-initial abbreviation
        val startsWithSyllable = trie.canonicalEndings(letters, 0).any { it.first >= 2 }
        val hasStrongExactAbbrev = wholeAbbrev.any { !it.opened && it.freq >= 200_000 }
        val cleanMidTyping = !solid &&
            body.isNotEmpty() &&
            open.any { seg ->
                seg.parts.last().length >= 3 &&
                    seg.parts.all { it.length >= 2 && it !in shengmu } &&
                    !activeTrie.isWord(seg.parts.last())
            }
        val weakSolidComposition = solid &&
            letters.length >= 4 &&
            rankedPinyin.none { it.consumed == letters.length && !it.opened && it.pieces == 1 } &&
            (rankedPinyin.firstOrNull()?.freq ?: 0) <= 80_000
        val shouldTryTypos = when {
            letters.length !in 2..22 -> false
            cleanMidTyping -> false
            body.isEmpty() -> true
            pinyin.isEmpty() && wholeAbbrev.isEmpty() -> true
            weakSolidComposition -> true
            !solid && letters.length >= 4 && pinyin.isEmpty() && startsWithSyllable && !hasStrongExactAbbrev -> true
            !solid && letters.length >= 3 && !book.hasMultiAbbrevInitials(letters, wholeAbbrev) -> true
            else -> false
        }
        if (shouldTryTypos) {
            val typoItems = book.typos(letters, touchHints, touchSamples)
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

        if (touchSamples.isNotEmpty() && letters.length >= 2) {
            val soft = book.softTouchVariants(letters, touchSamples)
            if (soft.isNotEmpty()) body = if (body.isEmpty()) soft else book.rank(body + soft)
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
            val topZh = body.firstOrNull()
            val invertedInitialFull = topZh != null &&
                !solid &&
                topZh.preedit.indexOf('\'') == 1 &&
                topZh.preedit.substringAfter('\'').let { tail -> tail.length >= 2 && trie.isWord(tail) }
            val credibleEnOverAbbrev = !solid &&
                letters.length >= 3 &&
                exactEnFreq >= 12_000 &&
                !trie.hasPrefix(letters, 0) &&
                (topZh?.freq ?: 0) < 800_000
            val strongZhPrefix = trie.hasPrefix(letters, 0) &&
                topZh != null &&
                !topZh.typo &&
                topZh.freq >= 500_000 &&
                exactEnFreq < 20_000
            val strongZh = solid || strongZhPrefix || (
                topZh != null &&
                    !credibleEnOverAbbrev &&
                    !topZh.typo &&
                    !topZh.opened &&
                    topZh.pieces == 1 &&
                    !invertedInitialFull &&
                    topZh.freq >= 12_000
                )
            if (!strongZh && exactEnFreq > 0) {
                val enSource = en.suggest(letters).filter { it.freq >= 50 || it.text.equals(letters, ignoreCase = true) }.take(2)
                val enItems = enSource.map { cand ->
                    val cased = applyCase(cand.text, typedCased)
                    Scored(cased, "", cand.freq, 200.0, typedCased, false, letters.length)
                }.filter { cand -> items.none { it.text.equals(cand.text, ignoreCase = true) } }
                if (enItems.isNotEmpty()) {
                    items.addAll(0, enItems)
                }
            } else if (!hadDirectFullOrAbbrevChinese && (exactEnFreq > 0 || (full.isEmpty() && open.isEmpty()))) {
                val enSource = if (exactEnFreq > 0) {
                    en.suggest(letters).take(3)
                } else {
                    en.suggest(letters).filter { it.freq >= 50 && it.text.startsWith(letters) }.take(2)
                }
                val enItems = enSource.map { cand ->
                    val cased = applyCase(cand.text, typedCased)
                    Scored(cased, "", cand.freq, 200.0, typedCased, false, letters.length)
                }.filter { cand -> items.none { it.text.equals(cand.text, ignoreCase = true) } }
                if (enItems.isNotEmpty()) {
                    items.addAll(0, enItems)
                }
            } else if (letters.length >= 2 && (exactEnFreq >= 1_000 || (exactEnFreq > 0 && rawLettersOnly.any { it.isUpperCase() }))) {
                val cased = applyCase(letters, typedCased)
                if (items.none { it.text.equals(cased, ignoreCase = true) }) {
                    val hasUpper = rawLettersOnly.any { it.isUpperCase() }
                    val insertAt = minOf(if (hasUpper) 2 else 3, items.size)
                    items.add(insertAt, Scored(cased, "", exactEnFreq, 50.0, typedCased, false, letters.length))
                }
            }
        }

        val best = items.firstOrNull()
        val preedit = if (keepWritten) {
            text
        } else when {
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

    fun nextLetterPriors(raw: String, @Suppress("UNUSED_PARAMETER") context: String = ""): FloatArray {
        val cleaned = raw.lowercase().filter { it in 'a'..'z' || it == '\'' }
        if (cleaned.isEmpty()) return baseInitialPriors()
        val chunk = cleaned.substringAfterLast('\'')
        if (chunk.isEmpty()) return baseInitialPriors()
        var sub: String? = null
        val maxLen = minOf(6, chunk.length)
        for (len in maxLen downTo 1) {
            val candidate = chunk.substring(chunk.length - len)
            if (trie.hasPrefix(candidate, 0) || trie.canonicalOf(candidate) != null) {
                sub = candidate
                break
            }
        }
        if (sub == null) return FloatArray(26)
        val out = FloatArray(26) { -0.30f }
        for (ch in trie.continuationLetters(sub)) {
            if (ch in 'a'..'z') out[ch - 'a'] = 0.85f
        }
        if (trie.canonicalOf(sub) != null) {
            for (ch in trie.initialLetters()) {
                if (ch in 'a'..'z') {
                    val idx = ch - 'a'
                    out[idx] = maxOf(out[idx], 0.30f)
                }
            }
        }
        return out
    }

    private fun baseInitialPriors(): FloatArray {
        val out = FloatArray(26)
        for (ch in "iuv") out[ch - 'a'] = -0.30f
        for (ch in "aeo") out[ch - 'a'] = 0.10f
        for (ch in "bpmfdtnlgkhjqxzcsryw") out[ch - 'a'] = 0.20f
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
        val pieces: Int = 1,
        val opened: Boolean = false,
        val untyped: Int = 0,
        val shatteredTail: Boolean = false,
    )

    private data class Piece(
        val text: String,
        val syllables: String,
        val freq: Int,
        val score: Double,
        val pieces: Int = 1,
    )

    private inner class Book(
        private val minFullSyllables: Int = Int.MAX_VALUE,
        private val fullLength: Int = 0,
        private val keepWritten: Boolean = false,
    ) {
        private val cache = HashMap<String, List<LexRow>>()
        private val abbrevCache = HashMap<String, List<LexRow>>()

        fun candidates(seg: Seg): List<Scored> {
            if (seg.partial && seg.parts.isNotEmpty() && !trie.isWord(seg.parts.last())) {
                return opened(seg)
            }
            val preedit = seg.parts.joinToString("'")
            val used = typedLength(seg.parts)
            val shattered = !keepWritten && !seg.partial && shattersIncompleteTail(seg.parts, trie)
            val items = ArrayList<Scored>()
            val fetch = if (seg.parts.size == 1) 200 else 40
            val exactRows = spell(seg.parts, boostUser = true, limit = fetch)
            for ((idx, row) in exactRows.withIndex()) {
                val whole = if (!seg.partial && seg.parts.size >= 3 && row.text.length >= 3) FULL_WORD_BONUS else 0.0
                val k = seg.parts.size.coerceAtLeast(1)
                val tailHomophoneAdj = if (idx >= 2 && k >= 2 && row.freq < 50_000) -0.35 * (idx - 1) * k * k else 0.0
                items += scored(row, wordScore(row.text, row.freq) + whole + tailHomophoneAdj, preedit, false, used, shatteredTail = shattered)
            }
            if (seg.parts.size >= 2) {
                for ((altParts, penPerSyl) in phoneticVariants(seg.parts)) {
                    val whole = if (!seg.partial && altParts.size >= 3) FULL_WORD_BONUS else 0.0
                    val count = altParts.size
                    val pen = count * count * penPerSyl
                    for (row in spell(altParts, boostUser = true, limit = 16)) {
                        items += scored(row, wordScore(row.text, row.freq) + whole - pen, preedit, false, used, shatteredTail = shattered)
                    }
                }
            }
            for (piece in cover(seg.parts)) {
                if (piece.text.isEmpty()) continue
                items += Scored(piece.text, piece.syllables, piece.freq, piece.score, preedit, false, used, piece.pieces, shatteredTail = shattered)
            }
            if ((seg.partial || seg.parts.size >= 2 || items.isEmpty()) && seg.parts.isNotEmpty() && trie.hasExtension(seg.parts.last())) {
                val codaSplit = !seg.partial &&
                    seg.parts.size >= 2 &&
                    trie.canonicalOf(seg.parts[seg.parts.lastIndex - 1] + seg.parts.last()) != null
                if (!codaSplit && !shattered) {
                    items += opened(seg)
                }
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
                                composed.pieces,
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
                if (shattersIncompleteTail(token, trie)) continue
                if (token.indices.any { idx ->
                        idx > 0 &&
                            token[idx].length == 1 &&
                            token[idx][0] in "aeiouv" &&
                            trie.canonicalOf(token[idx - 1] + token[idx]) == null &&
                            trie.hasPrefix(token[idx - 1] + token[idx], 0)
                    }) continue
                val shortened = token.count { it.length < 2 || !trie.isWord(it) }
                if (shortened == 0) continue
                val key = buildString { for (part in token) append(part[0]) }
                val rows = initialsQuery(key, 64, exactOnly = false)
                for (row in rows) {
                    val parts = row.syllables.split(' ')
                    if (parts.size < token.size || parts.size > token.size + 2) continue
                    if (!fits(parts, token)) continue
                    val extra = parts.size - token.size
                    val pCount = parts.size.coerceAtLeast(1)
                    var penalty = pCount * (2.6 * shortened + 4.2 * extra)
                    for (index in token.indices) {
                        val syllable = parts[index]
                        if ('v' !in token[index] && syllable.length >= 2 && syllable[1] == 'v') penalty += pCount * 4.5
                    }
                    items += scored(
                        row,
                        wordScore(row.text, row.freq) - penalty,
                        token.joinToString("'"),
                        false,
                        letters.length,
                        opened = extra > 0,
                        untyped = if (extra > 0) 1 else 0,
                    )
                }
            }
            return items
        }

        // Multi-word DP combining full syllables and abbreviated initials (e.g. "jintiantq" -> "今天天气", "wszgr" -> "我是中国人")
        fun mixedAbbreviations(letters: String): List<Scored> {
            data class AbbrevPiece(
                val text: String,
                val syllables: String,
                val freq: Int,
                val linearScore: Double,
                val hasMulti: Boolean,
                val pieces: Int,
                val extra: Int,
            )

            val items = ArrayList<Scored>()
            val paths = tokenPaths(letters)
            for (token in paths) {
                if (token.size !in 2..20) continue
                val shortenedCount = token.count { it.length < 2 || !trie.isWord(it) }
                if (shortenedCount == 0) continue
                val lastTok = token.last()
                if (shortenedCount == 1 && (lastTok.length < 2 || !trie.isWord(lastTok)) && trie.hasExtension(lastTok)) {
                    continue
                }
                // Abbreviated tokens must be consonant initials/prefixes, never vowel splits like "mao'bi'a"
                if (token.any { (it.length < 2 || !trie.isWord(it)) && it[0] in "aeiouv" }) continue
                if (shattersIncompleteTail(token, trie)) continue

                val dp = Array(token.size + 1) { ArrayList<AbbrevPiece>() }
                dp[0].add(AbbrevPiece("", "", 0, 0.0, false, pieces = 0, extra = 0))
                val maxSpan = minOf(5, token.size)

                for (end in 1..token.size) {
                    val found = ArrayList<AbbrevPiece>()
                    for (start in maxOf(0, end - maxSpan) until end) {
                        if (token.size >= 2 && start == 0 && end == token.size) continue
                        if (dp[start].isEmpty()) continue
                        val span = token.subList(start, end)
                        val spanShortened = span.count { it.length < 2 || !trie.isWord(it) }
                        val allowSingleShort = span.size == 1 && (
                            (start == 0 && token.size >= 3) ||
                                (end == token.size && start >= 2)
                            )
                        val candidatesForSpan: List<Triple<LexRow, Double, Int>> = when {
                            spanShortened == 0 -> {
                                val sLen = span.size
                                val cohesion = 1.4 * (sLen - 1).coerceAtLeast(0)
                                spell(span, boostUser = (start == 0 && end == token.size)).take(3).map { row ->
                                    val singlePen = when {
                                        sLen != 1 || token.size <= 1 -> 0.0
                                        start > 0 && row.text in grammaticalParticles -> 1.4
                                        else -> 5.2
                                    }
                                    Triple(row, sLen * ln(row.freq.coerceAtLeast(1).toDouble()) + cohesion - singlePen, 0)
                                }
                            }
                            span.size == 1 && allowSingleShort -> {
                                val prefixHasShort = start > 0 && token.subList(0, start).any { it.length < 2 || !trie.isWord(it) }
                                charsOf(span[0]).take(6).map { row ->
                                    val umlautPen = if ('v' !in span[0] && row.syllables.length >= 2 && row.syllables[1] == 'v') 5.0 else 0.0
                                    val clauseEndAdj = if (end == token.size && token.size >= 3 && row.text == "把") -1.8 else 0.0
                                    val particleBonus = if (end == token.size && start >= 2 && row.text in clauseFinalParticles) 2.0 else 0.0
                                    val chainedShortPen = if (prefixHasShort && row.text !in clauseFinalParticles) 4.5 else 0.0
                                    Triple(
                                        row,
                                        ln(row.freq.coerceAtLeast(1).toDouble()) - 5.2 - umlautPen - chainedShortPen + clauseEndAdj + particleBonus,
                                        0,
                                    )
                                }
                            }
                            span.size >= 2 -> {
                                abbrevSpanLinear(span, allowOneExtra = (end == token.size)).take(24)
                            }
                            else -> emptyList()
                        }
                        if (candidatesForSpan.isEmpty()) continue
                        val isMultiSpan = span.size >= 2
                        for (prev in dp[start]) {
                            val stepPen = if (prev.pieces == 0) 0.0 else 5.5
                            val prevIsDay = prev.text in dayPrefixes
                            for ((row, spanScore, spanExtra) in candidatesForSpan) {
                                val collocationBonus = if (prevIsDay && row.text in dayCollocations) 4.2 else 0.0
                                found += AbbrevPiece(
                                    prev.text + row.text,
                                    joinSyl(prev.syllables, row.syllables),
                                    if (prev.text.isEmpty()) row.freq else minOf(prev.freq, row.freq),
                                    prev.linearScore + spanScore + collocationBonus - stepPen,
                                    prev.hasMulti || isMultiSpan,
                                    prev.pieces + 1,
                                    prev.extra + spanExtra,
                                )
                            }
                        }
                    }
                    val dedup = LinkedHashMap<String, AbbrevPiece>()
                    for (cand in found) {
                        if (cand.text.isEmpty()) continue
                        val existing = dedup[cand.text]
                        if (existing == null || cand.linearScore > existing.linearScore) dedup[cand.text] = cand
                    }
                    dp[end] = ArrayList(dedup.values.sortedByDescending { it.linearScore }.take(5))
                }

                val preedit = token.joinToString("'")
                for (piece in dp[token.size]) {
                    if (piece.text.isNotEmpty() && piece.hasMulti) {
                        val totalK = (token.size + piece.extra).coerceAtLeast(1)
                        val multiPieceAdj = if (piece.pieces > 1) 2.6 + 0.75 * (piece.pieces - 2).coerceAtLeast(0) else 0.0
                        val avgPerSyl = piece.linearScore / totalK.toDouble() - multiPieceAdj
                        val mappedScore = totalK.toDouble() * totalK * avgPerSyl +
                            FRAGMENT_PENALTY * (piece.pieces - 1).coerceAtLeast(0)
                        items += Scored(
                            piece.text,
                            piece.syllables,
                            piece.freq,
                            mappedScore,
                            preedit,
                            false,
                            letters.length,
                            pieces = piece.pieces.coerceAtLeast(1),
                            opened = piece.extra > 0,
                            untyped = if (piece.extra > 0) 1 else 0,
                        )
                    }
                }
            }
            return items
        }

        private fun abbrevSpanLinear(span: List<String>, allowOneExtra: Boolean): List<Triple<LexRow, Double, Int>> {
            val key = buildString { for (part in span) append(normalizePrefix(part)[0]) }
            val shortened = span.count { it.length < 2 || !trie.isWord(it) }
            val maxParts = if (allowOneExtra) span.size + 1 else span.size
            val out = ArrayList<Triple<LexRow, Double, Int>>()
            val exactLimit = if (key.length == 2) 160 else 80
            val candidateRows = if (allowOneExtra) {
                (initialsQuery(key, exactLimit, exactOnly = true) + initialsQuery(key, 64, exactOnly = false))
                    .distinctBy { it.text to it.syllables }
            } else {
                initialsQuery(key, exactLimit, exactOnly = true)
            }
            for (row in candidateRows) {
                val parts = row.syllables.split(' ')
                if (parts.size < span.size || parts.size > maxParts) continue
                if (!fits(parts, span)) continue
                val extra = parts.size - span.size
                var linearPen = 4.2 * shortened + 4.5 * extra
                for (index in span.indices) {
                    val syllable = parts[index]
                    if ('v' !in span[index] && syllable.length >= 2 && syllable[1] == 'v') linearPen += 4.5
                }
                val pCount = parts.size
                val cohesion = 0.8 * (pCount - 1).coerceAtLeast(0)
                out += Triple(row, pCount * ln(row.freq.coerceAtLeast(1).toDouble()) + cohesion - linearPen, extra)
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

        private fun syllableCount(item: Scored): Int {
            return if (item.syllables.isBlank()) {
                item.text.length.coerceAtLeast(1)
            } else {
                item.syllables.count { it == ' ' } + 1
            }
        }

        // Normalize lexical scores per syllable so over-splitting or prefix-guessing into more characters
        // never inflates the quadratic length factor, while preserving phrase-over-fragment preference
        // within the same syllable count and penalizing unrequested syllable splits or untyped extensions.
        fun effective(item: Scored): Double {
            val k = syllableCount(item).coerceAtLeast(1)
            val perSyl = (item.score - FRAGMENT_PENALTY * (item.pieces - 1).coerceAtLeast(0)) / (k.toDouble() * k)
            val minSyl = if (item.consumed == fullLength) minFullSyllables else Int.MAX_VALUE
            val extraSplits = if (minSyl < Int.MAX_VALUE && k > minSyl) k - minSyl else 0
            val splitUnit = if (minSyl == 1) SINGLE_SYLLABLE_SPLIT_PENALTY else SPLIT_PENALTY
            val openPenalty = if (item.opened) {
                OPENED_PENALTY + 0.08 * (item.untyped - 1).coerceAtLeast(0)
            } else {
                0.0
            }
            val shatterPenalty = if (item.shatteredTail) SPLIT_PENALTY + OPENED_PENALTY else 0.0
            return item.consumed * CONSUMED_WEIGHT + perSyl - splitUnit * extraSplits - openPenalty - shatterPenalty
        }

        fun fuzzy(seg: Seg): List<Scored> {
            if (seg.parts.isEmpty() || seg.parts.size > 6) return emptyList()
            val used = typedLength(seg.parts)
            val maxSubSyls = minOf(3, seg.parts.size)
            data class FuzzyPath(val parts: List<String>, val subSyls: Int, val featureEdits: Int)
            var beam = listOf(FuzzyPath(emptyList(), 0, 0))
            for (part in seg.parts) {
                val options = LinkedHashMap<String, Int>()
                for ((variant, dist) in expandSyllableWithCost(part)) {
                    val canonical = trie.canonicalOf(variant) ?: continue
                    val prev = options[canonical]
                    if (prev == null || dist < prev) options[canonical] = dist
                    if (options.size >= 5) break
                }
                if (options.isEmpty()) return emptyList()
                val nextBeam = ArrayList<FuzzyPath>(beam.size * options.size)
                for (prefix in beam) {
                    for ((alt, dist) in options) {
                        val nextSub = prefix.subSyls + if (dist > 0) 1 else 0
                        val nextEdits = prefix.featureEdits + dist
                        if (nextSub > maxSubSyls || nextEdits > 4) continue
                        nextBeam += FuzzyPath(prefix.parts + alt, nextSub, nextEdits)
                    }
                }
                nextBeam.sortWith(compareBy<FuzzyPath> { it.featureEdits }.thenBy { it.subSyls })
                beam = nextBeam.take(18)
            }

            val items = ArrayList<Scored>()
            val count = seg.parts.size
            for (path in beam) {
                if (path.featureEdits == 0) continue
                val parts = path.parts
                val preedit = parts.joinToString("'")
                val rawPenalty = count.toDouble() * count * (4.62 + 0.15 * (path.featureEdits - 1))
                val matched = spell(parts, boostUser = true).take(3)
                for (row in matched) {
                    items += scored(row, wordScore(row.text, row.freq) - rawPenalty, preedit, false, used)
                }
                if (parts.size >= 2 && (matched.maxOfOrNull { it.freq } ?: 0) < 5_000) {
                    for (piece in cover(parts).take(1)) {
                        if (piece.text.isNotEmpty()) {
                            items += Scored(piece.text, piece.syllables, piece.freq, piece.score - rawPenalty, preedit, false, used, piece.pieces)
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
                val k = syllableCount(item).coerceAtLeast(1)
                val kScale = (k.toDouble() * k) / maxOf(1.0, k * 0.85)
                var bonus = 0.0
                for (len in minOf(4, cjkContext.length) downTo 1) {
                    val count = userBigrams[cjkContext.takeLast(len)]?.get(item.text) ?: 0
                    if (count > 0) {
                        val uBonus = ((if (len >= 2) 24.0 else 16.0) + 6.0 * ln((count + 1).toDouble())) * k * k
                        if (uBonus > bonus) bonus = uBonus
                    }
                }
                if (item.text.length in 1..4) {
                    for (len in suffixLengths) {
                        val freq = compoundFreqs[cjkContext.takeLast(len) + item.text] ?: 0
                        if (freq >= 100) {
                            val cBonus = if (len >= 2) {
                                2.8 * ln(freq.toDouble()) * kScale
                            } else if (freq >= 500) {
                                minOf(14.0, 1.25 * ln(freq.toDouble())) * kScale
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

        fun typos(
            letters: String,
            touchHints: List<String> = emptyList(),
            touchSamples: List<TouchSample?> = emptyList(),
        ): List<Scored> {
            val items = ArrayList<Scored>()
            val corrected = correctedSegments(letters, touchHints, touchSamples)
            for ((seg, cost, spatialPenalty) in corrected) {
                val k = seg.parts.size.coerceAtLeast(1)
                val perSylPen = if (cost == 1) {
                    if (spatialPenalty < 0.99f) {
                        2.15 - 1.05 * (1.0 - spatialPenalty.coerceIn(0f, 1f))
                    } else {
                        3.95
                    }
                } else {
                    3.20 * cost
                }
                val scaleK2 = if (k == 1) 4.0 else k.toDouble() * k
                val rawPenalty = scaleK2 * perSylPen
                val directRows = spell(seg.parts, boostUser = true).take(2)
                for (row in directRows) {
                    items += scored(row, wordScore(row.text, row.freq) - rawPenalty, letters, true, letters.length)
                }
                if (seg.parts.size >= 2 && (directRows.maxOfOrNull { it.freq } ?: 0) < 5_000) {
                    for (piece in cover(seg.parts).take(1)) {
                        if (piece.text.isNotEmpty()) {
                            items += Scored(piece.text, piece.syllables, piece.freq, piece.score - rawPenalty, letters, true, letters.length, piece.pieces)
                        }
                    }
                }
            }
            // Cross-syllable adjacent transposition (e.g. "nhiao" -> "nihao", "wmoen" -> "women", "zohngguo" -> "zhongguo")
            if (letters.length in 3..18) {
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
                    val k = seg.parts.size.coerceAtLeast(1)
                    val rawSwapPen = k.toDouble() * k * 2.25
                    val direct = spell(seg.parts, boostUser = true).take(2)
                    for (row in direct) {
                        items += scored(row, wordScore(row.text, row.freq) - rawSwapPen, letters, true, letters.length)
                    }
                    if ((direct.maxOfOrNull { it.freq } ?: 0) < 5_000) {
                        for (piece in cover(seg.parts).take(1)) {
                            if (piece.text.isNotEmpty()) {
                                items += Scored(piece.text, piece.syllables, piece.freq, piece.score - rawSwapPen, letters, true, letters.length, piece.pieces)
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
                    for ((seg, cost, headSpatial) in correctedSegments(
                        headText,
                        touchHints.take(headText.length),
                        touchSamples.take(headText.length),
                    ).filter { it.second == 1 }.take(2)) {
                        val hSyls = seg.parts.size.coerceAtLeast(1)
                        val headPieces = spell(seg.parts, boostUser = true).take(1).map {
                            Piece(it.text, it.syllables, it.freq, wordScore(it.text, it.freq))
                        }.ifEmpty { cover(seg.parts).take(1) }
                        for (hp in headPieces) {
                            for (tc in tailChars.take(2)) {
                                val totalK = hSyls + 1
                                val baseCombined = combinePiecesScore(
                                    hp.score,
                                    hSyls,
                                    hp.pieces,
                                    wordScore(tc.text, tc.freq),
                                    1,
                                    1,
                                    stepPen = 8.5,
                                )
                                val editUnit = if (headSpatial < 0.99f) 3.2 else 6.2
                                val rawPen = totalK.toDouble() * totalK * (editUnit * cost + 1.2)
                                items += Scored(
                                    hp.text + tc.text,
                                    joinSyl(hp.syllables, tc.syllables),
                                    minOf(hp.freq.coerceAtLeast(1), tc.freq),
                                    baseCombined - rawPen,
                                    letters,
                                    true,
                                    letters.length,
                                    hp.pieces + 1,
                                )
                            }
                        }
                    }
                    if (items.isNotEmpty()) break
                }
            }
            return rank(items)
        }

        fun softTouchVariants(letters: String, samples: List<TouchSample?>): List<Scored> {
            if (letters.length < 2) return emptyList()
            data class PosAlt(val pos: Int, val ch: Char, val penalty: Float)
            val collected = ArrayList<PosAlt>()
            val limit = minOf(letters.length, samples.size)
            for (pos in 0 until limit) {
                val sample = samples[pos] ?: continue
                val ch = letters[pos]
                val ambiguous = (sample.primary == ch) || ((sample.penaltyOf(ch) ?: 1f) >= 0.55f)
                if (!ambiguous) continue
                sample.alternatives
                    .filter { it.penalty <= 0.35f && it.ch in 'a'..'z' && it.ch != ch }
                    .sortedBy { it.penalty }
                    .take(2)
                    .forEach { alt -> collected += PosAlt(pos, alt.ch, alt.penalty) }
            }
            if (collected.isEmpty()) return emptyList()
            collected.sortBy { it.penalty }

            val variants = ArrayList<Pair<String, Double>>(8)
            for (pa in collected.take(6)) {
                val chars = letters.toCharArray()
                chars[pa.pos] = pa.ch
                variants += String(chars) to (0.80 + 4.50 * pa.penalty)
            }
            val distinctPos = collected.distinctBy { it.pos }
            if (distinctPos.size >= 2 && distinctPos[0].penalty <= 0.30f && distinctPos[1].penalty <= 0.30f) {
                val chars = letters.toCharArray()
                chars[distinctPos[0].pos] = distinctPos[0].ch
                chars[distinctPos[1].pos] = distinctPos[1].ch
                variants += String(chars) to (1.40 + 4.00 * (distinctPos[0].penalty + distinctPos[1].penalty))
            }

            val items = ArrayList<Scored>()
            for ((variant, perSylPen) in variants) {
                val vSegs = segmentPinyin(variant, trie)
                val vFull = vSegs.filter { !it.partial }
                val vOpen = vSegs.filter { seg ->
                    seg.partial && seg.parts.isNotEmpty() &&
                        (!trie.isWord(seg.parts.last()) || (seg.parts.last().length == 1 && trie.hasExtension(seg.parts.last())))
                }
                val vMinSyl = vFull
                    .filter { !shattersIncompleteTail(it.parts, trie) }
                    .minOfOrNull { it.parts.size }
                    ?: if (vOpen.isEmpty()) variant.length.coerceAtLeast(1) else Int.MAX_VALUE
                val vBook = Book(minFullSyllables = vMinSyl, fullLength = variant.length, keepWritten = false)
                for (seg in (vFull.take(2) + vOpen.take(1)).take(2)) {
                    for (cand in vBook.candidates(seg).take(4)) {
                        val k = syllableCount(cand).coerceAtLeast(1)
                        val extraSplits = if (vMinSyl < Int.MAX_VALUE && k > vMinSyl) k - vMinSyl else 0
                        val rawPen = k.toDouble() * k * (perSylPen + SPLIT_PENALTY * extraSplits)
                        items += cand.copy(score = cand.score - rawPen, shatteredTail = false)
                    }
                    for (pref in vBook.prefixes(seg).take(2)) {
                        val k = syllableCount(pref).coerceAtLeast(1)
                        items += pref.copy(score = pref.score - k.toDouble() * k * perSylPen)
                    }
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
                if (previous == null || effective(item) > effective(previous)) best[item.text] = item
            }
            return best.values.sortedByDescending { effective(it) }
        }

        private fun opened(seg: Seg): List<Scored> {
            val preedit = seg.parts.joinToString("'")
            val used = typedLength(seg.parts)
            val items = ArrayList<Scored>()
            val head = seg.parts.dropLast(1)
            val tail = normalizePrefix(seg.parts.last())
            val bareInitialTail = tail.length == 1 || tail in shengmu
            val canonicalHead = head.map { if (it == "r") "er" else (trie.canonicalOf(it) ?: it) }
            val normalizedParts = canonicalHead + tail
            val prefix = normalizedParts.joinToString(" ")
            val longer = lexicon.spellingStarting(prefix, normalizedParts.size, 16) + user.filter { extends(it, normalizedParts) }
            for (row in longer) {
                if (!extends(row, normalizedParts)) continue
                val untyped = if (bareInitialTail) 1 else (row.syllables.substringAfterLast(' ').length - tail.length).coerceAtLeast(1)
                items += scored(row, wordScore(row.text, row.freq), preedit, false, used, opened = true, untyped = untyped)
            }
            if (canonicalHead.isNotEmpty()) {
                for ((altHead, penPerSyl) in phoneticVariants(canonicalHead, allowSingle = true)) {
                    val altParts = altHead + tail
                    val altPrefix = altParts.joinToString(" ")
                    val count = altParts.size
                    val pen = count * count * penPerSyl
                    for (row in lexicon.spellingStarting(altPrefix, altParts.size, 10)) {
                        if (!extends(row, altParts)) continue
                        val untyped = if (bareInitialTail) 1 else (row.syllables.substringAfterLast(' ').length - tail.length).coerceAtLeast(1)
                        items += scored(row, wordScore(row.text, row.freq) - pen, preedit, false, used, opened = true, untyped = untyped)
                    }
                }
            }
            // Trailing erhua support (e.g. "wanr" -> "玩儿", "niqunar" -> "你去哪儿", "yidianr" -> "一点儿")
            if (tail == "r" && canonicalHead.isNotEmpty()) {
                for (takeCount in 1..minOf(2, canonicalHead.size)) {
                    val erhuaParts = canonicalHead.takeLast(takeCount) + "er"
                    val erhuaRows = spell(erhuaParts, boostUser = true).take(2)
                    if (erhuaRows.isEmpty()) continue
                    val headLen = canonicalHead.size - takeCount
                    val erSyls = erhuaParts.size
                    val prefixHeads = headPieces(canonicalHead.dropLast(takeCount))
                    for (hp in prefixHeads) {
                        for (erRow in erhuaRows) {
                            val combined = hp.text + erRow.text
                            if (combined.isEmpty()) continue
                            val totalSyl = headLen + erSyls
                            val erhuaBonus = if (erRow.text.endsWith("儿")) 1.5 * totalSyl * totalSyl else 0.0
                            val combinedScore = combinePiecesScore(
                                hp.score,
                                headLen,
                                hp.pieces,
                                wordScore(erRow.text, erRow.freq),
                                erSyls,
                                1,
                            ) + erhuaBonus
                            items += Scored(
                                combined,
                                joinSyl(hp.syllables, erRow.syllables),
                                if (hp.text.isEmpty()) erRow.freq else minOf(hp.freq, erRow.freq),
                                combinedScore,
                                preedit,
                                false,
                                used,
                                hp.pieces + 1,
                            )
                        }
                    }
                }
            }
            val heads = headPieces(canonicalHead)
            val headSyls = canonicalHead.size
            for (piece in heads) {
                for (row in charsOf(tail)) {
                    val text = piece.text + row.text
                    if (text.isEmpty()) continue
                    val untyped = if (bareInitialTail) 1 else (row.syllables.length - tail.length).coerceAtLeast(1)
                    val clauseEndAdj = when {
                        headSyls >= 2 && row.text == "把" -> -1.8
                        headSyls >= 2 && tail.length == 1 && row.text in clauseFinalParticles -> 2.2
                        else -> 0.0
                    }
                    val combinedScore = combinePiecesScore(
                        piece.score,
                        headSyls,
                        piece.pieces,
                        wordScore(row.text, row.freq) + clauseEndAdj,
                        1,
                        1,
                        stepPen = 14.5,
                    )
                    items += Scored(
                        text,
                        joinSyl(piece.syllables, row.syllables),
                        if (piece.text.isEmpty()) row.freq else minOf(piece.freq, row.freq),
                        combinedScore,
                        preedit,
                        false,
                        used,
                        piece.pieces + 1,
                        opened = true,
                        untyped = untyped,
                    )
                }
            }
            return items
        }

        private fun headPieces(head: List<String>): List<Piece> {
            if (head.isEmpty()) return listOf(Piece("", "", 0, 0.0, pieces = 0))
            val words = spell(head, boostUser = true).take(3).map { Piece(it.text, it.syllables, it.freq, wordScore(it.text, it.freq), pieces = 1) }
            val altWords = if (head.size >= 1) {
                phoneticVariants(head, allowSingle = head.size >= 1).flatMap { (alt, penPerSyl) ->
                    val c = alt.size
                    spell(alt, boostUser = true).take(2).map {
                        Piece(it.text, it.syllables, it.freq, wordScore(it.text, it.freq) - c * c * penPerSyl, pieces = 1)
                    }
                }
            } else {
                emptyList()
            }
            val built = if (head.size >= 2) cover(head) else emptyList()
            val merged = rankPieces(words + altWords + built).take(3)
            return merged.ifEmpty { listOf(Piece("", "", 0, 0.0, pieces = 0)) }
        }

        private fun cover(parts: List<String>): List<Piece> {
            if (parts.isEmpty()) return emptyList()
            val dp = Array(parts.size + 1) { ArrayList<Piece>() }
            dp[0].add(Piece("", "", 0, 0.0, pieces = 0))
            val width = minOf(6, parts.size)
            val multiSylSentence = parts.size >= 2
            for (end in 1..parts.size) {
                val found = ArrayList<Piece>()
                for (start in maxOf(0, end - width) until end) {
                    if (parts.size >= 2 && start == 0 && end == parts.size) continue
                    if (dp[start].isEmpty()) continue
                    val spanLen = end - start
                    val subParts = parts.subList(start, end)
                    val spanOptions = ArrayList<Pair<LexRow, Double>>(6)
                    for (row in spell(subParts, boostUser = (spanLen >= 2)).take(if (spanLen == 1) 4 else 3)) {
                        spanOptions += row to 0.0
                    }
                    if (spanLen >= 2) {
                        for ((altSub, penPerSyl) in phoneticVariants(subParts)) {
                            for (row in spell(altSub, boostUser = true).take(2)) {
                                spanOptions += row to (spanLen * penPerSyl)
                            }
                        }
                    } else if (multiSylSentence && (subParts[0] == "nu" || subParts[0] == "zhei" || subParts[0] == "nei")) {
                        for ((altSub, penPerSyl) in phoneticVariants(subParts, allowSingle = true)) {
                            for (row in spell(altSub, boostUser = false).take(1)) {
                                spanOptions += row to (penPerSyl + 1.1)
                            }
                        }
                    }
                    for (prev in dp[start]) {
                        var singleAddedForPrev = 0
                        for ((row, variantPen) in spanOptions) {
                            if (spanLen == 1 && parts.size > 1) {
                                if (singleAddedForPrev >= 2) continue
                                singleAddedForPrev++
                            }
                            val stepPenalty = if (prev.pieces == 0) 0.0 else 5.5
                            val beforeParticle = start > 0 &&
                                end < parts.size &&
                                row.text in resultativeComplements &&
                                parts[end] in particleSyllables
                            val singleCharPen = when {
                                spanLen != 1 || parts.size <= 1 -> 0.0
                                start > 0 && row.text in grammaticalParticles -> 1.4
                                beforeParticle -> 2.0
                                else -> 5.2
                            }
                            val cohesion = 1.8 * (spanLen - 1) + if (spanLen >= 4) 7.5 * (spanLen - 2) else 0.0
                            val timeBonus = if (spanLen == 2 && isTemporalPrefix(prev.text) && isClockHourWord(row.text)) 5.5 else 0.0
                            val clauseEndAdj = if (end == parts.size && parts.size >= 3 && spanLen == 1 && row.text == "把") -1.5 else 0.0
                            val linearStep = spanLen * ln(row.freq.coerceAtLeast(1).toDouble()) + cohesion + timeBonus + clauseEndAdj - singleCharPen - variantPen - stepPenalty
                            found += Piece(
                                prev.text + row.text,
                                joinSyl(prev.syllables, row.syllables),
                                if (prev.text.isEmpty()) row.freq else minOf(prev.freq, row.freq),
                                prev.score + linearStep,
                                prev.pieces + 1,
                            )
                        }
                    }
                }
                // Bridge dead-end syllables (e.g., "n", "m", colloquial "zhei", or rare syllables missing nsy=1) so multi-word DP never collapses
                if (found.isEmpty() && dp[end - 1].isNotEmpty()) {
                    val bridgeRows = fallbackCharsForSyllable(parts[end - 1])
                    for (prev in dp[end - 1]) {
                        for (row in bridgeRows) {
                            val stepPenalty = if (prev.pieces == 0) 0.0 else 5.5
                            val linearStep = ln(row.freq.coerceAtLeast(1).toDouble()) - 6.0 - stepPenalty
                            found += Piece(
                                prev.text + row.text,
                                joinSyl(prev.syllables, row.syllables),
                                if (prev.text.isEmpty()) row.freq else minOf(prev.freq, row.freq),
                                prev.score + linearStep,
                                prev.pieces + 1,
                            )
                        }
                    }
                }
                val bestLinear = LinkedHashMap<String, Piece>()
                for (cand in found) {
                    if (cand.text.isEmpty()) continue
                    val existing = bestLinear[cand.text]
                    if (existing == null || cand.score > existing.score) bestLinear[cand.text] = cand
                }
                dp[end] = ArrayList(bestLinear.values.sortedByDescending { it.score }.take(6))
            }
            val totalSyl = parts.size.coerceAtLeast(1)
            return dp[parts.size].take(4).map { piece ->
                val mappedScore = if (totalSyl == 1) {
                    wordScore(piece.text, piece.freq)
                } else {
                    val avgPerSyl = piece.score / totalSyl.toDouble() - 2.8
                    totalSyl.toDouble() * totalSyl * avgPerSyl + FRAGMENT_PENALTY * (piece.pieces - 1).coerceAtLeast(0)
                }
                piece.copy(score = mappedScore)
            }
        }

        private fun phoneticVariants(parts: List<String>, allowSingle: Boolean = false): List<Pair<List<String>, Double>> {
            if (parts.isEmpty() || (!allowSingle && parts.size < 2)) return emptyList()
            val canonical = parts.map { if (it == "r") "er" else (trie.canonicalOf(it) ?: it) }
            val positions = ArrayList<Triple<Int, String, Double>>(2)
            for ((idx, syl) in canonical.withIndex()) {
                when (syl) {
                    "lu" -> positions += Triple(idx, "lv", 0.50)
                    "nu" -> positions += Triple(idx, "nv", 0.50)
                    "zhei" -> positions += Triple(idx, "zhe", 0.40)
                    "nei" -> positions += Triple(idx, "na", 0.40)
                }
                if (positions.size >= 2) break
            }
            if (positions.isEmpty()) return emptyList()
            val out = ArrayList<Pair<List<String>, Double>>(3)
            val totalMasks = 1 shl positions.size
            for (mask in 1 until totalMasks) {
                val copy = canonical.toMutableList()
                var totalPen = 0.0
                var subs = 0
                for (bit in positions.indices) {
                    if ((mask and (1 shl bit)) != 0) {
                        val (idx, replacement, pen) = positions[bit]
                        copy[idx] = replacement
                        totalPen += pen
                        subs++
                    }
                }
                out += copy to (totalPen / subs.coerceAtLeast(1))
            }
            return out
        }

        private fun isTemporalPrefix(text: String): Boolean {
            if (text.length < 2) return false
            return temporalSuffixes.any { text.endsWith(it) }
        }

        private fun isClockHourWord(text: String): Boolean {
            return text.length == 2 && text[1] == '点' && text[0] in "一二两三四五六七八九十几"
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

        private fun scored(
            row: LexRow,
            score: Double,
            preedit: String,
            typo: Boolean,
            consumed: Int,
            pieces: Int = 1,
            opened: Boolean = false,
            untyped: Int = 0,
            shatteredTail: Boolean = false,
        ) = Scored(row.text, row.syllables, row.freq, score, preedit, typo, consumed, pieces, opened, untyped, shatteredTail)

        private fun typedLength(parts: List<String>) = parts.sumOf { it.length }

        private fun pieceEffective(item: Piece): Double =
            item.score - FRAGMENT_PENALTY * (item.pieces - 1).coerceAtLeast(0)

        private fun rankPieces(items: List<Piece>): List<Piece> {
            val best = LinkedHashMap<String, Piece>()
            for (item in items) {
                if (item.text.isEmpty()) continue
                val previous = best[item.text]
                if (previous == null || pieceEffective(item) > pieceEffective(previous)) best[item.text] = item
            }
            return best.values.sortedByDescending { pieceEffective(it) }
        }

        private fun joinSyl(left: String, right: String): String = when {
            left.isEmpty() -> right
            right.isEmpty() -> left
            else -> "$left $right"
        }

        private fun combinePiecesScore(
            leftScore: Double,
            leftSyls: Int,
            leftPieces: Int,
            rightScore: Double,
            rightSyls: Int,
            rightPieces: Int,
            stepPen: Double = 2.8,
        ): Double {
            if (leftSyls <= 0) return rightScore
            val rSyl = rightSyls.coerceAtLeast(1)
            val leftPerSyl = (leftScore - FRAGMENT_PENALTY * (leftPieces - 1).coerceAtLeast(0)) / (leftSyls.toDouble() * leftSyls)
            val rightPerSyl = (rightScore - FRAGMENT_PENALTY * (rightPieces - 1).coerceAtLeast(0)) / (rSyl.toDouble() * rSyl)
            val total = leftSyls + rSyl
            val totalPieces = leftPieces + rightPieces
            val avg = (leftSyls * leftPerSyl + rSyl * rightPerSyl - stepPen) / total.toDouble()
            return total.toDouble() * total * avg + FRAGMENT_PENALTY * (totalPieces - 1).coerceAtLeast(0)
        }
    }

    private fun wordScore(text: String, freq: Int): Double {
        val count = text.length.coerceAtLeast(1)
        return count * count * ln(freq.coerceAtLeast(1).toDouble())
    }

    private companion object {
        const val FULL_WORD_BONUS = 28.0
        const val FRAGMENT_PENALTY = 10.0
        const val CONSUMED_WEIGHT = 25.0
        const val SPLIT_PENALTY = 5.5
        const val SINGLE_SYLLABLE_SPLIT_PENALTY = 8.5
        const val OPENED_PENALTY = 4.50
        val shengmu = setOf("zh", "ch", "sh")
        val grammaticalParticles = setOf("的", "了", "吗", "呢", "吧", "啊", "呀", "啦", "着", "过")
        val clauseFinalParticles = setOf("吧", "了", "吗", "呢", "啊", "呀", "啦")
        val resultativeComplements = setOf("到", "好", "完", "走", "来", "去", "给", "上", "下", "成", "住", "出")
        val particleSyllables = setOf("le", "de", "ma", "ba", "ne", "la", "guo", "zhe")
        val dayPrefixes = setOf("今天", "明天", "昨天", "后天", "每天", "今晚", "明早")
        val dayCollocations = setOf("早上", "上午", "下午", "晚上", "中午", "凌晨", "天气")
        val temporalSuffixes = listOf("早上", "上午", "下午", "晚上", "中午", "凌晨", "明早", "今晚")
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

    private fun correctedSegments(
        text: String,
        touchHints: List<String> = emptyList(),
        touchSamples: List<TouchSample?> = emptyList(),
    ): List<Triple<Seg, Int, Float>> {
        val paths = Array(text.length + 1) { ArrayList<Node>(4) }
        paths[0].add(Node(emptyList(), 0, 0.0, 1f, -1))
        for (start in 0 until text.length) {
            val from = paths[start]
            if (from.isEmpty()) continue
            for (edge in syllableEdges(text, start, touchHints, touchSamples)) {
                val dest = paths[edge.end]
                val consumed = edge.end - start
                for (node in from) {
                    val cost = node.cost + edge.cost
                    if (cost > 2) continue
                    val nextPenalty = if (edge.cost > 0) {
                        minOf(node.spatialPenalty, edge.spatialPenalty)
                    } else {
                        node.spatialPenalty
                    }
                    val spatialBonus = if (edge.cost > 0) 6.0 * (1.0 - edge.spatialPenalty) else 0.0
                    val nextEditPos = if (edge.cost > 0 && node.editPos < 0) start else node.editPos
                    dest += Node(
                        node.parts + edge.syllable,
                        cost,
                        node.score + consumed * consumed - edge.cost * 6.0 + spatialBonus,
                        nextPenalty,
                        nextEditPos,
                    )
                }
                prune(dest)
            }
        }
        val sortedFinal = paths[text.length]
            .filter { it.cost > 0 && it.parts.isNotEmpty() }
            .sortedWith(
                compareBy<Node> { it.cost }
                    .thenBy { it.spatialPenalty }
                    .thenByDescending { it.score },
            )
        val finalPerPos = HashMap<Int, Int>()
        val diversified = ArrayList<Triple<Seg, Int, Float>>(36)
        val seenFinal = HashSet<Pair<List<String>, Int>>()
        for (node in sortedFinal) {
            val key = node.parts to node.cost
            if (!seenFinal.add(key)) continue
            val posCount = finalPerPos[node.editPos] ?: 0
            if (posCount >= 5) continue
            finalPerPos[node.editPos] = posCount + 1
            diversified += Triple(Seg(node.parts, partial = false), node.cost, node.spatialPenalty)
            if (diversified.size >= 36) break
        }
        return diversified
    }

    private data class Edge(val syllable: String, val end: Int, val cost: Int, val spatialPenalty: Float = 1f)

    private fun syllableEdges(
        text: String,
        start: Int,
        touchHints: List<String>,
        touchSamples: List<TouchSample?>,
    ): List<Edge> {
        val exact = trie.canonicalEndings(text, start).mapNotNull { (end, canonical) ->
            if (isFullLatticeSyllable(canonical)) Edge(canonical, end, 0) else null
        }
        val typos = ArrayList<Edge>()
        val remain = text.length - start
        for (syllable in syllableList) {
            if (syllable.length < 2 || syllable.length > remain + 1) continue
            val edge = typoEdge(text, start, syllable, touchHints, touchSamples) ?: continue
            typos += edge
        }
        typos.sortWith(
            compareBy<Edge> { it.cost }
                .thenBy { it.spatialPenalty }
                .thenByDescending { it.syllable.length },
        )
        return exact + typos.take(6)
    }

    private fun typoEdge(
        text: String,
        start: Int,
        syllable: String,
        touchHints: List<String>,
        touchSamples: List<TouchSample?>,
    ): Edge? {
        val remain = text.length - start
        var best: Edge? = null
        for (take in intArrayOf(syllable.length, syllable.length - 1, syllable.length + 1)) {
            if (take < 1 || take > remain) continue
            val sub = text.substring(start, start + take)
            if (take == syllable.length - 1 && start + take == text.length && syllable.startsWith(sub)) continue
            val (cost, spatialPenalty) = editCost(sub, syllable, touchHints, touchSamples, start) ?: continue
            if (cost != 1) continue
            val edge = Edge(syllable, start + take, cost, spatialPenalty)
            if (
                best == null ||
                edge.spatialPenalty < best.spatialPenalty ||
                (edge.spatialPenalty == best.spatialPenalty && take > best.end - start)
            ) {
                best = edge
            }
        }
        return best
    }

    private fun prune(nodes: MutableList<Node>) {
        if (nodes.size <= 24) return
        nodes.sortWith(compareBy<Node> { it.cost }.thenBy { it.spatialPenalty }.thenByDescending { it.score })
        val kept = ArrayList<Node>(42)
        var plain = 0
        var edited = 0
        val perEditPos = HashMap<Int, Int>()
        for (node in nodes) {
            if (node.cost == 0 && plain < 6) {
                kept += node
                plain++
            } else if (node.cost > 0 && edited < 36) {
                val posCount = perEditPos[node.editPos] ?: 0
                if (posCount < 5) {
                    kept += node
                    edited++
                    perEditPos[node.editPos] = posCount + 1
                }
            }
        }
        nodes.clear()
        nodes.addAll(kept)
    }

    private data class Node(val parts: List<String>, val cost: Int, val score: Double, val spatialPenalty: Float, val editPos: Int = -1)
}

private fun editCost(
    typed: String,
    target: String,
    touchHints: List<String> = emptyList(),
    touchSamples: List<TouchSample?> = emptyList(),
    offset: Int = 0,
): Pair<Int, Float>? {
    if (typed == target) return 0 to 1f
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
                return 1 to 1f
            } else {
                return null
            }
        }
        if (diff < 0) return 0 to 1f
        val sample = touchSamples.getOrNull(offset + diff)
        val samplePenalty = sample?.penaltyOf(target[diff])
        val legacyHit = sample == null && target[diff] in touchHints.getOrNull(offset + diff).orEmpty()
        val isNeighbor = adjacentKey(typed[diff], target[diff])
        val spatialPenalty = when {
            samplePenalty != null -> samplePenalty.coerceIn(0f, 1f)
            legacyHit -> 0.2f
            isNeighbor -> 0.55f
            else -> 1f
        }
        return if (samplePenalty != null || legacyHit || isNeighbor) {
            1 to spatialPenalty
        } else {
            null
        }
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
    if (!skipped && leftover == 1) return 1 to 1f
    if (skipped && leftover == 0) return 1 to 1f
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
