package dev.dpvoliin.liveupdates

import android.app.PendingIntent
import android.graphics.drawable.Icon
import android.app.NotificationManager
import android.os.Bundle

/**
 * 一条「进行中活动」（Live Update）的完整描述。
 *
 * 正常不需要直接 new，用 [LiveUpdate.publish] / [LiveUpdate.update] 的 DSL 构建：
 *
 * ```
 * LiveUpdate.publish(context, "class-1") {
 *     title = "正在上课 · 高等数学"
 *     text  = "还有 43 分钟下课 · 3教305"
 *     smallIconRes = R.drawable.ic_class
 *     progress(5, 45)
 *     timeout(50 * 60_000L)
 * }
 * ```
 */
class LiveUpdateSpec internal constructor(builder: Builder) {

    val id: String = builder.id
    val title: CharSequence = builder.title
    val text: CharSequence? = builder.text
    val subText: CharSequence? = builder.subText
    /** 实时通知上显示的**超短**文字（≤6 字，如「26分」）。不设它，系统可能不显示这条实时通知。 */
    val shortCriticalText: String? = builder.shortCriticalText
    val smallIconRes: Int = builder.smallIconRes
    val largeIcon: Icon? = builder.largeIcon
    val progressUsed: Long = builder.used
    val progressTotal: Long = builder.total
    val segmentLengths: List<Int> = builder.segmentLengths.toList()
    val progressPoints: List<Int> = builder.progressPoints.toList()
    val timeoutMillis: Long = builder.timeoutMillis
    val ongoing: Boolean = builder.ongoing
    val channelId: String = builder.channelId
    val channelName: CharSequence = builder.channelName
    val contentIntent: PendingIntent? = builder.contentIntent
    val showTimestamp: Boolean = builder.showTimestamp
    val alertOnce: Boolean = builder.alertOnce
    /** 是否静音。部分 ROM 可能把"静音"当成非实时通知，做成可调项便于真机对比。 */
    val silent: Boolean = builder.silent
    /** 通道重要度（**只在通道首次创建时生效**，系统不允许改已有的通道）。 */
    val importance: Int = builder.importance
    /** 逃生舱：原样塞进通知 extras 的自定义键值（厂商私有字段等）。 */
    val extras: Bundle? = builder.extras
    /** 保留字段：OSS 版不做原生岛，已无效果。 */
    val xiaomiIsland: Boolean = builder.xiaomiIsland

    /** 进度百分比 0..100（系统 ProgressStyle 的进度上限固定为 100）；未设进度时为 null。 */
    val percent: Int?
        get() = if (progressTotal > 0L) {
            ((progressUsed.coerceIn(0L, progressTotal) * 100L) / progressTotal).toInt()
        } else null

    override fun toString(): String =
        "LiveUpdateSpec(id=$id, title=$title, progress=$progressUsed/$progressTotal, timeout=$timeoutMillis)"

    /**
     * DSL 构造器。[LiveUpdate.publish] 传入 `LiveUpdateSpec.Builder.() -> Unit` 接收方，
     * 所以直接写 `title = "…"` / `progress(a, b)` 即可。
     */
    class Builder internal constructor(internal val id: String) {

        /** 用于「在已有条目的基础上改几项」（见 [LiveUpdate.update]）。 */
        internal constructor(spec: LiveUpdateSpec) : this(spec.id) {
            title = spec.title
            text = spec.text
            subText = spec.subText
            shortCriticalText = spec.shortCriticalText
            smallIconRes = spec.smallIconRes
            largeIcon = spec.largeIcon
            used = spec.progressUsed
            total = spec.progressTotal
            segmentLengths = spec.segmentLengths
            progressPoints = spec.progressPoints
            timeoutMillis = spec.timeoutMillis
            ongoing = spec.ongoing
            channelId = spec.channelId
            channelName = spec.channelName
            contentIntent = spec.contentIntent
            showTimestamp = spec.showTimestamp
            alertOnce = spec.alertOnce
            silent = spec.silent
            importance = spec.importance
            extras = spec.extras
            xiaomiIsland = spec.xiaomiIsland
        }

