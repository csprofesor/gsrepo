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
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class DiziGom : MainAPI() {
    override var mainUrl = "https://www.dizigom.icu"
    override var name = "DiziGom"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries)

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

    private val genreRoutes = linkedMapOf(
        "Yeni Diziler" to "",
        "Aile" to "Aile",
        "Aksiyon" to "Aksiyon",
        "Animasyon" to "Animasyon",
        "Belgesel" to "Belgesel",
        "Bilim Kurgu" to "Bilim Kurgu",
        "Biyografi" to "Biyografi",
        "Dram" to "Dram",
        "Fantastik" to "Fantastik",
        "Gençlik" to "Gençlik",
        "Gerilim" to "Gerilim",
        "Gizem" to "Gizem",
        "Komedi" to "Komedi",
        "Korku" to "Korku",
        "Macera" to "Macera",
        "Polisiye" to "Polisiye",
        "Romantik" to "Romantik",
        "Savaş" to "Savaş",
        "Suç" to "Suç",
        "Tarih" to "Tarih"
    )

    override val mainPage = mainPageOf(
        *genreRoutes.map { (genre, slug) ->
            if (slug.isEmpty()) "$mainUrl/dizi-izle/" to genre
            else "$mainUrl/dizi-izle/?tur=$slug" to genre
        }.toTypedArray()
    )

    private fun cleanUrl(value: String?): String? = value
        ?.replace("\\/", "/")
        ?.replace("\\u0026", "&")
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
        val title = sequenceOf(
            selectFirst("div.categorytitle a")?.text(),
            selectFirst("div.cat-title a")?.text(),
            selectFirst("div.episode-name a")?.text(),
            selectFirst(".serie-name a")?.text(),
            selectFirst(".serie-name")?.text()
        ).mapNotNull { it?.substringBefore(" izle")?.trim()?.takeIf { value -> value.isNotBlank() } }.firstOrNull() ?: return null

        val href = sequenceOf(
            selectFirst("div.cat-img a")?.attr("href"),
            selectFirst("div.categorytitle a")?.attr("href"),
            selectFirst("div.cat-title a")?.attr("href"),
            selectFirst("a[href*='/diziler/']")?.attr("href"),
            selectFirst("a[href*='/dizi/']")?.attr("href")
        ).mapNotNull { cleanUrl(it) }.firstOrNull() ?: return null

        val posterUrl = selectFirst("div.cat-img img")?.let { img ->
            cleanUrl(img.attr("data-src").takeIf { !it.isNullOrBlank() } ?: img.attr("src"))
        } ?: posterUrl()

        val imdbText = selectFirst("div.imdbp")?.text()
        val rating = Regex("([0-9]+(?:[.,][0-9]+)?)", RegexOption.IGNORE_CASE)
            .find(imdbText.orEmpty())?.value

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
            this.score = Score.from10(rating)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = if (page <= 1) {
            request.data
        } else {
            if (request.data.contains("?")) "${request.data}&sayfa=$page" else "${request.data}?sayfa=$page"
        }

        val document = runCatching { app.get(pageUrl, referer = "$mainUrl/", interceptor = interceptor).document }.getOrNull()
            ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)

        val results = document.select("div.single-item, div.episode-box, div.item, div.box, div.post")
            .mapNotNull { it.toMainPageResult() }
            .distinctBy { it.url }

        Log.d("DiziGom", "${request.name}: page=$page count=${results.size} url=$pageUrl")
        return newHomePageResponse(request.name, results, hasNext = results.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get(
            "$mainUrl/?s=${query.trim().replace(" ", "+")}",
            referer = "$mainUrl/",
            interceptor = interceptor
        ).document
        return document.select("div.single-item, div.episode-box, div.item, div.box, div.post")
            .mapNotNull { it.toMainPageResult() }
            .distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private fun Element.firstText(vararg selectors: String): String? = selectors.asSequence()
        .mapNotNull { selectFirst(it)?.text()?.trim() }
        .firstOrNull { it.isNotBlank() }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, referer = "$mainUrl/", interceptor = interceptor).document
        val title = document.firstText("div.serieTitle h1", ".serieTitle h1", "h1.entry-title", "article h1", "h1")
            ?.substringBefore(" izle")
            ?: return null

        val poster = document.selectFirst("div.seriePoster")?.posterUrl()
            ?: document.selectFirst("div.seriePoster img")?.posterUrl()
            ?: document.selectFirst("div.category_image img")?.let { cleanUrl(it.attr("data-src").takeIf { s -> s.isNotBlank() } ?: it.attr("src")) }
            ?: document.selectFirst("meta[property='og:image']")?.attr("content")?.let { cleanUrl(it) }

        val description = document.firstText(
            "div.serieDescription p", ".serieDescription p", ".description p", ".entry-content p", "div.category_desc"
        )

        val year = Regex("(?:Yapım Yılı|Yapim Yili)\\s*:?\\s*(\\d{4})", RegexOption.IGNORE_CASE)
            .find(document.text())?.groupValues?.getOrNull(1)?.toIntOrNull()
        val rating = Regex("(?:IMDB|IMDb)\\s*:?\\s*([0-9]+(?:[.,][0-9]+)?)", RegexOption.IGNORE_CASE)
            .find(document.text())?.groupValues?.getOrNull(1)

        val tags = document.select("div.genreList a, .genreList a, div.genres a")
            .map { it.text().trim() }.filter { it.isNotBlank() }.distinct()

        val actors = document.select("div.owl-stage a, .cast a, .actors a")
            .mapNotNull { link ->
                val actor = link.text().trim()
                if (actor.isBlank()) null else Actor(actor, link.posterUrl())
            }.distinctBy { it.name }

        val episodes = document.select("div.bolumust, a[href*='-sezon-'][href*='-bolum']")
            .mapNotNull { element ->
                val link = if (element.tagName() == "a") element else element.selectFirst("a") ?: return@mapNotNull null
                val href = cleanUrl(link.attr("href")) ?: return@mapNotNull null
                val source = "${element.text()} ${link.attr("title")}".trim()
                val season = Regex("(\\d+)\\s*\\.?\\s*Sezon", RegexOption.IGNORE_CASE)
                    .find(source)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: Regex("-(\\d+)-sezon-", RegexOption.IGNORE_CASE).find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                val episode = Regex("(\\d+)\\s*\\.?\\s*Bölüm", RegexOption.IGNORE_CASE)
                    .find(source)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: Regex("-(\\d+)-bolum", RegexOption.IGNORE_CASE).find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                if (season == null || episode == null) return@mapNotNull null
                newEpisode(href) {
                    name = element.selectFirst("div.bolum-ismi")?.text()?.trim() ?: element.text().trim()
                    this.season = season
                    this.episode = episode
                }
            }.distinctBy { it.data }
            .sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            posterUrl = poster
            this.year = year
            plot = description
            this.tags = tags
            score = Score.from10(rating)
            addActors(actors)
        }
    }

    private fun extractPlayerUrl(document: Document): String? {
        return document.select("iframe[src], frame[src]")
            .mapNotNull { cleanUrl(it.attr("src")) }
            .firstOrNull { it.contains("s.php", true) || it.contains("pilayerplay", true) }
            ?: Regex("https?://[^\\\"'\\s<>]+/s\\.php\\?[^\\\"'\\s<>]+", RegexOption.IGNORE_CASE)
                .find(document.html())?.value?.let { cleanUrl(it) }
    }

    private fun extractPlayerStream(html: String): String? {
        val stream = Regex(
            "[\\\"']stream[\\\"']\\s*:\\s*[\\\"']([^\\\"']+)[\\\"']",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.getOrNull(1)
        if (!stream.isNullOrBlank()) return cleanUrl(stream)

        return Regex(
            "https?://[^\\\"'\\s<>]+/api/stream\\.php(?:\\?[^\\\"'\\s<>]+)?",
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
            val playerUrl = extractPlayerUrl(document)
            if (!playerUrl.isNullOrBlank()) {
                val playerResponse = runCatching { app.get(playerUrl, referer = data, interceptor = interceptor) }.getOrNull()
                val playerHtml = playerResponse?.text.orEmpty()
                val streamUrl = extractPlayerStream(playerHtml)

                if (!streamUrl.isNullOrBlank()) {
                    Log.d("DiziGom", "PilayerPlay stream bulundu: $streamUrl")
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "DiziGom 1080p",
                            url = streamUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            referer = playerUrl
                            quality = 1080
                        }
                    )
                    found = true
                }

                val directPlayerUrl = Regex(
                    "https?://[^\\\"'\\s<>]+(?:\\.m3u8(?:\\?[^\\\"'\\s<>]*)?|\\.mp4(?:\\?[^\\\"'\\s<>]*)?)",
                    RegexOption.IGNORE_CASE
                ).findAll(playerHtml).map { cleanUrl(it.value) }.filterNotNull().distinct().toList()

                for (stream in directPlayerUrl) {
                    callback(
                        newExtractorLink(source = name, name = "DiziGom", url = stream,
                            type = if (stream.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                            referer = playerUrl
                            quality = getQualityFromName(stream)
                        }
                    )
                    found = true
                }
            }

            val directUrls = Regex(
                "https?://[^\\\"'\\s<>]+(?:\\.m3u8(?:\\?[^\\\"'\\s<>]*)?|\\.mp4(?:\\?[^\\\"'\\s<>]*)?)",
                RegexOption.IGNORE_CASE
            ).findAll(document.html()).map { cleanUrl(it.value) }.filterNotNull().distinct().toList()

            for (stream in directUrls) {
                callback(
                    newExtractorLink(source = name, name = "DiziGom", url = stream,
                        type = if (stream.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                        referer = data
                        quality = getQualityFromName(stream)
                    }
                )
                found = true
            }
        }

        if (!found) {
            Log.d("DiziGom", "Fallback to DiziGomWebViewExtractor for $data")
            DiziGomPlugin.pluginContext?.let { ctx ->
                val webExtractor = DiziGomWebViewExtractor(ctx, name)
                runCatching {
                    webExtractor.getUrl(data, data, subtitleCallback) { link ->
                        callback(link)
                        found = true
                    }
                }
            }
        }

        return found
    }
}
