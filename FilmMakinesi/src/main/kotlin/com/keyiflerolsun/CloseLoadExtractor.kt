package com.keyiflerolsun

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.util.regex.Pattern


private fun getm3uLink(data: String): String {
    val first = Base64.decode(data, Base64.DEFAULT).reversedArray()
    val second = Base64.decode(first, Base64.DEFAULT)
    return second.toString(Charsets.UTF_8).split("|")[1]
}

open class CloseLoadExtractor : ExtractorApi() {
    override val mainUrl = "https://closeload.filmmakinesi.to"
    override val name = "CloseLoad"
    override val requiresReferer = true

    companion object {
        fun decodeCloseLoad(jsCode: String, arrStr: String): String {
            val arrRegex = Pattern.compile("\"([^\"]+)\"")
            val arrMatcher = arrRegex.matcher(arrStr)
            val sb = java.lang.StringBuilder()
            while (arrMatcher.find()) {
                sb.append(arrMatcher.group(1))
            }
            var kspgo = sb.toString()

            val keysRegex = Pattern.compile("var\\s+[a-zA-Z0-9_]+\\s*=\\s*\"([^\"]+)\";\\s*var\\s+[a-zA-Z0-9_]+\\s*=\\s*\"([^\"]+)\";")
            val keysMatcher = keysRegex.matcher(jsCode)
            if (!keysMatcher.find()) return ""
            val key1 = keysMatcher.group(1) ?: return ""
            val key2 = keysMatcher.group(2) ?: return ""

            var o0v = 0
            var rbc = 0
            for (ro7 in key1.indices) {
                val wlv = key1[ro7].code
                o0v = (o0v * 31 + wlv) % 251
                rbc = (rbc xor (wlv + ro7)) and 255
            }

            val lbxe = (o0v + rbc) % 256
            val u2u5r = (o0v % 13) + 3
            var d6en9 = ((o0v * 256 + rbc) % 65521) + 1

            for (ro7 in key2.length - 1 downTo 0) {
                val x7ed6 = key2[ro7]
                if (x7ed6 == 'b') {
                    var padded = kspgo
                    val missing = padded.length % 4
                    if (missing != 0) {
                        padded += "=".repeat(4 - missing)
                    }
                    val decodedBytes = Base64.decode(padded, Base64.DEFAULT)
                    kspgo = String(decodedBytes, Charsets.ISO_8859_1)
                } else if (x7ed6 == 'v') {
                    kspgo = kspgo.reversed()
                } else {
                    val ql55 = (26 - ((x7ed6.code - 64) % 26)) % 26
                    val chars = java.lang.StringBuilder()
                    for (c in kspgo) {
                        if (c.isLetter()) {
                            val y85 = c.code
                            val fro = if (y85 <= 90) 65 else 97
                            chars.append(((y85 - fro + ql55) % 26 + fro).toChar())
                        } else {
                            chars.append(c)
                        }
                    }
                    kspgo = chars.toString()
                }
            }

            val trn = kspgo.length
            val npz8 = IntArray(trn)
            for (ro7 in trn - 1 downTo 1) {
                d6en9 = (d6en9 * 75 + 74) % 65537
                npz8[ro7] = d6en9 % (ro7 + 1)
            }

            val oe7 = kspgo.toCharArray()
            for (ro7 in 1 until trn) {
                val fy6 = npz8[ro7]
                val awn = oe7[ro7]
                oe7[ro7] = oe7[fy6]
                oe7[fy6] = awn
            }
            kspgo = String(oe7)

            var tds = lbxe
            val v8y7l = java.lang.StringBuilder()
            for (ro7 in kspgo.indices) {
                val wlv = kspgo[ro7].code
                tds = (tds + u2u5r) % 256
                v8y7l.append((wlv xor tds).toChar())
                tds = (tds + wlv) % 256
            }

            return v8y7l.toString()
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Referer" to (referer ?: "https://filmmakinesi.to/")
        )

        val html = app.get(url, headers = headers).text

        val arrRegex = Pattern.compile("\\[\\s*\"[^\\]]+\"\\s*\\]")
        val arrMatcher = arrRegex.matcher(html)

        while (arrMatcher.find()) {
            val arrStr = arrMatcher.group(0) ?: continue

            if (arrStr.contains(".jpg") || arrStr.contains(".png") || arrStr.contains(".webp")) {
                continue
            }

            try {
                val streamUrl = decodeCloseLoad(html, arrStr)

                if (
                    streamUrl.isNotEmpty() &&
                    (streamUrl.contains(".m3u8") || streamUrl.contains(".txt") || streamUrl.contains("/hls/"))
                ) {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = streamUrl,
                            type = INFER_TYPE
                        ) {
                            val domain = Regex("""(https?://[^/]+)""").find(url)?.groupValues?.get(1)
                            this.referer = domain?.plus("/") ?: url
                            this.headers = mapOf(
                                "Origin" to (domain ?: ""),
                                "Accept" to "*/*",
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                            )
                        }
                    )
                    break
                }
            } catch (_: Exception) {
            }
        }

        val tracksRegex = Regex("""\{"file":"(https?:[^"]+\\.vtt)"[^}]+?"label":"([^"]+)"""")
        tracksRegex.findAll(html).forEach { match ->
            val subUrl = match.groupValues[1].replace("\\\\/", "/")
            subtitleCallback(
                SubtitleFile(
                    lang = match.groupValues[2],
                    url = subUrl
                )
            )
        }
    }

    private fun unpackPackerJs(rawHtml: String): String? {
        return try {
            val startMarker = "eval(function(p,a,c,k,e,d){"
            val endMarker = ",0,{}))"

            val startIdx = rawHtml.indexOf(startMarker)
            if (startIdx == -1) return null

            val endIdx = rawHtml.indexOf(endMarker, startIdx + startMarker.length)
            if (endIdx == -1) return null

            val block = rawHtml.substring(startIdx, endIdx + endMarker.length)
            val packedStart = block.indexOf("}('") + 3
            val packedEnd = block.indexOf("',", packedStart)
            if (packedStart == -1 || packedEnd == -1) return null
            val packed = block.substring(packedStart, packedEnd)
            val afterPacked = block.substring(packedEnd + 2)
            val baseEnd = afterPacked.indexOf(",")
            if (baseEnd == -1) return null
            val base = afterPacked.substring(0, baseEnd).toInt()
            val afterBase = afterPacked.substring(baseEnd + 1)
            val countEnd = afterBase.indexOf(",")
            if (countEnd == -1) return null
            val count = afterBase.substring(0, countEnd).toInt()
            val dictQuoteStart = afterBase.indexOf("'") + 1
            val dictQuoteEnd = afterBase.indexOf("'.split", dictQuoteStart)
            if (dictQuoteStart == -1 || dictQuoteEnd == -1) return null
            val dictStr = afterBase.substring(dictQuoteStart, dictQuoteEnd)

            val dictionary = dictStr.split('|')
            val lookup = mutableMapOf<String, String>()

            var c = count - 1
            while (c >= 0) {
                val key = packerEncode(c, base)
                lookup[key] = if (c < dictionary.size && dictionary[c].isNotEmpty()) {
                    dictionary[c]
                } else {
                    key
                }
                c--
            }

            var result = packed
            val sortedKeys = lookup.keys.sortedByDescending { it.length }
            for (key in sortedKeys) {
                val value = lookup[key]!!
                result = result.replace(Regex("\\b${Regex.escape(key)}\\b"), value)
            }

            result
        } catch (e: Exception) {
            null
        }
    }

    private fun packerEncode(num: Int, base: Int): String {
        val digits = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        if (num == 0) return "0"
        var n = num
        val sb = StringBuilder()
        while (n > 0) {
            sb.insert(0, digits[n % base])
            n /= base
        }
        return sb.toString()
    }

    private fun extractFuncBody(jsCode: String, funcName: String): String? {
        val startIdx = jsCode.indexOf("function $funcName")
        if (startIdx == -1) return null

        val braceIdx = jsCode.indexOf('{', startIdx)
        if (braceIdx == -1) return null

        var braceCount = 1
        var i = braceIdx + 1
        while (braceCount > 0 && i < jsCode.length) {
            when (jsCode[i]) {
                '{' -> braceCount++
                '}' -> braceCount--
            }
            i++
        }
        return if (braceCount == 0) jsCode.substring(braceIdx + 1, i - 1) else null
    }

    private fun parseAndExecuteJs(funcBody: String, parts: List<String>): String? {
        return try {
            val seedMatch = Regex(
                """var\s+(\w+)\s*=\s*"([^"]+)"\s*;\s*var\s+(\w+)\s*=\s*"([^"]+)""""
            ).find(funcBody) ?: run {
                Log.w(name, "Seed/ops string'leri bulunamadı")
                return null
            }
            val seedStr = seedMatch.groupValues[2]
            val opsStr = seedMatch.groupValues[4]
            Log.d(name, "Seed: '$seedStr', Ops: '$opsStr'")

            var la8q = parts.joinToString("")
            var m1l = 0
            var rfdgf = 0
            for (i in seedStr.indices) {
                val ioz = seedStr[i].code
                m1l = (m1l * 31 + ioz) % 251
                rfdgf = (rfdgf xor (ioz + i)) and 255
            }
            val ucv = (m1l + rfdgf) % 256
            val h52gx = (m1l % 13) + 3
            var ws7g = ((m1l * 256 + rfdgf) % 65521) + 1
            Log.d(name, "ucv=$ucv, h52gx=$h52gx, ws7g=$ws7g")
            for (i in opsStr.length - 1 downTo 0) {
                val ch = opsStr[i]
                la8q = when (ch) {
                    'b' -> atob(la8q)
                    'v' -> la8q.reversed()
                    else -> {
                        val tcxa = (26 - ((ch.code - 64) % 26)) % 26
                        caesarShift(la8q, tcxa)
                    }
                }
            }
            if (opsStr.length > 4096) la8q = la8q.reversed()

            Log.d(name, "Operasyonlar sonrası uzunluk: ${la8q.length}")
            val tmzq = la8q.length
            if (tmzq > 1) {
                val gtwld = IntArray(tmzq)
                for (xfm8 in tmzq - 1 downTo 1) {
                    ws7g = (ws7g * 75 + 74) % 65537
                    gtwld[xfm8] = ws7g % (xfm8 + 1)
                }
                val onw = la8q.toCharArray()
                for (xfm8 in 1 until tmzq) {
                    val j = gtwld[xfm8]
                    val tmp = onw[xfm8]
                    onw[xfm8] = onw[j]
                    onw[j] = tmp
                }
                la8q = String(onw)
            }
            val sb = StringBuilder(tmzq)
            var ew0 = ucv
            for (c in la8q) {
                val ioz = c.code
                ew0 = (ew0 + h52gx) % 256
                sb.append((ioz xor ew0).toChar())
                ew0 = (ew0 + ioz) % 256
            }

            val result = sb.toString()
            Log.d(name, "Çözülen değer: ${result.take(200)}")
            result.trim()
        } catch (e: Exception) {
            Log.e(name, "JS Parser hatası: ${e.message}")
            null
        }
    }

    private fun atob(s: String): String {
        var str = s.trim()
        val padding = 4 - str.length % 4
        if (padding != 4) str += "=".repeat(padding)
        return Base64.decode(str, Base64.DEFAULT).toString(Charsets.ISO_8859_1)
    }

    private fun caesarShift(text: String, shift: Int): String {
        return text.map { c ->
            when {
                c in 'A'..'Z' -> ((c.code - 'A'.code + shift) % 26 + 'A'.code).toChar()
                c in 'a'..'z' -> ((c.code - 'a'.code + shift) % 26 + 'a'.code).toChar()
                else -> c
            }
        }.joinToString("")
    }

    private fun xorUnmix(text: String, accStart: Int, increment: Int): String {
        var acc = accStart
        val unmix = StringBuilder()
        for (i in text.indices) {
            val b = text[i].code
            acc = (acc + increment) % 256
            val plain = b xor acc
            acc = (acc + b) % 256
            unmix.append(plain.toChar())
        }
        return unmix.toString()
    }

    private fun tryAllDecryptors(parts: List<String>): String? {
        val decryptors = listOf(::decryptV1, ::decryptV2, ::decryptV3, ::decryptV4)
        for ((index, decryptor) in decryptors.withIndex()) {
            try {
                val result = decryptor(parts)
                if (!result.isNullOrBlank() && result.contains("http")) {
                    Log.d(name, "Fallback decryptor v${index + 1} başarılı!")
                    return result
                }
            } catch (e: Exception) {
                Log.d(name, "Fallback decryptor v${index + 1} başarısız: ${e.message}")
            }
        }
        return null
    }

    private fun decryptV1(valueParts: List<String>): String? {
        var value = valueParts.joinToString("")
        value = caesarShift(value, 9); value = caesarShift(value, 16)
        value = value.reversed()
        var decoded = atob(value); decoded = atob(decoded)
        return xorUnmix(decoded, 241, 11)
    }

    private fun decryptV2(valueParts: List<String>): String? {
        var value = valueParts.joinToString("")
        value = value.reversed(); value = caesarShift(value, 15)
        var decoded = atob(value); decoded = decoded.reversed(); decoded = atob(decoded)
        return xorUnmix(decoded, 185, 12)
    }

    private fun decryptV3(valueParts: List<String>): String? {
        var value = valueParts.joinToString("")
        var decoded = atob(value); decoded = atob(decoded)
        decoded = decoded.reversed(); decoded = caesarShift(decoded, 25); decoded = atob(decoded)
        return xorUnmix(decoded, 77, 9)
    }

    private fun decryptV4(valueParts: List<String>): String? {
        var value = valueParts.joinToString("")
        var decoded = atob(value); decoded = decoded.reversed(); decoded = atob(decoded)
        return xorUnmix(decoded, 130, 10)
    }

    private fun parseSubtitles(
        rawHtml: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val tracksMatch = Regex("""tracks:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL).find(rawHtml)
        tracksMatch?.groupValues?.get(1)?.let { tracksStr ->
            val subMatches = Regex(
                """"file"\s*:\s*"([^"]+)".*?"label"\s*:\s*"([^"]+)"""",
                RegexOption.DOT_MATCHES_ALL
            ).findAll(tracksStr).toList()

            Log.d(name, "Bulunan altyazı sayısı: ${subMatches.size}")

            subMatches.forEachIndexed { index, match ->
                var subUrl = match.groupValues[1].replace("\\/", "/").replace("\\\"", "\"")
                val subLabel = match.groupValues[2]
                if (!subUrl.startsWith("http")) {
                    subUrl = mainUrl.trimEnd('/') + (if (subUrl.startsWith("/")) "" else "/") + subUrl
                }

                val lang = when {
                    subLabel.contains("Turkish", ignoreCase = true) -> "Türkçe"
                    subLabel.contains("Forced", ignoreCase = true) -> "Forced"
                    subLabel.contains("English", ignoreCase = true) -> "İngilizce"
                    else -> "Türkçe"
                }

                Log.d(name, "Altyazı #$index - lang: '$lang', label: '$subLabel'")
                subtitleCallback.invoke(SubtitleFile(lang, subUrl))
            }
        }
    }
}

class CloseLoadTo : CloseLoadExtractor() {
    override val mainUrl = "https://closeload.filmmakinesi.to"
}

class CloseLoadFilm : CloseLoadExtractor() {
    override val mainUrl = "https://closeload.filmmakinesi.film"
}

class CloseLoadDe : CloseLoadExtractor() {
    override val mainUrl = "https://closeload.filmmakinesi.de"
}

class CloseLoadTv : CloseLoadExtractor() {
    override val mainUrl = "https://closeload.filmmakinesi.tv"
}

class CloseLoadSh : CloseLoadExtractor() {
    override val mainUrl = "https://closeload.filmmakinesi.sh"
}
