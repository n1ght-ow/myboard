package dev.cheng.keyboard.engine

import dev.cheng.keyboard.model.Candidate
import java.util.PriorityQueue
import kotlin.math.ln

class LatinDecoder {
    private class Node {
        val next = arrayOfNulls<Node>(27)
        var freq = 0
        var best = 0
        var word: String? = null
    }

    @Volatile
    private var root = Node()

    @Volatile
    private var published = 0
    private val extra = HashMap<String, Int>()
    private val bigrams = HashMap<String, MutableMap<String, Int>>()

    init {
        seedDefaultBigrams()
    }

    fun load(words: Sequence<Pair<String, Int>>) {
        val built = Node()
        for ((word, freq) in words) insert(built, word, freq)
        for ((word, freq) in extra) insert(built, word, freq)
        root = built
        published++
    }

    fun exactFreq(word: String): Int {
        published
        val cleaned = word.lowercase()
        val node = walk(cleaned)
        if (node != null && node.word != null && node.freq > 0) return node.freq
        return directContractions[cleaned]?.let { 500_000 } ?: 0
    }

    fun suggest(raw: String, verbatimFirst: Boolean = false): List<Candidate> {
        published
        val prefix = raw.lowercase()
        if (prefix.isEmpty() || prefix.any { it !in 'a'..'z' && it != '\'' }) return emptyList()
        if (prefix == "i") {
            val base = collectPrefixCompletions(prefix, walk(prefix))
            val out = ArrayList<Candidate>(20)
            out += Candidate("i", "i", 2_000_000)
            for ((word, freq) in base) {
                if (word != "i") out += Candidate(word, word, freq)
            }
            return out.take(20)
        }

        val scored = LinkedHashMap<String, Pair<Int, Double>>()
        fun offer(word: String, freq: Int, rankScore: Double) {
            val current = scored[word]
            if (current == null || rankScore > current.second) {
                scored[word] = freq to rankScore
            }
        }

        directContractions[prefix]?.let { contraction ->
            offer(contraction, 900_000, 140.0)
        }

        val start = walk(prefix)
        val exactWord = start?.takeIf { it.word != null && it.freq > 0 }
        if (exactWord != null) {
            val word = exactWord.word!!
            val boost = if (word in directContractions) -12.0 else 30.0
            offer(word, exactWord.freq, ln(exactWord.freq.coerceAtLeast(1).toDouble()) + boost)
        }

        val completions = collectPrefixCompletions(prefix, start)
        for ((word, freq) in completions) {
            if (word == prefix) continue
            val extraLen = (word.length - prefix.length).coerceAtLeast(0)
            val rankScore = ln(freq.coerceAtLeast(1).toDouble()) - extraLen * 0.55
            offer(word, freq, rankScore)
        }

        ambiguousContractions[prefix]?.let { contraction ->
            val anchorScore = scored[prefix]?.second ?: 22.0
            offer(contraction, 600_000, anchorScore - 0.25)
        }

        val strongPrefixMatch = exactWord != null || completions.any { it.second >= 500 }
        if (prefix.length >= 2 && (!strongPrefixMatch || completions.size < 3)) {
            for (hit in fuzzyMatches(prefix)) {
                val baseScore = ln(hit.freq.coerceAtLeast(1).toDouble()) - hit.cost * 3.2
                val adjusted = if (strongPrefixMatch) baseScore - 8.0 else baseScore
                offer(hit.word, hit.freq, adjusted)
            }
        }

        if (scored.isEmpty()) return emptyList()

        val ranked = scored.entries
            .sortedByDescending { it.value.second }
            .map { Candidate(it.key, it.key, it.value.first) }
            .toMutableList()

        if (verbatimFirst) {
            ranked.removeAll { it.text == prefix }
            ranked.add(0, Candidate(prefix, prefix, exactWord?.freq ?: 1))
        } else if (ranked.first().text != prefix && prefix !in directContractions && prefix.length >= 2) {
            val existingIdx = ranked.indexOfFirst { it.text == prefix }
            if (existingIdx < 0) {
                ranked.add(1.coerceAtMost(ranked.size), Candidate(prefix, prefix, 1))
            } else if (existingIdx > 2) {
                val item = ranked.removeAt(existingIdx)
                ranked.add(1, item)
            }
        }

        return ranked.take(20)
    }

    fun predictNext(previousWord: String): List<Candidate> {
        val prev = previousWord.trim().lowercase()
        if (prev.isEmpty()) return emptyList()
        val followers = bigrams[prev] ?: return emptyList()
        return followers.entries
            .sortedByDescending { it.value }
            .take(12)
            .map { Candidate(it.key, it.key, it.value) }
    }

