package dev.dpvoliin.islandtimetable

import android.app.TimePickerDialog
import android.os.Bundle
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import java.time.LocalTime

/**
 * 作息时间（节次表）设置页 —— v1.0.3。
 *
 * 为什么必须有这一页：教务导入（正方 / 强智…）和 CSV / Excel 导入**只给节次序号，不给时刻**，
 * 所以库里只有一份**内置占位作息**（第 1 节 08:00–08:40、第 2 节 08:50–09:30 …）。
 * 不设置的话，课表上显示的"上课时间"、上课提醒、写入日历、自动静音**全都按错的作息走**。
 *
 * 这里只改节次时间，不动课程、周次、学期起始日。
 */
class PeriodActivity : AppCompatActivity() {

    private lateinit var rowsBox: LinearLayout
    private lateinit var etStart: EditText
    private lateinit var etLesson: EditText
    private lateinit var etBreak: EditText
    private lateinit var tvHint: TextView

    private var periods: MutableList<Period> = mutableListOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_period)
        SystemBars.apply(this)

        rowsBox = findViewById(R.id.rowsBox)
        etStart = findViewById(R.id.etStart)
        etLesson = findViewById(R.id.etLesson)
        etBreak = findViewById(R.id.etBreak)
        tvHint = findViewById(R.id.tvHint)

        periods = (DemoTimetable.PERIODS.ifEmpty { DemoTimetable.BUILTIN_PERIODS }).toMutableList()
        etStart.setText(periods.firstOrNull()?.start?.toString() ?: "08:00")
        tvHint.text = if (DemoTimetable.periodsArePlaceholder) {
            "⚠ 当前是**内置占位作息**，不是你们学校的真实时间。请在这里填一次，" +
                "否则课表显示的节次时间、上课提醒、写入日历、自动静音都会按错时间走。"
        } else {
            "保存后立即生效：课表左侧节次时间、课程时段、上课提醒、写入日历、自动静音都会按这套作息走。"
        }
        rebuild()

        findViewById<MaterialButton>(R.id.btnGenerate).setOnClickListener { generate() }
        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener { save() }
        findViewById<MaterialButton>(R.id.btnReset).setOnClickListener {
            periods = DemoTimetable.BUILTIN_PERIODS.toMutableList()
            rebuild()
            toast("已恢复内置默认作息（还要点「保存」才生效）")
        }
    }

    /** 按「起始时间 + 每节时长 + 课间」连续生成整套作息（生成后可再逐节点改）。 */
    private fun generate() {
        val start = parseTime(etStart.text?.toString()) ?: run {
            toast("起始时间格式要像 08:00")
            return
        }
        val lesson = etLesson.text?.toString()?.trim()?.toIntOrNull()?.coerceIn(10, 120) ?: 45
        val gap = etBreak.text?.toString()?.trim()?.toIntOrNull()?.coerceIn(0, 60) ?: 10
        val count = periods.size.coerceAtLeast(1)
        val next = mutableListOf<Period>()
        var cursor = start
        for (i in 1..count) {
            val end = cursor.plusMinutes(lesson.toLong())
            next.add(Period(i, cursor, end))
            cursor = end.plusMinutes(gap.toLong())
        }
        periods = next
        rebuild()
        toast("已生成 $count 节（上午/下午/晚上的空档请手动改）")
    }

    private fun rebuild() {
        rowsBox.removeAllViews()
        periods.forEachIndexed { index, period ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4f), 0, dp(4f))
            }
            row.addView(TextView(this).apply {
                text = "第 ${period.index} 节"
                setTextColor(getColor(R.color.text_secondary))
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(timeButton(period.start) { picked ->
                periods[index] = periods[index].copy(start = picked)
            })
            row.addView(TextView(this).apply {
                text = "–"
                setTextColor(getColor(R.color.text_tertiary))
                setPadding(dp(6f), 0, dp(6f), 0)
            })
            row.addView(timeButton(period.end) { picked ->
                periods[index] = periods[index].copy(end = picked)
            })
            rowsBox.addView(row)
        }
    }

    /** 一个显示时刻的按钮，点了弹系统时间选择器。 */
    private fun timeButton(initial: LocalTime, onPicked: (LocalTime) -> Unit): android.widget.Button =
        android.widget.Button(this).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setTextColor(getColor(R.color.accent))
            text = "%02d:%02d".format(initial.hour, initial.minute)
            textSize = 13f
            minWidth = 0
            setPadding(dp(10f), 0, dp(10f), 0)
            setOnClickListener {
                TimePickerDialog(
                    this@PeriodActivity,
                    { _, hour, minute ->
                        val picked = LocalTime.of(hour, minute)
                        onPicked(picked)
                        text = "%02d:%02d".format(picked.hour, picked.minute)
                    },
                    initial.hour,
                    initial.minute,
                    true
                ).show()
            }
        }

    private fun save() {
        val cleaned = periods
            .mapIndexed { i, p -> p.copy(index = i + 1) }
            .filter { it.end.isAfter(it.start) }
        if (cleaned.isEmpty()) {
            toast("至少要有一节有效时间（下课时间要晚于上课时间）")
            return
        }
        DemoTimetable.savePeriods(this, cleaned)
        toast("已保存 ${cleaned.size} 节作息，课表时间已更新")
        finish()
    }

    private fun parseTime(text: String?): LocalTime? = runCatching {
        val t = text.orEmpty().trim().replace("：", ":")
        val parts = t.split(":")
        LocalTime.of(parts[0].toInt(), parts[1].toInt())
    }.getOrNull()

    /** dp → px（与仓库其余地方一致：收 Float，调用写 dp(16f)）。 */
    private fun dp(value: Float): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}
