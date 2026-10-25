package com.hee.charge

import android.os.BatteryManager
import android.os.PowerManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private fun Double.f(digits: Int) = String.format(Locale.US, "%.${digits}f", this)

/** 숫자가 툭 바뀌지 않고 0.8초 동안 부드럽게 따라가게 한다 */
@Composable
private fun smoothly(target: Double): Double {
    val v by animateFloatAsState(
        targetValue = target.toFloat(),
        animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing),
        label = "smooth",
    )
    return v.toDouble()
}

private fun minutesText(min: Double): String {
    val total = min.roundToInt().coerceAtLeast(0)
    val h = total / 60
    val m = total % 60
    return if (h > 0) "${h}시간 ${m}분" else "${m}분"
}

// 상태 색 (온도 경고 전용, 항상 글자 라벨과 함께 쓴다)
private val Good = Color(0xFF2E9D5B)
private val Warn = Color(0xFFD99A0B)
private val Serious = Color(0xFFE5672B)
private val Critical = Color(0xFFD32F2F)

private data class TempLevel(val label: String, val color: Color)

private fun tempLevel(c: Double) = when {
    c < 35 -> TempLevel("정상", Good)
    c < 40 -> TempLevel("따뜻함", Warn)
    c < 45 -> TempLevel("높음", Serious)
    else -> TempLevel("과열 위험", Critical)
}

@Composable
fun ChargeScreen(
    state: UiState,
    keepScreenOn: Boolean,
    onKeepScreenOnChange: (Boolean) -> Unit,
    onBackgroundChange: (Boolean) -> Unit,
    onResetBest: () -> Unit,
    onClearHistory: () -> Unit,
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val s = state.snapshot
            if (s == null) {
                Text("배터리 정보를 읽는 중…", modifier = Modifier.padding(24.dp))
            } else {
                MainContent(
                    state, s, keepScreenOn, onKeepScreenOnChange, onBackgroundChange, onResetBest, onClearHistory,
                )
            }
        }
    }
}

@Composable
private fun MainContent(
    state: UiState,
    s: BatterySnapshot,
    keepScreenOn: Boolean,
    onKeepScreenOnChange: (Boolean) -> Unit,
    onBackgroundChange: (Boolean) -> Unit,
    onResetBest: () -> Unit,
    onClearHistory: () -> Unit,
) {
    val times = state.samples.map { it.timeMs }
    HeaderCard(s)
    ToggleCard(
        title = "백그라운드 측정",
        subtitle = if (state.backgroundEnabled) {
            "앱을 닫거나 화면을 꺼도 계속 기록해요 (알림창에 표시)"
        } else {
            "꺼짐: 앱이 화면에 떠 있을 때만 기록해요"
        },
        checked = state.backgroundEnabled,
        onChange = onBackgroundChange,
    )
    MetricsGrid(s, state.avgW10, state.avgA10)
    state.diagnosis?.let { DiagnosisCard(it, onResetBest) }
    EstimateCard(state, s)
    LineChart(
        title = "전력 (W, 10초 평균) · 최근 5분",
        unit = "W",
        times = times,
        values = smooth(state.samples, 10_000) { it.powerW },
        color = MaterialTheme.colorScheme.primary,
        decimals = 1,
    )
    LineChart(
        title = "온도 (°C) · 최근 5분",
        unit = "°C",
        times = times,
        values = state.samples.map { it.tempC.toFloat() },
        color = Color(0xFFE5672B),
        decimals = 1,
    )
    state.live?.let { LiveSessionCard(it) }
    DetailCard(s, state.capacityMah)
    CompareCard(state.history)
    HistoryCard(state.history, onClearHistory)
    ToggleCard(
        title = "화면 켜두기",
        subtitle = "충전하면서 계속 지켜볼 때 켜세요",
        checked = keepScreenOn,
        onChange = onKeepScreenOnChange,
    )
    Text(
        "· 값은 휴대폰 배터리 센서 기준이며, 기기마다 정확도가 다를 수 있어요.\n" +
            "· 화면을 켜둘 때 휴대폰이 쓰는 전력은 추정치예요.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.animateContentSize().padding(16.dp)) { content() }
    }
}

