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

/** 충전 중에 생긴 "변화 순간" (온도 넘음, 화면 켬/끔, 전력 급변 등) */
data class ChargeEvent(
    val timeMs: Long,
    val icon: String,
    val text: String,
    val beforeW: Double?,
    /** null = 아직 측정 중, 음수 = 표시 안 함 */
    val afterW: Double?,
)

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
    /** 최신이 앞 */
    val events: List<ChargeEvent> = emptyList(),
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

    // 변화 순간 감지용
    private val events = ArrayDeque<ChargeEvent>()
    private var tempZone: Int? = null
    private var lastInteractive: Boolean? = null
    private var lastLevel: Int? = null

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
            val prev = lastType
            lastType = s.chargeType
            typeChangedMs = now
            if (prev != null) {
                when {
                    s.chargeType.isCharging && !prev.isCharging ->
                        addEvent(now, "🔌", "충전 시작 (${s.chargeType.label})", null)
                    !s.chargeType.isCharging && prev.isCharging ->
                        addEvent(now, "⏏", "충전기 분리 (${s.levelPercent}%)", null, done = true)
                    else -> addEvent(now, "🔁", "충전 방식 바뀜 → ${s.chargeType.label}", null)
                }
            }
            // 새로 꽂으면 온도·화면 기준을 다시 잡는다
            tempZone = null
            lastInteractive = null
            lastLevel = null
        }
        // 꽂거나 뺀 직후 몇 초는 값이 튀므로 그래프·기록에서 뺀다
        val settled = now - typeChangedMs >= 3000

        if (settled) samples.addLast(Sample(now, s.powerW, s.currentA, s.temperatureC))
        while (samples.isNotEmpty() && now - samples.first().timeMs > WINDOW_MS) samples.removeFirst()

        detectEvents(s, settled)

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

        // "원래 속도" 기록: 화면 꺼짐 + 시원할 때(37°C 미만) 30초 넘게 안정적으로 잰 값만 쓴다
        val wireless = s.chargeType.isWireless
        val interactive = pm.isInteractive
        val bucket = (s.levelPercent / 10).coerceIn(0, 9)
        val table = settings.expectedTable(wireless)
        val goodCondition = !interactive && avgTemp30 < 37 && s.chargeType != ChargeType.WIRED_USB &&
            s.status == android.os.BatteryManager.BATTERY_STATUS_CHARGING
        if (charging && goodCondition && s.timeMs - typeChangedMs >= 33_000 && avgW30 > table[bucket]) {
            table[bucket] = avgW30
            settings.saveExpectedTable(wireless, table)
        }
        val expectedW = expectedFor(table, bucket)

        val liveSession = live?.toSession()
        val usage = if (interactive) usageW ?: DEFAULT_USAGE_W else SCREEN_OFF_USAGE_W
        val diagnosis = if (charging) {
            val reason = stableReason(pickReason(s, avgW30, expectedW, avgTemp30, usage, interactive), s.timeMs)
            val (cuts, summary) = if (expectedW != null) {
                splitGap(s, avgW30, expectedW, avgTemp30, usage, interactive)
            } else {
                emptyList<Cut>() to reason.headline
            }
            Diagnosis(avgW30, expectedW, reason, cuts, summary, buildFactors(s, avgTemp30, usage, interactive))
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
            events = events.reversed(),
        )
    }

    /** 이 잔량 구간의 원래 속도. 기록이 없으면 바로 옆 구간을 쓴다. */
    private fun expectedFor(table: DoubleArray, bucket: Int): Double? {
        if (table[bucket] > 0) return table[bucket]
        val near = listOfNotNull(table.getOrNull(bucket - 1), table.getOrNull(bucket + 1)).filter { it > 0 }
        return if (near.isEmpty()) null else near.average()
    }

    private fun addEvent(now: Long, icon: String, text: String, beforeW: Double?, done: Boolean = false) {
        // done=true 면 "이후 전력"을 채우지 않는다 (분리 등)
        events.addLast(ChargeEvent(now, icon, text, beforeW, if (done) -1.0 else null))
        while (events.size > 40) events.removeFirst()
    }

    private fun tempZoneOf(t: Double) = when {
        t >= 40 -> 3
        t >= 37 -> 2
        t >= 35 -> 1
        else -> 0
    }

    private fun f1(v: Double) = String.format(java.util.Locale.US, "%.1f", v)

    private fun detectEvents(s: BatterySnapshot, settled: Boolean) {
        val now = s.timeMs
        val avgW10 = avgOf(s, 10_000) { it.powerW } ?: s.powerW

        // 30초 지난 이벤트에 "이후 전력"을 채운다
        for (i in events.indices) {
            val e = events[i]
            if (e.afterW == null && now - e.timeMs >= 30_000) events[i] = e.copy(afterW = avgW10)
        }
        if (!s.chargeType.isCharging || !settled) return

        // 온도 구간 변화 (내려갈 때는 0.5°C 여유를 둬서 들락날락 방지)
        val temp = avgOf(s, 10_000) { it.tempC } ?: s.temperatureC
        val zone = tempZoneOf(temp)
        val curZone = tempZone
        if (curZone == null) {
            tempZone = zone
        } else if (zone > curZone) {
            tempZone = zone
            val limit = when (zone) {
                3 -> 40
                2 -> 37
                else -> 35
            }
            addEvent(now, "🌡", "배터리 ${limit}°C 넘음 (${f1(temp)}°C)", avgW10)
        } else if (zone < curZone && tempZoneOf(temp + 0.5) < curZone) {
            tempZone = zone
            addEvent(now, "❄", "배터리 식음 (${f1(temp)}°C)", avgW10)
        }

        // 화면 켬/끔
        val interactive = pm.isInteractive
        val prevInteractive = lastInteractive
        lastInteractive = interactive
        if (prevInteractive != null && prevInteractive != interactive) {
            addEvent(now, "📱", if (interactive) "화면 켬" else "화면 끔", avgW10)
        }

        // 잔량 50% / 80% 넘음
        val level = s.levelPercent
        val prevLevel = lastLevel
        lastLevel = level
        if (prevLevel != null) {
            if (prevLevel < 50 && level >= 50) addEvent(now, "🔋", "50% 넘음 (속도가 줄어드는 구간)", avgW10)
            if (prevLevel < 80 && level >= 80) addEvent(now, "🔋", "80% 넘음 (천천히 채우는 구간)", avgW10)
        }

        // 다른 변화 없이 전력이 크게 바뀐 경우: 30~40초 전과 비교
        val past = samples.filter { now - it.timeMs in 30_000..40_000 }
        val lastEventMs = events.lastOrNull()?.timeMs ?: 0L
        if (past.size >= 3 && now - lastEventMs >= 45_000) {
            val pastW = past.sumOf { it.powerW } / past.size
            val diff = avgW10 - pastW
            if (kotlin.math.abs(diff) >= maxOf(2.0, pastW * 0.25)) {
                val why = shownReason?.short?.let { " · 지금 원인: $it" } ?: ""
                val text = if (diff < 0) "전력 크게 떨어짐$why" else "전력 크게 올라감$why"
                events.addLast(ChargeEvent(now, if (diff < 0) "📉" else "📈", text, pastW, avgW10))
                while (events.size > 40) events.removeFirst()
            }
        }
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
