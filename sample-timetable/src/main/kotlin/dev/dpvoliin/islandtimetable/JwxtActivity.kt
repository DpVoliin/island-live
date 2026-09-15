package dev.dpvoliin.islandtimetable

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONTokener

/**
 * **教务直连（正方）** —— 在 WebView 里让你自己登录，登录后由本页在你自己的会话里取课表。
 *
 * ## 为什么不直接收账号密码
 * 教务登录几乎都带验证码/短信，硬做自动登录既脆又危险。这里改成：
 * **你像平时一样在网页里登录** → 点「抓取课表」→ 我在**同一个 WebView 会话**里请求接口
 * （新正方的课表接口会带上你的 Cookie）。
 *
 * 隐私：**不保存任何账号密码**，也不把数据发到别处；只把抓到的课表存进 App 私有目录。
 * 网址存在本机偏好里（下次不用重填）。
 *
 * > 唯一需要联网的功能就是这一页。其余导入方式（Excel / CSV / JSON / ICS）全部离线。
 */
class JwxtActivity : AppCompatActivity() {

    private lateinit var etUrl: EditText
    private lateinit var tvStatus: TextView
    private lateinit var webView: WebView
    private lateinit var btnFetch: MaterialButton

    /** 最近一次抓到的**原始返回**（给"复制原始数据"用 —— 排障要靠真数据，不能靠猜）。 */
    private var lastRaw: String = ""

