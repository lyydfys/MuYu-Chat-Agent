package com.muyuchat.mca.presentation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.muyuchat.feature.agent.workspace.AgentDocumentLauncher
import com.muyuchat.feature.agent.workspace.AgentDocumentRef

/**
 * SAF launcher bridge for the Agent workspace. It lives in the app module because only an Activity
 * can own Activity Result contracts; the feature module sees only plain callbacks.
 *
 * One callback is held per picker kind; starting a second picker before the first returns replaces
 * the stale callback instead of invoking both.
 */
@Composable
fun rememberAgentDocumentLauncher(): AgentDocumentLauncher {
    var pendingCreate by remember { mutableStateOf<((AgentDocumentRef?) -> Unit)?>(null) }
    var pendingOpen by remember { mutableStateOf<((AgentDocumentRef?) -> Unit)?>(null) }
    var pendingTree by remember { mutableStateOf<((AgentDocumentRef?) -> Unit)?>(null) }

    val createLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        val callback = pendingCreate
        pendingCreate = null
        callback?.invoke(uri?.let { AgentDocumentRef(it.toString()) })
    }
    val openLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val callback = pendingOpen
        pendingOpen = null
        callback?.invoke(uri?.let { AgentDocumentRef(it.toString()) })
    }
    val treeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val callback = pendingTree
        pendingTree = null
        callback?.invoke(uri?.let { AgentDocumentRef(it.toString()) })
    }

    return remember(createLauncher, openLauncher, treeLauncher) {
        object : AgentDocumentLauncher {
            override fun createDocument(
                suggestedFileName: String,
                onResult: (AgentDocumentRef?) -> Unit
            ) {
                pendingCreate = onResult
                createLauncher.launch(suggestedFileName)
            }

            override fun openDocument(onResult: (AgentDocumentRef?) -> Unit) {
                pendingOpen = onResult
                openLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
            }

            override fun openTree(onResult: (AgentDocumentRef?) -> Unit) {
                pendingTree = onResult
                treeLauncher.launch(null)
            }
        }
    }
}
