package com.hee.charge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 충전 한 번의 기록 */
data class ChargeSession(
    val type: ChargeType,
    val startMs: Long,
    val endMs: Long,
    val startLevel: Int,
    val endLevel: Int,
    val avgW: Double,
    val maxW: Double,
    val avgTempC: Double,
    val maxTempC: Double,
    val energyWh: Double,
) {
    val durationMin: Double get() = (endMs - startMs) / 60_000.0
    val gainedPercent: Int get() = endLevel - startLevel
    val percentPerMin: Double get() = if (durationMin > 0) gainedPercent / durationMin else 0.0
}

class SessionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("sessions", Context.MODE_PRIVATE)

    fun load(): List<ChargeSession> = runCatching {
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun add(session: ChargeSession): List<ChargeSession> {
        val list = (listOf(session) + load()).take(MAX)
        save(list)
        return list
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    private fun save(list: List<ChargeSession>) {
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private fun toJson(s: ChargeSession) = JSONObject().apply {
        put("type", s.type.name)
        put("startMs", s.startMs)
        put("endMs", s.endMs)
        put("startLevel", s.startLevel)
        put("endLevel", s.endLevel)
        put("avgW", s.avgW)
        put("maxW", s.maxW)
        put("avgTempC", s.avgTempC)
        put("maxTempC", s.maxTempC)
        put("energyWh", s.energyWh)
    }

    private fun fromJson(o: JSONObject) = ChargeSession(
        type = runCatching { ChargeType.valueOf(o.getString("type")) }.getOrDefault(ChargeType.WIRED_AC),
        startMs = o.getLong("startMs"),
        endMs = o.getLong("endMs"),
        startLevel = o.getInt("startLevel"),
        endLevel = o.getInt("endLevel"),
        avgW = o.getDouble("avgW"),
        maxW = o.getDouble("maxW"),
        avgTempC = o.getDouble("avgTempC"),
        maxTempC = o.getDouble("maxTempC"),
        energyWh = o.getDouble("energyWh"),
    )

    companion object {
        private const val KEY = "list"
        private const val MAX = 50
    }
}
