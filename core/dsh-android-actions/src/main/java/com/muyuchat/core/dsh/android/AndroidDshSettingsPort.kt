package com.muyuchat.core.dsh.android

import android.Manifest
import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import com.muyuchat.core.dsh.contract.capability.DshCapabilityState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * `POST /setting` adapter for the `system` namespace.
 *
 * Two write paths, matching the reference tool's key list:
 *  - the four volume keys go to `AudioManager.setStreamVolume` for the mapped stream and report
 *    `stream`/`level`/`max` (the official tool reads those fields);
 *  - every other key goes to `Settings.System.putString`, which needs `WRITE_SETTINGS`, and the
 *    value is read back so `oldValue`/`newValue` describe the real state after the write.
 */
class AndroidDshSettingsPort(
    context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : DshAppSettingsPort {

    private val appContext: Context = context.applicationContext

    private val audioManager: AudioManager? =
        appContext.getSystemService(AudioManager::class.java)

    override fun writeAccess(key: String): DshCapabilityState {
        if (key in VOLUME_STREAMS) {
            return if (audioManager == null) DshCapabilityState.UNSUPPORTED else DshCapabilityState.GRANTED
        }
        if (!AndroidDshPermissionChecks.declares(appContext, Manifest.permission.WRITE_SETTINGS)) {
            return DshCapabilityState.NOT_DECLARED
        }
        return if (Settings.System.canWrite(appContext)) {
            DshCapabilityState.GRANTED
        } else {
            DshCapabilityState.DENIED
        }
    }

    override suspend fun read(key: String): String? = withContext(dispatcher) {
        try {
            Settings.System.getString(appContext.contentResolver, key)
        } catch (_: RuntimeException) {
            null
        }
    }

    override suspend fun write(
        key: String,
        value: String
    ): DshSettingWriteResult = withContext(dispatcher) {
        val stream = VOLUME_STREAMS[key]
        if (stream != null) writeStreamValue(key, stream, value) else writeSystemValue(key, value)
    }

    private fun writeStreamValue(
        key: String,
        stream: Int,
        value: String
    ): DshSettingWriteResult {
        val audio = audioManager
            ?: return DshSettingWriteResult.Rejected(
                DshAndroidActionFailure.of(
                    DshAndroidActionErrorCode.CAPABILITY_UNAVAILABLE,
                    "当前系统没有音频服务"
                )
            )
        val maxLevel = try {
            audio.getStreamMaxVolume(stream)
        } catch (_: RuntimeException) {
            return DshSettingWriteResult.Rejected(
                DshAndroidActionFailure.of(
                    DshAndroidActionErrorCode.INTERNAL,
                    "读取音量上限失败"
                )
            )
        }
        val level = value.trim().toIntOrNull()
            ?: return DshSettingWriteResult.Rejected(
                DshAndroidActionFailure.of(
                    DshAndroidActionErrorCode.INVALID_REQUEST,
                    "音量档位必须是整数"
                )
            )
        if (level !in 0..maxLevel) {
            return DshSettingWriteResult.Rejected(
                DshAndroidActionFailure.of(
                    DshAndroidActionErrorCode.INVALID_REQUEST,
                    "音量档位必须在 0..$maxLevel 之间"
                )
            )
        }
        return try {
            val oldLevel = audio.getStreamVolume(stream)
            audio.setStreamVolume(stream, level, 0)
            val newLevel = audio.getStreamVolume(stream)
            DshSettingWriteResult.Written(
                DshSettingWriteOutcome(
                    oldValue = oldLevel.toString(),
                    newValue = newLevel.toString(),
                    stream = key,
                    level = newLevel,
                    maxLevel = maxLevel
                )
            )
        } catch (_: SecurityException) {
            DshSettingWriteResult.Rejected(
                DshAndroidActionFailure.of(
                    DshAndroidActionErrorCode.PERMISSION_DENIED,
                    "系统拒绝了音量写入"
                )
            )
        } catch (_: RuntimeException) {
            DshSettingWriteResult.Rejected(
                DshAndroidActionFailure.of(
                    DshAndroidActionErrorCode.INTERNAL,
                    "写入音量失败"
                )
            )
        }
    }

    private fun writeSystemValue(key: String, value: String): DshSettingWriteResult {
        val resolver = appContext.contentResolver
        val oldValue = try {
            Settings.System.getString(resolver, key)
        } catch (_: RuntimeException) {
            null
        }
        val written = try {
            Settings.System.putString(resolver, key, value)
        } catch (_: SecurityException) {
            return DshSettingWriteResult.Rejected(
                DshAndroidActionFailure.of(
                    DshAndroidActionErrorCode.PERMISSION_DENIED,
                    "未授予「修改系统设置」权限（WRITE_SETTINGS）"
                )
            )
        } catch (_: RuntimeException) {
            return DshSettingWriteResult.Rejected(
                DshAndroidActionFailure.of(
                    DshAndroidActionErrorCode.INTERNAL,
                    "写入系统设置失败"
                )
            )
        }
        if (!written) {
            return DshSettingWriteResult.Rejected(
                DshAndroidActionFailure.of(
                    DshAndroidActionErrorCode.INTERNAL,
                    "系统拒绝了设置写入"
                )
            )
        }
        val newValue = try {
            Settings.System.getString(resolver, key)
        } catch (_: RuntimeException) {
            null
        }
        return DshSettingWriteResult.Written(
            DshSettingWriteOutcome(oldValue = oldValue, newValue = newValue)
        )
    }

    companion object {
        /** `Settings.System` keys the reference maps onto audio streams. */
        private val VOLUME_STREAMS: Map<String, Int> = mapOf(
            "volume_music" to AudioManager.STREAM_MUSIC,
            "volume_ring" to AudioManager.STREAM_RING,
            "volume_alarm" to AudioManager.STREAM_ALARM,
            "volume_notification" to AudioManager.STREAM_NOTIFICATION
        )
    }
}
