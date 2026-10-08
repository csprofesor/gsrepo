package com.gsrepo

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

class SinemaTVWebViewExtractor(private val context: Context, private val pluginName: String) : ExtractorApi() {
    override val name = "SinemaTV WebView"
    override val mainUrl = "https://sinematv.az"
    override val requiresReferer = true

    private var webView: WebView? = null

    private fun isJunkUrl(url: String): Boolean {
        val lower = url.lowercase()
        if (lower.contains("doubleclick") ||
            lower.contains("google-analytics") ||
            lower.contains("googletagmanager") ||
            lower.contains("googlesyndication") ||
            lower.contains("gstatic.com") ||
            lower.contains("yandex") ||
            lower.contains("facebook") ||
            lower.contains("disqus") ||
            lower.contains("popunder") ||
            lower.contains("pixel") ||
            lower.contains("banner") ||
            lower.contains("tracker") ||
            lower.contains("vast") ||
            lower.contains("vv-api.php") ||
            lower.contains("parsed.json") ||
            lower.contains("catalog-api")
        ) {
            return true
        }

        val path = lower.substringBefore("?").substringBefore("#")
        return path.endsWith(".js") ||
               path.endsWith(".css") ||
               path.endsWith(".png") ||
               path.endsWith(".jpg") ||
               path.endsWith(".jpeg") ||
               path.endsWith(".gif") ||
               path.endsWith(".webp") ||
               path.endsWith(".svg") ||
               path.endsWith(".ico") ||
               path.endsWith(".txt")
    }

    private fun isValidStreamUrl(url: String): Boolean {
        if (isJunkUrl(url)) return false
        val lower = url.lowercase()
        val path = lower.substringBefore("?").substringBefore("#")
        return path.endsWith(".m3u8") ||
               path.endsWith(".mp4") ||
               lower.contains("storage.googleapis.com") ||
               lower.contains("vkvideo.cloud") ||
               lower.contains("sssrr.org") ||
               lower.contains("deovi.mvapspdmpg.com") ||
               lower.contains("grouped.m3u8") ||
               lower.contains("master.m3u8") ||
               lower.contains("index.m3u8")
    }

