package com.gsrepo.hdfilmcehennemi

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class HDFilmCehennemi : MainAPI() {
    override var mainUrl = "https://www.hdfilmcehennemi.nl"
    override var name = "HDFilmCehennemi"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val response = chain.proceed(request)
            val body = response.peekBody(1024 * 1024).string()
            val doc = Jsoup.parse(body)

            if (doc.html().contains("Just a moment", ignoreCase = true)) {
                return cloudflareKiller.intercept(chain)
            }

            return response
        }
    }

    override val mainPage = mainPageOf(
        "${mainUrl}/" to "Son Eklenenler",
        "${mainUrl}/category/film-izle-2/" to "Filmler",
        "${mainUrl}/yabancidiziizle-5/" to "Yabancı Diziler",
        "${mainUrl}/dil/turkce-dublajli-film-izleyin-6/" to "Türkçe Dublaj",
        "${mainUrl}/dil/turkce-altyazili-filmleri-izleme-sitesi-3/" to "Türkçe Altyazılı",
        "${mainUrl}/en-cok-izlenen-filmler-hd-1/" to "En Çok İzlenenler",
        "${mainUrl}/category/tavsiye-filmler-izle3/" to "Tavsiye Filmler",
        "${mainUrl}/top100-2/" to "IMDb Top 100",
        "${mainUrl}/category/marvel-yapimlarini-izle-5/" to "Marvel Yapımları",
        "${mainUrl}/category/dc-yapimlarini-izle-1/" to "DC Yapımları",
        "${mainUrl}/tur/aksiyon-filmleri-izleyin-8/" to "Aksiyon",
        "${mainUrl}/tur/animasyon-filmlerini-izleyin-5/" to "Animasyon",
        "${mainUrl}/tur/bilim-kurgu-filmlerini-izleyin-5/" to "Bilim Kurgu",
        "${mainUrl}/tur/dram-filmlerini-izle-2/" to "Dram",
        "${mainUrl}/tur/fantastik-filmlerini-izleyin-4/" to "Fantastik",
        "${mainUrl}/tur/gerilim-filmlerini-izle-4/" to "Gerilim",
        "${mainUrl}/tur/komedi-filmlerini-izleyin-2/" to "Komedi",
        "${mainUrl}/tur/korku-filmlerini-izle-9/" to "Korku",
        "${mainUrl}/tur/macera-filmlerini-izleyin-5/" to "Macera",
        "${mainUrl}/tur/romantik-filmleri-izle-3/" to "Romantik"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val base = request.data.removeSuffix("/")
            val url = if (page <= 1) request.data else "$base/page/$page/"
            val doc = app.get(url, referer = "$mainUrl/", interceptor = interceptor).document
            val items = parseHomePage(doc)
            newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
        } catch (e: Exception) {
            Log.e(name, "getMainPage error: ${e.message}")
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    private fun parseHomePage(doc: Document): List<SearchResponse> {
        return doc.select("a.poster, a.card, div.poster, div.card, article, div.movie-box")
            .mapNotNull { parseSearchElement(it) }
            .distinctBy { it.url }
    }

    private fun parseSearchElement(element: Element): SearchResponse? {
        val link = if (element.tagName() == "a") element else element.selectFirst("a") ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null
        if (href.contains("/oyuncu/") || href.contains("/yonetmen/") || href.contains("/kategori/") || href.contains("/tur/")) {
            return null
        }

        val title = element.attr("data-title").ifEmpty { null }
            ?: element.selectFirst("h2.title, h3.title, h4.title, div.title, .poster-title, strong")?.text()?.trim()
            ?: link.attr("title").ifEmpty { null }
            ?: element.selectFirst("img")?.attr("alt")?.replace(" izle", "")?.trim()
            ?: return null

        val img = element.selectFirst("img")
        val poster = fixUrlNull(
            img?.attr("data-src")?.ifEmpty { null }
                ?: img?.attr("data-srcset")?.split(",")?.firstOrNull()?.trim()?.split(" ")?.firstOrNull()
                ?: img?.attr("srcset")?.split(",")?.firstOrNull()?.trim()?.split(" ")?.firstOrNull()
                ?: img?.attr("src")?.takeUnless { it.startsWith("data:") }
        )

        val score = element.selectFirst(".imdb, .score, span.rating, div.rating")?.text()?.trim()
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
            val response = app.get(
                "${mainUrl}/search?q=$query",
                referer = "$mainUrl/",
                headers = mapOf(
                    "X-Requested-With" to "fetch",
                    "Content-Type" to "application/json"
                ),
                interceptor = interceptor
            )

            val searchData = tryParseJson<SearchApiResponse>(response.text)
            if (searchData?.results != null && searchData.results.isNotEmpty()) {
                searchData.results.mapNotNull { html ->
                    val doc = Jsoup.parse(html)
                    val a = doc.selectFirst("a.search-result, a.poster, a") ?: return@mapNotNull null
                    val url = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
                    val title = a.selectFirst("h4.title, .title, strong")?.text()?.trim()
                        ?: a.attr("aria-label").ifEmpty { a.attr("title") }
                    val img = a.selectFirst("img")
                    val poster = fixUrlNull(
                        img?.attr("data-src")?.ifEmpty { null }
                            ?: img?.attr("src")?.takeUnless { it.startsWith("data:") }
                    )
                    if (url.contains("/dizi/")) {
                        newTvSeriesSearchResponse(title, url, TvType.TvSeries) { posterUrl = poster }
                    } else {
                        newMovieSearchResponse(title, url, TvType.Movie) { posterUrl = poster }
                    }
                }.distinctBy { it.url }
            } else {
                val doc = app.get("${mainUrl}/arama/?s=$query", referer = "$mainUrl/", interceptor = interceptor).document
                parseHomePage(doc)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    data class SearchApiResponse(val results: List<String>? = null)

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, referer = "$mainUrl/", interceptor = interceptor).document

        val title = doc.selectFirst("h1, meta[property='og:title']")?.let {
            if (it.tagName() == "meta") it.attr("content") else it.text().trim()
        }?.replace(" - HDFilmCehennemi", "")
            ?.replace(" izle", "")
            ?.replace(" Full HD izle", "")
            ?.trim() ?: return null

        val poster = fixUrlNull(
            doc.selectFirst("aside.post-info-poster img")?.attr("data-src")?.ifEmpty { null }
                ?: doc.selectFirst("aside.post-info-poster img")?.attr("src")?.takeUnless { it.startsWith("data:") }
                ?: doc.selectFirst("meta[property='og:image']")?.attr("content")
        )

        val description = doc.selectFirst("article.post-info-content p, div.description, article, meta[property='og:description']")?.let {
            if (it.tagName() == "meta") it.attr("content") else it.text().trim()
        }

        val year = doc.selectFirst("div.post-info-year-country a[href*='/yil/'], span.year")?.text()
            ?.filter { it.isDigit() }?.take(4)?.toIntOrNull()

        val score = doc.selectFirst(".post-info-imdb-rating span, span.imdb")?.text()?.trim()

        val durationStr = doc.selectFirst(".post-info-duration")?.text()?.trim()
        val durationMinutes = durationStr?.filter { it.isDigit() }?.toIntOrNull()

        val tags = doc.select(".post-info-genres a, .post-info-cats a").map { it.text().trim() }.distinct()

        // Extract Actors with Photos
        val actorElements = doc.select(".post-info-cast a[href*='/oyuncu/'], div.cast a[href*='/oyuncu/']")
        val actorList = actorElements.mapNotNull { a ->
            val actorName = a.selectFirst("strong")?.text()?.trim()
                ?: a.attr("title").ifEmpty { null }
                ?: return@mapNotNull null
            val img = a.selectFirst("img")
            val photoUrl = fixUrlNull(
                img?.attr("data-src")?.ifEmpty { null }
                    ?: img?.attr("src")?.takeUnless { it.startsWith("data:") }
            )
            Actor(actorName, photoUrl)
        }

        val recommendations = doc.select(".similar-movies a, .related-movies a, .poster-slider a, div.similar a")
            .mapNotNull { parseSearchElement(it) }
            .distinctBy { it.url }

        val isTv = url.contains("/dizi/") || doc.select(".seasons, .seasons-wrapper, a[href*='bolum']").isNotEmpty()

        if (isTv) {
            val episodes = doc.select("a[href*='bolum'], div.seasons-tab-content a.mini-poster, div.seasons a[href*='bolum']")
                .mapNotNull { a ->
                    val epHref = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
                    val epTitle = a.selectFirst(".mini-poster-title")?.text()?.trim() ?: a.text().trim()
                    val season = Regex("""(\d+)\.\s*Sezon""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                        ?: Regex("""/sezon-(\d+)/""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                        ?: 1
                    val episode = Regex("""(\d+)\.\s*B[öo]l[üu]m""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                        ?: Regex("""/bolum-(\d+)/""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                        ?: return@mapNotNull null
                    newEpisode(epHref) {
                        name = epTitle
                        this.season = season
                        this.episode = episode
                    }
                }.distinctBy { it.data }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster
                plot = description
                this.year = year
                this.score = Score.from10(score)
                this.duration = durationMinutes
                this.tags = tags
                addActors(actorList)
                this.recommendations = recommendations
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = poster
            plot = description
            this.year = year
            this.score = Score.from10(score)
            this.duration = durationMinutes
            this.tags = tags
            addActors(actorList)
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, referer = "$mainUrl/", interceptor = interceptor).document
        val iframes = mutableListOf<String>()

        fun addIframeUrl(rawUrl: String?) {
            if (rawUrl.isNullOrBlank()) return
            val fixed = fixUrlNull(rawUrl) ?: return
            val lower = fixed.lowercase()
            if (lower.endsWith(".webp") || lower.endsWith(".jpg") || lower.endsWith(".png") || lower.endsWith(".jpeg") || lower.endsWith(".svg") || lower.endsWith(".gif") || lower.endsWith(".css") || lower.endsWith(".js")) return
            if (lower.contains("youtube.com") || lower.contains("youtu.be")) return
            if (lower.contains("google") || lower.contains("analytics") || lower.contains("yandex") || lower.contains("facebook") || lower.contains("doubleclick") || lower.contains("adservice") || lower.contains("popunder")) return
            iframes.add(fixed)
        }

        // 1. Direct player iframes
        doc.select("div.player-container iframe, div.card-video iframe, iframe#player, iframe.player-iframe, iframe[src*='embed'], iframe[data-src*='embed']").forEach { el ->
            val src = el.attr("data-src").ifEmpty { el.attr("src") }.ifEmpty { el.attr("data-lazy-src") }
            addIframeUrl(src)
        }

        // 2. Player navigation tabs & buttons
        doc.select("nav.card-nav a, a.card-nav-link, button.card-nav-link, [data-video], [data-url]").forEach { el ->
            val src = el.attr("data-video").ifEmpty { el.attr("data-url") }.ifEmpty { el.attr("href") }
            if (src.isNotEmpty() && !src.startsWith("#") && !src.startsWith("javascript:")) {
                val fullUrl = if (src.startsWith("/")) {
                    if (src.startsWith("/video/")) "https://hdfilmcehennemi.mobi$src" else "$mainUrl$src"
                } else src
                addIframeUrl(fullUrl)
            }
        }

        val distinctIframes = iframes.distinct()
        var found = false

        distinctIframes.forEach { iframe ->
            try {
                if (iframe.contains("rapidrame") || iframe.contains("hdfilmcehennemi") || iframe.contains("playmix") || iframe.contains("close") || iframe.contains("embed")) {
                    RapidrameExtractor().getUrl(iframe, "$mainUrl/", subtitleCallback) { link ->
                        found = true
                        callback(link)
                    }
                }

                if (!found) {
                    if (loadExtractor(iframe, "$mainUrl/", subtitleCallback) { link ->
                        found = true
                        callback(link)
                    }) {
                        found = true
                    }
                }
            } catch (_: Exception) {}
        }
        return found
    }
}
