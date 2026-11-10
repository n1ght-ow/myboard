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
import java.io.File

val Context.settingsStore by preferencesDataStore(name = "keyboard")

object PrefKeys {
    val theme = stringPreferencesKey("theme")
    val height = floatPreferencesKey("height")
    val haptic = booleanPreferencesKey("haptic")
    val fuzzy = booleanPreferencesKey("fuzzy")
    val askModel = stringPreferencesKey("ask_model")
    val userEpoch = longPreferencesKey("user_epoch")
}

fun Preferences.themeMode(): ThemeMode =
    runCatching { ThemeMode.valueOf(this[PrefKeys.theme] ?: ThemeMode.System.name) }.getOrDefault(ThemeMode.System)

class Secrets(context: Context) {
    private val app = context.applicationContext
    private val plain = app.getSharedPreferences("keyboard_secret_plain", Context.MODE_PRIVATE)
    private val encrypted = runCatching { openEncrypted(app) }.getOrNull()

    fun get(): String? {
        val fromEncrypted = encrypted?.getString(KEY, null)?.trim().orEmpty()
        if (fromEncrypted.isNotEmpty()) return fromEncrypted
        return plain.getString(KEY, null)?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun set(value: String) {
        val trimmed = value.trim()
        plain.edit().putString(KEY, trimmed).commit()
        encrypted?.edit()?.putString(KEY, trimmed)?.commit()
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

    override fun byInitials(prefix: String, limit: Int): List<LexRow> {
        if (prefix.isEmpty()) return emptyList()
        val exact = query(
            "SELECT text, syllables, letters, freq FROM entry WHERE initials = ? ORDER BY freq DESC LIMIT ?",
            arrayOf(prefix, limit.toString()),
        )
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

    fun clear() {
        db.execSQL("DELETE FROM zh")
        db.execSQL("DELETE FROM en")
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
    chars[chars.lastIndex] = chars[chars.lastIndex] + 1
    return String(chars)
}
