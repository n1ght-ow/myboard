package dev.cheng.keyboard.engine

import dev.cheng.keyboard.model.Candidate
import dev.cheng.keyboard.model.Choice
import dev.cheng.keyboard.model.EnterKind
import dev.cheng.keyboard.model.KeyboardPage
import dev.cheng.keyboard.model.Language
import dev.cheng.keyboard.model.Pending
import dev.cheng.keyboard.model.ShiftState
import dev.cheng.keyboard.model.TouchSample

data class EngineState(
    val language: Language = Language.Chinese,
    val shift: ShiftState = ShiftState.Off,
    val raw: String = "",
    val preedit: String = "",
    val candidates: List<Candidate> = emptyList(),
    val associating: Boolean = false,
    val password: Boolean = false,
    val noLearn: Boolean = false,
    val page: KeyboardPage = KeyboardPage.Letters,
    val partialText: String = "",
    val partialSyllables: String = "",
    val autoSpace: Boolean = false,
    val lastEnglishWord: String? = null,
    val undoAutocorrect: Pair<String, Int>? = null,
    val verbatimFirst: Boolean = false,
    val lastChineseContext: String = "",
    val touchHints: List<String> = emptyList(),
    val touchSamples: List<TouchSample?> = emptyList(),
    val uriOrEmail: Boolean = false,
)

sealed interface Cmd {
    data class Commit(val text: String) : Cmd
    data class Compose(val text: String) : Cmd
    data object ClearCompose : Cmd
    data object DeleteOne : Cmd
    data class DeleteCount(val count: Int) : Cmd
    data object DeleteWord : Cmd
    data class MoveCursor(val delta: Int) : Cmd
    data class EditorAction(val kind: EnterKind) : Cmd
    data class LearnZh(val text: String, val syllables: String, val prevContext: String = "") : Cmd
    data class LearnZhBigram(val prev: String, val next: String) : Cmd
    data class LearnEn(val word: String, val prevWord: String? = null) : Cmd
}

sealed interface EngineAct {
    data class Letter(
        val ch: Char,
        val force: Choice?,
        val symbol: String,
        val neighbors: String = "",
        val sample: TouchSample? = null,
    ) : EngineAct
    data class Direct(val text: String) : EngineAct
    data object Space : EngineAct
    data object Backspace : EngineAct
    data object BackspaceWord : EngineAct
    data class MoveCursor(val delta: Int) : EngineAct
    data object Enter : EngineAct
    data object Shift : EngineAct
    data object Language : EngineAct
    data class Page(val page: KeyboardPage) : EngineAct
    data class Pick(val index: Int) : EngineAct
    data class Candidates(val preedit: String, val items: List<Candidate>) : EngineAct
}

data class EngineStep(
    val state: EngineState,
    val cmds: List<Cmd> = emptyList(),
    val pending: Pending = Pending.None,
    val associateFrom: String? = null,
)

class InputEngine(private val latin: LatinDecoder) {
    fun reduce(state: EngineState, act: EngineAct, enter: EnterKind): EngineStep {
        return when (act) {
            is EngineAct.Letter -> letter(state, act)
            is EngineAct.Direct -> direct(state, act.text)
            EngineAct.Space -> space(state)
            EngineAct.Backspace -> backspace(state)
            EngineAct.BackspaceWord -> backspaceWord(state)
            is EngineAct.MoveCursor -> moveCursor(state, act.delta)
            EngineAct.Enter -> enter(state, enter)
            EngineAct.Shift -> if (state.language == Language.Chinese && state.raw.isNotEmpty() && !state.password) {
                separate(state)
            } else {
                EngineStep(state.copy(shift = cycle(state.shift)))
            }
            EngineAct.Language -> switchLanguage(state)
            is EngineAct.Page -> EngineStep(state.copy(page = act.page))
            is EngineAct.Pick -> pick(state, act.index)
            is EngineAct.Candidates -> EngineStep(
                state.copy(
                    preedit = if (state.associating || state.raw.isEmpty()) state.preedit else act.preedit,
                    candidates = act.items,
                ),
            )
        }
    }

