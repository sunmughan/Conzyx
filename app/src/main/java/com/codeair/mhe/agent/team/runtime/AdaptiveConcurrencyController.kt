package com.codeair.mhe.agent.team.runtime

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How often device pressure is re-evaluated while a team run is active. */
private const val MONITOR_INTERVAL_MS = 30_000L

private const val GB = 1024L * 1024L * 1024L

/**
 * Smart resource-aware scheduler for parallel agent execution.
 *
 * Keeps the whole app smooth whether 1 or 3 agents run, on old phones and new
 * phones alike. The parallel agent count auto-adapts to device load:
 *
 * Baseline (computed once at startup from hardware):
 * - 1 agent when total RAM is 3 GB or less, or CPU cores are 4 or fewer
 * - 2 agents when total RAM is 6 GB or less
 * - 3 agents above that (never more than 3)
 *
 * Dynamic throttling (re-evaluated before every dispatch stage and every 30s
 * during a run; scales back up when pressure clears, never above baseline):
 * - thermal state MODERATE or worse: drop to 1 agent
 * - available RAM below 15% of total: drop to 1; below 25%: drop to 2
 * - battery below 15% and not charging: drop to 1 agent
 *
 * In-flight agent runs are never killed by throttling; the new limit applies
 * to newly dispatched stages.
 */
class AdaptiveConcurrencyController(context: Context) {

    data class ThrottleState(
        /** Agents allowed to run concurrently right now. */
        val effectiveMaxAgents: Int,
        /** Hardware baseline; the effective count never exceeds this. */
        val baselineMaxAgents: Int,
        /** Human-readable reason shown in the UI, null when not throttled. */
        val throttleReason: String?,
    )

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Hardware baseline, computed once at startup. */
    val baselineMaxAgents: Int = computeBaseline()

    private val _state = MutableStateFlow(
        ThrottleState(
            effectiveMaxAgents = baselineMaxAgents,
            baselineMaxAgents = baselineMaxAgents,
            throttleReason = null,
        )
    )

    /** Observable throttle state for the UI header ("2 agents running (thermal throttle)"). */
    val state: StateFlow<ThrottleState> = _state.asStateFlow()

    /** Current allowed parallelism (thread-safe read). */
    fun currentMaxAgents(): Int = _state.value.effectiveMaxAgents

    init {
        scope.launch { evaluateNow() }
    }

    private fun computeBaseline(): Int {
        val totalMemBytes = readTotalMemBytes()
        val cores = Runtime.getRuntime().availableProcessors()
        return when {
            totalMemBytes <= 3 * GB || cores <= 4 -> 1
            totalMemBytes <= 6 * GB -> 2
            else -> 3
        }.coerceIn(1, 3)
    }

    /**
     * Re-reads thermal, memory and battery pressure right now and updates the
     * effective agent count. Safe to call from any thread.
     */
    suspend fun evaluateNow() = withContext(Dispatchers.Default) {
        val baseline = baselineMaxAgents
        var effective = baseline
        var reason: String? = null

        // Thermal pressure wins: a hot phone must cool down first.
        if (readThermalStatus() >= PowerManager.THERMAL_STATUS_MODERATE) {
            effective = 1
            reason = "thermal throttle"
        }

        // Memory pressure.
        val availRatio = readAvailableMemRatio()
        val memLimit = when {
            availRatio < 0.15 -> 1
            availRatio < 0.25 -> 2
            else -> baseline
        }
        if (memLimit < effective) {
            effective = memLimit
            reason = "low memory"
        }

        // Battery: only throttles when low AND not charging.
        val battery = readBattery()
        if (battery != null && battery.level in 0..14 && !battery.charging && effective > 1) {
            effective = 1
            reason = "low battery"
        }

        _state.value = ThrottleState(
            effectiveMaxAgents = effective.coerceIn(1, 3),
            baselineMaxAgents = baseline,
            throttleReason = reason,
        )
    }

    private var monitorJob: Job? = null

    /** Starts the 30s pressure monitor; call when a team run begins. Idempotent. */
    fun startMonitoring() {
        if (monitorJob?.isActive == true) return
        monitorJob = scope.launch {
            while (isActive) {
                delay(MONITOR_INTERVAL_MS)
                evaluateNow()
            }
        }
    }

    /** Stops the pressure monitor; call when a team run ends. Idempotent. */
    fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
    }

    private fun readTotalMemBytes(): Long {
        val activityManager =
            appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return 0L
        val info = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)
        return info.totalMem
    }

    private fun readAvailableMemRatio(): Double {
        val activityManager =
            appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return 1.0
        val info = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)
        if (info.totalMem <= 0L) return 1.0
        return info.availMem.toDouble() / info.totalMem.toDouble()
    }

    private fun readThermalStatus(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return PowerManager.THERMAL_STATUS_NONE
        val powerManager =
            appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
                ?: return PowerManager.THERMAL_STATUS_NONE
        return powerManager.currentThermalStatus
    }

    private data class BatteryState(val level: Int, val charging: Boolean)

    private fun readBattery(): BatteryState? {
        val batteryManager =
            appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                ?: return null
        val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (level < 0) return null // unknown
        val charging = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            batteryManager.isCharging
        } else {
            false
        }
        return BatteryState(level, charging)
    }
}
