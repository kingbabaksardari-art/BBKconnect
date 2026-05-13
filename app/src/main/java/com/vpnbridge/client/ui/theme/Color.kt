package com.vpnbridge.client.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

internal val Primary = Color(0xFF2E7D32)
internal val PrimaryContainer = Color(0xFFA5D6A7)
internal val Secondary = Color(0xFF455A64)
internal val Error = Color(0xFFC62828)
internal val Surface = Color(0xFFF7F7F7)
internal val OnSurface = Color(0xFF1B1B1B)

internal val LightColors = lightColorScheme(
    primary = Primary,
    onPrimary = Color.White,
    primaryContainer = PrimaryContainer,
    onPrimaryContainer = Color(0xFF003912),
    secondary = Secondary,
    onSecondary = Color.White,
    error = Error,
    background = Color.White,
    onBackground = OnSurface,
    surface = Surface,
    onSurface = OnSurface,
)

internal val DarkColors = darkColorScheme(
    primary = Color(0xFF81C784),
    onPrimary = Color(0xFF003912),
    secondary = Color(0xFF90A4AE),
    error = Color(0xFFEF9A9A),
    background = Color(0xFF111315),
    onBackground = Color(0xFFE6E6E6),
    surface = Color(0xFF1A1C1E),
    onSurface = Color(0xFFE6E6E6),
)
