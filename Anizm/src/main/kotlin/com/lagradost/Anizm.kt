@file:Suppress("DEPRECATION")

package com.lagradost

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Collections

class Anizm : MainAPI() {
    override var mainUrl = "https://anizm.com.tr"
    override var name = "Anizm"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    companion object {
        private const val logTag = "Anizm"
        private const val mainServer = "https://anizmplayer.com"
        private const val browserUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private val browserHeaders = mapOf(
            "User-Agent" to browserUserAgent,
            "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
            "sec-ch-ua" to "\"Chromium\";v=\"124\", \"Google Chrome\";v=\"124\", \"Not-A.Brand\";v=\"99\"",
            "sec-ch-ua-mobile" to "?0",
            "sec-ch-ua-platform" to "\"Windows\""
        )
    }

    private val seenAnimeUrls: MutableSet<String> = Collections.synchronizedSet(HashSet())

    override val mainPage = mainPageOf(
        mainUrl to "Son Eklenen Animeler",
        "$mainUrl/arama?kategoriler%5B0%5D=1" to "Macera",
        "$mainUrl/arama?kategoriler%5B0%5D=2" to "Aksiyon",
        "$mainUrl/arama?kategoriler%5B0%5D=3" to "Komedi",
        "$mainUrl/arama?kategoriler%5B0%5D=4" to "Dram",
        "$mainUrl/arama?kategoriler%5B0%5D=5" to "Romantizm",
        "$mainUrl/arama?kategoriler%5B0%5D=6" to "Ecchi",
        "$mainUrl/arama?kategoriler%5B0%5D=8" to "Bilim-Kurgu",
        "$mainUrl/arama?kategoriler%5B0%5D=11" to "Doğaüstü-Güçler",
        "$mainUrl/arama?kategoriler%5B0%5D=13" to "Fantastik",
        "$mainUrl/arama?kategoriler%5B0%5D=26" to "Okul",
        "$mainUrl/arama?kategoriler%5B0%5D=31" to "Seinen",
        "$mainUrl/arama?kategoriler%5B0%5D=34" to "Shounen",
    )

    private fun normalizeUrl(url: String): String {
        return url.trim()
            .replace("https://www.anizm.net", mainUrl)
            .replace("http://www.anizm.net", mainUrl)
            .replace("https://anizm.net", mainUrl)
            .replace("http://anizm.net", mainUrl)
            .replace("https://www.anizm.tv", mainUrl)
            .replace("http://www.anizm.tv", mainUrl)
            .replace("https://anizm.tv", mainUrl)
            .replace("http://anizm.tv", mainUrl)
    }

    private fun isHomepageUrl(url: String): Boolean {
        val normalized = normalizeUrl(url).trimEnd('/')
        val root = mainUrl.trimEnd('/')
        return normalized.equals(root, ignoreCase = true)
    }

    private fun getProperAnimeLink(uri: String): String {
        val normalized = normalizeUrl(uri)
        return if (normalized.contains("-bolum")) {
            val basePart = normalized.substringAfter("$mainUrl/").replace(Regex("-[0-9]+-bolum.*"), "")
            "$mainUrl/$basePart"
        } else normalized.trimEnd('/')
    }

    private fun isGrayCover(posterUrl: String?): Boolean {
        if (posterUrl.isNullOrBlank()) return true
        val p = posterUrl.lowercase()
        return p.contains("defaultcover") || p.contains("no-cover") || p.contains("placeholder")
    }

    private fun extractPosterUrl(element: Element): String? {
        val imgs = element.select("img")
        for (img in imgs) {
            val raw = img.attr("data-v3-deferred-src").ifBlank {
                img.attr("data-src").ifBlank {
                    img.attr("data-original").ifBlank {
                        img.attr("data-lazy-src").ifBlank {
                            img.attr("src")
                        }
                    }
                }
            }
            val fixed = fixUrlNull(raw)
            if (!isGrayCover(fixed)) {
                return fixed
            }
        }
        val noscriptRaw = element.selectFirst("noscript img")?.attr("src")
        val noscriptFixed = fixUrlNull(noscriptRaw)
        return if (!isGrayCover(noscriptFixed)) noscriptFixed else null
    }

