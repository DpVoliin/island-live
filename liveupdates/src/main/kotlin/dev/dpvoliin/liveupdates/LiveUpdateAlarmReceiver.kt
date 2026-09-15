package dev.dpvoliin.liveupdates

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 库内部使用：超时兜底闹钟到点时，把对应的「岛」撤下。
 *
 * 之所以不只用进程内的 Handler：上一条课的通知如果不撤，会变成划不掉的常驻通知，很脏。
 * 走 AlarmManager 后，用户杀掉 App 进程也能准时撤下。
 */
class LiveUpdateAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            // 到期撤下
            LiveUpdateManager.ACTION_REAP -> {
                val id = intent.getStringExtra(LiveUpdateManager.EXTRA_ID) ?: return
                LiveUpdateManager.get(context).finish(id)
            }
            // 看门狗：通知被划掉/被系统清了就补回来，然后排下一次
            LiveUpdateManager.ACTION_KEEPALIVE -> LiveUpdateManager.get(context).onKeepAlive()
        }
    }
}
