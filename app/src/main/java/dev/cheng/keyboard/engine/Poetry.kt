package dev.cheng.keyboard.engine

data class PoemLine(
    val initials: String,
    val text: String,
    val author: String,
    val title: String,
)

class PoetryIndex private constructor(
    private val byInitials: Map<String, List<PoemLine>>,
) {
    val size: Int = byInitials.values.sumOf { it.size }

    fun exact(letters: String, limit: Int = 6): List<PoemLine> {
        if (limit <= 0 || letters.length !in MIN_INITIALS..MAX_INITIALS) return emptyList()
        if (letters.any { it !in 'a'..'z' }) return emptyList()
        return byInitials[letters]?.take(limit).orEmpty()
    }

    companion object {
        const val MIN_INITIALS = 5
        const val MAX_INITIALS = 12

        val Empty: PoetryIndex = PoetryIndex(emptyMap())

        fun parse(raw: String): PoetryIndex {
            val indexed = LinkedHashMap<String, LinkedHashMap<String, PoemLine>>()
            for (sourceLine in raw.lineSequence()) {
                val line = sourceLine.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val parts = line.split('|', limit = 4)
                if (parts.size < 2) continue
                val initials = parts[0].trim()
                val text = parts[1].trim()
                if (initials.length !in MIN_INITIALS..MAX_INITIALS) continue
                if (initials.any { it !in 'a'..'z' }) continue
                if (text.length != initials.length || text.any { !isCjkChar(it) }) continue
                val poem = PoemLine(
                    initials = initials,
                    text = text,
                    author = parts.getOrNull(2).orEmpty().trim(),
                    title = parts.getOrNull(3).orEmpty().trim(),
                )
                indexed.getOrPut(initials) { LinkedHashMap() }.putIfAbsent(text, poem)
            }
            return PoetryIndex(indexed.mapValues { (_, lines) -> lines.values.toList() })
        }
    }
}
