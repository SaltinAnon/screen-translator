package com.saltin.screentranslator.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import com.saltin.screentranslator.R
import com.saltin.screentranslator.core.SettingsStore
import com.saltin.screentranslator.service.TranslationServiceController

class MainActivity : ComponentActivity() {

    private lateinit var settingsStore: SettingsStore

    /** 投屏授权 → 启动前台服务 */
    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                TranslationServiceController.start(this, result.resultCode, data)
                Toast.makeText(this, R.string.toast_started, Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, R.string.toast_capture_denied, Toast.LENGTH_SHORT).show()
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        settingsStore = SettingsStore.get(this)
        requestNotificationPermissionIfNeeded()

        setContent {
            var showTutorial by rememberSaveable { mutableStateOf(false) }

            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF1B5E9C),
                    onPrimary = Color.White,
                    primaryContainer = Color(0xFFD3E4FF),
                    onPrimaryContainer = Color(0xFF0B2E52),
                    secondary = Color(0xFF2E7D5B),
                    background = Color(0xFFF6F7FB),
                    surface = Color.White,
                    surfaceVariant = Color(0xFFE7EBF3),
                    error = Color(0xFFB3261E),
                ),
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    if (showTutorial) {
                        BackHandler { showTutorial = false }
                        TutorialScreen(onBack = { showTutorial = false })
                    } else {
                        SettingsScreen(
                            settingsStore = settingsStore,
                            onStart = ::requestScreenCapture,
                            onStop = { TranslationServiceController.stop(this) },
                            onTranslateNow = { TranslationServiceController.translateNow(this) },
                            onClearOverlay = { TranslationServiceController.clear(this) },
                            onRequestOverlayPermission = ::requestOverlayPermission,
                            onRequestNotificationPermission = ::requestNotificationPermissionIfNeeded,
                            onOpenTutorial = {
                                settingsStore.update { it.copy(tutorialSeen = true) }
                                showTutorial = true
                            },
                        )
                    }
                }
            }
        }
    }

    private fun requestScreenCapture() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.toast_need_overlay, Toast.LENGTH_LONG).show()
            requestOverlayPermission()
            return
        }
        val manager = getSystemService(MediaProjectionManager::class.java)
        if (manager == null) {
            Toast.makeText(this, R.string.err_no_token, Toast.LENGTH_SHORT).show()
            return
        }
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "已具备悬浮窗权限", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
            )
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
