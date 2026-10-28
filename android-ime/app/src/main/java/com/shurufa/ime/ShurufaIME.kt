package com.shurufa.ime

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.concurrent.thread

class ShurufaIME : InputMethodService() {

    // ---------- 视图 ----------
    private var rootView: LinearLayout? = null
    private var candidateBar: LinearLayout? = null
    private var preview: TextView? = null
    private var tip: TextView? = null
    private var kbHost: LinearLayout? = null

    // ---------- 状态 ----------
    private val composing = StringBuilder()
    private var candidates: List<String> = emptyList()
    private var englishMode = false
    private var shiftOn = false
    private var lastCommitted = StringBuilder()
    private var extraMode = 0 // 0 无 / 1 组词 / 2 成语
    private var dataReady = false

    private val cBg = Color.parseColor("#F3F5F8")
    private val cKey = Color.WHITE
    private val cKeyFunc = Color.parseColor("#DCE0E6")
    private val cText = Color.parseColor("#1A2233")
    private val cAccent = Color.parseColor("#2E7DF7")
    private val cSub = Color.parseColor("#6B7A94")

    override fun onCreate() {
        super.onCreate()
        thread(name = "dict-loader") {
            runCatching { Dict.load(this@ShurufaIME) }
            dataReady = Dict.ready
            rootView?.post {
                refreshPreview()
                if (dataReady) refreshCandidates()
            }
        }
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun roundBg(color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

    // ==================== 视图构建 ====================

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreateInputView(): View {
        rootView?.let { return it }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cBg)
            setPadding(0, dp(2), 0, dp(6))
        }

        // ---- 顶部状态提示 ----
        val pv = TextView(this).apply {
            text = "柠檬拼音 · 正在加载词库…"
            setTextColor(cSub)
            textSize = 12f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
        }
        preview = pv
        root.addView(pv, LinearLayout.LayoutParams(MATCH_PARENT, dp(22)))

        // ---- 候选栏 ----
        val cb = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, dp(6), 0)
        }
        candidateBar = cb
        root.addView(
            HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                setBackgroundColor(Color.WHITE)
                addView(cb, LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT))
            },
            LinearLayout.LayoutParams(MATCH_PARENT, dp(44))
        )

        // ---- 功能条 ----
        root.addView(buildToolbar(), LinearLayout.LayoutParams(MATCH_PARENT, dp(34)))

        // ---- 键盘 ----
        val kh = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(3), dp(3), dp(3), 0)
        }
        kbHost = kh
        root.addView(kh, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        buildKeyboard()

        rootView = root
        if (Dict.ready) {
            dataReady = true
            refreshPreview()
            refreshCandidates()
        }
        return root
    }

    private fun buildToolbar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(2), dp(6), dp(2))
        }

        fun btn(label: String, color: Int, onClick: () -> Unit): TextView =
            TextView(this).apply {
                text = label
                setTextColor(color)
                textSize = 13f
                gravity = Gravity.CENTER
                isClickable = true
                setOnClickListener { onClick() }
                layoutParams = LinearLayout.LayoutParams(0, dp(28), 1f).apply {
                    marginEnd = dp(4)
                }
                background = roundBg(Color.WHITE, 6)
            }

        bar.addView(btn("组词", cAccent) { doZuCi() })
        bar.addView(btn("成语", cAccent) { doChengYu() })
        bar.addView(btn("翻译", cAccent) { doTranslate() })
        bar.addView(btn("收起", cSub) { requestHideSelf(0) })
        return bar
    }

    private fun buildKeyboard() {
        val host = kbHost ?: return
        host.removeAllViews()

        val gap = 3

        fun addRow(keys: List<Pair<String, Float>>) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            for ((label, weight) in keys) {
                val tv = makeKey(label)
                row.addView(tv, LinearLayout.LayoutParams(0, dp(42), weight).apply {
                    setMargins(dp(gap), dp(gap), dp(gap), 0)
                })
            }
            host.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }

        addRow("qwertyuiop".map { it.toString() }.map { it to 1f })
        addRow("asdfghjkl".map { it.toString() }.map { it to 1f })

        // 第三行：上档 + zxcvbnm + 删除
        val r3 = ArrayList<Pair<String, Float>>()
        r3.add("⇧" to 1.4f)
        r3.addAll("zxcvbnm".map { it.toString() }.map { it to 1f })
        r3.add("⌫" to 1.4f)
        addRow(r3)

        // 第四行：中/英 + 逗号 + 空格 + 句号 + 回车
        val r4 = ArrayList<Pair<String, Float>>()
        r4.add((if (englishMode) "中" else "En") to 1.2f)
        r4.add("，" to 1f)
        r4.add("空格" to 3.6f)
        r4.add("。" to 1f)
        r4.add("↵" to 1.2f)
        addRow(r4)
    }

    private fun makeKey(label: String): TextView {
        val isFunc = label.length > 1 || label in listOf("⇧", "⌫", "↵", "，", "。", "En", "中")
        val tv = TextView(this).apply {
            text = if (label.length == 1 && label[0] in 'a'..'z' && shiftOn && !englishMode) label.uppercase() else label
            setTextColor(cText)
            textSize = if (label == "空格") 13f else 17f
            gravity = Gravity.CENTER
            isClickable = true
            background = roundBg(if (isFunc) cKeyFunc else cKey, 5)
            setOnClickListener { onKey(label) }
            if (label == "空格") {
                setOnLongClickListener {
                    onKey("En")
                    true
                }
            }
        }
        return tv
    }

    // ==================== 按键处理 ====================

    private fun onKey(label: String) {
        val ic = currentInputConnection ?: return
        when (label) {
            "⌫" -> doBackspace()
            "↵" -> {
                commitComposingIfAny()
                ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER))
                ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER))
            }
            "空格" -> {
                if (composing.isNotEmpty() && candidates.isNotEmpty()) {
                    pick(candidates[0])
                } else if (englishMode) {
                    ic.commitText(" ", 1)
                } else {
                    commitComposingIfAny()
                }
            }
            "En", "中" -> {
                commitComposingIfAny()
                englishMode = !englishMode
                extraMode = 0
                buildKeyboard()
                refreshCandidates()
                refreshPreview()
            }
            "⇧" -> {
                shiftOn = !shiftOn
                buildKeyboard()
            }
            "，", "。" -> {
                commitComposingIfAny()
                ic.commitText(label, 1)
                afterCommit(label)
            }
            else -> {
                val ch = if (englishMode && shiftOn) label.uppercase() else label
                if (englishMode) {
                    ic.commitText(ch, 1)
                    afterCommit(ch)
                    if (shiftOn) {
                        shiftOn = false
                        buildKeyboard()
                    }
                } else {
                    composing.append(ch)
                    ic.setComposingText(composing.toString(), 1)
                    extraMode = 0
                    refreshCandidates()
                    refreshPreview()
                }
            }
        }
    }

    private fun doBackspace() {
        val ic = currentInputConnection ?: return
        if (!englishMode && composing.isNotEmpty()) {
            composing.deleteCharAt(composing.length - 1)
            if (composing.isEmpty()) ic.setComposingText("", 1) else ic.setComposingText(composing.toString(), 1)
            extraMode = 0
            refreshCandidates()
            refreshPreview()
        } else {
            val sel = ic.getSelectedText(0)
            if (sel.isNullOrEmpty()) {
                ic.deleteSurroundingText(1, 0)
                if (lastCommitted.isNotEmpty()) lastCommitted.deleteCharAt(lastCommitted.length - 1)
            } else {
                ic.commitText("", 1)
            }
        }
    }

    private fun pick(word: String) {
        val ic = currentInputConnection ?: return
        ic.commitText(word, 1)
        composing.setLength(0)
        extraMode = 0
        refreshCandidates()
        afterCommit(word)
        refreshPreview()
    }

    private fun commitComposingIfAny() {
        if (composing.isEmpty()) return
        val ic = currentInputConnection ?: return
        val s = composing.toString()
        ic.commitText(s, 1)
        composing.setLength(0)
        afterCommit(s)
        refreshCandidates()
        refreshPreview()
    }

    private fun afterCommit(s: String) {
        lastCommitted.append(s)
        if (lastCommitted.length > 40) {
            lastCommitted.delete(0, lastCommitted.length - 40)
        }
    }

    // ==================== 功能：组词 / 成语 / 翻译 ====================

    private fun currentChar(): Char? {
        if (composing.isNotEmpty()) return null
        return lastCommitted.lastOrNull()
    }

    private fun doZuCi() {
        if (!Dict.ready) {
            toast("词库还在加载，稍等一下")
            return
        }
        val ch = currentChar()
        if (ch == null) {
            toast("先打出一个字，再点组词")
            return
        }
        val list = Dict.wordsContaining(ch, 100)
        if (list.isEmpty()) {
            toast("没找到含「$ch」的词语")
            return
        }
        extraMode = 1
        candidates = list
        showCandidates()
        refreshPreview()
    }

    private fun doChengYu() {
        if (!Dict.ready) {
            toast("词库还在加载，稍等一下")
            return
        }
        val ch = currentChar()
        if (ch == null) {
            toast("先打出一个字，再点成语")
            return
        }
        val list = Dict.idiomsContaining(ch, 100)
        if (list.isEmpty()) {
            toast("没找到含「$ch」的成语")
            return
        }
        extraMode = 2
        candidates = list
        showCandidates()
        refreshPreview()
    }

    private fun doTranslate() {
        val text = when {
            composing.isNotEmpty() -> composing.toString()
            lastCommitted.isNotEmpty() -> {
                val s = lastCommitted.toString()
                val cut = listOf(
                    s.lastIndexOf('，'), s.lastIndexOf('。'),
                    s.lastIndexOf(' '), s.lastIndexOf('\n')
                ).maxOrNull() ?: -1
                s.substring(cut + 1)
            }
            else -> ""
        }
        if (text.isEmpty()) {
            toast("先打点中文，再点翻译")
            return
        }
        toast("正在翻译…")
        thread {
            val online = runCatching { Translate.online(text.trim()) }.getOrNull()
            val result = when {
                !online.isNullOrBlank() -> online
                Dict.ready -> Dict.offlineTranslate(text.trim())
                else -> ""
            }
            rootView?.post {
                if (result.isBlank()) {
                    toast("翻译失败，检查一下网络")
                } else {
                    val ic = currentInputConnection
                    if (ic != null) {
                        if (composing.isNotEmpty()) {
                            composing.setLength(0)
                            ic.setComposingText("", 1)
                        }
                        ic.commitText(result, 1)
                        afterCommit(result)
                        extraMode = 0
                        candidates = emptyList()
                        refreshCandidates()
                        refreshPreview()
                        toast("已翻译并上屏")
                    }
                }
            }
        }
    }

    private fun toast(msg: String) {
        tip?.let { rootView?.removeView(it) }
        val tv = TextView(this).apply {
            text = msg
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = roundBg(Color.parseColor("#333A46"), 8)
        }
        tip = tv
        rootView?.addView(tv, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(2)
        })
        rootView?.postDelayed({
            if (tip === tv) {
                rootView?.removeView(tv)
                tip = null
            }
        }, 1800)
    }

    // ==================== 界面刷新 ====================

    private fun refreshCandidates() {
        if (extraMode != 0) return
        candidates = if (englishMode) emptyList() else Dict.candidates(composing.toString())
        showCandidates()
    }

    private fun showCandidates() {
        val bar = candidateBar ?: return
        bar.removeAllViews()

        if (candidates.isEmpty()) {
            val hint = TextView(this).apply {
                text = when {
                    !Dict.ready -> "正在加载词库…"
                    englishMode -> "英文模式 · 点左下角切回中文"
                    composing.isEmpty() -> "打拼音，比如 nihao"
                    else -> "没有候选，按空格上屏原字母"
                }
                setTextColor(cSub)
                textSize = 14f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), 0, dp(8), 0)
            }
            bar.addView(hint, LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT))
            return
        }

        val shown = if (extraMode == 0) candidates.take(30) else candidates.take(60)
        shown.forEachIndexed { idx, w ->
            val tv = TextView(this).apply {
                text = if (idx == 0) "1 $w" else (idx + 1).toString() + " " + w
                setTextColor(if (idx == 0) cAccent else cText)
                textSize = if (idx == 0) 19f else 18f
                gravity = Gravity.CENTER
                setPadding(dp(10), 0, dp(10), 0)
                isClickable = true
                setOnClickListener { pick(w) }
            }
            bar.addView(tv, LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT))
        }
    }

    private fun refreshPreview() {
        val p = preview ?: return
        p.text = when {
            !Dict.ready -> "柠檬拼音 · 正在加载词库…"
            englishMode -> "英文模式 · 直接敲字母"
            composing.isNotEmpty() -> "拼音：" + composing
            extraMode == 1 -> "组词结果 · 点一个直接上屏"
            extraMode == 2 -> "成语结果 · 点一个直接上屏"
            else -> "柠檬拼音 · 词语 " + Dict.wordCount + " · 成语 " + Dict.idiomCount
        }
    }

    // ==================== 生命周期 ====================

    override fun onStartInput(attribute: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        composing.setLength(0)
        candidates = emptyList()
        extraMode = 0
        refreshCandidates()
        refreshPreview()
    }

    override fun onFinishInput() {
        commitComposingIfAny()
        super.onFinishInput()
    }

    companion object {
        private const val MATCH_PARENT = -1
        private const val WRAP_CONTENT = -2
    }
}
