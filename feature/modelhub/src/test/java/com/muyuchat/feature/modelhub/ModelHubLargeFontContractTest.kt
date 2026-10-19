package com.muyuchat.feature.modelhub

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the large-font/state pass: segmented tabs wrap, the import label shortens, the cloud save
 * bar can grow, expanded recommendation details are not line-capped, and the status card is
 * classified with the module's error helper.
 */
class ModelHubLargeFontContractTest {

    @Test
    fun `segmented tabs wrap and keep a single ellipsised line`() {
        val hub = source("feature/modelhub/src/main/java/com/muyuchat/feature/modelhub/ModelHubScreen.kt")
        assertTrue(hub.contains("fontScale >= 1.5f"))
        assertTrue(hub.contains("FlowRow("))
        assertTrue(hub.contains("softWrap = false"))
    }

    @Test
    fun `import button shortens its label at large font`() {
        val local = source("feature/modelhub/src/main/java/com/muyuchat/feature/modelhub/LocalModelsSection.kt")
        assertTrue(local.contains("fontScale >= 1.5f"))
        assertTrue(local.contains("\"+ 导入本地模型\""))
        assertTrue(local.contains("maxLines = 1"))
    }

    @Test
    fun `cloud save bar can grow and the status card uses the error classifier`() {
        val cloud = source("feature/modelhub/src/main/java/com/muyuchat/feature/modelhub/CloudModelsSection.kt")
        assertTrue(cloud.contains("heightIn(min = 48.dp)"))
        assertTrue(cloud.contains("isError = modelHubStatusMessageIsError(status)"))
    }

    @Test
    fun `expanded recommendation details are not line capped`() {
        val card = source("feature/modelhub/src/main/java/com/muyuchat/feature/modelhub/RecommendedModelCard.kt")
        assertTrue(card.contains("Int.MAX_VALUE"))
    }

    private fun source(relativePath: String): String =
        File(projectRoot(), relativePath).readText(Charsets.UTF_8)

    private fun projectRoot(): File {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            if (File(directory, "settings.gradle.kts").isFile) return directory
            directory = directory.parentFile
        }
        error("project root not found")
    }
}
