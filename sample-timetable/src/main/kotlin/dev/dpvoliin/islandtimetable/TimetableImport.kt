package dev.dpvoliin.islandtimetable

import java.nio.charset.Charset

/**
 * 所有导入格式的**统一结果**（UI 与存储只认它）。
 *
 * 这样"新增一种导入格式" = 新增一个 `parse()`，界面/存储/课表逻辑一行都不用改。
 */
data class ImportResult(
    val courses: List<Course>,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val layout: ImportLayout = ImportLayout.ROW,
    /** 人类可读的来源，比如 `CSV/TXT`、`Excel (.xlsx)` —— 预览界面会显示。 */
    val format: String = TimetableImport.FORMAT_CSV,
    /**
     * 解析器**自己算出的学期起始日**（ISO `yyyy-MM-dd`），空 = 没建议。
     *
     * 目前只有 [TimetableIcs] 会给：ICS 里是绝对日期，把它最早一节课所在周当第 1 周，
     * 导入时用它覆盖"现在是第几周"的推算，两边才对得齐。其它格式留空，照旧由用户填的周次推算。
     */
    val suggestedTermStart: String = ""
) {
    val ok: Boolean get() = courses.isNotEmpty()

    /** 周次范围描述（预览用）。 */
    val weekRange: String
        get() {
            val all = courses.flatMap { it.weeks }
            return if (all.isEmpty()) "每周" else "${all.min()}–${all.max()} 周"
        }
}

/** 表格形态：一行一门课 / 课表网格。 */
enum class ImportLayout { ROW, GRID }

/**
 * 导入总入口：**按文件内容分派**给具体解析器。
 *
 * | 输入 | 解析器 |
 * |---|---|
 * | 含 `BEGIN:VCALENDAR` | [TimetableIcs]（ICS 日历：导出课表 / 订阅日历） |
 * | 以 `{` / `[` 开头（合法 JSON） | [TimetableJson]（小爱课程表脚本 / 课表 App 导出） |
 * | zip 头（`PK`）或 .xlsx 后缀 | [TimetableXlsx]（最小 xlsx 读取，内部转成 TSV 再复用 CSV 逻辑） |
 * | 老版 .xls（`D0 CF 11 E0`） | 不解析，给出"另存为 .xlsx/.csv"的明确提示 |
 * | 其它（CSV / TXT / 从 Excel 复制保存的文本） | [TimetableCsv] |
 *
 * 编码：UTF-8 优先，出现替换字符（U+FFFD）就回退 **GBK** —— Excel 中文"另存为 CSV"默认是 GBK。
 */
object TimetableImport {

    const val FORMAT_CSV = "CSV/TXT"
    const val FORMAT_XLSX = "Excel (.xlsx)"
    const val FORMAT_JSON = "JSON（小爱课程表脚本 / 课表 App 导出）"
    const val FORMAT_ICS = "ICS 日历 (.ics)"

    private const val FORMAT_XLS = "Excel 97-2003 (.xls，请另存为 .xlsx 或 .csv)"

    /** 老版 .xls（二进制 BIFF，不是 zip）—— 我们没有解析它，给个明确指引而不是"解析失败"。 */
    private fun looksLikeOldXls(name: String, bytes: ByteArray): Boolean {
        if (name.lowercase().endsWith(".xls")) return true
        // BIFF8 的复合文档头：D0 CF 11 E0
        return bytes.size > 4 &&
            bytes[0] == 0xD0.toByte() && bytes[1] == 0xCF.toByte() &&
            bytes[2] == 0x11.toByte() && bytes[3] == 0xE0.toByte()
    }

    fun parse(fileName: String, bytes: ByteArray): ImportResult {
        if (bytes.isEmpty()) return ImportResult(emptyList(), listOf("文件是空的"))
        if (looksLikeOldXls(fileName, bytes)) {
            return ImportResult(
                emptyList(),
                listOf("这是老版 .xls 格式。请用 Excel/WPS「另存为」→ 选 .xlsx 或 .csv 再导入。"),
                format = FORMAT_XLS
            )
        }
        return if (isZip(bytes) || fileName.lowercase().endsWith(".xlsx")) {
            TimetableXlsx.parse(bytes)
        } else {
            parseText(decodeText(bytes))
        }
    }

    /** 内容像 JSON 吗（跳过 BOM 与空白后以 `{` 或 `[` 开头）。 */
    fun looksLikeJson(text: String): Boolean {
        val t = text.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        return t.startsWith("{") || t.startsWith("[")
    }

    /** 落盘用的来源标识（写进 Timetable.source，界面会显示）。 */
    fun sourceKey(format: String): String = when (format) {
        FORMAT_XLSX -> "xlsx"
        FORMAT_JSON -> "json"
        FORMAT_ICS -> "ics"
        else -> "csv"
    }

    /** 内容像 iCalendar 吗（同时有 VCALENDAR 与 VEVENT，避免把普通文本误判成 ICS）。 */
    fun looksLikeIcs(text: String): Boolean =
        text.contains("BEGIN:VCALENDAR", ignoreCase = true) &&
            text.contains("BEGIN:VEVENT", ignoreCase = true)

    /**
     * 按**文本内容**分派 —— 粘贴进来的内容走这里（没有文件名可参考）。
     *
     * 先看是不是 JSON，再当表格文本处理：这样"粘贴小爱课程表脚本的输出"和
     * "粘贴从 Excel 框选复制的内容"都能直接扔进同一个框。
     */
    fun parseText(text: String): ImportResult = when {
        looksLikeJson(text) -> TimetableJson.parse(text)
        looksLikeIcs(text) -> TimetableIcs.parse(text)
        else -> TimetableCsv.parse(text)
    }

    fun isZip(bytes: ByteArray): Boolean =
        bytes.size > 3 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
            (bytes[2] == 0x03.toByte() || bytes[2] == 0x05.toByte() || bytes[2] == 0x07.toByte())

    /**
     * 中文 CSV 的编码坑：Excel「另存为 CSV」在中文 Windows 上是 **GBK**，直接按 UTF-8 读会全是乱码。
     * 先按 UTF-8 解，出现替换字符就回退 GBK。
     */
    fun decodeText(bytes: ByteArray): String {
        val utf8 = String(bytes, Charsets.UTF_8)
        if (!utf8.contains('\uFFFD')) return utf8
        return runCatching { String(bytes, Charset.forName("GBK")) }.getOrDefault(utf8)
    }
}
