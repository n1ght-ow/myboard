package dev.cheng.keyboard.engine

import dev.cheng.keyboard.model.Candidate
import dev.cheng.keyboard.model.LexRow
import dev.cheng.keyboard.model.Lexicon
import java.util.ArrayDeque

data class Seg(val parts: List<String>, val partial: Boolean)

class SylTrie(words: Collection<String>) {
    private class Node {
        val next = arrayOfNulls<Node>(26)
        var end = false
    }

    private val root = Node()

    init {
        words.forEach(::insert)
    }

    private fun insert(word: String) {
        var node = root
        for (ch in word) {
            if (ch !in 'a'..'z') return
            val index = ch - 'a'
            val child = node.next[index] ?: Node().also { node.next[index] = it }
            node = child
        }
        if (word.isNotEmpty()) node.end = true
    }

    fun endings(text: String, start: Int): List<Int> {
        val out = ArrayList<Int>(4)
        var node = root
        for (index in start until text.length) {
            val ch = text[index]
            if (ch !in 'a'..'z') break
            node = node.next[ch - 'a'] ?: break
            if (node.end) out += index + 1
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

    fun isWord(text: String): Boolean {
        var node = root
        if (text.isEmpty()) return false
        for (ch in text) {
            if (ch !in 'a'..'z') return false
            node = node.next[ch - 'a'] ?: return false
        }
        return node.end
    }
}

fun segmentPinyin(raw: String, trie: SylTrie): List<Seg> {
    if ('\'' in raw) {
        val forced = forcedSegments(raw.trimEnd('\''), trie)
        if (forced.isNotEmpty()) return forced
    }
    val text = raw.filter { it in 'a'..'z' }
    if (text.isEmpty()) return emptyList()
    data class Path(val parts: List<String>, val score: Int)
    val size = text.length
    val paths = Array(size + 1) { ArrayList<Path>(4) }
    paths[0].add(Path(emptyList(), 0))
    for (start in 0 until size) {
        val from = paths[start]
        if (from.isEmpty()) continue
        for (end in trie.endings(text, start)) {
            val syllable = text.substring(start, end)
            val bonus = syllable.length * syllable.length
            val dest = paths[end]
            for (path in from) dest.add(Path(path.parts + syllable, path.score + bonus))
            if (dest.size > 16) {
                dest.sortByDescending { it.score }
                while (dest.size > 12) dest.removeAt(dest.lastIndex)
            }
        }
    }
    val full = paths[size].map { Seg(it.parts, partial = false) }
    val partials = ArrayList<Seg>()
    for (start in 0..size) {
        if (paths[start].isEmpty()) continue
        if (start == size || !trie.hasPrefix(text, start)) continue
        val rest = text.substring(start)
        if (rest.isEmpty()) continue
        for (path in paths[start]) partials.add(Seg(path.parts + rest, partial = true))
    }
    return (full + partials).distinctBy { it.parts to it.partial }.take(32)
}

private fun forcedSegments(raw: String, trie: SylTrie): List<Seg> {
    val bits = raw.split('\'')
    if (bits.isEmpty() || bits.any { it.isEmpty() }) return emptyList()
    if (bits.dropLast(1).any { !trie.isWord(it) }) return emptyList()
    val last = bits.last()
    if (!trie.isWord(last) && !trie.hasPrefix(last, 0)) return emptyList()
    val out = ArrayList<Seg>(2)
    if (trie.isWord(last)) out += Seg(bits, partial = false)
    if (trie.hasExtension(last)) out += Seg(bits, partial = true)
    if (out.isEmpty()) out += Seg(bits, partial = true)
    return out
}

fun expandSyllable(input: String): List<String> {
    val seen = LinkedHashSet<String>()
    val queue = ArrayDeque<String>()
    queue.add(input)
    while (queue.isNotEmpty() && seen.size < 12) {
        val current = queue.removeFirst()
        if (!seen.add(current)) continue
        swapInitial(current)?.let(queue::add)
        swapFinal(current)?.let(queue::add)
    }
    return seen.toList()
}

private fun swapInitial(syllable: String): String? = when {
    syllable.startsWith("zh") -> "z" + syllable.removePrefix("zh")
    syllable.startsWith("ch") -> "c" + syllable.removePrefix("ch")
    syllable.startsWith("sh") -> "s" + syllable.removePrefix("sh")
    syllable.startsWith("z") -> "zh" + syllable.removePrefix("z")
    syllable.startsWith("c") -> "ch" + syllable.removePrefix("c")
    syllable.startsWith("s") -> "sh" + syllable.removePrefix("s")
    syllable.startsWith("n") -> "l" + syllable.removePrefix("n")
    syllable.startsWith("l") -> "n" + syllable.removePrefix("l")
    else -> null
}

private fun swapFinal(syllable: String): String? {
    val pairs = listOf(
        "iang" to "ian",
        "uang" to "uan",
        "ang" to "an",
        "eng" to "en",
        "ing" to "in",
        "ian" to "iang",
        "uan" to "uang",
        "an" to "ang",
        "en" to "eng",
        "in" to "ing",
    )
    for ((from, to) in pairs) {
        if (syllable.endsWith(from)) return syllable.dropLast(from.length) + to
    }
    return null
}

data class Decode(val preedit: String, val candidates: List<Candidate>)

class PinyinDecoder(syllables: Set<String>) {
    private val trie = SylTrie(syllables)
    private val syllableList = syllables.filter { syllable -> syllable.isNotEmpty() && syllable.all { it in 'a'..'z' } }

    @Volatile
    var lexicon: Lexicon = dev.cheng.keyboard.model.MemoryLexicon(emptyList())

    private val user = mutableListOf<LexRow>()

    fun decode(raw: String, fuzzy: Boolean): Decode {
        val text = raw.lowercase().filter { it in 'a'..'z' || it == '\'' }
        if (text.isEmpty()) return Decode("", emptyList())
        val letters = text.filter { it in 'a'..'z' }
        val segs = segmentPinyin(text, trie)
        val hits = LinkedHashMap<String, Ranked>()
        val ordered = segs.sortedByDescending { segScore(it) }
        for (seg in ordered.take(6)) {
            for ((key, penalty) in letterKeys(seg, fuzzy)) {
                consider(lexicon.exact(key, 24), seg, fuzzy, hits, penalty)
                if (key.length >= 3) consider(lexicon.prefixed(key, 16), seg, fuzzy, hits, penalty)
            }
            val onlyPartial = seg.partial && seg.parts.size == 1
            if (onlyPartial) consider(lexicon.charsStarting(seg.parts[0], 24), seg, fuzzy, hits, 0)
        }
        for (row in user) {
            val matched = segs.firstOrNull { matches(row, it, fuzzy) } ?: continue
            consider(listOf(row), matched, fuzzy, hits, 0)
        }
        if ('\'' !in text && letters.length in 2..48) {
            for ((seg, edits) in correctedSegments(letters)) {
                val penalty = edits + 1
                val key = seg.parts.joinToString("")
                consider(lexicon.exact(key, 8), seg, fuzzy = false, hits, penalty)
                for (row in user) {
                    if (matches(row, seg, false)) consider(listOf(row), seg, false, hits, penalty)
                }
            }
        }
        for (seg in ordered.take(2)) {
            if (seg.parts.size >= 3 || hits.isEmpty()) {
                for (candidate in covers(seg)) {
                    addHit(candidate.text, candidate.syllables, candidate.freq, rank = 3, seg, hits)
                }
            }
        }
        val initialsRank = if (ordered.isNotEmpty() && ordered.all { seg -> seg.parts.all { it.length <= 1 } }) 0 else 4
        val anchor = ordered.firstOrNull() ?: Seg(listOf(letters), partial = true)
        for (row in lexicon.byInitials(letters, 16)) {
            addHit(row.text, row.syllables, row.freq, initialsRank, anchor, hits)
        }
        for (row in user) {
            if (dev.cheng.keyboard.model.initialsOf(row.syllables).startsWith(letters)) {
                addHit(row.text, row.syllables, row.freq, initialsRank, anchor, hits)
            }
        }
        if (hits.isEmpty()) return Decode(if (segs.isEmpty()) text else segs.first().parts.joinToString("'"), emptyList())
        val ranked = hits.values.sortedWith(
            compareBy<Ranked> { it.rank }.thenBy { it.penalty }.thenByDescending { it.row.freq },
        )
        val candidates = ranked.take(30).map { rankedHit ->
            Candidate(rankedHit.row.text, rankedHit.row.syllables, rankedHit.row.freq)
        }
        val preedit = (ranked.firstOrNull()?.seg ?: segs.firstOrNull() ?: anchor).parts.joinToString("'")
        val shown = if (text.endsWith("'") && !preedit.endsWith("'")) "$preedit'" else preedit
        return Decode(shown, candidates)
    }

    fun associate(text: String): List<Candidate> {
        if (text.isBlank()) return emptyList()
        return lexicon.phrasesStarting(text, 20).map { row ->
            Candidate(row.text.removePrefix(text), row.syllables, row.freq)
        }.filter { it.text.isNotEmpty() }
    }

    fun learn(text: String, syllables: String) {
        val spelling = syllables.trim().replace("'", " ")
        val letters = spelling.replace(" ", "")
        if (text.isBlank() || letters.isBlank()) return
        user.removeAll { it.text == text && it.letters == letters }
        user.add(LexRow(text, spelling, letters, 1_200_000_000))
    }

    fun loadUser(rows: List<LexRow>) {
        user.clear()
        user.addAll(rows)
    }

    fun clearUser() {
        user.clear()
    }

    private fun covers(seg: Seg): List<Candidate> {
        val parts = seg.parts
        if (parts.size < 2) return emptyList()
        val fixed = if (seg.partial) parts.dropLast(1) else parts
        if (fixed.isEmpty()) return emptyList()
        val paths = bestCovers(fixed).take(2)
        if (!seg.partial) {
            return paths.map { Candidate(it.text, it.syllables, it.freq) }
        }
        val tails = topChars(parts.last(), partial = true).take(6)
        val out = ArrayList<Candidate>(8)
        for (path in paths) {
            for (tail in tails) {
                out += Candidate(
                    path.text + tail.text,
                    "${path.syllables} ${tail.syllables}".trim(),
                    minOf(path.freq, tail.freq),
                )
                if (out.size >= 8) return out.distinctBy { it.text }
            }
        }
        return out.distinctBy { it.text }
    }

    private data class Cover(val text: String, val syllables: String, val freq: Int)

    private fun bestCovers(parts: List<String>): List<Cover> {
        val dp = Array(parts.size + 1) { mutableListOf<Cover>() }
        dp[0].add(Cover("", "", Int.MAX_VALUE))
        for (end in 1..parts.size) {
            val found = ArrayList<Cover>()
            for (start in maxOf(0, end - 6) until end) {
                for (prev in dp[start]) {
                    val slice = parts.subList(start, end)
                    val choices = if (slice.size == 1) {
                        topChars(slice[0], partial = false).take(3)
                    } else {
                        lookupPhrase(slice).take(2)
                    }
                    for (choice in choices) {
                        found += Cover(
                            prev.text + choice.text,
                            (prev.syllables + " " + choice.syllables).trim(),
                            minOf(prev.freq, choice.freq),
                        )
                    }
                }
            }
            dp[end] = found.sortedByDescending { it.freq }.distinctBy { it.text }.take(3).toMutableList()
            if (dp[end].isEmpty()) {
                val prev = dp[end - 1].firstOrNull() ?: continue
                val fallback = topChars(parts[end - 1], partial = false).firstOrNull() ?: continue
                dp[end].add(
                    Cover(
                        prev.text + fallback.text,
                        (prev.syllables + " " + fallback.syllables).trim(),
                        minOf(prev.freq, fallback.freq),
                    ),
                )
            }
        }
        return dp[parts.size]
    }

    private fun lookupPhrase(syllables: List<String>): List<LexRow> {
        if (syllables.isEmpty()) return emptyList()
        val expect = syllables.joinToString(" ")
        return lexicon.exact(syllables.joinToString(""), 16).filter { it.syllables == expect }
    }

    private fun topChars(syllable: String, partial: Boolean): List<LexRow> {
        val rows = if (partial) {
            lexicon.charsStarting(syllable, 8)
        } else {
            val exact = lexicon.exact(syllable, 12).filter { it.syllables == syllable && it.text.length == 1 }
            exact.ifEmpty { lexicon.charsStarting(syllable, 8).filter { it.syllables == syllable } }
        }
        return rows.filter { it.text.length == 1 }.distinctBy { it.text }
    }

    private fun addHit(
        text: String,
        syllables: String,
        freq: Int,
        rank: Int,
        seg: Seg,
        hits: LinkedHashMap<String, Ranked>,
    ) {
        val row = LexRow(text, syllables, syllables.replace(" ", ""), freq)
        val previous = hits[text]
        if (previous == null || better(previous, rank, 0, freq)) {
            hits[text] = Ranked(row, rank, seg, 0)
        }
    }

    private fun consider(
        rows: List<LexRow>,
        seg: Seg,
        fuzzy: Boolean,
        hits: LinkedHashMap<String, Ranked>,
        basePenalty: Int,
    ) {
        for (row in rows) {
            val penalty = when {
                matches(row, seg, false) -> basePenalty
                fuzzy && matches(row, seg, true) -> maxOf(basePenalty, 1)
                else -> continue
            }
            val rank = rankOf(row, seg)
            val previous = hits[row.text]
            if (previous == null || better(previous, rank, penalty, row.freq)) {
                hits[row.text] = Ranked(row, rank, seg, penalty)
            }
        }
    }

    private fun better(previous: Ranked, rank: Int, penalty: Int, freq: Int): Boolean {
        if (rank != previous.rank) return rank < previous.rank
        if (penalty != previous.penalty) return penalty < previous.penalty
        return freq > previous.row.freq
    }

    private fun correctedSegments(text: String): List<Pair<Seg, Int>> {
        val paths = Array(text.length + 1) { ArrayList<Node>(4) }
        paths[0].add(Node(emptyList(), 0, 0))
        for (start in 0 until text.length) {
            val from = paths[start]
            if (from.isEmpty()) continue
            for (edge in syllableEdges(text, start)) {
                val dest = paths[edge.end]
                for (node in from) {
                    val cost = node.cost + edge.cost
                    if (cost > 2) continue
                    dest += Node(
                        node.parts + edge.syllable,
                        cost,
                        node.score + edge.syllable.length * edge.syllable.length - edge.cost * 5,
                    )
                }
                prune(dest)
            }
        }
        return paths[text.length]
            .filter { it.cost > 0 && it.parts.isNotEmpty() }
            .map { Seg(it.parts, partial = false) to it.cost }
            .distinctBy { it.first.parts to it.second }
            .take(8)
    }

    private data class Edge(val syllable: String, val end: Int, val cost: Int)

    private fun syllableEdges(text: String, start: Int): List<Edge> {
        val exact = trie.endings(text, start).map { end ->
            Edge(text.substring(start, end), end, 0)
        }
        val typos = ArrayList<Edge>()
        val remain = text.length - start
        for (syllable in syllableList) {
            if (syllable.length < 2 || syllable.length > remain + 1) continue
            val edge = typoEdge(text, start, syllable) ?: continue
            typos += edge
        }
        typos.sortWith(compareBy<Edge> { it.cost }.thenByDescending { it.syllable.length })
        return exact + typos.take(6)
    }

    private fun typoEdge(text: String, start: Int, syllable: String): Edge? {
        val remain = text.length - start
        var best: Edge? = null
        for (take in intArrayOf(syllable.length, syllable.length - 1, syllable.length + 1)) {
            if (take < 1 || take > remain) continue
            val cost = editCost(text.substring(start, start + take), syllable) ?: continue
            if (cost != 1) continue
            val edge = Edge(syllable, start + take, cost)
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

    private data class Node(val parts: List<String>, val cost: Int, val score: Int)

    private data class Ranked(val row: LexRow, val rank: Int, val seg: Seg, val penalty: Int)
}

private fun letterKeys(seg: Seg, fuzzy: Boolean): List<Pair<String, Int>> {
    val parts = seg.parts
    if (parts.isEmpty()) return emptyList()
    val groups = parts.mapIndexed { index, part ->
        val tail = seg.partial && index == parts.lastIndex
        if (!fuzzy || tail) listOf(part) else expandSyllable(part).take(4)
    }
    fun join(choose: (Int) -> String) = buildString {
        for (index in groups.indices) append(choose(index))
    }
    val keys = LinkedHashSet<Pair<String, Int>>()
    val exact = join { groups[it].first() }
    keys += exact to 0
    if (!fuzzy) return keys.toList()
    for (index in groups.indices) {
        for (alt in groups[index].drop(1)) {
            val key = join { if (it == index) alt else groups[it].first() }
            if (key != exact) keys += key to 1
            if (keys.size >= 36) return keys.toList()
        }
    }
    if (parts.size <= 6) {
        for (left in groups.indices) {
            for (right in left + 1 until groups.size) {
                for (a in groups[left].drop(1).take(2)) {
                    for (b in groups[right].drop(1).take(2)) {
                        val key = join {
                            when (it) {
                                left -> a
                                right -> b
                                else -> groups[it].first()
                            }
                        }
                        if (key != exact) keys += key to 1
                        if (keys.size >= 36) return keys.toList()
                    }
                }
            }
        }
    }
    return keys.toList()
}

private fun editCost(typed: String, target: String): Int? {
    if (typed == target) return 0
    val gap = typed.length - target.length
    if (gap > 1 || gap < -1) return null
    if (gap == 0) {
        var index = -1
        for (i in typed.indices) {
            if (typed[i] == target[i]) continue
            if (index >= 0) {
                val swapped = index == i - 1 && typed[i] == target[index] && typed[index] == target[i]
                if (!swapped) return null
                for (cursor in i + 1 until typed.length) {
                    if (typed[cursor] != target[cursor]) return null
                }
                return 1
            }
            if (!adjacentKey(typed[i], target[i])) return null
            index = i
        }
        return if (index >= 0) 1 else null
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
    if (!skipped && leftover == 1) return 1
    if (skipped && leftover == 0) return 1
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

internal fun matches(row: LexRow, seg: Seg, fuzzy: Boolean): Boolean {
    val parts = row.syllables.split(' ')
    val typed = seg.parts
    if (typed.isEmpty() || parts.size < typed.size) return false
    val complete = if (seg.partial) typed.lastIndex else typed.size
    for (index in 0 until complete) {
        if (!syllableEquals(typed[index], parts[index], fuzzy)) return false
    }
    if (!seg.partial) return true
    val target = parts[typed.lastIndex]
    val partial = typed.last()
    if (target.startsWith(partial)) return true
    if (!fuzzy) return false
    return expandSyllable(partial).any { target.startsWith(it) }
}

private fun syllableEquals(typed: String, dict: String, fuzzy: Boolean): Boolean {
    if (typed == dict) return true
    if (!fuzzy) return false
    return dict in expandSyllable(typed)
}

private fun segScore(seg: Seg): Int {
    return seg.parts.withIndex().sumOf { (index, part) ->
        val complete = !(seg.partial && index == seg.parts.lastIndex)
        if (complete) part.length * part.length else part.length
    }
}

private fun rankOf(row: LexRow, seg: Seg): Int {
    val count = row.syllables.count { it == ' ' } + 1
    return when {
        !seg.partial && count == seg.parts.size -> 0
        !seg.partial -> 1
        else -> 2
    }
}
