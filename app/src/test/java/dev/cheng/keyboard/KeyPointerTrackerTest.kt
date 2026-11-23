package dev.cheng.keyboard

import dev.cheng.keyboard.engine.EngineAct
import dev.cheng.keyboard.ime.gesture.GestureFrame
import dev.cheng.keyboard.ime.gesture.KeyGesture
import dev.cheng.keyboard.ime.gesture.KeyPointerChange
import dev.cheng.keyboard.ime.gesture.KeyPointerTracker
import dev.cheng.keyboard.model.KeyKind
import dev.cheng.keyboard.model.KeyModel
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyPointerTrackerTest {
    private class Typist {
        val tracker = KeyPointerTracker()
        val gesture = KeyGesture(16f, 34f, 36f)
        val text = StringBuilder()
        var allowRollover = true
        var cancels = 0
        var now = 0L

        fun event(vararg changes: KeyPointerChange) {
            now += 20L
            fun key(id: Long): KeyModel {
                val ch = "nihao"[(id - 1L).toInt() % 5]
                return KeyModel("l-$ch", KeyKind.Letter, ch.toString(), hint = "1")
            }
            fun accept(frame: GestureFrame) {
                frame.actions.filterIsInstance<EngineAct.Letter>().forEach { text.append(it.ch) }
            }
            tracker.process(
                changes.toList(),
                onDown = { accept(gesture.down(key(it.id), it.x, it.y, it.time.takeIf { at -> at > 0 } ?: now)); true },
                onRollover = {
                    val frames = if (allowRollover) gesture.rollover(key(it.id), it.x, it.y, now) else emptyList()
                    frames.forEach(::accept)
                    frames.isNotEmpty()
                },
                onMove = {},
                onUp = { accept(gesture.up()) },
                onCancel = { cancels++; gesture.cancel() },
            )
        }
    }

    private fun down(id: Long) = KeyPointerChange(id, pressed = true, down = true)
    private fun held(id: Long) = KeyPointerChange(id, pressed = true)
    private fun up(id: Long) = KeyPointerChange(id, pressed = false)

    @Test
    fun settledFirstFingerCannotTypeAgainAfterSecondFingerLifts() {
        val typist = Typist()
        typist.event(down(1))
        typist.event(held(1), down(2))
        typist.event(held(1), up(2))
        repeat(4) { typist.event(held(1)) }
        typist.event(up(1))
        assertEquals("ni", typist.text.toString())
    }

    @Test
    fun bothLiftOrdersCommitExactlyOnce() {
        for (secondLiftsFirst in listOf(false, true)) {
            val typist = Typist()
            typist.event(down(1))
            typist.event(held(1), down(2))
            if (secondLiftsFirst) {
                typist.event(held(1), up(2))
                typist.event(up(1))
            } else {
                typist.event(up(1), held(2))
                typist.event(up(2))
            }
            assertEquals("ni", typist.text.toString())
        }
    }

    @Test
    fun sameFrameReleasePrecedesNextDown() {
        val typist = Typist()
        typist.event(down(1))
        typist.event(up(1), down(2))
        typist.event(up(2))
        assertEquals("ni", typist.text.toString())
    }

    @Test
    fun threeOverlappingPressesSurviveEveryReleaseOrder() {
        val orders = listOf(listOf(1L, 2L, 3L), listOf(1L, 3L, 2L), listOf(2L, 1L, 3L),
            listOf(2L, 3L, 1L), listOf(3L, 1L, 2L), listOf(3L, 2L, 1L))
        for (order in orders) {
            val typist = Typist()
            typist.event(down(1))
            typist.event(held(1), down(2))
            typist.event(held(1), held(2), down(3))
            val remaining = linkedSetOf(1L, 2L, 3L)
            for (id in order) {
                typist.event(*(remaining.map { if (it == id) up(it) else held(it) }.toTypedArray()))
                remaining.remove(id)
                if (remaining.isNotEmpty()) typist.event(*remaining.map(::held).toTypedArray())
            }
            assertEquals("release order $order", "nih", typist.text.toString())
        }
    }

    @Test
    fun deferredUnsettledFingerStartsImmediatelyWhenActiveFingerLifts() {
        val typist = Typist().apply { allowRollover = false }
        typist.event(down(1))
        typist.event(held(1), down(2))
        typist.event(up(1), held(2))
        typist.event(up(2))
        assertEquals("ni", typist.text.toString())
    }

    @Test
    fun deferredFingerReleasedBeforeAdoptionDoesNotLeavePhantomPress() {
        val typist = Typist().apply { allowRollover = false }
        typist.event(down(1))
        typist.event(held(1), down(2))
        typist.event(held(1), up(2))
        typist.event(up(1))
        typist.event(down(3))
        typist.event(up(3))
        assertEquals("nh", typist.text.toString())
    }

    @Test
    fun simultaneousDownsAndReusedPointerIdAreIndependentPresses() {
        val typist = Typist()
        typist.event(down(1), down(2))
        typist.event(up(1), up(2))
        typist.event(down(1))
        typist.event(up(1))
        assertEquals("nin", typist.text.toString())
    }

    @Test
    fun missingActivePointerCancelsAndDoesNotAdoptSettledFinger() {
        val typist = Typist()
        typist.event(down(1))
        typist.event(held(1), down(2))
        typist.event(held(1))
        typist.event(up(1))
        typist.event(down(3))
        typist.event(up(3))
        assertEquals(1, typist.cancels)
        assertEquals("nh", typist.text.toString())
    }

    @Test
    fun cancelledPointerCallsCancelWithoutReleasingTheGesture() {
        val tracker = KeyPointerTracker()
        val trace = mutableListOf<String>()
        fun event(vararg changes: KeyPointerChange) = tracker.process(
            changes.toList(),
            onDown = { trace += "down:${it.id}"; true },
            onRollover = { false },
            onMove = { trace += "move:${it.pressed}" },
            onUp = { trace += "up" },
            onCancel = { trace += "cancel" },
        )
        event(down(1))
        trace.clear()
        event(up(1).copy(cancelled = true))
        assertEquals(listOf("cancel"), trace)
    }

    @Test
    fun releaseCoordinatesReachMoveAsUnpressedBeforeUp() {
        val tracker = KeyPointerTracker()
        val trace = mutableListOf<String>()
        fun event(vararg changes: KeyPointerChange) = tracker.process(
            changes.toList(),
            onDown = { true },
            onRollover = { false },
            onMove = { trace += "move:${it.pressed}:${it.x}" },
            onUp = { trace += "up" },
            onCancel = { trace += "cancel" },
        )
        event(down(1))
        trace.clear()
        event(up(1).copy(x = 120f, time = 90L))
        assertEquals(listOf("move:false:120.0", "up"), trace)
    }

    @Test
    fun cancelledActivePointerDropsWaitingFingerWithoutAdoptingIt() {
        val tracker = KeyPointerTracker()
        var starts = 0
        var cancels = 0
        fun event(vararg changes: KeyPointerChange) = tracker.process(
            changes.toList(),
            onDown = { starts++; true },
            onRollover = { false },
            onMove = {}, onUp = {}, onCancel = { cancels++ },
        )
        event(down(1))
        event(held(1), down(2))
        event(up(1).copy(cancelled = true), held(2))
        event(held(2))
        assertEquals(1, starts)
        assertEquals(1, cancels)
    }

    @Test
    fun releaseCoordinatesAreDeliveredBeforeUpAndNextDown() {
        val tracker = KeyPointerTracker()
        val trace = mutableListOf<String>()
        fun event(vararg changes: KeyPointerChange) = tracker.process(
            changes.toList(),
            onDown = { trace += "down:${it.id}"; true },
            onRollover = { false },
            onMove = { trace += "move:${it.id}:${it.x}:${it.time}" },
            onUp = { trace += "up" },
            onCancel = { trace += "cancel" },
        )
        event(down(1))
        trace.clear()
        event(up(1).copy(x = 120f, time = 90L), down(2).copy(time = 90L))
        assertEquals(listOf("move:1:120.0:90", "up", "down:2"), trace)
    }

    @Test
    fun deferredPressRetainsPhysicalDownCoordinatesAndTime() {
        val tracker = KeyPointerTracker()
        val starts = mutableListOf<KeyPointerChange>()
        fun event(vararg changes: KeyPointerChange) = tracker.process(
            changes.toList(),
            onDown = { starts += it; true },
            onRollover = { false },
            onMove = {}, onUp = {}, onCancel = {},
        )
        val original = down(2).copy(x = 10f, y = 20f, time = 30L)
        event(down(1))
        event(held(1), original)
        event(held(1), held(2).copy(x = 70f, y = 80f, time = 100L))
        event(up(1), held(2).copy(x = 90f, y = 110f, time = 200L))
        assertEquals(original, starts.last())
    }

    @Test
    fun deliberateSlideCanFinishOnReleaseWithoutAnIntermediateMoveEvent() {
        val tracker = KeyPointerTracker()
        val gesture = KeyGesture(16f, 34f, 36f)
        val f = KeyModel("l-f", KeyKind.Letter, "f", hint = "#")
        val g = KeyModel("l-g", KeyKind.Letter, "g", hint = "%")
        val text = StringBuilder()
        fun event(change: KeyPointerChange) = tracker.process(
            listOf(change),
            onDown = { gesture.down(f, it.x, it.y, it.time); true },
            onRollover = { false },
            onMove = { gesture.move(it.x, it.y, it.time, if (it.x >= 100f) g else f) },
            onUp = { gesture.up().actions.filterIsInstance<EngineAct.Letter>().forEach { text.append(it.ch) } },
            onCancel = { gesture.cancel() },
        )
        event(down(1).copy(x = 50f, y = 50f, time = 1L))
        event(up(1).copy(x = 150f, y = 50f, time = 100L))
        assertEquals("g", text.toString())
    }

    @Test
    fun movingFirstFingerIsResolvedBeforeRollover() {
        val tracker = KeyPointerTracker()
        val trace = mutableListOf<String>()
        fun event(vararg changes: KeyPointerChange) = tracker.process(
            changes.toList(),
            onDown = { true },
            onRollover = { trace += "roll:${it.id}"; true },
            onMove = { trace += "move:${it.id}:${it.x}" },
            onUp = {}, onCancel = {},
        )
        event(down(1))
        event(held(1).copy(x = 140f), down(2))
        assertEquals(listOf("move:1:140.0", "roll:2"), trace)
    }
}