    private fun cleanTitle(rawTitle: String?): String? {
        if (rawTitle.isNullOrBlank()) return null
        val cleaned = rawTitle.trim()
            .replace(Regex("""\s+posteri$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+izle$""", RegexOption.IGNORE_CASE), "")
            .trim()
        if (cleaned.equals("Logo", ignoreCase = true) || cleaned.startsWith("Anizm", ignoreCase = true)) return null
        return cleaned.ifBlank { null }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val requestUrl = request.data
        if (page <= 1 && (isHomepageUrl(requestUrl) || requestUrl == mainPage.first().data)) {
            seenAnimeUrls.clear()
        }

        val targetUrl = normalizeUrl(
            if (page <= 1) {
                requestUrl
            } else if (requestUrl.contains("?")) {
                "$requestUrl&sayfa=$page"
            } else {
                "$requestUrl?sayfa=$page"
            }
        )

        Log.d(logTag, "getMainPage: targetUrl = $targetUrl")
        val isHome = isHomepageUrl(targetUrl)

        val items = mutableListOf<AnimeSearchResponse>()

        if (isHome) {
            val document = app.get(targetUrl, headers = browserHeaders).document
            val railElements = document.select(
                "#v3-latest-rail a, #v3-popular-rail a, #v3-added-rail a, .v3-poster-rail a"
            )
            for (element in railElements) {
                val hrefRaw = element.attr("href").trim()
                if (hrefRaw.isBlank()) continue
                val animeUrl = getProperAnimeLink(fixUrl(hrefRaw))
                if (isHomepageUrl(animeUrl)) continue

                if (seenAnimeUrls.contains(animeUrl)) continue

                val posterUrl = extractPosterUrl(element) ?: continue
                val titleRaw = element.selectFirst("h3")?.text()
                    ?: element.selectFirst("img")?.attr("alt")
                    ?: element.attr("title")
                val title = cleanTitle(titleRaw) ?: continue

                seenAnimeUrls.add(animeUrl)
                val episodeText = element.selectFirst("span.v3-card-tag, .v3-card-copy span")?.text()
                val episode = Regex("""([0-9]+)""").find(episodeText.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull()

                items.add(newAnimeSearchResponse(title, animeUrl, TvType.Anime) {
                    this.posterUrl = posterUrl
                    addSub(episode)
                })
            }
            Log.d(logTag, "getMainPage: Home loaded ${items.size} items")
            return newHomePageResponse(request.name, items, hasNext = false)
        } else {
            if (page <= 1) {
                var currentPage = 1
                val maxPagesToFetch = 5
                val targetCount = 20

                while (items.size < targetCount && currentPage <= maxPagesToFetch) {
                    val catPageUrl = if (currentPage == 1) {
                        targetUrl
                    } else if (targetUrl.contains("?")) {
                        "$targetUrl&sayfa=$currentPage"
                    } else {
                        "$targetUrl?sayfa=$currentPage"
                    }

                    try {
                        val document = app.get(catPageUrl, headers = browserHeaders).document
                        val articles = document.select("article.v3-catalog-card, div.searchResultItem, div.posterBlock")
                        if (articles.isEmpty()) break

                        var addedCountOnPage = 0
                        for (article in articles) {
                            val link = article.selectFirst("h2 a, a.v3-catalog-poster, a[href]") ?: continue
                            val hrefRaw = link.attr("href").trim()
                            if (hrefRaw.isBlank()) continue
                            val animeUrl = getProperAnimeLink(fixUrl(hrefRaw))
                            if (isHomepageUrl(animeUrl)) continue

                            if (seenAnimeUrls.contains(animeUrl)) continue

                            val posterUrl = extractPosterUrl(article) ?: continue
                            val titleRaw = article.selectFirst("h2")?.text()
                                ?: article.selectFirst("img")?.attr("alt")
                                ?: link.text()
                            val title = cleanTitle(titleRaw) ?: continue

                            seenAnimeUrls.add(animeUrl)

                            items.add(newAnimeSearchResponse(title, animeUrl, TvType.Anime) {
                                this.posterUrl = posterUrl
                            })
                            addedCountOnPage++
                        }

                        if (addedCountOnPage == 0 && articles.size < 12) break
                    } catch (e: Throwable) {
                        Log.w(logTag, "getMainPage: failed page $currentPage for $targetUrl: ${e.message}")
                        break
                    }
                    currentPage++
                }

                Log.d(logTag, "getMainPage: Category ${request.name} page 1 loaded ${items.size} items across pages")
                return newHomePageResponse(request.name, items, hasNext = true)
            } else {
                val document = app.get(targetUrl, headers = browserHeaders).document
                val articles = document.select("article.v3-catalog-card, div.searchResultItem, div.posterBlock")
                for (article in articles) {
                    val link = article.selectFirst("h2 a, a.v3-catalog-poster, a[href]") ?: continue
                    val hrefRaw = link.attr("href").trim()
                    if (hrefRaw.isBlank()) continue
                    val animeUrl = getProperAnimeLink(fixUrl(hrefRaw))
                    if (isHomepageUrl(animeUrl)) continue

                    val posterUrl = extractPosterUrl(article) ?: continue
                    val titleRaw = article.selectFirst("h2")?.text()
                        ?: article.selectFirst("img")?.attr("alt")
                        ?: link.text()
                    val title = cleanTitle(titleRaw) ?: continue

                    items.add(newAnimeSearchResponse(title, animeUrl, TvType.Anime) {
                        this.posterUrl = posterUrl
                    })
                }

                val hasNext = document.selectFirst(
                    "nav.v3-pagination a[href*='sayfa='], div.nextBeforeButtons a.right:not(.disabled), a[rel=next]:not(.disabled)"
                ) != null

                Log.d(logTag, "getMainPage: Category ${request.name} page $page loaded ${items.size} items")
                return newHomePageResponse(request.name, items.distinctBy { it.url }, hasNext = hasNext)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        Log.d(logTag, "search: query = $query")
        val searchJsonUrl = "$mainUrl/searchAnime?query=$query&page=1"
        try {
            val response = app.get(
                searchJsonUrl,
                headers = browserHeaders + mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to "$mainUrl/"
                )
            ).parsedSafe<SearchAnimeResponse>()

            val searchItems = response?.data
            if (!searchItems.isNullOrEmpty()) {
                val results = searchItems.mapNotNull { item: SearchAnimeItem ->
                    val title = cleanTitle(item.title) ?: return@mapNotNull null
                    val slug = item.slug?.trim() ?: return@mapNotNull null
                    val href = fixUrl("$mainUrl/$slug")
                    val posterUrl = item.poster?.let { fixUrl("$mainUrl/storage/pcovers/$it") }
                        ?.takeIf { !isGrayCover(it) }

                    newAnimeSearchResponse(title, href, TvType.Anime) {
                        this.posterUrl = posterUrl
                    }
                }
                if (results.isNotEmpty()) {
                    Log.d(logTag, "search: JSON API returned ${results.size} results")
                    return results.distinctBy { it.url }
                }
            }
        } catch (e: Throwable) {
            Log.e(logTag, "search: JSON API failed: ${e.message}")
        }

        val document = app.get(
            "$mainUrl/arama?arama=$query",
            headers = browserHeaders + mapOf(
                "Referer" to "$mainUrl/"
            )
        ).document
        val fallbackResults = document.select("article.v3-catalog-card, div.searchResultItem, div.posterBlock")
            .mapNotNull { article ->
                val link = article.selectFirst("h2 a, a.v3-catalog-poster, a[href]") ?: return@mapNotNull null
                val href = getProperAnimeLink(fixUrl(link.attr("href")))
                if (href.isBlank() || isHomepageUrl(href)) return@mapNotNull null

                val posterUrl = extractPosterUrl(article) ?: return@mapNotNull null
                val titleRaw = article.selectFirst("h2")?.text() ?: article.selectFirst("img")?.attr("alt") ?: link.text()
                val title = cleanTitle(titleRaw) ?: return@mapNotNull null

                newAnimeSearchResponse(title, href, TvType.Anime) {
                    this.posterUrl = posterUrl
                }
            }
            .distinctBy { it.url }
        Log.d(logTag, "search: Fallback HTML returned ${fallbackResults.size} results")
        return fallbackResults
    }

    private fun extractImdbId(document: Document): String? {
        val candidates = sequenceOf(
            document.selectFirst("a[href*='imdb.com/title/']")?.attr("href"),
            document.selectFirst("[data-imdb-id]")?.attr("data-imdb-id"),
            document.selectFirst("[data-imdb]")?.attr("data-imdb"),
            document.select("span.dataValue").joinToString(" ") { it.text() }
        )

        return candidates.filterNotNull().mapNotNull {
            Regex("""tt[0-9]{7,9}""").find(it)?.value
        }.firstOrNull()
    }

    override suspend fun load(url: String): LoadResponse {
        val normalizedUrl = normalizeUrl(url)
        Log.d(logTag, "load: inputUrl = $url, normalizedUrl = $normalizedUrl")
        val document = app.get(normalizedUrl, headers = browserHeaders).document

        val rawTitle = document.selectFirst("h1, h2.anizm_pageTitle a, h2.anizm_pageTitle")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "Anime"

        val title = cleanTitle(rawTitle) ?: "Anime"

        val episodeElements = document.select(
            "div.v3-episode-list a, div.episodeListTabContent div > a, .info_episodeList a, a[href*='-bolum-izle'], a[href*='-bolum'], a[href*='-izle']"
        )

        val episodes = episodeElements.mapNotNull { element ->
            val href = element.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val fullUrl = normalizeUrl(fixUrl(href))
            if (isHomepageUrl(fullUrl)) return@mapNotNull null
            if (!fullUrl.contains("-bolum") && !fullUrl.contains("-izle")) return@mapNotNull null

            val episodeName = element.selectFirst("span.v3-episode-list-title, div.episodeBlock")?.text()?.trim()
                ?: element.text().trim().takeIf { it.isNotBlank() }
                ?: "Bölüm"

            newEpisode(fullUrl) {
                name = episodeName
            }
        }.distinctBy { it.data }.toMutableList()

        if (episodes.isEmpty() && (normalizedUrl.contains("-izle") || normalizedUrl.contains("-bolum"))) {
            episodes.add(
                newEpisode(normalizedUrl) {
                    name = title
                }
            )
        }

        Log.d(logTag, "load: title = $title, episodesCount = ${episodes.size}")

        val type = if (episodes.size <= 1) TvType.Movie else TvType.Anime
        val trailer = document.selectFirst(
            "div.yt-hd-thumbnail-inner-container iframe, iframe[src*='youtube.com'], iframe[src*='youtu.be']"
        )?.attr("src")

        val year = Regex("""\b(19|20)\d{2}\b""").find(
            document.select(".v3-series-overview, div.infoSta li, div.anizm_boxContent li.dataRow")
                .joinToString(" ") { it.text() }
        )?.value?.toIntOrNull()

        val imdbId = extractImdbId(document)
        val mainPoster = fixUrlNull(
            document.selectFirst("meta[property='og:image']")?.attr("content")
                ?: document.selectFirst("div.v3-series-cover img, div.infoPosterImg img")?.let { extractPosterUrl(it) }
        )?.takeIf { !isGrayCover(it) }

        return newAnimeLoadResponse(title, normalizedUrl, type) {
            posterUrl = mainPoster
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            plot = document.selectFirst("div.v3-watch-summary p, meta[property='og:description'], div.infoDesc")?.text()?.trim()
            tags = document.select(
                "div.v3-catalog-preview-tags span, span.dataValue > span.tag > span.label"
            ).map { it.text() }.distinct()
            imdbId?.let { addImdbId(it) }
            trailer?.let { addTrailer(it) }
        }
    }

    private suspend fun invokeLokalSource(
        url: String,
        translator: String,
        sourceName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        sourceCallback: (ExtractorLink) -> Unit
    ) {
        val normalizedUrl = normalizeUrl(url)
        Log.d(logTag, "invokeLokalSource: fetching player page = $normalizedUrl ($sourceName - $translator)")
        val playerDocument = app.get(
            normalizedUrl,
            referer = "$mainUrl/",
            headers = browserHeaders + mapOf("Referer" to "$mainUrl/")
        ).document

        val playerHtml = playerDocument.html()

        playerDocument.selectFirst("iframe")?.attr("src")?.takeIf { it.isNotBlank() }?.let { innerIframe ->
            val fixedInner = fixUrl(innerIframe)
            if (!fixedInner.contains("anizmplayer.com") && !fixedInner.contains("anizm.com.tr") && !fixedInner.contains("anizm.net") && !fixedInner.contains("anizm.tv")) {
                Log.d(logTag, "invokeLokalSource: loading external extractor = $fixedInner")
                loadExtractor(fixedInner, normalizedUrl, subtitleCallback, sourceCallback)
                return
            }
        }

        val packedScript = playerDocument.select("script").firstOrNull { script ->
            script.data().contains("eval(function(p,a,c,k,e,d)")
        }

        if (packedScript != null) {
            val unpacked = getAndUnpack(packedScript.data())
            val key = unpacked.substringAfter("FirePlayer(\"").substringBefore("\",")
            if (key.isNotBlank() && !unpacked.startsWith(key)) {
                val referer = "$mainServer/video/$key"
                val link = "$mainServer/player/index.php?data=$key&do=getVideo"
                Log.d(logTag, "invokeLokalSource: resolved FirePlayer key = $key, requesting $link")
                val response = app.post(
                    link,
                    data = mapOf("hash" to key, "r" to "$mainUrl/"),
                    referer = referer,
                    headers = mapOf(
                        "Accept" to "*/*",
                        "Origin" to mainServer,
                        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                        "X-Requested-With" to "XMLHttpRequest",
                        "User-Agent" to browserUserAgent
                    )
                ).parsedSafe<Source>()

                val videoSource = response?.securedLink ?: response?.videoSource
                if (!videoSource.isNullOrBlank()) {
                    Log.d(logTag, "invokeLokalSource: found FirePlayer stream = $videoSource")
                    val displayName = if (sourceName.isNotBlank() && sourceName != translator) {
                        "${this.name} - $sourceName ($translator)"
                    } else {
                        "${this.name} ($translator)"
                    }

                    try {
                        M3u8Helper.generateM3u8(
                            displayName,
                            videoSource,
                            referer,
                            headers = mapOf("Referer" to referer, "User-Agent" to browserUserAgent)
                        ).forEach(sourceCallback)
                    } catch (e: Throwable) {
                        Log.w(logTag, "invokeLokalSource: M3u8Helper failed: ${e.message}")
                    }

                    sourceCallback(
                        newExtractorLink(
                            source = this.name,
                            name = displayName,
                            url = videoSource,
                            type = INFER_TYPE
                        ) {
                            this.referer = "https://anizmplayer.com/"
                            this.quality = Qualities.P1080.value
                        }
                    )
                    return
                }
            }
        }

        val directUrlMatch = Regex("""(?:const|var|let)\s+url\s*=\s*[\"']([^\"']+)[\"']""")
            .find(playerHtml)?.groupValues?.getOrNull(1)?.replace("\\/", "/")
        val fileMatch = Regex("""file:\s*[\"']([^\"']+)[\"']""")
            .find(playerHtml)?.groupValues?.getOrNull(1)?.replace("\\/", "/")
        val mediaUrl = directUrlMatch ?: fileMatch

        if (!mediaUrl.isNullOrBlank() && mediaUrl.startsWith("http")) {
            val isMedia = mediaUrl.contains(".m3u8") ||
                mediaUrl.contains(".mp4") ||
                mediaUrl.contains("/storage/uploads/") ||
                mediaUrl.contains("/cdn/hls/")
            val isStaticAsset = mediaUrl.endsWith(".js") ||
                mediaUrl.endsWith(".css") ||
                mediaUrl.endsWith(".png") ||
                mediaUrl.endsWith(".jpg") ||
                mediaUrl.endsWith(".jpeg") ||
                mediaUrl.endsWith(".webp") ||
                mediaUrl.endsWith(".gif") ||
                mediaUrl.endsWith(".svg")

            if (isMedia && !isStaticAsset) {
                Log.d(logTag, "invokeLokalSource: found direct media URL = $mediaUrl")
                val displayName = if (sourceName.isNotBlank() && sourceName != translator) {
                    "${this.name} - $sourceName ($translator)"
                } else {
                    "${this.name} ($translator)"
                }

                if (mediaUrl.contains(".m3u8")) {
                    try {
                        M3u8Helper.generateM3u8(
                            displayName,
                            mediaUrl,
                            normalizedUrl
                        ).forEach(sourceCallback)
                    } catch (e: Throwable) {
                        Log.w(logTag, "invokeLokalSource: M3u8Helper failed for direct media: ${e.message}")
                    }
                }
                sourceCallback(
                    newExtractorLink(
                        source = this.name,
                        name = displayName,
                        url = mediaUrl,
                        type = INFER_TYPE
                    ) {
                        this.referer = normalizedUrl
                        this.quality = Qualities.P1080.value
                    }
                )
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val normalizedData = normalizeUrl(data)
        Log.d(logTag, "loadLinks: episodeUrl = $normalizedData")
        val document = app.get(
            normalizedData,
            referer = "$mainUrl/",
            headers = browserHeaders + mapOf("Referer" to "$mainUrl/")
        ).document

        val translatorButtons = document.select(
            ".v3-translator-button[data-sources], button[data-sources]"
        )

        if (translatorButtons.isNotEmpty()) {
            Log.d(logTag, "loadLinks: found ${translatorButtons.size} v3 translator buttons")
            for (btn in translatorButtons) {
                safeApiCall {
                    val sourcesUrlRaw = btn.attr("data-sources").takeIf { it.isNotBlank() } ?: return@safeApiCall
                    val sourcesUrl = fixUrl(sourcesUrlRaw)
                    val translatorName = btn.text().trim().ifBlank { "TR" }

                    Log.d(logTag, "loadLinks: requesting v3 translator sources = $sourcesUrl ($translatorName)")

                    val sourcesResp = app.get(
                        sourcesUrl,
                        referer = normalizedData,
                        headers = browserHeaders + mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                            "Referer" to normalizedData
                        )
                    ).parsedSafe<V3SourcesResponse>()

                    val videos = sourcesResp?.videos
                    if (!videos.isNullOrEmpty()) {
                        Log.d(logTag, "loadLinks: translator $translatorName has ${videos.size} videos")
                        coroutineScope {
                            videos.map { v ->
                                async {
                                    val vid = v.id ?: return@async
                                    val videoName = v.name ?: "Video"
                                    safeApiCall {
                                        val videoInfoUrl = "$mainUrl/video/$vid"
                                        val videoInfoResp = app.get(
                                            videoInfoUrl,
                                            referer = normalizedData,
                                            headers = browserHeaders + mapOf(
                                                "X-Requested-With" to "XMLHttpRequest",
                                                "Referer" to normalizedData
                                            )
                                        ).parsedSafe<V3VideoInfoResponse>()

                                        val playerHtml = videoInfoResp?.player
                                        val videoUrl = videoInfoResp?.url

                                        val playerIframeUrl = if (!playerHtml.isNullOrBlank()) {
                                            Jsoup.parse(playerHtml).selectFirst("iframe")?.attr("src")
                                        } else videoUrl

                                        if (!playerIframeUrl.isNullOrBlank()) {
                                            val fixedIframe = normalizeUrl(fixUrl(playerIframeUrl))
                                            Log.d(logTag, "loadLinks: resolved iframe = $fixedIframe ($videoName)")
                                            if (fixedIframe.contains("anizmplayer.com") || fixedIframe.contains("/player/")) {
                                                invokeLokalSource(fixedIframe, translatorName, videoName, subtitleCallback, callback)
                                            } else {
                                                loadExtractor(fixedIframe, normalizedData, subtitleCallback, callback)
                                            }
                                        }
                                    }
                                }
                            }.awaitAll()
                        }
                    }
                }
            }
            return true
        }

        val translatorItems = document.select(
            "div.episodeTranslators div#fansec, div.episodeTranslators [translator], a[translator], [data-translatorclick]"
        ).distinctBy {
            it.selectFirst("a[translator]")?.attr("translator")
                ?: it.attr("translator")
                ?: it.attr("href")
        }

        Log.d(logTag, "loadLinks: found ${translatorItems.size} legacy translators")

        translatorItems.forEach { item ->
            safeApiCall {
                val translatorAnchor = item.selectFirst("a[translator]") ?: item.takeIf { it.hasAttr("translator") }
                val translatorUrlRaw = translatorAnchor?.attr("translator")?.let(::fixUrl).orEmpty()
                    .ifBlank { translatorAnchor?.attr("href")?.let(::fixUrl).orEmpty() }
                val translatorUrl = normalizeUrl(translatorUrlRaw)
                val translator = item.selectFirst("div.title, span")?.text()?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: translatorAnchor?.text()?.trim().orEmpty()

                if (translatorUrl.isBlank() || translatorUrl == "#") {
                    return@safeApiCall
                }

                val translatorResponse = app.get(
                    translatorUrl,
                    referer = normalizedData,
                    headers = browserHeaders + mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Referer" to normalizedData
                    )
                ).parsedSafe<Translators>()

                val translatorHtml = translatorResponse?.data
                if (translatorHtml.isNullOrBlank()) {
                    return@safeApiCall
                }

                val videoLinks = Jsoup.parse(translatorHtml).select("a[video]")
                coroutineScope {
                    videoLinks.map { video ->
                        async {
                            val videoUrlRaw = video.attr("video").let(::fixUrl)
                            if (videoUrlRaw.isBlank()) return@async
                            val videoUrl = normalizeUrl(videoUrlRaw)
                            val videoSourceName = video.attr("data-video-name").ifBlank { video.text().trim() }

                            safeApiCall {
                                val player = app.get(
                                    videoUrl,
                                    referer = normalizedData,
                                    headers = browserHeaders + mapOf(
                                        "X-Requested-With" to "XMLHttpRequest",
                                        "Referer" to normalizedData
                                    )
                                ).parsedSafe<Videos>()?.player

                                if (player.isNullOrBlank()) return@safeApiCall

                                val parsed = Jsoup.parse(player)
                                val iframeElement = parsed.selectFirst("iframe, embed")
                                val link = (
                                    iframeElement?.attr("src")
                                        ?: iframeElement?.attr("data-src")
                                        ?: iframeElement?.attr("data-lazy-src")
                                )?.trim()?.takeIf { it.isNotBlank() }
                                    ?: player.trim().takeIf { it.startsWith("http") }

                                if (link.isNullOrBlank()) return@safeApiCall

                                val fixedLink = normalizeUrl(fixUrl(link))
                                if (fixedLink.contains("anizmplayer.com") || fixedLink.contains("/player/")) {
                                    invokeLokalSource(fixedLink, translator, videoSourceName, subtitleCallback, callback)
                                } else {
                                    loadExtractor(fixedLink, normalizedData, subtitleCallback, callback)
                                }
                            }
                        }
                    }.awaitAll()
                }
            }
        }

        return true
    }

    data class SearchAnimeResponse(
        @JsonProperty("data") val data: List<SearchAnimeItem>? = null
    )

    data class SearchAnimeItem(
        @JsonProperty("info_title") val title: String? = null,
        @JsonProperty("info_slug") val slug: String? = null,
        @JsonProperty("info_poster") val poster: String? = null,
        @JsonProperty("info_year") val year: String? = null
    )

    data class Source(
        @JsonProperty("videoSource") val videoSource: String? = null,
        @JsonProperty("securedLink") val securedLink: String? = null
    )

    data class Videos(
        @JsonProperty("player") val player: String? = null
    )

    data class Translators(
        @JsonProperty("data") val data: String? = null
    )

    data class V3SourcesResponse(
        @JsonProperty("success") val success: Boolean? = null,
        @JsonProperty("videos") val videos: List<V3VideoItem>? = null
    )

    data class V3VideoItem(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null
    )

    data class V3VideoInfoResponse(
        @JsonProperty("success") val success: Boolean? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("player") val player: String? = null,
        @JsonProperty("source") val source: String? = null,
        @JsonProperty("translator") val translator: String? = null
    )
}
