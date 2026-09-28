// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.network.CloudflareKiller
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup

class DiziMom : MainAPI() {
    override var mainUrl              = "https://www.dizimom.help"
    override var name                 = "DiziMom"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.TvSeries)

    override var sequentialMainPage = true        // * https://recloudstream.github.io/dokka/-cloudstream/com.lagradost.cloudstream3/-main-a-p-i/index.html#-2049735995%2FProperties%2F101969414
    override var sequentialMainPageDelay       = 50L  // ? 0.05 saniye
    override var sequentialMainPageScrollDelay = 50L  // ? 0.05 saniye

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
        "${mainUrl}/tum-bolumler/page/"        to "Son Bölümler",
        "${mainUrl}/yerli-dizi-izle/page/"     to "Yerli Diziler",
        "${mainUrl}/yabanci-dizi-izle/page/"   to "Yabancı Diziler",
        "${mainUrl}/tv-programlari-izle/page/" to "TV Programları",
        "${mainUrl}/netflix-dizileri-izle/page/"      to "Netflix Dizileri",
        // "${mainUrl}/turkce-dublaj-diziler/page/"      to "Dublajlı Diziler",   // ! "Son Bölümler" Ana sayfa yüklenmesini yavaşlattığı için bunlar devre dışı bırakılmıştır..
        // "${mainUrl}/kore-dizileri-izle/page/"         to "Kore Dizileri",
        // "${mainUrl}/full-hd-hint-dizileri-izle/page/" to "Hint Dizileri",
    )

    private fun Element.posterUrl(): String? {
        val img = this.selectFirst("img") ?: return null
        val url = img.attr("data-src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?: img.attr("data-lazy-src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?: img.attr("data-original").takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?: img.attr("data-srcset").split(",").firstOrNull()?.trim()?.split(" ")?.firstOrNull()?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?: img.attr("srcset").split(",").firstOrNull()?.trim()?.split(" ")?.firstOrNull()?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?: img.attr("src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
        return fixUrlNull(url)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}${page}/", interceptor = interceptor).document
        val home     = if (request.data.contains("/tum-bolumler/")) {
            document.select("div.episode-box, div.single-item, article, div.post-item").mapNotNull { it.sonBolumler() } 
        } else {
            document.select("div.single-item, div.cat-item, div.episode-box, article, div.post-item").mapNotNull { it.diziler() }
        }

        return newHomePageResponse(request.name, home)
    }

    private suspend fun Element.sonBolumler(): SearchResponse? {
        val titleEl = this.selectFirst("div.episode-name a, div.title a, h2 a, h3 a, a")
        val rawName = titleEl?.text()?.substringBefore(" izle")?.trim() ?: return null
        val title   = rawName.replace(Regex("""\s*\d+\.\s*Sezon\s*\d+\.\s*Bölüm""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*\d+x\d+"""), "")
            .trim()
            .ifBlank { rawName }

        val epHref    = fixUrlNull(titleEl.attr("href")) ?: return null
        val posterUrl = this.posterUrl() ?: this.selectFirst("a")?.posterUrl()

        val href = try {
            val epDoc = app.get(epHref, interceptor = interceptor).document
            fixUrlNull(epDoc.selectFirst("div#benzerli a, div.benzerli a, a.series-link, div.series-title a")?.attr("href")) ?: epHref
        } catch (_: Exception) {
            epHref
        }

        val ratingStr = this.selectFirst("span.imdb, div.imdb, span.puan, div.puan, span.score, .label-imdb")?.text()?.trim()
            ?: Regex("""(?i)(?:imdb|puan)\s*:?\s*([0-9]+(?:[.,][0-9]+)?)""").find(this.text())?.groupValues?.get(1)

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
            this.score     = Score.from10(ratingStr)
        }
    }

    private fun Element.diziler(): SearchResponse? {
        val titleEl   = this.selectFirst("div.categorytitle a, div.title a, h2 a, h3 a, a.title")
        val title     = titleEl?.text()?.substringBefore(" izle")?.trim()
            ?.ifBlank { null }
            ?: this.selectFirst("img")?.attr("alt")?.substringBefore(" izle")?.trim()
            ?.ifBlank { null }
            ?: return null

        val href      = fixUrlNull(
            titleEl?.attr("href")
                ?: this.selectFirst("div.cat-img a, a")?.attr("href")
        ) ?: return null

        val posterUrl = this.posterUrl()
            ?: this.selectFirst("div.cat-img, div.poster, a")?.posterUrl()

        val ratingStr = this.selectFirst("span.imdb, div.imdb, span.score, div.puan, span.puan, span.rating, .label-imdb, .cat-rating")?.text()?.trim()
            ?: Regex("""(?i)(?:imdb|puan)\s*:?\s*([0-9]+(?:[.,][0-9]+)?)""").find(this.text())?.groupValues?.get(1)

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
            this.score     = Score.from10(ratingStr)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/?s=${query}", interceptor = interceptor).document

        return document.select("div.single-item, div.cat-item, div.episode-box, article, div.post-item").mapNotNull { it.diziler() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, interceptor = interceptor).document

        val title       = document.selectFirst("div.title h1, h1.entry-title, h1.title, h1")?.text()?.substringBefore(" izle")?.trim() ?: return null
        val poster      = document.selectFirst("div.category_image, div.poster, div.cat-img, div.featured-image")?.posterUrl()
            ?: document.posterUrl()
        val year        = document.selectXpath("//div[span[contains(text(), 'Yapım Yılı')]]").text().substringAfter("Yapım Yılı : ").trim().toIntOrNull()
            ?: Regex("""\b(19\d\d|20\d\d)\b""").find(document.text())?.value?.toIntOrNull()
        val ratingStr   = document.selectFirst("span.imdb_score, div.imdb_score, span.imdb, div.imdb, span.puan, div.puan, .rating-score, .score")?.text()?.trim()
            ?: Regex("""(?i)(?:imdb|puan)\s*:?\s*([0-9]+(?:[.,][0-9]+)?)""").find(document.text())?.groupValues?.get(1)
        val description = document.selectFirst("div.category_desc, div.cat_desc, div.entry-content, div.konu, div.summary, div.description, p.description")?.text()?.trim()
        val tags        = document.select("div.genres a, div.categories a, div.tags a, a[href*='/kategori/']").mapNotNull { it.text().trim() }.filter { it.isNotBlank() }

        val actorElements = document.select("div.actor-item, div.cast-item, div.oyuncu, div.cast_member, ul.cast li, .owl-stage div.item, div.oyuncular a, .cast a, .actors a")
        val actors = if (actorElements.isNotEmpty()) {
            actorElements.mapNotNull { el ->
                val actorName = el.selectFirst("span.name, div.name, h3, a, .actor-name")?.text()?.trim()
                    ?.ifBlank { null }
                    ?: el.text().trim().takeIf { it.isNotBlank() }
                if (actorName == null) return@mapNotNull null
                val actorImage = el.posterUrl()
                Actor(actorName, actorImage)
            }
        } else {
            val actorText = document.selectXpath("//div[span[contains(text(), 'Oyuncular')]]").text().substringAfter("Oyuncular : ").trim()
                .ifBlank { document.selectFirst("div.oyuncular, div.cast, .actors")?.text()?.substringAfter("Oyuncular:")?.trim() ?: "" }
            actorText.split(",").mapNotNull { name ->
                val trimmed = name.trim()
                if (trimmed.isNotBlank()) Actor(trimmed, null) else null
            }
        }

        val episodes    = document.select("div.bolumust, div.episode-item, li.bolum, div.episode-list a").mapNotNull {
            val epName    = it.selectFirst("div.baslik, span.title, a")?.text()?.trim() ?: return@mapNotNull null
            val epHref    = fixUrlNull(it.selectFirst("a")?.attr("href") ?: it.attr("href")) ?: return@mapNotNull null
            val epEpisode = Regex("""(\d+)\.\s*Bölüm""", RegexOption.IGNORE_CASE).find(epName)?.groupValues?.get(1)?.toIntOrNull()
            val epSeason  = Regex("""(\d+)\.\s*Sezon""", RegexOption.IGNORE_CASE).find(epName)?.groupValues?.get(1)?.toIntOrNull() ?: 1

            newEpisode(epHref) {
                this.name    = epName.substringBefore(" izle").replace(title, "", ignoreCase = true).trim()
                this.season  = epSeason
                this.episode = epEpisode
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.year      = year
            this.plot      = description
            this.tags      = tags
            this.score     = Score.from10(ratingStr)
            addActors(actors)
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("DZM", "data » $data")

        val ua = mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36")

        app.post(
            "${mainUrl}/wp-login.php",
            headers = ua,
            referer = "${mainUrl}/",
            data    = mapOf(
                "log"         to "keyiflerolsun",
                "pwd"         to "12345",
                "rememberme"  to "forever",
                "redirect_to" to mainUrl,
            )
        )

        val document = app.get(data, headers=ua, interceptor = interceptor).document

        val iframes     = mutableListOf<String>()
        val mainIframe = document.selectFirst("div.video p iframe")?.attr("src") ?: return false
        iframes.add(mainIframe)

        document.select("div.sources a").forEach {
            val subDocument = app.get(it.attr("href"), headers=ua, interceptor = interceptor).document
            val subIframe   = subDocument.selectFirst("div.video p iframe")?.attr("src") ?: return@forEach

            iframes.add(subIframe)
        }

        for (iframe in iframes) {
            Log.d("DZM", "iframe » $iframe")
            loadExtractor(iframe, "${mainUrl}/", subtitleCallback, callback)
        }

        return true
    }
}
