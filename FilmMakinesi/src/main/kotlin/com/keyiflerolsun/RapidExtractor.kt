package com.keyiflerolsun

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

open class RapidExtractor : ExtractorApi() {
    override val mainUrl = "https://rapid.filmmakinesi.to"
    override val name = "Rapid"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d(name, "getUrl çağrıldı, url: $url")

        val domain = Regex("""(https?://[^/]+)""").find(url)?.groupValues?.get(1) ?: mainUrl
        val response = app.get(url, referer = referer ?: domain)
        val rawHtml = response.text
        var cookies = response.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        Log.d(name, "Raw HTML uzunluğu: ${rawHtml.length}")

        var videoUrl: String? = null

        val unpackedJs = try { getAndUnpack(rawHtml) } catch (e: Exception) { null }
        val searchHtml = unpackedJs ?: rawHtml

        val directMatch = Regex("""(https?://[^"'\s]+\.(?:m3u8|txt)[^"'\s]*)""").find(searchHtml)
            ?: Regex("""(https?://[^"'\s]+\.(?:m3u8|txt)[^"'\s]*)""").find(rawHtml)
        if (directMatch != null) {
            videoUrl = directMatch.groupValues[1].replace("\\/", "/")
            Log.d(name, "Direkt m3u8/txt bulundu: $videoUrl")
        }

        if (videoUrl.isNullOrBlank()) {
            val atobMatch = Regex("""aHR0[0-9a-zA-Z+\/=]+""").find(searchHtml) ?: Regex("""aHR0[0-9a-zA-Z+\/=]+""").find(rawHtml)
            if (atobMatch != null) {
                var atob = atobMatch.value
                val padding = atob.length % 4
                if (padding != 0) {
                    atob += "=".repeat(4 - padding)
                }
                try {
                    videoUrl = String(Base64.decode(atob, Base64.DEFAULT), Charsets.UTF_8)
                    Log.d(name, "Atob decoded URL: $videoUrl")
                } catch (e: Exception) {
                    Log.w(name, "Atob decode hatası: ${e.message}")
                }
            }
        }

        if (videoUrl.isNullOrBlank()) {
            val fileMatch = Regex(""""file"\s*:\s*"([^"]+)"""").find(searchHtml) ?: Regex(""""file"\s*:\s*"([^"]+)"""").find(rawHtml)
            if (fileMatch != null) {
                videoUrl = fileMatch.groupValues[1].replace("\\/", "/")
                Log.d(name, "File match URL: $videoUrl")
            }
        }

        videoUrl = when {
            videoUrl.isNullOrBlank() -> null
            videoUrl.startsWith("//") -> "https:$videoUrl"
            videoUrl.startsWith("http") -> videoUrl
            else -> domain.trimEnd('/') + "/" + videoUrl.trimStart('/')
        }

        if (videoUrl.isNullOrBlank()) {
            Log.e(name, "Video URL bulunamadı!")
            return
        }

        val ajaxHashMatch = Regex("""hash\s*:\s*["']([^"']+)["']""").find(searchHtml)
        val ajaxUrlMatch = Regex("""url\s*:\s*["']([^"']+ah/)["']""").find(searchHtml) ?: Regex("""url\s*:\s*["']([^"']+)["']""").find(searchHtml)
        
        if (ajaxHashMatch != null && ajaxUrlMatch != null) {
            val ajaxUrl = ajaxUrlMatch.groupValues[1]
            val ajaxHash = ajaxHashMatch.groupValues[1]
            val fullAjaxUrl = if (ajaxUrl.startsWith("http")) ajaxUrl else domain.trimEnd('/') + "/" + ajaxUrl.trimStart('/')
            Log.d(name, "AJAX POST yapılıyor: $fullAjaxUrl hash=$ajaxHash")
            try {
                val ajaxRes = app.post(
                    url = fullAjaxUrl,
                    data = mapOf("hash" to ajaxHash),
                    headers = mapOf(
                        "Referer" to "$domain/",
                        "Origin" to domain,
                        "X-Requested-With" to "XMLHttpRequest",
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                        if (cookies.isNotBlank()) "Cookie" to cookies else "" to ""
                    ).filter { it.key.isNotBlank() }
                )
                Log.d(name, "AJAX Response: ${ajaxRes.code} - ${ajaxRes.text}")
                val newCookies = ajaxRes.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
                if (newCookies.isNotBlank()) {
                    cookies = if (cookies.isNotBlank()) "$cookies; $newCookies" else newCookies
                }
            } catch (e: Exception) {
                Log.w(name, "AJAX POST hatası: ${e.message}")
            }
        }

        parseSubtitles(rawHtml, subtitleCallback)

        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = videoUrl,
                type = INFER_TYPE
            ) {
                this.referer = domain + "/"
                this.headers = mapOf(
                    "Origin" to domain,
                    "Referer" to (domain + "/"),
                    "Accept" to "*/*",
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                    if (cookies.isNotBlank()) "Cookie" to cookies else "" to ""
                ).filter { it.key.isNotBlank() }
            }
        )
        Log.d(name, "ExtractorLink eklendi: $videoUrl, Cookies: $cookies")
    }

    private fun parseSubtitles(
        rawHtml: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val tracksMatch = Regex("""tracks:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL).find(rawHtml)
        tracksMatch?.groupValues?.get(1)?.let { tracksStr ->
            val subMatches = Regex(
                """"file"\s*:\s*"([^"]+)".*?"label"\s*:\s*"([^"]+)"""",
                RegexOption.DOT_MATCHES_ALL
            ).findAll(tracksStr).toList()

            subMatches.forEachIndexed { _, match ->
                var subUrl = match.groupValues[1].replace("\\/", "/").replace("\\\"", "\"")
                val subLabel = match.groupValues[2]
                if (!subUrl.startsWith("http")) {
                    subUrl = mainUrl.trimEnd('/') + (if (subUrl.startsWith("/")) "" else "/") + subUrl
                }

                val lang = when {
                    subLabel.contains("Turkish", ignoreCase = true) -> "Türkçe"
                    subLabel.contains("Forced", ignoreCase = true) -> "Forced"
                    subLabel.contains("English", ignoreCase = true) -> "İngilizce"
                    else -> "Türkçe"
                }

                subtitleCallback.invoke(SubtitleFile(lang, subUrl))
            }
        }
    }
}

class RapidTo : RapidExtractor() {
    override val mainUrl = "https://rapid.filmmakinesi.to"
}

class RapidFilm : RapidExtractor() {
    override val mainUrl = "https://rapid.filmmakinesi.film"
}

class RapidDe : RapidExtractor() {
    override val mainUrl = "https://rapid.filmmakinesi.de"
}

class RapidTv : RapidExtractor() {
    override val mainUrl = "https://rapid.filmmakinesi.tv"
}

class RapidSh : RapidExtractor() {
    override val mainUrl = "https://rapid.filmmakinesi.sh"
}
