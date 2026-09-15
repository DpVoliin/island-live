package dev.dpvoliin.islandtimetable

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.abs

/**
 * **ICS 日历（iCalendar）导入** —— 自研最小解析器，**零第三方依赖**。
 *
 * 为什么值得做：ICS 是"课表界的通用交换格式"，WakeUp 之类 App 都能导出；
 * 拿到 .ics 就等于拿到一份带**真实日期与重复规则**的课表。
 *
 * ## 支持范围（只做课表需要的部分）
 * | 项 | 支持 |
 * |---|---|
 * | 折行续行（75 字符换行） | ✅ 先还原 |
 * | `DTSTART` / `DTEND` | ✅ 含 `Z`(UTC) 与 `TZID=…` 时区换算成本地时间 |
 * | `RRULE` | ✅ `FREQ=WEEKLY` + `INTERVAL` / `COUNT` / `UNTIL` / `BYDAY` |
 * | `EXDATE` | ✅ 排除那些日期对应的周次 |
 * | `LOCATION` / `SUMMARY` | ✅（`SUMMARY` 里写成 `课程@教室` 也会拆） |
 * | 全天事件（只有日期没有时间） | ⛔ 跳过并提示（对不上节次） |
 * | `FREQ=DAILY` 等其它频率 | ⛔ 按"单次课"处理 |
 *
 * ## 学期起始日：**由文件自己决定**
 * ICS 里的重复规则是**绝对日期**，而本项目的周次是"相对学期第 1 周"。
 * 所以解析器取"文件里最早一节课所在周的周一"作为参考周一，
 * 并在结果里给出 [ImportResult.suggestedTermStart] —— 导入时用它当学期起始日，
 * 两边就完全对齐（用户不用再手填"现在是第几周"）。
 *
 * 复用：[TimetableCsv.colorFor] 配色、[WeekSet.MAX_WEEK] 周次上限、节次表用 [DemoTimetable.PERIODS]。
 */
object TimetableIcs {

    const val FORMAT = "ICS 日历 (.ics)"

    /** 时间对齐节次的容差（分钟）—— 导出方用的作息和本校节次表常常对不齐。 */
    private const val TOLERANCE_MINUTES = 30L

    private data class Event(
        val summary: String,
        val location: String,
        val start: LocalDateTime?,
        val end: LocalDateTime?,
        val rule: String?,
        val weekdays: List<Int>,
        val exDates: List<LocalDate>
    )

    fun parse(text: String): ImportResult {
        val lines = unfold(text)
        val events = readEvents(lines)
        if (events.isEmpty()) {
            return ImportResult(
                emptyList(),
                listOf("这个 ICS 里没有 VEVENT（日历事件）——确定导出时勾选了课表吗？"),
                format = FORMAT
            )
        }

        // 参考周一：所有事件里最早那天的所在周
        val earliest = events.mapNotNull { it.start?.toLocalDate() }.minOrNull()
        val refMonday = earliest?.minusDays((earliest.dayOfWeek.value - 1).toLong())

        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val courses = mutableListOf<Course>()
        val seen = mutableSetOf<String>()

        for (event in events) {
            val start = event.start
            if (start == null) {
                warnings.add("事件「${event.summary.ifBlank { "(无标题)" }}」没有可用时间（全天事件？），已跳过")
                continue
            }
            if (event.summary.isBlank()) {
                warnings.add("有一个事件没有标题，已跳过")
                continue
            }
            val (name, roomInTitle) = splitSummaryRoom(event.summary)
            val room = event.location.ifBlank { roomInTitle }
            val startPeriod = periodFor(start.toLocalTime())
            if (startPeriod == null) {
                errors.add("「$name」的上课时间 ${start.toLocalTime()} 对不上任何节次，已跳过")
                continue
            }
            val endTime = event.end?.toLocalTime() ?: start.toLocalTime().plusMinutes(45)
            val span = spanFor(start.toLocalTime(), endTime)

            var weeks = expandWeeks(event.rule, start.toLocalDate(), refMonday)
            if (event.exDates.isNotEmpty() && weeks.isNotEmpty() && refMonday != null) {
                val excluded = event.exDates.map { weekOf(it, refMonday) }.toSet()
                val before = weeks.size
                weeks = weeks.filterNot { it in excluded }
                if (weeks.size != before) {
                    warnings.add("「$name」按 EXDATE 去掉了 ${before - weeks.size} 周")
                }
            }
            if (event.rule == null) {
                warnings.add("「$name」没有重复规则，按只上一次处理")
            }

            val days = event.weekdays.ifEmpty { listOf(start.dayOfWeek.value) }
            for (day in days) {
                val key = listOf(name, day, startPeriod, span, weeks.joinToString(",")).joinToString("|")
                if (!seen.add(key)) continue
                courses.add(
                    Course(
                        id = "ics-" + key.hashCode().toUInt().toString(16),
                        name = name,
                        teacher = "",
                        room = room,
                        dayOfWeek = day,
                        startPeriod = startPeriod,
                        span = span,
                        weeks = weeks,
                        color = TimetableCsv.colorFor(name)
                    )
                )
            }
        }

        if (courses.isEmpty() && errors.isEmpty()) errors.add("没有解析出任何课程")
        return ImportResult(
            courses = courses,
            errors = errors,
            warnings = warnings,
            layout = ImportLayout.ROW,
            format = FORMAT,
            suggestedTermStart = refMonday?.toString().orEmpty()
        )
    }

