package com.muyuchat.api.local


internal inline fun LocalApiRuntime.invokeControlPlane(
        action: (LocalApiControlPlane) -> LocalApiControlResult
    ): LocalApiControlResult {
        val plane = controlPlane
            ?: return LocalApiControlResult.Rejected(
                httpStatus = 503,
                code = "control_plane_unavailable",
                message = "The app runtime control plane is not attached."
            )
        return runCatching { action(plane) }.getOrElse {
            LocalApiControlResult.Rejected(
                httpStatus = 503,
                code = "control_plane_failed",
                message = "The app runtime control plane could not complete the request."
            )
        }
    }

internal fun LocalApiRuntime.normalizeTuningJobResult(
        requestedJobId: String?,
        result: LocalApiControlResult
    ): LocalApiControlResult {
        if (result !is LocalApiControlResult.Success) return result
        val root = result.json.toJsonObjectOrNull() ?: return result
        // Some legacy coordinators returned the complete global tuning state plus a nested `job`
        // for an explicit id. Expose only that persisted job to avoid mixing unrelated current
        // progress into historical/recovering job queries.
        val normalized = if (requestedJobId != null) {
            root.optJSONObject("job")?.publicCopy()?.also { job ->
                val rootJobId = root.firstString("jobId", "activeJobId")
                if (rootJobId == requestedJobId) {
                    val publicRoot = root.publicCopy()
                    TUNING_JOB_DETAIL_FIELDS.forEach { field ->
                        if (!job.has(field) && publicRoot.has(field)) {
                            job.put(field, publicRoot.opt(field))
                        }
                    }
                }
            } ?: root.publicCopy()
        } else {
            root.publicCopy()
        }
        if (!normalized.has("jobId") && requestedJobId != null) {
            normalized.put("jobId", requestedJobId)
        }
        if (requestedJobId == null && !normalized.has("activeJobId")) {
            normalized.firstValue("jobId")?.let { normalized.put("activeJobId", it) }
        }
        if (!normalized.has("tuningJobState")) {
            normalized.firstValue("state")?.let { normalized.put("tuningJobState", it) }
        }
        if (!normalized.has("state")) {
            normalized.firstValue("tuningJobState")?.let { normalized.put("state", it) }
        }
        return result.copy(json = normalized.toString())
    }
    private val TUNING_JOB_DETAIL_FIELDS = setOf(
        "phase",
        "progress",
        "etaSeconds",
        "autoApply",
        "candidateProfileId",
        "candidates",
        "hardGates",
        "diff",
        "signatures",
        "failure",
        "cancellationRequested",
        "createdAt",
        "updatedAt",
        "lastHeartbeatAt"
    )
