// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty

open class Odnoklassniki : ExtractorApi() {
    override val name            = "Odnoklassniki"
    override val mainUrl         = "https://odnoklassniki.ru"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        Log.d("Kekik_${this.name}", "url » $url")

        val userAgent = mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36")

        val videoReq = app.get(url, headers=userAgent).text
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("\\\\", "\\")
            .replace(Regex("\\\\u([0-9A-Fa-f]{4})")) { matchResult ->
                Integer.parseInt(matchResult.groupValues[1], 16).toChar().toString()
            }

        val hlsMatch = Regex(""""hlsManifestUrl":\s*"([^"]+)"""").find(videoReq)
        if (hlsMatch != null) {
            val hlsUrl = hlsMatch.groupValues[1].replace("\\u0026", "&").replace("\\u003d", "=")
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = "${this.name} HLS",
                    url = hlsUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    headers = userAgent
                    this.quality = Qualities.P720.value
                }
            )
        }

        val videosStr = Regex(""""videos":(\[[^]]*])""").find(videoReq)?.groupValues?.get(1) ?: return
        val videos    = AppUtils.tryParseJson<List<OkRuVideo>>(videosStr) ?: return

        for (video in videos) {
            Log.d("Kekik_${this.name}", "video » $video")

            var videoUrl = if (video.url.startsWith("//")) "https:${video.url}" else video.url
            videoUrl = videoUrl.replace("\\u0026", "&").replace("\\u003d", "=")

            val quality   = video.name.uppercase()
                .replace("MOBILE", "144p")
                .replace("LOWEST", "240p")
                .replace("LOW",    "360p")
                .replace("SD",     "480p")
                .replace("HD",     "720p")
                .replace("FULL",   "1080p")
                .replace("QUAD",   "1440p")
                .replace("ULTRA",  "4k")

            callback.invoke(
              newExtractorLink(
                source = this.name,
                name = this.name,
                url = videoUrl,
                type = INFER_TYPE
            ) {
                headers = userAgent
                this.quality = getQualityFromName(quality) // `Int` olarak ayarlandı
              /**
              * varsayılan olarak false olması gerekiyor şimdilik böyle kalsın ve ellemeyelim 
              * isM3u8 = false
              */
        }
    )
        }
    }

    data class OkRuVideo(
        @JsonProperty("name") val name: String,
        @JsonProperty("url")  val url: String,
    )
}
