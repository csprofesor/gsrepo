package com.gsrepo

import android.util.Log
import org.json.JSONObject
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer

class TavsiyeFilmIzle : MainAPI() {
    override var mainUrl              = "https://tavsiyefilmizle.net"
    override var name                 = "TavsiyeFilmIzle"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries)

    companion object {
        private const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    override val mainPage = mainPageOf(
        "${mainUrl}/category/tavsiye-filmler"             to "Tavsiye Filmler",
        "${mainUrl}/category/trendler"                     to "Popüler Filmler",
        "${mainUrl}/category/en-kaliteli-filmler"         to "En Kaliteli Filmler",
        "${mainUrl}/category/mutlaka-izlenmesi-gerekenler" to "Mutlaka İzlenmesi Gerekenler",
        "${mainUrl}/category/aksiyon-filmleri"             to "Aksiyon Filmleri",
        "${mainUrl}/category/animasyon-filmleri"           to "Animasyon Filmleri",
        "${mainUrl}/category/bilim-kurgu-filmleri"        to "Bilim Kurgu Filmleri",
        "${mainUrl}/category/dram-filmleri-hd"             to "Dram Filmleri",
        "${mainUrl}/category/fantastik-filmler"           to "Fantastik Filmler",
        "${mainUrl}/category/gerilim-filmleri"             to "Gerilim Filmleri",
        "${mainUrl}/category/gizem-filmleri"              to "Gizem Filmleri",
        "${mainUrl}/category/komedi-filmleri"             to "Komedi Filmleri",
        "${mainUrl}/category/korku-filmleri"              to "Korku Filmleri",
        "${mainUrl}/category/macera-filmleri"             to "Macera Filmleri",
        "${mainUrl}/category/romantik-filmler"            to "Romantik Filmler",
        "${mainUrl}/category/suc-filmleri"                to "Suç Filmleri",
        "${mainUrl}/category/turkce-altyazili"            to "Türkçe Altyazılı",
        "${mainUrl}/category/turkce-dublaj"               to "Türkçe Dublaj",
        "${mainUrl}/category/yerli-filmler"               to "Yerli Filmler",
        "${mainUrl}/category/dizi-izle"                   to "Dizi İzle",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = if (page <= 1) {
            "${request.data}/"
        } else {
            "${request.data}/page/$page/"
        }
        val document = app.get(pageUrl, headers = mapOf("User-Agent" to DESKTOP_UA)).document
        val home     = document.select("div.movie-preview").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val aEl       = this.selectFirst("a") ?: return null
        val href      = fixUrlNull(aEl.attr("href")) ?: return null
        val imgEl     = this.selectFirst("img")
        val title     = imgEl?.attr("alt")?.trim()?.ifEmpty { null }
            ?: aEl.text().trim().ifEmpty { null }
            ?: return null
        val posterUrl = fixUrlNull(imgEl?.attr("data-src")?.ifEmpty { null } ?: imgEl?.attr("src"))

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/?s=${query}", headers = mapOf("User-Agent" to DESKTOP_UA)).document
        return document.select("div.movie-preview").mapNotNull { it.toSearchResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val response = app.get(url, headers = mapOf("User-Agent" to DESKTOP_UA))
        val document = response.document

        val rawTitle = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("div.title h1")?.text()?.trim()
            ?: return null

        val title = rawTitle.replace(Regex("""\s*full\s*hd\s*izle.*""", RegexOption.IGNORE_CASE), "").trim()

        val poster = fixUrlNull(
            document.selectFirst("div.poster img")?.attr("data-src")
                ?: document.selectFirst("div.poster img")?.attr("src")
                ?: document.selectFirst("img.wp-post-image")?.attr("data-src")
                ?: document.selectFirst("img.wp-post-image")?.attr("src")
        )

        var plot: String? = null
        var year: Int? = null
        var ratingStr: String? = null
        val tags = mutableListOf<String>()

        val jsonLdScripts = document.select("script[type='application/ld+json']")
        for (script in jsonLdScripts) {
            val scriptText = script.data()
            if (scriptText.contains("\"@type\": \"Movie\"") || scriptText.contains("\"@type\":\"Movie\"")) {
                try {
                    val json = JSONObject(scriptText)
                    if (json.has("description")) {
                        val d = json.optString("description")
                        if (d.isNotBlank()) plot = d
                    }
                    if (json.has("genre")) {
                        val g = json.optString("genre")
                        g.split(",").map { it.trim() }.filter { it.isNotBlank() }.forEach { tags.add(it) }
                    }
                    if (json.has("dateCreated")) {
                        val dateStr = json.optString("dateCreated")
                        val yearMatch = Regex("""\b(19|20)\d{2}\b""").find(dateStr)
                        if (yearMatch != null) {
                            year = yearMatch.value.toIntOrNull()
                        }
                    }
                    if (json.has("aggregateRating")) {
                        val ratingObj = json.optJSONObject("aggregateRating")
                        val rValue = ratingObj?.optString("ratingValue")
                        if (!rValue.isNullOrBlank()) {
                            ratingStr = rValue
                        }
                    }
                } catch (e: Exception) {
                    Log.e("TavsiyeFilmIzle", "Error parsing JSON-LD: ${e.message}")
                }
            }
        }

        if (plot.isNullOrBlank()) {
            plot = document.selectFirst("div.single-content.detail p")?.text()?.trim()
                ?: document.selectFirst("div.entry-content p")?.text()?.trim()
        }

        if (year == null) {
            val yearMatch = Regex("""\b(19|20)\d{2}\b""").find(title)
            if (yearMatch != null) year = yearMatch.value.toIntOrNull()
        }

        if (tags.isEmpty()) {
            document.select("a[href*='/category/']").forEach {
                val t = it.text().trim()
                if (t.isNotBlank() && !t.contains("20") && !tags.contains(t)) {
                    tags.add(t)
                }
            }
        }

        val trailer = document.selectFirst("iframe[src*='youtube']")?.attr("src")

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot      = plot
            this.year      = year
            this.tags      = tags
            this.score     = Score.from10(ratingStr)
            addTrailer(trailer)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("TavsiyeFilmIzle", "loadLinks data » $data")
        val document = app.get(data, headers = mapOf("User-Agent" to DESKTOP_UA)).document

        val candidatePages = mutableListOf<String>()
        candidatePages.add(data)

        val partLinks = document.select("div.keremiya_part a, div.source-popup a, div.parts a, ul.parts a, div.flexcroll a, a.part, span.part a")
        partLinks.forEach { a ->
            val href = fixUrlNull(a.attr("href"))
            if (href != null && !candidatePages.contains(href) && href.startsWith(mainUrl)) {
                candidatePages.add(href)
            }
        }

        val extractedIframes = mutableSetOf<String>()

        for (pageUrl in candidatePages) {
            try {
                val doc = if (pageUrl == data) document else app.get(pageUrl, headers = mapOf("User-Agent" to DESKTOP_UA)).document

                doc.select("iframe").forEach { iframe ->
                    val src = fixUrlNull(iframe.attr("src")?.ifEmpty { null } ?: iframe.attr("data-src")) ?: return@forEach
                    if (src == "about:blank" || extractedIframes.contains(src)) return@forEach
                    extractedIframes.add(src)

                    if (src.contains("hotstream.club")) {
                        HotStream().getUrl(src, pageUrl, subtitleCallback, callback)
                    } else {
                        loadExtractor(src, pageUrl, subtitleCallback, callback)
                    }
                }

                val html = doc.html()
                val iframeMatches = Regex("""src=["'](https?://[^"']+)["']""").findAll(html)
                for (match in iframeMatches) {
                    val src = fixUrlNull(match.groupValues[1]) ?: continue
                    if (src.contains("hotstream.club") && !extractedIframes.contains(src)) {
                        extractedIframes.add(src)
                        HotStream().getUrl(src, pageUrl, subtitleCallback, callback)
                    }
                }
            } catch (e: Exception) {
                Log.e("TavsiyeFilmIzle", "Error fetching links from $pageUrl: ${e.message}")
            }
        }

        return true
    }
}
