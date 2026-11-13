package dev.cheng.keyboard.engine

import dev.cheng.keyboard.model.Candidate
import dev.cheng.keyboard.model.TouchSample
import java.util.PriorityQueue
import kotlin.math.ln

internal fun spatialSubstitutionCost(
    candidate: Char,
    sample: TouchSample?,
    legacySpatial: Boolean,
): Float? {
    val penalty = sample?.penaltyOf(candidate)
    return when {
        penalty != null -> 0.35f + 0.50f * penalty.coerceIn(0f, 1f)
        sample == null && legacySpatial -> 0.78f
        else -> null
    }
}

class LatinDecoder {
    private class Node {
        val next = arrayOfNulls<Node>(27)
        var freq = 0
        var baseFreq = 0
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
        var loadedCount = 0
        for ((word, freq) in words) {
            if (word.length == 1 && word != "a" && word != "i") continue
            insert(built, word, freq, isBase = true)
            loadedCount++
        }
        if (loadedCount >= 1_000) {
            for ((word, floorFreq) in defaultTechWords) {
                val existing = walkFrom(built, word)?.baseFreq ?: 0
                if (existing < floorFreq) {
                    insert(built, word, floorFreq, isBase = true)
                }
            }
        }
        for ((word, freq) in extra) insert(built, word, freq, isBase = false)
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

