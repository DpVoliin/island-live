package dev.dpvoliin.islandtimetable

/**
 * 通知文案的**唯一出处** —— 「课前提醒」「常驻通知」「默认通知（实时通知）」三条都从这里取词。
 *
 * v1.0.5 修：这三条原来各写一套，用户看到的就是"第一次打开的通知"和"开了常驻后的通知"长得不一样。
 * 现在统一成同一个形状：
 *
 *   标题 = {状态} · {课程名}      状态 ∈ 正在上课 / 下一节 / N 分钟后上课 / 现在上课
 *   内容 = {星期 第N–M节} · {教室}（缺的自动省略；正在上课时内容换成"还有 N 分钟下课"）
 */
object NotifyText {

    const val STATUS_NOW = "正在上课"
    const val STATUS_NEXT = "下一节"

    /** 课前提醒的状态词。 */
    fun statusBefore(leadMinutes: Int): String =
        if (leadMinutes <= 0) "现在上课" else "$leadMinutes 分钟后上课"

    /** 统一标题形状。 */
    fun title(status: String, courseName: String): String = "$status · $courseName"

    /** 节次文本：第N节 / 第N–M节。 */
    fun periodsText(startPeriod: Int, span: Int): String =
        if (span > 1) "第$startPeriod–${startPeriod + span - 1}节" else "第${startPeriod}节"

    /** 位置细节：`{星期} {第N–M节} · {教室}`（缺的省略）。 */
    fun where(dayLabel: String?, periods: String, room: String): String {
        val head = buildString {
            if (!dayLabel.isNullOrBlank()) append(dayLabel)
            if (periods.isNotBlank()) {
                if (isNotEmpty()) append(' ')
                append(periods)
            }
        }
        return listOf(head, room)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
    }

    /** 正在上课时的内容（岛与常驻共用，保证一字不差）。 */
    fun nowContent(remainingMinutes: Int, room: String): String {
        val tail = if (room.isBlank()) "" else " · $room"
        return if (remainingMinutes > 0) "还有 $remainingMinutes 分钟下课$tail" else "即将下课$tail"
    }
}
