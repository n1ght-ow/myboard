package dev.cheng.keyboard.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dev.cheng.keyboard.model.LexRow
import dev.cheng.keyboard.model.Lexicon
import dev.cheng.keyboard.model.ThemeMode
import org.json.JSONArray
import java.io.File

const val MAX_RECENT_CLIPS = 12
const val MAX_PINNED_CLIPS = 12
private const val MAX_CLIP_CHARS = 4096
private val GRAPHEME_CLUSTER = Regex("\\X")

val Context.settingsStore by preferencesDataStore(name = "keyboard")

data class ClipboardEntry(
    val text: String,
    val pinned: Boolean,
)

object PrefKeys {
    val theme = stringPreferencesKey("theme")
    val height = floatPreferencesKey("height")
    val haptic = booleanPreferencesKey("haptic")
    val fuzzy = booleanPreferencesKey("fuzzy")
    val askModel = stringPreferencesKey("ask_model")
    val userEpoch = longPreferencesKey("user_epoch")
    val calibLeft = floatPreferencesKey("calib_left")
    val calibRight = floatPreferencesKey("calib_right")
    val calibDy = floatPreferencesKey("calib_dy")
    val calibEpoch = longPreferencesKey("calib_epoch")
    val recentClips = stringPreferencesKey("recent_clips")
    val pinnedClips = stringPreferencesKey("pinned_clips")
}

fun mergeRecentClips(
    current: String?,
    existing: List<String>,
    limit: Int = MAX_RECENT_CLIPS,
): List<String> {
    val normalized = current?.let(::normalizeRecentClip)
    val cleaned = existing.mapNotNull(::normalizeRecentClip)
    return (listOfNotNull(normalized) + cleaned).distinct().take(limit.coerceAtLeast(0))
}

fun mergeRecentClipLists(
    primary: List<String>,
    secondary: List<String>,
    limit: Int = MAX_RECENT_CLIPS,
): List<String> {
    if (limit <= 0) return emptyList()
    val out = ArrayList<String>(limit.coerceAtLeast(0))
    for (item in primary.asSequence() + secondary.asSequence()) {
        val normalized = normalizeRecentClip(item) ?: continue
        if (normalized !in out) {
            out += normalized
            if (out.size >= limit) break
        }
    }
    return out
}

fun clipboardItemsForDisplay(
    current: String?,
    history: List<String>,
    limit: Int = MAX_RECENT_CLIPS,
): List<String> {
    if (limit <= 0) return emptyList()

    val boundedHistory = mergeRecentClipLists(emptyList(), history, limit)
    val fullCurrent = current?.takeIf { it.isNotBlank() } ?: return boundedHistory
    if (boundedHistory.isEmpty()) return listOf(fullCurrent)

    val storedCurrent = truncateRecentClip(fullCurrent, MAX_CLIP_CHARS)
    val currentIndex = boundedHistory.indexOf(storedCurrent)
    if (currentIndex < 0) return boundedHistory
    return boundedHistory.toMutableList().also { it[currentIndex] = fullCurrent }
}

fun togglePinnedClip(
    text: String,
    pinned: List<String>,
    limit: Int = MAX_PINNED_CLIPS,
): List<String> {
    val normalized = normalizeRecentClip(text) ?: return mergeRecentClipLists(emptyList(), pinned, limit)
    val cleaned = mergeRecentClipLists(emptyList(), pinned, limit)
    return if (normalized in cleaned) {
        cleaned.filterNot { it == normalized }
    } else {
        (listOf(normalized) + cleaned).distinct().take(limit.coerceAtLeast(0))
    }
}

fun removeClip(text: String, clips: List<String>): List<String> {
    val normalized = normalizeRecentClip(text)
    return clips.filterNot { it == text || (normalized != null && it == normalized) }
}

