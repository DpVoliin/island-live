package dev.dpvoliin.islandtimetable

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat

/**
 * 自绘周课表网格（8 节次 × 5 天）。
 *
 * 为什么自绘而不是堆 ViewGroup：一张 5×8 的网格用 40 个 View 拼会很难对齐、很难做「当前时间线」
 * 和块内进度，也没法做得像日历。Canvas 一次画完反而更简单、更好看、更省内存。
 *
 * 支持两种「现在」：
 * - 空闲时：按真实时间在今天的列上画一条横线（像系统日历那样）
 * - 模拟上课时：把这条线压进正在推送的那门课里，随进度往下走
 */
class TimetableGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var periods: List<Period> = DemoTimetable.PERIODS
        set(value) {
            field = value
            invalidate()
        }

    var dayLabels: List<String> = DemoTimetable.DAY_LABELS
        set(value) {
            field = value
            invalidate()
        }

    var courses: List<Course> = DemoTimetable.COURSES
        set(value) {
            field = value
            invalidate()
        }

    /** 正在推送的课程 id（会加呼吸描边 + 底部进度条）。 */
    var highlightCourseId: String? = null
        set(value) {
            field = value
            invalidate()
        }

    /** 高亮课程在本节课内的进度 0..1。 */
    var highlightProgress: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    private var nowDayIndex: Int = -1
    private var nowFraction: Float = -1f

    fun setNow(dayIndex: Int, fraction: Float) {
        nowDayIndex = dayIndex
        nowFraction = fraction
        invalidate()
    }

    // ------------------------------------------------------------------ 画笔

    private val density = resources.displayMetrics.density

    private fun dp(value: Float): Float = value * density

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = ContextCompat.getColor(context, R.color.grid_line)
    }

    private val todayColumnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.today_column)
    }

    private val headerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(12f)
        color = ContextCompat.getColor(context, R.color.text_secondary)
        textAlign = Paint.Align.CENTER
    }

    private val todayHeaderPaint = TextPaint(headerPaint).apply {
        color = ContextCompat.getColor(context, R.color.accent)
        isFakeBoldText = true
    }

    private val periodPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(10.5f)
        color = ContextCompat.getColor(context, R.color.text_tertiary)
        textAlign = Paint.Align.CENTER
    }

    private val courseNamePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        // 11.5sp：原来 10.5sp 偏小，课名读起来吃力（用户反馈"缩太小了"）
        textSize = sp(11.5f)
        color = ContextCompat.getColor(context, R.color.text_primary)
        isFakeBoldText = true
    }

    private val courseMetaPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(9.5f)
        color = ContextCompat.getColor(context, R.color.text_secondary)
    }

    private val blockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }
    private val progressTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val progressFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val nowLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = dp(1.5f)
        color = ContextCompat.getColor(context, R.color.accent)
    }
    private val nowDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent)
    }

    private val blockRect = RectF()

    // ------------------------------------------------------------------ 呼吸动画

    private var pulse = 0f

    /**
     * 高度按节次数自适应：一天 16 节也不会挤成一团（外层 ScrollView 负责滚动）。
     * 原来固定 320dp 只够 8 节——实测很多学校一天能排到 16 节。
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = View.MeasureSpec.getSize(widthMeasureSpec)
        val desired = (dp(HEADER_HEIGHT_DP) + periods.size * dp(ROW_HEIGHT_DP)).toInt() +
            paddingTop + paddingBottom
        setMeasuredDimension(width, desired)
    }

    private val pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1800L
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = LinearInterpolator()
        addUpdateListener {
            pulse = it.animatedValue as Float
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        pulseAnimator.start()
    }

    override fun onDetachedFromWindow() {
        pulseAnimator.cancel()
        super.onDetachedFromWindow()
    }

    // ------------------------------------------------------------------ 绘制

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val headerHeight = dp(HEADER_HEIGHT_DP)
        val leftWidth = dp(LEFT_WIDTH_DP)
        val columns = dayLabels.size
        val rows = periods.size
        if (columns == 0 || rows == 0) return

        val gridLeft = leftWidth
        val gridTop = headerHeight
        val gridWidth = (width - leftWidth).coerceAtLeast(1f)
        val gridHeight = (height - headerHeight).coerceAtLeast(1f)
        val columnWidth = gridWidth / columns
        val rowHeight = gridHeight / rows

        // 今天那一列的底色
        if (nowDayIndex in 0 until columns) {
            ctxRoundRect(
                canvas,
                gridLeft + nowDayIndex * columnWidth + dp(1.5f),
                gridTop,
                gridLeft + (nowDayIndex + 1) * columnWidth - dp(1.5f),
                gridTop + gridHeight,
                dp(10f),
                todayColumnPaint
            )
        }

        // 表头（周一…周五）
        for (column in 0 until columns) {
            val paint = if (column == nowDayIndex) todayHeaderPaint else headerPaint
            val centerX = gridLeft + columnWidth * column + columnWidth / 2f
            val baseline = gridTop / 2f - (paint.descent() + paint.ascent()) / 2f
            canvas.drawText(dayLabels[column], centerX, baseline, paint)
        }

        // 左侧节次（序号 + 起始时间）
        for (row in 0 until rows) {
            val period = periods[row]
            val centerY = gridTop + rowHeight * row
            val numberBaseline = centerY + rowHeight / 2f - dp(3f)
            canvas.drawText(period.index.toString(), leftWidth / 2f, numberBaseline, periodPaint)
            canvas.drawText(period.label, leftWidth / 2f, numberBaseline + dp(11f), periodPaint)
        }

        // 网格线
        for (row in 0..rows) {
            val y = gridTop + rowHeight * row
            canvas.drawLine(gridLeft, y, gridLeft + gridWidth, y, gridPaint)
        }
        for (column in 0..columns) {
            val x = gridLeft + columnWidth * column
            canvas.drawLine(x, gridTop, x, gridTop + gridHeight, gridPaint)
        }

        // 课程块
        //
        // ⚠️ 真踩过：原来是"按顺序一门门画"，同一格里的多门课会**后画的白盖掉先画的** ——
        // 表现出来就是"有一门课永远卡在一个位置、另一门干脆看不见（缺课）"。
        // 所以先算出每门课的**车道**：同一格重叠的就横向切成多条车道，谁也不盖谁。
        val active = courses.filter {
            it.dayOfWeek - 1 in 0 until columns && it.startPeriod - 1 in 0 until rows
        }
        val lanes = HashMap<String, Pair<Int, Int>>()
        for (course in active) {
            val start = course.startPeriod - 1
            val end = start + course.span - 1
            val peers = active.filter { other ->
                other.id != course.id && other.dayOfWeek == course.dayOfWeek &&
                    other.startPeriod - 1 <= end && other.startPeriod - 1 + other.span - 1 >= start
            }
            // 用 id 排序定车道，保证每次重绘位置稳定（不闪）
            lanes[course.id] = peers.count { it.id < course.id } to (peers.size + 1)
        }
        for (course in active) {
            val (lane, laneCount) = lanes[course.id] ?: (0 to 1)
            val column = course.dayOfWeek - 1
            val rowStart = course.startPeriod - 1
            val rowEnd = (rowStart + course.span).coerceAtMost(rows)
            val cellLeft = gridLeft + column * columnWidth
            val laneWidth = (columnWidth - dp(4f)) / laneCount
            blockRect.set(
                cellLeft + dp(2f) + lane * laneWidth,
                gridTop + rowStart * rowHeight + dp(2f),
                cellLeft + dp(2f) + (lane + 1) * laneWidth,
                gridTop + rowEnd * rowHeight - dp(2f)
            )
            drawCourseBlock(canvas, course, blockRect)
        }

        // 「现在」时间线
        if (nowDayIndex in 0 until columns && nowFraction in 0f..1f) {
            val y = gridTop + gridHeight * nowFraction
            val lineStart = gridLeft + nowDayIndex * columnWidth
            canvas.drawLine(lineStart, y, lineStart + columnWidth, y, nowLinePaint)
            canvas.drawCircle(lineStart + dp(4f), y, dp(3.2f), nowDotPaint)
        }
    }

    private fun drawCourseBlock(canvas: Canvas, course: Course, rect: RectF) {
        val highlighted = course.id == highlightCourseId
        val radius = dp(9f)
        val accent = course.color

        // 底色：课程色的低透明度铺底（深色主题下就是一块若有若无的色块）
        blockPaint.color = accent
        blockPaint.alpha = if (highlighted) 78 else 44
        canvas.drawRoundRect(rect, radius, radius, blockPaint)

        // 左侧色条
        barPaint.color = lighten(accent, if (highlighted) 0.28f else 0.10f)
        barPaint.alpha = if (highlighted) 255 else 190
        canvas.drawRoundRect(
            rect.left + dp(1f),
            rect.top + dp(6f),
            rect.left + dp(3.5f),
            rect.bottom - dp(6f),
            dp(2f),
            dp(2f),
            barPaint
        )

        // 高亮的课：呼吸描边 + 底部进度条
        if (highlighted) {
            ringPaint.color = accent
            ringPaint.alpha = (110 + 120 * pulse).toInt().coerceIn(0, 255)
            canvas.drawRoundRect(rect, radius, radius, ringPaint)

            val trackTop = rect.bottom - dp(5f)
            val trackBottom = rect.bottom - dp(2f)
            progressTrackPaint.color = accent
            progressTrackPaint.alpha = 70
            canvas.drawRoundRect(
                rect.left + dp(5f), trackTop, rect.right - dp(5f), trackBottom, dp(1.5f), dp(1.5f),
                progressTrackPaint
            )
            val usable = rect.width() - dp(10f)
            val filled = usable * highlightProgress.coerceIn(0f, 1f)
            if (filled > dp(1.5f)) {
                progressFillPaint.color = lighten(accent, 0.30f)
                canvas.drawRoundRect(
                    rect.left + dp(5f), trackTop, rect.left + dp(5f) + filled, trackBottom, dp(1.5f), dp(1.5f),
                    progressFillPaint
                )
            }
        }

        // 文字：**课名优先**（连堂课最多 3 行），教室只在课名没被截断、且下面还有空间时才画。
        // 踩过：原来固定 2 行 + 永远画教室，结果单节课的格子课名被截得看不清。
        val textLeft = rect.left + dp(5f)
        val textWidth = (rect.width() - dp(9f)).coerceAtLeast(dp(10f))
        val maxNameLines = if (rect.height() >= dp(58f)) 3 else 2
        val nameLines = wrap(course.name, courseNamePaint, textWidth, maxNameLines)
        var baseline = rect.top + courseNamePaint.textSize + dp(3f)
        for (line in nameLines) {
            canvas.drawText(line.toString(), textLeft, baseline, courseNamePaint)
            baseline += courseNamePaint.textSize + dp(2f)
        }
        val nameTruncated = nameLines.isNotEmpty() && nameLines.last().toString().endsWith("…")
        if (!nameTruncated && course.room.isNotBlank() && baseline + dp(6f) <= rect.bottom - dp(4f)) {
            val room = TextUtils.ellipsize(course.room, courseMetaPaint, textWidth, TextUtils.TruncateAt.END)
            canvas.drawText(room.toString(), textLeft, baseline + dp(1f), courseMetaPaint)
        }
    }

    private fun ctxRoundRect(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        radius: Float,
        paint: Paint
    ) {
        blockRect.set(left, top, right, bottom)
        canvas.drawRoundRect(blockRect, radius, radius, paint)
    }

    /** 按像素宽度折行（最多 maxLines 行，最后一行省略号收尾）。 */
    private fun wrap(text: String, paint: TextPaint, maxWidth: Float, maxLines: Int): List<CharSequence> {
        if (text.isEmpty()) return emptyList()
        val lines = mutableListOf<CharSequence>()
        // 必须是 String（Paint.breakText 的 4 参重载只接受 String/char[]）
        var rest: String = text
        while (lines.size < maxLines) {
            val count = paint.breakText(rest, true, maxWidth, null)
            if (count <= 0) break
            if (count >= rest.length) {
                lines += rest
                break
            }
            if (lines.size == maxLines - 1) {
                lines += TextUtils.ellipsize(rest, paint, maxWidth, TextUtils.TruncateAt.END)
                break
            }
            lines += rest.substring(0, count)
            rest = rest.substring(count).trimStart()
        }
        return lines
    }

    private fun lighten(color: Int, fraction: Float): Int {
        val r = color shr 16 and 0xFF
        val g = color shr 8 and 0xFF
        val b = color and 0xFF
        val nr = (r + (255 - r) * fraction).toInt().coerceIn(0, 255)
        val ng = (g + (255 - g) * fraction).toInt().coerceIn(0, 255)
        val nb = (b + (255 - b) * fraction).toInt().coerceIn(0, 255)
        return 0xFF000000.toInt() or (nr shl 16) or (ng shl 8) or nb
    }

    companion object {
        private const val HEADER_HEIGHT_DP = 26f
        private const val ROW_HEIGHT_DP = 38f
        private const val LEFT_WIDTH_DP = 34f
    }
}
