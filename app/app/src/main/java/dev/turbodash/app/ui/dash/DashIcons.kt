package dev.turbodash.app.ui.dash

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Line icons (24-unit grid, round strokes) that tell the main-page tiles apart at a glance. */
object DashIcons {
    val water = icon(
        "M12 2.8C12 2.8 5.2 10.4 5.2 15A6.8 6.8 0 0 0 18.8 15C18.8 10.4 12 2.8 12 2.8Z",
        "M8.6 15.6c1.1 0.9 2.3 0.9 3.4 0s2.3 -0.9 3.4 0",
    )
    val air = icon("M3 8.5h10.5a3 3 0 1 0 -3 -3", "M3 12.5h15a3 3 0 1 1 -3 3", "M3 16.5h6.5")
    val gauge = icon(
        "M4.3 17.5a8.6 8.6 0 1 1 15.4 0",
        "M12 14.3l4.3 -5",
        "M10.5 14.3a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0 -3 0",
        "M6.4 11.6l1.2 0.5M12 6.4v1.3M17.6 11.6l-1.2 0.5",
    )
    val fuel = icon(
        "M5 20.5V5a2 2 0 0 1 2 -2h6a2 2 0 0 1 2 2v15.5",
        "M3.5 20.5h13",
        "M8.3 6.5h3.4a0.8 0.8 0 0 1 0.8 0.8v2.4a0.8 0.8 0 0 1 -0.8 0.8h-3.4a0.8 0.8 0 0 1 -0.8 -0.8v-2.4a0.8 0.8 0 0 1 0.8 -0.8Z",
        "M15 9.5h1.5a1.5 1.5 0 0 1 1.5 1.5v5a1.5 1.5 0 0 0 3 0V8.2L18.5 5.7",
    )
    val battery = icon(
        "M4.8 7.5h14.4a2 2 0 0 1 2 2v8a2 2 0 0 1 -2 2H4.8a2 2 0 0 1 -2 -2v-8a2 2 0 0 1 2 -2Z",
        "M6.5 7.5V5.3h3v2.2M14.5 7.5V5.3h3v2.2",
        "M6.4 13.5h3.2M8 11.9v3.2M14.4 13.5h3.2",
    )
    val pedal = icon(
        "M9.2 3.6l6.6 1.4a1.6 1.6 0 0 1 1.2 1.9l-2.6 11.6a1.6 1.6 0 0 1 -1.9 1.2L5.9 18.3a1.6 1.6 0 0 1 -1.2 -1.9L7.3 4.8a1.6 1.6 0 0 1 1.9 -1.2Z",
        "M8.6 8.2l6 1.3M7.9 11.4l6 1.3M7.2 14.6l6 1.3",
        "M4 21.5h16",
    )

    private fun icon(vararg paths: String): ImageVector =
        ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            paths.forEach {
                addPath(
                    addPathNodes(it), fill = null, stroke = SolidColor(Color.White), strokeLineWidth = 1.9f,
                    strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()
}
