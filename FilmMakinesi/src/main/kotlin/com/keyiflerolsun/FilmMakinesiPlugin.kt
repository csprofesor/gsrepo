package com.keyiflerolsun

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class FilmMakinesiPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(FilmMakinesi())

        registerExtractorAPI(CloseLoadExtractor())
        registerExtractorAPI(CloseLoadTo())
        registerExtractorAPI(CloseLoadFilm())
        registerExtractorAPI(CloseLoadDe())
        registerExtractorAPI(CloseLoadTv())
        registerExtractorAPI(CloseLoadSh())
        registerExtractorAPI(CloseLoadCom())
        registerExtractorAPI(CloseLoadNet())
        registerExtractorAPI(CloseLoadOrg())

        registerExtractorAPI(RapidExtractor())
        registerExtractorAPI(RapidTo())
        registerExtractorAPI(RapidFilm())
        registerExtractorAPI(RapidDe())
        registerExtractorAPI(RapidTv())
        registerExtractorAPI(RapidSh())
        registerExtractorAPI(RapidNet())
        registerExtractorAPI(RapidCom())
    }
}
