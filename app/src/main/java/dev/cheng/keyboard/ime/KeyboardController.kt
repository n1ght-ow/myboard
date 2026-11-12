package dev.cheng.keyboard.ime

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.SystemClock
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import dev.cheng.keyboard.ai.ASK_PROMPT
import dev.cheng.keyboard.ai.AiResult
import dev.cheng.keyboard.ai.DeepSeekClient
import dev.cheng.keyboard.ai.MODEL_FLASH
import dev.cheng.keyboard.ai.MODEL_PRO
import dev.cheng.keyboard.ai.POLISH_PROMPT
import dev.cheng.keyboard.ai.TRANSLATE_PROMPT
import dev.cheng.keyboard.data.PrefKeys
import dev.cheng.keyboard.data.Secrets
import dev.cheng.keyboard.data.SqliteLexicon
import dev.cheng.keyboard.data.UserStore
import dev.cheng.keyboard.data.installLexicon
import dev.cheng.keyboard.data.settingsStore
import dev.cheng.keyboard.data.themeMode
import dev.cheng.keyboard.engine.Cmd
import dev.cheng.keyboard.engine.EngineAct
import dev.cheng.keyboard.engine.EngineState
import dev.cheng.keyboard.engine.InputEngine
import dev.cheng.keyboard.engine.LatinDecoder
import dev.cheng.keyboard.engine.PinyinDecoder
import dev.cheng.keyboard.engine.applyAskDraft
import dev.cheng.keyboard.engine.extractTrailingCjk
import dev.cheng.keyboard.engine.stripWrapped
import dev.cheng.keyboard.ime.gesture.HapticKind
import dev.cheng.keyboard.ime.gesture.KeyGesture
import dev.cheng.keyboard.model.Choice
import dev.cheng.keyboard.model.KeyModel
import dev.cheng.keyboard.model.KeyboardPage
import dev.cheng.keyboard.model.Language
import dev.cheng.keyboard.model.Pending
import dev.cheng.keyboard.model.ShiftState
import dev.cheng.keyboard.model.ThemeMode
import dev.cheng.keyboard.settings.SettingsActivity
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream

