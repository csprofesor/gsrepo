// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Base64
import android.util.Log
import java.util.Collections
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

class DiziPal : MainAPI() {
    override var mainUrl              = "https://dizipal1820.com"
    override var name                 = "DiziPal"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.TvSeries, TvType.Movie)

    // ! CloudFlare bypass
    override var sequentialMainPage            = true
    override var sequentialMainPageDelay       = 150L
    override var sequentialMainPageScrollDelay = 150L

    private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    // ! CloudFlare v2
    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor      by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller): Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request  = chain.request()
            val response = chain.proceed(request)
            val bodyStr  = response.peekBody(1024 * 1024).string()
            val doc      = Jsoup.parse(bodyStr)
            val htmlStr  = doc.html()
            val htmlLow  = htmlStr.lowercase()

            if (response.code == 429 || response.code == 403 || response.code == 503
                || response.header("cf-mitigated") != null
                || htmlStr.contains("Just a moment")
                || htmlStr.contains("Attention Required!")
                || htmlStr.contains("Cloudflare")
                || htmlStr.contains("erisime_engellenmis")
                || htmlStr.contains("5651")
                || htmlStr.contains("Doğrulama")
                || htmlLow.contains("cf-turnstile")
                || htmlLow.contains("turnstile")
                || doc.title().contains("Doğrulama", ignoreCase = true)
            ) {
                return cloudflareKiller.intercept(chain)
            }

            return response
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/filmler"                      to "Son Filmler",
        "$mainUrl/diziler"                      to "Son Diziler",
        "$mainUrl/anime"                        to "Anime",
        "$mainUrl/yeni-eklenen-bolumler"        to "Yeni Bölümler",
        "$mainUrl/filmler/kategori/aksiyon"     to "Aksiyon Filmleri",
        "$mainUrl/filmler/kategori/komedi"      to "Komedi Filmleri",
        "$mainUrl/filmler/kategori/dram"        to "Dram Filmleri",
        "$mainUrl/filmler/kategori/korku"       to "Korku Filmleri",
        "$mainUrl/filmler/kategori/bilim-kurgu" to "Bilim Kurgu Filmleri",
        "$mainUrl/filmler/kategori/macera"      to "Macera Filmleri",
        "$mainUrl/filmler/kategori/animasyon"   to "Animasyon Filmleri",
        "$mainUrl/filmler/kategori/belgesel"    to "Belgesel Filmleri",
        "$mainUrl/filmler/kategori/gerilim"     to "Gerilim Filmleri",
        "$mainUrl/filmler/kategori/gizem"       to "Gizem Filmleri",
        "$mainUrl/filmler/kategori/romantik"    to "Romantik Filmleri",
        "$mainUrl/filmler/kategori/fantastik"   to "Fantastik Filmleri",
        "$mainUrl/diziler/kategori/aksiyon"     to "Aksiyon Dizileri",
        "$mainUrl/diziler/kategori/komedi"      to "Komedi Dizileri",
        "$mainUrl/diziler/kategori/dram"        to "Dram Dizileri",
        "$mainUrl/diziler/kategori/korku"       to "Korku Dizileri",
        "$mainUrl/diziler/kategori/bilim-kurgu" to "Bilim Kurgu Dizileri",
        "$mainUrl/diziler/kategori/animasyon"   to "Animasyon Dizileri",
        "$mainUrl/diziler/kategori/gerilim"     to "Gerilim Dizileri",
        "$mainUrl/diziler/kategori/gizem"       to "Gizem Dizileri",
        "$mainUrl/diziler/kategori/romantik"    to "Romantik Dizileri"
    )

    private val cardSelector = "li.content-card, article.content-card, article.dp-card, div.dp-card, div.movie-item, div.serie-item, div.content-item, a.top10-item, div.top10-item, a.trend-rank-card, a.m3-surface-2, .m3-surface-2"

    private fun fixPosterUrl(url: String?, backdropUrl: String? = null): String? {
        val cleanUrl = fixUrlNull(url?.takeIf {
            it.isNotBlank() && !it.startsWith("data:") && !it.contains("no-image") && !it.contains("placeholder")
        })
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

        val cleanBack = fixUrlNull(backdropUrl?.takeIf {
            it.isNotBlank() && !it.startsWith("data:") && !it.contains("no-image") && !it.contains("placeholder")
        })
        if (!cleanBack.isNullOrBlank()) {
            val finalBack = if (cleanBack.startsWith("http")) cleanBack else "$mainUrl/${cleanBack.removePrefix("/")}"
            return finalBack.replace("/backdrop/", "/poster/").replace("/face/", "/poster/")
        }
        return null
    }

    private val seenUrls = Collections.synchronizedSet(HashSet<String>())

    private fun String.toNormalizedPath(): String {
        return this.lowercase()
            .replace(Regex("""https?://[^/]+"""), "")
            .substringBefore("?")
            .substringBefore("#")
            .trimEnd('/')
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val firstCategory = mainPage.firstOrNull()
        if (page == 1 && (firstCategory == null || request.data == firstCategory.data || request.name == firstCategory.name)) {
            seenUrls.clear()
        }

        val home = mutableListOf<SearchResponse>()

        try {
            val targetUrl = request.data.replace(Regex("""https?://(dizipal\d*|ctutu|dizipal)\.[a-z0-9]+"""), mainUrl)
            var currentPage = if (page > 1) page else 1

            while (home.size < 12 && currentPage <= (if (page > 1) page else 3)) {
                val fetchUrl = if (currentPage == 1) {
                    targetUrl
                } else {
                    if (targetUrl.contains("?")) "$targetUrl&page=$currentPage" else "$targetUrl?page=$currentPage"
                }

                val document = try {
                    app.get(
                        fetchUrl, timeout = 10000, interceptor = interceptor, headers = getHeaders(mainUrl)
                    ).document
                } catch (_: Exception) { null } ?: break

                val cardElements = document.select(cardSelector)
                val pageResults = if (cardElements.isNotEmpty()) {
                    cardElements.mapNotNull { it.diziler() }
                } else {
                    val fallbackLinks = document.select("a[href*='/dizi/'], a[href*='/film/'], a[href*='/series/'], a[href*='/movies/']")
                    fallbackLinks.mapNotNull { it.diziler() }
                }

                if (pageResults.isEmpty()) break

                for (item in pageResults) {
                    val normPath = item.url.toNormalizedPath()
                    if (normPath.isNotBlank() && seenUrls.add(normPath)) {
                        home.add(item)
                    }
                }

                if (page > 1) break
                currentPage++
            }
        } catch (e: Exception) {
            Log.e("DiziPal", "getMainPage Hatası: ${e.message}")
        }

        return newHomePageResponse(request.name, home, hasNext = home.isNotEmpty())
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

        val imgEl = this.selectFirst("img") ?: aTag.selectFirst("img") ?: return null

        val rawPoster = imgEl.attr("src").takeIf { it.isNotBlank() && !it.startsWith("data:") && !it.contains("no-image") && !it.contains("placeholder") }
            ?: imgEl.attr("srcset")?.split(",")?.firstOrNull()?.trim()?.split(" ")?.firstOrNull()?.takeIf { it.isNotBlank() && !it.startsWith("data:") && !it.contains("no-image") && !it.contains("placeholder") }
            ?: imgEl.attr("data-src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") && !it.contains("no-image") && !it.contains("placeholder") }
            ?: imgEl.attr("data-original")?.takeIf { it.isNotBlank() && !it.startsWith("data:") && !it.contains("no-image") && !it.contains("placeholder") }
            ?: imgEl.attr("data-lazy-src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") && !it.contains("no-image") && !it.contains("placeholder") }
            ?: imgEl.attr("data-bg")?.takeIf { it.isNotBlank() && !it.startsWith("data:") && !it.contains("no-image") && !it.contains("placeholder") }
            ?: this.selectFirst("[style*='background']")?.attr("style")?.let { style ->
                Regex("""url\((['"]?)(.*?)\1\)""").find(style)?.groupValues?.get(2)?.takeIf { !it.startsWith("data:") && !it.contains("no-image") && !it.contains("placeholder") }
            }
            ?: aTag.selectFirst("[style*='background']")?.attr("style")?.let { style ->
                Regex("""url\((['"]?)(.*?)\1\)""").find(style)?.groupValues?.get(2)?.takeIf { !it.startsWith("data:") && !it.contains("no-image") && !it.contains("placeholder") }
            }

        val backdropImg = this.selectFirst("img[src*='backdrop'], img[data-src*='backdrop']")
            ?: aTag.selectFirst("img[src*='backdrop'], img[data-src*='backdrop']")
        val backdropUrl = backdropImg?.attr("data-src")?.takeIf { it.isNotBlank() } ?: backdropImg?.attr("src")

        val posterUrl = fixPosterUrl(rawPoster, backdropUrl) ?: return null

        val titleSelectors = ".card-title, .top10-title-text, h2, h3, h4, h5, .title, .name, .content-title, .dp-title, div.font-semibold, div.truncate, div.line-clamp-1, div.line-clamp-2, span.title, span.name"
        
        val titleFromEl = this.select(titleSelectors).firstOrNull {
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
        }?.text()?.trim()

        val aTitle = aTag.attr("title").trim()
        val imgAlt = imgEl.attr("alt")?.trim() ?: ""

        val rawTitle = if (!titleFromEl.isNullOrBlank()) {
            titleFromEl
        } else if (aTitle.isNotBlank() && !aTitle.lowercase().endsWith("izle")) {
            aTitle
        } else if (imgAlt.isNotBlank() && imgAlt != "loading icon" && !imgAlt.lowercase().contains("reklam")) {
            imgAlt
        } else {
            aTag.attr("title").ifBlank { this.attr("title") }
        }

        val title = rawTitle
            .removeSuffix(" izle").removeSuffix(" İzle")
            .removeSuffix(" afişi").removeSuffix(" Afişi")
            .removeSuffix(" izle -").removeSuffix(" İzle -")
            .split("\n").firstOrNull()?.trim() ?: ""

        if (title.isBlank() || title.matches(Regex("""^\d+$"""))) return null

        val scoreRaw = (
            this.selectFirst(".card-rating, .top10-rating, .imdb, .rating, .score, .point, .dp-rating, .dp-imdb, span[class*='imdb'], div[class*='imdb'], span[class*='rating'], div[class*='rating']")?.text()
            ?: aTag.selectFirst(".card-rating, .top10-rating, .imdb, .rating, .score, .point, .dp-rating, .dp-imdb, span[class*='imdb'], div[class*='imdb'], span[class*='rating'], div[class*='rating']")?.text()
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

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return results

        try {
            val mainDoc = try {
                app.get(mainUrl, headers = getHeaders(mainUrl), interceptor = interceptor).document
            } catch (_: Exception) { null }

            val cKey = mainDoc?.selectFirst("input[name='cKey']")?.attr("value") ?: ""
            val cValue = mainDoc?.selectFirst("input[name='cValue']")?.attr("value") ?: ""

            val responseRaw = app.post(
                "$mainUrl/bg/searchcontent",
                data = mapOf(
                    "cKey" to cKey,
                    "cValue" to cValue,
                    "type" to "hepsi",
                    "searchterm" to cleanQuery
                ),
                headers = mapOf(
                    "Accept" to "application/json, text/javascript, */*; q=0.01",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Content-Type" to "application/x-www-form-urlencoded",
                    "Referer" to "$mainUrl/"
                ),
                interceptor = interceptor
            ).text

            val mapper = jacksonObjectMapper()
            val searchResp: BgSearchResponse = mapper.readValue(responseRaw)
            searchResp.data?.result?.forEach { item ->
                val title = item.trTitle?.takeIf { it.isNotBlank() } ?: item.title?.trim()?.takeIf { it.isNotBlank() } ?: return@forEach
                val slug = item.slug?.trim()?.removePrefix("/") ?: return@forEach
                val href = if (slug.startsWith("http")) slug else "$mainUrl/$slug"
                val posterUrl = fixPosterUrl(item.poster, item.backUrl)
                val isMovie = item.type.equals("Movies", ignoreCase = true) || item.type.equals("film", ignoreCase = true) || href.contains("/film/")
                val scoreNum = item.imdb?.toString()?.trim()

                val res = if (isMovie) {
                    newMovieSearchResponse(title, href, TvType.Movie) {
                        this.posterUrl = posterUrl
                        this.score = Score.from10(scoreNum)
                    }
                } else {
                    newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                        this.posterUrl = posterUrl
                        this.score = Score.from10(scoreNum)
                    }
                }
                results.add(res)
            }
        } catch (e: Exception) {
            Log.e("DiziPal", "bg/searchcontent hatası: ${e.message}")
        }

        if (results.isEmpty()) {
            try {
                val doc = app.get(
                    "$mainUrl/arama?q=$cleanQuery",
                    headers = getHeaders(mainUrl),
                    interceptor = interceptor
                ).document
                val cardElements = doc.select(cardSelector)
                results.addAll(cardElements.mapNotNull { it.diziler() })
            } catch (e: Exception) {
                Log.e("DiziPal", "HTML search hatası: ${e.message}")
            }
        }

        return results.distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val targetUrl = url.replace(Regex("""https?://(dizipal\d*|ctutu|dizipal)\.[a-z0-9]+"""), mainUrl)
        val document = app.get(
            targetUrl, timeout = 10000, interceptor = interceptor, headers = getHeaders(mainUrl)
        ).document

        val rawTitle = document.selectFirst("h1, h2.title, div.page-top h1, .detail-title")?.text()?.trim()
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
            ?: return null

        val title = rawTitle
            .removeSuffix(" izle | Dizipal").removeSuffix(" izle - Dizipal")
            .removeSuffix(" izle").removeSuffix(" İzle").trim()

        val rawPoster = document.selectFirst("meta[property='og:image']")?.attr("content")?.takeIf { it.isNotBlank() && !it.startsWith("data:") && !it.contains("no-image") }
            ?: document.selectFirst("meta[name='twitter:image']")?.attr("content")?.takeIf { it.isNotBlank() && !it.startsWith("data:") && !it.contains("no-image") }
            ?: document.selectFirst(".film-poster img, .series-poster img, .detail-poster img, .poster img")?.let { img ->
                img.attr("src").takeIf { it.isNotBlank() && !it.startsWith("data:") && !it.contains("no-image") }
                    ?: img.attr("data-src").takeIf { it.isNotBlank() && !it.startsWith("data:") && !it.contains("no-image") }
            }
            ?: document.selectFirst("div.page-top img[alt]")?.let { img ->
                img.attr("src").takeIf { it.isNotBlank() && !it.startsWith("data:") && !it.contains("no-image") }
                    ?: img.attr("data-src").takeIf { it.isNotBlank() && !it.startsWith("data:") && !it.contains("no-image") }
            }
            ?: document.selectFirst("img[data-src*='posters']")?.attr("data-src")
            ?: document.selectFirst("img[src*='posters']")?.attr("src")

        val backdropImg = document.selectFirst("img[src*='backdrop'], img[data-src*='backdrop']")
        val backdropUrl = backdropImg?.attr("data-src")?.takeIf { it.isNotBlank() } ?: backdropImg?.attr("src")

        val poster = fixPosterUrl(rawPoster, backdropUrl)

        val year = document.selectXpath("//div[text()='Yıl']//following-sibling::div").text().trim().toIntOrNull()
            ?: document.select(".info-row, .sidebar-info div").firstNotNullOfOrNull { el ->
                if (el.text().contains("Yıl")) Regex("""(\d{4})""").find(el.text())?.groupValues?.get(1)?.toIntOrNull() else null
            }
            ?: Regex("""\((\d{4})\)""").find(document.title())?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""\((\d{4})\)""").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()

        val description = document.selectFirst("p.series-description, p.movie-description, div.summary p, div.description p, p.overview, .detail-summary, .overview, div.detail-desc")?.text()?.trim()
            ?: document.selectFirst("meta[name='description']")?.attr("content")?.trim()
            ?: document.selectFirst("meta[property='og:description']")?.attr("content")?.trim()

        val tags = document.select(".sidebar-info a[href*='/kategori/'], .sidebar-info a[href*='/tur/'], a[href*='/kategori/'], a[href*='/tur/']").map { it.text().trim() }.filter { it.isNotEmpty() }.distinct()

        val scoreRaw = document.selectFirst(".card-rating, .top10-rating, .imdb, .rating, .dp-rating, span[class*='imdb'], div[class*='imdb']")?.text()?.trim()
            ?: document.select(".info-row").firstNotNullOfOrNull { el ->
                if (el.text().contains("IMDB")) Regex("""(\d+(?:[.,]\d+)?)""").find(el.text())?.groupValues?.get(1) else null
            }
        val ratingNum = scoreRaw?.let { Regex("""(\d+(?:[.,]\d+)?)""").find(it)?.groupValues?.get(1)?.replace(",", ".") }

        if (targetUrl.contains("/movies/") || targetUrl.contains("/film/")) {
            return newMovieLoadResponse(title, targetUrl, TvType.Movie, targetUrl) {
                this.posterUrl = poster
                this.year      = year
                this.plot      = description
                this.tags      = tags
                this.score     = Score.from10(ratingNum)
            }
        } else {
            val episodeElements = document.select("a[href*='-bolum'], a[href*='/bolum/'], a[href*='-episode'], a[href*='/episode/'], li.episode-item, div.episode-card, a.dp-detail-episode, .detail-episode-item-wrap a")
            val episodes = episodeElements.mapNotNull { element ->
                val linkElement = if (element.tagName() == "a") element else element.selectFirst("a[href]") ?: return@mapNotNull null
                val epHref = fixUrlNull(linkElement.attr("href")) ?: return@mapNotNull null

                if (epHref.contains("yeni-eklenen-bolumler") || epHref.endsWith("/diziler")) return@mapNotNull null

                val rawEpName = linkElement.selectFirst("h2, small, strong, span")?.text()?.trim() ?: linkElement.text().trim()
                if (rawEpName.contains("İlk Bölüm") || rawEpName.contains("Son Bölüm")) return@mapNotNull null

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

            return newTvSeriesLoadResponse(title, targetUrl, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year      = year
                this.plot      = description
                this.tags      = tags
                this.score     = Score.from10(ratingNum)
            }
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
            Log.e("DiziPal", "Player config decryption failed: ${e.message}")
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val targetUrl = data.replace(Regex("""https?://(dizipal\d*|ctutu|dizipal)\.[a-z0-9]+"""), mainUrl)
        Log.d("DiziPal", "--> loadLinks URL: $targetUrl")
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

            // 1. Try div[data-rm-k=true] encrypted player payload first
            val encryptedText = doc.selectFirst("div[data-rm-k=true]")?.text() ?: ""
            if (encryptedText.isNotBlank()) {
                iframeUrl = decryptDizipalData(encryptedText)
            }

            // 2. Try legacy ajax-player-config if iframeUrl is still blank
            if (iframeUrl.isBlank()) {
                val cfgElements = doc.select(".video-player-container[data-cfg], [data-cfg]")
                val cfgList = cfgElements.mapNotNull { it.attr("data-cfg").takeIf { c -> c.isNotBlank() } }.distinct()

                for (cfg in cfgList) {
                    try {
                        val cookieHeader = cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
                        val tokResp = app.get(
                            "$mainUrl/ajax-token",
                            headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Accept" to "*/*",
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
                        Log.e("DiziPal", "ajax-token hatası: ${e.message}")
                    }

                    val finalCookieHeader = cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
                    val configResp = app.post(
                        "$mainUrl/ajax-player-config",
                        data = mapOf("cfg" to cfg),
                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Accept" to "*/*",
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
                        iframeUrl = configData.config.v ?: ""
                    }

                    if (iframeUrl.isNotBlank()) break
                }
            }

            // 3. Fallback to iframe src if still blank
            if (iframeUrl.isBlank()) {
                iframeUrl = doc.selectFirst("iframe")?.attr("src") ?: ""
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

                Log.d("DiziPal", "--> Extractor'a gönderilen Final URL: $iframeUrl")

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
                                "User-Agent" to USER_AGENT
                            )
                            Qualities.Unknown.value
                        }
                    )
                } else {
                    try {
                        val embedResp = app.get(
                            iframeUrl,
                            interceptor = interceptor,
                            timeout = 15000,
                            headers = mapOf(
                                "Referer" to targetUrl,
                                "User-Agent" to USER_AGENT
                            )
                        )
                        DizipalPlayer().extractFromHtml(
                            url = iframeUrl,
                            referer = targetUrl,
                            html = embedResp.text,
                            subtitleCallback = subtitleCallback,
                            callback = callback
                        )
                    } catch (e: Exception) {
                        Log.e("DiziPal", "embed fetch hatası: ${e.message}")
                        val loadedExt = loadExtractor(iframeUrl, targetUrl, subtitleCallback, callback)
                        if (!loadedExt) {
                            DizipalPlayer().getUrl(
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
            Log.e("DiziPal", "loadLinks Hatası: ${e.message}")
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
                ?: return "".also { Log.e("DiziPal", "--> HATA: Regex 'ciphertext' değerini bulamadı!") }

            val ivMatch = """"iv"\s*:\s*"([^"]+)"""".toRegex().find(rawJsonText)?.groupValues?.get(1)
                ?: return "".also { Log.e("DiziPal", "--> HATA: Regex 'iv' değerini bulamadı!") }

            val saltMatch = """"salt"\s*:\s*"([^"]+)"""".toRegex().find(rawJsonText)?.groupValues?.get(1)
                ?: return "".also { Log.e("DiziPal", "--> HATA: Regex 'salt' değerini bulamadı!") }

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
            Log.e("DiziPal", "--> HATA: Decryption sırasında Exception fırlatıldı! Mesaj: ${e.message}")
            e.printStackTrace()
            ""
        }
    }

    private fun getHeaders(baseUrl: String): Map<String, String> {
        return mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
            "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
            "Sec-Ch-Ua" to "\"Not-A.Brand\";v=\"99\", \"Chromium\";v=\"124\", \"Google Chrome\";v=\"124\"",
            "Sec-Ch-Ua-Mobile" to "?0",
            "Sec-Ch-Ua-Platform" to "\"Windows\"",
            "Sec-Fetch-Dest" to "document",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "same-origin",
            "Sec-Fetch-User" to "?1",
            "Upgrade-Insecure-Requests" to "1",
            "Referer" to baseUrl
        )
    }
}
