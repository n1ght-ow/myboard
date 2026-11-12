package dev.cheng.keyboard.ime

import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodSubtype
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.cheng.keyboard.ime.gesture.HapticKind
import dev.cheng.keyboard.ime.ui.KeyboardRoot
import dev.cheng.keyboard.model.Language
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import android.view.HapticFeedbackConstants

class KeyboardService : InputMethodService(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controller: KeyboardController? = null
    private var inputView: View? = null

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
        moveTo(Lifecycle.State.CREATED)
        controller = KeyboardController(
            applicationContext,
            scope,
            { currentInputConnection },
            ::performKeyboardHaptic,
            { requestHideSelf(0) },
        )
        moveTo(Lifecycle.State.STARTED)
    }

    override fun onCreateInputView(): View {
        val view = ComposeView(this)
        inputView = view
        window?.window?.decorView?.let(::installOwners)
        installOwners(view)
        val active = controller
        if (active != null) view.setContent { KeyboardRoot(active) }
        return view
    }

    override fun onStartInput(info: EditorInfo, restarting: Boolean) {
        super.onStartInput(info, restarting)
        controller?.startInput(info, restarting)
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        controller?.startInput(info, restarting)
        moveTo(Lifecycle.State.RESUMED)
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        controller?.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
    }

    override fun onWindowShown() {
        super.onWindowShown()
        moveTo(Lifecycle.State.RESUMED)
    }

    override fun onWindowHidden() {
        controller?.onHidden()
        moveTo(Lifecycle.State.STARTED)
        super.onWindowHidden()
    }

    override fun onCurrentInputMethodSubtypeChanged(newSubtype: InputMethodSubtype) {
        super.onCurrentInputMethodSubtypeChanged(newSubtype)
        val locale = newSubtype.languageTag.ifBlank { "zh" }
        val language = if (locale.startsWith("zh")) Language.Chinese else Language.English
        controller?.setLanguage(language)
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onDestroy() {
        controller?.close()
        scope.cancel()
        moveTo(Lifecycle.State.DESTROYED)
        store.clear()
        super.onDestroy()
    }

    private fun installOwners(view: View) {
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeViewModelStoreOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
    }

    private fun moveTo(state: Lifecycle.State) {
        if (lifecycleRegistry.currentState == Lifecycle.State.DESTROYED && state != Lifecycle.State.DESTROYED) return
        lifecycleRegistry.currentState = state
    }

    private fun performKeyboardHaptic(kind: HapticKind) {
        val view = inputView ?: return
        val constant = when (kind) {
            HapticKind.Arm -> HapticFeedbackConstants.LONG_PRESS
            HapticKind.Step -> HapticFeedbackConstants.CLOCK_TICK
            HapticKind.Down, HapticKind.Delete -> HapticFeedbackConstants.KEYBOARD_TAP
        }
        view.performHapticFeedback(constant, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING)
    }
}
