// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.network.CloudflareKiller
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup

class DiziMom : MainAPI() {
    override var mainUrl              = "https://ddizimom.net"
    override var name                 = "DiziMom"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.TvSeries, TvType.Movie, TvType.AsianDrama, TvType.Anime)

    override var sequentialMainPage = true
    override var sequentialMainPageDelay       = 50L
    override var sequentialMainPageScrollDelay = 50L

    // ! CloudFlare v2
    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor      by lazy { CloudflareInterceptor(cloudflareKiller) }

    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
    )

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
        "$mainUrl/diziler"         to "Diziler",
        "$mainUrl/filmler"         to "Filmler",
        "$mainUrl/tur/aksiyon"     to "Aksiyon",
        "$mainUrl/tur/dram"        to "Dram",
        "$mainUrl/tur/komedi"      to "Komedi",
        "$mainUrl/tur/bilim-kurgu" to "Bilim-Kurgu",
        "$mainUrl/tur/fantastik"  to "Fantastik",
        "$mainUrl/tur/gerilim"     to "Gerilim",
        "$mainUrl/tur/korku"       to "Korku",
        "$mainUrl/tur/anime"       to "Animeler",
        "$mainUrl/tur/belgesel"    to "Belgeseller",
    )

    private fun Element.toSearchResponse(): SearchResponse? {
        val href = fixUrlNull(this.attr("href")) ?: return null
        if (!href.contains("/dizi/") && !href.contains("/film/") && !href.contains("/anime/")) return null
        if (href.contains("/sezon-") || href.contains("/bolum-")) return null

        val h3 = this.selectFirst("h3, h2, .entry-title")
        val img = this.selectFirst("img")

        val rawTitle = h3?.text()?.trim()
            ?: img?.attr("alt")?.replace(" Dizi izle", "")?.replace(" Film izle", "")?.trim()
            ?: this.attr("title").replace(" izle", "").trim()

        if (rawTitle.isBlank()) return null

        val posterUrl = fixUrlNull(
            img?.attr("src")
                ?: img?.attr("data-src")
        )

        val scoreStr = this.selectFirst("span.badge-rating, .rating, .score")?.text()?.trim()
            ?: Regex("""(?i)(?:imdb|score|puan)\s*:?\s*([0-9]+(?:[.,][0-9]+)?)""").find(this.text())?.groupValues?.get(1)

        val tvType = when {
            href.contains("/film/") -> TvType.Movie
            href.contains("/anime/") -> TvType.Anime
            else -> TvType.TvSeries
        }

        return if (tvType == TvType.Movie) {
            newMovieSearchResponse(rawTitle, href, tvType) {
                this.posterUrl = posterUrl
                this.score     = Score.from10(scoreStr)
            }
        } else {
            newTvSeriesSearchResponse(rawTitle, href, tvType) {
                this.posterUrl = posterUrl
                this.score     = Score.from10(scoreStr)
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            request.data.trimEnd('/')
        } else {
            if (request.data.contains("?")) "${request.data}&page=$page" else "${request.data}?page=$page"
        }

        val document = try {
            app.get(url, headers = baseHeaders, interceptor = interceptor).document
        } catch (e: Exception) {
            Log.d("DZM", "Error loading main page: ${e.message}")
            return newHomePageResponse(request.name, emptyList())
        }

        val items = document.select("a.group, a[href*='/dizi/'], a[href*='/film/'], a[href*='/anime/']")
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/ara?q=${query.trim().replace(" ", "+")}"
        val document = try {
            app.get(url, headers = baseHeaders, interceptor = interceptor).document
        } catch (_: Exception) {
            return emptyList()
        }

        return document.select("a.group, a[href*='/dizi/'], a[href*='/film/'], a[href*='/anime/']")
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = try {
            app.get(url, headers = baseHeaders, interceptor = interceptor).document
        } catch (_: Exception) {
            return null
        }

        val isMovie = url.contains("/film/")
        val tvType = if (isMovie) TvType.Movie else if (url.contains("/anime/")) TvType.Anime else TvType.TvSeries

        val title = document.selectFirst("h1, h2.title")?.text()
            ?.replace(" Dizi izle", "")
            ?.replace(" Film izle", "")
            ?.trim() ?: return null

        val poster = fixUrlNull(
            document.selectFirst("img[src*='posters'], img[src*='storage/tmdb'], div.poster img")?.attr("src")
                ?: document.selectFirst("img")?.attr("src")
        )

        val year = document.selectFirst("p.text-content-muted, span.year")?.text()
            ?.let { Regex("""\b(19\d\d|20\d\d)\b""").find(it)?.value?.toIntOrNull() }

        val ratingStr = document.selectFirst("span.badge-rating, .rating, .score")?.text()?.trim()

        val description = document.selectFirst("div.description, div.summary, p.lead, div.konu, .overview")?.text()?.trim()

        val tags = document.select("a[href*='/tur/']").map { it.text().trim() }.filter { it.isNotBlank() }.distinct()

        val actorElements = document.select("div.cast-item, div.actor-item, div.oyuncu, .actors a")
        val actors = actorElements.mapNotNull { el ->
            val actorName = el.selectFirst("span, p, .name")?.text()?.trim() ?: el.text().trim().takeIf { it.isNotBlank() }
            if (actorName == null) return@mapNotNull null
            val actorImage = fixUrlNull(el.selectFirst("img")?.attr("src"))
            Actor(actorName, actorImage)
        }

        if (isMovie) {
            return newMovieLoadResponse(title, url, tvType, data = url) {
                this.posterUrl = poster
                this.year      = year
                this.plot      = description
                this.tags      = tags
                this.score     = Score.from10(ratingStr)
                addActors(actors)
            }
        }

        // Multi-season TV Series episode parsing
        val seasonUrls = document.select("a[href*='?sezon=']").mapNotNull {
            fixUrlNull(it.attr("href"))
        }.distinct()

        val allDocs = mutableListOf(document)
        for (sUrl in seasonUrls) {
            if (sUrl != url) {
                try {
                    val sDoc = app.get(sUrl, headers = baseHeaders, interceptor = interceptor).document
                    allDocs.add(sDoc)
                } catch (_: Exception) {}
            }
        }

        val episodes = mutableListOf<Episode>()
        val seenEpUrls = mutableSetOf<String>()

        for (doc in allDocs) {
            val epElements = doc.select("a[href*='/sezon-'][href*='/bolum-']")
            for (el in epElements) {
                val epHref = fixUrlNull(el.attr("href")) ?: continue
                if (seenEpUrls.contains(epHref)) continue
                seenEpUrls.add(epHref)

                val epSeason = Regex("""sezon-(\d+)""", RegexOption.IGNORE_CASE).find(epHref)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val epEpisode = Regex("""bolum-(\d+)""", RegexOption.IGNORE_CASE).find(epHref)?.groupValues?.get(1)?.toIntOrNull()

                val rawEpName = el.selectFirst("span, p, div")?.text()?.trim()
                val epName = if (!rawEpName.isNullOrBlank()) {
                    rawEpName.replace(" Dizi izle", "").replace(title, "", ignoreCase = true).trim()
                } else {
                    el.text().trim().replace(" Dizi izle", "").replace(title, "", ignoreCase = true).trim()
                }

                episodes.add(
                    newEpisode(epHref) {
                        this.name    = epName
                        this.season  = epSeason
                        this.episode = epEpisode
                    }
                )
            }
        }

        return newTvSeriesLoadResponse(title, url, tvType, episodes) {
            this.posterUrl = poster
            this.year      = year
            this.plot      = description
            this.tags      = tags
            this.score     = Score.from10(ratingStr)
            addActors(actors)
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("DZM", "data » $data")

        val document = try {
            app.get(data, headers = baseHeaders, interceptor = interceptor).document
        } catch (e: Exception) {
            Log.d("DZM", "Error getting page: ${e.message}")
            return false
        }

        val html = document.html()
        var foundAny = false

        // Extract data-pv="SLUG"
        val pvRegex = Regex("""data-pv="([^"]+)"""")
        val pvSlugs = pvRegex.findAll(html).map { it.groupValues[1] }.distinct().toList()

        for (slug in pvSlugs) {
            val sPhpUrl = "https://play2.pilavyerplay.top/assets/js/s.php?s=$slug"
            try {
                val embedDoc = app.get(sPhpUrl, referer = "$mainUrl/", headers = baseHeaders, interceptor = interceptor).text
                val playerMatch = Regex("""window\.__PLAYER__\s*=\s*(\{.*?\}|\{.*?\n\});""").find(embedDoc)
                if (playerMatch != null) {
                    val playerJsonStr = playerMatch.groupValues[1]
                    val streamMatch = Regex(""""stream"\s*:\s*"([^"]+)"""").find(playerJsonStr)
                    val streamUrl = streamMatch?.groupValues?.get(1)?.replace("""\u0026""", "&")
                    if (!streamUrl.isNullOrBlank()) {
                        callback.invoke(
                            newExtractorLink(
                                source = "Pilavyer",
                                name = "Pilavyer",
                                url = streamUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = "https://play2.pilavyerplay.top/"
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        foundAny = true
                    }
                }
            } catch (e: Exception) {
                Log.d("DZM", "Error loading Pilavyer embed: ${e.message}")
            }
        }

        // Also check iframes
        val iframes = document.select("iframe").mapNotNull {
            val src = it.attr("data-src").takeIf { s -> s.isNotBlank() && s != "about:blank" }
                ?: it.attr("src").takeIf { s -> s.isNotBlank() && s != "about:blank" }
            src?.let { urlStr -> fixUrlNull(urlStr) }
        }.distinct()

        for (iframe in iframes) {
            if (iframe.contains("youtube.com")) continue
            Log.d("DZM", "iframe » $iframe")
            loadExtractor(iframe, "$mainUrl/", subtitleCallback, callback)
            foundAny = true
        }

        return foundAny
    }
}
