package dev.dpvoliin.islandtimetable

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.sin

/**
 * 「悬浮岛」——贴在**屏幕左/右边缘**的环形倒计时小卡。
 *
 * ## 为什么只在侧边
 * 实测（vivo，Android 16）：**应用悬浮窗的层级低于 SystemUI 的顶部信息栏**，
 * 顶部那一行画不进去（会被状态栏压住、被系统实时通知挡住）。侧边完全没有这个问题，也一点内容都不挡。
 *
 * ## 交互
 * - **点**：收起时先展开；展开时回到 App
 * - **长按**：隐藏本次（下次开始上课自动回来）
 * - **拖动**：随意挪，松手**自动吸附到最近的左/右边缘**；吸附侧与竖直位置会被记住
 * - **自动收起**：每次交互后 [AUTO_COLLAPSE_MS] 未再交互 → 自动缩成贴边小按钮
 *
 * ## 动画上的两条铁律（都是踩过的坑）
 * 1. **缩放永远不超过 1 倍**：窗口是 `WRAP_CONTENT`，尺寸正好等于卡片，任何 >1 的缩放或非 0 的位移
 *    都会让绘制被窗口裁掉 —— 表现就是「外围白框断开/散开」。
 * 2. **要移动就移动窗口本身**（`ValueAnimator` + `updateViewLayout`），不要用 `translationX` 补偿。
 *
 * 需要「显示在其他应用上层」权限。
 */
class IslandOverlay(private val context: Context) {

    /** 吸附到哪一侧。 */
    enum class Side { LEFT, RIGHT }

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var pill: PillView? = null

    /**
     * 当前**已附着到 WindowManager** 的那个视图。
     *
     * 为什么单独记一份：`hide()` 的移除挂在动画结束回调上（要保留收起动画），
     * 如果用户在收起动画没播完时又点"打开"，`show()` 会 addView 出第二个窗口，
     * 于是屏幕上出现**两个悬浮岛**（用户实测踩到）。有了这份引用，
     * `show()` 就能先把旧的**立刻摘掉**，`hide()` 的迟到回调也不会误删新窗口。
     */
    private var attachedView: PillView? = null
    private var params: WindowManager.LayoutParams? = null

    private var side: Side = Side.RIGHT
    private var yRatio: Float = DEFAULT_Y_RATIO

    private var moveStartX = 0
    private var moveStartY = 0

    /** 长按隐藏：本次课程内不再自动浮出（下一次开始上课会重新出现）。 */
    private var hiddenByUser = false

    /** 最近一次交互的时刻 —— 自动收起由看门狗按它判断（比一次性定时器可靠）。 */
    private var lastInteractionAt = 0L

    /** 窗口横向滑动动画（入场 / 吸附）。 */
    private var slideAnimator: ValueAnimator? = null

    /**
     * 系统侧滑（返回）手势区宽度，左右各一份。
     *
     * 用户实测：卡片贴着屏幕边缘时，从右侧想拖动卡片的动作**经常被判成返回手势**。
     * 所以这里直接问系统要手势区宽度（`WindowInsets.systemGestureInsets`），把卡片放到手势区之外，
     * 而不是猜一个固定边距。
     */
    private var gestureInsetLeft = 0
    private var gestureInsetRight = 0

