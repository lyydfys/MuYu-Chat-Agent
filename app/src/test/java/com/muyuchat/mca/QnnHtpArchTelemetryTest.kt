package com.muyuchat.mca

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QnnHtpArchTelemetryTest {
    @Test
    fun `positive native selected and transport values win`() {
        val telemetry = resolveQnnHtpArchTelemetry(
            JSONObject()
                .put("selectedHtpArch", 79)
                .put("htpArchVersion", 73)
                .put("transportHtpArch", 81)
                .put("unetTransportHtpArch", 75),
            stagedRuntime = staged(context = 68, transport = 83)
        )

        assertEquals(79, telemetry.selectedHtpArch)
        assertEquals(81, telemetry.transportHtpArch)
    }

    @Test
    fun `zero native values fall through to execution runtime`() {
        val telemetry = resolveQnnHtpArchTelemetry(
            JSONObject()
                .put("selectedHtpArch", 0)
                .put("htpArchVersion", 0)
                .put("transportHtpArch", 0)
                .put(
                    "executionRuntime",
                    JSONObject()
                        .put("htpArchVersion", 79)
                        .put("htpSkelLibraryPath", "/data/qnn/libQnnHtpV79Skel.so")
                        .put("htpStubLibraryPath", "/data/qnn/libQnnHtpV79Stub.so")
                ),
            stagedRuntime = staged(context = 68, transport = 83)
        )

        assertEquals(79, telemetry.selectedHtpArch)
        assertEquals(79, telemetry.transportHtpArch)
    }

    @Test
    fun `versioned skel or stub path supplies execution architecture`() {
        val skel = resolveQnnHtpArchTelemetry(
            JSONObject().put(
                "executionRuntime",
                JSONObject().put("htpSkelLibraryPath", "C:\\qnn\\libQnnHtpV75Skel.so")
            )
        )
        val stubOnly = resolveQnnHtpArchTelemetry(
            JSONObject().put(
                "executionRuntime",
                JSONObject().put("htpStubLibraryPath", "/vendor/lib64/libQnnHtpV73Stub.so")
            )
        )

        assertEquals(75, skel.selectedHtpArch)
        assertEquals(75, skel.transportHtpArch)
        assertEquals(73, stubOnly.selectedHtpArch)
        assertEquals(73, stubOnly.transportHtpArch)
    }

    @Test
    fun `generic or malformed library paths do not claim an architecture`() {
        val telemetry = resolveQnnHtpArchTelemetry(
            JSONObject().put(
                "executionRuntime",
                JSONObject()
                    .put("htpSkelLibraryPath", "/vendor/lib64/libQnnHtp.so")
                    .put("htpStubLibraryPath", "/vendor/lib64/libQnnHtpVfooStub.so")
            )
        )

        assertNull(telemetry.selectedHtpArch)
        assertNull(telemetry.transportHtpArch)
    }

    @Test
    fun `staged context and transport are last resort fallbacks`() {
        val telemetry = resolveQnnHtpArchTelemetry(
            JSONObject().put("runtimeInspection", JSONObject().put("htpArchVersion", 0)),
            stagedRuntime = staged(context = 68, transport = 79)
        )

        assertEquals(68, telemetry.selectedHtpArch)
        assertEquals(79, telemetry.transportHtpArch)
    }

    @Test
    fun `native phase transport remains distinct from staged context`() {
        val telemetry = resolveQnnHtpArchTelemetry(
            JSONObject().put("unetTransportHtpArch", 81),
            stagedRuntime = staged(context = 68, transport = 79)
        )

        assertEquals(68, telemetry.selectedHtpArch)
        assertEquals(81, telemetry.transportHtpArch)
    }

    @Test
    fun `missing and nonpositive values resolve to null`() {
        val telemetry = resolveQnnHtpArchTelemetry(
            JSONObject()
                .put("selectedHtpArch", -1)
                .put("htpArchVersion", "0")
                .put("transportHtpArch", "not-a-number")
        )

        assertNull(telemetry.selectedHtpArch)
        assertNull(telemetry.transportHtpArch)
    }

    private fun staged(context: Int, transport: Int): QnnImageStagedRuntime =
        QnnImageStagedRuntime(
            directory = File("/tmp/qnn-stage"),
            sourceDirectory = File("/tmp/qnn-source"),
            htpArchVersion = context,
            transportHtpArchVersion = transport,
            fingerprint = "test",
            files = emptyList(),
            reused = true
        )
}
