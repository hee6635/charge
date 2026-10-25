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

    /** 이 휴대폰이 받아본 최고 충전 속도 (30초 평균, 유선/무선 따로) */
    fun bestW(wireless: Boolean): Double? = getDouble(if (wireless) "bestWireless" else "bestWired")

    fun setBestW(wireless: Boolean, w: Double) = putDouble(if (wireless) "bestWireless" else "bestWired", w)

    fun resetBest() {
        p.edit().remove("bestWireless").remove("bestWired").apply()
    }

    private fun getDouble(key: String): Double? = p.getFloat(key, -1f).takeIf { it > 0f }?.toDouble()

    private fun putDouble(key: String, v: Double?) {
        p.edit().putFloat(key, (v ?: -1.0).toFloat()).apply()
    }
}
