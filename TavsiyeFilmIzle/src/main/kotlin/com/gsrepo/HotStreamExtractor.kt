package com.gsrepo

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.helper.AesHelper
import com.lagradost.cloudstream3.utils.*

open class HotStream : ExtractorApi() {
    override var name            = "HotStream"
    override var mainUrl         = "https://hotstream.club"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val extRef  = referer ?: mainUrl
        val iSource = app.get(url, referer = extRef).text

        val bePlayer = Regex("""bePlayer\('([^']+)',\s*'(\{[^}]+\})'\);""").find(iSource)?.groupValues
        if (bePlayer != null) {
            val bePlayerPass = bePlayer[1]
            val bePlayerData = bePlayer[2]
            val decrypted    = AesHelper.cryptoAESHandler(bePlayerData, bePlayerPass.toByteArray(), false)?.replace("\\", "")
            Log.d("HotStream", "decrypted » $decrypted")

            val m3uLink = Regex("""(?:video_location|file)":"([^"]+)""").find(decrypted ?: "")?.groupValues?.get(1)
            if (!m3uLink.isNullOrBlank()) {
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name   = this.name,
                        url    = m3uLink,
                        type   = ExtractorLinkType.M3U8
                    ) {
                        headers = mapOf("Referer" to url)
                        quality = Qualities.Unknown.value
                    }
                )
            }

            val trackMatches = Regex("""(?:file|src)":"([^"]+)".*?label":"([^"]+)"""").findAll(decrypted ?: "")
            for (match in trackMatches) {
                val subUrl   = match.groupValues[1]
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
                        source = this.name,
                        name   = this.name,
                        url    = m3uLink,
                        type   = ExtractorLinkType.M3U8
                    ) {
                        headers = mapOf("Referer" to url)
                        quality = Qualities.Unknown.value
                    }
                )
            }
        }
    }
}
