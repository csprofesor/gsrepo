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
    override var mainUrl = "dizipal.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val reqHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Referer" to (referer ?: "https://dizipal2137.com/")
            )
            val response = app.get(url, headers = reqHeaders).text
            extractFromHtml(url, referer, response, subtitleCallback, callback)
        } catch (e: Exception) {
            Log.e("DiziPal", "DizipalPlayer Extractor Hata: ${e.message}")
        }
    }

    suspend fun extractFromHtml(
        url: String,
        referer: String?,
        html: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val subUrls = mutableSetOf<String>()

            // 1. Extract tracks from JS setup/objects: file: "...", label: "..."
            val trackRegex = Regex("""(?:file|src)\s*:\s*["']([^"']+\.(?:vtt|srt)[^"']*)["'](?:\s*,\s*label\s*:\s*["']([^"']+)["'])?""")
            trackRegex.findAll(html).forEach { match ->
                val rawSubUrl = match.groupValues[1]
                val rawLabel = match.groupValues[2].ifBlank {
                    if (rawSubUrl.contains("_tur") || rawSubUrl.contains("/tr") || rawSubUrl.contains("turkish")) "Türkçe"
                    else if (rawSubUrl.contains("_eng") || rawSubUrl.contains("/en") || rawSubUrl.contains("english")) "English"
                    else "Altyazı"
                }

                var subUrl = rawSubUrl.replace("\\/", "/").replace("\\u0026", "&").replace("\\", "")
                if (subUrl.startsWith("//")) subUrl = "https:$subUrl"
                else if (!subUrl.startsWith("http")) subUrl = "https://$subUrl"

                val subLang = rawLabel
                    .replace("\\u0131", "ı")
                    .replace("\\u0130", "İ")
                    .replace("\\u00fc", "ü")
                    .replace("\\u00e7", "ç")
                    .replace("\\u011f", "ğ")
                    .replace("\\u015f", "ş")

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

            // 2. Extract tracks from JSON format: "file":"...","label":"..."
            val jsonSubRegex = Regex(""""file"\s*:\s*"([^"]+\.(?:vtt|srt)[^"]*)"(?:\s*,\s*"label"\s*:\s*"([^"]+)")?""")
            jsonSubRegex.findAll(html).forEach { match ->
                val rawSubUrl = match.groupValues[1]
                val rawLabel = match.groupValues[2].ifBlank { "Türkçe" }

                var subUrl = rawSubUrl.replace("\\/", "/").replace("\\u0026", "&").replace("\\", "")
                if (subUrl.startsWith("//")) subUrl = "https:$subUrl"
                else if (!subUrl.startsWith("http")) subUrl = "https://$subUrl"

                val subLang = rawLabel
                    .replace("\\u0131", "ı")
                    .replace("\\u0130", "İ")
                    .replace("\\u00fc", "ü")
                    .replace("\\u00e7", "ç")
                    .replace("\\u011f", "ğ")
                    .replace("\\u015f", "ş")

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

            val domain = Regex("""https?://[^/]+""").find(url)?.value ?: "https://dizipal2137.com"

            // Stream Extraction - M3U8
            val fileMatches = Regex("""(?:file|src)\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").findAll(html)
                .map { it.groupValues[1] }.toList()
                .ifEmpty {
                    Regex("""https?://[^\s"']+\.m3u8[^\s"']*""").findAll(html)
                        .map { it.value }.toList()
                }

            if (fileMatches.isNotEmpty()) {
                fileMatches.distinct().forEach { rawFile ->
                    var fileUrl = rawFile.replace("\\/", "/")
                    if (fileUrl.startsWith("//")) fileUrl = "https:$fileUrl"
                    else if (!fileUrl.startsWith("http")) fileUrl = "https://$fileUrl"

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

            // Stream Extraction - MP4
            val mp4Matches = Regex("""(?:file|src)\s*:\s*["']([^"']+\.mp4[^"']*)["']""").findAll(html)
                .map { it.groupValues[1] }.toList()
                .ifEmpty {
                    Regex("""https?://[^\s"']+\.mp4[^\s"']*""").findAll(html)
                        .map { it.value }.toList()
                }

            if (mp4Matches.isNotEmpty()) {
                mp4Matches.distinct().forEach { rawFile ->
                    var fileUrl = rawFile.replace("\\/", "/")
                    if (fileUrl.startsWith("//")) fileUrl = "https:$fileUrl"
                    else if (!fileUrl.startsWith("http")) fileUrl = "https://$fileUrl"

                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "DiziPal (MP4)",
                            url = fileUrl,
                            type = ExtractorLinkType.VIDEO
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

            // Fallback for openPlayer / source2.php
            val openPlayerRegex = """window\.openPlayer\s*\(\s*['"]([^'"]+)['"]""".toRegex()
            val playlistId = openPlayerRegex.find(html)?.groupValues?.get(1)
            if (playlistId != null) {
                val dplayerDomain = Regex("""https?://[^/]+""").find(url)?.value ?: "https://dplayer82.site"
                val apiUrl = "$dplayerDomain/source2.php?v=$playlistId"
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
                            headers = mapOf(
                                "Origin" to dplayerDomain,
                                "Referer" to url,
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                            )
                            Qualities.Unknown.value
                        }
                    )
                }
                return
            }

            // Fallback for nested iframe
            val iframeSrc = Regex("""<iframe[^>]+src=["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            if (!iframeSrc.isNullOrBlank() && iframeSrc != url) {
                var nestedUrl = iframeSrc.replace("\\/", "/")
                if (nestedUrl.startsWith("//")) nestedUrl = "https:$nestedUrl"
                else if (!nestedUrl.startsWith("http")) nestedUrl = "https://$nestedUrl"

                getUrl(nestedUrl, url, subtitleCallback, callback)
            }
        } catch (e: Exception) {
            Log.e("DiziPal", "DizipalPlayer Extractor Hata: ${e.message}")
        }
    }
}
