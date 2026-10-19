package com.gsrepo

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class DiziMag : MainAPI() {
    override var mainUrl = "https://dizimag.onl"
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
        "$mainUrl/" to "Son Eklenenler",
        "$mainUrl/diziler" to "Diziler",
        "$mainUrl/filmler" to "Filmler",
        "$mainUrl/imdbtop250" to "IMDb Top 250",
        "$mainUrl/seri-filmler" to "Seri Filmler",
        "$mainUrl/tum-diziler" to "Tüm Diziler",
    )

    private val globalSeenUrls = mutableSetOf<String>()

    private fun extractPoster(element: Element): String? {
        val imgTags = if (element.tagName() == "img") listOf(element) else element.select("img")
        for (img in imgTags) {
            for (attr in listOf("data-src", "data-lazy-src", "data-original", "src")) {
                val rawSrc = img.attr(attr).trim()
                if (rawSrc.isNotBlank() && !rawSrc.startsWith("data:image")) {
                    val lower = rawSrc.lowercase()
                    if (!lower.endsWith(".svg") && !lower.contains("/logo/") && !lower.contains("/icons/") && !lower.contains("avatar")) {
                        val fixed = fixUrlNull(rawSrc)
                        if (fixed != null) return fixed
                    }
                }
            }
        }
        return null
    }

    private fun extractRating(element: Element): String? {
        val ratingEl = element.selectFirst(".rating, .imdb, span.rating, div.imdb, .poster-long-on .rating")
        if (ratingEl != null) {
            val text = ratingEl.text().trim()
            val match = Regex("""\b([1-9]\.\d|10(?:\.0)?|[1-9])\b""").find(text)
            if (match != null) {
                val score = match.groupValues[1]
                if (score != "0" && score != "0.0") {
                    return score
                }
            }
        }
        return null
    }

    private fun extractTitle(card: Element, aTag: Element): String? {
        var rawTitle = card.selectFirst("h1, h2, h3, h4")?.text()?.trim()
            ?: aTag.attr("title").takeIf { it.isNotBlank() }
            ?: card.selectFirst("img")?.attr("alt")?.takeIf { it.isNotBlank() && !it.startsWith("/") }
            ?: aTag.text().trim().takeIf { it.isNotBlank() }
            ?: return null

        rawTitle = rawTitle.split("\n")[0].trim()
        rawTitle = Regex("""\s*(Sezon|Bölüm|\d+\.\s*Sezon|\d+\.\s*Bölüm).*""", RegexOption.IGNORE_CASE).replace(rawTitle, "").trim()
        if (rawTitle.startsWith("/")) rawTitle = rawTitle.removePrefix("/").trim()
        return rawTitle.takeIf { it.isNotBlank() }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            request.data
        } else {
            val base = request.data.removeSuffix("/")
            if (base.contains("?")) {
                "$base&page=$page"
            } else {
                "$base?page=$page"
            }
        }

        if (page == 1 && request.data == "$mainUrl/") {
            globalSeenUrls.clear()
        }

        val document = app.get(url, headers = mapOf("User-Agent" to "Mozilla/5.0")).document
        val home = mutableListOf<SearchResponse>()

        val cards = document.select(".poster-long, .poster-xs, .filter-result-box, .poster-md, .poster-mini, .all-time-trends-series, .serial-movies-item")
            .ifEmpty { document.select("a[href*='/dizi/'], a[href*='/film/'], a[href*='/seri-filmler/']") }

        for (card in cards) {
            val aTag = if (card.tagName() == "a") card else card.selectFirst("a[href]") ?: continue
            val rawHref = aTag.attr("href")
            val href = fixUrlNull(rawHref) ?: continue
            if (href == "$mainUrl/" || href.endsWith("/diziler") || href.endsWith("/filmler")) continue

            var cleanHref = href
            if (href.contains("/sezon-") || href.contains("/bolum-") || href.contains("-bolum-")) {
                cleanHref = href.substringBefore("/sezon-").substringBefore("/bolum-").substringBefore("-bolum-")
            }

            if (globalSeenUrls.contains(cleanHref)) continue

            val posterUrl = extractPoster(card) ?: extractPoster(aTag)
            val title = extractTitle(card, aTag) ?: continue
            val rating = extractRating(card)

            val isTvSeries = cleanHref.contains("/dizi/") || (!cleanHref.contains("/film/") && !cleanHref.contains("/seri-filmler/"))

            val response = if (isTvSeries) {
                newTvSeriesSearchResponse(title, cleanHref, TvType.TvSeries) {
                    this.posterUrl = posterUrl
                    this.score = Score.from10(rating)
                }
            } else {
                newMovieSearchResponse(title, cleanHref, TvType.Movie) {
                    this.posterUrl = posterUrl
                    this.score = Score.from10(rating)
                }
            }

            globalSeenUrls.add(cleanHref)
            home.add(response)
        }

        return newHomePageResponse(request.name, home.distinctBy { it.url }, hasNext = home.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        try {
            val responseText = app.post(
                "$mainUrl/search",
                data = mapOf("query" to query),
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to "$mainUrl/"
                )
            ).text

            val htmlContent = try {
                JSONObject(responseText).optString("theme", responseText)
            } catch (_: Exception) {
                responseText
            }

            val document = Jsoup.parse(htmlContent)

            val cards = document.select(".poster-long, .poster-xs, .filter-result-box, .poster-md, .poster-mini, .leftbar-search-result-in, a[href*='/dizi/'], a[href*='/film/']")

            cards.forEach { card ->
                val aTag = if (card.tagName() == "a") card else card.selectFirst("a[href]") ?: return@forEach
                val rawHref = aTag.attr("href")
                val href = fixUrlNull(rawHref) ?: return@forEach
                if (href == "$mainUrl/" || href.endsWith("/diziler") || href.endsWith("/filmler")) return@forEach

                var cleanHref = href
                if (href.contains("/sezon-") || href.contains("/bolum-") || href.contains("-bolum-")) {
                    cleanHref = href.substringBefore("/sezon-").substringBefore("/bolum-").substringBefore("-bolum-")
                }

                val posterUrl = extractPoster(card) ?: extractPoster(aTag)
                val title = extractTitle(card, aTag) ?: return@forEach
                val rating = extractRating(card)

                val isTvSeries = cleanHref.contains("/dizi/") || !cleanHref.contains("/film/")
                if (isTvSeries) {
                    results.add(newTvSeriesSearchResponse(title, cleanHref, TvType.TvSeries) {
                        this.posterUrl = posterUrl
                        this.score = Score.from10(rating)
                    })
                } else {
                    results.add(newMovieSearchResponse(title, cleanHref, TvType.Movie) {
                        this.posterUrl = posterUrl
                        this.score = Score.from10(rating)
                    })
                }
            }
        } catch (_: Exception) {}

        return results.distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val originalDocument = app.get(url, referer = "$mainUrl/").document

        val isEpisodeUrl = url.contains("/sezon-") || url.contains("/bolum-") || url.contains("-bolum-")
        val seriesUrl = if (isEpisodeUrl) {
            originalDocument.select("a[href*='/dizi/']").mapNotNull { fixUrlNull(it.attr("href")) }
                .firstOrNull { !it.contains("/sezon-") && !it.contains("/bolum-") && !it.contains("-bolum-") }
        } else null

        val targetUrl = seriesUrl ?: url
        val document = if (targetUrl != url) app.get(targetUrl, referer = "$mainUrl/").document else originalDocument

        val titleRaw = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")
            ?: return null

        val title = titleRaw.substringBefore(" izle").substringBefore(" |").substringBefore(" Türkçe").trim()

        val poster = fixUrlNull(
            document.selectFirst("meta[property='og:image']")?.attr("content")
                ?: extractPoster(document)
        )

        val description = document.selectFirst("meta[name='description']")?.attr("content")
            ?: document.selectFirst("meta[property='og:description']")?.attr("content")

        val ratingText = document.select("div.series-profile-info, li:contains(IMDB Puanı), .episode-date, .imdb").text()
        val rating = Regex("""\b([1-9]\.\d|10(?:\.0)?|[1-9])\b""").find(ratingText ?: "")?.value

        val actors = document.select("a[href*='/oyuncu/']").mapNotNull { actorTag ->
            val actorName = actorTag.text().trim().split("\n")[0].trim()
            if (actorName.isNotBlank() && !actorName.contains("Oyuncular", ignoreCase = true)) {
                ActorData(Actor(actorName, null))
            } else null
        }.distinctBy { it.actor.name }

        val isTvSeries = targetUrl.contains("/dizi/")

        if (isTvSeries) {
            val episodes = mutableListOf<Episode>()

            document.select("a[href*='/sezon-'], a[href*='/bolum-'], a[href*='-sezon-'], a[href*='-bolum-']").forEach { aTag ->
                val epHref = fixUrlNull(aTag.attr("href")) ?: return@forEach
                if (!epHref.contains("sezon") && !epHref.contains("bolum")) return@forEach

                val epText = aTag.text().trim()

                val seasonNum = Regex("""sezon-(\d+)""", RegexOption.IGNORE_CASE).find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""(\d+)\.\s*Sezon""", RegexOption.IGNORE_CASE).find(epText)?.groupValues?.get(1)?.toIntOrNull()
                    ?: 1

                val episodeNum = Regex("""bolum-(\d+)""", RegexOption.IGNORE_CASE).find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""(\d+)\.\s*Bölüm""", RegexOption.IGNORE_CASE).find(epText)?.groupValues?.get(1)?.toIntOrNull()
                    ?: 1

                episodes.add(
                    newEpisode(epHref) {
                        this.name = epText.takeIf { it.isNotBlank() && !it.startsWith("http") } ?: "Sezon $seasonNum Bölüm $episodeNum"
                        this.season = seasonNum
                        this.episode = episodeNum
                    }
                )
            }

            return newTvSeriesLoadResponse(title, targetUrl, TvType.TvSeries, episodes.distinctBy { it.data }) {
                this.posterUrl = poster
                this.plot = description
                this.score = Score.from10(rating)
                this.actors = actors
            }
        } else {
            return newMovieLoadResponse(title, targetUrl, TvType.Movie, targetUrl) {
                this.posterUrl = poster
                this.plot = description
                this.score = Score.from10(rating)
                this.actors = actors
            }
        }
    }

    private fun unescapeUnicode(str: String): String {
        return Regex("""\\u([0-9a-fA-F]{4})""").replace(str) { match ->
            match.groupValues[1].toInt(16).toChar().toString()
        }
    }

    private fun isMediaFile(url: String): Boolean {
        val lower = url.lowercase()
        return lower.endsWith(".webp") || lower.endsWith(".jpg") || lower.endsWith(".png") ||
               lower.endsWith(".svg") || lower.endsWith(".jpeg") || lower.contains("/uploads/")
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, referer = "$mainUrl/").document

        val iframes = mutableSetOf<String>()

        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")?.takeIf { it.isNotBlank() } ?: iframe.attr("data-src")
            val fixed = fixUrlNull(src)
            if (fixed != null && !isMediaFile(fixed)) {
                iframes.add(fixed)
            }
        }

        document.select("[data-frame], [data-hhs], [data-video], [data-url], [data-embed]").forEach { el ->
            for (attr in listOf("data-frame", "data-hhs", "data-video", "data-url", "data-embed")) {
                val value = el.attr(attr)
                if (value.startsWith("http://") || value.startsWith("https://") || value.startsWith("//")) {
                    val fixed = fixUrlNull(value)
                    if (fixed != null && !isMediaFile(fixed)) {
                        iframes.add(fixed)
                    }
                }
            }
        }

        var foundLinks = false

        for (iframe in iframes) {
            try {
                var targetUrl = iframe
                var referer = "$mainUrl/"

                if (iframe.contains("yabancidizim.com/rplayer/")) {
                    val hash = iframe.substringAfter("rplayer/").substringBefore(".html")
                    if (hash.isNotBlank()) {
                        targetUrl = "https://ksdpictures.site/ch/$hash?st=ZGl6aW1hZy5vbmU="
                        referer = "https://yabancidizim.com/"
                    }
                }

                val playerResponse = app.get(targetUrl, headers = mapOf("Referer" to referer), timeout = 10)
                val playerHtml = playerResponse.text

                val videoMatch = Regex("""videoFile\s*=\s*["']([^"']+)["']""").find(playerHtml)
                    ?: Regex("""var\s+SOURCE\s*=\s*["']([^"']+)["']""").find(playerHtml)
                    ?: Regex("""file:\s*["']([^"']+)["']""").find(playerHtml)

                if (videoMatch != null) {
                    val sourceUrl = videoMatch.groupValues[1].replace("\\/", "/")
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = this.name,
                            url = sourceUrl,
                            type = if (sourceUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = targetUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    foundLinks = true
                }

                Regex("""\{"file"\s*:\s*"([^"]+)"\s*,\s*"label"\s*:\s*"([^"]+)"""").findAll(playerHtml).forEach { match ->
                    val file = fixUrlNull(match.groupValues[1].replace("\\/", "/")) ?: return@forEach
                    val label = unescapeUnicode(match.groupValues[2]).replace("\\/", "/")
                    subtitleCallback(newSubtitleFile(label, file))
                }
            } catch (_: Exception) { }

            val extracted = loadExtractor(iframe, "$mainUrl/", subtitleCallback, callback)
            if (extracted) {
                foundLinks = true
            } else {
                try {
                    val resolver = WebViewResolver(Regex(".*(?:\\.m3u8|\\.mp4|\\.txt|/hls/|playlist).*"))
                    val wvResp = app.get(iframe, headers = mapOf("Referer" to "$mainUrl/"), interceptor = resolver)
                    val wvUrl = wvResp.url
                    if (wvUrl.contains(".m3u8") || wvUrl.contains("/hls/") || wvUrl.contains("playlist") || wvUrl.contains(".mp4") || wvUrl.contains(".mkv")) {
                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = this.name,
                                url = wvUrl,
                                type = if (wvUrl.contains(".m3u8") || wvUrl.contains("/hls/")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = iframe
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        foundLinks = true
                    }
                } catch (_: Exception) { }
            }
        }

        return foundLinks || iframes.isNotEmpty()
    }
}
