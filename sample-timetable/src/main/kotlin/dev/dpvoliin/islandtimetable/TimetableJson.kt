package dev.dpvoliin.islandtimetable

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * **JSON 课表导入** —— 主要面向**小爱课程表（AISchedule）众包脚本**的输出，也兼容其它课表 App 导出的 JSON。
 *
 * 为什么优先做这个：学校教务系统一改版，改的是**社区维护的脚本**，不是我们的代码；
 * 而脚本输出格式是稳定的（下面这套字段）。所以它是覆盖面最广、维护成本最低的一条导入路。
 *
 * ## 容错设计（宁可多认几种写法，也不让用户去改 JSON）
 * | 情况 | 兼容写法 |
 * |---|---|
 * | 顶层结构 | 数组 `[{…}]`，或对象 `{courses:[…]}` / `{data:[…]}`（自动往对象里找装课程的数组） |
 * | 课程名 | `name` / `courseName` / `course` / `title` / `课程名` |
 * | 教师 | `teacher` / `teacherName` / `老师` / `任课教师` |
 * | 教室 | `position` / `room` / `location` / `classroom` / `教室` / `地点` |
 * | 星期 | `1`(周一)…`7`、`0` 按周日、`"周一"`、`"Monday"` |
 * | 节次 | `[1,2]` / `"1-2"` / `1` / `startSection`+`endSection` |
 * | 周次 | `[1,2,3]` / `"1-16单"` / `"1,3,5"` / 缺省＝每周 |
 *
 * 复用已经跑通的组件：周次字符串用 [WeekSet.parse]，星期解析用 [TimetableCsv.parseDay]，
 * 配色用 [TimetableCsv.colorFor]（同一门课永远同色）—— 不重复造轮子。
 *
 * 注意：本项目模型是"**起始节 + 连续节数**"，所以节次不连续（如 `[1,3]`）会拆成两门课并给一条提示。
 */
object TimetableJson {

    const val FORMAT = "JSON"

    private val NAME_KEYS = listOf("name", "courseName", "course", "title", "课程名", "课程", "科目")
    private val TEACHER_KEYS = listOf(
        "teacher", "teacherName", "teacher_name", "tutor", "老师", "教师", "任课教师"
    )
    private val ROOM_KEYS = listOf(
        "position", "room", "location", "classroom", "place", "venue", "教室", "地点", "上课地点"
    )
    private val DAY_KEYS = listOf(
        "day", "dayOfWeek", "weekday", "weekDay", "dayIndex", "星期", "周几", "上课星期"
    )
    private val SECTIONS_KEYS = listOf(
        "sections", "section", "periods", "period", "sectionIndex", "节次", "节数"
    )
    private val START_KEYS = listOf(
        "startSection", "startPeriod", "start", "beginSection", "开始节次", "起始节次"
    )
    private val END_KEYS = listOf(
        "endSection", "endPeriod", "end", "finishSection", "结束节次", "终止节次"
    )
    private val WEEKS_KEYS = listOf("weeks", "week", "weekList", "weekRange", "weekIndexes", "周次", "周数")

    /** 常见的"装着课程数组"的键名（先查这些，再退化为深度搜索）。 */
    private val CONTAINER_KEYS = listOf(
        "courses", "courseList", "data", "result", "list", "items",
        "table", "schedule", "schedules", "lessons", "classes", "课表", "课程列表"
    )

    private const val MAX_SECTION = 30

