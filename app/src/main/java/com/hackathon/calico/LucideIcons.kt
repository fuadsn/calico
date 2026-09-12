package com.hackathon.calico

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Lucide (ISC) drawn as Compose vectors: 24x24 viewport, 2px round stroke, no fill.
 * Stroke colour is a placeholder — Icon() tints the whole vector, so pass tint at the call site.
 * Circles, rects and lines from the source SVGs are written out as path data.
 */
private fun lucide(name: String, vararg paths: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        paths.forEach { d ->
            addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()

object Lucide {
    val Cat = lucide(
        "cat",
        "M12 5c.67 0 1.35.09 2 .26 1.78-2 5.03-2.84 6.42-2.26 1.4.58-.42 7-.42 7 .57 1.07 1 2.24 1 3.44C21 17.9 16.97 21 12 21s-9-3-9-7.56c0-1.25.5-2.4 1-3.44 0 0-1.89-6.42-.5-7 1.39-.58 4.72.23 6.5 2.23A9.04 9.04 0 0 1 12 5Z",
        "M8 14v.5",
        "M16 14v.5",
        "M11.25 16.25h1.5L12 17l-.75-.75Z",
    )
    val User = lucide(
        "user",
        "M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2",
        "M12 3a4 4 0 1 0 0 8 4 4 0 1 0 0-8Z",
    )
    val Hand = lucide(
        "hand",
        "M18 11V6a2 2 0 0 0-2-2a2 2 0 0 0-2 2",
        "M14 10V4a2 2 0 0 0-2-2a2 2 0 0 0-2 2v2",
        "M10 10.5V6a2 2 0 0 0-2-2a2 2 0 0 0-2 2v8",
        "M18 8a2 2 0 1 1 4 0v6a8 8 0 0 1-8 8h-2c-2.8 0-4.5-.86-5.99-2.34l-3.6-3.6a2 2 0 0 1 2.83-2.82L7 15",
    )
    val Zap = lucide(
        "zap",
        "M15.914 4a1.5 1.5 0 00-2.474-1.561l-9 9A1.5 1.5 0 005.5 14h4.002a.5.5 0 01.471.666L8.086 20a1.5 1.5 0 002.475 1.56l9-9A1.5 1.5 0 0018.5 10h-3.997a.5.5 0 01-.472-.667z",
    )
    val Flame = lucide(
        "flame",
        "M12 3q1 4 4 6.5t3 5.5a1 1 0 0 1-14 0 5 5 0 0 1 1-3 1 1 0 0 0 5 0c0-2-1.5-3-1.5-5q0-2 2.5-4",
    )
    val Lock = lucide(
        "lock",
        "M5 11h14a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2z",
        "M7 11V7a5 5 0 0 1 10 0v4",
    )
    val Check = lucide("check", "M20 6 9 17l-5-5")
    val ThumbsUp = lucide(
        "thumbs-up",
        "M15 5.88 14 10h5.83a2 2 0 0 1 1.92 2.56l-2.33 8A2 2 0 0 1 17.5 22H4a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2h2.76a2 2 0 0 0 1.79-1.11L12 2a3.13 3.13 0 0 1 3 3.88Z",
        "M7 10v12",
    )
    val Repeat = lucide(
        "repeat",
        "m17 2 4 4-4 4",
        "M3 11v-1a4 4 0 0 1 4-4h14",
        "m7 22-4-4 4-4",
        "M21 13v1a4 4 0 0 1-4 4H3",
    )
    val Timer = lucide(
        "timer",
        "M10 2h4",
        "M12 14 15 11",
        "M4 14a8 8 0 1 0 16 0a8 8 0 1 0-16 0",
    )
    val Dumbbell = lucide(
        "dumbbell",
        "M17.596 12.768a2 2 0 1 0 2.829-2.829l-1.768-1.767a2 2 0 0 0 2.828-2.829l-2.828-2.828a2 2 0 0 0-2.829 2.828l-1.767-1.768a2 2 0 1 0-2.829 2.829z",
        "m2.5 21.5 1.4-1.4",
        "m20.1 3.9 1.4-1.4",
        "M5.343 21.485a2 2 0 1 0 2.829-2.828l1.767 1.768a2 2 0 1 0 2.829-2.829l-6.364-6.364a2 2 0 1 0-2.829 2.829l1.768 1.767a2 2 0 0 0-2.828 2.829z",
        "m9.6 14.4 4.8-4.8",
    )
    val Footprints = lucide(
        "footprints",
        "M4 16v-2.38C4 11.5 2.97 10.5 3 8c.03-2.72 1.49-6 4.5-6C9.37 2 10 3.8 10 5.5c0 3.11-2 5.66-2 8.68V16a2 2 0 1 1-4 0Z",
        "M20 20v-2.38c0-2.12 1.03-3.12 1-5.62-.03-2.72-1.49-6-4.5-6C14.63 6 14 7.8 14 9.5c0 3.11 2 5.66 2 8.68V20a2 2 0 1 0 4 0Z",
        "M16 17h4",
        "M4 13h4",
    )
    val ChevronsUp = lucide("chevrons-up", "m17 11-5-5-5 5", "m17 18-5-5-5 5")
    val Stretch = lucide(
        "accessibility",
        "M15 4a1 1 0 1 0 2 0a1 1 0 1 0-2 0",
        "m18 19 1-7-6 1",
        "m5 8 3-3 5.5 3-2.36 3.5",
        "M4.24 14.5a5 5 0 0 0 6.88 6",
        "M13.76 17.5a5 5 0 0 0-6.88-6",
    )
}

/** Icon for each split, keyed on its title so the model layer stays free of Compose. */
fun splitIcon(title: String): ImageVector = when (title) {
    "Push" -> Lucide.Dumbbell
    "Legs" -> Lucide.Footprints
    "Core" -> Lucide.Flame
    "Cardio" -> Lucide.Zap
    "Pull" -> Lucide.ChevronsUp
    else -> Lucide.Stretch
}
