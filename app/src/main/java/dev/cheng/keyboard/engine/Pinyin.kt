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
            if (dest.size > 28) {
                dest.sortByDescending { it.score }
                val best = dest.first().score
                val kept = ArrayList<Path>(16)
                val tails = HashSet<String>()
                for (option in dest) {
                    if (kept.size >= 16) break
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
    val full = paths[size].map { Seg(it.parts, partial = false) }
    val partials = ArrayList<Seg>()
    for (start in 0..size) {
        if (paths[start].isEmpty()) continue
        if (start == size || !trie.hasPrefix(text, start)) continue
        val rest = text.substring(start)
        if (rest.isEmpty()) continue
        val ranked = paths[start].sortedByDescending { it.score }.take(3)
        for (path in ranked) partials.add(Seg(path.parts + rest, partial = true))
    }
    return (full + partials).distinctBy { it.parts to it.partial }.sortedByDescending { segScore(it) }.take(24)
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
        val book = Book()
        val full = segs.filter { !it.partial }
        val open = segs.filter { it.partial && it.parts.isNotEmpty() && !trie.isWord(it.parts.last()) }
        val chosen = ArrayList<Seg>(5)
        for (seg in full) {
            if (chosen.size >= 3) break
            chosen += seg
        }
        var partials = 0
        for (seg in open) {
            if (partials >= 2) break
            chosen += seg
            partials++
        }
        val solid = full.firstOrNull()?.parts?.all { it.length >= 2 } == true
        val pinyin = ArrayList<Scored>()
        val ordered = ArrayList<Scored>()
        val seen = HashSet<String>()
        for ((index, seg) in chosen.withIndex()) {
            val block = book.rank(book.candidates(seg))
            val leading = if (index == 0 && !seg.partial) book.prefixes(seg) else emptyList()
            for (item in block + leading) {
                pinyin += item
                if (seen.add(item.text)) ordered += item
            }
        }
        val abbrev = if (solid || letters.length !in 2..16) emptyList() else book.abbreviations(letters)
        var body = if (solid) ordered else book.rank(pinyin + abbrev)
        val primary = full.firstOrNull() ?: open.firstOrNull()
        if (fuzzy && primary != null && !primary.partial) {
            val extra = book.fuzzy(primary).filter { item -> body.none { it.text == item.text } }
            body = if (body.isEmpty()) extra else body + extra
        }
        if (body.isEmpty() && letters.isNotEmpty()) body = book.typos(letters)
        val single = primary != null && !primary.partial && primary.parts.size == 1
        val chars = if (single) emptyList() else book.anchor(primary).filter { item -> body.none { it.text == item.text } }.take(8)
        val items = if (single) body.take(200) else (body.take(40) + chars).take(48)
        val best = items.firstOrNull()
        val preedit = when {
            best == null -> chosen.firstOrNull()?.parts?.joinToString("'") ?: letters
            best.typo -> letters
            else -> best.preedit
        }
        val shown = if (text.endsWith("'") && !preedit.endsWith("'")) "$preedit'" else preedit
        return Decode(shown, items.map { Candidate(it.text, it.syllables, it.freq, it.consumed) })
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
            if (seg.parts.size >= 2 && trie.hasExtension(seg.parts.last())) items += opened(seg)
            return items
        }

        fun prefixes(seg: Seg): List<Scored> {
            if (seg.partial || seg.parts.size < 2) return emptyList()
            val items = ArrayList<Scored>()
            for (end in seg.parts.size - 1 downTo 1) {
                val head = seg.parts.subList(0, end)
                val row = spell(head).firstOrNull() ?: continue
                items += scored(
                    row,
                    wordScore(row.text, row.freq),
                    head.joinToString("'"),
                    false,
                    typedLength(head),
                )
                if (items.size >= 2) break
            }
            return items
        }

        fun abbreviations(letters: String): List<Scored> {
            val items = ArrayList<Scored>()
            val paths = listOf(tokens(letters, false), tokens(letters, true)).distinct()
            for (token in paths) {
                val shortened = token.count { it.length < 2 || !trie.isWord(it) }
                if (shortened == 0) continue
                val key = buildString { for (part in token) append(part[0]) }
                val rows = lexicon.byInitials(key, 48) + user.filter {
                    dev.cheng.keyboard.model.initialsOf(it.syllables).startsWith(key)
                }
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

        fun fuzzy(seg: Seg): List<Scored> {
            if (seg.parts.isEmpty() || seg.parts.size > 4) return emptyList()
            val used = typedLength(seg.parts)
            val items = ArrayList<Scored>()
            for (index in seg.parts.indices) {
                for (alt in expandSyllable(seg.parts[index]).drop(1).take(2)) {
                    val parts = seg.parts.toMutableList()
                    parts[index] = alt
                    val preedit = parts.joinToString("'")
                    for (row in spell(parts).take(2)) {
                        items += scored(row, wordScore(row.text, row.freq) - 32.0, preedit, false, used)
                    }
                }
            }
            return rank(items)
        }

        fun typos(letters: String): List<Scored> {
            val items = ArrayList<Scored>()
            for ((seg, cost) in correctedSegments(letters)) {
                for (row in spell(seg.parts).take(2)) {
                    items += scored(row, wordScore(row.text, row.freq) - 42.0 * cost, letters, true, letters.length)
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
                spell(listOf(syllable), boostUser = true).filter { it.text.length == 1 }.take(8)
            }
            val preedit = syllable
            return rows.map { scored(it, wordScore(it.text, it.freq), preedit, false, syllable.length) }
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
            val tail = seg.parts.last()
            val prefix = seg.parts.joinToString(" ")
            val longer = lexicon.spellingStarting(prefix, seg.parts.size, 6) + user.filter { extends(it, seg.parts) }
            for (row in longer) {
                if (!extends(row, seg.parts)) continue
                items += scored(row, wordScore(row.text, row.freq), preedit, false, used)
            }
            val heads = headPieces(head)
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
            val words = spell(head).take(3).map { Piece(it.text, it.syllables, it.freq, wordScore(it.text, it.freq)) }
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
                    val rows = spell(parts.subList(start, end)).take(if (end - start == 1) 4 else 3)
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
                dp[end] = ArrayList(rankPieces(found).take(3))
            }
            return dp[parts.size]
        }

        private fun spell(parts: List<String>, boostUser: Boolean = false, limit: Int = 12): List<LexRow> {
            if (parts.isEmpty()) return emptyList()
            val key = parts.joinToString(" ")
            val cacheKey = "${if (boostUser) "u" else "d"}:$limit:$key"
            return cache.getOrPut(cacheKey) {
                val dict = lexicon.spelling(key, limit).ifEmpty {
                    if (parts.size == 1) {
                        lexicon.charsStarting(key, limit).filter { it.syllables == key && it.text.length == 1 }
                    } else {
                        emptyList()
                    }
                }
                if (!boostUser) {
                    dict
                } else {
                    val extra = user.filter { it.syllables == key }
                    (extra + dict).distinctBy { it.text }.sortedByDescending { it.freq }
                }
            }
        }

        private fun charsOf(prefix: String): List<LexRow> {
            return lexicon.charsStarting(prefix, 8)
                .filter { it.text.length == 1 && it.syllables.startsWith(prefix) }
                .distinctBy { it.text }
                .sortedByDescending { it.freq }
                .take(6)
        }

        private fun tokens(text: String, mergeShengmu: Boolean): List<String> {
            val out = ArrayList<String>()
            var index = 0
            while (index < text.length) {
                var end = -1
                for (cut in trie.endings(text, index)) {
                    if (cut - index >= 2) end = maxOf(end, cut)
                }
                if (end > index) {
                    out += text.substring(index, end)
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
                if (typed.length >= 2 && trie.isWord(typed)) {
                    if (syllable != typed) return false
                } else if (!syllable.startsWith(typed)) {
                    return false
                }
            }
            return true
        }

        private fun extends(row: LexRow, parts: List<String>): Boolean {
            val syllables = row.syllables.split(' ')
            if (syllables.size != parts.size || parts.isEmpty()) return false
            for (index in 0 until parts.lastIndex) {
                if (syllables[index] != parts[index]) return false
            }
            val typed = parts.last()
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
        return count * count * kotlin.math.ln(freq.coerceAtLeast(1).toDouble())
    }

    private companion object {
        const val FULL_WORD_BONUS = 28.0
        val shengmu = setOf("zh", "ch", "sh")
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

private fun segScore(seg: Seg): Int {
    return seg.parts.withIndex().sumOf { (index, part) ->
        val complete = !(seg.partial && index == seg.parts.lastIndex)
        if (complete) part.length * part.length else part.length
    }
}

