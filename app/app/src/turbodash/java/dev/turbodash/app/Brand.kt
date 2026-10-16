package dev.turbodash.app

import androidx.compose.ui.graphics.Color

/** Look and defaults of this build (white label). Each product flavor has its own copy. */
object Brand {
    const val name = "TurboDash"
    const val maker: String = ""
    const val demo = false
    val accent = Color(0xFFFF9F1C)
    val warn = Color(0xFFFFBF00)
    val background = Color(0xFF0E1116)
    val surface = Color(0xFF171B22)
    val surfaceHigh = Color(0xFF1F252E)
    val track = Color(0xFF2B323D)
    /** Logo for dark backgrounds, or null. */
    val logo: Int? = null
    /** ECU profile shipped inside the app (assets/profile), loaded on first run; null = user imports it. */
    val bundledSymbols: Set<String>? = null
}
