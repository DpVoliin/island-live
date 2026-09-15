package dev.dpvoliin.islandtimetable

import android.app.Application

/** 启动即套用用户选定的主题（默认「跟随系统」）。 */
class IslandApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)          // 闪退时把堆栈写到本机私有目录，可在「设置 → 上次崩溃」复制
        ThemeMode.applySaved(this)
        // ★ 课表必须在 Application 里加载：常驻通知 / 课前提醒的前台服务与闹钟是**独立入口**，
        //   系统可能在 MainActivity 从未跑过的情况下把进程拉起来（重启后、进程被杀后）。
        //   那时若没加载课表，就会拿**内置演示课表**算课 —— 通知里出现"课表里根本没有的课"。
        DemoTimetable.apply(this)
    }
}
