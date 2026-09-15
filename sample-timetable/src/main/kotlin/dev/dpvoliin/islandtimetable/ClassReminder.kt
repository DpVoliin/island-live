package dev.dpvoliin.islandtimetable

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 上课提醒。
 *
 * ## 设计：永远只排**最近一节课**的一个闹钟
 * 而不是给整学期排一堆 —— 响完之后立刻排下一节。这样"课表改了 / 翻周了 / 删了课 / 开关了提醒"
 * 只要重排一次就够，永远不会留下过期闹钟（也不会像批量排闹钟那样越排越多）。
 *
 * ## 精确度
 * Android 12+ 想用**精确**闹钟需要用户在「闹钟与提醒」里授权；没授权就退化成"可能延迟几分钟"的
 * 闹钟（[needsExactAlarmPermission] 用来在界面上给引导），功能不丢。
 *
 * 这个能力**不依赖任何厂商放行**，全机型可用 —— 与实时通知那套无关，是课表本身该有的功能。
 */
object ClassReminder {

    const val ACTION_FIRE = "dev.dpvoliin.islandtimetable.REMINDER_FIRE"

    private const val CHANNEL_ID = "class_reminder"
    /** 震动通道单独建一个：Android 8+ 通道建好后**改不了**是否震动，只能用两个通道区分。 */
    private const val CHANNEL_ID_VIBRATE = "class_reminder_vibrate"
    private const val CHANNEL_NAME = "上课提醒"
    private const val KEY_VIBRATE = "reminder_vibrate"
    private const val REQUEST_CODE = 4201
    private const val NOTIFICATION_ID = 4202
    private const val PREFS = "reminder"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LEAD = "lead_minutes"

    /** 默认提前 10 分钟。 */
    const val DEFAULT_LEAD = 10

    /** 可选的提前量（分钟）—— 界面上的四个按钮。 */
    val LEAD_OPTIONS = listOf(0, 5, 10, 15)

    // ------------------------------------------------------------ 设置

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        reschedule(context)
    }

    fun leadMinutes(context: Context): Int = prefs(context).getInt(KEY_LEAD, DEFAULT_LEAD).coerceIn(0, 60)

    fun setLeadMinutes(context: Context, minutes: Int) {
        prefs(context).edit().putInt(KEY_LEAD, minutes.coerceIn(0, 60)).apply()
        reschedule(context)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ------------------------------------------------------------ 权限

    /** Android 12+ 未授权「闹钟与提醒」时，精确闹钟不可用（只能退化为不精确）。 */
    fun needsExactAlarmPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    // ------------------------------------------------------------ 排闹钟

    /** 下一次提醒的触发时刻（没有下一节课 / 没开提醒时返回 null）。 */
    fun nextTriggerAt(context: Context, now: LocalDateTime = LocalDateTime.now()): Long? {
        if (!isEnabled(context)) return null
        val upcoming = DemoTimetable.upcoming(now) ?: return null
        return upcoming.start
            .minusMinutes(leadMinutes(context).toLong())
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }

    /**
     * 重排闹钟。**课表变化、设置变化、开机、提醒响过之后都要调它**（幂等，随便调）。
     */
    fun reschedule(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java)
        // 先撤掉旧的（同一个 requestCode + component + action，所以是同一个 PendingIntent）
        manager.cancel(pendingIntent(context, null))
        if (!isEnabled(context)) return

        val upcoming = DemoTimetable.upcoming() ?: return
        val triggerAt = upcoming.start
            .minusMinutes(leadMinutes(context).toLong())
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        // 已经过了提前量（例如刚打开开关）：5 秒后补一条，别让用户觉得"没生效"
        val at = if (triggerAt <= System.currentTimeMillis()) {
            System.currentTimeMillis() + 5_000L
        } else {
            triggerAt
        }
        val extras = Bundle().apply {
            putString(KEY_NAME, upcoming.course.name)
            putString(KEY_ROOM, upcoming.course.room)
            putString(KEY_PERIODS, periodsText(upcoming.course))
            putString(KEY_DAY, DemoTimetable.dayLabel(upcoming.course.dayOfWeek))
        }
        val pending = pendingIntent(context, extras)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()) {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        } else {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }

    private const val KEY_NAME = "name"
    private const val KEY_ROOM = "room"
    private const val KEY_PERIODS = "periods"
    private const val KEY_DAY = "day"

    private fun pendingIntent(context: Context, extras: Bundle?): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_FIRE
            extras?.let { putExtras(it) }
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun periodsText(course: Course): String =
        if (course.span > 1) {
            "第${course.startPeriod}–${course.startPeriod + course.span - 1}节"
        } else {
            "第${course.startPeriod}节"
        }

    // ------------------------------------------------------------ 响铃

    /** 到点了：发一条通知 + 立刻排下一节。 */
    fun fire(context: Context, info: ReminderInfo) {
        // 信息为空（比如是很久以前留下的闹钟）：现算一次，尽量别发空通知
        val name = info.name.ifBlank { DemoTimetable.upcoming()?.course?.name.orEmpty() }
        if (name.isNotBlank()) {
            ensureChannel(context)
            val lead = leadMinutes(context)
            // 与常驻通知同一形状：标题 = 状态 · 课程名；内容 = 星期 第N节 · 教室
            val detail = NotifyText.where(info.day, info.periods, info.room)
            val tap = PendingIntent.getActivity(
                context,
                REQUEST_CODE,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val channelId = if (isVibrateEnabled(context)) CHANNEL_ID_VIBRATE else CHANNEL_ID
            val notification = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_class)
                .setContentTitle(NotifyText.title(NotifyText.statusBefore(lead), name))
                .setContentText(detail)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setContentIntent(tap)
                .build()
            if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                runCatching {
                    val nm = NotificationManagerCompat.from(context)
                    // 「重新弹出一个通知」：先撤掉再发 —— 同 id 已存在时直接 notify 不会再弹横幅
                    nm.cancel(NOTIFICATION_ID)
                    nm.notify(NOTIFICATION_ID, notification)
                }
            }
        }
        reschedule(context) // 接着排下一节
    }

    /** 是否震动（设置里的开关）。默认开。 */
    fun isVibrateEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_VIBRATE, true)

    fun setVibrateEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_VIBRATE, enabled).apply()
        ensureChannel(context)
    }

    /**
     * 建两个通道：**静音**与**震动**。
     *
     * 原因：Android 8+ 的通道一旦创建，`是否震动`就改不了 —— 想用开关控制只能拆成两个通道，
     * 发通知时按开关选。两个通道都**不响铃**（本应用不做响铃相关）。
     */
    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "上课前提醒（只弹通知，不响铃）"
                    setSound(null, null)
                    enableVibration(false)
                }
            )
        }
        if (manager.getNotificationChannel(CHANNEL_ID_VIBRATE) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID_VIBRATE,
                    "$CHANNEL_NAME（震动）",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "上课前提醒（弹通知 + 震动，不响铃）"
                    setSound(null, null)
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 180, 120, 180)
                }
            )
        }
    }

    /** 通知内容（跟着 PendingIntent 一起送达，避免"到点时再算"算错课）。 */
    data class ReminderInfo(
        val name: String,
        val room: String,
        val periods: String,
        val day: String = ""
    )
}
