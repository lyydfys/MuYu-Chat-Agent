package com.muyuchat.mca.dsh.overlay

import android.content.Context
import android.os.Bundle
import androidx.compose.runtime.Composable
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

/**
 * Lifecycle owner for the Compose content of one overlay window.
 *
 * A floating window is added by a started Service, so there is no Activity to borrow a
 * `LifecycleOwner`/`ViewModelStoreOwner` from. Compose needs both a lifecycle (to create and stop its
 * recomposer) and a saved-state registry (for the platform's view-tree lookups); this object supplies
 * them for the exact lifetime of one attached window and is destroyed with it.
 *
 * It holds no business state: the service pushes its [DshOverlayUiState] into the composable.
 */
internal class DshOverlayComposeHost(context: Context) :
    LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val appContext: Context = context.applicationContext
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    /** Builds the detached view; the caller adds it to the window and then calls [attached]. */
    fun createView(content: @Composable () -> Unit): ComposeView = ComposeView(appContext).apply {
        setViewTreeLifecycleOwner(this@DshOverlayComposeHost)
        setViewTreeViewModelStoreOwner(this@DshOverlayComposeHost)
        setViewTreeSavedStateRegistryOwner(this@DshOverlayComposeHost)
        setContent(content)
    }

    /** Promotes the composition once the window is attached; idempotent. */
    fun attached() {
        if (!savedStateController.savedStateRegistry.isRestored) {
            savedStateController.performRestore(null as Bundle?)
        }
        if (lifecycleRegistry.currentState != Lifecycle.State.RESUMED) {
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        }
    }

    /** Releases the composition and the view-model store; the window must already be removed. */
    fun release() {
        if (lifecycleRegistry.currentState != Lifecycle.State.DESTROYED) {
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        }
        store.clear()
    }
}