    private fun letter(state: EngineState, act: EngineAct.Letter): EngineStep {
        val base = act.ch.lowercaseChar()
        if (state.password) {
            val text = when (act.force) {
                Choice.Symbol -> act.symbol
                Choice.Upper -> base.uppercaseChar().toString()
                Choice.Lower -> base.toString()
                null -> if (state.shift == ShiftState.Off) base.toString() else base.uppercaseChar().toString()
            }
            val shift = if (act.force == null && state.shift == ShiftState.Once) ShiftState.Off else state.shift
            return EngineStep(state.copy(shift = shift), listOf(Cmd.Commit(text)))
        }
        return if (state.language == Language.Chinese) chineseLetter(state, base, act) else englishLetter(state, base, act)
    }

    private fun chineseLetter(state: EngineState, base: Char, act: EngineAct.Letter): EngineStep {
        when (act.force) {
            Choice.Symbol -> return confirmThen(state, act.symbol)
            Choice.Upper -> return confirmThen(state, base.uppercaseChar().toString())
            Choice.Lower, null -> {
                if (act.force == null && state.shift != ShiftState.Off) {
                    val committed = confirmThen(state, base.uppercaseChar().toString())
                    val shift = if (state.shift == ShiftState.Once) ShiftState.Off else state.shift
                    return committed.copy(state = committed.state.copy(shift = shift))
                }
                val raw = state.raw + base
                val hint = if (state.uriOrEmail) "" else act.neighbors
                val sample = if (state.uriOrEmail) null else act.sample
                return EngineStep(
                    state.copy(
                        raw = raw,
                        preedit = raw,
                        associating = false,
                        candidates = emptyList(),
                        autoSpace = false,
                        undoAutocorrect = null,
                        touchHints = state.touchHints + hint,
                        touchSamples = state.touchSamples + sample,
                    ),
                    listOf(Cmd.Compose(raw)),
                    pending = Pending.Pinyin,
                )
            }
        }
    }

    private fun englishLetter(state: EngineState, base: Char, act: EngineAct.Letter): EngineStep {
        return when (act.force) {
            Choice.Symbol -> commitEnglishSymbolOrPunctuation(state, act.symbol)
            Choice.Upper -> appendEnglish(state, base.uppercaseChar(), consumeShift = false, neighbors = act.neighbors, sample = act.sample)
            Choice.Lower -> appendEnglish(state, base, consumeShift = false, neighbors = act.neighbors, sample = act.sample)
            null -> appendEnglish(
                state,
                if (state.shift == ShiftState.Off) base else base.uppercaseChar(),
                consumeShift = state.shift == ShiftState.Once,
                neighbors = act.neighbors,
                sample = act.sample,
            )
        }
    }

    private fun appendEnglish(
        state: EngineState,
        ch: Char,
        consumeShift: Boolean,
        neighbors: String = "",
        sample: TouchSample? = null,
    ): EngineStep {
        val raw = state.raw + ch
        val nextHints = state.touchHints + (if (state.uriOrEmail) "" else neighbors)
        val nextSamples = state.touchSamples + (if (state.uriOrEmail) null else sample)
        val keepVerbatim = state.verbatimFirst && state.raw.isNotEmpty()
        return EngineStep(
            state.copy(
                raw = raw,
                preedit = raw,
                candidates = latin.suggest(
                    raw.lowercase(),
                    verbatimFirst = keepVerbatim,
                    prevWord = if (state.uriOrEmail) null else state.lastEnglishWord,
                    touchHints = if (state.uriOrEmail) emptyList() else nextHints,
                    touchSamples = if (state.uriOrEmail) emptyList() else nextSamples,
                ),
                associating = false,
                shift = if (consumeShift) ShiftState.Off else state.shift,
                autoSpace = false,
                undoAutocorrect = null,
                verbatimFirst = keepVerbatim,
                touchHints = nextHints,
                touchSamples = nextSamples,
            ),
            listOf(Cmd.Compose(raw)),
        )
    }

