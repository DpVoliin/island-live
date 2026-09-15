package dev.dpvoliin.islandtimetable

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build

/**
 * 「提升为岛」（promoted notification）—— 只用 **Android 官方公开 API**，自写实现。
 *
 * 背景（为什么以前没做、现在为什么可以试）：
 *  · v0.14.0 时放弃系统原生岛，理由是"厂商限制无 API 无文档"。
 *  · 实测本机 android-36（Android 16 / API 36）平台包里，promoted 已经是**正规公开 API**：
 *      - `NotificationManager.canPostPromotedNotifications()`
 *      - `Notification.FLAG_PROMOTED_ONGOING`
 *      - `Notification.hasPromotableCharacteristics()`
 *    （**没有** `requestPromotedOngoing`，那是扩展/反射层的名字，不采纳。）
 *
 * 所以这里走"**能升就升、升不了什么都不变**"的最小路径：
 *  1. 常驻通知照旧发（现在的行为不变）；
 *  2. 条件满足时给这条通知打上 [Notification.FLAG_PROMOTED_ONGOING]；
 *  3. 系统认为它"具备可提升特征"（有计时/进度/标题）才真的升为岛；否则就是普通常驻通知。
 *
 * **可回退**：任何异常/不支持都吞掉，绝不因为"升不上去"影响常驻通知本身。
 */
object PromotedNotify {

    /** 平台是否提供了 promoted 这套 API（API 36 起）。 */
    val apiAvailable: Boolean = Build.VERSION.SDK_INT >= 36

    /**
     * 本机此刻**允许**发 promoted 通知吗？
     *
     * `canPostPromotedNotifications()` 会连带把"清单里有没有声明
     * `android.permission.POST_PROMOTED_NOTIFICATIONS`、系统实现认不认"一起判掉，
     * 所以它就是我们要的那个判据，不用自己拼条件。
     */
    fun allowed(context: Context): Boolean {
        if (!apiAvailable) return false
        return runCatching {
            context.getSystemService(NotificationManager::class.java)
                ?.canPostPromotedNotifications() == true
        }.getOrDefault(false)
    }

    /** 这条通知本身具不具备可提升特征（系统最终是否升为岛还取决于厂商实现）。 */
    fun hasPromotableCharacteristics(notification: Notification): Boolean {
        if (!apiAvailable) return false
        return runCatching { notification.hasPromotableCharacteristics() }.getOrDefault(false)
    }

    /**
     * 给通知打上 promoted 标记（调用方已经建好通知、还没 notify）。
     *
     * `flags` 是 Notification 的公开字段，直接按位或，不依赖任何隐藏 API。
     */
    fun markIfAllowed(context: Context, notification: Notification): Boolean {
        if (!allowed(context)) return false
        return runCatching {
            notification.flags = notification.flags or Notification.FLAG_PROMOTED_ONGOING
            true
        }.getOrDefault(false)
    }

    /** 给用户看的一句话状态（设置/提示里用），避免"开了没反应却不知道为啥"。 */
    fun describe(context: Context): String = when {
        !apiAvailable -> "本机系统版本低于 Android 16，保持普通通知"
        allowed(context) -> "本机支持提升为岛（系统将按机型决定是否真的上岛）"
        else -> "本机未开放提升为岛（保持普通通知）"
    }
}