    fun parse(text: String): ImportResult {
        val raw = text.trim()
        if (raw.isEmpty()) {
            return ImportResult(emptyList(), listOf("内容是空的"), format = FORMAT)
        }
        val root = try {
            JSONTokener(raw).nextValue()
        } catch (t: Throwable) {
            return ImportResult(
                emptyList(),
                listOf(
                    "不是合法 JSON：${t.message.orEmpty().take(60)}" +
                        "（如果粘贴的是脚本源码，请粘贴脚本**运行后**的输出）"
                ),
                format = FORMAT
            )
        }

        val array = findCourseArray(root, requireName = true)
            ?: findCourseArray(root, requireName = false)
            ?: return ImportResult(
                emptyList(),
                listOf("这个 JSON 里找不到课程数组（期望形如 [{\"name\":\"高等数学\", …}]）"),
                format = FORMAT
            )

        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val courses = mutableListOf<Course>()
        val seen = mutableSetOf<String>()

        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i)
            if (obj == null) {
                warnings.add("第 ${i + 1} 项不是课程对象，已跳过")
                continue
            }
            val name = firstString(obj, NAME_KEYS)
            if (name.isEmpty()) {
                errors.add("第 ${i + 1} 项没有课程名，已跳过")
                continue
            }
            val day = parseDayValue(obj)
            if (day == null) {
                errors.add("「$name」认不出星期，已跳过")
                continue
            }
            val sections = parseSections(obj)
            if (sections.isEmpty()) {
                errors.add("「$name」认不出节次，已跳过")
                continue
            }
            val teacher = firstString(obj, TEACHER_KEYS)
            val room = firstString(obj, ROOM_KEYS)
            val weeks = parseWeeks(obj)

