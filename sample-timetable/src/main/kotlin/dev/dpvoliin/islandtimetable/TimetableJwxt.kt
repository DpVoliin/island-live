package dev.dpvoliin.islandtimetable

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * **正方教务系统（新正方 jwglxt）课表解析** —— 被 [JwxtActivity] 抓回来的接口原文喂进来。
 *
 * 接口（社区适配脚本里长期稳定的事实性约定，本项目代码是自己写的）：
 * ```
 * GET  /jwglxt/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N2151   ← 先访问，页面里带默认学年/学期
 * POST /jwglxt/kbcx/xskbcx_cxXsKb.html  xnm=<学年>&xqm=<学期> ← 返回 {"kbList":[...]}
 * ```
 *
 * `kbList` 单条的字段映射：
 * | 正方字段 | 含义 | 本项目字段 |
 * |---|---|---|
 * | `kcmc` | 课程名称 | [Course.name] |
 * | `cdmc` | 上课地点 | [Course.room] |
 * | `xm` | 教师姓名 | [Course.teacher] |
 * | `xqj` | 星期（1=周一…7=周日） | [Course.dayOfWeek] |
 * | `jc` | 节次（`"1-2"` / `"1,2"` / `"3"`） | [Course.startPeriod] + [Course.span] |
 * | `zcd` | 周次（`"1-16周"` / `"1-16周(单)"`） | [Course.weeks]（复用 [WeekSet.parse]） |
 *
 * 节次不连续（如 `1,3`）会拆成多门课 —— 本项目模型是"起始节 + 连续节数"。
 * 教务给的是**节次序号**而不是时间，所以这里不需要像 ICS 那样做时间对齐，是最稳的一条导入路。
 */
object TimetableJwxt {

    const val FORMAT = "正方教务（直连抓取）"

    /** 通用表格模式（强智 / 青果 / URP / 老正方都用这个）。 */
    const val FORMAT_TABLE = "教务页面（通用表格解析）"

