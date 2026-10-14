package ruido.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Warm colors only (no blue light), readable on a dim screen. */
object Night {
    val Black = Color(0xFF000000)
    val Background = Color(0xFF0E0A07)
    val Surface = Color(0xFF1C140E)
    val SurfaceHigh = Color(0xFF2A1E14)
    val Amber = Color(0xFFE3A15A)
    val AmberDim = Color(0xFF8A5A2A)
    val Text = Color(0xFFEADBC8)
    val TextDim = Color(0xFFB39C84)
    val Green = Color(0xFF9DBF6A)
    val Red = Color(0xFFE07A5F)
}

private val scheme = darkColorScheme(
    primary = Night.Amber,
    onPrimary = Night.Black,
    primaryContainer = Night.AmberDim,
    onPrimaryContainer = Night.Text,
    secondary = Night.Amber,
    onSecondary = Night.Black,
    secondaryContainer = Night.SurfaceHigh,
    onSecondaryContainer = Night.Text,
    background = Night.Background,
    onBackground = Night.Text,
    surface = Night.Background,
    onSurface = Night.Text,
    surfaceVariant = Night.SurfaceHigh,
    onSurfaceVariant = Night.TextDim,
    surfaceContainerLowest = Night.Background,
    surfaceContainerLow = Night.Surface,
    surfaceContainer = Night.Surface,
    surfaceContainerHigh = Night.SurfaceHigh,
    surfaceContainerHighest = Night.SurfaceHigh,
    outline = Night.AmberDim,
    outlineVariant = Night.SurfaceHigh,
    error = Night.Red,
)

private val base = Typography()
private val typography = base.copy(
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 18.sp),
    bodyLarge = base.bodyLarge.copy(fontSize = 17.sp),
    bodyMedium = base.bodyMedium.copy(fontSize = 15.sp),
)

@Composable
fun RuidoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