    // ---------------------------------------------------------------- 行处理

    /** 还原折行：ICS 超过 75 字符会在行尾断开，续行以空格/制表符开头。 */
    private fun unfold(text: String): List<String> {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val out = mutableListOf<String>()
        var current = StringBuilder()
        for (line in normalized.split('\n')) {
            if ((line.startsWith(" ") || line.startsWith("\t")) && current.isNotEmpty()) {
                current.append(line.substring(1))
            } else {
                if (current.isNotEmpty()) out.add(current.toString())
                current = StringBuilder(line)
            }
        }
        if (current.isNotEmpty()) out.add(current.toString())
        return out
    }

    private fun readEvents(lines: List<String>): List<Event> {
        val events = mutableListOf<Event>()
        var inEvent = false
        var inAlarm = false
        var props = mutableMapOf<String, String>()

        for (raw in lines) {
            val line = raw.trim()
            if (line.equals("BEGIN:VEVENT", ignoreCase = true)) {
                inEvent = true
                inAlarm = false
                props = mutableMapOf()
                continue
            }
            if (line.equals("END:VEVENT", ignoreCase = true)) {
                if (inEvent) events.add(buildEvent(props))
                inEvent = false
                continue
            }
            if (!inEvent) continue
            // VALARM 里也有 DTSTART，别让它污染事件时间
            if (line.startsWith("BEGIN:VALARM", ignoreCase = true)) {
                inAlarm = true
                continue
            }
            if (line.startsWith("END:VALARM", ignoreCase = true)) {
                inAlarm = false
                continue
            }
            if (inAlarm) continue

            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val head = line.substring(0, idx)
            val name = head.substringBefore(';').uppercase()
            props[name] = line.substring(idx + 1)
            props["#PARAM#$name"] = head.substringAfter(';', "")
        }
        return events
    }

    private fun buildEvent(props: Map<String, String>): Event {
        val summary = unescape(props["SUMMARY"].orEmpty()).trim()
        val location = unescape(props["LOCATION"].orEmpty()).trim()
        val rule = props["RRULE"]
        return Event(
            summary = summary,
            location = location,
            start = parseDateTime(props["DTSTART"], props["#PARAM#DTSTART"]),
            end = parseDateTime(props["DTEND"], props["#PARAM#DTEND"]),
            rule = rule,
            weekdays = parseByDay(rule),
            exDates = parseExDates(props["EXDATE"], props["#PARAM#EXDATE"])
        )
    }

    // ---------------------------------------------------------------- 取值

    /** `课程@教室` 这种写法在导出文件里很常见，顺手拆开。 */
    private fun splitSummaryRoom(summary: String): Pair<String, String> {
        val at = summary.indexOf('@')
        if (at <= 0) return summary.trim() to ""
        val name = summary.substring(0, at).trim()
        val room = summary.substring(at + 1).trim()
        return if (name.isEmpty()) summary.trim() to "" else name to room
    }