    fun learn(word: String, previousWord: String? = null) {
        val cleaned = word.lowercase()
        if (cleaned.any { it !in 'a'..'z' && it != '\'' }) return
        if (cleaned.length >= 2) {
            val base = walk(cleaned)?.freq ?: 0
            val freq = maxOf(base, extra[cleaned] ?: 0) + 20_000
            extra[cleaned] = freq
            insert(root, cleaned, freq)
        }
        val prev = previousWord?.trim()?.lowercase().orEmpty()
        if (prev.isNotEmpty() && cleaned.isNotEmpty() && prev.all { it in 'a'..'z' || it == '\'' }) {
            val bucket = bigrams.getOrPut(prev) { HashMap() }
            bucket[cleaned] = (bucket[cleaned] ?: 0) + 50
        }
    }

    fun loadUser(words: List<Pair<String, Int>>) {
        extra.clear()
        extra.putAll(words)
        for ((word, freq) in words) insert(root, word, freq)
    }

    fun clearUser() {
        extra.clear()
        bigrams.clear()
        seedDefaultBigrams()
    }

    fun userWords(): List<Pair<String, Int>> = extra.entries.map { it.key to it.value }

    private fun collectPrefixCompletions(prefix: String, start: Node?): List<Pair<String, Int>> {
        if (start == null) return emptyList()
        val heap = PriorityQueue<Pair<Node, String>>(compareByDescending { it.first.best })
        heap.add(start to prefix)
        val found = ArrayList<Pair<String, Int>>(32)
        var steps = 0
        while (heap.isNotEmpty() && steps < 500 && found.size < 32) {
            steps++
            val (node, path) = heap.remove()
            val word = node.word
            if (word != null && node.freq > 0) found += word to node.freq
            for (index in node.next.indices) {
                val child = node.next[index] ?: continue
                val ch = if (index == 26) '\'' else 'a' + index
                heap.add(child to path + ch)
            }
        }
        return found
    }

    private data class FuzzyHit(val word: String, val freq: Int, val cost: Double)

    private fun fuzzyMatches(typed: String): List<FuzzyHit> {
        val maxCost = if (typed.length <= 4) 1.85 else 2.9
        val hits = HashMap<String, FuzzyHit>()
        var visits = 0

        fun dfs(node: Node, pos: Int, cost: Double) {
            if (cost > maxCost || visits > 1800) return
            visits++
            if (pos == typed.length) {
                val w = node.word
                if (w != null && node.freq > 0 && w != typed) {
                    val prev = hits[w]
                    if (prev == null || cost < prev.cost) hits[w] = FuzzyHit(w, node.freq, cost)
                }
                if (cost + 1.45 <= maxCost) {
                    for (idx in node.next.indices) {
                        val child = node.next[idx] ?: continue
                        val addCost = if (idx == 26) 0.6 else 1.45
                        dfs(child, pos, cost + addCost)
                    }
                }
                return
            }

            val ch = typed[pos]
            val exactIdx = indexOf(ch)
            if (exactIdx != null) {
                node.next[exactIdx]?.let { dfs(it, pos + 1, cost) }
            }

            // Adjacent character transposition (e.g. "teh" -> "the", "recieve" -> "receive")
            if (pos + 1 < typed.length && typed[pos] != typed[pos + 1] && cost + 1.05 <= maxCost) {
                val firstIdx = indexOf(typed[pos + 1])
                val secondIdx = indexOf(typed[pos])
                if (firstIdx != null && secondIdx != null) {
                    node.next[firstIdx]?.next?.get(secondIdx)?.let { transposed ->
                        dfs(transposed, pos + 2, cost + 1.05)
                    }
                }
            }

            // Substitution (QWERTY adjacent key preferred; general substitution only on longer words)
            for (idx in 0 until 26) {
                val child = node.next[idx] ?: continue
                val candidateChar = 'a' + idx
                if (candidateChar == ch) continue
                val subCost = when {
                    isQwertyNeighbor(ch, candidateChar) -> 1.25
                    typed.length >= 4 && pos > 0 -> 2.15
                    else -> continue
                }
                if (cost + subCost <= maxCost) {
                    dfs(child, pos + 1, cost + subCost)
                }
            }

            // Omitted apostrophe inside word (e.g. user omitted ' in contraction)
            node.next[26]?.let { apoChild ->
                if (cost + 0.55 <= maxCost) dfs(apoChild, pos, cost + 0.55)
            }

            // Missing character in typed input (insertion into target word, e.g. "thnks" -> "thanks")
            if (typed.length >= 3 && cost + 1.65 <= maxCost) {
                for (idx in 0 until 26) {
                    val child = node.next[idx] ?: continue
                    dfs(child, pos, cost + 1.65)
                }
            }

            // Extra character in typed input (deletion from typed input, e.g. "helllo" -> "hello")
            if (typed.length >= 3 && cost + 1.55 <= maxCost) {
                val dupBonus = if (pos > 0 && typed[pos] == typed[pos - 1]) 1.15 else 1.55
                if (cost + dupBonus <= maxCost) {
                    dfs(node, pos + 1, cost + dupBonus)
                }
            }
        }

        dfs(root, 0, 0.0)
        return hits.values
            .sortedByDescending { ln(it.freq.coerceAtLeast(1).toDouble()) - it.cost * 3.2 }
            .take(8)
    }

