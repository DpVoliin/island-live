package dev.dpvoliin.islandtimetable

import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageButton
import com.google.android.material.button.MaterialButton

/**
 * 可点元素的「按下反馈」：按下轻微缩小、松手弹回。
 *
 * 为什么要自己写：Material 按钮自带水波纹，但侧栏这批按钮用的是自定义 style（水波纹被压得很淡），
 * 而且"整体缩小"这种反馈比水波纹更直观。触摸事件**返回 false**，所以不影响原有点击逻辑。
 */
fun View.enablePressFeedback(scale: Float = 0.96f) {
    setOnTouchListener { v, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN ->
                v.animate().scaleX(scale).scaleY(scale).setDuration(90L).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                v.animate()
                    .scaleX(1f).scaleY(1f)
                    .setDuration(150L)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
        }
        false
    }
}

/**
 * 把按压反馈挂到整棵视图树里的按钮上（侧栏与主屏通用）。
 *
 * 只处理按钮类（MaterialButton / ImageButton / Button），开关（MaterialSwitch）不缩放 ——
 * 开关缩放会让人觉得"整块在抖"，手感反而差。
 */
fun ViewGroup.enablePressFeedbackDeep() {
    for (i in 0 until childCount) {
        val child = getChildAt(i)
        when {
            child is MaterialButton || child is ImageButton || child is android.widget.Button ->
                child.enablePressFeedback()
            child is ViewGroup -> child.enablePressFeedbackDeep()
        }
    }
}
