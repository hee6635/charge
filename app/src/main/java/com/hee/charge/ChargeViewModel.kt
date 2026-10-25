package com.hee.charge

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

data class Sample(val timeMs: Long, val powerW: Double, val currentA: Double, val tempC: Double)

data class UiState(
    val snapshot: BatterySnapshot? = null,
    val samples: List<Sample> = emptyList(),
    val live: ChargeSession? = null,
    val history: List<ChargeSession> = emptyList(),
    val avgCurrentA: Double = 0.0,
    val estimateMinutes: Double? = null,
    val capacityMah: Double? = null,
)

class ChargeViewModel(app: Application) : AndroidViewModel(app) {
    private val reader = BatteryReader(app)
    private val store = SessionStore(app)

    private val _state = MutableStateFlow(UiState(history = store.load()))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val samples = ArrayDeque<Sample>()
    private var live: LiveSession? = null
    private var pollJob: Job? = null

    /** 화면이 보일 때만 1초마다 읽는다. */
    fun setActive(active: Boolean) {
        if (active && pollJob == null) {
            pollJob = viewModelScope.launch {
                while (isActive) {
                    tick()
                    delay(1000)
                }
            }
        } else if (!active) {
            pollJob?.cancel()
            pollJob = null
        }
    }

    fun clearHistory() {
        store.clear()
        _state.value = _state.value.copy(history = emptyList())
    }

    private fun tick() {
        val s = reader.read() ?: return

        samples.addLast(Sample(s.timeMs, s.powerW, s.currentA, s.temperatureC))
        while (samples.size > MAX_SAMPLES) samples.removeFirst()

        var history = _state.value.history
        if (s.chargeType.isCharging) {
            val cur = live
            if (cur == null || cur.type != s.chargeType) {
                cur?.let { history = finish(it) ?: history }
                live = LiveSession(s.chargeType, s.timeMs, s.levelPercent, s.timeMs)
            }
            live?.add(s)
        } else {
            live?.let { history = finish(it) ?: history }
            live = null
        }

        val recent = samples.toList().takeLast(30)
        val avgA = if (recent.isEmpty()) 0.0 else recent.sumOf { it.currentA } / recent.size

        val capacity = s.chargeCounterMah
            ?.takeIf { s.levelPercent in 5..100 }
            ?.let { it * 100.0 / s.levelPercent }
            ?.takeIf { it in 500.0..30_000.0 }

        val estimate = if (s.chargeType.isCharging && !s.isFull && avgA > 0.05 && capacity != null) {
            val remainingMah = capacity * (100 - s.levelPercent) / 100.0
            remainingMah / 1000.0 / avgA * 60.0
        } else null

        _state.value = UiState(
            snapshot = s,
            samples = samples.toList(),
            live = live?.toSession(),
            history = history,
            avgCurrentA = avgA,
            estimateMinutes = estimate,
            capacityMah = capacity,
        )
    }

    /** 1분 넘게 충전한 경우만 기록에 남긴다. */
    private fun finish(session: LiveSession): List<ChargeSession>? {
        val result = session.toSession()
        return if (result.durationMin >= 1.0) store.add(result) else null
    }

    override fun onCleared() {
        live?.let { finish(it) }
        live = null
        super.onCleared()
    }

    private class LiveSession(
        val type: ChargeType,
        val startMs: Long,
        val startLevel: Int,
        var lastMs: Long,
    ) {
        var lastLevel = startLevel
        var count = 0
        var sumW = 0.0
        var maxW = 0.0
        var sumTemp = 0.0
        var maxTemp = -100.0
        var energyWh = 0.0

        fun add(s: BatterySnapshot) {
            // 앱이 꺼져 있던 공백은 에너지 계산에서 빼려고 최대 5초로 자른다.
            val dt = ((s.timeMs - lastMs) / 1000.0).coerceIn(0.0, 5.0)
            val w = max(s.powerW, 0.0)
            energyWh += w * dt / 3600.0
            count++
            sumW += w
            maxW = max(maxW, w)
            sumTemp += s.temperatureC
            maxTemp = max(maxTemp, s.temperatureC)
            lastMs = s.timeMs
            lastLevel = s.levelPercent
        }

        fun toSession() = ChargeSession(
            type = type,
            startMs = startMs,
            endMs = lastMs,
            startLevel = startLevel,
            endLevel = lastLevel,
            avgW = if (count > 0) sumW / count else 0.0,
            maxW = maxW,
            avgTempC = if (count > 0) sumTemp / count else 0.0,
            maxTempC = if (count > 0) maxTemp else 0.0,
            energyWh = energyWh,
        )
    }

    companion object {
        const val MAX_SAMPLES = 300 // 5분
    }
}
