package com.campusnet.autologin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 开机 / 应用更新后恢复后台监控（仅当用户开启过监控且已配置账号）。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val settings = SettingsStore.load(context)
        AppLog.init(context, settings.verboseLog)
        AppLog.info("收到 $action，monitoring=${settings.monitoring} configured=${settings.configured}")
        if (!settings.monitoring || !settings.configured) return
        try {
            PortalMonitorService.start(context)
        } catch (e: Exception) {
            AppLog.info("开机启动服务失败：${e.message}")
            // 部分 ROM 限制后台启动，忽略；用户打开 APP 后会恢复
        }
    }
}