    /** 看门狗：每 [WATCHDOG_INTERVAL_MS] 检查一次「该不该自动收起」。 */
    private val watchdog = object : Runnable {
        override fun run() {
            val view = pill
            if (view == null) return
            if (!view.isCollapsed() &&
                System.currentTimeMillis() - lastInteractionAt >= AUTO_COLLAPSE_MS
            ) {
                collapseToTab()
            }
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    var isShowing: Boolean = false
        private set

    /** 位置变化回调：让调用方把「吸附侧 + 竖直比例」存进偏好，下次上课沿用。 */
    var onPositionChanged: ((Side, Float) -> Unit)? = null

    fun canShow(): Boolean = Settings.canDrawOverlays(context)

    fun isHiddenByUser(): Boolean = hiddenByUser

    /**
     * 显示（已显示则只更新内容）。
     *
     * @param side   吸附侧（调用方通常传上次记住的那一侧）
     * @param yRatio 竖直位置比例 0..1（0.42 ≈ 屏幕偏上一点，拇指好够）
     */
    fun show(
        title: String,
        remaining: String,
        progress: Float,
        side: Side = this.side,
        yRatio: Float = this.yRatio
    ) {
        if (!canShow()) return
        this.side = side
        this.yRatio = yRatio.coerceIn(MIN_Y_RATIO, MAX_Y_RATIO)
        hiddenByUser = false

        // 先把上一个（可能还在播收起动画的）窗口立刻摘掉 —— 否则会叠出两个悬浮岛
        attachedView?.let { old ->
            runCatching { old.animate().cancel() }
            runCatching { windowManager.removeView(old) }
        }
        attachedView = null

        // 硬判据：**只有真挂在 WindowManager 上（parent != null）才认它"在显示"**。
        // 只信 pill 这个变量踩过坑：pill 可能指向一个已经被摘掉的窗口，
        // 于是 show() 走进"复用"分支 → 窗口没加回来，而状态说在显示；下一次再点又加一个 ⇒ 屏幕上两个悬浮岛。
        val existing = pill
        if (existing != null && existing.parent != null) {
            existing.bind(remaining, progress)
            markInteraction()
            return
        }

        val view = PillView(context).apply {
            bind(remaining, progress)
            onInteract = { markInteraction() }
            onDragStart = {
                markInteraction()
                moveStartX = params?.x ?: 0
                moveStartY = params?.y ?: 0
                // 手感反馈：略微「捏小」+ 淡化（缩放不超过 1，否则会被窗口裁边）
                resetTransform(this)
                animate().scaleX(0.96f).scaleY(0.96f).alpha(0.94f)
                    .setDuration(120L).setInterpolator(DecelerateInterpolator()).start()
            }
            onDrag = { dx, dy ->
                markInteraction() // 拖动期间别让看门狗把卡片收走
                moveTo(moveStartX + dx, moveStartY + dy)
            }
            onDragEnd = {
                markInteraction()
                resetTransform(this)
                animate().scaleX(1f).scaleY(1f).alpha(1f)
                    .setDuration(150L).setInterpolator(DecelerateInterpolator()).start()
                snapToNearestSide()
            }
            onLongPress = {
                hide(byUser = true)
                toast("悬浮岛已隐藏 · 下次开始上课会回来")
            }
            onGestureInsets = { left, right -> applyGestureInsets(left, right) }
            setOnClickListener {
                if (isCollapsed()) expand() else pulseThen { openApp() }
            }
        }

        val metrics = context.resources.displayMetrics
        val targetEdgeX = edgeX(side, metrics.widthPixels)
        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // 入场从屏幕外滑进来：初始 x 放到屏幕外，随后滑动到贴边位置
            x = if (side == Side.LEFT) {
                -(currentWidthPx() + dp(10f)).toInt()
            } else {
                metrics.widthPixels + dp(10f).toInt()
            }
            y = (metrics.heightPixels * this@IslandOverlay.yRatio).toInt()
        }

        try {
            windowManager.addView(view, layoutParams)
        } catch (t: Throwable) {
            return
        }
        pill = view
        attachedView = view
        params = layoutParams
        isShowing = true

        view.alpha = 0f
        view.scaleX = 0.88f
        view.scaleY = 0.88f
        view.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(240L)
            .setInterpolator(DecelerateInterpolator())
            .start()
        slideTo(targetEdgeX, SNAP_DURATION_MS)

        // 有些 ROM 的 insets 要等窗口挂上去才拿得到，挂载后再读一次
        view.post { readGestureInsets(view) }

        markInteraction()
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS)
    }

