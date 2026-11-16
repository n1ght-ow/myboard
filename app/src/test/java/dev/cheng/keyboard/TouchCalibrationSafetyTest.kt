package dev.cheng.keyboard

import dev.cheng.keyboard.ime.touch.TouchCalibrator
import dev.cheng.keyboard.model.KeyLikelihood
import dev.cheng.keyboard.model.TouchSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchCalibrationSafetyTest {
    private val cleanSample = TouchSample(
        primary = 'a',
        residualX = 0.05f,
        residualY = 0.22f,
        leftSide = true,
    )

    @Test
    fun dirtySegmentStaysExcludedUntilCommitThenFreshWordLearns() {
        val calibrator = TouchCalibrator()
        repeat(3) { calibrator.record(cleanSample) }
        calibrator.markDirty()
        repeat(4) { calibrator.record(cleanSample) }

        assertFalse(calibrator.commitIfClean())
        assertEquals(Triple(0f, 0f, 0f), calibrator.snapshot())
        assertFalse(calibrator.shouldPersist())

        repeat(3) { calibrator.record(cleanSample) }
        assertTrue(calibrator.commitIfClean())
        assertEquals(0.006f, calibrator.snapshot().first, 1e-6f)
        assertEquals(0.018f, calibrator.snapshot().third, 1e-6f)
        assertFalse(calibrator.commitIfClean())
    }

    @Test
    fun shortSegmentIsClearedAtCommitBoundary() {
        val calibrator = TouchCalibrator()
        repeat(2) { calibrator.record(cleanSample) }
        assertFalse(calibrator.commitIfClean())
        calibrator.record(cleanSample)
        assertFalse(calibrator.commitIfClean())
        assertEquals(Triple(0f, 0f, 0f), calibrator.snapshot())

        repeat(3) { calibrator.record(cleanSample) }
        assertTrue(calibrator.commitIfClean())
    }

    @Test
    fun discardPendingPreservesLearnedOffsetsAndPersistenceState() {
        val calibrator = TouchCalibrator()
        repeat(20) {
            repeat(3) { calibrator.record(cleanSample) }
            assertTrue(calibrator.commitIfClean())
        }
        val learned = calibrator.snapshot()
        assertTrue(calibrator.shouldPersist())

        repeat(3) { calibrator.record(cleanSample) }
        calibrator.discardPending()
        assertEquals(learned, calibrator.snapshot())
        assertTrue(calibrator.shouldPersist())
        assertFalse(calibrator.commitIfClean())

        calibrator.markDirty()
        calibrator.discardPending()
        repeat(3) { calibrator.record(cleanSample) }
        assertTrue(calibrator.commitIfClean())
        assertTrue(calibrator.snapshot().first > learned.first)
        calibrator.markPersisted()
        assertFalse(calibrator.shouldPersist())
    }

    @Test
    fun invalidSamplesCannotCompleteALearningSegment() {
        val invalidSamples = listOf(
            cleanSample.copy(residualX = Float.NaN),
            cleanSample.copy(residualX = Float.POSITIVE_INFINITY),
            cleanSample.copy(residualX = Float.NEGATIVE_INFINITY),
            cleanSample.copy(residualY = Float.NaN),
            cleanSample.copy(residualY = Float.POSITIVE_INFINITY),
            cleanSample.copy(residualY = Float.NEGATIVE_INFINITY),
            cleanSample.copy(residualX = 0.36f),
            cleanSample.copy(residualY = -0.36f),
            cleanSample.copy(primary = null),
            cleanSample.copy(primary = '1'),
            cleanSample.copy(primary = ' '),
            cleanSample.copy(primary = '!'),
            cleanSample.copy(alternatives = listOf(KeyLikelihood('b', 0f))),
            cleanSample.copy(alternatives = listOf(KeyLikelihood('b', 0.20f))),
            cleanSample.copy(alternatives = listOf(KeyLikelihood('b', -0.1f))),
            cleanSample.copy(alternatives = listOf(KeyLikelihood('b', 0.8f), KeyLikelihood('c', 0.1f))),
        )
        for (sample in invalidSamples) {
            val calibrator = TouchCalibrator()
            repeat(2) { calibrator.record(cleanSample) }
            repeat(3) { calibrator.record(sample) }
            assertFalse("Invalid sample was accepted: $sample", calibrator.commitIfClean())
            assertEquals(Triple(0f, 0f, 0f), calibrator.snapshot())
        }
    }

    @Test
    fun onlyFiniteDistinctLetterAlternativesWithinThresholdExcludeSamples() {
        val allowedAlternatives = listOf(
            KeyLikelihood('a', 0f),
            KeyLikelihood('A', 0f),
            KeyLikelihood('!', 0f),
            KeyLikelihood('1', 0f),
            KeyLikelihood('b', Float.NaN),
            KeyLikelihood('c', Float.NEGATIVE_INFINITY),
            KeyLikelihood('d', Float.POSITIVE_INFINITY),
            KeyLikelihood('e', 0.2001f),
        )
        val calibrator = TouchCalibrator()
        repeat(3) { calibrator.record(cleanSample.copy(alternatives = allowedAlternatives)) }
        assertTrue(calibrator.commitIfClean())
        assertEquals(0.006f, calibrator.snapshot().first, 1e-6f)
    }

    @Test
    fun nonFiniteLoadedValuesSanitizeAndFiniteValuesClamp() {
        val calibrator = TouchCalibrator()
        for (invalid in listOf(Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY)) {
            calibrator.load(invalid, invalid, invalid)
            assertEquals(Triple(0f, 0f, 0f), calibrator.snapshot())
        }
        calibrator.load(Float.NaN, 0.03f, Float.POSITIVE_INFINITY)
        assertEquals(Triple(0f, 0.03f, 0f), calibrator.snapshot())
        calibrator.load(1f, -1f, 1f)
        assertEquals(Triple(0.06f, -0.06f, 0.08f), calibrator.snapshot())
        calibrator.load(-1f, 1f, -1f)
        assertEquals(Triple(-0.06f, 0.06f, -0.09f), calibrator.snapshot())
    }

    @Test
    fun convergencePrivateBiasAndResetRemainCompatible() {
        val calibrator = TouchCalibrator()
        assertEquals(TouchCalibrator.BASE_DY, calibrator.bias(isPrivate = false).dyRatio, 1e-4f)
        repeat(20) {
            repeat(4) { index ->
                calibrator.record(cleanSample.copy(primary = 'a' + index, leftSide = index % 2 == 0))
            }
            assertTrue(calibrator.commitIfClean())
        }
        assertTrue(calibrator.shouldPersist())
        val learned = calibrator.bias(isPrivate = false)
        assertEquals(0.05f * (1f - Math.pow(0.88, 20.0).toFloat()), learned.leftDxRatio, 1e-6f)
        assertEquals(learned.leftDxRatio, learned.rightDxRatio, 1e-6f)
        assertTrue(learned.dyRatio > TouchCalibrator.BASE_DY)
        assertTrue(learned.dyRatio <= 0.15f)
        val privateBias = calibrator.bias(isPrivate = true)
        assertEquals(0f, privateBias.leftDxRatio, 1e-4f)
        assertEquals(0f, privateBias.rightDxRatio, 1e-4f)
        assertEquals(TouchCalibrator.BASE_DY, privateBias.dyRatio, 1e-4f)

        calibrator.reset()
        assertEquals(Triple(0f, 0f, 0f), calibrator.snapshot())
        assertFalse(calibrator.shouldPersist())
        assertEquals(TouchCalibrator.BASE_DY, calibrator.bias(isPrivate = false).dyRatio, 1e-4f)
    }

    @Test
    fun samplesBeyondTheFirst64DoNotChangeLearning() {
        val calibrator = TouchCalibrator()
        val reference = TouchCalibrator()
        repeat(64) {
            calibrator.record(cleanSample)
            reference.record(cleanSample)
        }
        repeat(128) { calibrator.record(cleanSample.copy(residualX = -0.3f, residualY = -0.3f)) }

        assertTrue(reference.commitIfClean())
        assertTrue(calibrator.commitIfClean())
        assertEquals(reference.snapshot(), calibrator.snapshot())
        repeat(3) { calibrator.record(cleanSample.copy(leftSide = false)) }
        assertTrue(calibrator.commitIfClean())
        assertTrue(calibrator.snapshot().second > 0f)
    }
}