fun clipboardDisplayEntries(
    current: String?,
    recent: List<String>,
    pinned: List<String>,
    limit: Int = MAX_RECENT_CLIPS,
): List<ClipboardEntry> {
    if (limit <= 0) return emptyList()
    val cleanPinned = mergeRecentClipLists(emptyList(), pinned, MAX_PINNED_CLIPS)
    val recentDisplay = clipboardItemsForDisplay(current, recent, limit)
    val fullCurrent = current?.takeIf { it.isNotBlank() }
    val storedCurrent = fullCurrent?.let { truncateRecentClip(it, MAX_CLIP_CHARS) }
    val pinnedEntries = cleanPinned.map { item ->
        val displayText = if (fullCurrent != null && item == storedCurrent) fullCurrent else item
        ClipboardEntry(text = displayText, pinned = true)
    }
    val pinnedKeys = cleanPinned.toHashSet()
    val recentEntries = recentDisplay.mapNotNull { item ->
        val key = truncateRecentClip(item, MAX_CLIP_CHARS)
        if (key in pinnedKeys || item in pinnedKeys) null else ClipboardEntry(text = item, pinned = false)
    }
    return pinnedEntries + recentEntries
}

private fun normalizeRecentClip(text: String): String? {
    if (text.isBlank()) return null
    return truncateRecentClip(text, MAX_CLIP_CHARS).takeIf { it.isNotBlank() }
}

private fun truncateRecentClip(text: String, maxChars: Int): String {
    if (maxChars <= 0) return ""
    if (text.length <= maxChars) {
        return if (text.lastOrNull()?.isHighSurrogate() == true) text.dropLast(1) else text
    }

    var end = maxChars
    if (text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
    return text.substring(0, graphemeSafeEnd(text, end))
}

private fun graphemeSafeEnd(text: String, target: Int): Int {
    val boundedEnd = target + Character.charCount(text.codePointAt(target))
    val matcher = GRAPHEME_CLUSTER.toPattern().matcher(text)
    matcher.region(0, boundedEnd)
    while (matcher.find()) {
        if (matcher.end() > target) return matcher.start()
    }
    return target
}

fun clipboardPreviewText(text: String, maxCharacters: Int = 5): String {
    val builder = StringBuilder(text.length)
    var pendingSpace = false
    for (char in text) {
        if (char.isWhitespace()) {
            pendingSpace = builder.isNotEmpty()
        } else {
            if (pendingSpace) builder.append(' ')
            builder.append(char)
            pendingSpace = false
        }
    }
    if (builder.isEmpty()) return ""
    if (maxCharacters <= 0) return "…"

    val preview = StringBuilder()
    var count = 0
    for (match in GRAPHEME_CLUSTER.findAll(builder)) {
        if (count == maxCharacters) return preview.append('…').toString()
        preview.append(match.value)
        count++
    }
    return preview.toString()
}

fun encodeRecentClips(clips: List<String>, limit: Int = MAX_RECENT_CLIPS): String {
    val array = JSONArray()
    mergeRecentClips(null, clips, limit).forEach { array.put(it) }
    return array.toString()
}

fun decodeRecentClips(raw: String?, limit: Int = MAX_RECENT_CLIPS): List<String> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        val array = JSONArray(raw)
        val items = ArrayList<String>(minOf(array.length(), limit.coerceAtLeast(0)))
        for (index in 0 until array.length()) {
            val value = array.optString(index, "")
            if (value.isNotBlank()) items += value
        }
        mergeRecentClips(null, items, limit)
    }.getOrDefault(emptyList())
}

fun Preferences.themeMode(): ThemeMode =
    runCatching { ThemeMode.valueOf(this[PrefKeys.theme] ?: ThemeMode.System.name) }.getOrDefault(ThemeMode.System)

class Secrets(context: Context) {
    private val app = context.applicationContext
    private val plain = app.getSharedPreferences("keyboard_secret_plain", Context.MODE_PRIVATE)
    private val encryptedResult = runCatching { openEncrypted(app) }
    private val encrypted = encryptedResult.getOrNull()