    /** 只更新内容（每秒调一次也没问题）。 */
    fun update(title: String, remaining: String, progress: Float) {
        pill?.bind(remaining, progress)
    }

    /** 隐藏。byUser = 长按隐藏时置位，避免同一次课程里又被自动浮出来。 */
    fun hide(byUser: Boolean = false) {
        hiddenByUser = byUser
        handler.removeCallbacks(watchdog)
        slideAnimator?.cancel()
        slideAnimator = null
        // pill 可能已被清而 attachedView 还在（上一个 show 摘窗时留下的），两个都要收
        val view = pill ?: attachedView ?: return
        isShowing = false
        pill = null
        resetTransform(view)
        view.animate()
            .alpha(0f)
            .scaleX(0.85f)
            .scaleY(0.85f)
            .setDuration(200L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                // 只有"它仍是我认的那个窗口"才移除：避免迟到回调把新窗口误删/或留下孤儿
                if (attachedView === view) {
                    runCatching { windowManager.removeView(view) }
                    attachedView = null
                }
            }
            .start()
    }

    // ---------------------------------------------------------------- 自动收起（看门狗）

    /** 记录一次交互：看门狗会从这一刻起重新计时。 */
    private fun markInteraction() {
        lastInteractionAt = System.currentTimeMillis()
    }

    /** 收起：先「缩」一下，再换成小按钮 —— 不是硬切，所以不突兀。 */
    private fun collapseToTab() {
        val view = pill ?: return
        resetTransform(view)
        view.animate()
            .scaleX(0.5f).scaleY(0.5f).alpha(0.6f)
            .setDuration(170L)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                view.setCollapsed(true)
                applyEdgeX()
                view.scaleX = 0.7f
                view.scaleY = 0.7f
                view.alpha = 0.5f
                view.animate()
                    .scaleX(1f).scaleY(1f).alpha(1f)
                    .setDuration(230L)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            .start()
    }

    /** 展开：小按钮先缩一下再长成小卡（同样不越过 1 倍）。 */
    private fun expand() {
        val view = pill ?: return
        markInteraction()
        resetTransform(view)
        view.animate()
            .scaleX(0.5f).scaleY(0.5f).alpha(0.6f)
            .setDuration(130L)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                view.setCollapsed(false)
                applyEdgeX()
                view.scaleX = 0.62f
                view.scaleY = 0.62f
                view.alpha = 0.45f
                view.animate()
                    .scaleX(1f).scaleY(1f).alpha(1f)
                    .setDuration(250L)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            .start()
    }