    fun parse(json: String, format: String = FORMAT): ImportResult {
        val raw = json.trim()
        if (raw.isEmpty()) return ImportResult(emptyList(), listOf("接口没有返回内容"), format = format)

        val array = try {
            when (val root = JSONTokener(raw).nextValue()) {
                is JSONArray -> root
                is JSONObject -> {
                    // 正方把**理论课**放 kbList，**实践/实验环节**另放 sjkList。
                    // 之前只读 kbList → 实践课（实习 / 课程设计 / 实验）整批丢失（已踩）。
                    val theory = root.optJSONArray("kbList") ?: root.optJSONArray("items")
                    val practice = root.optJSONArray("sjkList")
                    when {
                        theory == null -> practice
                        practice == null -> theory
                        else -> org.json.JSONArray().apply {
                            for (i in 0 until theory.length()) put(theory.get(i))
                            for (i in 0 until practice.length()) put(practice.get(i))
                        }
                    }
                }
                else -> null
            }
        } catch (t: Throwable) {
            null
        } ?: return ImportResult(
            emptyList(),
            listOf("不是预期的课表 JSON（期望 {\"kbList\":[…]}) —— 可能未登录、学年学期不对，或学校不是新正方"),
            format = format
        )

        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val courses = mutableListOf<Course>()
        val seen = mutableSetOf<String>()

        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val name = TextFix.decodeEntities(item.optString("kcmc").trim())
            if (name.isEmpty()) continue

            // 星期：常见 1..7（1=周一）；个别版本用 0 表示周日
            // 实践环节（`sjkList`）用的是**另一套字段**：没有 xqj/jc/zcd，只有
            //   qsjsz（周次，如 "11-14周"）、jsxm（教师）、xqmc（校区）、qtkcgs（"…(共4周)/11-14周/无"）
            // 实测（gzist 原始数据）：「&ldquo;三全育人&rdquo;实践活动Ⅴ」= qsjsz "11-14周"、无教室。
            // 它确实没有固定星期/节次 → 按「未排时间」保留，但**周次必须取 qsjsz**（否则连周次都丢）。
            val practiceWeeks = item.optString("qsjsz").trim()
            if (practiceWeeks.isNotEmpty() && !item.has("xqj")) {
                val pWeeks = WeekSet.parse(practiceWeeks)
                val pTeacher = TextFix.decodeEntities(item.optString("jsxm").trim())
                val pCampus = TextFix.decodeEntities(item.optString("xqmc").trim())
                val pKey = "practice|$name|$pTeacher"
                if (seen.add(pKey)) {
                    courses.add(
                        Course(
                            id = pKey,
                            name = name,
                            teacher = pTeacher,
                            room = pCampus,
                            dayOfWeek = 0,
                            startPeriod = 1,
                            span = 1,
                            weeks = pWeeks,
                            color = TimetableCsv.colorFor(name)
                        )
                    )
                }
                warnings.add("「$name」是实践环节（教务只给周次 ${practiceWeeks}，没有星期/节次），按「未排时间」保留")
                continue
            }

            // 星期：常见 1..7（1=周一）；个别版本用 0 表示周日。
            // ⚠️ 实践环节（`sjkList`，如「三全育人实践活动」）教务里**经常不带上课时间**（`xqj` 为空），
            // 老实现直接跳过 ⇒ 用户看到的现像是"**有个课抓不到**"。
            // 现在保留：`dayOfWeek = 0` 表示「未排时间」——不进周课表（网格会过滤掉），但课表里数得到、不会丢。
            val dayRaw = item.optString("xqj").trim().toIntOrNull()
            val day = when {
                dayRaw == 0 -> 7
                dayRaw == null || dayRaw !in 1..7 -> 0
                else -> dayRaw
            }
            if (dayRaw == 0) warnings.add("「$name」星期给的是 0，已按周日处理（请核对）")
            if (day == 0) warnings.add("「$name」教务没给上课时间，已按「未排时间」保留（不占周课表格子）")
            // 节次字段各版本不一致：常见 jc，也有 jcs / jcor / jcstr
            val sectionsText = listOf("jc", "jcs", "jcor", "jcstr")
                .map { item.optString(it).trim() }
                .firstOrNull { it.isNotEmpty() }
                .orEmpty()
            val periods = if (day == 0) listOf(1) else parseSections(sectionsText)
            if (day != 0 && periods.isEmpty()) {
                warnings.add("「$name」节次字段异常（jc/jcs/jcor 都读不出，原值「$sectionsText」），已跳过")
                continue
            }
            val room = TextFix.decodeEntities(item.optString("cdmc").trim())
            val teacher = item.optString("xm").trim()
            // 周次字段：常见 zcd，也有 zcmc（周次名称）
            val weeksRaw = listOf("zcd", "zcmc")
                .map { item.optString(it).trim() }
                .firstOrNull { it.isNotEmpty() }
                .orEmpty()
            val weeks = WeekSet.parse(weeksText(weeksRaw))
            if (weeks.isEmpty() && weeksRaw.isNotEmpty()) {
                warnings.add("「$name」周次没解析出来（原值「$weeksRaw」）—— 已按「每周」处理")
            }

            val runs = contiguousRuns(periods)
            if (runs.size > 1) {
                warnings.add("「$name」节次不连续（${item.optString("jc")}），已拆成 ${runs.size} 门")
            }
            for ((start, span) in runs) {
                val key = listOf(name, day, start, span, weeks.joinToString(",")).joinToString("|")
                if (!seen.add(key)) continue
                courses.add(
                    Course(
                        id = "jwxt-" + key.hashCode().toUInt().toString(16),
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

        if (courses.isEmpty()) {
            errors.add(if (array.length() == 0) "接口返回的课表是空的（可能学期选错了，或还没选课）" else "没有解析出任何课程")
        }
        return ImportResult(courses, errors, warnings, ImportLayout.ROW, FORMAT)
    }

    /**
     * 周次文本：`"1-16周"` / `"1-16周(单)"` / `"1-8,10-16周"` / `"3周"`。
     *
     * 有些版本会在 `周` 后面再跟 `(1-2节)`，那部分**不是周次**，要去掉，否则会多出假周次。
     */
    private fun weeksText(zcd: String): String {
        val text = zcd.trim()
        val idx = text.indexOf('周')
        if (idx < 0) return text
        val head = text.substring(0, idx + 1)
        val tail = text.substring(idx + 1)
        val flag = when {
            tail.contains('单') -> "单"
            tail.contains('双') -> "双"
            else -> ""
        }
        return head + flag
    }

    /**
     * 节次：`"1-2"` / `"1,2,3"` / `"3"` / `"第1-2节"` / `"3-4大节"` → `[1,2]` 等。
     *
     * ⚠️ 真踩过：只 `replace("节","")` 是不够的 —— `"第1-2节"` 会留下 `"第1-2"`，
     * 拆出来后 `"第1"` 转不成数字被丢掉，结果只剩 `[2]`（起始节错成 2、跨度错成 1），
     * 表现出来就是"某门课位置不对"。所以这里把所有非数字/逗号/连接符的字符都去掉。
     */
    private fun parseSections(jc: String): List<Int> {
        val text = jc.replace("第", "")
            .replace("节", "")
            .replace("大节", "")
            .replace("小节", "")
            .trim()
        if (text.isEmpty()) return emptyList()
        val out = linkedSetOf<Int>()
        for (part in text.split(',', '，')) {
            val piece = part.trim()
            if (piece.isEmpty()) continue
            val range = piece.split('-', '—', '－').mapNotNull { it.trim().toIntOrNull() }
            when {
                range.size == 1 -> if (range[0] in 1..30) out.add(range[0])
                range.size >= 2 -> {
                    val from = range[0]
                    val to = range[1]
                    if (from in 1..30 && to in from..30) for (p in from..to) out.add(p)
                }
            }
        }
        return out.toList()
    }

    /** 把节次压成若干**连续**区间：`[1,2,5]` → `[(1,2), (5,1)]`。 */
    private fun contiguousRuns(periods: List<Int>): List<Pair<Int, Int>> {
        val sorted = periods.distinct().sorted()
        if (sorted.isEmpty()) return emptyList()
        val runs = mutableListOf<Pair<Int, Int>>()
        var start = sorted.first()
        var prev = start
        for (p in sorted.drop(1)) {
            if (p == prev + 1) {
                prev = p
            } else {
                runs.add(start to (prev - start + 1))
                start = p
                prev = p
            }
        }
        runs.add(start to (prev - start + 1))
        return runs
    }

    /** 自检/排障用：给一份 `kbList` 样本，检查能不能解析（界面不调，测试与文档用）。 */
    fun sampleJson(): String = """
        {"kbList":[
          {"kcmc":"高等数学","cdmc":"3教305","xm":"张建国","xqj":"1","jc":"1-2","zcd":"1-16周"},
          {"kcmc":"电路原理","cdmc":"2教108","xm":"王海涛","xqj":"3","jc":"3-4","zcd":"1-16周"},
          {"kcmc":"数字电子技术实验","cdmc":"实验楼B204","xm":"赵晓东","xqj":"2","jc":"6-8","zcd":"1-8周(单)"}
        ]}
    """.trimIndent()
}
