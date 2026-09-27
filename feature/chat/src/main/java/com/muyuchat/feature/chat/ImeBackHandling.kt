package com.muyuchat.feature.chat

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.compose.LocalLifecycleOwner

internal class ImeBackState {
    private var visible = false
    private var hideConsumed = false
    private var gestureStartedWithIme = false

    fun observe(imeVisible: Boolean) {
        if (imeVisible && !visible) hideConsumed = false
        visible = imeVisible
    }

    fun beginGesture() {
        gestureStartedWithIme = visible && !hideConsumed
    }

    fun cancelGesture() {
        gestureStartedWithIme = false
    }

    fun consume(): Boolean {
        val consume = !hideConsumed && (visible || gestureStartedWithIme)
        gestureStartedWithIme = false
        if (!consume) return false
        hideConsumed = true
        return true
    }
}

private class WindowImeBackController(private val root: View) {
    private val state = ImeBackState()

    fun observe() {
        state.observe(isImeVisible(root))
    }

    fun beginGesture() {
        observe()
        state.beginGesture()
    }

    fun cancelGesture() = state.cancelGesture()

    fun consume(): Boolean {
        observe()
        if (!state.consume()) return false
        ViewCompat.getWindowInsetsController(root)?.hide(WindowInsetsCompat.Type.ime())
        val focused = root.findFocus() ?: root
        (root.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(focused.windowToken, InputMethodManager.HIDE_NOT_ALWAYS)
        return true
    }
}

private fun isImeVisible(root: View): Boolean {
    val insets = ViewCompat.getRootWindowInsets(root)
    if (insets?.isVisible(WindowInsetsCompat.Type.ime()) == true) return true
    if (!root.isAttachedToWindow || root.height <= 0) return false
    val frame = Rect()
    root.getWindowVisibleDisplayFrame(frame)
    val threshold = (160f * root.resources.displayMetrics.density.coerceAtLeast(1f)).toInt()
    return root.height - frame.bottom > threshold
}

@Composable
private fun rememberWindowImeBackController(): WindowImeBackController {
    val root = LocalView.current.rootView
    val controller = remember(root) { WindowImeBackController(root) }
    DisposableEffect(root, controller) {
        val observer = root.viewTreeObserver
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            controller.observe()
        }
        controller.observe()
        observer.addOnGlobalLayoutListener(listener)
        onDispose {
            if (observer.isAlive) observer.removeOnGlobalLayoutListener(listener)
        }
    }
    return controller
}

/** Register after page handlers, once per activity window. */
@Composable
fun ConsumeImeBackHandler() {
    val controller = rememberWindowImeBackController()
    val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val lifecycleOwner = LocalLifecycleOwner.current
    val callback = remember(controller, dispatcher) {
        object : OnBackPressedCallback(true) {
            override fun handleOnBackStarted(backEvent: BackEventCompat) = controller.beginGesture()
            override fun handleOnBackCancelled() = controller.cancelGesture()
            override fun handleOnBackPressed() {
                if (controller.consume()) return
                isEnabled = false
                try {
                    dispatcher?.onBackPressed()
                } finally {
                    isEnabled = true
                }
            }
        }
    }
    DisposableEffect(dispatcher, lifecycleOwner, callback) {
        dispatcher?.addCallback(lifecycleOwner, callback)
        onDispose { callback.remove() }
    }
}

/** Dialog dismissal runs against its own window, whose IME can differ from the activity. */
@Composable
fun ImeAwareAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    shape: Shape = AlertDialogDefaults.shape,
    containerColor: Color = AlertDialogDefaults.containerColor,
    iconContentColor: Color = AlertDialogDefaults.iconContentColor,
    titleContentColor: Color = AlertDialogDefaults.titleContentColor,
    textContentColor: Color = AlertDialogDefaults.textContentColor,
    tonalElevation: Dp = AlertDialogDefaults.TonalElevation,
    properties: DialogProperties = DialogProperties()
) {
    val holder = remember { arrayOfNulls<WindowImeBackController>(1) }
    val currentDismiss = rememberUpdatedState(onDismissRequest)
    AlertDialog(
        onDismissRequest = { if (holder[0]?.consume() != true) currentDismiss.value() },
        confirmButton = confirmButton,
        modifier = modifier,
        dismissButton = dismissButton,
        icon = icon,
        title = title,
        text = {
            holder[0] = rememberWindowImeBackController()
            Box { text?.invoke() }
        },
        shape = shape,
        containerColor = containerColor,
        iconContentColor = iconContentColor,
        titleContentColor = titleContentColor,
        textContentColor = textContentColor,
        tonalElevation = tonalElevation,
        properties = properties
    )
}