class KeyboardController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val connection: () -> InputConnection?,
    private val haptic: (HapticKind) -> Unit,
    private val hide: () -> Unit,
) {
    private val latin = LatinDecoder()
    @Volatile
    private var pinyin = PinyinDecoder(emptySet()).also { it.latin = latin }
    private val engine = InputEngine(latin)
    private val bridge = EditorBridge(connection)
    private val secrets = Secrets(context)
    private val client = DeepSeekClient()
    private val users = UserStore(context)
    private val db = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "keyboard-dict").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val gesture = KeyGesture(slopPx = 16f, caseSlopPx = 24f, swipePx = 36f)
    private val snapshot = androidx.compose.runtime.mutableStateOf(UiState())
    private var engineState = EngineState()
    private var preferredLanguage = Language.Chinese
    private var activeInfo: EditorInfo? = null
    private var sawComposingSpan = false
    private var enter = dev.cheng.keyboard.model.EnterKind.Newline
    private var fuzzy = false
    private var askModel = MODEL_FLASH
    private var userEpoch = 0L
    @Volatile
    private var token = 0
    private var alarmJob: Job? = null
    private var clipExpireJob: Job? = null
    private var noteClearJob: Job? = null
    private var source: FieldSource? = null
    private var candidateRaw = ""
    private var aiToken = 0
    private val clipboard = context.getSystemService(ClipboardManager::class.java)
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { onClipboardChanged() }

    val state: UiState get() = snapshot.value

    init {
        runCatching { clipboard?.addPrimaryClipChangedListener(clipListener) }
        scope.launch {
            context.settingsStore.data.collectLatest { prefs ->
                fuzzy = prefs[PrefKeys.fuzzy] == true
                askModel = prefs[PrefKeys.askModel] ?: MODEL_FLASH
                val epoch = prefs[PrefKeys.userEpoch] ?: 0L
                if (epoch != userEpoch) {
                    userEpoch = epoch
                    pinyin.clearUser()
                    latin.clearUser()
                }
                update {
                    it.copy(
                        theme = prefs.themeMode(),
                        heightScale = (prefs[PrefKeys.height] ?: 1f).coerceIn(0.8f, 1.25f),
                        haptic = prefs[PrefKeys.haptic] != false,
                        fuzzy = fuzzy,
                    )
                }
                if (engineState.raw.isNotEmpty() && engineState.language == Language.Chinese) requestPinyin(engineState.raw)
            }
        }
        scope.launch(db) {
            loadDictionaries()
            withContext(Dispatchers.Main) {
                if (engineState.raw.isNotEmpty()) requestPinyin(engineState.raw)
            }
        }
    }

    fun configure(density: Float) {
        gesture.slopPx = 16f * density
        gesture.caseSlopPx = 34f * density
        gesture.swipePx = 36f * density
    }

    fun close() {
        alarmJob?.cancel()
        clipExpireJob?.cancel()
        noteClearJob?.cancel()
        runCatching { clipboard?.removePrimaryClipChangedListener(clipListener) }
        client.cancel()
        db.close()
    }

    fun startInput(info: EditorInfo, restarting: Boolean) {
        activeInfo = info
        val traits = fieldTraitsOf(info)
        enter = enterKindOf(info)
        if (!restarting) {
            val targetLanguage = if (traits.uriOrEmail) Language.English else preferredLanguage
            val targetPage = if (traits.numeric) KeyboardPage.Number else KeyboardPage.Letters
            val before = if (traits.password) "" else connection()?.getTextBeforeCursor(64, 0)?.toString().orEmpty()
            val shift = if (targetLanguage == Language.English) {
                resolveAutoShift(info, before)
            } else {
                ShiftState.Off
            }
            engineState = engineState.copy(
                language = targetLanguage,
                page = targetPage,
                raw = "",
                preedit = "",
                candidates = emptyList(),
                associating = false,
                password = traits.password,
                noLearn = traits.noLearn,
                uriOrEmail = traits.uriOrEmail,
                shift = shift,
                partialText = "",
                partialSyllables = "",
                autoSpace = false,
                lastEnglishWord = null,
                undoAutocorrect = null,
                verbatimFirst = false,
                lastChineseContext = if (traits.password) "" else extractTrailingCjk(before),
                touchHints = emptyList(),
            )
            bridge.reset()
            sawComposingSpan = false
            candidateRaw = ""
        } else {
            engineState = engineState.copy(
                password = traits.password,
                noLearn = traits.noLearn,
                uriOrEmail = traits.uriOrEmail,
            )
        }
        gesture.cancel()
        update { it.copy(pressedId = null, popup = null, expanded = false, enter = enter) }
        publish()
    }

    fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        if (state.overlay == Overlay.Ask || state.overlay == Overlay.NeedKey) return
        if (candidatesStart >= 0 && candidatesEnd > candidatesStart) {
            sawComposingSpan = true
            return
        }
        val selectionMoved = newSelStart != oldSelStart || newSelEnd != oldSelEnd
        if (engineState.raw.isNotEmpty() && sawComposingSpan && candidatesStart < 0 && candidatesEnd < 0 && selectionMoved) {
            token++
            sawComposingSpan = false
            candidateRaw = ""
            bridge.reset()
            val before = if (engineState.password) "" else connection()?.getTextBeforeCursor(64, 0)?.toString().orEmpty()
            val nextShift = if (engineState.language == Language.English && engineState.shift != ShiftState.Caps) {
                activeInfo?.let { resolveAutoShift(it, before) } ?: ShiftState.Off
            } else {
                engineState.shift
            }
            engineState = engineState.copy(
                raw = "",
                preedit = "",
                candidates = emptyList(),
                associating = false,
                partialText = "",
                partialSyllables = "",
                autoSpace = false,
                undoAutocorrect = null,
                verbatimFirst = false,
                shift = nextShift,
                lastChineseContext = if (engineState.password) "" else extractTrailingCjk(before),
                touchHints = emptyList(),
            )
            publish()
            return
        }
        if (engineState.raw.isEmpty() && selectionMoved && !engineState.password) {
            val before = connection()?.getTextBeforeCursor(64, 0)?.toString().orEmpty()
            val nextShift = if (engineState.language == Language.English && engineState.shift != ShiftState.Caps) {
                activeInfo?.let { resolveAutoShift(it, before) } ?: engineState.shift
            } else {
                engineState.shift
            }
            val nextZhCtx = extractTrailingCjk(before)
            if (nextShift != engineState.shift || nextZhCtx != engineState.lastChineseContext || engineState.undoAutocorrect != null) {
                engineState = engineState.copy(
                    shift = nextShift,
                    lastChineseContext = nextZhCtx,
                    autoSpace = false,
                    undoAutocorrect = null,
                    associating = false,
                    candidates = if (engineState.associating) emptyList() else engineState.candidates,
                )
                publish()
            }
        }
    }

    fun onHidden() {
        aiToken++
        client.cancel()
        gesture.cancel()
        alarmJob?.cancel()
        source = null
        update {
            it.copy(
                pressedId = null,
                popup = null,
                menu = false,
                expanded = false,
                overlay = Overlay.None,
                askDraft = "",
                askAnswer = "",
                askReason = "",
                askAttached = null,
                resultText = "",
                resultSource = "",
                busy = false,
                note = null,
            )
        }
    }

    fun onDown(key: KeyModel, x: Float, y: Float, time: Long, touchNeighbors: String = "") {
        val frame = gesture.down(key, x, y, time, touchNeighbors)
        applyFrame(frame)
        schedule(time)
    }

    fun onMove(x: Float, y: Float, time: Long, hit: KeyModel?) {
        applyFrame(gesture.move(x, y, time, hit))
        schedule(time)
    }

    fun onUp(time: Long) {
        alarmJob?.cancel()
        applyFrame(gesture.up())
    }

    fun onCancel() {
        alarmJob?.cancel()
        applyFrame(gesture.cancel())
    }

    fun pick(index: Int) = dispatch(EngineAct.Pick(index))

    fun forgetCandidate(index: Int) {
        val item = state.engine.candidates.getOrNull(index) ?: return
        val text = item.text
        var removed = false
        if (engineState.language == Language.English || text.all { it in 'a'..'z' || it in 'A'..'Z' || it == '\'' }) {
            val word = text.lowercase()
            removed = latin.forget(word)
            scope.launch(db) { users.deleteEn(word) }
            if (engineState.language == Language.English && engineState.raw.isNotEmpty()) {
                engineState = engineState.copy(
                    candidates = latin.suggest(
                        engineState.raw.lowercase(),
                        verbatimFirst = engineState.verbatimFirst,
                        prevWord = engineState.lastEnglishWord,
                        touchHints = engineState.touchHints,
                    ),
                )
                publish()
            }
        } else {
            removed = pinyin.forget(text)
            scope.launch(db) { users.deleteZh(text) }
            if (engineState.raw.isNotEmpty()) {
                candidateRaw = ""
                requestPinyin(engineState.raw)
            } else if (engineState.associating) {
                engineState = engineState.copy(candidates = engineState.candidates.filter { it.text != text })
                publish()
            }
        }
        if (removed && state.haptic) haptic(HapticKind.Delete)
        if (removed) flashNote("已移除「$text」")
    }

    fun toggleExpanded() = update { it.copy(expanded = !it.expanded) }

    fun toggleMenu() {
        if (state.menu) {
            update { it.copy(menu = false, clip = null) }
        } else {
            val current = readClip()
            val merged = if (current != null) {
                (listOf(current) + state.recentClips.filter { it != current }).take(6)
            } else {
                state.recentClips
            }
            update { it.copy(menu = true, clip = current, recentClips = merged, overlay = Overlay.None) }
        }
    }

    fun paste() {
        val text = state.clip ?: return
        pasteText(text)
    }

    fun pasteText(text: String) {
        if (text.isEmpty()) return
        clipExpireJob?.cancel()
        dispatch(EngineAct.Direct(text))
        update { it.copy(menu = false, freshClip = null) }
    }

    fun dismissFreshClip() {
        clipExpireJob?.cancel()
        update { it.copy(freshClip = null) }
    }

    fun pasteIntoDraft() {
        val text = readClip()?.trim().orEmpty()
        if (text.isEmpty()) {
            update { it.copy(note = "剪贴板是空的") }
            return
        }
        when (state.overlay) {
            Overlay.NeedKey -> update { it.copy(keyDraft = text, note = null) }
            Overlay.Ask -> update { it.copy(askDraft = it.askDraft + text, note = null) }
            else -> Unit
        }
    }

    fun setHeight(scale: Float) {
        val value = scale.coerceIn(0.8f, 1.25f)
        update { it.copy(heightScale = value) }
        scope.launch { context.settingsStore.edit { it[PrefKeys.height] = value } }
    }

    fun setTheme(mode: ThemeMode) {
        update { it.copy(theme = mode) }
        scope.launch { context.settingsStore.edit { it[PrefKeys.theme] = mode.name } }
    }

    fun setFuzzy(enabled: Boolean) {
        fuzzy = enabled
        update { it.copy(fuzzy = enabled) }
        scope.launch { context.settingsStore.edit { it[PrefKeys.fuzzy] = enabled } }
    }

    fun setHaptic(enabled: Boolean) {
        update { it.copy(haptic = enabled) }
        scope.launch { context.settingsStore.edit { it[PrefKeys.haptic] = enabled } }
    }

    fun setAskModel(model: String) {
        askModel = model
        scope.launch { context.settingsStore.edit { it[PrefKeys.askModel] = model } }
    }

    fun openSettings() {
        context.startActivity(Intent(context, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun dismiss() = hide()

    fun setLanguage(language: Language) {
        preferredLanguage = language
        if (engineState.language != language) dispatch(EngineAct.Language)
    }

    fun closeSymbols() = dispatch(EngineAct.Page(KeyboardPage.Letters))

    fun selectSymbolCategory(index: Int) = update { it.copy(symbolIndex = index, symbolRecent = false) }

    fun cycleSymbolGroups() {
        val count = dev.cheng.keyboard.ime.symbol.symbolPages.size
        update { it.copy(symbolGroup = (it.symbolGroup + 1) % count, symbolIndex = 0, symbolRecent = false) }
    }

    fun toggleSymbolLock() = update { it.copy(symbolLocked = !it.symbolLocked) }

    fun toggleSymbolRecent() = update { it.copy(symbolRecent = !it.symbolRecent) }

    fun insertSymbol(text: String) {
        val locked = state.symbolLocked
        val recent = (listOf(text) + state.recentSymbols.filter { it != text }).take(40)
        update { it.copy(recentSymbols = recent) }
        dispatch(EngineAct.Direct(text))
        if (!locked) dispatch(EngineAct.Page(KeyboardPage.Letters))
    }

    fun ask() {
        if (engineState.password) return
        if (secrets.get().isNullOrBlank()) {
            update { it.copy(overlay = Overlay.NeedKey, menu = false, keyDraft = "") }
            return
        }
        val input = connection()
        if (engineState.raw.isNotEmpty()) {
            input?.setComposingText("", 1)
            input?.finishComposingText()
            engineState = engineState.copy(raw = "", preedit = "", candidates = emptyList(), associating = false)
        }
        val field = input?.let(::readField)
        update {
            it.copy(
                overlay = Overlay.Ask,
                menu = false,
                expanded = false,
                askDraft = "",
                askAnswer = "",
                askReason = "",
                askAttached = field?.text?.takeIf { text -> text.isNotBlank() },
                note = null,
            )
        }
        publish()
    }

    fun translate() = transform(ResultKind.Translate, TRANSLATE_PROMPT, 0.2)
    fun polish() = transform(ResultKind.Polish, POLISH_PROMPT, 0.3)

    fun closeOverlay() = finishOverlay(null)

    private fun finishOverlay(note: String?) {
        aiToken++
        client.cancel()
        source = null
        if (state.overlay == Overlay.Ask) {
            engineState = engineState.copy(raw = "", preedit = "", candidates = emptyList(), associating = false)
        }
        update {
            it.copy(
                overlay = Overlay.None,
                busy = false,
                askAnswer = "",
                askReason = "",
                askDraft = "",
                resultText = "",
                resultSource = "",
                note = note,
                engine = engineState,
            )
        }
    }

    fun toggleThinking() = update { it.copy(askThinking = !it.askThinking) }

    fun clearAttach() = update { it.copy(askAttached = null) }

    fun saveKey() {
        val value = state.keyDraft.trim()
        if (value.isEmpty()) return
        secrets.set(value)
        update { it.copy(overlay = Overlay.None, keyDraft = "") }
    }

    fun typeKey(ch: Char) = update { it.copy(keyDraft = it.keyDraft + ch) }

    fun sendAsk() {
        if (state.busy) return
        if (state.overlay == Overlay.Ask && engineState.raw.isNotEmpty()) {
            dispatch(EngineAct.Space)
        }
        val draft = state.askDraft.trim()
        val attached = state.askAttached
        if (draft.isEmpty() && attached.isNullOrBlank()) {
            update { it.copy(note = "先输入问题") }
            return
        }
        val key = secrets.get()
        if (key.isNullOrBlank()) {
            update { it.copy(overlay = Overlay.NeedKey, note = null) }
            return
        }
        val user = when {
            draft.isEmpty() -> "请根据下面的文字给出可以直接使用的结果：\n$attached"
            attached.isNullOrBlank() -> draft
            else -> "$draft\n\n当前文字：\n$attached"
        }
        update { it.copy(busy = true, askAnswer = "", askReason = "", note = null) }
        launchAi(
            key = key,
            model = askModel,
            system = ASK_PROMPT,
            user = user,
            temperature = 0.7,
            maxTokens = 4096,
            thinking = state.askThinking,
            onStream = { reason, answer ->
                update { it.copy(askReason = reason, askAnswer = stripWrapped(answer)) }
            },
            onOk = { text ->
                update { it.copy(busy = false, askAnswer = text, note = null) }
            },
        )
    }

    fun insertResult() {
        val text = currentOutput() ?: return
        val input = connection() ?: return
        input.finishComposingText()
        engineState = engineState.copy(raw = "", preedit = "", candidates = emptyList(), associating = false)
        input.commitText(text, 1)
        publish()
        closeOverlay()
    }

    fun replaceResult() {
        val text = currentOutput() ?: return
        val input = connection() ?: return
        input.finishComposingText()
        val target = source
        if (target != null && target.start >= 0) {
            val current = input.getExtractedText(
                android.view.inputmethod.ExtractedTextRequest().apply {
                    hintMaxChars = 10000
                    hintMaxLines = 200
                },
                0,
            )?.text?.toString()
            if (current != null && target.end <= current.length && current.substring(target.start, target.end) == target.text) {
                input.setSelection(target.start, target.end)
                input.commitText(text, 1)
                closeOverlay()
                return
            }
        }
        input.commitText(text, 1)
        finishOverlay("原文有变化，已改为插入")
    }

    fun copyResult() {
        val text = currentOutput() ?: return
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("text", text))
        update { it.copy(note = "已复制") }
    }

    fun clearUserWords() {
        scope.launch(db) {
            users.clear()
            pinyin.clearUser()
            latin.clearUser()
        }
    }

    private fun transform(kind: ResultKind, prompt: String, temperature: Double) {
        if (engineState.password) return
        val key = secrets.get()
        if (key.isNullOrBlank()) {
            update { it.copy(overlay = Overlay.NeedKey, menu = false, keyDraft = "") }
            return
        }
        val input = connection()
        val field = input?.let(::readField)
        if (field == null) {
            update { it.copy(overlay = Overlay.Result(kind), resultText = "", note = "读不到输入框内容", busy = false, menu = false) }
            return
        }
        if (field.text.isBlank()) {
            update { it.copy(overlay = Overlay.Result(kind), resultText = "", note = "没有可处理的内容", busy = false, menu = false) }
            return
        }
        source = field
        update {
            it.copy(
                overlay = Overlay.Result(kind),
                resultText = "",
                resultSource = field.text.take(80),
                note = null,
                busy = true,
                menu = false,
                expanded = false,
            )
        }
        launchAi(
            key = key,
            model = MODEL_FLASH,
            system = prompt,
            user = field.text,
            temperature = temperature,
            maxTokens = 2048,
            thinking = false,
            onStream = { _, content ->
                if (content.isNotBlank()) update { it.copy(resultText = stripWrapped(content)) }
            },
            onOk = { text ->
                update { it.copy(busy = false, resultText = text, note = null) }
            },
        )
    }

    private fun launchAi(
        key: String,
        model: String,
        system: String,
        user: String,
        temperature: Double,
        maxTokens: Int,
        thinking: Boolean,
        onStream: (String, String) -> Unit = { _, _ -> },
        onOk: (String) -> Unit,
    ) {
        val ticket = ++aiToken
        scope.launch(Dispatchers.IO) {
            var lastEmit = 0L
            val result = client.stream(key, model, system, user, temperature, maxTokens, thinking) { reason, content ->
                val now = SystemClock.uptimeMillis()
                if (now - lastEmit >= 35L) {
                    lastEmit = now
                    scope.launch(Dispatchers.Main) {
                        if (ticket == aiToken) onStream(reason, content)
                    }
                }
            }
            withContext(Dispatchers.Main) {
                if (ticket != aiToken) return@withContext
                when (result) {
                    is AiResult.Ok -> onOk(result.text)
                    is AiResult.Err -> update {
                        it.copy(busy = false, note = result.message.ifBlank { null })
                    }
                }
            }
        }
    }

    private fun currentOutput(): String? {
        val raw = when (state.overlay) {
            is Overlay.Result -> state.resultText
            Overlay.Ask -> state.askAnswer
            else -> return null
        }
        val text = stripWrapped(raw)
        return text.ifBlank { null }
    }

    private fun applyFrame(frame: dev.cheng.keyboard.ime.gesture.GestureFrame) {
        if (state.haptic && frame.haptic != null) haptic(frame.haptic)
        update { it.copy(pressedId = frame.pressedId, popup = frame.popup) }
        frame.actions.forEach(::dispatch)
    }

    private fun schedule(now: Long) {
        alarmJob?.cancel()
        val at = gesture.nextAlarm(now) ?: return
        alarmJob = scope.launch {
            kotlinx.coroutines.delay((at - android.os.SystemClock.uptimeMillis()).coerceAtLeast(0))
            val time = android.os.SystemClock.uptimeMillis()
            applyFrame(gesture.alarm(time))
            schedule(time)
        }
    }

    private fun dispatch(act: EngineAct) {
        if (interceptAsk(act)) return
        if (needsChineseNow(act)) refreshChinese()
        val step = engine.reduce(engineState, act, enter)
        engineState = step.state
        if (act == EngineAct.Language && !engineState.uriOrEmail) {
            preferredLanguage = engineState.language
        }
        val asking = state.overlay == Overlay.Ask
        if (!asking) {
            step.cmds.filterIsInstance<Cmd.LearnZh>().forEach { cmd ->
                val letters = cmd.syllables.replace("'", "").replace(" ", "")
                val freq = pinyin.learn(cmd.text, cmd.syllables, cmd.prevContext)
                val bigrams = pinyin.bigramEntriesFor(cmd.prevContext, cmd.text)
                if (freq > 0) {
                    val row = dev.cheng.keyboard.model.LexRow(cmd.text, cmd.syllables.replace("'", " "), letters, freq)
                    scope.launch(db) {
                        users.saveZh(row)
                        for ((prev, next, count) in bigrams) users.saveZhBigram(prev, next, count)
                    }
                }
            }
            step.cmds.filterIsInstance<Cmd.LearnZhBigram>().forEach { cmd ->
                val updated = pinyin.learnBigram(cmd.prev, cmd.next)
                if (updated.isNotEmpty()) {
                    scope.launch(db) {
                        for ((prev, next, count) in updated) users.saveZhBigram(prev, next, count)
                    }
                }
            }
            step.cmds.filterIsInstance<Cmd.LearnEn>().forEach { cmd ->
                latin.learn(cmd.word, cmd.prevWord)
                val prev = cmd.prevWord?.trim()?.lowercase().orEmpty()
                val bCount = if (prev.isNotEmpty()) latin.bigramCount(prev, cmd.word) else 0
                if (cmd.word.length >= 2 || bCount > 0) {
                    val freq = latin.userWords().firstOrNull { it.first == cmd.word }?.second ?: 20_000
                    scope.launch(db) {
                        if (cmd.word.length >= 2) users.saveEn(cmd.word, freq)
                        if (bCount > 0) users.saveEnBigram(prev, cmd.word.lowercase(), bCount)
                    }
                }
            }
            bridge.apply(step.cmds)
            if (engineState.raw.isEmpty()) sawComposingSpan = false
        } else {
            val draft = applyAskDraft(state.askDraft, step.cmds)
            update { it.copy(askDraft = draft) }
        }
        when (step.pending) {
            Pending.Pinyin -> requestPinyin(engineState.raw)
            Pending.Associate -> requestAssociate(step.associateFrom.orEmpty())
            Pending.None -> Unit
        }
        if (act !is EngineAct.Candidates) update { it.copy(expanded = false, menu = false) }
        publish()
    }

    private fun interceptAsk(act: EngineAct): Boolean {
        if (state.overlay == Overlay.NeedKey) return editKey(act)
        if (state.overlay != Overlay.Ask || act != EngineAct.Enter) return false
        if (engineState.raw.isNotEmpty()) dispatch(EngineAct.Space)
        sendAsk()
        return true
    }

    private fun editKey(act: EngineAct): Boolean {
        val edited = editDraft(state.keyDraft, act)
        if (edited != null) {
            update { it.copy(keyDraft = edited) }
            return true
        }
        return when (act) {
            EngineAct.Enter -> {
                saveKey()
                true
            }
            EngineAct.Shift -> true
            else -> false
        }
    }

    private fun editDraft(current: String, act: EngineAct): String? = when (act) {
        is EngineAct.Letter -> current + when (act.force) {
            Choice.Symbol -> act.symbol
            Choice.Upper -> act.ch.uppercaseChar().toString()
            Choice.Lower -> act.ch.lowercaseChar().toString()
            null -> if (engineState.shift == ShiftState.Off) act.ch.lowercaseChar().toString() else act.ch.uppercaseChar().toString()
        }
        is EngineAct.Direct -> current + act.text
        EngineAct.Space -> "$current "
        EngineAct.Backspace -> current.dropLast(1)
        EngineAct.BackspaceWord -> ""
        else -> null
    }

    private fun needsChineseNow(act: EngineAct): Boolean {
        if (engineState.language != Language.Chinese || engineState.raw.isEmpty() || engineState.password) return false
        return when (act) {
            EngineAct.Space, EngineAct.Enter, is EngineAct.Direct, is EngineAct.Pick -> true
            is EngineAct.Letter -> act.force == Choice.Symbol || act.force == Choice.Upper ||
                (act.force == null && engineState.shift != ShiftState.Off)
            else -> false
        }
    }

    private fun refreshChinese() {
        if (candidateRaw == engineState.raw && engineState.candidates.isNotEmpty()) return
        val raw = engineState.raw
        val useFuzzy = fuzzy
        val ctx = engineState.lastChineseContext + engineState.partialText
        val hints = engineState.touchHints
        val decoded = runBlocking(db) { pinyin.decode(raw, useFuzzy, ctx, hints) }
        if (engineState.raw != raw) return
        candidateRaw = raw
        engineState = engineState.copy(preedit = decoded.preedit, candidates = decoded.candidates)
    }

    private fun requestPinyin(raw: String) {
        val current = ++token
        val useFuzzy = fuzzy
        val ctx = engineState.lastChineseContext + engineState.partialText
        val hints = engineState.touchHints
        scope.launch(db) {
            if (current != token) return@launch
            val decoded = pinyin.decode(raw, useFuzzy, ctx, hints)
            if (current != token) return@launch
            withContext(Dispatchers.Main) {
                if (current != token || engineState.raw != raw || engineState.language != Language.Chinese) return@withContext
                candidateRaw = raw
                dispatch(EngineAct.Candidates(decoded.preedit, decoded.candidates))
            }
        }
    }

    private fun requestAssociate(text: String) {
        val current = ++token
        scope.launch(db) {
            val items = pinyin.associate(text)
            withContext(Dispatchers.Main) {
                if (current != token || engineState.raw.isNotEmpty() || !engineState.associating) return@withContext
                dispatch(EngineAct.Candidates("", items))
            }
        }
    }

    private suspend fun loadDictionaries() {
        val syllables = runCatching {
            context.assets.open("dict/syllables.txt").bufferedReader().use { reader ->
                reader.readLines().filter { it.isNotBlank() }.toSet()
            }
        }.getOrDefault(emptySet())
        val fresh = if (syllables.isEmpty()) pinyin else PinyinDecoder(syllables)
        fresh.latin = latin
        val file = installLexicon(context)
        if (file != null) {
            val sqlite = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
            fresh.lexicon = SqliteLexicon(sqlite)
        }
        fresh.loadUser(users.loadZh())
        fresh.loadUserBigrams(users.loadZhBigrams())
        pinyin = fresh
        runCatching {
            val stream = runCatching { context.assets.open("dict/english.tsv") }
                .getOrElse { GZIPInputStream(context.assets.open("dict/english.tsv.gz")) }
            stream.bufferedReader().use { reader ->
                latin.load(reader.lineSequence().mapNotNull { line ->
                    val bits = line.split('\t')
                    val freq = bits.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
                    bits[0] to freq
                })
            }
        }
        latin.loadUser(users.loadEn())
        latin.loadUserBigrams(users.loadEnBigrams())
    }

    private fun onClipboardChanged() {
        val text = readClip(skipSensitive = true) ?: return
        clipExpireJob?.cancel()
        val merged = (listOf(text) + state.recentClips.filter { it != text }).take(6)
        update { it.copy(freshClip = text, recentClips = merged) }
        clipExpireJob = scope.launch {
            delay(45_000)
            update { it.copy(freshClip = null) }
        }
    }

    private fun flashNote(message: String) {
        noteClearJob?.cancel()
        update { it.copy(note = message) }
        noteClearJob = scope.launch {
            delay(1_800)
            update { if (it.note == message) it.copy(note = null) else it }
        }
    }

    private fun readClip(skipSensitive: Boolean = false): String? {
        val manager = clipboard ?: return null
        val clip = manager.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        if (skipSensitive && Build.VERSION.SDK_INT >= 33) {
            val sensitive = clip.description?.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) == true
            if (sensitive) return null
        }
        return clip.getItemAt(0).coerceToText(context)?.toString()?.takeIf { it.isNotBlank() }
    }

    private fun publish() {
        if (engineState.raw.isEmpty()) candidateRaw = ""
        update { prev ->
            val shownEngine = if (
                engineState.language == Language.Chinese &&
                engineState.raw.isNotEmpty() &&
                engineState.candidates.isEmpty() &&
                !engineState.associating &&
                candidateRaw.isNotEmpty() &&
                candidateRaw != engineState.raw &&
                prev.engine.candidates.isNotEmpty()
            ) {
                engineState.copy(candidates = prev.engine.candidates)
            } else {
                engineState
            }
            prev.copy(engine = shownEngine, enter = enter, fuzzy = fuzzy)
        }
    }

    private fun update(block: (UiState) -> UiState) {
        snapshot.value = block(snapshot.value)
    }
}
