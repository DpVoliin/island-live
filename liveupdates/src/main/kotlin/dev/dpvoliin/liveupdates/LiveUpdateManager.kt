package dev.dpvoliin.liveupdates

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import android.graphics.drawable.Icon

/**
 * 库内部实现：通知构建 / 通道管理 / 超时兜底 / 残留清理。
 *
 * 公开 API 一律走 [LiveUpdate] 门面，外部不要直接依赖这个类。
 *
 * 几个刻意的设计：
 * - 同一 id 复用同一个通知（tag=id），更新即原地刷新，不会在通知栏堆一串。
 * - 超时用 [AlarmManager] 而不是进程内的 Handler —— 进程被杀后仍然能准时把岛撤下。
 * - 更新时保留原超时时间：反复刷新进度不会把「下课时间」越推越远。
 * - 落盘记录活跃条目，App 下次启动时把过期残留清掉（否则会留一个划不掉的常驻通知）。
 */
internal class LiveUpdateManager private constructor(private val appContext: Context) {

    private class Active(val spec: LiveUpdateSpec, val deadlineMillis: Long) {
        /**
         * 「主通知」= 当前最新那一条。
         *
         * 它**不带 tag** 发（键 = (null, NOTIFICATION_ID)），这样前台服务 startForeground 能认领
         * **同一条**通知 —— 否则用户会看到"岛上多出一条前台服务的常驻通知"，很脏。
         * 多路并存时，非主通知才带 tag 区分。
         */
        var primary: Boolean = false
    }

    /** 最近一次渲染的 spec：前台服务被系统拉回来时用它重建通知（进程内缓存）。 */
    @Volatile
    private var lastSpec: LiveUpdateSpec? = null

    private val active = LinkedHashMap<String, Active>()
    private val notificationManager: NotificationManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- 公开动作

    fun post(spec: LiveUpdateSpec): Boolean {
        val deadline = if (spec.timeoutMillis > 0L) {
            System.currentTimeMillis() + spec.timeoutMillis
        } else {
            0L
        }
        return render(spec, deadline)
    }

    fun update(id: String, configure: LiveUpdateSpec.Builder.() -> Unit): Boolean {
        val current = active[id] ?: return false
        val builder = LiveUpdateSpec.Builder(current.spec)
        builder.configure()
        return render(builder.build(), current.deadlineMillis)
    }

    fun finish(id: String) {
        val wasPrimary = active[id]?.primary == true
        active.remove(id)
        cancelAlarm(id)
        // 两个键都撤：带 tag 的 + 主的（撤不存在的通知是空操作）
        notificationManager.cancel(tagFor(id), NOTIFICATION_ID)
        if (wasPrimary) notificationManager.cancel(NOTIFICATION_ID)
        LiveUpdateStore.remove(appContext, id)
        // 主通知没了：把剩下最新的那条提升为主，否则前台服务就没"自己的"通知了
        if (wasPrimary) {
            val next = active.entries.lastOrNull()
            if (next != null) {
                next.value.primary = true
                lastSpec = next.value.spec
                runCatching { notificationManager.cancel(tagFor(next.value.spec.id), NOTIFICATION_ID) }
                runCatching { notificationManager.notify(NOTIFICATION_ID, buildNotification(next.value.spec)) }
            }
        }
        if (active.isEmpty()) {
            lastSpec = null
            cancelKeepAlive()
            if (isForegroundEnabled()) LiveUpdateService.stop(appContext)
        }
        persist()
    }

    fun finishAll() {
        for (id in allKnownIds()) {
            finish(id)
        }
        // 兜底：全清之后不该再有任何残留
        LiveUpdateStore.clear(appContext)
        lastSpec = null
        cancelKeepAlive()
        if (isForegroundEnabled()) LiveUpdateService.stop(appContext)
    }

    fun isActive(id: String): Boolean = active.containsKey(id)

    fun activeIds(): List<String> = active.keys.toList()

