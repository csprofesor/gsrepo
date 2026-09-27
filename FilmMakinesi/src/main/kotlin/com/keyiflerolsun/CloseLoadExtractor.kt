package com.keyiflerolsun

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

open class CloseLoadExtractor : ExtractorApi() {
    override val mainUrl = "https://closeload.filmmakinesi.to"
    override val name = "CloseLoad"
    override val requiresReferer = true

    private fun isValidVideoUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        if (url.contains("playmix.uno", ignoreCase = true)) return false
        return true
    }

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

        // 1. Try unpacking packer JS
        val unpackedJs = try { getAndUnpack(rawHtml) } catch (e: Exception) { null }
        val searchHtml = unpackedJs ?: rawHtml

        var videoUrl: String? = null

        // 2. CloseLoad exposes the current stream directly in the embed HTML.
        val directFileMatch = Regex("""(?i)(?:["']?file["']?)\s*:\s*["'](https?://[^"']+)["']""")
            .find(searchHtml)
            ?: Regex("""(?i)(?:["']?file["']?)\s*:\s*["'](https?://[^"']+)["']""")
                .find(rawHtml)

        if (directFileMatch != null) {
            val candidate = directFileMatch.groupValues[1].replace("\\/", "/")
            if (isValidVideoUrl(candidate)) {
                videoUrl = candidate
                Log.d(name, "Current CloseLoad file source: $videoUrl")
            }
        }

        // 3. First check JWPlayer file or sources or contentUrl in HTML/Unpacked JS before AJAX
        if (!isValidVideoUrl(videoUrl)) {
            val jwFileMatches = Regex(""""(?:file|contentUrl|url)"\s*:\s*"([^"]+)"""").findAll(searchHtml) +
                    Regex(""""(?:file|contentUrl|url)"\s*:\s*"([^"]+)"""").findAll(rawHtml)
            for (match in jwFileMatches) {
                val candidate = match.groupValues[1].replace("\\/", "/")
                if (isValidVideoUrl(candidate)) {
                    videoUrl = candidate
                    Log.d(name, "JWPlayer/JSON file match bulundu: $videoUrl")
                    break
                }
            }
        }

        // 4. AJAX Hash authorization & Video URL retrieval (if videoUrl not found yet)
        if (!isValidVideoUrl(videoUrl)) {
            val ajaxHash = Regex(""""hash"\s*:\s*"([^"]+)"""").find(searchHtml)?.groupValues?.get(1)
                ?: Regex("""hash\s*:\s*['"]([a-zA-Z0-9]{32})['"]""").find(searchHtml)?.groupValues?.get(1)
                ?: Regex(""""hash"\s*:\s*"([^"]+)"""").find(rawHtml)?.groupValues?.get(1)

            val ajaxPath = Regex(""""url"\s*:\s*"([^"]+ah/)"\s*""").find(searchHtml)?.groupValues?.get(1)
                ?: Regex("""url\s*:\s*['"]([^'"]+ah/)['"]""").find(searchHtml)?.groupValues?.get(1)
                ?: "/video/ah/"

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
                        ).filter { it.key.isNotBlank() }
                    )
                    Log.d(name, "AJAX Response: ${ajaxRes.code} - ${ajaxRes.text}")
                    val newCookies = ajaxRes.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
                    if (newCookies.isNotBlank()) {
                        cookies = if (cookies.isNotBlank()) "$cookies; $newCookies" else newCookies
                    }

                    val ajaxText = ajaxRes.text
                    val jsonMatch = Regex(""""(?:file|url|hls|source|securedLink)"\s*:\s*"([^"]+)"""").find(ajaxText)
                    if (jsonMatch != null) {
                        val candidate = jsonMatch.groupValues[1].replace("\\/", "/")
                        if (isValidVideoUrl(candidate)) {
                            videoUrl = candidate
                            Log.d(name, "AJAX Response'dan video URL bulundu: $videoUrl")
                        } else {
                            Log.w(name, "AJAX Response'daki URL geçersiz veya PlayMix: $candidate")
                        }
                    }
                    if (!isValidVideoUrl(videoUrl)) {
                        val urlMatches = Regex("""(https?://[^"'\s]+\.(?:m3u8|txt|mp4|m3u)[^"'\s]*)""").findAll(ajaxText)
                        for (match in urlMatches) {
                            val candidate = match.groupValues[1].replace("\\/", "/")
                            if (isValidVideoUrl(candidate)) {
                                videoUrl = candidate
                                Log.d(name, "AJAX Response'dan direkt URL bulundu: $videoUrl")
                                break
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(name, "AJAX POST hatası: ${e.message}")
                }
            }
        }

        // 5. Look for m3u8 or .txt direct link in unpacked JS or raw HTML if not found
        if (!isValidVideoUrl(videoUrl)) {
            val directMatches = Regex("""(https?://[^"'\s]+\.(?:m3u8|txt|mp4|m3u)[^"'\s]*)""").findAll(searchHtml) +
                    Regex("""(https?://[^"'\s]+\.(?:m3u8|txt|mp4|m3u)[^"'\s]*)""").findAll(rawHtml)
            for (match in directMatches) {
                val candidate = match.groupValues[1].replace("\\/", "/")
                if (isValidVideoUrl(candidate)) {
                    videoUrl = candidate
                    Log.d(name, "Direkt m3u8/txt bulundu: $videoUrl")
                    break
                }
            }
        }

        // 6. Look for base64 / atob in script
        if (!isValidVideoUrl(videoUrl)) {
            val atobMatches = Regex("""aHR0[0-9a-zA-Z+\/=]+""").findAll(searchHtml) +
                    Regex("""aHR0[0-9a-zA-Z+\/=]+""").findAll(rawHtml)
            for (atobMatch in atobMatches) {
                var atob = atobMatch.value
                val padding = atob.length % 4
                if (padding != 0) {
                    atob += "=".repeat(4 - padding)
                }
                try {
                    val decoded = String(Base64.decode(atob, Base64.DEFAULT), Charsets.UTF_8)
                    if (isValidVideoUrl(decoded)) {
                        videoUrl = decoded
                        Log.d(name, "Atob decoded URL: $videoUrl")
                        break
                    }
                } catch (e: Exception) {
                    Log.w(name, "Atob decode hatası: ${e.message}")
                }
            }
        }

        val finalUrl = videoUrl
        videoUrl = when {
            !isValidVideoUrl(finalUrl) -> null
            finalUrl!!.startsWith("//") -> "https:$finalUrl"
            finalUrl.startsWith("http") -> finalUrl
            else -> domain.trimEnd('/') + "/" + finalUrl.trimStart('/')
        }

        if (videoUrl.isNullOrBlank() || !isValidVideoUrl(videoUrl)) {
            Log.e(name, "Video URL bulunamadı veya geçerli değil!")
            return
        }

        parseSubtitles(rawHtml, subtitleCallback)

        val videoDomain = Regex("""(https?://[^/]+)""").find(videoUrl)?.groupValues?.get(1) ?: domain

        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = videoUrl,
                type = INFER_TYPE
            ) {
                this.referer = "$videoDomain/"
                this.headers = mapOf(
                    "Origin" to videoDomain,
                    "Referer" to "$videoDomain/",
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

            subMatches.forEachIndexed { index, match ->
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

class CloseLoadTo : CloseLoadExtractor() {
    override val mainUrl = "https://closeload.filmmakinesi.to"
}

class CloseLoadFilm : CloseLoadExtractor() {
    override val mainUrl = "https://closeload.filmmakinesi.film"
}

class CloseLoadDe : CloseLoadExtractor() {
    override val mainUrl = "https://closeload.filmmakinesi.de"
}

class CloseLoadTv : CloseLoadExtractor() {
    override val mainUrl = "https://closeload.filmmakinesi.tv"
}

class CloseLoadSh : CloseLoadExtractor() {
    override val mainUrl = "https://closeload.filmmakinesi.sh"
}
