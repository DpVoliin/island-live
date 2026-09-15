package dev.dpvoliin.islandtimetable

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * 常驻通知：一条**划不掉**的状态通知，常显「下一节课」。
 *
 * 为什么用它：
 *  - `setOngoing(true)` 让通知无法被划掉（普通通知一划就没，课表类应用最需要的就是"一直在"）；
 *  - 开机、回到前台、被系统清掉之后都会自动补回来（[repostIfEnabled]）。
 *
 * 边界（如实说明）：
 *  - 用户在系统里点「强行停止」、或本应用被厂商省电策略冻结时，Android 不允许任何应用自行恢复
 *    （只有厂商白名单能豁免）—— 这不是本应用能绕的；
 *  - 部分 ROM 允许长按常驻通知「隐藏」它，这是系统行为。
 */
object StickyNotification {

    /**
     * 与**默认通知**（系统实时通知）共用同一条通道 —— 外观统一的关键一半。
     *
     * 通知栏里每条通知下面都写着「App 名 · 通道名」；通道不同 ⇒ 一眼就看出"不是一套"。
     * 共用后，图标/重要度/静音/横幅等设置也随之统一（用户改一次，两条一起变）。
     */
    private val CHANNEL_ID = dev.dpvoliin.liveupdates.LiveUpdate.DEFAULT_CHANNEL_ID
    /** 看门狗闹钟的 action：每 15 分钟检查一次，常驻通知被清掉就补回来。 */
    const val ACTION_STICKY_CHECK = "dev.dpvoliin.islandtimetable.STICKY_CHECK"

    /** 通知被划掉时的回调：立刻补回，做到"卡在通知栏里"。 */
    const val ACTION_REPOST = "dev.dpvoliin.islandtimetable.STICKY_REPOST"

    /** 被从最近任务划掉后的"秒复活"闹钟。 */
    const val ACTION_REVIVE = "dev.dpvoliin.islandtimetable.STICKY_REVIVE"

    /** 前台服务与通知共用同一个 id（startForeground 要求一致）。 */
    const val NOTIFICATION_ID = 9001
    private const val PREFS = "island_timetable"
    private const val KEY_ENABLED = "sticky_notification"

    private const val KEY_HIDE_WHEN_LIVE = "sticky_hide_when_live"

