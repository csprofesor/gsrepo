package com.gsrepo

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class SinemaTvAz : MainAPI() {
    override var mainUrl = "https://sinematv.az"
    override var name = "SinemaTvAz"
    override val hasMainPage = true
    override var lang = "az"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "az,tr,en-US;q=0.9,en;q=0.8"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/xarici-filmler/" to "Xarici Filmlər",
        "$mainUrl/hind-filmleri/" to "Hind Filmləri",
        "$mainUrl/serial/" to "Seriallar",
        "$mainUrl/rus-filmleri/" to "Rus Filmləri",
        "$mainUrl/mult/" to "Cizgi Filmləri",
        "$mainUrl/anime/" to "Anime",
        "$mainUrl/dorama/" to "Doramalar"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val document = app.get(url, headers = browserHeaders).document
        var items = document.select("div.sect:not(.sect--top) .poster-item, div.sect:not(.sect--top) .grid-item, div.sect:not(.sect--top) .shortstory, div.sect:not(.sect--top) article")
        if (items.isEmpty()) {
            items = document.select(".poster-item, .grid-item, div.shortstory, article.shortstory, div.movie-item, div.item")
        }
        val home = items.mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = when {
            this.tagName() == "a" -> this.attr("href")
            else -> this.selectFirst("a.poster-item, div.shortstory-title a, h2.title a, a.shortstory-title, a.title, a")?.attr("href")
        } ?: return null

        val fixHref = fixUrl(href)

        val titleElement = this.selectFirst(".poster-item__title, div.shortstory-title, h2.title, span.title")
        val title = (titleElement?.text()?.trim() ?: "").ifEmpty {
            this.attr("title")
        }.ifEmpty {
            this.selectFirst("img")?.attr("title")?.ifEmpty { this.selectFirst("img")?.attr("alt") } ?: ""
        }.ifEmpty {
            this.text().trim()
        }

        if (title.isBlank()) return null

        val imgElement = this.selectFirst("img")
        val posterUrl = imgElement?.attr("data-src")?.ifEmpty { imgElement.attr("src") }
            ?.replace("/uploads/movies/", "/movies/")
            ?.let { fixUrl(it) }

        val isTvSeries = fixHref.contains("/serial/") || fixHref.contains("/mult/") || fixHref.contains("/anime/") || fixHref.contains("/dorama/") || title.contains("sezon", ignoreCase = true)

        return if (isTvSeries) {
            newTvSeriesSearchResponse(title, fixHref, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, fixHref, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/index.php?do=search"
        val response = app.post(
            searchUrl,
            data = mapOf(
                "do" to "search",
                "subaction" to "search",
                "story" to query
            ),
            headers = browserHeaders
        ).document

        return response.select("div.sect:not(.sect--top) .poster-item, div.sect:not(.sect--top) .grid-item, .poster-item, .grid-item, div.shortstory, article.shortstory, div.movie-item, div.item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = browserHeaders).document

        val title = document.selectFirst("h1.title, h1.entry-title, h1")?.text()?.trim() ?: ""
        val poster = document.selectFirst("div.poster img, div.shortstory-poster img, div.story-poster img, img")?.let {
            it.attr("data-src").ifEmpty { it.attr("src") }
        }?.replace("/uploads/movies/", "/movies/")?.let { fixUrl(it) }

        val description = document.selectFirst("div.full-text, div.story-text, div.description")?.text()?.trim()
        val year = document.selectFirst("div.info:contains(İl), span:contains(İl)")?.text()?.let {
            Regex("\\d{4}").find(it)?.value?.toIntOrNull()
        }

        val episodes = mutableListOf<Episode>()

        val episodeElements = document.select("div.episodes-list a, ul.episodes a, div.seasons-list a")
        if (episodeElements.isNotEmpty()) {
            episodeElements.forEach { element ->
                val epTitle = element.text().trim()
                val epHref = fixUrl(element.attr("href"))
                val seasonNum = Regex("(\\d+)\\.\\s*Sezon", RegexOption.IGNORE_CASE).find(epTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val epNum = Regex("(\\d+)\\.\\s*Bölüm", RegexOption.IGNORE_CASE).find(epTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 1

                episodes.add(
                    newEpisode(epHref) {
                        this.name = epTitle
                        this.season = seasonNum
                        this.episode = epNum
                    }
                )
            }
        }

        val isTvSeries = url.contains("/serial/") || url.contains("/mult/") || url.contains("/anime/") || url.contains("/dorama/") || title.contains("sezon", ignoreCase = true)

        return if (isTvSeries) {
            val finalEpisodes = if (episodes.isEmpty()) {
                listOf(
                    newEpisode(url) {
                        this.name = "1. Bölüm"
                        this.season = 1
                        this.episode = 1
                    }
                )
            } else {
                episodes
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, finalEpisodes) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
            }
        }
    }

    private suspend fun extractSinemaTvAzCdn(
        embedUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val embedDoc = app.get(embedUrl, headers = mapOf("Referer" to referer)).text
            val datasMatch = Regex("""const\s+datas\s*=\s*"([^"]+)"""").find(embedDoc)?.groupValues?.get(1) ?: return false

            val base64Bytes = Base64.decode(datasMatch, Base64.DEFAULT)
            val jsonStr = String(base64Bytes, Charsets.ISO_8859_1)

            val json = JSONObject(jsonStr)
            val slug = json.optString("slug")
            val userId = json.opt("user_id")?.toString() ?: ""
            val md5Id = json.opt("md5_id")?.toString() ?: ""
            val mediaStr = json.optString("media")

            if (slug.isEmpty() || userId.isEmpty() || md5Id.isEmpty() || mediaStr.isEmpty()) return false

            val keyStr = "$userId:$slug:$md5Id"
            val md5Bytes = MessageDigest.getInstance("MD5").digest(keyStr.toByteArray(Charsets.UTF_8))
            val md5Hex = md5Bytes.joinToString("") { "%02x".format(it) }

            val keyBytes = md5Hex.toByteArray(Charsets.UTF_8)
            val counterBytes = keyBytes.copyOfRange(0, 16)

            val secretKey = SecretKeySpec(keyBytes, "AES")
            val ivSpec = IvParameterSpec(counterBytes)
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)

            val mediaBytes = mediaStr.map { it.code.toByte() }.toByteArray()
            val decryptedBytes = cipher.doFinal(mediaBytes)
            val decryptedText = String(decryptedBytes, Charsets.UTF_8)

            val decryptedJson = JSONObject(decryptedText)
            val section = decryptedJson.optJSONObject("mp4") ?: decryptedJson.optJSONObject("hls") ?: return false
            val fristDatas = section.optJSONArray("fristDatas")

            val embedDomain = fixUrl(embedUrl).substringBefore("/?").trimEnd('/') + "/"
            val streamHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36",
                "Referer" to embedDomain
            )

            var foundAny = false

            if (fristDatas != null) {
                for (i in 0 until fristDatas.length()) {
                    val item = fristDatas.getJSONObject(i)
                    val streamUrl = item.optString("url")
                    if (streamUrl.isNotEmpty()) {
                        val resId = item.optInt("res_id")
                        val codec = item.optString("codec")
                        val quality = when (resId) {
                            1 -> Qualities.P144.value
                            2 -> Qualities.P360.value
                            3 -> Qualities.P480.value
                            4 -> Qualities.P720.value
                            5 -> Qualities.P1080.value
                            7 -> Qualities.P1440.value
                            8 -> Qualities.P2160.value
                            else -> Qualities.Unknown.value
                        }
                        val codecLabel = if (codec.isNotEmpty()) " [$codec]" else ""

                        callback.invoke(
                            newExtractorLink(
                                source = "SinemaTvAz",
                                name = "SinemaTvAz$codecLabel",
                                url = streamUrl,
                                type = ExtractorLinkType.VIDEO,
                            ) {
                                this.quality = quality
                                this.headers = streamHeaders
                            }
                        )
                        foundAny = true
                    }
                }
            }

            return foundAny
        } catch (e: Exception) {
            return false
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = browserHeaders, referer = "$mainUrl/").document
        val iframes = document.select("iframe")
        var foundAny = false

        for (iframe in iframes) {
            val src = iframe.attr("data-src").ifEmpty { iframe.attr("src") }
            val title = iframe.attr("title")

            if (title.contains("Трейлер", ignoreCase = true) || title.contains("Trailer", ignoreCase = true)) {
                continue
            }

            if (src.isEmpty() || src.contains("googletagmanager") || src.contains("yandex") || src.contains("facebook") || src.contains("/t?token=") || src.contains("allarknow") || src.contains("/t/")) {
                continue
            }

            val playerUrl = fixUrl(src)
            if (playerUrl.isEmpty()) continue

            if (playerUrl.contains("cdn.sinematv.az") || playerUrl.contains("cdn1.sinematv.az") || playerUrl.contains("v=")) {
                if (extractSinemaTvAzCdn(playerUrl, data, subtitleCallback, callback)) {
                    foundAny = true
                    continue
                }
            }

            if (loadExtractor(playerUrl, data, subtitleCallback, callback)) {
                foundAny = true
            }
        }

        if (!foundAny) {
            val context = SinemaTvAzPlugin.pluginContext
            if (context != null) {
                SinemaTvAzWebViewExtractor(context).getUrl(data, data, subtitleCallback, callback)
                return true
            }
        }

        return foundAny
    }
}
