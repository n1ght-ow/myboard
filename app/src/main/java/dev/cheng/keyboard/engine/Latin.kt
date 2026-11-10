package dev.cheng.keyboard.engine

import dev.cheng.keyboard.model.Candidate
import java.util.PriorityQueue

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

    fun load(words: Sequence<Pair<String, Int>>) {
        val built = Node()
        for ((word, freq) in words) insert(built, word, freq)
        for ((word, freq) in extra) insert(built, word, freq)
        root = built
        published++
    }

    fun suggest(raw: String): List<Candidate> {
        published
        val prefix = raw.lowercase()
        if (prefix.isEmpty() || prefix.any { it !in 'a'..'z' && it != '\'' }) return emptyList()
        val start = walk(prefix) ?: return emptyList()
        val heap = PriorityQueue<Pair<Node, String>>(compareByDescending { it.first.best })
        heap.add(start to prefix)
        val found = ArrayList<Pair<String, Int>>(30)
        var steps = 0
        while (heap.isNotEmpty() && steps < 500 && found.size < 30) {
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
            .sortedByDescending { it.second }
            .distinctBy { it.first }
            .take(20)
            .map { Candidate(it.first, it.first, it.second) }
    }

    fun learn(word: String) {
        val cleaned = word.lowercase()
        if (cleaned.length < 2 || cleaned.any { it !in 'a'..'z' && it != '\'' }) return
        val freq = (extra[cleaned] ?: 0) + 20_000
        extra[cleaned] = freq
        insert(root, cleaned, freq)
    }

    fun loadUser(words: List<Pair<String, Int>>) {
        extra.clear()
        extra.putAll(words)
        for ((word, freq) in words) insert(root, word, freq)
    }

    fun clearUser() {
        extra.clear()
    }

    fun userWords(): List<Pair<String, Int>> = extra.entries.map { it.key to it.value }

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
        var best = node.freq
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
}
