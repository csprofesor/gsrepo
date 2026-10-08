// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.extractors.helper.AesHelper

open class CizgiDuo : ExtractorApi() {
    override var name            = "CizgiDuo"
    override var mainUrl         = "https://cizgiduo.online"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val m3uLink:String?
        val extRef  = referer ?: ""
        val iSource = app.get(url, referer=extRef).text

        val bePlayer     = Regex("""bePlayer\('([^']+)',\s*'(\{[^}]+\})'\);""").find(iSource)?.groupValues ?: return
        val bePlayerPass = bePlayer.getOrNull(1) ?: return
        val bePlayerData = bePlayer.getOrNull(2) ?: return
        val encrypted    = AesHelper.cryptoAESHandler(bePlayerData, bePlayerPass.toByteArray(), false)?.replace("\\", "") ?: return
        Log.d("Kekik_${this.name}", "encrypted » $encrypted")

        m3uLink = Regex("""video_location":"([^"]+)""").find(encrypted)?.groupValues?.get(1) ?: return

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = m3uLink,
                type = ExtractorLinkType.M3U8 // Tür olarak M3U8'yi belirtiyoruz
            ) {
                quality = Qualities.Unknown.value // Varsayılan kalite ayarlandı
                /* referer = url // bunun yerine headers kodunu ekledim */
                headers = mapOf("Referer" to url) // Referer burada başlıklar üzerinden ayarlandı
                /* site açılmıyor şu anda o yüzden hata vermemesi için bunu kapatıyorum isM3u8 = true */
            }
        )
    }
}