    private fun direct(state: EngineState, text: String): EngineStep {
        if (state.password) return EngineStep(state, listOf(Cmd.Commit(text)))
        if (state.language == Language.English) {
            return commitEnglishSymbolOrPunctuation(state, text)
        }
        val pending = takePending(state)
        val updatedContext = extractTrailingCjk(pending.first.lastChineseContext + text)
        return EngineStep(
            pending.first.copy(
                associating = false,
                candidates = emptyList(),
                partialText = "",
                partialSyllables = "",
                autoSpace = false,
                undoAutocorrect = null,
                lastChineseContext = updatedContext,
                touchHints = emptyList(),
                touchSamples = emptyList(),
            ),
            pending.second + Cmd.Commit(text),
        )
    }

    private fun commitEnglishSymbolOrPunctuation(state: EngineState, text: String): EngineStep {
        if (state.uriOrEmail) {
            val cmds = mutableListOf<Cmd>()
            if (state.raw.isNotEmpty()) cmds += Cmd.Commit(state.raw)
            cmds += Cmd.Commit(text)
            val keepCaps = if (state.shift == ShiftState.Caps) ShiftState.Caps else ShiftState.Off
            return EngineStep(
                state.copy(
                    raw = "",
                    preedit = "",
                    shift = keepCaps,
                    associating = false,
                    candidates = emptyList(),
                    autoSpace = false,
                    undoAutocorrect = null,
                    verbatimFirst = false,
                    lastEnglishWord = null,
                    touchHints = emptyList(),
                    touchSamples = emptyList(),
                ),
                cmds,
            )
        }
        if (state.raw.isEmpty() && state.autoSpace && text in englishAttachPunctuation) {
            val nextShift = shiftAfter(state.shift, text)
            return EngineStep(
                state.copy(
                    shift = nextShift,
                    associating = false,
                    candidates = emptyList(),
                    autoSpace = false,
                    undoAutocorrect = null,
                    verbatimFirst = false,
                    lastEnglishWord = if (text in sentenceEndings) null else state.lastEnglishWord,
                    touchHints = emptyList(),
                    touchSamples = emptyList(),
                ),
                listOf(Cmd.DeleteOne, Cmd.Commit("$text ")),
            )
        }
        val pending = takePending(state)
        val nextShift = shiftAfter(state.shift, text)
        return EngineStep(
            pending.first.copy(
                shift = nextShift,
                associating = false,
                candidates = emptyList(),
                autoSpace = false,
                undoAutocorrect = null,
                verbatimFirst = false,
                lastEnglishWord = if (text in sentenceEndings) null else state.lastEnglishWord,
                touchHints = emptyList(),
                touchSamples = emptyList(),
            ),
            pending.second + Cmd.Commit(text),
        )
    }

