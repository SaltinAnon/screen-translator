package com.saltin.screentranslator.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.saltin.screentranslator.R
import com.saltin.screentranslator.service.TranslationForegroundService
import com.saltin.screentranslator.ui.MainActivity

/**
 * 前台服务常驻通知。
 *
 * Android 14+ 硬性合规：以 mediaProjection 类型运行的 FGS **必须**在通知栏可见，
 * 否则 startForeground 会抛 ForegroundServiceStartNotAllowedException / 被系统强制停服。
 *
 * 通知里放的三个按钮，其实是很多用户唯一的控制入口（悬浮球被挡住时特别有用）：
 * 「翻译一次」「清空」「停止」。
 */
object NotificationHelper {

    const val CHANNEL_ID = "screen_translation"
    const val NOTIFICATION_ID = 0x5101

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notif_channel_desc)
            setShowBadge(false)
            enableVibration(false)
            enableLights(false)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        manager.createNotificationChannel(channel)
    }

    fun build(context: Context, text: String, manual: Boolean): Notification {
        val openApp = PendingIntent.getActivity(
            context,
            REQ_OPEN,
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stop = PendingIntent.getService(
            context,
            REQ_STOP,
            Intent(context, TranslationForegroundService::class.java)
                .setAction(TranslationForegroundService.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val translateNow = PendingIntent.getService(
            context,
            REQ_NOW,
            Intent(context, TranslationForegroundService::class.java)
                .setAction(TranslationForegroundService.ACTION_TRANSLATE_NOW),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val clear = PendingIntent.getService(
            context,
            REQ_CLEAR,
            Intent(context, TranslationForegroundService::class.java)
                .setAction(TranslationForegroundService.ACTION_CLEAR),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_translate)
            .setContentTitle(context.getString(R.string.notif_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp)

        // 手动模式才需要「翻译一次」按钮；连续模式下它没有意义
        if (manual) {
            builder.addAction(0, context.getString(R.string.action_translate_now), translateNow)
        }
        builder.addAction(0, context.getString(R.string.action_clear), clear)
        builder.addAction(0, context.getString(R.string.action_stop), stop)

        return builder.build()
    }

    private const val REQ_OPEN = 0x51
    private const val REQ_STOP = 0x52
    private const val REQ_NOW = 0x53
    private const val REQ_CLEAR = 0x54
}
