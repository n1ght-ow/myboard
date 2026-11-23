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
import android.widget.Toast
import dev.cheng.keyboard.ai.ADAPTIVE_POLISH_SYSTEM_PROMPT
import dev.cheng.keyboard.ai.ASK_PROMPT
import dev.cheng.keyboard.ai.AiResult
import dev.cheng.keyboard.ai.DeepSeekClient
import dev.cheng.keyboard.ai.MODEL_FLASH
import dev.cheng.keyboard.ai.MODEL_PRO
import dev.cheng.keyboard.ai.PolishTone
import dev.cheng.keyboard.ai.PolishUndo
import dev.cheng.keyboard.ai.TRANSLATE_PROMPT
import dev.cheng.keyboard.ai.buildPolishUserPrompt
import dev.cheng.keyboard.ai.parsePolishStream
import dev.cheng.keyboard.data.PrefKeys
import dev.cheng.keyboard.data.Secrets
import dev.cheng.keyboard.data.SqliteLexicon
import dev.cheng.keyboard.data.UserStore
import dev.cheng.keyboard.data.decodeRecentClips
import dev.cheng.keyboard.data.encodeRecentClips
import dev.cheng.keyboard.data.installLexicon
import dev.cheng.keyboard.data.mergeRecentClipLists
import dev.cheng.keyboard.data.mergeRecentClips
import dev.cheng.keyboard.data.settingsStore
import dev.cheng.keyboard.data.themeMode
import dev.cheng.keyboard.engine.Cmd
import dev.cheng.keyboard.engine.EngineAct
import dev.cheng.keyboard.engine.EngineState
import dev.cheng.keyboard.engine.InputEngine
import dev.cheng.keyboard.engine.LatinDecoder
import dev.cheng.keyboard.engine.PinyinDecoder
import dev.cheng.keyboard.engine.PoetryIndex
import dev.cheng.keyboard.engine.applyAskDraft
import dev.cheng.keyboard.engine.extractTrailingCjk
import dev.cheng.keyboard.engine.stripWrapped
import dev.cheng.keyboard.ime.gesture.HapticKind
import dev.cheng.keyboard.ime.assist.TypingAssistContext
import dev.cheng.keyboard.ime.assist.TypingAssistKind
import dev.cheng.keyboard.ime.assist.TypingSuggestion
import dev.cheng.keyboard.ime.assist.typingSuggestions
import dev.cheng.keyboard.ime.assist.isLiteralAddressContext
import dev.cheng.keyboard.ime.gesture.BackspaceHint
import dev.cheng.keyboard.ime.gesture.KeyGesture
import dev.cheng.keyboard.ime.gesture.TapPreview
import dev.cheng.keyboard.ime.layout.HitContext
import dev.cheng.keyboard.ime.layout.NoPriors
import dev.cheng.keyboard.ime.touch.TouchCalibrator
import dev.cheng.keyboard.ime.touch.matchesTouchSpelling
import dev.cheng.keyboard.model.Choice
import dev.cheng.keyboard.model.KeyKind
import dev.cheng.keyboard.model.KeyModel
import dev.cheng.keyboard.model.KeyboardPage
import dev.cheng.keyboard.model.Language
import dev.cheng.keyboard.model.Pending
import dev.cheng.keyboard.model.ShiftState
import dev.cheng.keyboard.model.ThemeMode
import dev.cheng.keyboard.model.TouchSample
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