    /**
     * 诊断：把系统里**已经发出去的那条通知**读回来，看关键字段到底有没有设上。
     * 「通知出现了但没升级成实时通知」时，这是唯一能自证的地方（外部只看得到现象）。
     */
    fun inspect(id: String): String {
        val all = notificationManager.activeNotifications
            ?: return "系统未返回活动通知列表"
        val record = all.firstOrNull { it.tag == tagFor(id) && it.id == NOTIFICATION_ID }
            ?: return "没找到 tag=${tagFor(id)} / id=$NOTIFICATION_ID 的活动通知（是否已撤下？）"
        val notification = record.notification
        val extras = notification.extras
        val keys = extras.keySet().sorted().joinToString(", ")
        return buildString {
            append("tag=").append(record.tag).append("  id=").append(record.id).append('\n')
            append("常驻(ongoing)=")
                .append((notification.flags and Notification.FLAG_ONGOING_EVENT) != 0)
                .append('\n')
            append("通道=").append(notification.channelId)
                .append("  重要度=")
                .append(notificationManager.getNotificationChannel(notification.channelId)?.importance ?: -1)
                .append('\n')
            append("extras 共 ").append(extras.size()).append(" 项: ").append(keys).append("\n\n")
            append("—— 实时通知关键项 ——\n")
            append(EXTRA_REQUEST_PROMOTED).append(" = ")
                .append(extras.getBoolean(EXTRA_REQUEST_PROMOTED, false)).append('\n')
            append(EXTRA_SHORT_CRITICAL).append(" = ")
                .append(extras.getCharSequence(EXTRA_SHORT_CRITICAL) ?: "（无）").append('\n')
            append("android.template（样式类）= ")
                .append(extras.get("android.template") ?: "（无）").append('\n')
            append("进度样式 extras: styledByProgress=")
                .append(extras.get("android.styledByProgress") ?: "无")
                .append(", progressPoints=").append(extras.get("android.progressPoints") ?: "无")
                .append(", progressSegments=").append(extras.get("android.progressSegments") ?: "无")
                .append('\n')
            append("静音=").append(notification.extras.getBoolean("android.silent", false))
                .append("  priority=").append(notification.priority).append('\n')
        }
    }

    /**
     * App 启动时调用（幂等）：撤下已过期的残留，给未到期的重新排兜底闹钟。
     * 本进程内正在活跃的条目不被动。
     */
    fun restore() {
        val now = System.currentTimeMillis()
        val kept = mutableSetOf<String>()
        for ((id, entry) in active) {
            if (entry.deadlineMillis > 0L) kept += encode(id, entry.deadlineMillis)
        }

        val stored = prefs.getStringSet(KEY_ACTIVE, emptySet())?.toList() ?: emptyList()
        var reaped = 0
        for (entry in stored) {
            val id = entry.substringBefore(SEP)
            if (active.containsKey(id)) continue
            val deadline = entry.substringAfter(SEP, "0").toLongOrNull() ?: 0L
            if (deadline <= 0L) continue
            if (deadline <= now) {
                notificationManager.cancel(tagFor(id), NOTIFICATION_ID)
                cancelAlarm(id)
                reaped++
            } else {
                scheduleAlarm(id, deadline)
                kept += encode(id, deadline)
            }
        }
        prefs.edit().putStringSet(KEY_ACTIVE, kept).apply()
        if (reaped > 0) Log.i(TAG, "restore: 清理了 $reaped 个已过期的残留条目")

        // 还有没结束的岛 → 让看门狗接管（进程被杀后闹钟照响，通知能被补回来）
        if (isForegroundEnabled() && LiveUpdateStore.load(appContext).isNotEmpty()) scheduleKeepAlive()
    }

    // ---------------------------------------------------------------- 通知/后台存活

    /** 当前该展示的那条通知：优先内存里的，没有就从磁盘重建（进程被杀后场景）。 */
    fun currentNotification(): Notification? {
        lastSpec?.let { return buildNotification(it) }
        return LiveUpdateStore.load(appContext).firstOrNull()?.let { (spec, _) -> buildNotification(spec) }
    }

    /** 是否用前台服务保活（默认开）。关掉能省一点开销，但进程更容易被系统回收。 */
    fun isForegroundEnabled(): Boolean = prefs.getBoolean(KEY_FOREGROUND, true)

