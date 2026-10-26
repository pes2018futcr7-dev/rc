package com.pes2018futcr7.rc

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class RCPlugin : Plugin() {
    override fun load(context: Context) {
        // Registra o provider no CloudStream
        registerMainAPI(RCProvider())
    }
}
