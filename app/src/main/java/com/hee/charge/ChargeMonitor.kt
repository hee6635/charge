package com.hee.charge

import android.content.Context
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
    val diagnosis: Diagnosis? = null,
    /** 10초 평균 (순간값은 너무 출렁여서 화면에는 이걸 크게 보여준다) */
    val avgW10: Double = 0.0,
    val avgA10: Double = 0.0,
    val backgroundEnabled: Boolean = false,
)

/**
 * 앱 화면과 백그라운드 서비스가 함께 쓰는 측정기 (프로세스에 하나).
 * 누군가 acquire 한 동안만 측정한다.
 */
object ChargeMonitor {
    const val CLIENT_UI = "ui"
    const val CLIENT_SERVICE = "service"

    private const val WINDOW_MS = 5 * 60 * 1000L
    private const val DEFAULT_USAGE_W = 1.5
    private const val SCREEN_OFF_USAGE_W = 0.3

    private lateinit var reader: BatteryReader
    private lateinit var store: SessionStore
    private lateinit var settings: Settings
    private lateinit var pm: PowerManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val clients = mutableSetOf<String>()
    private var job: Job? = null
    private val samples = ArrayDeque<Sample>()
    private var live: LiveSession? = null
    private var usageW: Double? = null
    private var usageTicks = 0
    private var lastType: ChargeType? = null
    private var typeChangedMs = 0L

    // 원인 표시가 자주 바뀌지 않게: 새 원인이 15초 이상 이어질 때만 바꾼다
    private var shownReason: Reason? = null
    private var candidateKey: String? = null
    private var candidateSinceMs = 0L

    fun init(context: Context) {
        if (::reader.isInitialized) return
        val app = context.applicationContext
        reader = BatteryReader(app)
        store = SessionStore(app)
        settings = Settings(app)
        pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        usageW = settings.usageW
        _state.value = UiState(
            history = store.load(),
            backgroundEnabled = settings.backgroundEnabled,
        )
    }

    fun acquire(client: String) {
        clients += client
        if (job == null) {
            job = scope.launch {
                while (isActive) {
                    tick()
                    delay(intervalMs())
                }
            }
        }
    }

    fun release(client: String) {
        clients -= client
        if (clients.isEmpty()) {
            job?.cancel()
            job = null
            val cur = live
            live = null
            val history = cur?.let { finish(it) }
            _state.value = _state.value.copy(live = null, history = history ?: _state.value.history)
        }
    }

    fun resetBest() {
        settings.resetBest()
        _state.value.snapshot?.let { recompute(it) }
    }

    fun setBackgroundEnabled(on: Boolean) {
        settings.backgroundEnabled = on
        _state.value = _state.value.copy(backgroundEnabled = on)
    }

    fun clearHistory() {
        store.clear()
        _state.value = _state.value.copy(history = emptyList())
    }

    private fun intervalMs(): Long = when {
        CLIENT_UI in clients -> 1000L
        _state.value.snapshot?.chargeType?.isCharging == true -> 2000L
        else -> 15_000L
    }

    private fun tick() {
        val s = reader.read() ?: return
        val now = s.timeMs

        if (s.chargeType != lastType) {
            lastType = s.chargeType
            typeChangedMs = now
        }
        // 꽂거나 뺀 직후 몇 초는 값이 튀므로 그래프·기록에서 뺀다
        val settled = now - typeChangedMs >= 3000

        if (settled) samples.addLast(Sample(now, s.powerW, s.currentA, s.temperatureC))
        while (samples.isNotEmpty() && now - samples.first().timeMs > WINDOW_MS) samples.removeFirst()

        // 화면 켜고 앱을 볼 때의 소비 전력을 방전 중에 배워둔다
        if (settled && !s.chargeType.isCharging && s.currentA < 0 && CLIENT_UI in clients && pm.isInteractive) {
            val w = -s.powerW
            usageW = usageW?.let { it * 0.95 + w * 0.05 } ?: w
            usageTicks++
            if (usageTicks % 30 == 0) settings.usageW = usageW
        }

        var history = _state.value.history
        if (s.chargeType.isCharging) {
            val cur = live
            if (cur == null || cur.type != s.chargeType) {
                if (cur != null) history = finish(cur) ?: history
                live = LiveSession(s.chargeType, now, s.levelPercent, now)
            }
            val l = live
            if (l != null) {
                if (settled) l.add(s) else l.lastMs = now
            }
        } else {
            val cur = live
            if (cur != null) history = finish(cur) ?: history
            live = null
        }

        _state.value = _state.value.copy(history = history)
        recompute(s)
    }

