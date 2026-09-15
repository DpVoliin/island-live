package dev.dpvoliin.islandtimetable

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * 最小 **.xlsx** 读取器 —— 自己写，不引 POI / FastExcel。
 *
 * ## 为什么自己写
 * - 我们只需要"把表读成文本网格"这一件事，POI 是几十 MB 的解析框架，移动端不划算
 * - 零第三方依赖 = 许可面最干净、APK 不变大、小构建机也扛得住
 *
 * ## xlsx 是什么
 * 一个 zip：共享字符串在 `xl/sharedStrings.xml`，单元格在 `xl/worksheets/sheetN.xml`，
 * 每个单元格 `<c r="A1" t="s"><v>5</v></c>`（`t="s"` 表示 v 是共享字符串下标）。
 *
 * ## 关键设计：转成 TSV 复用已有的解析
 * 把工作表读成"制表符分隔的文本"后**直接交给 [TimetableCsv]** ——
 * 于是"一行一门课"与"课表网格"两种形态、表头关键词识别、周次语法、星期写法**全部复用**，一行都不重复实现。
 *
 * ## 合并单元格（课表网格里极常见）
 * **不需要特殊处理**：xlsx 只在合并区左上角存值，其余格子在 XML 里压根不存在 →
 * 转成 TSV 就是"空格子"，而 [TimetableCsv] 的网格解析本来就把"下一行同列为空"当作上一门课的延续，
 * 会自然并成**连堂课**（span 累加）。
 */
object TimetableXlsx {

    private const val ENTRY_SHARED = "xl/sharedStrings.xml"
    private const val ENTRY_WORKBOOK = "xl/workbook.xml"
    private const val ENTRY_RELS = "xl/_rels/workbook.xml.rels"

    fun parse(bytes: ByteArray): ImportResult {
        if (!TimetableImport.isZip(bytes)) {
            return fail("这不是 xlsx 文件（缺少 zip 头）")
        }
        val entries = try {
            readEntries(bytes)
        } catch (t: Throwable) {
            return fail(
                "读取 xlsx 失败：${t.javaClass.simpleName}${t.message?.let { " · $it" } ?: ""}\n" +
                    "若是加密/受保护的工作簿，请先另存为无密码的 .xlsx 或 .csv"
            )
        }
        val shared = entries[ENTRY_SHARED]
            ?.let { runCatching { parseSharedStrings(it) }.getOrDefault(emptyList()) }
            ?: emptyList()
        val sheetPath = pickSheet(entries) ?: return fail("xlsx 里找不到工作表（xl/worksheets/*.xml）")
        val sheetXml = entries[sheetPath] ?: return fail("读不到工作表：$sheetPath")

        val tsv = try {
            sheetToTsv(sheetXml, shared)
        } catch (t: Throwable) {
            return fail("解析工作表失败：${t.javaClass.simpleName}${t.message?.let { " · $it" } ?: ""}")
        }
        if (tsv.isBlank()) return fail("工作表里没有内容（选了空表？）")

        val base = TimetableCsv.parse(tsv)
        return base.copy(format = TimetableImport.FORMAT_XLSX)
    }

    private fun fail(message: String) =
        ImportResult(emptyList(), listOf(message), format = TimetableImport.FORMAT_XLSX)

    // ---------------------------------------------------------------- zip

    /** 只解出需要的那几个 xml，图片/主题等一概不读（省内存）。 */
    private fun readEntries(bytes: ByteArray): MutableMap<String, ByteArray> {
        val out = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                if (keep(name)) out[name] = zip.readBytes()
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return out
    }

    private fun keep(name: String): Boolean =
        name == ENTRY_SHARED || name == ENTRY_WORKBOOK || name == ENTRY_RELS ||
            (name.startsWith("xl/worksheets/") && name.endsWith(".xml"))

    /**
     * 选出要读的工作表：优先 `workbook.xml` + `_rels` 指定**第一张**表；
     * 拿不到就按 sheet 序号取第一张（注意 sheet10 不能排在 sheet2 前面，所以按数字排）。
     */
    private fun pickSheet(entries: Map<String, ByteArray>): String? {
        val candidates = entries.keys
            .filter { it.startsWith("xl/worksheets/") && it.endsWith(".xml") && !it.contains("_rels") }
        if (candidates.isEmpty()) return null

        val workbook = entries[ENTRY_WORKBOOK]
        val rels = entries[ENTRY_RELS]
        if (workbook != null && rels != null) {
            val firstRid = firstSheetRid(workbook)
            if (firstRid != null) {
                val target = relTarget(rels, firstRid)
                if (target != null) {
                    val normalized = normalizeTarget(target)
                    if (entries.containsKey(normalized)) return normalized
                }
            }
        }
        return candidates.sortedBy { sheetOrdinal(it) }.firstOrNull()
    }

    private fun firstSheetRid(workbook: ByteArray): String? = try {
        val parser = Xml.newPullParser()
        parser.setInput(ByteArrayInputStream(workbook), null)
        var event = parser.eventType
        var found: String? = null
        while (event != XmlPullParser.END_DOCUMENT && found == null) {
            if (event == XmlPullParser.START_TAG && parser.name == "sheet") {
                found = parser.getAttributeValue(null, "r:id")
                    ?: parser.getAttributeValue(null, "id")
                    ?: attributeEndingWith(parser, "id")
            }
            event = parser.next()
        }
        found
    } catch (t: Throwable) {
        null
    }

