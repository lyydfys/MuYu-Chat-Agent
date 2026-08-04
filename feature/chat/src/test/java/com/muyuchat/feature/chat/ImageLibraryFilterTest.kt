package com.muyuchat.feature.chat

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageLibraryFilterTest {
    @Test
    fun `structured filters combine exact history facets without model fallback`() {
        val qnn = image(
            id = "qnn",
            createdAtMillis = 300L,
            width = 768,
            height = 512,
            scheduler = "DPM++ 2M",
            runtime = "QNN HTP",
            device = "HTP V79",
            favorite = true
        )
        val mnn = image(
            id = "mnn",
            createdAtMillis = 200L,
            width = 512,
            height = 512,
            scheduler = "Euler",
            runtime = "MNN Diffusion",
            device = "OpenCL GPU"
        )
        val legacy = image(
            id = "legacy",
            createdAtMillis = 100L,
            width = 768,
            height = 512
        )

        val result = filterImageLibrary(
            images = listOf(legacy, mnn, qnn),
            filter = ImageLibraryFilter(
                favoritesOnly = true,
                dimensions = ImageLibraryDimensions(768, 512),
                scheduler = "DPM++ 2M",
                runtime = "QNN HTP",
                device = "HTP V79"
            )
        )

        assertEquals(listOf("qnn"), result.map(ImageAssetUiItem::id))
        assertEquals(
            listOf("qnn", "mnn", "legacy"),
            filterImageLibrary(listOf(legacy, mnn, qnn), ImageLibraryFilter())
                .map(ImageAssetUiItem::id)
        )
        assertEquals(
            listOf("qnn"),
            filterImageLibrary(
                listOf(legacy, qnn),
                ImageLibraryFilter(runtime = "QNN HTP")
            ).map(ImageAssetUiItem::id)
        )
    }

    @Test
    fun `date picker range uses local inclusive calendar days`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val startSelection = Instant.parse("2026-07-01T00:00:00Z").toEpochMilli()
        val endSelection = Instant.parse("2026-07-03T00:00:00Z").toEpochMilli()
        val range = requireNotNull(
            imageLibraryDateRangeFromUtcPickerSelection(startSelection, endSelection, zone)
        )
        val lastIncluded = ZonedDateTime.parse("2026-07-03T23:59:59+08:00[Asia/Shanghai]")
            .toInstant()
            .toEpochMilli()
        val firstExcluded = ZonedDateTime.parse("2026-07-04T00:00:00+08:00[Asia/Shanghai]")
            .toInstant()
            .toEpochMilli()

        assertEquals(
            listOf("inside"),
            filterImageLibrary(
                images = listOf(
                    image("outside", firstExcluded),
                    image("inside", lastIncluded)
                ),
                filter = ImageLibraryFilter(dateRange = range)
            ).map(ImageAssetUiItem::id)
        )
        assertNull(imageLibraryDateRangeFromUtcPickerSelection(null, null, zone))
    }

    @Test
    fun `single selected day becomes one local day and oldest ordering is timestamp based`() {
        val zone = ZoneId.of("UTC")
        val selected = Instant.parse("2026-07-19T00:00:00Z").toEpochMilli()
        val range = requireNotNull(
            imageLibraryDateRangeFromUtcPickerSelection(selected, null, zone)
        )

        assertEquals(24L * 60L * 60L * 1_000L, range.endExclusiveMillis - range.startInclusiveMillis)
        assertEquals(
            listOf("old", "new"),
            filterImageLibrary(
                images = listOf(image("new", 20L), image("old", 10L)),
                filter = ImageLibraryFilter(newestFirst = false)
            ).map(ImageAssetUiItem::id)
        )
    }

    @Test
    fun `search includes runtime device scheduler and dimensions`() {
        val item = image(
            id = "searchable",
            createdAtMillis = 1L,
            width = 1024,
            height = 768,
            scheduler = "Flow Match",
            runtime = "stable-diffusion.cpp",
            device = "CPU"
        )

        listOf("flow", "stable-diffusion", "cpu", "1024x768").forEach { query ->
            assertEquals(
                listOf("searchable"),
                filterImageLibrary(listOf(item), ImageLibraryFilter(query = query))
                    .map(ImageAssetUiItem::id)
            )
        }
    }

    private fun image(
        id: String,
        createdAtMillis: Long,
        width: Int = 512,
        height: Int = 512,
        scheduler: String = "",
        runtime: String = "",
        device: String = "",
        favorite: Boolean = false
    ): ImageAssetUiItem = ImageAssetUiItem(
        id = id,
        name = "$id.png",
        uriString = "content://images/$id",
        source = "generated:test",
        prompt = "prompt",
        createdAtText = "07-19 00:00",
        createdAtMillis = createdAtMillis,
        sizeText = "1 KB",
        width = width,
        height = height,
        generationSampler = scheduler,
        generationRuntime = runtime,
        generationDevice = device,
        favorite = favorite
    )
}
