package dev.dpvoliin.islandtimetable

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.provider.OpenableColumns
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

/**
 * 导入课表。
 *
 * 支持格式（**按文件内容自动分派**，见 [TimetableImport]）：
 * - **.xlsx**（Excel 原生）：自研最小读取器 [TimetableXlsx]，无需第三方库
 * - **.csv / .txt**：一行一门课，或从 Excel 课表框选复制出来的网格（[TimetableCsv]）
 * - **JSON**（[TimetableJson]）：小爱课程表众包脚本的输出，或课表 App 导出的 JSON —— 可直接**粘贴**
 *
 * 隐私：文件**只在本机解析**，解析结果写进 app 私有目录，不联网、不上传。
 */
class ImportActivity : AppCompatActivity() {

    private lateinit var tvPreview: TextView
    private lateinit var etWeek: TextInputEditText
    private lateinit var btnConfirm: MaterialButton
    private lateinit var etJson: EditText

    /** 导出/备份：把当前课表存成 JSON 文件。 */
    private val exportBackup =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri == null) return@registerForActivityResult
            val text = TimetableStore.exportText(this)
            if (text.isBlank()) {
                toast("还没有课表可导出")
                return@registerForActivityResult
            }
            val ok = runCatching {
                contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                true
            }.getOrDefault(false)
            toast(if (ok) "已导出 · 换机/重装后用「导入课表」选它即可还原" else "保存失败，换个目录试试")
        }

    /** 当前解析结果（点了「确认导入」才落盘）。 */
    private var parsed: ImportResult? = null

    private val pickFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            val name = displayName(uri)
            val bytes = runCatching {
                contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()
            if (bytes == null || bytes.isEmpty()) {
                toast("读不到文件内容（换个文件管理器再试）")
                return@registerForActivityResult
            }
            applyParsed(TimetableImport.parse(name, bytes))
        }

    private val saveTemplate =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            if (uri == null) return@registerForActivityResult
            val ok = runCatching {
                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(TimetableCsv.templateRows().toByteArray(Charsets.UTF_8))
                }
                true
            }.getOrDefault(false)
            toast(if (ok) "模板已保存 · 用 Excel/WPS 填好再回来导入" else "保存失败，换个目录试试")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_import)
        SystemBars.apply(this)
            // 让出状态栏/手势条高度（targetSdk 36 强边到边，
            // 不处理内容会顶进状态栏、底部被手势条压住）
            findViewById<View>(R.id.rootImport).applySystemBarInsets()
        findViewById<android.view.ViewGroup>(R.id.rootImport).enablePressFeedbackDeep()

        tvPreview = findViewById(R.id.tvImportPreview)
        etWeek = findViewById(R.id.etWeek)
        etJson = findViewById(R.id.etJson)

        findViewById<MaterialButton>(R.id.btnJsonSample).setOnClickListener {
            etJson.setText(TimetableJson.sample())
            etJson.setSelection(etJson.length())
        }
        findViewById<MaterialButton>(R.id.btnJsonParse).setOnClickListener {
            val text = etJson.text?.toString().orEmpty()
            if (text.isBlank()) {
                toast("先粘贴 JSON，或点「填入示例」看格式")
            } else {
                if (!tryRestoreBackup(text)) applyParsed(TimetableImport.parseText(text))
            }
        }
        btnConfirm = findViewById(R.id.btnImportConfirm)

        findViewById<MaterialButton>(R.id.btnPickFile).setOnClickListener {
            // 不限 mime：不同文件管理器对 csv/xlsx 的报法不一样（甚至报成 octet-stream），
            // 一律放行，交给 TimetableImport 按内容分派
            pickFile.launch(arrayOf("*/*"))
        }
        findViewById<MaterialButton>(R.id.btnSaveTemplate).setOnClickListener {
            saveTemplate.launch("课表模板.csv")
        }
        btnConfirm.setOnClickListener { confirmImport() }
        findViewById<MaterialButton>(R.id.btnExportBackup).setOnClickListener {
            if (!TimetableStore.exists(this)) {
                toast("还没有课表可导出")
            } else {
                exportBackup.launch("岛课表备份-" + java.time.LocalDate.now() + ".json")
            }
        }
        findViewById<MaterialButton>(R.id.btnImportReset).setOnClickListener {
            DemoTimetable.resetToDemo(this)
            toast("已恢复内置演示课表")
            finish()
        }

        // 周次防错：**没设置过学期起始日时绝不预填**。
        // 踩过：原来预填 1，用户直接点导入 → 整表周次错位 → 表现为"缺课 / 课位置不对"。
        if (DemoTimetable.termStartDate.isNotEmpty()) {
            etWeek.setText(DemoTimetable.computeWeek().toString())
        } else {
            etWeek.setText("")
        }

        findViewById<MaterialButton>(R.id.btnClipboard).setOnClickListener {
            val cm = getSystemService(android.content.ClipboardManager::class.java)
            val text = cm?.primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.coerceToText(this)?.toString()
            if (text.isNullOrBlank()) {
                toast("剪贴板是空的")
            } else if (tryRestoreBackup(text)) {
                toast("已从备份还原整份课表")
            } else {
                applyParsed(TimetableImport.parseText(text))
            }
        }

        renderPreview(null)
        // 从"外部打开/分享"进来的内容（文件 Urix、HTML、纯文本）
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    /** 处理外部打开/分享：文件 → 按内容分派；HTML → 交给教务页的 WebView 提取器；纯文本 → 直接解析。 */
    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        @Suppress("DEPRECATION")
        val uri = intent.data ?: intent.getParcelableExtra(Intent.EXTRA_STREAM)
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
        when {
            uri != null -> loadUri(uri)
            !sharedText.isNullOrBlank() -> {
                if (!tryRestoreBackup(sharedText)) applyParsed(TimetableImport.parseText(sharedText))
            }
        }
    }

    /** 是备份文件就整体还原，返回是否处理了。 */
    private fun tryRestoreBackup(text: String): Boolean {
        if (!TimetableStore.looksLikeBackup(text)) return false
        val restored = TimetableStore.importFrom(this, text) ?: return false
        applyParsed(
            ImportResult(
                courses = restored.courses,
                warnings = listOf("已从备份还原：学期起始日 " + restored.termStartDate + "，共 " + restored.courses.size + " 门课"),
                format = "备份还原"
            )
        )
        return true
    }

    private fun loadUri(uri: Uri) {
        val name = displayName(uri)
        val bytes = runCatching {
            contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
        if (bytes == null || bytes.isEmpty()) {
            toast("读不到文件内容（换个文件管理器再试）")
            return
        }
        val whole = runCatching { String(bytes, Charsets.UTF_8) }.getOrDefault("")
        if (tryRestoreBackup(whole)) {
            toast("已从备份还原整份课表")
            return
        }
        val head = String(bytes.copyOf(minOf(bytes.size, 4096)), Charsets.UTF_8).lowercase()
        if (head.contains("<html") || head.contains("<table")) {
            // 教务课表页"另存为 HTML"的情况：交给教务页复用同一套表格提取器
            startActivity(
                Intent(this, JwxtActivity::class.java)
                    .putExtra(JwxtActivity.EXTRA_LOCAL_HTML, uri.toString())
            )
            toast("检测到 HTML 课表，正在用页面解析…")
            return
        }
        applyParsed(TimetableImport.parse(name, bytes))
    }

    // ---------------------------------------------------------------- 解析与预览

    /** 解析完把页面滚到底 —— 否则「确认导入」被上面的说明/粘贴框挤到屏幕外，用户会以为按钮没了。 */
    private fun scrollToConfirm() {
        val root = findViewById<View>(R.id.rootImport)
        // 注意：View.post 收的是 Runnable，**没有 it**（写成 { it -> … } 会 Unresolved reference 'it'）
        root.post { (root as? android.widget.ScrollView)?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun applyParsed(result: ImportResult) {
        // 顺手把 HTML 实体解开（教务页文本常带 &ldquo; 这类实体）
        val cleaned = result.copy(
            courses = result.courses.map { c ->
                c.copy(
                    name = TextFix.decodeEntities(c.name),
                    room = TextFix.decodeEntities(c.room),
                    teacher = TextFix.decodeEntities(c.teacher)
                )
            }
        )
        parsed = cleaned
        btnConfirm.isEnabled = cleaned.ok
        renderPreview(cleaned)
        scrollToConfirm()
    }

    private fun renderPreview(result: ImportResult?) {
        if (result == null) {
            tvPreview.text = "还没选内容 · 支持 Excel / CSV / TXT / JSON / ICS"
            return
        }
        val sb = StringBuilder()
        val layoutName = if (result.layout == ImportLayout.GRID) "网格形态" else "一行一门课"
        sb.append("来源 ${result.format} · 识别为「$layoutName」\n")
        sb.append("解析出 ${result.courses.size} 条 · 周次 ${result.weekRange}\n")
        if (result.suggestedTermStart.isNotBlank()) {
            sb.append("第 1 周按 ${result.suggestedTermStart} 算（文件里最早一节课所在周）\n")
        }

        val preview = result.courses.take(6)
        preview.forEach { c ->
            val day = DemoTimetable.BUILTIN_DAY_LABELS.getOrElse(c.dayOfWeek - 1) { "周${c.dayOfWeek}" }
            val span =
                if (c.span > 1) "第${c.startPeriod}–${c.startPeriod + c.span - 1}节" else "第${c.startPeriod}节"
            val room = if (c.room.isEmpty()) "" else " @${c.room}"
            sb.append("  · $day $span ${c.name}$room (${WeekSet.describe(c.weeks)})\n")
        }
        if (result.courses.size > preview.size) sb.append("  … 还有 ${result.courses.size - preview.size} 条\n")

        result.warnings.forEach { sb.append("⚠ $it\n") }
        result.errors.take(5).forEach { sb.append("✗ $it\n") }
        if (result.errors.size > 5) sb.append("✗ … 另有 ${result.errors.size - 5} 条问题\n")

        tvPreview.text = sb.toString().trimEnd()
    }

    // ---------------------------------------------------------------- 落盘

    private fun confirmImport() {
        val result = parsed ?: return
        if (!result.ok) {
            toast("还没有可导入的内容")
            return
        }
        val rawWeek = etWeek.text?.toString()?.trim().orEmpty()
        // 空值不再默默当 1（那正是"缺课"的来源）—— 必须用户明确填一次
        if (rawWeek.isEmpty() && result.suggestedTermStart.isBlank()) {
            toast("先填「当前教学周」：教务里显示的第几周就是几")
            return
        }
        val week = rawWeek.toIntOrNull()?.coerceIn(1, WeekSet.MAX_WEEK) ?: 1
        // ICS 自带绝对日期：用解析器算出的学期起始日（文件里最早一节课所在周 = 第 1 周），
        // 其它格式照旧按用户填的"现在是第几周"反推 —— 这样两边的周次才对得齐。
        val suggested = result.suggestedTermStart
        val termStart = if (suggested.isNotBlank()) suggested else DemoTimetable.deriveTermStart(week)
        val timetable = Timetable(
            courses = result.courses,
            periods = DemoTimetable.BUILTIN_PERIODS,
            dayLabels = DemoTimetable.BUILTIN_DAY_LABELS,
            termStartDate = termStart,
            source = TimetableImport.sourceKey(result.format),
            importedAt = System.currentTimeMillis()
        )
        DemoTimetable.importTimetable(this, timetable)
        val suffix = if (suggested.isNotBlank()) {
            " · 学期起始日 $termStart（按 ICS 推算）"
        } else {
            " · 现在是第 $week 周"
        }
        val periodsHint = if (DemoTimetable.periodsArePlaceholder) " · 记得去侧栏「作息时间」填本校作息（否则显示的时间是默认占位）" else ""
        toast("已导入 ${result.courses.size} 门课$suffix$periodsHint")
        finish()
    }

    // ---------------------------------------------------------------- 工具

    /** 取文件显示名（用于按后缀分派；拿不到就给空串，反正还有 zip 头兜底）。 */
    private fun displayName(uri: Uri): String = runCatching {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull().orEmpty()

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}