    private fun space(state: EngineState): EngineStep {
        if (state.password) return EngineStep(state, listOf(Cmd.Commit(" ")))
        if (state.language == Language.Chinese) {
            if (state.raw.isNotEmpty()) {
                val chosen = state.candidates.firstOrNull()
                if (chosen == null) {
                    return EngineStep(
                        state.copy(
                            raw = "",
                            preedit = "",
                            candidates = emptyList(),
                            associating = true,
                            partialText = "",
                            partialSyllables = "",
                            lastChineseContext = "",
                            touchHints = emptyList(),
                            touchSamples = emptyList(),
                        ),
                        listOf(Cmd.Commit(state.raw)),
                        pending = Pending.Associate,
                        associateFrom = state.raw,
                    )
                }
                return chooseChinese(state, chosen)
            }
            if (state.associating && state.candidates.isNotEmpty()) {
                val text = state.candidates.first().text
                val cmds = mutableListOf<Cmd>(Cmd.Commit(text))
                if (learnable(state) && state.lastChineseContext.isNotEmpty()) {
                    cmds += Cmd.LearnZhBigram(state.lastChineseContext, text)
                }
                val nextCtx = extractTrailingCjk(state.lastChineseContext + text)
                return EngineStep(
                    state.copy(candidates = emptyList(), associating = true, lastChineseContext = nextCtx),
                    cmds,
                    pending = Pending.Associate,
                    associateFrom = text,
                )
            }
            return EngineStep(
                state.copy(
                    partialText = "",
                    partialSyllables = "",
                    lastChineseContext = "",
                    touchHints = emptyList(),
                    touchSamples = emptyList(),
                ),
                listOf(Cmd.Commit(" ")),
            )
        }

        // English Spacebar handling
        if (state.raw.isEmpty()) {
            // Double-space after an auto-spaced word inserts ". " and arms auto-capitalization
            if (state.autoSpace && !state.uriOrEmail) {
                val nextShift = if (state.shift == ShiftState.Caps) ShiftState.Caps else ShiftState.Once
                return EngineStep(
                    state.copy(
                        shift = nextShift,
                        associating = false,
                        candidates = emptyList(),
                        autoSpace = false,
                        undoAutocorrect = null,
                        lastEnglishWord = null,
                        verbatimFirst = false,
                    ),
                    listOf(Cmd.DeleteOne, Cmd.Commit(". ")),
                )
            }
            val shift = if (state.shift == ShiftState.Caps) ShiftState.Caps else ShiftState.Off
            return EngineStep(
                state.copy(shift = shift, associating = false, candidates = emptyList(), autoSpace = false, undoAutocorrect = null),
                listOf(Cmd.Commit(" ")),
            )
        }

        val chosen = if (state.verbatimFirst) {
            applyCase(state.raw, state.raw)
        } else if (state.uriOrEmail) {
            state.raw
        } else {
            state.candidates.firstOrNull()?.let { applyCase(it.text, state.raw) } ?: applyCase(state.raw, state.raw)
        }
        val committed = "$chosen "
        val cmds = mutableListOf<Cmd>(Cmd.Commit(committed))
        if (learnable(state)) cmds += Cmd.LearnEn(chosen.lowercase(), state.lastEnglishWord)
        val shift = if (state.shift == ShiftState.Caps) ShiftState.Caps else ShiftState.Off
        val nextPredictions = if (state.uriOrEmail) emptyList() else latin.predictNext(chosen.lowercase())
        val undo = if (!state.verbatimFirst && !state.uriOrEmail && chosen != state.raw) {
            state.raw to committed.length
        } else {
            null
        }
        return EngineStep(
            state.copy(
                raw = "",
                preedit = "",
                candidates = nextPredictions,
                associating = nextPredictions.isNotEmpty(),
                shift = shift,
                autoSpace = !state.uriOrEmail,
                lastEnglishWord = chosen.lowercase(),
                undoAutocorrect = undo,
                verbatimFirst = false,
                touchHints = emptyList(),
                touchSamples = emptyList(),
            ),
            cmds,
        )
    }

