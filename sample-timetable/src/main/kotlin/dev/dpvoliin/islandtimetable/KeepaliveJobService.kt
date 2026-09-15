package dev.dpvoliin.islandtimetable

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

/**
 * 保活巡检任务（**JobScheduler**）。
 *
 * 为什么在"15 分钟闹钟看门狗"之外**再加一条**：
 *  - 厂商 ROM 对 `AlarmManager`（尤其精确闹钟）的压制比对 JobScheduler 更狠；
 *    JobScheduler 是 Android 的**标准**后台调度机制，ROM 一般会更守规矩；
 *  - `setPersisted(true)` 让它在**重启后自动恢复注册**（需持有 RECEIVE_BOOT_COMPLETED 权限）。
 *
 * 于是现在有**三条独立路径**互兜底：闹钟看门狗 ✓ / 本 Job ✓ / 划掉任务后的秒复活闹钟 ✓ ——
 * 任何一条活下来，常驻通知就会被补回。
 *
 * 如实边界：用户在系统里点「强行停止」、或厂商白名单未开时，Android 不允许应用自恢复。
 * 关于厂商白名单：实测资料（dontkillmyapp.com）明确写了 vivo / 小米这类后台限制
 * **没有任何 API、也没有官方文档**，只能靠用户在系统设置里手动开（见 docs/keepalive-oem.md）。
 */
class KeepaliveJobService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        val context = applicationContext
        StickyNotification.postDirect(context)      // ① 先把通知补回（任何场景都允许）
        StickyService.start(context)                // ② 再尝试拉回前台服务（后台受限时静默失败）
        runCatching { TimetableWidget.refreshAll(context) }
        runCatching { AutoSilent.scheduleNext(context) }
        jobFinished(params, false)                  // 不重试：下一次周期自己会来
        return false
    }

    /** 被系统打断（如进入省电）时返回 true，让它重新排期。 */
    override fun onStopJob(params: JobParameters?): Boolean = true

    companion object {

        private const val JOB_ID = 4401
        private const val INTERVAL_MS = 15 * 60 * 1000L

        /** 排上周期巡检（幂等：同 id 重复排会覆盖旧的）。 */
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, KeepaliveJobService::class.java))
                .setPeriodic(INTERVAL_MS)
                .setPersisted(true)                        // 重启后自动恢复（需 RECEIVE_BOOT_COMPLETED）
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                .build()
            runCatching { scheduler.schedule(job) }
        }

        fun cancel(context: Context) {
            runCatching { context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID) }
        }
    }
}
