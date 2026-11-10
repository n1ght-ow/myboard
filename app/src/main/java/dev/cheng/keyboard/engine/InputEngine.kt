package dev.cheng.keyboard.engine

import dev.cheng.keyboard.model.Candidate
import dev.cheng.keyboard.model.Choice
import dev.cheng.keyboard.model.EnterKind
import dev.cheng.keyboard.model.KeyboardPage
import dev.cheng.keyboard.model.Language
import dev.cheng.keyboard.model.Pending
import dev.cheng.keyboard.model.ShiftState

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
)

sealed interface Cmd {
    data class Commit(val text: String) : Cmd
    data class Compose(val text: String) : Cmd
    data object ClearCompose : Cmd
    data object DeleteOne : Cmd
    data object DeleteWord : Cmd
    data class EditorAction(val kind: EnterKind) : Cmd
    data class LearnZh(val text: String, val syllables: String) : Cmd
    data class LearnEn(val word: String) : Cmd
}

sealed interface EngineAct {
    data class Letter(val ch: Char, val force: Choice?, val symbol: String) : EngineAct
    data class Direct(val text: String) : EngineAct
    data object Space : EngineAct
    data object Backspace : EngineAct
    data object BackspaceWord : EngineAct
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
                return EngineStep(
                    state.copy(raw = raw, preedit = raw, associating = false, candidates = emptyList()),
                    listOf(Cmd.Compose(raw)),
                    pending = Pending.Pinyin,
                )
            }
        }
    }

    private fun englishLetter(state: EngineState, base: Char, act: EngineAct.Letter): EngineStep {
        return when (act.force) {
            Choice.Symbol -> {
                val pending = takePending(state)
                EngineStep(
                    pending.first.copy(shift = shiftAfter(state.shift, act.symbol), candidates = emptyList()),
                    pending.second + Cmd.Commit(act.symbol),
                )
            }
            Choice.Upper -> appendEnglish(state, base.uppercaseChar(), consumeShift = false)
            Choice.Lower -> appendEnglish(state, base, consumeShift = false)
            null -> appendEnglish(
                state,
                if (state.shift == ShiftState.Off) base else base.uppercaseChar(),
                consumeShift = state.shift == ShiftState.Once,
            )
        }
    }

    private fun appendEnglish(state: EngineState, ch: Char, consumeShift: Boolean): EngineStep {
        val raw = state.raw + ch
        return EngineStep(
            state.copy(
                raw = raw,
                preedit = raw,
                candidates = latin.suggest(raw.lowercase()),
                associating = false,
                shift = if (consumeShift) ShiftState.Off else state.shift,
            ),
            listOf(Cmd.Compose(raw)),
        )
    }

    private fun direct(state: EngineState, text: String): EngineStep {
        if (state.password) return EngineStep(state, listOf(Cmd.Commit(text)))
        val pending = takePending(state)
        val shift = if (state.language == Language.English) shiftAfter(state.shift, text) else pending.first.shift
        return EngineStep(
            pending.first.copy(shift = shift, associating = false, candidates = emptyList()),
            pending.second + Cmd.Commit(text),
        )
    }

    private fun space(state: EngineState): EngineStep {
        if (state.password) return EngineStep(state, listOf(Cmd.Commit(" ")))
        if (state.language == Language.Chinese) {
            if (state.raw.isNotEmpty()) {
                val chosen = state.candidates.firstOrNull()
                val text = chosen?.text ?: state.raw
                val cmds = mutableListOf<Cmd>(Cmd.Commit(text))
                if (chosen != null && learnable(state)) cmds += Cmd.LearnZh(text, chosen.syllables)
                return EngineStep(
                    state.copy(raw = "", preedit = "", candidates = emptyList(), associating = true),
                    cmds,
                    pending = Pending.Associate,
                    associateFrom = text,
                )
            }
            if (state.associating && state.candidates.isNotEmpty()) {
                val text = state.candidates.first().text
                return EngineStep(
                    state.copy(candidates = emptyList(), associating = true),
                    listOf(Cmd.Commit(text)),
                    pending = Pending.Associate,
                    associateFrom = text,
                )
            }
            return EngineStep(state, listOf(Cmd.Commit(" ")))
        }
        if (state.raw.isEmpty()) {
            val shift = if (state.shift == ShiftState.Caps) ShiftState.Caps else ShiftState.Off
            return EngineStep(state.copy(shift = shift), listOf(Cmd.Commit(" ")))
        }
        val word = state.candidates.firstOrNull()?.let { applyCase(it.text, state.raw) } ?: state.raw
        val cmds = mutableListOf<Cmd>(Cmd.Commit("$word "))
        if (learnable(state)) cmds += Cmd.LearnEn(word.lowercase())
        val shift = if (state.shift == ShiftState.Caps) ShiftState.Caps else ShiftState.Off
        return EngineStep(
            state.copy(raw = "", preedit = "", candidates = emptyList(), shift = shift),
            cmds,
        )
    }

    private fun backspace(state: EngineState): EngineStep {
        if (state.raw.isEmpty()) {
            return EngineStep(
                state.copy(associating = false, candidates = emptyList(), preedit = ""),
                listOf(Cmd.DeleteOne),
            )
        }
        val raw = state.raw.dropLast(1)
        if (raw.isEmpty()) {
            return EngineStep(
                state.copy(raw = "", preedit = "", candidates = emptyList(), associating = false),
                listOf(Cmd.ClearCompose),
            )
        }
        if (state.language == Language.English) {
            return EngineStep(
                state.copy(raw = raw, preedit = raw, candidates = latin.suggest(raw.lowercase()), associating = false),
                listOf(Cmd.Compose(raw)),
            )
        }
        return EngineStep(
            state.copy(raw = raw, preedit = raw, candidates = emptyList(), associating = false),
            listOf(Cmd.Compose(raw)),
            pending = Pending.Pinyin,
        )
    }

    private fun backspaceWord(state: EngineState): EngineStep {
        if (state.raw.isNotEmpty()) {
            return EngineStep(
                state.copy(raw = "", preedit = "", candidates = emptyList(), associating = false),
                listOf(Cmd.ClearCompose),
            )
        }
        return EngineStep(
            state.copy(associating = false, candidates = emptyList()),
            listOf(Cmd.DeleteWord),
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
        val cmds = mutableListOf<Cmd>()
        val cleared = if (state.raw.isNotEmpty() && state.language == Language.Chinese) {
            val pending = takePending(state)
            cmds += pending.second
            pending.first
        } else if (state.raw.isNotEmpty()) {
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
        return EngineStep(cleared.copy(raw = "", preedit = "", associating = false, shift = shift), cmds)
    }

    private fun switchLanguage(state: EngineState): EngineStep {
        val next = if (state.language == Language.Chinese) Language.English else Language.Chinese
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
            ),
            cmds,
        )
    }

    private fun pick(state: EngineState, index: Int): EngineStep {
        val item = state.candidates.getOrNull(index) ?: return EngineStep(state)
        if (state.password) return EngineStep(state)
        if (state.language == Language.English) {
            val word = applyCase(item.text, state.raw.ifEmpty { item.text })
            val cmds = mutableListOf<Cmd>(Cmd.Commit("$word "))
            if (learnable(state)) cmds += Cmd.LearnEn(word.lowercase())
            val shift = if (state.shift == ShiftState.Caps) ShiftState.Caps else ShiftState.Off
            return EngineStep(
                state.copy(raw = "", preedit = "", candidates = emptyList(), associating = false, shift = shift),
                cmds,
            )
        }
        val cmds = mutableListOf<Cmd>(Cmd.Commit(item.text))
        if (!state.associating && learnable(state) && item.syllables.isNotBlank()) {
            cmds += Cmd.LearnZh(item.text, item.syllables)
        }
        return EngineStep(
            state.copy(raw = "", preedit = "", candidates = emptyList(), associating = true),
            cmds,
            pending = Pending.Associate,
            associateFrom = item.text,
        )
    }

    private fun confirmThen(state: EngineState, extra: String): EngineStep {
        val pending = takePending(state)
        return EngineStep(
            pending.first.copy(associating = false, candidates = emptyList(), preedit = "", raw = ""),
            pending.second + Cmd.Commit(extra),
        )
    }

    private fun takePending(state: EngineState): Pair<EngineState, List<Cmd>> {
        if (state.raw.isEmpty()) return state to emptyList()
        if (state.language == Language.Chinese) {
            val chosen = state.candidates.firstOrNull()
            val text = chosen?.text ?: state.raw
            val cmds = mutableListOf<Cmd>(Cmd.Commit(text))
            if (chosen != null && learnable(state)) cmds += Cmd.LearnZh(chosen.text, chosen.syllables)
            return state.copy(raw = "", preedit = "", candidates = emptyList(), associating = false) to cmds
        }
        return state.copy(raw = "", preedit = "", candidates = emptyList()) to listOf(Cmd.Commit(state.raw))
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

    private fun learnable(state: EngineState) = !state.password && !state.noLearn

    private fun cycle(shift: ShiftState): ShiftState = when (shift) {
        ShiftState.Off -> ShiftState.Once
        ShiftState.Once -> ShiftState.Caps
        ShiftState.Caps -> ShiftState.Off
    }
}
