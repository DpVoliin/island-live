package dev.dpvoliin.islandtimetable

import android.content.Context
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * 一节次的时间段。
 *
 * v0.2.1 起可由导入文件自带节次表（ICS 的 DTSTART/DTEND、或第二段"节次表"），
 * 现在先用内置的 16 小节制占位。
 */
data class Period(val index: Int, val start: LocalTime, val end: LocalTime) {
    val label: String get() = "%02d:%02d".format(start.hour, start.minute)
    val endLabel: String get() = "%02d:%02d".format(end.hour, end.minute)
}

/**
 * 课程条目。
 *
 * 字段刻意对齐 AISchedule / 小爱课程表 众包脚本的输出约定（name / position / teacher / weeks / day / sections），
 * 这样接入社区适配脚本时不用再翻译一层。
 */
data class Course(
    val id: String,
    val name: String,
    val teacher: String,
    val room: String,
    val dayOfWeek: Int,          // 1 = 周一 … 7 = 周日
    val startPeriod: Int,        // 从 1 开始
    val span: Int = 1,           // 连上几节
    val weeks: List<Int> = emptyList(),  // 空 = 每周；否则只在这些周显示
    val color: Int = 0xFF4F9E80.toInt()
)

/**
 * 当前课表（**全局单例**）。
 *
 * 名字保留 `DemoTimetable` 是为了不动 20 多处调用点，但语义已经变了：
 * v0.1 它是"写死的演示数据"，**v0.2 起它是"当前生效的课表"** ——
 * 启动时从 [TimetableStore] 载入导入结果，没导入过才回落到内置演示数据。
 *
 * - [BUILTIN_*] 内置演示数据（也当兜底）
 * - [PERIODS] / [COURSES] / [DAY_LABELS] 当前生效的数据（UI 只读这些）
 * - [source] 数据来源：demo / csv / …
 */
object DemoTimetable {

    // ------------------------------------------------------------ 内置演示数据（兜底）

    val BUILTIN_DAY_LABELS: List<String> =
        listOf("周一", "周二", "周三", "周四", "周五")

    /**
     * 16 小节制（一节课 40 分钟 + 10 分钟课间）。
     * 8 节不够 —— 很多学校一天能排到 16 节（含晚间）。这里是**占位时刻**，后续可由导入文件覆盖。
     */
    val BUILTIN_PERIODS: List<Period> = listOf(
        // 上午 1–5
        Period(1, LocalTime.of(8, 0), LocalTime.of(8, 40)),
        Period(2, LocalTime.of(8, 50), LocalTime.of(9, 30)),
        Period(3, LocalTime.of(9, 40), LocalTime.of(10, 20)),
        Period(4, LocalTime.of(10, 30), LocalTime.of(11, 10)),
        Period(5, LocalTime.of(11, 20), LocalTime.of(12, 0)),
        // 下午 6–10
        Period(6, LocalTime.of(14, 0), LocalTime.of(14, 40)),
        Period(7, LocalTime.of(14, 50), LocalTime.of(15, 30)),
        Period(8, LocalTime.of(15, 40), LocalTime.of(16, 20)),
        Period(9, LocalTime.of(16, 30), LocalTime.of(17, 10)),
        Period(10, LocalTime.of(17, 20), LocalTime.of(18, 0)),
        // 晚上 11–16
        Period(11, LocalTime.of(19, 0), LocalTime.of(19, 40)),
        Period(12, LocalTime.of(19, 50), LocalTime.of(20, 30)),
        Period(13, LocalTime.of(20, 40), LocalTime.of(21, 20)),
        Period(14, LocalTime.of(21, 30), LocalTime.of(22, 10)),
        Period(15, LocalTime.of(22, 20), LocalTime.of(23, 0)),
        Period(16, LocalTime.of(23, 10), LocalTime.of(23, 50))
    )

    private const val TEAL = 0xFF4F9E80.toInt()
    private const val AMBER = 0xFFC2953F.toInt()
    private const val SLATE = 0xFF6B8CAE.toInt()
    private const val MAUVE = 0xFFA97B9E.toInt()
    private const val OLIVE = 0xFF8E9B5A.toInt()
    private const val CLAY = 0xFFB07A63.toInt()

