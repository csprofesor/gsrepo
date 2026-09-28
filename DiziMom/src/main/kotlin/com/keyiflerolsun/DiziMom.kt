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

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request  = chain.request()
            val response = chain.proceed(request)
            val body     = response.peekBody(1024 * 1024).string()
            val doc      = Jsoup.parse(body)

            if (response.code == 403 || response.code == 503 ||
                response.header("cf-mitigated") != null ||
                body.contains("Just a moment", ignoreCase = true) ||
                body.contains("Checking your browser", ignoreCase = true) ||
                body.contains("cf-challenge", ignoreCase = true) ||
                body.contains("turnstile", ignoreCase = true) ||
                doc.title().contains("Just a moment", ignoreCase = true) ||
                doc.title().contains("Attention Required", ignoreCase = true)
            ) {
                return cloudflareKiller.intercept(chain)
            }

            return response
        }
    }
    
    override val mainPage = mainPageOf(
        "${mainUrl}/tum-bolumler"        to "Son Bölümler",
        "${mainUrl}/yerli-dizi-izle"     to "Yerli Diziler",
        "${mainUrl}/yabanci-dizi-izle"   to "Yabancı Diziler",
        "${mainUrl}/tv-programlari-izle" to "TV Programları",
        "${mainUrl}/netflix-dizileri-izle"      to "Netflix Dizileri",
        "${mainUrl}/kore-dizileri-izle"         to "Kore Dizileri",
    )

    private fun Element.posterUrl(): String? {
        val img = this.selectFirst("img") ?: if (this.tagName() == "img") this else return null
        val url = img.attr("data-src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?: img.attr("data-lazy-src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?: img.attr("data-original").takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?: img.attr("data-srcset").split(",").firstOrNull()?.trim()?.split(" ")?.firstOrNull()?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?: img.attr("srcset").split(",").firstOrNull()?.trim()?.split(" ")?.firstOrNull()?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?: img.attr("src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
        return fixUrlNull(url)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            request.data.trimEnd('/') + "/"
        } else {
            "${request.data.trimEnd('/')}/page/$page/"
        }
        val document = app.get(url, interceptor = interceptor).document
        
        val items = document.select("div.items article, div.result-item article, div.single-item, div.episode-box, div.cat-item, div.dizi-box, article, div.post-item, div.box, div.poster, div.item, div.movie, div.card, div.flix-item, div.movie-box, div.movies-list-item, a.poster")
        val home = if (request.data.contains("/tum-bolumler")) {
            items.mapNotNull { it.sonBolumler() }.ifEmpty { items.mapNotNull { it.diziler() } }
        } else {
            items.mapNotNull { it.diziler() }
        }

        return newHomePageResponse(request.name, home)
    }

    private suspend fun Element.sonBolumler(): SearchResponse? {
        val titleEl = if (this.tagName() == "a") this else (this.selectFirst("div.episode-name a, div.title a, h2 a, h3 a, a") ?: this)
        val rawName = titleEl.text().substringBefore(" izle").trim()
            .ifBlank { titleEl.attr("title").substringBefore(" izle").trim() }
            .ifBlank { this.attr("title").substringBefore(" izle").trim() }
            .ifBlank { return null }

        val title   = rawName.replace(Regex("""\s*\d+\.\s*Sezon\s*\d+\.\s*Bölüm""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*\d+x\d+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*\d+\.\s*Bölüm""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*\d+\.\s*Sezon""", RegexOption.IGNORE_CASE), "")
            .trim()
            .ifBlank { rawName }

        val epHref    = fixUrlNull(titleEl.attr("href").takeIf { it.isNotBlank() } ?: this.attr("href")) ?: return null
        val posterUrl = this.posterUrl() ?: titleEl.posterUrl()

        val href = try {
            val epDoc = app.get(epHref, interceptor = interceptor).document
            fixUrlNull(epDoc.selectFirst("div#benzerli a, div.benzerli a, a.series-link, div.series-title a, div.dizi-link a, .srelacionados a, .infox a")?.attr("href")) ?: epHref
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
        val titleEl = if (this.tagName() == "a") this else (this.selectFirst("div.categorytitle a, div.episode-name a, div.title a, h2 a, h3 a, a.title, header a, .entry-title a, a") ?: this)
        val title = titleEl.text().substringBefore(" izle").trim()
            .ifBlank { null }
            ?: titleEl.attr("title").substringBefore(" izle").trim()
            .ifBlank { null }
            ?: this.selectFirst("img")?.attr("alt")?.substringBefore(" izle")?.trim()
            ?.ifBlank { null }
            ?: this.attr("title").substringBefore(" izle").trim()
            .ifBlank { null }
            ?: return null

        val href = fixUrlNull(
            titleEl.attr("href").takeIf { it.isNotBlank() }
                ?: this.attr("href").takeIf { it.isNotBlank() }
                ?: this.selectFirst("a")?.attr("href")
        ) ?: return null

        val posterUrl = this.posterUrl() ?: titleEl.posterUrl()

        val ratingStr = this.selectFirst("span.imdb, div.imdb, span.score, div.puan, span.puan, span.rating, .label-imdb, .cat-rating")?.text()?.trim()
            ?: Regex("""(?i)(?:imdb|puan)\s*:?\s*([0-9]+(?:[.,][0-9]+)?)""").find(this.text())?.groupValues?.get(1)

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
            this.score     = Score.from10(ratingStr)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/?s=${query}", interceptor = interceptor).document

        return document.select("div.items article, div.result-item article, div.single-item, div.episode-box, div.cat-item, div.dizi-box, article, div.post-item, div.box, div.poster, div.item, div.movie, div.card, div.flix-item, div.movie-box, div.movies-list-item, a.poster").mapNotNull { it.diziler() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, interceptor = interceptor).document

        val title       = document.selectFirst("div.title h1, h1.entry-title, h1.title, h1, div.categorytitle")?.text()?.substringBefore(" izle")?.trim() ?: return null
        val poster      = document.selectFirst("div.category_image, div.poster, div.cat-img, div.featured-image, div.single-poster")?.posterUrl()
            ?: document.posterUrl()
        val year        = document.selectXpath("//div[span[contains(text(), 'Yapım Yılı')]]").text().substringAfter("Yapım Yılı : ").trim().toIntOrNull()
            ?: Regex("""\b(19\d\d|20\d\d)\b""").find(document.text())?.value?.toIntOrNull()
        val ratingStr   = document.selectFirst("span.imdb_score, div.imdb_score, span.imdb, div.imdb, span.puan, div.puan, .rating-score, .score")?.text()?.trim()
            ?: Regex("""(?i)(?:imdb|puan)\s*:?\s*([0-9]+(?:[.,][0-9]+)?)""").find(document.text())?.groupValues?.get(1)
        val description = document.selectFirst("div.category_desc, div.cat_desc, div.entry-content, div.konu, div.summary, div.description, p.description, .overview")?.text()?.trim()
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

        val episodes    = document.select("div.bolumust, div.episode-item, li.bolum, div.episode-list a, div.bolumler a, table.episodes tr").mapNotNull {
            val epName    = it.selectFirst("div.baslik, span.title, a, .episode-title")?.text()?.trim() ?: it.text().trim().takeIf { t -> t.isNotBlank() } ?: return@mapNotNull null
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

        try {
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
        } catch (_: Exception) {}

        val document = try {
            app.get(data, headers = ua, interceptor = interceptor).document
        } catch (e: Exception) {
            Log.d("DZM", "Error getting page: ${e.message}")
            return false
        }

        val iframes = mutableListOf<String>()
        document.select("div.video iframe, div.video p iframe, iframe").forEach { iframe ->
            val src = iframe.attr("data-src").takeIf { it.isNotBlank() && it != "about:blank" }
                ?: iframe.attr("src").takeIf { it.isNotBlank() && it != "about:blank" }
            if (src != null) {
                iframes.add(fixUrl(src))
            }
        }

        document.select("div.sources a, div.diziplus_sources a").forEach {
            val href = it.attr("href")
            if (href.isNotBlank() && href != "#") {
                try {
                    val subDocument = app.get(href, headers = ua, interceptor = interceptor).document
                    subDocument.select("div.video iframe, div.video p iframe, iframe").forEach { iframe ->
                        val subSrc = iframe.attr("data-src").takeIf { it.isNotBlank() && it != "about:blank" }
                            ?: iframe.attr("src").takeIf { it.isNotBlank() && it != "about:blank" }
                        if (subSrc != null) {
                            iframes.add(fixUrl(subSrc))
                        }
                    }
                } catch (e: Exception) {
                    Log.d("DZM", "Error in source: ${e.message}")
                }
            }
        }

        for (iframe in iframes.distinct()) {
            Log.d("DZM", "iframe » $iframe")
            loadExtractor(iframe, "${mainUrl}/", subtitleCallback, callback)
        }

        return iframes.isNotEmpty()
    }
}