    fun suggest(
        raw: String,
        verbatimFirst: Boolean = false,
        prevWord: String? = null,
        touchHints: List<String> = emptyList(),
        touchSamples: List<TouchSample?> = emptyList(),
    ): List<Candidate> {
        published
        val prefix = raw.lowercase()
        if (prefix.isEmpty() || prefix.any { it !in 'a'..'z' && it != '\'' }) return emptyList()
        val followers = prevWord?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { bigrams[it] }
        fun contextBonus(word: String): Double {
            val count = followers?.get(word) ?: 0
            return if (count <= 0) 0.0 else minOf(8.5, 1.65 * ln((count + 1).toDouble()))
        }
        if (prefix == "i") {
            val base = collectPrefixCompletions(prefix, walk(prefix))
            val out = ArrayList<Candidate>(20)
            out += Candidate("i", "i", 2_000_000)
            val sortedBase = if (followers != null) {
                base.sortedByDescending { (w, f) -> ln(f.coerceAtLeast(1).toDouble()) + contextBonus(w) }
            } else {
                base
            }
            for ((word, freq) in sortedBase) {
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
        val completions = collectPrefixCompletions(prefix, start)
        val bestCompletionFreq = completions.asSequence()
            .filter { it.first != prefix }
            .maxOfOrNull { it.second } ?: 0
        val largeCorpus = root.best >= 1_000_000
        val minCredibleFreq = if (largeCorpus) 500 else 1
        val credibleExact = exactWord != null && (
            prefix in extra ||
                exactWord.freq >= 5_000 ||
                (exactWord.freq >= minCredibleFreq && bestCompletionFreq < exactWord.freq.toLong() * 40L)
            )
        if (exactWord != null) {
            val word = exactWord.word!!
            val boost = when {
                word in directContractions -> -12.0
                credibleExact -> 30.0
                largeCorpus && exactWord.freq < 300 -> -4.0
                else -> 0.0
            }
            offer(word, exactWord.freq, ln(exactWord.freq.coerceAtLeast(1).toDouble()) + boost + contextBonus(word))
        }

        for ((word, freq) in completions) {
            if (word == prefix) continue
            val extraLen = (word.length - prefix.length).coerceAtLeast(0)
            val rankScore = ln(freq.coerceAtLeast(1).toDouble()) - extraLen * 0.55 + contextBonus(word)
            offer(word, freq, rankScore)
        }

        ambiguousContractions[prefix]?.let { contraction ->
            val anchorScore = scored[prefix]?.second ?: 22.0
            offer(contraction, 600_000, anchorScore - 0.25)
        }

        val strongPrefixMatch = credibleExact || completions.any { it.first != prefix && it.second >= 500 }
        val hasBorderTap = touchSamples.any { sample ->
            sample != null && sample.alternatives.any { it.penalty <= 0.35f }
        }
        if (prefix.length >= 2 && (!strongPrefixMatch || completions.size < 3 || hasBorderTap)) {
            for (hit in fuzzyMatches(prefix, touchHints, touchSamples)) {
                val baseScore = ln(hit.freq.coerceAtLeast(1).toDouble()) - hit.cost * 3.2 + contextBonus(hit.word)
                val adjusted = when {
                    !strongPrefixMatch -> baseScore
                    hit.cost <= 0.60 && credibleExact && hit.freq.toLong() > (exactWord?.freq ?: 0).toLong() * 8L ->
                        baseScore + 28.5
                    hit.cost <= 0.60 && !credibleExact -> baseScore - 1.2
                    hit.cost <= 0.60 -> baseScore - 2.0
                    else -> baseScore - 8.0
                }
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

    fun nextLetterPriors(prefix: String, prevWord: String? = null): FloatArray {
        published
        val cleaned = prefix.lowercase()
        if (cleaned.isNotEmpty()) {
            val node = walk(cleaned) ?: return FloatArray(26)
            val logs = FloatArray(26)
            var maxLog = 0f
            for (index in 0..25) {
                val child = node.next[index]
                if (child != null && child.best > 0) {
                    val value = ln((child.best + 1).toDouble()).toFloat()
                    logs[index] = value
                    if (value > maxLog) maxLog = value
                }
            }
            if (maxLog <= 0f) return FloatArray(26)
            return FloatArray(26) { index ->
                val child = node.next[index]
                if (child != null && child.best > 0) {
                    (logs[index] / maxLog).coerceIn(0f, 1f)
                } else {
                    -0.20f
                }
            }
        }
        val prev = prevWord?.trim()?.lowercase().orEmpty()
        if (prev.isNotEmpty()) {
            val followers = bigrams[prev] ?: return FloatArray(26)
            val counts = FloatArray(26)
            var maxCount = 0f
            for ((word, count) in followers) {
                if (count <= 0) continue
                val first = word.firstOrNull()?.lowercaseChar() ?: continue
                if (first in 'a'..'z') {
                    val idx = first - 'a'
                    counts[idx] += count.toFloat()
                    if (counts[idx] > maxCount) maxCount = counts[idx]
                }
            }
            if (maxCount <= 0f) return FloatArray(26)
            return FloatArray(26) { index ->
                if (counts[index] > 0f) ((counts[index] / maxCount) * 0.55f).coerceIn(0f, 0.55f) else 0f
            }
        }
        return FloatArray(26)
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
            insert(root, cleaned, freq, isBase = false)
        }
        val prev = previousWord?.trim()?.lowercase().orEmpty()
        if (prev.isNotEmpty() && cleaned.isNotEmpty() && prev.all { it in 'a'..'z' || it == '\'' }) {
            val bucket = bigrams.getOrPut(prev) { HashMap() }
            bucket[cleaned] = (bucket[cleaned] ?: 0) + 50
        }
    }

    fun bigramCount(previousWord: String, word: String): Int =
        bigrams[previousWord.trim().lowercase()]?.get(word.trim().lowercase()) ?: 0

    fun loadUserBigrams(entries: List<Triple<String, String, Int>>) {
        for ((prev, next, count) in entries) {
            if (prev.isBlank() || next.isBlank() || count <= 0) continue
            val bucket = bigrams.getOrPut(prev.lowercase()) { HashMap() }
            bucket[next.lowercase()] = maxOf(bucket[next.lowercase()] ?: 0, count)
        }
    }

    fun forget(word: String): Boolean {
        val cleaned = word.trim().lowercase()
        if (cleaned.isEmpty()) return false
        val removedExtra = extra.remove(cleaned) != null
        var removedBigram = false
        if (bigrams.remove(cleaned) != null) removedBigram = true
        for (bucket in bigrams.values) {
            if (bucket.remove(cleaned) != null) removedBigram = true
        }
        val node = walk(cleaned)
        if (node != null && removedExtra) {
            node.freq = node.baseFreq
            if (node.baseFreq == 0) node.word = null
            published++
        }
        return removedExtra || removedBigram
    }

    fun loadUser(words: List<Pair<String, Int>>) {
        extra.clear()
        extra.putAll(words)
        for ((word, freq) in words) insert(root, word, freq, isBase = false)
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

    private fun fuzzyMatches(
        typed: String,
        touchHints: List<String> = emptyList(),
        touchSamples: List<TouchSample?> = emptyList(),
    ): List<FuzzyHit> {
        val fullMaxCost = if (typed.length <= 4) 1.85 else 2.9
        val minBranchBest = if (root.best >= 1_000_000) 100 else 1
        val hits = HashMap<String, FuzzyHit>()
        var visits = 0

        fun dfs(node: Node, pos: Int, cost: Double, lastChar: Char, maxCost: Double, maxVisits: Int) {
            if (cost > maxCost || visits > maxVisits) return
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
                        if (child.best < minBranchBest) continue
                        val addCost = if (idx == 26) 0.6 else 1.45
                        val nextChar = if (idx == 26) '\'' else 'a' + idx
                        dfs(child, pos, cost + addCost, nextChar, maxCost, maxVisits)
                    }
                }
                return
            }

            val ch = typed[pos]
            val exactIdx = indexOf(ch)
            if (exactIdx != null) {
                node.next[exactIdx]?.let { dfs(it, pos + 1, cost, ch, maxCost, maxVisits) }
            }

            // Adjacent character transposition (e.g. "teh" -> "the", "recieve" -> "receive")
            if (pos + 1 < typed.length && typed[pos] != typed[pos + 1] && cost + 1.05 <= maxCost) {
                val firstIdx = indexOf(typed[pos + 1])
                val secondIdx = indexOf(typed[pos])
                if (firstIdx != null && secondIdx != null) {
                    node.next[firstIdx]?.next?.get(secondIdx)?.let { transposed ->
                        dfs(transposed, pos + 2, cost + 1.05, typed[pos], maxCost, maxVisits)
                    }
                }
            }

            // Substitution (QWERTY adjacent key preferred; general substitution only on longer words)
            val spatial = touchHints.getOrNull(pos).orEmpty()
            val sample = touchSamples.getOrNull(pos)
            for (idx in 0 until 26) {
                val child = node.next[idx] ?: continue
                if (child.best < minBranchBest) continue
                val candidateChar = 'a' + idx
                if (candidateChar == ch) continue
                val spatialCost = spatialSubstitutionCost(candidateChar, sample, candidateChar in spatial)?.toDouble()
                val subCost = when {
                    spatialCost != null -> spatialCost
                    isQwertyNeighbor(ch, candidateChar) -> 1.25
                    pos >= 2 && ch in "aeiou" && candidateChar in "aeiou" -> 1.35
                    typed.length >= 4 && pos > 0 -> 2.15
                    else -> continue
                }
                if (cost + subCost <= maxCost) {
                    dfs(child, pos + 1, cost + subCost, candidateChar, maxCost, maxVisits)
                }
            }

            // Omitted apostrophe inside word (e.g. user omitted ' in contraction)
            node.next[26]?.let { apoChild ->
                if (cost + 0.55 <= maxCost) dfs(apoChild, pos, cost + 0.55, '\'', maxCost, maxVisits)
            }

            // Missing character in typed input (insertion into target word, e.g. "thnks" -> "thanks")
            if (typed.length >= 3 && cost + 1.10 <= maxCost) {
                for (idx in 0 until 26) {
                    val child = node.next[idx] ?: continue
                    if (child.best < minBranchBest) continue
                    val insChar = 'a' + idx
                    val insCost = if (insChar == lastChar) 1.10 else 1.65
                    if (cost + insCost <= maxCost) {
                        dfs(child, pos, cost + insCost, insChar, maxCost, maxVisits)
                    }
                }
            }

            // Extra character in typed input (deletion from typed input, e.g. "helllo" -> "hello")
            if (typed.length >= 3 && cost + 1.15 <= maxCost) {
                val dupBonus = when {
                    pos > 0 && typed[pos] == typed[pos - 1] -> 1.15
                    pos == 0 -> 1.75
                    else -> 1.55
                }
                if (cost + dupBonus <= maxCost) {
                    dfs(node, pos + 1, cost + dupBonus, lastChar, maxCost, maxVisits)
                }
            }
        }

        val firstPassMax = minOf(fullMaxCost, 1.75)
        dfs(root, 0, 0.0, '\u0000', firstPassMax, 2500)
        if (fullMaxCost > firstPassMax && hits.values.none { it.cost <= 1.35 && it.freq >= 5_000 }) {
            visits = 0
            dfs(root, 0, 0.0, '\u0000', fullMaxCost, 4500)
        }
        return hits.values
            .sortedByDescending { ln(it.freq.coerceAtLeast(1).toDouble()) - it.cost * 3.2 }
            .take(8)
    }

    private fun insert(root: Node, word: String, freq: Int, isBase: Boolean) {
        val stack = ArrayList<Node>()
        var node = root
        stack += node
        for (ch in word) {
            val index = indexOf(ch) ?: return
            val child = node.next[index] ?: Node().also { node.next[index] = it }
            node = child
            stack += node
        }
        if (isBase && freq > node.baseFreq) {
            node.baseFreq = freq
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

    private fun walk(prefix: String): Node? = walkFrom(root, prefix)

    private fun walkFrom(startNode: Node, prefix: String): Node? {
        var node = startNode
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

        val defaultTechWords = listOf(
            "api" to 8_000,
            "apk" to 8_000,
            "bug" to 12_000,
            "sdk" to 6_000,
            "ide" to 5_000,
            "cli" to 5_000,
            "gui" to 5_000,
            "pdf" to 12_000,
            "ppt" to 12_000,
            "docx" to 6_000,
            "xlsx" to 6_000,
            "csv" to 6_000,
            "usb" to 8_000,
            "cpu" to 8_000,
            "gpu" to 8_000,
            "npu" to 5_000,
            "ssd" to 6_000,
            "ios" to 10_000,
            "macos" to 6_000,
            "linux" to 8_000,
            "ubuntu" to 5_000,
            "android" to 12_000,
            "kotlin" to 10_000,
            "golang" to 6_000,
            "flutter" to 6_000,
            "html" to 8_000,
            "css" to 8_000,
            "json" to 10_000,
            "yaml" to 6_000,
            "xml" to 8_000,
            "sql" to 8_000,
            "http" to 10_000,
            "https" to 12_000,
            "url" to 10_000,
            "dns" to 6_000,
            "ssh" to 6_000,
            "vpn" to 10_000,
            "wifi" to 15_000,
            "wlan" to 6_000,
            "nfc" to 6_000,
            "git" to 10_000,
            "github" to 12_000,
            "gitlab" to 6_000,
            "docker" to 8_000,
            "redis" to 6_000,
            "mysql" to 6_000,
            "sqlite" to 6_000,
            "chatgpt" to 10_000,
            "deepseek" to 10_000,
            "openai" to 8_000,
            "vscode" to 8_000,
            "adb" to 6_000,
            "ota" to 6_000,
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