    val BUILTIN_COURSES: List<Course> = listOf(
        // 周一
        Course("mon-gs", "高等数学", "张建国", "3教305", 1, 1, 2, color = TEAL),
        Course("mon-wl", "大学物理", "李文博", "4教201", 1, 3, 2, color = SLATE),
        Course("mon-dl", "电路原理", "王海涛", "2教108", 1, 6, 2, color = AMBER),
        Course("mon-ty", "体育（篮球）", "陈峰", "体育馆", 1, 8, 2, color = OLIVE),
        Course("mon-zx", "晚自习 · 高数答疑", "张建国", "3教305", 1, 11, 2, color = TEAL),

        // 周二
        Course("tue-xx", "线性代数", "刘敏", "3教207", 2, 1, 2, color = MAUVE),
        Course("tue-gs", "高等数学", "张建国", "3教305", 2, 3, 2, color = TEAL),
        Course("tue-sd", "数字电子技术实验", "赵晓东", "实验楼B204", 2, 6, 3, color = CLAY),
        Course("tue-zy", "专业导论", "周立", "1教401", 2, 9, 2, color = SLATE),
        Course("tue-xk", "公选课 · 人工智能导论", "杨帆", "6教301", 2, 13, 2, color = MAUVE),

        // 周三
        Course("wed-yy", "大学英语", "Amelia", "5教112", 3, 1, 2, color = AMBER),
        Course("wed-dl", "电路原理", "王海涛", "2教108", 3, 3, 2, color = AMBER),
        Course("wed-fb", "复变函数与积分变换", "刘敏", "3教211", 3, 6, 2, color = MAUVE),
        Course("wed-ty", "体育（篮球）", "陈峰", "体育馆", 3, 8, 2, color = OLIVE),
        Course("wed-sy", "电路实验", "赵晓东", "实验楼B201", 3, 11, 3, color = TEAL),

        // 周四
        Course("thu-xh", "信号与系统", "郑楠", "3教308", 4, 1, 2, color = SLATE),
        Course("thu-wl", "大学物理", "李文博", "4教201", 4, 3, 2, color = SLATE),
        Course("thu-sz", "习近平新时代中国特色社会主义思想概论", "黄伟", "1教101", 4, 6, 2, color = CLAY),
        Course("thu-dlsx", "电路实验", "赵晓东", "实验楼B201", 4, 9, 3, color = TEAL),
        Course("thu-zx", "晚自习 · 物理答疑", "李文博", "4教201", 4, 13, 2, color = SLATE),

        // 周五
        Course("fri-gl", "概率论与数理统计", "刘敏", "3教207", 5, 1, 2, color = MAUVE),
        Course("fri-zz", "自动控制原理", "孙彦", "2教305", 5, 3, 2, color = TEAL),
        Course("fri-yy", "大学英语", "Amelia", "5教112", 5, 6, 2, color = AMBER),
        Course("fri-js", "金工实习", "陈峰", "工程训练中心", 5, 8, 3, color = OLIVE)
    )

    // ------------------------------------------------------------ 当前生效的数据

    /** 当前的节次表（UI 只读这里）。 */
    var PERIODS: List<Period> = BUILTIN_PERIODS
        private set

    /** 当前的表头（周一…周五 / 周日按需扩展）。 */
    var DAY_LABELS: List<String> = BUILTIN_DAY_LABELS
        private set

    /** 当前的课程列表（UI 只读这里）。 */
    var COURSES: List<Course> = BUILTIN_COURSES
        private set

    /** 数据来源：demo / csv / xlsx / ics / jwxt。 */
    var source: String = "demo"
        private set

    /** 学期第一周的周一（ISO），空 = 未设置。 */
    var termStartDate: String = ""
        private set

    var importedAt: Long = 0L
        private set

    /** 当前第几周（由学期起始日推算；未设置时按 1）。 */
    var currentWeek: Int = 1
        private set

    /** 界面正在查看第几周（默认＝当前周）。翻周看是为了让"单周课 / 前 8 周结课"这类安排可见。 */
    var viewWeek: Int = 1
        private set

    val isDemo: Boolean get() = source == "demo"

    /** 记住「学期开始的周一」到现在第几周。 */
    fun setViewWeek(week: Int) {
        viewWeek = week.coerceIn(1, WeekSet.MAX_WEEK)
    }

    fun resetViewWeek() {
        viewWeek = currentWeek
    }

    fun isViewingCurrentWeek(): Boolean = viewWeek == currentWeek

