package dev.dpvoliin.islandtimetable

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 课表落盘：`filesDir/timetable.json`。
 *
 * **只用 app 私有目录、不联网、不上传** —— 用户的课表属于隐私数据。
 * 序列化用平台自带的 `org.json`，不引第三方库（APK 不因此变大）。
 */
object TimetableStore {

    private const val FILE_NAME = "timetable.json"
    private const val FORMAT_VERSION = 1

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun exists(context: Context): Boolean = file(context).isFile

    /** 导出：把落盘的课表 JSON 原样取出来（同一份格式，导入时能原样还原）。 */
    fun exportText(context: Context): String =
        runCatching { file(context).readText() }.getOrDefault("")

    /**
     * 从备份文本恢复整份课表（课程 + 节次表 + 学期起始日 + 来源）。
     *
     * 与"导入课表"不同：这是**还原备份**，会整体替换现有课表（含学期起始日），
     * 所以调用方要明确告诉用户这一点。
     */
    fun importFrom(context: Context, text: String): Timetable? = runCatching {
        val root = JSONObject(text)
        require(root.optJSONArray("courses") != null) { "不是岛课表备份文件" }
        file(context).writeText(text)
        load(context)
    }.getOrNull()

    /** 这段文本看起来像岛课表备份吗（用于导入时识别）。 */
    fun looksLikeBackup(text: String): Boolean =
        text.contains("\"formatVersion\"") && text.contains("\"courses\"") && text.contains("\"dayLabels\"")

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    fun save(context: Context, timetable: Timetable): Boolean = runCatching {
        val root = JSONObject().apply {
            put("formatVersion", FORMAT_VERSION)
            put("source", timetable.source)
            put("termStartDate", timetable.termStartDate)
            put("importedAt", timetable.importedAt)
            put("dayLabels", JSONArray(timetable.dayLabels))

            put("periods", JSONArray().apply {
                timetable.periods.forEach { p ->
                    put(
                        JSONObject().apply {
                            put("index", p.index)
                            put("start", p.start.toString())   // hh:mm
                            put("end", p.end.toString())
                        }
                    )
                }
            })

            put("courses", JSONArray().apply {
                timetable.courses.forEach { c ->
                    put(
                        JSONObject().apply {
                            put("id", c.id)
                            put("name", c.name)
                            put("teacher", c.teacher)
                            put("room", c.room)
                            put("day", c.dayOfWeek)
                            put("startPeriod", c.startPeriod)
                            put("span", c.span)
                            put("weeks", WeekSet.format(c.weeks))
                            put("color", c.color)
                        }
                    )
                }
            })
        }
        file(context).writeText(root.toString(2))
        true
    }.getOrDefault(false)

    /** 读不到 / 解析失败一律返回 null（上层回落到内置演示课表）。 */
    fun load(context: Context): Timetable? = runCatching {
        val f = file(context)
        if (!f.isFile) return null
        val root = JSONObject(f.readText())

        val courses = mutableListOf<Course>()
        val courseArray = root.optJSONArray("courses") ?: JSONArray()
        for (i in 0 until courseArray.length()) {
            val o = courseArray.optJSONObject(i) ?: continue
            val name = o.optString("name")
            if (name.isEmpty()) continue
            courses.add(
                Course(
                    id = o.optString("id", "csv-$i"),
                    name = name,
                    teacher = o.optString("teacher"),
                    room = o.optString("room"),
                    dayOfWeek = o.optInt("day", 1),
                    startPeriod = o.optInt("startPeriod", 1),
                    span = o.optInt("span", 1).coerceAtLeast(1),
                    weeks = WeekSet.parse(o.optString("weeks")),
                    color = o.optInt("color", DEFAULT_COLOR)
                )
            )
        }

        val periods = mutableListOf<Period>()
        val periodArray = root.optJSONArray("periods") ?: JSONArray()
        for (i in 0 until periodArray.length()) {
            val o = periodArray.optJSONObject(i) ?: continue
            val start = runCatching { java.time.LocalTime.parse(o.optString("start")) }.getOrNull()
            val end = runCatching { java.time.LocalTime.parse(o.optString("end")) }.getOrNull()
            if (start != null && end != null) {
                periods.add(Period(o.optInt("index", i + 1), start, end))
            }
        }

        val labels = mutableListOf<String>()
        root.optJSONArray("dayLabels")?.let { arr ->
            for (i in 0 until arr.length()) labels.add(arr.optString(i))
        }

        Timetable(
            courses = courses,
            periods = periods.ifEmpty { DemoTimetable.BUILTIN_PERIODS },
            dayLabels = labels.ifEmpty { DemoTimetable.BUILTIN_DAY_LABELS },
            termStartDate = root.optString("termStartDate"),
            source = root.optString("source", "csv"),
            importedAt = root.optLong("importedAt", 0L)
        )
    }.getOrNull()

    /** 内置演示课表用的默认色（与 [Course] 的默认颜色一致）。 */
    private const val DEFAULT_COLOR = 0xFF4F9E80.toInt()
}
