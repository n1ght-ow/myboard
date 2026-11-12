package dev.cheng.keyboard.ime.touch

import dev.cheng.keyboard.model.TouchBias
import dev.cheng.keyboard.model.TouchSample
import kotlin.math.abs

class TouchCalibrator {
    private var learnedLeft = 0f
    private var learnedRight = 0f
    private var learnedDy = 0f
    private val samples = ArrayList<TouchSample>(16)
    private var dirty = false
    private var commitsSinceSave = 0

    companion object {
        const val BASE_DY = 0.07f
        private const val ALPHA = 0.12f
        private const val MIN_SAMPLES = 3
        private const val MAX_RESIDUAL = 0.35f
        private const val PERSIST_EVERY = 20
    }

    fun load(left: Float, right: Float, dy: Float) {
        learnedLeft = left.coerceIn(-0.06f, 0.06f)
        learnedRight = right.coerceIn(-0.06f, 0.06f)
        learnedDy = dy.coerceIn(-0.09f, 0.08f)
    }

    fun bias(isPrivate: Boolean): TouchBias = if (isPrivate) {
        TouchBias(dyRatio = BASE_DY)
    } else {
        TouchBias(learnedLeft, learnedRight, (BASE_DY + learnedDy).coerceIn(-0.02f, 0.15f))
    }

    fun record(sample: TouchSample) {
        if (dirty) {
            if (samples.isNotEmpty()) return
            dirty = false
        }
        if (abs(sample.residualX) > MAX_RESIDUAL) return
        if (abs(sample.residualY) > MAX_RESIDUAL) return
        samples += sample
    }

    fun markDirty() {
        dirty = true
        samples.clear()
    }

    fun commitIfClean(): Boolean {
        if (dirty || samples.size < MIN_SAMPLES) {
            samples.clear()
            dirty = false
            return false
        }
        val left = samples.filter { it.leftSide }.map { it.residualX }
        val right = samples.filterNot { it.leftSide }.map { it.residualX }
        val dy = samples.map { it.residualY }
        if (left.isNotEmpty()) learnedLeft = ema(learnedLeft, median(left), ALPHA)
        if (right.isNotEmpty()) learnedRight = ema(learnedRight, median(right), ALPHA)
        learnedDy = ema(learnedDy, median(dy) - BASE_DY, ALPHA)
        learnedLeft = learnedLeft.coerceIn(-0.06f, 0.06f)
        learnedRight = learnedRight.coerceIn(-0.06f, 0.06f)
        learnedDy = learnedDy.coerceIn(-0.09f, 0.08f)
        samples.clear()
        dirty = false
        commitsSinceSave++
        return true
    }

    fun shouldPersist(): Boolean = commitsSinceSave >= PERSIST_EVERY

    fun markPersisted() {
        commitsSinceSave = 0
    }

    fun snapshot(): Triple<Float, Float, Float> = Triple(learnedLeft, learnedRight, learnedDy)

    fun reset() {
        learnedLeft = 0f
        learnedRight = 0f
        learnedDy = 0f
        samples.clear()
        dirty = false
        commitsSinceSave = 0
    }

    private fun ema(previous: Float, target: Float, alpha: Float): Float =
        previous * (1f - alpha) + target * alpha

    private fun median(values: List<Float>): Float {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2f else sorted[mid]
    }
}