    fun get(): String? = read(KEY)

    fun set(value: String) {
        write(KEY, value.trim())
    }

    fun githubToken(): String? = read(GITHUB)

    fun setGithubToken(value: String) {
        write(GITHUB, value.trim())
    }

    private fun read(name: String): String? {
        val encryptedPreferences = encrypted ?: return readPlain(name)
        val fromEncrypted = runCatching {
            encryptedPreferences.getString(name, null)?.trim()?.takeIf { it.isNotEmpty() }
        }.getOrNull()
        if (fromEncrypted != null) {
            runCatching { removePlainIfPresent(name) }
            return fromEncrypted
        }

        val fromPlain = readPlain(name) ?: return null
        runCatching {
            putEncrypted(encryptedPreferences, name, fromPlain)
            removePlainIfPresent(name)
        }
        return fromPlain
    }

    private fun write(name: String, value: String) {
        val encryptedPreferences = encrypted ?: throw IllegalStateException(
            "Encrypted secret storage is unavailable",
            encryptedResult.exceptionOrNull(),
        )
        if (value.isEmpty()) {
            removePlainIfPresent(name)
            commitOrThrow(
                encryptedPreferences.edit().remove(name),
                "Unable to clear the encrypted secret",
            )
            return
        }

        putEncrypted(encryptedPreferences, name, value)
        removePlainIfPresent(name)
    }

