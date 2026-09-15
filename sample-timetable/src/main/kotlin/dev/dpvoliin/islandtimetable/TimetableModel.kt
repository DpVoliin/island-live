package dev.dpvoliin.islandtimetable

/**
 * 一份完整的课表 —— 导入的结果、也是落盘的对象。
 *
 * 设计原则：**一切都能从 JSON 还原**（[TimetableStore] 存的就是它），
 * 所以导入格式（CSV / Excel / ICS / 教务）只是"把行变成 [Course]"的适配器，彼此不干扰。
 */
data class Timetable(
    val courses: List<Course>,
    val periods: List<Period> = DemoTimetable.BUILTIN_PERIODS,
    val dayLabels: List<String> = DemoTimetable.BUILTIN_DAY_LABELS,
    /** 学期第一周的周一（ISO 格式 yyyy-MM-dd）；空 = 未设置，[DemoTimetable.currentWeek] 会按 1 处理。 */
    val termStartDate: String = "",
    /** 数据来源：demo / csv / xlsx / ics / jwxt —— 用于界面提示与排障。 */
    val source: String = "demo",
    val importedAt: Long = 0L
) {
    val isDemo: Boolean get() = source == "demo"

    /** 课程实际占用的最大节次（用于校验 span 是否越界）。 */
    val maxPeriod: Int
        get() = courses.maxOfOrNull { it.startPeriod + it.span - 1 } ?: periods.size
}

/**
 * 周次集合的工具：解析 / 输出 / 人类可读描述。
 *
 * 支持写法（教务系统和 Excel 里最常见的几种）：
 * - `1-16` 连续
 * - `1,3,5,7` 离散
 * - `1-16单` / `1-16双` 单双周
 * - `3-18(单)` 括号写法
 * - 空 → 视为"每周都上"（返回空表，业务侧按"不限周次"处理）
 */
object WeekSet {

    const val MAX_WEEK = 30

    fun parse(raw: String): List<Int> {
        val text = raw.trim()
            .replace("（", "(")
            .replace("）", ")")
            .replace("，", ",")
            .replace("、", ",")
            .replace("；", ",")
            .replace(";", ",")
            .replace("－", "-")
            .replace("—", "-")
            .replace("~", "-")
            .replace("至", "-")
        if (text.isEmpty()) return emptyList()

        val result = linkedSetOf<Int>()
        // **逐段解析**：单/双 只作用于它所在的那一段。
        // 踩过：老实现把单双当"整串标记"——`3周,10-12周(双),15周` 里只要出现一个「双」，
        // 就会把所有段都过滤成双周（实测被砍成 [10,12]，或者干脆解析失败退化成「每周」＝每周都显示）。
        for (rawSeg in text.split(',')) {
            val seg = rawSeg.trim()
            if (seg.isEmpty()) continue
            val segOdd = seg.contains("单")
            val segEven = seg.contains("双")
            val nums = seg.filter { it.isDigit() || it == '-' }
            val parts = nums.split('-').filter { it.isNotBlank() }
            if (parts.isEmpty()) continue
            if (parts.size == 1) {
                val one = parts[0].toIntOrNull() ?: continue
                if (one in 1..MAX_WEEK) result.add(one)
                continue
            }
            val from = parts[0].toIntOrNull() ?: continue
            val to = parts[1].toIntOrNull() ?: continue
            if (from !in 1..MAX_WEEK || to < from) continue
            for (w in from..to) {
                if (segOdd && !segEven && w % 2 == 0) continue
                if (segEven && !segOdd && w % 2 == 1) continue
                result.add(w)
            }
        }
        return result.sorted()
    }

    /** 紧凑输出：连续段折叠成 `a-b`，单双周补后缀（用于 CSV 导出 / JSON 存储）。 */
    fun format(weeks: List<Int>): String {
        if (weeks.isEmpty()) return ""
        val sorted = weeks.distinct().sorted()
        val allOdd = sorted.all { it % 2 == 1 }
        val allEven = sorted.all { it % 2 == 0 }
        val suffix = when {
            sorted.size > 1 && allOdd -> "单"
            sorted.size > 1 && allEven -> "双"
            else -> ""
        }
        val body = if (suffix.isEmpty()) {
            collapse(sorted)
        } else {
            // 单双周：把连续的奇数/偶数序列折叠（1,3,5 → 1-5）
            collapse(sorted, step = 2)
        }
        return body + suffix
    }

    private fun collapse(sorted: List<Int>, step: Int = 1): String {
        if (sorted.isEmpty()) return ""
        val parts = mutableListOf<String>()
        var start = sorted.first()
        var prev = start
        for (i in 1 until sorted.size) {
            val cur = sorted[i]
            if (cur - prev == step) {
                prev = cur
                continue
            }
            parts.add(if (start == prev) "$start" else "$start-$prev")
            start = cur
            prev = cur
        }
        parts.add(if (start == prev) "$start" else "$start-$prev")
        return parts.joinToString(",")
    }

    /** 人类可读（预览界面用）：`1-16周` / `1-16周(单)` / `每周`。 */
    fun describe(weeks: List<Int>): String {
        if (weeks.isEmpty()) return "每周"
        val text = format(weeks)
        val hasSuffix = text.endsWith("单") || text.endsWith("双")
        val body = if (hasSuffix) text.dropLast(1) else text
        val suffix = if (hasSuffix) "(${text.last()})" else ""
        return "${body}周$suffix"
    }
}
