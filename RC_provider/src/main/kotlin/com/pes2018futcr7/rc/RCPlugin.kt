package com.davipassos.redecanais

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class RedeCanaisPlugin : Plugin() {
    override fun load(context: Context) {
        // Registra o provider no CloudStream
        registerMainAPI(RedeCanaisProvider())
    }
}
