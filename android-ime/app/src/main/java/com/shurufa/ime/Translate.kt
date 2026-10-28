package com.shurufa.ime

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 联网翻译：走 Google 的公开翻译接口（无需注册、无需密钥）。
 * 失败返回 null，由调用方回退到离线词典。
 */
object Translate {

    fun online(text: String): String? {
        val q = text.trim()
        if (q.isEmpty()) return null

        val hasChinese = q.any { it.code in 0x4E00..0x9FFF }
        val sl = if (hasChinese) "zh-CN" else "en"
        val tl = if (hasChinese) "en" else "zh-CN"

        val url = "https://translate.googleapis.com/translate_a/single" +
                "?client=gtx&sl=" + sl + "&tl=" + tl + "&dt=t&q=" + URLEncoder.encode(q, "UTF-8")

        var conn: HttpURLConnection? = null
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            conn = c
            c.requestMethod = "GET"
            c.connectTimeout = 8000
            c.readTimeout = 10000
            c.setRequestProperty("User-Agent", "Mozilla/5.0")

            if (c.responseCode != 200) return null

            val body = c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return parse(body)
        } catch (e: Throwable) {
            return null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /** 返回体形如 [[["hello","你好",null,null,10]],null,"zh-CN"] */
    private fun parse(body: String): String? {
        return runCatching {
            val arr = JSONArray(body)
            val segs = arr.optJSONArray(0)
            if (segs == null) {
                null
            } else {
                val sb = StringBuilder()
                for (i in 0 until segs.length()) {
                    val seg = segs.optJSONArray(i) ?: continue
                    val t = seg.optString(0, "")
                    if (t.isNotEmpty()) sb.append(t)
                }
                sb.toString().ifBlank { null }
            }
        }.getOrNull()
    }
}
