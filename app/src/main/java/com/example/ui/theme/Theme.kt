package com.example.ui.theme

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

private val DarkColorScheme = darkColorScheme(
    primary = FuelAmber,
    onPrimary = OnFuelAmber,
    primaryContainer = FuelAmberContainer,
    onPrimaryContainer = FuelAmber,
    secondary = PetrolNavy,
    onSecondary = DarkOnSurface,
    secondaryContainer = PetrolSurfaceVariant,
    onSecondaryContainer = DarkOnSurface,
    tertiary = StationEmerald,
    onTertiary = Color.White,
    background = PetrolDeep,
    onBackground = DarkOnSurface,
    surface = PetrolSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = PetrolSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = PetrolOutline,
    error = StationRed,
    onError = Color.White,
)

private val LightColorScheme = lightColorScheme(
    primary = FuelAmberDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFECC2),
    onPrimaryContainer = Color(0xFF2B1A00),
    secondary = PetrolNavy,
    onSecondary = Color.White,
    secondaryContainer = LightSurfaceVariant,
    onSecondaryContainer = LightOnSurface,
    tertiary = StationEmerald,
    onTertiary = Color.White,
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = Color(0xFF9FB2C7),
    error = StationRed,
    onError = Color.White,
)

@Composable
fun SpbuSimulatorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme =
        when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val context = LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }
            darkTheme -> DarkColorScheme
            else -> LightColorScheme
        }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content,
    )
}
