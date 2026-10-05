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

    private fun calcXSp(sp: String, spT: Long, offsetSeconds: Long = 0): String {
        val chars = "0123456789abcdefghijklmnopqrstuvwxyz"
        var num = (Math.random() * 2176782336).toLong()
        var r = ""
        while (num > 0) {
            r = chars[(num % 36).toInt()] + r
            num /= 36
        }
        if (r.isEmpty()) r = "0"

        val tNow = spT + offsetSeconds
        val s = "$sp|$tNow|$r"
        var t = 2166136261L
        for (char in s) {
            t = t xor char.code.toLong()
            t = (t * 16777619L) and 0xFFFFFFFFL
        }
        val hashHex = (t and 0xFFFFFFFFL).toString(16)
        return "$tNow.$r.$hashHex"
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        val startTime = System.currentTimeMillis()

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

        fun elapsedSec(): Long = (System.currentTimeMillis() - startTime) / 1000

        val xSpNow = if (sp.isNotEmpty()) calcXSp(sp, spT, elapsedSec()) else ""
        val masterText = try {
            app.get(
                url = m3uLink,
                headers = mapOf(
                    "Referer" to targetUrl,
                    "User-Agent" to userAgent,
                    "X-Sp" to xSpNow
                )
            ).text
        } catch (_: Exception) {
            ""
        }

        val streams = mutableListOf<Pair<String, Int>>()

        if (masterText.isNotEmpty()) {
            if (masterText.contains("#EXTINF")) {
                val qual = getQualityFromUrl(m3uLink)
                streams.add(Pair(m3uLink, qual))
            } else {
                val lines = masterText.split("\n")
                var lastQuality = Qualities.Unknown.value

                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) continue

                    if (trimmed.startsWith("#EXT-X-STREAM-INF")) {
                        val resMatch = Regex("""RESOLUTION=\d+x(\d+)""", RegexOption.IGNORE_CASE).find(trimmed)
                        if (resMatch != null) {
                            val height = resMatch.groupValues[1].toIntOrNull()
                            if (height != null) {
                                lastQuality = height
                            }
                        }
                    } else if (!trimmed.startsWith("#")) {
                        val absoluteUrl = when {
                            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
                            trimmed.startsWith("/") -> "https://fastplay.mom$trimmed"
                            else -> m3uLink.substringBeforeLast("/") + "/" + trimmed
                        }

                        val quality = if (lastQuality != Qualities.Unknown.value) {
                            lastQuality
                        } else {
                            getQualityFromUrl(absoluteUrl)
                        }

                        streams.add(Pair(absoluteUrl, quality))
                        lastQuality = Qualities.Unknown.value
                    }
                }
            }
        }

        if (streams.isNotEmpty()) {
            streams.forEach { (streamUrl, quality) ->
                val currentElapsed = elapsedSec()
                val xSpFuture = if (sp.isNotEmpty()) calcXSp(sp, spT, currentElapsed) else ""
                val headersMap = mapOf(
                    "Referer" to targetUrl,
                    "User-Agent" to userAgent,
                    "X-Sp" to xSpFuture
                )

                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = streamUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.quality = quality
                        this.headers = headersMap
                    }
                )
            }
        } else {
            val currentElapsed = elapsedSec()
            val xSpFallback = if (sp.isNotEmpty()) calcXSp(sp, spT, currentElapsed) else ""
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = m3uLink,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf(
                        "Referer" to targetUrl,
                        "User-Agent" to userAgent,
                        "X-Sp" to xSpFallback
                    )
                }
            )
        }
    }

    private fun getQualityFromUrl(url: String): Int {
        return when {
            url.contains("1080") -> Qualities.P1080.value
            url.contains("720") -> Qualities.P720.value
            url.contains("480") -> Qualities.P480.value
            url.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }
}
