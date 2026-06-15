package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

import androidx.compose.ui.graphics.Color

private val DarkColorScheme =
  darkColorScheme(
    primary = GlowGreen,
    secondary = SoftCyan,
    tertiary = SparkOrange,
    background = PetrolDeepBg,
    surface = PetrolSurface,
    surfaceVariant = PetrolSurfaceLight,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = Color(0xFF191C19),
    onSurface = Color(0xFF191C19),
    onSurfaceVariant = MintyGray,
    error = HighAlertRed
  )

private val LightColorScheme =
  lightColorScheme(
    primary = GlowGreen,
    secondary = SoftCyan,
    tertiary = SparkOrange,
    background = PetrolDeepBg,
    surface = PetrolSurface,
    surfaceVariant = PetrolSurfaceLight,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = Color(0xFF191C19),
    onSurface = Color(0xFF191C19),
    onSurfaceVariant = MintyGray,
    error = HighAlertRed
  )

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = false, // Clean Minimalism uses a crisp, high-visibility light theme
  dynamicColor: Boolean = false, // Set to false to retain our precisely curated palette
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

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
