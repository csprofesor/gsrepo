package com.keyiflerolsun

import android.util.Base64
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val PRIVATE_AES_KEY = "9bYMCNQiWsXIYFWYAu7EkdsSbmGBTyUI"
private val jacksonMapper = ObjectMapper()

class SelcukFlix : MainAPI() {
    override var mainUrl              = "https://selcukflix.app"
    override var name                 = "SelcukFlix"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val hasDownloadSupport   = true
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries)

    override var sequentialMainPage            = true
    override var sequentialMainPageDelay       = 50L
    override var sequentialMainPageScrollDelay = 50L

    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor      by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request  = chain.request()
            val response = chain.proceed(request)
            val doc      = Jsoup.parse(response.peekBody(10 * 1024).string())
            if (response.code == 503
                || doc.html().contains("Just a moment")
                || doc.html().contains("verifying")
                || doc.selectFirst("meta[name='cloudflare']") != null
            ) {
                return cloudflareKiller.intercept(chain)
            }
            return response
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/film-izle"                  to "Yeni Eklenen Filmler",
        "$mainUrl/dizi-izle"                  to "Yeni Diziler",
        "$mainUrl/trend"                      to "Trendler",
        "$mainUrl/kesfet"                     to "Keşfet",
        "$mainUrl/film-kategori/aksiyon"      to "Aksiyon Filmleri",
        "$mainUrl/film-kategori/komedi"       to "Komedi Filmleri",
        "$mainUrl/film-kategori/suc"          to "Suç Filmleri",
        "$mainUrl/film-kategori/animasyon"    to "Animasyon Filmleri",
        "$mainUrl/film-kategori/korku"        to "Korku Filmleri",
        "$mainUrl/film-kategori/bilim-kurgu"  to "Bilim Kurgu Filmleri",
        "$mainUrl/film-kategori/dram"         to "Dram Filmleri",
        "$mainUrl/film-kategori/romantik"     to "Romantik Filmler",
        "$mainUrl/film-kategori/macera"       to "Macera Filmleri",
        "$mainUrl/film-kategori/aile"         to "Aile Filmleri",
        "$mainUrl/film-kategori/fantastik"    to "Fantastik Filmler",
        "$mainUrl/film-kategori/gerilim"      to "Gerilim Filmleri",
        "$mainUrl/film-kategori/gizem"        to "Gizem Filmleri",
        "$mainUrl/film-kategori/savas"        to "Savaş Filmleri",
        "$mainUrl/film-kategori/western"      to "Western Filmleri"
    )

    private fun decryptAES(encryptedData: String): String? {
        if (encryptedData.isBlank()) return null
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val bytes  = PRIVATE_AES_KEY.toByteArray(Charsets.UTF_8)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(bytes, "AES"), IvParameterSpec(ByteArray(16)))
            String(cipher.doFinal(Base64.decode(encryptedData, 0)), Charsets.UTF_8)
        } catch (_: Exception) { null }
    }

    private fun decodeSecureData(secureData: String): String? {
        return if (secureData.startsWith("eyJ")) {
            try { String(Base64.decode(secureData, 0), Charsets.UTF_8) } catch (_: Exception) { null }
        } else {
            decryptAES(secureData)
        }
    }

    private fun extractSecureData(html: String): String? {
        return try {
            val doc    = Jsoup.parse(html)
            val script = doc.selectFirst("script#__NEXT_DATA__")?.data() ?: return null
            val root   = jacksonMapper.readTree(script)
            root?.get("props")?.get("pageProps")?.get("secureData")?.asText()
        } catch (_: Exception) { null }
    }

    private fun fixPosterUrl(raw: String?): String? {
        if (raw.isNullOrBlank() || raw == "null") return null
        var url = raw.trim().replace("images-macellan-online.cdn.ampproject.org/i/s/", "")

        if (url.startsWith("//")) {
            url = "https:$url"
        } else if (url.startsWith("/")) {
            url = fixUrl(url)
        }

        url = url.replace(Regex("https?://file\\.[a-zA-Z0-9.-]+/"), "https://file.macellan.online/")
        url = url.replace(Regex("https?://images\\.[a-zA-Z0-9.-]+/"), "https://images.macellan.online/")
        url = url.replace("/f/f/", "/630/910/")

        return fixUrlNull(url)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val data  = request.data
        val items = mutableListOf<SearchResponse>()

        val url = if (page > 1) {
            if (data.contains("/film-kategori/")) {
                val catSlug = data.substringAfter("/film-kategori/")
                "$mainUrl/api/bg/findMovies?curPage=$page&perPageCount=24&queryStr=&categorySlugsComma=film-kategori/$catSlug&countryCodesComma="
            } else if (data.endsWith("/film-izle")) {
                "$mainUrl/api/bg/findMovies?curPage=$page&perPageCount=24&queryStr=&categorySlugsComma=&countryCodesComma="
            } else {
                "$data?page=$page"
            }
        } else {
            data
        }

        try {
            if (url.contains("/api/bg/findMovies")) {
                val responseText = app.post(
                    url = url,
                    headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                        "Accept" to "application/json, text/plain, */*",
                        "Referer" to "$mainUrl/"
                    ),
                    interceptor = interceptor
                ).text
                val encryptedData = jacksonMapper.readTree(responseText)?.get("response")?.asText()
                if (!encryptedData.isNullOrBlank()) {
                    val decoded = decryptAES(encryptedData)
                    if (decoded != null) {
                        val json: JsonNode = jacksonMapper.readTree(decoded)
                        json.get("result")?.forEach { item ->
                            val title  = item.get("object_name")?.asText() ?: item.get("original_title")?.asText() ?: return@forEach
                            val slug   = item.get("used_slug")?.asText() ?: return@forEach
                            val poster = fixPosterUrl(item.get("object_poster_url")?.asText() ?: item.get("poster_url")?.asText())
                            val href   = fixUrl(slug)
                            items.add(newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster })
                        }
                    }
                }
            } else {
                val responseText = app.get(url, interceptor = interceptor).text
                val secureDataRaw = extractSecureData(responseText)
                if (secureDataRaw != null) {
                    val jsonText = decodeSecureData(secureDataRaw)
                    if (jsonText != null) {
                        try {
                            val json: JsonNode = jacksonMapper.readTree(jsonText)
                            val listItemsNode = json.get("listItems")
                                ?: json.get("dailyTrends")
                                ?: json.get("getLastMovies")
                                ?: json.get("trendMovies")
                                ?: json.get("allPopularSeries")

                            listItemsNode?.forEach { item ->
                                val title  = item.get("object_name")?.asText()
                                    ?: item.get("original_title")?.asText()
                                    ?: item.get("title")?.asText()
                                    ?: return@forEach
                                val slug   = item.get("used_slug")?.asText()
                                    ?: item.get("slug")?.asText()
                                    ?: return@forEach
                                val poster = fixPosterUrl(
                                    item.get("object_poster_url")?.asText()
                                        ?: item.get("poster_url")?.asText()
                                )
                                val href   = fixUrl(slug)
                                if (href.contains("/dizi/")) {
                                    items.add(newTvSeriesSearchResponse(title, href.substringBefore("/sezon"), TvType.TvSeries) { posterUrl = poster })
                                } else {
                                    items.add(newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster })
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }

                if (items.isEmpty()) {
                    val doc = Jsoup.parse(responseText)
                    doc.select("a[href*=/film/], a[href*=/dizi/]").forEach { el ->
                        val href = fixUrlNull(el.attr("href")) ?: return@forEach
                        if (href == "$mainUrl/film-izle"
                            || href == "$mainUrl/dizi-izle"
                            || href == "$mainUrl/seri-filmler"
                            || href == "$mainUrl/trend"
                            || href == "$mainUrl/kesfet") return@forEach

                        val img   = el.selectFirst("img")
                        val title = el.selectFirst("h2,h3")?.text()
                            ?: img?.attr("alt")?.replace(Regex("\\d+\\.\\s*(Sezon|Bölüm)|izle", RegexOption.IGNORE_CASE), "")?.trim()
                            ?: return@forEach
                        if (title.isBlank()) return@forEach

                        val poster = fixPosterUrl(
                            img?.attr("data-src")?.takeIf { it.isNotBlank() } ?: img?.attr("src")
                        )

                        if (href.contains("/dizi/")) {
                            items.add(newTvSeriesSearchResponse(title, href.substringBefore("/sezon"), TvType.TvSeries) { posterUrl = poster })
                        } else {
                            items.add(newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster })
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        return newHomePageResponse(request.name, items.distinctBy { it.url }, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()

        try {
            val searchUrl = "$mainUrl/api/bg/searchcontent?searchterm=$query"
            val response  = app.post(
                url         = searchUrl,
                headers     = mapOf(
                    "User-Agent"       to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "Accept"           to "application/json, text/plain, */*",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer"          to "$mainUrl/"
                ),
                referer     = "$mainUrl/",
                interceptor = interceptor
            ).text

            val encryptedData = jacksonMapper.readTree(response)?.get("response")?.asText()
            if (!encryptedData.isNullOrBlank()) {
                val decoded = decryptAES(encryptedData)
                if (decoded != null) {
                    val json: JsonNode = jacksonMapper.readTree(decoded)
                    json.get("result")?.forEach { item: JsonNode ->
                        val title  = item.get("object_name")?.asText() ?: return@forEach
                        val slug   = item.get("used_slug")?.asText() ?: return@forEach
                        val poster = fixPosterUrl(item.get("object_poster_url")?.asText() ?: item.get("poster_url")?.asText())
                        val type   = item.get("type")?.asText() ?: ""
                        val href   = fixUrl(slug)
                        if (!href.contains("/seri-filmler/")) {
                            if (type == "Movies" || href.contains("/film/")) {
                                results.add(newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster })
                            } else {
                                results.add(newTvSeriesSearchResponse(title, href.substringBefore("/sezon"), TvType.TvSeries) { posterUrl = poster })
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        if (results.isEmpty()) {
            try {
                val doc = app.get("$mainUrl/arama?q=$query", interceptor = interceptor).document
                doc.select("a[href*=/film/], a[href*=/dizi/]").forEach { el ->
                    val href  = fixUrlNull(el.attr("href")) ?: return@forEach
                    if (href == "$mainUrl/film-izle" || href == "$mainUrl/dizi-izle") return@forEach
                    val img   = el.selectFirst("img")
                    val title = el.selectFirst("h2,h3")?.text()
                        ?: img?.attr("alt")?.replace(" izle", "")?.trim()
                        ?: return@forEach
                    val poster = fixPosterUrl(img?.attr("data-src")?.takeIf { it.isNotBlank() } ?: img?.attr("src"))
                    if (href.contains("/dizi/")) {
                        results.add(newTvSeriesSearchResponse(title, href.substringBefore("/sezon"), TvType.TvSeries) { posterUrl = poster })
                    } else {
                        results.add(newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster })
                    }
                }
            } catch (_: Exception) {}
        }

        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val html     = app.get(url, interceptor = interceptor).text
        val isSeries = url.contains("/dizi/")

        var title       = Jsoup.parse(html).selectFirst("h1")?.text() ?: ""
        var poster      : String? = null
        var bgPoster    : String? = null
        var description : String? = null
        var year        : Int?    = null
        var tags        : List<String>? = null
        val episodes    = mutableListOf<Episode>()

        val secureDataRaw = extractSecureData(html)
        if (secureDataRaw != null) {
            val jsonText = decodeSecureData(secureDataRaw)
            if (jsonText != null) {
                try {
                    val json: JsonNode = jacksonMapper.readTree(jsonText)

                    val item: JsonNode? = json.get("contentItem")
                    if (item != null) {
                        val origTitle = item.get("original_title")?.asText()
                        if (!origTitle.isNullOrBlank() && origTitle != "null" && title.isBlank()) title = origTitle

                        val pUrl = item.get("poster_url")?.asText()
                        if (!pUrl.isNullOrBlank() && pUrl != "null") poster = pUrl

                        val bUrl = item.get("back_url")?.asText()
                        if (!bUrl.isNullOrBlank() && bUrl != "null") bgPoster = bUrl

                        val desc = item.get("description")?.asText()
                        if (!desc.isNullOrBlank() && desc != "null") {
                            description = desc.replace("\\n", "\n").replace("\\r", "").replace("\\", "")
                        }

                        val yearNode = item.get("release_year")
                        if (yearNode != null && !yearNode.isNull) year = yearNode.asInt().takeIf { it > 0 }

                        val cats = item.get("categories")?.asText()
                        if (!cats.isNullOrBlank() && cats != "null") {
                            tags = cats.split(",").map { it.trim() }.filter { it.isNotBlank() }
                        }
                    }

                    if (isSeries) {
                        val seasons: JsonNode? = json.get("RelatedResults")
                            ?.get("getSerieSeasonAndEpisodes")
                            ?.get("result")
                        seasons?.forEach { season: JsonNode ->
                            val sNum = season.get("season_no")?.asInt() ?: return@forEach
                            season.get("episodes")?.forEach { ep: JsonNode ->
                                val eNum   = ep.get("episode_no")?.asInt() ?: return@forEach
                                val epText = ep.get("episode_text")?.asText()?.takeIf { it.isNotBlank() } ?: "Bölüm $eNum"
                                val epSlug = ep.get("used_slug")?.asText() ?: return@forEach
                                val epUrl  = fixUrl(epSlug)
                                episodes.add(newEpisode(epUrl) {
                                    this.name    = epText
                                    this.season  = sNum
                                    this.episode = eNum
                                })
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        poster   = fixPosterUrl(poster)
        bgPoster = fixPosterUrl(bgPoster)

        if (isSeries && episodes.isEmpty()) {
            Jsoup.parse(html).select("a[href*=/sezon]").forEach { link ->
                val epUrl  = fixUrlNull(link.attr("href")) ?: return@forEach
                val epTxt  = link.text().takeIf { it.isNotEmpty() }
                    ?: link.selectFirst("h2,h3,span")?.text() ?: "Bölüm"
                val sMatch = Regex("/sezon-([0-9]+)").find(epUrl)
                val eMatch = Regex("/bolum-([0-9]+)").find(epUrl)
                val sNum   = sMatch?.groupValues?.get(1)?.toIntOrNull() ?: return@forEach
                val eNum   = eMatch?.groupValues?.get(1)?.toIntOrNull() ?: return@forEach
                episodes.add(newEpisode(epUrl) {
                    this.name    = epTxt
                    this.season  = sNum
                    this.episode = eNum
                })
            }
        }

        return if (isSeries) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinctBy { it.data }) {
                posterUrl           = poster
                backgroundPosterUrl = bgPoster ?: poster
                plot                = description
                this.year           = year
                this.tags           = tags
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl           = poster
                backgroundPosterUrl = bgPoster ?: poster
                plot                = description
                this.year           = year
                this.tags           = tags
            }
        }
    }

    override suspend fun loadLinks(
        data             : String,
        isCasting        : Boolean,
        subtitleCallback : (SubtitleFile) -> Unit,
        callback         : (ExtractorLink) -> Unit
    ): Boolean {
        val html = app.get(data, interceptor = interceptor).text

        val secureDataRaw = extractSecureData(html) ?: return false
        val jsonText      = decodeSecureData(secureDataRaw) ?: return false
        val json: JsonNode = try { jacksonMapper.readTree(jsonText) } catch (_: Exception) { return false }
        val related: JsonNode = json.get("RelatedResults") ?: return false

        val sourceContent: String? = if (data.contains("/dizi/") || data.contains("/bolum-")) {
            related.get("getEpisodeSources")
                ?.get("result")
                ?.get(0)
                ?.get("source_content")
                ?.asText()
        } else {
            var content: String? = null

            val firstPartId = related.get("getMoviePartsById")
                ?.get("result")?.get(0)?.get("id")?.asInt()

            if (firstPartId != null) {
                content = related.get("getMoviePartSourcesById_$firstPartId")
                    ?.get("result")?.get(0)?.get("source_content")?.asText()
            }
            if (content.isNullOrBlank()) {
                content = related.get("getMoviePartSourcesById")
                    ?.get("result")?.get(0)?.get("source_content")?.asText()
            }
            content
        }

        if (sourceContent.isNullOrBlank()) return false

        val iframeEl  = Jsoup.parse(sourceContent).selectFirst("iframe")
        val iframeUrl = iframeEl?.attr("src") ?: return false
        var finalUrl  = fixUrlNull(iframeUrl) ?: return false

        finalUrl = finalUrl
            .replace(Regex("sn\\.dplayer\\d*\\.site"), "sn.hotlinger.com")
            .replace(Regex("(four\\.|v\\.)?pichive\\.online"), "sn.hotlinger.com")

        loadExtractor(finalUrl, data, subtitleCallback, callback)
        return true
    }
}
