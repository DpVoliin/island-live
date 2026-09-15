package dev.dpvoliin.islandtimetable

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/**
 * 主题模式：跟随系统（默认）/ 浅色 / 深色。
 *
 * 只用系统标准能力（AppCompatDelegate + DayNight），不引入任何第三方依赖。
 * 选择存在本机 SharedPreferences 里，启动时由 [IslandApp] 套用。
 */
object ThemeMode {

    const val SYSTEM = "system"
    const val LIGHT = "light"
    const val DARK = "dark"

    private const val PREF = "theme"
    private const val KEY = "mode"

    /** 当前模式（非法值一律回落到「跟随系统」）。 */
    fun get(context: Context): String {
        val value = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, SYSTEM)
        return if (value == LIGHT || value == DARK) value else SYSTEM
    }

    /** 记住并立即套用。系统会自行重建界面，无需手动刷新页面。 */
    fun set(context: Context, mode: String) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, mode).apply()
        apply(mode)
    }

    fun apply(mode: String) {
        AppCompatDelegate.setDefaultNightMode(
            when (mode) {
                LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    fun applySaved(context: Context) = apply(get(context))

    /** 点一下按钮要切到的下一个模式：跟随系统 → 浅色 → 深色 → 跟随系统。 */
    fun next(mode: String): String = when (mode) {
        SYSTEM -> LIGHT
        LIGHT -> DARK
        else -> SYSTEM
    }

    /** 按钮上的文案。 */
    fun label(context: Context, mode: String = get(context)): String = when (mode) {
        LIGHT -> "浅色"
        DARK -> "深色"
        else -> "跟随系统"
    }
}