    /** 看门狗间隔：15 分钟。 */
    private const val WATCHDOG_INTERVAL_MS = 15 * 60 * 1000L

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 开关状态（默认关：常驻通知是用户明确要才开的东西）。 */
    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) {
            // ★ 先**立刻**出一条通知：开关的手感必须是确定的 ——
            //   不能等前台服务（可能被系统拦），否则用户看到"开了却没反应"，以为按钮坏了。
            postDirect(context)
            StickyService.start(context)
            scheduleWatchdog(context)
            runCatching { KeepaliveJobService.schedule(context) }   // 第二条独立路径（JobScheduler，重启后自恢复）
        } else {
            cancelWatchdog(context)
            runCatching { KeepaliveJobService.cancel(context) }
            StickyService.stop(context)
            cancel(context)                                        // 顺手撤掉，别等系统
            // ⚠️ 服务是**异步**死的：stopService 返回后它仍可能再执行一次 startForeground，
            //    而**前台服务的通知撤不掉** ⇒ 表现就是「关闭通知偶尔失灵」（用户实测）。
            //    半秒后确认一次：开关仍是关的就再撤，盖住这个竞态（也顺带盖住看门狗闹钟刚好触发的情形）。
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (!isEnabled(context)) cancel(context)
            }, 500L)
        }
    }

    /**
     * 「有系统实时通知时，自动隐藏常驻」。
     *
     * **默认关** —— 常驻开关的语义就是"开了它就一直挂在通知栏"。
     * 之前我把它做成了**自动让位**（发现实时通知就把常驻撤掉），于是用户"关了再开"、
     * 或者刚推过一条实时通知时，常驻就像**失效**了一样（其实是它在暗自动决策）。
     * 按"按钮各司其职、不做内部自动决策"的原则，这里改成用户显式选择。
     */
    fun isHideWhenLiveEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_HIDE_WHEN_LIVE, false)

    fun setHideWhenLiveEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_HIDE_WHEN_LIVE, enabled).apply()
        if (isEnabled(context)) repostIfEnabled(context)
    }

    /** 现在该不该让位：**用户显式开了去重**、且系统里确实挂着一条实时通知。 */
    fun shouldWithdraw(context: Context): Boolean =
        isHideWhenLiveEnabled(context) && notifyVisible(context)

    /**
     * 15 分钟一次的看门狗闹钟。
     *
     * 为什么必须有：前台服务能扛住"划不掉"，但**开关屏、系统深度省电、长时间不打开 App**
     * 仍可能把它清掉（进程被杀、通知被系统回收）。闹钟是独立于 App 进程的，到点照响 →
     * 检查开关并补回常驻通知，这样"长期不点开也还在"。
     */
    fun scheduleWatchdog(context: Context) {
        val alarm = context.getSystemService(android.app.AlarmManager::class.java) ?: return
        val pi = watchdogIntent(context)
        runCatching {
            alarm.setInexactRepeating(
                android.app.AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + WATCHDOG_INTERVAL_MS,
                WATCHDOG_INTERVAL_MS,
                pi
            )
        }
    }

    /**
     * 被划掉任务后的"秒复活"：1 秒后闹一次闹钟，由 [ReminderReceiver] 把服务重新拉起来。
     *
     * 为什么要隔 1 秒：厂商 ROM 把"划掉任务"当成"用户不要了"，会连带杀掉前台服务；
     * 划掉后的**瞬间**系统会短暂限制启动服务，立刻重启大概率失败 —— 隔一下再拉才稳。
     */
    fun scheduleTaskRemovedRevive(context: Context) {
        val alarm = context.getSystemService(android.app.AlarmManager::class.java) ?: return
        val pi = android.app.PendingIntent.getBroadcast(
            context,
            43,
            Intent(context, ReminderReceiver::class.java).setAction(ACTION_REVIVE),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        runCatching {
            alarm.setExactAndAllowWhileIdle(
                android.app.AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + 1000L,
                pi
            )
        }
    }

    /** 「划掉即补回」用的 PendingIntent（挂在通知的 deleteIntent 上）。 */
    fun repostIntent(context: Context): android.app.PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).setAction(ACTION_REPOST)
        return android.app.PendingIntent.getBroadcast(
            context,
            44,
            intent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    fun cancelWatchdog(context: Context) {
        val alarm = context.getSystemService(android.app.AlarmManager::class.java) ?: return
        runCatching { alarm.cancel(watchdogIntent(context)) }
    }

    private fun watchdogIntent(context: Context): android.app.PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).setAction(ACTION_STICKY_CHECK)
        return android.app.PendingIntent.getBroadcast(
            context,
            42,
            intent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /**
     * 开机 / 回到前台 / 被清掉之后调用：开着就补回来。
     *
     * 走**前台服务**而不是直接 notify() —— 普通 ongoing 通知会被 MIUI / OriginOS 划掉，
     * 前台服务的通知才真的划不掉（踩过"常驻通知不常驻"）。
     */
    /**
     * **默认通知**（系统实时通知）此刻是否真的挂在通知栏上。
     *
     * 为真时，常驻通知让位（否则两条通知内容几乎一样，都在通知栏里）。
     * 判据必须看"系统里真的有没有"：库的内存状态会因持久化残留而"以为还在"，
     * 那样常驻会被永久让位 —— 用户看到的就是「常驻通知又没了」。
     * 另外要排除自己（共用通道后，常驻也挂在同一条通道上）。
     */
    fun notifyVisible(context: Context): Boolean = runCatching {
        val nm = context.getSystemService(android.app.NotificationManager::class.java)
        nm.activeNotifications.any { posted ->
            posted.id != NOTIFICATION_ID &&
                posted.packageName == context.packageName &&
                posted.notification?.channelId == dev.dpvoliin.liveupdates.LiveUpdate.DEFAULT_CHANNEL_ID
        }
    }.getOrDefault(false)

    /**
     * 让位：**只撤通知，不停服务**（开关保持"开"）。
     *
     * 为什么不停服务：Android 12+ 禁止从后台启动前台服务。服务一旦被停，
     * 等默认通知撤下再想从后台拉回来会被系统拦住 → 结果就是"常驻通知又没了"
     * （v1.0.4 / v1.0.6 都踩过）。服务留在后台继续巡检，默认通知一撤下它自己重新认领。
     */
    fun withdrawForDefaultNotify(context: Context) {
        cancel(context)
    }

    fun repostIfEnabled(context: Context) {
        if (!isEnabled(context)) return
        // 只有用户显式打开「有实时通知时自动隐藏常驻」才让位（默认关：开关要可预期）
        if (shouldWithdraw(context)) {
            withdrawForDefaultNotify(context)
            return
        }
        // ① 先**直接补发通知** —— 任何场景（含后台闹钟、锁屏解锁）都允许，这是保底
        postDirect(context)
        // ② 再尝试升级为前台服务（拿到"划不掉"）。Android 12+ 在后台启动前台服务会被系统拦，
        //    拦掉也不影响① —— 所以这里允许静默失败。
        StickyService.start(context)
    }

    /** 直接发一条常驻通知（不依赖前台服务，后台也能调用）。 */
    fun postDirect(context: Context) {
        runCatching {
            context.getSystemService(android.app.NotificationManager::class.java)
                .notify(NOTIFICATION_ID, build(context))
        }
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    /** 构建常驻通知（前台服务与手动补发都用它，保证内容一致）。 */
    fun build(context: Context): android.app.Notification {
        ensureChannel(context)

        // 文案与「默认通知」共用 NotifyText；取课用 currentOrNext（正在上优先，否则下一节）
        val nn = runCatching { DemoTimetable.currentOrNext() }.getOrNull()
        val (title, content) = when {
            nn == null -> "岛课表" to "接下来没有课"
            nn.isNow -> NotifyText.title(NotifyText.STATUS_NOW, nn.course.name) to
                NotifyText.nowContent(nn.remainingMinutes(), nn.course.room)
            else -> NotifyText.title(NotifyText.STATUS_NEXT, nn.course.name) to
                NotifyText.where(
                    DemoTimetable.dayLabel(nn.course.dayOfWeek),
                    NotifyText.periodsText(nn.course.startPeriod, nn.course.span),
                    nn.course.room
                )
        }

        val tapIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            // ★ 外观与**默认通知**逐项对齐：同图标（ic_class，不是 launcher）、同类目（PROGRESS）、
            //   同样的静音/时间戳/仅提示一次策略 —— 两条通知在通知栏里长得一样。
            .setSmallIcon(R.drawable.ic_class)
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(tapIntent)
            .setOngoing(true)                          // 关键：划不掉
            .setAutoCancel(false)                      // 点开也不自动消失
            .setDeleteIntent(repostIntent(context))    // 真被划掉 → 立刻补回（"卡在通知栏"）
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        // 上课中：把默认通知还多的两样也补上 —— 副标题（时段 · 教师）与课程进度条；
        // 空闲态两边都不带进度、不带副标题。
        if (nn != null && nn.isNow) {
            builder.setSubText(DemoTimetable.timeRange(nn.course) + " · " + nn.course.teacher)
            val end = nn.end
            if (end != null) {
                val total = java.time.Duration.between(nn.start, end).toMillis().coerceAtLeast(1L)
                val used = java.time.Duration.between(nn.start, java.time.LocalDateTime.now()).toMillis()
                val percent = ((used * 100L) / total).coerceIn(0L, 100L).toInt()
                builder.setProgress(100, percent, false)
            }
        }

        val notification = builder.build()
        // 「提升为岛」：只在系统允许时打标记（纯公开 API；不支持就原样，什么都不变）
        PromotedNotify.markIfAllowed(context, notification)
        return notification
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                dev.dpvoliin.liveupdates.LiveUpdate.DEFAULT_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "上课期间的实时通知与常驻课表状态（不可划掉）"
                setShowBadge(false)
                enableVibration(false)
            }
            manager.createNotificationChannel(channel)
        }
    }
}
