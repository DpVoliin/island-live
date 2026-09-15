package dev.dpvoliin.islandtimetable

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * 上课自动静音：**上课时切到"仅闹钟"（勿扰），下课后自动恢复**。
 *
 * 为什么用「勿扰」而不是直接调音量：
 *  - Android 6 起，改静音/勿扰需要**勿扰访问权限**（`ACCESS_NOTIFICATION_POLICY`），
 *    用户必须手动在系统设置里授予一次 —— 没有它调用 `setRingerMode` 会被系统忽略；
 *  - 勿扰（`setInterruptionFilter`）是标准做法，能保留闹钟滴答（不会让你错过闹钟），
 *    而且恢复的是**用户原来的模式**，不会把人家本来开的静音给关掉。
 *
 * 排期方式：一次只排**最近的一条**上课闹钟，触发后再排下一条（触发式链式排期），
 * 这样不用一次排两周几十个闹钟，也不会因为课表变化留下陈旧闹钟。
 */
object AutoSilent {

    const val ACTION_ON = "dev.dpvoliin.islandtimetable.AUTO_SILENT_ON"
    const val ACTION_OFF = "dev.dpvoliin.islandtimetable.AUTO_SILENT_OFF"

    private const val PREFS = "island_timetable"
    private const val KEY_ENABLED = "auto_silent"
    private const val KEY_PREV_FILTER = "auto_silent_prev_filter"
    private const val REQ_ON = 4301
    private const val REQ_OFF = 4302

    /** 下课恢复的兜底时长：课上到最晚不超过这么久（防止下课闹钟丢了导致一直勿扰）。 */
    private const val MAX_CLASS_MINUTES = 200L

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) {
            scheduleNext(context)
        } else {
            cancelAll(context)
            restore(context)
        }
    }

    /** 是否已授予「勿扰访问权限」。 */
    fun hasAccess(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java)
            ?.isNotificationPolicyAccessGranted == true

    /** 跳到系统的「勿扰访问权限」页，让用户授权（一次性）。 */
    fun requestAccess(context: Context) {
        runCatching {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** 排下一条"上课静音"闹钟（只排最近一条，触发后再排下一条）。 */
    fun scheduleNext(context: Context) {
        if (!isEnabled(context)) return
        val upcoming = runCatching { DemoTimetable.upcoming() }.getOrNull() ?: return
        // 直接用它算好的开始时刻，和主屏/提醒完全一致（不用自己再算一遍）
        val startAt = upcoming.start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (startAt <= System.currentTimeMillis()) return
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching {
            alarm.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, startAt, pending(context, ACTION_ON, REQ_ON)
            )
        }
    }

    /** 上课时刻：切到「仅闹钟」，并排好下课的恢复闹钟。 */
    fun muteNow(context: Context) {
        if (!isEnabled(context) || !hasAccess(context)) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        // 记住用户原来的模式，下课后原样恢复
        runCatching {
            prefs(context).edit()
                .putInt(KEY_PREV_FILTER, manager.currentInterruptionFilter)
                .apply()
            manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
        }
        scheduleRestore(context)
    }

    /** 下课：恢复用户原来的模式。 */
    fun restore(context: Context) {
        if (!hasAccess(context)) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val previous = prefs(context).getInt(KEY_PREV_FILTER, NotificationManager.INTERRUPTION_FILTER_ALL)
        runCatching {
            manager.setInterruptionFilter(
                if (previous == NotificationManager.INTERRUPTION_FILTER_NONE ||
                    previous == NotificationManager.INTERRUPTION_FILTER_ALARMS ||
                    previous == NotificationManager.INTERRUPTION_FILTER_PRIORITY ||
                    previous == NotificationManager.INTERRUPTION_FILTER_ALL
                ) previous else NotificationManager.INTERRUPTION_FILTER_ALL
            )
        }
    }

    private fun scheduleRestore(context: Context) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        val upcoming = runCatching { DemoTimetable.upcoming() }.getOrNull()
        val endAt = upcoming?.let { up ->
            val duration = java.time.Duration
                .between(periodStart(up.course), courseEnd(up.course)).toMillis()
            up.start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() + duration
        } ?: (System.currentTimeMillis() + MAX_CLASS_MINUTES * 60_000L)
        runCatching {
            alarm.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, endAt, pending(context, ACTION_OFF, REQ_OFF)
            )
        }
    }

    private fun cancelAll(context: Context) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching { alarm.cancel(pending(context, ACTION_ON, REQ_ON)) }
        runCatching { alarm.cancel(pending(context, ACTION_OFF, REQ_OFF)) }
    }

    private fun pending(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    // ---------------------------------------------------------------- 时刻计算

    private fun periodStart(course: Course): LocalTime =
        DemoTimetable.PERIODS.firstOrNull { it.index == course.startPeriod }?.start ?: LocalTime.of(8, 0)

    private fun courseEnd(course: Course): LocalTime = DemoTimetable.PERIODS
        .firstOrNull { it.index == course.startPeriod + course.span - 1 }?.end
        ?: periodStart(course).plusMinutes(45)

}

