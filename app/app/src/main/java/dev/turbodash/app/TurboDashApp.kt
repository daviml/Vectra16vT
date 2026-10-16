package dev.turbodash.app

import android.app.Application
import dev.turbodash.app.data.ProfileStore
import dev.turbodash.app.ecu.SessionManager

class TurboDashApp : Application() {

    lateinit var store: ProfileStore
        private set
    lateinit var sessions: SessionManager
        private set
    lateinit var nav: dev.turbodash.app.nav.Navigation
        private set

    override fun onCreate() {
        super.onCreate()
        store = ProfileStore(this)
        loadBundledProfile()
        sessions = SessionManager(this, store)
        nav = dev.turbodash.app.nav.Navigation(this, sessions.gps)
        Thread { runCatching { sessions.refreshProfile() } }.start()
    }

    /** Brands that ship their firmware INI (and a sample calibration) need no import on first run. */
    private fun loadBundledProfile() {
        val symbols = Brand.bundledSymbols ?: return
        if (store.hasIni) return
        runCatching {
            store.symbols = symbols
            store.installBundled(
                assets.open("profile/mainController.ini").use { it.readBytes() },
                runCatching { assets.open("profile/tune.msq").use { it.readBytes() } }.getOrNull(),
            )
        }
    }
}
