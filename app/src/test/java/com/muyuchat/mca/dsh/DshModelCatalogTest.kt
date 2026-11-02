package com.muyuchat.mca.dsh

import com.muyuchat.mca.CloudApiFormat
import com.muyuchat.mca.CloudModelKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The catalog is a capability contract: it must list only serveable models, expose stable
 * namespaced ids, label prompted tool compatibility honestly and never leak credentials.
 */
class DshModelCatalogTest {

    @Test
    fun `empty when nothing is loaded or configured`() {
        assertTrue(DshModelCatalog.build(null, emptyList()).isEmpty())
    }

    @Test
    fun `local model is listed with prompted tool capability only`() {
        val models = DshModelCatalog.build(localModel(), emptyList())
        assertEquals(1, models.size)
        val local = models.single()
        assertEquals("mca-local:test-model", local.id)
        assertEquals("Test Local Model", local.displayName)
        assertEquals(4096, local.contextWindow)
        assertNull(local.maxOutputTokens)
        assertTrue(local.supportsTools)
        assertFalse(local.nativeToolCalling)
        assertEquals(DshPromptedToolProtocol.PROTOCOL_LABEL, local.toolProtocolLabel)
    }

    @Test
    fun `downloaded but not loaded local models are selectable in DSH`() {
        val active = localModel()
        val other = active.copy(modelId = "other-model", displayName = "Other Model", contextWindow = null)
        val models = DshModelCatalog.build(active, emptyList(), listOf(active, other))
        assertEquals(
            listOf("mca-local:test-model", "mca-local:other-model"),
            models.map { it.id }
        )
        val installed = models.first { it.id == "mca-local:other-model" }
        assertNull("a not-loaded model has no live context window", installed.contextWindow)
        assertTrue(installed.supportsTools)
        assertEquals(DshPromptedToolProtocol.PROTOCOL_LABEL, installed.toolProtocolLabel)
        assertEquals(2, models.size)
    }

    @Test
    fun `cloud capability follows the existing transport and user tool setting`() {
        val responsesNative = cloudRecord(
            id = "native",
            format = CloudApiFormat.OPENAI_RESPONSES,
            supportsTools = true
        )
        val responsesNoTools = cloudRecord(
            id = "responses-prompted",
            format = CloudApiFormat.OPENAI_RESPONSES,
            supportsTools = false
        )
        val compatible = cloudRecord(id = "compatible", format = CloudApiFormat.OPENAI_COMPATIBLE)
        val anthropic = cloudRecord(id = "anthropic", format = CloudApiFormat.ANTHROPIC)

        val models = DshModelCatalog.build(null, listOf(responsesNative, responsesNoTools, compatible, anthropic))
            .associateBy { it.id }

        val native = models.getValue("mca-cloud:native")
        assertTrue(native.nativeToolCalling)
        assertNull(native.toolProtocolLabel)

        listOf("mca-cloud:responses-prompted", "mca-cloud:compatible", "mca-cloud:anthropic")
            .forEach { id ->
                val model = models.getValue(id)
                assertFalse(model.nativeToolCalling)
                assertEquals(DshPromptedToolProtocol.PROTOCOL_LABEL, model.toolProtocolLabel)
                assertTrue(model.supportsTools)
            }
    }

    @Test
    fun `image and unconfigured records are not serveable`() {
        val image = cloudRecord(
            id = "image",
            kind = CloudModelKind.IMAGE,
            format = CloudApiFormat.OPENAI_COMPATIBLE
        )
        val unconfigured = cloudRecord(id = "unconfigured", baseUrl = "", apiKey = "")
        val noModel = cloudRecord(id = "no-model", modelName = "")
        val models = DshModelCatalog.build(null, listOf(image, unconfigured, noModel))
        assertTrue(models.isEmpty())
    }

    @Test
    fun `ids are stable, namespaced and sorted with the local model first`() {
        val a = cloudRecord(id = "b", displayName = "Zeta")
        val b = cloudRecord(id = "a", displayName = "Alpha")
        val models = DshModelCatalog.build(localModel(), listOf(a, b))
        assertEquals(listOf("mca-local:test-model", "mca-cloud:a", "mca-cloud:b"), models.map { it.id })

        val again = DshModelCatalog.build(localModel(), listOf(b, a))
        assertEquals(models.map { it.id }, again.map { it.id })
    }

    @Test
    fun `duplicate cloud record ids are listed once`() {
        val record = cloudRecord(id = "same")
        val models = DshModelCatalog.build(null, listOf(record, record.copy(displayName = "Other")))
        assertEquals(1, models.size)
    }

    @Test
    fun `catalog never contains credentials`() {
        val record = cloudRecord(
            id = "secret-check",
            baseUrl = "https://private.invalid/v1",
            apiKey = "sk-super-secret-value"
        )
        val models = DshModelCatalog.build(localModel(), listOf(record))
        val rendered = models.joinToString("|") { model ->
            listOf(
                model.id,
                model.displayName,
                model.contextWindow?.toString().orEmpty(),
                model.maxOutputTokens?.toString().orEmpty(),
                model.supportsTools.toString(),
                model.nativeToolCalling.toString(),
                model.toolProtocolLabel.orEmpty()
            ).joinToString(",")
        }
        assertFalse(rendered.contains("sk-super-secret-value"))
        assertFalse(rendered.contains("private.invalid"))
    }

    @Test
    fun `revision is stable for equal catalogs and changes with capability`() {
        val base = DshModelCatalog.build(
            localModel(),
            listOf(cloudRecord(id = "native", format = CloudApiFormat.OPENAI_RESPONSES, supportsTools = true))
        )
        val same = DshModelCatalog.build(
            localModel(),
            listOf(cloudRecord(id = "native", format = CloudApiFormat.OPENAI_RESPONSES, supportsTools = true))
        )
        assertEquals(DshModelCatalog.revision(base), DshModelCatalog.revision(same))

        val prompted = DshModelCatalog.build(
            localModel(),
            listOf(cloudRecord(id = "native", format = CloudApiFormat.OPENAI_RESPONSES, supportsTools = false))
        )
        assertNotEquals(DshModelCatalog.revision(base), DshModelCatalog.revision(prompted))
    }

    @Test
    fun `local model with blank id is omitted rather than faked`() {
        assertTrue(DshModelCatalog.build(localModel(modelId = " "), emptyList()).isEmpty())
    }
}
