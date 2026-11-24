package dev.cheng.keyboard.ime.gesture

data class KeyPointerChange(
    val id: Long,
    val pressed: Boolean,
    val down: Boolean = false,
    val x: Float = 0f,
    val y: Float = 0f,
    val time: Long = 0L,
    val cancelled: Boolean = false,
)

/** Tracks physical presses, so a finger already settled by rollover cannot type twice. */
class KeyPointerTracker {
    private var activeId: Long? = null
    private val waiting = linkedMapOf<Long, KeyPointerChange>()

    fun process(
        changes: List<KeyPointerChange>,
        onDown: (KeyPointerChange) -> Boolean,
        onRollover: (KeyPointerChange) -> Boolean,
        onMove: (KeyPointerChange) -> Unit,
        onUp: () -> Unit,
        onCancel: () -> Unit,
    ) {
        if (changes.any { it.cancelled }) {
            waiting.clear()
            if (activeId != null) onCancel()
            activeId = null
            return
        }
        waiting.keys.removeAll { id -> changes.none { it.id == id && it.pressed } }
        val active = activeId
        var movedId: Long? = null
        if (active != null) {
            val change = changes.firstOrNull { it.id == active }
            when {
                change == null -> {
                    onCancel()
                    activeId = null
                    waiting.clear()
                    return
                }
                !change.pressed -> {
                    onMove(change)
                    onUp()
                    activeId = null
                }
                else -> {
                    onMove(change)
                    movedId = active
                }
            }
        }

        // Only a physical down can enter the queue; a queued press can replace a yielding gesture.
        for (change in changes) {
            if (change.down && change.pressed && change.id != activeId) waiting[change.id] = change
        }
        var started = false
        if (activeId == null) {
            while (waiting.isNotEmpty()) {
                val press = waiting.values.first()
                waiting.remove(press.id)
                if (onDown(press)) {
                    activeId = press.id
                    started = true
                    break
                }
            }
        }

        for (change in changes) {
            if (!change.down || !change.pressed || change.id !in waiting) continue
            if (onRollover(change)) {
                waiting.remove(change.id)
                activeId = change.id
                started = true
            }
        }
        if (!started && activeId != null) {
            val held = waiting.values.firstOrNull { press ->
                changes.any { it.id == press.id && it.pressed && !it.down }
            }
            if (held != null && onRollover(held)) {
                waiting.remove(held.id)
                activeId = held.id
                started = true
            }
        }
        val current = changes.firstOrNull { it.id == activeId }
        if (current != null && current.id != movedId && (!started || !current.down)) onMove(current)
    }
}
