package dev.dpvoliin.islandtimetable

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 给页面根布局让出系统栏（状态栏 / 底部手势条）的高度。
 *
 * 本项目 targetSdk 36 —— Android 15+ 强制「边到边」，不处理 insets 的话：
 * - 顶部内容会钻到状态栏底下（真机观感就是「顶得太靠上」）
 * - 底部内容会被手势条压住（教务页的 WebView 尤其明显）
 *
 * [extraTopDp] / [extraBottomDp] 是在系统栏之上再留的一点呼吸空位。
 * 只算一次基础 padding，监听器重复回调也不会累加。
 */
fun View.applySystemBarInsets(extraTopDp: Int = 10, extraBottomDp: Int = 10) {
    val baseTop = paddingTop
    val baseBottom = paddingBottom
    val density = resources.displayMetrics.density
    val extraTop = (extraTopDp * density).toInt()
    val extraBottom = (extraBottomDp * density).toInt()
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        view.setPadding(
            view.paddingLeft,
            baseTop + bars.top + extraTop,
            view.paddingRight,
            baseBottom + bars.bottom + extraBottom
        )
        insets
    }
    ViewCompat.requestApplyInsets(this)
}