    private fun avgOf(s: BatterySnapshot, windowMs: Long, pick: (Sample) -> Double): Double? {
        val recent = samples.filter { s.timeMs - it.timeMs <= windowMs }
        return if (recent.isEmpty()) null else recent.sumOf(pick) / recent.size
    }

    private fun recompute(s: BatterySnapshot) {
        val avgW10 = avgOf(s, 10_000) { it.powerW } ?: s.powerW
        val avgA10 = avgOf(s, 10_000) { it.currentA } ?: s.currentA
        val avgA30 = avgOf(s, 30_000) { it.currentA } ?: s.currentA
        val avgW30 = avgOf(s, 30_000) { it.powerW } ?: s.powerW
        val avgTemp30 = avgOf(s, 30_000) { it.tempC } ?: s.temperatureC

        val capacity = s.chargeCounterMah
            ?.takeIf { s.levelPercent in 5..100 }
            ?.let { it * 100.0 / s.levelPercent }
            ?.takeIf { it in 500.0..30_000.0 }

        val charging = s.chargeType.isCharging && !s.isFull
        val estimate = if (charging && avgA30 > 0.05 && capacity != null) {
            capacity * (100 - s.levelPercent) / 100.0 / 1000.0 / avgA30 * 60.0
        } else {
            null
        }

        // 최고 기록은 "좋은 조건"(화면 꺼짐, 80% 미만)에서 30초 넘게 안정적으로 잰 값만 쓴다
        val wireless = s.chargeType.isWireless
        val interactive = pm.isInteractive
        val goodCondition = !interactive && s.levelPercent < 80 && s.chargeType != ChargeType.WIRED_USB
        if (charging && goodCondition && s.timeMs - typeChangedMs >= 33_000 && avgW30 > 0) {
            val best = settings.bestW(wireless)
            if (best == null || avgW30 > best) settings.setBestW(wireless, avgW30)
        }

        val liveSession = live?.toSession()
        val usage = if (interactive) usageW ?: DEFAULT_USAGE_W else SCREEN_OFF_USAGE_W
        val diagnosis = if (charging) {
            val bestW = settings.bestW(wireless)
            val reason = stableReason(pickReason(s, avgW30, bestW, avgTemp30, usage, interactive), s.timeMs)
            Diagnosis(avgW30, bestW, reason, buildFactors(s, avgTemp30, usage, interactive))
        } else {
            shownReason = null
            candidateKey = null
            null
        }

        _state.value = _state.value.copy(
            snapshot = s,
            samples = samples.toList(),
            live = liveSession,
            avgCurrentA = avgA30,
            estimateMinutes = estimate,
            capacityMah = capacity,
            diagnosis = diagnosis,
            avgW10 = avgW10,
            avgA10 = avgA10,
        )
    }

    private fun stableReason(next: Reason, now: Long): Reason {
        val shown = shownReason
        if (shown == null || shown.key == next.key) {
            shownReason = next
            candidateKey = null
            return next
        }
        if (candidateKey != next.key) {
            candidateKey = next.key
            candidateSinceMs = now
        }
        if (now - candidateSinceMs >= 15_000) {
            shownReason = next
            candidateKey = null
            return next
        }
        return shown
    }

    /** 1분 넘게 충전한 경우만 기록에 남긴다. */
    private fun finish(session: LiveSession): List<ChargeSession>? {
        val result = session.toSession()
        return if (result.durationMin >= 1.0 && result.endLevel >= result.startLevel) store.add(result) else null
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
            // 측정이 끊겼던 공백은 에너지 계산에서 빼려고 최대 5초로 자른다.
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
}
