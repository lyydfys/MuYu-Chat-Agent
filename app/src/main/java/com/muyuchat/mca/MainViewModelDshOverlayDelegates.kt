package com.muyuchat.mca

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.muyuchat.core.dsh.contract.DshRuntimeClient
import com.muyuchat.core.dsh.contract.auth.DshAccountLoginPort
import com.muyuchat.core.dsh.contract.console.DshAccountAuthUrlMatcher
import com.muyuchat.core.dsh.contract.console.DshConsolePort
import com.muyuchat.core.dsh.contract.console.DshExternalBrowserPort
import com.muyuchat.core.dsh.contract.console.DshMaintenanceConsolePort
import com.muyuchat.core.dsh.contract.console.DshPluginAdminPort
import com.muyuchat.feature.dsh.workspace.DshFirstFrameReporter
import com.muyuchat.feature.dsh.workspace.ui.DshOverlayUiPort
import com.muyuchat.mca.dsh.overlay.DshOverlayHost
import com.muyuchat.mca.dsh.overlay.DshOverlayRuntimeHooks
import kotlinx.coroutines.launch

/**
 * DSH host ports and the app-outside display owner, as `MainViewModel` extensions.
 *
 * Same pattern as the other `MainViewModel*Delegates.kt` files: the ViewModel stays a composition root
 * and does not grow one accessor per DSH surface. Nothing here holds state — every getter reads the one
 * DSH host assembly the runtime root already owns, and the overlay getter binds the process-level
 * overlay owner supplied by the app.
 */

/** Single app-facing client of the independent official-DSH mode; never a second runtime. */
internal val MainViewModel.dshRuntimeClient: DshRuntimeClient
    get() = runtimeAssembly.dshHost.runtimeClient

internal val MainViewModel.dshFirstFrameReporter: DshFirstFrameReporter
    get() = runtimeAssembly.dshHost.firstFrameReporter

internal val MainViewModel.dshConsolePort: DshConsolePort
    get() = runtimeAssembly.dshHost.console

internal val MainViewModel.dshPluginAdminPort: DshPluginAdminPort
    get() = runtimeAssembly.dshHost.pluginAdmin

internal val MainViewModel.dshMaintenanceConsolePort: DshMaintenanceConsolePort
    get() = runtimeAssembly.dshHost.maintenanceConsolePort

internal val MainViewModel.dshExternalBrowserPort: DshExternalBrowserPort
    get() = runtimeAssembly.dshHost.externalBrowserPort

internal val MainViewModel.dshAccountAuthUrlMatcher: DshAccountAuthUrlMatcher
    get() = runtimeAssembly.dshHost.accountAuthUrlMatcher

internal val MainViewModel.dshAccountLoginPort: DshAccountLoginPort
    get() = runtimeAssembly.dshHost.accountLoginPort

/**
 * App-outside display port. The DSH page only renders the switch and the appearance panel; the window
 * service, the keep-alive foreground service, the image copy and the persistence stay in the app.
 *
 * The one runtime fact the port may ask for is "make sure the existing DSH host is running": that call
 * goes through the same frozen runtime client, so no second host process and no second gateway port is
 * ever created. [DshOverlayHost.bindRuntime] installs the hook once per process and re-attaches the
 * window when the user still has it switched on; repeated reads are therefore cheap and idempotent.
 */
internal val MainViewModel.dshOverlayPort: DshOverlayUiPort
    get() = DshOverlayHost.of(getApplication<Application>()).bindRuntime(
        DshOverlayRuntimeHooks(
            ensureStarted = {
                viewModelScope.launch {
                    runCatching { dshRuntimeClient.ensureStarted() }
                }
            }
        )
    )
