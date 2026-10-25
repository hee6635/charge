package com.hee.charge

import android.os.BatteryManager
import android.os.PowerManager
import java.util.Locale

enum class Level { OK, INFO, WARN }

data class Factor(val title: String, val value: String, val detail: String, val level: Level)

/** 느려진 원인 하나 */
data class Reason(val key: String, val short: String, val headline: String, val detail: String)

/** "원래 속도"에서 깎인 몫 하나 (예: 온도 때문에 8W) */
data class Cut(val key: String, val icon: String, val label: String, val watts: Double)

/**
 * 충전 속도 진단. 순간값 대신 30초 평균을 쓴다.
 * 기준(expectedW)은 충전기 표기가 아니라, 이 휴대폰이 같은 잔량대에서
 * 화면 끄고 시원할 때 실제로 받아본 속도.
 */
data class Diagnosis(
    val nowW: Double,
    val expectedW: Double?,
    val reason: Reason,
    val cuts: List<Cut>,
    val summary: String,
    val factors: List<Factor>,
) {
    /** 요약 문장이 "다른 내용"으로 바뀌었는지 판단하는 키 (숫자만 바뀌면 같은 키) */
    val summaryKey: String get() = cuts.maxByOrNull { it.watts }?.key ?: reason.key

    /** 원래 속도 대비 지금 속도 (0~1) */
    val ratio: Double? get() = expectedW?.takeIf { it > 0 }?.let { (nowW / it).coerceIn(0.0, 1.0) }
}

private fun Double.f1() = String.format(Locale.US, "%.1f", this)

/** 지금 상황에서 가장 큰 원인을 고른다 (위에 있을수록 우선) */
fun pickReason(
    s: BatterySnapshot,
    avgW: Double,
    expectedW: Double?,
    tempC: Double,
    usageW: Double,
    interactive: Boolean,
): Reason {
    val level = s.levelPercent
    val thermal = s.thermalStatus ?: PowerManager.THERMAL_STATUS_NONE
    val atBest = expectedW != null && avgW >= expectedW * 0.85
    return when {
        s.status == BatteryManager.BATTERY_STATUS_NOT_CHARGING -> Reason(
            "paused",
            "충전 멈춤",
            "충전이 잠시 멈춰 있어요",
            "배터리 보호 설정(예: 85%까지만 충전)이나 높은 온도 때문에 휴대폰이 충전을 멈췄어요.",
        )
        s.chargeType == ChargeType.WIRED_USB -> Reason(
            "usb",
            "USB 포트",
            "USB 포트라서 전력이 작아요",
            "컴퓨터 USB 포트 등은 보통 2.5~7.5W만 줘요. 벽 충전기에 꽂으면 빨라져요.",
        )
        atBest -> Reason(
            "best",
            "정상 속도",
            "원래 속도로 잘 받고 있어요",
            "이 잔량에서 평소 받던 속도와 비슷해요. 따로 막는 원인이 없어요.",
        )
        s.chargeType.isWireless -> Reason(
            "wireless",
            "무선",
            "무선 충전은 원래 느려요",
            "코일로 전달하면서 손실이 크고, 무선 최대 속도도 유선보다 낮아요. 휴대폰 위치가 어긋나면 더 느려져요.",
        )
        tempC >= 40 || thermal >= PowerManager.THERMAL_STATUS_SEVERE -> Reason(
            "hot",
            "온도 높음",
            "배터리가 뜨거워서 느려졌어요",
            "배터리가 ${tempC.f1()}°C예요. 휴대폰이 열을 줄이려고 충전을 늦췄어요. 케이스를 빼거나 시원한 곳에 두면 빨라져요.",
        )
        level >= 80 -> Reason(
            "full",
            "80% 이상",
            "거의 다 차서 천천히 채우는 중이에요",
            "80%가 넘으면 배터리 수명을 지키려고 일부러 천천히 충전해요. 정상이에요.",
        )
        interactive && tempC >= 35 -> Reason(
            "screen_warm",
            "화면+온도",
            "화면을 켜둬서 + 배터리가 따뜻해서 느려졌어요",
            "화면이 켜진 채 배터리가 35°C를 넘으면 많은 휴대폰(특히 삼성)이 발열을 막으려고 충전을 줄여요. " +
                "화면을 끄면 보통 다시 빨라져요.",
        )
        tempC >= 37 || thermal >= PowerManager.THERMAL_STATUS_MODERATE -> Reason(
            "warm",
            "온도",
            "배터리가 따뜻해서 조금 느려졌어요",
            "배터리가 ${tempC.f1()}°C예요. 많은 휴대폰이 37~40°C쯤부터 충전을 줄여요.",
        )
        level >= 50 -> Reason(
            "half",
            "50% 이상",
            "절반 넘게 차서 속도가 줄어드는 중이에요",
            "고속 충전은 배터리가 비어 있을 때 가장 빠르고, 50%가 넘으면 단계적으로 줄어요. 정상이에요.",
        )
        interactive -> Reason(
            "screen",
            "화면 켜짐",
            "화면을 켜둬서 조금 느려졌어요",
            "휴대폰이 쓰는 전력(약 ${usageW.f1()}W)만큼 배터리로 덜 들어가요. 화면을 끄면 그만큼 빨라져요.",
        )
        expectedW == null -> Reason(
            "learning",
            "기록 중",
            "비교할 기록을 모으는 중이에요",
            "백그라운드 측정을 켜고 화면을 끈 채 충전하면, 잔량별로 이 휴대폰이 원래 받는 속도가 기록돼요. 그다음부터 얼마나, 왜 줄었는지 알려드려요.",
        )
        else -> Reason(
            "device",
            "원인 불명",
            "뚜렷한 원인이 안 보여요",
            "잔량·온도·화면은 문제없어요. 케이블이 고속 충전(3A 이상)을 지원하는지 확인해보세요.",
        )
    }
}

