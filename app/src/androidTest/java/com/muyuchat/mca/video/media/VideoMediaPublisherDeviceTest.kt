package com.muyuchat.mca.video.media

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.muyuchat.core.video.contract.VideoBackendKind
import com.muyuchat.core.video.contract.VideoRunState
import com.muyuchat.mca.McaRoomDatabase
import com.muyuchat.mca.video.data.RoomVideoRunStore
import com.muyuchat.mca.video.codec.VideoMp4Encoder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Device evidence for the V1-04 two-phase publish protocol: a real encoded MP4 is verified,
 * journaled, atomically renamed, committed with the media row + run terminal state, then deleted
 * (tombstone + owned file). Synthetic frames only; model generation remains V1-03/V1-06.
 */
@RunWith(AndroidJUnit4::class)
class VideoMediaPublisherDeviceTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun publishedMediaIsCommittedAndDeletionRemovesOnlyTheOwnedFile() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, McaRoomDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val store = RoomVideoRunStore(database.videoWorkflowDao())
        val publisher = VideoMediaPublisher(context, store)
        val runId = "v-pub-${UUID.randomUUID().toString().lowercase()}"
        val runDir = publisher.ensureRunDirectory(runId)
        try {
            val params = com.muyuchat.core.video.contract.VideoResolvedParams(
                width = 128,
                height = 128,
                frames = 9,
                fps = 16,
                steps = 1,
                sampler = "euler",
                guidance = com.muyuchat.core.video.contract.VideoNodeValue.DecimalValue(6000L, 3),
                flowShift = com.muyuchat.core.video.contract.VideoNodeValue.DecimalValue(3000L, 3),
                seed = 3L,
                promptSha256 = "publisher-test",
                negativePromptSha256 = null,
                imageRef = null,
            )
            val snapshot = com.muyuchat.core.video.contract.VideoRunSnapshot(
                workflowId = com.muyuchat.core.video.contract.VideoWorkflowId("wf-pub"),
                revisionId = com.muyuchat.core.video.contract.VideoRevisionId("rev-pub"),
                canonicalSha256 = "pub",
                inputRefs = emptyMap(),
                requestedParams = params,
                resolvedParams = params,
                modelFingerprint = "mf",
                profileFingerprint = "pf",
                backend = VideoBackendKind.LOCAL_NATIVE,
            )
            store.insertRun(
                runId = runId,
                attemptId = "v-pub-attempt",
                requestId = "v-pub-request",
                snapshot = snapshot,
                backend = VideoBackendKind.LOCAL_NATIVE,
                state = VideoRunState.PUBLISHING,
                nowMs = System.currentTimeMillis(),
            )
            val frames = File(runDir, "frames").apply { mkdirs() }
            val names = (0 until 9).map { index ->
                val name = "frame_%05d.ppm".format(index)
                File(frames, name).writeBytes(ppm(128, 128, index))
                name
            }
            val staged = File(runDir, "output.mp4")
            val encoded = VideoMp4Encoder().encode(frames, names, 128, 128, 16, staged)
            assertTrue("encoder failed: $encoded", encoded is VideoMp4Encoder.Result.Encoded)

            val published = publisher.publish(
                runId = runId,
                attemptId = "v-pub-attempt",
                expectedVersion = 0,
                params = snapshot.resolvedParams,
                shouldAbort = { false },
            )
            assertTrue("publish failed: $published", published is VideoMediaPublisher.PublishResult.Published)
            published as VideoMediaPublisher.PublishResult.Published
            val record = published.record
            val committed = store.completeRunWithMedia(
                runId = runId,
                attemptId = "v-pub-attempt",
                expectedVersion = 0,
                fromState = VideoRunState.PUBLISHING.name,
                media = record,
                nowMs = System.currentTimeMillis(),
            )
            assertTrue("terminal transaction failed", committed)
            publisher.finalizePublish(runId, record)

            val row = store.mediaById(record.mediaId)
            assertNotNull(row)
            assertEquals(VideoRunState.COMPLETED.name, store.runById(runId)!!.state)
            assertTrue("published file missing", File(context.filesDir, "video/media/${record.relativePath}").isFile)
            assertTrue("playback reference must be a content URI", published.reference.startsWith("content://"))

            val deleted = publisher.deleteOwnedMedia(com.muyuchat.core.video.contract.VideoMediaAssetId(record.mediaId))
            assertTrue("delete failed: $deleted", deleted is com.muyuchat.core.video.contract.VideoDeleteResult.Deleted)
            assertNull(store.mediaById(record.mediaId))
            assertTrue(!File(context.filesDir, "video/media/${record.relativePath}").exists())
        } finally {
            runDir.deleteRecursively()
            database.close()
        }
    }

    private fun ppm(width: Int, height: Int, index: Int): ByteArray {
        val header = "P6\n$width $height\n255\n".toByteArray(Charsets.ISO_8859_1)
        val pixels = ByteArray(width * height * 3)
        for (offset in pixels.indices step 3) {
            pixels[offset] = (30 + index * 20).coerceAtMost(255).toByte()
            pixels[offset + 1] = 80.toByte()
            pixels[offset + 2] = 160.toByte()
        }
        return header + pixels
    }
}
