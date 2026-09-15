package dev.dpvoliin.islandtimetable

/**
 * 文本清洗：把教务返回里的 HTML 实体解开。
 *
 * 为什么需要：教务页面里的课程名常带实体（实测见「&ldquo;三全育人&rdquo;实践活动V」），
 * 我们抓的是页面文本 ⇒ 会原样带进课表，界面上很难看，而且"同名课程"也可能因此对不上。
 *
 * 只处理常见的命名实体 + 数字实体（`&#xxx;` / `&#xHH;`），不做完整的 HTML 解析
 * （课表里不需要，也不值得为它引一个解析器）。
 */
object TextFix {

    private val NAMED = mapOf(
        "&ldquo;" to "「",
        "&rdquo;" to "」",
        "&lsquo;" to "『",
        "&rsquo;" to "』",
        "&quot;" to "\"",
        "&apos;" to "'",
        "&amp;" to "&",
        "&lt;" to "<",
        "&gt;" to ">",
        "&nbsp;" to " ",
        "&mdash;" to "—",
        "&ndash;" to "–",
        "&hellip;" to "…",
        "&middot;" to "·",
        "&times;" to "×"
    )

    /** 解开 HTML 实体；没有实体时原样返回。 */
    fun decodeEntities(text: String): String {
        if (text.isEmpty() || !text.contains('&')) return text
        var out = text
        NAMED.forEach { (k, v) -> if (out.contains(k)) out = out.replace(k, v) }
        // 数字实体：&#123; / &#x1F600;
        if (out.contains("&#")) {
            out = Regex("&#(x?)([0-9a-fA-F]+);").replace(out) { m ->
                val code = if (m.groupValues[1].isEmpty()) {
                    m.groupValues[2].toIntOrNull(10)
                } else {
                    m.groupValues[2].toIntOrNull(16)
                }
                code?.let { runCatching { String(Character.toChars(it)) }.getOrNull() } ?: m.value
            }
        }
        return out
    }
}
