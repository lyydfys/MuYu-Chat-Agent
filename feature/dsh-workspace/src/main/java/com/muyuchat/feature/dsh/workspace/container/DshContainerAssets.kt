package com.muyuchat.feature.dsh.workspace.container

import java.io.InputStream

/**
 * The packaged container runtime as the APK ships it.
 *
 * The assets are third-party binaries (see `docs/architecture/DSH_CONTAINER.md` §7). They are
 * staged outside Git by `scripts/dsh/stage-container.ps1`; a build without them is still valid and
 * this source reports that honestly by returning `null`, which the installer maps to
 * [DshContainerInstallResult.AssetsMissing] instead of installing an unverified tree.
 */
interface DshContainerAssetSource {
    /** Short description for diagnostics, e.g. `apk:offline/container`. */
    val description: String

    /** `container-manifest.json`; null when the build carries no container assets. */
    fun openManifest(): InputStream?

    /** The rootfs archive named by the manifest; null when the build carries no container assets. */
    fun openRootfsArchive(): InputStream?
}

/**
 * What the manifest declares about the packaged assets.
 *
 * Only the fields the installer actually enforces are modelled: the schema (so an old app never
 * misreads a new asset), the archive name/bytes/SHA-256 (integrity) and the provenance strings the
 * console shows so the user can see *which* Ubuntu and which proot version they are installing.
 */
data class DshContainerAssetManifest(
    val schema: String,
    val rootfsFile: String,
    val rootfsBytes: Long,
    val rootfsSha256: String,
    val distribution: String,
    val sourceUrl: String,
    val prootVersion: String,
    val prootLicense: String,
    val prootSha256: String,
    val rootfsLicense: String
) {
    /** True when the archive the manifest describes is the one this app opened. */
    fun matchesArchive(bytes: Long, sha256: String): Boolean =
        bytes == rootfsBytes && sha256.equals(this.rootfsSha256, ignoreCase = true)
}

/**
 * Parses `container-manifest.json`.
 *
 * Kept separate from the Android asset reader so the schema rules are provable on the JVM: a
 * malformed manifest must fail *before* anything is extracted, never half-way through 80 MB of
 * unpacking.
 */
object DshContainerManifest {
    /** The only schema this app understands; a bump means a new installer, not a silent upgrade. */
    const val SUPPORTED_SCHEMA: String = "mca.dsh.container.v1"

    /** Upper bound of one manifest read; the real document is well under 2 KiB. */
    const val MAX_MANIFEST_BYTES: Int = 16 * 1024

    private val SHA256 = Regex("^[0-9a-f]{64}$")

    fun parse(text: String): Result<DshContainerAssetManifest> = runCatching {
        val root = org.json.JSONObject(text)
        val schema = root.optString("schema", "")
        require(schema == SUPPORTED_SCHEMA) {
            "容器资产清单版本不受支持：$schema（本版只认 $SUPPORTED_SCHEMA）"
        }
        val rootfs = root.optJSONObject("rootfs") ?: error("容器资产清单缺少 rootfs 段")
        val proot = root.optJSONObject("proot") ?: error("容器资产清单缺少 proot 段")
        val archive = rootfs.optString("archive", "")
        require(archive.isNotBlank() && !archive.contains('/') && !archive.contains('\\')) {
            "容器资产清单的 archive 必须是裸文件名：$archive"
        }
        val sha = rootfs.optString("sha256", "").lowercase()
        require(SHA256.matches(sha)) { "容器资产清单的 rootfs.sha256 不是 64 位十六进制" }
        val bytes = rootfs.optLong("bytes", -1)
        require(bytes > 0) { "容器资产清单的 rootfs.bytes 无效：$bytes" }
        val prootSha = proot.optString("sha256", "").lowercase()
        require(SHA256.matches(prootSha)) { "容器资产清单的 proot.sha256 不是 64 位十六进制" }
        DshContainerAssetManifest(
            schema = schema,
            rootfsFile = archive,
            rootfsBytes = bytes,
            rootfsSha256 = sha,
            distribution = rootfs.optString("distribution", "未知发行版"),
            sourceUrl = rootfs.optString("sourceUrl", ""),
            prootVersion = proot.optString("version", "未知版本"),
            prootLicense = proot.optString("license", "未标注"),
            prootSha256 = prootSha,
            rootfsLicense = rootfs.optString("license", "未标注")
        )
    }
}
