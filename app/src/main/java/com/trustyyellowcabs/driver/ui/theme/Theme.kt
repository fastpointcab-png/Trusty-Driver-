package com.trustyyellowcabs.driver.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val RedLightColorScheme = lightColorScheme(
    primary = TaxiRed,
    onPrimary = TaxiWhite,
    primaryContainer = TaxiRedLight,
    onPrimaryContainer = TaxiRedDark,
    secondary = TaxiBlack,
    onSecondary = TaxiWhite,
    background = TaxiBackground,
    onBackground = TaxiBlack,
    surface = TaxiSurface,
    onSurface = TaxiBlack,
    surfaceVariant = TaxiLine,
    onSurfaceVariant = TaxiGray
)

@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = RedLightColorScheme,
        typography = Typography,
        content = content
    )
}
