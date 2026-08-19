package eu.kanade.presentation.theme.colorscheme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Colors for Default (Kioku) theme
 *
 * Key colors:
 * Primary #E11D48 (Rose 600) / #FB7185 (Rose 400)
 */
internal object TachiyomiColorScheme : BaseColorScheme() {

    override val darkScheme = darkColorScheme(
        primary = Color(0xFFFB7185),
        onPrimary = Color(0xFF5F001F),
        primaryContainer = Color(0xFF880030),
        onPrimaryContainer = Color(0xFFFFD9DF),
        inversePrimary = Color(0xFFE11D48),
        secondary = Color(0xFFFB7185), // Unread badge
        onSecondary = Color(0xFF5F001F), // Unread badge text
        secondaryContainer = Color(0xFF880030), // Navigation bar selector pill
        onSecondaryContainer = Color(0xFFFFD9DF), // Navigation bar selector icon
        tertiary = Color(0xFF7ADC77), // Downloaded badge
        onTertiary = Color(0xFF003909), // Downloaded badge text
        tertiaryContainer = Color(0xFF005312),
        onTertiaryContainer = Color(0xFF95F990),
        background = Color(0xFF161114),
        onBackground = Color(0xFFEFE0E3),
        surface = Color(0xFF161114),
        onSurface = Color(0xFFEFE0E3),
        surfaceVariant = Color(0xFF22171C), // Navigation bar background
        onSurfaceVariant = Color(0xFFD6C1C6),
        surfaceTint = Color(0xFFFB7185),
        inverseSurface = Color(0xFFEFE0E3),
        inverseOnSurface = Color(0xFF161114),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        outline = Color(0xFF9E8B90),
        outlineVariant = Color(0xFF514347),
        surfaceContainerLowest = Color(0xFF110C0F),
        surfaceContainerLow = Color(0xFF1B1418),
        surfaceContainer = Color(0xFF22171C), // Navigation bar background
        surfaceContainerHigh = Color(0xFF2C2126),
        surfaceContainerHighest = Color(0xFF372B31),
    )

    override val lightScheme = lightColorScheme(
        primary = Color(0xFFE11D48),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFFFD9DF),
        onPrimaryContainer = Color(0xFF3F0013),
        inversePrimary = Color(0xFFFFB2BE),
        secondary = Color(0xFFE11D48), // Unread badge
        onSecondary = Color(0xFFFFFFFF), // Unread badge text
        secondaryContainer = Color(0xFFFFD9DF), // Navigation bar selector pill
        onSecondaryContainer = Color(0xFF3F0013), // Navigation bar selector icon
        tertiary = Color(0xFF006E1B), // Downloaded badge
        onTertiary = Color(0xFFFFFFFF), // Downloaded badge text
        tertiaryContainer = Color(0xFF95F990),
        onTertiaryContainer = Color(0xFF002203),
        background = Color(0xFFFFF8F8),
        onBackground = Color(0xFF22191C),
        surface = Color(0xFFFFF8F8),
        onSurface = Color(0xFF22191C),
        surfaceVariant = Color(0xFFF9EAEF), // Navigation bar background
        onSurfaceVariant = Color(0xFF514347),
        surfaceTint = Color(0xFFE11D48),
        inverseSurface = Color(0xFF372E31),
        inverseOnSurface = Color(0xFFFDEDF0),
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        outline = Color(0xFF837377),
        outlineVariant = Color(0xFFD4C2C6),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFFFF0F3),
        surfaceContainer = Color(0xFFF9EAEF), // Navigation bar background
        surfaceContainerHigh = Color(0xFFF4E4E9),
        surfaceContainerHighest = Color(0xFFEEDEE3),
    )
}