    private fun backspace(state: EngineState): EngineStep {
        if (state.raw.isEmpty()) {
            // Undo English Spacebar autocorrect on immediate Backspace
            val undo = state.undoAutocorrect
            if (state.language == Language.English && undo != null) {
                val (originalRaw, committedLen) = undo
                return EngineStep(
                    state.copy(
                        raw = originalRaw,
                        preedit = originalRaw,
                        candidates = latin.suggest(originalRaw.lowercase(), verbatimFirst = true),
                        associating = false,
                        autoSpace = false,
                        undoAutocorrect = null,
                        verbatimFirst = true,
                        touchHints = emptyList(),
                        touchSamples = emptyList(),
                    ),
                    listOf(Cmd.DeleteCount(committedLen), Cmd.Compose(originalRaw)),
                )
            }
            return EngineStep(
                state.copy(
                    associating = false,
                    candidates = emptyList(),
                    preedit = "",
                    partialText = "",
                    partialSyllables = "",
                    autoSpace = false,
                    undoAutocorrect = null,
                    verbatimFirst = false,
                    lastChineseContext = state.lastChineseContext.dropLast(1),
                    touchHints = emptyList(),
                    touchSamples = emptyList(),
                ),
                listOf(Cmd.DeleteOne),
            )
        }
        val droppedChar = state.raw.last()
        val raw = state.raw.dropLast(1)
        if (raw.isEmpty()) {
            return EngineStep(
                state.copy(
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
                ),
                listOf(Cmd.ClearCompose),
            )
        }
        val nextHints = if (droppedChar == '\'') state.touchHints else state.touchHints.dropLast(1)
        val nextSamples = if (droppedChar == '\'') state.touchSamples else state.touchSamples.dropLast(1)
        if (state.language == Language.English) {
            return EngineStep(
                state.copy(
                    raw = raw,
                    preedit = raw,
                    candidates = latin.suggest(
                        raw.lowercase(),
                        verbatimFirst = state.verbatimFirst,
                        prevWord = if (state.uriOrEmail) null else state.lastEnglishWord,
                        touchHints = if (state.uriOrEmail) emptyList() else nextHints,
                        touchSamples = if (state.uriOrEmail) emptyList() else nextSamples,
                    ),
                    associating = false,
                    autoSpace = false,
                    undoAutocorrect = null,
                    touchHints = nextHints,
                    touchSamples = nextSamples,
                ),
                listOf(Cmd.Compose(raw)),
            )
        }
        return EngineStep(
            state.copy(
                raw = raw,
                preedit = raw,
                candidates = emptyList(),
                associating = false,
                partialText = "",
                partialSyllables = "",
                touchHints = nextHints,
                touchSamples = nextSamples,
            ),
            listOf(Cmd.Compose(raw)),
            pending = Pending.Pinyin,
        )
    }

    private fun backspaceWord(state: EngineState): EngineStep {
        if (state.raw.isNotEmpty()) {
            return EngineStep(
                state.copy(
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
                ),
                listOf(Cmd.ClearCompose),
            )
        }
        return EngineStep(
            state.copy(
                associating = false,
                candidates = emptyList(),
                partialText = "",
                partialSyllables = "",
                autoSpace = false,
                undoAutocorrect = null,
                verbatimFirst = false,
                lastChineseContext = "",
                touchHints = emptyList(),
                touchSamples = emptyList(),
            ),
            listOf(Cmd.DeleteWord),
        )
    }

    private fun moveCursor(state: EngineState, delta: Int): EngineStep {
        if (delta == 0) return EngineStep(state)
        if (state.raw.isNotEmpty()) return EngineStep(state)
        return EngineStep(
            state.copy(
                associating = false,
                candidates = emptyList(),
                partialText = "",
                partialSyllables = "",
                autoSpace = false,
                undoAutocorrect = null,
                verbatimFirst = false,
                lastChineseContext = "",
                touchHints = emptyList(),
                touchSamples = emptyList(),
            ),
            listOf(Cmd.MoveCursor(delta)),
        )
    }

