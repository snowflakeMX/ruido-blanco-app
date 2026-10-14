package ruido.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Warm and dim colors only: no blue light at night. */
object Night {
    val Black = Color(0xFF000000)
    val Surface = Color(0xFF140D08)
    val SurfaceHigh = Color(0xFF21160D)
    val Amber = Color(0xFFC98236)
    val AmberDim = Color(0xFF5E3A16)
    val Text = Color(0xFFBFA88C)
    val TextDim = Color(0xFF7A6652)
    /** For the night screen: barely visible on a dark-adapted eye. */
    val Ember = Color(0xFF4A1E0C)
    val Red = Color(0xFFB0472E)
}

private val scheme = darkColorScheme(
    primary = Night.Amber,
    onPrimary = Night.Black,
    primaryContainer = Night.AmberDim,
    onPrimaryContainer = Night.Text,
    secondary = Night.Amber,
    onSecondary = Night.Black,
    background = Night.Black,
    onBackground = Night.Text,
    surface = Night.Surface,
    onSurface = Night.Text,
    surfaceVariant = Night.SurfaceHigh,
    onSurfaceVariant = Night.TextDim,
    surfaceContainer = Night.Surface,
    surfaceContainerHigh = Night.SurfaceHigh,
    surfaceContainerHighest = Night.SurfaceHigh,
    outline = Night.AmberDim,
    outlineVariant = Night.SurfaceHigh,
    error = Night.Red,
)

@Composable
fun RuidoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
