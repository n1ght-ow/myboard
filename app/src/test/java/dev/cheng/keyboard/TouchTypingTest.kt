package dev.cheng.keyboard

import dev.cheng.keyboard.engine.EngineAct
import dev.cheng.keyboard.engine.EngineState
import dev.cheng.keyboard.engine.InputEngine
import dev.cheng.keyboard.engine.LatinDecoder
import dev.cheng.keyboard.engine.PinyinDecoder
import dev.cheng.keyboard.engine.spatialSubstitutionCost
import dev.cheng.keyboard.ime.gesture.KeyGesture
import dev.cheng.keyboard.ime.layout.HitContext
import dev.cheng.keyboard.ime.layout.NoPriors
import dev.cheng.keyboard.ime.layout.effectivePriors
import dev.cheng.keyboard.ime.layout.hitKey
import dev.cheng.keyboard.ime.layout.placeKeys
import dev.cheng.keyboard.ime.layout.touchSampleOf
import dev.cheng.keyboard.ime.touch.TouchCalibrator
import dev.cheng.keyboard.model.Choice
import dev.cheng.keyboard.model.EnterKind
import dev.cheng.keyboard.model.KeyKind
import dev.cheng.keyboard.model.KeyLikelihood
import dev.cheng.keyboard.model.KeyModel
import dev.cheng.keyboard.model.KeyboardPage
import dev.cheng.keyboard.model.Language
import dev.cheng.keyboard.model.LexRow
import dev.cheng.keyboard.model.MemoryLexicon
import dev.cheng.keyboard.model.TouchSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchTypingTest {
    private val placement = placeKeys(
        page = KeyboardPage.Letters,
        language = Language.English,
        widthPx = 1080f,
        keyHeightPx = 144f,
        gapX = 18f,
        gapY = 30f,
        sidePx = 12f,
    )

    @Test
    fun hitTestNeverDeadAcrossLetterPanel() {
        var y = 0f
        while (y <= placement.height) {
            var x = 0f
            while (x <= placement.width) {
                assertNotNull("Expected non-null hit at ($x, $y)", hitKey(placement.keys, x, y))
                x += 4f
            }
            y += 4f
        }
        val aKey = placement.keys.first { it.model.id == "l-a" }
        val lKey = placement.keys.first { it.model.id == "l-l" }
        assertEquals("l-a", hitKey(placement.keys, 4f, aKey.y + aKey.h * 0.5f)?.model?.id)
        assertEquals("l-l", hitKey(placement.keys, placement.width - 4f, lKey.y + lKey.h * 0.5f)?.model?.id)
    }

    @Test
    fun coreSafeBoxProtectsEveryKey() {
        val maxPriors = List(26) { 1f }
        for (key in placement.keys) {
            val priors = maxPriors.mapIndexed { idx, v ->
                val ch = key.model.label.firstOrNull()?.lowercaseChar()
                if (key.model.kind == KeyKind.Letter && ch == 'a' + idx) -0.35f else v
            }
            val ctx = HitContext(
                priors = priors,
                composing = true,
                hasCandidates = true,
                boardWidthPx = placement.width,
            )
            val points = listOf(
                (key.x + 0.50f * key.w) to (key.y + 0.50f * key.h),
                (key.x + 0.22f * key.w) to (key.y + 0.20f * key.h),
                (key.x + 0.78f * key.w) to (key.y + 0.20f * key.h),
                (key.x + 0.22f * key.w) to (key.y + 0.80f * key.h),
                (key.x + 0.78f * key.w) to (key.y + 0.80f * key.h),
            )
            for ((px, py) in points) {
                assertEquals(
                    "Core box point ($px, $py) must stay on ${key.model.id}",
                    key.model.id,
                    hitKey(placement.keys, px, py, ctx)?.model?.id,
                )
            }
        }
    }

    @Test
    fun dynamicPriorExpandsIntoGap() {
        val eKey = placement.keys.first { it.model.id == "l-e" }
        val rKey = placement.keys.first { it.model.id == "l-r" }
        val priors = FloatArray(26).also { it['e' - 'a'] = 1f }.toList()
        val gapMidX = (eKey.x + eKey.w + rKey.x) * 0.5f
        val cy = eKey.y + eKey.h * 0.5f
        val hit = hitKey(placement.keys, gapMidX, cy, HitContext(priors = priors))
        assertEquals("l-e", hit?.model?.id)
    }

    @Test
    fun coreBoxStopsDynamicExpansion() {
        val rKey = placement.keys.first { it.model.id == "l-r" }
        val priors = FloatArray(26).also { it['e' - 'a'] = 1f }.toList()
        val insideRCoreLeft = rKey.x + 0.22f * rKey.w + 0.5f
        val cy = rKey.y + rKey.h * 0.5f
        val hit = hitKey(placement.keys, insideRCoreLeft, cy, HitContext(priors = priors))
        assertEquals("l-r", hit?.model?.id)
    }

    @Test
    fun passwordDisablesDynamicPrior() {
        val rKey = placement.keys.first { it.model.id == "l-r" }
        val priors = FloatArray(26).also { it['e' - 'a'] = 1f }.toList()
        val rOuterEdgeX = rKey.x + 0.05f * rKey.w
        val cy = rKey.y + rKey.h * 0.5f
        assertEquals("l-e", hitKey(placement.keys, rOuterEdgeX, cy, HitContext(priors = priors))?.model?.id)
        assertEquals(
            "l-r",
            hitKey(placement.keys, rOuterEdgeX, cy, HitContext(priors = priors, password = true))?.model?.id,
        )
        assertEquals(
            "l-r",
            hitKey(placement.keys, rOuterEdgeX, cy, HitContext(priors = priors, noLearn = true))?.model?.id,
        )
    }

    @Test
    fun englishNextLetterPriorsAfterTim() {
        val latin = LatinDecoder().apply {
            load(sequenceOf("time" to 900_000, "times" to 400_000, "timber" to 8_000))
        }
        val priors = latin.nextLetterPriors("tim")
        assertTrue(priors['e' - 'a'] > 0.9f)
        assertTrue(priors['e' - 'a'] > priors['b' - 'a'])
        assertEquals(-0.20f, priors['w' - 'a'], 1e-4f)
        assertEquals(-0.20f, priors['r' - 'a'], 1e-4f)

        val afterGood = latin.nextLetterPriors("", prevWord = "good")
        assertTrue(afterGood['m' - 'a'] > 0.4f)
        assertEquals(0f, afterGood['z' - 'a'], 1e-4f)
    }

    @Test
    fun pinyinNextLetterPriorsAfterZhAndQ() {
        val decoder = PinyinDecoder(
            setOf("zha", "zhe", "zhi", "zhong", "zhu", "qi", "qu", "ni", "hao", "nv"),
        )
        val afterZh = decoder.nextLetterPriors("zh")
        for (vowel in "aeiou") {
            assertEquals(0.85f, afterZh[vowel - 'a'], 1e-4f)
        }
        assertEquals(-0.30f, afterZh['v' - 'a'], 1e-4f)

        val afterQ = decoder.nextLetterPriors("q")
        assertEquals(0.85f, afterQ['i' - 'a'], 1e-4f)
        assertEquals(0.85f, afterQ['u' - 'a'], 1e-4f)
        assertEquals(-0.30f, afterQ['a' - 'a'], 1e-4f)
    }

    @Test
    fun rolloverCommitsBothLetters() {
        val gesture = KeyGesture(slopPx = 16f, caseSlopPx = 34f, swipePx = 36f, verticalFlickPx = 46f)
        val nKey = KeyModel("l-n", KeyKind.Letter, "n", hint = ";")
        val iKey = KeyModel("l-i", KeyKind.Letter, "i", hint = "8")

        val downFrame = gesture.down(nKey, 500f, 350f, 0L)
        assertEquals("l-n", downFrame.tapPreview?.keyId)
        assertTrue(gesture.canRollover())

        val rollFrames = gesture.rollover(iKey, 750f, 70f, 25L)
        assertEquals(2, rollFrames.size)
        val firstAction = rollFrames[0].actions.single() as EngineAct.Letter
        assertEquals('n', firstAction.ch)
        assertEquals("l-i", rollFrames[1].tapPreview?.keyId)

        val upFrame = gesture.up()
        val secondAction = upFrame.actions.single() as EngineAct.Letter
        assertEquals('i', secondAction.ch)
    }

    @Test
    fun fastTapLockPreventsMicroSlideSwitch() {
        val gesture = KeyGesture(slopPx = 16f, caseSlopPx = 34f, swipePx = 36f, verticalFlickPx = 46f)
        val fKey = KeyModel("l-f", KeyKind.Letter, "f", hint = "#")
        val gKey = KeyModel("l-g", KeyKind.Letter, "g", hint = "%")

        // 40ms micro-slide of 20px (< 70ms lock) keeps original key 'f'
        gesture.down(fKey, 300f, 220f, 0L)
        gesture.move(320f, 220f, 40L, gKey)
        val lockedTap = gesture.up().actions.single() as EngineAct.Letter
        assertEquals('f', lockedTap.ch)

        // 90ms deliberate slide of 20px (>= 70ms) switches to 'g' and preserves new TouchSample
        val gSample = TouchSample(primary = 'g', alternatives = listOf(KeyLikelihood('h', 0.25f)))
        gesture.down(fKey, 300f, 220f, 0L)
        val switchedFrame = gesture.move(320f, 220f, 90L, gKey, gSample.neighborText, gSample)
        assertEquals("l-g", switchedFrame.pressedId)
        val switchedTap = gesture.up().actions.single() as EngineAct.Letter
        assertEquals('g', switchedTap.ch)
        assertEquals(gSample, switchedTap.sample)
    }

    @Test
    fun verticalFlickRequiresSteepLongGesture() {
        val gesture = KeyGesture(slopPx = 16f, caseSlopPx = 34f, swipePx = 36f, verticalFlickPx = 46f)
        val qKey = KeyModel("l-q", KeyKind.Letter, "q", hint = "1")

        // 60.8° (dy = -50, dx = 28 -> atan2(50, 28) = 60.75°), 100ms -> triggers Choice.Symbol
        gesture.down(qKey, 100f, 100f, 0L)
        val triggered = gesture.move(128f, 50f, 100L, null)
        assertEquals(Choice.Symbol, triggered.popup?.choice)
        assertEquals(Choice.Symbol, (gesture.up().actions.single() as EngineAct.Letter).force)

        // ~51° (dy = -50, dx = 40 -> atan2(50, 40) = 51.3°), 100ms -> does NOT trigger flick
        gesture.down(qKey, 100f, 100f, 0L)
        val shallow = gesture.move(140f, 50f, 100L, qKey)
        assertNull(shallow.popup)
        assertNull((gesture.up().actions.single() as EngineAct.Letter).force)

        // Steep 90° (dy = -50, dx = 0) but only 30ms (< 45ms) -> does NOT trigger flick
        gesture.down(qKey, 100f, 100f, 0L)
        val tooFast = gesture.move(100f, 50f, 30L, qKey)
        assertNull(tooFast.popup)
        assertNull((gesture.up().actions.single() as EngineAct.Letter).force)
    }

    @Test
    fun touchSampleAtCenterHasNoNeighbors() {
        val fKey = placement.keys.first { it.model.id == "l-f" }
        val sample = touchSampleOf(
            placement.keys,
            fKey.x + fKey.w * 0.5f,
            fKey.y + fKey.h * 0.5f,
            fKey.model,
        )
        assertNotNull(sample)
        assertTrue(sample!!.alternatives.isEmpty())
    }

    @Test
    fun touchSampleAtGapIncludesBothKeys() {
        val fKey = placement.keys.first { it.model.id == "l-f" }
        val gKey = placement.keys.first { it.model.id == "l-g" }
        val gapX = (fKey.x + fKey.w + gKey.x) * 0.5f
        val cy = fKey.y + fKey.h * 0.5f
        val sample = touchSampleOf(placement.keys, gapX, cy, fKey.model)
        assertNotNull(sample)
        assertEquals('f', sample!!.primary)
        val gAlt = sample.alternatives.firstOrNull { it.ch == 'g' }
        assertNotNull(gAlt)
        assertTrue("Expected low penalty for G at F/G gap, got ${gAlt!!.penalty}", gAlt.penalty < 0.15f)
    }

    @Test
    fun pinyinSpatialRecoveryUsesSamples() {
        val decoder = PinyinDecoder(setOf("ni", "ha", "hao", "ping"))
        decoder.lexicon = MemoryLexicon(
            listOf(
                LexRow("你", "ni", "ni", 1_800_000),
                LexRow("哈", "ha", "ha", 200_000),
                LexRow("平", "ping", "ping", 500_000),
                LexRow("你好", "ni hao", "nihao", 850_000),
            ),
        )
        val oKey = placement.keys.first { it.model.id == "l-o" }
        val pKey = placement.keys.first { it.model.id == "l-p" }
        val borderX = pKey.x + 2f
        val cy = pKey.y + pKey.h * 0.5f
        val pBorderSample = touchSampleOf(placement.keys, borderX, cy, pKey.model)
        assertNotNull(pBorderSample)
        assertTrue((pBorderSample!!.penaltyOf('o') ?: 1f) < 0.30f)
        assertTrue(oKey.x < pKey.x)

        val decoded = decoder.decode(
            raw = "nihap",
            fuzzy = false,
            touchSamples = listOf(null, null, null, null, pBorderSample),
        )
        assertEquals("你好", decoded.candidates.first().text)
    }

    @Test
    fun pinyinSoftFirstLetterRecoversBihao() {
        val decoder = PinyinDecoder(setOf("bi", "ni", "hao"))
        decoder.lexicon = MemoryLexicon(
            listOf(
                LexRow("比", "bi", "bi", 1_000_000),
                LexRow("好", "hao", "hao", 1_500_000),
                LexRow("比号", "bi hao", "bihao", 3_000),
                LexRow("你好", "ni hao", "nihao", 850_000),
            ),
        )
        val bKey = placement.keys.first { it.model.id == "l-b" }
        val nKey = placement.keys.first { it.model.id == "l-n" }
        val borderX = (bKey.x + bKey.w + nKey.x) * 0.5f - 2f
        val cy = bKey.y + bKey.h * 0.5f
        val borderSample = touchSampleOf(placement.keys, borderX, cy, bKey.model)
        assertNotNull(borderSample)

        val borderDecoded = decoder.decode(
            raw = "bihao",
            fuzzy = false,
            touchSamples = listOf(borderSample, null, null, null, null),
        )
        assertEquals("你好", borderDecoded.candidates.first().text)

        // Center tap on 'b' does NOT soft-correct 'bihao' to '你好'
        val centerSample = touchSampleOf(
            placement.keys,
            bKey.x + bKey.w * 0.5f,
            cy,
            bKey.model,
        )
        val centerDecoded = decoder.decode(
            raw = "bihao",
            fuzzy = false,
            touchSamples = listOf(centerSample, null, null, null, null),
        )
        assertEquals("比号", centerDecoded.candidates.first().text)
    }

    @Test
    fun calibratorConvergesAndClamps() {
        val calibrator = TouchCalibrator()
        assertEquals(TouchCalibrator.BASE_DY, calibrator.bias(isPrivate = false).dyRatio, 1e-4f)

        repeat(20) {
            repeat(4) { idx ->
                calibrator.record(
                    TouchSample(
                        primary = 'a' + idx,
                        residualX = 0.05f,
                        residualY = 0.22f,
                        leftSide = idx % 2 == 0,
                    ),
                )
            }
            assertTrue(calibrator.commitIfClean())
        }
        assertTrue(calibrator.shouldPersist())
        val learned = calibrator.bias(isPrivate = false)
        assertTrue(learned.dyRatio > TouchCalibrator.BASE_DY)
        assertTrue(learned.dyRatio <= 0.15f)

        // Private mode ignores learned offsets and keeps static BASE_DY
        val priv = calibrator.bias(isPrivate = true)
        assertEquals(0f, priv.leftDxRatio, 1e-4f)
        assertEquals(0f, priv.rightDxRatio, 1e-4f)
        assertEquals(TouchCalibrator.BASE_DY, priv.dyRatio, 1e-4f)

        calibrator.reset()
        assertFalse(calibrator.shouldPersist())
        assertEquals(TouchCalibrator.BASE_DY, calibrator.bias(isPrivate = false).dyRatio, 1e-4f)
    }

    @Test
    fun noLearnProducesNoPriors() {
        val rawPriors = List(26) { 0.9f }
        val passwordCtx = HitContext(priors = rawPriors, password = true)
        val noLearnCtx = HitContext(priors = rawPriors, noLearn = true)
        assertEquals(NoPriors, passwordCtx.effectivePriors)
        assertEquals(NoPriors, noLearnCtx.effectivePriors)
    }

    @Test
    fun englishSpatialSampleAndUriModeBoundary() {
        val borderSample = TouchSample(primary = 'p', alternatives = listOf(KeyLikelihood('o', 0.1f)))
        val centerSample = TouchSample(primary = 'p', alternatives = emptyList())
        assertEquals(0.40f, spatialSubstitutionCost('o', borderSample, legacySpatial = false) ?: -1f, 1e-4f)
        assertNull(spatialSubstitutionCost('o', centerSample, legacySpatial = true))
        assertEquals(0.78f, spatialSubstitutionCost('o', null, legacySpatial = true) ?: -1f, 1e-4f)

        val latin = LatinDecoder().apply {
            load(sequenceOf("hello" to 800_000, "hellp" to 0))
        }
        val engine = InputEngine(latin)
        var normalState = EngineState(language = Language.English)
        for (ch in "hell") {
            normalState = engine.reduce(normalState, EngineAct.Letter(ch, null, ""), EnterKind.Newline).state
        }
        normalState = engine.reduce(
            normalState,
            EngineAct.Letter('p', null, "", neighbors = "o", sample = borderSample),
            EnterKind.Newline,
        ).state
        assertEquals(5, normalState.touchSamples.size)
        assertEquals(borderSample, normalState.touchSamples.last())
        assertEquals("hello", normalState.candidates.first().text)

        var uriState = EngineState(language = Language.English, uriOrEmail = true)
        for (ch in "hell") {
            uriState = engine.reduce(uriState, EngineAct.Letter(ch, null, ""), EnterKind.Go).state
        }
        uriState = engine.reduce(
            uriState,
            EngineAct.Letter('p', null, "", neighbors = "o", sample = borderSample),
            EnterKind.Go,
        ).state
        assertTrue(uriState.touchSamples.all { it == null })
    }
}
