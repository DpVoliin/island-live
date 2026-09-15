package dev.dpvoliin.liveupdates

import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import org.json.JSONArray
import org.json.JSONObject

/**
 * 把"当前正在展示的通知"完整存到磁盘。
 *
 * **为什么必须存全量**：通知被系统回收 / 进程被杀之后，如果只知道 id 和一个截止时间，
 * 是重建不出那条"带进度条的岛"的 —— 只能眼睁睁看着它消失。所以这里把渲染所需的字段
 * 全部序列化，进程重启后能还原出**一模一样**的一条（含小米私有字段）。
 *
 * 不做的事：不存 PendingIntent（无法序列化），重建时改成"点开 App"的入口；
 * 不存 largeIcon / extras（视觉细节，丢了不影响功能）。
 */
internal object LiveUpdateStore {

    private const val PREFS = "liveupdates_store"
    private const val KEY_ITEMS = "items"

    /** 一条 = spec 的 JSON + 截止时间。 */
    fun save(context: Context, spec: LiveUpdateSpec, deadlineMillis: Long) {
        val all = loadRaw(context)
        all.put(spec.id, toJson(spec, deadlineMillis))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ITEMS, all.toString()).apply()
    }

    fun remove(context: Context, id: String) {
        val all = loadRaw(context)
        if (!all.has(id)) return
        all.remove(id)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ITEMS, all.toString()).apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /** 读出所有未过期的条目（已过期的顺手清掉）。 */
    fun load(context: Context): List<Pair<LiveUpdateSpec, Long>> {
        val all = loadRaw(context)
        val now = System.currentTimeMillis()
        val out = mutableListOf<Pair<LiveUpdateSpec, Long>>()
        val keep = JSONObject()
        for (id in all.keys().asSequence().toList()) {
            val obj = all.optJSONObject(id) ?: continue
            val deadline = obj.optLong("deadline", 0L)
            if (deadline in 1..now) continue                    // 已过期，丢弃
            val spec = fromJson(context, obj) ?: continue
            out += spec to deadline
            keep.put(id, obj)
        }
        if (keep.length() != all.length()) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_ITEMS, keep.toString()).apply()
        }
        return out
    }

    // ------------------------------------------------------------ 序列化

    private fun loadRaw(context: Context): JSONObject {
        val text = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ITEMS, null) ?: return JSONObject()
        return runCatching { JSONObject(text) }.getOrDefault(JSONObject())
    }

    private fun toJson(spec: LiveUpdateSpec, deadline: Long): JSONObject = JSONObject().apply {
        put("id", spec.id)
        put("title", spec.title.toString())
        spec.text?.let { put("text", it.toString()) }
        spec.subText?.let { put("subText", it.toString()) }
        spec.shortCriticalText?.let { put("critical", it) }
        put("icon", spec.smallIconRes)
        put("used", spec.progressUsed)
        put("total", spec.progressTotal)
        put("seg", JSONArray(spec.segmentLengths))
        put("points", JSONArray(spec.progressPoints))
        put("ongoing", spec.ongoing)
        put("channelId", spec.channelId)
        put("channelName", spec.channelName.toString())
        put("importance", spec.importance)
        put("silent", spec.silent)
        put("alertOnce", spec.alertOnce)
        put("showTimestamp", spec.showTimestamp)
        put("deadline", deadline)
    }

    private fun fromJson(context: Context, obj: JSONObject): LiveUpdateSpec? {
        val id = obj.optString("id").takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            LiveUpdateSpec.Builder(id).apply {
                title = obj.optString("title", id)
                if (obj.has("text")) text = obj.optString("text")
                if (obj.has("subText")) subText = obj.optString("subText")
                if (obj.has("critical")) shortCriticalText = obj.optString("critical")
                smallIconRes = obj.optInt("icon", 0)
                ongoing = obj.optBoolean("ongoing", true)
                channelId = obj.optString("channelId", LiveUpdate.DEFAULT_CHANNEL_ID)
                channelName = obj.optString("channelName", LiveUpdate.DEFAULT_CHANNEL_NAME.toString())
                importance = obj.optInt("importance", android.app.NotificationManager.IMPORTANCE_LOW)
                silent = obj.optBoolean("silent", true)
                alertOnce = obj.optBoolean("alertOnce", true)
                showTimestamp = obj.optBoolean("showTimestamp", false)
                // 进度：0/0 表示没有进度条
                val total = obj.optLong("total", 0L)
                if (total > 0L) progress(obj.optLong("used", 0L), total)
                val seg = obj.optJSONArray("seg")
                if (seg != null && seg.length() > 0) {
                    segmentLengths = (0 until seg.length()).map { seg.optInt(it) }
                }
                val pts = obj.optJSONArray("points")
                if (pts != null && pts.length() > 0) {
                    progressPoints = (0 until pts.length()).map { pts.optInt(it) }
                }
                // PendingIntent 不能序列化：重建时改成"点开 App"，至少点了有反应
                launcherIntent(context)?.let { contentIntent = it }
            }.build()
        }.getOrNull()
    }

    private fun launcherIntent(context: Context): PendingIntent? = runCatching {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }.getOrNull()
}
