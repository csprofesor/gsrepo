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
import com.lagradost.cloudstream3.*
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
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class SinemaTvAzWebViewExtractor(private val context: Context) : ExtractorApi() {
    override val name = "SinemaTvAz Özel"
    override val mainUrl = "https://sinematv.az"
    override val requiresReferer = true

    private var webView: WebView? = null
    private val emittedUrls: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    private fun isJunkUrl(url: String): Boolean {
        val lower = url.lowercase()
        val path = lower.substringBefore("?").substringBefore("#")

        if (path.endsWith(".m3u8") ||
            path.endsWith(".mp4") ||
            lower.contains(".m3u8") ||
            lower.contains("storage.googleapis.com") ||
            lower.contains("vkvideo.cloud") ||
            lower.contains("vk.com") ||
            lower.contains("vk.ru")
        ) {
            return false
        }

        if (lower.contains("google-analytics") ||
            lower.contains("googletagmanager") ||
            lower.contains("google.com/g/collect") ||
            lower.contains("yandex") ||
            lower.contains("mc.yandex") ||
            lower.contains("metrika") ||
            lower.contains("doubleclick") ||
            lower.contains("facebook") ||
            lower.contains("pixel.morphify") ||
            lower.contains("kkkkkkkrrrrrrrr") ||
            lower.contains("decafeligiblyhad") ||
            lower.contains("yaropolka.link") ||
            lower.contains("pepyakanew.link") ||
            lower.contains("rude-movie.com") ||
            lower.contains("/contents/") ||
            lower.contains("/user-stats/") ||
            lower.contains("player-metrics") ||
            lower.contains("gtag") ||
            lower.contains("favicon") ||
            lower.contains("blank.mp4") ||
            lower.contains("dummy.mp4") ||
            lower.contains("empty.mp4")
        ) {
            return true
        }

        if (path.endsWith(".js") ||
            path.endsWith(".css") ||
            path.endsWith(".png") ||
            path.endsWith(".jpg") ||
            path.endsWith(".jpeg") ||
            path.endsWith(".gif") ||
            path.endsWith(".webp") ||
            path.endsWith(".svg") ||
            path.endsWith(".ico")
        ) {
            return true
        }

        return false
    }

    @SuppressLint("SetJavaScriptEnabled")
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val defaultUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36"
        val foundStream = AtomicBoolean(false)

        fun getHeadersForStream(streamUrl: String): Map<String, String> {
            val lower = streamUrl.lowercase()
            val streamReferer = when {
                lower.contains("vkvideo") || lower.contains("vk.com") || lower.contains("vk.ru") -> "https://vk.com/"
                lower.contains("storage.googleapis.com") -> "https://cdn1.sinematv.az/"
                lower.contains("abyss.to") -> "https://abyss.to/"
                lower.contains("cdn2.sinematv.az") -> "https://cdn2.sinematv.az/"
                lower.contains("cdn.sinematv.az") || lower.contains("cdn1.sinematv.az") -> "https://sinematv.az/"
                else -> referer ?: "$mainUrl/"
            }
            return mapOf(
                "User-Agent" to defaultUserAgent,
                "Referer" to streamReferer
            )
        }

        fun emitStream(streamUrl: String) {
            var fixStream = streamUrl
            if (fixStream.startsWith("//")) {
                fixStream = "https:$fixStream"
            }

            if (isJunkUrl(fixStream)) {
                return
            }

            if (!emittedUrls.add(fixStream)) {
                return
            }

            if (!fixStream.startsWith("http://") && !fixStream.startsWith("https://")) {
                return
            }

            Log.d("SinemaTvAzWebView", "EMITTING_STREAM=$fixStream")

            CoroutineScope(Dispatchers.IO).launch {
                val lower = fixStream.lowercase()
                val path = fixStream.substringBefore("?").lowercase()

                if (lower.contains("parsed.json") || lower.contains("catalog-api") || lower.contains("balancer-api") || lower.contains("proxy/playlists") || lower.contains("vv-api.php") || lower.contains("api/v1/player")) {
                    try {
                        val jsonStr = app.get(fixStream, headers = getHeadersForStream(fixStream)).text
                        val streamUrls = Regex("https?://[^\"'\\s<>]+?\\.(?:m3u8|mp4)(?:\\?[^\"'\\s<>]*)?", RegexOption.IGNORE_CASE)
                            .findAll(jsonStr)
                            .map { it.value }
                            .distinct()
                            .toList()

                        for (sUrl in streamUrls) {
                            emitStream(sUrl)
                        }
                    } catch (e: Exception) {
                        Log.e("SinemaTvAzWebView", "API JSON fetch failed for $fixStream", e)
                    }
                } else if (path.endsWith(".m3u8") || path.endsWith(".mp4") || lower.contains(".m3u8") || lower.contains("storage.googleapis.com") || lower.contains("vkvideo.cloud") || lower.contains("playlist")) {
                    foundStream.set(true)
                    callback.invoke(
                        newExtractorLink(
                            source = "SinemaTvAzWebView",
                            name = "SinemaTvAz",
                            url = fixStream,
                            type = if (path.endsWith(".mp4") || lower.contains("storage.googleapis.com")) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8,
                        ) {
                            this.quality = Qualities.Unknown.value
                            this.headers = getHeadersForStream(fixStream)
                        }
                    )
                }
            }
        }

        val finalUrl = if (url.startsWith("http")) url else "$mainUrl${if (url.startsWith("/")) "" else "/"}$url"

        withContext(Dispatchers.Main) {
            webView = WebView(context).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    userAgentString = defaultUserAgent
                }

                addJavascriptInterface(object : Any() {
                    @JavascriptInterface
                    fun onStreamFound(body: String, reqUrl: String) {
                        Log.d("SinemaTvAzWebView", "BRIDGE_FOUND: $reqUrl")
                        val urls = Regex("https?://[^\"'\\s<>]+(?:\\.m3u8(?:\\?[^\"',\\s<>]*)?|\\.mp4(?:\\?[^\"',\\s<>]*)?|parsed\\.json(?:\\?[^\"',\\s<>]*)?)", RegexOption.IGNORE_CASE)
                            .findAll("$body $reqUrl")
                            .map { it.value }
                            .distinct()
                            .toList()

                        for (stream in urls) {
                            emitStream(stream)
                        }
                    }
                }, "AndroidBridge")

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        val js = """
                            (function() {
                                function autoPlay() {
                                    try {
                                        const docs = [document];
                                        const iframes = document.querySelectorAll('iframe');
                                        iframes.forEach(f => {
                                            try {
                                                const ds = f.getAttribute('data-src');
                                                if (ds && (!f.src || f.src === 'about:blank')) {
                                                    f.src = ds;
                                                }
                                                if (f.contentDocument) docs.push(f.contentDocument);
                                                else if (f.contentWindow && f.contentWindow.document) docs.push(f.contentWindow.document);
                                            } catch(e) {}
                                        });

                                        docs.forEach(d => {
                                            const ov = d.getElementById('overlay');
                                            if (ov) {
                                                try { ov.click(); } catch(e) {}
                                                try { ov.remove(); } catch(e) {}
                                            }

                                            const win = d.defaultView || window;
                                            if (win) {
                                                if (typeof win.jwplayer !== 'undefined') {
                                                    try {
                                                        const p = win.jwplayer();
                                                        if (p) {
                                                            if (typeof p.play === 'function' && p.getState && p.getState() !== 'playing') {
                                                                p.play();
                                                            }
                                                            if (p.getConfig) {
                                                                const cfg = p.getConfig();
                                                                if (cfg) {
                                                                    if (cfg.file) window.AndroidBridge.onStreamFound(cfg.file, cfg.file);
                                                                    if (cfg.playlist && cfg.playlist[0] && cfg.playlist[0].file) {
                                                                        window.AndroidBridge.onStreamFound(cfg.playlist[0].file, cfg.playlist[0].file);
                                                                    }
                                                                    if (cfg.sources) {
                                                                        cfg.sources.forEach(s => {
                                                                            if (s.file) window.AndroidBridge.onStreamFound(s.file, s.file);
                                                                        });
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    } catch(e) {}
                                                }

                                                if (typeof win.player !== 'undefined' && win.player.config) {
                                                    if (win.player.config.url) window.AndroidBridge.onStreamFound(win.player.config.url, win.player.config.url);
                                                    if (win.player.config.manifestUrl) window.AndroidBridge.onStreamFound(win.player.config.manifestUrl, win.player.config.manifestUrl);
                                                }
                                            }

                                            try {
                                                const html = d.documentElement.innerHTML;
                                                const matches = html.match(/https?:\/\/[^\"'\s<>]+?\.(?:m3u8|mp4)(?:\?[^\"'\s<>]*)?/gi);
                                                if (matches) {
                                                    matches.forEach(m => window.AndroidBridge.onStreamFound(m, m));
                                                }
                                            } catch(e) {}
                                        });
                                    } catch(e) {}
                                }

                                const originalFetch = window.fetch;
                                window.fetch = async function(...args) {
                                    const response = await originalFetch.apply(this, args);
                                    try {
                                        const clone = response.clone();
                                        const text = await clone.text();
                                        if (text.includes('m3u8') || text.includes('playlist') || text.includes('mp4') || text.includes('manifest')) {
                                            window.AndroidBridge.onStreamFound(text, response.url);
                                        }
                                    } catch(e) {}
                                    return response;
                                };
                                
                                const originalXHR = window.XMLHttpRequest.prototype.open;
                                window.XMLHttpRequest.prototype.open = function(method, url, ...args) {
                                    this.addEventListener('load', function() {
                                        try {
                                            if (this.responseText && (this.responseText.includes('m3u8') || this.responseText.includes('playlist') || this.responseText.includes('mp4') || this.responseText.includes('manifest'))) {
                                                window.AndroidBridge.onStreamFound(this.responseText, url);
                                            }
                                        } catch(e) {}
                                    });
                                    return originalXHR.apply(this, [method, url, ...args]);
                                };

                                setInterval(autoPlay, 200);
                            })();
                        """.trimIndent()
                        evaluateJavascript(js, null)
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): WebResourceResponse? {
                        val reqUrl = request?.url?.toString() ?: ""

                        if (!isJunkUrl(reqUrl)) {
                            val path = reqUrl.substringBefore("?").lowercase()
                            if (path.endsWith(".m3u8") ||
                                path.endsWith(".mp4") ||
                                path.contains("master.m3u8") ||
                                path.contains("index.m3u8") ||
                                path.contains("playlist") ||
                                path.contains("manifest") ||
                                reqUrl.contains("storage.googleapis.com") ||
                                reqUrl.contains("vkvideo.cloud") ||
                                reqUrl.contains("parsed.json") ||
                                reqUrl.contains("catalog-api") ||
                                reqUrl.contains("balancer-api") ||
                                reqUrl.contains("proxy/playlists") ||
                                reqUrl.contains("vv-api.php") ||
                                reqUrl.contains("api/v1/player")
                            ) {
                                emitStream(reqUrl)
                            }
                        }

                        return super.shouldInterceptRequest(view, request)
                    }
                }

                loadUrl(finalUrl, mapOf("Referer" to (referer ?: "$mainUrl/")))
            }
        }

        var elapsed = 0L
        while (elapsed < 15000L) {
            delay(300L)
            elapsed += 300L
            if (foundStream.get() && elapsed >= 2000L) {
                break
            }
        }

        withContext(Dispatchers.Main) {
            try {
                webView?.destroy()
                webView = null
            } catch (_: Exception) {
                // Ignore
            }
        }
    }
}
