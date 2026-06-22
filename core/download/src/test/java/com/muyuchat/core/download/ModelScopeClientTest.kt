package com.muyuchat.core.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelScopeClientTest {
    private val client = ModelScopeClient()

    @Test
    fun parsesPlainRepoId() {
        assertEquals(
            "lmstudio-community/Qwen3.5-0.8B-GGUF",
            client.parseRepoId("lmstudio-community/Qwen3.5-0.8B-GGUF")
        )
    }

    @Test
    fun stripsSummarySuffixFromModelPageUrl() {
        assertEquals(
            "lmstudio-community/Qwen3.5-0.8B-GGUF",
            client.parseRepoId("https://www.modelscope.cn/models/lmstudio-community/Qwen3.5-0.8B-GGUF/summary")
        )
    }

    @Test
    fun stripsFilesSuffixFromModelPageUrl() {
        assertEquals(
            "lmstudio-community/Qwen3.5-0.8B-GGUF",
            client.parseRepoId("https://modelscope.cn/models/lmstudio-community/Qwen3.5-0.8B-GGUF/files")
        )
    }

    @Test
    fun stripsResolvePathFromDownloadUrl() {
        assertEquals(
            "lmstudio-community/Qwen3.5-0.8B-GGUF",
            client.parseRepoId("https://www.modelscope.cn/models/lmstudio-community/Qwen3.5-0.8B-GGUF/resolve/master/model.gguf")
        )
    }

    @Test
    fun stripsExtraSegmentsFromManualInput() {
        assertEquals(
            "lmstudio-community/Qwen3.5-0.8B-GGUF",
            client.parseRepoId("lmstudio-community/Qwen3.5-0.8B-GGUF/summary")
        )
    }

    @Test
    fun decodesEncodedRepoIdFromModelPageUrl() {
        assertEquals(
            "owner name/模型 GGUF",
            client.parseRepoId("https://modelscope.cn/models/owner%20name/%E6%A8%A1%E5%9E%8B%20GGUF/files")
        )
    }

    @Test
    fun includesDefaultRecommendedModels() {
        val recommendations = client.recommendedModels()

        assertEquals(19, recommendations.size)
        assertEquals(ModelScopeRecommendedGroup.LIGHT_CHAT, recommendations[0].group)
        assertEquals("lmstudio-community/Qwen3.5-4B-GGUF", recommendations.first { it.id == "qwen35_4b_q4" }.repoId)
        assertEquals("Qwen3.5-4B-Q4_K_M.gguf", recommendations.first { it.id == "qwen35_4b_q4" }.recommendedFileName)
        assertTrue(recommendations.any { it.group == ModelScopeRecommendedGroup.LOCAL_IMAGE && it.downloadable })
        assertTrue(recommendations.none { !it.downloadable })
        assertEquals("qwen-image-2512-Q2_K.gguf", recommendations.first { it.id == "qwen_image_2512_q2" }.recommendedFileName)
        assertEquals("sd_turbo.safetensors", recommendations.first { it.id == "sd_turbo_384_fast" }.recommendedFileName)
        assertEquals("sd_turbo.safetensors", recommendations.first { it.id == "sd_turbo_512_quality" }.recommendedFileName)
        assertEquals("GLM-4.7-Flash-UD-TQ1_0.gguf", recommendations.first { it.id == "glm47_flash_tq1" }.recommendedFileName)
        assertEquals("google_gemma-4-26B-A4B-it-IQ2_XXS.gguf", recommendations.first { it.id == "google_gemma4_26b_a4b_iq2_xxs" }.recommendedFileName)
        assertTrue(recommendations.all { it.provider == ModelRepositoryProvider.MODELSCOPE })
        assertEquals(ModelScopeRecommendedKind.IMAGE, recommendations.last().kind)
        assertTrue(recommendations.none { it.repoId.contains("Qwen2.5", ignoreCase = true) })
        val imageRecommendations = recommendations.filter { it.kind == ModelScopeRecommendedKind.IMAGE }
        assertTrue(imageRecommendations.all { it.imageEngineBundle != null })
        assertTrue(imageRecommendations.all { it.imageEngineBundle!!.requiredComponents.isNotEmpty() })
        assertTrue(imageRecommendations.all { it.localImageEngineTier != null })
        assertEquals(LocalImageEngineTier.QUICK, recommendations.first { it.id == "sd_turbo_384_fast" }.localImageEngineTier)
        assertEquals(LocalImageEngineTier.STANDARD, recommendations.first { it.id == "sd_turbo_512_quality" }.localImageEngineTier)
        assertEquals(LocalImageEngineTier.COMPACT_QUALITY, recommendations.first { it.id == "flux2_klein_4b_q4" }.localImageEngineTier)
        assertEquals(LocalImageEngineTier.LARGE_QUALITY, recommendations.first { it.id == "z_image_turbo_q4" }.localImageEngineTier)
        assertEquals(LocalImageEngineTier.HEAVY_EXPERIMENTAL, recommendations.first { it.id == "qwen_image_2512_q2" }.localImageEngineTier)
        assertEquals(LocalImageEngineTier.HEAVY_EXPERIMENTAL, recommendations.first { it.id == "longcat_image_q4" }.localImageEngineTier)
        assertNotNull(
            recommendations.first { it.id == "sd_turbo_384_fast" }
                .imageEngineBundle!!
                .components
                .firstOrNull { it.role == ImageEngineBundleComponentRole.DIFFUSION && it.fileName == "sd_turbo.safetensors" }
        )
        assertNotNull(
            recommendations.first { it.id == "flux2_klein_4b_q4" }
                .imageEngineBundle!!
                .components
                .firstOrNull { it.role == ImageEngineBundleComponentRole.TEXT_ENCODER && it.fileName == "Qwen3-4B-Q4_K_M.gguf" }
        )
        assertNotNull(
            recommendations.first { it.id == "qwen_image_2512_q2" }
                .imageEngineBundle!!
                .components
                .firstOrNull { it.role == ImageEngineBundleComponentRole.VAE && it.fileName.endsWith("qwen_image_vae.safetensors") }
        )
    }

    @Test
    fun buildsModelPageUrlForRepoId() {
        assertEquals(
            "https://www.modelscope.cn/models/lmstudio-community/Qwen3.5-2B-GGUF/summary",
            modelScopeModelPageUrl("lmstudio-community/Qwen3.5-2B-GGUF")
        )
    }

    @Test
    fun classifiesAuxiliaryGgufFiles() {
        assertTrue(remote("Qwen3.5-4B-Q4_K_M.gguf").isChatModelCandidate())
        assertEquals(RemoteModelFileKind.IMAGE_MODEL, remote("z_image_turbo-Q4_K.gguf").fileKind())
        assertEquals(RemoteModelFileKind.IMAGE_MODEL, remote("qwen-image-Q4_K_M.gguf").fileKind())
        assertEquals(RemoteModelFileKind.IMAGE_MODEL, remote("LongCat-Image-Q4_0.gguf").fileKind())
        assertEquals(RemoteModelFileKind.IMAGE_MODEL, remote("GLM-Image-Q4_0.gguf").fileKind())
        assertEquals(RemoteModelFileKind.PROJECTOR, remote("mmproj-Qwen3.5-4B-BF16.gguf").fileKind())
        assertEquals(RemoteModelFileKind.IMATRIX, remote("imatrix_unsloth.gguf").fileKind())
        assertEquals(RemoteModelFileKind.SPECULATIVE, remote("mtp-Qwen3.6-35B-A3B-Q4_0.gguf").fileKind())
        assertEquals(RemoteModelFileKind.SPLIT_PART, remote("Qwen3.6-27B-BF16-00001-of-00002.gguf").fileKind())
    }

    @Test
    fun parsesNestedGgufFileListShapes() {
        val files = client.parseGgufFilesForTest(
            repoId = "owner/model",
            revision = "main",
            endpoint = "https://modelscope.cn",
            body = """
                {
                  "Data": {
                    "Files": [
                      {"Path": "README.md", "Size": 10},
                      {"Path": "sub/Qwen3.5-4B-Q4_K_M.gguf", "Size": 2707000000, "Sha256": "abc"},
                      {"Name": "mmproj-Qwen3.5.gguf", "DownloadUrl": "https://cdn.example/mmproj.gguf"}
                    ]
                  }
                }
            """.trimIndent()
        )

        assertEquals(2, files.size)
        assertEquals("sub/Qwen3.5-4B-Q4_K_M.gguf", files[0].path)
        assertEquals("abc", files[0].sha256)
        assertEquals("https://modelscope.cn/models/owner/model/resolve/main/sub/Qwen3.5-4B-Q4_K_M.gguf", files[0].downloadUrl)
        assertEquals("https://cdn.example/mmproj.gguf", files[1].downloadUrl)
    }

    @Test
    fun parsesAlternateSearchResponseShapes() {
        val result = client.parseModelSearchResultForTest(
            query = "qwen gguf",
            body = """
                {
                  "Data": {
                    "Items": [
                      {
                        "model_id": "owner/Qwen3.5-4B-GGUF",
                        "model_name": "Qwen3.5 4B",
                        "download_count": 12,
                        "like_count": 3,
                        "License": "apache-2.0",
                        "Tags": [{"name": "gguf"}, {"name": "q4"}]
                      }
                    ],
                    "page_number": 2,
                    "page_size": 20,
                    "total_count": 99
                  }
                }
            """.trimIndent()
        )

        assertEquals(1, result.models.size)
        assertEquals("owner/Qwen3.5-4B-GGUF", result.models.single().id)
        assertEquals("Qwen3.5 4B", result.models.single().displayName)
        assertEquals(12, result.models.single().downloads)
        assertEquals(listOf("gguf", "q4"), result.models.single().tags)
    }

    @Test
    fun parsesLowercaseSearchResponseShape() {
        val result = client.parseModelSearchResultForTest(
            query = "gemma gguf",
            body = """
                {
                  "data": {
                    "models": [
                      {
                        "id": "google/gemma-4-E2B-it-GGUF",
                        "displayName": "Gemma 4 E2B GGUF",
                        "downloads": 1200,
                        "likes": 88,
                        "license": "gemma",
                        "tasks": ["text-generation"],
                        "tags": ["gguf", "q4_k_m"]
                      }
                    ]
                  }
                }
            """.trimIndent()
        )

        assertEquals("google/gemma-4-E2B-it-GGUF", result.models.single().id)
        assertEquals("Gemma 4 E2B GGUF", result.models.single().displayName)
        assertEquals(1200, result.models.single().downloads)
        assertEquals(listOf("text-generation"), result.models.single().tasks)
    }

    private fun remote(name: String): RemoteModelFile = RemoteModelFile(
        repoId = "owner/model",
        revision = "master",
        path = name,
        name = name,
        downloadUrl = "https://example.com/$name"
    )
}
