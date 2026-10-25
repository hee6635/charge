package com.hee.charge

import android.content.Context

class Settings(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** 사용자가 고른 충전기 최대 출력 (W) */
    var chargerW: Int
        get() = p.getInt("chargerW", 25)
        set(v) = p.edit().putInt("chargerW", v).apply()

    var backgroundEnabled: Boolean
        get() = p.getBoolean("background", false)
        set(v) = p.edit().putBoolean("background", v).apply()

    /** 화면 켜고 쓸 때의 소비 전력 (방전 중에 잰 값) */
    var usageW: Double?
        get() = p.getFloat("usageW", -1f).takeIf { it > 0f }?.toDouble()
        set(v) = p.edit().putFloat("usageW", (v ?: -1.0).toFloat()).apply()
}