    // ------------------------------------------------------------ 载入 / 导入 / 重置

    /** 启动时调用：有导入结果就用它，否则回落到内置演示课表。 */
    fun apply(context: Context) {
        val loaded = TimetableStore.load(context)
        if (loaded == null) {
            useBuiltin()
            return
        }
        PERIODS = fitPeriods(loaded.periods, loaded.courses)
        DAY_LABELS = widenLabels(loaded.dayLabels, loaded.courses)
        COURSES = loaded.courses
        source = loaded.source.ifEmpty { "csv" }
        termStartDate = loaded.termStartDate
        importedAt = loaded.importedAt
        currentWeek = computeWeek()
        viewWeek = currentWeek
    }

    /** 导入结果：直接生效 + 落盘。 */
    /**
     * 修正「现在是第几周」：按它反推学期起始日并落盘。
     *
     * 用途：教务里显示的"当前教学周"和 App 的周次对不上时（导入时那个数字填错、
     * 或教务用校历周/教学周两套编号），**不用重新导入**，在这儿对齐一次即可。
     */
    fun correctTermWeek(context: Context, week: Int) {
        val timetable = Timetable(
            courses = COURSES,
            periods = PERIODS,
            dayLabels = DAY_LABELS,
            termStartDate = deriveTermStart(week),
            source = source,
            importedAt = System.currentTimeMillis()
        )
        importTimetable(context, timetable)
    }

    /**
     * 只替换**节次表（作息时间）**，课程 / 周次 / 学期起始日 / 来源原样保留。
     *
     * 教务导入只给节次序号、不给时刻，所以用户必须能自己设一次本校作息 —— 这个入口就是它。
     */
    fun savePeriods(context: Context, newPeriods: List<Period>) {
        val timetable = Timetable(
            courses = COURSES,
            periods = newPeriods,
            dayLabels = DAY_LABELS,
            termStartDate = termStartDate,
            source = source,
            importedAt = if (importedAt > 0L) importedAt else System.currentTimeMillis()
        )
        importTimetable(context, timetable)
    }

    /**
     * 当前作息是否还是**内置占位时间**（＝导入数据里没带时刻，用户也没设过）。
     * 为真时界面要明确提示用户去「作息时间」设成本校的，否则显示的时间是错的。
     */
    val periodsArePlaceholder: Boolean
        get() = PERIODS.isNotEmpty() && PERIODS.size == BUILTIN_PERIODS.size &&
            PERIODS.zip(BUILTIN_PERIODS).all { (a, b) -> a.start == b.start && a.end == b.end }

    fun importTimetable(context: Context, timetable: Timetable) {
        TimetableStore.save(context, timetable)
        apply(context)
    }

    /** 清掉导入结果，回到内置演示课表。 */
    fun resetToDemo(context: Context) {
        TimetableStore.clear(context)
        useBuiltin()
    }

    private fun useBuiltin() {
        PERIODS = BUILTIN_PERIODS
        DAY_LABELS = BUILTIN_DAY_LABELS
        COURSES = BUILTIN_COURSES
        source = "demo"
        termStartDate = ""
        importedAt = 0L
        currentWeek = 1
        viewWeek = 1
    }

    /** 由"今天 + 学期起始"推算第几周。 */
    fun computeWeek(today: LocalDate = LocalDate.now()): Int {
        if (termStartDate.isEmpty()) return 1
        val start = runCatching { LocalDate.parse(termStartDate) }.getOrNull() ?: return 1
        val days = ChronoUnit.DAYS.between(start, today)
        return ((days / 7).toInt() + 1).coerceAtLeast(1)
    }

    /**
     * 反推学期起始日：已知"现在是第 week 周"，求第一周的周一。
     * 导入界面用它把用户填的"当前第几周"换算成日期（比让用户选日期简单）。
     */
    fun deriveTermStart(currentWeek: Int, today: LocalDate = LocalDate.now()): String {
        val thisMonday = today.minusDays((today.dayOfWeek.value - 1).toLong())
        return thisMonday.minusWeeks((currentWeek.coerceAtLeast(1) - 1).toLong()).toString()
    }


