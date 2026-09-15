package dev.dpvoliin.liveupdates

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * 能力探测报告：给引导页 / 设置页 / bug 上报用。
 *
 * @param canPromote 系统是否允许把通知提升为「实时通知」（= 能不能显示实时通知）
 * @param liveUpdateChannelBlocked 本库的默认通道是否被用户手动关掉了
 * @param oemHint 按厂商给的中文指引；未知厂商为 null
 */
data class CapabilityReport(
    val sdkInt: Int,
    val androidRelease: String,
    val manufacturer: String,
    val model: String,
    val canPromote: Boolean,
    val notificationsEnabled: Boolean,
    val liveUpdateChannelBlocked: Boolean,
    val oemHint: String?,
    val liveUpdateApiVersion: String
) {

    val isAndroid16OrAbove: Boolean get() = sdkInt >= LIVE_UPDATE_MIN_SDK

    /** 一切就绪：能显示实时通知 + 通知开着 + 通道没被关。 */
    val isFullyReady: Boolean get() = canPromote && notificationsEnabled && !liveUpdateChannelBlocked

    /** 给用户看的一句话结论。 */
    val headline: String
        get() = when {
            isFullyReady -> "✅ 这台机器可以显示实时通知（标准 Live Updates）"
            !isAndroid16OrAbove -> "❌ 系统太旧：实时通知需要 Android 16 及以上（当前 $androidRelease / API $sdkInt）"
            !canPromote -> "⚠️ Android 16 已就位，但系统没允许「本应用」提升为实时通知 —— 这是系统/OEM 侧的开关（通知照发，App 会用悬浮岛兜底）"
            !notificationsEnabled -> "⚠️ 具备实时通知条件，但通知权限没开"
            liveUpdateChannelBlocked -> "⚠️ 「$DEFAULT_CHANNEL_NAME」通知通道被关闭了"
            else -> "⚠️ 系统未开放实时通知（是否被厂商策略限制？）"
        }

    val details: String
        get() = buildString {
            append("机型：$manufacturer $model\n")
            append("系统：Android $androidRelease（API $sdkInt）\n")
            append("实时通知 API：$liveUpdateApiVersion\n")
            append("提升为实时通知：").append(
                if (canPromote) "支持"
                else "不支持（系统/OEM 未放行本应用 → 通知照发，但不会升级为实时通知）"
            ).append('\n')
            append("通知总开关：").append(if (notificationsEnabled) "已开" else "未开").append('\n')
            append("本库通道被关：").append(if (liveUpdateChannelBlocked) "是" else "否")

        }

    companion object {
        const val LIVE_UPDATE_MIN_SDK: Int = 36
        private const val DEFAULT_CHANNEL_NAME = "进行中活动"
    }
}

/** 能力探测实现。纯读系统状态，不发任何网络请求。 */
internal object CapabilityProbe {

    fun report(context: Context): CapabilityReport {
        val sdk = Build.VERSION.SDK_INT
        val managerCompat = NotificationManagerCompat.from(context)

        val notificationsEnabled = if (sdk >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED && managerCompat.areNotificationsEnabled()
        } else {
            managerCompat.areNotificationsEnabled()
        }

        // androidx 内部会做版本判断；这里再显式卡一次 SDK，语义更清楚
        val canPromote = sdk >= CapabilityReport.LIVE_UPDATE_MIN_SDK &&
            managerCompat.canPostPromotedNotifications()

        val channelBlocked = run {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = nm.getNotificationChannel(LiveUpdate.DEFAULT_CHANNEL_ID)
            channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE
        }

        return CapabilityReport(
            sdkInt = sdk,
            androidRelease = Build.VERSION.RELEASE ?: "?",
            manufacturer = Build.MANUFACTURER ?: "?",
            model = Build.MODEL ?: "?",
            canPromote = canPromote,
            notificationsEnabled = notificationsEnabled,
            liveUpdateChannelBlocked = channelBlocked,
            oemHint = oemHint(Build.MANUFACTURER ?: ""),
            liveUpdateApiVersion = "Android 16+ Live Updates（androidx.core NotificationCompat.ProgressStyle）",
        )
    }

    /** 各厂商「岛」的入口说法不同，给一句人话指引。 */
    private fun oemHint(manufacturer: String): String? = when {
        manufacturer.contains("vivo", ignoreCase = true) ||
            manufacturer.contains("iqoo", ignoreCase = true) ->
            "vivo / iQOO：系统实时通知需要 OriginOS 6（Android 16 底层）及以上。「设置 → 通知与状态栏 → 原子通知」里允许本应用；「设置 → 应用 → 本应用 → 电池」设为“允许后台高耗电”。"

        manufacturer.contains("xiaomi", ignoreCase = true) ||
            manufacturer.contains("redmi", ignoreCase = true) ||
            manufacturer.contains("poco", ignoreCase = true) ->
            "小米 / Redmi：系统实时通知需要 HyperOS 3（Android 16 底层）及以上；并且必须在「设置 → 通知与控制中心 → 应用通知 → 本应用」里打开「在状态栏显示」（官方文档明确要求），再把省电策略设为「无限制」。"

        manufacturer.contains("oppo", ignoreCase = true) ||
            manufacturer.contains("oneplus", ignoreCase = true) ||
            manufacturer.contains("realme", ignoreCase = true) ->
            "OPPO / 一加 / realme：实时活动需要 ColorOS 16（Android 16 底层）及以上。请在「设置 → 通知与状态栏 → 实时活动」中允许本应用。"

        manufacturer.contains("huawei", ignoreCase = true) ||
            manufacturer.contains("honor", ignoreCase = true) ->
            "华为 / 荣耀：系统实时通知需要 HarmonyOS 6 / MagicOS 10（Android 16 底层）及以上。请在「设置 → 通知」中允许本应用使用实况窗。"

        manufacturer.contains("samsung", ignoreCase = true) ->
            "三星：One UI 8（Android 16）起支持实时通知，无需额外开关，但需在「设置 → 通知 → 高级设置」中开启实时通知。"

        else -> null
    }
}
