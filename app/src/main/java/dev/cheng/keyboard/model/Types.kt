package dev.cheng.keyboard.model

enum class Language { Chinese, English }

enum class ShiftState { Off, Once, Caps }

enum class Choice { Upper, Symbol, Lower }

enum class KeyboardPage { Letters, Number, Symbols, More }

enum class EnterKind { Newline, Go, Search, Send, Done, Next }

enum class KeyKind {
    Letter,
    Char,
    Shift,
    Backspace,
    Enter,
    Space,
    Language,
    PageLetters,
    PageNumber,
    PageSymbols,
    PageMore,
}

enum class ThemeMode { System, Dark, Light }

enum class Pending { None, Pinyin, Associate }

data class KeyModel(
    val id: String,
    val kind: KeyKind,
    val label: String,
    val hint: String? = null,
    val insert: String? = null,
    val weight: Float = 1f,
    val accent: Boolean = false,
)

data class Candidate(
    val text: String,
    val syllables: String,
    val freq: Int,
    val consumed: Int = -1,
)

data class LexRow(
    val text: String,
    val syllables: String,
    val letters: String,
    val freq: Int,
)

interface Lexicon {
    fun exact(letters: String, limit: Int): List<LexRow>
    fun prefixed(letters: String, limit: Int): List<LexRow>
    fun charsStarting(syllablePrefix: String, limit: Int): List<LexRow>
    fun phrasesStarting(text: String, limit: Int): List<LexRow>
    fun phraseFreqs(texts: Collection<String>): Map<String, Int> = emptyMap()
    fun byInitials(prefix: String, limit: Int, exactOnly: Boolean = false): List<LexRow>
    fun spelling(syllables: String, limit: Int): List<LexRow>
    fun spellingStarting(prefix: String, nsy: Int, limit: Int): List<LexRow>
}

class MemoryLexicon(private val rows: List<LexRow>) : Lexicon {
    override fun exact(letters: String, limit: Int): List<LexRow> =
        rows.filter { it.letters == letters }.sortedByDescending { it.freq }.take(limit)

    override fun prefixed(letters: String, limit: Int): List<LexRow> =
        rows.filter { it.letters.startsWith(letters) && it.letters.length > letters.length }
            .sortedByDescending { it.freq }
            .take(limit)

    override fun charsStarting(syllablePrefix: String, limit: Int): List<LexRow> =
        rows.filter { it.syllables.indexOf(' ') < 0 && it.syllables.startsWith(syllablePrefix) }
            .sortedByDescending { it.freq }
            .take(limit)

    override fun phrasesStarting(text: String, limit: Int): List<LexRow> =
        rows.filter { it.text.startsWith(text) && it.text.length > text.length }
            .sortedByDescending { it.freq }
            .take(limit)

    override fun phraseFreqs(texts: Collection<String>): Map<String, Int> {
        if (texts.isEmpty()) return emptyMap()
        val targets = texts.toHashSet()
        val out = HashMap<String, Int>(targets.size)
        for (row in rows) {
            if (row.text in targets) {
                val prev = out[row.text] ?: 0
                if (row.freq > prev) out[row.text] = row.freq
            }
        }
        return out
    }

    override fun byInitials(prefix: String, limit: Int, exactOnly: Boolean): List<LexRow> {
        if (prefix.isEmpty()) return emptyList()
        return rows.filter {
            val init = initialsOf(it.syllables)
            if (exactOnly) init == prefix else init.startsWith(prefix)
        }
            .sortedWith(compareBy<LexRow> { initialsOf(it.syllables) != prefix }.thenByDescending { it.freq })
            .take(limit)
    }

    override fun spelling(syllables: String, limit: Int): List<LexRow> =
        rows.filter { it.syllables == syllables }.sortedByDescending { it.freq }.take(limit)

    override fun spellingStarting(prefix: String, nsy: Int, limit: Int): List<LexRow> =
        rows.filter { row ->
            row.syllables.startsWith(prefix) &&
                row.syllables.length > prefix.length &&
                row.syllables.count { it == ' ' } + 1 == nsy
        }.sortedByDescending { it.freq }.take(limit)
}

internal fun initialsOf(syllables: String): String =
    syllables.split(' ').joinToString("") { part -> part.take(1) }
