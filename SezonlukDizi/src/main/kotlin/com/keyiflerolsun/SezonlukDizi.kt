// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors

class SezonlukDizi : MainAPI() {
    override var mainUrl              = "https://sezonlukdizi.cc"
    override var name                 = "SezonlukDizi"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/diziler.asp?siralama_tipi=id&s="          to "Son Eklenenler",
        "$mainUrl/diziler.asp?siralama_tipi=id&tur=mini&s=" to "Mini Diziler",
        "$mainUrl/diziler.asp?siralama_tipi=id&kat=2&s="    to "Yerli Diziler",
        "$mainUrl/diziler.asp?siralama_tipi=id&kat=1&s="    to "Yabancı Diziler",
        "$mainUrl/diziler.asp?siralama_tipi=id&kat=3&s="    to "Asya Dizileri",
        "$mainUrl/diziler.asp?siralama_tipi=id&kat=4&s="    to "Animasyonlar",
        "$mainUrl/diziler.asp?siralama_tipi=id&kat=5&s="    to "Animeler",
        "$mainUrl/diziler.asp?siralama_tipi=id&kat=6&s="    to "Belgeseller",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}$page").document
        val home     = document.select("div.afis a").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title     = this.selectFirst("div.description")?.text()?.trim() ?: return null
        val href      = fixUrlNull(this.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src"))

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/diziler.asp?adi=$query").document

        return document.select("div.afis a").mapNotNull { it.toSearchResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title       = document.selectFirst("div.header")?.text()?.trim() ?: return null
        val poster      = fixUrlNull(document.selectFirst("div.image img")?.attr("data-src")) ?: return null
        val year        = document.selectFirst("div.extra span")?.text()?.trim()?.split("-")?.first()?.toIntOrNull()
        val description = document.selectFirst("span#tartismayorum-konu")?.text()?.trim()
        val tags        = document.select("div.labels a[href*='tur']").mapNotNull { it.text().trim() }
        val duration    = document.selectXpath("//span[contains(text(), 'Dk.')]").text().trim().substringBefore(" Dk.").toIntOrNull()

        val endpoint    = url.split("/").last()

        val actorsReq  = app.get("$mainUrl/oyuncular/$endpoint").document
        val actors     = actorsReq.select("div.doubling div.ui").map {
            Actor(
                it.selectFirst("div.header")!!.text().trim(),
                fixUrlNull(it.selectFirst("img")?.attr("src")),
            )
        }

        val episodesReq = app.get("$mainUrl/bolumler/$endpoint").document
        val episodes    = mutableListOf<Episode>()
        for (sezon in episodesReq.select("table.unstackable")) {
            for (bolum in sezon.select("tbody tr")) {
                val epName    = bolum.selectFirst("td:nth-of-type(4) a")?.text()?.trim() ?: continue
                val epHref    = fixUrlNull(bolum.selectFirst("td:nth-of-type(4) a")?.attr("href")) ?: continue
                val epEpisode = bolum.selectFirst("td:nth-of-type(3)")?.text()?.substringBefore(".Bölüm")?.trim()?.toIntOrNull()
                val epSeason  = bolum.selectFirst("td:nth-of-type(2)")?.text()?.substringBefore(".Sezon")?.trim()?.toIntOrNull()

                episodes.add(
                    newEpisode(epHref) {
                        this.name    = epName
                        this.season  = epSeason
                        this.episode = epEpisode
                    }
                )
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.year      = year
            this.plot      = description
            this.tags      = tags
            this.duration  = duration
            addActors(actors)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("SZD", "data » $data")
        val document = app.get(data).document
        val aspData = getAspData(document)
        val bid = document.selectFirst("div#dilsec")?.attr("data-id") ?: return false
        Log.d("SZD", "bid » $bid")

        // --- ALTYAZI KISMI ---
        val altyaziResponse = app.post(
            "$mainUrl/ajax/dataAlternatif${aspData.alternatif}.asp",
            headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
            data = mapOf(
                "bid" to bid,
                "dil" to "1",
            )
        ).parsedSafe<Kaynak>()

        if (altyaziResponse?.status == "success") {
            for (veri in altyaziResponse.data) {
                Log.d("SZD", "dil»1 | veri.baslik » ${veri.baslik}")

                val veriResponse = app.post(
                    "$mainUrl/ajax/dataEmbed${aspData.embed}.asp",
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
                    data = mapOf("id" to veri.id.toString()),
                ).document

                val iframeSrc = veriResponse.selectFirst("iframe")?.attr("src")
                val iframe = fixUrlNull(iframeSrc) ?: continue
                Log.d("SZD", "dil»1 | iframe » $iframe")

                invokeExtractor("AltYazı", veri, iframe, subtitleCallback, callback)
            }
        }

        // --- DUBLAJ KISMI ---
        val dublajResponse = app.post(
            "$mainUrl/ajax/dataAlternatif${aspData.alternatif}.asp",
            headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
            data = mapOf(
                "bid" to bid,
                "dil" to "0",
            )
        ).parsedSafe<Kaynak>()

        if (dublajResponse?.status == "success") {
            for (veri in dublajResponse.data) {
                Log.d("SZD", "dil»0 | veri.baslik » ${veri.baslik}")

                val veriResponse = app.post(
                    "$mainUrl/ajax/dataEmbed${aspData.embed}.asp",
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
                    data = mapOf("id" to veri.id.toString()),
                ).document

                val iframeSrc = veriResponse.selectFirst("iframe")?.attr("src")
                val iframe = fixUrlNull(iframeSrc) ?: continue
                Log.d("SZD", "dil»0 | iframe » $iframe")

                invokeExtractor("Dublaj", veri, iframe, subtitleCallback, callback)
            }
        }

        return true
    }

    private suspend fun invokeExtractor(
        prefix: String,
        veri: Veri,
        iframe: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d("SZD", "invokeExtractor | prefix=$prefix | baslik=${veri.baslik} | iframe=$iframe")
        var found = false

        // 1. VidMoly direct extraction
        if (iframe.contains("vidmoly", ignoreCase = true)) {
            try {
                val iSource = app.get(iframe, headers = mapOf("Referer" to "$mainUrl/")).text
                val m3uLink = Regex("""file:\s*["']([^"']+\.m3u8[^"']*)["']""").find(iSource)?.groupValues?.get(1)
                    ?: Regex("""file:\s*["']([^"']+)["']""").find(iSource)?.groupValues?.get(1)

                if (!m3uLink.isNullOrEmpty() && (m3uLink.contains(".m3u8") || m3uLink.startsWith("http"))) {
                    callback.invoke(
                        newExtractorLink(
                            source = "$prefix - ${veri.baslik}",
                            name = "$prefix - ${veri.baslik}",
                            url = m3uLink,
                            type = INFER_TYPE
                        ) {
                            this.quality = Qualities.Unknown.value
                            this.headers = mapOf("Referer" to iframe)
                        }
                    )
                    found = true
                }
            } catch (e: Exception) {
                Log.e("SZD", "VidMoly direct error: ${e.message}")
            }
        }

        // 2. Sibnet direct extraction
        if (!found && iframe.contains("sibnet", ignoreCase = true)) {
            try {
                val iSource = app.get(iframe, headers = mapOf("Referer" to "$mainUrl/")).text
                val videoPath = Regex("""player\.src\(\[\{src:\s*["']([^"']+)["']""").find(iSource)?.groupValues?.get(1)
                if (videoPath != null) {
                    val fullUrl = if (videoPath.startsWith("http")) videoPath else "https://video.sibnet.ru$videoPath"
                    callback.invoke(
                        newExtractorLink(
                            source = "$prefix - ${veri.baslik}",
                            name = "$prefix - ${veri.baslik}",
                            url = fullUrl,
                            type = INFER_TYPE
                        ) {
                            this.quality = Qualities.Unknown.value
                            this.headers = mapOf("Referer" to iframe)
                        }
                    )
                    found = true
                }
            } catch (e: Exception) {
                Log.e("SZD", "Sibnet direct error: ${e.message}")
            }
        }

        // 3. Fallback / Standard loadExtractor
        if (!found) {
            val extractedLinks = mutableListOf<ExtractorLink>()
            val extracted = loadExtractor(iframe, "$mainUrl/", subtitleCallback) { link ->
                extractedLinks.add(link)
            }
            extractedLinks.forEach { link ->
                callback.invoke(
                    newExtractorLink(
                        source = "$prefix - ${veri.baslik}",
                        name = "$prefix - ${veri.baslik}",
                        url = link.url,
                        type = link.type
                    ) {
                        this.referer = link.referer
                        this.quality = link.quality
                        this.headers = link.headers
                        this.extractorData = link.extractorData
                    }
                )
            }

            if (!extracted && (iframe.contains("byse") || iframe.contains("filemoon"))) {
                val filemoonId = iframe.split("/e/").lastOrNull()?.split("?")?.firstOrNull()?.split("/")?.firstOrNull()
                if (filemoonId != null) {
                    val filemoonUrl = "https://filemoon.sx/e/$filemoonId"
                    val filemoonLinks = mutableListOf<ExtractorLink>()
                    loadExtractor(filemoonUrl, "$mainUrl/", subtitleCallback) { link ->
                        filemoonLinks.add(link)
                    }
                    filemoonLinks.forEach { link ->
                        callback.invoke(
                            newExtractorLink(
                                source = "$prefix - ${veri.baslik}",
                                name = "$prefix - ${veri.baslik}",
                                url = link.url,
                                type = link.type
                            ) {
                                this.referer = link.referer
                                this.quality = link.quality
                                this.headers = link.headers
                                this.extractorData = link.extractorData
                            }
                        )
                    }
                }
            }
        }
    }

    private suspend fun getAspData(document: Document? = null): AspData {
        return try {
            val jsSrc = document?.selectFirst("script[src*='site.min.js']")?.attr("src")
            val jsUrl = jsSrc?.let { fixUrl(it) } ?: "$mainUrl/js/site.min.js?v=0.82"
            val websiteCustomJavascript = app.get(jsUrl).text
            val dataAlternatifAsp = Regex("""dataAlternatif(\d+)\.asp""").find(websiteCustomJavascript)?.groupValues?.get(1) ?: "22"
            val dataEmbedAsp = Regex("""dataEmbed(\d+)\.asp""").find(websiteCustomJavascript)?.groupValues?.get(1) ?: "22"
            AspData(dataAlternatifAsp, dataEmbedAsp)
        } catch (_: Exception) {
            AspData("22", "22")
        }
    }
}
