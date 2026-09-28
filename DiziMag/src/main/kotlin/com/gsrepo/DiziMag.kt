package com.gsrepo

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class DiziMag : MainAPI() {
    override var mainUrl = "https://www.dizimag.com.tr"
    override var name = "DiziMag"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 250L
    override var sequentialMainPageScrollDelay = 250L

    override val mainPage = mainPageOf(
        "${mainUrl}/Kategori/yabanci-diziler/" to "Yabancı Diziler",
        "${mainUrl}/Kategori/yerli-diziler/" to "Yerli Diziler",
        "${mainUrl}/Kategori/anime-dizileri/" to "Anime Dizileri",

        "${mainUrl}/Kategori/filmler/aile/" to "Aile Filmleri",
        "${mainUrl}/Kategori/filmler/aksiyon/" to "Aksiyon Filmleri",
        "${mainUrl}/Kategori/filmler/animasyon/" to "Animasyon Filmleri",
        "${mainUrl}/Kategori/filmler/bilim-kurgu/" to "Bilim Kurgu Filmleri",
        "${mainUrl}/Kategori/filmler/dram/" to "Dram Filmleri",
        "${mainUrl}/Kategori/filmler/fantastik/" to "Fantastik Filmleri",
        "${mainUrl}/Kategori/filmler/gerilim/" to "Gerilim Filmleri",
        "${mainUrl}/Kategori/filmler/gizem/" to "Gizem Filmleri",
        "${mainUrl}/Kategori/filmler/komedi/" to "Komedi Filmleri",
        "${mainUrl}/Kategori/filmler/korku/" to "Korku Filmleri",
        "${mainUrl}/Kategori/filmler/macera/" to "Macera Filmleri",
        "${mainUrl}/Kategori/filmler/romantik/" to "Romantik Filmleri",
        "${mainUrl}/Kategori/filmler/savas/" to "Savaş Filmleri",
        "${mainUrl}/Kategori/filmler/suc/" to "Suç Filmleri",

        "${mainUrl}/Kategori/netflix/" to "Netflix",
        "${mainUrl}/Kategori/blutv/" to "BluTV",
        "${mainUrl}/Kategori/disney/" to "Disney+",
        "${mainUrl}/Kategori/gain/" to "Gain",
        "${mainUrl}/Kategori/tabii/" to "Tabii",
        "${mainUrl}/Kategori/hbo-max/" to "HBO Max",
        "${mainUrl}/Kategori/amazon-prime-video/" to "Amazon Prime Video",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val baseUrl = request.data.removeSuffix("/")
        val url = if (page == 1) request.data else "$baseUrl/page/$page/"
        val document = app.get(url).document

        val home = document
            .select("article")
            .mapNotNull { it.toDiziMagSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, home, hasNext = home.isNotEmpty())
    }

    private fun Element.toDiziMagSearchResult(): SearchResponse? {
        val rawHref = if (tagName() == "a") attr("href") else selectFirst("a")?.attr("href")
        if (rawHref.isNullOrBlank()) return null

        val href = fixUrlNull(rawHref) ?: return null
        if (href == mainUrl || href == "$mainUrl/") return null
        if (href.contains("/Kategori/") || href.contains("/hakkimizda/") ||
            href.contains("/gizlilik") || href.contains("/iletisim") ||
            href.contains("/giris/") || href.contains("/uye-ol/") ||
            href.contains("/page/") || href.contains("/etiket/") ||
            href.contains("/author/")
        ) return null

        var title = selectFirst("h1, h2, h3, h4, h5, .featured-big-title, .featured-small-title, .post-card-title")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        if (title.isNullOrBlank()) {
            title = selectFirst("a[href]")?.text()?.trim()?.takeIf { it.isNotBlank() }
        }

        if (title.isNullOrBlank()) {
            title = attr("title").trim().takeIf { it.isNotBlank() }
                ?: attr("aria-label").trim().takeIf { it.isNotBlank() }
        }

        if (title.isNullOrBlank()) return null

        var posterUrl: String? = null
        val image = selectFirst("img")
        if (image != null) {
            posterUrl = listOf(
                image.attr("data-src"),
                image.attr("data-lazy-src"),
                image.attr("data-original"),
                image.attr("src")
            ).firstOrNull { it.isNotBlank() }?.let { fixUrlNull(it) }

            if (posterUrl.isNullOrBlank()) {
                posterUrl = image.attr("srcset")
                    .split(",")
                    .firstOrNull()
                    ?.trim()
                    ?.split(" ")
                    ?.firstOrNull()
                    ?.let { fixUrlNull(it) }
            }
        }

        val categoryText = select("a.single-blog-category-badge, span.category-badge").text().lowercase()
        val isTvSeries = href.contains("dizi") || categoryText.contains("dizi")

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
        val url = "$mainUrl/?s=${query}"
        val document = app.get(url).document

        return document
            .select("article")
            .mapNotNull { it.toDiziMagSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, referer = mainUrl).document

        val title = document.selectFirst("h1.single-blog-title, h1")?.text()?.trim()
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")
            ?: return null

        val poster = fixUrlNull(
            document.selectFirst("div.single-blog-featured-image img")?.attr("src")
                ?: document.selectFirst("meta[property='og:image']")?.attr("content")
        )

        val description = document.select("div.single-blog-content p")
            .map { it.text().trim() }
            .filter { it.isNotBlank() && !it.contains("dizimag.com.tr", ignoreCase = true) }
            .joinToString("\n\n")
            .takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property='og:description']")?.attr("content")

        val tags = document.select("a.single-blog-category-badge, a.single-blog-tag")
            .mapNotNull { it.text().trim().takeIf { t -> t.isNotBlank() } }
            .distinct()

        val year = document.select("div.single-blog-content ul li")
            .firstOrNull { it.text().contains("Yayın Tarihi", ignoreCase = true) }
            ?.text()
            ?.let { Regex("""\b(19|20)\d{2}\b""").find(it)?.value?.toIntOrNull() }

        val rating = document.selectFirst("span.post-card-imdb, span.color-imdb")?.text()
            ?.replace("⭐", "")?.trim()

        val actors = mutableListOf<Actor>()
        document.select("div.single-blog-content ul li")
            .firstOrNull { it.text().contains("Oyuncular", ignoreCase = true) }
            ?.text()
            ?.substringAfter(":")
            ?.split(",")
            ?.forEach { actorName ->
                val name = actorName.trim()
                if (name.isNotBlank()) {
                    actors.add(Actor(name, null))
                }
            }

        val isTvSeries = url.contains("dizi") || tags.any { it.contains("dizi", ignoreCase = true) }

        return if (isTvSeries) {
            val episodes = mutableListOf<Episode>()
            val epIframe = document.selectFirst("iframe")?.attr("src")
            if (epIframe != null) {
                episodes.add(
                    newEpisode(url) {
                        this.name = title
                        this.season = 1
                        this.episode = 1
                    }
                )
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = Score.from10(rating)
                addActors(actors)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = Score.from10(rating)
                addActors(actors)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, referer = "$mainUrl/").document

        val iframes = document.select("iframe").mapNotNull { fixUrlNull(it.attr("src")) }
        for (iframe in iframes) {
            loadExtractor(iframe, "$mainUrl/", subtitleCallback, callback)
        }

        return iframes.isNotEmpty()
    }
}
