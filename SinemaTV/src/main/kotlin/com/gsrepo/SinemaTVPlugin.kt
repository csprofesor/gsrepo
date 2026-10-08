package com.gsrepo

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import java.lang.ref.WeakReference

@CloudstreamPlugin
class SinemaTVPlugin: Plugin() {
    companion object {
        private var pluginContextRef: WeakReference<Context>? = null

        var pluginContext: Context?
            get() = pluginContextRef?.get()
            set(value) {
                pluginContextRef = value?.let { WeakReference(it) }
            }
    }

    override fun load(context: Context) {
        pluginContext = context
        registerMainAPI(SinemaTV(context))
        registerExtractorAPI(SinemaTVWebViewExtractor(context, "SinemaTV"))
    }
}
