package dev.dpvoliin.liveupdates

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * **保活用的前台服务**：让本进程以「前台」优先级活着，系统回收后台进程时不会先杀它。
 *
 * 关键设计：**不额外占一条通知** —— 前台服务要的那条通知，就是「岛」本身
 * （同一个 id，由 [LiveUpdateManager] 渲染）。所以你看不到"多出来一条常驻通知"。
 *
 * `START_STICKY`：被杀之后系统会尽量把它拉回来；拉回来时没有 Intent，
 * 就从 [LiveUpdateStore] 的持久化状态重建那条通知（所以状态必须存全量）。
 *
 * 注意：**用户「强行停止」应用后不会自动恢复**（这是 Android 的设计，任何应用都做不到，
 * 只有系统级白名单能豁免）。重启后同理 —— 我们不会自己冒出来。
 */
class LiveUpdateService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelfSafely()
            return START_NOT_STICKY
        }
        val notification = LiveUpdateManager.get(this).currentNotification()
        if (notification == null) {
            // 没有活跃的岛：不需要前台服务
            stopSelfSafely()
            return START_NOT_STICKY
        }
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(LiveUpdateManager.NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(LiveUpdateManager.NOTIFICATION_ID, notification)
            }
        }.onFailure { Log.w(TAG, "startForeground 失败：${it.javaClass.simpleName}: ${it.message}") }.isSuccess
        return if (ok) START_STICKY else { stopSelf(); START_NOT_STICKY }
    }

    private fun stopSelfSafely() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    companion object {
        private const val TAG = "LiveUpdateService"
        const val ACTION_START = "dev.dpvoliin.liveupdates.action.START_FOREGROUND"
        const val ACTION_STOP = "dev.dpvoliin.liveupdates.action.STOP_FOREGROUND"

        /** 起服务。只在 App 处于前台（用户操作）时调用 —— Android 12+ 禁止后台起前台服务。 */
        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, LiveUpdateService::class.java).setAction(ACTION_START)
                context.startForegroundService(intent)
            }.onFailure { Log.w(TAG, "起前台服务失败（后台限制？）：${it.javaClass.simpleName}: ${it.message}") }
        }

        fun stop(context: Context) {
            runCatching {
                context.startService(Intent(context, LiveUpdateService::class.java).setAction(ACTION_STOP))
            }.onFailure {
                // 起 service 失败就直接让它自己停
                runCatching { context.stopService(Intent(context, LiveUpdateService::class.java)) }
            }
        }
    }
}