    private fun emitStream(
        streamUrl: String,
        foundStream: AtomicBoolean,
        browserUserAgent: String,
        targetUrl: String,
        callback: (ExtractorLink) -> Unit
    ) {
        if (!foundStream.getAndSet(true)) {
            Log.d("SinemaTVWebView", "EMITTING_STREAM=$streamUrl")
            CoroutineScope(Dispatchers.IO).launch {
                val refererHost = try { URI(targetUrl).let { "${it.scheme}://${it.host}/" } } catch(_: Exception) { "$mainUrl/" }
                val originHost = refererHost.trimEnd('/')

                // AbyssPlayer ve benzer sağlayıcılar, ana sayfayı referans alarak koruma uygulayabilir
                val finalReferer = when {
                    streamUrl.contains("storage.googleapis.com") || streamUrl.contains("vkvideo.cloud") || streamUrl.contains("deovi.mvapspdmpg.com") || streamUrl.contains("sssrr.org") -> targetUrl
                    else -> refererHost
                }
                
                val finalOrigin = try { URI(finalReferer).let { "${it.scheme}://${it.host}" } } catch(_: Exception) { originHost }

                if (streamUrl.contains(".m3u8", ignoreCase = true) || streamUrl.contains("playlist", ignoreCase = true) || streamUrl.contains("manifest", ignoreCase = true)) {
                    val resolved = SinemaTVHelper.resolveM3u8Streams(
                        pluginName,
                        streamUrl,
                        finalReferer,
                        mapOf("User-Agent" to browserUserAgent),
                        callback
                    )
                    if (!resolved) {
                        callback.invoke(
                            newExtractorLink(
                                source = pluginName,
                                name = pluginName,
                                url = streamUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.quality = Qualities.P1080.value
                                this.headers = mapOf(
                                    "Referer" to finalReferer,
                                    "Origin" to finalOrigin,
                                    "User-Agent" to browserUserAgent
                                )
                            }
                        )
                    }
                } else {
                    callback.invoke(
                        newExtractorLink(
                            source = pluginName,
                            name = pluginName,
                            url = streamUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.quality = Qualities.P1080.value
                            this.headers = mapOf(
                                "Referer" to finalReferer,
                                "Origin" to finalOrigin,
                                "User-Agent" to browserUserAgent
                            )
                        }
                    )
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        Log.d("SinemaTVWebView", "START_EXTRACTOR=$url")
        val foundStream = AtomicBoolean(false)
        val targetUrl = url

        val browserUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

        withContext(Dispatchers.Main) {
            webView = WebView(context).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    userAgentString = browserUserAgent
                }

                addJavascriptInterface(
                    object : Any() {
                        @JavascriptInterface
                        fun onStreamFound(body: String, reqUrl: String) {
                            Log.d("SinemaTVWebView", "BRIDGE_FOUND: $reqUrl")
                            val m3u8 = Regex("""https?://[^\s"'<>]+?(?:\.m3u8|\.mp4|storage\.googleapis\.com|vkvideo\.cloud|sssrr\.org|deovi\.mvapspdmpg\.com)[^\s"'<>]*""", RegexOption.IGNORE_CASE).find(body)?.value
                                ?: Regex("""https?://[^\s"'<>]+?(?:\.m3u8|\.mp4|storage\.googleapis\.com|vkvideo\.cloud|sssrr\.org|deovi\.mvapspdmpg\.com)[^\s"'<>]*""", RegexOption.IGNORE_CASE).find(reqUrl)?.value
                                ?: reqUrl

                            if (isValidStreamUrl(m3u8)) {
                                emitStream(m3u8, foundStream, browserUserAgent, reqUrl, callback)
                            }
                        }
                    },
                    "AndroidBridge"
                )

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        val js = """
                            (function() {
                                function processWindow(win) {
                                    try {
                                        const v = win.document.querySelector('video');
                                        if (v && v.paused) { v.muted = true; v.play(); }
                                        const btns = win.document.querySelectorAll('#overlay, [role="button"], .play-button, button, .play');
                                        for (let b of btns) { b.click(); }
                                        if (win.jwplayer && win.jwplayer().getPlaylist) {
                                            const pl = win.jwplayer().getPlaylist();
                                            if (pl && pl.length) {
                                                for (let item of pl) {
                                                    if (item.file && !item.file.startsWith('blob:')) window.AndroidBridge.onStreamFound(item.file, item.file);
                                                    if (item.allSources) {
                                                        for (let s of item.allSources) {
                                                            if (s.file && !s.file.startsWith('blob:')) window.AndroidBridge.onStreamFound(s.file, s.file);
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    } catch(e) {}
                                }

                                setInterval(() => {
                                    processWindow(window);
                                    try {
                                        const iframes = document.querySelectorAll('iframe');
                                        for (let ifr of iframes) {
                                            if (ifr.contentWindow) {
                                                processWindow(ifr.contentWindow);
                                            }
                                        }
                                    } catch(e) {}
                                }, 500);

                                const originalFetch = window.fetch;
                                window.fetch = async function(...args) {
                                    const response = await originalFetch.apply(this, args);
                                    try {
                                        const clone = response.clone();
                                        const text = await clone.text();
                                        if (text.includes('.m3u8') || text.includes('.mp4') || text.includes('storage.googleapis.com') || text.includes('vkvideo.cloud') || text.includes('sssrr.org') || text.includes('deovi.mvapspdmpg.com')) {
                                            window.AndroidBridge.onStreamFound(text, response.url);
                                        }
                                    } catch(e) {}
                                    return response;
                                };
                                
                                const originalXHR = window.XMLHttpRequest.prototype.open;
                                window.XMLHttpRequest.prototype.open = function(method, url, ...args) {
                                    this.addEventListener('load', function() {
                                        try {
                                            if (this.responseText && (this.responseText.includes('.m3u8') || this.responseText.includes('.mp4') || this.responseText.includes('storage.googleapis.com') || this.responseText.includes('vkvideo.cloud') || this.responseText.includes('sssrr.org') || this.responseText.includes('deovi.mvapspdmpg.com'))) {
                                                window.AndroidBridge.onStreamFound(this.responseText, url);
                                            }
                                        } catch(e) {}
                                    });
                                    return originalXHR.apply(this, [method, url, ...args]);
                                };

                                function scanRes() {
                                    try {
                                        const entries = performance.getEntriesByType('resource');
                                        for (let e of entries) {
                                            if (e.name && (e.name.includes('.m3u8') || e.name.includes('.mp4') || e.name.includes('storage.googleapis.com') || e.name.includes('vkvideo.cloud') || e.name.includes('sssrr.org') || e.name.includes('deovi.mvapspdmpg.com'))) {
                                                window.AndroidBridge.onStreamFound(e.name, e.name);
                                            }
                                        }
                                    } catch(e) {}
                                }
                                setInterval(scanRes, 1000);
                            })();
                        """.trimIndent()
                        view?.evaluateJavascript(js, null)
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val reqUrl = request?.url?.toString() ?: ""
                        if (isJunkUrl(reqUrl)) {
                            return WebResourceResponse("text/plain", "UTF-8", null)
                        }

                        if (isValidStreamUrl(reqUrl)) {
                            Log.d("SinemaTVWebView", "INTERCEPTED_REQ=$reqUrl")
                            emitStream(reqUrl, foundStream, browserUserAgent, reqUrl, callback)
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                }

                val wrapperHtml = """
                    <!DOCTYPE html>
                    <html>
                    <head><meta name="viewport" content="width=device-width, initial-scale=1.0"></head>
                    <body style="margin:0;padding:0;background:#000;">
                      <iframe id="plr" src="$targetUrl" style="width:100%;height:100vh;border:none;" allowfullscreen></iframe>
                    </body>
                    </html>
                """.trimIndent()

                val baseDomainUrl = try { URI(targetUrl).let { "${it.scheme}://${it.host}/" } } catch(_: Exception) { referer ?: "$mainUrl/" }
                loadDataWithBaseURL(baseDomainUrl, wrapperHtml, "text/html", "UTF-8", null)
            }
        }

        var elapsed = 0L
        while ((!foundStream.get()) && elapsed < 15000L) {
            delay(300L)
            elapsed += 300L
        }

        withContext(Dispatchers.Main) {
            try {
                webView?.destroy()
                webView = null
            } catch (e: Exception) {
                Log.e("SinemaTVWebView", "WEBVIEW_DESTROY_ERROR", e)
            }
        }
    }
}
