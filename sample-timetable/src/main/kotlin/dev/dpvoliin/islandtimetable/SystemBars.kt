package dev.dpvoliin.islandtimetable

import android.app.Activity
import android.content.res.Configuration
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 系统栏图标的明暗（浅色主题配深色图标）。
 *
 * 为什么不写在 themes.xml 里：`android:windowLightNavigationBar` 是 API 27 才有的属性，
 * 而本应用 minSdk 26 —— 写进 XML 会在 26 的机器上被 Lint 判为不兼容（实测 lint error）。
 * 用 compat 助手统一处理：状态栏 API 23+、导航栏 API 27+，低版本自动忽略。
 */
object SystemBars {

    fun apply(activity: Activity) {
        val night = (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        runCatching {
            val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
            controller.isAppearanceLightStatusBars = !night
            controller.isAppearanceLightNavigationBars = !night
        }
    }
}
