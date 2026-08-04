package com.muyuchat.core.deviceprofile

import com.muyuchat.core.telemetry.SocFamily
import com.muyuchat.core.telemetry.SocInfo
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceAccelerationAnalyzerTest {
    @Test
    fun snapdragon8EliteIsQnnCandidateWhenRuntimeIsMissing() {
        val profile = DeviceAccelerationAnalyzer.assess(
            soc = snapdragon("Qualcomm", "SM8750P"),
            totalRamBytes = 16.gb,
            qnnRuntime = QnnRuntimeStatus.Missing
        )

        assertEquals(SnapdragonAccelerationTier.SNAPDRAGON_8_ELITE, profile.snapdragonTier)
        assertEquals("SM8750P", profile.chipsetCode)
        assertEquals("HTP v79 class", profile.qnnHtpGeneration)
        assertTrue(profile.stableDiffusion15NpuCandidate)
        assertTrue(profile.sdxlNpuCandidate)
        assertEquals(
            AccelerationCapabilityStatus.DEVICE_CAPABLE_RUNTIME_MISSING,
            profile.localVision.status
        )
        assertEquals(
            AccelerationCapabilityStatus.DEVICE_CAPABLE_RUNTIME_MISSING,
            profile.localImage.status
        )
        assertFalse(profile.qnnRuntime.ready)
    }

    @Test
    fun snapdragon8EliteGen5MapsToHighestNpuClass() {
        val profile = DeviceAccelerationAnalyzer.assess(
            soc = snapdragon("Qualcomm", "SM8850"),
            totalRamBytes = 16.gb,
            qnnRuntime = readyRuntime()
        )

        assertEquals(SnapdragonAccelerationTier.SNAPDRAGON_8_ELITE_GEN5, profile.snapdragonTier)
        assertEquals("HTP v81 class", profile.qnnHtpGeneration)
        assertEquals(AccelerationCapabilityStatus.EXPERIMENTAL_READY, profile.localVision.status)
        assertEquals(AccelerationCapabilityStatus.EXPERIMENTAL_READY, profile.localImage.status)
        assertTrue(profile.qnnRuntime.ready)
    }

    @Test
    fun qnnRuntimeLoadFailureDoesNotOpenSmokeGate() {
        val profile = DeviceAccelerationAnalyzer.assess(
            soc = snapdragon("Qualcomm", "SM8750"),
            totalRamBytes = 16.gb,
            qnnRuntime = failedRuntime("mock load failure")
        )

        assertTrue(profile.qnnRuntime.ready)
        assertFalse(profile.qnnRuntime.usableForSmoke)
        assertEquals(
            AccelerationCapabilityStatus.DEVICE_CAPABLE_RUNTIME_LOAD_FAILED,
            profile.localVision.status
        )
        assertEquals(
            AccelerationCapabilityStatus.DEVICE_CAPABLE_RUNTIME_LOAD_FAILED,
            profile.localImage.status
        )
        assertTrue(profile.localImage.reason.contains("mock load failure"))
    }

    @Test
    fun qnnRuntimeFilesWithoutLoadProbeDoNotOpenSmokeGate() {
        val profile = DeviceAccelerationAnalyzer.assess(
            soc = snapdragon("Qualcomm", "SM8750"),
            totalRamBytes = 16.gb,
            qnnRuntime = unverifiedRuntime()
        )

        assertTrue(profile.qnnRuntime.ready)
        assertFalse(profile.qnnRuntime.usableForSmoke)
        assertEquals(
            AccelerationCapabilityStatus.DEVICE_CAPABLE_RUNTIME_UNVERIFIED,
            profile.localVision.status
        )
        assertEquals(
            AccelerationCapabilityStatus.DEVICE_CAPABLE_RUNTIME_UNVERIFIED,
            profile.localImage.status
        )
    }

    @Test
    fun snapdragon8Gen2KeepsBothQnnImageRoutesOpen() {
        val profile = DeviceAccelerationAnalyzer.assess(
            soc = snapdragon("qcom", "SM8550"),
            totalRamBytes = 12.gb,
            qnnRuntime = readyRuntime()
        )

        assertEquals(SnapdragonAccelerationTier.SNAPDRAGON_8_GEN2, profile.snapdragonTier)
        assertEquals("HTP v73 class", profile.qnnHtpGeneration)
        assertTrue(profile.stableDiffusion15NpuCandidate)
        assertTrue(profile.sdxlNpuCandidate)
        assertEquals(AccelerationCapabilityStatus.EXPERIMENTAL_READY, profile.localImage.status)
    }

    @Test
    fun unknownFutureSnapdragonIsNotBlockedByMissingTierProfile() {
        val profile = DeviceAccelerationAnalyzer.assess(
            soc = snapdragon("Qualcomm", "SM9999"),
            totalRamBytes = 12.gb,
            qnnRuntime = readyRuntime()
        )

        assertEquals(SnapdragonAccelerationTier.SNAPDRAGON_OTHER, profile.snapdragonTier)
        assertTrue(profile.stableDiffusion15NpuCandidate)
        assertTrue(profile.sdxlNpuCandidate)
        assertEquals(AccelerationCapabilityStatus.EXPERIMENTAL_READY, profile.localVision.status)
        assertEquals(AccelerationCapabilityStatus.EXPERIMENTAL_READY, profile.localImage.status)
        assertFalse(profile.localVision.reason.contains("白名单"))
    }

    @Test
    fun nonSnapdragonKeepsDiscoveredQnnRuntimeForRealSmoke() {
        val profile = DeviceAccelerationAnalyzer.assess(
            soc = SocInfo("MediaTek", "Dimensity 9400", SocFamily.Dimensity),
            totalRamBytes = 16.gb,
            qnnRuntime = readyRuntime()
        )

        assertEquals(SnapdragonAccelerationTier.NONE, profile.snapdragonTier)
        assertFalse(profile.supportsSnapdragonNpu)
        assertFalse(profile.stableDiffusion15NpuCandidate)
        assertFalse(profile.sdxlNpuCandidate)
        assertEquals(AccelerationCapabilityStatus.READY, profile.localChat.status)
        assertTrue(profile.qnnRuntime.usableForSmoke)
        assertEquals(AccelerationCapabilityStatus.EXPERIMENTAL_READY, profile.localVision.status)
        assertEquals(AccelerationCapabilityStatus.EXPERIMENTAL_READY, profile.localImage.status)
        assertTrue(profile.notes.any { it.contains("真实 native graph smoke") })
    }

    @Test
    fun qnnRuntimeInspectorRequiresSystemHtpAndSkelLibraries() {
        val dir = Files.createTempDirectory("qnn-runtime").toFile()
        dir.resolve("libQnnSystem.so").writeText("x")
        dir.resolve("libQnnHtp.so").writeText("x")

        assertFalse(QnnRuntimeStatus.inspect(listOf(dir)).ready)

        dir.resolve("libQnnHtpV79Skel.so").writeText("x")

        val status = QnnRuntimeStatus.inspect(listOf(dir))
        assertTrue(status.ready)
        assertFalse(status.loadable)
        assertEquals(QnnRuntimeProbeState.NOT_REQUESTED, status.probeState)
        assertTrue(status.probeMessage.contains("not requested"))
        assertTrue(status.searchDirectories.any { it.contains("qnn-runtime") })
        assertTrue(status.qnnSystemLibraryPath!!.endsWith("libQnnSystem.so"))
        assertTrue(status.qnnHtpLibraryPath!!.endsWith("libQnnHtp.so"))
        assertTrue(status.htpSkelLibraryPath!!.endsWith("libQnnHtpV79Skel.so"))
    }

    @Test
    fun qnnRuntimeInspectorCanProbeNativeLoadability() {
        val dir = Files.createTempDirectory("qnn-runtime-probe").toFile()
        dir.resolve("libQnnSystem.so").writeText("x")
        dir.resolve("libQnnHtp.so").writeText("x")
        dir.resolve("libQnnHtpV79Skel.so").writeText("x")
        val loaded = mutableListOf<String>()

        val status = QnnRuntimeStatus.inspect(
            searchDirectories = listOf(dir),
            probeLibraries = true,
            libraryLoader = { loaded += File(it).name }
        )

        assertTrue(status.ready)
        assertTrue(status.loadable)
        assertEquals(QnnRuntimeProbeState.LOADABLE, status.probeState)
        assertEquals(
            listOf("libQnnSystem.so"),
            loaded
        )
    }

    @Test
    fun qnnRuntimeInspectorTreatsCdspRpcAsDiagnosticNotSmokeGate() {
        val dir = Files.createTempDirectory("qnn-runtime-rpc-blocked").toFile()
        dir.resolve("libQnnSystem.so").writeText("x")
        dir.resolve("libQnnHtp.so").writeText("x")
        dir.resolve("libQnnHtpV81Skel.so").writeText("x")
        dir.resolve("libQnnHtpV81Stub.so").writeText("x")
        dir.resolve("libcdsprpc.so").writeText("x")
        val loaded = mutableListOf<String>()

        val status = QnnRuntimeStatus.inspect(
            searchDirectories = listOf(dir),
            probeLibraries = true,
            libraryLoader = { path ->
                val name = File(path).name
                loaded += name
            }
        )
        val profile = DeviceAccelerationAnalyzer.assess(
            soc = snapdragon("Qualcomm", "SM8750P"),
            totalRamBytes = 16.gb,
            qnnRuntime = status
        )

        assertTrue(status.ready)
        assertTrue(status.hostRuntimeLoadable)
        assertTrue(status.htpTransportVerified)
        assertFalse(status.transportDependencyBlocked)
        assertTrue(status.usableForSmoke)
        assertTrue(status.cdspRpcLibraryPresent)
        assertFalse(status.cdspRpcLibraryLoadable)
        assertTrue(status.cdspRpcMessage.contains("not probed directly"))
        assertEquals(
            listOf("libQnnSystem.so"),
            loaded
        )
        assertEquals(
            AccelerationCapabilityStatus.EXPERIMENTAL_READY,
            profile.localVision.status
        )
        assertEquals(
            AccelerationCapabilityStatus.EXPERIMENTAL_READY,
            profile.localImage.status
        )
    }

    @Test
    fun qnnRuntimeInspectorRejectsSideLoadedCrossDirectoryCombination() {
        val systemDir = Files.createTempDirectory("qnn-runtime-system").toFile()
        val skelDir = Files.createTempDirectory("qnn-runtime-skel").toFile()
        systemDir.resolve("libQnnSystem.so").writeText("x")
        systemDir.resolve("libQnnHtp.so").writeText("x")
        skelDir.resolve("libQnnHtpV73Skel.so").writeText("x")
        skelDir.resolve("libQnnHtpV73Stub.so").writeText("x")

        val status = QnnRuntimeStatus.inspect(
            listOf(systemDir, skelDir),
            preferredHtpArchVersion = 73
        )

        assertFalse(status.ready)
        assertFalse(status.usableForSmoke)
        assertTrue(status.probeMessage.contains("coherent"))
    }

    @Test
    fun qnnRuntimeCandidatesRejectSideLoadedSplitButPermitPlatformSplit() {
        val hostDir = Files.createTempDirectory("qnn-runtime-platform-host").toFile()
        val dspDir = Files.createTempDirectory("qnn-runtime-platform-dsp").toFile()
        hostDir.resolve("libQnnSystem.so").writeText("system")
        hostDir.resolve("libQnnHtp.so").writeText("htp")
        dspDir.resolve("libQnnHtpV83Skel.so").writeText("skel")
        dspDir.resolve("libQnnHtpV83Stub.so").writeText("stub")

        assertTrue(QnnRuntimeProfileSelector.candidates(listOf(hostDir, dspDir)).isEmpty())

        val platformPaths = setOf(hostDir.canonicalPath, dspDir.canonicalPath)
        val candidates = QnnRuntimeProfileSelector.candidates(
            searchDirectories = listOf(hostDir, dspDir),
            preferredHtpArchVersion = null,
            isPlatformDirectory = { directory -> directory.canonicalPath in platformPaths }
        )

        assertEquals(1, candidates.size)
        assertEquals(83, candidates.single().htpArchVersion)
        assertEquals(hostDir.canonicalPath, candidates.single().hostDirectory.canonicalPath)
        assertEquals(dspDir.canonicalPath, candidates.single().dspDirectory.canonicalPath)
    }

    @Test
    fun qnnRuntimeCandidatesKeepEverySameArchProfileAndOnlyRankPreferredArch() {
        fun runtime(name: String, arch: Int, withStub: Boolean = true): File =
            Files.createTempDirectory(name).toFile().also { directory ->
                directory.resolve("libQnnSystem.so").writeText("system-$name")
                directory.resolve("libQnnHtp.so").writeText("htp-$name")
                directory.resolve("libQnnHtpV${arch}Skel.so").writeText("skel-$name")
                if (withStub) {
                    directory.resolve("libQnnHtpV${arch}Stub.so").writeText("stub-$name")
                }
            }

        val futureA = runtime("qnn-runtime-v83-a", 83)
        val futureB = runtime("qnn-runtime-v83-b", 83)
        val preferred = runtime("qnn-runtime-v79", 79, withStub = false)

        val candidates = QnnRuntimeProfileSelector.candidates(
            searchDirectories = listOf(futureA, preferred, futureB),
            preferredHtpArchVersion = 79
        )

        assertEquals(listOf(79, 83, 83), candidates.map(QnnRuntimeProfile::htpArchVersion))
        assertEquals(
            listOf(preferred, futureA, futureB).map(File::getCanonicalPath),
            candidates.map { profile -> profile.hostDirectory.canonicalPath }
        )
        assertEquals(null, candidates.first().htpStubLibrary)
        assertEquals(preferred.canonicalPath, QnnRuntimeProfileSelector.select(
            listOf(futureA, preferred, futureB),
            preferredHtpArchVersion = 79
        )?.hostDirectory?.canonicalPath)
    }

    @Test
    fun qnnRuntimeSelectShortCircuitsBeforeMaterializingPlatformCrossProduct() {
        fun host(name: String): File = Files.createTempDirectory(name).toFile().also { directory ->
            directory.resolve("libQnnSystem.so").writeText("system-$name")
            directory.resolve("libQnnHtp.so").writeText("htp-$name")
        }
        fun dsp(name: String): File = Files.createTempDirectory(name).toFile().also { directory ->
            directory.resolve("libQnnHtpV83Skel.so").writeText("skel-$name")
            directory.resolve("libQnnHtpV83Stub.so").writeText("stub-$name")
        }
        val hostA = host("qnn-lazy-host-a")
        val dspA = dsp("qnn-lazy-dsp-a")
        val hostB = host("qnn-lazy-host-b")
        val dspB = dsp("qnn-lazy-dsp-b")
        var classifierCalls = 0

        val selected = QnnRuntimeProfileSelector.select(
            searchDirectories = listOf(hostA, dspA, hostB, dspB),
            preferredHtpArchVersion = 83,
            isPlatformDirectory = {
                classifierCalls += 1
                true
            }
        )

        assertEquals(hostA.canonicalPath, selected?.hostDirectory?.canonicalPath)
        assertEquals(dspA.canonicalPath, selected?.dspDirectory?.canonicalPath)
        assertEquals(2, classifierCalls)
    }

    @Test
    fun qnnRuntimeInspectorSelectsExactGen2ProfileInsteadOfHighestProfile() {
        val genericDir = Files.createTempDirectory("qnn-runtime-v81").toFile()
        val gen2Dir = Files.createTempDirectory("qnn-runtime-v73").toFile()
        genericDir.resolve("libQnnSystem.so").writeText("v81-system")
        genericDir.resolve("libQnnHtp.so").writeText("v81-htp")
        genericDir.resolve("libQnnHtpV81Skel.so").writeText("v81-skel")
        genericDir.resolve("libQnnHtpV81Stub.so").writeText("v81-stub")
        gen2Dir.resolve("libQnnSystem.so").writeText("v73-system")
        gen2Dir.resolve("libQnnHtp.so").writeText("v73-htp")
        gen2Dir.resolve("libQnnHtpV73Skel.so").writeText("v73-skel")
        gen2Dir.resolve("libQnnHtpV73Stub.so").writeText("v73-stub")
        val loaded = mutableListOf<String>()

        val status = QnnRuntimeStatus.inspect(
            searchDirectories = listOf(genericDir, gen2Dir),
            probeLibraries = true,
            preferredHtpArchVersion = 73,
            libraryLoader = { path -> loaded += File(path).name }
        )

        assertTrue(status.ready)
        assertTrue(status.usableForSmoke)
        assertEquals(73, status.htpArchVersion)
        assertTrue(status.qnnSystemLibraryPath!!.contains("qnn-runtime-v73"))
        assertTrue(status.qnnHtpLibraryPath!!.contains("qnn-runtime-v73"))
        assertTrue(status.htpSkelLibraryPath!!.endsWith("libQnnHtpV73Skel.so"))
        assertTrue(status.htpStubLibraryPath!!.endsWith("libQnnHtpV73Stub.so"))
        assertEquals(listOf("libQnnSystem.so"), loaded)
    }

    @Test
    fun qnnRuntimeInspectorFallsBackWhenPreferredTransportIsNotPackaged() {
        val gen2Dir = Files.createTempDirectory("qnn-runtime-v73-fallback").toFile()
        gen2Dir.resolve("libQnnSystem.so").writeText("v73-system")
        gen2Dir.resolve("libQnnHtp.so").writeText("v73-htp")
        gen2Dir.resolve("libQnnHtpV73Skel.so").writeText("v73-skel")
        gen2Dir.resolve("libQnnHtpV73Stub.so").writeText("v73-stub")

        val status = QnnRuntimeStatus.inspect(
            searchDirectories = listOf(gen2Dir),
            preferredHtpArchVersion = 79
        )

        assertTrue(status.ready)
        assertEquals(73, status.htpArchVersion)
        assertTrue(status.htpSkelLibraryPath!!.endsWith("libQnnHtpV73Skel.so"))
        assertTrue(status.htpStubLibraryPath!!.endsWith("libQnnHtpV73Stub.so"))
    }

    @Test
    fun qnnRuntimeInspectorReportsNativeLoadFailureWithoutClaimingLoadable() {
        val dir = Files.createTempDirectory("qnn-runtime-probe-fail").toFile()
        dir.resolve("libQnnSystem.so").writeText("x")
        dir.resolve("libQnnHtp.so").writeText("x")
        dir.resolve("libQnnHtpV79Skel.so").writeText("x")

        val status = QnnRuntimeStatus.inspect(
            searchDirectories = listOf(dir),
            probeLibraries = true,
            libraryLoader = { error("mock load failure") }
        )

        assertTrue(status.ready)
        assertFalse(status.loadable)
        assertEquals(QnnRuntimeProbeState.LOAD_FAILED, status.probeState)
        assertTrue(status.probeMessage.contains("mock load failure"))
    }

    @Test
    fun qnnRuntimeInspectorReportsWhichRuntimeFilesAreMissing() {
        val dir = Files.createTempDirectory("qnn-runtime-missing").toFile()
        dir.resolve("libQnnSystem.so").writeText("x")

        val status = QnnRuntimeStatus.inspect(listOf(dir), probeLibraries = true)

        assertFalse(status.ready)
        assertFalse(status.loadable)
        assertEquals(QnnRuntimeProbeState.FILES_MISSING, status.probeState)
        assertTrue(status.probeMessage.contains("libQnnHtp.so"))
        assertTrue(status.probeMessage.contains("libQnnHtpVxxSkel.so"))
    }

    @Test
    fun chipsetCodeNormalizationAcceptsCommonQualcommPrefixes() {
        assertEquals("SM8750P", DeviceAccelerationAnalyzer.normalizeChipsetCode("qualcomm snapdragon SM8750P"))
        assertEquals("QCS8550", DeviceAccelerationAnalyzer.normalizeChipsetCode("qcs8550 robotics"))
        assertEquals("SDM845", DeviceAccelerationAnalyzer.normalizeChipsetCode("Qualcomm SDM845"))
        assertEquals("", DeviceAccelerationAnalyzer.normalizeChipsetCode("snapdragon 8 elite"))
    }

    @Test
    fun userFacingChipsetNamesDoNotExposeInternalQualcommCodes() {
        val mainstreamNames = mapOf(
            "SM6375" to "骁龙 695",
            "SM6450" to "骁龙 6 Gen 1",
            "SM6475" to "骁龙 6 Gen 3",
            "SM7325" to "骁龙 778G",
            "SM7350" to "骁龙 780G",
            "SM7435" to "骁龙 7s Gen 2",
            "SM7450" to "骁龙 7 Gen 1",
            "SM7475" to "骁龙 7+ Gen 2",
            "SM7550" to "骁龙 7 Gen 3",
            "SM7635" to "骁龙 7s Gen 3",
            "SM7675" to "骁龙 7+ Gen 3"
        )
        mainstreamNames.forEach { (code, publicName) ->
            assertEquals(publicName, DeviceAccelerationAnalyzer.userFacingChipsetName(code))
            assertFalse(DeviceAccelerationAnalyzer.publicChipsetDisplayName(code).contains(code))
        }
        assertEquals("骁龙 855", DeviceAccelerationAnalyzer.userFacingChipsetName("SM8150"))
        assertEquals("骁龙 865", DeviceAccelerationAnalyzer.userFacingChipsetName("SM8250"))
        assertEquals("骁龙 888", DeviceAccelerationAnalyzer.userFacingChipsetName("SM8350"))
        assertEquals("骁龙 8 Gen 1", DeviceAccelerationAnalyzer.userFacingChipsetName("SM8450"))
        assertEquals("骁龙 8+ Gen 1", DeviceAccelerationAnalyzer.userFacingChipsetName("SM8475"))
        assertEquals("骁龙 8 Gen 2", DeviceAccelerationAnalyzer.userFacingChipsetName("SM8550P"))
        assertEquals("骁龙 8s Gen 3", DeviceAccelerationAnalyzer.userFacingChipsetName("SM8635"))
        assertEquals("骁龙 8 Gen 3", DeviceAccelerationAnalyzer.userFacingChipsetName("SM8650"))
        assertEquals("骁龙 8 Elite", DeviceAccelerationAnalyzer.userFacingChipsetName("SM8750"))
        assertEquals("骁龙 8 Elite Gen 5", DeviceAccelerationAnalyzer.userFacingChipsetName("SM8850P"))
        assertEquals("骁龙芯片", DeviceAccelerationAnalyzer.userFacingChipsetName("SM9999"))
        assertEquals("骁龙 7 Gen 1", DeviceAccelerationAnalyzer.userFacingChipsetName("Qualcomm SM7450-AB"))
    }

    @Test
    fun publicChipsetDisplayNameNeverLeaksUnknownInternalCodes() {
        assertEquals(
            "骁龙芯片",
            DeviceAccelerationAnalyzer.publicChipsetDisplayName("Qualcomm SM9999-AB", SocFamily.Snapdragon)
        )
        assertEquals(
            "骁龙芯片",
            DeviceAccelerationAnalyzer.publicChipsetDisplayName("Qualcomm SDM845")
        )
        assertEquals(
            "未识别芯片",
            DeviceAccelerationAnalyzer.publicChipsetDisplayName("MediaTek MT9999", SocFamily.Dimensity)
        )
        assertEquals(
            "未识别芯片",
            DeviceAccelerationAnalyzer.publicChipsetDisplayName("vendor-internal-42", SocFamily.Unknown)
        )
        assertEquals(
            "天玑 9300 系列",
            DeviceAccelerationAnalyzer.publicChipsetDisplayName("MediaTek MT6989", SocFamily.Dimensity)
        )
        assertEquals(
            "天玑 9400",
            DeviceAccelerationAnalyzer.publicChipsetDisplayName("MediaTek Dimensity 9400", SocFamily.Dimensity)
        )
        assertEquals(
            "骁龙 8 Elite",
            DeviceAccelerationAnalyzer.publicChipsetDisplayName("Qualcomm Snapdragon 8 Elite", SocFamily.Snapdragon)
        )
    }

    @Test
    fun qnnSocModelMappingCoversTargetSnapdragonTiers() {
        assertEquals(69, DeviceAccelerationAnalyzer.expectedQnnSocModelForChipsetCode("SM8750P"))
        assertEquals(87, DeviceAccelerationAnalyzer.expectedQnnSocModelForChipsetCode("SM8850"))
        assertEquals(43, DeviceAccelerationAnalyzer.expectedQnnSocModelForChipsetCode("SM8550"))
        assertEquals(66, DeviceAccelerationAnalyzer.expectedQnnSocModelForChipsetCode("QCS8550"))
        assertEquals("SM8550", DeviceAccelerationAnalyzer.qnnSocModelName(43))
        assertEquals("SM8750", DeviceAccelerationAnalyzer.qnnSocModelName(69))
        assertEquals("socModel=999", DeviceAccelerationAnalyzer.qnnSocModelName(999))
    }

    @Test
    fun publicSnapdragonCodesMapToExactHtpProfiles() {
        assertEquals(68, DeviceAccelerationAnalyzer.expectedQnnHtpArchVersionForChipsetCode("SM8350"))
        assertEquals(69, DeviceAccelerationAnalyzer.expectedQnnHtpArchVersionForChipsetCode("SM8475"))
        assertEquals(73, DeviceAccelerationAnalyzer.expectedQnnHtpArchVersionForChipsetCode("SM8550"))
        assertEquals(75, DeviceAccelerationAnalyzer.expectedQnnHtpArchVersionForChipsetCode("SM8650"))
        assertEquals(79, DeviceAccelerationAnalyzer.expectedQnnHtpArchVersionForChipsetCode("SM8750P"))
        assertEquals(81, DeviceAccelerationAnalyzer.expectedQnnHtpArchVersionForChipsetCode("SM8850"))
        assertEquals(73, QnnRuntimeProfileSelector.htpArchVersionForSocModel(43))
        assertEquals(81, QnnRuntimeProfileSelector.htpArchVersionForSocModel(87))
    }

    private fun snapdragon(manufacturer: String, model: String): SocInfo =
        SocInfo(manufacturer = manufacturer, model = model, family = SocFamily.Snapdragon)

    private fun readyRuntime(): QnnRuntimeStatus =
        QnnRuntimeStatus(
            qnnSystemLibraryPresent = true,
            qnnHtpLibraryPresent = true,
            htpSkelLibraryPresent = true,
            htpStubLibraryPresent = true,
            searchDirectories = listOf("/data/local/tmp/qnn"),
            probeState = QnnRuntimeProbeState.LOADABLE,
            probeMessage = "mock load ok"
        )

    private fun unverifiedRuntime(): QnnRuntimeStatus =
        QnnRuntimeStatus(
            qnnSystemLibraryPresent = true,
            qnnHtpLibraryPresent = true,
            htpSkelLibraryPresent = true,
            searchDirectories = listOf("/data/local/tmp/qnn")
        )

    private fun failedRuntime(message: String): QnnRuntimeStatus =
        QnnRuntimeStatus(
            qnnSystemLibraryPresent = true,
            qnnHtpLibraryPresent = true,
            htpSkelLibraryPresent = true,
            searchDirectories = listOf("/data/local/tmp/qnn"),
            probeState = QnnRuntimeProbeState.LOAD_FAILED,
            probeMessage = message
        )

    private val Int.gb: Long
        get() = this * 1024L * 1024L * 1024L
}
