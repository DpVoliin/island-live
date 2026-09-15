package dev.dpvoliin.islandtimetable

/**
 * CSV / TSV 课表解析。
 *
 * 支持**两种最常见**的表格形态，自动识别：
 *
 * ### A. 一行一门课（教务处导出、自己填模板）
 * ```
 * 课程名,教师,教室,星期,开始节次,节数,周次
 * 高等数学,张建国,3教305,周一,1,2,1-16
 * ```
 * 表头可以按关键词识别（顺序随便调、少几列也能用）；没有表头就按上面的默认顺序。
 *
 * ### B. 网格（直接从 Excel/WPS 课表里框选复制，制表符分隔）
 * ```
 * 节次	周一	周二	周三
 * 1	高等数学@3教305
 * 2	高等数学@3教305	线性代数@3教207
 * ```
 * 同一格往下重复（或合并单元格留空）会被合并成**连堂课**（span 自动累加）。
 *
 * 解析器只做"文本 → 课程列表"，不碰存储、不碰 UI，方便单测与后续接 Excel/ics。
 */
object TimetableCsv {

    // ---------------------------------------------------------------- 入口

    fun parse(text: String): ImportResult {
        val clean = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val lines = clean.split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        if (lines.isEmpty()) return ImportResult(emptyList(), listOf("文件是空的"))

        val delimiter = detectDelimiter(lines.first())
        val table = lines.map { splitLine(it, delimiter) }

        return if (looksLikeGrid(table)) {
            parseGrid(table)
        } else {
            parseRows(table, delimiter)
        }
    }

    // ---------------------------------------------------------------- A. 一行一门课

    private fun parseRows(table: List<List<String>>, delimiter: Char): ImportResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        // 表头识别：首行若含"课程/名称/科目"就当表头，用来定位列
        val hasHeader = table.first().any { it.contains("课程") || it.contains("名称") || it.contains("科目") }
        val cols = if (hasHeader) mapColumns(table.first()) else DEFAULT_COLUMNS
        val body = if (hasHeader) table.drop(1) else table

        val courses = mutableListOf<Course>()
        body.forEachIndexed { i, cells ->
            val lineNo = i + if (hasHeader) 2 else 1
            val name = cells.getOrNull(cols.name).orEmpty().trim()
            if (name.isEmpty()) return@forEachIndexed

            val day = parseDay(cells.getOrNull(cols.day).orEmpty())
            if (day == null) {
                errors.add("第 $lineNo 行：星期「${cells.getOrNull(cols.day).orEmpty()}」看不懂（写 周一 / 星期一 / 1）")
                return@forEachIndexed
            }
            val start = cells.getOrNull(cols.start).orEmpty().trim().toIntOrNull()
            if (start == null || start < 1) {
                errors.add("第 $lineNo 行：开始节次「${cells.getOrNull(cols.start).orEmpty()}」不是正整数")
                return@forEachIndexed
            }
            val spanRaw = cells.getOrNull(cols.span).orEmpty().trim()
            val span = spanRaw.toIntOrNull()?.coerceAtLeast(1) ?: 1
            val weeks = WeekSet.parse(cells.getOrNull(cols.weeks).orEmpty())

            if (start + span - 1 > DemoTimetable.BUILTIN_PERIODS.size) {
                warnings.add("第 $lineNo 行：$name 占到第 ${start + span - 1} 节，超出内置节次表，已按最大节次截断")
            }
            courses.add(
                buildCourse(
                    name = name,
                    teacher = cells.getOrNull(cols.teacher).orEmpty().trim(),
                    room = cells.getOrNull(cols.room).orEmpty().trim(),
                    day = day,
                    start = start,
                    span = span,
                    weeks = weeks,
                    key = "$day-$start-$name"
                )
            )
        }

