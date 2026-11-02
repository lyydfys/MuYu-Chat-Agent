package com.muyuchat.mca.agent.data

/** Logical workspace layout and the immutable physical artifact-version naming rule. */
object AgentArtifactLayout {
    const val INPUTS: String = "inputs"
    const val ARTIFACTS: String = "artifacts"
    const val PAYLOADS: String = "payloads"
    const val STAGING: String = "staging"

    /** Hidden physical-version directory inside `artifacts/`; never exposed as a logical path. */
    const val VERSIONS: String = ".versions"

    fun physicalStorageRef(artifactId: String, commitId: String): String =
        "$ARTIFACTS/$VERSIONS/${artifactId}_$commitId.blob"

    fun isInternalArtifactsPath(path: String): Boolean =
        path == "$ARTIFACTS/$VERSIONS" || path.startsWith("$ARTIFACTS/$VERSIONS/")
}
