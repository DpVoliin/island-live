package dev.dpvoliin.islandtimetable

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃日志器（**本地**）：把最后一次未捕获异常的堆栈写到私有目录，供「设置 → 上次崩溃」查看/复制。
 *
 * 为什么需要它：用户在真机上遇到闪退时拿不到 logcat，只能口述"闪一下就没了"，
 * 排障变成猜谜。装上这个之后，复现一次 → 复制文本 → 直接定位。
 * 只写本机私有目录，不联网、不上传（与项目的隐私口径一致）。
 */
object CrashLog {

    private const val FILE_NAME = "last_crash.txt"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val head = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val device = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
                File(app.filesDir, FILE_NAME).writeText(
                    "$head\n$device\n线程: ${thread.name}\n\n${Log.getStackTraceString(error)}"
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun read(context: Context): String =
        runCatching { File(context.filesDir, FILE_NAME).readText() }.getOrDefault("")

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE_NAME).delete() }
    }
}
