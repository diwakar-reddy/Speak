package com.apps.dsimpletools.speak.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val SpeakBlue = Color(0xFF2962FF)
private val SpeakRed = Color(0xFFE53935)
private val SpeakOrange = Color(0xFFFB8C00)

private val LightColors = lightColorScheme(
    primary = SpeakBlue,
    secondary = SpeakOrange,
    tertiary = SpeakRed
)

private val DarkColors = darkColorScheme(
    primary = SpeakBlue,
    secondary = SpeakOrange,
    tertiary = SpeakRed
)

@Composable
fun SpeakTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
