package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val RahYabDarkColorScheme = darkColorScheme(
    primary = CyanBeacon,
    onPrimary = CockpitMidnight,
    primaryContainer = CyanBeaconContainerDark,
    onPrimaryContainer = Color(0xFFB3FAF4),
    secondary = CompassGold,
    onSecondary = CockpitMidnight,
    secondaryContainer = CompassGoldContainerDark,
    onSecondaryContainer = CompassGoldContainerLight,
    tertiary = RoutePrimaryBlue,
    onTertiary = CockpitMidnight,
    background = CockpitMidnight,
    onBackground = NightOnSurface,
    surface = CockpitSurfaceDark,
    onSurface = NightOnSurface,
    surfaceVariant = CockpitSurfaceElevated,
    onSurfaceVariant = NightOnSurfaceMuted,
    outline = CockpitOutlineDark,
    error = AlertRedDark,
    onError = Color.White,
    errorContainer = Color(0xFF5C1111),
    onErrorContainer = Color(0xFFFFDAD6)
)

private val RahYabLightColorScheme = lightColorScheme(
    primary = CyanBeaconDark,
    onPrimary = Color.White,
    primaryContainer = CyanBeaconContainerLight,
    onPrimaryContainer = Color(0xFF002B28),
    secondary = CompassGoldDark,
    onSecondary = Color.White,
    secondaryContainer = CompassGoldContainerLight,
    onSecondaryContainer = Color(0xFF332500),
    tertiary = RoutePrimaryBlue,
    onTertiary = Color.White,
    background = DaylightBackground,
    onBackground = DaylightOnSurface,
    surface = DaylightSurface,
    onSurface = DaylightOnSurface,
    surfaceVariant = DaylightSurfaceVariant,
    onSurfaceVariant = DaylightOnSurfaceMuted,
    outline = DaylightOutline,
    error = AlertRed,
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002)
)

@Composable
fun RahYabTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    isPersian: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) RahYabDarkColorScheme else RahYabLightColorScheme
    val typography = buildRahYabTypography(isPersian = isPersian)

    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography,
        content = content
    )
}
