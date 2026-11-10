package dev.cheng.keyboard.ime

import dev.cheng.keyboard.engine.EngineState
import dev.cheng.keyboard.ime.gesture.PopupPreview
import dev.cheng.keyboard.model.EnterKind
import dev.cheng.keyboard.model.ThemeMode

enum class ResultKind { Translate, Polish }

sealed interface Overlay {
    data object None : Overlay
    data object Ask : Overlay
    data object NeedKey : Overlay
    data class Result(val kind: ResultKind) : Overlay
}

data class UiState(
    val engine: EngineState = EngineState(),
    val theme: ThemeMode = ThemeMode.System,
    val heightScale: Float = 1f,
    val haptic: Boolean = true,
    val fuzzy: Boolean = false,
    val enter: EnterKind = EnterKind.Newline,
    val pressedId: String? = null,
    val popup: PopupPreview? = null,
    val menu: Boolean = false,
    val clip: String? = null,
    val expanded: Boolean = false,
    val overlay: Overlay = Overlay.None,
    val askDraft: String = "",
    val askAnswer: String = "",
    val askReason: String = "",
    val askAttached: String? = null,
    val askThinking: Boolean = false,
    val busy: Boolean = false,
    val resultText: String = "",
    val resultSource: String = "",
    val note: String? = null,
    val keyDraft: String = "",
    val symbolGroup: Int = 0,
    val symbolIndex: Int = 0,
    val symbolLocked: Boolean = false,
    val symbolRecent: Boolean = false,
    val recentSymbols: List<String> = emptyList(),
)
