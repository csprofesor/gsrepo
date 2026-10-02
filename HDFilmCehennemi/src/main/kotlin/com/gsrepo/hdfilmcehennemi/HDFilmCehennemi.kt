package com.gsrepo.hdfilmcehennemi

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.mozilla.javascript.Context
import org.mozilla.javascript.ScriptableObject

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
            val home = parseHomePage(doc)
            newHomePageResponse(request.name, home, hasNext = home.isNotEmpty())
        } catch (e: Exception) {
            Log.e(name, "getMainPage error: ${e.message}")
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    private fun parseHomePage(doc: Document): List<SearchResponse> {
        return doc.select("a.poster, div.poster, a.card, div.card, div.slider-slide, article, div.movie-box")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    private fun parseSearchElement(element: Element): SearchResponse? = element.toSearchResult()

    private fun Element.toSearchResult(): SearchResponse? {
        val link = if (this.tagName() == "a") this else this.selectFirst("a") ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null
        if (href.contains("/oyuncu/") || href.contains("/yonetmen/") || href.contains("/kategori/")) return null

        val title = this.attr("title").ifEmpty { null }
            ?: this.attr("data-title").ifEmpty { null }
            ?: this.selectFirst("strong.poster-title, strong, h2.title, h3.title, h4.title, div.title, .poster-title")?.text()?.trim()
            ?: link.attr("title").ifEmpty { null }
            ?: this.selectFirst("img")?.attr("alt")?.replace(" izle", "")?.trim()
            ?: return null

        val img = this.selectFirst("img")
        val posterUrl = fixUrlNull(
            img?.attr("data-src")?.ifEmpty { null }
                ?: img?.attr("data-srcset")?.split(",")?.firstOrNull()?.trim()?.split(" ")?.firstOrNull()
                ?: img?.attr("srcset")?.split(",")?.firstOrNull()?.trim()?.split(" ")?.firstOrNull()
                ?: img?.attr("src")?.takeUnless { it.startsWith("data:") }
        )

        val score = this.selectFirst(".imdb, .score, span.rating, div.rating")?.text()?.trim()
        val isTv = href.contains("/dizi/")
        return if (isTv) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.score = Score.from10(score)
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
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
        Log.d("HDCH", "loadLinks data » $data")
        val doc = app.get(data, referer = "$mainUrl/", interceptor = interceptor).document
        val iframes = mutableListOf<Pair<String, String>>()

        fun addIframeUrl(name: String, rawUrl: String?) {
            if (rawUrl.isNullOrBlank()) return
            val fixed = fixUrlNull(rawUrl) ?: return
            val lower = fixed.lowercase()
            if (lower.endsWith(".webp") || lower.endsWith(".jpg") || lower.endsWith(".png") || lower.endsWith(".jpeg") || lower.endsWith(".svg") || lower.endsWith(".gif") || lower.endsWith(".css") || lower.endsWith(".js")) return
            if (lower.contains("youtube.com") || lower.contains("youtu.be")) return
            if (lower.contains("google") || lower.contains("analytics") || lower.contains("yandex") || lower.contains("facebook") || lower.contains("doubleclick")) return
            iframes.add(name to fixed)
        }

        // 1. Direct player iframes anywhere on page
        doc.select("iframe[data-src], iframe[src]").forEach { frame ->
            val src = frame.attr("data-src").ifEmpty { frame.attr("src") }
            addIframeUrl("Ana Kaynak", src)
        }

        // 2. Player navigation tabs / buttons
        doc.select("nav.card-nav a, a.card-nav-link, button.card-nav-link, .card-video a, [data-video], [data-url]").forEach { a ->
            val playerUrl = a.attr("data-video").ifEmpty { a.attr("data-url") }.ifEmpty { a.attr("href") }
            val name = a.selectFirst("span")?.text()?.trim() ?: a.text().trim()
            if (playerUrl.isNotBlank() && !playerUrl.startsWith("#") && !playerUrl.startsWith("javascript:")) {
                val fullUrl = if (playerUrl.startsWith("/")) {
                    if (playerUrl.startsWith("/video/")) "https://hdfilmcehennemi.mobi$playerUrl" else "$mainUrl$playerUrl"
                } else playerUrl
                addIframeUrl(name.ifEmpty { "Kaynak" }, fullUrl)
            }
        }

        val distinctIframes = iframes.distinctBy { it.second }
        var found = false

        distinctIframes.forEach { (name, iframe) ->
            try {
                if (iframe.contains("rapidrame") || iframe.contains("hdfilmcehennemi") || iframe.contains("playmix") || iframe.contains("close") || iframe.contains("embed")) {
                    invokeLocalSource(name, iframe, subtitleCallback) { link ->
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

    private suspend fun invokeLocalSource(
        source: String,
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val response = app.get(url, referer = "$mainUrl/", interceptor = interceptor)
            val pageText = response.text
            val doc = Jsoup.parse(pageText)

            // Extract contentUrl from schema.org json-ld if present as last fallback
            val schemaContentUrl = Regex("""(?i)"contentUrl"\s*:\s*"([^"]+)"""").find(pageText)?.groupValues?.get(1)?.replace("\\/", "/")

            // Combine all script tags for Rhino decryption
            val allScripts = doc.select("script").map { it.data() }.filter { it.isNotBlank() }.joinToString("\n;\n")
            val decrypted = if (allScripts.isNotBlank()) decryptWithRhino(allScripts) else emptyList()

            // Regex fallback for .m3u8 and .txt HLS playlists
            val regexUrls = Regex("""https?://[^\s"'<>]+\.(?:m3u8|txt)[^\s"'<>]*""").findAll(pageText)
                .map { it.value.replace("\\/", "/") }
                .filter { it.contains(".m3u8") || it.contains(".txt") || it.contains("/hls/") }
                .toList()

            // Prioritize Rhino decrypted links over schema.org (schema.org often contains fake filmakinesi 404 links)
            val streamUrls = (decrypted.ifEmpty { listOfNotNull(schemaContentUrl) } + regexUrls)
                .map { it.replace("\\/", "/") }
                .filter { it.startsWith("http") && (it.contains(".m3u8") || it.contains(".txt") || it.contains("/hls/") || it.contains("master")) }
                .distinct()

            if (streamUrls.isEmpty()) {
                Log.w("HDCH", "No stream URLs decrypted from $url")
                return
            }

            // Extract subtitles
            val tracksStr = pageText.substringAfter("tracks: [", "").substringBefore("]", "")
            if (tracksStr.isNotBlank()) {
                try {
                    val jsonStr = "[$tracksStr]"
                    val mapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
                    val subs: List<SubSource>? = mapper.readValue(jsonStr)
                    subs?.forEach { sub ->
                        val subFile = sub.file ?: return@forEach
                        val subLang = sub.label ?: sub.language ?: "Türkçe"
                        val fullSub = if (subFile.startsWith("http")) subFile else mainUrl.trimEnd('/') + "/" + subFile.trimStart('/')
                        subtitleCallback(newSubtitleFile(subLang, fullSub))
                    }
                } catch (_: Exception) {}
            }

            val embedDomain = Regex("""(https?://[^/]+)""").find(url)?.groupValues?.get(1) ?: "https://hdfilmcehennemi.mobi"

            streamUrls.forEachIndexed { index, streamUrl ->
                val linkName = if (streamUrls.size > 1) "$source ${index + 1}" else source
                callback(
                    newExtractorLink(
                        source = name,
                        name = linkName,
                        url = streamUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$embedDomain/"
                        this.headers = mapOf(
                            "Referer" to "$embedDomain/",
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                        )
                    }
                )
            }
        } catch (e: Exception) {
            Log.e("HDCH", "invokeLocalSource fetch error: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun decryptWithRhino(packedScript: String): List<String> {
        val polyfill = """
            var console = { log: function() {} };
            var _b64chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=';
            if (typeof btoa === 'undefined') {
                btoa = function(input) {
                    var str = String(input);
                    var output = '';
                    for (var block = 0, charCode, idx = 0, map = _b64chars;
                        str.charAt(idx | 0) || (map = '=', idx % 1);
                        output += map.charAt(63 & block >> 8 - idx % 1 * 8)) {
                        charCode = str.charCodeAt(idx += 3/4);
                        if (charCode > 0xFF) throw new Error('btoa failed');
                        block = block << 8 | charCode;
                    }
                    return output;
                };
            }
            if (typeof atob === 'undefined') {
                atob = function(input) {
                    var str = String(input).replace(/[=]+$/, '');
                    if (str.length % 4 == 1) throw new Error('atob failed');
                    var output = '';
                    for (var bc = 0, bs = 0, buffer, idx = 0;
                        buffer = str.charAt(idx++);
                        ~buffer && (bs = bc % 4 ? bs * 64 + buffer : buffer,
                            bc++ % 4) ? output += String.fromCharCode(255 & bs >> (-2 * bc & 6)) : 0
                    ) {
                        buffer = _b64chars.indexOf(buffer);
                    }
                    return output;
                };
            }
        """.trimIndent()

        val collector = """
            var __found_links__ = [];
            for (var k in this) {
                try {
                    var v = this[k];
                    if (typeof v === 'string' && (v.indexOf('.m3u8') !== -1 || v.indexOf('.txt') !== -1 || v.indexOf('master') !== -1 || v.indexOf('/hls/') !== -1)) {
                        if (v.indexOf('http') === 0 && __found_links__.indexOf(v) === -1) __found_links__.push(v);
                    }
                } catch(e){}
            }
            __found_links__.join('|||');
        """.trimIndent()

        val cx = Context.enter()
        cx.optimizationLevel = -1
        cx.languageVersion = 200
        try {
            val scope: ScriptableObject = cx.initStandardObjects()
            cx.evaluateString(scope, polyfill, "polyfill", 1, null)
            cx.evaluateString(scope, packedScript, "unpacked", 1, null)
            val res = cx.evaluateString(scope, collector, "collector", 1, null)
            val rawLinks = res?.toString()?.split("|||") ?: emptyList()
            return rawLinks.map { it.trim() }.filter { it.startsWith("http") && (it.contains(".m3u8") || it.contains(".txt") || it.contains("/hls/") || it.contains("master")) }
        } catch (e: Exception) {
            Log.e("HDCH", "Rhino decrypt error: ${e.message}")
            return emptyList()
        } finally {
            Context.exit()
        }
    }

    private data class SubSource(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("language") val language: String? = null,
        @JsonProperty("kind") val kind: String? = null
    )
}
