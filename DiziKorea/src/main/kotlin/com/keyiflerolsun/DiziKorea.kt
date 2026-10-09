// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup

class DiziKorea : MainAPI() {
    override var mainUrl              = "https://dizikorea3.com"
    override var name                 = "DiziKorea"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.AsianDrama)

        override var sequentialMainPage = true        // * https://recloudstream.github.io/dokka/-cloudstream/com.lagradost.cloudstream3/-main-a-p-i/index.html#-2049735995%2FProperties%2F101969414
    override var sequentialMainPageDelay       = 150L  // ? 0.15 saniye
    override var sequentialMainPageScrollDelay = 150L  // ? 0.15 saniye

    // ! CloudFlare v2
    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor      by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller): Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request  = chain.request()
            val response = chain.proceed(request)
            val doc      = Jsoup.parse(response.peekBody(1024 * 1024).string())

            if (doc.html().contains("Just a moment")) {
                return cloudflareKiller.intercept(chain)
            }

            return response
        }
    }

    override val mainPage = mainPageOf(
        "${mainUrl}/kore-dizileri-izle-dq1/sayfa/" to "Kore Dizileri",
        "${mainUrl}/cin-dizileri/sayfa/"         to "Çin Dizileri",
        "${mainUrl}/japon-dizileri/sayfa/"       to "Japon Dizileri",
        "${mainUrl}/tayland-dizileri/sayfa/"     to "Tayland Dizileri",
        "${mainUrl}/tayvan-dizileri/sayfa/"      to "Tayvan Dizileri",
        "${mainUrl}/filipin-dizileri/sayfa/"     to "Filipin Dizileri",
        "${mainUrl}/filmler/sayfa/"              to "Filmler",
        "${mainUrl}/cin-filmleri/sayfa/"         to "Çin Filmleri",
        "${mainUrl}/tayland-filmleri/sayfa/"     to "Tayland Filmleri",
        "${mainUrl}/efsane-diziler/sayfa/"       to "Efsane Diziler",
        "${mainUrl}/dizi-arsivi/sayfa/"         to "Dizi Arşivi"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}${page}", interceptor = interceptor).document
        Log.d("DZK", "Ana sayfa HTML içeriği:\n${document.outerHtml()}")
        val home     = document.select("a.poster-card").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title     = this.selectFirst("span.poster-card-title")?.text()?.trim()
            ?: this.selectFirst("div.poster-card-meta")?.text()?.trim()
            ?: this.selectFirst(".title, h3, h2")?.text()?.trim()
            ?: return null
        val href      = fixUrlNull(this.attr("href")) ?: return null
        val imgEl     = this.selectFirst("div.poster-card-image img, img")

        var posterUrl = imgEl?.attr("data-src")?.takeIf { it.isNotEmpty() }
            ?: imgEl?.attr("data-lazy-src")?.takeIf { it.isNotEmpty() }
            ?: imgEl?.attr("data-original")?.takeIf { it.isNotEmpty() }
            ?: imgEl?.attr("src")?.takeIf { it.isNotEmpty() }

        if (posterUrl != null && (posterUrl.contains("data:image") || posterUrl.contains("placeholder") || posterUrl.contains("blank") || posterUrl.contains("grey") || posterUrl.contains("gray"))) {
            posterUrl = imgEl?.attr("data-src")?.takeIf { it.isNotEmpty() }
                ?: imgEl?.attr("srcset")?.split(",")?.firstOrNull()?.trim()?.split(" ")?.firstOrNull()
        }

        val cleanPoster = fixUrlNull(posterUrl)
        val isMovie     = href.contains("/film/")
        val tvType      = if (isMovie) TvType.Movie else TvType.AsianDrama

        return if (isMovie) {
            newMovieSearchResponse(title, href, tvType) { this.posterUrl = cleanPoster }
        } else {
            newTvSeriesSearchResponse(title, href, tvType) { this.posterUrl = cleanPoster }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.get(
            "${mainUrl}/ara?q=$query",
            interceptor = interceptor
        ).parsedSafe<KoreaSearchResponse>()

        val results = mutableListOf<SearchResponse>()
        response?.items?.forEach { item ->
            val href      = fixUrl(item.url)
            val title     = item.title
            val posterUrl = fixUrlNull(item.poster)

            val isMovie   = href.contains("/film/")
            val tvType    = if (isMovie) TvType.Movie else TvType.AsianDrama

            if (isMovie) {
                results.add(newMovieSearchResponse(title, href, tvType) {
                    this.posterUrl = posterUrl
                })
            } else {
                results.add(newTvSeriesSearchResponse(title, href, tvType) {
                    this.posterUrl = posterUrl
                })
            }
        }

        return results
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, interceptor = interceptor).document

        val title       = document.selectFirst("h1.series-title, h1.watch-title, h1.entry-title, h1")?.text()?.trim() ?: return null
        val imgEl       = document.selectFirst("div.series-hero-poster img, img.sidebar-poster, div.poster img, img.poster")
        val poster      = fixUrlNull(
            imgEl?.attr("data-src")?.takeIf { it.isNotEmpty() }
            ?: imgEl?.attr("data-lazy-src")?.takeIf { it.isNotEmpty() }
            ?: imgEl?.attr("src")?.takeIf { it.isNotEmpty() }
        )

        val description = document.selectFirst("div.series-synopsis, div.series-summary, div.summary, div.description, div.overview")?.text()?.trim()
        val year        = document.selectFirst("span.series-year, span.year")?.text()?.trim()?.toIntOrNull()
        val ratingStr   = document.selectFirst("span.series-rating, span.rating, div.rating")?.text()?.trim()

        if (url.contains("/dizi/")) {
            val episodes    = mutableListOf<Episode>()
            document.select("div.episode-list").forEach { seasonGroup ->
                val epSeason = seasonGroup.attr("data-season").toIntOrNull() ?: 1

                seasonGroup.select("a.episode-item").forEach ep@ { episodeElement ->
                    val epHref    = fixUrlNull(episodeElement.attr("href")) ?: return@ep
                    val epEpisode = episodeElement.selectFirst("span.ep-number")?.text()?.trim()?.toIntOrNull()

                    episodes.add(newEpisode(epHref) {
                        this.name = "${epSeason}. Sezon ${epEpisode ?: 1}. Bölüm"
                        this.season = epSeason
                        this.episode = epEpisode
                    })
                }
            }

            if (episodes.isEmpty()) {
                document.select("a.episode-item").forEach ep@ { episodeElement ->
                    val epHref = fixUrlNull(episodeElement.attr("href")) ?: return@ep
                    val epEpisode = episodeElement.selectFirst("span.ep-number")?.text()?.trim()?.toIntOrNull()

                    episodes.add(newEpisode(epHref) {
                        this.name = episodeElement.text().trim()
                        this.episode = epEpisode
                    })
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.AsianDrama, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.score = Score.from10(ratingStr)
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.score = Score.from10(ratingStr)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("DZK", "data » $data")
        val document = app.get(data, interceptor = interceptor).document

        document.select("div.player-source iframe, iframe").forEach {
            val rawHhs = it.attr("data-src").ifEmpty { it.attr("src") }
            Log.d("DZK", "Found button with data-src/src: $rawHhs")

            val iframe = fixUrlNull(rawHhs) ?: return@forEach
            Log.d("DZK", "iframe » $iframe")

            loadExtractor(iframe, "$mainUrl/", subtitleCallback, callback)
        }

        return true
    }
}
