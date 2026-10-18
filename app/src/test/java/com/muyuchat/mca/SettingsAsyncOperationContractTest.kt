package com.muyuchat.mca

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsAsyncOperationContractTest {
    @Test
    fun cleanupOperationsAreSingleFlightAndPublishOwnerBusyState() {
        val viewModel = sourceFile("MainViewModel.kt")
        val helperStart = viewModel.indexOf("internal fun launchSettingsCleanup(")
        val helperEnd = viewModel.indexOf("fun tryAcquireObservedImageGenerationLease", helperStart)
        assertTrue(helperStart >= 0 && helperEnd > helperStart)
        val helper = viewModel.substring(helperStart, helperEnd)

        assertTrue(helper.contains("if (activeSettingsCleanupJob?.isActive == true) return null"))
        assertTrue(helper.contains("settingsOperations = state.settingsOperations.copy(cleanup = operation)"))
        assertTrue(helper.contains("job.invokeOnCompletion"))
        assertTrue(helper.contains("activeSettingsCleanupJob = null"))
        assertFalse(helper.contains("setTimeout"))
        assertFalse(helper.contains("delay("))
    }

    @Test
    fun clearActionsOnlyReportCompletionFromTheirAuthoritativeOwners() {
        val chat = sourceFile("ChatSessionLifecycleOperations.kt")
        val chatClear = functionBody(chat, "clearChatHistoryFor")
        assertTrue(chatClear.contains("persistConversationMutation("))
        assertTrue(chatClear.contains("statusMessage = \"已清空聊天记录\""))
        assertTrue(chatClear.contains("return persistenceJob"))

        val files = functionBody(sourceFile("ChatAttachmentOperations.kt"), "clearFileLibraryFor")
        assertTrue(files.contains("persistFiles(emptyList())"))
        assertTrue(files.contains("if (persistence.isFailure) return@launch"))
        val persistIndex = files.indexOf("val persistence =")
        val clearIndex = files.indexOf("files = emptyList()")
        assertTrue(persistIndex >= 0 && clearIndex > persistIndex)

        val cache = functionBody(sourceFile("CacheManagementCoordinator.kt"), "schedulePersistentPrefixCacheClear")
        assertTrue(cache.contains("return scope.launch(Dispatchers.IO)"))
        assertTrue(cache.contains("finally"))
        assertTrue(cache.contains("engine.setPersistentPrefixCacheEnabled(enabledNow)"))
        assertFalse(cache.contains("setTimeout"))
    }

    @Test
    fun webSearchTestsUseOneRealOwnerJobAndClearBusyInFinally() {
        val coordinator = sourceFile("WebSearchWorkflowCoordinator.kt")
        assertTrue(coordinator.contains("private fun launchExclusive(start: () -> Job?): Job?"))
        assertTrue(coordinator.contains("if (activeOperationJob?.isActive == true) return null"))
        assertTrue(coordinator.contains("job.invokeOnCompletion"))

        val operations = sourceFile("WebSearchConfigOperations.kt")
        assertTrue(operations.contains("settingsOperations = it.settingsOperations.copy(webSearch = operation)"))
        assertTrue(operations.contains("try {"))
        assertTrue(operations.contains("} finally {"))
        assertTrue(operations.contains("settingsOperations = state.settingsOperations.copy(webSearch = null)"))
        assertFalse(operations.contains("busy = true"))
        assertFalse(operations.contains("busy = false"))
        assertFalse(operations.contains("setTimeout"))
        assertFalse(operations.contains("delay("))
    }

    @Test
    fun settingsMappingAndCallbacksStayOnOwnerStateBoundary() {
        val mapping = sourceFile("presentation/SettingsUiStateMapping.kt")
        assertTrue(mapping.contains("cleanupBusy = SettingsCleanupBusyUiState("))
        assertTrue(mapping.contains("operationBusy = settingsOperations.webSearch != null"))

        val activity = sourceFile("MainActivity.kt")
        listOf(
            "onClearChatHistory = viewModel::clearChatHistory",
            "onClearImageLibrary = viewModel::clearImageLibrary",
            "onClearFileLibrary = viewModel::clearFileLibrary",
            "onClearPersistentPrefixCache = viewModel::clearPersistentPrefixCache"
        ).forEach { binding -> assertTrue(activity.contains(binding)) }

        val root = projectRoot()
        val featureSources = listOf(
            "feature/settings/src/main/java/com/muyuchat/feature/settings/SettingsScreens.kt",
            "feature/settings/src/main/java/com/muyuchat/feature/settings/SettingsSearchScreen.kt",
            "feature/settings/src/main/java/com/muyuchat/feature/settings/SettingsLogsPrivacyScreens.kt"
        ).joinToString("\n") { File(root, it).readText(Charsets.UTF_8) }
        assertFalse(featureSources.contains("import com.muyuchat.mca"))
        assertFalse(featureSources.contains("import kotlinx.coroutines.Job"))
        assertFalse(featureSources.contains("Deferred<"))
    }

    private fun functionBody(source: String, name: String): String {
        val declaration = source.indexOf("fun $name")
        require(declaration >= 0) { "Missing function $name" }
        val openingBrace = source.indexOf('{', declaration)
        var depth = 0
        for (index in openingBrace until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(openingBrace + 1, index)
                }
            }
        }
        error("Unterminated function $name")
    }

    private fun sourceFile(name: String): String = sequenceOf(
        File("src/main/java/com/muyuchat/mca/$name"),
        File("app/src/main/java/com/muyuchat/mca/$name"),
        File(projectRoot(), "app/src/main/java/com/muyuchat/mca/$name")
    ).firstOrNull(File::isFile)?.readText(Charsets.UTF_8)
        ?: error("Unable to locate $name")

    private fun projectRoot(): File {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            if (File(directory, "settings.gradle.kts").isFile) return directory
            directory = directory.parentFile
        }
        error("Unable to locate the project root from ${System.getProperty("user.dir")}")
    }
}
