package com.hee.charge

import android.content.Context

class Settings(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var backgroundEnabled: Boolean
        get() = p.getBoolean("background", false)
        set(v) = p.edit().putBoolean("background", v).apply()

    /** 화면 켜고 쓸 때의 소비 전력 (방전 중에 잰 값) */
    var usageW: Double?
        get() = getDouble("usageW")
        set(v) = putDouble("usageW", v)

    /**
     * 잔량 구간(10% 단위)별로 "좋은 조건"(화면 꺼짐, 시원함)에서 받아본 최고 속도 (30초 평균, W).
     * 0이면 아직 기록 없음. 유선/무선 따로.
     */
    fun expectedTable(wireless: Boolean): DoubleArray {
        val parts = p.getString(tableKey(wireless), null)?.split(",") ?: emptyList()
        return DoubleArray(10) { parts.getOrNull(it)?.toDoubleOrNull() ?: 0.0 }
    }

    fun saveExpectedTable(wireless: Boolean, table: DoubleArray) {
        p.edit().putString(tableKey(wireless), table.joinToString(",")).apply()
    }

    fun resetBest() {
        p.edit().remove(tableKey(true)).remove(tableKey(false)).apply()
    }

    private fun tableKey(wireless: Boolean) = if (wireless) "expectedWireless" else "expectedWired"

    private fun getDouble(key: String): Double? = p.getFloat(key, -1f).takeIf { it > 0f }?.toDouble()

    private fun putDouble(key: String, v: Double?) {
        p.edit().putFloat(key, (v ?: -1.0).toFloat()).apply()
    }
}
