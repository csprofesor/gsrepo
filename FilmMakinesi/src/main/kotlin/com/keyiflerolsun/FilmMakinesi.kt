package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.network.CloudflareKiller
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class FilmMakinesi : MainAPI() {
    override var mainUrl = "https://filmmakinesi.to"
    override var name = "FilmMakinesi"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // ! CloudFlare v2
    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor      by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller) : Interceptor {
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

    override val mainPage = mainPageOf(
        "${mainUrl}/" to "Ana Sayfa",
        "${mainUrl}/filmler-1/" to "Son Eklenen Filmler",
        "${mainUrl}/yabanci-dizi-izle-1/" to "Diziler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val url = if (page <= 1) request.data else "${request.data.removeSuffix("/")}/sayfa/$page/"
            val doc = app.get(
                url, 
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36",
                    "Referer" to "${mainUrl}/"
                ),
                interceptor = interceptor
            ).document
            val home = parseHomePage(doc)
            newHomePageResponse(request.name, home, hasNext = home.isNotEmpty())
        } catch (e: Exception) {
            Log.e(name, "getMainPage failed: ${e.message}")
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    private fun parseHomePage(doc: Document): List<SearchResponse> {
        return doc.select("a.item, a.slide, div.item-relative a.item, div.movie-box a, .item-movie a, article a")
            .mapNotNull { parseSearchElement(it) }
            .distinctBy { it.url }
    }

    private fun parseSearchElement(element: Element): SearchResponse? {
        val link = if (element.tagName() == "a") element else element.selectFirst("a") ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null
        val title = element.attr("data-title").ifEmpty { null }
            ?: element.selectFirst(".title, .item-title, h4, h3, .movie-title, .name")?.text()?.trim()
            ?: link.attr("title").ifEmpty { null }
            ?: element.selectFirst("img")?.attr("alt")?.trim()
            ?: return null

        val poster = element.selectFirst("img")?.let {
            val src = it.attr("src").ifEmpty { it.attr("data-src") }.ifEmpty { it.attr("data-original") }
            fixUrlNull(src)
        }

        val score = element.attr("data-score").ifEmpty { null }
            ?: element.selectFirst(".rating, .imdb-score span, .imdb")?.text()?.trim()

        return if (href.contains("/dizi/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = poster
                this.score = Score.from10(score)
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = poster
                this.score = Score.from10(score)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val doc = app.get(
                "${mainUrl}/arama/?s=$query", 
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36",
                    "Referer" to "${mainUrl}/"
                ),
                interceptor = interceptor
            ).document
            doc.select("a.item, div.item-relative a.item, div.movie-box a, .item-movie a, article a")
                .mapNotNull { parseSearchElement(it) }
                .distinctBy { it.url }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(
            url, 
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36",
                "Referer" to "${mainUrl}/"
            ),
            interceptor = interceptor
        ).document

        val title = doc.selectFirst("h1, meta[property='og:title']")?.let {
            if (it.tagName() == "meta") it.attr("content") else it.text().trim()
        }?.replace(" - FilmMakinesi", "")?.replace(" izle", "")?.trim() ?: return null

        val poster = fixUrlNull(doc.selectFirst("meta[property='og:image']")?.attr("content"))
            ?: fixUrlNull(doc.selectFirst("div.poster img, .movie-poster img")?.attr("src"))
        val description = doc.selectFirst("meta[property='og:description'], div.description, div.info-content .description, .plot")?.let {
            if (it.tagName() == "meta") it.attr("content") else it.text().trim()
        }
        val year = doc.selectFirst("div.info span:first-child, span.year, .year")?.text()?.filter { it.isDigit() }?.take(4)?.toIntOrNull()
        val score = doc.selectFirst("div.imdb-score span, .rating, .imdb")?.text()?.trim()

        if (url.contains("/dizi/")) {
            val episodes = doc.select("a[href*='bolum'], div.episodes a, .episode-list a").mapNotNull { a ->
                val epHref = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
                val epTitle = a.text().trim()
                val season = Regex("""(\d+)\.\s*Sezon""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""/sezon-(\d+)/""").find(epHref)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val episode = Regex("""(\d+)\.\s*B[öo]l[üu]m""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""/bolum-(\d+)/""").find(epHref)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
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
        val doc = try {
            app.get(
                data, 
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36",
                    "Referer" to "${mainUrl}/"
                ),
                interceptor = interceptor
            ).document
        } catch (e: Exception) {
            Log.e(name, "loadLinks app.get failed: ${e.message}")
            return false
        }
        val candidates = doc.select("iframe").mapNotNull {
            val src = it.attr("data-src").ifEmpty { it.attr("src") }.ifEmpty { it.attr("data-lazy-src") }
            fixUrlNull(src)?.takeUnless { it.contains("youtube.com") || it.contains("youtu.be") }
        }.distinct().toMutableList()

        doc.select(".video-parts a[data-video_url], .parts a[data-src], .player-option[data-url]").forEach {
            val src = fixUrlNull(it.attr("data-video_url").ifEmpty { it.attr("data-src") }.ifEmpty { it.attr("data-url") })
            if (src != null && !candidates.contains(src)) candidates.add(0, src)
        }

        var found = false
        candidates.forEach { embedUrl ->
            try {
                loadExtractor(embedUrl, data, subtitleCallback) { link ->
                    found = true
                    callback(link)
                }

                if (!found) {
                    if (embedUrl.contains("closeload", ignoreCase = true)) {
                        CloseLoadExtractor().getUrl(embedUrl, data, subtitleCallback) { link ->
                            found = true
                            callback(link)
                        }
                    } else if (embedUrl.contains("rapid", ignoreCase = true)) {
                        RapidExtractor().getUrl(embedUrl, data, subtitleCallback) { link ->
                            found = true
                            callback(link)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(name, "Extractor failed: $embedUrl - ${e.message}")
            }
        }
        return found
    }
}
