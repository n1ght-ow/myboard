package dev.cheng.keyboard.ime.gesture

import dev.cheng.keyboard.engine.EngineAct
import dev.cheng.keyboard.model.Choice
import dev.cheng.keyboard.model.KeyKind
import dev.cheng.keyboard.model.KeyModel
import dev.cheng.keyboard.model.KeyboardPage
import kotlin.math.abs

enum class HapticKind { Down, Arm, Step, Delete }

data class PopupPreview(
    val keyId: String,
    val lower: String,
    val upper: String,
    val symbol: String,
    val choice: Choice,
)

data class GestureFrame(
    val pressedId: String?,
    val popup: PopupPreview?,
    val actions: List<EngineAct> = emptyList(),
    val haptic: HapticKind? = null,
)

class KeyGesture(
    var slopPx: Float,
    var caseSlopPx: Float,
    var swipePx: Float,
    private val longMs: Long = 350,
    private val repeatMs: Long = 40,
) {
    private var tracking = false
    private var key: KeyModel? = null
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var armed = false
    private var cancelled = false
    private var choice = Choice.Symbol
    private var swiped = false
    private var repeating = false
    private var lastRepeat = 0L

    fun down(target: KeyModel, x: Float, y: Float, time: Long): GestureFrame {
        tracking = true
        key = target
        downX = x
        downY = y
        downAt = time
        armed = false
        cancelled = false
        choice = Choice.Symbol
        swiped = false
        repeating = false
        lastRepeat = time
        val actions = if (target.kind == KeyKind.Backspace) listOf(EngineAct.Backspace) else emptyList()
        return GestureFrame(
            pressedId = target.id,
            popup = null,
            actions = actions,
            haptic = if (target.kind == KeyKind.Backspace) HapticKind.Delete else HapticKind.Down,
        )
    }

    fun move(x: Float, y: Float, time: Long, hit: KeyModel?): GestureFrame {
        val current = key ?: return idle()
        if (!tracking) return idle()
        val dx = x - downX
        val dy = y - downY
        if (current.kind == KeyKind.Backspace) {
            if (!swiped && dx <= -swipePx && abs(dy) <= swipePx * 1.4f) {
                swiped = true
                repeating = false
                return GestureFrame(current.id, null, listOf(EngineAct.BackspaceWord), HapticKind.Delete)
            }
            return frame()
        }
        if (current.kind == KeyKind.Letter && armed) {
            val next = when {
                dx <= -caseSlopPx -> Choice.Upper
                dx >= caseSlopPx -> Choice.Lower
                else -> Choice.Symbol
            }
            val haptic = if (next != choice) HapticKind.Step else null
            choice = next
            return frame(haptic)
        }
        if (current.kind == KeyKind.Letter && !armed) {
            val moved = abs(dx) > slopPx || abs(dy) > slopPx
            if (moved && hit != null && hit.kind == KeyKind.Letter && hit.id != current.id) {
                return down(hit, x, y, time)
            }
            if (moved && hit?.id != current.id) cancelled = true
            if (hit?.id == current.id) cancelled = false
            return frame()
        }
        if (abs(dx) > slopPx * 2 || abs(dy) > slopPx * 2) cancelled = true
        return frame()
    }

    fun up(): GestureFrame {
        val current = key
        if (!tracking || current == null) return idle()
        tracking = false
        val actions = when {
            cancelled -> emptyList()
            current.kind == KeyKind.Backspace -> emptyList()
            current.kind == KeyKind.Letter && armed -> listOf(longPress(current, choice))
            else -> listOfNotNull(tap(current))
        }
        key = null
        armed = false
        return GestureFrame(null, null, actions, null)
    }

    fun cancel(): GestureFrame {
        tracking = false
        key = null
        armed = false
        cancelled = false
        return idle()
    }

    fun alarm(time: Long): GestureFrame {
        val current = key ?: return idle()
        if (!tracking) return idle()
        if (current.kind == KeyKind.Letter && !armed && !cancelled && time >= downAt + longMs) {
            armed = true
            choice = Choice.Symbol
            return frame(HapticKind.Arm)
        }
        if (current.kind == KeyKind.Backspace && !swiped && time >= nextRepeatAt()) {
            repeating = true
            lastRepeat = time
            return GestureFrame(current.id, null, listOf(EngineAct.Backspace), HapticKind.Delete)
        }
        return frame()
    }

    fun nextAlarm(now: Long): Long? {
        val current = key ?: return null
        if (!tracking) return null
        if (current.kind == KeyKind.Letter && !armed && !cancelled) return downAt + longMs
        if (current.kind == KeyKind.Backspace && !swiped) return nextRepeatAt().coerceAtLeast(now)
        return null
    }

    private fun nextRepeatAt(): Long = if (!repeating) downAt + longMs else lastRepeat + repeatMs

    private fun frame(haptic: HapticKind? = null): GestureFrame {
        val current = key
        return GestureFrame(
            pressedId = current?.id,
            popup = if (current != null && armed && current.kind == KeyKind.Letter) preview(current) else null,
            haptic = haptic,
        )
    }

    private fun preview(current: KeyModel) = PopupPreview(
        keyId = current.id,
        lower = current.label.lowercase(),
        upper = current.label.uppercase(),
        symbol = current.hint.orEmpty(),
        choice = choice,
    )

    private fun longPress(current: KeyModel, selected: Choice) =
        EngineAct.Letter(current.label.first(), selected, current.hint.orEmpty())

    private fun tap(current: KeyModel): EngineAct? = when (current.kind) {
        KeyKind.Letter -> EngineAct.Letter(current.label.first(), null, current.hint.orEmpty())
        KeyKind.Char -> EngineAct.Direct(current.insert ?: current.label)
        KeyKind.Shift -> EngineAct.Shift
        KeyKind.Enter -> EngineAct.Enter
        KeyKind.Space -> EngineAct.Space
        KeyKind.Language -> EngineAct.Language
        KeyKind.PageLetters -> EngineAct.Page(KeyboardPage.Letters)
        KeyKind.PageNumber -> EngineAct.Page(KeyboardPage.Number)
        KeyKind.PageSymbols -> EngineAct.Page(KeyboardPage.Symbols)
        KeyKind.PageMore -> EngineAct.Page(KeyboardPage.More)
        KeyKind.Backspace -> null
    }

    private fun idle() = GestureFrame(null, null)
}
