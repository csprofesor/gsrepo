// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

class SezonlukDiziCaptchaSolver(private val context: Context) {
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun solve(mainUrl: String): Boolean = withTimeoutOrNull(25000) {
        suspendCancellableCoroutine { continuation ->
            var isSolved = false
            CoroutineScope(Dispatchers.Main).launch {
                var webView: WebView? = null
                fun cleanup() {
                    try {
                        webView?.stopLoading()
                        webView?.destroy()
                        webView = null
                    } catch (_: Exception) {}
                }

                try {
                    webView = WebView(context).apply {
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            mediaPlaybackRequiresUserGesture = false
                            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                        }

                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                val js = """
                                    (function() {
                                        if (window.injectedSubmitHook) return;
                                        window.injectedSubmitHook = true;

                                        var reportSuccess = function() {
                                            document.title = 'CAPTCHA_SOLVED';
                                        };

                                        var origSubmit = window.submitVerification;
                                        window.submitVerification = function(token) {
                                            if (typeof $ !== 'undefined' && $.post) {
                                                $.post("/ajax/reCAPTCHA.asp", { 'cf-turnstile-response': token }, function(data) {
                                                    if (data === "ok") {
                                                        reportSuccess();
                                                    } else if (typeof origSubmit === 'function') {
                                                        origSubmit(token);
                                                    }
                                                });
                                            } else if (typeof origSubmit === 'function') {
                                                origSubmit(token);
                                            }
                                        };

                                        setInterval(function() {
                                            try {
                                                var iframe = document.querySelector('#turnstile-container iframe, iframe[src*="turnstile"], iframe[src*="challenges.cloudflare.com"]');
                                                if (iframe) {
                                                    var rect = iframe.getBoundingClientRect();
                                                    if (rect && rect.width > 0 && rect.height > 0) {
                                                        var clickX = Math.round(rect.left + 35);
                                                        var clickY = Math.round(rect.top + (rect.height / 2));
                                                        document.title = 'CLICK_POS:' + clickX + ':' + clickY;
                                                    }
                                                }
                                            } catch(e) {}
                                        }, 500);
                                    })();
                                """.trimIndent()
                                view?.evaluateJavascript(js, null)
                            }
                        }

                        var lastClickX = -1f
                        var lastClickY = -1f

                        webChromeClient = object : WebChromeClient() {
                            override fun onReceivedTitle(view: WebView?, title: String?) {
                                if (title == "CAPTCHA_SOLVED" && !isSolved) {
                                    isSolved = true
                                    try { CookieManager.getInstance().flush() } catch (_: Exception) {}
                                    if (continuation.isActive) continuation.resume(true)
                                    CoroutineScope(Dispatchers.Main).launch {
                                        delay(500)
                                        cleanup()
                                    }
                                } else if (title?.startsWith("CLICK_POS:") == true) {
                                    try {
                                        val parts = title.split(":")
                                        if (parts.size == 3) {
                                            lastClickX = parts[1].toFloatOrNull() ?: -1f
                                            lastClickY = parts[2].toFloatOrNull() ?: -1f
                                        }
                                    } catch (_: Exception) {}
                                }
                            }
                        }

                        CoroutineScope(Dispatchers.Main).launch {
                            repeat(40) {
                                delay(600)
                                if (isSolved) return@launch
                                try {
                                    val v = webView ?: return@launch
                                    val w = v.width.coerceAtLeast(600)
                                    val h = v.height.coerceAtLeast(600)

                                    val targetX: Float
                                    val targetY: Float

                                    if (lastClickX > 0 && lastClickY > 0) {
                                        val density = context.resources.displayMetrics.density
                                        targetX = lastClickX * density
                                        targetY = lastClickY * density
                                    } else {
                                        targetX = (w / 2f) - 115f
                                        targetY = h / 2f
                                    }

                                    val now = SystemClock.uptimeMillis()
                                    val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, targetX, targetY, 0)
                                    val up = MotionEvent.obtain(now, now + 100, MotionEvent.ACTION_UP, targetX, targetY, 0)
                                    v.dispatchTouchEvent(down)
                                    v.dispatchTouchEvent(up)
                                    down.recycle()
                                    up.recycle()
                                } catch (_: Exception) {}
                            }
                            if (!isSolved && continuation.isActive) {
                                continuation.resume(false)
                                CoroutineScope(Dispatchers.Main).launch {
                                    cleanup()
                                }
                            }
                        }

                        loadUrl("$mainUrl/ajax/reCAPTCHADATA.asp")
                    }
                } catch (e: Exception) {
                    Log.e("SZD", "CaptchaSolver error: ${e.message}")
                    if (continuation.isActive) continuation.resume(false)
                }

                continuation.invokeOnCancellation {
                    CoroutineScope(Dispatchers.Main).launch {
                        cleanup()
                    }
                }
            }
        }
    } ?: false
}

