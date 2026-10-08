package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.fixUrl
import com.lagradost.cloudstream3.utils.newExtractorLink

class DizipalPlayer : ExtractorApi() {
    override var name = "DizipalPlayer"
    override var mainUrl = "dplayer82.site"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val response = app.get(url, referer = referer).text

            val subUrls = mutableSetOf<String>()
            Regex(""""file":"((?:\\\\\"|[^"])+)","label":"((?:\\\\\"|[^"])+)"""").findAll(response).forEach {
                val (subUrlExt, subLangExt) = it.destructured
                val subUrl = subUrlExt.replace("\\/", "/").replace("\\u0026", "&").replace("\\", "")
                val subLang = subLangExt.replace("\\u0131", "ı").replace("\\u0130", "İ").replace("\\u00fc", "ü").replace("\\u00e7", "ç").replace("\\u011f", "ğ").replace("\\u015f", "ş")

                if (subUrl !in subUrls) {
                    subUrls.add(subUrl)
                    subtitleCallback.invoke(
                        newSubtitleFile(lang = subLang, url = fixUrl(subUrl)) {
                            headers = mapOf(
                                "Referer" to url,
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                            )
                        }
                    )
                }
            }

            val fileMatches = Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").findAll(response)
                .map { it.groupValues[1] }.toList()
                .ifEmpty {
                    Regex("""https?://[a-zA-Z0-9.-]+/[a-zA-Z0-9._/,-]+\.m3u8[a-zA-Z0-9._/,-]*""").findAll(response)
                        .map { it.value }.toList()
                }

            if (fileMatches.isNotEmpty()) {
                fileMatches.distinct().forEach { rawFile ->
                    var fileUrl = rawFile.replace("\\/", "/")
                    if (fileUrl.startsWith("//")) fileUrl = "https:$fileUrl"
                    else if (!fileUrl.startsWith("http")) fileUrl = "https://$fileUrl"

                    val domain = Regex("""https?://[^/]+""").find(url)?.value ?: mainUrl
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "DiziPal (HLS)",
                            url = fileUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            headers = mapOf(
                                "Origin" to domain,
                                "Referer" to url,
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                            )
                            Qualities.Unknown.value
                        }
                    )
                }
                return
            }

            val openPlayerRegex = """window\.openPlayer\s*\(\s*['"]([^'"]+)['"]""".toRegex()
            val playlistId = openPlayerRegex.find(response)?.groupValues?.get(1)
            if (playlistId != null) {
                val domain = Regex("""https?://[^/]+""").find(url)?.value ?: "https://dplayer82.site"
                val apiUrl = "$domain/source2.php?v=$playlistId"
                val apiResponse = app.get(apiUrl, referer = url).text

                val apiMatches = Regex(""""file"\s*:\s*"([^"]+)"""").findAll(apiResponse)
                apiMatches.forEach { matchResult ->
                    var fileUrl = matchResult.groupValues[1].replace("\\/", "/")
                    if (fileUrl.startsWith("//")) fileUrl = "https:$fileUrl"
                    else if (!fileUrl.startsWith("http")) fileUrl = "https://$fileUrl"

                    if (fileUrl.contains("m.php")) fileUrl = fileUrl.replace("m.php", "master.m3u8")

                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "DPlayer (Auto)",
                            url = fileUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            headers = mapOf("Origin" to domain, "Referer" to url)
                            Qualities.Unknown.value
                        }
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("DiziPal", "DizipalPlayer Extractor Hata: ${e.message}")
        }
    }
}
