package com.runanywhere.kotlin_starter_example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// ── Dark Glassmorphism Color Palette ──

// Backgrounds — dark with blue-tinted feel
val PrimaryDark = Color(0xFF0A0E1A)        // Deep dark navy
val PrimaryMid = Color(0xFF141A2E)         // Slightly lighter dark
val SurfaceCard = Color(0xFF1A1F35)        // Dark card surface

// Accent colors — Light Blue / Cyan family (NO PURPLE)
val AccentCyan = Color(0xFF38BDF8)         // Light Sky Blue (primary accent)
val AccentViolet = Color(0xFF38BDF8)       // SAME as AccentCyan — no purple anywhere
val AccentPink = Color(0xFFE56DB1)         // Soft Pink
val AccentGreen = Color(0xFF22C997)        // Fresh Mint Green
val NoteAmber = Color(0xFFF5A623)          // Warm Amber

// Text — white/bright for dark backgrounds
val TextPrimary = Color(0xFFF1F5F9)        // Soft white text
val TextMuted = Color(0xFF94A3B8)          // Muted blue-grey

// ── Glassmorphism helpers ──
val GlassWhite = Color(0xFF1A2035).copy(alpha = 0.55f)   // Dark translucent glass
val GlassBorder = Color.White.copy(alpha = 0.12f)         // Subtle white border
val GlassCardBg = Color.White.copy(alpha = 0.08f)         // Translucent glass card
val GlassCardShape = 22  // dp — extremely rounded corners

// Background gradient brush (top-left to bottom-right)
val GlassBackgroundBrush = Brush.linearGradient(
    colors = listOf(
        Color(0xFF0A0E1A),
        Color(0xFF0F1629),
        Color(0xFF0A0E1A),
    )
)

// Button gradient brush (Light Blue → Cyan)
val GlassButtonBrush = Brush.horizontalGradient(
    colors = listOf(
        Color(0xFF38BDF8),   // Light Sky Blue
        Color(0xFF22D3EE),   // Cyan
    )
)

private val GlassColorScheme = darkColorScheme(
    primary = AccentCyan,
    secondary = AccentCyan,
    tertiary = AccentPink,
    background = PrimaryDark,
    surface = SurfaceCard,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    surfaceVariant = Color(0xFF1E2538),
    outline = GlassBorder,
)

@Composable
fun KotlinStarterTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = GlassColorScheme,
        typography = Typography,
        content = content
    )
}