fun buildFactors(s: BatterySnapshot, tempC: Double, usageW: Double, interactive: Boolean): List<Factor> {
    val level = s.levelPercent
    val thermal = s.thermalStatus
    return buildList {
        add(
            when {
                s.chargeType == ChargeType.WIRED_USB -> Factor("충전 방식", "USB 포트", "출력이 작아요", Level.WARN)
                s.chargeType.isWireless -> Factor("충전 방식", "무선", "유선보다 느려요", Level.INFO)
                else -> Factor("충전 방식", "유선", "가장 빠른 방식", Level.OK)
            },
        )
        add(
            when {
                level >= 80 -> Factor("배터리 잔량", "$level%", "거의 다 차서 천천히 충전", Level.WARN)
                level >= 50 -> Factor("배터리 잔량", "$level%", "속도가 줄어드는 구간", Level.INFO)
                else -> Factor("배터리 잔량", "$level%", "가장 빠른 구간", Level.OK)
            },
        )
        add(
            when {
                tempC >= 40 -> Factor("배터리 온도", "${tempC.f1()}°C", "뜨거움, 충전이 많이 줄어요", Level.WARN)
                tempC >= 37 -> Factor("배터리 온도", "${tempC.f1()}°C", "따뜻함, 조금 줄 수 있어요", Level.INFO)
                tempC >= 35 && interactive ->
                    Factor("배터리 온도", "${tempC.f1()}°C", "화면 켜져 있으면 줄 수 있어요", Level.INFO)
                tempC >= 35 -> Factor("배터리 온도", "${tempC.f1()}°C", "조금 따뜻함, 화면 꺼져 있으면 괜찮아요", Level.INFO)
                else -> Factor("배터리 온도", "${tempC.f1()}°C", "괜찮아요", Level.OK)
            },
        )
        if (thermal != null) {
            add(
                when {
                    thermal >= PowerManager.THERMAL_STATUS_SEVERE ->
                        Factor("휴대폰 발열", "높음", "휴대폰이 충전을 줄이는 중", Level.WARN)
                    thermal >= PowerManager.THERMAL_STATUS_MODERATE ->
                        Factor("휴대폰 발열", "보통", "조금 줄 수 있어요", Level.INFO)
                    else -> Factor("휴대폰 발열", "정상", "괜찮아요", Level.OK)
                },
            )
        }
        add(
            if (interactive) {
                Factor("화면", "켜짐", "약 ${usageW.f1()}W를 휴대폰이 써요", Level.INFO)
            } else {
                Factor("화면", "꺼짐", "괜찮아요", Level.OK)
            },
        )
    }
}

/**
 * 원래 속도와 지금 속도의 차이를 원인별로 나눈다.
 * 화면 몫은 재둔 사용 전력, 나머지는 그때 상황(온도·잔량 등)에 돌린다.
 */
fun splitGap(
    s: BatterySnapshot,
    nowW: Double,
    expectedW: Double,
    tempC: Double,
    usageW: Double,
    interactive: Boolean,
): Pair<List<Cut>, String> {
    val gap = expectedW - nowW
    if (gap <= maxOf(0.5, expectedW * 0.1)) {
        return emptyList<Cut>() to "이 잔량에서 원래 받던 만큼 잘 받고 있어요"
    }
    val cuts = mutableListOf<Cut>()
    var rest = gap
    if (interactive) {
        val screen = minOf(usageW, rest)
        cuts += Cut("screen", "📱", "화면 켜짐 → 휴대폰이 사용", screen)
        rest -= screen
    }
    val thermal = s.thermalStatus ?: PowerManager.THERMAL_STATUS_NONE
    if (rest > 0.3) {
        cuts += when {
            s.status == BatteryManager.BATTERY_STATUS_NOT_CHARGING ->
                Cut("paused", "⏸", "충전 멈춤 (보호 설정·온도)", rest)
            tempC >= 35 || thermal >= PowerManager.THERMAL_STATUS_MODERATE ->
                Cut("temp", "🌡", "배터리 ${tempC.f1()}°C → 발열 때문에 줄임", rest)
            else -> Cut("unknown", "❓", "뚜렷한 원인 없음 (케이블·사용량 확인)", rest)
        }
    }
    val main = cuts.maxBy { it.watts }
    val summary = when (main.key) {
        "temp" -> "배터리가 ${tempC.f1()}°C라서 휴대폰이 일부러 약 ${main.watts.f1()}W 줄였어요"
        "screen" -> "화면을 켜둬서 약 ${main.watts.f1()}W를 휴대폰이 쓰고 있어요"
        "paused" -> "휴대폰이 충전을 잠시 멈춰서 약 ${main.watts.f1()}W 덜 받고 있어요"
        else -> "원래보다 약 ${gap.f1()}W 덜 받는데 뚜렷한 원인이 안 보여요"
    }
    return cuts to summary
}