    // ---- 「抓取整个学期」用的状态：自动逐周切换并合并周次 ----
    /** 自动切整学期页后，等页面加载完再解析。 */
    private var pendingWholeTerm = false
    private var pendingLocalParse = false
    private var sweepActive = false
    private var sweepWeek = 0
    private val sweepMerged = LinkedHashMap<String, Course>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_jwxt)
        SystemBars.apply(this)
            // 让出状态栏/手势条高度（targetSdk 36 强边到边，
            // 不处理内容会顶进状态栏、底部被手势条压住）
            findViewById<View>(R.id.rootJwxt).applySystemBarInsets()

        etUrl = findViewById(R.id.etJwxtUrl)
        tvStatus = findViewById(R.id.tvJwxtStatus)
        webView = findViewById(R.id.webJwxt)
        findViewById<android.view.ViewGroup>(R.id.rootJwxt).enablePressFeedbackDeep()
        btnFetch = findViewById(R.id.btnJwxtFetch)

        etUrl.setText(prefs.getString(KEY_URL, "").orEmpty())

        findViewById<MaterialButton>(R.id.btnJwxtOpen).setOnClickListener {
            val url = normalize(etUrl.text?.toString().orEmpty())
            if (url.isEmpty()) {
                toast("先填教务系统网址，例如 https://jw.xxx.edu.cn/jwglxt/")
                return@setOnClickListener
            }
            prefs.edit().putString(KEY_URL, url).apply()
            status("正在打开 $url …… 登录后回到本页点「抓取课表」")
            webView.loadUrl(url)
        }

        // 「操作」面板：默认收起，把高度让给网页（点一下展开，再点收起）
        val actionsBody = findViewById<android.widget.LinearLayout>(R.id.jwxtActions)
        val actionsHeader = findViewById<TextView>(R.id.tvJwxtActions)
        actionsHeader.setOnClickListener {
            val open = actionsBody.visibility == android.view.View.VISIBLE
            androidx.transition.TransitionManager.beginDelayedTransition(
                findViewById(R.id.rootJwxt),
                androidx.transition.AutoTransition().setDuration(200L)
            )
            actionsBody.visibility = if (open) android.view.View.GONE else android.view.View.VISIBLE
            actionsHeader.text = if (open) "操作 ▾ 点开（其它教务 / 复制 / 自检）" else "操作 ▴ 收起"
        }

        findViewById<MaterialButton>(R.id.btnJwxtSample).setOnClickListener { showSample() }
        findViewById<MaterialButton>(R.id.btnJwxtSweep).setOnClickListener { startSweep() }

        // ① 抓取课表：正方走接口、表格模式走当前页（朴素、可预期）
        // 「抓取课表（正方接口）」：恒走新正方 jwglxt 的 JSON 接口，不再做任何自动猜测
        btnFetch.setOnClickListener {
            btnFetch.isEnabled = false
            status("正在取课表……（需要先在这个网页里登录）")
            webView.evaluateJavascript(SCRIPT_FETCH) { raw -> onFetched(raw) }
        }

        // 「抓取当前页（表格）」：恒定解析当前页面的 HTML 表格（强智 / 青果 / URP / 老正方都走这条）
        findViewById<MaterialButton>(R.id.btnJwxtCurrentPage).setOnClickListener {
            btnFetch.isEnabled = false
            status("正在解析当前页面的课表表格…")
            webView.evaluateJavascript(SCRIPT_TABLE) { raw -> onFetched(raw) }
        }

        // ② 抓取整学期课表：单独一个按钮 —— 自动识别整学期页（格子里带周次）→ 直接解析；
        //    若是单周视图 → 自动切到整学期页再解析；识别不了 → 兜底按当前页表格解析
        findViewById<MaterialButton>(R.id.btnJwxtWholeTerm).setOnClickListener {
            btnFetch.isEnabled = false
            startSmartFetch()
        }

        findViewById<MaterialButton>(R.id.btnJwxtCopy).setOnClickListener {
            val text = lastRaw.ifBlank { "还没抓过数据" }
            if (text == "还没抓过数据") {
                toast(text)
                return@setOnClickListener
            }
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("jwxt", text))
            status("已复制原始返回（${text.length} 字）")
        }


        setupWebView()

        val saved = etUrl.text?.toString().orEmpty()
        val localHtml = intent.getStringExtra(EXTRA_LOCAL_HTML)
        if (localHtml != null) {
            webView.settings.allowFileAccess = true
            val target = runCatching {
                val file = java.io.File(cacheDir, "local_timetable.html")
                contentResolver.openInputStream(android.net.Uri.parse(localHtml))?.use { input ->
                    file.outputStream().use { input.copyTo(it) }
                }
                file
            }.getOrNull()
            if (target != null) {
                pendingLocalParse = true
                status("正在解析本地 HTML 课表…")
                webView.loadUrl("file://" + target.absolutePath)
            } else {
                status("读不到这个 HTML 文件")
            }
            return
        }
        if (saved.isNotBlank()) webView.loadUrl(normalize(saved)) else status(INTRO)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true          // 抓取脚本要跑在教务页里（同源，才能带上登录 Cookie）
            domStorageEnabled = true
            userAgentString = userAgentString + " IslandTimetable"
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (btnFetch.isEnabled.not()) return
                if (pendingLocalParse) {
                    pendingLocalParse = false
                    status("本地 HTML 已载入，正在提取课表…")
                    webView.postDelayed({
                        webView.evaluateJavascript(SCRIPT_TABLE) { raw -> onFetched(raw) }
                    }, 600)
                    return
                }
                if (pendingWholeTerm) {
                    pendingWholeTerm = false
                    status("已切到整学期课表，正在解析…")
                    webView.postDelayed({
                        webView.evaluateJavascript(SCRIPT_TABLE) { raw -> onFetched(raw) }
                    }, 800)
                } else {
                    status("页面已加载。登录完成后点「抓取课表」。")
                }
            }
        }
    }

    /** 抓取脚本：先访问课表页读默认学年/学期，再 POST 课表接口，把原文交给 Kotlin 解析。 */
    private fun onFetched(raw: String?) {
        btnFetch.isEnabled = true
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || text == "null") {
            status("没有拿到返回（页面还没加载完？先打开教务页并登录）")
            return
        }
        val payload = runCatching {
            (JSONTokener(text).nextValue() as? String) ?: text
        }.getOrDefault(text)

        lastRaw = payload

        val obj = runCatching { org.json.JSONObject(payload) }.getOrNull()
        if (obj == null) {
            status("返回内容无法识别：${payload.take(160)}")
            return
        }
        if (!obj.optBoolean("ok", false)) {
            val msg = obj.optString("msg").ifBlank { "抓取失败" }
            val body = obj.optString("body")
            status(if (body.isBlank()) msg else "$msg\n接口原文片段：$body")
            return
        }

        val json = obj.optString("json")
        val format = if (obj.optString("mode") == "table") TimetableJwxt.FORMAT_TABLE else TimetableJwxt.FORMAT
        val result = TimetableJwxt.parse(json, format)
        status("学年 ${obj.optString("xnm")} · 学期 ${obj.optString("xqm")} · 解析出 ${result.courses.size} 门课")
        if (!result.ok) {
            status(
                "没解析出课程（可试「抓取整个学期（自动逐周）」，或确认停在课表页）：\n" +
                    (result.errors + result.warnings).joinToString("\n").take(280)
            )
            return
        }
        confirmImport(result)
    }

    private fun confirmImport(result: ImportResult) {
        val week = if (DemoTimetable.termStartDate.isEmpty()) 1 else DemoTimetable.computeWeek()
        val input = EditText(this).apply {
            hint = "当前教学周（1–30）"
            setText(week.toString())
        }
        val lines = result.courses.take(6).joinToString("\n") { c ->
            val day = DemoTimetable.BUILTIN_DAY_LABELS.getOrElse(c.dayOfWeek - 1) { "周${c.dayOfWeek}" }
            val span = if (c.span > 1) "第${c.startPeriod}–${c.startPeriod + c.span - 1}节" else "第${c.startPeriod}节"
            "· $day $span ${TextFix.decodeEntities(c.name)}${if (c.room.isEmpty()) "" else " @" + TextFix.decodeEntities(c.room)}"
        }
        // ⚠️ 内容一长（56 门课 + 告警）用 setMessage 会把对话框顶出屏幕、「导入」按钮就点不到了。
        // 这里改成"限高 + 可滚动的内容 + 按钮固定在对话框底部"。
        val density = resources.displayMetrics.density
        fun px(v: Float) = (v * density + 0.5f).toInt()
        val body = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(px(22f), px(6f), px(22f), 0)
            addView(
                android.widget.TextView(this@JwxtActivity).apply {
                    text = buildString {
                        append(lines)
                        if (result.courses.size > 6) append("\n… 还有 ${result.courses.size - 6} 门")
                        result.warnings.take(6).forEach { append("\n⚠ $it") }
                        if (result.warnings.size > 6) append("\n⚠ … 另有 ${result.warnings.size - 6} 条被跳过的原因")
                        append("\n\n填「现在是第几周」用来推算学期起始日。")
                        append(" 填教务里显示的当前教学周（填错会缺课）")
                    }
                    textSize = 13f
                    setTextColor(getColor(dev.dpvoliin.islandtimetable.R.color.text_secondary))
                    setLineSpacing(px(4f).toFloat(), 1f)
                }
            )
            addView(input)
        }
        val scroller = android.widget.ScrollView(this).apply {
            addView(body)
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.52f).toInt()
            )
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("抓到 ${result.courses.size} 门课")
            .setView(scroller)
            .setPositiveButton("导入") { _, _ ->
                val current = input.text?.toString()?.trim()?.toIntOrNull()?.coerceIn(1, WeekSet.MAX_WEEK) ?: 1
                DemoTimetable.importTimetable(
                    this,
                    Timetable(
                        courses = result.courses.map { c ->
                            c.copy(
                                name = TextFix.decodeEntities(c.name),
                                room = TextFix.decodeEntities(c.room),
                                teacher = TextFix.decodeEntities(c.teacher)
                            )
                        },
                        periods = DemoTimetable.BUILTIN_PERIODS,
                        dayLabels = DemoTimetable.BUILTIN_DAY_LABELS,
                        termStartDate = DemoTimetable.deriveTermStart(current),
                        source = "jwxt",
                        importedAt = System.currentTimeMillis()
                    )
                )
                val periodsHint = if (DemoTimetable.periodsArePlaceholder) "\n教务不给上课时刻，请去侧栏「作息时间」填一次本校作息" else ""
                toast("已导入 ${result.courses.size} 门课 · 现在是第 $current 周$periodsHint")
                finish()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 智能抓取（HTML 表格模式）：
     *  ① 先探测当前页 —— 格子里带周次 = 已经是**整学期课表** → 直接解析（一次拿全）
     *  ② 是单周视图 → 用学年学期参数自动跳到整学期课表地址，页面加载完再解析
     *  ③ 都失败 → 提示用「抓取整个学期（自动逐周）」
     *
     * 强智的判定依据：整学期表格的格子内带「1-16周」这类文字，且页面有 id=xnxq01id 的学期下拉。
     */
    private fun startSmartFetch() {
        btnFetch.isEnabled = false
        status("正在识别当前页面…")
        webView.evaluateJavascript(SCRIPT_DETECT) { res ->
            val info = runCatching {
                val text = (res ?: "").trim()
                val json = (JSONTokener(text).nextValue() as? String) ?: text
                org.json.JSONObject(json)
            }.getOrNull()
            val onWholeTermPage = info?.optBoolean("weekColFound", false) ?: false
            val wholeUrl = info?.optString("wholeUrl").orEmpty()
            val isJwglxt = info?.optBoolean("isJwglxt", false) ?: false
            when {
                // 新正方：整学期数据本来就走 JSON 接口 —— 直接取，
                // 别去拼强智那种 /xskb/xskb_list.do 地址（实测 gzist 是正方 v5，拼出来的地址是错的）
                isJwglxt -> {
                    status("识别到新正方页面，按正方接口取整学期…")
                    webView.evaluateJavascript(SCRIPT_FETCH) { raw -> onFetched(raw) }
                }
                onWholeTermPage -> {
                    status("识别到整学期课表（格子带周次），直接解析")
                    webView.evaluateJavascript(SCRIPT_TABLE) { raw -> onFetched(raw) }
                }
                wholeUrl.isNotEmpty() -> {
                    status("当前是单周视图，自动切到整学期课表…")
                    pendingWholeTerm = true
                    webView.loadUrl(wholeUrl)
                }
                else -> {
                    // 兜底：识别不出整学期页就按"当前页表格"解析 —— 绝不什么都不做（踩过）
                    status("没识别出整学期页，按当前页表格解析…")
                    webView.evaluateJavascript(SCRIPT_TABLE) { raw -> onFetched(raw) }
                }
            }
        }
    }

    /**
     * 「抓取整个学期」：自动逐周切换页面并把各周的课**按周次合并**。
     *
     * 为什么选这条路：不依赖某校的私有接口，只要周次控件能被识别就能扫 ——
     * 三重兜底：① 周次下拉框 ② 「下一周」按钮/链接 ③ URL 里的周次参数（zc/week/dqzc…）。
     * 覆盖强智 / 青果 / URP / 老正方等"页面只能看单周"的教务。
     */
    private fun startSweep() {
        // 不再限制模式（踩过：方正模式下点它直接被拒，用户以为按钮坏了）
        sweepMerged.clear()
        sweepWeek = 0
        sweepActive = true
        status("开始逐周抓取（最多 $SWEEP_MAX_WEEKS 周，期间别切走页面）…")
        sweepStep()
    }

    private fun sweepStep() {
        if (!sweepActive) return
        sweepWeek++
        if (sweepWeek > SWEEP_MAX_WEEKS) {
            finishSweep("已扫满 $SWEEP_MAX_WEEKS 周")
            return
        }
        status("正在解析第 $sweepWeek 周…（已合并 ${sweepMerged.size} 门）")
        webView.evaluateJavascript(SCRIPT_TABLE) { raw ->
            runCatching {
                // evaluateJavascript 回传的是"被 JSON 转义过的字符串"，和 onFetched 一样处理
                val text = (raw ?: "").trim()
                val payload = (JSONTokener(text).nextValue() as? String) ?: text
                val parsed = TimetableJwxt.parse(payload)
                mergeInto(sweepMerged, parsed.courses)
            }
            if (sweepWeek >= SWEEP_MAX_WEEKS) {
                finishSweep("已扫满 $SWEEP_MAX_WEEKS 周")
                return@evaluateJavascript
            }
            webView.evaluateJavascript(SCRIPT_NEXT_WEEK) { res ->
                val marker = (res ?: "").trim().trim('"')
                when (marker) {
                    "NONE", "DROPDOWN_END" ->
                        finishSweep("第 $sweepWeek 周之后识别不到「下一周」控件，已停在能抓到的范围")
                    else -> {
                        status("第 $sweepWeek 周完成（$marker），继续…")
                        webView.postDelayed({ sweepStep() }, SWEEP_DELAY_MS)
                    }
                }
            }
        }
    }

    private fun mergeInto(merged: LinkedHashMap<String, Course>, courses: List<Course>) {
        courses.forEach { c ->
            val key = listOf(c.name, c.dayOfWeek, c.startPeriod, c.span, c.room).joinToString("|")
            val old = merged[key]
            merged[key] = if (old == null) {
                c
            } else if (old.weeks.isEmpty() || c.weeks.isEmpty()) {
                old.copy(weeks = emptyList())            // 出现"每周"就是每周
            } else {
                old.copy(weeks = (old.weeks + c.weeks).distinct().sorted())
            }
        }
    }

    private fun finishSweep(note: String) {
        sweepActive = false
        if (sweepMerged.isEmpty()) {
            status("逐周抓取没拿到课程（$note）—— 确认已登录并停在课表页")
            return
        }
        status("扫描完成：合并出 ${sweepMerged.size} 门课（$note）")
        confirmImport(
            ImportResult(
                courses = sweepMerged.values.toList(),
                errors = emptyList(),
                warnings = listOf(note),
                format = "教务逐周扫描"
            )
        )
    }

    private fun showSample() {
        val result = TimetableJwxt.parse(TimetableJwxt.sampleJson())
        val head = result.courses.take(3).joinToString("\n") { c ->
            val day = DemoTimetable.BUILTIN_DAY_LABELS.getOrElse(c.dayOfWeek - 1) { "周${c.dayOfWeek}" }
            "· $day 第${c.startPeriod}${if (c.span > 1) "–${c.startPeriod + c.span - 1}" else ""}节 ${c.name} @${c.room} (${WeekSet.describe(c.weeks)})"
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("解析器自检（${result.courses.size} 门）")
            .setMessage("$head\n（内置样本，不联网）")
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun normalize(input: String): String {
        val text = input.trim()
        if (text.isEmpty()) return ""
        return if (text.startsWith("http://") || text.startsWith("https://")) text else "https://$text"
    }

    private fun status(text: String) {
        tvStatus.text = text
    }

    // ---------------------------------------------------------------- 抓取模式

    /** auto：按网址猜（含 jwglxt 走接口，否则走页面表格）。 */




    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        btnFetch.isEnabled = true
    }

    private val prefs by lazy { getSharedPreferences("jwxt", MODE_PRIVATE) }

    companion object {
        /** 从外部传入本地 HTML 课表（"另存为网页"的文件）。 */
        const val EXTRA_LOCAL_HTML = "local_html"

        private const val KEY_URL = "url"

        private const val INTRO =
            "用法：① 填教务系统网址 ② 在页面里正常登录 ③ 选模式：\n" +
                "· 正方（jwglxt）：有 JSON 接口，登录后直接抓\n" +
                "· 当前页表格：强智 / 青果 / URP / 老正方通用 —— 先点开「课表查询 / 我的课表」页面，再抓\n" +
                "账号密码不会被保存。"

        /**
         * 跑在教务页面里的抓取脚本：
         * ① GET 课表页 → 从 `<select name=xnm/xqm>` 读系统当前的学年/学期（不用用户选）
         * ② POST `/jwglxt/kbcx/xskbcx_cxXsKb.html`（同源，自动带登录 Cookie）
         * ③ 把接口原文回传，交给 [TimetableJwxt] 解析
         */
        /**
         * 「当前页表格」模式的抓取脚本：**直接解析你正在看的课表页面**。
         *
         * 好处是不用猜各家的接口：强智（jsxsd）/ 青果 / URP / 老正方的课表页都是 HTML 网格，
         * 这里自动找"最像课表"的表格（表头星期列最多），再按单元格文本拆出 课程/教室/周次。
         * 提取结果用**正方的字段名**回传，于是 Kotlin 侧一套解析逻辑通吃。
         *
         * 这段 JS 在本地用假 DOM（强智风格表格）跑过：6 门课全部正确抽出（含"1-16周(单)"）。
         */
        /**
         * 自动识别当前页是不是"整学期"课表：
         *  - `weekColFound`：表格单元格里有没有周次文字（如 1-16周）→ 有就是整学期视图
         *  - `termSelect` / `termOptions`：强智标准的学年学期下拉框（id=xnxq01id）
         *  - `wholeUrl`：按当前路径前缀猜的整学期课表地址（强智为 <前缀>/xskb/xskb_list.do）
         */
        private const val SCRIPT_DETECT = """
(function(){
  var out = {weekColFound:false, termSelect:'', termOptions:[], wholeUrl:''};
  var tds = document.querySelectorAll('td');
  for (var i = 0; i < tds.length; i++) {
    var t = (tds[i].innerText || '').replace(/\s+/g, '');
    if (/\d+-\d+\u5468|\d+\u5468/.test(t)) { out.weekColFound = true; break; }
  }
  // 学年学期下拉：强智是 #xnxq01id，新正方是 #xnm + #xqm —— 两种都认（实测 gzist 是正方 v5）
  var sel = document.getElementById('xnxq01id') || document.getElementById('xnm');
  var isJwglxt = !!document.getElementById('xnm');
  out.isJwglxt = isJwglxt;
  if (!sel) { sel = document.querySelector('select[name=xnxq01id]'); }
  if (sel) {
    out.termSelect = sel.value || '';
    for (var j = 0; j < sel.options.length; j++) { out.termOptions.push(sel.options[j].value); }
  }
  var seg = location.pathname.split('/')[1] || 'jsxsd';
  out.wholeUrl = location.origin + '/' + seg + '/xskb/xskb_list.do' + (out.termSelect ? ('?xnxq01id=' + out.termSelect) : '');
  return JSON.stringify(out);
})();
"""

        private const val SWEEP_MAX_WEEKS = 20
        private const val SWEEP_DELAY_MS = 1500L

        /** 切到"下一周"：下拉框 → 按钮 → URL 参数，三重兜底（各校教务 DOM 差异大）。 */
        private const val SCRIPT_NEXT_WEEK = """
(function(){
  var sels = document.querySelectorAll('select');
  for (var i = 0; i < sels.length; i++) {
    var s = sels[i];
    var txt = (s.innerText || '') + ' ' + (s.id || '') + ' ' + (s.name || '');
    if (txt.indexOf('周') < 0) continue;
    if (s.selectedIndex >= 0 && s.selectedIndex < s.options.length - 1) {
      s.selectedIndex = s.selectedIndex + 1;
      s.dispatchEvent(new Event('change', {bubbles:true}));
      return 'DROPDOWN:' + s.options[s.selectedIndex].text;
    }
    return 'DROPDOWN_END';
  }
  var nodes = document.querySelectorAll('a,button,input,span,div,li');
  for (var j = 0; j < nodes.length; j++) {
    var n = nodes[j];
    var t = ((n.innerText || n.value || '') + '').replace(/\s+/g, '');
    if (t === '下一周' || t === '下周' || t === '>' || t === '>>' || t === '»') {
      n.click();
      return 'BUTTON:' + t;
    }
  }
  var keys = ['zc','week','dqzc','weekNum','zcd'];
  var u = new URL(location.href);
  for (var k = 0; k < keys.length; k++) {
    if (u.searchParams.has(keys[k])) {
      var v = parseInt(u.searchParams.get(keys[k]) || '1', 10) + 1;
      u.searchParams.set(keys[k], String(v));
      location.href = u.toString();
      return 'URL:' + keys[k];
    }
  }
  return 'NONE';
})();
"""

        private val SCRIPT_TABLE = """
            (function () {
              try {
                function islandExtractTimetable(doc) {
                  function txt(el) { return ((el && (el.innerText || el.textContent)) || '').replace(/\u00a0/g, ' ').trim(); }
                  function isDay(s) { s = (s || '').replace(/\s/g, ''); return /^(周|星期|礼拜)[一二三四五六日天]/.test(s) || /^周[1-7]$/.test(s); }
                  function dayIndex(s) {
                    var m = (s || '').replace(/\s/g, '').match(/([一二三四五六日天])/);
                    var map = { '一': 1, '二': 2, '三': 3, '四': 4, '五': 5, '六': 6, '日': 7, '天': 7 };
                    if (m) return map[m[1]];
                    var n = (s || '').match(/[1-7]/);
                    return n ? parseInt(n[0]) : 0;
                  }
                  var tables = doc.querySelectorAll('table');
                  var best = null, bestScore = 0;
                  for (var t = 0; t < tables.length; t++) {
                    var rows = tables[t].rows;
                    if (!rows || rows.length < 3) continue;
                    var score = 0;
                    var headCells = rows[0].cells || [];
                    for (var c = 0; c < headCells.length; c++) if (isDay(txt(headCells[c]))) score += 2;
                    if (score === 0) { for (var r2 = 0; r2 < rows.length && r2 < 4; r2++) if (/周/.test(txt(rows[r2]))) score += 1; }
                    if (score > bestScore) { bestScore = score; best = tables[t]; }
                  }
                  if (!best || bestScore < 2) return { error: '当前页面找不到课表表格：请先进入「课表查询 / 我的课表」页面再点抓取' };
                  var colDay = {};
                  var head = best.rows[0].cells || [];
                  for (var i = 0; i < head.length; i++) if (isDay(txt(head[i]))) colDay[i] = dayIndex(txt(head[i]));
                  if (!Object.keys(colDay).length) for (var j = 1; j < head.length && j <= 7; j++) colDay[j] = j;
                  var out = [];
                  for (var r = 1; r < best.rows.length; r++) {
                    var row = best.rows[r];
                    var cells = row.cells || [];
                    if (!cells.length) continue;
                    var secNums = (txt(cells[0]).match(/\d+/g) || []).slice(0, 2);
                    if (!secNums.length) continue;
                    var jc = secNums.length > 1 ? (secNums[0] + '-' + secNums[1]) : secNums[0];
                    for (var col in colDay) {
                      var cell = cells[col];
                      if (!cell) continue;
                      var blocks = cell.querySelectorAll ? cell.querySelectorAll('div, p, span') : [];
                      var texts = [];
                      if (blocks && blocks.length) for (var b = 0; b < blocks.length; b++) { var bt = txt(blocks[b]); if (bt) texts.push(bt); }
                      if (!texts.length) { var ct = txt(cell); if (ct) texts.push(ct); }
                      for (var k = 0; k < texts.length; k++) {
                        var lines = texts[k].split(/\n+/).map(function (s) { return s.replace(/\s+/g, ' ').trim(); }).filter(function (s) { return s; });
                        if (!lines.length) continue;
                        var name = lines[0], room = '', weeks = '', teacher = '';
                        for (var li = 1; li < lines.length; li++) {
                          var l = lines[li];
                          if (!weeks && /周/.test(l)) { weeks = l; continue; }
                          if (!room && /(教|楼|室|馆|机房|校区|号|A\d|B\d)/.test(l)) { room = l; continue; }
                          if (!teacher && l.length <= 12 && !/\d/.test(l)) { teacher = l; continue; }
                        }
                        if (!name || name.length > 40) continue;
                        out.push({ kcmc: name, cdmc: room, xm: teacher, xqj: '' + colDay[col], jc: jc, zcd: weeks });
                      }
                    }
                  }
                  if (!out.length) return { error: '识别到课表表格但没抽出课程：把课表页截图发我，我针对这个页面改规则' };
                  return { list: out };
                }
                var r = islandExtractTimetable(document);
                if (!r || r.error) return JSON.stringify({ ok: false, msg: (r && r.error) || "解析失败" });
                return JSON.stringify({ ok: true, mode: "table", json: JSON.stringify({ kbList: r.list }) });
              } catch (e) {
                return JSON.stringify({ ok: false, msg: "解析页面异常：" + e });
              }
            })()
        """.trimIndent()

        private val SCRIPT_FETCH = """
            (function () {
              try {
                var idx = new XMLHttpRequest();
                idx.open('GET', '/jwglxt/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N2151', false);
                idx.send();
                var html = idx.responseText || '';
                var doc = new DOMParser().parseFromString(html, 'text/html');
                function pick(name) {
                  var sel = doc.querySelector('select[name=' + name + ']');
                  if (!sel) return '';
                  var opt = sel.querySelector('option[selected]');
                  if (!opt) opt = sel.options && sel.options[0];
                  return opt ? String(opt.value || opt.text || '') : '';
                }
                var xnm = pick('xnm'), xqm = pick('xqm');
                if (!xnm || !xqm) {
                  return JSON.stringify({ ok: false, msg: '没读到学年/学期：可能还没登录，或学校用的不是新正方（jwglxt）' });
                }
                var kb = new XMLHttpRequest();
                kb.open('POST', '/jwglxt/kbcx/xskbcx_cxXsKb.html', false);
                kb.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded');
                kb.send('xnm=' + encodeURIComponent(xnm) + '&xqm=' + encodeURIComponent(xqm));
                var text = kb.responseText || '';
                if (text.indexOf('kbList') < 0) {
                  return JSON.stringify({ ok: false, msg: '接口没返回课表（未登录 / 学年学期不对 / 不是新正方）', body: text.substring(0, 200) });
                }
                return JSON.stringify({ ok: true, xnm: xnm, xqm: xqm, json: text });
              } catch (e) {
                return JSON.stringify({ ok: false, msg: '抓取异常：' + e });
              }
            })()
        """.trimIndent()
    }
}