        if (courses.isEmpty() && errors.isEmpty()) errors.add("没解析出任何课程——检查一下列顺序，或改用模板")
        return ImportResult(courses, errors, warnings, ImportLayout.ROW)
    }

    // ---------------------------------------------------------------- B. 网格形态

    /**
     * 判断是不是「网格」形态。
     *
     * 不能只看表头里有没有"周"字 —— 「一行一门课」的表头（课程名,教师,…周次）也含"周"。
     * 判据：① 首列是"节次/时间"且后面至少两列能认成星期；或 ② 第二行首列是小的节次数字、
     * 且该行在多个星期列上都有内容（无表头的网格）。
     */
    private fun looksLikeGrid(table: List<List<String>>): Boolean {
        val header = table.first()
        if (header.size < 3) return false
        val firstCell = header.first().trim()
        if (parseDay(firstCell) != null) return false

        val dayColumns = header.drop(1).count { parseDay(it) != null }
        if ((firstCell.contains("节") || firstCell.contains("时间")) && dayColumns >= 2) return true

        val second = table.getOrNull(1) ?: return false
        if (second.size != header.size) return false
        val secondFirst = second.firstOrNull()?.trim()?.toIntOrNull() ?: return false
        val filled = second.drop(1).count { it.isNotBlank() }
        return secondFirst in 1..20 && filled >= 2
    }

    private fun parseGrid(table: List<List<String>>): ImportResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val header = table.first()

        // 有表头 → 按表头认得；没表头 → 第 2..7 列按 周一..周六 排（最常见的排法）
        val hasHeader = header.any { it.contains("节") || it.contains("时间") } ||
            header.drop(1).count { parseDay(it) != null } >= 2
        val dayOfColumn: Map<Int, Int> = if (hasHeader) {
            header.mapIndexedNotNull { index, cell ->
                if (index == 0) null else parseDay(cell)?.let { index to it }
            }.toMap()
        } else {
            header.indices.drop(1).mapIndexed { idx, col -> col to (idx + 1).coerceAtMost(7) }.toMap()
        }
        if (dayOfColumn.isEmpty()) return ImportResult(emptyList(), listOf("网格表头里没认出星期列"))

        // 每列当前"活跃"的课程下标，用于把合并单元格/重复格并成连堂
        val active = HashMap<Int, Int>()
        val courses = mutableListOf<Course>()

        val body = if (hasHeader) table.drop(1) else table
        body.forEachIndexed { i, cells ->
            val lineNo = i + if (hasHeader) 2 else 1
            val period = cells.firstOrNull()?.trim()?.toIntOrNull()
            if (period == null) {
                if (cells.any { it.isNotBlank() }) errors.add("第 $lineNo 行：首列不是节次数字，跳过")
                return@forEachIndexed
            }
            dayOfColumn.forEach { (col, day) ->
                val raw = cells.getOrNull(col).orEmpty().trim()
                val prevIndex = active[col]
                if (raw.isEmpty()) {
                    // 空 = 上一门课的延续（合并单元格复制出来的样子）
                    if (prevIndex != null && courses[prevIndex].startPeriod + courses[prevIndex].span == period) {
                        courses[prevIndex] = courses[prevIndex].copy(span = courses[prevIndex].span + 1)
                    }
                    return@forEach
                }
                val (name, room) = splitNameRoom(raw)
                if (prevIndex != null && courses[prevIndex].name == name &&
                    courses[prevIndex].startPeriod + courses[prevIndex].span == period
                ) {
                    courses[prevIndex] = courses[prevIndex].copy(span = courses[prevIndex].span + 1)
                    return@forEach
                }
                courses.add(
                    buildCourse(
                        name = name,
                        teacher = "",
                        room = room,
                        day = day,
                        start = period,
                        span = 1,
                        weeks = emptyList(),
                        key = "$day-$period-$name"
                    )
                )
                active[col] = courses.lastIndex
            }
        }
        if (courses.isEmpty()) errors.add("网格里没解析出课程")
        warnings.add("网格形态认不出周次，已按「每周」处理；要精确周次请用「一行一门课」模板")
        return ImportResult(courses, errors, warnings, ImportLayout.GRID)
    }

    /** `高等数学@3教305` / `高等数学(3教305)` / `高等数学 3教305` → (课程名, 教室) */
    private fun splitNameRoom(raw: String): Pair<String, String> {
        for (sep in listOf("@", "／", "/", "（", "(", "·", " - ")) {
            val at = raw.indexOf(sep)
            if (at > 0) {
                val name = raw.substring(0, at).trim()
                var room = raw.substring(at + sep.length).trim()
                room = room.trim(')', '）').trim()
                if (name.isNotEmpty()) return name to room
            }
        }
        return raw.trim() to ""
    }

    // ---------------------------------------------------------------- 表头映射

    private data class Columns(val name: Int, val teacher: Int, val room: Int, val day: Int, val start: Int, val span: Int, val weeks: Int)

    private val DEFAULT_COLUMNS = Columns(0, 1, 2, 3, 4, 5, 6)

    private fun mapColumns(header: List<String>): Columns {
        fun find(vararg keys: String): Int =
            header.indexOfFirst { cell -> keys.any { cell.contains(it) } }

        val byName = find("课程", "名称", "科目")
        val mapped = Columns(
            name = if (byName >= 0) byName else 0,
            teacher = find("教师", "老师", "授课").takeIf { it >= 0 } ?: 1,
            room = find("教室", "地点").takeIf { it >= 0 } ?: 2,
            day = find("星期", "周几", "周次").takeIf { it >= 0 && it != find("周次") } ?: 3,
            start = find("开始", "起始", "第几节", "节次").takeIf { it >= 0 } ?: 4,
            span = find("节数", "连堂", "时长").takeIf { it >= 0 } ?: 5,
            weeks = find("周次", "周数", "上课周").takeIf { it >= 0 } ?: 6
        )
        // 「星期」和「周次」都含"周"，按上一步的 find 顺序可能撞车，这里做一次纠正
        val dayCol = header.indexOfFirst { it.contains("星期") || it.contains("周几") || it.trim() == "周" }
        val weekCol = header.indexOfFirst { it.contains("周次") || it.contains("周数") || it.contains("上课周") }
        return mapped.copy(
            day = if (dayCol >= 0) dayCol else mapped.day,
            weeks = if (weekCol >= 0) weekCol else mapped.weeks
        )
    }

    // ---------------------------------------------------------------- 基础工具

    /** 周几：周一 / 星期一 / 礼拜一 / 1 / 一 都认。 */
    fun parseDay(raw: String): Int? {
        val text = raw.trim().replace("星期", "").replace("周", "").replace("礼拜", "").trim()
        if (text.isEmpty()) return null
        text.toIntOrNull()?.let { return if (it in 1..7) it else null }
        val cn = "一二三四五六日天".indexOfFirst { it == text.first() }
        if (cn >= 0) return cn + 1
        // 英文缩写，兼容从 Google Calendar 复制的情况
        val en = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")
        val lower = raw.lowercase()
        en.forEachIndexed { i, key -> if (lower.startsWith(key)) return i + 1 }
        return null
    }

    /** 取第一个有内容的字符猜分隔符（Excel 复制出来是制表符，手填模板是逗号）。 */
    fun detectDelimiter(line: String): Char {
        val candidates = listOf(',', '\t', ';', '，')
        return candidates.maxByOrNull { line.count { c -> c == it } }?.takeIf { line.count { c -> c == it } > 0 } ?: ','
    }

    /** 引号感知的按分隔符切分（`"a,b",c` → [a,b] [c]）。 */
    fun splitLine(line: String, delimiter: Char): List<String> {
        val cells = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' -> {
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        sb.append('"')
                        i++
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                ch == delimiter && !inQuotes -> {
                    cells.add(sb.toString())
                    sb.setLength(0)
                }
                else -> sb.append(ch)
            }
            i++
        }
        cells.add(sb.toString())
        return cells.map { it.trim() }
    }

    // ---------------------------------------------------------------- 颜色与构造

    private val PALETTE = listOf(
        0xFF4F9E80.toInt(), 0xFFC2953F.toInt(), 0xFF6B8CAE.toInt(),
        0xFFA97B9E.toInt(), 0xFF8E9B5A.toInt(), 0xFFB07A63.toInt()
    )

    /** 同一门课永远同色（按名字哈希），这样导入后一眼能分辨科目。 */
    fun colorFor(name: String): Int =
        PALETTE[((name.hashCode() % PALETTE.size) + PALETTE.size) % PALETTE.size]

    private fun buildCourse(
        name: String,
        teacher: String,
        room: String,
        day: Int,
        start: Int,
        span: Int,
        weeks: List<Int>,
        key: String
    ): Course = Course(
        id = "csv-" + key.hashCode().toUInt().toString(16),
        name = name,
        teacher = teacher,
        room = room,
        dayOfWeek = day,
        startPeriod = start,
        span = span,
        weeks = weeks,
        color = colorFor(name)
    )

    // ---------------------------------------------------------------- 模板

    private const val BOM = "\uFEFF"

    /** 模板 A：一行一门课（推荐，能写清周次）。 */
    fun templateRows(): String = BOM + buildString {
        appendLine("课程名,教师,教室,星期,开始节次,节数,周次")
        appendLine("高等数学,张建国,3教305,周一,1,2,1-16")
        appendLine("大学物理,李文博,4教201,周一,3,2,1-16")
        appendLine("电路原理,王海涛,2教108,周三,3,2,1-16")
        appendLine("数字电子技术实验,赵晓东,实验楼B204,周二,6,3,1-8单")
        appendLine("大学英语,Amelia,5教112,周五,6,2,1-16")
        append("公选课·人工智能导论,杨帆,6教301,周二,13,2,9-16")
    }

    /** 模板 B：网格（直接从 Excel 课表复制出来的样子，周次认不出、按每周处理）。 */
    fun templateGrid(): String = BOM + buildString {
        appendLine("节次\t周一\t周二\t周三\t周四\t周五")
        appendLine("1\t高等数学@3教305\t线性代数@3教207\t大学英语@5教112\t信号与系统@3教308\t概率论@3教207")
        appendLine("2\t高等数学@3教305\t线性代数@3教207\t大学英语@5教112\t信号与系统@3教308\t概率论@3教207")
        appendLine("3\t大学物理@4教201\t高等数学@3教305\t电路原理@2教108\t大学物理@4教201\t自动控制原理@2教305")
        appendLine("4\t大学物理@4教201\t高等数学@3教305\t电路原理@2教108\t大学物理@4教201\t自动控制原理@2教305")
        append("6\t\t数字电子技术实验@实验楼B204\t复变函数@3教211\t习近平新时代思想概论@1教101\t大学英语@5教112")
    }
}
