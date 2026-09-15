package dev.dpvoliin.islandtimetable

import android.widget.EditText
import android.net.Uri
import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.materialswitch.MaterialSwitch
import dev.dpvoliin.liveupdates.CapabilityReport
import dev.dpvoliin.liveupdates.LiveUpdate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.roundToInt
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * v0.1 的真机验证页：把「上课」这件事推成一条实时通知（默认通知），看看系统认不认。
 *
 * 界面上故意做全了：一张真的周课表（自绘）+ 当前课程卡 + 两个时长预设 + 设备能力卡。
 * 复用同一套课程模型，v0.2 换成真实导入的数据即可，UI 不用重写。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var chipStatus: TextView
    private lateinit var tvToday: TextView
    private lateinit var gridView: TimetableGridView
    private lateinit var tvNowState: TextView
    private lateinit var tvNowRange: TextView
    private lateinit var tvNowTitle: TextView
    private lateinit var tvNowSub: TextView
    private lateinit var tvNowRemain: TextView
    private lateinit var progressNow: LinearProgressIndicator
    private lateinit var btnFull: MaterialButton
    private lateinit var btnFinish: MaterialButton
    private lateinit var btnNotifSettings: MaterialButton
    private lateinit var btnTheme: MaterialButton
    private lateinit var btnCrash: MaterialButton
    private lateinit var btnPeriods: MaterialButton

    private lateinit var btnOverlay: MaterialButton
    private lateinit var switchAutoIsland: MaterialSwitch
    private lateinit var btnImport: MaterialButton
    private lateinit var tvTimetableInfo: TextView
    private lateinit var btnJwxt: MaterialButton
    private lateinit var btnWeekFix: MaterialButton
    private lateinit var tvAliveStatus: TextView
    private lateinit var btnAliveCheck: MaterialButton
    private lateinit var btnBatteryOpt: MaterialButton
    private lateinit var btnAppInfo: MaterialButton
    private lateinit var switchAlive: MaterialSwitch
    private lateinit var switchSticky: MaterialSwitch
    private lateinit var switchStickyDedupe: MaterialSwitch
    private lateinit var switchReminderVibrate: MaterialSwitch
    private lateinit var switchAutoSilent: MaterialSwitch
    private lateinit var btnCalendar: MaterialButton

    private var calendarsPending = false

    /** 日历权限请求码。 */
    private val REQ_CALENDAR = 77
    private lateinit var btnWeekPrev: MaterialButton
    private lateinit var btnWeekNext: MaterialButton
    private lateinit var tvWeekBadge: TextView
    private lateinit var switchReminder: MaterialSwitch
    private lateinit var tvReminderHint: TextView
    private lateinit var btnLead0: MaterialButton
    private lateinit var btnLead5: MaterialButton
    private lateinit var btnLead10: MaterialButton
    private lateinit var btnLead15: MaterialButton
    // 侧栏（v0.4：实验性功能收进抽屉，主屏只留课表相关）
    private lateinit var drawerLayout: androidx.drawerlayout.widget.DrawerLayout
    private lateinit var btnMenu: android.widget.ImageButton
    private lateinit var rootMain: android.widget.LinearLayout
    private lateinit var drawerContent: android.widget.LinearLayout
    private lateinit var tvDrawerVersion: TextView

    /** 「悬浮岛」悬浮窗（不依赖任何厂商；vivo 这类机型上唯一的可视方案）。 */
    private var islandOverlay: IslandOverlay? = null

    /** 当前实际走哪条通道（onResume / 每次刷新能力时更新）。 */

    private val prefs by lazy { getSharedPreferences("island", MODE_PRIVATE) }

    /** 自动路由开关：系统实时通知能用就用它，用不了才启用悬浮岛（默认开）。 */
    private var autoIsland: Boolean
        get() = prefs.getBoolean("auto_fake_island", true)
        set(value) = prefs.edit().putBoolean("auto_fake_island", value).apply()

    /** 悬浮岛上次吸附的边（拖动后自动记住）。 */
    private var overlaySide: IslandOverlay.Side
        get() = if (prefs.getString("overlay_side", "right") == "left") {
            IslandOverlay.Side.LEFT
        } else {
            IslandOverlay.Side.RIGHT
        }
        set(value) = prefs
            .edit()
            .putString("overlay_side", if (value == IslandOverlay.Side.LEFT) "left" else "right")
            .apply()

    /** 悬浮岛上次的竖直位置比例（拖动后自动记住）。 */
    private var overlayY: Float
        get() = prefs.getFloat("overlay_y", 0.42f)
        set(value) = prefs.edit().putFloat("overlay_y", value).apply()

    private val handler = Handler(Looper.getMainLooper())
    private var session: Session? = null
    private var ticking = false
    private var lastPushedSignature: String? = null
    private var lastPushAtMillis = 0L

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshCapability() }

    private val contentPendingIntent: PendingIntent by lazy {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** 一次模拟上课。真实版本里 startedAt 由闹钟在课前 1 分钟触发。 */
    private class Session(
        val course: Course,
        val startedAtMillis: Long,
        val durationMillis: Long,
        /** 本次推送时这节课**是不是真的正在上**（false = 只是"下一节"的预告，文案不能写"正在上课"）。 */
        val inClass: Boolean = true
    ) {
        fun elapsed(): Long = (System.currentTimeMillis() - startedAtMillis).coerceIn(0L, durationMillis)
        fun progress(): Float =
            if (durationMillis <= 0L) 0f else elapsed().toFloat() / durationMillis.toFloat()

        fun remainingMillis(): Long = (durationMillis - elapsed()).coerceAtLeast(0L)
    }

    // ---------------------------------------------------------------- 生命周期

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        SystemBars.apply(this)

        // 库的启动清理：把上次进程被杀时残留的、已过期的岛撤下
        LiveUpdate.init(this)

        bindViews()
        // v0.2：先载入课表（有导入结果就用它，否则内置演示），再灌进网格
        DemoTimetable.apply(this)
        applyTimetableToUi()

        btnFull.setOnClickListener { drawerLayout.closeDrawers(); startSession(45L * 60_000L) }
        btnFinish.setOnClickListener {
            drawerLayout.closeDrawers()
            // ⚠️ 顺序要紧：**先关常驻开关**再停会话。
            //    反过来的话，stopSession() 里的 repostIfEnabled() 会把常驻通知补发回来，
            //    而后面的 setEnabled(false) 又撤不掉前台服务刚认领的那条 ⇒ 「关闭通知偶尔失灵」。
            val stickyWasOn = StickyNotification.isEnabled(this)
            if (stickyWasOn) {
                StickyNotification.setEnabled(this, false)
                runCatching { switchSticky.isChecked = false }
            }
            stopSession(byUser = true)
            if (stickyWasOn) {
                Toast.makeText(this, "常驻通知也一起关了（要留着就去设置里重开）", Toast.LENGTH_SHORT).show()
            }
        }
        btnNotifSettings.setOnClickListener { drawerLayout.closeDrawers(); openNotificationSettings() }


        btnOverlay.setOnClickListener { drawerLayout.closeDrawers(); toggleOverlay() }
        btnImport.setOnClickListener {
            drawerLayout.closeDrawers()
            startActivity(Intent(this, ImportActivity::class.java))
        }
        btnJwxt.setOnClickListener {
            drawerLayout.closeDrawers()
            startActivity(Intent(this, JwxtActivity::class.java))
        }
        btnWeekPrev.setOnClickListener {
            switchWeekAnimated(DemoTimetable.viewWeek - 1)
            applyTimetableToUi()
        }
        btnWeekNext.setOnClickListener {
            switchWeekAnimated(DemoTimetable.viewWeek + 1)
            applyTimetableToUi()
        }
        tvWeekBadge.setOnClickListener { // 点周次 = 回到本周
            DemoTimetable.resetViewWeek()
            applyTimetableToUi()
        }
        switchReminder.setOnCheckedChangeListener { _, checked ->
            ClassReminder.setEnabled(this, checked)
            refreshReminderUi()
        }
        leadButtons().forEach { (button, minutes) ->
            button.setOnClickListener {
                ClassReminder.setLeadMinutes(this, minutes)
                refreshReminderUi()
            }
        }
        tvReminderHint.setOnClickListener {
            if (ClassReminder.needsExactAlarmPermission(this)) openExactAlarmSettings()
        }
        switchAutoIsland.setOnCheckedChangeListener { _, checked ->
            autoIsland = checked
            if (!checked) islandOverlay?.hide()
        }


        refreshCapability()
        ensureNotificationPermission()
        render()
    }

    override fun onStart() {
        super.onStart()
        if (!ticking) {
            ticking = true
            handler.post(ticker)
        }
    }

    override fun onStop() {
        ticking = false
        handler.removeCallbacks(ticker)
        super.onStop()
    }

    private val ticker = object : Runnable {
        override fun run() {
            render()
            if (ticking) handler.postDelayed(this, 1_000L)
        }
    }

    private fun bindViews() {
        chipStatus = findViewById(R.id.chipStatus)
        tvToday = findViewById(R.id.tvToday)
        gridView = findViewById(R.id.gridView)
        tvNowState = findViewById(R.id.tvNowState)
        tvNowRange = findViewById(R.id.tvNowRange)
        tvNowTitle = findViewById(R.id.tvNowTitle)
        tvNowSub = findViewById(R.id.tvNowSub)
        tvNowRemain = findViewById(R.id.tvNowRemain)
        progressNow = findViewById(R.id.progressNow)
        btnFull = findViewById(R.id.btnFull)
        btnFinish = findViewById(R.id.btnFinish)
        // 设置折叠（抽屉式）：默认收起，点标题展开
        val settingsBody = findViewById<android.widget.LinearLayout>(R.id.settingsBody)
        val settingsHeader = findViewById<TextView>(R.id.tvSettingsHeader)
        settingsHeader.setOnClickListener {
            val open = settingsBody.visibility == View.VISIBLE
            // 展开/收起带动画（高度+淡出淡入由 TransitionManager 自动补间）
            androidx.transition.TransitionManager.beginDelayedTransition(
                drawerContent,
                androidx.transition.AutoTransition().setDuration(200L)
            )
            settingsBody.visibility = if (open) View.GONE else View.VISIBLE
            settingsHeader.text = if (open) "▾  设置（点展开）" else "▴  设置（点收起）"
        }
        btnNotifSettings = findViewById(R.id.btnNotifSettings)
        btnPeriods = findViewById(R.id.btnPeriods)
        btnPeriods.setOnClickListener {
            drawerLayout.closeDrawers()
            startActivity(Intent(this, PeriodActivity::class.java))
        }
        btnCrash = findViewById(R.id.btnCrash)
        btnTheme = findViewById(R.id.btnTheme)
        btnTheme.setOnClickListener {
            // 跟随系统 → 浅色 → 深色 → 跟随系统
            ThemeMode.set(this, ThemeMode.next(ThemeMode.get(this)))
            refreshThemeUi()
        }

        btnOverlay = findViewById(R.id.btnOverlay)
        switchAutoIsland = findViewById(R.id.switchAutoIsland)
        btnImport = findViewById(R.id.btnImport)
        btnJwxt = findViewById(R.id.btnJwxt)
        btnWeekFix = findViewById(R.id.btnWeekFix)
        btnWeekFix.setOnClickListener {
            drawerLayout.closeDrawers()
            askTermWeek()
        }

        tvAliveStatus = findViewById(R.id.tvAliveStatus)
        btnAliveCheck = findViewById(R.id.btnAliveCheck)
        btnBatteryOpt = findViewById(R.id.btnBatteryOpt)
        btnAppInfo = findViewById(R.id.btnAppInfo)
        btnCalendar = findViewById(R.id.btnCalendar)
        btnCalendar.setOnClickListener {
            drawerLayout.closeDrawers()
            if (CalendarExport.hasPermission(this)) {
                writeCalendar()
            } else {
                calendarsPending = true
                requestPermissions(
                    arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
                    REQ_CALENDAR
                )
            }
        }
        switchAutoSilent = findViewById(R.id.switchAutoSilent)
        switchAutoSilent.isChecked = AutoSilent.isEnabled(this)
        switchAutoSilent.setOnCheckedChangeListener { _, checked ->
            if (checked && !AutoSilent.hasAccess(this)) {
                Toast.makeText(this, "需要先授予「勿扰访问权限」", Toast.LENGTH_SHORT).show()
                AutoSilent.requestAccess(this)
            }
            AutoSilent.setEnabled(this, checked)
        }
        switchReminderVibrate = findViewById(R.id.switchReminderVibrate)
        switchReminderVibrate.isChecked = ClassReminder.isVibrateEnabled(this)
        switchReminderVibrate.setOnCheckedChangeListener { _, checked ->
            ClassReminder.setVibrateEnabled(this, checked)
        }
        switchSticky = findViewById(R.id.switchSticky)
        switchSticky.setOnCheckedChangeListener { _, checked ->
            // 整段兜异常：通知 / 前台服务在各种 ROM 上都有被系统拒绝的可能，绝不因此闪退
            val ok = runCatching {
                StickyNotification.setEnabled(this, checked)
                true
            }.getOrDefault(false)
            val text = when {
                !ok -> "开启失败：系统拦了前台服务（试试关掉省电限制后重开）"
                // 顺带说清「岛」这条路：升不上去是正常的，免得以为开关坏了
                checked -> "已开启常驻通知（划不掉）· " + PromotedNotify.describe(this)
                else -> "已关闭常驻通知"
            }
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
        }
        switchStickyDedupe = findViewById(R.id.switchStickyDedupe)
        switchStickyDedupe.isChecked = StickyNotification.isHideWhenLiveEnabled(this)
        switchStickyDedupe.setOnCheckedChangeListener { _, checked ->
            runCatching { StickyNotification.setHideWhenLiveEnabled(this, checked) }
        }
        switchAlive = findViewById(R.id.switchAlive)

        // 存活面板（两个变体都有）
        switchAlive.setOnCheckedChangeListener { _, checked ->
            LiveUpdate.setKeepAliveEnabled(this, checked)
            refreshAliveUi()
        }
        btnAliveCheck.setOnClickListener {
            val fixed = LiveUpdate.checkAlive(this)
            Toast.makeText(
                this,
                if (fixed > 0) "补回了 $fixed 条通知" else "通知都在，无需补",
                Toast.LENGTH_SHORT
            ).show()
            refreshAliveUi()
        }
        btnBatteryOpt.setOnClickListener {
            drawerLayout.closeDrawers()
            val power = getSystemService(android.os.PowerManager::class.java)
            if (power?.isIgnoringBatteryOptimizations(packageName) == true) {
                Toast.makeText(this, "已经是「不优化」状态，系统不会再限制后台", Toast.LENGTH_SHORT).show()
            } else {
                val direct = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(android.net.Uri.parse("package:" + packageName))
                runCatching { startActivity(direct) }.onFailure {
                    runCatching {
                        startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                }
            }
        }
        btnAppInfo.setOnClickListener {
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                )
            }.onFailure { Toast.makeText(this, "打不开应用信息页", Toast.LENGTH_SHORT).show() }
        }
        refreshAliveUi()


        tvTimetableInfo = findViewById(R.id.tvTimetableInfo)
        btnWeekPrev = findViewById(R.id.btnWeekPrev)
        btnWeekNext = findViewById(R.id.btnWeekNext)
        tvWeekBadge = findViewById(R.id.tvWeekBadge)
        switchReminder = findViewById(R.id.switchReminder)
        tvReminderHint = findViewById(R.id.tvReminderHint)
        btnLead0 = findViewById(R.id.btnLead0)
        btnLead5 = findViewById(R.id.btnLead5)
        btnLead10 = findViewById(R.id.btnLead10)
        btnLead15 = findViewById(R.id.btnLead15)
        switchAutoIsland.isChecked = autoIsland
        refreshThemeUi()
        refreshCrashUi()

        // ---- 侧栏 ----
        drawerLayout = findViewById(R.id.drawerLayout)
        btnMenu = findViewById(R.id.btnMenu)
        rootMain = findViewById(R.id.rootMain)
        drawerContent = findViewById(R.id.drawerContent)
        tvDrawerVersion = findViewById(R.id.tvDrawerVersion)

        // 「关于」：折叠 + 版本号（库/开源库/免责声明是静态文案，写在布局里）
        val aboutHeader = findViewById<TextView>(R.id.tvAboutHeader)
        val aboutBody = findViewById<android.widget.LinearLayout>(R.id.aboutBody)
        findViewById<TextView>(R.id.tvAboutVersion).text = runCatching {
            val pkg = packageManager.getPackageInfo(packageName, 0)
            "岛课表 v" + pkg.versionName + "（versionCode " +
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pkg.longVersionCode else pkg.versionCode.toLong()) + "）"
        }.getOrDefault("岛课表")
        aboutHeader.setOnClickListener {
            val open = aboutBody.visibility == View.VISIBLE
            androidx.transition.TransitionManager.beginDelayedTransition(
                drawerContent,
                androidx.transition.AutoTransition().setDuration(200L)
            )
            aboutBody.visibility = if (open) View.GONE else View.VISIBLE
            aboutHeader.text = if (open) "▾  关于（点展开）" else "▴  关于（点收起）"
        }

        // 抽屉动画：拉开时主内容轻微缩小后退 + 抽屉渐显（系统默认只是生硬地滑一下）
        drawerLayout.addDrawerListener(object : androidx.drawerlayout.widget.DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {
                rootMain.translationX = rootMain.width * 0.10f * slideOffset
                rootMain.scaleX = 1f - 0.06f * slideOffset
                rootMain.scaleY = 1f - 0.06f * slideOffset
                drawerView.alpha = 0.55f + 0.45f * slideOffset
            }

            override fun onDrawerOpened(drawerView: View) {
                drawerView.alpha = 1f
            }

            override fun onDrawerClosed(drawerView: View) {
                rootMain.translationX = 0f
                rootMain.scaleX = 1f
                rootMain.scaleY = 1f
                drawerView.alpha = 1f
            }
        })

        // 主界面边缘侧滑开侧栏：从左边缘 28dp 内往右滑 → 打开；抽屉开着时往左滑 → 关闭
        rootMain.setOnTouchListener(object : android.view.View.OnTouchListener {
            private var downX = 0f
            private var downY = 0f
            private var handled = false

            override fun onTouch(v: android.view.View, event: android.view.MotionEvent): Boolean {
                var consumed = false
                val action = event.actionMasked
                if (action == android.view.MotionEvent.ACTION_DOWN) {
                    downX = event.rawX
                    downY = event.rawY
                    handled = false
                } else if (action == android.view.MotionEvent.ACTION_MOVE && !handled) {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    // 只在"横向意图明显（>36dp 且大于竖向 1.6 倍）"时接管，避免和上下滚动打架
                    if (Math.abs(dx) > dp(36f) && Math.abs(dx) > Math.abs(dy) * 1.6f) {
                        if (dx > 0 && downX <= dp(28f)) {
                            drawerLayout.openDrawer(GravityCompat.START)
                            handled = true
                            consumed = true
                        } else if (dx < 0 && drawerLayout.isDrawerOpen(GravityCompat.START)) {
                            drawerLayout.closeDrawer(GravityCompat.START)
                            handled = true
                            consumed = true
                        }
                    }
                }
                return consumed
            }
        })

        // 按钮按压反馈：主屏 + 侧栏里所有按钮
        rootMain.enablePressFeedbackDeep()
        drawerContent.enablePressFeedbackDeep()

        tvDrawerVersion.text = runCatching {
            "v" + packageManager.getPackageInfo(packageName, 0).versionName + " · MIT · 全离线"
        }.getOrDefault("MIT · 全离线")

        // 注意：openDrawer/isDrawerOpen 只接受 DrawerLayout 的**直接子 View**（或 gravity）。
        // 早先传的是抽屉内部那层 LinearLayout（孙 View）→ 抛 IllegalArgumentException → 一点 ☰ 就闪退。
        btnMenu.setOnClickListener {
            if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.closeDrawers()
            } else {
                drawerLayout.openDrawer(GravityCompat.START)
            }
        }
        // Android 16 起强制 edge-to-edge：不给系统栏留白，顶部标题会被状态栏文字压住
        ViewCompat.setOnApplyWindowInsetsListener(drawerLayout) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            rootMain.setPadding(0, bars.top, 0, bars.bottom)
            drawerContent.setPadding(
                dp(18f),
                bars.top + dp(26f),
                dp(18f),
                bars.bottom + dp(30f)
            )
            insets
        }
        ViewCompat.requestApplyInsets(drawerLayout)

    }

    override fun onPause() {
        super.onPause()
        if (::drawerLayout.isInitialized) drawerLayout.closeDrawers()
    }

    override fun onResume() {
        super.onResume()
        // ① 从「导入课表」页回来：重新载入课表并刷新网格
        DemoTimetable.apply(this)
        applyTimetableToUi()
        // ①.5 课表刚被换掉的话：正在推送的那节课如果已经不在新课表里，立刻撤下
        //      （否则通知栏会一直挂着"上一个课表"里的课，看起来像读到了旧数据）
        session?.let { pushed ->
            if (DemoTimetable.COURSES.none { it.id == pushed.course.id }) {
                session = null
                lastPushedSignature = null
                runCatching { LiveUpdate.finishAll(this) }
                islandOverlay?.hide()
            }
        }
        // ①.6 通知栏里若还挂着"新课表里没有的课"（换过课表/换过学校时的典型现象）→ 一并撤下
        runCatching {
            LiveUpdate.activeIds(this)
                .filter { id -> DemoTimetable.COURSES.none { it.id == id } }
                .forEach { id -> LiveUpdate.finish(this, id) }
        }
        // ② 常驻通知：回到前台刷新内容，并把被系统清掉的补回来
        StickyNotification.repostIfEnabled(this)
        // 桌面小组件：课表可能刚导入/换了周次，顺手刷新
        TimetableWidget.refreshAll(this)
        AutoSilent.scheduleNext(this)
        // ③ 推送路由（实时通知 or 悬浮岛）+ 空闲时的顶部状态
        refreshCapability()
    }

    /** 把当前课表灌进网格与信息行（启动时、导入回来后各调一次）。 */
    private fun applyTimetableToUi() {
        gridView.periods = DemoTimetable.PERIODS
        gridView.dayLabels = DemoTimetable.DAY_LABELS
        gridView.courses = DemoTimetable.coursesForGrid()
        tvTimetableInfo.text = run {
            val base = DemoTimetable.summary()
            val pending = DemoTimetable.unscheduled().size
            val tail = if (pending > 0) " · 另有 $pending 门未排时间（点这行看）" else ""
            // 教务/CSV 导入不带时刻 → 节次时间是内置占位，必须明确告诉用户，否则看到的时间是错的
            val warn = if (DemoTimetable.periodsArePlaceholder) {
                " · ⚠ 作息为默认占位，点侧栏「作息时间」改成学校的"
            } else {
                ""
            }
            base + tail + warn
        }
        // 未排时间的课不占格子，但得能看见：点信息行弹清单
        tvTimetableInfo.setOnClickListener {
            val pending = DemoTimetable.unscheduled()
            if (pending.isEmpty()) return@setOnClickListener
            val msg = pending.joinToString("\n") { c ->
                val weeks = WeekSet.format(c.weeks).ifEmpty { "每周" }
                "· ${c.name}" + (if (c.room.isBlank()) "" else "（${c.room}）") + " · $weeks" +
                    (if (c.teacher.isBlank()) "" else " · ${c.teacher}")
            }
            MaterialAlertDialogBuilder(this)
                .setTitle("未排时间（${pending.size} 门）")
                .setMessage(msg + "\n\n教务没给这些课的星期/节次，所以不占周课表格子。")
                .setPositiveButton("知道了", null)
                .show()
        }
        refreshWeekUi()
        refreshReminderUi()
        ClassReminder.reschedule(this)
        render()
    }

    /** 提前量按钮（按钮 ↔ 分钟数）。 */
    private fun leadButtons(): List<Pair<MaterialButton, Int>> = listOf(
        btnLead0 to 0, btnLead5 to 5, btnLead10 to 10, btnLead15 to 15
    )


    /** 问「现在是第几周」→ 反推学期起始日（周次错位时用，不必重新导入）。 */
    private fun askTermWeek() {
        if (DemoTimetable.source == "demo") {
            Toast.makeText(this, "现在用的是内置演示数据，先导入真实课表", Toast.LENGTH_SHORT).show()
            return
        }
        val input = EditText(this).apply {
            hint = "当前教学周（1–30）"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(DemoTimetable.computeWeek().toString())
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("修正「现在是第几周」")
            .setMessage(
                "填**教务里显示的当前教学周**（注意：有的教务同时显示「校历周」和「教学周」两个数字，要填教学周）。\n\n" +
                    "填错会让单双周课程、以及「本周」判断整体错位 —— 表现出来就是「某些课位置不对 / 某些课看不到」。"
            )
            .setView(input)
            .setPositiveButton("对齐") { _, _ ->
                val week = input.text?.toString()?.trim()?.toIntOrNull()?.coerceIn(1, WeekSet.MAX_WEEK) ?: return@setPositiveButton
                DemoTimetable.correctTermWeek(this, week)
                applyTimetableToUi()
                Toast.makeText(this, "已按第 $week 周对齐学期起始日", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 存活面板刷新。 */
    private fun refreshAliveUi() {
        tvAliveStatus.text = LiveUpdate.keepAliveStatus(this)
        val on = LiveUpdate.isKeepAliveEnabled(this)
        if (switchAlive.isChecked != on) switchAlive.isChecked = on
        val sticky = StickyNotification.isEnabled(this)
        if (switchSticky.isChecked != sticky) switchSticky.isChecked = sticky
        refreshSessionButtons()
    }


    /** dp → px（返回 Int，方便直接喂给 setPadding；只在本文件的 insets 留白用）。 */
    private fun dp(value: Float): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    /** 周次切换相关界面：网格、徽标、按钮可用性。 */
    private fun refreshWeekUi() {
        val week = DemoTimetable.viewWeek
        tvWeekBadge.text =
            if (DemoTimetable.isViewingCurrentWeek()) "第 $week 周" else "第 $week 周（非本周）"
        btnWeekPrev.isEnabled = week > 1
        btnWeekNext.isEnabled = week < WeekSet.MAX_WEEK
        gridView.courses = DemoTimetable.coursesForGrid()
        gridView.invalidate()
    }

    /** 上课提醒界面：开关、提前量高亮、下一节提示与精确闹钟授权引导。 */
    private fun refreshReminderUi() {
        val enabled = ClassReminder.isEnabled(this)
        if (switchReminder.isChecked != enabled) switchReminder.isChecked = enabled

        val lead = ClassReminder.leadMinutes(this)
        val accent = getColor(R.color.accent)
        val secondary = getColor(R.color.text_secondary)
        leadButtons().forEach { (button, minutes) ->
            button.setTextColor(if (minutes == lead) accent else secondary)
        }

        tvReminderHint.text = buildString {
            if (!enabled) {
                // 简短状态，不写说明文案
           } else {
                val upcoming = DemoTimetable.upcoming()
                if (upcoming == null) {
                    append("未来两周没有课")
                } else {
                    append("下一节课：")
                    append(weekdayLabel(upcoming.course.dayOfWeek))
                    append(" 第 ${upcoming.course.startPeriod} 节 · ")
                    append(upcoming.course.name)
                    append(" · ")
                    append(relativeUntil(upcoming))
                }
                if (ClassReminder.needsExactAlarmPermission(this@MainActivity)) {
                    append("\n⚠ 未授权「闹钟与提醒」，提醒可能晚几分钟 —— 点这里去授权")
                }
            }
        }
    }

    /** 相对时间描述：还有 X 分钟 / X 小时 Y 分钟 / X 天 Y 小时。 */
    private fun relativeUntil(upcoming: DemoTimetable.Upcoming): String {
        val millis = upcoming.millisFrom()
        if (millis <= 0) return "马上就上课了"
        val minutes = millis / 60_000L
        return when {
            minutes < 60L -> "还有 $minutes 分钟"
            minutes < 60L * 24L -> "还有 ${minutes / 60} 小时 ${minutes % 60} 分钟"
            else -> "还有 ${minutes / (60L * 24L)} 天 ${(minutes % (60L * 24L)) / 60} 小时"
        }
    }

    /** 精确闹钟授权页（Android 12+）。 */
    private fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val ok = runCatching {
            startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    .setData(android.net.Uri.fromParts("package", packageName, null))
            )
        }.isSuccess
        if (!ok) {
            Toast.makeText(
                this,
                "没有这个设置页：去 设置 → 应用 → 特殊权限 → 闹钟与提醒 里打开",
                Toast.LENGTH_LONG
            ).show()
        }
    }
    // ---------------------------------------------------------------- 上课 / 下课

    private fun startSession(durationMillis: Long) {
        if (!LiveUpdate.capability(this).notificationsEnabled) {
            Toast.makeText(this, "请先允许通知，否则推送不出去", Toast.LENGTH_SHORT).show()
            ensureNotificationPermission()
            return
        }
        // 时长参数忽略：一律按**当前时间**取课（正在上的优先，否则下一节）
        val picked = courseByNow()
        if (picked == null) {
            Toast.makeText(this, "现在没有课，课表里也没有下一节", Toast.LENGTH_SHORT).show()
            return
        }
        val course = picked.course
        // 通知的存活时长：到这节课下课（正在上＝剩余时长；下一节＝到那天那节课下课），再留 1 分钟缓冲
        val lifeMillis = picked.endAt
            ?.let { java.time.Duration.between(LocalDateTime.now(), it).toMillis() }
            ?.coerceAtLeast(60_000L)
            ?: 45L * 60_000L
        val newSession = Session(course, System.currentTimeMillis(), lifeMillis, picked.inClass)
        session = newSession
        lastPushedSignature = null
        lastPushAtMillis = 0L
        pushToIsland(newSession, force = true)
        // 岛出现了 → 常驻通知立刻让位（否则通知栏两条内容重复的通知）
        StickyNotification.withdrawForDefaultNotify(this)
        autoShowOverlayIfNeeded(newSession)
        render()
        Toast.makeText(
            this,
            if (picked.inClass) {
                "已推送「${course.name}」· 距下课还有 ${picked.edgeMillis / 60_000} 分钟"
            } else {
                "现在没课，已推送下一节「${course.name}」· " +
                    DemoTimetable.dayLabel(course.dayOfWeek) + " " +
                    NotifyText.periodsText(course.startPeriod, course.span)
            },
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun stopSession(byUser: Boolean) {
        val current = session
        if (current == null) {
            // 课程**已经自动结束**（到点那条路径把 session 清空了）——
            // 手动点「下课」也必须要有反应：把可能残留的通知与悬浮岛一并收拾干净，并给一句明确反馈。
            LiveUpdate.finishAll(this)
            islandOverlay?.hide()
            lastPushedSignature = null
            render()
            if (byUser) {
                Toast.makeText(this, "已经下课了，没有残留通知", Toast.LENGTH_SHORT).show()
            }
            return
        }
        session = null
        lastPushedSignature = null
        LiveUpdate.finish(this, current.course.id)
        islandOverlay?.hide()
        // 岛撤下了 → 常驻通知补回来（显示当前课/下一节）
        StickyNotification.repostIfEnabled(this)
        render()
        if (byUser) {
            Toast.makeText(this, "已撤下通知", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 推 / 更新**默认通知**（系统实时通知）。
     *
     * 刻意做的节流：只有在**界面上显示的值变了**（剩余分钟数或百分比）才真的更新通知，
     * 且最快 1 秒一次 —— 倒计时 UI 可以每秒刷新，但没必要每秒打扰系统。
     */
    private fun pushToIsland(current: Session, force: Boolean = false) {
        val course = current.course
        val totalSeconds = (current.durationMillis / 1_000L).coerceAtLeast(1L)
        val elapsedSeconds = (current.elapsed() / 1_000L).coerceIn(0L, totalSeconds)
        val percent = ((elapsedSeconds * 100L) / totalSeconds).toInt()
        val remainingMinutes = ((current.remainingMillis() + 59_999L) / 60_000L).toInt()

        val signature = "$remainingMinutes|$percent"
        val now = System.currentTimeMillis()
        if (!force) {
            if (signature == lastPushedSignature) return
            if (now - lastPushAtMillis < 1_000L) return
        }
        lastPushedSignature = signature
        lastPushAtMillis = now

        // 「正在上课」和「只是下一节」是两种东西：标题、内容、进度条、超短文字全部区分开。
        // （与「课前提醒」「常驻通知」共用同一形状：标题 = 状态 · 课名）
        val body = if (current.inClass) {
            NotifyText.nowContent(remainingMinutes, course.room)
        } else {
            NotifyText.where(
                DemoTimetable.dayLabel(course.dayOfWeek),
                NotifyText.periodsText(course.startPeriod, course.span),
                course.room
            )
        }

        val configure: dev.dpvoliin.liveupdates.LiveUpdateSpec.Builder.() -> Unit = {
            title = NotifyText.title(
                if (current.inClass) NotifyText.STATUS_NOW else NotifyText.STATUS_NEXT,
                course.name
            )
            text = body
            subText = DemoTimetable.timeRange(course) + " · ${course.teacher}"
            // ★ 实时通知上显示的**超短**文字（系统对这类通知的展示有字数约束）
            shortCriticalText = when {
                !current.inClass -> "待上课"                       // 还没上课：不报"还有 N 分钟下课"
                remainingMinutes > 0 -> "${remainingMinutes}分"
                else -> "下课"
            }
            smallIconRes = R.drawable.ic_class
            silent = true
            importance = NotificationManager.IMPORTANCE_LOW
            channelId = LiveUpdate.DEFAULT_CHANNEL_ID
            channelName = LiveUpdate.DEFAULT_CHANNEL_NAME

            contentIntent = contentPendingIntent
            showTimestamp = false
            // 只有"正在上课"才有课程进度；"下一节"不画进度条（否则会出现一条莫名的进度）
            if (current.inClass) {
                progress(elapsedSeconds, totalSeconds)
                if (course.span > 1) {
                    // 「一格一节课」：把进度条按节次分段
                    val base = 100 / course.span
                    val lengths = IntArray(course.span) { base }
                    lengths[0] = 100 - base * (course.span - 1)
                    segments(*lengths)
                }
            }
            timeoutMillis = current.remainingMillis() + 60_000L
        }

        // update 会保留原本的超时时间；进程重启后 id 不存在，则回落到 publish
        if (!LiveUpdate.update(this, course.id, configure)) {
            LiveUpdate.publish(this, course.id, configure)
        }
    }


    /**

     * 自动路由（用户 2026-09-14 要求）：**识别机型后决定要不要启用悬浮岛**。
     *
     * - 标准 Live Updates 可用（系统真放行）→ 不浮
     * - 系统实时通知可用 → 不浮悬浮岛
     * - 两条都不给（vivo 等）→ 自动启用悬浮岛（需「显示在其他应用上层」权限；没有则静默跳过，
     *   能力卡片里会显示「悬浮岛权限：未授权」提示用户去授权）
     *
     * 判定结果缓存在 [islandRoute]，每秒的倒计时刷新不再重复探测。
     */
    private fun autoShowOverlayIfNeeded(current: Session) {
        if (false) {
            islandOverlay?.hide()
            return
        }
        if (!autoIsland) return
        val overlay = overlay()
        if (!overlay.canShow()) return
        val leftMinutes = ((current.remainingMillis() + 59_999L) / 60_000L).toInt()
        overlay.show(
            current.course.name,
            if (leftMinutes > 0) "还有 $leftMinutes 分" else "即将下课",
            current.progress(),
            side = overlaySide,
            yRatio = overlayY
        )
    }

    /**
     * 「悬浮岛」预览：在状态栏下方浮一个自绘药丸。
     *
     * vivo 这类不放行系统实时通知的机型上，这是唯一能「把课程显示出来」的可视方案
     * （不申请厂商准入、不依赖任何私有接口，只需「显示在其他应用上层」权限）。
     * 局限见 `docs/floating-island.md`：它画在状态栏**下方**，锁屏不出现 —— 是替代品，不是假货。
     */
    private fun toggleOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "先允许「显示在其他应用上层」，再回来点一次", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                    .setData(android.net.Uri.parse("package:$packageName"))
            )
            return
        }
        val overlay = overlay()
        if (overlay.isShowing) {
            overlay.hide()
            Toast.makeText(this, "已收起悬浮岛", Toast.LENGTH_SHORT).show()
            return
        }
        val running = session
        val leftMinutes = running?.let { ((it.remainingMillis() + 59_999L) / 60_000L).toInt() } ?: 26
        overlay.show(
            running?.course?.name ?: "高等数学（预览）",
            if (leftMinutes > 0) "还有 $leftMinutes 分" else "即将下课",
            running?.progress() ?: 0.42f,
            side = overlaySide,
            yRatio = overlayY
        )
        Toast.makeText(this, "悬浮岛已浮出 · 点它回 App、长按隐藏、拖动可换边", Toast.LENGTH_SHORT).show()
    }

    /** 取（或首次创建）悬浮岛悬浮窗，并把「吸附侧 + 竖直位置」的变更存进偏好。 */
    private fun overlay(): IslandOverlay {
        val existing = islandOverlay
        if (existing != null) return existing
        return IslandOverlay(this).also { created ->
            created.onPositionChanged = { side, yRatio ->
                overlaySide = side
                overlayY = yRatio
            }
            islandOverlay = created
        }
    }

    /**
     * 按**当前时间**选课，并算出"距离这节课结束还有多久"。
     *
     * 顺序：① 正在上的课（此刻落在它的节次区间内）→ ② 下一节（跨天，走 [DemoTimetable.upcoming] 同一套逻辑）。
     * 踩过：原来用的是"今天的第一门课"（没有今天的课就取全体第一门）⇒ 任何时间点开启通知都显示**第 1 节**。
     */
    /** 当前该显示哪节课，以及它是"正在上"还是"下一节"。 */
    private data class NowPick(
        val course: Course,
        /** true = 正在上课；false = 还没开始（只是预告下一节）。 */
        val inClass: Boolean,
        /** 这节课的下课时刻（正在上＝今天；下一节＝那一天），用来算超时撤下。 */
        val endAt: LocalDateTime?,
        /** 正在上＝距下课还有多久；下一节＝距上课还有多久（毫秒，至少 1 分钟）。 */
        val edgeMillis: Long
    )

    private fun courseByNow(): NowPick? {
        val now = LocalDateTime.now()
        fun startOf(c: Course) = DemoTimetable.PERIODS.firstOrNull { it.index == c.startPeriod }?.start
        fun endOf(c: Course) = DemoTimetable.PERIODS
            .firstOrNull { it.index == c.startPeriod + c.span - 1 }?.end

        // ① 正在上：**今天** + 当前时刻落在这节课的节次区间内
        for (c in DemoTimetable.coursesToday(now.dayOfWeek.value)) {
            val start = startOf(c) ?: continue
            val end = endOf(c) ?: continue
            val t = now.toLocalTime()
            if (!t.isBefore(start) && t.isBefore(end)) {
                val endAt = LocalDateTime.of(now.toLocalDate(), end)
                val rest = java.time.Duration.between(now, endAt).toMillis()
                return NowPick(c, true, endAt, rest.coerceAtLeast(60_000L))
            }
        }
        // ② 还没上课 → 「下一节」（跨天/跨周都算）。
        //    ⚠️ 注意：这里的时长是"距**上课**还有多久"，**不是**"距下课" ——
        //    踩过的坑：旧代码把"到那节课下课"当成剩余时间，于是周二晚上推出
        //    "正在上课 · 还有 1482 分钟下课"（1482 ≈ 到周三第 10 节下课）。
        val up = DemoTimetable.upcoming() ?: return null
        val end = endOf(up.course)
        val endAt = end?.let { up.start.toLocalDate().atTime(it) }
        val untilStart = java.time.Duration.between(now, up.start).toMillis().coerceAtLeast(60_000L)
        return NowPick(up.course, false, endAt, untilStart)
    }

    // ---------------------------------------------------------------- 渲染

    private fun render() {
        // 上课/下课按钮的可用状态跟着会话走（render 是所有会话状态变化的汇聚点）
        refreshSessionButtons()
        val current = session
        val now = LocalDateTime.now()
        val weekday = now.dayOfWeek.value
        val sourceText = if (DemoTimetable.isDemo) "演示数据" else "已导入 ${DemoTimetable.COURSES.size} 门"
        tvToday.text = "第 ${DemoTimetable.viewWeek} 周 · ${weekdayLabel(weekday)} · $sourceText"

        if (current == null) {
            renderIdle(now)
        } else {
            renderRunning(current)
        }
    }

    private fun renderIdle(now: LocalDateTime) {
        val dayIndex = now.dayOfWeek.value - 1
        val fraction = realTimeFraction(now.toLocalTime())
        if (dayIndex in DemoTimetable.DAY_LABELS.indices && fraction != null) {
            gridView.setNow(dayIndex, fraction)
        } else {
            gridView.setNow(-1, -1f)
        }
        gridView.highlightCourseId = null
        gridView.highlightProgress = 0f

        progressNow.setProgressCompat(0, true)
        chipStatus.text = idleChipText()

        // 跨天找下一节课：今天上完了就往后看（周三没课也能告诉用户"下一节是周五…"）
        val upcoming = DemoTimetable.upcoming(now)
        if (upcoming != null) {
            val course = upcoming.course
            tvNowState.text = if (upcoming.week == DemoTimetable.viewWeek) "接下来" else "第 ${upcoming.week} 周"
            tvNowTitle.text = course.name
            tvNowSub.text = "${course.room} · ${course.teacher}"
            tvNowRange.text = "${weekdayLabel(course.dayOfWeek)} ${DemoTimetable.timeRange(course)}"
            tvNowRemain.text = relativeUntil(upcoming)
        } else {
            tvNowState.text = "没有课"
            tvNowTitle.text = "空闲"
            tvNowSub.text = "未来两周内课表里没有课"
            tvNowRange.text = "—"
            tvNowRemain.text = "点下面的按钮，把课程推送到通知栏试试"
        }
    }

    private fun renderRunning(current: Session) {
        val course = current.course
        val progress = current.progress()

        gridView.highlightCourseId = course.id
        gridView.highlightProgress = progress
        gridView.setNow(
            course.dayOfWeek - 1,
            DemoTimetable.fractionOf(course.startPeriod, progress * course.span)
        )

        progressNow.setProgressCompat(if (current.inClass) (progress * 100f).roundToInt() else 0, true)
        chipStatus.text = "已推送"

        tvNowState.text = if (current.inClass) "正在上课 · 已推送通知" else "现在没课 · 已推送下一节"
        tvNowTitle.text = course.name
        tvNowSub.text = "${course.room} · ${course.teacher}"
        tvNowRange.text = DemoTimetable.timeRange(course)
        // 悬浮岛跟着倒计时一起走；能显示实时通知的机型上直接收起悬浮岛，避免重复显示
        if (false) {
            islandOverlay?.hide()
        } else {
            islandOverlay?.update(
                course.name,
                if (current.inClass) {
                    "还有 ${((current.remainingMillis() + 59_999L) / 60_000L)} 分"
                } else {
                    DemoTimetable.dayLabel(course.dayOfWeek) + " " +
                        NotifyText.periodsText(course.startPeriod, course.span)
                },
                if (current.inClass) current.progress() else 0f
            )
        }
        tvNowRemain.text = if (current.inClass) {
            "剩余 " + formatDuration(current.remainingMillis())
        } else {
            DemoTimetable.dayLabel(course.dayOfWeek) + " " +
                NotifyText.periodsText(course.startPeriod, course.span)
        }

        if (current.remainingMillis() <= 0L) {
            // 到点自动下课（真实版本由 AlarmManager 触发，这里只是模拟）
            // ⚠️ 这条路径也必须收悬浮岛 —— 之前漏了，导致过了倒计时药丸还赖在屏幕上
            session = null
            lastPushedSignature = null
            LiveUpdate.finish(this, course.id)
            islandOverlay?.hide()
            render()
        }
    }

    // ---------------------------------------------------------------- 能力探测

    /** 空闲时顶部小标签的文案：一眼看出这台机器实际走哪条通道。 */
    /** 上课/下课两个按钮的状态：空闲只能点「上课」，进行中只能点「下课」。 */
    private fun refreshSessionButtons() {
        val running = session != null
        btnFull.isEnabled = !running
        btnFinish.isEnabled = running
    }

    private fun idleChipText(): String = when {
        LiveUpdate.isSupported(this) -> "通知可用"
        else -> "悬浮岛模式"
    }

    /**
     * 解析推送路由 + 刷新顶部状态。
     *
     * 设备能力面板（含 OEM 提示）已按要求整段移除 —— 这里只保留**功能上必需**的两件事：
     * 路由判断（实时通知还是悬浮岛）和空闲时的顶部状态文案。
     */
    private fun refreshCapability() {
        if (session == null) {
            chipStatus.text = idleChipText()
        }
        refreshSessionButtons()
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** 主题按钮文案（切换后系统会重建界面，这里保证文案始终一致）。 */
    /** 上次崩溃日志（有才显示入口）：一键复制，方便直接发我定位。 */
    private fun refreshCrashUi() {
        val text = CrashLog.read(this)
        if (text.isBlank()) {
            btnCrash.visibility = View.GONE
            return
        }
        btnCrash.visibility = View.VISIBLE
        btnCrash.setOnClickListener {
            drawerLayout.closeDrawers()
            val body = TextView(this).apply {
                this.text = text
                textSize = 11f
                setTextIsSelectable(true)
                setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
            }
            MaterialAlertDialogBuilder(this)
                .setTitle("上次崩溃日志（本机，未上传）")
                .setView(android.widget.ScrollView(this).apply { addView(body) })
                .setPositiveButton("复制全部") { _, _ ->
                    (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                        .setPrimaryClip(android.content.ClipData.newPlainText("crash", text))
                    Toast.makeText(this, "已复制，发我就行", Toast.LENGTH_SHORT).show()
                }
                .setNeutralButton("清除") { _, _ ->
                    CrashLog.clear(this)
                    refreshCrashUi()
                }
                .setNegativeButton("关闭", null)
                .show()
        }
    }

    private fun refreshThemeUi() {
        btnTheme.text = "主题：" + ThemeMode.label(this)
    }

    private fun openNotificationSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.fromParts("package", packageName, null))
        }
        startActivity(intent)
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.fromParts("package", packageName, null))
        )
    }

    /** 直达"本应用这条通知通道"的设置页 —— 有些 ROM 的"实时通知"开关就藏在这里。 */
    private fun openChannelSettings() {
        val channelId = LiveUpdate.DEFAULT_CHANNEL_ID
        startActivity(
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
        )
    }

    // ---------------------------------------------------------------- 小工具


    private fun weekdayLabel(dayOfWeek: Int): String {
        val labels = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
        return labels.getOrElse(dayOfWeek - 1) { "周日" }
    }



    /** 真实时间里「现在」在整张课表的纵向位置；不在任何节次内则为 null。 */
    private fun realTimeFraction(time: LocalTime): Float? {
        val periods = DemoTimetable.PERIODS
        for (period in periods) {
            if (!time.isBefore(period.start) && time.isBefore(period.end)) {
                val span = java.time.Duration.between(period.start, period.end).toMillis()
                    .coerceAtLeast(1L)
                val within = java.time.Duration.between(period.start, time).toMillis().toFloat() / span
                return DemoTimetable.fractionOf(period.index, within)
            }
        }
        return null
    }

    private fun formatDuration(millis: Long): String {
        val totalSeconds = (millis + 999L) / 1_000L
        val hours = totalSeconds / 3600L
        val minutes = totalSeconds % 3600L / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%02d:%02d".format(minutes, seconds)
        }
    }

    /** 把当前课表写进系统日历的专用日历「岛课表」。 */
    private fun writeCalendar() {
        val timetable = TimetableStore.load(this)
        if (timetable == null) {
            Toast.makeText(this, "还没有课表可写入（先导入课表）", Toast.LENGTH_SHORT).show()
            return
        }
        val written = CalendarExport.write(this, timetable)
        Toast.makeText(
            this,
            if (written > 0) "已写入 " + written + " 条到日历「岛课表」" else "写入失败（检查日历权限或学期起始日）",
            Toast.LENGTH_LONG
        ).show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CALENDAR && calendarsPending) {
            calendarsPending = false
            val granted = grantResults.isNotEmpty() &&
                grantResults.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }
            if (granted) writeCalendar() else Toast.makeText(this, "没有日历权限，写不进去", Toast.LENGTH_SHORT).show()
        }
    }



    /**
     * 切周动画：整块课表**先淡出并轻微左移 → 换数据 → 从右侧淡入**。
     *
     * 为什么不直接 fade：课表是"格子墙"，纯淡入淡出看不出"往前/往后翻"的方向感；
     * 加一点横向位移，翻周的方向就出来了。
     */
    private fun switchWeekAnimated(target: Int) {
        if (target == DemoTimetable.viewWeek) return
        gridView.animate().cancel()
        gridView.animate()
            .alpha(0f)
            .translationX(-dp(26f).toFloat())
            .setDuration(110L)
            .withEndAction {
                DemoTimetable.setViewWeek(target)
                applyTimetableToUi()
            refreshWeekUi()
                gridView.translationX = dp(26f).toFloat()
                gridView.animate()
                    .alpha(1f)
                    .translationX(0f)
                    .setDuration(170L)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
            }
            .start()
    }
}
