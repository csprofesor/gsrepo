package com.gsrepo

import android.util.Base64
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI

object SinemaTVHelper {
    private const val DEFAULT_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    suspend fun resolveM3u8Streams(
        pluginName: String,
        m3u8Url: String,
        refererUrl: String = "https://sinematv.az/",
        extraHeaders: Map<String, String> = emptyMap(),
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val headers = mutableMapOf(
            "Referer" to refererUrl,
            "User-Agent" to DEFAULT_UA
        )
        headers.putAll(extraHeaders)

        try {
            val masterResp = app.get(m3u8Url, headers = headers)
            if (!masterResp.isSuccessful) return false
            val masterText = masterResp.text
            val masterLines = masterText.lines()

            val baseUri = try { URI(m3u8Url) } catch (_: Exception) { null }
            val plParam = if (m3u8Url.contains("?")) m3u8Url.substringAfter("?") else ""

            val isMaster = masterLines.any { it.contains("#EXT-X-STREAM-INF") }
            val subLinks = mutableListOf<Pair<String, String>>()

            if (isMaster) {
                var currentQuality = "1080p"
                for (line in masterLines) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("#EXT-X-STREAM-INF")) {
                        val nameMatch = Regex("""NAME=([^\s,]+)""", RegexOption.IGNORE_CASE).find(trimmed)
                        val resMatch = Regex("""RESOLUTION=\d+x(\d+)""", RegexOption.IGNORE_CASE).find(trimmed)
                        currentQuality = nameMatch?.groupValues?.get(1)?.replace("\"", "")
                            ?: resMatch?.groupValues?.get(1)?.let { "${it}p" }
                            ?: "1080p"
                    } else if (trimmed.isNotBlank() && !trimmed.startsWith("#")) {
                        val fullSubUrl = when {
                            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
                            baseUri != null -> {
                                try { baseUri.resolve(trimmed).toString() } catch (_: Exception) { trimmed }
                            }
                            else -> trimmed
                        }

                        val finalSubUrl = if (plParam.isNotBlank() && !fullSubUrl.contains("?")) {
                            "$fullSubUrl?$plParam"
                        } else {
                            fullSubUrl
                        }

                        subLinks.add(currentQuality to finalSubUrl)
                    }
                }
            } else {
                subLinks.add("1080p" to m3u8Url)
            }

            for ((qualityStr, subUrl) in subLinks) {
                try {
                    val subResp = app.get(subUrl, headers = headers)
                    if (subResp.isSuccessful) {
                        val subText = subResp.text
                        val subBaseUri = try { URI(subUrl) } catch (_: Exception) { null }
                        val fixedSubLines = subText.lines().map { l ->
                            val t = l.trim()
                            if (!t.startsWith("#") && t.isNotBlank()) {
                                if (t.startsWith("http://") || t.startsWith("https://")) {
                                    t
                                } else if (subBaseUri != null) {
                                    try { subBaseUri.resolve(t).toString() } catch (_: Exception) { t }
                                } else {
                                    t
                                }
                            } else {
                                t
                            }
                        }
                        val fixedSubText = fixedSubLines.joinToString("\n")
                        val b64 = Base64.encodeToString(
                            fixedSubText.toByteArray(Charsets.UTF_8),
                            Base64.NO_WRAP
                        )
                        val dataUri = "data:application/vnd.apple.mpegurl;base64,$b64"

                        val qualityInt = qualityStr.replace("p", "").toIntOrNull() ?: Qualities.P1080.value

                        callback.invoke(
                            newExtractorLink(
                                source = pluginName,
                                name = "$pluginName ($qualityStr)",
                                url = dataUri,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.quality = qualityInt
                                this.headers = headers
                            }
                        )
                        found = true
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return found
    }
}
