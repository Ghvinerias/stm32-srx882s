package com.eelab.rflab.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LabGreen = Color(0xFF2E7D32)
private val LabAmber = Color(0xFFFFB300)

private val LightColors = lightColorScheme(
    primary = LabGreen,
    secondary = Color(0xFF546E7A),
    tertiary = LabAmber,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF81C784),
    secondary = Color(0xFF90A4AE),
    tertiary = LabAmber,
)

@Composable
fun RfLabTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
