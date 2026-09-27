package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class FilmMakinesi : MainAPI() {
    override var mainUrl = "https://filmmakinesi.to"
    override var name = "FilmMakinesi"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "${mainUrl}/filmler-1/" to "Son Eklenen Filmler",
        "${mainUrl}/tur/aksiyon-fmy54y/film/" to "Aksiyon Filmleri",
        "${mainUrl}/yabanci-dizi-izle-1/" to "Diziler",
        "${mainUrl}/yil/2026-fmfbkb/film/" to "2026 Filmleri"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data.removeSuffix("/")}/sayfa/$page/"
        val doc = app.get(url).document
        val home = parseHomePage(doc)
        return newHomePageResponse(request.name, home, hasNext = home.isNotEmpty())
    }

    fun parseHomePage(doc: org.jsoup.nodes.Document): List<SearchResponse> {
        return doc.select("a.item, a.slide, div.item-relative a.item")
            .mapNotNull { parseSearchElement(it) }
            .distinctBy { it.url }
    }

    private fun parseSearchElement(element: Element): SearchResponse? {
        val link = if (element.tagName() == "a") element else element.selectFirst("a") ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null
        val title = element.attr("data-title").ifEmpty { null }
            ?: element.selectFirst(".title, .item-title, h4")?.text()?.trim()
            ?: link.attr("title").ifEmpty { null }
            ?: element.selectFirst("img")?.attr("alt")?.trim()
            ?: return null
        val poster = fixUrlNull(element.selectFirst("img")?.let {
            it.attr("src").ifEmpty { null }
                ?: it.attr("srcset").split(",").firstOrNull()?.trim()?.split(" ")?.firstOrNull()
        })
        val score = element.attr("data-score").ifEmpty { null }
            ?: element.selectFirst(".rating, .imdb-score span")?.text()?.trim()
        val type = if (href.contains("/dizi/")) TvType.TvSeries else TvType.Movie

        return if (type == TvType.TvSeries) {
            newTvSeriesSearchResponse(title, href, type) {
                posterUrl = poster
                this.score = Score.from10(score)
            }
        } else {
            newMovieSearchResponse(title, href, type) {
                posterUrl = poster
                this.score = Score.from10(score)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val doc = app.get("${mainUrl}/arama/?s=$query", referer = "${mainUrl}/").document
            doc.select("a.item, div.item-relative a.item")
                .mapNotNull { parseSearchElement(it) }
                .distinctBy { it.url }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, referer = "${mainUrl}/").document
        val title = doc.selectFirst("h1, meta[property='og:title']")?.let {
            if (it.tagName() == "meta") it.attr("content") else it.text().trim()
        }?.replace(" - FilmMakinesi", "")?.replace(" izle", "")?.trim() ?: return null

        val poster = fixUrlNull(doc.selectFirst("meta[property='og:image']")?.attr("content"))
        val description = doc.selectFirst("meta[property='og:description'], div.description, div.info-content .description")?.let {
            if (it.tagName() == "meta") it.attr("content") else it.text().trim()
        }
        val year = doc.selectFirst("div.info span:first-child, span.year")?.text()?.filter { it.isDigit() }?.take(4)?.toIntOrNull()
        val score = doc.selectFirst("div.imdb-score span, .rating")?.text()?.trim()

        if (url.contains("/dizi/")) {
            val episodes = doc.select("a[href*='bolum'], div.episodes a").mapNotNull { a ->
                val epHref = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
                val epTitle = a.text().trim()
                val season = Regex("""(d+)\.\s*Sezon""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""/sezon-(d+)/""").find(epHref)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val episode = Regex("""(d+)\.\s*B[öo]l[üu]m""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""/bolum-(d+)/""").find(epHref)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
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
        val candidates = doc.select("iframe").mapNotNull {
            val src = it.attr("data-src").ifEmpty { it.attr("src") }
            fixUrlNull(src)?.takeUnless { it.contains("youtube.com") || it.contains("youtu.be") }
        }.distinct().toMutableList()

        doc.select(".video-parts a[data-video_url]").forEach {
            val src = fixUrlNull(it.attr("data-video_url"))
            if (src != null && !candidates.contains(src)) candidates.add(0, src)
        }

        var found = false
        candidates.forEach { embedUrl ->
            try {
                loadExtractor(embedUrl, data, subtitleCallback) { link ->
                    found = true
                    callback(link)
                }
            } catch (e: Exception) {
                Log.d(name, "Extractor failed: $embedUrl - ${e.message}")
            }
        }
        return found
    }
}
