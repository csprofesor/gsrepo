package com.keyiflerolsun

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup

open class RapidExtractor : ExtractorApi() {
    override val mainUrl = "https://rapid.filmmakinesi.to"
    override val name = "Rapid"
    override val requiresReferer = true

    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor by lazy { CloudflareInterceptor(cloudflareKiller) }

    private class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request  = chain.request()
            val response = chain.proceed(request)
            val body     = response.peekBody(1024 * 1024).string()
            val doc      = Jsoup.parse(body)

            if (response.code == 403 || response.code == 503 ||
                response.header("cf-mitigated") != null ||
                body.contains("Just a moment", ignoreCase = true) ||
                body.contains("Checking your browser", ignoreCase = true) ||
                body.contains("cf-challenge", ignoreCase = true) ||
                body.contains("turnstile", ignoreCase = true) ||
                doc.title().contains("Just a moment", ignoreCase = true) ||
                doc.title().contains("Attention Required", ignoreCase = true)
            ) {
                return cloudflareKiller.intercept(chain)
            }

            return response
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d(name, "getUrl çağrıldı, url: $url")

        val domain = Regex("""(https?://[^/]+)""").find(url)?.groupValues?.get(1) ?: mainUrl
        val response = try {
            app.get(url, referer = referer ?: domain, interceptor = interceptor)
        } catch (e: Exception) {
            Log.e(name, "Embed GET hatası: ${e.message}")
            return
        }

        val rawHtml = response.text
        var cookies = response.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        Log.d(name, "Raw HTML uzunluğu: ${rawHtml.length}")

        val unpackedJs = try { getAndUnpack(rawHtml) } catch (e: Exception) { null }
        val searchHtml = unpackedJs ?: rawHtml

        // AJAX Hash authorization & Video URL retrieval (Must happen before extracting final video URL)
        val ajaxHash = Regex(""""hash"\s*:\s*"([^"]+)"""").find(searchHtml)?.groupValues?.get(1)
            ?: Regex("""hash\s*:\s*['"]([a-zA-Z0-9]{32})['"]""").find(searchHtml)?.groupValues?.get(1)
            ?: Regex(""""hash"\s*:\s*"([^"]+)"""").find(rawHtml)?.groupValues?.get(1)

        val ajaxPath = Regex(""""url"\s*:\s*"([^"]+ah/)"\s*""").find(searchHtml)?.groupValues?.get(1)
            ?: Regex("""url\s*:\s*['"]([^'"]+ah/)['"]""").find(searchHtml)?.groupValues?.get(1)
            ?: "/video/ah/"

        var videoUrl: String? = null

        if (ajaxHash != null) {
            val fullAjaxUrl = if (ajaxPath.startsWith("http")) ajaxPath else domain.trimEnd('/') + "/" + ajaxPath.trimStart('/')
            Log.d(name, "AJAX POST yapılıyor: $fullAjaxUrl hash=$ajaxHash")
            try {
                val ajaxRes = app.post(
                    url = fullAjaxUrl,
                    data = mapOf("hash" to ajaxHash),
                    headers = mapOf(
                        "Referer" to url,
                        "Origin" to domain,
                        "X-Requested-With" to "XMLHttpRequest",
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                        if (cookies.isNotBlank()) "Cookie" to cookies else "" to ""
                    ).filter { it.key.isNotBlank() },
                    interceptor = interceptor
                )
                Log.d(name, "AJAX Response: ${ajaxRes.code} - ${ajaxRes.text}")
                val newCookies = ajaxRes.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
                if (newCookies.isNotBlank()) {
                    cookies = if (cookies.isNotBlank()) "$cookies; $newCookies" else newCookies
                }

                val ajaxText = ajaxRes.text
                val jsonMatch = Regex(""""(?:file|url|hls|source|securedLink)"\s*:\s*"([^"]+)"""").find(ajaxText)
                if (jsonMatch != null) {
                    videoUrl = jsonMatch.groupValues[1].replace("\\/", "/")
                    Log.d(name, "AJAX Response'dan video URL bulundu: $videoUrl")
                } else {
                    val urlMatch = Regex("""(https?://[^"'\s]+\.(?:m3u8|txt|mp4)[^"'\s]*)""").find(ajaxText)
                    if (urlMatch != null) {
                        videoUrl = urlMatch.groupValues[1].replace("\\/", "/")
                        Log.d(name, "AJAX Response'dan direkt URL bulundu: $videoUrl")
                    }
                }
            } catch (e: Exception) {
                Log.w(name, "AJAX POST hatası: ${e.message}")
            }
        }

        if (videoUrl.isNullOrBlank()) {
            val directMatch = Regex("""(https?://[^"'\s]+\.(?:m3u8|txt|mp4)[^"'\s]*)""").find(searchHtml)
                ?: Regex("""(https?://[^"'\s]+\.(?:m3u8|txt|mp4)[^"'\s]*)""").find(rawHtml)
            if (directMatch != null) {
                videoUrl = directMatch.groupValues[1].replace("\\/", "/")
                Log.d(name, "Direkt m3u8/txt bulundu: $videoUrl")
            }
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

        parseSubtitles(rawHtml, subtitleCallback)

        val linkType = if (videoUrl.contains(".m3u8", ignoreCase = true) ||
            videoUrl.contains(".txt", ignoreCase = true) ||
            videoUrl.contains("/hls/", ignoreCase = true) ||
            videoUrl.contains("playlist", ignoreCase = true)
        ) {
            ExtractorLinkType.M3U8
        } else {
            INFER_TYPE
        }

        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = videoUrl,
                type = linkType
            ) {
                this.referer = url
                this.headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                    "Referer" to url,
                    "Origin" to domain,
                    "Accept" to "*/*",
                    if (cookies.isNotBlank()) "Cookie" to cookies else "" to ""
                ).filter { it.key.isNotBlank() }
            }
        )
        Log.d(name, "ExtractorLink eklendi: $videoUrl, Referer: $url, Cookies: $cookies")
    }

    private suspend fun parseSubtitles(
        rawHtml: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val tracksMatch = Regex("""tracks:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL).find(rawHtml)
        tracksMatch?.groupValues?.get(1)?.let { tracksStr ->
            val subMatches = Regex(
                """"file"\s*:\s*"([^"]+)".*?"label"\s*:\s*"([^"]+)"""",
                RegexOption.DOT_MATCHES_ALL
            ).findAll(tracksStr).toList()

            subMatches.forEach { match ->
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

                subtitleCallback.invoke(newSubtitleFile(lang, subUrl))
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

class RapidNet : RapidExtractor() {
    override val mainUrl = "https://rapid.filmmakinesi.net"
}

class RapidCom : RapidExtractor() {
    override val mainUrl = "https://rapid.filmmakinesi.com"
}