    private fun insert(root: Node, word: String, freq: Int) {
        val stack = ArrayList<Node>()
        var node = root
        stack += node
        for (ch in word) {
            val index = indexOf(ch) ?: return
            val child = node.next[index] ?: Node().also { node.next[index] = it }
            node = child
            stack += node
        }
        if (freq >= node.freq) {
            node.freq = freq
            node.word = word
        }
        val best = node.freq
        for (index in stack.lastIndex downTo 0) {
            val current = stack[index]
            if (best <= current.best) break
            current.best = best
        }
        published++
    }

    private fun walk(prefix: String): Node? {
        var node = root
        for (ch in prefix) {
            node = node.next[indexOf(ch) ?: return null] ?: return null
        }
        return node
    }

    private fun indexOf(ch: Char): Int? = when (ch) {
        in 'a'..'z' -> ch - 'a'
        '\'' -> 26
        else -> null
    }

    private fun seedDefaultBigrams() {
        val defaults = mapOf(
            "i" to listOf("am", "have", "think", "will", "can", "don't", "want", "need"),
            "i'm" to listOf("not", "going", "sorry", "on", "here", "ready"),
            "you" to listOf("are", "can", "have", "want", "know", "need"),
            "thank" to listOf("you", "goodness"),
            "thanks" to listOf("for", "a", "again"),
            "how" to listOf("are", "to", "about", "much", "many"),
            "what" to listOf("is", "are", "about", "do", "time"),
            "good" to listOf("morning", "night", "idea", "luck", "job"),
            "looking" to listOf("forward", "for", "at", "good"),
            "let" to listOf("me", "us", "it"),
            "let's" to listOf("go", "see", "get", "do"),
            "of" to listOf("the", "course", "my", "this"),
            "in" to listOf("the", "a", "my", "this"),
            "to" to listOf("the", "be", "do", "get", "make", "see"),
            "for" to listOf("the", "you", "your", "a", "this"),
            "on" to listOf("the", "my", "this", "time"),
            "have" to listOf("a", "to", "been", "you"),
            "do" to listOf("you", "not", "it", "this"),
            "can" to listOf("you", "i", "we", "be"),
        )
        for ((prev, list) in defaults) {
            val map = HashMap<String, Int>()
            list.forEachIndexed { idx, next -> map[next] = (list.size - idx) * 20 }
            bigrams[prev] = map
        }
    }

    private companion object {
        val directContractions = mapOf(
            "dont" to "don't",
            "cant" to "can't",
            "wont" to "won't",
            "im" to "i'm",
            "ive" to "i've",
            "isnt" to "isn't",
            "arent" to "aren't",
            "wasnt" to "wasn't",
            "werent" to "weren't",
            "hasnt" to "hasn't",
            "havent" to "haven't",
            "hadnt" to "hadn't",
            "doesnt" to "doesn't",
            "didnt" to "didn't",
            "wouldnt" to "wouldn't",
            "couldnt" to "couldn't",
            "shouldnt" to "shouldn't",
            "youre" to "you're",
            "youve" to "you've",
            "youd" to "you'd",
            "youll" to "you'll",
            "theyre" to "they're",
            "theyve" to "they've",
            "theyd" to "they'd",
            "theyll" to "they'll",
            "hes" to "he's",
            "shes" to "she's",
            "thats" to "that's",
            "whats" to "what's",
            "heres" to "here's",
            "theres" to "there's",
            "wheres" to "where's",
            "whos" to "who's",
            "lets" to "let's",
            "aint" to "ain't",
        )

        val ambiguousContractions = mapOf(
            "ill" to "i'll",
            "id" to "i'd",
            "well" to "we'll",
            "were" to "we're",
            "wed" to "we'd",
            "shell" to "she'll",
            "hell" to "he'll",
            "its" to "it's",
        )

        fun isQwertyNeighbor(left: Char, right: Char): Boolean {
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
    }
}
