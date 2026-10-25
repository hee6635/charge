package com.hee.charge

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {

    // 알림 권한은 거절해도 측정은 된다 (알림만 안 보임)
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { startBackground() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ChargeMonitor.init(this)
        if (ChargeMonitor.state.value.backgroundEnabled) startBackground()
        enableEdgeToEdge()
        setContent {
            ChargeTheme {
                val state by ChargeMonitor.state.collectAsStateWithLifecycle()
                var keepScreenOn by rememberSaveable { mutableStateOf(false) }
                LaunchedEffect(keepScreenOn) {
                    if (keepScreenOn) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
                ChargeScreen(
                    state = state,
                    keepScreenOn = keepScreenOn,
                    onKeepScreenOnChange = { keepScreenOn = it },
                    onBackgroundChange = ::setBackground,
                    onChargerWChange = ChargeMonitor::setChargerW,
                    onClearHistory = ChargeMonitor::clearHistory,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ChargeMonitor.acquire(ChargeMonitor.CLIENT_UI)
    }

    override fun onStop() {
        ChargeMonitor.release(ChargeMonitor.CLIENT_UI)
        super.onStop()
    }

    private fun setBackground(on: Boolean) {
        ChargeMonitor.setBackgroundEnabled(on)
        if (!on) {
            ChargeService.stop(this)
            return
        }
        val needsPermission = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startBackground()
        }
    }

    private fun startBackground() {
        runCatching { ChargeService.start(this) }
    }
}

@Composable
fun ChargeTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
