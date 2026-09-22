package dev.ironlog.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Dark palette: slate surfaces, a vivid blue accent, green for "done". */
object IronlogColors {
    val Blue = Color(0xFF3D9BFF)
    val Green = Color(0xFF43C463)
    val Red = Color(0xFFFF5A5F)  // swipe-to-delete background
    val Background = Color(0xFF15191E)
    val Surface = Color(0xFF1E242B)
    val SurfaceVariant = Color(0xFF2A323B)
    val Gray = Color(0xFF9BA6B2)
}

private val IronlogDarkColors = darkColorScheme(
    primary = IronlogColors.Blue,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF123A5F),
    onPrimaryContainer = Color(0xFFCFE6FF),
    secondary = Color(0xFF5AA9FF),
    onSecondary = Color(0xFF00254A),
    background = IronlogColors.Background,
    onBackground = Color(0xFFE7ECF1),
    surface = IronlogColors.Surface,
    onSurface = Color(0xFFE7ECF1),
    surfaceVariant = IronlogColors.SurfaceVariant,
    onSurfaceVariant = IronlogColors.Gray,
    outline = Color(0xFF3A434D),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF000000),
)

@Composable
fun IronlogTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = IronlogDarkColors, content = content)
}