    private fun unescape(value: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                when (val next = value[i + 1]) {
                    'n', 'N' -> sb.append(' ')
                    ',', ';', '\\', ':' -> sb.append(next)
                    else -> sb.append(next)
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    /** `20260907T080000Z` / `20260907T080000`（可带 `TZID=Asia/Shanghai`）。全天事件返回 null。 */
    private fun parseDateTime(value: String?, params: String?): LocalDateTime? {
        if (value.isNullOrBlank()) return null
        val raw = value.trim()
        val zulu = raw.endsWith("Z", ignoreCase = true)
        val digits = raw.removeSuffix("Z").removeSuffix("z").replace("T", "")
        if (digits.length < 8 || !digits.take(8).all { it.isDigit() }) return null
        val date = runCatching {
            LocalDate.of(
                digits.substring(0, 4).toInt(),
                digits.substring(4, 6).toInt(),
                digits.substring(6, 8).toInt()
            )
        }.getOrNull() ?: return null
        if (digits.length < 12) return null // 只有日期 → 全天事件

        val time = runCatching {
            LocalTime.of(digits.substring(8, 10).toInt(), digits.substring(10, 12).toInt(), 0)
        }.getOrNull() ?: return null
        val local = LocalDateTime.of(date, time)

        return when {
            zulu -> local.atZone(ZoneId.of("UTC"))
                .withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()

            !params.isNullOrBlank() && params.contains("TZID=") -> {
                val tz = params.substringAfter("TZID=").substringBefore(';').trim().trim('"')
                runCatching {
                    local.atZone(ZoneId.of(tz))
                        .withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()
                }.getOrDefault(local)
            }

            else -> local
        }
    }

    private fun parseExDates(value: String?, params: String?): List<LocalDate> {
        if (value.isNullOrBlank()) return emptyList()
        return value.split(',').mapNotNull { parseDateTime(it.trim(), params)?.toLocalDate() }
    }

    /** `BYDAY=MO,WE` → [1,3]。 */
    private fun parseByDay(rule: String?): List<Int> {
        if (rule.isNullOrBlank()) return emptyList()
        val part = rule.split(';').firstOrNull { it.startsWith("BYDAY=", ignoreCase = true) } ?: return emptyList()
        return part.substringAfter('=').split(',')
            .mapNotNull { token ->
                when (token.trim().takeLast(2).uppercase()) {
                    "MO" -> 1
                    "TU" -> 2
                    "WE" -> 3
                    "TH" -> 4
                    "FR" -> 5
                    "SA" -> 6
                    "SU" -> 7
                    else -> null
                }
            }
            .distinct()
            .sorted()
    }

    /**
     * 展开 `RRULE` 成周次列表（相对参考周一）。
     *
     * - 给 `COUNT` → 明确列表（第 1 次 + 每隔 `INTERVAL` 周）
     * - 给 `UNTIL` → 展开到那个日期为止
     * - **都没给 → 返回空列表**（本项目里"空 = 每周"，比乱猜一个周数更保守）
     */
    private fun expandWeeks(rule: String?, start: LocalDate, refMonday: LocalDate?): List<Int> {
        val first = if (refMonday == null) 1 else weekOf(start, refMonday)
        if (rule.isNullOrBlank()) return listOf(first)

        val map = rule.split(';').mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) null else part.substring(0, i).trim().uppercase() to part.substring(i + 1).trim()
        }.toMap()

        if (!map["FREQ"].equals("WEEKLY", ignoreCase = true)) return listOf(first)

        val interval = map["INTERVAL"]?.toIntOrNull()?.coerceIn(1, 30) ?: 1
        val count = map["COUNT"]?.toIntOrNull()
        val until = map["UNTIL"]?.let { parseDateTime(it, null)?.toLocalDate() }

        val result = linkedSetOf<Int>()
        when {
            count != null && count > 0 -> {
                var week = first
                repeat(count.coerceAtMost(64)) {
                    if (week in 1..WeekSet.MAX_WEEK) result.add(week)
                    week += interval
                }
            }

            until != null && refMonday != null -> {
                var week = first
                while (week in 1..WeekSet.MAX_WEEK) {
                    val monday = refMonday.plusWeeks((week - 1).toLong())
                    if (monday.isAfter(until)) break
                    result.add(week)
                    week += interval
                }
            }

            else -> return emptyList()
        }
        return result.toList()
    }

    private fun weekOf(date: LocalDate, refMonday: LocalDate): Int =
        (((Duration.between(refMonday.atStartOfDay(), date.atStartOfDay()).toDays() / 7).toInt()) + 1)
            .coerceAtLeast(1)

    // ---------------------------------------------------------------- 时间 → 节次

    /**
     * 时间 → 节次。
     *
     * 现实里 ICS 的时间常和本校节次表**对不齐**（导出方按自己的作息导出，例如它 10:00 上课，
     * 而本校第 3 节是 09:40），所以规则是：
     * ① 先看这个时间**落在哪一节的时间区间内**（最符合直觉）
     * ② 都不落在区间内，再取开始时间最接近的一节（容差 [TOLERANCE_MINUTES] 分钟）
     */
    private fun periodFor(time: LocalTime): Int? {
        val periods = DemoTimetable.PERIODS
        periods.firstOrNull { !time.isBefore(it.start) && time.isBefore(it.end) }?.let { return it.index }
        val best = periods.minByOrNull { abs(Duration.between(it.start, time).toMinutes()) } ?: return null
        val diff = abs(Duration.between(best.start, time).toMinutes())
        return if (diff <= TOLERANCE_MINUTES) best.index else null
    }

    /** 时长覆盖到哪一节：数"开始时间早于下课时间"的连续节次。 */
    private fun spanFor(start: LocalTime, end: LocalTime): Int {
        val startIndex = periodFor(start) ?: return 1
        var span = 1
        for (period in DemoTimetable.PERIODS) {
            if (period.index <= startIndex) continue
            if (!period.start.isBefore(end)) break
            span++
        }
        return span.coerceAtLeast(1)
    }

    /** 星期数字（1=周一 … 7=周日）—— 预览界面用。 */
    fun dayLabel(day: Int): String =
        DemoTimetable.DAY_LABELS.getOrNull(day - 1) ?: DayOfWeek.of(day.coerceIn(1, 7)).name
}
