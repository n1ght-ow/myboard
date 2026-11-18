package dev.cheng.keyboard

import android.view.inputmethod.InputConnection
import dev.cheng.keyboard.engine.Cmd
import dev.cheng.keyboard.ime.EditorBridge
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorPasteRequestTest {
    @Test
    fun pasteMakesOneVerbatimRequestAndClearsCompositionOnAcceptance() {
        val input = PasteInput { true }
        val bridge = EditorBridge { input.connection }
        bridge.apply(listOf(Cmd.Compose("ni")))

        assertTrue(bridge.commitPaste("你()\n😀"))
        bridge.apply(listOf(Cmd.ClearCompose))

        assertEquals(listOf("你()\n😀"), input.commits)
    }

    @Test
    fun unconfirmedPasteDoesNotRetryOrForgetComposition() {
        val refusals: List<() -> Boolean> = listOf({ false }, { error("synthetic write failure") })
        for (refusal in refusals) {
            val input = PasteInput(refusal)
            val bridge = EditorBridge { input.connection }
            bridge.apply(listOf(Cmd.Compose("ni")))

            assertFalse(bridge.commitPaste("你clip"))
            assertEquals(listOf("你clip"), input.commits)
            bridge.apply(listOf(Cmd.ClearCompose))
            assertEquals(listOf("你clip", ""), input.commits)
        }
        assertFalse(EditorBridge { null }.commitPaste("clip"))
    }

    private class PasteInput(acceptPaste: () -> Boolean) {
        val commits = mutableListOf<String>()
        val connection = Proxy.newProxyInstance(
            InputConnection::class.java.classLoader,
            arrayOf(InputConnection::class.java),
        ) { _, method, args ->
            when (method.name) {
                "setComposingText" -> true
                "commitText" -> {
                    val text = args[0].toString()
                    commits += text
                    if (text.isEmpty()) true else acceptPaste()
                }
                else -> error("Unexpected method: ${method.name}")
            }
        } as InputConnection
    }
}