        /** 岛的标题（必填）。例如「正在上课 · 高等数学」。 */
        var title: CharSequence = ""

        /** 副文本，通常放「还剩多少时间 · 地点」。 */
        var text: CharSequence? = null

        /** 更小的补充行（部分 OEM 会忽略）。 */
        var subText: CharSequence? = null

        /**
          * 实时通知上显示的超短文字，**越短越好**：`"26分"`、`"上课中"`、`"剩3站"`。
         * 这是 Android 16 实时通知的关键字段——不设它，系统可能干脆不显示。
         */
        var shortCriticalText: String? = null

        /** 状态栏小图标（必须单色）。为 0 时回落到应用图标。 */
        var smallIconRes: Int = 0

        var largeIcon: Icon? = null

        /** 超时自动撤下（毫秒）。<=0 表示不自动撤下。默认 90 分钟。 */
        var timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS

        /** 是否常驻（不可划掉）。Live Updates 要求 true，关掉等于放弃实时通知。 */
        var ongoing: Boolean = true

        var channelId: String = LiveUpdate.DEFAULT_CHANNEL_ID

        var channelName: CharSequence = LiveUpdate.DEFAULT_CHANNEL_NAME

        /** 点岛 / 点通知要跳去哪。 */
        var contentIntent: PendingIntent? = null

        var showTimestamp: Boolean = false

        var alertOnce: Boolean = true

        /** 静音（默认 true）。个别 OEM 会把静音通知排除在"实时通知"之外，可关掉做对比。 */
        var silent: Boolean = true

        /** 通知通道重要度，默认 [NotificationManager.IMPORTANCE_LOW]；改它必须换 channelId 才生效。 */
        var importance: Int = NotificationManager.IMPORTANCE_LOW

        /**
         * 逃生舱：直接写进通知 extras 的键值对。
         *
         * 用途是「系统/厂商的私有字段」这类标准 API 覆盖不到的东西
         * （例如 vivo 原子通知的 `notification.superx.*`、将来新增的 Live Updates 字段）。
         * 库不解释这些键，只负责原样带上；`android.requestPromotedOngoing` 与
         * `android.shortCriticalText` 由库保证一定写入，不会被这里的 extras 影响。
         */
        var extras: Bundle? = null

        /**
         * 是否在小米 HyperOS 上附加「超级岛 / 焦点通知」payload，默认 true。
         *
         * 但受 `persist.sys.feature.island` 与 `canShowFocus` 两个系统开关约束；
         */
        var xiaomiIsland: Boolean = true

        internal var used: Long = 0L
        internal var total: Long = 0L
        internal var segmentLengths: List<Int> = emptyList()
        internal var progressPoints: List<Int> = emptyList()

        /** 设置确定性进度，例如 `progress(已上课分钟, 总分钟)`。 */
        fun progress(used: Long, total: Long) {
            this.used = used
            this.total = total
        }

        /** 直接给百分比 0..100。 */
        fun progressPercent(percent: Int) {
            this.total = 100L
            this.used = percent.toLong().coerceIn(0L, 100L)
        }

        /**
         * 分段长度（各段之和通常为 100），用来画「一格一节课」那样的刻度。
         * 例如 `segments(11, 11, 11, 11, 11, 11, 11, 11, 12)`。
         * 部分 OEM 不支持分段，会自动退化成普通进度条。
         */
        fun segments(vararg lengths: Int) {
            this.segmentLengths = lengths.filter { it > 0 }
        }

        /** 进度点标记位置（0..100）。 */
        fun progressPoints(vararg positions: Int) {
            this.progressPoints = positions.filter { it in 0..100 }
        }

        internal fun build(): LiveUpdateSpec = LiveUpdateSpec(this)

        companion object {
            const val DEFAULT_TIMEOUT_MILLIS: Long = 90L * 60L * 1000L
        }
    }
}
