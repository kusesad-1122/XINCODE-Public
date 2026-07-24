package com.xincode.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import com.xincode.core.PowerMode
import kotlinx.coroutines.flow.*

/**
 * Monitors device battery state and emits [PowerMode] updates.
 *
 * Uses sticky broadcast for initial state, then registers receivers for
 * ACTION_BATTERY_CHANGED, ACTION_POWER_CONNECTED, ACTION_POWER_DISCONNECTED.
 */
class BatteryMonitor(private val context: Context) {

    companion object {
        private const val TAG = "BatteryMonitor"
        /** Battery percentage threshold for power save mode. */
        private const val LOW_BATTERY_PCT = 20
        /** Battery percentage above which HIGH_PERF mode activates when charging. */
        private const val HIGH_BATTERY_PCT = 80
    }

    data class BatteryState(
        val level: Int,          // 0–100
        val temperature: Float,  // Celsius
        val isCharging: Boolean,
        val powerMode: PowerMode
    )

    private val _batteryState = MutableStateFlow(initialBatteryState())
    val batteryState: StateFlow<BatteryState> = _batteryState.asStateFlow()

    private val powerModeState = MutableStateFlow(_batteryState.value.powerMode)
    val powerMode: StateFlow<PowerMode> = powerModeState.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    val state = parseBatteryIntent(intent)
                    _batteryState.value = state
                    powerModeState.value = state.powerMode
                }
                Intent.ACTION_POWER_CONNECTED -> {
                    val cur = _batteryState.value
                    val mode = computeMode(cur.level, true)
                    _batteryState.value = cur.copy(isCharging = true, powerMode = mode)
                    powerModeState.value = mode
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    val cur = _batteryState.value
                    val mode = computeMode(cur.level, false)
                    _batteryState.value = cur.copy(isCharging = false, powerMode = mode)
                    powerModeState.value = mode
                }
            }
        }
    }

    private var registered = false

    fun start() {
        if (registered) return
        registered = true
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        context.registerReceiver(receiver, filter)
        Log.i(TAG, "BatteryMonitor started: ${_batteryState.value}")
    }

    fun stop() {
        if (!registered) return
        registered = false
        context.unregisterReceiver(receiver)
    }

    /** Read initial battery state from sticky intent (no manifest receiver needed). */
    private fun initialBatteryState(): BatteryState {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return parseBatteryIntent(intent ?: Intent())
    }

    private fun parseBatteryIntent(intent: Intent): BatteryState {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 50)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val pct = level * 100 / scale
        val tempC = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 250) / 10.0f
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val isCharging = plugged != 0
        return BatteryState(
            level = pct,
            temperature = tempC,
            isCharging = isCharging,
            powerMode = computeMode(pct, isCharging)
        )
    }

    private fun computeMode(levelPct: Int, isCharging: Boolean): PowerMode {
        if (isCharging && levelPct > HIGH_BATTERY_PCT) return PowerMode.HIGH_PERF
        if (isCharging) return PowerMode.NORMAL
        if (levelPct < LOW_BATTERY_PCT) return PowerMode.POWER_SAVE
        if (levelPct > HIGH_BATTERY_PCT) return PowerMode.HIGH_PERF
        return PowerMode.NORMAL
    }
}