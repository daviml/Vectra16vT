package dev.turbodash.app

import androidx.compose.ui.graphics.Color

/**
 * Costa Mega Speed demo build (private pitch). Colors from costamegaspeed.com.br:
 * yellow #FFC94F on graphite.
 */
object Brand {
    const val name = "CMS Dash"
    const val maker = "Costa Mega Speed"
    const val demo = true
    val accent = Color(0xFFFFC94F)
    // Alerts in orange so they never look like the brand yellow.
    val warn = Color(0xFFFF8A1F)
    val background = Color(0xFF111111)
    val surface = Color(0xFF1C1C1C)
    val surfaceHigh = Color(0xFF262626)
    val track = Color(0xFF353535)
    val logo: Int? = R.drawable.brand_logo
    // MegaSpeed firmware INI shipped in the app: the customer only pairs Bluetooth.
    val bundledSymbols: Set<String>? = setOf("CELSIUS", "LAMBDA", "PORT_STATUS")
}
