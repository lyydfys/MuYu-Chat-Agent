package com.muyuchat.core.dsh.android

import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteRequest
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteResponse
import com.muyuchat.core.dsh.contract.capability.DshCapabilityState
import org.json.JSONObject

/**
 * `GET /usage` — `dsh-tool-android`'s `android_usage`.
 *
 * Wire in: `?days=1..30` (absent means 1). Wire out:
 * `{"ok":true,"days":1,"count":3,"apps":[{"pkg":"..","name":"..","ms":1234,"min":2}]}` sorted by
 * foreground time descending. Exactly this key set: the tool's output schema is
 * `additionalProperties: false`, so an extra field would make DSH reject the result.
 *
 * Refusals keep the same shape as the other routes (`ok`/`error`) and carry the platform reason,
 * because an empty usage list and a missing usage-access grant must not look the same to the model.
 */
class DshUsageAction(
    private val port: DshUsageStatsPort
) : DshAndroidRoute() {

    override val path: String = ROUTE

    override suspend fun executeTyped(
        request: DshCapabilityRouteRequest
    ): DshCapabilityRouteResponse {
        DshAndroidEnvelope.requireGet(request, ROUTE, QUERY_KEYS)
        val days = DshAndroidEnvelope.queryInt(
            request,
            "days",
            DshAndroidActionLimits.MIN_USAGE_DAYS,
            DshAndroidActionLimits.MAX_USAGE_DAYS,
            DshAndroidActionLimits.MIN_USAGE_DAYS
        )

        val access = port.access()
        if (access != DshCapabilityState.GRANTED) {
            return refused(
                DshAndroidActionFailure.of(
                    DshAndroidActionErrorCode.refusalFor(access),
                    refusalReason(access)
                )
            )
        }

        return when (val outcome = port.query(days)) {
            is DshUsageQueryOutcome.Loaded -> DshAndroidWire.response(usageBody(days, outcome.apps))
            is DshUsageQueryOutcome.Unavailable -> refused(outcome.failure)
        }
    }

    private fun usageBody(days: Int, apps: List<DshUsageApp>): JSONObject {
        val normalized = apps.asSequence()
            .filter { isValidPackageName(it.packageName) }
            .map { app ->
                DshUsageApp(
                    packageName = app.packageName,
                    label = sanitizeLabel(app.label, app.packageName),
                    foregroundMillis = app.foregroundMillis.coerceAtLeast(0L)
                )
            }
            .sortedWith(
                compareByDescending<DshUsageApp> { it.foregroundMillis }
                    .thenBy { it.packageName }
            )
            .take(DshAndroidActionLimits.MAX_USAGE_APPS)
            .toList()

        val rows = normalized.map { app ->
            JSONObject()
                .put("pkg", app.packageName)
                .put("name", app.label)
                .put("ms", app.foregroundMillis)
                .put("min", app.foregroundMillis / MILLIS_PER_MINUTE)
        }
        return okBody()
            .put("days", days)
            .put("count", rows.size)
            .put("apps", DshAndroidWire.array(rows))
    }

    private fun sanitizeLabel(label: String, packageName: String): String {
        val cleaned = label
            .map { character -> if (isForbiddenFailureCharacter(character)) ' ' else character }
            .joinToString("")
            .trim()
            .ifEmpty { packageName }
        val capped = cleaned.take(DshAndroidActionLimits.MAX_USAGE_APP_NAME_CHARS)
        return capped
    }

    private fun isValidPackageName(packageName: String): Boolean =
        packageName.length in 1..DshAndroidActionLimits.MAX_PACKAGE_NAME_CHARS &&
            PACKAGE_NAME.matches(packageName)

    private fun refusalReason(state: DshCapabilityState): String = when (state) {
        DshCapabilityState.NOT_DECLARED ->
            "应用未声明使用情况访问权限（PACKAGE_USAGE_STATS）"
        DshCapabilityState.NOT_ENABLED ->
            "使用情况访问服务未启用"
        DshCapabilityState.UNSUPPORTED ->
            "当前系统版本不支持使用情况访问"
        else ->
            "未授予使用情况访问权限，请在系统设置中为本应用开启"
    }

    companion object {
        const val ROUTE = "/usage"

        private const val MILLIS_PER_MINUTE = 60_000L

        private val QUERY_KEYS: Set<String> = setOf("days")

        /** Package names are ASCII identifiers; anything else is not a real Android package. */
        private val PACKAGE_NAME = Regex("[A-Za-z0-9_][A-Za-z0-9_.]*")
    }
}
