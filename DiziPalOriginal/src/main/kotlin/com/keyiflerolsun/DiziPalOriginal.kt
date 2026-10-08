// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
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
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class DiziPalOriginal : MainAPI() {
    override var mainUrl = "https://dizipal2137.com"
    override var name = "DiziPalOriginal"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)
    override var sequentialMainPage = true
    override var sequentialMainPageDelay       = 150L
    override var sequentialMainPageScrollDelay = 150L

    // ! CloudFlare v2
    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor      by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller): Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request  = chain.request()
            val response = chain.proceed(request)
            val doc      = Jsoup.parse(response.peekBody(1024 * 1024).string())
            val htmlStr  = doc.html()
            val htmlLow  = htmlStr.lowercase()

            if (htmlStr.contains("Just a moment")
                || htmlStr.contains("Attention Required!")
                || htmlStr.contains("Cloudflare")
                || htmlStr.contains("erisime_engellenmis")
                || htmlStr.contains("5651")
                || htmlStr.contains("Doğrulama")
                || htmlLow.contains("cf-turnstile")
                || htmlLow.contains("turnstile")
            ) {
                return cloudflareKiller.intercept(chain)
            }

            return response
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/trend"                 to "Trendler",
        "$mainUrl/diziler"               to "Diziler",
        "$mainUrl/filmler"               to "Filmler",
        "$mainUrl/platform/netflix"      to "Netflix",
        "$mainUrl/platform/blutv"        to "BluTV",
        "$mainUrl/platform/disney-plus"  to "Disney+",
        "$mainUrl/platform/exxen"        to "Exxen",
        "$mainUrl/platform/gain"         to "GAİN",
        "$mainUrl/platform/max"          to "Max",
        "$mainUrl/platform/prime-video"  to "Prime Video",
        "$mainUrl/platform/tabii"        to "tabii",
        "$mainUrl/kategori/aksiyon"      to "Aksiyon",
        "$mainUrl/kategori/komedi"       to "Komedi",
        "$mainUrl/kategori/dram"         to "Dram",
        "$mainUrl/kategori/korku"        to "Korku",
        "$mainUrl/kategori/bilim-kurgu"  to "Bilim Kurgu",
        "$mainUrl/kategori/animasyon"    to "Animasyon",
        "$mainUrl/kategori/belgesel"     to "Belgesel",
        "$mainUrl/kategori/gerilim"      to "Gerilim",
        "$mainUrl/kategori/gizem"        to "Gizem",
        "$mainUrl/kategori/romantik"     to "Romantik"
    )

    private val cardSelector = "li.content-card, div.content-card, article.content-card, article.dp-card, div.dp-card, div.poster, div.movie-item, div.serie-item, div.content-item, div.card, div.group"

    private fun fixPosterUrl(url: String?, backdropUrl: String? = null): String? {
        val cleanUrl = fixUrlNull(url?.takeIf { it.isNotBlank() && !it.startsWith("data:") })
        if (!cleanUrl.isNullOrBlank()) {
            val finalUrl = if (cleanUrl.startsWith("http")) cleanUrl else "$mainUrl/${cleanUrl.removePrefix("/")}"
            if (finalUrl.contains("ampproject.org")) {
                return finalUrl
                    .replace("/backdrop/", "/poster/")
                    .replace("/face/", "/poster/")
                    .replace("/square/", "/poster/")
                    .replace("/brand/", "/poster/")
            }
            return finalUrl
        }

        val cleanBack = fixUrlNull(backdropUrl?.takeIf { it.isNotBlank() && !it.startsWith("data:") })
        if (!cleanBack.isNullOrBlank()) {
            val finalBack = if (cleanBack.startsWith("http")) cleanBack else "$mainUrl/${cleanBack.removePrefix("/")}"
            return finalBack.replace("/backdrop/", "/poster/").replace("/face/", "/poster/")
        }
        return null
    }

    private fun Element.diziler(): SearchResponse? {
        val aTag = if (this.tagName() == "a") this else this.selectFirst("a[href]") ?: return null
        val rawHref = aTag.attr("href")
        val href = fixUrlNull(rawHref) ?: return null

        if (href.isBlank()
            || href.endsWith("/kanal/")
            || href.endsWith("/trendler")
            || href.endsWith("/diziler")
            || href.endsWith("/filmler")
            || href.endsWith("/yeni-eklenen-bolumler")
            || href.endsWith("/yabanci-dizi-izle")
            || href.endsWith("/hd-film-izle")
            || href.endsWith("/anime")
            || href == mainUrl
            || href == "$mainUrl/"
            || href.contains("javascript:")
            || (href.contains("/tur/") && !href.contains("/dizi/") && !href.contains("/film/") && !href.contains("/series/") && !href.contains("/movies/"))
            || (href.contains("/kategori/") && !href.contains("/dizi/") && !href.contains("/film/") && !href.contains("/series/") && !href.contains("/movies/"))
            || (href.contains("/platform/") && !href.contains("/dizi/") && !href.contains("/film/") && !href.contains("/series/") && !href.contains("/movies/"))
        ) return null

        val imgEl = this.selectFirst("img") ?: aTag.selectFirst("img")

        val rawPoster = imgEl?.attr("data-src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?: imgEl?.attr("data-original")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?: imgEl?.attr("data-lazy-src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?: imgEl?.attr("data-bg")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?: imgEl?.attr("srcset")?.split(",")?.firstOrNull()?.trim()?.split(" ")?.firstOrNull()?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?: imgEl?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?: this.selectFirst("[style*='background']")?.attr("style")?.let { style ->
                Regex("""url\((['"]?)(.*?)\1\)""").find(style)?.groupValues?.get(2)?.takeIf { !it.startsWith("data:") }
            }
            ?: aTag.selectFirst("[style*='background']")?.attr("style")?.let { style ->
                Regex("""url\((['"]?)(.*?)\1\)""").find(style)?.groupValues?.get(2)?.takeIf { !it.startsWith("data:") }
            }

        val backdropImg = this.selectFirst("img[src*='backdrop'], img[data-src*='backdrop']")
            ?: aTag.selectFirst("img[src*='backdrop'], img[data-src*='backdrop']")
        val backdropUrl = backdropImg?.attr("data-src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?: backdropImg?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }

        val posterUrl = fixPosterUrl(rawPoster, backdropUrl) ?: return null

        val titleSelectors = ".card-title, h2, h3, h4, h5, .title, .name, .content-title, .dp-title, div.font-semibold, div.truncate, div.line-clamp-1, div.line-clamp-2, span.title, span.name"
        
        val aTitle = aTag.attr("title").trim()
        val imgAlt = imgEl?.attr("alt")?.trim() ?: ""
        val rawTitle = if (aTitle.isNotBlank() && !aTitle.lowercase().endsWith("izle")) {
            aTitle
        } else if (imgAlt.isNotBlank() && imgAlt != "loading icon" && !imgAlt.lowercase().contains("reklam")) {
            imgAlt
        } else {
            this.select(titleSelectors).firstOrNull {
                val t = it.text().trim()
                val cls = it.className().lowercase()
                val tLow = t.lowercase()
                t.isNotBlank()
                && !t.matches(Regex("""^[\d.,\s/]+$"""))
                && !tLow.contains("imdb")
                && !tLow.contains("sezon")
                && !tLow.contains("bölüm")
                && !cls.contains("imdb")
                && !cls.contains("score")
                && !cls.contains("rating")
                && !cls.contains("point")
            }?.text()
            ?: aTag.attr("title")
            ?: this.attr("title")
        }.toString().trim()

        val title = rawTitle
            .removeSuffix(" izle").removeSuffix(" İzle")
            .removeSuffix(" izle -").removeSuffix(" İzle -").trim()
        if (title.isBlank()) return null

        val scoreRaw = (
            this.selectFirst(".card-rating, .imdb, .rating, .score, .point, .dp-rating, .dp-imdb, span[class*='imdb'], div[class*='imdb'], span[class*='rating'], div[class*='rating']")?.text()
            ?: aTag.selectFirst(".card-rating, .imdb, .rating, .score, .point, .dp-rating, .dp-imdb, span[class*='imdb'], div[class*='imdb'], span[class*='rating'], div[class*='rating']")?.text()
            ?: this.attr("data-imdb").takeIf { it.isNotBlank() }
            ?: aTag.attr("data-imdb").takeIf { it.isNotBlank() }
            ?: this.attr("data-score").takeIf { it.isNotBlank() }
            ?: aTag.attr("data-score").takeIf { it.isNotBlank() }
        )?.trim()

        val scoreNum = scoreRaw?.let { Regex("""(\d+(?:[.,]\d+)?)""").find(it)?.groupValues?.get(1)?.replace(",", ".") }

        val isMovie = href.contains("/movies/") || href.contains("/film/")
        return if (isMovie) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.score     = Score.from10(scoreNum)
            }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.score     = Score.from10(scoreNum)
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val home = mutableListOf<SearchResponse>()

        try {
            val targetUrl = request.data.replace(Regex("""https://dizipal\d+\.com"""), mainUrl)
            val url = if (page > 1) {
                if (targetUrl.contains("?")) "$targetUrl&page=$page" else "$targetUrl?page=$page"
            } else {
                targetUrl
            }

            val document = app.get(
                url, timeout = 10000, interceptor = interceptor, headers = getHeaders(mainUrl)
            ).document

            val cardElements = document.select(cardSelector)
            val pageResults = cardElements.mapNotNull { it.diziler() }
            if (pageResults.isNotEmpty()) {
                home.addAll(pageResults)
            } else {
                val fallbackLinks = document.select("a[href*='/dizi/'], a[href*='/film/']")
                home.addAll(fallbackLinks.mapNotNull { it.diziler() })
            }
        } catch (e: Exception) {
            Log.e("DiziPalOriginal", "getMainPage Hatası: ${e.message}")
        }

        val items = home.distinctBy { it.url }
        return newHomePageResponse(request.name, items, items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        try {
            val responseRaw = app.get(
                "$mainUrl/ajax-search?q=${query.trim()}",
                headers = mapOf(
                    "Accept" to "application/json, text/javascript, */*; q=0.01",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to "$mainUrl/"
                ),
                interceptor = interceptor
            ).text

            val mapper = jacksonObjectMapper()
            val searchResp: DizipalSearchResponse = mapper.readValue(responseRaw)
            searchResp.results?.forEach { item ->
                val title = item.title?.trim()?.takeIf { it.isNotBlank() } ?: return@forEach
                val rawHref = item.url?.trim()?.takeIf { it.isNotBlank() } ?: return@forEach
                val href = if (rawHref.startsWith("http")) rawHref else "$mainUrl/${rawHref.removePrefix("/")}"
                val posterUrl = fixPosterUrl(item.poster)
                val isMovie = item.type.equals("film", ignoreCase = true) || href.contains("/film/") || href.contains("/movies/")
                val ratingStr = item.rating?.toString()?.trim()

                val res = if (isMovie) {
                    newMovieSearchResponse(title, href, TvType.Movie) {
                        this.posterUrl = posterUrl
                        this.score = Score.from10(ratingStr)
                    }
                } else {
                    newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                        this.posterUrl = posterUrl
                        this.score = Score.from10(ratingStr)
                    }
                }
                results.add(res)
            }
        } catch (e: Exception) {
            Log.e("DiziPalOriginal", "ajax-search hatası: ${e.message}")
        }

        if (results.isEmpty()) {
            try {
                val doc = app.get(
                    "$mainUrl/arama?q=${query.trim()}",
                    headers = getHeaders(mainUrl),
                    interceptor = interceptor
                ).document
                val cardElements = doc.select(cardSelector)
                results.addAll(cardElements.mapNotNull { it.diziler() })
            } catch (e: Exception) {
                Log.e("DiziPalOriginal", "HTML search hatası: ${e.message}")
            }
        }

        return results.distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(
            url, timeout = 10000, interceptor = interceptor, headers = getHeaders(mainUrl)
        ).document

        val rawTitle = document.selectFirst("h1, h2.title, div.page-top h1")?.text()?.trim()
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
            ?: return null

        val title = rawTitle
            .removeSuffix(" izle | Dizipal").removeSuffix(" izle - Dizipal")
            .removeSuffix(" izle").removeSuffix(" İzle").trim()

        val rawPoster = document.selectFirst("img[data-src*='posters']")?.attr("data-src")
            ?: document.selectFirst("img[src*='posters']")?.attr("src")
            ?: document.selectFirst("div.page-top img[alt]")?.attr("src")
            ?: document.selectFirst("meta[property='og:image']")?.attr("content")

        val backdropImg = document.selectFirst("img[src*='backdrop'], img[data-src*='backdrop']")
        val backdropUrl = backdropImg?.attr("data-src")?.takeIf { it.isNotBlank() } ?: backdropImg?.attr("src")

        val poster = fixPosterUrl(rawPoster, backdropUrl)

        val year = document.selectXpath("//div[text()='Yıl']//following-sibling::div").text().trim().toIntOrNull()
            ?: Regex("""\((\d{4})\)""").find(document.title())?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""\((\d{4})\)""").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()

        val description = document.selectFirst("div.summary p, div.description p, p.overview")?.text()?.trim()
            ?: document.selectFirst("meta[name='description']")?.attr("content")

        val tags = document.select("a[href*='/kategori/'], a[href*='/tur/']").map { it.text().trim() }.filter { it.isNotEmpty() }.distinct()

        val scoreRaw = document.selectFirst(".card-rating, .imdb, .rating, .dp-rating, span[class*='imdb'], div[class*='imdb']")?.text()?.trim()
        val ratingNum = scoreRaw?.let { Regex("""(\d+(?:[.,]\d+)?)""").find(it)?.groupValues?.get(1)?.replace(",", ".") }

        if (url.contains("/movies/") || url.contains("/film/")) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year      = year
                this.plot      = description
                this.tags      = tags
                this.score     = Score.from10(ratingNum)
            }
        }

        val episodeElements = document.select("a[href*='/bolum/'], a[href*='/episode/'], li.episode-item, div.episode-card, a.dp-detail-episode")
        val episodes = episodeElements.mapNotNull { element ->
            val linkElement = if (element.tagName() == "a") element else element.selectFirst("a[href]") ?: return@mapNotNull null
            val epHref = fixUrlNull(linkElement.attr("href")) ?: return@mapNotNull null
            val rawEpName = linkElement.selectFirst("h2, small, strong, span")?.text()?.trim() ?: linkElement.text().trim()

            val epSeason = Regex("""(\d+)\.\s*Sezon""").find(rawEpName)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""(\d+)-sezon""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""(\d+)x\d+""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                ?: 1

            val epEpisode = Regex("""(\d+)\.\s*Bölüm""").find(rawEpName)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""(\d+)-bolum""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""\d+x(\d+)""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()

            newEpisode(epHref) {
                this.name    = rawEpName.ifBlank { "Bölüm $epEpisode" }
                this.episode = epEpisode
                this.season  = epSeason
            }
        }.distinctBy { it.data }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.year      = year
            this.plot      = description
            this.tags      = tags
            this.score     = Score.from10(ratingNum)
        }
    }

    private fun decryptPlayerConfig(enc: DizipalEncData): String? {
        return try {
            val c = enc.c?.let { Base64.decode(it, Base64.DEFAULT) } ?: return null
            val iv = enc.iv?.let { Base64.decode(it, Base64.DEFAULT) } ?: return null
            val k1 = enc.k1?.let { Base64.decode(it, Base64.DEFAULT) } ?: return null
            val k2 = enc.k2?.let { Base64.decode(it, Base64.DEFAULT) } ?: return null

            val minLen = minOf(k1.size, k2.size)
            val keyBytes = ByteArray(minLen) { i -> (k1[i].toInt() xor k2[i].toInt()).toByte() }

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv))

            val decryptedBytes = cipher.doFinal(c)
            var decryptedUrl = String(decryptedBytes, Charsets.UTF_8).trim()

            if (decryptedUrl.startsWith("//")) {
                decryptedUrl = "https:$decryptedUrl"
            } else if (decryptedUrl.startsWith("://")) {
                decryptedUrl = "https$decryptedUrl"
            } else if (!decryptedUrl.startsWith("http")) {
                decryptedUrl = "https://$decryptedUrl"
            }

            decryptedUrl
        } catch (e: Exception) {
            Log.e("DiziPalOriginal", "Player config decryption failed: ${e.message}")
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val targetUrl = data.replace(Regex("""https://dizipal\d+\.com"""), mainUrl)
        Log.d("DiziPalOriginal", "--> loadLinks URL: $targetUrl")
        var iframeUrl = ""

        try {
            val cookieMap = mutableMapOf<String, String>()

            fun updateCookies(headers: Headers) {
                val setCookies = headers.values("Set-Cookie")
                for (sc in setCookies) {
                    val part = sc.substringBefore(";")
                    if (part.contains("=")) {
                        val key = part.substringBefore("=").trim()
                        val value = part.substringAfter("=").trim()
                        if (key.isNotBlank() && value.isNotBlank()) {
                            cookieMap[key] = value
                        }
                    }
                }
            }

            val pageResp = app.get(
                targetUrl, timeout = 10000, interceptor = interceptor, headers = getHeaders(mainUrl)
            )
            updateCookies(pageResp.headers)
            val doc = pageResp.document

            val cfgElements = doc.select(".video-player-container[data-cfg], [data-cfg]")
            val cfgList = cfgElements.mapNotNull { it.attr("data-cfg").takeIf { c -> c.isNotBlank() } }.distinct()

            for (cfg in cfgList) {
                try {
                    val cookieHeader = cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
                    val tokResp = app.get(
                        "$mainUrl/ajax-token",
                        headers = mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                            "Referer" to targetUrl,
                            "Cookie" to cookieHeader
                        ),
                        interceptor = interceptor
                    )
                    updateCookies(tokResp.headers)

                    val tokText = tokResp.text
                    val tokenVal = Regex(""""t"\s*:\s*"([^"]+)"""").find(tokText)?.groupValues?.get(1)
                    if (!tokenVal.isNullOrBlank()) {
                        cookieMap["_ct"] = tokenVal
                    }
                } catch (e: Exception) {
                    Log.e("DiziPalOriginal", "ajax-token hatası: ${e.message}")
                }

                val finalCookieHeader = cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
                val configResp = app.post(
                    "$mainUrl/ajax-player-config",
                    data = mapOf("cfg" to cfg),
                    headers = mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Content-Type" to "application/x-www-form-urlencoded",
                        "Referer" to targetUrl,
                        "Cookie" to finalCookieHeader
                    ),
                    cookies = cookieMap,
                    interceptor = interceptor
                ).text

                val mapper = jacksonObjectMapper()
                val configData: DizipalPlayerConfigResponse = mapper.readValue(configResp)
                if (configData.enc != null) {
                    iframeUrl = decryptPlayerConfig(configData.enc) ?: ""
                }
                if (iframeUrl.isBlank() && !configData.config?.v.isNullOrBlank()) {
                    iframeUrl = configData.config.v
                }

                if (iframeUrl.isNotBlank()) {
                    if (iframeUrl.contains("<iframe")) {
                        val extracted = Regex("""src=["']([^"']+)["']""").find(iframeUrl)?.groupValues?.get(1)
                        if (!extracted.isNullOrBlank()) {
                            iframeUrl = extracted
                        }
                    }

                    if (iframeUrl.startsWith("//")) iframeUrl = "https:$iframeUrl"
                    else if (iframeUrl.startsWith("://")) iframeUrl = "https$iframeUrl"
                    else if (!iframeUrl.startsWith("http")) iframeUrl = "https://$iframeUrl"

                    Log.d("DiziPalOriginal", "--> Extractor'a gönderilen Final URL: $iframeUrl")

                    if (iframeUrl.contains(".m3u8") || iframeUrl.contains(".mp4")) {
                        val isM3u8 = iframeUrl.contains(".m3u8")
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = "DiziPal Direct",
                                url = iframeUrl,
                                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                headers = mapOf(
                                    "Referer" to targetUrl,
                                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                                )
                                Qualities.Unknown.value
                            }
                        )
                    } else {
                        val loadedExt = loadExtractor(iframeUrl, targetUrl, subtitleCallback, callback)
                        if (!loadedExt) {
                            try {
                                val embedResp = app.get(
                                    iframeUrl,
                                    interceptor = interceptor,
                                    timeout = 15000,
                                    headers = mapOf(
                                        "Referer" to targetUrl,
                                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                                    )
                                )
                                DizipalOriginalPlayer().extractFromHtml(
                                    url = iframeUrl,
                                    referer = targetUrl,
                                    html = embedResp.text,
                                    subtitleCallback = subtitleCallback,
                                    callback = callback
                                )
                            } catch (e: Exception) {
                                Log.e("DiziPalOriginal", "embed fetch hatası: ${e.message}")
                                DizipalOriginalPlayer().getUrl(
                                    url = iframeUrl,
                                    referer = targetUrl,
                                    subtitleCallback = subtitleCallback,
                                    callback = callback
                                )
                            }
                        }
                    }
                }
            }

            if (iframeUrl.isBlank()) {
                val encryptedText = doc.selectFirst("div[data-rm-k=true]")?.text() ?: ""
                if (encryptedText.isNotEmpty()) {
                    iframeUrl = decryptDizipalData(encryptedText)
                } else {
                    iframeUrl = doc.selectFirst("iframe")?.attr("src") ?: ""
                }

                if (iframeUrl.isNotBlank()) {
                    if (iframeUrl.startsWith("//")) iframeUrl = "https:$iframeUrl"
                    else if (iframeUrl.startsWith("://")) iframeUrl = "https$iframeUrl"
                    else if (!iframeUrl.startsWith("http")) iframeUrl = "https://$iframeUrl"

                    val loadedExt = loadExtractor(iframeUrl, targetUrl, subtitleCallback, callback)
                    if (!loadedExt) {
                        try {
                            val embedResp = app.get(
                                iframeUrl,
                                interceptor = interceptor,
                                timeout = 15000,
                                headers = mapOf(
                                    "Referer" to targetUrl,
                                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                                )
                            )
                            DizipalOriginalPlayer().extractFromHtml(
                                url = iframeUrl,
                                referer = targetUrl,
                                html = embedResp.text,
                                subtitleCallback = subtitleCallback,
                                callback = callback
                            )
                        } catch (e: Exception) {
                            Log.e("DiziPalOriginal", "embed fetch hatası: ${e.message}")
                            DizipalOriginalPlayer().getUrl(
                                url = iframeUrl,
                                referer = targetUrl,
                                subtitleCallback = subtitleCallback,
                                callback = callback
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("DiziPalOriginal", "loadLinks Hatası: ${e.message}")
        }

        return true
    }

    private fun String.decodeHex(): ByteArray {
        check(length % 2 == 0) { "Hex string çift uzunlukta olmalıdır" }
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun decryptDizipalData(rawJsonText: String): String {
        return try {
            val passphrase = "3hPn4uCjTVtfYWcjIcoJQ4cL1WWk1qxXI39egLYOmNv6IblA7eKJz68uU3eLzux1biZLCms0quEjTYniGv5z1JcKbNIsDQFSeIZOBZJz4is6pD7UyWDggWWzTLBQbHcQFpBQdClnuQaMNUHtLHTpzCvZy33p6I7wFBvL4fnXBYH84aUIyWGTRvM2G5cfoNf4705tO2kv"

            val ctMatch = """"ciphertext"\s*:\s*"([^"]+)"""".toRegex().find(rawJsonText)?.groupValues?.get(1)
                ?: return "".also { Log.e("DiziPalOriginal", "--> HATA: Regex 'ciphertext' değerini bulamadı!") }

            val ivMatch = """"iv"\s*:\s*"([^"]+)"""".toRegex().find(rawJsonText)?.groupValues?.get(1)
                ?: return "".also { Log.e("DiziPalOriginal", "--> HATA: Regex 'iv' değerini bulamadı!") }

            val saltMatch = """"salt"\s*:\s*"([^"]+)"""".toRegex().find(rawJsonText)?.groupValues?.get(1)
                ?: return "".also { Log.e("DiziPalOriginal", "--> HATA: Regex 'salt' değerini bulamadı!") }

            val salt = saltMatch.decodeHex()
            val iv = ivMatch.decodeHex()
            val ciphertext = Base64.decode(ctMatch, Base64.DEFAULT)

            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
            val spec = PBEKeySpec(passphrase.toCharArray(), salt, 999, 256)
            val secretKey = factory.generateSecret(spec)
            val secret = SecretKeySpec(secretKey.encoded, "AES")

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, secret, IvParameterSpec(iv))

            val decryptedBytes = cipher.doFinal(ciphertext)
            var finalUrl = String(decryptedBytes, Charsets.UTF_8).replace("\\/", "/")

            if (finalUrl.startsWith("://")) {
                finalUrl = "https$finalUrl"
            } else if (finalUrl.startsWith("//")) {
                finalUrl = "https:$finalUrl"
            } else if (!finalUrl.startsWith("http")) {
                finalUrl = "https://$finalUrl"
            }

            finalUrl
        } catch (e: Exception) {
            Log.e("DiziPalOriginal", "--> HATA: Decryption sırasında Exception fırlatıldı! Mesaj: ${e.message}")
            e.printStackTrace()
            ""
        }
    }

    private fun getHeaders(baseUrl: String): Map<String, String> {
        return mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
            "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
            "Referer" to baseUrl
        )
    }
}
