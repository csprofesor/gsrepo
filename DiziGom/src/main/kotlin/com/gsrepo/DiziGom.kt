package com.gsrepo

import android.util.Log
import com.lagradost.cloudstream3.Actor
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
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class DiziGom : MainAPI() {
    override var mainUrl = "https://dizigom3.top"
    override var name = "DiziGom"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime)

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
        "$mainUrl/diziler" to "Diziler",
        "$mainUrl/filmler" to "Filmler",
        "$mainUrl/animes" to "Animeler",
        "$mainUrl/trend" to "Trend İçerikler",
        "$mainUrl/tur/aksiyon" to "Aksiyon",
        "$mainUrl/tur/bilim-kurgu" to "Bilim Kurgu",
        "$mainUrl/tur/dram" to "Dram",
        "$mainUrl/tur/fantastik" to "Fantastik",
        "$mainUrl/tur/gerilim" to "Gerilim",
        "$mainUrl/tur/gizem" to "Gizem",
        "$mainUrl/tur/komedi" to "Komedi",
        "$mainUrl/tur/korku" to "Korku",
        "$mainUrl/tur/macera" to "Macera",
        "$mainUrl/tur/romantik" to "Romantik",
        "$mainUrl/tur/suc" to "Suç",
        "$mainUrl/platform/netflix" to "Netflix",
        "$mainUrl/platform/disney" to "Disney+",
        "$mainUrl/platform/hbo-max" to "HBO Max",
        "$mainUrl/platform/amazon-prime" to "Amazon Prime"
    )

    private fun cleanUrl(value: String?): String? = value
        ?.replace("\\/", "/")
        ?.replace("\\u0026", "&")
        ?.replace("&amp;", "&")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { fixUrlNull(it) }

    private fun Element.backgroundUrl(): String? {
        val style = attr("style")
        val match = Regex("url\\((?:\\\"|')?([^\\\"')]+)", RegexOption.IGNORE_CASE).find(style)
        return cleanUrl(match?.groupValues?.getOrNull(1))
    }

    private fun Element.posterUrl(): String? {
        val img = selectFirst("img")
        val candidates = sequenceOf(
            attr("data-poster"), attr("data-bg"), attr("data-background"), attr("data-image"),
            img?.attr("data-src"), img?.attr("data-lazy-src"), img?.attr("data-original"),
            img?.attr("data-image"), img?.attr("src"), backgroundUrl()
        )
        return candidates
            .mapNotNull { it?.takeIf { s -> s.isNotBlank() }?.substringBefore(",")?.trim()?.substringBefore(" ") }
            .mapNotNull { cleanUrl(it) }
            .firstOrNull()
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val a = if (tagName() == "a") this else selectFirst("a[href*='/dizi/'], a[href*='/film/'], a[href*='/anime/']") ?: return null
        val href = cleanUrl(a.attr("href")) ?: return null
        if (!href.contains("/dizi/") && !href.contains("/film/") && !href.contains("/anime/")) return null
        if (href.contains("/sezon-") || href.contains("/bolum-")) return null

        val title = sequenceOf(
            selectFirst("h3")?.text(),
            selectFirst("h2")?.text(),
            selectFirst("img")?.attr("alt")?.replace(" Dizi izle", "")?.replace(" Film izle", "")?.replace(" Anime izle", "")
        ).mapNotNull { it?.trim()?.takeIf { value -> value.isNotBlank() } }.firstOrNull() ?: return null

        val posterUrl = selectFirst("img")?.let { img ->
            cleanUrl(img.attr("src").takeIf { !it.isNullOrBlank() } ?: img.attr("data-src"))
        } ?: posterUrl()

        val rating = selectFirst("span.badge-rating, .badge-rating")?.text()?.trim()

        val tvType = when {
            href.contains("/film/") -> TvType.Movie
            href.contains("/anime/") -> TvType.Anime
            else -> TvType.TvSeries
        }

        return newTvSeriesSearchResponse(title, href, tvType) {
            this.posterUrl = posterUrl
            this.score = Score.from10(rating)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = if (page <= 1) {
            request.data
        } else {
            if (request.data.contains("?")) "${request.data}&page=$page" else "${request.data}?page=$page"
        }

        val document = runCatching { app.get(pageUrl, referer = "$mainUrl/", interceptor = interceptor).document }.getOrNull()
            ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)

        val results = document.select("a.group, div.single-item, div.episode-box, div.item, div.box, div.post, a[href*='/dizi/'], a[href*='/film/'], a[href*='/anime/']")
            .mapNotNull { it.toMainPageResult() }
            .distinctBy { it.url }

        Log.d("DiziGom", "${request.name}: page=$page count=${results.size} url=$pageUrl")
        return newHomePageResponse(request.name, results, hasNext = results.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get(
            "$mainUrl/ara?q=${query.trim().replace(" ", "+")}",
            referer = "$mainUrl/",
            interceptor = interceptor
        ).document
        return document.select("a.group, div.single-item, div.episode-box, div.item, div.box, div.post, a[href*='/dizi/'], a[href*='/film/'], a[href*='/anime/']")
            .mapNotNull { it.toMainPageResult() }
            .distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private fun Element.firstText(vararg selectors: String): String? = selectors.asSequence()
        .mapNotNull { selectFirst(it)?.text()?.trim() }
        .firstOrNull { it.isNotBlank() }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, referer = "$mainUrl/", interceptor = interceptor).document
        val title = document.firstText("h1", "div.serieTitle h1", ".serieTitle h1", "h1.entry-title")
            ?.substringBefore(" izle")
            ?: return null

        val poster = document.selectFirst("img[src*='/posters/']")?.let { img ->
            cleanUrl(img.attr("src").takeIf { s -> s.isNotBlank() } ?: img.attr("data-src"))
        } ?: document.selectFirst("div.seriePoster img")?.posterUrl()
          ?: document.selectFirst("meta[property='og:image']")?.attr("content")?.let { cleanUrl(it) }

        val description = document.selectFirst("meta[property='og:description']")?.attr("content")
            ?: document.firstText("p.text-content-secondary", "p.leading-relaxed", "div.serieDescription p", ".serieDescription p")

        val year = Regex("\\b(19\\d\\d|20\\d\\d)\\b").find(document.text())?.groupValues?.getOrNull(1)?.toIntOrNull()
        val rating = document.selectFirst("span.badge-rating, .badge-rating")?.text()?.trim()

        val tags = document.select("a[href*='/tur/'], div.genreList a, div.genres a")
            .map { it.text().trim() }.filter { it.isNotBlank() }.distinct()

        val actors = document.select("a[href*='/oyuncu/'], div.cast a, div.actors a, img[src*='/profiles/']")
            .mapNotNull { element ->
                val actor = element.attr("alt").takeIf { it.isNotBlank() }
                    ?: element.selectFirst("p.font-semibold, h3, span")?.text()?.trim()
                    ?: element.text().trim()
                val imgUrl = element.selectFirst("img")?.attr("src") ?: element.attr("src")
                if (actor.isBlank()) null else Actor(actor, cleanUrl(imgUrl))
            }.distinctBy { it.name }

        val isMovie = url.contains("/film/")
        val tvType = when {
            isMovie -> TvType.Movie
            url.contains("/anime/") -> TvType.Anime
            else -> TvType.TvSeries
        }

        val episodes = if (isMovie) {
            listOf(
                newEpisode(url) {
                    name = title
                    season = 1
                    episode = 1
                }
            )
        } else {
            document.select("a[href*='/sezon-'][href*='/bolum-']")
                .mapNotNull { element ->
                    val href = cleanUrl(element.attr("href")) ?: return@mapNotNull null
                    val season = Regex("sezon-(\\d+)", RegexOption.IGNORE_CASE).find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    val episode = Regex("bolum-(\\d+)", RegexOption.IGNORE_CASE).find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    if (season == null || episode == null) return@mapNotNull null

                    val epName = element.selectFirst("p.font-semibold, div.bolum-ismi")?.text()?.trim()
                        ?: element.text().trim()

                    newEpisode(href) {
                        name = epName
                        this.season = season
                        this.episode = episode
                    }
                }.distinctBy { it.data }
                .sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
        }

        return newTvSeriesLoadResponse(title, url, tvType, episodes) {
            posterUrl = poster
            this.year = year
            plot = description
            this.tags = tags
            score = Score.from10(rating)
            addActors(actors)
        }
    }

    private fun extractPlayerUrls(document: Document): List<String> {
        val candidates = mutableListOf<String>()

        document.select("iframe[src], iframe[data-src], iframe[data-lazy-src], frame[src], [data-embed], [data-player], [data-url], [data-frame]").forEach { element ->
            val src = cleanUrl(
                element.attr("src").takeIf { it.isNotBlank() }
                    ?: element.attr("data-src").takeIf { it.isNotBlank() }
                    ?: element.attr("data-lazy-src").takeIf { it.isNotBlank() }
                    ?: element.attr("data-embed").takeIf { it.isNotBlank() }
                    ?: element.attr("data-player").takeIf { it.isNotBlank() }
                    ?: element.attr("data-url").takeIf { it.isNotBlank() }
                    ?: element.attr("data-frame")
            )
            if (!src.isNullOrBlank()) {
                candidates.add(src.replace("vidmoly.org", "vidmoly.to").replace("vidmoly.net", "vidmoly.to"))
            }
        }

        val htmlMatches = Regex(
            """https?://[^\s"'<>]+(?:/s\.php|pilavyer|pilayer|spidypro|vidmoly|sibnet|fembed|dood|filemoon|vOE|streamtape|play2)[^\s"'<>]*""",
            RegexOption.IGNORE_CASE
        ).findAll(document.html()).mapNotNull { 
            cleanUrl(it.value)?.replace("vidmoly.org", "vidmoly.to")?.replace("vidmoly.net", "vidmoly.to") 
        }

        candidates.addAll(htmlMatches)

        return candidates.distinct().filter { url ->
            val lower = url.lowercase()
            !lower.contains("youtube.com") &&
            !lower.contains("youtu.be") &&
            !lower.contains("google.com") &&
            !lower.contains("facebook.com") &&
            !lower.contains("disqus.com") &&
            !lower.contains("doubleclick") &&
            !lower.contains("wargamings.net") &&
            !lower.endsWith(".js") &&
            !lower.endsWith(".css") &&
            !lower.endsWith(".png") &&
            !lower.endsWith(".jpg")
        }
    }

    private fun extractPlayerStream(html: String): String? {
        val stream = Regex(
            """["']stream["']\s*:\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.getOrNull(1)
        if (!stream.isNullOrBlank()) return cleanUrl(stream)

        return Regex(
            """https?://[^\s"'<>]+/api/stream\.php[^\s"'<>]*""",
            RegexOption.IGNORE_CASE
        ).find(html)?.value?.let { cleanUrl(it) }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("DiziGom", "Resolving episode: $data")
        var found = false
        val document = runCatching { app.get(data, referer = "$mainUrl/", interceptor = interceptor).document }.getOrNull()

        if (document != null) {
            val playerUrls = extractPlayerUrls(document)
            Log.d("DiziGom", "Found player URLs (${playerUrls.size}): $playerUrls")

            for (playerUrl in playerUrls) {
                Log.d("DiziGom", "Processing player URL: $playerUrl")

                if (playerUrl.contains("vidmoly", true) || playerUrl.contains("sibnet", true) ||
                    playerUrl.contains("dood", true) || playerUrl.contains("filemoon", true) ||
                    playerUrl.contains("fembed", true) || playerUrl.contains("streamtape", true)) {
                    if (loadExtractor(playerUrl, data, subtitleCallback, callback)) {
                        found = true
                        continue
                    }
                }

                val playerHtml = runCatching { app.get(playerUrl, referer = data, interceptor = interceptor).text }.getOrNull()
                val streamUrl = playerHtml?.let { extractPlayerStream(it) }

                if (!streamUrl.isNullOrBlank()) {
                    Log.d("DiziGom", "Extracted stream from HTML: $streamUrl")
                    val domain = Regex("""(https?://[^/]+)""").find(playerUrl)?.groupValues?.get(1) ?: "https://play2.pilavyerplay.top"
                    val reqHeaders = mapOf(
                        "Referer" to playerUrl,
                        "Origin" to domain,
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
                    )

                    if (streamUrl.contains(".m3u8", true) || streamUrl.contains("stream.php", true)) {
                        val m3u8Links = runCatching {
                            M3u8Helper.generateM3u8(
                                source = name,
                                streamUrl = streamUrl,
                                referer = playerUrl,
                                headers = reqHeaders
                            )
                        }.getOrNull()

                        if (!m3u8Links.isNullOrEmpty()) {
                            m3u8Links.forEach { link ->
                                callback(link)
                                found = true
                            }
                        } else {
                            callback(
                                newExtractorLink(
                                    source = name,
                                    name = "DiziGom",
                                    url = streamUrl,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    referer = playerUrl
                                    headers = reqHeaders
                                    quality = Qualities.P1080.value
                                }
                            )
                            found = true
                        }
                    } else {
                        callback(
                            newExtractorLink(
                                source = name,
                                name = "DiziGom",
                                url = streamUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                referer = playerUrl
                                headers = reqHeaders
                                quality = Qualities.P1080.value
                            }
                        )
                        found = true
                    }
                } else {
                    DiziGomPlugin.pluginContext?.let { ctx ->
                        val webExtractor = DiziGomWebViewExtractor(ctx, name)
                        runCatching {
                            webExtractor.getUrl(playerUrl, data, subtitleCallback) { link ->
                                callback(link)
                                found = true
                            }
                        }
                    }

                    if (!found) {
                        if (loadExtractor(playerUrl, data, subtitleCallback, callback)) {
                            found = true
                        }
                    }
                }
            }

            val directUrls = Regex(
                """https?://[^\s"'<>]+(?:\.m3u8(?:\?[^\s"'<>]*)?|\.mp4(?:\?[^\s"'<>]*)?)""",
                RegexOption.IGNORE_CASE
            ).findAll(document.html()).mapNotNull { cleanUrl(it.value) }.distinct().toList()

            for (stream in directUrls) {
                val lower = stream.lowercase()
                if (lower.contains("doubleclick") || lower.contains("ad_status") || lower.contains("blank") || lower.contains("dummy")) continue
                callback(
                    newExtractorLink(
                        source = name,
                        name = "DiziGom",
                        url = stream,
                        type = if (stream.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        referer = data
                        headers = mapOf(
                            "Referer" to data,
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
                        )
                        quality = getQualityFromName(stream)
                    }
                )
                found = true
            }
        }

        if (!found) {
            Log.d("DiziGom", "Fallback to loadExtractor on $data")
            found = loadExtractor(data, data, subtitleCallback, callback)
        }

        return found
    }
}
