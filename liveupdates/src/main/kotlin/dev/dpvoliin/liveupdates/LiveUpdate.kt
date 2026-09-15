package dev.dpvoliin.liveupdates

import android.content.Context

/**
 * 实时通知库的门面 API。
 *
 * 三个动作 + 两个探测，够用：
 * ```
 * LiveUpdate.init(context)                       // App 启动时调一次（清理过期残留）
 * LiveUpdate.publish(context, "class-1") { … }   // 推一条实时通知
 * LiveUpdate.update(context, "class-1") { … }    // 原地更新（内部自动节流）
 * LiveUpdate.finish(context, "class-1")          // 撤下
 * LiveUpdate.isSupported(context)                // 这台机器能不能显示实时通知
 * ```
 *
 * 设计约束：
 * - 能力不够时**静默降级**为普通通知，绝不抛异常、绝不弹错。
 * - 库只依赖 androidx.core，不引任何厂商 SDK，公开 API 不暴露 androidx 类型。
 */
object LiveUpdate {

    /**
     * 发通知的"闸门"（可选，默认 null = 直接发）。
     *
     * 存在的唯一理由：**某些厂商会拦第三方实时通知**。已知案例是小米 HyperOS ——
     * 第三方应用用私有字段发"焦点通知"时，会被 XMSF（`com.xiaomi.xmsf`，互联互通框架）拦下，
     * 表现为「通知在、岛不出」。绕过办法是发通知前**临时禁止 XMSF 联网**、发完再恢复，
     * 但那需要 shell 权限（Shizuku）—— 所以做成钩子，让"带提权的变体"自己注入，
     * 默认变体保持零依赖、零提权。
     *
     * 实现见 sample-timetable 的 `szk` 变体（`SzkBridge.applyGate`）。
     */
    var notifyGate: ((post: () -> Unit) -> Unit)? = null

    const val DEFAULT_CHANNEL_ID: String = "island_live_updates"

    const val DEFAULT_CHANNEL_NAME: String = "进行中活动"

    /**
     * App 启动时调用一次即可（幂等）。
     * 作用：把上次进程被杀时留下的、已过期的「岛」撤下，并给还没到期的重新排兜底闹钟。
     */
    fun init(context: Context) {
        LiveUpdateManager.get(context).restore()
    }

    /** 这台设备/系统是否具备显示实时通知的条件（Android 16+ 且系统允许提升）。 */
    fun isSupported(context: Context): Boolean = capability(context).canPromote

    /** 完整能力探测报告，适合在设置页/引导页展示。 */
    fun capability(context: Context): CapabilityReport = CapabilityProbe.report(context)

    /**
     * 推一条「进行中活动」。
     *
     * @return true = 已发出去（含降级为普通通知）；false = 连通知都没权限，什么都没发生。
     */
    fun publish(context: Context, id: String, configure: LiveUpdateSpec.Builder.() -> Unit): Boolean {
        val builder = LiveUpdateSpec.Builder(id)
        builder.configure()
        return LiveUpdateManager.get(context).post(builder.build())
    }

    /**
     * 原地更新已存在的条目（保留原超时时间，不会因为反复更新而无限续命）。
     *
     * @return false = 该 id 当前不存在（例如进程重启后），需要改用 [publish]。
     */
    fun update(context: Context, id: String, configure: LiveUpdateSpec.Builder.() -> Unit): Boolean =
        LiveUpdateManager.get(context).update(id) { configure() }

    /** 把一个条目从岛上撤下。幂等。 */
    fun finish(context: Context, id: String) {
        LiveUpdateManager.get(context).finish(id)
    }

    /** 撤下本库发出的全部条目。 */
    fun finishAll(context: Context) {
        LiveUpdateManager.get(context).finishAll()
    }

    fun isActive(context: Context, id: String): Boolean = LiveUpdateManager.get(context).isActive(id)

    // ---------------------------------------------------------- 通知 / 后台存活

    /** 存活状态（给设置界面显示）。 */
    fun keepAliveStatus(context: Context): String = LiveUpdateManager.get(context).keepAliveStatus()

    /** 立刻检查一遍：通知被划掉/被系统清了就补回来，返回补回来的条数。 */
    fun checkAlive(context: Context): Int = LiveUpdateManager.get(context).keepAliveNow()

    /** 是否用前台服务保活（默认开）。 */
    fun isKeepAliveEnabled(context: Context): Boolean = LiveUpdateManager.get(context).isForegroundEnabled()

    fun setKeepAliveEnabled(context: Context, enabled: Boolean) =
        LiveUpdateManager.get(context).setForegroundEnabled(enabled)

    fun activeIds(context: Context): List<String> = LiveUpdateManager.get(context).activeIds()

    /**
     * 诊断：读回系统里**已经发出去的那条通知**，看 `requestPromotedOngoing` /
     * `shortCriticalText` / 进度样式到底有没有设上。
     *
     * 「通知出现了却没升级成实时通知」时，把它打印出来就能分清是"我们没设"还是"系统/OEM 不给显示"。
     * 返回的文本适合直接截图上报机型问题。
     */
    fun inspect(context: Context, id: String): String = LiveUpdateManager.get(context).inspect(id)

    /** Java 友好的一行式重载（不写 DSL）。 */
    @JvmOverloads
    fun publish(
        context: Context,
        id: String,
        title: CharSequence,
        text: CharSequence? = null,
        progressUsed: Long = 0L,
        progressTotal: Long = 0L,
        smallIconRes: Int = 0,
        timeoutMillis: Long = LiveUpdateSpec.Builder.DEFAULT_TIMEOUT_MILLIS
    ): Boolean = publish(context, id) {
        this.title = title
        this.text = text
        this.smallIconRes = smallIconRes
        this.timeoutMillis = timeoutMillis
        if (progressTotal > 0L) progress(progressUsed, progressTotal)
    }
}
