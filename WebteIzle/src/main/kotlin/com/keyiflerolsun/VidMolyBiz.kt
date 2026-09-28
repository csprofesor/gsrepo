package com.keyiflerolsun

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class VidMolyBiz : ExtractorApi() {
    override val name = "VidMoly"
    override val mainUrl = "https://vidmoly.biz"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val res = app.get(url, referer = referer).text
        val m3u8Match = Regex("""file\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""").find(res)
        if (m3u8Match != null) {
            callback.invoke(
                newExtractorLink(
                    name = this.name,
                    source = this.name,
                    url = m3u8Match.groupValues[1],
                    type = ExtractorLinkType.M3U8,
                ) {
                    this.referer = url
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    }
}

class RubyVidHub : ExtractorApi() {
    override val name = "RubyVidHub"
    override val mainUrl = "https://rubyvidhub.com"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val res = app.get(url, referer = referer).text
        val m3u8Match = Regex("""file\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""").find(res)
        if (m3u8Match != null) {
            callback.invoke(
                newExtractorLink(
                    name = this.name,
                    source = this.name,
                    url = m3u8Match.groupValues[1],
                    type = ExtractorLinkType.M3U8,
                ) {
                    this.referer = url
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    }
}
