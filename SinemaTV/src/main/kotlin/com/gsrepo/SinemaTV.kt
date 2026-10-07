package com.gsrepo

import android.content.Context
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class SinemaTV(private val context: Context? = null) : MainAPI() {
    override var mainUrl = "https://sinematv.az"
    override var name = "SinemaTV.az"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.Cartoon,
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Son Eklenenler",
        "$mainUrl/film/" to "Filmler",
        "$mainUrl/serial/" to "Diziler",
        "$mainUrl/mult/" to "Çizgi Filmler",
        "$mainUrl/anime/" to "Anime",
        "$mainUrl/boevik/" to "Aksiyon",
        "$mainUrl/horror/" to "Korku",
        "$mainUrl/comedy/" to "Komedi",
        "$mainUrl/drama/" to "Dram",
        "$mainUrl/fantastic/" to "Bilim Kurgu",
        "$mainUrl/turkce-filmler/" to "Türkçe Filmler",
        "$mainUrl/hind-filmleri/" to "Hind Filmleri",
        "$mainUrl/xarici-filmler/" to "Xarici Filmler",
        "$mainUrl/rus-filmleri/" to "Rus Filmleri",
        "$mainUrl/dorama/" to "Dorama",
        "$mainUrl/thriller/" to "Triller",
        "$mainUrl/fantasy/" to "Fantastik",
        "$mainUrl/4k-filmy-i-serialy/" to "4K Filmler",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val targetUrl = if (page == 1) {
            request.data
        } else {
            "${request.data.trimEnd('/')}/page/$page/"
        }
        val document = app.get(targetUrl).document
        val isHome = request.data == "$mainUrl/" || request.data == mainUrl

        val items = if (isHome) {
            document.select("a.poster-item, div.poster-item")
        } else {
            document.select(".sect:not(.sect--top) a.poster-item, .sect:not(.sect--top) div.poster-item, #dle-content a.poster-item")
                .ifEmpty { document.select("a.poster-item, div.poster-item") }
        }

        val home = items
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleEl = this.selectFirst(".poster-item__title, .poster-title, .title")
        val title = titleEl?.text()?.trim() ?: this.attr("title").trim()
        if (title.isBlank()) return null

        val rawHref = this.attr("href").ifEmpty { this.selectFirst("a")?.attr("href") ?: "" }
        val href = fixUrlNull(rawHref) ?: return null

        val imgEl = this.selectFirst("img")
        var posterUrl = imgEl?.let { it.attr("data-src").ifEmpty { it.attr("src") } }
        if (posterUrl != null && (!posterUrl.startsWith("http"))) {
            posterUrl = "$mainUrl$posterUrl"
        }

        val type = when {
            href.contains("/serial/") || href.contains("/dorama/") -> TvType.TvSeries
            href.contains("/anime/") -> TvType.Anime
            href.contains("/mult/") -> TvType.Cartoon
            else -> TvType.Movie
        }

        return newMovieSearchResponse(title, href, type) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?do=search&subaction=search&story=$query"
        val document = app.get(searchUrl).document

        return document.select("a.poster-item, div.poster-item")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private fun parseDurationMinutes(durationStr: String?): Int? {
        if (durationStr.isNullOrBlank()) return null
        val match = Regex("""(\d+):(\d+)""").find(durationStr)
        if (match != null) {
            val hours = match.groupValues[1].toIntOrNull() ?: 0
            val mins = match.groupValues[2].toIntOrNull() ?: 0
            return hours * 60 + mins
        }
        val minsMatch = Regex("""(\d+)""").find(durationStr)
        return minsMatch?.groupValues?.get(1)?.toIntOrNull()
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text()?.trim() ?: return null

        val posterEl = document.selectFirst(".page__poster img, .full-story__img img, .poster img")
        var posterUrl = posterEl?.let { it.attr("data-src").ifEmpty { it.attr("src") } }
        if (posterUrl != null && (!posterUrl.startsWith("http"))) {
            posterUrl = "$mainUrl$posterUrl"
        }

        val yearStr = document.selectFirst(".page__year")?.text()?.trim()
        val year = yearStr?.toIntOrNull()

        val genresEl = document.selectFirst(".page__meta-item--genres")
        val tags = genresEl?.text()?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }

        val durationStr = document.selectFirst(".page__meta-item--duration")?.text()?.trim()
        val durationInMinutes = parseDurationMinutes(durationStr)

        val rating = document.selectFirst(".page__rating-item--critics div, .page__rating-item div")?.text()?.trim()

        val descEl = document.selectFirst(".page__text.full-text, .page__text, #full-text, .full-story__text")
        val description = descEl?.text()?.trim()

        val actors = mutableListOf<String>()
        for (info in document.select(".page__info-subinfo, .line-clamp")) {
            val txt = info.text()
            if (txt.contains("ролях", ignoreCase = true) || txt.contains("aktyorlar", ignoreCase = true) || txt.contains("role:", ignoreCase = true)) {
                val clean = txt.substringAfter(":").trim()
                actors.addAll(clean.split(",").map { it.trim() }.filter { it.isNotBlank() })
                break
            }
        }

        val isTvSeries = url.contains("/serial/") || url.contains("/dorama/") || (url.contains("/anime/") && (!url.contains("film")))

        val recommendations = document.select(".sect__content a.poster-item, div.poster-item")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
            .filter { it.url != url }

        return if (isTvSeries) {
            val episode = newEpisode(url) {
                this.name = title
                this.season = 1
                this.episode = 1
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, listOf(episode)) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = Score.from10(rating?.replace("%", "")?.trim())
                this.duration = durationInMinutes
                this.recommendations = recommendations
                addActors(actors)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = Score.from10(rating?.replace("%", "")?.trim())
                this.duration = durationInMinutes
                this.recommendations = recommendations
                addActors(actors)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val document = app.get(data).document
        val iframes = document.select("iframe")

        var linksFound = false

        for (ifr in iframes) {
            val src = ifr.attr("data-src").ifEmpty { ifr.attr("src") }
            val title = ifr.attr("title").trim().lowercase()

            if (src.isBlank() || src.startsWith("data:")) continue
            if (title.contains("трейлер") || title.contains("trailer")) continue
            if (src.contains("/t/") || src.contains("/trailer/")) continue

            val iframeUrl = if (src.startsWith("/")) "$mainUrl$src" else src

            if (iframeUrl.contains("vv-player.php")) {
                val movieId = iframeUrl.substringAfter("movie_id=").substringBefore("&")
                if (movieId.isNotBlank()) {
                    try {
                        val playerHtml = app.get(iframeUrl, referer = "$mainUrl/").text
                        val hdrMatch = Regex("""window\.REQUEST_HEADERS=(\{.*?\});""").find(playerHtml)?.groupValues?.get(1)?.replace("'", "\"")

                        val headersMap = mutableMapOf(
                            "Referer" to iframeUrl,
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                            "X-Has-Token" to "1"
                        )

                        if (hdrMatch != null) {
                            val parsedHdrs = jacksonObjectMapper().readValue<Map<String, Any>>(hdrMatch)
                            for ((k, v) in parsedHdrs) {
                                headersMap[k] = v.toString()
                            }
                        }

                        val apiUrl = "$mainUrl/vv-api.php?path=/balancer-api/proxy/playlists/catalog-api/episodes&content-id=$movieId"
                        val apiResp = app.get(apiUrl, headers = headersMap).text

                        val m3u8Regex = Regex("""https?://[^\s"'<>]+?(?:\.m3u8|parsed\.json)[^\s"'<>]*""")
                        val matches = m3u8Regex.findAll(apiResp)
                        for (match in matches) {
                            val streamPath = match.value
                            if (streamPath.contains("parsed.json")) {
                                try {
                                    val parsedResp = app.get(streamPath, referer = iframeUrl).text
                                    val innerM3u8Regex = Regex("""https?://[^\s"'<>]+?grouped\.m3u8[^\s"'<>]*""")
                                    val innerMatches = innerM3u8Regex.findAll(parsedResp)
                                    for (innerMatch in innerMatches) {
                                        val resolved = SinemaTVHelper.resolveM3u8Streams(
                                            name,
                                            innerMatch.value,
                                            "$mainUrl/",
                                            headersMap,
                                            callback
                                        )
                                        if (resolved) linksFound = true
                                    }
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            } else if (streamPath.contains("master.m3u8") || streamPath.contains("grouped.m3u8") || streamPath.contains("gorodyshka.link")) {
                                val resolved = SinemaTVHelper.resolveM3u8Streams(
                                    name,
                                    streamPath,
                                    "$mainUrl/",
                                    headersMap,
                                    callback
                                )
                                if (resolved) linksFound = true
                            } else if (streamPath.contains(".m3u8")) {
                                M3u8Helper.generateM3u8(
                                    name,
                                    streamPath,
                                    "$mainUrl/",
                                    headers = headersMap
                                ).forEach { link ->
                                    callback.invoke(link)
                                    linksFound = true
                                }
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }

            val loaded = loadExtractor(iframeUrl, "$mainUrl/", subtitleCallback, callback)
            if (loaded) {
                linksFound = true
            } else if (context != null) {
                val webExtractor = SinemaTVWebViewExtractor(context, name)
                webExtractor.getUrl(iframeUrl, "$mainUrl/", subtitleCallback, callback)
                linksFound = true
            }
        }

        return linksFound
    }
}