    /** 点按反馈：先压一下再弹回，然后执行动作。 */
    private fun pulseThen(action: () -> Unit) {
        val view = pill
        if (view == null) {
            action()
            return
        }
        markInteraction()
        resetTransform(view)
        view.animate()
            .scaleX(0.88f).scaleY(0.88f)
            .setDuration(90L)
            .withEndAction {
                action()
                view.animate()
                    .scaleX(1f).scaleY(1f)
                    .setDuration(170L)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            .start()
    }

    /**
     * 把视图变换恢复到静止态。
     *
     * ⚠️ 动画被 cancel 时，scale/alpha 会**停在中间值**；而窗口尺寸正好等于卡片，
     * 非静止的变换会让绘制被窗口裁掉（「白框散开」的元凶）。所以每次起新动画前先复位。
     */
    private fun resetTransform(view: View) {
        view.animate().cancel()
        view.scaleX = 1f
        view.scaleY = 1f
        view.translationX = 0f
        view.translationY = 0f
        view.alpha = 1f
    }

    // ---------------------------------------------------------------- 拖动 / 吸附

    private fun moveTo(rawX: Int, rawY: Int) {
        val view = pill ?: return
        val layoutParams = params ?: return
        val metrics = context.resources.displayMetrics
        layoutParams.x = rawX.coerceIn(
            edgeInsetFor(Side.LEFT),
            metrics.widthPixels - currentWidthPx() - edgeInsetFor(Side.RIGHT)
        )
        layoutParams.y = rawY.coerceIn(
            statusBarHeight() + dp(4f).toInt(),
            metrics.heightPixels - currentHeightPx() - dp(8f).toInt()
        )
        runCatching { windowManager.updateViewLayout(view, layoutParams) }
    }

    private fun snapToNearestSide() {
        val layoutParams = params ?: return
        val metrics = context.resources.displayMetrics
        val centerX = layoutParams.x + currentWidthPx() / 2f
        side = if (centerX < metrics.widthPixels / 2f) Side.LEFT else Side.RIGHT
        yRatio = (layoutParams.y.toFloat() / metrics.heightPixels.toFloat())
            .coerceIn(MIN_Y_RATIO, MAX_Y_RATIO)
        // 让**窗口本身**滑过去（不是移动视图内容）—— 这样不会被窗口裁边
        slideTo(edgeX(side, metrics.widthPixels), SNAP_DURATION_MS)
        onPositionChanged?.invoke(side, yRatio)
        toast(if (side == Side.LEFT) "已吸附到左侧" else "已吸附到右侧")
    }

    /** 逐帧改窗口 x 实现滑动（`updateViewLayout`），比 translationX 补偿干净。 */
    private fun slideTo(targetX: Int, durationMs: Long) {
        val view = pill ?: return
        val layoutParams = params ?: return
        slideAnimator?.cancel()
        val animator = ValueAnimator.ofInt(layoutParams.x, targetX)
        animator.duration = durationMs
        animator.interpolator = DecelerateInterpolator()
        animator.addUpdateListener { anim ->
            layoutParams.x = anim.animatedValue as Int
            runCatching { windowManager.updateViewLayout(view, layoutParams) }
        }
        animator.start()
        slideAnimator = animator
    }

    // ---------------------------------------------------------------- 手势区避让

    private fun readGestureInsets(view: View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val insets = view.rootWindowInsets ?: return
        applyGestureInsets(insets.systemGestureInsets.left, insets.systemGestureInsets.right)
    }

    private fun applyGestureInsets(left: Int, right: Int) {
        if (left == gestureInsetLeft && right == gestureInsetRight) return
        gestureInsetLeft = left
        gestureInsetRight = right
        // 学到手势区后立刻重新贴边，避免卡片落在手势区里（否则一拖就触发返回）
        if (pill != null) {
            slideTo(edgeX(side, context.resources.displayMetrics.widthPixels), 140L)
        }
    }

    /** 某一侧的「安全起点」：固定间隙与系统手势区取较大者，再多留 4dp 余量。 */
    private fun edgeInsetFor(side: Side): Int {
        val gesture = if (side == Side.LEFT) gestureInsetLeft else gestureInsetRight
        return maxOf(dp(EDGE_GAP_DP).toInt(), gesture + dp(4f).toInt())
    }

    /** 当前宽度 / 高度（收起时是小按钮的尺寸）。 */
    private fun currentWidthPx(): Int =
        dp(if (pill?.isCollapsed() == true) TAB_W_DP else EXPANDED_W_DP).toInt()

    private fun currentHeightPx(): Int =
        dp(if (pill?.isCollapsed() == true) TAB_H_DP else EXPANDED_H_DP).toInt()

    /**
     * 贴边时的 x —— 离屏幕边缘留 [EDGE_GAP_DP] 的间隙（贴太紧不好点）。
     * 按**当前尺寸**算，所以收起成小按钮后也会重新贴好边。
     */
    private fun edgeX(side: Side, screenWidth: Int): Int {
        val gap = edgeInsetFor(side)
        return when (side) {
            Side.LEFT -> gap
            Side.RIGHT -> screenWidth - currentWidthPx() - gap
        }
    }

    /** 尺寸变化（收起/展开）后重新贴边（带滑动过渡）。 */
    private fun applyEdgeX() {
        if (params == null) return
        slideTo(edgeX(side, context.resources.displayMetrics.widthPixels), 160L)
    }

    private fun openApp() {
        runCatching {
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            intent?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    private fun toast(text: String) {
        runCatching { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
    }

    private fun statusBarHeight(): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else dp(28f).toInt()
    }

    private fun dp(value: Float): Float = value * context.resources.displayMetrics.density

    // ---------------------------------------------------------------- 小卡本体

    private inner class PillView(context: Context) : View(context) {

        var onInteract: (() -> Unit)? = null
        var onDragStart: (() -> Unit)? = null
        var onDrag: ((Int, Int) -> Unit)? = null
        var onDragEnd: (() -> Unit)? = null
        var onLongPress: (() -> Unit)? = null
        var onGestureInsets: ((Int, Int) -> Unit)? = null

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF00E1013.toInt() }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1f)
        }
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = dp(2.5f)
        }
        private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = sp(11f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4ADE80.toInt() }
        private val ringRect = RectF()