    fun setForegroundEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_FOREGROUND, enabled).apply()
        if (enabled) {
            if (active.isNotEmpty()) {
                LiveUpdateService.start(appContext)
                scheduleKeepAlive()
            }
        } else {
            cancelKeepAlive()
            LiveUpdateService.stop(appContext)
        }
    }

    /**
     * 看门狗（由 AlarmManager 触发，**进程死了也照响**）：
     * 该在的通知不在了就补回来 —— 覆盖"用户划掉常驻通知""系统清掉通知""服务没被系统拉回来"。
     */
    fun onKeepAlive() {
        val stored = LiveUpdateStore.load(appContext)
        if (stored.isEmpty()) {
            cancelKeepAlive()
            return
        }
        val now = System.currentTimeMillis()
        for ((spec, deadline) in stored) {
            if (deadline in 1..now) {
                finish(spec.id)                       // 已经过点了，撤下
                continue
            }
            if (!isNotificationAlive(spec.id)) {
                Log.i(TAG, "看门狗：${spec.id} 的通知不在了，补回来")
                render(spec, deadline)
            }
        }
        if (LiveUpdateStore.load(appContext).isNotEmpty()) scheduleKeepAlive()
    }

    /** 立刻查一遍（设置页按钮用），返回补回来的条数。 */
    fun keepAliveNow(): Int {
        val stored = LiveUpdateStore.load(appContext)
        val now = System.currentTimeMillis()
        var fixed = 0
        for ((spec, deadline) in stored) {
            if (deadline in 1..now) continue
            if (!isNotificationAlive(spec.id)) {
                render(spec, deadline)
                fixed++
            }
        }
        if (LiveUpdateStore.load(appContext).isNotEmpty()) scheduleKeepAlive()
        return fixed
    }

    /** 给设置界面看的人话状态。 */
    fun keepAliveStatus(): String {
        val stored = LiveUpdateStore.load(appContext)
        if (stored.isEmpty()) return "没有进行中的岛"
        val alive = stored.count { isNotificationAlive(it.first.id) }
        val fg = if (isForegroundEnabled()) "开（前台服务保活）" else "关（更容易被系统回收）"
        return "进行中 ${stored.size} 条 · 通知还在 $alive 条\n前台服务保活：$fg"
    }

    private fun isNotificationAlive(id: String): Boolean = runCatching {
        notificationManager.activeNotifications.any { it.tag == tagFor(id) && it.id == NOTIFICATION_ID }
    }.getOrDefault(true)   // 查不到就当它活着，不乱补

    private fun scheduleKeepAlive() {
        val am = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        runCatching { am.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + KEEPALIVE_INTERVAL_MS, keepAliveIntent()) }
    }

    private fun cancelKeepAlive() {
        val am = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        runCatching { am.cancel(keepAliveIntent()) }
    }

    private fun keepAliveIntent(): PendingIntent = PendingIntent.getBroadcast(
        appContext,
        7,
        Intent(appContext, LiveUpdateAlarmReceiver::class.java).setAction(ACTION_KEEPALIVE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    // ---------------------------------------------------------------- 内部实现

    private fun render(spec: LiveUpdateSpec, deadlineMillis: Long): Boolean {
        if (!canPostNotifications()) {
            Log.w(TAG, "没有通知权限，publish 被忽略：${spec.id}")
            return false
        }
        ensureChannel(spec.channelId, spec.channelName, spec.importance)
        val previous = active[spec.id]
        active[spec.id] = Active(spec, deadlineMillis).also { it.primary = previous?.primary ?: true }
        // 新来的是主通知，其余降级成带 tag 的普通通知
        for ((id, entry) in active) {
            if (id == spec.id) continue
            if (entry.primary) {
                entry.primary = false
                runCatching { notificationManager.notify(tagFor(id), NOTIFICATION_ID, buildNotification(entry.spec)) }
                // 旧的主通知是"不带 tag"的那条，必须撤掉：同一个 NOTIFICATION_ID 带不带 tag
                // 在系统里算**两条通知**，不撤就会出现"同一门课两条通知"
                runCatching { notificationManager.cancel(NOTIFICATION_ID) }
            }
        }
        val post = {
            val notification = buildNotification(spec)
            if (active[spec.id]?.primary == true) {
                // 主通知：不带 tag → 前台服务能认领同一条。
                // 发之前先撤掉"同 id 的 tag 版本"——两条并存就是用户看到的"两个通知"
                runCatching { notificationManager.cancel(tagFor(spec.id), NOTIFICATION_ID) }
                notificationManager.notify(NOTIFICATION_ID, notification)
            } else {
                notificationManager.notify(tagFor(spec.id), NOTIFICATION_ID, notification)
            }
        }
        // 有闸门就走闸门（例如 szk 变体：先临时关掉厂商拦截，再发）；失败也要把通知发出去
        val gate = LiveUpdate.notifyGate
        if (gate == null) post() else runCatching { gate(post) }.onFailure { post() }
        if (deadlineMillis > 0L) scheduleAlarm(spec.id, deadlineMillis) else cancelAlarm(spec.id)
        persist()

        // ---- 通知存活 / 后台存活 ----
        // ① 落盘全量状态：进程被杀后还能重建出**一模一样**的这条通知
        lastSpec = spec
        LiveUpdateStore.save(appContext, spec, deadlineMillis)
        // ② 前台服务：把进程顶到"前台"优先级；③ 看门狗：通知被划掉就补回来
        if (isForegroundEnabled()) {
            LiveUpdateService.start(appContext)
            scheduleKeepAlive()
        }
        return true
    }

    private fun buildNotification(spec: LiveUpdateSpec): Notification {
        val builder = NotificationCompat.Builder(appContext, spec.channelId)
            .setContentTitle(spec.title)
            .setSmallIcon(if (spec.smallIconRes != 0) spec.smallIconRes else fallbackSmallIcon())
            .setOngoing(spec.ongoing)
            .setAutoCancel(false)
            .setOnlyAlertOnce(spec.alertOnce)
            .setShowWhen(spec.showTimestamp)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(spec.silent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setStyle(progressStyle(spec))

        // 逃生舱：自定义 extras（厂商私有字段等）。
        // ⚠️ setExtras 会**替换**整个 extras bundle，所以下面必须把两个关键字段重新补上。
        spec.extras?.let { builder.setExtras(it) }
        // 小米 HyperOS：附加「超级岛 / 焦点通知」payload。
        // 这里刻意**只要是小米设备就附带**：万一某些 ROM 的 canShowFocus 探测不准，
        // 也给系统一个认领的机会；系统不认时按协议 `isShowNotification=true` 退化为普通通知，无害。
        // ★ 实时通知上显示的超短文字（系统对这类通知有字数约束）

        spec.text?.let { builder.setContentText(it) }
        spec.subText?.let { builder.setSubText(it) }
        spec.largeIcon?.let { builder.setLargeIcon(it) }
        spec.contentIntent?.let { builder.setContentIntent(it) }
        return builder.build()
    }

    private fun progressStyle(spec: LiveUpdateSpec): NotificationCompat.ProgressStyle {
        val style = NotificationCompat.ProgressStyle()
        spec.percent?.let { style.setProgress(it) }
        if (spec.segmentLengths.isNotEmpty()) {
            style.setProgressSegments(
                spec.segmentLengths.map { NotificationCompat.ProgressStyle.Segment(it) }
            )
        }
        if (spec.progressPoints.isNotEmpty()) {
            style.setProgressPoints(
                spec.progressPoints.map { NotificationCompat.ProgressStyle.Point(it) }
            )
        }
        return style
    }

    private fun ensureChannel(channelId: String, channelName: CharSequence, importance: Int) {
        if (notificationManager.getNotificationChannel(channelId) != null) return
        val channel = NotificationChannel(
            channelId,
            channelName,
            importance
        ).apply {
            description = "进行中活动的实时进度（Live Updates · 实时通知）"
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun canPostNotifications(): Boolean {
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        return granted && NotificationManagerCompat.from(appContext).areNotificationsEnabled()
    }

    private fun fallbackSmallIcon(): Int {
        val appIcon = appContext.applicationInfo.icon
        return if (appIcon != 0) appIcon else android.R.drawable.stat_notify_more
    }

    private fun scheduleAlarm(id: String, deadlineMillis: Long) {
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            deadlineMillis,
            alarmPendingIntent(id)
        )
    }

    private fun cancelAlarm(id: String) {
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(alarmPendingIntent(id))
    }

    private fun alarmPendingIntent(id: String): PendingIntent {
        val intent = Intent(appContext, LiveUpdateAlarmReceiver::class.java)
            .setAction(ACTION_REAP)
            .putExtra(EXTRA_ID, id)
        return PendingIntent.getBroadcast(
            appContext,
            id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun persist() {
        val set = active.entries
            .filter { it.value.deadlineMillis > 0L }
            .map { encode(it.key, it.value.deadlineMillis) }
            .toSet()
        prefs.edit().putStringSet(KEY_ACTIVE, set).apply()
    }

    private fun allKnownIds(): Set<String> {
        val ids = LinkedHashSet(active.keys)
        prefs.getStringSet(KEY_ACTIVE, emptySet())?.forEach { ids += it.substringBefore(SEP) }
        return ids
    }

    companion object {
        private const val TAG = "LiveUpdate"
        private const val PREFS_NAME = "island_live_updates"
        private const val KEY_ACTIVE = "active"
        private const val SEP = '|'
        /** 通知 id。注意：主通知不带 tag 发，前台服务靠它认领同一条。 */
        internal const val NOTIFICATION_ID = 1901
        private const val KEY_FOREGROUND = "foreground_enabled"
        /** 看门狗间隔。15 分钟足够（前台服务才是主力，这个只兜底），也不用精确闹钟权限。 */
        private const val KEEPALIVE_INTERVAL_MS = 15 * 60 * 1000L

        internal const val ACTION_KEEPALIVE = "dev.dpvoliin.liveupdates.action.KEEPALIVE"

        internal const val ACTION_REAP = "dev.dpvoliin.liveupdates.action.REAP"
        internal const val EXTRA_ID = "dev.dpvoliin.liveupdates.extra.ID"
        internal const val EXTRA_REQUEST_PROMOTED = "android.requestPromotedOngoing"
        internal const val EXTRA_SHORT_CRITICAL = "android.shortCriticalText"

        @Volatile
        private var instance: LiveUpdateManager? = null

        fun get(context: Context): LiveUpdateManager {
            val appContext = context.applicationContext
            return instance ?: synchronized(this) {
                instance ?: LiveUpdateManager(appContext).also { instance = it }
            }
        }

        /** 注意：`id` 里不要出现 '|'，它被用来做落盘分隔符。 */
        private fun tagFor(id: String): String = "liveupdates:$id"

        private fun encode(id: String, deadlineMillis: Long): String = "$id$SEP$deadlineMillis"
    }
}