    private fun relTarget(rels: ByteArray, rid: String): String? = try {
        val parser = Xml.newPullParser()
        parser.setInput(ByteArrayInputStream(rels), null)
        var event = parser.eventType
        var found: String? = null
        while (event != XmlPullParser.END_DOCUMENT && found == null) {
            if (event == XmlPullParser.START_TAG && parser.name == "Relationship" &&
                parser.getAttributeValue(null, "Id") == rid
            ) {
                found = parser.getAttributeValue(null, "Target")
            }
            event = parser.next()
        }
        found
    } catch (t: Throwable) {
        null
    }

    private fun attributeEndingWith(parser: XmlPullParser, suffix: String): String? {
        for (i in 0 until parser.attributeCount) {
            val name = parser.getAttributeName(i)
            if (name == suffix || name.endsWith(":$suffix")) return parser.getAttributeValue(i)
        }
        return null
    }

    /** `worksheets/sheet1.xml` / `/xl/worksheets/sheet1.xml` → `xl/worksheets/sheet1.xml` */
    private fun normalizeTarget(target: String): String {
        val t = target.removePrefix("/")
        return when {
            t.startsWith("xl/") -> t
            t.startsWith("worksheets/") -> "xl/$t"
            else -> "xl/$t"
        }
    }

    private fun sheetOrdinal(path: String): Int =
        path.substringAfterLast("sheet").substringBefore('.').toIntOrNull() ?: Int.MAX_VALUE

    // ---------------------------------------------------------------- 共享字符串

    /** `<sst><si><t>文本</t></si><si><r><t>富</t></r><r><t>文本</t></r></si></sst>` —— 富文本要拼接。 */
    private fun parseSharedStrings(xml: ByteArray): List<String> {
        val list = mutableListOf<String>()
        val parser = Xml.newPullParser()
        parser.setInput(ByteArrayInputStream(xml), null)
        var event = parser.eventType
        val buffer = StringBuilder()
        var inItem = false
        var inText = false
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "si" -> {
                        inItem = true
                        buffer.setLength(0)
                    }
                    "t" -> inText = true
                }

                XmlPullParser.TEXT -> if (inItem && inText) buffer.append(parser.text)

                XmlPullParser.END_TAG -> when (parser.name) {
                    "t" -> inText = false
                    "si" -> {
                        list.add(buffer.toString())
                        inItem = false
                    }
                }
            }
            event = parser.next()
        }
        return list
    }

    // ---------------------------------------------------------------- 工作表 → TSV

    /**
     * 读成 TSV：
     * - 行/列位置取自单元格引用（`C7`），所以**空行空列不会错位**
     * - 只取文本；数值原样（`1` 而不是 `1.0`）；公式取缓存结果
     */
    private fun sheetToTsv(xml: ByteArray, shared: List<String>): String {
        val rows = sortedMapOf<Int, MutableMap<Int, String>>()
        val parser = Xml.newPullParser()
        parser.setInput(ByteArrayInputStream(xml), null)

        var event = parser.eventType
        var rowIndex = 0
        var colIndex = -1
        var cellType: String? = null
        var inValue = false
        val buffer = StringBuilder()

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "row" -> {
                        rowIndex = parser.getAttributeValue(null, "r")?.toIntOrNull()
                            ?: (rowIndex + 1)
                        rows.getOrPut(rowIndex) { mutableMapOf() }
                    }

                    "c" -> {
                        cellType = parser.getAttributeValue(null, "t")
                        val ref = parser.getAttributeValue(null, "r")
                        colIndex = if (ref != null) columnIndex(ref) else colIndex + 1
                        buffer.setLength(0)
                    }

                    "v", "t" -> inValue = true
                }

                XmlPullParser.TEXT -> if (inValue && rowIndex > 0 && colIndex >= 0) {
                    buffer.append(parser.text)
                }

                XmlPullParser.END_TAG -> when (parser.name) {
                    "v", "t" -> {
                        if (inValue && rowIndex > 0 && colIndex >= 0) {
                            val raw = buffer.toString()
                            val text = when (cellType) {
                                "s" -> shared.getOrNull(raw.trim().toIntOrNull() ?: -1).orEmpty()
                                else -> raw
                            }
                            if (text.isNotBlank()) {
                                rows.getOrPut(rowIndex) { mutableMapOf() }[colIndex] = text.trim()
                            }
                        }
                        inValue = false
                        buffer.setLength(0)
                    }

                    "c" -> {
                        cellType = null
                        colIndex = -1
                    }
                }
            }
            event = parser.next()
        }

        if (rows.isEmpty()) return ""
        val maxColumn = rows.values.maxOf { cells -> cells.keys.maxOrNull() ?: 0 }
        return buildString {
            rows.forEach { (_, cells) ->
                for (col in 0..maxColumn) {
                    if (col > 0) append('\t')
                    append(cells[col].orEmpty())
                }
                append('\n')
            }
        }
    }

    /** `A1` → 0、`B2` → 1、`AB12` → 27（列字母转 0 基下标）。 */
    private fun columnIndex(ref: String): Int {
        var col = 0
        for (ch in ref) {
            if (!ch.isLetter()) break
            col = col * 26 + (ch.uppercaseChar() - 'A' + 1)
        }
        return (col - 1).coerceAtLeast(0)
    }
}
