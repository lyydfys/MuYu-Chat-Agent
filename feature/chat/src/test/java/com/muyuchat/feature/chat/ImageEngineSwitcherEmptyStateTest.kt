package com.muyuchat.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Test

// B45/S2: the image-page model dropdown must distinguish "capabilities still being prepared" from
// "the catalog really has no models". Only the local source can be in the loading state; the cloud
// rows are projected synchronously from state.cloudModels and keep their existing wording.
class ImageEngineSwitcherEmptyStateTest {
    @Test
    fun localEmptyStateShowsLoadingUntilTheCatalogRowsArePrepared() {
        assertEquals("正在加载本地生图模型…", imageEngineMenuEmptyText(isCloud = false, loading = true))
        assertEquals("暂无本地生图模型", imageEngineMenuEmptyText(isCloud = false, loading = false))
    }

    @Test
    fun cloudEmptyStateKeepsItsWordingRegardlessOfTheLocalLoadingFlag() {
        assertEquals("暂无云端生图模型", imageEngineMenuEmptyText(isCloud = true, loading = true))
        assertEquals("暂无云端生图模型", imageEngineMenuEmptyText(isCloud = true, loading = false))
    }
}