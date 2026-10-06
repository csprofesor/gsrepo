package com.gsrepo

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.helper.AesHelper
import com.lagradost.cloudstream3.utils.*

open class HotStream : ExtractorApi() {
    override var name            = "HotStream"
    override var mainUrl         = "https://hotstream.club"
    override val requiresReferer = true

    companion object {
        private const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val extRef  = referer ?: mainUrl
        val iSource = app.get(
            url,
            referer = extRef,
            headers = mapOf("User-Agent" to DESKTOP_UA)
        ).text

        val bePlayer = Regex("""bePlayer\s*\(\s*['"]([^'"]+)['"]\s*,\s*['"](\{[^}]+\})['"]\s*\)""").find(iSource)?.groupValues
        if (bePlayer != null) {
            val bePlayerPass = bePlayer[1]
            val bePlayerData = bePlayer[2]
            val decrypted    = AesHelper.cryptoAESHandler(bePlayerData, bePlayerPass.toByteArray(), false)?.replace("\\", "")
            Log.d("HotStream", "decrypted » $decrypted")

            val m3uLink = Regex("""(?:video_location|file|src)":"([^"]+)""").find(decrypted ?: "")?.groupValues?.get(1)?.replace("\\/", "/")
            if (!m3uLink.isNullOrBlank()) {
                val streamHeaders = mapOf(
                    "User-Agent" to DESKTOP_UA,
                    "Referer" to url,
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "*/*"
                )

                try {
                    val masterResponse = app.get(m3uLink, headers = streamHeaders).text
                    val lines = masterResponse.split("\n")
                    var foundStreams = false

                    for (i in lines.indices) {
                        val line = lines[i].trim()
                        if (line.startsWith("#EXT-X-STREAM-INF")) {
                            val heightMatch = Regex("""RESOLUTION=\d+x(\d+)""").find(line)
                            val quality = heightMatch?.groupValues?.get(1)?.toIntOrNull() ?: Qualities.Unknown.value

                            val streamUrl = lines.getOrNull(i + 1)?.trim()
                            if (!streamUrl.isNullOrBlank() && streamUrl.startsWith("http")) {
                                foundStreams = true
                                callback.invoke(
                                    newExtractorLink(
                                        source  = this.name,
                                        name    = this.name,
                                        url     = streamUrl,
                                        type    = ExtractorLinkType.M3U8
                                    ) {
                                        headers = streamHeaders
                                        this.quality = quality
                                    }
                                )
                            }
                        }
                    }

                    if (!foundStreams) {
                        callback.invoke(
                            newExtractorLink(
                                source  = this.name,
                                name    = this.name,
                                url     = m3uLink,
                                type    = ExtractorLinkType.M3U8
                            ) {
                                headers = streamHeaders
                                quality = Qualities.Unknown.value
                            }
                        )
                    }
                } catch (e: Exception) {
                    Log.e("HotStream", "Error fetching master M3U8: ${e.message}")
                    callback.invoke(
                        newExtractorLink(
                            source  = this.name,
                            name    = this.name,
                            url     = m3uLink,
                            type    = ExtractorLinkType.M3U8
                        ) {
                            headers = streamHeaders
                            quality = Qualities.Unknown.value
                        }
                    )
                }
            }

            val trackMatches = Regex("""(?:file|src)":"([^"]+)".*?label":"([^"]+)"""").findAll(decrypted ?: "")
            for (match in trackMatches) {
                val subUrl   = match.groupValues[1].replace("\\/", "/")
                val subLabel = match.groupValues[2]
                if (subUrl.isNotBlank() && (subUrl.endsWith(".vtt") || subUrl.endsWith(".srt"))) {
                    subtitleCallback.invoke(
                        newSubtitleFile(
                            lang = subLabel,
                            url  = fixUrl(subUrl)
                        )
                    )
                }
            }
        } else {
            val m3uLink = Regex("""file:\s*"([^"]+)"""").find(iSource)?.groupValues?.get(1)
                ?: Regex("""file:\s*'([^']+)'""").find(iSource)?.groupValues?.get(1)
            if (!m3uLink.isNullOrBlank()) {
                callback.invoke(
                    newExtractorLink(
                        source  = this.name,
                        name    = this.name,
                        url     = m3uLink,
                        type    = ExtractorLinkType.M3U8
                    ) {
                        headers = mapOf(
                            "User-Agent" to DESKTOP_UA,
                            "Referer" to url,
                            "X-Requested-With" to "XMLHttpRequest"
                        )
                        quality = Qualities.Unknown.value
                    }
                )
            }
        }
    }
}
