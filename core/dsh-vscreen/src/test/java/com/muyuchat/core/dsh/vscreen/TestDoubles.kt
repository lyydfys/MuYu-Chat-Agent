package com.muyuchat.core.dsh.vscreen

import kotlinx.coroutines.CompletableDeferred

internal class FakeClock(var now: Long = 1_000L) {
    fun advance(deltaMillis: Long) {
        now += deltaMillis
    }
}

internal class FakeDisplayPort(
    var gate: CompletableDeferred<Unit>? = null,
    var failure: Exception? = null,
    var nextDisplayId: Int = 7
) : DshVscreenDisplayPort {
    val handles = mutableListOf<FakeDisplayHandle>()
    var startCalls: Int = 0

    override suspend fun start(geometry: DshVscreenGeometry): DshVscreenDisplayHandle {
        startCalls += 1
        gate?.await()
        failure?.let { throw it }
        return FakeDisplayHandle(nextDisplayId++, geometry).also { handles += it }
    }
}

internal class FakeDisplayHandle(
    override val displayId: Int,
    override val geometry: DshVscreenGeometry
) : DshVscreenDisplayHandle {
    val pendingFrames: ArrayDeque<DshVscreenFrame?> = ArrayDeque()
    var frameProvider: (suspend () -> DshVscreenFrame?)? = null
    var latestFrameCalls: Int = 0
    var releaseCount: Int = 0

    override suspend fun latestFrame(timeoutMillis: Long): DshVscreenFrame? {
        latestFrameCalls += 1
        frameProvider?.let { provider -> return provider() }
        return if (pendingFrames.isEmpty()) null else pendingFrames.removeFirst()
    }

    override suspend fun release() {
        releaseCount += 1
    }
}

internal class FakeFrameSink : DshVscreenFrameSink {
    var persisted: Int = 0
    var failWith: Exception? = null

    override suspend fun persist(frame: DshVscreenFrame, displayId: Int): DshVscreenPersistedFrame {
        failWith?.let { throw it }
        persisted += 1
        return DshVscreenPersistedFrame("/tmp/vscreen/frame-$persisted.png", frame.width, frame.height)
    }
}

internal class FakePreviewPort : DshVscreenPreviewPort {
    val delivered = mutableListOf<DshVscreenFrame>()
    val stoppedDisplayIds = mutableListOf<Int>()

    override fun onFrame(displayId: Int, frame: DshVscreenFrame, deliveredAtMillis: Long) {
        delivered += frame
    }

    override fun onDisplayStopped(displayId: Int, reason: String) {
        stoppedDisplayIds += displayId
    }
}

internal class FakePrivilegedPort(
    var report: DshVscreenProbeReport = probeReport(rootProven = true),
    var commandResult: DshVscreenCommandResult = DshVscreenCommandResult(0, "", "", false)
) : DshVscreenPrivilegedPort {
    val commands = mutableListOf<DshVscreenPrivilegedCommand>()
    var probeCalls: Int = 0

    override suspend fun probe(): DshVscreenProbeReport {
        probeCalls += 1
        return report
    }

    override suspend fun execute(command: DshVscreenPrivilegedCommand): DshVscreenCommandResult {
        commands += command
        return commandResult
    }

    override fun targets(): DshVscreenPrivilegedTargets =
        DshVscreenPrivilegedTargets(
            privilegedDexPath = "/data/local/tmp/dsh-vscreen.jar",
            shizukuAppId = "com.muyuchat.mca"
        )
}

internal fun evidence(
    transport: DshVscreenTransport,
    shellProven: Boolean
): DshVscreenTransportEvidence = DshVscreenTransportEvidence(
    transport = transport,
    shellProven = shellProven,
    shellDetail = if (shellProven) "proven shell" else "no shell"
)

internal fun captureEvidence(
    proof: DshVscreenProof = DshVscreenProof.UNPROVEN
): DshVscreenCaptureEvidence = DshVscreenCaptureEvidence(
    source = if (proof == DshVscreenProof.UNSUPPORTED) {
        DshVscreenDisplaySource.NONE
    } else {
        DshVscreenDisplaySource.PROJECTION
    },
    proof = proof,
    detail = if (proof == DshVscreenProof.UNSUPPORTED) {
        "screen capture is unavailable"
    } else {
        "screen capture path"
    }
)

internal fun probeReport(
    rootProven: Boolean = false,
    shizukuProven: Boolean = false,
    capture: DshVscreenCaptureEvidence = captureEvidence()
): DshVscreenProbeReport = DshVscreenProbeReport(
    root = evidence(DshVscreenTransport.ROOT, rootProven),
    shizuku = evidence(DshVscreenTransport.SHIZUKU, shizukuProven),
    capture = capture
)

internal fun createRequest(orientation: DshVscreenOrientation = DshVscreenOrientation.PORTRAIT): DshVscreenCreateRequest =
    DshVscreenCreateRequest(
        geometry = DshVscreenGeometry.canonical(orientation),
        explicitOrientation = false
    )

internal fun testFrame(
    sizeBytes: Int = 16,
    width: Int = 100,
    height: Int = 200,
    capturedAtMillis: Long = 1_000L
): DshVscreenFrame = DshVscreenFrame(
    format = DshVscreenFrameFormat.PNG,
    bytes = ByteArray(sizeBytes) { 1 },
    width = width,
    height = height,
    capturedAtMillis = capturedAtMillis
)