class SezonlukDiziWebViewExtractor(private val context: Context) {
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun extract(
        url: String,
        referer: String = "https://sezonlukdizi.cc/",
    ): String? = withTimeoutOrNull(25000) {
        suspendCancellableCoroutine { continuation ->
            val foundStream = AtomicBoolean(false)

            CoroutineScope(Dispatchers.Main).launch {
                var webView: WebView? = null
                fun cleanup() {
                    try {
                        webView?.stopLoading()
                        webView?.destroy()
                        webView = null
                    } catch (_: Exception) {}
                }

                try {
                    webView = WebView(context).apply {
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            mediaPlaybackRequiresUserGesture = false
                            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                        }

                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, pageUrl: String?) {
                                super.onPageFinished(view, pageUrl)
                                CoroutineScope(Dispatchers.Main).launch {
                                    while (!foundStream.get()) {
                                        delay(1000)
                                        if (foundStream.get()) break
                                        try {
                                            val js = """
                                                (function() {
                                                    try {
                                                        let btn = document.querySelector('.jw-display-icon-container, .jw-icon-play, button[aria-label="Play"], video, .vjs-big-play-button, #play, .play');
                                                        if (btn) btn.click();
                                                        
                                                        let ifr = document.querySelector('iframe');
                                                        if (ifr) ifr.click();
                                                    } catch(e) {}
                                                })();
                                            """.trimIndent()
                                            view?.evaluateJavascript(js, null)
                                        } catch (_: Exception) {
                                            break
                                        }
                                    }
                                }
                            }

                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {
                                val reqUrl = request?.url?.toString() ?: ""
                                val lowerUrl = reqUrl.lowercase()

                                val isMedia = (lowerUrl.contains(".m3u8") ||
                                        lowerUrl.contains("/hls/") ||
                                        lowerUrl.contains(".mp4") ||
                                        lowerUrl.contains(".mkv") ||
                                        lowerUrl.contains("/stream/") ||
                                        lowerUrl.contains("playlist") ||
                                        lowerUrl.contains("googlevideo.com") ||
                                        lowerUrl.contains("video.sibnet.ru") ||
                                        lowerUrl.contains("pixeldrain.com/api/file/")) &&
                                        !lowerUrl.contains("recaptcha") &&
                                        !lowerUrl.contains("turnstile") &&
                                        !lowerUrl.contains("analytics") &&
                                        !lowerUrl.contains("google") &&
                                        !lowerUrl.contains("beacon") &&
                                        !lowerUrl.contains(".png") &&
                                        !lowerUrl.contains(".jpg") &&
                                        !lowerUrl.contains(".css") &&
                                        !lowerUrl.contains(".js")

                                if (isMedia) {
                                    if (!foundStream.getAndSet(true)) {
                                        Log.d("SZD", "SezonlukDiziWebViewExtractor stream found: $reqUrl")
                                        if (continuation.isActive) {
                                            continuation.resume(reqUrl)
                                        }
                                        CoroutineScope(Dispatchers.Main).launch {
                                            delay(500)
                                            cleanup()
                                        }
                                    }
                                }
                                return super.shouldInterceptRequest(view, request)
                            }
                        }

                        CoroutineScope(Dispatchers.Main).launch {
                            repeat(30) {
                                delay(500)
                                if (foundStream.get()) return@launch
                                try {
                                    val v = webView ?: return@launch
                                    val w = v.width.coerceAtLeast(600)
                                    val h = v.height.coerceAtLeast(600)
                                    val x = w / 2f
                                    val y = h / 2f
                                    val now = SystemClock.uptimeMillis()
                                    val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
                                    val up = MotionEvent.obtain(now, now + 100, MotionEvent.ACTION_UP, x, y, 0)
                                    v.dispatchTouchEvent(down)
                                    v.dispatchTouchEvent(up)
                                    down.recycle()
                                    up.recycle()
                                } catch (_: Exception) {}
                            }
                        }

                        loadUrl(url, mapOf("Referer" to referer))
                    }
                } catch (e: Exception) {
                    Log.e("SZD", "WebView creation error: ${e.message}")
                    if (continuation.isActive) continuation.resume(null)
                }

