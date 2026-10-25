package com.hee.charge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

/** 앱을 닫아도 측정을 이어가는 서비스. 알림창에 현재 값을 보여준다. */
class ChargeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastText = ""
    private var lastNotifyMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ChargeMonitor.init(this)
        createChannel()
        val started = runCatching {
            val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
            ServiceCompat.startForeground(this, NOTIF_ID, build("측정 준비 중", ""), type)
        }.isSuccess
        if (!started) {
            stopSelf()
            return
        }
        ChargeMonitor.acquire(ChargeMonitor.CLIENT_SERVICE)
        scope.launch { ChargeMonitor.state.collect { update(it) } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        scope.cancel()
        ChargeMonitor.release(ChargeMonitor.CLIENT_SERVICE)
        releaseWakeLock()
        super.onDestroy()
    }

    private fun update(state: UiState) {
        val s = state.snapshot ?: return

        // 충전 중에는 화면이 꺼져도 측정이 멈추지 않게 CPU를 깨워둔다
        if (s.chargeType.isCharging) acquireWakeLock() else releaseWakeLock()

        val title: String
        val text: String
        if (s.chargeType.isCharging) {
            val icon = if (s.chargeType.isWireless) "무선" else "유선"
            title = "$icon · ${s.powerW.f(1)} W · ${s.temperatureC.f(1)}°C · ${s.levelPercent}%"
            val parts = mutableListOf<String>()
            state.estimateMinutes?.let { parts += "완충까지 약 ${minutes(it)}" }
            state.diagnosis?.let { if (it.reasonShort != "없음") parts += "제한: ${it.reasonShort}" }
            text = parts.joinToString(" · ").ifEmpty { "충전 중" }
        } else {
            title = "충전 안 함 · ${s.levelPercent}% · ${s.temperatureC.f(1)}°C"
            text = "충전기를 연결하면 자동으로 기록해요"
        }

        val key = "$title|$text"
        val now = System.currentTimeMillis()
        if (key == lastText || now - lastNotifyMs < 2000) return
        lastText = key
        lastNotifyMs = now
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, build(title, text))
    }

    private fun build(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_charge)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "충전 측정", NotificationManager.IMPORTANCE_LOW).apply {
            description = "백그라운드에서 충전 상태를 측정하는 동안 표시돼요"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ChargeMonitor:measure").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun Double.f(d: Int) = String.format(Locale.US, "%.${d}f", this)

    private fun minutes(min: Double): String {
        val total = min.roundToInt().coerceAtLeast(0)
        return if (total >= 60) "${total / 60}시간 ${total % 60}분" else "${total}분"
    }

    companion object {
        private const val CHANNEL_ID = "measure"
        private const val NOTIF_ID = 1

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ChargeService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ChargeService::class.java))
        }
    }
}
