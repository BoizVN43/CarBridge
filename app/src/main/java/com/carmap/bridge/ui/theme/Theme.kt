package com.carmap.bridge.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.carmap.bridge.model.ThemeMode

private val CockpitTypography = Typography().run {
    copy(
        displayLarge = displayLarge.copy(fontFeatureSettings = "tnum"),
        displayMedium = displayMedium.copy(fontFeatureSettings = "tnum"),
        displaySmall = displaySmall.copy(fontFeatureSettings = "tnum"),
        headlineLarge = headlineLarge.copy(fontFeatureSettings = "tnum"),
        headlineMedium = headlineMedium.copy(fontFeatureSettings = "tnum"),
        headlineSmall = headlineSmall.copy(fontFeatureSettings = "tnum"),
        titleLarge = titleLarge.copy(fontFeatureSettings = "tnum"),
        titleMedium = titleMedium.copy(fontFeatureSettings = "tnum"),
        titleSmall = titleSmall.copy(fontFeatureSettings = "tnum"),
        bodyLarge = bodyLarge.copy(fontFeatureSettings = "tnum"),
        bodyMedium = bodyMedium.copy(fontFeatureSettings = "tnum"),
        bodySmall = bodySmall.copy(fontFeatureSettings = "tnum"),
        labelLarge = labelLarge.copy(fontFeatureSettings = "tnum"),
        labelMedium = labelMedium.copy(fontFeatureSettings = "tnum"),
        labelSmall = labelSmall.copy(fontFeatureSettings = "tnum")
    )
}

@Immutable
data class CockpitAccents(
    val isDark: Boolean,
    val emerald: Color,
    val emeraldContainer: Color,
    val cyan: Color,
    val cyanContainer: Color,
    val coral: Color,
    val coralContainer: Color,
    val amber: Color,
    val amberContainer: Color,
    val heroObsidian: Color,
    val heroObsidianTop: Color
)

@Immutable
data class AdaptiveMetrics(
    val widthDp: Dp = 400.dp,
    val heightDp: Dp = 800.dp,
    val isTwoColumn: Boolean = false,
    val isLandscape: Boolean = false,
    val scale: Float = 1f,
    val screenPadding: Dp = 12.dp,
    val sectionGap: Dp = 8.dp,
    val cardPadding: Dp = 12.dp,
    val navBottomPadding: Dp = 78.dp
) {
    fun sp(base: Float): TextUnit = (base * scale).sp
    fun dp(base: Float): Dp = (base * scale).dp

    companion object {
        fun from(width: Dp, height: Dp): AdaptiveMetrics {
            val isLandscape = width > height
            val isTwoColumn = width >= 600.dp || (isLandscape && width >= 540.dp)
            val scale = when {
                width >= 900.dp -> 1.10f
                width >= 600.dp -> 1.04f
                width < 360.dp || height < 620.dp -> 0.90f
                else -> 1.0f
            }
            return AdaptiveMetrics(
                widthDp = width,
                heightDp = height,
                isTwoColumn = isTwoColumn,
                isLandscape = isLandscape,
                scale = scale,
                screenPadding = if (isTwoColumn) 16.dp else (14f * scale).dp,
                sectionGap = if (isTwoColumn) 12.dp else (12f * scale).dp,
                cardPadding = if (isTwoColumn) 16.dp else (14f * scale).dp,
                navBottomPadding = if (isLandscape) 68.dp else 78.dp
            )
        }
    }
}

private val LightAccents = CockpitAccents(
    isDark = false,
    emerald = Color(0xFF10B981),
    emeraldContainer = Color(0x1E10B981),
    cyan = Color(0xFF0284C7),
    cyanContainer = Color(0x1C0284C7),
    coral = Color(0xFFE0523C),
    coralContainer = Color(0x1EE0523C),
    amber = Color(0xFFD97706),
    amberContainer = Color(0x1ED97706),
    heroObsidian = Color(0xFF090D14),
    heroObsidianTop = Color(0xFF0F1725)
)

private val DarkAccents = CockpitAccents(
    isDark = true,
    emerald = Color(0xFF34D399),
    emeraldContainer = Color(0x2A10B981),
    cyan = Color(0xFF38BDF8),
    cyanContainer = Color(0x2A38BDF8),
    coral = Color(0xFFFB7185),
    coralContainer = Color(0x2EFB7185),
    amber = Color(0xFFFBBF24),
    amberContainer = Color(0x29FBBF24),
    heroObsidian = Color(0xFF070B11),
    heroObsidianTop = Color(0xFF101926)
)

val LocalCockpitAccents = staticCompositionLocalOf { DarkAccents }
val LocalAdaptiveMetrics = staticCompositionLocalOf { AdaptiveMetrics() }

private val LightColors = lightColorScheme(
    primary = Color(0xFF0D131D),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8EEF6),
    onPrimaryContainer = Color(0xFF0F172A),
    secondary = Color(0xFF10B981),
    onSecondary = Color(0xFF042116),
    background = Color(0xFFF6F7FB),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFF1F5F9),
    onSurfaceVariant = Color(0xFF64748B),
    outline = Color(0x140F172A)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF10B981),
    onPrimary = Color(0xFF051A12),
    primaryContainer = Color(0xFF122D28),
    onPrimaryContainer = Color(0xFF34D399),
    secondary = Color(0xFF34D399),
    onSecondary = Color(0xFF051A12),
    background = Color(0xFF0A0F17),
    onBackground = Color(0xFFF1F5F9),
    surface = Color(0xFF121823),
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = Color(0xFF1A2230),
    onSurfaceVariant = Color(0xFF8B9BB4),
    outline = Color(0x18FFFFFF)
)

@Composable
fun CarMapTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    applySystemBars: Boolean = true,
    content: @Composable () -> Unit
) {
    val isDark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val colorScheme = if (isDark) DarkColors else LightColors
    val accents = if (isDark) DarkAccents else LightAccents

    if (applySystemBars) {
        val view = LocalView.current
        if (!view.isInEditMode) {
            SideEffect {
                val window = (view.context as? Activity)?.window ?: return@SideEffect
                @Suppress("DEPRECATION")
                window.statusBarColor = colorScheme.background.toArgb()
                @Suppress("DEPRECATION")
                window.navigationBarColor = colorScheme.background.toArgb()
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !isDark
                controller.isAppearanceLightNavigationBars = !isDark
            }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val adaptive = AdaptiveMetrics.from(maxWidth, maxHeight)
        CompositionLocalProvider(
            LocalCockpitAccents provides accents,
            LocalAdaptiveMetrics provides adaptive
        ) {
            MaterialTheme(
                colorScheme = colorScheme,
                typography = CockpitTypography,
                content = content
            )
        }
    }
}