                continuation.invokeOnCancellation {
                    CoroutineScope(Dispatchers.Main).launch {
                        cleanup()
                    }
                }
            }
        }
    }
}

class SezonlukDizi : MainAPI() {
    override var mainUrl              = "https://sezonlukdizi.cc"
    override var name                 = "SezonlukDizi"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.TvSeries)

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
        "$mainUrl/diziler.asp?s="          to "Son Eklenenler",
        "$mainUrl/diziler.asp?kat=1&s="    to "Yabancı Diziler",
        "$mainUrl/diziler.asp?kat=2&s="    to "Yerli Diziler",
        "$mainUrl/diziler.asp?kat=3&s="    to "Asya Dizileri",
        "$mainUrl/diziler.asp?kat=4&s="    to "Animasyonlar",
        "$mainUrl/diziler.asp?kat=5&s="    to "Animeler",
        "$mainUrl/diziler.asp?kat=6&s="    to "Belgeseller",
        "$mainUrl/diziler.asp?tur=mini&s=" to "Mini Diziler",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}$page", interceptor = interceptor).document
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
        val document = app.get("$mainUrl/diziler.asp?adi=$query", interceptor = interceptor).document

        return document.select("div.afis a").mapNotNull { it.toSearchResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, interceptor = interceptor).document

        val title       = document.selectFirst("div.header")?.text()?.trim() ?: return null
        val poster      = fixUrlNull(document.selectFirst("div.image img")?.attr("data-src")) ?: return null
        val year        = document.selectFirst("div.extra span")?.text()?.trim()?.split("-")?.first()?.toIntOrNull()
        val description = document.selectFirst("span#tartismayorum-konu")?.text()?.trim()
        val tags        = document.select("div.labels a[href*='tur']").mapNotNull { it.text().trim() }
        val duration    = document.selectXpath("//span[contains(text(), 'Dk.')]").text().trim().substringBefore(" Dk.").toIntOrNull()

        val endpoint    = url.split("/").last()

        val actorsReq  = app.get("$mainUrl/oyuncular/$endpoint", interceptor = interceptor).document
        val actors     = actorsReq.select("div.doubling div.ui").map {
            Actor(
                it.selectFirst("div.header")!!.text().trim(),
                fixUrlNull(it.selectFirst("img")?.attr("src")),
            )
        }

        val episodesReq = app.get("$mainUrl/bolumler/$endpoint", interceptor = interceptor).document
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
        val document = app.get(data, interceptor = interceptor).document
        val aspData = getAspData(document)
        val bid = document.selectFirst("div#dilsec")?.attr("data-id")
            ?: Regex("""data-id=["'](\d+)["']""").find(document.html())?.groupValues?.get(1)
            ?: return false
        Log.d("SZD", "bid » $bid")

        var isCaptchaSolved = false

        suspend fun handleCaptcha(veri: Veri, initialIframe: String?): String? {
            var currentIframe = initialIframe
            if (currentIframe == null || currentIframe.contains("reCAPTCHADATA", ignoreCase = true)) {
                if (!isCaptchaSolved) {
                    Log.d("SZD", "Solving Turnstile Captcha...")
                    val ctx = SezonlukDiziPlugin.pluginContext
                    if (ctx != null) {
                        isCaptchaSolved = SezonlukDiziCaptchaSolver(ctx).solve(mainUrl)
                        Log.d("SZD", "Captcha Solved: $isCaptchaSolved")
                    }
                }

                if (isCaptchaSolved) {
                    val cookieManager = CookieManager.getInstance()
                    val webCookiesStr = cookieManager.getCookie(mainUrl) ?: ""
                    if (webCookiesStr.isNotBlank()) {
                        val urlObj = "$mainUrl/".toHttpUrl()
                        val cookiesList = webCookiesStr.split(";").mapNotNull {
                            val parts = it.split("=", limit = 2)
                            if (parts.size == 2) {
                                Cookie.Builder()
                                    .name(parts[0].trim())
                                    .value(parts[1].trim())
                                    .domain(urlObj.host)
                                    .build()
                            } else null
                        }
                        app.baseClient.cookieJar.saveFromResponse(urlObj, cookiesList)
                    }

                    val newVeriResponse = app.post(
                        "$mainUrl/ajax/dataEmbed${aspData.embed}.asp",
                        headers = mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                            "Cookie" to webCookiesStr
                        ),
                        referer = data,
                        interceptor = interceptor,
                        data = mapOf("id" to veri.id.toString()),
                    ).document
                    currentIframe = fixUrlNull(newVeriResponse.selectFirst("iframe")?.attr("src"))
                }
            }

            if (currentIframe == null || currentIframe.contains("reCAPTCHADATA", ignoreCase = true)) {
                val scriptSource = document.html()
                val functionMatch = Regex("""(vidmoly|sruby|filemoon|pixel|okru|mailru)\('([^']+)'""").find(scriptSource)
                if (functionMatch != null) {
                    val platform = functionMatch.groupValues[1]
                    val vidId = functionMatch.groupValues[2]

                    currentIframe = when (platform) {
                        "vidmoly" -> "https://vidmoly.to/embed-$vidId.html"
                        "sruby" -> "https://rubyvidhub.com/embed-$vidId.html"
                        "filemoon" -> "https://bysejikuar.com/e/$vidId"
                        "pixel" -> "https://pixeldrain.com/u/$vidId"
                        "okru" -> "https://ok.ru/videoembed/$vidId"
                        "mailru" -> "https://my.mail.ru/video/embed/$vidId"
                        else -> currentIframe
                    }
                }
            }

            return currentIframe
        }

        // --- ALTYAZI KISMI ---
        val altyaziResponse = app.post(
            "$mainUrl/ajax/dataAlternatif${aspData.alternatif}.asp",
            headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
            referer = data,
            interceptor = interceptor,
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
                    referer = data,
                    interceptor = interceptor,
                    data = mapOf("id" to veri.id.toString()),
                ).document

                var iframeSrc = fixUrlNull(veriResponse.selectFirst("iframe")?.attr("src"))
                iframeSrc = handleCaptcha(veri, iframeSrc)

                val iframe = fixUrlNull(iframeSrc) ?: continue
                if (iframe.contains("reCAPTCHA", ignoreCase = true) || iframe.contains("reCAPTCHADATA", ignoreCase = true)) {
                    Log.d("SZD", "Skipping reCAPTCHA iframe: $iframe")
                    continue
                }

                Log.d("SZD", "dil»1 | iframe » $iframe")
                invokeExtractor("AltYazı", veri, iframe, subtitleCallback, callback)
            }
        }

        // --- DUBLAJ KISMI ---
        val dublajResponse = app.post(
            "$mainUrl/ajax/dataAlternatif${aspData.alternatif}.asp",
            headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
            referer = data,
            interceptor = interceptor,
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
                    referer = data,
                    interceptor = interceptor,
                    data = mapOf("id" to veri.id.toString()),
                ).document

                var iframeSrc = fixUrlNull(veriResponse.selectFirst("iframe")?.attr("src"))
                iframeSrc = handleCaptcha(veri, iframeSrc)

                val iframe = fixUrlNull(iframeSrc) ?: continue
                if (iframe.contains("reCAPTCHA", ignoreCase = true) || iframe.contains("reCAPTCHADATA", ignoreCase = true)) {
                    Log.d("SZD", "Skipping reCAPTCHA iframe: $iframe")
                    continue
                }

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
                var iSource = app.get(iframe, headers = mapOf("Referer" to "$mainUrl/"), interceptor = interceptor).text
                val redirMatch = Regex("""window\.location\.replace\(['"]([^'"]+)['"]""").find(iSource)
                if (redirMatch != null) {
                    val redirUrl = redirMatch.groupValues[1]
                    iSource = app.get(redirUrl, headers = mapOf("Referer" to iframe), interceptor = interceptor).text
                }

                val m3uLink = Regex("""file:\s*["']([^"']+\.m3u8[^"']*)["']""").find(iSource)?.groupValues?.get(1)
                    ?: Regex("""file:\s*["']([^"']+)["']""").find(iSource)?.groupValues?.get(1)
                    ?: Regex("""sources:\s*\[\{\s*file:\s*["']([^"']+)["']""").find(iSource)?.groupValues?.get(1)

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
                val iSource = app.get(iframe, headers = mapOf("Referer" to "$mainUrl/"), interceptor = interceptor).text
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

        // 3. Pixeldrain direct extraction
        if (!found && (iframe.contains("pixeldrain", ignoreCase = true) || iframe.contains("pixel", ignoreCase = true))) {
            try {
                val pixelId = iframe.split("/u/").lastOrNull()?.split("?")?.firstOrNull()?.split("/")?.firstOrNull()
                    ?: iframe.split("v=").lastOrNull()?.split("&")?.firstOrNull()
                if (pixelId != null) {
                    val downloadUrl = "https://pixeldrain.com/api/file/$pixelId?download"
                    callback.invoke(
                        newExtractorLink(
                            source = "$prefix - ${veri.baslik}",
                            name = "$prefix - ${veri.baslik}",
                            url = downloadUrl,
                            type = INFER_TYPE
                        ) {
                            this.quality = Qualities.Unknown.value
                            this.headers = mapOf("Referer" to iframe)
                        }
                    )
                    found = true
                }
            } catch (e: Exception) {
                Log.e("SZD", "Pixeldrain direct error: ${e.message}")
            }
        }

        // 4. Custom WebView extractor for Byse, Filemoon, SPA players
        if (!found) {
            val ctx = SezonlukDiziPlugin.pluginContext
            if (ctx != null) {
                try {
                    Log.d("SZD", "Trying SezonlukDiziWebViewExtractor for $iframe...")
                    val wvUrl = SezonlukDiziWebViewExtractor(ctx).extract(iframe, "$mainUrl/")
                    if (wvUrl != null && wvUrl.isNotBlank()) {
                        callback.invoke(
                            newExtractorLink(
                                source = "$prefix - ${veri.baslik}",
                                name = "$prefix - ${veri.baslik}",
                                url = wvUrl,
                                type = INFER_TYPE
                            ) {
                                this.quality = Qualities.Unknown.value
                                this.headers = mapOf("Referer" to iframe)
                            }
                        )
                        found = true
                        Log.d("SZD", "SezonlukDiziWebViewExtractor success: $wvUrl")
                    }
                } catch (e: Exception) {
                    Log.e("SZD", "SezonlukDiziWebViewExtractor error: ${e.message}")
                }
            }
        }

        // 5. Fallback / Standard loadExtractor
        if (!found) {
            val extractedLinks = mutableListOf<ExtractorLink>()
            loadExtractor(iframe, "$mainUrl/", subtitleCallback) { link ->
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
                found = true
            }
        }

        // 6. WebViewResolver fallback for SPA players
        if (!found) {
            try {
                Log.d("SZD", "Trying WebViewResolver for $iframe...")
                val resolver = WebViewResolver(Regex(".*(?:\\.m3u8|\\.txt|/hls/|playlist|\\.mp4|\\.mkv).*"))
                val wvResp = app.get(iframe, headers = mapOf("Referer" to "$mainUrl/"), interceptor = resolver)
                val wvUrl = wvResp.url
                if (wvUrl.contains(".m3u8") || wvUrl.contains("/hls/") || wvUrl.contains("playlist") || wvUrl.contains(".mp4") || wvUrl.contains(".mkv")) {
                    callback.invoke(
                        newExtractorLink(
                            source = "$prefix - ${veri.baslik}",
                            name = "$prefix - ${veri.baslik}",
                            url = wvUrl,
                            type = INFER_TYPE
                        ) {
                            this.quality = Qualities.Unknown.value
                            this.headers = mapOf("Referer" to iframe)
                        }
                    )
                    Log.d("SZD", "WebViewResolver found stream URL: $wvUrl")
                } else {
                    Log.w("SZD", "WebViewResolver returned non-stream URL: $wvUrl")
                }
            } catch (e: Exception) {
                Log.e("SZD", "WebViewResolver error: ${e.message}")
            }
        }
    }

    private suspend fun getAspData(document: Document? = null): AspData {
        return try {
            val jsSrc = document?.selectFirst("script[src*='site.min.js']")?.attr("src")
            val jsUrl = jsSrc?.let { fixUrl(it) } ?: "$mainUrl/js/site.min.js?v=0.82"
            val websiteCustomJavascript = app.get(jsUrl, interceptor = interceptor).text
            val dataAlternatifAsp = Regex("""dataAlternatif(\d+)\.asp""").find(websiteCustomJavascript)?.groupValues?.get(1) ?: "22"
            val dataEmbedAsp = Regex("""dataEmbed(\d+)\.asp""").find(websiteCustomJavascript)?.groupValues?.get(1) ?: "22"
            AspData(dataAlternatifAsp, dataEmbedAsp)
        } catch (_: Exception) {
            AspData("22", "22")
        }
    }
}