    /**
     * 按导入数据**实际用到的节次**裁剪节次表。
     *
     * 为什么必须裁：内置节次表是 16 小节制（很多学校能排到晚间），而多数学校一天只用 8~12 节。
     * 不裁的话，导入后网格永远画满 16 行 —— 看起来就是"每天 16 节课、挤成一团"。
     *
     * 注意：网格用 `节次序号 - 1` 当**行索引**，所以节次表必须从第 1 节开始连续，
     * 只能裁**尾部**，不能掐头（否则课程会整体错行）。
     * 最少保留 8 节，避免只上两三节时表格只剩两行显得空。
     */
    private fun fitPeriods(periods: List<Period>, courses: List<Course>): List<Period> {
        if (periods.isEmpty() || courses.isEmpty()) return periods
        // 用户自己设过作息（节数与内置不同）→ 说明是他要的完整作息，不做裁剪
        if (periods.size != BUILTIN_PERIODS.size) return periods
        val maxUsed = courses.maxOf { it.startPeriod + it.span - 1 }
        val keep = maxUsed.coerceAtLeast(MIN_ROWS).coerceAtMost(periods.size)
        return periods.take(keep)
    }

    /** 网格最少保留的节次行数。 */
    private const val MIN_ROWS = 8

    /** 表头宽度：课程排到周日时自动把"周日"补上。 */
    private fun widenLabels(labels: List<String>, courses: List<Course>): List<String> {
        val need = courses.maxOfOrNull { it.dayOfWeek } ?: return labels.ifEmpty { BUILTIN_DAY_LABELS }
        val base = labels.ifEmpty { BUILTIN_DAY_LABELS }
        if (base.size >= need) return base
        val all = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
        return all.take(need)
    }

    // ------------------------------------------------------------ 查询（UI 用）

    /** 某天的课（默认按**正在查看的周**过滤；也可以显式指定周次）。 */
    fun coursesOn(dayOfWeek: Int, week: Int = viewWeek): List<Course> =
        sortedOn(dayOfWeek, week)

    /** 某天的课（按**真实当前周**过滤）—— "下一节课"用这个，不受翻周影响。 */
    fun coursesToday(dayOfWeek: Int): List<Course> = sortedOn(dayOfWeek, currentWeek)

    private fun sortedOn(dayOfWeek: Int, week: Int): List<Course> =
        COURSES.filter { it.dayOfWeek == dayOfWeek && isActive(it, week) }
            .sortedBy { it.startPeriod }

    fun isActive(course: Course, week: Int = viewWeek): Boolean =
        course.weeks.isEmpty() || week in course.weeks

    /** 当前查看周要画到网格上的课。 */
    fun coursesForGrid(week: Int = viewWeek): List<Course> =
        // dayOfWeek = 0 是「未排时间」（教务没给上课时间的实践环节）：不进网格，否则会画到错误的格子上
        COURSES.filter { it.dayOfWeek in 1..7 && isActive(it, week) }

    /** 「未排时间」的课（教务没给星期/节次的实践环节）—— 不进网格，但要能告诉用户有多少门。 */
    fun unscheduled(week: Int = viewWeek): List<Course> =
        COURSES.filter { it.dayOfWeek == 0 && isActive(it, week) }

    fun timeRange(course: Course): String {
        val start = PERIODS.firstOrNull { it.index == course.startPeriod } ?: return "--:--"
        val end = PERIODS.firstOrNull { it.index == course.startPeriod + course.span - 1 } ?: start
        return "${start.label} – ${end.endLabel}"
    }

    /** 一节课在整张表里的纵向位置（0..1），用来画「现在」那条线。 */
    fun fractionOf(periodIndex: Int, within: Float = 0f, totalPeriods: Int = PERIODS.size): Float {
        val raw = (periodIndex - 1) + within
        return (raw / totalPeriods).coerceIn(0f, 1f)
    }

    /** 学期信息一行字（界面顶部用）：`第 3 周 · 导入(csv) · 共 24 门课`。 */
    fun summary(): String {
        val src = when (source) {
            "demo" -> "内置演示"
            "csv" -> "CSV 导入"
            else -> "$source 导入"
        }
        if (source == "demo") return "$src · 共 ${COURSES.size} 门"
        // 踩过：这里原来只写"当前周"，于是徽标显示"第13周"、下面这行却写"第1周"，
        // 看起来像"课表卡住不动"。查看周和当前周不一致时两个都写出来。
        val head = if (viewWeek == currentWeek) "第 $viewWeek 周" else "第 $viewWeek 周（当前第 $currentWeek 周）"
        return "$head · $src · 共 ${COURSES.size} 门"
    }

