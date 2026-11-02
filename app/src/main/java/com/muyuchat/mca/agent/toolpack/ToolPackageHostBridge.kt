package com.muyuchat.mca.agent.toolpack

import com.muyuchat.mca.agent.tools.AgentToolJson
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/**
 * Enforces the host API whitelist, the package-declared capabilities, the resolved resource
 * surface, per-call write approval, replay identity, argument surface and output budgets for one
 * execution instance.
 *
 * Every call re-checks the code-owned registry and the package snapshot; nothing is cached across
 * calls except the *outcome* of an already executed stable ordinal. Isolation defaults are
 * fail-closed: an undeclared/unknown method never reaches the handler, a resource that the handler
 * cannot resolve is refused, a write needs its own approval bound to the exact call, a repeated
 * ordinal with different arguments is refused and a cancellation stops new calls. The bridge records
 * only bounded counters and canonical argument hashes; it never retains script source or
 * argument/result payloads beyond the per-ordinal replay entry.
 */
internal class ToolPackageHostBridge(
    private val bundle: ExecutableToolPackage,
    private val scope: ToolPackageExecutionScope,
    private val instanceId: String,
    private val handler: ToolPackageHostOperationHandler,
    private val approval: ToolPackageHostApprovalPort,
    private val control: ToolPackageExecutionControl,
    private val limits: ToolPackageRuntimeLimits
) : ToolPackageHostCaller {

    private val ledger = ToolPackageHostCallLedger(limits)

    /** True once any write handler was entered; a later failure then reports possible side effects. */
    var possibleSideEffects: Boolean = false
        private set

    override suspend fun call(method: String, argumentsJson: String, stableCallOrdinal: Int): String {
        control.ensureActive()
        if (stableCallOrdinal < 0 || stableCallOrdinal > ToolPackageLimits.MAX_STABLE_CALL_ORDINAL) {
            toolPackageFailure(
                ToolPackageErrorCode.HOST_CALL_ORDINAL_INVALID,
                "The host call ordinal is outside the bounded range."
            )
        }
        val api = ToolPackageHostApis.find(method)
            ?: toolPackageFailure(
                ToolPackageErrorCode.HOST_API_UNKNOWN,
                "The script requested a host API that is not registered."
            )
        if (api.method !in bundle.declaredHostApis) {
            toolPackageFailure(
                ToolPackageErrorCode.HOST_API_NOT_DECLARED,
                "The script requested a host API the package did not declare."
            )
        }
        val argumentBudget = minOf(api.maxArgumentsBytes, limits.maxHostArgumentsBytes)
        if (argumentsJson.toByteArray(Charsets.UTF_8).size > argumentBudget) {
            toolPackageFailure(
                ToolPackageErrorCode.HOST_CALL_ARGUMENTS_TOO_LARGE,
                "The host call arguments exceed the argument budget."
            )
        }
        val arguments = canonicalObject(argumentsJson)
            ?: toolPackageFailure(
                ToolPackageErrorCode.HOST_CALL_ARGUMENTS_INVALID,
                "The host call arguments must be a JSON object."
            )
        validateArguments(api, arguments)
        val resource = resolveResource(api, arguments)
        val argumentsHash = AgentToolJson.sha256Hex(arguments)

        val ordinal = when (val started = ledger.begin(api.method, stableCallOrdinal, argumentsHash)) {
            is ToolPackageHostCallLedger.Start.Replay ->
                return replay(started.outcome, limits.maxHostResultBytes)
            ToolPackageHostCallLedger.Start.OrdinalMismatch -> toolPackageFailure(
                ToolPackageErrorCode.HOST_CALL_REPLAY_MISMATCH,
                "The stable ordinal was already used for a different host call."
            )
            ToolPackageHostCallLedger.Start.OrdinalBusy -> toolPackageFailure(
                ToolPackageErrorCode.HOST_CALL_REPLAY_MISMATCH,
                "The stable ordinal is still executing another host call."
            )
            ToolPackageHostCallLedger.Start.LimitExceeded -> toolPackageFailure(
                ToolPackageErrorCode.HOST_CALL_LIMIT,
                "The script exceeded the host call budget."
            )
            is ToolPackageHostCallLedger.Start.Fresh -> started.ordinal
        }
        val binding = ToolPackageHostCallBinding(
            packageId = bundle.packageId,
            packageContentHash = bundle.contentHash,
            runId = scope.runId,
            parentInvocationId = scope.invocationId,
            instanceId = instanceId,
            stableCallOrdinal = ordinal,
            method = api.method,
            access = api.access,
            argumentsHash = argumentsHash,
            resource = resource,
            revocation = ToolPackageHostRevocation(scope.runEpoch, scope.policyVersion)
        )
        return dispatch(api, arguments, binding, ordinal)
    }

    private suspend fun dispatch(
        api: ToolPackageHostApi,
        arguments: String,
        binding: ToolPackageHostCallBinding,
        ordinal: Int
    ): String {
        try {
            if (api.access == ToolPackageHostAccess.WRITE) {
                authorize(binding)
            }
            // A cancellation between the approval and the handler must leave zero side effects.
            control.ensureActive()
            if (api.access == ToolPackageHostAccess.WRITE) possibleSideEffects = true
            val result = try {
                handler.handle(
                    ToolPackageHostOperationRequest(
                        api = api,
                        argumentsJson = arguments,
                        instanceId = instanceId,
                        packageId = bundle.packageId,
                        packageContentHash = bundle.contentHash,
                        scope = scope,
                        resource = binding.resource
                    )
                )
            } catch (failure: ToolPackageException) {
                throw failure
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (cancelled: ToolPackageCancelledException) {
                throw cancelled
            } catch (_: Exception) {
                toolPackageFailure(
                    ToolPackageErrorCode.HOST_CALL_FAILED,
                    "The host operation failed without a safe detail."
                )
            }
            val returned = when (result) {
                is ToolPackageHostOperationResult.Returned -> result
                is ToolPackageHostOperationResult.Denied -> throw ToolPackageException(result.error)
            }
            if (returned.json.toByteArray(Charsets.UTF_8).size > limits.maxHostResultBytes) {
                toolPackageFailure(
                    ToolPackageErrorCode.HOST_CALL_OUTPUT_LIMIT,
                    "The host call result exceeds the per-call output budget."
                )
            }
            val canonicalResult = canonicalObject(returned.json)
                ?: toolPackageFailure(
                    ToolPackageErrorCode.HOST_CALL_RESULT_INVALID,
                    "The host operation returned a non-object result."
                )
            val resultBytes = canonicalResult.toByteArray(Charsets.UTF_8).size.toLong()
            if (!ledger.accountResult(resultBytes)) {
                toolPackageFailure(
                    ToolPackageErrorCode.HOST_CALL_TOTAL_OUTPUT_LIMIT,
                    "The script exceeded the total host output budget."
                )
            }
            // Recorded before the post-dispatch cancellation check: a dispatched write is never
            // replayed by a retry, even when the execution stops right after the handler returns.
            ledger.finish(ordinal, ToolPackageHostCallLedger.Outcome.returned(api.method, binding.argumentsHash, canonicalResult, resultBytes))
            control.ensureActive()
            return canonicalResult
        } catch (failure: ToolPackageException) {
            // A decided refusal is the determined outcome of this ordinal: a retry must not repeat it.
            ledger.finish(ordinal, ToolPackageHostCallLedger.Outcome.refused(api.method, binding.argumentsHash, failure.error))
            throw failure
        } catch (cancelled: CancellationException) {
            ledger.abandon(ordinal)
            throw cancelled
        } catch (cancelled: ToolPackageCancelledException) {
            ledger.abandon(ordinal)
            throw cancelled
        }
    }

    private suspend fun authorize(binding: ToolPackageHostCallBinding) {
        val decision = try {
            approval.approve(binding)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (cancelled: ToolPackageCancelledException) {
            throw cancelled
        } catch (_: Exception) {
            toolPackageFailure(
                ToolPackageErrorCode.HOST_API_WRITE_NOT_APPROVED,
                "The approval source failed; the host write was not dispatched."
            )
        }
        when (decision) {
            is ToolPackageHostApprovalDecision.Approved -> {
                if (decision.bindingId != binding.bindingId) {
                    toolPackageFailure(
                        ToolPackageErrorCode.HOST_API_APPROVAL_REVOKED,
                        "The approval is bound to a different host call."
                    )
                }
                if (decision.revocation != binding.revocation) {
                    toolPackageFailure(
                        ToolPackageErrorCode.HOST_API_APPROVAL_REVOKED,
                        "The approval was issued for a revoked run epoch."
                    )
                }
                if (!ledger.consumeApproval(decision.approvalId)) {
                    toolPackageFailure(
                        ToolPackageErrorCode.HOST_API_APPROVAL_REVOKED,
                        "This approval was already used by another host call."
                    )
                }
            }
            is ToolPackageHostApprovalDecision.Refused -> when (decision.reason) {
                ToolPackageHostApprovalRefusal.NO_APPROVAL_SOURCE,
                ToolPackageHostApprovalRefusal.DENIED -> toolPackageFailure(
                    ToolPackageErrorCode.HOST_API_WRITE_NOT_APPROVED,
                    decision.safeMessage
                )
                ToolPackageHostApprovalRefusal.REVOKED,
                ToolPackageHostApprovalRefusal.BINDING_MISMATCH,
                ToolPackageHostApprovalRefusal.ALREADY_CONSUMED -> toolPackageFailure(
                    ToolPackageErrorCode.HOST_API_APPROVAL_REVOKED,
                    decision.safeMessage
                )
            }
        }
    }

    private suspend fun resolveResource(api: ToolPackageHostApi, canonicalArguments: String): ToolPackageHostResource {
        val resolution = try {
            handler.resolveResource(
                ToolPackageHostResourceRequest(
                    api = api,
                    argumentsJson = canonicalArguments,
                    instanceId = instanceId,
                    packageId = bundle.packageId,
                    packageContentHash = bundle.contentHash,
                    scope = scope
                )
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (cancelled: ToolPackageCancelledException) {
            throw cancelled
        } catch (_: Exception) {
            toolPackageFailure(
                ToolPackageErrorCode.HOST_CALL_RESOURCE_UNRESOLVED,
                "The host handler failed to resolve the requested resource."
            )
        }
        return when (resolution) {
            is ToolPackageHostResourceResolution.Resolved -> {
                val resource = resolution.resource
                if (resource.capability != api.method) {
                    toolPackageFailure(
                        ToolPackageErrorCode.HOST_CALL_RESOURCE_UNDECLARED,
                        "The resolved resource does not belong to this host capability."
                    )
                }
                if (resource.kind != api.resourceKind) {
                    toolPackageFailure(
                        ToolPackageErrorCode.HOST_CALL_RESOURCE_UNDECLARED,
                        "The resolved resource family is not the one this capability declares."
                    )
                }
                resource
            }
            is ToolPackageHostResourceResolution.Refused -> throw ToolPackageException(resolution.error)
        }
    }

    private fun replay(outcome: ToolPackageHostCallLedger.Outcome, limitBytes: Int): String {
        control.ensureActive()
        outcome.error?.let { throw ToolPackageException(it) }
        val json = requireNotNull(outcome.resultJson)
        if (json.toByteArray(Charsets.UTF_8).size > limitBytes) {
            toolPackageFailure(
                ToolPackageErrorCode.HOST_CALL_OUTPUT_LIMIT,
                "The host call result exceeds the per-call output budget."
            )
        }
        if (!ledger.accountResult(outcome.resultBytes)) {
            toolPackageFailure(
                ToolPackageErrorCode.HOST_CALL_TOTAL_OUTPUT_LIMIT,
                "The script exceeded the total host output budget."
            )
        }
        control.ensureActive()
        return json
    }

    private fun validateArguments(api: ToolPackageHostApi, canonicalJson: String) {
        val objectValue = JSONObject(canonicalJson)
        val names = objectValue.keys().asSequence().toSet()
        if (!names.containsAll(api.requiredArguments)) {
            toolPackageFailure(
                ToolPackageErrorCode.HOST_CALL_ARGUMENTS_INVALID,
                "The host call is missing a required argument for this operation."
            )
        }
        if (!api.allowedArguments.containsAll(names)) {
            toolPackageFailure(
                ToolPackageErrorCode.HOST_CALL_ARGUMENTS_INVALID,
                "The host call uses an argument this operation does not declare."
            )
        }
    }

    private fun canonicalObject(raw: String): String? {
        val parsed = try {
            JSONObject(raw)
        } catch (_: Exception) {
            return null
        }
        return AgentToolJson.canonical(parsed)
    }

}
