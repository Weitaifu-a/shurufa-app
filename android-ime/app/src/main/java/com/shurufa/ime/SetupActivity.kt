package com.shurufa.ime

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 桌面图标点进来的引导页：三步开启输入法。
 */
class SetupActivity : android.app.Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        box.addView(TextView(this).apply {
            text = "柠檬拼音"
            textSize = 24f
            setPadding(0, 0, 0, pad / 2)
        })

        box.addView(TextView(this).apply {
            text = "组词 · 成语 · 自动翻译"
            textSize = 14f
            setTextColor(0xFF6B7A94.toInt())
            setPadding(0, 0, 0, pad)
        })

        status = TextView(this).apply {
            textSize = 15f
            setPadding(0, 0, 0, pad)
        }
        box.addView(status)

        box.addView(stepText("第 1 步", "点下面这个按钮，进入系统的「输入法」设置页"))
        box.addView(bigButton("① 去开启输入法") {
            runCatching {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }
        })

        box.addView(stepText("第 2 步", "在列表里找到「柠檬拼音」，把开关打开；系统弹确认框时点「确定 / 允许」"))
        box.addView(stepText("第 3 步", "回到这个页面，点下面这个按钮，选中「柠檬拼音」"))
        box.addView(bigButton("② 选择当前输入法") {
            runCatching {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showInputMethodPicker()
            }
        })

        box.addView(stepText("", "设置好后，在微信、备忘录里长按输入框 → 选择输入法 → 柠檬拼音，就能用了。"))

        box.addView(Button(this).apply {
            text = "检查是否已开启"
            setOnClickListener { refresh() }
        })

        setContentView(ScrollView(this).apply { addView(box) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val enabled = runCatching {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.enabledInputMethodList.any { it.packageName == packageName }
        }.getOrDefault(false)

        status.text = if (enabled) {
            "✅ 已开启。现在去微信里长按输入框，切换成「柠檬拼音」即可。"
        } else {
            "⚠️ 还没开启。请按下面第 1 步操作。"
        }
        status.setTextColor(if (enabled) 0xFF1E6B34.toInt() else 0xFFB25000.toInt())
    }

    private fun stepText(title: String, body: String): TextView =
        TextView(this).apply {
            text = if (title.isEmpty()) body else "$title：$body"
            textSize = 14f
            setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, (6 * resources.displayMetrics.density).toInt())
        }

    private fun bigButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            gravity = Gravity.CENTER
            setOnClickListener { onClick() }
        }
}
