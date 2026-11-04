package com.muyuchat.mca.agent.device

import android.content.Context
import com.muyuchat.mca.agent.device.sensors.AgentSensorCatalog
import com.muyuchat.mca.agent.device.sensors.AgentSensorId
import com.muyuchat.mca.agent.device.sensors.AgentStepBaseline

/**
 * The one consent store of the device-access lane (sensors/location/torch + read-only SMS).
 *
 * Rules:
 *  - every switch defaults to `false`; there is no "everything on" shortcut and no pre-granted
 *    capability, so a fresh install exposes nothing to DSH;
 *  - the settings card writes these values directly (the feature module has no dependency on the
 *    app module), which is why the preference name and keys are public constants pinned by
 *    `AgentDeviceAccessSettingsKeyContractTest`;
 *  - SMS results are never stored here: the only SMS keys are the two consent switches.
 */
class AgentDeviceAccessStore(context: Context) : AgentDeviceConsentGate {

    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    init {
        // "本次运行内不再询问" is process-scoped: the DSH lane is created once per app process, so
        // clearing the persisted flag here makes a restart re-arm the per-query confirmation even
        // when the switch was left on. Reading it back never re-enables it.
        if (preferences.getBoolean(SMS_SKIP_CONFIRMATION_KEY, false)) {
            preferences.edit().putBoolean(SMS_SKIP_CONFIRMATION_KEY, false).apply()
        }
    }

    override fun sensorEnabled(id: AgentSensorId): Boolean =
        preferences.getBoolean(sensorKey(id), false)

    fun setSensorEnabled(id: AgentSensorId, enabled: Boolean) {
        preferences.edit().putBoolean(sensorKey(id), enabled).apply()
    }

    override fun smsReadEnabled(): Boolean =
        preferences.getBoolean(SMS_READ_ENABLED_KEY, false)

    fun setSmsReadEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(SMS_READ_ENABLED_KEY, enabled).apply()
    }

    override fun smsSkipConfirmationThisSession(): Boolean =
        preferences.getBoolean(SMS_SKIP_CONFIRMATION_KEY, false)

    fun setSmsSkipConfirmationThisSession(enabled: Boolean) {
        preferences.edit().putBoolean(SMS_SKIP_CONFIRMATION_KEY, enabled).apply()
    }

    /**
     * Step-counter baseline (one epoch-day + boot counter). It is the only state this lane keeps
     * besides the switches: Android's step counter is "since boot", so today's number needs it.
     */
    fun stepBaseline(): AgentStepBaseline? {
        val day = preferences.getLong(STEP_BASELINE_DAY_KEY, Long.MIN_VALUE)
        val counter = preferences.getInt(STEP_BASELINE_COUNTER_KEY, Int.MIN_VALUE)
        if (day == Long.MIN_VALUE || counter == Int.MIN_VALUE) return null
        return AgentStepBaseline(epochDay = day, counter = counter)
    }

    fun setStepBaseline(baseline: AgentStepBaseline) {
        preferences.edit()
            .putLong(STEP_BASELINE_DAY_KEY, baseline.epochDay)
            .putInt(STEP_BASELINE_COUNTER_KEY, baseline.counter)
            .apply()
    }

    companion object {
        const val PREFERENCES_NAME: String = "mca_agent_device_access"

        /** Read-only SMS switch; the permission itself is requested from the settings card. */
        const val SMS_READ_ENABLED_KEY: String = "sms.read_enabled"

        /** "Do not ask again until the app restarts" switch for the per-query SMS confirmation. */
        const val SMS_SKIP_CONFIRMATION_KEY: String = "sms.skip_confirmation_session"

        /** Today's step baseline: epoch day + the platform's since-boot counter at that moment. */
        const val STEP_BASELINE_DAY_KEY: String = "sensor.step_baseline_day"
        const val STEP_BASELINE_COUNTER_KEY: String = "sensor.step_baseline_counter"

        fun sensorKey(id: AgentSensorId): String = "sensor." + AgentSensorCatalog.wireName(id)
    }
}
