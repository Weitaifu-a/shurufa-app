package com.shurufa.ime

import android.content.Context
import java.io.BufferedReader

/**
 * 词库引擎：拼音 -> 汉字候选 / 组词 / 成语 / 离线中英词典
 * 全部数据来自 assets 里的三个 txt，纯离线，首次加载约 1-2 秒。
 */
object Dict {

    /** 拼音 -> 该拼音对应的所有词 */
    private val byPinyin = HashMap<String, MutableList<String>>()

    /** 单字 -> 拼音 */
    private val charPinyin = HashMap<Char, String>()

    /** 全部词语（用于组词） */
    private val allWords = ArrayList<String>(45000)

    /** 成语及其释义 */
    private val idiomList = ArrayList<String>(32000)
    private val idiomDesc = HashMap<String, String>()

    /** 中文 -> 英文（离线翻译兜底） */
    private val zhEn = HashMap<String, String>(50000)

    @Volatile
    var ready = false
        private set

    var wordCount = 0
        private set
    var idiomCount = 0
        private set
    var dictCount = 0
        private set

    fun load(ctx: Context) {
        if (ready) return
        synchronized(this) {
            if (ready) return

            runCatching {
                ctx.assets.open("words.txt").use { ins ->
                    val r = ins.bufferedReader(Charsets.UTF_8)
                    var line: String?
                    while (true) {
                        line = r.readLine() ?: break
                        val i = line.indexOf('\t')
                        if (i <= 1) continue
                        val w = line.substring(0, i)
                        val p = line.substring(i + 1)
                        if (p.isEmpty()) continue
                        allWords.add(w)
                        if (w.length == 1) charPinyin[w[0]] = p
                        byPinyin.getOrPut(p) { ArrayList(4) }.add(w)
                    }
                }
                wordCount = allWords.size
            }

            runCatching {
                ctx.assets.open("idioms.txt").use { ins ->
                    val r = ins.bufferedReader(Charsets.UTF_8)
                    var line: String?
                    while (true) {
                        line = r.readLine() ?: break
                        val a = line.indexOf('\t')
                        if (a <= 1) continue
                        val w = line.substring(0, a)
                        val rest = line.substring(a + 1)
                        val b = rest.indexOf('\t')
                        val p = if (b >= 0) rest.substring(0, b) else rest
                        val d = if (b >= 0) rest.substring(b + 1) else ""
                        idiomList.add(w)
                        if (d.isNotEmpty()) idiomDesc[w] = d
                        if (p.isNotEmpty()) byPinyin.getOrPut(p) { ArrayList(4) }.add(w)
                    }
                }
                idiomCount = idiomList.size
            }

            runCatching {
                ctx.assets.open("cedict.txt").use { ins ->
                    val r = ins.bufferedReader(Charsets.UTF_8)
                    var line: String?
                    while (true) {
                        line = r.readLine() ?: break
                        val i = line.indexOf('\t')
                        if (i <= 0) continue
                        zhEn[line.substring(0, i)] = line.substring(i + 1)
                    }
                }
                dictCount = zhEn.size
            }

            // 候选按字数短的优先，长词后置
            for (list in byPinyin.values) {
                list.sortWith(compareBy({ it.length }, { it }))
            }
            ready = true
        }
    }

    /**
     * 输入拼音，返回候选词。
     * 先精确匹配（绝大多数情况够用）；精确匹配为空时才做前缀模糊匹配，
     * 避免每次按键都遍历全部词库导致卡顿。
     */
    fun candidates(pinyin: String, limit: Int = 60): List<String> {
        if (pinyin.isEmpty() || !ready) return emptyList()
        val out = LinkedHashSet<String>(limit * 2)

        byPinyin[pinyin]?.let { exact ->
            for (w in exact) {
                out.add(w)
                if (out.size >= limit) return out.toList()
            }
        }

        if (out.isEmpty()) {
            for (entry in byPinyin.entries) {
                val k = entry.key
                if (k.length > pinyin.length && k.startsWith(pinyin)) {
                    for (w in entry.value) {
                        out.add(w)
                        if (out.size >= limit) return out.toList()
                    }
                }
            }
        }
        return out.toList()
    }

    /** 组词：含有这个字的所有词语，最多 limit 个 */
    fun wordsContaining(ch: Char, limit: Int = 80): List<String> {
        if (!ready) return emptyList()
        val out = ArrayList<String>(limit)
        for (w in allWords) {
            if (w.length > 1 && w.indexOf(ch) >= 0) {
                out.add(w)
                if (out.size >= limit) break
            }
        }
        out.sortWith(compareBy({ it.length }, { it }))
        return out
    }

    /** 成语：含有这个字的成语 */
    fun idiomsContaining(ch: Char, limit: Int = 80): List<String> {
        if (!ready) return emptyList()
        val out = ArrayList<String>(limit)
        for (w in idiomList) {
            if (w.indexOf(ch) >= 0) {
                out.add(w)
                if (out.size >= limit) break
            }
        }
        return out
    }

    fun idiomDesc(w: String): String = idiomDesc[w] ?: ""

    fun pinyinOf(ch: Char): String = charPinyin[ch] ?: ""

    /** 离线逐词直译（整句翻译失败时兜底） */
    fun offlineTranslate(text: String): String {
        if (!ready) return ""
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            var hit: String? = null
            var used = 0
            var len = 8
            while (len >= 1) {
                if (i + len <= text.length) {
                    val w = text.substring(i, i + len)
                    val v = zhEn[w]
                    if (v != null) {
                        hit = v
                        used = len
                        break
                    }
                }
                len--
            }
            if (hit == null) {
                val c = text[i].toString()
                hit = zhEn[c] ?: c
                used = 1
            }
            sb.append(hit)
            i += used
            if (i < text.length) sb.append(' ')
        }
        return sb.toString()
    }
}
