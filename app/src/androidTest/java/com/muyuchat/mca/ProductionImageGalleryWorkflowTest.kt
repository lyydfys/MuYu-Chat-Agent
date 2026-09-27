package com.muyuchat.mca

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.muyuchat.feature.chat.ChatScreen
import com.muyuchat.feature.chat.ChatUiState
import com.muyuchat.feature.chat.ImageAssetUiItem
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProductionImageGalleryWorkflowTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun galleryPreviewFavoriteAndReturnRemainBoundToTheSelectedAsset() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runId = UUID.randomUUID().toString()
        val fixtureRoot = File(context.cacheDir, "repair-gallery-$runId").apply { check(mkdir()) }
        val preferences = mutableSetOf<String>()
        val red = createImage(fixtureRoot, "slot-a", Color.RED)
        val green = createImage(fixtureRoot, "slot-b", Color.GREEN)
        var state by mutableStateOf(ChatUiState(images = listOf(red, green)))
        val favoriteEvents = mutableListOf<Pair<String, Boolean>>()
        try {
            compose.setContent {
                val host = LocalContext.current
                val isolated = remember(host) {
                    object : ContextWrapper(host) {
                        override fun getApplicationContext(): Context = this
                        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                            val testName = "repair-gallery-$runId-$name"
                            preferences += testName
                            return super.getSharedPreferences(testName, mode)
                        }
                    }
                }
                CompositionLocalProvider(LocalContext provides isolated) {
                    MaterialTheme {
                        ChatScreen(
                            state = state,
                            onInputChange = { state = state.copy(input = it) },
                            onSend = {}, onStop = {}, onNewConversation = {},
                            onSelectConversation = {}, onDeleteConversation = {}, onClearHistory = {},
                            onRenameConversation = { _, _ -> }, onTogglePinConversation = {}, onExportConversation = {},
                            onRegenerate = {}, onDeleteMessage = {}, onUploadFile = {},
                            releaseGenerationImageGrantsIfCoordinatorIdle = { release -> release(); true },
                            onReasoningModeChange = {}, onOpenAgent = {}, onOpenModels = {}, onOpenApi = {}, onOpenSettings = {},
                            onSetImageAssetFavorite = { id, favorite ->
                                favoriteEvents += id to favorite
                                state = state.copy(images = state.images.map { image ->
                                    if (image.id == id) image.copy(favorite = favorite) else image
                                })
                            }
                        )
                    }
                }
            }

            compose.onNodeWithContentDescription("\u6253\u5f00\u5386\u53f2").performClick()
            compose.onNodeWithText("\u56fe\u7247").performClick()
            compose.onNodeWithTag("image.gallery").performScrollToNode(hasTestTag("image.asset.${red.id}"))
            compose.onNodeWithTag("image.asset.${red.id}").performClick()
            assertPreviewBitmap(red.id, expectRed = true)
            compose.onNodeWithContentDescription("\u6536\u85cf\u56fe\u7247").performClick()
            compose.runOnIdle {
                assertEquals(listOf(red.id to true), favoriteEvents)
                assertTrue(state.images.first { it.id == red.id }.favorite)
                assertFalse(state.images.first { it.id == green.id }.favorite)
            }
            compose.onNodeWithContentDescription("\u5173\u95ed\u9884\u89c8").performClick()
            compose.onNodeWithTag("image.preview.${red.id}").assertDoesNotExist()
            compose.onNodeWithTag("image.gallery").performScrollToNode(hasTestTag("image.asset.${green.id}"))
            compose.onNodeWithTag("image.asset.${green.id}").performClick()
            assertPreviewBitmap(green.id, expectRed = false)
            compose.onNodeWithTag("image.preview.${red.id}").assertDoesNotExist()
            compose.onNodeWithContentDescription("\u5173\u95ed\u9884\u89c8").performClick()
            compose.runOnIdle {
                assertEquals(listOf(red.id, green.id), state.images.map(ImageAssetUiItem::id))
                assertTrue(File(Uri.parse(red.uriString).path!!).isFile)
                assertTrue(File(Uri.parse(green.uriString).path!!).isFile)
            }
        } finally {
            preferences.forEach { context.deleteSharedPreferences(it) }
            fixtureRoot.deleteRecursively()
        }
    }

    private fun assertPreviewBitmap(id: String, expectRed: Boolean) {
        compose.waitUntil(10_000L) {
            compose.onNodeWithTag("image.preview.bitmap.$id").runCatching { fetchSemanticsNode() }.isSuccess
        }
        compose.onNodeWithTag("image.preview.$id").assertIsDisplayed()
        val pixels = compose.onNodeWithTag("image.preview.bitmap.$id").captureToImage().toPixelMap()
        val center = pixels[pixels.width / 2, pixels.height / 2]
        assertTrue("Selected fixture bitmap must be decoded", center.alpha > 0.9f)
        if (expectRed) assertTrue(center.red > 0.8f && center.green < 0.2f)
        else assertTrue(center.green > 0.8f && center.red < 0.2f)
    }

    private fun createImage(root: File, id: String, color: Int): ImageAssetUiItem {
        val file = File(root, "$id.png")
        val bitmap = Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(color)
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        return ImageAssetUiItem(id, id, Uri.fromFile(file).toString(), "import", "fixture-$id", "", "48x32", 48, 32)
    }
}
