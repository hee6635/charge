package com.hee.charge

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import kotlin.math.abs

enum class ChargeType(val label: String) {
    NONE("충전 안 함"),
    WIRED_AC("유선 (충전기)"),
    WIRED_USB("유선 (USB)"),
    WIRELESS("무선"),
    DOCK("유선 (독)");

    val isCharging: Boolean get() = this != NONE
    val isWireless: Boolean get() = this == WIRELESS
}

/** 한 순간의 배터리 상태. 전류는 충전 중이면 +, 방전 중이면 - */
data class BatterySnapshot(
    val timeMs: Long,
    val chargeType: ChargeType,
    val status: Int,
    val levelPercent: Int,
    val voltageV: Double,
    val currentA: Double,
    val temperatureC: Double,
    val health: Int,
    val technology: String?,
    val chargeCounterMah: Double?,
    val cycleCount: Int?,
    val systemRemainingMs: Long?,
    val thermalStatus: Int?,
) {
    val powerW: Double get() = voltageV * currentA
    val isFull: Boolean get() = status == BatteryManager.BATTERY_STATUS_FULL
}

class BatteryReader(context: Context) {
    private val app = context.applicationContext
    private val bm = app.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    private val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager

    fun read(): BatterySnapshot? {
        val intent = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return null

        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val type = when {
            plugged and BatteryManager.BATTERY_PLUGGED_WIRELESS != 0 -> ChargeType.WIRELESS
            plugged and BatteryManager.BATTERY_PLUGGED_AC != 0 -> ChargeType.WIRED_AC
            plugged and BatteryManager.BATTERY_PLUGGED_USB != 0 -> ChargeType.WIRED_USB
            plugged and PLUGGED_DOCK != 0 -> ChargeType.DOCK
            else -> ChargeType.NONE
        }
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)

        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else 0

        val voltageV = normalizeVoltage(intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0))
        val tempC = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0

        // 기기마다 단위(µA / mA)와 부호가 달라서 정규화한다.
        val rawCurrent = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val currentAbsA = if (rawCurrent == Int.MIN_VALUE) 0.0 else normalizeMicro(abs(rawCurrent))
        val charging = type.isCharging && status != BatteryManager.BATTERY_STATUS_DISCHARGING
        val currentA = if (charging) currentAbsA else -currentAbsA

        val rawCounter = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val counterMah = if (rawCounter <= 0 || rawCounter == Int.MIN_VALUE) null
        else normalizeMicro(rawCounter) * 1000.0

        val cycles = if (Build.VERSION.SDK_INT >= 34) {
            intent.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1).takeIf { it >= 0 }
        } else null

        val systemRemaining = if (Build.VERSION.SDK_INT >= 28) {
            bm.computeChargeTimeRemaining().takeIf { it > 0 }
        } else null

        val thermal = if (Build.VERSION.SDK_INT >= 29) pm.currentThermalStatus else null

        return BatterySnapshot(
            timeMs = System.currentTimeMillis(),
            chargeType = type,
            status = status,
            levelPercent = percent,
            voltageV = voltageV,
            currentA = currentA,
            temperatureC = tempC,
            health = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN),
            technology = intent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY),
            chargeCounterMah = counterMah,
            cycleCount = cycles,
            systemRemainingMs = systemRemaining,
            thermalStatus = thermal,
        )
    }

    companion object {
        private const val PLUGGED_DOCK = 8

        /** 보통 mV로 오지만 V나 µV로 주는 기기도 있다. */
        fun normalizeVoltage(raw: Int): Double = when {
            raw <= 0 -> 0.0
            raw < 100 -> raw.toDouble()            // V
            raw > 100_000 -> raw / 1_000_000.0      // µV
            else -> raw / 1000.0                    // mV
        }

        /** µA(µAh)가 표준이지만 mA(mAh)로 주는 기기가 있다. 결과는 A(Ah). */
        fun normalizeMicro(raw: Int): Double =
            if (raw in 1 until 20_000) raw / 1000.0 else raw / 1_000_000.0
    }
}