    /** 某个日期属于第几周（由学期起始日推算；没设起始日就返回当前周）。 */
    fun weekOf(date: LocalDate): Int {
        if (termStartDate.isEmpty()) return currentWeek
        val start = runCatching { LocalDate.parse(termStartDate) }.getOrNull() ?: return currentWeek
        val days = ChronoUnit.DAYS.between(start, date)
        return ((days / 7).toInt() + 1).coerceAtLeast(1)
    }

    /** 某个节次的开始时刻。 */
    fun periodStart(period: Int): LocalTime? = PERIODS.firstOrNull { it.index == period }?.start

    /** 某个节次的结束时刻。 */
    fun periodEnd(period: Int): LocalTime? = PERIODS.firstOrNull { it.index == period }?.end

    /** 星期几的中文标签（1 = 周一 … 7 = 周日）。 */
    fun dayLabel(dayOfWeek: Int): String =
        listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日").getOrElse(dayOfWeek - 1) { "周日" }

    /**
     * 「现在这节课」：① 正在上的课（此刻落在它的节次区间内）→ isNow = true；
     * ② 否则就是下一节（跨天，复用 [upcoming] 那套周次/单双周逻辑）。
     *
     * 为什么要有它：常驻通知原来直接用 [upcoming]，而它**只返回还没开始**的课 ⇒
     * 上课期间常驻通知还写着"下一节"。手动推送与主界面都已改成"当前课优先"，
     * 这次把口径统一到这一个函数，通知与界面就不会再各说各话。
     */
    data class NowOrNext(
        val course: Course,
        val isNow: Boolean,
        val start: LocalDateTime,
        val end: LocalDateTime?,
        val week: Int
    ) {
        /** 距离下课还有几分钟（不是正在上课时为 0）。 */
        fun remainingMinutes(now: LocalDateTime = LocalDateTime.now()): Int {
            val e = end ?: return 0
            return ((java.time.Duration.between(now, e).toMillis() + 59_999L) / 60_000L).toInt()
                .coerceAtLeast(0)
        }
    }

    fun currentOrNext(now: LocalDateTime = LocalDateTime.now()): NowOrNext? {
        val week = weekOf(now.toLocalDate())
        val time = now.toLocalTime()
        for (course in sortedOn(now.dayOfWeek.value, week)) {
            val start = periodStart(course.startPeriod) ?: continue
            val end = periodEnd(course.startPeriod + course.span - 1) ?: continue
            if (!time.isBefore(start) && time.isBefore(end)) {
                return NowOrNext(
                    course, true,
                    LocalDateTime.of(now.toLocalDate(), start),
                    LocalDateTime.of(now.toLocalDate(), end),
                    week
                )
            }
        }
        val up = upcoming(now) ?: return null
        val end = periodEnd(up.course.startPeriod + up.course.span - 1)
        return NowOrNext(up.course, false, up.start, end?.let { up.start.toLocalDate().atTime(it) }, up.week)
    }

    /** 下一节课（含它的开始时刻与第几周）—— 空闲卡片 + 上课提醒都用它。 */
    data class Upcoming(val course: Course, val start: LocalDateTime, val week: Int) {
        /** 距离上课还有多久（毫秒，可能为负＝已经开始）。 */
        fun millisFrom(now: LocalDateTime = LocalDateTime.now()): Long =
            java.time.Duration.between(now, start).toMillis()
    }

    /**
     * 从现在往后找最近的一节课（最多找 14 天）。
     *
     * 计算方式：逐天看"那天是第几周"，再取那天在这个周次下真实存在的课，
     * 用节次开始时刻和当前时间比较 —— 所以**跨周、单双周、结课**都自然正确。
     */
    fun upcoming(now: LocalDateTime = LocalDateTime.now()): Upcoming? {
        for (offset in 0..13) {
            val date = now.toLocalDate().plusDays(offset.toLong())
            val week = weekOf(date)
            val dayCourses = sortedOn(date.dayOfWeek.value, week)
            for (course in dayCourses) {
                val start = periodStart(course.startPeriod) ?: continue
                val at = LocalDateTime.of(date, start)
                if (at.isAfter(now)) return Upcoming(course, at, week)
            }
        }
        return null
    }
}
