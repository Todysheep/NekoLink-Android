package app.nekolink.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Light grey-blue visual language (align Windows ADR 0010 / monorepo 0016).
private val BlueGrey50 = Color(0xFFF4F7FA)
private val BlueGrey100 = Color(0xFFE8EEF5)
private val BlueGrey200 = Color(0xFFD0DCE8)
private val BluePrimary = Color(0xFF3B6FA0)
private val BluePrimaryDark = Color(0xFF2A5278)
private val OnPrimary = Color(0xFFFFFFFF)
private val Surface = Color(0xFFFFFFFF)
private val OnSurface = Color(0xFF1C2430)
private val Error = Color(0xFFB3261E)

private val LightColors = lightColorScheme(
    primary = BluePrimary,
    onPrimary = OnPrimary,
    primaryContainer = BlueGrey100,
    onPrimaryContainer = BluePrimaryDark,
    secondary = BluePrimaryDark,
    background = BlueGrey50,
    onBackground = OnSurface,
    surface = Surface,
    onSurface = OnSurface,
    surfaceVariant = BlueGrey100,
    outline = BlueGrey200,
    error = Error,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8BB4D9),
    onPrimary = Color(0xFF0D1B2A),
    background = Color(0xFF121820),
    surface = Color(0xFF1A222C),
    onBackground = Color(0xFFE8EEF5),
    onSurface = Color(0xFFE8EEF5),
)

@Composable
fun NekoTheme(content: @Composable () -> Unit) {
    // Product default: light grey-blue; still respect system dark if forced.
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content,
    )
}
