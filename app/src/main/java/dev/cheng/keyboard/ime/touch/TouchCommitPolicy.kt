package dev.cheng.keyboard.ime.touch

import dev.cheng.keyboard.engine.EngineAct
import dev.cheng.keyboard.engine.EngineState
import dev.cheng.keyboard.model.Language

/** A correction or abbreviation is not evidence of the user's intended touch centres. */
internal fun matchesTouchSpelling(state: EngineState, act: EngineAct): Boolean {
    if (state.password || state.noLearn || state.uriOrEmail || state.raw.isEmpty()) return false
    if (act == EngineAct.Enter) return true // Enter submits the literal spelling.
    val candidate = when (act) {
        EngineAct.Space -> {
            if (state.language == Language.English && state.verbatimFirst) return true
            state.candidates.firstOrNull() ?: return true
        }
        is EngineAct.Pick -> state.candidates.getOrNull(act.index) ?: return false
        else -> return false
    }
    return if (state.language == Language.English) {
        candidate.text.equals(state.raw, ignoreCase = true)
    } else {
        val typed = state.raw.filter { it in 'a'..'z' || it in 'A'..'Z' }.lowercase()
        val spelling = candidate.syllables.filter { it in 'a'..'z' || it in 'A'..'Z' }.lowercase()
        typed.isNotEmpty() && typed == spelling
    }
}