    private fun enter(state: EngineState, kind: EnterKind): EngineStep {
        if (state.password) {
            return if (kind == EnterKind.Newline) {
                EngineStep(state, listOf(Cmd.Commit("\n")))
            } else {
                EngineStep(state, listOf(Cmd.EditorAction(kind)))
            }
        }
        if (state.language == Language.Chinese && state.raw.any { it in 'a'..'z' || it in 'A'..'Z' }) {
            val spelling = buildString(state.raw.length) {
                for (ch in state.raw) if (ch in 'a'..'z' || ch in 'A'..'Z') append(ch)
            }
            return EngineStep(
                state.copy(
                    raw = "",
                    preedit = "",
                    candidates = emptyList(),
                    associating = false,
                    partialText = "",
                    partialSyllables = "",
                    lastChineseContext = "",
                    touchHints = emptyList(),
                    touchSamples = emptyList(),
                ),
                listOf(Cmd.Commit(spelling)),
            )
        }
        val cmds = mutableListOf<Cmd>()
        val cleared = if (state.raw.isNotEmpty()) {
            cmds += Cmd.Commit(state.raw)
            state.copy(raw = "", preedit = "", candidates = emptyList())
        } else {
            state.copy(candidates = emptyList(), associating = false, preedit = "")
        }
        if (kind == EnterKind.Newline) cmds += Cmd.Commit("\n") else cmds += Cmd.EditorAction(kind)
        val shift = when {
            state.language == Language.English && state.shift == ShiftState.Caps -> ShiftState.Caps
            state.language == Language.English && kind == EnterKind.Newline -> ShiftState.Once
            state.language == Language.English -> ShiftState.Off
            else -> cleared.shift
        }
        return EngineStep(
            cleared.copy(
                raw = "",
                preedit = "",
                associating = false,
                shift = shift,
                partialText = "",
                partialSyllables = "",
                autoSpace = false,
                lastEnglishWord = null,
                undoAutocorrect = null,
                verbatimFirst = false,
                lastChineseContext = "",
                touchHints = emptyList(),
                touchSamples = emptyList(),
            ),
            cmds,
        )
    }

    private fun switchLanguage(state: EngineState): EngineStep {
        val next = if (state.language == Language.Chinese) Language.English else Language.Chinese
        // Seamless mid-word language switch: keep typed letters instead of discarding them!
        if (!state.password && state.raw.isNotEmpty()) {
            val letters = state.raw.filter { it in 'a'..'z' || it in 'A'..'Z' }
            if (letters.isNotEmpty()) {
                if (next == Language.English) {
                    return EngineStep(
                        state.copy(
                            language = Language.English,
                            raw = letters,
                            preedit = letters,
                            candidates = latin.suggest(
                                letters.lowercase(),
                                touchHints = if (state.uriOrEmail) emptyList() else state.touchHints,
                                touchSamples = if (state.uriOrEmail) emptyList() else state.touchSamples,
                            ),
                            associating = false,
                            shift = ShiftState.Off,
                            page = KeyboardPage.Letters,
                            partialText = "",
                            partialSyllables = "",
                            autoSpace = false,
                            undoAutocorrect = null,
                            verbatimFirst = false,
                        ),
                        listOf(Cmd.Compose(letters)),
                    )
                } else if (letters.all { it in 'a'..'z' }) {
                    return EngineStep(
                        state.copy(
                            language = Language.Chinese,
                            raw = letters,
                            preedit = letters,
                            candidates = emptyList(),
                            associating = false,
                            shift = ShiftState.Off,
                            page = KeyboardPage.Letters,
                            partialText = "",
                            partialSyllables = "",
                            autoSpace = false,
                            undoAutocorrect = null,
                            verbatimFirst = false,
                        ),
                        listOf(Cmd.Compose(letters)),
                        pending = Pending.Pinyin,
                    )
                } else {
                    return EngineStep(
                        state.copy(
                            language = Language.Chinese,
                            raw = "",
                            preedit = "",
                            candidates = emptyList(),
                            associating = false,
                            shift = ShiftState.Off,
                            page = KeyboardPage.Letters,
                            partialText = "",
                            partialSyllables = "",
                            autoSpace = false,
                            undoAutocorrect = null,
                            verbatimFirst = false,
                            touchHints = emptyList(),
                            touchSamples = emptyList(),
                        ),
                        listOf(Cmd.Commit(letters)),
                    )
                }
            }
        }
        val cmds = if (state.raw.isNotEmpty()) listOf<Cmd>(Cmd.ClearCompose) else emptyList()
        return EngineStep(
            state.copy(
                language = next,
                raw = "",
                preedit = "",
                candidates = emptyList(),
                associating = false,
                shift = ShiftState.Off,
                page = KeyboardPage.Letters,
                partialText = "",
                partialSyllables = "",
                autoSpace = false,
                undoAutocorrect = null,
                verbatimFirst = false,
                touchHints = emptyList(),
                touchSamples = emptyList(),
            ),
            cmds,
        )
    }

