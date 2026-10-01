package com.muyuchat.core.download

import java.security.MessageDigest

/** Repository artifact identity. Display names and directory sanitization are not identities. */
fun RemoteModelFile.sourceIdentity(): String = artifactIdentity(
    provider.name, repoId, revision, path.replace('\\', '/'), relativePath.replace('\\', '/'),
    mnnBundleRole?.name.orEmpty(), bundleRole?.name.orEmpty(), visionBundleRole?.name.orEmpty(),
    sha256?.trim()?.lowercase().orEmpty()
)

fun modelBundleSourceIdentity(components: List<RemoteModelFile>, runtime: String): String =
    artifactIdentity(runtime, *components.map { it.sourceIdentity() }.sorted().toTypedArray())

private fun artifactIdentity(vararg values: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    values.forEach { value ->
        val bytes = value.toByteArray(Charsets.UTF_8)
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
        digest.update(bytes)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
