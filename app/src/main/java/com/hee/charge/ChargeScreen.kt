package com.hee.charge

import android.os.BatteryManager
import android.os.PowerManager
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
                MainContent(state, s, keepScreenOn, onKeepScreenOnChange, onClearHistory)
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
    onClearHistory: () -> Unit,
) {
    HeaderCard(s)
    MetricsGrid(s)
    EstimateCard(state, s)
    LineChart(
        title = "전력 (W) · 최근 5분",
        unit = "W",
        values = state.samples.map { it.powerW.toFloat() },
        color = MaterialTheme.colorScheme.primary,
        decimals = 1,
    )
    LineChart(
        title = "온도 (°C) · 최근 5분",
        unit = "°C",
        values = state.samples.map { it.tempC.toFloat() },
        color = Color(0xFFE5672B),
        decimals = 1,
    )
    state.live?.let { LiveSessionCard(it) }
    DetailCard(s, state.capacityMah)
    CompareCard(state.history)
    HistoryCard(state.history, onClearHistory)
    SettingsCard(keepScreenOn, onKeepScreenOnChange)
    Text(
        "· 값은 휴대폰 배터리 센서 기준이며, 기기마다 정확도가 다를 수 있어요.\n" +
            "· 충전 기록은 앱이 켜져 있는 동안 측정한 값으로 저장돼요.",
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
        Column(Modifier.padding(16.dp)) { content() }
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
            Text("${s.levelPercent}%", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator(
            progress = { s.levelPercent / 100f },
            modifier = Modifier.fillMaxWidth().height(8.dp),
            strokeCap = StrokeCap.Round,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
    }
}

@Composable
private fun MetricsGrid(s: BatterySnapshot) {
    val temp = tempLevel(s.temperatureC)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricTile("전력", s.powerW.f(2), "W", Modifier.weight(1f), big = true)
            MetricTile("온도", s.temperatureC.f(1), "°C", Modifier.weight(1f), big = true, badge = temp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricTile("전압", s.voltageV.f(3), "V", Modifier.weight(1f))
            MetricTile("전류", s.currentA.f(3), "A", Modifier.weight(1f))
        }
    }
}

@Composable
private fun MetricTile(
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
    big: Boolean = false,
    badge: TempLevel? = null,
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
                    Box(Modifier.size(8.dp).background(it.color, CircleShape))
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
private fun LineChart(title: String, unit: String, values: List<Float>, color: Color, decimals: Int) {
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    SectionCard {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (values.size < 2) {
            Text("데이터를 모으는 중…", color = muted, modifier = Modifier.padding(vertical = 24.dp))
        } else {
            ChartBody(values, unit, color, decimals, gridColor, muted)
        }
    }
}

@Composable
private fun ChartBody(values: List<Float>, unit: String, color: Color, decimals: Int, gridColor: Color, muted: Color) {
    var touchX by remember { mutableStateOf<Float?>(null) }
    var widthPx by remember { mutableStateOf(1f) }
    Column {
        val minV = values.min()
        val maxV = values.max()
        val range = (maxV - minV).coerceAtLeast(0.5f)
        val lo = minV - range * 0.1f
        val hi = maxV + range * 0.1f

        val touchedIndex = touchX?.let { x ->
            ((x / widthPx) * (values.size - 1)).roundToInt().coerceIn(0, values.size - 1)
        }
        val headerValue = touchedIndex?.let { values[it] } ?: values.last()
        val headerLabel = touchedIndex?.let { "${values.size - 1 - it}초 전" } ?: "현재"

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
            fun x(i: Int) = i.toFloat() / (values.size - 1) * size.width

            // 옅은 기준선 (최소/최대)
            listOf(minV, maxV).forEach { v ->
                drawLine(gridColor, Offset(0f, y(v)), Offset(size.width, y(v)), strokeWidth = 1.dp.toPx())
            }

            val path = Path()
            values.forEachIndexed { i, v ->
                if (i == 0) path.moveTo(x(i), y(v)) else path.lineTo(x(i), y(v))
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
            Text("${values.size - 1}초 전", style = MaterialTheme.typography.labelSmall, color = muted)
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
                "아직 기록이 없어요. 앱을 켠 채로 1분 이상 충전하면 저장돼요.",
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
private fun SettingsCard(keepScreenOn: Boolean, onChange: (Boolean) -> Unit) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("화면 켜두기", fontWeight = FontWeight.Medium)
                Text(
                    "충전하면서 계속 지켜볼 때 켜세요",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = keepScreenOn, onCheckedChange = onChange)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, fontWeight = FontWeight.Medium)
    }
}
