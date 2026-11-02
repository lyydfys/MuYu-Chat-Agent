package com.muyuchat.core.dsh.android

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import com.muyuchat.core.dsh.contract.capability.DshCapabilityState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * `GET /usage` adapter over `UsageStatsManager`.
 *
 * The app-op check is the authoritative grant: `PACKAGE_USAGE_STATS` is a signature/appop
 * permission, so the manifest must declare it (with an explicit lint suppression) and the user must
 * switch "usage access" on. Both states are reported separately instead of collapsing into an
 * empty list, because "no data" and "no permission" mean different things to the model.
 */
class AndroidDshUsageStatsPort(
    context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis
) : DshUsageStatsPort {

    private val appContext: Context = context.applicationContext

    private val usageStatsManager: UsageStatsManager? =
        appContext.getSystemService(UsageStatsManager::class.java)

    private val appOpsManager: AppOpsManager? =
        appContext.getSystemService(AppOpsManager::class.java)

    @Suppress("DEPRECATION")
    override fun access(): DshCapabilityState {
        if (!AndroidDshPermissionChecks.declares(appContext, PERMISSION_USAGE_STATS)) {
            return DshCapabilityState.NOT_DECLARED
        }
        if (usageStatsManager == null) return DshCapabilityState.UNSUPPORTED
        val appOps = appOpsManager ?: return DshCapabilityState.UNSUPPORTED
        val mode = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    appContext.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    appContext.packageName
                )
            }
        } catch (_: RuntimeException) {
            return DshCapabilityState.UNSUPPORTED
        }
        return when (mode) {
            AppOpsManager.MODE_ALLOWED -> DshCapabilityState.GRANTED
            AppOpsManager.MODE_DEFAULT, AppOpsManager.MODE_IGNORED, AppOpsManager.MODE_ERRORED ->
                DshCapabilityState.DENIED
            else -> DshCapabilityState.DENIED
        }
    }

    override suspend fun query(days: Int): DshUsageQueryOutcome = withContext(dispatcher) {
        val manager = usageStatsManager
            ?: return@withContext DshUsageQueryOutcome.Unavailable(
                DshAndroidActionFailure.of(
                    DshAndroidActionErrorCode.CAPABILITY_UNAVAILABLE,
                    "当前系统不支持 UsageStatsManager"
                )
            )
        val endMillis = clock()
        val beginMillis = endMillis - days.toLong() * MILLIS_PER_DAY
        val stats = try {
            manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, beginMillis, endMillis)
                .orEmpty()
        } catch (_: SecurityException) {
            return@withContext DshUsageQueryOutcome.Unavailable(
                DshAndroidActionFailure.of(
                    DshAndroidActionErrorCode.PERMISSION_DENIED,
                    "未授予使用情况访问权限"
                )
            )
        } catch (_: RuntimeException) {
            return@withContext DshUsageQueryOutcome.Unavailable(
                DshAndroidActionFailure.of(
                    DshAndroidActionErrorCode.INTERNAL,
                    "读取使用情况数据失败"
                )
            )
        }

        val totals = LinkedHashMap<String, Long>()
        stats.forEach { stat ->
            val packageName = stat.packageName ?: return@forEach
            val foreground = stat.totalTimeInForeground
            if (foreground > 0L) {
                totals[packageName] = (totals[packageName] ?: 0L) + foreground
            }
        }
        DshUsageQueryOutcome.Loaded(
            totals.map { (packageName, foregroundMillis) ->
                DshUsageApp(
                    packageName = packageName,
                    label = displayLabel(packageName),
                    foregroundMillis = foregroundMillis
                )
            }
        )
    }

    private fun displayLabel(packageName: String): String = try {
        @Suppress("DEPRECATION")
        val info = appContext.packageManager.getApplicationInfo(packageName, 0)
        appContext.packageManager.getApplicationLabel(info).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        packageName
    } catch (_: RuntimeException) {
        packageName
    }

    companion object {
        private const val PERMISSION_USAGE_STATS = "android.permission.PACKAGE_USAGE_STATS"
        private const val MILLIS_PER_DAY = 86_400_000L
    }
}
