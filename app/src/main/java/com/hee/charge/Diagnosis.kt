package com.hee.charge

import android.os.BatteryManager
import android.os.PowerManager
import java.util.Locale
import kotlin.math.max

enum class Level { OK, INFO, WARN }

data class Factor(val title: String, val value: String, val detail: String, val level: Level)

/**
 * 충전기 출력이 어디로 갔는지 나눠서 보여주기 위한 값.
 * 배터리 전력만 실제 측정값이고, 사용 전력·손실은 추정이다.
 */
data class Diagnosis(
    val chargerW: Int,
    val batteryW: Double,
    val usageW: Double,
    val lossW: Double,
    val unusedW: Double,
    val reasonShort: String,
    val headline: String,
    val detail: String,
    val factors: List<Factor>,
)

private fun Double.f1() = String.format(Locale.US, "%.1f", this)

fun diagnose(
    s: BatterySnapshot,
    live: ChargeSession?,
    chargerW: Int,
    usageW: Double,
    interactive: Boolean,
): Diagnosis {
    val batteryW = max(s.powerW, 0.0)
    val lossRate = if (s.chargeType.isWireless) 0.30 else 0.12
    val inputW = (batteryW + usageW) / (1 - lossRate)
    val lossW = inputW - batteryW - usageW
    val unusedW = max(0.0, chargerW - inputW)

    val t = s.temperatureC
    val level = s.levelPercent
    val thermal = s.thermalStatus ?: PowerManager.THERMAL_STATUS_NONE
    val notCharging = s.status == BatteryManager.BATTERY_STATUS_NOT_CHARGING
    val nearMax = inputW >= chargerW * 0.8
    val peak = live?.maxW ?: 0.0

    // 가장 큰 원인 하나를 고른다 (위에 있을수록 우선)
    val (short, headline, detail) = when {
        notCharging -> Triple(
            "충전 멈춤",
            "충전이 잠시 멈춰 있어요",
            "배터리 보호 설정(예: 85%까지만 충전)이나 높은 온도 때문에 휴대폰이 충전을 멈췄어요.",
        )
        s.chargeType == ChargeType.WIRED_USB -> Triple(
            "USB 포트",
            "USB 포트라서 전력이 작아요",
            "컴퓨터 USB 포트 등은 보통 2.5~7.5W만 줘요. 벽 충전기에 꽂으면 빨라져요.",
        )
        s.chargeType.isWireless -> Triple(
            "무선 충전",
            "무선 충전은 원래 느리고 손실이 커요",
            "코일로 전달하면서 30% 안팎이 열로 사라지고, 무선 최대 속도도 유선보다 낮아요. " +
                "휴대폰 위치가 어긋나면 더 느려져요.",
        )
        nearMax -> Triple(
            "없음",
            "충전기 성능을 거의 다 쓰고 있어요",
            "지금은 특별한 제한 없이 빠르게 충전 중이에요.",
        )
        t >= 40 || thermal >= PowerManager.THERMAL_STATUS_SEVERE -> Triple(
            "온도",
            "온도가 높아서 속도를 낮췄어요",
            "배터리가 ${t.f1()}°C예요. 열을 줄이려고 휴대폰이 충전 전류를 줄였어요. " +
                "케이스를 빼거나 시원한 곳에 두면 빨라져요.",
        )
        level >= 80 -> Triple(
            "잔량 80%+",
            "배터리 보호 구간이에요 (80% 이상)",
            "배터리가 거의 차면 수명을 지키려고 전류를 크게 줄여요. 정상이에요.",
        )
        t >= 37 || thermal >= PowerManager.THERMAL_STATUS_MODERATE -> Triple(
            "온도",
            "온도 때문에 조금 낮췄을 수 있어요",
            "배터리가 ${t.f1()}°C로 따뜻해요. 많은 휴대폰이 37~40°C쯤부터 충전 속도를 줄여요.",
        )
        level >= 50 -> Triple(
            "잔량 50%+",
            "충전이 점점 느려지는 구간이에요",
            "초고속 충전은 잔량이 낮을 때 가장 빠르고, 50%를 넘으면 단계적으로 줄어요. 정상이에요.",
        )
        interactive && t >= 35 -> Triple(
            "화면+온도",
            "화면 켜짐 + 따뜻한 배터리 (${t.f1()}°C)",
            "화면이 켜진 채 배터리가 35°C를 넘으면 많은 휴대폰(특히 삼성)이 발열을 막으려고 충전 전류를 크게 줄여요. " +
                "화면을 끄면 보통 다시 빨라져요. 백그라운드 측정을 켜고 1~2분 끈 뒤 그래프로 확인해보세요.",
        )
        interactive -> Triple(
            "화면 사용",
            "화면을 켜고 쓰는 중이에요",
            "쓰는 전력(약 ${usageW.f1()}W)만큼 배터리로 덜 가고, 일부 기종은 사용 중 발열을 줄이려 충전 속도를 낮춰요. " +
                "백그라운드 측정을 켜고 화면을 1~2분 끈 뒤 그래프를 보면 차이를 알 수 있어요.",
        )
        else -> Triple(
            "기종/케이블",
            "휴대폰이나 케이블 한계로 보여요",
            "잔량·온도는 문제없어요. 이 기종의 최대 속도이거나, 케이블이 고속 충전(3A 이상)을 지원하지 않을 수 있어요.",
        )
    }

    val factors = buildList {
        add(
            when {
                s.chargeType == ChargeType.WIRED_USB ->
                    Factor("충전 방식", "USB 포트", "컴퓨터 등은 출력이 작아요", Level.WARN)
                s.chargeType.isWireless ->
                    Factor("충전 방식", "무선", "손실 약 30%, 최대 속도 낮음", Level.INFO)
                else -> Factor("충전 방식", "유선", "가장 빠른 방식", Level.OK)
            },
        )
        add(
            when {
                level >= 80 -> Factor("배터리 잔량", "$level%", "보호를 위해 천천히 충전", Level.WARN)
                level >= 50 -> Factor("배터리 잔량", "$level%", "점점 느려지는 구간", Level.INFO)
                else -> Factor("배터리 잔량", "$level%", "가장 빠른 구간", Level.OK)
            },
        )
        add(
            when {
                t >= 40 -> Factor("배터리 온도", "${t.f1()}°C", "높음, 속도 제한 가능성 큼", Level.WARN)
                t >= 37 -> Factor("배터리 온도", "${t.f1()}°C", "따뜻함, 약간 제한될 수 있음", Level.INFO)
                t >= 35 && interactive ->
                    Factor("배터리 온도", "${t.f1()}°C", "화면 켜진 상태에선 제한될 수 있음", Level.INFO)
                else -> Factor("배터리 온도", "${t.f1()}°C", "문제없음", Level.OK)
            },
        )
        if (s.thermalStatus != null) {
            add(
                when {
                    thermal >= PowerManager.THERMAL_STATUS_SEVERE ->
                        Factor("기기 발열", "높음", "휴대폰이 성능·충전을 제한 중", Level.WARN)
                    thermal >= PowerManager.THERMAL_STATUS_MODERATE ->
                        Factor("기기 발열", "보통", "일부 제한될 수 있음", Level.INFO)
                    else -> Factor("기기 발열", "정상", "문제없음", Level.OK)
                },
            )
        }
        add(
            if (interactive) {
                Factor("화면 사용", "켜짐", "약 ${usageW.f1()}W를 휴대폰이 사용", Level.INFO)
            } else {
                Factor("화면 사용", "꺼짐", "배터리로 최대한 들어감", Level.OK)
            },
        )
        if (notCharging) add(Factor("충전 상태", "멈춤", "배터리 보호 설정 확인", Level.WARN))
        if (peak >= batteryW * 1.3 && peak >= 5) {
            add(Factor("이번 충전 최대", "${peak.f1()}W", "충전기·케이블은 더 빠르게도 돼요", Level.OK))
        }
    }

    return Diagnosis(chargerW, batteryW, usageW, lossW, unusedW, short, headline, detail, factors)
}
