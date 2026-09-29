// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer

class FilmModu : MainAPI() {
    override var mainUrl              = "https://www.filmmodu.one"
    override var name                 = "FilmModu"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "${mainUrl}/film-tur/4k-film-izle"          to "4K",
        "${mainUrl}/film-tur/aile-filmleri"         to "Aile",
        "${mainUrl}/film-tur/aksiyon"               to "Aksiyon",
        "${mainUrl}/film-tur/animasyon"             to "Animasyon",
        "${mainUrl}/film-tur/belgeseller"           to "Belgesel",
        "${mainUrl}/film-tur/bilim-kurgu-filmleri"  to "Bilim-Kurgu",
        "${mainUrl}/film-tur/dram-filmleri"         to "Dram",
        "${mainUrl}/film-tur/fantastik-filmler"     to "Fantastik",
        "${mainUrl}/film-tur/gerilim"               to "Gerilim",
        "${mainUrl}/film-tur/gizem-filmleri"        to "Gizem",
        "${mainUrl}/film-tur/hd-hint-filmleri"      to "Hint Filmleri",
        "${mainUrl}/film-tur/kisa-film"             to "Kısa Film",
        "${mainUrl}/film-tur/hd-komedi-filmleri"    to "Komedi",
        "${mainUrl}/film-tur/korku-filmleri"        to "Korku",
        "${mainUrl}/film-tur/kult-filmler-izle"     to "Kült Filmler",
        "${mainUrl}/film-tur/macera-filmleri"       to "Macera",
        "${mainUrl}/film-tur/muzik"                 to "Müzik",
        "${mainUrl}/film-tur/odullu-filmler-izle"   to "Oscar Ödüllü Filmler",
        "${mainUrl}/film-tur/romantik-filmler"      to "Romantik",
        "${mainUrl}/film-tur/savas-filmleri"        to "Savaş",
        "${mainUrl}/film-tur/stand-up"              to "Stand Up",
        "${mainUrl}/film-tur/suc-filmleri"          to "Suç",
        "${mainUrl}/film-tur/tarih"                 to "Tarih",
        "${mainUrl}/film-tur/tavsiye-filmler"       to "Tavsiye Filmler",
        "${mainUrl}/film-tur/tv-film"               to "TV film",
        "${mainUrl}/film-tur/vahsi-bati-filmleri"   to "Vahşi Batı",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}?page=${page}").document
        val home     = document.select("div.movie").mapNotNull { it.toMainPageResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val title     = this.selectFirst("a")?.text()?.trim() ?: return null
        val href      = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(
            this.selectFirst("img")?.attr("data-src")?.ifEmpty { null }
                ?: this.selectFirst("img")?.attr("src")
        )

        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/film-ara?term=${query}").document

        return document.select("div.movie").mapNotNull { it.toMainPageResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val orgTitle    = document.selectFirst("div.titles h1")?.text()?.trim() ?: return null
        val altTitle    = document.selectFirst("div.titles h2")?.text()?.trim() ?: ""
        val title       = if (altTitle.isNotEmpty()) "$orgTitle - $altTitle" else orgTitle
        val poster      = fixUrlNull(
            document.selectFirst("img.img-responsive")?.attr("src")
                ?: document.selectFirst("div.poster img")?.attr("src")
                ?: document.selectFirst("img")?.attr("src")
        )
        val description = document.selectFirst("p[itemprop='description']")?.text()?.trim()
        val year        = document.selectFirst("span[itemprop='dateCreated']")?.text()?.trim()?.toIntOrNull()
        val tags        = document.select("div.description a[href*='film-tur/']").map { it.text().trim() }
        val actors      = document.select("div.description a[href*='/aktor/'], div.description a[href*='-oyuncu-']").mapNotNull {
            val name = it.selectFirst("span[itemprop='name']")?.text()?.trim() ?: it.text().trim()
            if (name.isNotEmpty()) Actor(name) else null
        }
        val trailer     = document.selectFirst("div.container iframe")?.attr("src")

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot      = description
            this.year      = year
            this.tags      = tags
            addActors(actors)
            addTrailer(trailer)
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("FLMMD", "data » $data")
        val document = app.get(data).document

        val candidatePages = mutableListOf<Pair<String, String>>()
        val alts = document.select("div.alternates a")
        if (alts.isNotEmpty()) {
            alts.forEach {
                val altLink = fixUrlNull(it.attr("href")) ?: return@forEach
                val altName = it.text().trim()
                if (altName.isNotEmpty()) {
                    candidatePages.add(altName to altLink)
                }
            }
        } else {
            candidatePages.add("Main" to data)
        }

        val videoIdRegex   = Regex("""videoId\s*=\s*['"]([^'"]+)['"]""")
        val videoTypeRegex = Regex("""videoType\s*=\s*['"]([^'"]*)['"]""")

        candidatePages.forEach { (altName, altLink) ->
            if (altName.contains("fragman", ignoreCase = true) || altName.contains("trailer", ignoreCase = true) || altName.contains("teaser", ignoreCase = true)) {
                return@forEach
            }

            try {
                val pageText = if (altLink == data) document.html() else app.get(altLink).text

                val vidId   = videoIdRegex.find(pageText)?.groupValues?.get(1) ?: return@forEach
                val vidType = videoTypeRegex.find(pageText)?.groupValues?.get(1) ?: return@forEach

                if (vidId.isBlank() || vidType.isBlank()) return@forEach

                val getSourceUrl = "${mainUrl}/get-source?movie_id=${vidId}&type=${vidType}"
                val vidReq = app.get(
                    getSourceUrl,
                    referer = altLink,
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                ).parsedSafe<GetSource>() ?: return@forEach

                if (!vidReq.subtitle.isNullOrBlank()) {
                    subtitleCallback.invoke(
                        newSubtitleFile(
                            lang = "Türkçe",
                            url  = fixUrl(vidReq.subtitle)
                        )
                    )
                }

                vidReq.sources?.forEach { source ->
                    val sourceUrl = fixUrlNull(source.src) ?: return@forEach
                    callback.invoke(
                        newExtractorLink(
                            source  = "${this.name} - $altName",
                            name    = "${this.name} - $altName",
                            url     = sourceUrl,
                            type    = ExtractorLinkType.M3U8
                        ) {
                            headers = mapOf("Referer" to "${mainUrl}/")
                            quality = getQualityFromName(source.label)
                        }
                    )
                }
            } catch (e: Exception) {
                Log.e("FLMMD", "Error loading links for $altName ($altLink): ${e.message}", e)
            }
        }

        return true
    }
}
