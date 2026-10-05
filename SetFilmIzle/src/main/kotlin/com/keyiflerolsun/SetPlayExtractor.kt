package com.keyiflerolsun

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject

open class SetPlay : ExtractorApi() {
    override val name            = "SetPlay"
    override val mainUrl         = "https://setplay.shop"
    override val requiresReferer = true

    private fun calcXSp(sp: String, spT: Long): String {
        val chars = "0123456789abcdefghijklmnopqrstuvwxyz"
        var num = (Math.random() * 2176782336).toLong()
        var r = ""
        while (num > 0) {
            r = chars[(num % 36).toInt()] + r
            num /= 36
        }
        if (r.isEmpty()) r = "0"

        val s = "$sp|$spT|$r"
        var t = 2166136261L
        for (char in s) {
            t = t xor char.code.toLong()
            t = (t * 16777619L) and 0xFFFFFFFFL
        }
        val hashHex = (t and 0xFFFFFFFFL).toString(16)
        return "$spT.$r.$hashHex"
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        val response = app.get(
            url = url,
            headers = mapOf(
                "User-Agent" to userAgent,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"
            ),
            referer = referer ?: "https://www.setfilmizle.ltd/"
        )
        val html = response.text

        val cerceveMatch = Regex("""SPG\.cerceve\("b2",\s*"([^"]+)",\s*"([^"]+)"\)""").find(html)
        val targetUrl = if (cerceveMatch != null) {
            try {
                val nB64 = cerceveMatch.groupValues[1]
                val oB64 = cerceveMatch.groupValues[2]
                val rBytes = Base64.decode(nB64, Base64.DEFAULT)
                val oBytes = Base64.decode(oB64, Base64.DEFAULT)
                rBytes.mapIndexed { i, byte ->
                    (byte.toInt() xor oBytes[i % oBytes.size].toInt()).toChar()
                }.joinToString("").substringBefore("|")
            } catch (_: Exception) {
                url
            }
        } else {
            url
        }

        val fastRes = app.get(
            url = targetUrl,
            headers = mapOf(
                "User-Agent" to userAgent,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"
            ),
            referer = "https://setplay.shop/"
        )
        val fastHtml = fastRes.text

        val spgMatch = Regex("""window\.SPG_A\s*=\s*(\{.*?\}|\{.*\});""").find(fastHtml)
        val spgJsonStr = spgMatch?.groupValues?.get(1)
        val (sp, spT) = if (spgJsonStr != null) {
            try {
                val obj = JSONObject(spgJsonStr)
                Pair(obj.optString("sp"), obj.optLong("spT"))
            } catch (_: Exception) {
                Pair("", 0L)
            }
        } else Pair("", 0L)

        val kopruMatch = Regex("""window\.STF_KOPRU\s*=\s*(\{.*?\}|\{.*\});""", setOf(RegexOption.DOT_MATCHES_ALL)).find(fastHtml)
        val kopruStr = kopruMatch?.groupValues?.get(1) ?: throw ErrorLoadingException("Player STF_KOPRU bulunamadı")

        val srcPath = Regex("""src:\s*"([^"]+)"""").find(kopruStr)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("M3U8 adresi bulunamadı")

        val m3uLink = if (srcPath.startsWith("http")) srcPath else "https://fastplay.mom" + srcPath

        val subtitlesStr = Regex("""subtitles:\s*(\[.*]),""", setOf(RegexOption.DOT_MATCHES_ALL)).find(kopruStr)?.groupValues?.get(1)
        if (subtitlesStr != null) {
            try {
                val array = AppUtils.parseJson<List<Map<String, Any>>>(subtitlesStr)
                array.forEach { item ->
                    val file = item["file"]?.toString() ?: return@forEach
                    val label = item["label"]?.toString() ?: item["lang"]?.toString() ?: "Tr"
                    val kind = item["kind"]?.toString() ?: ""
                    if (file.isNotEmpty() && (file.contains(".vtt") || kind == "captions")) {
                        subtitleCallback.invoke(newSubtitleFile(label, file))
                    }
                }
            } catch (e: Exception) {
                Log.e("SetPlay", "Subtitle error: ${e.message}")
            }
        }

        val xSp1 = if (sp.isNotEmpty()) calcXSp(sp, spT) else ""
        val masterText = try {
            app.get(
                url = m3uLink,
                headers = mapOf(
                    "Referer" to targetUrl,
                    "User-Agent" to userAgent,
                    "X-Sp" to xSp1
                )
            ).text
        } catch (_: Exception) {
            ""
        }

        val subUrls = mutableListOf<String>()
        if (masterText.isNotEmpty()) {
            masterText.split("\n").forEach { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("http") && (trimmed.contains("video~") || trimmed.contains("master"))) {
                    subUrls.add(trimmed)
                }
            }
        }

        var foundStream = false
        if (subUrls.isNotEmpty()) {
            subUrls.forEach { subUrl ->
                val xSp2 = if (sp.isNotEmpty()) calcXSp(sp, spT) else ""
                val subHeaders = mapOf(
                    "Referer" to targetUrl,
                    "User-Agent" to userAgent,
                    "X-Sp" to xSp2
                )
                val links = M3u8Helper.generateM3u8(
                    source = this.name,
                    streamUrl = subUrl,
                    referer = targetUrl,
                    headers = subHeaders
                )
                if (links.isNotEmpty()) {
                    foundStream = true
                    links.forEach(callback)
                }
            }
        }

        if (!foundStream) {
            val xSpFallback = if (sp.isNotEmpty()) calcXSp(sp, spT) else ""
            val fallbackHeaders = mapOf(
                "Referer" to targetUrl,
                "User-Agent" to userAgent,
                "X-Sp" to xSpFallback
            )
            val links = M3u8Helper.generateM3u8(
                source = this.name,
                streamUrl = m3uLink,
                referer = targetUrl,
                headers = fallbackHeaders
            )
            if (links.isNotEmpty()) {
                links.forEach(callback)
            } else {
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = m3uLink,
                        type = ExtractorLinkType.M3U8
                    ) {
                        quality = Qualities.Unknown.value
                        headers = fallbackHeaders
                    }
                )
            }
        }
    }
}
