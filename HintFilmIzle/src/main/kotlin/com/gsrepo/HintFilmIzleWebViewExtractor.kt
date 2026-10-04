package com.gsrepo

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class HintFilmIzleWebViewExtractor(private val context: Context, private val pluginName: String) : ExtractorApi() {
    override val name = "HintFilmİzle WebView"
    override val mainUrl = "https://www.hintfilmizle.com"
    override val requiresReferer = true

    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d("HintFilmIzleWebView", "WEBVIEW_EXTRACTOR_START=$url")
        val foundStream = AtomicBoolean(false)
        val targetUrl = url

        withContext(Dispatchers.Main) {
            webView = WebView(context).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
                }

                addJavascriptInterface(object : Any() {
                    @JavascriptInterface
                    fun onStreamFound(body: String, reqUrl: String) {
                        Log.d("HintFilmIzleWebView", "BRIDGE_FOUND: reqUrl=$reqUrl, bodyLen=${body.length}")

                        val m3u8Content = if (body.startsWith("#EXTM3U")) {
                            body
                        } else {
                            Regex("https?://[^\"'\\s<>]+(?:\\.m3u8|/hls/|playlist|manifest)[^\"'\\s<>]*", RegexOption.IGNORE_CASE).find(body)?.value 
                                ?: Regex("https?://[^\"'\\s<>]+(?:\\.m3u8|/hls/|playlist|manifest)[^\"'\\s<>]*", RegexOption.IGNORE_CASE).find(reqUrl)?.value
                                ?: reqUrl
                        }

                        if (!foundStream.getAndSet(true)) {
                            val streamUrl = if (m3u8Content.startsWith("#EXTM3U")) {
                                val base64 = Base64.encodeToString(m3u8Content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                                "data:application/vnd.apple.mpegurl;base64,$base64"
                            } else {
                                m3u8Content
                            }

                            Log.d("HintFilmIzleWebView", "EMITTING_STREAM=$streamUrl")
                            GlobalScope.launch(Dispatchers.IO) {
                                callback.invoke(
                                    newExtractorLink(
                                        source = pluginName,
                                        name = pluginName,
                                        url = streamUrl,
                                        type = ExtractorLinkType.M3U8
                                    ) {
                                        this.quality = Qualities.P1080.value
                                        this.headers = mapOf("Referer" to "$mainUrl/", "Origin" to mainUrl)
                                    }
                                )
                            }
                        }
                    }
                }, "AndroidBridge")

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        val js = """
                            (function() {
                                function tryDecryptAndEmit(body, reqUrl) {
                                    try {
                                        if (typeof body === 'string' && body.indexOf('#EXTM3U') === 0) {
                                            window.AndroidBridge.onStreamFound(body, reqUrl);
                                            return true;
                                        }
                                        if (body && (typeof body === 'object' || (typeof body === 'string' && body.indexOf('"p"') > -1)) && window.decryptM3U8Content) {
                                            const parsed = typeof body === 'object' ? body : JSON.parse(body);
                                            const decrypted = window.decryptM3U8Content(parsed);
                                            if (decrypted && decrypted.indexOf('#EXTM3U') === 0) {
                                                window.AndroidBridge.onStreamFound(decrypted, reqUrl);
                                                return true;
                                            }
                                        }
                                    } catch(e) {}
                                    return false;
                                }

                                const origFetch = window.fetch;
                                if (origFetch) {
                                    window.fetch = async function(...args) {
                                        const resp = await origFetch.apply(this, args);
                                        try {
                                            const clone = resp.clone();
                                            const text = await clone.text();
                                            tryDecryptAndEmit(text, resp.url);
                                        } catch(e) {}
                                        return resp;
                                    };
                                }

                                const origXHR = window.XMLHttpRequest.prototype.open;
                                if (origXHR) {
                                    window.XMLHttpRequest.prototype.open = function(method, reqUrl, ...args) {
                                        this.addEventListener('load', function() {
                                            try {
                                                if (this.responseText) {
                                                    tryDecryptAndEmit(this.responseText, reqUrl);
                                                }
                                            } catch(e) {}
                                        });
                                        return origXHR.apply(this, [method, reqUrl, ...args]);
                                    };
                                }

                                setInterval(() => {
                                    try {
                                        const fp = document.querySelector('.fplayer');
                                        if (fp) fp.click();
                                        const btns = document.querySelectorAll('.cpp-switcher button, #singlePlay, [data-frame]');
                                        btns.forEach(b => b.click());

                                        const v = document.querySelector('video');
                                        if (v && v.src && v.src.indexOf('m3u8') > -1) {
                                            window.AndroidBridge.onStreamFound(v.src, v.src);
                                        }
                                    } catch(e) {}
                                }, 300);
                            })();
                        """.trimIndent()
                        view?.evaluateJavascript(js, null)
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val reqUrl = request?.url?.toString() ?: ""

                        if (reqUrl.endsWith(".m3u8", true) && !reqUrl.contains("/hls/", true)) {
                            Log.d("HintFilmIzleWebView", "INTERCEPTED_REQ=$reqUrl")
                            if (!foundStream.getAndSet(true)) {
                                GlobalScope.launch(Dispatchers.IO) {
                                    callback.invoke(
                                        newExtractorLink(
                                            source = pluginName,
                                            name = pluginName,
                                            url = reqUrl,
                                            type = ExtractorLinkType.M3U8
                                        ) {
                                            this.quality = Qualities.P1080.value
                                            this.headers = mapOf("Referer" to "$mainUrl/", "Origin" to mainUrl)
                                        }
                                    )
                                }
                            }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                }

                loadUrl(targetUrl, mapOf("Referer" to "$mainUrl/", "Origin" to mainUrl))
            }
        }

        var elapsed = 0L
        while (!foundStream.get() && elapsed < 12000L) {
            delay(200L)
            elapsed += 200L
        }

        withContext(Dispatchers.Main) {
            try {
                webView?.apply {
                    stopLoading()
                    loadUrl("about:blank")
                    clearHistory()
                    removeAllViews()
                    destroy()
                }
                webView = null
                Log.d("HintFilmIzleWebView", "WEBVIEW_EXTRACTOR_DESTROYED")
            } catch (e: Exception) {
                Log.e("HintFilmIzleWebView", "WEBVIEW_DESTROY_ERROR", e)
            }
        }
    }
}
