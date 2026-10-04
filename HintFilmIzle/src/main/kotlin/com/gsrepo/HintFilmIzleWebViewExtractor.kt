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
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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

                        if (!foundStream.getAndSet(true)) {
                            val streamUrl = if (body.startsWith("#EXTM3U")) {
                                val base64 = Base64.encodeToString(body.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                                "data:application/vnd.apple.mpegurl;base64,$base64"
                            } else {
                                body
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

                        if (reqUrl.contains("embed.js", true)) {
                            Log.d("HintFilmIzleWebView", "INTERCEPTING_EMBED_JS=$reqUrl")
                            runCatching {
                                val originalJs = runBlocking {
                                    app.get(reqUrl, headers = mapOf("Referer" to "$mainUrl/", "Origin" to mainUrl)).text
                                }
                                val injection = """
                                    ;(function() {
                                        console.log('>>> INJECTED HOOK INSIDE EMBED.JS IN IFRAME <<<');
                                        var emitted = false;

                                        function checkAndEmit(body, url) {
                                            if (emitted) return;
                                            try {
                                                if (typeof body === 'string' && body.indexOf('#EXTM3U') === 0) {
                                                    emitted = true;
                                                    window.AndroidBridge.onStreamFound(body, url);
                                                    return;
                                                }
                                                if (body && (typeof body === 'object' || (typeof body === 'string' && body.indexOf('"p"') > -1)) && window.decryptM3U8Content) {
                                                    var parsed = typeof body === 'object' ? body : JSON.parse(body);
                                                    var decrypted = window.decryptM3U8Content(parsed);
                                                    if (decrypted && decrypted.indexOf('#EXTM3U') === 0) {
                                                        emitted = true;
                                                        window.AndroidBridge.onStreamFound(decrypted, url);
                                                        return;
                                                    }
                                                }
                                            } catch(e) {}
                                        }

                                        var origFetch = window.fetch;
                                        if (origFetch) {
                                            window.fetch = async function(...args) {
                                                var resp = await origFetch.apply(this, args);
                                                try {
                                                    var clone = resp.clone();
                                                    var text = await clone.text();
                                                    checkAndEmit(text, resp.url);
                                                } catch(e) {}
                                                return resp;
                                            };
                                        }

                                        var origXHR = window.XMLHttpRequest.prototype.open;
                                        if (origXHR) {
                                            window.XMLHttpRequest.prototype.open = function(method, url, ...args) {
                                                this.addEventListener('load', function() {
                                                    try {
                                                        if (this.responseText) {
                                                            checkAndEmit(this.responseText, url);
                                                        }
                                                    } catch(e) {}
                                                });
                                                return origXHR.apply(this, [method, url, ...args]);
                                            };
                                        }

                                        setInterval(function() {
                                            if (emitted) return;
                                            try {
                                                if (window.pljssglobal && window.pljssglobal.length > 0) {
                                                    for (var i = 0; i < window.pljssglobal.length; i++) {
                                                        var inst = window.pljssglobal[i];
                                                        if (inst && inst.api) {
                                                            var file = inst.api('file');
                                                            if (file && typeof file === 'string' && file.indexOf('.m3u8') > -1) {
                                                                var matches = file.match(/https?:\/\/[^"',\s]+\.m3u8[^"',\s]*/g);
                                                                if (matches && matches.length > 0) {
                                                                    var targetUrl = matches[matches.length - 1];
                                                                    fetch(targetUrl).then(function(r){ return r.json(); }).then(function(json){
                                                                        if (window.decryptM3U8Content) {
                                                                            var dec = window.decryptM3U8Content(json);
                                                                            if (dec && dec.indexOf('#EXTM3U') === 0) {
                                                                                emitted = true;
                                                                                window.AndroidBridge.onStreamFound(dec, targetUrl);
                                                                            }
                                                                        }
                                                                    }).catch(function(e){});
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            } catch(e) {}
                                        }, 200);
                                    })();
                                """.trimIndent()

                                val modifiedJs = "$originalJs\n$injection"
                                return WebResourceResponse(
                                    "application/javascript",
                                    "UTF-8",
                                    modifiedJs.byteInputStream(Charsets.UTF_8)
                                )
                            }
                        }

                        if (reqUrl.contains(".m3u8", true) || reqUrl.contains("/hls/", true)) {
                            Log.d("HintFilmIzleWebView", "INTERCEPTED_REQ=$reqUrl")
                            GlobalScope.launch(Dispatchers.IO) {
                                runCatching {
                                    val respText = app.get(reqUrl, headers = mapOf("Referer" to "$mainUrl/", "Origin" to mainUrl)).text
                                    if (respText.startsWith("#EXTM3U")) {
                                        if (!foundStream.getAndSet(true)) {
                                            val base64 = Base64.encodeToString(respText.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                                            val dataUri = "data:application/vnd.apple.mpegurl;base64,$base64"
                                            Log.d("HintFilmIzleWebView", "EMITTING_DIRECT_EXTM3U_STREAM=$reqUrl")
                                            callback.invoke(
                                                newExtractorLink(
                                                    source = pluginName,
                                                    name = pluginName,
                                                    url = dataUri,
                                                    type = ExtractorLinkType.M3U8
                                                ) {
                                                    this.quality = Qualities.P1080.value
                                                    this.headers = mapOf("Referer" to "$mainUrl/", "Origin" to mainUrl)
                                                }
                                            )
                                        }
                                    }
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
        while (!foundStream.get() && elapsed < 15000L) {
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
