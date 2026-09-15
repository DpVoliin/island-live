package dev.dpvoliin.islandtimetable

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 上课提醒的接收端：一个 receiver 同时管"闹钟到点"和"开机后重排"。
 *
 * 开机重排很关键：闹钟不会跨重启存活，不重排就等于用户重启一次手机、提醒就永久失效了。
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AutoSilent.ACTION_ON -> {
                AutoSilent.muteNow(context)
                return
            }

            AutoSilent.ACTION_OFF -> {
                AutoSilent.restore(context)
                AutoSilent.scheduleNext(context)   // 排下一节
                return
            }

            StickyNotification.ACTION_REPOST,
            StickyNotification.ACTION_STICKY_CHECK,
            StickyNotification.ACTION_REVIVE,
            Intent.ACTION_USER_PRESENT,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                ClassReminder.reschedule(context)
                // 常驻通知也一起补回来（开关开着才发）；看门狗到点也会把闹钟再排上
                StickyNotification.repostIfEnabled(context)
                StickyNotification.scheduleWatchdog(context)
                // 小组件也刷一下（开机/看门狗时课表可能变了）
                TimetableWidget.refreshAll(context)
                AutoSilent.scheduleNext(context)
            }

            ClassReminder.ACTION_FIRE -> {
                // 到点提醒时，常驻通知的内容也该从"下一节"切成"正在上课"
                StickyNotification.repostIfEnabled(context)
                ClassReminder.fire(
                    context,
                    ClassReminder.ReminderInfo(
                        name = intent.getStringExtra("name").orEmpty(),
                        room = intent.getStringExtra("room").orEmpty(),
                        periods = intent.getStringExtra("periods").orEmpty(),
                        day = intent.getStringExtra("day").orEmpty()
                    )
                )
            }
        }
    }
}