    private fun readPlain(name: String): String? = runCatching {
        plain.getString(name, null)?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun putEncrypted(preferences: android.content.SharedPreferences, name: String, value: String) {
        commitOrThrow(
            preferences.edit().putString(name, value),
            "Unable to write the encrypted secret",
        )
    }

    private fun removePlainIfPresent(name: String) {
        if (runCatching { !plain.contains(name) }.getOrDefault(false)) return
        commitOrThrow(
            plain.edit().remove(name),
            "Unable to remove the legacy plaintext secret",
        )
    }

    private fun commitOrThrow(editor: android.content.SharedPreferences.Editor, message: String) {
        val committed = try {
            editor.commit()
        } catch (error: Exception) {
            throw IllegalStateException(message, error)
        }
        if (!committed) throw IllegalStateException(message)
    }

    private fun openEncrypted(context: Context) = EncryptedSharedPreferences.create(
        context,
        "keyboard_secret",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private companion object {
        const val KEY = "api_key"
        const val GITHUB = "github_token"
    }
}

class SqliteLexicon(private val db: SQLiteDatabase) : Lexicon {
    override fun exact(letters: String, limit: Int): List<LexRow> = query(
        "SELECT text, syllables, letters, freq FROM entry WHERE letters = ? ORDER BY freq DESC LIMIT ?",
        arrayOf(letters, limit.toString()),
    )

    override fun prefixed(letters: String, limit: Int): List<LexRow> {
        if (letters.isEmpty()) return emptyList()
        return query(
            "SELECT text, syllables, letters, freq FROM entry WHERE letters >= ? AND letters < ? AND letters != ? ORDER BY freq DESC LIMIT ?",
            arrayOf(letters, prefixEnd(letters), letters, limit.toString()),
        )
    }

    override fun charsStarting(syllablePrefix: String, limit: Int): List<LexRow> = query(
        "SELECT text, syllables, letters, freq FROM entry WHERE nsy = 1 AND syllables >= ? AND syllables < ? ORDER BY freq DESC LIMIT ?",
        arrayOf(syllablePrefix, prefixEnd(syllablePrefix), limit.toString()),
    )

    override fun spelling(syllables: String, limit: Int): List<LexRow> {
        if (syllables.isBlank()) return emptyList()
        val count = syllables.count { it == ' ' } + 1
        return query(
            "SELECT text, syllables, letters, freq FROM entry WHERE nsy = ? AND syllables = ? ORDER BY freq DESC LIMIT ?",
            arrayOf(count.toString(), syllables, limit.toString()),
        )
    }

    override fun spellingStarting(prefix: String, nsy: Int, limit: Int): List<LexRow> {
        if (prefix.isBlank() || nsy < 1) return emptyList()
        return query(
            "SELECT text, syllables, letters, freq FROM entry WHERE nsy = ? AND syllables >= ? AND syllables < ? AND syllables LIKE ? ORDER BY freq DESC LIMIT ?",
            arrayOf(nsy.toString(), prefix, prefixEnd(prefix), "$prefix%", limit.toString()),
        )
    }

    override fun byInitials(prefix: String, limit: Int, exactOnly: Boolean): List<LexRow> {
        if (prefix.isEmpty()) return emptyList()
        val exact = query(
            "SELECT text, syllables, letters, freq FROM entry WHERE initials = ? ORDER BY freq DESC LIMIT ?",
            arrayOf(prefix, limit.toString()),
        ).distinctBy { it.text }
        if (exactOnly || exact.size >= limit) return exact.take(limit)
        val longer = query(
            "SELECT text, syllables, letters, freq FROM entry WHERE initials > ? AND initials < ? ORDER BY freq DESC LIMIT ?",
            arrayOf(prefix, prefixEnd(prefix), limit.toString()),
        )
        return (exact + longer).distinctBy { it.text }.take(limit)
    }

    override fun phrasesStarting(text: String, limit: Int): List<LexRow> {
        if (text.isEmpty()) return emptyList()
        return query(
            "SELECT text, syllables, letters, freq FROM entry WHERE text >= ? AND text < ? AND text != ? ORDER BY freq DESC LIMIT ?",
            arrayOf(text, textPrefixEnd(text), text, limit.toString()),
        )
    }

    override fun phraseFreqs(texts: Collection<String>): Map<String, Int> {
        val distinct = texts.filter { it.isNotEmpty() }.distinct()
        if (distinct.isEmpty()) return emptyMap()
        val out = HashMap<String, Int>(distinct.size)
        for (chunk in distinct.chunked(64)) {
            val placeholders = chunk.joinToString(",") { "?" }
            db.rawQuery(
                "SELECT text, MAX(freq) FROM entry WHERE text IN ($placeholders) GROUP BY text",
                chunk.toTypedArray(),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    out[cursor.getString(0)] = cursor.getInt(1)
                }
            }
        }
        return out
    }

    private fun query(sql: String, args: Array<String>): List<LexRow> {
        val out = ArrayList<LexRow>()
        db.rawQuery(sql, args).use { cursor ->
            while (cursor.moveToNext()) {
                out += LexRow(
                    text = cursor.getString(0),
                    syllables = cursor.getString(1),
                    letters = cursor.getString(2),
                    freq = cursor.getInt(3),
                )
            }
        }
        return out
    }
}

class UserStore(context: Context) {
    private val db = SQLiteDatabase.openOrCreateDatabase(File(context.filesDir, "user.db"), null)

    init {
        db.execSQL("CREATE TABLE IF NOT EXISTS zh (text TEXT, syllables TEXT, letters TEXT, freq INTEGER, PRIMARY KEY(text, letters))")
        db.execSQL("CREATE TABLE IF NOT EXISTS en (word TEXT PRIMARY KEY, freq INTEGER)")
        db.execSQL("CREATE TABLE IF NOT EXISTS zh_bigram (prev TEXT, next TEXT, count INTEGER, PRIMARY KEY(prev, next))")
        db.execSQL("CREATE TABLE IF NOT EXISTS en_bigram (prev TEXT, next TEXT, count INTEGER, PRIMARY KEY(prev, next))")
    }

    fun loadZh(): List<LexRow> {
        val out = ArrayList<LexRow>()
        db.rawQuery("SELECT text, syllables, letters, freq FROM zh", null).use { cursor ->
            while (cursor.moveToNext()) {
                out += LexRow(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getInt(3))
            }
        }
        return out
    }

