package com.gsrepo.hdfilmcehennemi

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class HDFilmCehennemi : MainAPI() {
    override var mainUrl = "https://www.hdfilmcehennemi.nl"
    override var name = "HDFilmCehennemi"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "${mainUrl}/" to "Son Eklenenler",
        "${mainUrl}/category/film-izle-2/" to "Filmler",
        "${mainUrl}/yabancidiziizle-5/" to "Diziler",
        "${mainUrl}/dil/turkce-dublajli-film-izleyin-6/" to "Türkçe Dublaj",
        "${mainUrl}/dil/turkce-altyazili-filmleri-izleme-sitesi-3/" to "Türkçe Altyazılı"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data.removeSuffix("/")
        val url = if (page <= 1) request.data else "$base/page/$page/"
        val doc = app.get(url).document
        val items = parseHomePage(doc)
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    private fun parseHomePage(doc: Document): List<SearchResponse> {
        return doc.select("a.poster, a.card, div.poster, div.card")
            .mapNotNull { parseSearchElement(it) }
            .distinctBy { it.url }
    }

    private fun parseSearchElement(element: Element): SearchResponse? {
        val link = if (element.tagName() == "a") element else element.selectFirst("a") ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null
        val title = element.selectFirst("h2.title, h3.title, h4.title, div.title, .poster-title")?.text()?.trim()
            ?: link.attr("title").ifEmpty { null }
            ?: element.selectFirst("img")?.attr("alt")?.trim()
            ?: return null
        val poster = fixUrlNull(element.selectFirst("img")?.let {
            it.attr("data-src").ifEmpty { null }
                ?: it.attr("srcset").split(",").firstOrNull()?.trim()?.split(" ")?.firstOrNull()
                ?: it.attr("src")
        })
        val type = if (href.contains("/dizi/")) TvType.TvSeries else TvType.Movie

        return if (type == TvType.TvSeries) {
            newTvSeriesSearchResponse(title, href, type) { posterUrl = poster }
        } else {
            newMovieSearchResponse(title, href, type) { posterUrl = poster }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val response = app.get(
                "${mainUrl}/search?q=$query",
                headers = mapOf(
                    "X-Requested-With" to "fetch",
                    "Content-Type" to "application/json"
                )
            ).parsed<SearchApiResponse>()

            response.results.orEmpty().mapNotNull { html ->
                val doc = org.jsoup.Jsoup.parse(html)
                val a = doc.selectFirst("a.search-result") ?: return@mapNotNull null
                val url = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
                val title = a.selectFirst("h4.title, .title")?.text()?.trim()
                    ?: a.attr("aria-label").ifEmpty { return@mapNotNull null }
                val poster = fixUrlNull(a.selectFirst("img")?.let {
                    it.attr("src").ifEmpty { null }
                        ?: it.attr("srcset").split(",").firstOrNull()?.trim()?.split(" ")?.firstOrNull()
                })
                if (url.contains("/dizi/")) {
                    newTvSeriesSearchResponse(title, url, TvType.TvSeries) { posterUrl = poster }
                } else {
                    newMovieSearchResponse(title, url, TvType.Movie) { posterUrl = poster }
                }
            }.distinctBy { it.url }
        } catch (_: Exception) {
            emptyList()
        }
    }

    data class SearchApiResponse(val results: List<String>? = null)

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1.section-title, h1, meta[property='og:title']")?.let {
            if (it.tagName() == "meta") it.attr("content") else it.text().trim()
        }?.replace(" - HDFilmCehennemi", "")?.replace(" Full HD izle", "")?.trim() ?: return null

        val poster = fixUrlNull(doc.selectFirst("meta[property='og:image']")?.attr("content"))
        val description = doc.selectFirst("meta[property='og:description'], div.description, article.text-white")?.let {
            if (it.tagName() == "meta") it.attr("content") else it.text().trim()
        }
        val year = doc.selectFirst("span.year, div.year")?.text()?.trim()?.toIntOrNull()
        val score = doc.selectFirst("span.imdb")?.text()?.trim()
        val isTv = url.contains("/dizi/") || doc.select(".seasons, .seasons-tabs-wrapper").isNotEmpty()

        if (isTv) {
            val episodes = doc.select("div.seasons-tab-content a.mini-poster, div.seasons a[href*='bolum']")
                .mapNotNull { a ->
                    val epHref = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
                    val epTitle = a.selectFirst(".mini-poster-title")?.text()?.trim() ?: a.text().trim()
                    val season = Regex("""(\d+)\.\s*Sezon""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    val episode = Regex("""(\d+)\.\s*B[öo]l[üu]m""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                        ?: Regex("""/bolum-(\d+)/""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                        ?: return@mapNotNull null
                    newEpisode(epHref) {
                        name = epTitle
                        this.season = season
                        this.episode = episode
                    }
                }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster
                plot = description
                this.year = year
                this.score = Score.from10(score)
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = poster
            plot = description
            this.year = year
            this.score = Score.from10(score)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, referer = mainUrl).document
        val iframes = doc.select("iframe").mapNotNull {
            val src = it.attr("data-src").ifEmpty { it.attr("src") }
            fixUrlNull(src)?.takeUnless { it.contains("youtube.com") || it.contains("youtu.be") }
        }.distinct()

        var found = false
        iframes.forEach { iframe ->
            try {
                if (iframe.contains("rapidrame") || iframe.contains("hdfilmcehennemi.mobi")) {
                    RapidrameExtractor().getUrl(iframe, data, subtitleCallback) {
                        found = true
                        callback(it)
                    }
                } else {
                    loadExtractor(iframe, data, subtitleCallback) {
                        found = true
                        callback(it)
                    }
                }
            } catch (_: Exception) {}
        }
        return found
    }
}