        private var remaining = ""
        private var progress = 0f
        private var collapsed = false

        // 触摸状态
        private var downRawX = 0f
        private var downRawY = 0f
        private var dragged = false
        private var longPressFired = false
        private var longPressRunnable: Runnable? = null

        fun bind(remaining: String, progress: Float) {
            if (this.remaining == remaining && abs(this.progress - progress) < 0.001f) return
            this.remaining = remaining
            this.progress = progress.coerceIn(0f, 1f)
            invalidate()
        }

        /** 兼容旧调用（标题在侧边形态里不显示）。 */
        fun bind(title: String, remaining: String, progress: Float) = bind(remaining, progress)

        fun isCollapsed(): Boolean = collapsed

        fun setCollapsed(value: Boolean) {
            if (collapsed == value) return
            collapsed = value
            requestLayout() // 窗口尺寸跟着变（收起＝变小，展开＝变回原样）
            invalidate()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            // 收起时窗口一起缩到「小按钮」大小 —— 那块看不见的区域就不会再吃掉点击
            if (collapsed) {
                setMeasuredDimension(dp(TAB_W_DP).toInt(), dp(TAB_H_DP).toInt())
            } else {
                setMeasuredDimension(dp(EXPANDED_W_DP).toInt(), dp(EXPANDED_H_DP).toInt())
            }
        }

