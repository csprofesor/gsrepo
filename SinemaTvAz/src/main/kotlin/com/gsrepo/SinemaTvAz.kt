package com.gsrepo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

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
        "$mainUrl/turkce-filmler/" to "Türkcə Filmlər",
        "$mainUrl/hind-filmleri/" to "Hind Filmləri",
        "$mainUrl/serial/" to "Seriallar",
        "$mainUrl/animasiya/" to "Animasiya"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val document = app.get(url, headers = browserHeaders).document
        val home = document.select("div.shortstory, article.shortstory, div.movie-item, div.item").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst("div.shortstory-title a, h2.title a, a.shortstory-title, a.title") ?: return null
        val title = titleElement.text().trim()
        val href = fixUrl(titleElement.attr("href"))

        val imgElement = this.selectFirst("div.shortstory-poster img, div.poster img, img")
        val posterUrl = imgElement?.attr("data-src")?.ifEmpty { imgElement.attr("src") }?.let { fixUrl(it) }

        val isTvSeries = href.contains("/serial/") || href.contains("/animasiya/") || title.contains("sezon", ignoreCase = true)

        return if (isTvSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
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

        return response.select("div.shortstory, article.shortstory, div.movie-item, div.item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = browserHeaders).document

        val title = document.selectFirst("h1.title, h1.entry-title, h1")?.text()?.trim() ?: ""
        val poster = document.selectFirst("div.poster img, div.shortstory-poster img, div.story-poster img")?.let {
            it.attr("data-src").ifEmpty { it.attr("src") }
        }?.let { fixUrl(it) }

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

        val isTvSeries = url.contains("/serial/") || url.contains("/animasiya/") || title.contains("sezon", ignoreCase = true)

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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = browserHeaders, referer = "$mainUrl/").document

        val iframes = document.select("iframe")
        var foundAny = false

        iframes.forEach { iframe ->
            val src = iframe.attr("data-src").ifEmpty { iframe.attr("src") }
            val title = iframe.attr("title")

            if (title.contains("Трейлер", ignoreCase = true) || title.contains("Trailer", ignoreCase = true)) {
                return@forEach
            }

            if (src.isEmpty() || src.contains("googletagmanager") || src.contains("yandex") || src.contains("facebook") || src.contains("/t?token=") || src.contains("allarknow") || src.contains("/t/")) {
                return@forEach
            }

            val playerUrl = fixUrl(src) ?: return@forEach
            foundAny = true
            val context = SinemaTvAzPlugin.pluginContext
            if (context != null) {
                SinemaTvAzWebViewExtractor(context).getUrl(playerUrl, data, subtitleCallback, callback)
            } else {
                loadExtractor(playerUrl, data, subtitleCallback, callback)
            }
        }

        return foundAny
    }
}
