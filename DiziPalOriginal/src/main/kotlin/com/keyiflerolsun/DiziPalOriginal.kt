package com.keyiflerolsun

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
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
    override var mainUrl = "https://dizipal2223.com"
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
        "$mainUrl/yeni-eklenen-bolumler"        to "Son Bölümler",
        "$mainUrl/diziler"                      to "Diziler",
        "$mainUrl/filmler"                      to "Filmler",
        "$mainUrl/anime"                        to "Anime",
        "$mainUrl/altyazili-filmler"            to "Altyazılı Filmler",
        "$mainUrl/turkce-dublaj-filmler"        to "Türkçe Dublaj Filmler",
        "$mainUrl/diziler/kategori/aksiyon"     to "Aksiyon",
        "$mainUrl/diziler/kategori/bilim-kurgu" to "Bilim Kurgu",
        "$mainUrl/diziler/kategori/komedi"     to "Komedi",
        "$mainUrl/diziler/kategori/dram"       to "Dram",
        "$mainUrl/diziler/kategori/fantastik"  to "Fantastik",
        "$mainUrl/diziler/kategori/gerilim"    to "Gerilim",
        "$mainUrl/diziler/kategori/gizem"      to "Gizem",
        "$mainUrl/diziler/kategori/korku"      to "Korku",
        "$mainUrl/diziler/kategori/macera"     to "Macera",
        "$mainUrl/diziler/kategori/romantik"   to "Romantik"
    )

    private fun Element.parseEpisodeItem(): SearchResponse? {
        val href = fixUrlNull(this.attr("href")) ?: return null
        val posterUrl = fixUrlNull(
            this.selectFirst("img")?.attr("data-src")?.takeIf { it.isNotEmpty() }
                ?: this.selectFirst("img")?.attr("src")
        )
        val name = this.selectFirst("strong")?.text()?.trim()
            ?: this.selectFirst("img")?.attr("alt")?.trim() ?: return null
        val episode = this.selectFirst("span.dp-episode-copy span, span")?.text()?.trim() ?: ""
        val title = if (episode.isNotEmpty() && episode != name) "$name $episode" else name

        val seriesHref = href.substringBefore("/sezon").substringBefore("/bolum")

        return newTvSeriesSearchResponse(title, seriesHref, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    private fun Element.parseCardItem(): SearchResponse? {
        val title = (this.selectFirst("h3 a, h2 a, h3, h2")?.text()
            ?: this.selectFirst("img")?.attr("alt"))?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        val href = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(
            this.selectFirst("img")?.attr("data-src")?.takeIf { it.isNotEmpty() }
                ?: this.selectFirst("img")?.attr("src")
        )

        val isMovie = href.contains("/film/") || href.contains("/movies/")
        return if (isMovie) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) {
            if (request.data.contains("?")) "${request.data}&page=$page" else "${request.data}?page=$page"
        } else {
            request.data
        }

        val document = app.get(
            url, timeout = 10000, interceptor = interceptor, headers = getHeaders(mainUrl)
        ).document

        val items = if (request.data.contains("/yeni-eklenen-bolumler")) {
            document.select("a.dp-episode").mapNotNull { it.parseEpisodeItem() }
        } else {
            document.select("article.dp-card, div.dp-card, article, div.bg-\\[\\#22232a\\]").mapNotNull { it.parseCardItem() }
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, items, items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get(
            "$mainUrl/diziler?kelime=$query",
            timeout = 10000,
            interceptor = interceptor,
            headers = getHeaders(mainUrl)
        ).document

        return document.select("article.dp-card, div.dp-card, article")
            .mapNotNull { it.parseCardItem() }
            .distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(
            url, timeout = 10000, interceptor = interceptor, headers = getHeaders(mainUrl)
        ).document

        val title = document.selectFirst("h1")?.text()?.substringBefore(" İzle")?.substringBefore(" izle")?.trim()
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")?.substringBefore(" İzle")?.substringBefore(" izle")?.trim()
            ?: return null

        val poster = fixUrlNull(
            document.selectFirst("img[src*='/poster/'], img[data-src*='/poster/']")?.attr("data-src")?.takeIf { it.isNotEmpty() }
                ?: document.selectFirst("img[src*='/poster/'], img[data-src*='/poster/']")?.attr("src")
                ?: document.selectFirst("meta[property='og:image']")?.attr("content")
                ?: document.selectFirst("div.page-top img[alt]")?.attr("src")
        )

        val year = document.selectXpath("//div[text()='Yıl']//following-sibling::div").text().trim().toIntOrNull()
            ?: Regex("""\((\d{4})\)""").find(document.title())?.groupValues?.get(1)?.toIntOrNull()
        val description = document.selectFirst("div.summary p, p.description, meta[name='description']")?.attr("content")?.takeIf { it.isNotEmpty() }
            ?: document.selectFirst("div.summary p")?.text()?.trim()
        val tags = document.select("a[href*='/kategori/'], a[href*='/tur/']").map { it.text().trim() }.filter { it.isNotEmpty() }.distinct()
        val duration = Regex("(\\d+)").find(document.selectXpath("//div[text()='Süre']//following-sibling::div").text())?.value?.toIntOrNull()

        if (url.contains("/film/")) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year      = year
                this.plot      = description
                this.tags      = tags
                this.duration  = duration
            }
        }

        val episodes = document.select("a.dp-detail-episode, a[href*='/sezon/']").mapNotNull { element ->
            val href = fixUrlNull(element.attr("href")) ?: return@mapNotNull null
            val match = Regex("""(\d+)-sezon/(\d+)-bolum""").find(href)
                ?: Regex("""(\d+)\.\s*Sezon\s*(\d+)\.\s*Bölüm""", RegexOption.IGNORE_CASE).find(element.text())

            val season = match?.groupValues?.get(1)?.toIntOrNull()
            val episode = match?.groupValues?.get(2)?.toIntOrNull()

            val epName = element.selectFirst("small")?.text()?.trim()
                ?: element.selectFirst("strong")?.text()?.trim()
                ?: element.text().trim()

            newEpisode(href) {
                this.name    = epName
                this.season  = season
                this.episode = episode
            }
        }.distinctBy { it.data }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.year      = year
            this.plot      = description
            this.tags      = tags
            this.duration  = duration
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
