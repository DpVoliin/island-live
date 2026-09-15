package dev.dpvoliin.islandtimetable

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * 写入系统日历：把整学期课表写进一个**专用日历「岛课表」**。
 *
 * 为什么建专用日历、而不是写进用户的主日历：
 *  - 不想要了，在日历 App 里**删掉这一个日历**就全清了（不用一条条挑）✓
 *  - 不会和用户自己的日程混在一起 ✓
 *
 * 为什么**逐条写**、不用 RRULE 重复规则：
 * 课表的周次是"1-16周 / 单周 / 1-4,9-12周"这种**不规则**集合，RRULE 想表达它得拼
 * `INTERVAL` + 一堆 `EXDATE`，很容易写错；逐条写虽然事件多，但**一定和课表一致** ✓
 * （都落在专用日历里，不想看把该日历隐藏即可）。
 */
object CalendarExport {

    private const val ACCOUNT_NAME = "island-timetable"
    private const val CALENDAR_NAME = "岛课表"
    private const val CALENDAR_COLOR = 0xFF3F8E73.toInt()

    /** 没有周次信息时按"1~18 周"写（够覆盖一个学期）。 */
    private const val DEFAULT_WEEKS = 18
    private const val DEFAULT_CLASS_MINUTES = 45L

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    /** 写入整学期，返回写了几条；-1 表示失败（无权限/没学期起始日/系统日历不可用）。 */
    fun write(context: Context, timetable: Timetable): Int {
        val start = parseDate(timetable.termStartDate) ?: return -1
        val calendarId = ensureCalendar(context) ?: return -1
        clearEvents(context, calendarId)   // 先清上次写的，避免重复叠加
        val zone = ZoneId.systemDefault()
        var written = 0
        for (course in timetable.courses) {
            val day = course.dayOfWeek.coerceIn(1, 7)
            val begin = timetable.periods.firstOrNull { it.index == course.startPeriod }?.start
                ?: LocalTime.of(8, 0)
            val finish = timetable.periods.firstOrNull { it.index == course.startPeriod + course.span - 1 }?.end
                ?: begin.plusMinutes(DEFAULT_CLASS_MINUTES)
            val weeks = course.weeks.ifEmpty { (1..DEFAULT_WEEKS).toList() }
            val title = buildString {
                append(course.name)
                if (course.room.isNotBlank()) append(" · ").append(course.room)
            }
            for (week in weeks) {
                val date = start.plusWeeks((week - 1).toLong()).plusDays((day - 1).toLong())
                val beginMs = date.atTime(begin).atZone(zone).toInstant().toEpochMilli()
                val endMs = date.atTime(finish).atZone(zone).toInstant().toEpochMilli()
                val values = ContentValues().apply {
                    put(CalendarContract.Events.CALENDAR_ID, calendarId)
                    put(CalendarContract.Events.TITLE, title)
                    put(CalendarContract.Events.DESCRIPTION, course.descriptionText())
                    put(CalendarContract.Events.EVENT_LOCATION, course.room)
                    put(CalendarContract.Events.DTSTART, beginMs)
                    put(CalendarContract.Events.DTEND, endMs)
                    put(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
                    put(CalendarContract.Events.HAS_ALARM, 0)
                }
                val ok = runCatching {
                    context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) != null
                }.getOrDefault(false)
                if (ok) written++
            }
        }
        return written
    }

    /** 清掉本应用写入的全部事件（保留日历本身）。 */
    fun clearEvents(context: Context, calendarId: Long): Int {
        val where = "${CalendarContract.Events.CALENDAR_ID}=?"
        val args = arrayOf(calendarId.toString())
        return runCatching {
            context.contentResolver.delete(CalendarContract.Events.CONTENT_URI.buildUpon()
                .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
                .build(), where, args)
        }.getOrDefault(0)
    }

    /** 整份清除：删掉专用日历（连带里面的课）。 */
    fun clearAll(context: Context): Boolean = runCatching {
        val selection = "${CalendarContract.Calendars.ACCOUNT_NAME}=? AND ${CalendarContract.Calendars.ACCOUNT_TYPE}=?"
        val args = arrayOf(ACCOUNT_NAME, CalendarContract.ACCOUNT_TYPE_LOCAL)
        val uri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            .build()
        context.contentResolver.delete(uri, selection, args) >= 0
    }.getOrDefault(false)

    /** 找到（没有就建）专用日历，返回 id。 */
    private fun ensureCalendar(context: Context): Long? {
        val projection = arrayOf(CalendarContract.Calendars._ID)
        val selection =
            "${CalendarContract.Calendars.ACCOUNT_NAME}=? AND ${CalendarContract.Calendars.ACCOUNT_TYPE}=?"
        val args = arrayOf(ACCOUNT_NAME, CalendarContract.ACCOUNT_TYPE_LOCAL)
        runCatching {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI, projection, selection, args, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) return cursor.getLong(0)
            }
        }
        val values = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(CalendarContract.Calendars.NAME, CALENDAR_NAME)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CALENDAR_NAME)
            put(CalendarContract.Calendars.CALENDAR_COLOR, CALENDAR_COLOR)
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, ACCOUNT_NAME)
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
        }
        val uri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            .build()
        return runCatching { context.contentResolver.insert(uri, values) }
            .getOrNull()
            ?.let { ContentUris.parseId(it) }
    }

    private fun parseDate(text: String): LocalDate? = runCatching { LocalDate.parse(text) }.getOrNull()

    private fun Course.descriptionText(): String = buildString {
        append("岛课表写入")
        if (teacher.isNotBlank()) append(" · ").append(teacher)
    }
}