internal fun englishAutoSpacePosition(snapshot: TypingEditorSnapshot?, word: String?): Int? {
    if (snapshot == null || word.isNullOrEmpty() || snapshot.selection.start < 0 ||
        snapshot.selection.start != snapshot.selection.end || !snapshot.before.endsWith("$word ", ignoreCase = true)
    ) return null
    return snapshot.selection.end
}

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
    private val calibrator = TouchCalibrator()
    private val snapshot = androidx.compose.runtime.mutableStateOf(UiState())
    private var engineState = EngineState()
    private var preferredLanguage = Language.Chinese
    private var activeInfo: EditorInfo? = null
    private var activeSession: EditorSessionKey? = null
    private var inputActive = false
    private var typingSnapshot: TypingEditorSnapshot? = null
    private var autoSpacePosition: Int? = null
    private var lastComposingSpan: EditorTextSpan? = null
    private var enter = dev.cheng.keyboard.model.EnterKind.Newline
    private var fuzzy = false
    private var askModel = MODEL_FLASH
    private var userEpoch = 0L
    private var calibLoaded = false
    private var calibEpoch = 0L
    @Volatile
    private var token = 0
    private var alarmJob: Job? = null
    private var tapClearJob: Job? = null
    private var clipExpireJob: Job? = null
    private var noteClearJob: Job? = null
    private var source: FieldSource? = null
    private var lastPolishUndo: PolishUndo? = null
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
                val nextCalibEpoch = prefs[PrefKeys.calibEpoch] ?: 0L
                if (!calibLoaded || nextCalibEpoch != calibEpoch) {
                    calibLoaded = true
                    calibEpoch = nextCalibEpoch
                    calibrator.load(
                        prefs[PrefKeys.calibLeft] ?: 0f,
                        prefs[PrefKeys.calibRight] ?: 0f,
                        prefs[PrefKeys.calibDy] ?: 0f,
                    )
                }
                val storedClips = decodeRecentClips(prefs[PrefKeys.recentClips])
                update {
                    it.copy(
                        theme = prefs.themeMode(),
                        heightScale = (prefs[PrefKeys.height] ?: 1f).coerceIn(0.8f, 1.25f),
                        haptic = prefs[PrefKeys.haptic] != false,
                        fuzzy = fuzzy,
                        recentClips = mergeRecentClipLists(it.recentClips, storedClips),
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
        gesture.verticalFlickPx = 46f * density
    }

    fun hitContext(widthPx: Float, refKeyWidthPx: Float, refKeyHeightPx: Float): HitContext {
        val isPrivate = engineState.password || engineState.noLearn
        val adaptive = !isPrivate && !engineState.uriOrEmail && !engineState.literalContext
        val priors = when {
            isPrivate || engineState.uriOrEmail || engineState.literalContext -> NoPriors
            engineState.language == Language.English ->
                latin.nextLetterPriors(engineState.raw, engineState.lastEnglishWord).toList()
            else ->
                pinyin.nextLetterPriors(engineState.raw, engineState.lastChineseContext).toList()
        }
        return HitContext(
            priors = priors,
            composing = adaptive && (engineState.raw.isNotEmpty() || engineState.candidates.isNotEmpty()),
            hasCandidates = adaptive && engineState.candidates.isNotEmpty(),
            password = isPrivate,
            noLearn = engineState.noLearn,
            bias = calibrator.bias(!adaptive),
            boardWidthPx = widthPx,
            refKeyWidthPx = refKeyWidthPx,
            refKeyHeightPx = refKeyHeightPx,
        )
    }

    fun close() {
        alarmJob?.cancel()
        tapClearJob?.cancel()
        clipExpireJob?.cancel()
        noteClearJob?.cancel()
        calibrator.discardPending()
        runCatching { clipboard?.removePrimaryClipChangedListener(clipListener) }
        client.cancel()
        db.close()
    }

    fun startInput(info: EditorInfo, restarting: Boolean) {
        inputActive = true
        alarmJob?.cancel()
        typingSnapshot = null
        val session = editorSessionKeyOf(info)
        val resetSession = shouldResetEditorSession(restarting, activeSession, session)
        activeSession = session
        activeInfo = info
        val traits = fieldTraitsOf(info)
        enter = enterKindOf(info)
        if (resetSession) {
            autoSpacePosition = null
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
                literalContext = false,
                shift = shift,
                partialText = "",
                partialSyllables = "",
                autoSpace = false,
                lastEnglishWord = null,
                undoAutocorrect = null,
                verbatimFirst = false,
                lastChineseContext = if (traits.password) "" else extractTrailingCjk(before),
                touchHints = emptyList(),
                touchSamples = emptyList(),
            )
            bridge.reset()
            lastComposingSpan = null
            candidateRaw = ""
            token++
            calibrator.discardPending()
        } else {
            engineState = engineState.copy(
                password = traits.password,
                noLearn = traits.noLearn,
                uriOrEmail = traits.uriOrEmail,
                literalContext = false,
            )
            synchronizeComposition()
        }
        gesture.cancel()
        tapClearJob?.cancel()
        lastPolishUndo = null
        if (resetSession) {
            aiToken++
            client.cancel()
            source = null
        }
        val isPrivateInput = traits.password || traits.noLearn
        val syncedClips = if (isPrivateInput) {
            state.recentClips
        } else {
            mergeRecentClips(readClip(skipSensitive = true), state.recentClips).also { merged ->
                if (merged != state.recentClips) saveRecentClips(merged)
            }
        }
        update {
            it.copy(
                pressedId = null,
                popup = null,
                tapPreview = null,
                backspaceHint = BackspaceHint.None,
                expanded = false,
                enter = enter,
                canUndoPolish = false,
                menu = false,
                clip = null,
                freshClip = null,
                recentClips = syncedClips,
                overlay = if (resetSession) Overlay.None else it.overlay,
                askDraft = if (resetSession) "" else it.askDraft,
                askAnswer = if (resetSession) "" else it.askAnswer,
                askReason = if (resetSession) "" else it.askReason,
                askAttached = if (resetSession) null else it.askAttached,
                resultText = if (resetSession) "" else it.resultText,
                resultSource = if (resetSession) "" else it.resultSource,
                polishTone = if (resetSession) PolishTone.Auto else it.polishTone,
                polishCandidates = if (resetSession) emptyList() else it.polishCandidates,
                polishSelected = if (resetSession) 0 else it.polishSelected,
                busy = if (resetSession) false else it.busy,
                keyDraft = if (resetSession) "" else it.keyDraft,
                note = if (resetSession) null else it.note,
            )
        }
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
        refreshLiteralAddressContext()
        val currentComposition = if (candidatesStart >= 0 && candidatesEnd > candidatesStart) {
            EditorTextSpan(candidatesStart, candidatesEnd).also { lastComposingSpan = it }
        } else {
            null
        }
        val tapSelection = EditorTextSpan(newSelStart, newSelEnd)
        val detachTarget = compositionDetachTarget(
            compositionLength = engineState.raw.length,
            remembered = lastComposingSpan,
            candidates = currentComposition,
            oldSelection = EditorTextSpan(oldSelStart, oldSelEnd),
            newSelection = tapSelection,
        )
        if (detachTarget != null) {
            detachComposition(detachTarget, tapSelection)
            return
        }
        if (currentComposition != null) {
            refreshTypingSuggestions()
            return
        }
        if (engineState.raw.isEmpty()) lastComposingSpan = null
        val selectionMoved = newSelStart != oldSelStart || newSelEnd != oldSelEnd
        if (engineState.raw.isEmpty() && selectionMoved && !engineState.password) {
            val clearedAutoSpace = (tapSelection.start != autoSpacePosition || tapSelection.end != autoSpacePosition) &&
                clearStaleAutoSpace()
            val before = connection()?.getTextBeforeCursor(64, 0)?.toString().orEmpty()
            val nextShift = if (engineState.language == Language.English && engineState.shift != ShiftState.Caps && !engineState.literalContext) {
                activeInfo?.let { resolveAutoShift(it, before) } ?: engineState.shift
            } else {
                engineState.shift
            }
            val nextZhCtx = extractTrailingCjk(before)
            val contextChanged = nextShift != engineState.shift || nextZhCtx != engineState.lastChineseContext ||
                engineState.undoAutocorrect != null
            if (contextChanged) {
                engineState = engineState.copy(
                    shift = nextShift,
                    lastChineseContext = nextZhCtx,
                    autoSpace = false,
                    undoAutocorrect = null,
                    associating = false,
                    candidates = if (engineState.associating) emptyList() else engineState.candidates,
                )
            }
            if (clearedAutoSpace || contextChanged) publish()
        }
        refreshTypingSuggestions()
    }

    private fun detachComposition(composition: EditorTextSpan, selection: EditorTextSpan) {
        val composedText = engineState.raw
        if (composedText.isEmpty()) return
        val candidate = if (
            engineState.language == Language.Chinese &&
            !engineState.password &&
            candidateRaw == composedText
        ) {
            engineState.candidates.firstOrNull()?.text
        } else {
            null
        }
        token++
        lastComposingSpan = null
        candidateRaw = ""
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
            touchHints = emptyList(),
            touchSamples = emptyList(),
        )
        bridge.detachComposition(composition, selection, composedText, candidate)
        bridge.reset()
        val before = if (engineState.password) "" else connection()?.getTextBeforeCursor(64, 0)?.toString().orEmpty()
        val nextShift = if (engineState.language == Language.English && engineState.shift != ShiftState.Caps && !engineState.literalContext) {
            activeInfo?.let { resolveAutoShift(it, before) } ?: ShiftState.Off
        } else {
            engineState.shift
        }
        engineState = engineState.copy(
            shift = nextShift,
            lastChineseContext = if (engineState.password) "" else extractTrailingCjk(before),
        )
        calibrator.markDirty()
        publish()
    }

    fun onHidden() {
        inputActive = false
        typingSnapshot = null
        autoSpacePosition = null
        engineState = engineState.copy(literalContext = false)
        aiToken++
        client.cancel()
        gesture.cancel()
        alarmJob?.cancel()
        tapClearJob?.cancel()
        calibrator.discardPending()
        source = null
        update {
            it.copy(
                pressedId = null,
                popup = null,
                tapPreview = null,
                backspaceHint = BackspaceHint.None,
                menu = false,
                expanded = false,
                overlay = Overlay.None,
                askDraft = "",
                askAnswer = "",
                askReason = "",
                askAttached = null,
                resultText = "",
                resultSource = "",
                polishTone = PolishTone.Auto,
                polishCandidates = emptyList(),
                polishSelected = 0,
                busy = false,
                note = null,
                typingSuggestions = emptyList(),
            )
        }
    }

    fun onDown(key: KeyModel, x: Float, y: Float, time: Long, sample: TouchSample? = null) {
        tapClearJob?.cancel()
        update { it.copy(tapPreview = null) }
        val frame = gesture.down(key, x, y, time, sample?.neighborText.orEmpty(), sample)
        applyFrame(frame)
        schedule(time)
    }

    fun onRollover(key: KeyModel, x: Float, y: Float, time: Long, sample: TouchSample? = null): Boolean {
        val frames = gesture.rollover(key, x, y, time, sample?.neighborText.orEmpty(), sample)
        if (frames.isEmpty()) return false
        alarmJob?.cancel()
        tapClearJob?.cancel()
        update { it.copy(tapPreview = null) }
        applyFrame(frames[0])
        applyFrame(frames[1])
        schedule(time)
        return true
    }

    fun onMove(
        x: Float,
        y: Float,
        time: Long,
        hit: KeyModel?,
        sample: TouchSample? = null,
        released: Boolean = false,
    ) {
        applyFrame(gesture.move(x, y, time, hit, sample?.neighborText.orEmpty(), sample, released))
        if (released) {
            alarmJob?.cancel()
        } else {
            schedule(time)
        }
    }

    fun onUp(time: Long) {
        alarmJob?.cancel()
        val activePreview = state.tapPreview
        applyFrame(gesture.up())
        scheduleTapClear(activePreview)
    }

    fun onCancel() {
        alarmJob?.cancel()
        calibrator.markDirty()
        val activePreview = state.tapPreview
        applyFrame(gesture.cancel())
        scheduleTapClear(activePreview)
    }

    fun resetTouchCalibration() {
        calibrator.reset()
        scope.launch {
            context.settingsStore.edit { prefs ->
                prefs[PrefKeys.calibLeft] = 0f
                prefs[PrefKeys.calibRight] = 0f
                prefs[PrefKeys.calibDy] = 0f
                prefs[PrefKeys.calibEpoch] = (prefs[PrefKeys.calibEpoch] ?: 0L) + 1L
            }
        }
    }

    fun pick(index: Int) = dispatch(EngineAct.Pick(index))

    fun pickTypingSuggestion(suggestion: TypingSuggestion) {
        val expected = typingSnapshot ?: return
        if (suggestion !in state.typingSuggestions || !canShowTypingSuggestions()) return
        val fresh = bridge.readTypingSnapshot()
        if (fresh != expected || typingReplacementRange(expected, suggestion) == null) {
            refreshTypingSuggestions()
            return
        }
        val input = connection() ?: return
        val result = bridge.replaceTypingSuggestionWithResult(expected, suggestion)
        if (result == TypingSuggestionEditResult.Unchanged) {
            flashNote("当前输入框暂不支持补全")
            refreshTypingSuggestions()
            return
        }
        val accepted = result == TypingSuggestionEditResult.Applied
        token++
        candidateRaw = ""
        lastComposingSpan = null
        lastPolishUndo = null
        calibrator.discardPending()
        bridge.reset()
        val before = runCatching { input.getTextBeforeCursor(64, 0)?.toString().orEmpty() }.getOrDefault("")
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
            lastEnglishWord = null,
            lastChineseContext = extractTrailingCjk(before),
            touchHints = emptyList(),
            touchSamples = emptyList(),
        )
        update {
            it.copy(
                expanded = false,
                menu = false,
                canUndoPolish = false,
                typingSuggestions = emptyList(),
            )
        }
        if (!accepted) flashNote("当前输入框暂不支持补全")
        publish()
    }

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
                        prevWord = if (engineState.uriOrEmail) null else engineState.lastEnglishWord,
                        touchHints = if (engineState.uriOrEmail) emptyList() else engineState.touchHints,
                        touchSamples = if (engineState.uriOrEmail) emptyList() else engineState.touchSamples,
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
            val isPrivate = engineState.password || engineState.noLearn
            val current = if (isPrivate) null else readClip(skipSensitive = true)
            val merged = mergeRecentClips(current, state.recentClips)
            if (!isPrivate && merged != state.recentClips) saveRecentClips(merged)
            update {
                it.copy(
                    menu = true,
                    clip = current,
                    freshClip = if (isPrivate) null else it.freshClip,
                    recentClips = merged,
                    overlay = Overlay.None,
                )
            }
        }
        refreshTypingSuggestions()
    }

    fun paste() {
        val text = state.clip ?: return
        pasteText(text)
    }

    fun pasteText(text: String) {
        if (text.isEmpty() || engineState.password || engineState.noLearn) return
        dispatch(EngineAct.Paste(text))
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
        update { it.copy(menu = false) }
        context.startActivity(
            Intent(context, SettingsActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ),
        )
    }

    fun dismiss() = hide()

    fun setLanguage(language: Language) {
        preferredLanguage = language
        if (engineState.language != language) dispatch(EngineAct.Language)
    }

    fun closeSymbols() {
        if (state.haptic) haptic(HapticKind.Down)
        dispatch(EngineAct.CloseSymbols)
    }

    fun openNumberPage() {
        if (state.haptic) haptic(HapticKind.Down)
        dispatch(EngineAct.Page(KeyboardPage.Number))
    }

    fun selectSymbolCategory(index: Int) {
        val safe = index.coerceIn(0, dev.cheng.keyboard.ime.symbol.symbolCategories.lastIndex)
        update { it.copy(symbolIndex = safe, symbolRecent = false) }
    }

    fun selectRecentSymbols() = update { it.copy(symbolRecent = true) }

    fun toggleSymbolLock() {
        if (state.haptic) haptic(HapticKind.Down)
        update { it.copy(symbolLocked = !it.symbolLocked) }
    }

    fun spaceFromSymbols() {
        if (state.haptic) haptic(HapticKind.Down)
        dispatch(EngineAct.Space)
    }

    fun insertSymbol(text: String) {
        if (state.haptic) haptic(HapticKind.Down)
        val locked = state.symbolLocked
        val recent = (listOf(text) + state.recentSymbols.filter { it != text }).take(40)
        update { it.copy(recentSymbols = recent) }
        dispatch(EngineAct.Direct(text))
        if (!locked) dispatch(EngineAct.CloseSymbols)
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
            bridge.reset()
            lastComposingSpan = null
            candidateRaw = ""
            engineState = engineState.copy(
                raw = "",
                preedit = "",
                candidates = emptyList(),
                associating = false,
                touchHints = emptyList(),
                touchSamples = emptyList(),
            )
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

    fun polish() = polish(PolishTone.Auto)

    fun polish(tone: PolishTone) {
        if (engineState.password) return
        val open = state.overlay as? Overlay.Result
        val reuse = open?.kind == ResultKind.Polish && source != null
        val field = if (reuse) source!! else prepareTransform(ResultKind.Polish)?.also { source = it } ?: return
        val key = secrets.get().orEmpty()
        if (key.isBlank()) return
        lastPolishUndo = null
        val scene = extractHostScene(activeInfo, enter)
        val user = buildPolishUserPrompt(field.text, field.contextBefore, field.contextAfter, scene, tone)
        update {
            it.copy(
                overlay = Overlay.Result(ResultKind.Polish),
                polishTone = tone,
                polishCandidates = emptyList(),
                polishSelected = 0,
                resultText = "",
                resultSource = field.text.take(80),
                note = null,
                busy = true,
                menu = false,
                expanded = false,
                canUndoPolish = false,
            )
        }
        launchAi(
            key = key,
            model = MODEL_FLASH,
            system = ADAPTIVE_POLISH_SYSTEM_PROMPT,
            user = user,
            temperature = 0.35,
            maxTokens = 2048,
            thinking = false,
            onStream = { _, content ->
                val list = parsePolishStream(content)
                if (list.isNotEmpty()) {
                    update { prev ->
                        val index = prev.polishSelected.coerceIn(0, list.lastIndex)
                        prev.copy(
                            polishCandidates = list,
                            polishSelected = index,
                            resultText = list[index].text,
                        )
                    }
                }
            },
            onOk = { text ->
                val list = parsePolishStream(text)
                update { prev ->
                    val index = prev.polishSelected.coerceIn(0, maxOf(0, list.lastIndex))
                    prev.copy(
                        busy = false,
                        polishCandidates = list,
                        polishSelected = index,
                        resultText = list.getOrNull(index)?.text.orEmpty(),
                        note = if (list.isEmpty()) "没有返回正文" else null,
                    )
                }
            },
        )
    }

    fun selectPolishCandidate(index: Int) {
        val list = state.polishCandidates
        if (index !in list.indices) return
        update { it.copy(polishSelected = index, resultText = list[index].text) }
    }

    fun closeOverlay() = finishOverlay(null)

    private fun finishOverlay(note: String?) {
        aiToken++
        client.cancel()
        source = null
        if (state.overlay == Overlay.Ask) {
            engineState = engineState.copy(
                raw = "",
                preedit = "",
                candidates = emptyList(),
                associating = false,
                touchHints = emptyList(),
                touchSamples = emptyList(),
            )
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
                polishTone = PolishTone.Auto,
                polishCandidates = emptyList(),
                polishSelected = 0,
                note = note,
                engine = engineState,
            )
        }
        refreshTypingSuggestions()
    }

    fun toggleThinking() = update { it.copy(askThinking = !it.askThinking) }

    fun clearAttach() = update { it.copy(askAttached = null) }

    fun saveKey() {
        val value = state.keyDraft.trim()
        if (value.isEmpty()) return
        if (runCatching { secrets.set(value) }.isFailure) {
            update { it.copy(note = "密钥保存失败，请稍后重试") }
            return
        }
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
        bridge.reset()
        lastComposingSpan = null
        candidateRaw = ""
        engineState = engineState.copy(
            raw = "",
            preedit = "",
            candidates = emptyList(),
            associating = false,
            touchHints = emptyList(),
            touchSamples = emptyList(),
        )
        input.commitText(text, 1)
        publish()
        closeOverlay()
    }

    fun replaceResult() {
        val text = currentOutput() ?: return
        val input = connection() ?: return
        input.finishComposingText()
        bridge.reset()
        lastComposingSpan = null
        candidateRaw = ""
        engineState = engineState.copy(
            raw = "",
            preedit = "",
            candidates = emptyList(),
            associating = false,
            touchHints = emptyList(),
            touchSamples = emptyList(),
        )
        val target = source
        if (target != null && target.start >= 0) {
            val current = input.getExtractedText(
                android.view.inputmethod.ExtractedTextRequest().apply {
                    hintMaxChars = 10000
                    hintMaxLines = 200
                },
                0,
            )
            val original = current?.text?.toString()?.let {
                textAtDocumentRange(it, current.startOffset, target.start, target.end)
            }
            if (original == null) {
                update { it.copy(note = "无法定位原文，请重新选择后替换") }
                return
            }
            if (original == target.text) {
                if (!input.setSelection(target.start, target.end) || !input.commitText(text, 1)) {
                    update { it.copy(note = "当前输入框未接受替换，请重试") }
                    return
                }
                if ((state.overlay as? Overlay.Result)?.kind == ResultKind.Polish) {
                    lastPolishUndo = PolishUndo(target.start, target.start + text.length, target.text, text)
                    finishOverlay("已替换")
                    update { it.copy(canUndoPolish = true) }
                    return
                }
                closeOverlay()
                return
            }
            update { it.copy(note = "原文已变化，请重新选择后替换") }
            return
        }
        update { it.copy(note = "当前输入框不支持定位替换，请使用插入") }
    }

    fun undoPolish() {
        val undo = lastPolishUndo ?: return
        if (engineState.raw.isNotEmpty()) {
            lastPolishUndo = null
            update { it.copy(canUndoPolish = false, note = "当前输入未完成，无法撤回") }
            return
        }
        val input = connection() ?: return
        val current = input.getExtractedText(
            android.view.inputmethod.ExtractedTextRequest().apply {
                hintMaxChars = 10000
                hintMaxLines = 200
            },
            0,
        ) ?: return
        val replaced = current.text?.toString()?.let {
            textAtDocumentRange(it, current.startOffset, undo.start, undo.end)
        }
        if (replaced == null) {
            update { it.copy(note = "当前无法定位替换位置，不能撤回") }
            return
        }
        if (undo.start < 0 || undo.start > undo.end || replaced != undo.replaced) {
            lastPolishUndo = null
            update { it.copy(canUndoPolish = false, note = "原文已变化，无法撤回") }
            return
        }
        if (!input.setSelection(undo.start, undo.end) || !input.commitText(undo.original, 1)) {
            update { it.copy(note = "当前输入框未接受撤回，请重试") }
            return
        }
        lastPolishUndo = null
        update { it.copy(canUndoPolish = false, note = "已撤回") }
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

    private fun prepareTransform(kind: ResultKind): FieldSource? {
        if (engineState.password) return null
        val key = secrets.get()
        if (key.isNullOrBlank()) {
            update { it.copy(overlay = Overlay.NeedKey, menu = false, keyDraft = "") }
            return null
        }
        val field = connection()?.let(::readField)
        if (field == null) {
            lastPolishUndo = null
            update {
                it.copy(
                    overlay = Overlay.Result(kind),
                    resultText = "",
                    resultSource = "",
                    polishCandidates = emptyList(),
                    polishSelected = 0,
                    canUndoPolish = false,
                    note = "读不到输入框内容",
                    busy = false,
                    menu = false,
                )
            }
            return null
        }
        if (field.text.isBlank()) {
            lastPolishUndo = null
            update {
                it.copy(
                    overlay = Overlay.Result(kind),
                    resultText = "",
                    resultSource = "",
                    polishCandidates = emptyList(),
                    polishSelected = 0,
                    canUndoPolish = false,
                    note = "没有可处理的内容",
                    busy = false,
                    menu = false,
                )
            }
            return null
        }
        return field
    }

    private fun transform(kind: ResultKind, prompt: String, temperature: Double) {
        val field = prepareTransform(kind) ?: return
        val key = secrets.get().orEmpty()
        if (key.isBlank()) return
        source = field
        lastPolishUndo = null
        update {
            it.copy(
                overlay = Overlay.Result(kind),
                polishCandidates = emptyList(),
                polishSelected = 0,
                canUndoPolish = false,
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
        update {
            it.copy(
                pressedId = frame.pressedId,
                popup = frame.popup,
                tapPreview = frame.tapPreview ?: it.tapPreview,
                backspaceHint = frame.backspaceHint,
            )
        }
        frame.actions.forEach(::dispatch)
    }

    private fun scheduleTapClear(preview: TapPreview?) {
        tapClearJob?.cancel()
        if (preview == null) {
            update { it.copy(tapPreview = null) }
            return
        }
        tapClearJob = scope.launch {
            delay(40L)
            update { if (it.tapPreview == preview) it.copy(tapPreview = null) else it }
        }
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
        val asking = state.overlay == Overlay.Ask
        val clearing = act == EngineAct.ClearInput && !asking
        if (!clearing) {
            synchronizeComposition()
            refreshLiteralAddressContext()
            if (!asking) clearStaleAutoSpace()
            if (needsChineseNow(act)) refreshChinese()
        }
        val step = engine.reduce(engineState, act, enter)
        val clearResult = if (clearing) {
            bridge.clearInput(privateField = engineState.password || engineState.noLearn)
        } else {
            null
        }
        when (clearResult) {
            ClearInputResult.Unchanged -> {
                publish()
                Toast.makeText(context, "输入框未确认清空", Toast.LENGTH_SHORT).show()
                return
            }
            ClearInputResult.CompositionFinalizedWithoutClear -> {
                Toast.makeText(context, "输入框未确认清空", Toast.LENGTH_SHORT).show()
            }
            ClearInputResult.Applied, null -> Unit
        }
        val paste = act is EngineAct.Paste && !asking
        if (paste) {
            val text = step.cmds.filterIsInstance<Cmd.Commit>().singleOrNull()?.text ?: return
            if (!bridge.commitPaste(text)) {
                publish()
                Toast.makeText(context, "输入框未确认粘贴，请检查内容", Toast.LENGTH_SHORT).show()
                return
            }
            clipExpireJob?.cancel()
            update { it.copy(freshClip = null) }
        }
        updateCalibrationForAction(act, step)
        engineState = step.state
        if (clearResult != null) {
            token++
            candidateRaw = ""
            lastComposingSpan = null
            autoSpacePosition = null
            lastPolishUndo = null
            typingSnapshot = null
            update {
                it.copy(
                    canUndoPolish = false,
                    typingSuggestions = emptyList(),
                )
            }
        }
        if (engineState.raw.isNotEmpty() && lastPolishUndo != null) {
            lastPolishUndo = null
            update { it.copy(canUndoPolish = false) }
        }
        if (act == EngineAct.Language && !engineState.uriOrEmail) {
            preferredLanguage = engineState.language
        }
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
            if (!paste && !clearing) bridge.apply(
                step.cmds,
                smartCommit = shouldUseSmartCommit(engineState.password, engineState.uriOrEmail || engineState.literalContext),
            )
            if (!engineState.autoSpace) {
                autoSpacePosition = null
            } else if (step.cmds.any { it is Cmd.Commit }) {
                autoSpacePosition = currentAutoSpacePosition()
            }
            if (engineState.raw.isEmpty()) lastComposingSpan = null
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

    private fun currentAutoSpacePosition(): Int? =
        englishAutoSpacePosition(bridge.readTypingSnapshot(), engineState.lastEnglishWord)

    private fun clearStaleAutoSpace(): Boolean {
        if (!engineState.autoSpace || engineState.password) return false
        if (autoSpacePosition != null && autoSpacePosition == currentAutoSpacePosition()) return false
        autoSpacePosition = null
        engineState = engineState.copy(
            autoSpace = false,
            lastEnglishWord = null,
            undoAutocorrect = null,
            candidates = if (engineState.associating) emptyList() else engineState.candidates,
            associating = false,
        )
        return true
    }

    private fun synchronizeComposition() {
        val raw = engineState.raw
        if (raw.isEmpty() || state.overlay == Overlay.Ask) return
        val input = connection() ?: return
        val before = input.getTextBeforeCursor(raw.length, 0)?.toString() ?: return
        if (before == raw) return
        val extracted = input.getExtractedText(
            android.view.inputmethod.ExtractedTextRequest().apply { hintMaxChars = raw.length + 64 },
            0,
        ) ?: return
        if (!compositionMissingFromEditor(raw, lastComposingSpan, extracted.text?.toString(), extracted.startOffset, extracted.selectionEnd)) return
        input.finishComposingText()
        bridge.reset()
        token++
        lastComposingSpan = null
        candidateRaw = ""
        calibrator.discardPending()
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
            lastEnglishWord = null,
            lastChineseContext = extractTrailingCjk(before),
            touchHints = emptyList(),
            touchSamples = emptyList(),
        )
    }

    private fun updateCalibrationForAction(act: EngineAct, step: dev.cheng.keyboard.engine.EngineStep) {
        val isPrivate = engineState.password || engineState.noLearn || engineState.uriOrEmail || engineState.literalContext
        if (isPrivate) {
            calibrator.discardPending()
            return
        }
        when (act) {
            EngineAct.Backspace,
            EngineAct.BackspaceWord,
            EngineAct.ClearInput,
            is EngineAct.Direct,
            is EngineAct.Paste,
            is EngineAct.MoveCursor,
            EngineAct.Language,
            EngineAct.CloseSymbols,
            is EngineAct.Page -> calibrator.markDirty()
            EngineAct.Shift -> {
                if (engineState.language == Language.Chinese && engineState.raw.isNotEmpty()) {
                    calibrator.markDirty()
                }
            }
            EngineAct.Space,
            is EngineAct.Pick,
            EngineAct.Enter -> {
                if (!matchesTouchSpelling(engineState, act)) calibrator.markDirty()
                val committed = step.state.raw.isEmpty() && step.cmds.any { it is Cmd.Commit }
                if (committed && calibrator.commitIfClean() && calibrator.shouldPersist()) {
                    val (left, right, dy) = calibrator.snapshot()
                    calibrator.markPersisted()
                    scope.launch {
                        context.settingsStore.edit { prefs ->
                            prefs[PrefKeys.calibLeft] = left
                            prefs[PrefKeys.calibRight] = right
                            prefs[PrefKeys.calibDy] = dy
                        }
                    }
                }
            }
            is EngineAct.Letter -> {
                if (act.force != null) {
                    calibrator.markDirty()
                } else {
                    act.sample?.takeIf { it.primary == act.ch.lowercaseChar() }?.let(calibrator::record)
                }
            }
            is EngineAct.Candidates -> Unit
        }
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
        is EngineAct.Paste -> current + act.text
        EngineAct.Space -> "$current "
        EngineAct.Backspace -> current.dropLast(1)
        EngineAct.BackspaceWord -> ""
        EngineAct.ClearInput -> ""
        else -> null
    }

    private fun needsChineseNow(act: EngineAct): Boolean {
        if (engineState.language != Language.Chinese || engineState.raw.isEmpty() || engineState.password || engineState.literalContext) return false
        return when (act) {
            EngineAct.Space, EngineAct.Enter, is EngineAct.Direct, is EngineAct.Paste, is EngineAct.Pick -> true
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
        val hints = if (engineState.uriOrEmail) emptyList() else engineState.touchHints
        val samples = if (engineState.uriOrEmail) emptyList() else engineState.touchSamples
        val decoded = runBlocking(db) { pinyin.decode(raw, useFuzzy, ctx, hints, samples) }
        if (engineState.raw != raw) return
        candidateRaw = raw
        engineState = engineState.copy(preedit = decoded.preedit, candidates = decoded.candidates)
    }

    private fun requestPinyin(raw: String) {
        if (engineState.literalContext) return
        val current = ++token
        val useFuzzy = fuzzy
        val ctx = engineState.lastChineseContext + engineState.partialText
        val hints = if (engineState.uriOrEmail) emptyList() else engineState.touchHints
        val samples = if (engineState.uriOrEmail) emptyList() else engineState.touchSamples
        scope.launch(db) {
            if (current != token) return@launch
            val decoded = pinyin.decode(raw, useFuzzy, ctx, hints, samples)
            if (current != token) return@launch
            withContext(Dispatchers.Main) {
                if (current != token || engineState.raw != raw || engineState.language != Language.Chinese || engineState.literalContext) return@withContext
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
                reader.readLines()
                    .map { line -> line.replace("\uFEFF", "").trim().lowercase() }
                    .filter { line -> line.isNotEmpty() && line.all { it in 'a'..'z' } }
                    .toSet()
            }
        }.getOrDefault(emptySet())
        val fresh = if (syllables.isEmpty()) pinyin else PinyinDecoder(syllables)
        fresh.latin = latin
        fresh.poetry = runCatching {
            context.assets.open("poetry/lines.txt").bufferedReader().use { reader ->
                PoetryIndex.parse(reader.readText())
            }
        }.getOrDefault(PoetryIndex.Empty)
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
        if (engineState.password || engineState.noLearn) {
            clipExpireJob?.cancel()
            update { it.copy(freshClip = null) }
            return
        }
        clipExpireJob?.cancel()
        val text = readClip(skipSensitive = true)
        if (text == null) {
            update { it.copy(freshClip = null, clip = null) }
            return
        }
        val merged = mergeRecentClips(text, state.recentClips)
        saveRecentClips(merged)
        update { it.copy(freshClip = text, clip = if (it.menu) text else it.clip, recentClips = merged) }
        clipExpireJob = scope.launch {
            delay(45_000)
            update { it.copy(freshClip = null) }
        }
    }

    private fun saveRecentClips(clips: List<String>) {
        val encoded = encodeRecentClips(clips)
        scope.launch {
            context.settingsStore.edit { prefs ->
                prefs[PrefKeys.recentClips] = encoded
            }
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
        refreshLiteralAddressContext()
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
        refreshTypingSuggestions()
    }

    private fun refreshLiteralAddressContext() {
        val traits = activeInfo?.let(::fieldTraitsOf)
        // A no-learning field still needs literal address entry; helper suggestions remain suppressed.
        val allowed = inputActive && traits != null && !traits.password &&
            !traits.numeric && state.overlay == Overlay.None
        val before = if (allowed) {
            runCatching { connection()?.getTextBeforeCursor(320, 0)?.toString() }.getOrNull()
        } else {
            null
        }
        val literal = before?.let(::isLiteralAddressContext) == true
        if (engineState.literalContext != literal) {
            engineState = engineState.copy(
                literalContext = literal,
                shift = if (literal && engineState.shift != ShiftState.Caps) ShiftState.Off else engineState.shift,
            )
        }
    }

    private fun canShowTypingSuggestions(): Boolean = inputActive &&
        !engineState.password && !engineState.noLearn && state.overlay == Overlay.None && !state.menu

    private fun refreshTypingSuggestions() {
        val current = if (canShowTypingSuggestions()) bridge.readTypingSnapshot() else null
        typingSnapshot = current
        val suggestions = if (current == null) {
            emptyList()
        } else {
            val info = activeInfo
            val inputClass = info?.inputType?.and(EditorInfo.TYPE_MASK_CLASS)
            val variation = info?.inputType?.and(EditorInfo.TYPE_MASK_VARIATION)
            typingSuggestions(
                TypingAssistContext(
                    before = current.before,
                    after = current.after,
                    composing = engineState.raw.takeIf { current.before.endsWith(it) }.orEmpty(),
                    emailField = inputClass == EditorInfo.TYPE_CLASS_TEXT && (
                        variation == EditorInfo.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                            variation == EditorInfo.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
                        ),
                    uriField = inputClass == EditorInfo.TYPE_CLASS_TEXT && variation == EditorInfo.TYPE_TEXT_VARIATION_URI,
                    numeric = info?.let(::fieldTraitsOf)?.numeric == true,
                    hasSelection = current.selection.start != current.selection.end,
                ),
            ).filterNot { suggestion ->
                suggestion.kind == TypingAssistKind.DateTime &&
                    state.engine.candidates.any { it.text == suggestion.replacement }
            }
        }
        if (state.typingSuggestions != suggestions) update { it.copy(typingSuggestions = suggestions) }
    }

    private fun update(block: (UiState) -> UiState) {
        val next = block(snapshot.value)
        snapshot.value = if (next.overlay != Overlay.None || next.menu || next.engine.password || next.engine.noLearn) {
            next.copy(typingSuggestions = emptyList())
        } else {
            next
        }
    }
}
