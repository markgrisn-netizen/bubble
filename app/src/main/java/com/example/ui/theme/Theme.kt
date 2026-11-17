package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val FaqqaDarkColorScheme = darkColorScheme(
    primary = AmberBubble,
    onPrimary = InkMidnight,
    primaryContainer = AmberBubbleContainer,
    onPrimaryContainer = IvoryText,
    secondary = CyanSpark,
    onSecondary = InkMidnight,
    secondaryContainer = CyanSparkContainer,
    onSecondaryContainer = IvoryText,
    tertiary = EmeraldSuccess,
    background = InkMidnight,
    onBackground = IvoryText,
    surface = InkSurface,
    onSurface = IvoryText,
    surfaceVariant = InkSurfaceElevated,
    onSurfaceVariant = MutedSlateText,
    outline = InkOutline,
    error = CrimsonError,
    onError = Color.White
)

private val FaqqaLightColorScheme = lightColorScheme(
    primary = InkPrimaryLight,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDBE2FF),
    onPrimaryContainer = InkPrimaryLight,
    secondary = AmberDeepLight,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE7C2),
    onSecondaryContainer = Color(0xFF3A1E00),
    tertiary = TealDeepLight,
    background = PaperCream,
    onBackground = Color(0xFF15192B),
    surface = PaperCard,
    onSurface = Color(0xFF15192B),
    surfaceVariant = PaperSurfaceVariant,
    onSurfaceVariant = Color(0xFF4F556B),
    outline = PaperOutline,
    error = CrimsonError,
    onError = Color.White
)

val FaqqaShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp)
)

@Composable
fun FaqqaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> FaqqaDarkColorScheme
        else -> FaqqaLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = FaqqaShapes,
        content = content
    )
}
