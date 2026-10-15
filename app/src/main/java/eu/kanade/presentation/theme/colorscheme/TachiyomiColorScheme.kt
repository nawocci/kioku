package eu.kanade.presentation.theme.colorscheme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Colors for Default (Kioku) theme
 *
 * Key colors:
 * Primary #C0182F (crimson) / #FFB3B8 (light tone)
 */
internal object TachiyomiColorScheme : BaseColorScheme() {

    override val darkScheme = darkColorScheme(
        primary = Color(0xFFFFB3B8),
        onPrimary = Color(0xFF5F0016),
        primaryContainer = Color(0xFF8C0025),
        onPrimaryContainer = Color(0xFFFFD9DC),
        inversePrimary = Color(0xFFC0182F),
        secondary = Color(0xFFFFB3B8), // Unread badge
        onSecondary = Color(0xFF5F0016), // Unread badge text
        secondaryContainer = Color(0xFF8C0025), // Navigation bar selector pill
        onSecondaryContainer = Color(0xFFFFD9DC), // Navigation bar selector icon
        tertiary = Color(0xFF7ADC77), // Downloaded badge
        onTertiary = Color(0xFF003909), // Downloaded badge text
        tertiaryContainer = Color(0xFF005312),
        onTertiaryContainer = Color(0xFF95F990),
        background = Color(0xFF191113),
        onBackground = Color(0xFFF0DEDF),
        surface = Color(0xFF191113),
        onSurface = Color(0xFFF0DEDF),
        surfaceVariant = Color(0xFF24171A), // Navigation bar background
        onSurfaceVariant = Color(0xFFD9C0C3),
        surfaceTint = Color(0xFFFFB3B8),
        inverseSurface = Color(0xFFF0DEDF),
        inverseOnSurface = Color(0xFF191113),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        outline = Color(0xFFA08C8E),
        outlineVariant = Color(0xFF534346),
        surfaceContainerLowest = Color(0xFF130C0E),
        surfaceContainerLow = Color(0xFF1E1416),
        surfaceContainer = Color(0xFF24171A), // Navigation bar background
        surfaceContainerHigh = Color(0xFF2E2023),
        surfaceContainerHighest = Color(0xFF3A2A2E),
    )

    override val lightScheme = lightColorScheme(
        primary = Color(0xFFC0182F),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFFFD9DC),
        onPrimaryContainer = Color(0xFF40000C),
        inversePrimary = Color(0xFFFFB3B8),
        secondary = Color(0xFFC0182F), // Unread badge
        onSecondary = Color(0xFFFFFFFF), // Unread badge text
        secondaryContainer = Color(0xFFFFD9DC), // Navigation bar selector pill
        onSecondaryContainer = Color(0xFF40000C), // Navigation bar selector icon
        tertiary = Color(0xFF006E1B), // Downloaded badge
        onTertiary = Color(0xFFFFFFFF), // Downloaded badge text
        tertiaryContainer = Color(0xFF95F990),
        onTertiaryContainer = Color(0xFF002203),
        background = Color(0xFFFFF8F8),
        onBackground = Color(0xFF22191B),
        surface = Color(0xFFFFF8F8),
        onSurface = Color(0xFF22191B),
        surfaceVariant = Color(0xFFF9EAEC), // Navigation bar background
        onSurfaceVariant = Color(0xFF534346),
        surfaceTint = Color(0xFFC0182F),
        inverseSurface = Color(0xFF382E30),
        inverseOnSurface = Color(0xFFFDEDEF),
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        outline = Color(0xFF857376),
        outlineVariant = Color(0xFFD6C2C5),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFFFF0F1),
        surfaceContainer = Color(0xFFF9EAEC), // Navigation bar background
        surfaceContainerHigh = Color(0xFFF4E4E7),
        surfaceContainerHighest = Color(0xFFEEDEE1),
    )
}
