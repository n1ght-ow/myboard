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
    fun byInitials(prefix: String, limit: Int): List<LexRow>
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

    override fun byInitials(prefix: String, limit: Int): List<LexRow> {
        if (prefix.isEmpty()) return emptyList()
        return rows.filter { initialsOf(it.syllables).startsWith(prefix) }
            .sortedWith(compareBy<LexRow> { initialsOf(it.syllables) != prefix }.thenByDescending { it.freq })
            .take(limit)
    }
}

internal fun initialsOf(syllables: String): String =
    syllables.split(' ').joinToString("") { part -> part.take(1) }