@Composable
private fun HeaderCard(s: BatterySnapshot) {
    val icon = when {
        s.chargeType.isWireless -> "📡"
        s.chargeType.isCharging -> "🔌"
        else -> "🔋"
    }
    val statusText = when {
        s.isFull -> "완충됨"
        s.chargeType.isCharging -> "충전 중"
        else -> "방전 중"
    }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(icon, fontSize = 36.sp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(s.chargeType.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(statusText, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                "${smoothly(s.levelPercent.toDouble()).roundToInt()}%",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(12.dp))
        val level = smoothly(s.levelPercent.toDouble()).toFloat()
        LinearProgressIndicator(
            progress = { level / 100f },
            modifier = Modifier.fillMaxWidth().height(8.dp),
            strokeCap = StrokeCap.Round,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
    }
}

@Composable
private fun MetricsGrid(s: BatterySnapshot, avgW10: Double, avgA10: Double) {
    val temp = tempLevel(s.temperatureC)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricTile(
                "전력 · 10초 평균", smoothly(avgW10).f(1), "W", Modifier.weight(1f), big = true,
                sub = "순간 ${s.powerW.f(2)} W",
            )
            MetricTile("온도", smoothly(s.temperatureC).f(1), "°C", Modifier.weight(1f), big = true, badge = temp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricTile("전압", smoothly(s.voltageV).f(2), "V", Modifier.weight(1f))
            MetricTile(
                "전류 · 10초 평균", smoothly(avgA10).f(2), "A", Modifier.weight(1f),
                sub = "순간 ${s.currentA.f(2)} A",
            )
        }
    }
}

/** 각 점마다 직전 windowMs 동안의 평균 (그래프용) */
private fun smooth(samples: List<Sample>, windowMs: Long, pick: (Sample) -> Double): List<Float> {
    val out = ArrayList<Float>(samples.size)
    var start = 0
    var sum = 0.0
    samples.forEachIndexed { i, smp ->
        sum += pick(smp)
        while (smp.timeMs - samples[start].timeMs > windowMs) {
            sum -= pick(samples[start])
            start++
        }
        out += (sum / (i - start + 1)).toFloat()
    }
    return out
}

@Composable
private fun MetricTile(
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
    big: Boolean = false,
    badge: TempLevel? = null,
    sub: String? = null,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                badge?.let {
                    val dot by animateColorAsState(it.color, tween(800), label = "badge")
                    Box(Modifier.size(8.dp).background(dot, CircleShape))
                    Spacer(Modifier.width(4.dp))
                    Text(it.label, style = MaterialTheme.typography.labelMedium)
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    value,
                    style = if (big) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(4.dp))
                Text(unit, modifier = Modifier.padding(bottom = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            sub?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun EstimateCard(state: UiState, s: BatterySnapshot) {
    SectionCard {
        Text("완충까지 예상 시간", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        when {
            !s.chargeType.isCharging -> Text("충전기를 연결하면 계산해요.")
            s.isFull || s.levelPercent >= 100 -> Text("완충 상태예요.")
            else -> {
                val est = state.estimateMinutes
                Text(
                    if (est != null) "약 ${minutesText(est)}" else "계산 중…",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "최근 30초 평균 전류 ${state.avgCurrentA.f(2)} A 기준",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                s.systemRemainingMs?.let {
                    Text("시스템 예상: ${minutesText(it / 60_000.0)}", style = MaterialTheme.typography.bodyMedium)
                }
                if (s.levelPercent >= 80) {
                    Text(
                        "80% 이후에는 배터리 보호를 위해 충전 속도가 느려져요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 단일 시리즈 라인 차트. 누르고 있으면 그 시점의 값을 보여준다. */
@Composable
private fun LineChart(
    title: String,
    unit: String,
    times: List<Long>,
    values: List<Float>,
    color: Color,
    decimals: Int,
) {
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    SectionCard {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (values.size < 2 || times.last() <= times.first()) {
            Text("데이터를 모으는 중…", color = muted, modifier = Modifier.padding(vertical = 24.dp))
        } else {
            ChartBody(times, values, unit, color, decimals, gridColor, muted)
        }
    }
}

private fun agoText(ms: Long): String {
    val sec = (ms / 1000).toInt().coerceAtLeast(0)
    return if (sec >= 60) "${sec / 60}분 ${sec % 60}초 전" else "${sec}초 전"
}

@Composable
private fun ChartBody(
    times: List<Long>,
    values: List<Float>,
    unit: String,
    color: Color,
    decimals: Int,
    gridColor: Color,
    muted: Color,
) {
    var touchX by remember { mutableStateOf<Float?>(null) }
    var widthPx by remember { mutableStateOf(1f) }
    Column {
        val minV = values.min()
        val maxV = values.max()
        val range = (maxV - minV).coerceAtLeast(0.5f)
        val lo by animateFloatAsState(minV - range * 0.1f, tween(600), label = "lo")
        val hi by animateFloatAsState(maxV + range * 0.1f, tween(600), label = "hi")

        // 측정 간격이 1초/2초로 바뀔 수 있어서 가로축은 실제 시각 기준
        val t0 = times.first()
        val tN = times.last()
        val span = (tN - t0).toFloat()
        val touchedIndex = touchX?.let { x ->
            val target = t0 + (x / widthPx).coerceIn(0f, 1f) * span
            times.indices.minByOrNull { kotlin.math.abs(times[it] - target) }
        }
        val headerValue = touchedIndex?.let { values[it] } ?: values.last()
        val headerLabel = touchedIndex?.let { agoText(tN - times[it]) } ?: "현재"

        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                "${headerValue.toDouble().f(decimals)} $unit",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(8.dp))
            Text(headerLabel, color = muted, modifier = Modifier.padding(bottom = 3.dp))
            Spacer(Modifier.weight(1f))
            Text(
                "최소 ${minV.toDouble().f(decimals)} · 최대 ${maxV.toDouble().f(decimals)}",
                style = MaterialTheme.typography.bodySmall,
                color = muted,
                modifier = Modifier.padding(bottom = 3.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: continue
                            touchX = if (change.pressed) change.position.x else null
                        }
                    }
                },
        ) {
            widthPx = size.width
            fun y(v: Float) = size.height - (v - lo) / (hi - lo) * size.height
            fun x(i: Int) = (times[i] - t0) / span * size.width

            // 옅은 기준선 (최소/최대)
            listOf(minV, maxV).forEach { v ->
                drawLine(gridColor, Offset(0f, y(v)), Offset(size.width, y(v)), strokeWidth = 1.dp.toPx())
            }

            val path = Path()
            // 점 사이를 곡선으로 이어서 꺾임 없이 보이게
            var prevX = x(0)
            var prevY = y(values[0])
            path.moveTo(prevX, prevY)
            for (i in 1 until values.size) {
                val cx = x(i)
                val cy = y(values[i])
                val mx = (prevX + cx) / 2f
                path.cubicTo(mx, prevY, mx, cy, cx, cy)
                prevX = cx
                prevY = cy
            }
            drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

            val idx = touchedIndex ?: values.lastIndex
            val px = x(idx)
            val py = y(values[idx])
            if (touchedIndex != null) {
                drawLine(muted, Offset(px, 0f), Offset(px, size.height), strokeWidth = 1.dp.toPx())
            }
            drawCircle(Color.White, radius = 6.dp.toPx(), center = Offset(px, py))
            drawCircle(color, radius = 4.dp.toPx(), center = Offset(px, py))
        }
        Row(Modifier.fillMaxWidth()) {
            Text(agoText(tN - t0), style = MaterialTheme.typography.labelSmall, color = muted)
            Spacer(Modifier.weight(1f))
            Text("지금", style = MaterialTheme.typography.labelSmall, color = muted)
        }
    }
}

@Composable
private fun LiveSessionCard(live: ChargeSession) {
    SectionCard {
        Text("이번 충전", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        InfoRow("충전 시간", minutesText(live.durationMin))
        InfoRow("충전량", "${live.startLevel}% → ${live.endLevel}% (+${live.gainedPercent}%)")
        InfoRow("평균 / 최대 전력", "${live.avgW.f(1)} W / ${live.maxW.f(1)} W")
        InfoRow("최고 온도", "${live.maxTempC.f(1)} °C")
        InfoRow("들어간 에너지", "${live.energyWh.f(2)} Wh")
    }
}

@Composable
private fun DetailCard(s: BatterySnapshot, capacityMah: Double?) {
    val health = when (s.health) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "좋음"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "과열"
        BatteryManager.BATTERY_HEALTH_DEAD -> "수명 다함"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "과전압"
        BatteryManager.BATTERY_HEALTH_COLD -> "저온"
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "이상"
        else -> "알 수 없음"
    }
    val thermal = when (s.thermalStatus) {
        null -> null
        PowerManager.THERMAL_STATUS_NONE -> "정상"
        PowerManager.THERMAL_STATUS_LIGHT -> "약간 따뜻함"
        PowerManager.THERMAL_STATUS_MODERATE -> "보통 (성능 조절 가능)"
        PowerManager.THERMAL_STATUS_SEVERE -> "뜨거움 (성능 제한)"
        PowerManager.THERMAL_STATUS_CRITICAL -> "매우 뜨거움"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "위험"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "종료 직전"
        else -> "알 수 없음"
    }
    SectionCard {
        Text("배터리 정보", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        InfoRow("배터리 상태", health)
        thermal?.let { InfoRow("기기 발열 상태", it) }
        s.chargeCounterMah?.let { InfoRow("남은 용량", "${it.roundToInt()} mAh") }
        capacityMah?.let { InfoRow("추정 전체 용량", "약 ${it.roundToInt()} mAh") }
        s.cycleCount?.let { InfoRow("충전 사이클", "${it}회") }
        s.technology?.takeIf { it.isNotBlank() }?.let { InfoRow("종류", it) }
    }
}

private data class TypeStats(val count: Int, val avgW: Double, val maxTemp: Double, val speed: Double)

private fun stats(list: List<ChargeSession>): TypeStats? {
    if (list.isEmpty()) return null
    return TypeStats(
        count = list.size,
        avgW = list.map { it.avgW }.average(),
        maxTemp = list.map { it.maxTempC }.average(),
        speed = list.map { it.percentPerMin }.average(),
    )
}

@Composable
private fun CompareCard(history: List<ChargeSession>) {
    if (history.isEmpty()) return
    val wired = stats(history.filter { !it.type.isWireless })
    val wireless = stats(history.filter { it.type.isWireless })
    SectionCard {
        Text("유선 vs 무선 비교", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "저장된 충전 기록의 평균",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            Text("", Modifier.weight(1.2f))
            Text("🔌 유선", Modifier.weight(1f), fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
            Text("📡 무선", Modifier.weight(1f), fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
        }
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        CompareRow("기록 수", wired?.count?.let { "${it}회" }, wireless?.count?.let { "${it}회" })
        CompareRow("평균 전력", wired?.avgW?.let { "${it.f(1)} W" }, wireless?.avgW?.let { "${it.f(1)} W" })
        CompareRow("평균 최고 온도", wired?.maxTemp?.let { "${it.f(1)} °C" }, wireless?.maxTemp?.let { "${it.f(1)} °C" })
        CompareRow("충전 속도", wired?.speed?.let { "${it.f(2)} %/분" }, wireless?.speed?.let { "${it.f(2)} %/분" })
    }
}

@Composable
private fun CompareRow(label: String, a: String?, b: String?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, Modifier.weight(1.2f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(a ?: "–", Modifier.weight(1f), textAlign = TextAlign.End)
        Text(b ?: "–", Modifier.weight(1f), textAlign = TextAlign.End)
    }
}

@Composable
private fun HistoryCard(history: List<ChargeSession>, onClear: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    val fmt = remember { SimpleDateFormat("M/d HH:mm", Locale.KOREA) }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "충전 기록",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            if (history.isNotEmpty()) TextButton(onClick = { confirm = true }) { Text("전체 삭제") }
        }
        if (history.isEmpty()) {
            Text(
                "아직 기록이 없어요. 1분 이상 충전하면 저장돼요. (백그라운드 측정을 켜면 앱을 닫아도 기록돼요)",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        history.take(10).forEachIndexed { i, h ->
            if (i > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (h.type.isWireless) "📡" else "🔌", fontSize = 20.sp)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("${fmt.format(Date(h.startMs))} · ${minutesText(h.durationMin)}", fontWeight = FontWeight.Medium)
                    Text(
                        "${h.startLevel}→${h.endLevel}% · 평균 ${h.avgW.f(1)}W · 최고 ${h.maxTempC.f(1)}°C",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("기록을 모두 지울까요?") },
            confirmButton = {
                TextButton(onClick = { onClear(); confirm = false }) { Text("삭제") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("취소") } },
        )
    }
}

@Composable
private fun ToggleCard(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}

@Composable
private fun DiagnosisCard(d: Diagnosis, onResetBest: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    var confirmReset by remember { mutableStateOf(false) }
    SectionCard {
        Text("충전 속도 진단", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))

        // 1) 지금 속도가 이 휴대폰 최고 기록의 몇 %인지
        val ratio = d.ratio
        val shownRatio = smoothly(ratio ?: 0.0)
        val shownNowW = smoothly(d.nowW)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                if (ratio != null) "${(shownRatio * 100).roundToInt()}%" else d.speedLabel,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
            )
            if (ratio != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    "최고 기록 대비 · ${d.speedLabel}",
                    color = muted,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
        }
        if (ratio != null) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { shownRatio.toFloat() },
                modifier = Modifier.fillMaxWidth().height(10.dp),
                strokeCap = StrokeCap.Round,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
            Spacer(Modifier.height(6.dp))
        }
        Text(
            buildString {
                append("지금 ${shownNowW.f(1)} W")
                d.bestW?.let { append(" · 이 휴대폰 최고 ${it.f(1)} W") }
                append(" (30초 평균)")
            },
            style = MaterialTheme.typography.bodySmall,
            color = muted,
        )

        // 2) 느린 이유 (15초 넘게 같은 원인일 때만 바뀜)
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        AnimatedContent(
            targetState = d.reason,
            contentKey = { it.key },
            transitionSpec = { fadeIn(tween(600)) togetherWith fadeOut(tween(400)) },
            label = "reason",
        ) { reason ->
            Column {
                Text(reason.headline, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(reason.detail, style = MaterialTheme.typography.bodyMedium)
            }
        }

        // 3) 체크리스트
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        d.factors.forEach { f ->
            val (mark, color) = when (f.level) {
                Level.OK -> "✓" to Good
                Level.INFO -> "!" to Warn
                Level.WARN -> "!" to Critical
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(20.dp).background(color, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(mark, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(f.title, fontWeight = FontWeight.Medium)
                    Text(f.detail, style = MaterialTheme.typography.bodySmall, color = muted)
                }
                Text(f.value, fontWeight = FontWeight.Medium)
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text(
            "충전기에 적힌 W(예: 25W)는 '최대' 출력이에요. 휴대폰은 잔량·온도·화면 상태에 따라 필요한 만큼만 받아서, " +
                "그 숫자가 그대로 들어오지는 않아요. 그래서 이 앱은 이 휴대폰이 실제로 받아본 최고 속도와 비교해요.",
            style = MaterialTheme.typography.bodySmall,
            color = muted,
        )
        if (d.bestW != null) {
            TextButton(onClick = { confirmReset = true }) { Text("최고 기록 초기화") }
        }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("최고 기록을 지울까요?") },
            text = { Text("충전기나 케이블을 바꿨을 때 쓰세요. 다음 충전부터 다시 기록해요.") },
            confirmButton = {
                TextButton(onClick = { onResetBest(); confirmReset = false }) { Text("초기화") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("취소") } },
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, fontWeight = FontWeight.Medium)
    }
}
