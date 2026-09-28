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
    override var mainUrl = "https://dizipal1584.com"
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

            if (doc.html().contains("Just a moment")) {
                return cloudflareKiller.intercept(chain)
            }

            return response
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/yabanci-dizi-izle"        to "Yeni Diziler",
        "$mainUrl/hd-film-izle"             to "Yeni Filmler",
        "$mainUrl/kanal/netflix"            to "Netflix",
        "$mainUrl/kanal/exxen"              to "Exxen",
        "$mainUrl/kanal/max"                to "Max",
        "$mainUrl/kanal/disney"             to "Disney+",
        "$mainUrl/kanal/amazon"             to "Amazon Prime",
        "$mainUrl/kanal/tod"                to "TOD (beIN)",
        "$mainUrl/kanal/tabii"              to "Tabii",
        "$mainUrl/kanal/hulu"               to "Hulu",
        "$mainUrl/anime"                    to "Anime"
    )

    private val cardSelector = "a[data-dizipal-pageloader], a[data-dizipalx-pageloader], a[href*='/series/'], a[href*='/movies/'], a[href*='/dizi/'], a[href*='/film/'], article.dp-card, article, div.dp-card, div.bg-\\[\\#22232a\\], div.poster, div.movie-item, div.serie-item, div.content-item, div.card"

    private fun Element.diziler(): SearchResponse? {
        val aTag = if (this.tagName() == "a") this else this.selectFirst("a[href]") ?: return null
        val href = fixUrlNull(aTag.attr("href")) ?: return null

        if (href.isBlank()
            || href.endsWith("/kanal/")
            || href.endsWith("/yabanci-dizi-izle")
            || href.endsWith("/hd-film-izle")
            || href.endsWith("/anime")
            || href.contains("/tur/")
            || href.contains("/kategori/")
            || href == mainUrl
            || href == "$mainUrl/"
            || href.contains("javascript:")
        ) return null

        val imgEl = this.selectFirst("img") ?: aTag.selectFirst("img")

        val rawTitle = (
            this.selectFirst("h2, h3, h4, h5, .title, .name, .content-title, .dp-title, .card-title, div.font-semibold, div.truncate, div.line-clamp-1, div.line-clamp-2, span.title, span.name")?.text()
            ?: aTag.selectFirst("h2, h3, h4, h5, .title, .name, .content-title, .dp-title, .card-title, div.font-semibold, div.truncate, div.line-clamp-1, div.line-clamp-2, span.title, span.name")?.text()
            ?: imgEl?.attr("alt")
            ?: imgEl?.attr("title")
            ?: aTag.attr("title")
            ?: this.attr("title")
        ).toString().trim()

        val title = rawTitle.removeSuffix(" izle").removeSuffix(" İzle").removeSuffix(" izle -").removeSuffix(" İzle -").trim()
        if (title.isBlank()) return null

        val posterUrl = fixUrlNull(
            imgEl?.attr("data-src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
                ?: imgEl?.attr("data-original")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
                ?: imgEl?.attr("data-lazy-src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
                ?: imgEl?.attr("data-bg")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
                ?: imgEl?.attr("srcset")?.split(",")?.firstOrNull()?.trim()?.split(" ")?.firstOrNull()?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
                ?: imgEl?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
                ?: this.selectFirst("[style*='background-image']")?.attr("style")?.let { style ->
                    Regex("""url\((['"]?)(.*?)\1\)""").find(style)?.groupValues?.get(2)
                }
                ?: aTag.selectFirst("[style*='background-image']")?.attr("style")?.let { style ->
                    Regex("""url\((['"]?)(.*?)\1\)""").find(style)?.groupValues?.get(2)
                }
        )

        val scoreRaw = (
            this.selectFirst(".imdb, .rating, .score, .point, .dp-rating, .dp-imdb, span[class*='imdb'], div[class*='imdb'], span[class*='rating'], div[class*='rating'], span[class*='score'], div[class*='score']")?.text()
            ?: aTag.selectFirst(".imdb, .rating, .score, .point, .dp-rating, .dp-imdb, span[class*='imdb'], div[class*='imdb'], span[class*='rating'], div[class*='rating'], span[class*='score'], div[class*='score']")?.text()
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
            val url = if (page > 1) {
                if (request.data.contains("?")) "${request.data}&sayfa=$page" else "${request.data}?sayfa=$page"
            } else {
                request.data
            }

            val document = app.get(
                url, timeout = 10000, interceptor = interceptor, headers = getHeaders(mainUrl)
            ).document

            val cardElements = document.select(cardSelector)
            home.addAll(cardElements.mapNotNull { it.diziler() })

            if (request.data.contains("/kanal/")) {
                val channelIdFromDoc = document.selectFirst("input[name=channelId]")?.attr("value")
                    ?: Regex("""channelId\s*[:=]\s*(\d+)""").find(document.html())?.groupValues?.get(1)
                val channelSlug = request.data.substringAfterLast("/")

                try {
                    val apiResponse = app.post(
                        "${mainUrl}/bg/getserielistbychannel",
                        headers = mapOf(
                            "Accept" to "application/json, text/javascript, */*; q=0.01",
                            "X-Requested-With" to "XMLHttpRequest"
                        ),
                        referer = request.data,
                        data = mapOf(
                            "cKey"       to "c61f91c5141d178450934fe81c0a2029",
                            "cValue"     to "MTc4NDQwNzIwMDhkMzJhNTc1YzUwOGU1ZjQwMjdjMjIyOWVjOGVhMTcwNGQyM2FjODM2YTI4YTU0NjUyMjI2ZmVjMzFkYzBkMWQyMWY4YzdiNA==",
                            "curPage"    to page.toString(),
                            "channelId"  to (channelIdFromDoc ?: "1"),
                            "languageId" to "2,3,4",
                            "slug"       to channelSlug
                        )
                    )

                    val mapper = jacksonObjectMapper()
                    val rootNode = mapper.readTree(apiResponse.text)

                    val htmlNode = rootNode.at("/data/html")
                    if (!htmlNode.isMissingNode) {
                        val htmlContent = htmlNode.asText()
                        val parsedDoc = Jsoup.parse(htmlContent)
                        val apiResults = parsedDoc.select(cardSelector).mapNotNull { it.diziler() }

                        apiResults.forEach { res ->
                            if (home.none { it.url == res.url }) {
                                home.add(res)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("DiziPalOriginal", "API Hatası: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e("DiziPalOriginal", "getMainPage Hatası: ${e.message}")
        }

        val items = home.distinctBy { it.url }
        return newHomePageResponse(request.name, items, items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val responseRaw = app.post(
                "${mainUrl}/bg/searchcontent",
                headers = mapOf(
                    "Accept" to "application/json, text/javascript, */*; q=0.01",
                    "X-Requested-With" to "XMLHttpRequest"
                ),
                referer = "${mainUrl}/",
                data = mapOf(
                    "cKey" to "c61f91c5141d178450934fe81c0a2029",
                    "cValue" to "MTc4NDQwNzIwMDhkMzJhNTc1YzUwOGU1ZjQwMjdjMjIyOWVjOGVhMTcwNGQyM2FjODM2YTI4YTU0NjUyMjI2ZmVjMzFkYzBkMWQyMWY4YzdiNA==",
                    "type" to "hepsi",
                    "searchterm" to query
                )
            )

            val mapper = jacksonObjectMapper()
            val rootNode = mapper.readTree(responseRaw.text)
            val resultArrayNode = rootNode.at("/data/result")

            if (resultArrayNode.isMissingNode || !resultArrayNode.isArray) {
                return emptyList()
            }

            val searchItems: List<DizipalSearchResult> = mapper.readValue(resultArrayNode.traverse())
            searchItems.mapNotNull { item ->
                val title = item.title?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val slugStr = item.slug?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val href = if (slugStr.startsWith("http")) slugStr else "$mainUrl/$slugStr"
                val posterUrl = fixUrlNull(item.poster?.takeIf { it.isNotBlank() && !it.startsWith("data:") })
                val imdbScore = item.imdb?.toString()?.trim()?.takeIf { it.isNotBlank() }

                if (item.type.equals("series", ignoreCase = true) || href.contains("/series/") || href.contains("/dizi/")) {
                    newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                        this.posterUrl = posterUrl
                        this.score     = Score.from10(imdbScore)
                    }
                } else {
                    newMovieSearchResponse(title, href, TvType.Movie) {
                        this.posterUrl = posterUrl
                        this.score     = Score.from10(imdbScore)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("DiziPalOriginal", "Search hatası: ${e.message}")
            emptyList()
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(
            url, timeout = 10000, interceptor = interceptor, headers = getHeaders(mainUrl)
        ).document

        val title = document.selectFirst("div.flex h2, h1")?.text()?.substringBefore(" İzle")?.substringBefore(" izle")?.trim()
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")?.substringBefore(" İzle")?.substringBefore(" izle")?.trim()
            ?: return null

        val poster = fixUrlNull(
            document.selectFirst("div.page-top img[alt]")?.attr("src")?.takeIf { !it.startsWith("data:") }
                ?: document.selectFirst("img[src*='/poster/'], img[data-src*='/poster/']")?.attr("data-src")?.takeIf { !it.startsWith("data:") }
                ?: document.selectFirst("img[src*='/poster/']")?.attr("src")?.takeIf { !it.startsWith("data:") }
                ?: document.selectFirst("meta[property='og:image']")?.attr("content")
        )

        val year = document.selectXpath("//div[text()='Yıl']//following-sibling::div").text().trim().toIntOrNull()
            ?: Regex("""\((\d{4})\)""").find(document.title())?.groupValues?.get(1)?.toIntOrNull()
        val description = document.selectFirst("div.summary p, meta[name='description']")?.text()?.trim()
            ?: document.selectFirst("meta[name='description']")?.attr("content")
        val tags = document.selectXpath("//div[text()='Kategoriler']//following-sibling::div").text().trim().split(" ").map { it.trim() }.filter { it.isNotEmpty() }
            .ifEmpty { document.select("a[href*='/tur/'], a[href*='/kategori/']").map { it.text().trim() } }
        val duration = Regex("(\\d+)").find(document.selectXpath("//div[text()='Süre']//following-sibling::div").text())?.value?.toIntOrNull()

        val scoreRaw = document.selectFirst(".imdb, .rating, .dp-rating, span[class*='imdb'], div[class*='imdb']")?.text()?.trim()
        val ratingNum = scoreRaw?.let { Regex("""(\d+(?:[.,]\d+)?)""").find(it)?.groupValues?.get(1)?.replace(",", ".") }

        if (url.contains("/movies/") || url.contains("/film/")) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year      = year
                this.plot      = description
                this.tags      = tags
                this.duration  = duration
                this.score     = Score.from10(ratingNum)
            }
        }

        val episodeElements = document.select("div.relative.w-full.flex.items-start.gap-4, a.dp-detail-episode, a[href*='/episode/'], a[href*='/bolum/']")
        val episodes = episodeElements.mapNotNull { element ->
            val linkElement = if (element.tagName() == "a") element else element.selectFirst("a[data-dizipal-pageloader], a[href]") ?: return@mapNotNull null
            val epHref = fixUrlNull(linkElement.attr("href")) ?: return@mapNotNull null
            val epName = linkElement.selectFirst("h2, small, strong")?.text()?.trim() ?: "Bölüm"

            val infoText = linkElement.selectFirst("div.text-white.text-sm.opacity-80")?.text()?.trim() ?: element.text().trim()

            val epSeason = Regex("""(\d+)\.\s*Sezon""").find(infoText)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""(\d+)-sezon""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""(\d+)x\d+""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
            val epEpisode = Regex("""(\d+)\.\s*Bölüm""").find(infoText)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""(\d+)-bolum""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""\d+x(\d+)""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()

            newEpisode(epHref) {
                this.name    = epName
                this.episode = epEpisode
                this.season  = epSeason
            }
        }.distinctBy { it.data }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.year      = year
            this.plot      = description
            this.tags      = tags
            this.duration  = duration
            this.score     = Score.from10(ratingNum)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("DiziPalOriginal", "--> loadLinks ÇAĞRILDI. Gelen URL: $data")
        val doc = app.get(
            data, timeout = 10000, interceptor = interceptor, headers = getHeaders(mainUrl)
        ).document

        val encryptedText = doc.selectFirst("div[data-rm-k=true]")?.text() ?: ""
        Log.d("DiziPalOriginal", "--> Şifreli metin uzunluğu: ${encryptedText.length}")

        var iframeUrl = if (encryptedText.isNotEmpty()) {
            Log.d("DiziPalOriginal", "--> Şifreli veri bulundu, decrypt işlemine geçiliyor...")
            decryptDizipalData(encryptedText)
        } else {
            Log.w("DiziPalOriginal", "--> DİKKAT: Şifreli veri DOM'da YOK! Fallback iframe aranıyor...")
            doc.selectFirst("iframe")?.attr("src") ?: ""
        }

        Log.d("DiziPalOriginal", "--> Elde edilen Ham Iframe URL: $iframeUrl")

        if (iframeUrl.isNotEmpty()) {
            if (iframeUrl.startsWith("//")) {
                iframeUrl = "https:$iframeUrl"
            }
            Log.d("DiziPalOriginal", "--> Extractor'a gönderilen Final URL: $iframeUrl")

            DizipalOriginalPlayer().getUrl(
                url = iframeUrl,
                referer = data,
                subtitleCallback = subtitleCallback,
                callback = callback
            )
        } else {
            Log.e("DiziPalOriginal", "--> HATA: iframeUrl tamamen BOŞ. Video linki bulunamadı!")
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

            Log.d("DiziPalOriginal", "--> Regex başarılı. Key türetiliyor...")

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

            Log.d("DiziPalOriginal", "--> AES Çözümleme Başarılı. İlk Çıktı: $finalUrl")

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
