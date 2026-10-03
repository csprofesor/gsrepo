// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Base64
import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.fasterxml.jackson.annotation.JsonProperty

class SinemaCX : MainAPI() {
    override var mainUrl              = "https://sinemacc.com"
    override var name                 = "SinemaCX"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Movie)

    // ! CloudFlare bypass
	/*
    override var sequentialMainPage = true        // * https://recloudstream.github.io/dokka/-cloudstream/com.lagradost.cloudstream3/-main-a-p-i/index.html#-2049735995%2FProperties%2F101969414
    override var sequentialMainPageDelay       = 250L // ? 0.25 saniye
    override var sequentialMainPageScrollDelay = 250L // ? 0.25 saniye
	*/

    override val mainPage = mainPageOf(
        "$mainUrl/page/"                               to "Son Eklenen Filmler",
        "$mainUrl/tur/aile-filmleri/page/"             to "Aile Filmleri",
        "$mainUrl/tur/aksiyon-filmleri/page/"          to "Aksiyon Filmleri",
        "$mainUrl/tur/animasyon-filmleri/page/"        to "Animasyon Filmleri",
        "$mainUrl/tur/belgesel/page/"                  to "Belgesel Filmleri",
        "$mainUrl/tur/bilim-kurgu-filmleri/page/"      to "Bilim Kurgu Filmleri",
        "$mainUrl/tur/biyografi/page/"                 to "Biyografi Filmleri",
        "$mainUrl/tur/dram-filmleri/page/"             to "Dram Filmleri",
        "$mainUrl/tur/fantastik-filmler/page/"         to "Fantastik Filmler",
        "$mainUrl/tur/gerilim-filmleri/page/"          to "Gerilim Filmleri",
        "$mainUrl/tur/gizem-filmleri/page/"            to "Gizem Filmleri",
        "$mainUrl/tur/komedi-filmleri/page/"           to "Komedi Filmleri",
        "$mainUrl/tur/korku-filmleri/page/"            to "Korku Filmleri",
        "$mainUrl/tur/macera-filmleri/page/"           to "Macera Filmleri",
        "$mainUrl/tur/muzikal-filmler/page/"           to "Müzikal Filmler",
        "$mainUrl/tur/romantik-filmler/page/"          to "Romantik Filmler",
        "$mainUrl/tur/savas-filmleri/page/"            to "Savaş Filmleri",
        "$mainUrl/tur/spor-filmleri/page/"             to "Spor Filmleri",
        "$mainUrl/tur/suc-filmleri/page/"              to "Suç Filmleri",
        "$mainUrl/tur/tarihi-filmler/page/"            to "Tarih Filmleri",
        "$mainUrl/tur/western-filmleri/page/"          to "Western Filmleri",
        "$mainUrl/tur/yetiskin-filmler/page/"          to "Yetişkin Filmler",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}$page").document
        val home     = document.select("div.film_kutusu, div.son div.frag-k, div.icerik div.frag-k").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("span.title span.text")?.text()?.trim()
            ?: this.selectFirst("div.yanac span")?.text()?.trim()
            ?: this.selectFirst("a")?.attr("title")?.removeSuffix(" İzle")?.trim()
            ?: return null

        val href = fixUrlNull(this.selectFirst("a")?.attr("href"))
            ?: fixUrlNull(this.selectFirst("div.yanac a")?.attr("href"))
            ?: return null

        val posterUrl = fixUrlNull(this.selectFirst("span.image img, a.resim img, img")?.attr("data-src"))
            ?: fixUrlNull(this.selectFirst("span.image img, a.resim img, img")?.attr("src"))

        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document

        return document.select("div.film_kutusu, div.icerik div.frag-k").mapNotNull { it.toSearchResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title       = document.selectFirst("h1.baslik, div.f-bilgi h1")?.text()?.removeSuffix(" İzle")?.trim() ?: return null
        val poster      = fixUrlNull(document.selectFirst("meta[property='og:image']")?.attr("content")) ?: fixUrlNull(document.selectFirst("link[rel='image_src']")?.attr("href"))
        val year        = document.selectFirst("a[href*='yil/']")?.text()?.toIntOrNull() ?: document.selectFirst("div.f-bilgi ul.detay a[href*='yapim']")?.text()?.toIntOrNull()
        val description = document.selectFirst("meta[property='og:description']")?.attr("content") ?: document.selectFirst("div.f-bilgi div.ackl")?.text()?.trim()
        val tags        = document.select("a[href*='tur/']").map { it.text().trim() }
        val duration    = Regex("""Süre: </span>(\d+) Dakika</li>""").find(document.html())?.groupValues?.get(1)?.toIntOrNull()
        val actors      = document.select("div.oyuncu_kutusu, li.oync li.oyuncu-k").mapNotNull {
            val name = it.selectFirst("small, span.isim")?.text() ?: return@mapNotNull null
            val actorPoster = fixUrlNull(it.selectFirst("img")?.attr("src") ?: it.selectFirst("img")?.attr("data-src"))
            Actor(name, actorPoster)
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.year      = year
            this.plot      = description
            this.tags      = tags
            this.duration  = duration
            addActors(actors)
        }
    }

    private fun decodeIfBase64(input: String): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.startsWith("//")) {
            return trimmed
        }
        return try {
            val decoded = String(Base64.decode(trimmed, Base64.DEFAULT), Charsets.UTF_8).trim()
            if (decoded.startsWith("http://") || decoded.startsWith("https://") || decoded.startsWith("//")) {
                decoded
            } else {
                trimmed
            }
        } catch (_: Exception) {
            trimmed
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("SCX", "data » $data")

        val document = app.get(data).document

        // Collect all potential player sources (from buttons and iframes)
        val rawSources = mutableListOf<Pair<String, String>>()

        // 1. From button[rel] in player_part or part_button
        document.select("div.player_part button[rel], button.part_button[rel], button.grup_button[rel]").forEach { button ->
            val rel = button.attr("rel")
            val label = button.text().trim()
            if (rel.isNotEmpty()) {
                rawSources.add(Pair(rel, label))
            }
        }

        // 2. From iframes
        document.select("iframe#video_playeriframe, iframe[data-vsrc], iframe[src]").forEach { iframe ->
            val vsrc = iframe.attr("data-vsrc").ifEmpty { iframe.attr("src") }
            if (vsrc.isNotEmpty()) {
                rawSources.add(Pair(vsrc, ""))
            }
        }

        // Decode base64, fix URLs, and filter out trailers and ads
        val validSources = rawSources.mapNotNull { (raw, label) ->
            val decoded = decodeIfBase64(raw)
            val fixed = fixUrlNull(decoded) ?: return@mapNotNull null
            Pair(fixed, label)
        }.distinctBy { it.first }.filter { (url, _) ->
            !url.contains("youtube", ignoreCase = true) &&
            !url.contains("fragman", ignoreCase = true) &&
            !url.contains("trailer", ignoreCase = true) &&
            !url.contains("vr_set=", ignoreCase = true)
        }

        Log.d("SCX", "validSources » ${validSources.map { it.first }}")

        if (validSources.isEmpty()) return false

        var foundAny = false

        for ((sourceUrl, label) in validSources) {
            val cleanUrl = sourceUrl.substringBefore("?img=")
            Log.d("SCX", "processing sourceUrl » $cleanUrl (label: $label)")

            val sourceName = if (label.isNotEmpty()) "${this.name} - $label" else this.name

            if (cleanUrl.lowercase().contains("player.filmizle.in")) {
                val baseUrl = Regex("""https?://([^/]+)""").find(cleanUrl)?.groupValues?.get(1) ?: continue
                val dataKey = cleanUrl.split("/").lastOrNull()?.substringBefore("?") ?: continue

                try {
                    val panel = app.post(
                        "https://$baseUrl/player/index.php?data=$dataKey&do=getVideo",
                        headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
                        referer = "$mainUrl/"
                    ).parsedSafe<Panel>()

                    val vidUrl = panel?.securedLink ?: continue

                    callback.invoke(
                        newExtractorLink(
                            source = sourceName,
                            name = sourceName,
                            url = vidUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            quality = Qualities.Unknown.value
                            headers = mapOf("Referer" to cleanUrl)
                        }
                    )
                    foundAny = true
                } catch (e: Exception) {
                    Log.e("SCX", "Error fetching filmizle.in player video", e)
                }

                // Subtitle extraction
                try {
                    val iframeSource = app.get(cleanUrl, referer = "$mainUrl/").text
                    val subtitleSectionRegex = Regex("""playerjsSubtitle\s*=\s*"(.+?)"""")
                    val subtitleSectionMatch = subtitleSectionRegex.find(iframeSource)
                    if (subtitleSectionMatch != null) {
                        val subtitleSection = subtitleSectionMatch.groupValues[1]
                        val subtitleRegex = Regex("""\[(.*?)](https?://[^\s",]+)""")
                        for (subtitleMatch in subtitleRegex.findAll(subtitleSection)) {
                            val subtitleLanguage = subtitleMatch.groupValues[1]
                            val subtitleUrl = subtitleMatch.groupValues[2]

                            subtitleCallback.invoke(
                                newSubtitleFile(
                                    lang = subtitleLanguage,
                                    url = fixUrl(subtitleUrl)
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e("SCX", "Error fetching subtitles", e)
                }
            } else {
                val loaded = loadExtractor(cleanUrl, "$mainUrl/", subtitleCallback, callback)
                if (loaded) foundAny = true
            }
        }

        return foundAny
    }

    data class Panel(
        @JsonProperty("hls")         val hls: Boolean?        = null,
        @JsonProperty("securedLink") val securedLink: String? = null
    )
}
