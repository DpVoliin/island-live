package dev.dpvoliin.islandtimetable

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * 桌面小组件：**下一节课 + 实时倒计时**，不打开 App 就能看。
 *
 * 关键设计：
 *  - 倒计时用 [android.widget.Chronometer]（倒计时模式）—— 它由系统自己走秒，
 *    所以**不需要频繁刷新组件**（小组件刷新最低 30 分钟一次，靠刷新是做不出秒级倒计时的）。
 *  - 只显示"下一节 + 今天还有几节"，不做列表：RemoteViews 不支持自定义列表，
 *    要做滚动列表得引 RemoteViewsService（复杂度不值当）。
 *  - 内容来源与主屏一致（[DemoTimetable]），所以导入/换周次后组件跟着变。
 *
 * 刷新时机：放置时、回到 App 时、开机/看门狗时，以及系统每 30 分钟一次。
 */
class TimetableWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val views = build(context)
        appWidgetIds.forEach { id -> appWidgetManager.updateAppWidget(id, views) }
    }

    companion object {

        /** 刷新所有已放置的小组件（回到前台、开机、看门狗时调用）。 */
        fun refreshAll(context: Context) {
            runCatching {
                val appContext = context.applicationContext
                val manager = AppWidgetManager.getInstance(appContext) ?: return
                val ids = manager.getAppWidgetIds(
                    ComponentName(appContext, TimetableWidget::class.java)
                )
                if (ids.isEmpty()) return
                val views = build(appContext)
                ids.forEach { id -> manager.updateAppWidget(id, views) }
            }
        }

        /** 组装组件视图（放置时与刷新时共用）。 */
        fun build(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_next_class)
            val tap = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            views.setOnClickPendingIntent(R.id.widgetRoot, tap)

            val upcoming = runCatching { DemoTimetable.upcoming() }.getOrNull()
            if (upcoming == null) {
                views.setTextViewText(R.id.widgetCourse, context.getString(R.string.widget_empty))
                views.setTextViewText(R.id.widgetMeta, "")
                views.setViewVisibility(R.id.widgetCountdown, View.GONE)
                views.setViewVisibility(R.id.widgetMore, View.GONE)
                return views
            }

            val course = upcoming.course
            val day = DemoTimetable.BUILTIN_DAY_LABELS
                .getOrElse(course.dayOfWeek - 1) { "周${course.dayOfWeek}" }
            val span = if (course.span > 1) {
                "第${course.startPeriod}–${course.startPeriod + course.span - 1}节"
            } else {
                "第${course.startPeriod}节"
            }
            views.setTextViewText(R.id.widgetCourse, course.name)
            views.setTextViewText(
                R.id.widgetMeta,
                "$day $span" + if (course.room.isBlank()) "" else " · ${course.room}"
            )

            val startAt = upcoming.start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val now = System.currentTimeMillis()
            views.setViewVisibility(R.id.widgetCountdown, View.VISIBLE)
            when {
                // 还没到上课：倒计时
                startAt > now -> {
                    views.setChronometerCountDown(R.id.widgetCountdown, true)
                    views.setChronometer(R.id.widgetCountdown, startAt, null, true)
                }
                // 正在上课（按 45 分钟估）：正计时
                now < startAt + 45 * 60_000L -> {
                    views.setChronometerCountDown(R.id.widgetCountdown, false)
                    views.setChronometer(R.id.widgetCountdown, startAt, null, true)
                }
                else -> views.setViewVisibility(R.id.widgetCountdown, View.GONE)
            }

            val todayLeft = runCatching {
                DemoTimetable.coursesOn(LocalDateTime.now().dayOfWeek.value, DemoTimetable.viewWeek).size
            }.getOrDefault(0)
            views.setTextViewText(
                R.id.widgetMore,
                if (todayLeft > 0) context.getString(R.string.widget_today_left, todayLeft) else ""
            )
            return views
        }

    }
}