    private fun pick(state: EngineState, index: Int): EngineStep {
        val item = state.candidates.getOrNull(index) ?: return EngineStep(state)
        if (state.password) return EngineStep(state)
        if (state.language == Language.English) {
            val typedRef = if (state.associating) {
                if (state.shift != ShiftState.Off) item.text.replaceFirstChar { it.uppercaseChar() } else item.text
            } else {
                state.raw.ifEmpty { item.text }
            }
            val word = applyCase(item.text, typedRef)
            val committed = if (state.uriOrEmail) word else "$word "
            val cmds = mutableListOf<Cmd>(Cmd.Commit(committed))
            if (learnable(state)) cmds += Cmd.LearnEn(word.lowercase(), state.lastEnglishWord)
            val shift = if (state.shift == ShiftState.Caps) ShiftState.Caps else ShiftState.Off
            val nextPredictions = if (state.uriOrEmail) emptyList() else latin.predictNext(word.lowercase())
            return EngineStep(
                state.copy(
                    raw = "",
                    preedit = "",
                    candidates = nextPredictions,
                    associating = nextPredictions.isNotEmpty(),
                    shift = shift,
                    autoSpace = !state.uriOrEmail,
                    lastEnglishWord = word.lowercase(),
                    undoAutocorrect = null,
                    verbatimFirst = false,
                    touchHints = emptyList(),
                    touchSamples = emptyList(),
                ),
                cmds,
            )
        }
        return chooseChinese(state, item)
    }

    private fun chooseChinese(state: EngineState, item: Candidate): EngineStep {
        val cmds = mutableListOf<Cmd>(Cmd.Commit(item.text))
        val rest = if (state.associating) "" else leftover(state.raw, item.consumed)
        if (rest.isNotEmpty()) {
            val nextPartialText = if (item.syllables.isNotBlank()) state.partialText + item.text else ""
            val nextPartialSyl = if (item.syllables.isNotBlank()) joinSpace(state.partialSyllables, item.syllables) else ""
            val remainLetters = rest.count { it in 'a'..'z' || it in 'A'..'Z' }
            cmds += Cmd.Compose(rest)
            return EngineStep(
                state.copy(
                    raw = rest,
                    preedit = rest,
                    candidates = emptyList(),
                    associating = false,
                    partialText = nextPartialText,
                    partialSyllables = nextPartialSyl,
                    touchHints = state.touchHints.takeLast(remainLetters),
                    touchSamples = state.touchSamples.takeLast(remainLetters),
                ),
                cmds,
                pending = Pending.Pinyin,
            )
        }

        if (!state.associating && learnable(state)) {
            if (state.partialText.isNotEmpty() && item.syllables.isNotBlank()) {
                val composedText = state.partialText + item.text
                val composedSyl = joinSpace(state.partialSyllables, item.syllables)
                if (composedText.length in 2..8) {
                    cmds += Cmd.LearnZh(composedText, composedSyl, prevContext = state.lastChineseContext)
                }
            } else if (item.syllables.isNotBlank()) {
                cmds += Cmd.LearnZh(item.text, item.syllables, prevContext = state.lastChineseContext)
            } else if (item.text.all { it in 'a'..'z' || it in 'A'..'Z' || it == '\'' }) {
                cmds += Cmd.LearnEn(item.text.lowercase())
            }
        } else if (state.associating && learnable(state) && state.lastChineseContext.isNotEmpty()) {
            cmds += Cmd.LearnZhBigram(state.lastChineseContext, item.text)
        }

        val nextContext = extractTrailingCjk(state.lastChineseContext + state.partialText + item.text)
        return EngineStep(
            state.copy(
                raw = "",
                preedit = "",
                candidates = emptyList(),
                associating = true,
                partialText = "",
                partialSyllables = "",
                lastChineseContext = nextContext,
                touchHints = emptyList(),
                touchSamples = emptyList(),
            ),
            cmds,
            pending = Pending.Associate,
            associateFrom = item.text,
        )
    }

