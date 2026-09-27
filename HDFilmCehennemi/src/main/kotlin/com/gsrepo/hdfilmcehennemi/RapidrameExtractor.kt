package com.gsrepo.hdfilmcehennemi

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.util.Base64
import java.util.regex.Pattern

class RapidrameExtractor : ExtractorApi() {
    override val name = "Rapidrame"
    override val mainUrl = "https://hdfilmcehennemi.mobi"
    override val requiresReferer = true

    private fun decode(jsCode: String, arrStr: String): String {
        val matcher = Pattern.compile(""([^"]+)"").matcher(arrStr)
        val sb = StringBuilder()
        while (matcher.find()) sb.append(matcher.group(1))
        var value = sb.toString()

        val keys = Pattern.compile("var\\s+[a-zA-Z0-9_]+\\s*=\\s*"([^"]+)";\\s*var\\s+[a-zA-Z0-9_]+\\s*=\\s*"([^"]+)";").matcher(jsCode)
        if (!keys.find()) return ""
        val key1 = keys.group(1) ?: return ""
        val key2 = keys.group(2) ?: return ""

        var o0v = 0
        var rbc = 0
        for (i in key1.indices) {
            val c = key1[i].code
            o0v = (o0v * 31 + c) % 251
            rbc = (rbc xor (c + i)) and 255
        }

        val lbxe = (o0v + rbc) % 256
        val u2u5r = (o0v % 13) + 3
        var d6en9 = ((o0v * 256 + rbc) % 65521) + 1

        for (i in key2.length - 1 downTo 0) {
            when (key2[i]) {
                'b' -> {
                    var padded = value
                    val missing = padded.length % 4
                    if (missing != 0) padded += "=".repeat(4 - missing)
                    value = String(Base64.getDecoder().decode(padded), Charsets.ISO_8859_1)
                }
                'v' -> value = value.reversed()
                else -> {
                    val shift = (26 - ((key2[i].code - 64) % 26)) % 26
                    value = value.map { c ->
                        if (c.isLetter()) {
                            val base = if (c.code <= 90) 65 else 97
                            ((c.code - base + shift) % 26 + base).toChar()
                        } else c
                    }.joinToString("")
                }
            }
        }

        val shuffle = IntArray(value.length)
        for (i in value.length - 1 downTo 1) {
            d6en9 = (d6en9 * 75 + 74) % 65537
            shuffle[i] = d6en9 % (i + 1)
        }
        val chars = value.toCharArray()
        for (i in 1 until value.length) {
            val j = shuffle[i]
            val tmp = chars[i]
            chars[i] = chars[j]
            chars[j] = tmp
        }
        value = String(chars)

        var tds = lbxe
        val out = StringBuilder()
        for (c in value) {
            val wlv = c.code
            tds = (tds + u2u5r) % 256
            out.append((wlv xor tds).toChar())
            tds = (tds + wlv) % 256
        }
        return out.toString()
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = app.get(url, headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36",
            "Referer" to (referer ?: "https://www.hdfilmcehennemi.nl/")
        )).text

        val matcher = Pattern.compile("\\[\\s*"[^\\]]+"\\s*\\]").matcher(html)
        while (matcher.find()) {
            val arr = matcher.group(0) ?: continue
            if (arr.contains(".jpg") || arr.contains(".png") || arr.contains(".webp")) continue
            try {
                val stream = decode(html, arr)
                if (stream.contains(".m3u8") || stream.contains(".txt") || stream.contains("/hls/")) {
                    callback(newExtractorLink(name, name, stream, INFER_TYPE) {
                        this.referer = url
                    })
                    break
                }
            } catch (_: Exception) {}
        }

        Regex("""\{"file":"(https?:[^"]+\.vtt)"[^}]+?"label":"([^"]+)"""").findAll(html).forEach {
            subtitleCallback(SubtitleFile(it.groupValues[2], it.groupValues[1].replace("""\/""", "/")))
        }
    }
}