        /** 读系统手势区宽度（左右各一份），交给外层计算「安全贴边位置」。 */
        override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val gesture = insets.systemGestureInsets
                if (gesture.left > 0 || gesture.right > 0) {
                    onGestureInsets?.invoke(gesture.left, gesture.right)
                }
            }
            return super.onApplyWindowInsets(insets)
        }

        override fun onDraw(canvas: Canvas) {
            if (collapsed) {
                drawCollapsedTab(canvas)
            } else {
                drawExpanded(canvas)
            }
            postInvalidateDelayed(40L) // 呼吸动画，约 25fps
        }

        /** 展开态：环形倒计时，圈里是剩余分钟。 */
        private fun drawExpanded(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val radius = w / 2f
            val box = RectF(0f, 0f, w, h)

            val breath = abs(sin(System.currentTimeMillis() / 1400.0)).toFloat()
            borderPaint.color = Color.argb((38 + 40 * breath).toInt(), 255, 255, 255)
            canvas.drawRoundRect(box, radius, radius, bgPaint)
            canvas.drawRoundRect(box, radius, radius, borderPaint)

            val ringSize = dp(24f)
            val cx = w / 2f
            val cy = h / 2f
            ringRect.set(
                cx - ringSize / 2f, cy - ringSize / 2f,
                cx + ringSize / 2f, cy + ringSize / 2f
            )
            ringPaint.color = 0x33FFFFFF
            canvas.drawArc(ringRect, 0f, 360f, false, ringPaint)
            ringPaint.color = 0xFF4ADE80.toInt()
            canvas.drawArc(ringRect, -90f, 360f * progress, false, ringPaint)

            drawRemaining(canvas, cx, cy)
        }

        /** 收起态：贴边小按钮，里面一条竖线表示进度。 */
        private fun drawCollapsedTab(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val box = RectF(0f, 0f, w, h)
            val radius = w / 2f

            val breath = abs(sin(System.currentTimeMillis() / 1600.0)).toFloat()
            borderPaint.color = Color.argb((26 + 44 * breath).toInt(), 255, 255, 255)
            canvas.drawRoundRect(box, radius, radius, bgPaint)
            canvas.drawRoundRect(box, radius, radius, borderPaint)

            val innerTop = dp(6f)
            val innerBottom = h - dp(6f)
            val lineTop = innerBottom - (innerBottom - innerTop) * progress
            val lineHalf = dp(2f)
            canvas.drawRoundRect(
                RectF(w / 2f - lineHalf, lineTop, w / 2f + lineHalf, innerBottom),
                lineHalf, lineHalf, accentPaint
            )
        }

        /** 圈里的数字：剩余分钟；没有数字时画对勾（即将下课）。 */
        private fun drawRemaining(canvas: Canvas, cx: Float, cy: Float) {
            val digits = remaining.filter { it.isDigit() }
            val label = if (digits.isNotEmpty() && digits.length <= 3) digits else "✓"
            canvas.drawText(
                label,
                cx - numberPaint.measureText(label) / 2f,
                cy - (numberPaint.descent() + numberPaint.ascent()) / 2f,
                numberPaint
            )
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    dragged = false
                    longPressFired = false
                    onInteract?.invoke()
                    onDragStart?.invoke()
                    longPressRunnable?.let { handler.removeCallbacks(it) }
                    val runnable = Runnable {
                        if (!dragged) {
                            longPressFired = true
                            onLongPress?.invoke()
                        }
                    }
                    longPressRunnable = runnable
                    handler.postDelayed(runnable, LONG_PRESS_MS)
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragged && (abs(dx) > dp(4f) || abs(dy) > dp(4f))) {
                        dragged = true
                        longPressRunnable?.let { handler.removeCallbacks(it) }
                    }
                    if (dragged) onDrag?.invoke(dx.toInt(), dy.toInt())
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    longPressRunnable?.let { handler.removeCallbacks(it) }
                    if (longPressFired) {
                        longPressFired = false
                        return true
                    }
                    if (dragged) onDragEnd?.invoke() else performClick()
                    return true
                }
            }
            return true
        }

        private fun dp(value: Float): Float = value * resources.displayMetrics.density
        private fun sp(value: Float): Float = value * resources.displayMetrics.scaledDensity
    }

    companion object {
        /** 展开态：宽 × 高（dp）。 */
        private const val EXPANDED_W_DP = 34f
        private const val EXPANDED_H_DP = 66f

        /** 收起态（贴边小按钮）：宽 × 高（dp）。 */
        private const val TAB_W_DP = 16f
        private const val TAB_H_DP = 34f

        /**
         * 离屏幕边缘的基础间隙（dp）。
         *
         * 从 8dp 提到 20dp：卡片贴太近时，从边缘方向拖动很容易被系统判成**侧滑返回手势**。
         * 运行时还会与系统手势区宽度取较大者（见 `edgeInsetFor`）。
         */
        private const val EDGE_GAP_DP = 20f

        private const val DEFAULT_Y_RATIO = 0.42f
        private const val MIN_Y_RATIO = 0.08f
        private const val MAX_Y_RATIO = 0.85f
        private const val LONG_PRESS_MS = 550L

        /** 交互后静止这么久 → 自动收起成贴边小按钮。 */
        private const val AUTO_COLLAPSE_MS = 3_000L

        /** 看门狗轮询间隔。 */
        private const val WATCHDOG_INTERVAL_MS = 400L

        /** 窗口横向滑动时长（入场 / 吸附）。 */
        private const val SNAP_DURATION_MS = 240L
    }
}
