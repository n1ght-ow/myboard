package dev.cheng.keyboard.ime.gesture

import dev.cheng.keyboard.engine.EngineAct
import dev.cheng.keyboard.model.Choice
import dev.cheng.keyboard.model.KeyKind
import dev.cheng.keyboard.model.KeyModel
import dev.cheng.keyboard.model.KeyboardPage
import dev.cheng.keyboard.model.TouchSample
import kotlin.math.abs

enum class HapticKind { Down, Action, Arm, Step, Delete }

enum class BackspaceHint { None, Repeat, ClearArmed }

data class TapPreview(val keyId: String, val label: String)

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
    val tapPreview: TapPreview? = null,
    val backspaceHint: BackspaceHint = BackspaceHint.None,
    val spaceSliding: Boolean = false,
)

class KeyGesture(
    var slopPx: Float,
    var caseSlopPx: Float,
    var swipePx: Float,
    var verticalFlickPx: Float = 46f,
    private val longMs: Long = 350,
    private val backspaceLongMs: Long = 400,
    private val repeatMs: Long = 50,
) {
    private val fastTapLockMs: Long = 70L
    private var tracking = false
    private var key: KeyModel? = null
    private var downX = 0f
    private var downY = 0f
    private var latestX = 0f
    private var latestY = 0f
    private var downAt = 0L
    private var gestureMoved = false
    private var armed = false
    private var flickChoice: Choice? = null
    private var cancelled = false
    private var choice = Choice.Symbol
    private var spaceSliding = false
    private var spaceAnchorX = 0f
    private var repeating = false
    private var clearArmed = false
    private var lastRepeat = 0L
    private var downNeighbors = ""
    private var downSample: TouchSample? = null

    fun down(
        target: KeyModel,
        x: Float,
        y: Float,
        time: Long,
        touchNeighbors: String = "",
        touchSample: TouchSample? = null,
    ): GestureFrame {
        tracking = true
        key = target
        downX = x
        downY = y
        latestX = x
        latestY = y
        downAt = time
        gestureMoved = false
        armed = false
        flickChoice = null
        cancelled = false
        choice = Choice.Symbol
        spaceSliding = false
        spaceAnchorX = x
        repeating = false
        clearArmed = false
        lastRepeat = time
        downNeighbors = touchNeighbors.ifEmpty { touchSample?.neighborText.orEmpty() }
        downSample = touchSample
        val tapPreview = if (target.kind == KeyKind.Letter) TapPreview(target.id, target.label) else null
        return GestureFrame(
            pressedId = target.id,
            popup = null,
            haptic = downHaptic(target),
            tapPreview = tapPreview,
        )
    }

    fun canRollover(): Boolean {
        val current = key ?: return false
        if (!tracking) return false
        if (cancelled) return current.kind == KeyKind.Backspace
        if (armed || flickChoice != null || spaceSliding || repeating) return false
        return current.kind in setOf(
            KeyKind.Letter,
            KeyKind.Char,
            KeyKind.Space,
            KeyKind.Enter,
            KeyKind.Shift,
        )
    }

    fun rollover(
        target: KeyModel,
        x: Float,
        y: Float,
        time: Long,
        touchNeighbors: String = "",
        touchSample: TouchSample? = null,
    ): List<GestureFrame> {
        if (!canRollover()) return emptyList()
        val first = up()
        val second = down(target, x, y, time, touchNeighbors, touchSample)
        return listOf(first, second)
    }

    fun move(
        x: Float,
        y: Float,
        time: Long,
        hit: KeyModel?,
        touchNeighbors: String = "",
        touchSample: TouchSample? = null,
        released: Boolean = false,
    ): GestureFrame {
        val current = key ?: return idle()
        if (!tracking) return idle()
        val dx = x - downX
        val dy = y - downY
        if (abs(dx) > slopPx || abs(dy) > slopPx) gestureMoved = true

        if (current.kind == KeyKind.Backspace) {
            if (cancelled) return frame()
            if (abs(dx) > horizontalCancelPx || dy > downCancelPx) {
                cancelled = true
                clearArmed = false
                return frame()
            }
            latestX = x
            latestY = y
            if (released && !repeating) return frame()
            if (repeating || time >= downAt + backspaceLongMs) {
                val startedByMove = !repeating
                if (startedByMove) {
                    repeating = true
                    lastRepeat = time
                }
                val nextArmed = when {
                    clearArmed -> dy <= -clearExitPx && abs(dx) <= clearExitMaxX
                    released -> false
                    else -> inClearArmZone()
                }
                if (nextArmed != clearArmed) {
                    clearArmed = nextArmed
                    if (!nextArmed) lastRepeat = time
                    return frame(HapticKind.Step)
                }
                if (startedByMove && !clearArmed && !released) {
                    return backspaceDelete(current)
                }
            }
            return frame()
        }

        if (current.kind == KeyKind.Space) {
            val stepPx = (swipePx * 0.55f).coerceAtLeast(12f)
            if (!spaceSliding && abs(dx) >= swipePx * 0.75f && abs(dy) <= swipePx * 1.4f) {
                spaceSliding = true
                cancelled = false
                val dir = if (dx > 0) 1 else -1
                spaceAnchorX = x
                return GestureFrame(
                    pressedId = current.id,
                    popup = null,
                    actions = listOf(EngineAct.MoveCursor(dir)),
                    haptic = HapticKind.Step,
                    spaceSliding = true,
                )
            }
            if (spaceSliding) {
                val deltaPx = x - spaceAnchorX
                if (abs(deltaPx) >= stepPx) {
                    val steps = (deltaPx / stepPx).toInt()
                    spaceAnchorX += steps * stepPx
                    return GestureFrame(
                        pressedId = current.id,
                        popup = null,
                        actions = listOf(EngineAct.MoveCursor(steps)),
                        haptic = HapticKind.Step,
                        spaceSliding = true,
                    )
                }
                return frame()
            }
            if (abs(dy) > slopPx * 2.2f) cancelled = true
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
            val elapsed = time - downAt
            // Vertical flick up -> instant symbol/number; vertical flick down -> instant uppercase
            if (dy <= -verticalFlickPx && abs(dy) > abs(dx) * 1.75f && elapsed >= 45L && !current.hint.isNullOrEmpty()) {
                val changed = flickChoice != Choice.Symbol
                flickChoice = Choice.Symbol
                cancelled = false
                return frame(if (changed) HapticKind.Step else null)
            }
            if (dy >= verticalFlickPx && abs(dy) > abs(dx) * 1.75f && elapsed >= 45L) {
                val changed = flickChoice != Choice.Upper
                flickChoice = Choice.Upper
                cancelled = false
                return frame(if (changed) HapticKind.Step else null)
            }
            if (flickChoice != null) {
                if (abs(dy) < verticalFlickPx * 0.6f) {
                    flickChoice = null
                    return frame(HapticKind.Step)
                }
                return frame()
            }

            if (
                gestureMoved &&
                elapsed >= fastTapLockMs &&
                hit != null &&
                hit.kind == KeyKind.Letter &&
                hit.id != current.id &&
                entersTargetCore(touchSample)
            ) {
                return retarget(hit, touchNeighbors, touchSample)
            }
            if (gestureMoved && hit?.id != current.id && abs(dy) > swipePx * 1.5f) cancelled = true
            if (hit?.id == current.id) cancelled = false
            return frame()
        }

        if (current.hasQuickChars()) {
            val elapsed = time - downAt
            if (dy <= -verticalFlickPx && abs(dy) > abs(dx) * 1.75f && elapsed >= 45L) {
                val changed = flickChoice != Choice.Symbol
                flickChoice = Choice.Symbol
                cancelled = false
                return frame(if (changed) HapticKind.Step else null)
            }
            if (flickChoice != null) {
                if (abs(dy) < verticalFlickPx * 0.6f) {
                    flickChoice = null
                    return frame(HapticKind.Step)
                }
                return frame()
            }
            val upwardIntent = dy < 0f && abs(dy) > abs(dx) * 1.3f && abs(dx) <= slopPx * 2
            if (!upwardIntent && (abs(dx) > slopPx * 2 || abs(dy) > slopPx * 2)) {
                cancelled = true
            } else if (hit?.id == current.id) {
                cancelled = false
            }
            return frame()
        }

        if (abs(dx) > slopPx * 2 || abs(dy) > slopPx * 2) cancelled = true
        return frame()
    }

    fun up(): GestureFrame {
        val current = key
        if (!tracking || current == null) return idle()
        tracking = false
        gestureMoved = false
        val flick = flickChoice
        val actions = when {
            cancelled -> emptyList()
            current.kind == KeyKind.Backspace -> when {
                clearArmed -> listOf(EngineAct.ClearInput)
                repeating -> emptyList()
                else -> listOf(EngineAct.Backspace)
            }
            current.kind == KeyKind.Space && spaceSliding -> emptyList()
            current.kind == KeyKind.Letter && armed -> listOf(longPress(current, choice))
            current.kind == KeyKind.Letter && flick != null -> listOf(longPress(current, flick))
            current.hasQuickChars() && flick != null -> listOf(quickCharAction(current, flick))
            else -> listOfNotNull(tap(current))
        }
        key = null
        armed = false
        flickChoice = null
        spaceSliding = false
        repeating = false
        clearArmed = false
        downNeighbors = ""
        downSample = null
        val haptic = if (actions.singleOrNull() == EngineAct.Backspace) HapticKind.Delete else null
        return GestureFrame(null, null, actions, haptic)
    }

    fun cancel(): GestureFrame {
        tracking = false
        key = null
        gestureMoved = false
        armed = false
        flickChoice = null
        spaceSliding = false
        cancelled = false
        repeating = false
        clearArmed = false
        downNeighbors = ""
        downSample = null
        return idle()
    }

    fun alarm(time: Long): GestureFrame {
        val current = key ?: return idle()
        if (!tracking) return idle()
        if (current.supportsLongPress() && !armed && flickChoice == null && !cancelled && time >= downAt + longMs) {
            armed = true
            choice = Choice.Symbol
            return frame(HapticKind.Arm)
        }
        if (current.kind == KeyKind.Backspace && !cancelled) {
            if (!repeating) {
                if (time < downAt + backspaceLongMs) return frame()
                repeating = true
                lastRepeat = time
                return backspaceDelete(current)
            }
            if (!clearArmed && time >= nextRepeatAt()) {
                lastRepeat = time
                return backspaceDelete(current)
            }
        }
        return frame()
    }

    fun nextAlarm(now: Long): Long? {
        val current = key ?: return null
        if (!tracking) return null
        if (current.supportsLongPress() && !armed && flickChoice == null && !cancelled) return downAt + longMs
        if (current.kind == KeyKind.Backspace && !cancelled && !clearArmed) {
            return nextRepeatAt().coerceAtLeast(now)
        }
        return null
    }

    private fun nextRepeatAt(): Long {
        if (!repeating) return downAt + backspaceLongMs
        return lastRepeat + repeatMs
    }

    private fun frame(haptic: HapticKind? = null): GestureFrame {
        val current = key
        val activeChoice = when {
            current != null && armed && current.supportsPopup() -> choice
            current != null && flickChoice != null && current.supportsPopup() -> flickChoice
            else -> null
        }
        return GestureFrame(
            pressedId = current?.id,
            popup = if (current != null && activeChoice != null) preview(current, activeChoice) else null,
            haptic = haptic,
            backspaceHint = when {
                current?.kind != KeyKind.Backspace || cancelled -> BackspaceHint.None
                clearArmed -> BackspaceHint.ClearArmed
                repeating -> BackspaceHint.Repeat
                else -> BackspaceHint.None
            },
            spaceSliding = spaceSliding,
        )
    }

    private fun retarget(
        target: KeyModel,
        touchNeighbors: String,
        touchSample: TouchSample?,
    ): GestureFrame {
        key = target
        cancelled = false
        choice = Choice.Symbol
        downNeighbors = touchNeighbors.ifEmpty { touchSample?.neighborText.orEmpty() }
        downSample = touchSample
        return GestureFrame(
            pressedId = target.id,
            popup = null,
            haptic = HapticKind.Step,
            tapPreview = TapPreview(target.id, target.label),
        )
    }

    private fun entersTargetCore(touchSample: TouchSample?): Boolean =
        touchSample == null ||
            (abs(touchSample.hitResidualX) <= 0.28f && abs(touchSample.hitResidualY) <= 0.30f)

    private val clearArmPx: Float
        get() = (swipePx * 2f).coerceAtLeast(56f)

    private val clearExitPx: Float
        get() = clearArmPx * 0.62f

    private val clearArmMaxX: Float
        get() = (swipePx * 1.1f).coerceAtLeast(28f)

    private val clearExitMaxX: Float
        get() = (swipePx * 1.45f).coerceAtLeast(clearArmMaxX)

    private val horizontalCancelPx: Float
        get() = (swipePx * 1.8f).coerceAtLeast(48f)

    private val downCancelPx: Float
        get() = (swipePx * 1.5f).coerceAtLeast(48f)

    private fun inClearArmZone(): Boolean =
        latestY - downY <= -clearArmPx && abs(latestX - downX) <= clearArmMaxX

    private fun backspaceDelete(current: KeyModel) = GestureFrame(
        pressedId = current.id,
        popup = null,
        actions = listOf(EngineAct.Backspace),
        haptic = HapticKind.Delete,
        backspaceHint = BackspaceHint.Repeat,
    )

    private fun preview(current: KeyModel, selected: Choice): PopupPreview = if (current.hasQuickChars()) {
        PopupPreview(
            keyId = current.id,
            lower = current.quickChars[2],
            upper = current.quickChars[0],
            symbol = current.quickChars[1],
            choice = selected,
        )
    } else {
        PopupPreview(
            keyId = current.id,
            lower = current.label.lowercase(),
            upper = current.label.uppercase(),
            symbol = current.hint.orEmpty(),
            choice = selected,
        )
    }

    private fun longPress(current: KeyModel, selected: Choice) =
        EngineAct.Letter(current.label.first(), selected, current.hint.orEmpty(), downNeighbors, downSample)

    private fun quickCharAction(current: KeyModel, selected: Choice): EngineAct.Direct {
        val text = when (selected) {
            Choice.Upper -> current.quickChars[0]
            Choice.Symbol -> current.quickChars[1]
            Choice.Lower -> current.quickChars[2]
        }
        return EngineAct.Direct(text)
    }

    private fun KeyModel.hasQuickChars(): Boolean = kind == KeyKind.Char && quickChars.size == 3

    private fun KeyModel.supportsLongPress(): Boolean = kind == KeyKind.Letter

    private fun KeyModel.supportsPopup(): Boolean = kind == KeyKind.Letter || hasQuickChars()

    private fun downHaptic(target: KeyModel): HapticKind? = when (target.kind) {
        KeyKind.Backspace -> null
        KeyKind.Letter, KeyKind.Char -> HapticKind.Down
        KeyKind.Space,
        KeyKind.Enter,
        KeyKind.Shift,
        KeyKind.Language,
        KeyKind.PageLetters,
        KeyKind.PageNumber,
        KeyKind.PageSymbols,
        KeyKind.PageMore -> HapticKind.Action
    }

    private fun tap(current: KeyModel): EngineAct? = when (current.kind) {
        KeyKind.Letter -> EngineAct.Letter(current.label.first(), null, current.hint.orEmpty(), downNeighbors, downSample)
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
