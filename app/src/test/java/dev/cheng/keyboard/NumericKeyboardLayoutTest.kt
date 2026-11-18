package dev.cheng.keyboard

import dev.cheng.keyboard.engine.Cmd
import dev.cheng.keyboard.engine.EngineAct
import dev.cheng.keyboard.engine.EngineState
import dev.cheng.keyboard.engine.InputEngine
import dev.cheng.keyboard.engine.LatinDecoder
import dev.cheng.keyboard.ime.layout.hitKey
import dev.cheng.keyboard.ime.layout.placeKeys
import dev.cheng.keyboard.model.EnterKind
import dev.cheng.keyboard.model.KeyKind
import dev.cheng.keyboard.model.KeyboardPage
import dev.cheng.keyboard.model.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NumericKeyboardLayoutTest {
    private val widths = listOf(320f, 360f, 412f, 600f, 840f)
    private val keyHeight = 52f
    private val gapX = 6f
    private val gapY = 10f
    private val side = 4f

    @Test
    fun symbolPageReturnsToThePageThatOpenedItEvenAfterAnInsertion() {
        val engine = InputEngine(LatinDecoder())
        for (initial in listOf(KeyboardPage.Letters, KeyboardPage.Number)) {
            val start = EngineState(page = initial)
            fun reduce(state: EngineState, action: EngineAct) = engine.reduce(state, action, EnterKind.Done)
            val symbols = reduce(start, EngineAct.Page(KeyboardPage.Symbols)).state
            assertEquals(KeyboardPage.Symbols, symbols.page)
            assertEquals(initial, reduce(symbols, EngineAct.CloseSymbols).state.page)

            val inserted = reduce(symbols, EngineAct.Direct("+"))
            assertEquals(listOf<Cmd>(Cmd.Commit("+")), inserted.cmds)
            assertEquals(KeyboardPage.Symbols, inserted.state.page)
            assertEquals(initial, reduce(inserted.state, EngineAct.CloseSymbols).state.page)

            val explicitNumber = reduce(symbols, EngineAct.Page(KeyboardPage.Number)).state
            assertEquals(KeyboardPage.Number, explicitNumber.page)
            val reopened = reduce(explicitNumber, EngineAct.Page(KeyboardPage.Symbols)).state
            assertEquals(KeyboardPage.Number, reduce(reopened, EngineAct.CloseSymbols).state.page)
        }
    }

    @Test
    fun numberPageUsesStableFiveColumnGridAcrossWidths() {
        val expectedIds = listOf(
            "np-plus", "num-1", "num-2", "num-3", "backspace",
            "np-minus", "num-4", "num-5", "num-6", "space",
            "np-slash", "num-7", "num-8", "num-9", "to-symbols",
            "to-letters", "comma", "num-0", "period", "enter",
        )

        for (width in widths) {
            for (language in Language.entries) {
                val layout = numberLayout(width, language)

                assertEquals(expectedIds, layout.keys.map { it.model.id })
                assertEquals(4, layout.keys.map { it.y }.distinct().size)
                assertEquals(5, layout.keys.groupBy { it.y }.values.map { it.size }.distinct().single())
                assertEquals(
                    listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
                    layout.keys.filter { it.model.id.startsWith("num-") }.map { it.model.label },
                )
                assertEquals(10, layout.keys.filter { it.model.id.startsWith("num-") }.map { it.model.id }.distinct().size)

                val digits = (0..9).associateWith { digit ->
                    layout.keys.first { it.model.id == "num-$digit" }
                }
                assertSameColumn(listOf(digits.getValue(1), digits.getValue(4), digits.getValue(7)))
                assertSameColumn(listOf(digits.getValue(2), digits.getValue(5), digits.getValue(8), digits.getValue(0)))
                assertSameColumn(listOf(digits.getValue(3), digits.getValue(6), digits.getValue(9)))
                val comma = layout.keys.first { it.model.id == "comma" }
                val zero = digits.getValue(0)
                val period = layout.keys.first { it.model.id == "period" }
                assertEquals(digits.getValue(1).x, comma.x, 0.01f)
                assertEquals(digits.getValue(1).w, comma.w, 0.01f)
                assertEquals(digits.getValue(2).x, zero.x, 0.01f)
                assertEquals(digits.getValue(2).w, zero.w, 0.01f)
                assertEquals(digits.getValue(3).x, period.x, 0.01f)
                assertEquals(digits.getValue(3).w, period.w, 0.01f)
                assertEquals((keyHeight + gapY) * 4f - gapY, layout.height, 0.01f)
                assertEquals(
                    numberLayout(width, Language.Chinese).height,
                    placeKeys(KeyboardPage.Letters, Language.Chinese, width, keyHeight, gapX, gapY, side).height,
                    0.01f,
                )
            }
        }
    }

    @Test
    fun everyNumberPageCenterHitsItsOwnKeyWithoutOverlapOrClipping() {
        for (width in widths) {
            for (language in Language.entries) {
                val layout = numberLayout(width, language)

                for (key in layout.keys) {
                    assertTrue("${key.model.id} starts outside the board", key.x >= side - EPSILON)
                    assertTrue("${key.model.id} ends outside the board", key.x + key.w <= width - side + EPSILON)
                    assertTrue("${key.model.id} starts above the board", key.y >= -EPSILON)
                    assertTrue("${key.model.id} ends below the board", key.y + key.h <= layout.height + EPSILON)
                    assertEquals(keyHeight, key.h, EPSILON)

                    val hit = hitKey(
                        layout.keys,
                        key.x + key.w * 0.5f,
                        key.y + key.h * 0.5f,
                    )
                    assertNotNull("No key hit at the center of ${key.model.id}", hit)
                    assertEquals(key.model.id, hit?.model?.id)
                }

                for (index in layout.keys.indices) {
                    val key = layout.keys[index]
                    for (otherIndex in index + 1 until layout.keys.size) {
                        val other = layout.keys[otherIndex]
                        val separated =
                            key.x + key.w <= other.x + EPSILON ||
                                other.x + other.w <= key.x + EPSILON ||
                                key.y + key.h <= other.y + EPSILON ||
                                other.y + other.h <= key.y + EPSILON
                        assertTrue("${key.model.id} overlaps ${other.model.id} at ${width}dp", separated)
                    }
                }
            }
        }
    }

    @Test
    fun numberPageOperationsUseExpectedKindsAndHalfWidthCharacters() {
        for (language in Language.entries) {
            val models = numberLayout(360f, language).keys.associate { it.model.id to it.model }

            for (digit in 0..9) {
                val model = models.getValue("num-$digit")
                assertEquals(KeyKind.Char, model.kind)
                assertEquals(digit.toString(), model.label)
                assertEquals(digit.toString(), model.insert)
            }

            assertCharacter(models.getValue("np-plus"), "+")
            assertCharacter(models.getValue("np-minus"), "-")
            assertCharacter(models.getValue("np-slash"), "/")
            assertCharacter(models.getValue("comma"), ",")
            assertCharacter(models.getValue("period"), ".")
            assertEquals(KeyKind.Backspace, models.getValue("backspace").kind)
            assertEquals(KeyKind.Space, models.getValue("space").kind)
            assertEquals(KeyKind.PageSymbols, models.getValue("to-symbols").kind)
            assertEquals(KeyKind.PageLetters, models.getValue("to-letters").kind)
            assertEquals("ABC", models.getValue("to-letters").label)
            assertEquals(KeyKind.Enter, models.getValue("enter").kind)
            assertTrue(models.getValue("enter").accent)
        }
    }

    @Test
    fun lettersBottomBarUsesBalancedSevenKeyWeightsInBothLanguages() {
        for (width in widths) {
            for (language in Language.entries) {
                val layout = placeKeys(KeyboardPage.Letters, language, width, keyHeight, gapX, gapY, side)
                val bottomIds = listOf("to-number", "to-symbols", "comma", "space", "period", "lang", "enter")
                val bottomModels = bottomIds.associateWith { id -> layout.keys.first { it.model.id == id }.model }
                val letter = layout.keys.first { it.model.id == "l-q" }
                val toNumber = layout.keys.first { it.model.id == "to-number" }
                val toSymbols = layout.keys.first { it.model.id == "to-symbols" }
                val space = layout.keys.first { it.model.id == "space" }

                assertEquals(bottomIds, layout.keys.filter { it.model.id in bottomIds }.map { it.model.id })
                assertEquals(
                    listOf(1.6f, 1.6f, 0.9f, 3.0f, 0.9f, 1.2f, 1.6f),
                    bottomIds.map { bottomModels.getValue(it).weight },
                )
                assertTrue("123 should be wider than letters at ${width}dp", toNumber.w > letter.w * 1.5f)
                assertTrue("Symbols should be wider than letters at ${width}dp", toSymbols.w > letter.w * 1.5f)
                assertEquals(toNumber.w, toSymbols.w, 0.01f)
                assertTrue("Space should stay usable at ${width}dp", space.w >= 60f)
                val spaceToPageRatio = space.w / toNumber.w
                assertTrue("Space should stay wider than 123 at ${width}dp", spaceToPageRatio >= 1.7f)
                assertTrue("Space should stay compact at ${width}dp", spaceToPageRatio <= 2.0f)

                if (width == 320f) {
                    assertTrue("123 should be at least 40dp at 320dp, got ${toNumber.w}", toNumber.w >= 40f)
                    assertTrue("Symbols should be at least 40dp at 320dp, got ${toSymbols.w}", toSymbols.w >= 40f)
                    assertTrue("Space should be at least 70dp at 320dp, got ${space.w}", space.w >= 70f)
                }

                val bottomKeys = layout.keys.filter { it.model.id in bottomIds }
                for (key in bottomKeys) {
                    assertEquals(
                        key.model.id,
                        hitKey(layout.keys, key.x + key.w * 0.5f, key.y + key.h * 0.5f)?.model?.id,
                    )
                }
                for (index in bottomKeys.indices) {
                    val key = bottomKeys[index]
                    for (otherIndex in index + 1 until bottomKeys.size) {
                        val other = bottomKeys[otherIndex]
                        assertTrue(
                            "${key.model.id} overlaps ${other.model.id} at ${width}dp",
                            key.x + key.w <= other.x + EPSILON || other.x + other.w <= key.x + EPSILON,
                        )
                    }
                }

                val numbers = numberLayout(width, language)
                val toLetters = numbers.keys.first { it.model.id == "to-letters" }
                assertEquals(toNumber.x, toLetters.x, 0.01f)
                assertEquals(toNumber.y, toLetters.y, 0.01f)
                val tapX = toNumber.x + toNumber.w * 0.5f
                val tapY = toNumber.y + toNumber.h * 0.5f
                assertEquals("to-number", hitKey(layout.keys, tapX, tapY)?.model?.id)
                assertEquals("to-letters", hitKey(numbers.keys, tapX, tapY)?.model?.id)

                val chinese = placeKeys(KeyboardPage.Letters, Language.Chinese, width, keyHeight, gapX, gapY, side)
                assertEquals(
                    chinese.keys.map { listOf(it.model.id, it.x, it.y, it.w, it.h) },
                    layout.keys.map { listOf(it.model.id, it.x, it.y, it.w, it.h) },
                )
            }
        }
    }

    private fun numberLayout(width: Float, language: Language) =
        placeKeys(KeyboardPage.Number, language, width, keyHeight, gapX, gapY, side)

    private fun assertSameColumn(keys: List<dev.cheng.keyboard.ime.layout.PlacedKey>) {
        val first = keys.first()
        for (key in keys.drop(1)) {
            assertEquals(first.x, key.x, 0.01f)
            assertEquals(first.w, key.w, 0.01f)
        }
    }

    private fun assertCharacter(model: dev.cheng.keyboard.model.KeyModel, text: String) {
        assertEquals(KeyKind.Char, model.kind)
        assertEquals(text, model.label)
        assertEquals(text, model.insert)
    }

    private companion object {
        const val EPSILON = 0.01f
    }
}
