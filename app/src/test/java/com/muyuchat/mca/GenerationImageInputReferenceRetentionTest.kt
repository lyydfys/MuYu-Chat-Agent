package com.muyuchat.mca

import org.junit.Assert.assertEquals
import org.junit.Test

class GenerationImageInputReferenceRetentionTest {
    @Test
    fun `history and terminal retry job drafts retain every content input reference`() {
        val retained = retainedGenerationImageContentReferences(
            historyReferences = setOf(
                "content://history/input",
                "file:///private/history.png"
            ),
            jobInputDrafts = listOf(
                LocalImageInputDraft(
                    taskMode = LocalImageTaskMode.INPAINT,
                    inputImageReference = " content://job/input ",
                    maskImageReference = "content://job/mask"
                ),
                LocalImageInputDraft(
                    taskMode = LocalImageTaskMode.CONTROL,
                    controlImageReference = "CONTENT://job/control"
                ),
                LocalImageInputDraft(
                    inputImageReference = "file:///private/job.png"
                )
            )
        )

        assertEquals(
            setOf(
                "content://history/input",
                "content://job/input",
                "content://job/mask",
                "CONTENT://job/control"
            ),
            retained
        )
    }
}