    private fun leftover(raw: String, consumed: Int): String {
        val letters = raw.count { it in 'a'..'z' || it in 'A'..'Z' }
        if (consumed < 0 || consumed >= letters) return ""
        var seen = 0
        var index = 0
        while (index < raw.length && seen < consumed) {
            if (raw[index] in 'a'..'z' || raw[index] in 'A'..'Z') seen++
            index++
        }
        if (index < raw.length && raw[index] == '\'') index++
        return raw.substring(index)
    }

    private fun confirmThen(state: EngineState, extra: String): EngineStep {
        val pending = takePending(state)
        val updatedContext = extractTrailingCjk(pending.first.lastChineseContext + extra)
        return EngineStep(
            pending.first.copy(
                associating = false,
                candidates = emptyList(),
                preedit = "",
                raw = "",
                partialText = "",
                partialSyllables = "",
                lastChineseContext = updatedContext,
                touchHints = emptyList(),
                touchSamples = emptyList(),
            ),
            pending.second + Cmd.Commit(extra),
        )
    }

    private fun takePending(state: EngineState): Pair<EngineState, List<Cmd>> {
        if (state.raw.isEmpty()) return state to emptyList()
        if (state.language == Language.Chinese) {
            val chosen = state.candidates.firstOrNull()
            val text = chosen?.text ?: state.raw
            val cmds = mutableListOf<Cmd>(Cmd.Commit(text))
            if (chosen != null && learnable(state) && chosen.syllables.isNotBlank()) {
                if (state.partialText.isNotEmpty() && (state.partialText.length + chosen.text.length) in 2..8) {
                    cmds += Cmd.LearnZh(
                        state.partialText + chosen.text,
                        joinSpace(state.partialSyllables, chosen.syllables),
                        prevContext = state.lastChineseContext,
                    )
                } else {
                    cmds += Cmd.LearnZh(chosen.text, chosen.syllables, prevContext = state.lastChineseContext)
                }
            }
            val nextContext = extractTrailingCjk(state.lastChineseContext + state.partialText + text)
            return state.copy(
                raw = "",
                preedit = "",
                candidates = emptyList(),
                associating = false,
                partialText = "",
                partialSyllables = "",
                lastChineseContext = nextContext,
                touchHints = emptyList(),
                touchSamples = emptyList(),
            ) to cmds
        }
        // English pending before punctuation: resolve contractions & standalone "i" -> "I" while preserving initials like "H."
        val top = state.candidates.firstOrNull()?.text
        val lowerRaw = state.raw.lowercase()
        val resolved = when {
            lowerRaw == "i" -> "I"
            top != null && top.contains('\'') && top.replace("'", "") == lowerRaw -> applyCase(top, state.raw)
            else -> state.raw
        }
        return state.copy(
            raw = "",
            preedit = "",
            candidates = emptyList(),
            touchHints = emptyList(),
            touchSamples = emptyList(),
        ) to listOf(Cmd.Commit(resolved))
    }

    private fun separate(state: EngineState): EngineStep {
        if (state.raw.endsWith("'")) return EngineStep(state)
        val raw = state.raw + "'"
        return EngineStep(
            state.copy(raw = raw, preedit = raw, associating = false, candidates = emptyList()),
            listOf(Cmd.Compose(raw)),
            pending = Pending.Pinyin,
        )
    }

    private fun joinSpace(left: String, right: String): String = when {
        left.isBlank() -> right.trim()
        right.isBlank() -> left.trim()
        else -> "${left.trim()} ${right.trim()}"
    }

    private fun learnable(state: EngineState) = !state.password && !state.noLearn

    private fun cycle(shift: ShiftState): ShiftState = when (shift) {
        ShiftState.Off -> ShiftState.Once
        ShiftState.Once -> ShiftState.Caps
        ShiftState.Caps -> ShiftState.Off
    }

    private companion object {
        val englishAttachPunctuation = setOf(".", ",", "!", "?", ";", ":")
        val sentenceEndings = setOf(".", "!", "?")
    }
}
