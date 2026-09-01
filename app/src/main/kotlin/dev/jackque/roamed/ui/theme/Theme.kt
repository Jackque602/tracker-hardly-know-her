package dev.jackque.roamed.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Teal = Color(0xFF0F8A7E)
private val TealLight = Color(0xFF7FD1C1)
private val Amber = Color(0xFFE0912A)
private val Ink = Color(0xFF102A43)

private val LightColors = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    secondary = Amber,
    onSecondary = Color.White,
    background = Color(0xFFF7F5F2),
    surface = Color(0xFFFFFFFF),
    onBackground = Ink,
    onSurface = Ink,
)

private val DarkColors = darkColorScheme(
    primary = TealLight,
    onPrimary = Color(0xFF00382F),
    secondary = Color(0xFFFFC46B),
    onSecondary = Color(0xFF3D2600),
    background = Color(0xFF0E1116),
    surface = Color(0xFF161A21),
    onBackground = Color(0xFFE6EAF0),
    onSurface = Color(0xFFE6EAF0),
)

@Composable
fun RoamedTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
