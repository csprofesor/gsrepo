package com.gsrepo

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class TavsiyeFilmIzlePlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(TavsiyeFilmIzle())
        registerExtractorAPI(HotStream())
    }
}
