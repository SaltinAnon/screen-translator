package com.saltin.screentranslator.ui

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.saltin.screentranslator.R
import com.saltin.screentranslator.core.ServiceState
import com.saltin.screentranslator.service.TranslationServiceController

/**
 * 只做一件事：在 Activity 上下文里弹出系统投屏授权对话框（QS 磁贴自己做不到），
 * 拿到 resultCode/data 后启动前台服务，然后立刻退出。
 */
class ProjectionRequestActivity : ComponentActivity() {

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                TranslationServiceController.start(this, result.resultCode, data)
            } else {
                Toast.makeText(this, R.string.toast_capture_denied, Toast.LENGTH_SHORT).show()
            }
            finishAndRemoveTask()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 磁贴是开关语义：已在运行则视为「关」
        if (ServiceState.running.value) {
            TranslationServiceController.stop(this)
            finishAndRemoveTask()
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.toast_need_overlay, Toast.LENGTH_LONG).show()
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                )
            }
            finishAndRemoveTask()
            return
        }

        val manager = getSystemService(MediaProjectionManager::class.java)
        if (manager == null) {
            Toast.makeText(this, R.string.err_no_token, Toast.LENGTH_SHORT).show()
            finishAndRemoveTask()
            return
        }

        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }
}