    fun saveZh(row: LexRow) {
        db.execSQL(
            "INSERT OR REPLACE INTO zh (text, syllables, letters, freq) VALUES (?, ?, ?, ?)",
            arrayOf<Any>(row.text, row.syllables, row.letters, row.freq),
        )
    }

    fun deleteZh(text: String) {
        db.execSQL("DELETE FROM zh WHERE text = ?", arrayOf<Any>(text))
        db.execSQL("DELETE FROM zh_bigram WHERE prev = ? OR next = ?", arrayOf<Any>(text, text))
    }

    fun loadZhBigrams(): List<Triple<String, String, Int>> {
        val out = ArrayList<Triple<String, String, Int>>()
        db.rawQuery("SELECT prev, next, count FROM zh_bigram", null).use { cursor ->
            while (cursor.moveToNext()) {
                out += Triple(cursor.getString(0), cursor.getString(1), cursor.getInt(2))
            }
        }
        return out
    }

    fun saveZhBigram(prev: String, next: String, count: Int) {
        db.execSQL(
            "INSERT OR REPLACE INTO zh_bigram (prev, next, count) VALUES (?, ?, ?)",
            arrayOf<Any>(prev, next, count),
        )
    }

    fun loadEn(): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>()
        db.rawQuery("SELECT word, freq FROM en", null).use { cursor ->
            while (cursor.moveToNext()) out += cursor.getString(0) to cursor.getInt(1)
        }
        return out
    }

    fun saveEn(word: String, freq: Int) {
        db.execSQL("INSERT OR REPLACE INTO en (word, freq) VALUES (?, ?)", arrayOf<Any>(word, freq))
    }

    fun deleteEn(word: String) {
        db.execSQL("DELETE FROM en WHERE word = ?", arrayOf<Any>(word))
        db.execSQL("DELETE FROM en_bigram WHERE prev = ? OR next = ?", arrayOf<Any>(word, word))
    }

    fun loadEnBigrams(): List<Triple<String, String, Int>> {
        val out = ArrayList<Triple<String, String, Int>>()
        db.rawQuery("SELECT prev, next, count FROM en_bigram", null).use { cursor ->
            while (cursor.moveToNext()) {
                out += Triple(cursor.getString(0), cursor.getString(1), cursor.getInt(2))
            }
        }
        return out
    }

    fun saveEnBigram(prev: String, next: String, count: Int) {
        db.execSQL(
            "INSERT OR REPLACE INTO en_bigram (prev, next, count) VALUES (?, ?, ?)",
            arrayOf<Any>(prev, next, count),
        )
    }

    fun clear() {
        db.execSQL("DELETE FROM zh")
        db.execSQL("DELETE FROM en")
        db.execSQL("DELETE FROM zh_bigram")
        db.execSQL("DELETE FROM en_bigram")
    }
}

fun installLexicon(context: Context): File? {
    return try {
        val stamp = context.assets.open("dict/SOURCES.txt").bufferedReader().use { it.readText() }
        val marker = File(context.filesDir, "lexicon.stamp")
        val out = File(context.filesDir, "lexicon.db")
        if (!out.exists() || !marker.exists() || marker.readText() != stamp) {
            context.assets.open("dict/lexicon.db").use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
            marker.writeText(stamp)
        }
        out
    } catch (_: Exception) {
        null
    }
}

private fun prefixEnd(prefix: String): String {
    val chars = prefix.toCharArray()
    var index = chars.lastIndex
    while (index >= 0) {
        if (chars[index] != 'z') {
            chars[index] = chars[index] + 1
            return String(chars, 0, index + 1)
        }
        index--
    }
    return "$prefix{"
}

private fun textPrefixEnd(prefix: String): String {
    val chars = prefix.toCharArray()
    if (chars.isEmpty()) return "\uFFFF"
    if (chars[chars.lastIndex] == '\uFFFF') return "$prefix\uFFFF"
    chars[chars.lastIndex] = chars[chars.lastIndex] + 1
    return String(chars)
}
