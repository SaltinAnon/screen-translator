package com.saltin.screentranslator.service

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.saltin.screentranslator.R
import com.saltin.screentranslator.core.ServiceState
import com.saltin.screentranslator.ui.ProjectionRequestActivity

/**
 * 阶段四：快捷设置磁贴。
 *
 * 约束：QS 磁贴运行在系统 UI 进程触发的回调里，**无法直接弹 MediaProjection 授权对话框**
 * （它必须由 Activity 发起）。所以这里只负责「开关意图」：
 * - 已在运行 → 直接停止；
 * - 未运行 → 用 startActivityAndCollapse(PendingIntent) 拉起一个透明 Activity 去申请授权，
 *   拿到授权后再启动前台服务。
 */
class TranslationTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onTileAdded() {
        super.onTileAdded()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        if (ServiceState.running.value) {
            TranslationServiceController.stop(this)
        } else {
            launchProjectionRequest()
        }
        // 状态会在监听回调里刷新，这里先乐观更新一次
        qsTile?.apply {
            state = if (ServiceState.running.value) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        tile.state = if (ServiceState.running.value) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (ServiceState.running.value) "运行中" else "已停止"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_translate)
            }
        }
        tile.label = getString(R.string.tile_label)
        tile.updateTile()
    }

    private fun launchProjectionRequest() {
        val intent = Intent(this, ProjectionRequestActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