            val runs = contiguousRuns(sections)
            if (runs.size > 1) {
                warnings.add("「$name」节次不连续（${sections.joinToString("、")}），已拆成 ${runs.size} 门")
            }
            for ((start, span) in runs) {
                val key = listOf(name, day, start, span, weeks.joinToString(",")).joinToString("|")
                if (!seen.add(key)) continue
                courses.add(
                    Course(
                        id = "json-" + key.hashCode().toUInt().toString(16),
                        name = name,
                        teacher = teacher,
                        room = room,
                        dayOfWeek = day,
                        startPeriod = start,
                        span = span,
                        weeks = weeks,
                        color = TimetableCsv.colorFor(name)
                    )
                )
            }
        }

        if (courses.isEmpty() && errors.isEmpty()) {
            errors.add("没有解析出任何课程")
        }
        return ImportResult(courses, errors, warnings, ImportLayout.ROW, FORMAT)
    }

    // ---------------------------------------------------------------- 找课程数组

    /**
     * 在任意 JSON 结构里找"课程数组"。
     *
     * @param requireName true 时只认"元素里有课程名字段"的数组（更准），
     *                    false 时退化为"任何装着对象的数组"（更宽）
     */
    private fun findCourseArray(node: Any?, requireName: Boolean): JSONArray? {
        when (node) {
            is JSONArray -> {
                if (node.length() == 0) return null
                val first = node.optJSONObject(0)
                if (first != null && (!requireName || firstString(first, NAME_KEYS).isNotEmpty())) {
                    return node
                }
                for (i in 0 until node.length()) {
                    findCourseArray(node.opt(i), requireName)?.let { return it }
                }
                return null
            }
            is JSONObject -> {
                for (k in CONTAINER_KEYS) {
                    if (node.has(k)) findCourseArray(node.opt(k), requireName)?.let { return it }
                }
                for (k in node.keys()) {
                    findCourseArray(node.opt(k), requireName)?.let { return it }
                }
                return null
            }
            else -> return null
        }
    }

    // ---------------------------------------------------------------- 字段取值

    private fun firstString(obj: JSONObject, keys: List<String>): String {
        for (k in keys) {
            if (!obj.has(k)) continue
            val v = obj.opt(k)
            val text = when (v) {
                null, JSONObject.NULL -> ""
                is String -> v.trim()
                is Number -> formatNumber(v)
                else -> ""
            }
            if (text.isNotEmpty()) return text
        }
        return ""
    }

    private fun formatNumber(v: Number): String {
        val d = v.toDouble()
        return if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
    }

    private fun parseDayValue(obj: JSONObject): Int? {
        for (k in DAY_KEYS) {
            if (!obj.has(k)) continue
            val v = obj.opt(k)
            val day = when (v) {
                is Number -> dayFromNumber(v.toInt())
                is String -> dayFromString(v)
                else -> null
            }
            if (day != null) return day
        }
        return null
    }

    private fun dayFromNumber(v: Int): Int? = when {
        v in 1..7 -> v
        v == 0 -> 7          // 0 = 周日（少数脚本这么写）
        else -> null
    }

    private fun dayFromString(s: String): Int? {
        val text = s.trim()
        if (text.isEmpty()) return null
        text.toIntOrNull()?.let { return dayFromNumber(it) }
        return TimetableCsv.parseDay(text)
    }

    /** 节次：优先 `sections` 一类的字段，退化为 `startSection` + `endSection`。 */
    private fun parseSections(obj: JSONObject): List<Int> {
        for (k in SECTIONS_KEYS) {
            if (!obj.has(k)) continue
            val list = numberList(obj.opt(k))
            if (list.isNotEmpty()) return list.filter { it in 1..MAX_SECTION }
        }
        val start = firstString(obj, START_KEYS).toIntOrNull()
        val end = firstString(obj, END_KEYS).toIntOrNull() ?: start
        if (start != null && end != null && start <= end) {
            return (start..end).filter { it in 1..MAX_SECTION }
        }
        return emptyList()
    }

    private fun parseWeeks(obj: JSONObject): List<Int> {
        for (k in WEEKS_KEYS) {
            if (!obj.has(k)) continue
            val v = obj.opt(k)
            if (v == null || v == JSONObject.NULL) continue
            val list = numberList(v)
            if (list.isNotEmpty()) return list.filter { it in 1..WeekSet.MAX_WEEK }
        }
        return emptyList()   // 空 = 每周
    }

    /**
     * 把"可能是数组 / 字符串 / 单个数字"的字段统一成整数数组。
     * 字符串走 [WeekSet.parse]，于是 `"1-16单"`、`"1,3,5"`、`"[1,2]"` 都能认。
     */
    private fun numberList(value: Any?): List<Int> = when (value) {
        null, JSONObject.NULL -> emptyList()
        is Number -> listOf(value.toInt())
        is String -> {
            val text = value.trim()
            val fromJson = runCatching { JSONArray(text) }.getOrNull()
            if (fromJson != null) {
                (0 until fromJson.length()).mapNotNull { idx ->
                    when (val e = fromJson.opt(idx)) {
                        is Number -> e.toInt()
                        is String -> e.trim().toIntOrNull()
                        else -> null
                    }
                }
            } else {
                WeekSet.parse(text)
            }
        }
        is JSONArray -> (0 until value.length()).mapNotNull { idx ->
            when (val e = value.opt(idx)) {
                is Number -> e.toInt()
                is String -> e.trim().toIntOrNull() ?: WeekSet.parse(e.trim()).firstOrNull()
                else -> null
            }
        }
        else -> emptyList()
    }

    /** 把节次数组压成若干段**连续**区间：`[1,2,5]` → `[(1,2), (5,1)]`。 */
    private fun contiguousRuns(sections: List<Int>): List<Pair<Int, Int>> {
        val sorted = sections.distinct().sorted()
        if (sorted.isEmpty()) return emptyList()
        val runs = mutableListOf<Pair<Int, Int>>()
        var start = sorted.first()
        var prev = start
        for (s in sorted.drop(1)) {
            if (s == prev + 1) {
                prev = s
            } else {
                runs.add(start to (prev - start + 1))
                start = s
                prev = s
            }
        }
        runs.add(start to (prev - start + 1))
        return runs
    }

    /** 粘贴框里的示例（界面用，也能让用户一眼看懂要粘什么）。 */
    fun sample(): String = """
        [
          {"name":"高等数学","teacher":"张建国","position":"3教305","day":1,"sections":[1,2],"weeks":"1-16"},
          {"name":"大学物理","teacher":"李文博","position":"4教201","day":1,"sections":[3,4],"weeks":"1-16"},
          {"name":"数字电子技术实验","teacher":"赵晓东","position":"实验楼B204","day":2,"sections":[6,7,8],"weeks":"1-8单"}
        ]
    """.trimIndent()
}
