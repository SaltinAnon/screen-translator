package com.saltin.screentranslator

import android.app.Application
import com.saltin.screentranslator.core.SettingsStore
import com.saltin.screentranslator.util.NotificationHelper

class ScreenTranslatorApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // 通知渠道要尽早建好，前台服务启动时才能立刻挂上常驻通知
        NotificationHelper.ensureChannel(this)
        SettingsStore.get(this)
    }
}
