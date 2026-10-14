package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

// videopark.top iki tür oynatıcı sunuyor:
//  /vip/ → WORKER_URL/api/stream|video JSON'u (hlsSource + mp4Sources)
//  /go/  → WORKER_BASE/v/{id}/info JSON'u (qualities → /v/{id}?q=)
open class VideoPark : ExtractorApi() {
    override val name            = "VideoPark"
    override val mainUrl         = "https://videopark.top"
    override val requiresReferer = true

    private data class Src(val file: String? = null, val label: String? = null)
    private data class StreamData(val hlsSource: Src? = null, val mp4Sources: List<Src>? = null)
    private data class GoInfo(val qualities: List<Int>? = null)

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val html = app.get(url, referer = referer).text
        fun jsConst(key: String) = Regex("""const\s+$key\s*=\s*"?([^";\n]*)"?;""").find(html)?.groupValues?.get(1)?.replace("\\/", "/")?.trim()?.takeIf { it.isNotEmpty() }

        jsConst("WORKER_BASE")?.trimEnd('/')?.let { base ->
            val id    = jsConst("VIDEO_ID") ?: return
            val info  = app.get("$base/v/$id/info", referer = url).parsedSafe<GoInfo>()
            val qs    = info?.qualities.orEmpty()
            Log.d("JTF_${name}", "go » $base id=$id q=$qs")
            if (qs.isEmpty()) emit("$base/v/$id", Qualities.Unknown.value, url, callback)
            else qs.forEach { emit("$base/v/$id?q=$it", it, url, callback) }
            return
        }

        val worker = jsConst("WORKER_URL")?.trimEnd('/') ?: return
        val pubId  = jsConst("PUB_ID")
        val vidId  = jsConst("VIDEO_ID")
        val api    = when {
            pubId != null -> "$worker/api/stream?pubId=$pubId&title=${jsConst("VIDEO_TITLE") ?: vidId}" +
                (jsConst("PUBLISHER_ID")?.let { "&publisherId=$it" } ?: "")
            vidId != null -> "$worker/api/video?id=$vidId"
            else          -> return
        }
        val data = app.get(api, referer = url).parsedSafe<StreamData>() ?: return
        Log.d("JTF_${name}", "vip » hls=${data.hlsSource?.file != null} mp4=${data.mp4Sources?.size}")

        data.hlsSource?.file?.let { emit(it, Qualities.Unknown.value, url, callback, ExtractorLinkType.M3U8) }
        data.mp4Sources?.forEach { s -> s.file?.let { emit(it, getQualityFromName(s.label), url, callback) } }
    }

    private suspend fun emit(link: String, q: Int, ref: String, callback: (ExtractorLink) -> Unit, type: ExtractorLinkType = ExtractorLinkType.VIDEO) {
        callback.invoke(newExtractorLink(name, name, link, type) {
            referer = ref
            quality = q
        })
    }
}
