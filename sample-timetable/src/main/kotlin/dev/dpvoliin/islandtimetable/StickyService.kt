package dev.dpvoliin.islandtimetable

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat

/**
 * 常驻通知的**承载前台服务**。
 *
 * 为什么必须有它：只发一条 `setOngoing(true)` 的普通通知是不够的 ——
 * 小米 / vivo 等 ROM 的省电策略会在后台把它划掉或清掉，用户看到的就是"常驻通知不常驻"。
 * 走前台服务（`startForeground` + specialUse 类型）后，通知由系统按"正在前台运行"对待，
 * 通知栏里划不掉，进程也被顶到前台优先级，不容易被回收。
 *
 * 边界（如实说明，不夸大）：
 *  - 用户在系统里点「强行停止」，或系统重启后本服务被厂商冻结时，Android 不允许应用自行恢复；
 *  - 部分 ROM 允许长按"隐藏"通知，属系统行为。
 */
class StickyService : Service() {

    companion object {
        private const val ACTION_START = "dev.dpvoliin.islandtimetable.STICKY_START"
        /** 常驻通知内容刷新间隔：1 分钟（够用且省电；系统合并通知本身也有节流）。 */
        private const val TICK_MS = 60_000L

        /** 开：拉起前台服务（已在跑就刷新通知内容）。 */
        fun start(context: Context) {
            val intent = Intent(context, StickyService::class.java).setAction(ACTION_START)
            // 后台启动前台服务在 Android 12+ 受限制，包一层避免任何情况下崩溃
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        /** 关：撤下通知并停服务。 */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, StickyService::class.java)) }
        }
    }

    /** 亮屏/解锁时用的动态接收器（只在服务活着时注册，服务没了自然释放）。 */
    private var screenReceiver: android.content.BroadcastReceiver? = null

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * 每分钟重建一次常驻通知。
     *
     * 为什么必须刷新：① 上课/下课时内容要从"下一节"切成"正在上课 · 还有 N 分钟下课"；
     * ② 剩余分钟要跟着走。原来只在 App 打开 / 15 分钟看门狗时刷新，
     * 于是"上课了通知还写着下一节"、"剩余分钟一直不动"。
     * 顺带在这里检查"默认通知是否挂在栏上"：挂着就自觉让位（撤自己的通知），避免两条并存。
     *
     * ⚠️ 刷新**必须走 [showForeground]（startForeground）**，不能图省事用
     * NotificationManager.notify() —— 后者在 MIUI / OriginOS 上会把"前台服务通知"
     * 降级成普通通知，用户看到的就是"常驻通知又能被划掉了"（v1.0.4 踩过这个坑）。
     */
    private val ticker = object : Runnable {
        override fun run() {
            if (!StickyNotification.isEnabled(this@StickyService)) {
                handler.removeCallbacks(this)
                return
            }
            if (!StickyNotification.isEnabled(this@StickyService)) {
                // 开关被关掉了（含系统重启后 START_STICKY 把我们拉回来）：老实收摊，别复活
                handler.removeCallbacks(this)
                StickyNotification.cancel(this@StickyService)
                stopSelf()
                return
            }
            if (StickyNotification.shouldWithdraw(this@StickyService)) {
                // 让位：撤掉自己的通知，但**不停服务**（停了以后从后台就拉不回来了 → "常驻没了"）
                runCatching {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    StickyNotification.cancel(this@StickyService)
                }
                handler.postDelayed(this, TICK_MS)
                return
            }
            if (!showForeground()) {
                // 系统暂时不让当前台服务（后台限制等）→ 退化成普通通知，绝不崩
                StickyNotification.postDirect(this@StickyService)
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // 厂商"息屏深省电"清掉通知后，亮屏/解锁这一下顺手补回来
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                        // 息屏深省电可能把前台通知降级/清掉：亮屏这一下重新认领
                        runCatching { showForeground() }
                    }
                }
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        runCatching {
            androidx.core.content.ContextCompat.registerReceiver(
                this, receiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
            )
            screenReceiver = receiver
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        screenReceiver?.let { r -> runCatching { unregisterReceiver(r) } }
        screenReceiver = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 开关关着（例如系统把 START_STICKY 的我们拉回来）→ 立刻收摊，不要复活通知
        if (!StickyNotification.isEnabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!showForeground()) {
            // 前台服务被系统拒：先保证有通知（否则像"开了没反应"），再收摊，
            // 免得踩 startForegroundService 的 5 秒契约（不 startForeground 会被判异常）
            StickyNotification.postDirect(this)
            stopSelf()
            return START_NOT_STICKY
        }
        // 内容自刷新（每分钟一次）
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, TICK_MS)
        // 被系统杀掉后自动重建（重建时会回到这里再 startForeground）
        return START_STICKY
    }

    /**
     * 用**当前内容重新认领**前台通知（`startForeground`）——这是"划不掉"的唯一正解。
     *
     * 为什么不能改用 NotificationManager.notify() 刷新：在 MIUI / OriginOS 上，
     * notify() 会把前台服务通知降级成普通通知 ⇒ 用户一划就没（v1.0.4 的 60 秒自刷新踩过）。
     * 每轮刷新都重新 startForeground，通知才一直挂在"前台服务"身份上。
     */
    private fun showForeground(): Boolean = runCatching {
        val notification = StickyNotification.build(this)
        // specialUse 类型常量是 API 34 才有的，低版本走无类型重载（避免传非法 type 抛异常）
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                StickyNotification.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(StickyNotification.NOTIFICATION_ID, notification)
        }
        true
    }.getOrElse { error ->
        // ⚠️ 这里必须兜住：Android 12+ 在后台调 startForeground 会抛
        // ForegroundServiceStartNotAllowedException —— 不接就是**直接闪退**（用户报的"再开通知会崩"）。
        android.util.Log.w("StickyService", "startForeground 被系统拒绝：" + error.javaClass.simpleName)
        false
    }

    /**
     * 用户从最近任务划掉本应用时调用 —— **"一滑掉就没了"的正解在这里**。
     *
     * 做两件事：
     *  ① 立刻**直接补发通知**（任何场景都允许，先把"常驻"这件事保住）；
     *  ② 1 秒后闹一次闹钟，让 [ReminderReceiver] 把前台服务重新拉起来（详见 scheduleTaskRemovedRevive）。
     *
     * 如实边界：用户在系统里点「强行停止」、或系统开机后不允许后台启动时，Android 不允许应用自行恢复。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (StickyNotification.isEnabled(this)) {
            StickyNotification.postDirect(this)
            StickyNotification.scheduleTaskRemovedRevive(this)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
