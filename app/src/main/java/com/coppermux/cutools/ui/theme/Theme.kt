package com.coppermux.cutools.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ================================================================
// CuTools Developer Design System Palettes
// ================================================================

/**
 * Charcoal / Slate Dark Theme.
 * Background, surface, text, and container hierarchy aligned 1:1 with official CuPDF foundation.
 */
val DevToolsDarkColorScheme: ColorScheme = darkColorScheme(
    primary = CopperBrandColors.BrightPeachDark, // Color(0xFFFFB59C) - Bright Peach accent, active icons
    onPrimary = CopperBrandColors.OnCopperDarkSurfacePrimary, // Color(0xFF5C1C00) - Dark brown text on peach buttons
    primaryContainer = CopperBrandColors.DarkCopperContainer, // Color(0xFF68402D) - Dark Copper container for active pills/switches
    onPrimaryContainer = CopperBrandColors.BrightPeachDark,   // Color(0xFFFFB59C) - Bright Peach content inside dark copper pills
    inversePrimary = CopperBrandColors.Copper,   // Color(0xFFC85A32)
    secondary = CopperBrandColors.BronzeDark,    // Color(0xFFE6BEAD) - Light bronze for dark surfaces
    onSecondary = CopperBrandColors.OnBronzeDark,// Color(0xFF442B20)
    secondaryContainer = CopperBrandColors.BronzeContainerDark, // Color(0xFF5C3F34)
    onSecondaryContainer = CopperBrandColors.BronzeContainer,   // Color(0xFFFFDBCF)
    tertiary = CopperBrandColors.GoldDark,       // Color(0xFFD2C78E) - Warm gold
    onTertiary = Color(0xFF373105),
    tertiaryContainer = CopperBrandColors.GoldContainerDark,    // Color(0xFF4E471A)
    onTertiaryContainer = CopperBrandColors.GoldContainer,      // Color(0xFFEFE2A7)
    background = BackgroundDark,                 // #121316 (CuPDF BackgroundDark)
    onBackground = CopperBrandColors.OnBackgroundDark, // #E2E2E6 (CuPDF OnBackgroundDark)
    surface = SurfaceDark,                       // #121316 (CuPDF SurfaceDark)
    onSurface = CopperBrandColors.OnSurfaceDark, // #E2E2E6 (Main text, menu items)
    surfaceVariant = SurfaceVariantDark,         // #28292E (Card background, unchecked switch track, divider)
    onSurfaceVariant = CopperBrandColors.SlateMuted, // #94A3B8 (Subtitles, muted icons, unchecked thumbs)
    surfaceContainerLowest = CopperBrandColors.SurfaceContainerLowestDark, // #0D0E11 (CuPDF SurfaceContainerLowestDark)
    surfaceContainerLow = CopperBrandColors.SurfaceContainerLowDark,       // #18191D (URL pill, menu background)
    surfaceContainer = CopperBrandColors.SurfaceContainerDark,             // #1E1F24 (CuPDF SurfaceContainerDark)
    surfaceContainerHigh = CopperBrandColors.SurfaceContainerHighDark,     // #28292F (CuPDF SurfaceContainerHighDark)
    surfaceContainerHighest = CopperBrandColors.SurfaceContainerHighestDark, // #33343A (CuPDF SurfaceContainerHighestDark)
    outline = CopperBrandColors.SlateBorder,     // #475569 (Switch borders, card borders)
    outlineVariant = CopperBrandColors.OutlineVariantDark,         // #43454A (CuPDF OutlineVariantDark)
    error = CopperBrandColors.ErrorDark,                           // #FFB4AB (CuPDF ErrorDark)
    onError = CopperBrandColors.OnErrorDark,                       // #690005 (CuPDF OnErrorDark)
    errorContainer = CopperBrandColors.ErrorContainerDark,         // #93000A (CuPDF ErrorContainerDark)
    onErrorContainer = CopperBrandColors.OnErrorContainerDark,     // #FFDAD6 (CuPDF OnErrorContainerDark)
)

/**
 * Precision Crisp Light Theme.
 * Background, surface, text, and container hierarchy aligned 1:1 with official CuPDF foundation.
 */
val DevToolsLightColorScheme: ColorScheme = lightColorScheme(
    primary = Copper,                       // Copper brand color
    onPrimary = Color.White,                // White
    primaryContainer = CopperContainer,     // Color(0xFFFFDBCF)
    onPrimaryContainer = Color(0xFF191C1E), // Matches CuPDF's dark text on light active tabs
    inversePrimary = Color(0xFFFFB59B),
    secondary = CopperBrandColors.Bronze,   // Color(0xFF76584B)
    onSecondary = CopperBrandColors.OnBronze, // Color(0xFFFFFFFF)
    secondaryContainer = CopperBrandColors.BronzeContainer, // Color(0xFFFFDBCF)
    onSecondaryContainer = CopperBrandColors.OnBronzeContainer, // Color(0xFF2C160D)
    tertiary = CopperBrandColors.Gold,      // Color(0xFF675E2F)
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = CopperBrandColors.GoldContainer, // Color(0xFFEFE2A7)
    onTertiaryContainer = CopperBrandColors.OnGoldContainer, // Color(0xFF201C00)
    background = BackgroundLight,           // #F8F9FA (CuPDF BackgroundLight)
    onBackground = CopperBrandColors.OnBackgroundLight, // #191C1E (CuPDF OnBackgroundLight)
    surface = SurfaceLight,                 // #F8F9FA (CuPDF SurfaceLight)
    onSurface = CopperBrandColors.OnSurfaceLight, // #191C1E (CuPDF OnSurfaceLight)
    surfaceVariant = SurfaceVariantLight,   // #EDE0DB (CuPDF SurfaceVariantLight)
    onSurfaceVariant = CopperBrandColors.OnSurfaceVariantLight, // #4C4542 (CuPDF OnSurfaceVariantLight)
    surfaceContainerLowest = CopperBrandColors.SurfaceContainerLowestLight, // #FFFFFF (CuPDF SurfaceContainerLowestLight)
    surfaceContainerLow = CopperBrandColors.SurfaceContainerLowLight,       // #F3F4F6 (CuPDF SurfaceContainerLowLight)
    surfaceContainer = CopperBrandColors.SurfaceContainerLight,             // #EEEEF0 (CuPDF SurfaceContainerLight)
    surfaceContainerHigh = CopperBrandColors.SurfaceContainerHighLight,     // #E8E9EC (CuPDF SurfaceContainerHighLight)
    surfaceContainerHighest = CopperBrandColors.SurfaceContainerHighestLight, // #E2E3E6 (CuPDF SurfaceContainerHighestLight)
    outline = CopperBrandColors.OutlineLight,                       // #7D7571 (CuPDF OutlineLight)
    outlineVariant = CopperBrandColors.OutlineVariantLight,         // #CFC4BF (CuPDF OutlineVariantLight)
    error = CopperBrandColors.ErrorLight,                           // #BA1A1A (CuPDF ErrorLight)
    onError = CopperBrandColors.OnErrorLight,                       // #FFFFFF (CuPDF OnErrorLight)
    errorContainer = CopperBrandColors.ErrorContainerLight,         // #FFDAD6 (CuPDF ErrorContainerLight)
    onErrorContainer = CopperBrandColors.OnErrorContainerLight,     // #410002 (CuPDF OnErrorContainerLight)
)

// ================================================================
// Status & Semantic Colors for DevTools
// ================================================================

object DevToolsColors {
    val GreenActive = Color(0xFF22C55E)
    val GreenContainerDark = Color(0xFF052E16)
    val GreenContentDark = Color(0xFF4ADE80)
    val GreenContainerLight = Color(0xFFDCFCE7)
    val GreenContentLight = Color(0xFF166534)

    val AmberConnecting = Color(0xFFF59E0B)
    val AmberContainerDark = Color(0xFF451A03)
    val AmberContentDark = Color(0xFFFCD34D)
    val AmberContainerLight = Color(0xFFFEF3C7)
    val AmberContentLight = Color(0xFF92400E)

    val RedError = Color(0xFFEF4444)
    val RedContainerDark = Color(0xFF450A0A)
    val RedContentDark = Color(0xFFFCA5A5)
    val RedContainerLight = Color(0xFFFEE2E2)
    val RedContentLight = Color(0xFF991B1B)

    val GrayInactive = Color(0xFF64748B)
    val GrayContainerDark = Color(0xFF1E293B)
    val GrayContentDark = Color(0xFF94A3B8)
    val GrayContainerLight = Color(0xFFF1F5F9)
    val GrayContentLight = Color(0xFF475569)

    val MonospaceBlue = Color(0xFF38BDF8)
    val MonospacePurple = Color(0xFFC084FC)
}

// ================================================================
// Shapes & Typography
// ================================================================

val DevToolsShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

val DevToolsTypography = Typography(
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = (-0.1).sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
    ),
)

// Monospace helper styles
val MonospaceCodeStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Normal,
    fontSize = 12.sp,
    lineHeight = 16.sp,
)

val MonospaceBadgeStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Bold,
    fontSize = 10.sp,
    lineHeight = 12.sp,
    letterSpacing = 0.5.sp,
)

@Composable
fun CuToolsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DevToolsDarkColorScheme else DevToolsLightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = DevToolsShapes,
        typography = DevToolsTypography,
        content = content,
    )
}

// ================================================================
// Semantic ColorScheme Extensions for DevTools
// ================================================================

val ColorScheme.isDark: Boolean
    get() = this.surface == SurfaceDark

val ColorScheme.success: Color
    get() = if (isDark) DevToolsColors.GreenContentDark else DevToolsColors.GreenActive

val ColorScheme.successContainer: Color
    get() = if (isDark) DevToolsColors.GreenContainerDark else DevToolsColors.GreenContainerLight

val ColorScheme.onSuccessContainer: Color
    get() = if (isDark) DevToolsColors.GreenContentDark else DevToolsColors.GreenContentLight

val ColorScheme.warning: Color
    get() = if (isDark) DevToolsColors.AmberContentDark else DevToolsColors.AmberContentLight

val ColorScheme.warningContainer: Color
    get() = if (isDark) DevToolsColors.AmberContainerDark else DevToolsColors.AmberContainerLight

val ColorScheme.onWarningContainer: Color
    get() = if (isDark) DevToolsColors.AmberContentDark else DevToolsColors.AmberContentLight

val ColorScheme.concentricOuter: Color
    get() = if (isDark) CopperBrandColors.ConcentricOuterDark else CopperBrandColors.ConcentricOuterLight

val ColorScheme.concentricMiddle: Color
    get() = if (isDark) CopperBrandColors.ConcentricMiddleDark else CopperBrandColors.ConcentricMiddleLight

val ColorScheme.concentricInner: Color
    get() = if (isDark) CopperBrandColors.ConcentricInnerDark else CopperBrandColors.ConcentricInnerLight

val ColorScheme.concentricIcon: Color
    get() = if (isDark) CopperBrandColors.ConcentricIconDark else CopperBrandColors.ConcentricIconLight

val ColorScheme.browserNavContainer: Color
    get() = if (isDark) CopperBrandColors.BrowserNavActiveDark else CopperBrandColors.BrowserNavActiveLight

val ColorScheme.onBrowserNavContainer: Color
    get() = if (isDark) CopperBrandColors.OnBrowserNavActiveDark else CopperBrandColors.OnBrowserNavActiveLight

val ColorScheme.dashboardNavContainer: Color
    get() = if (isDark) CopperBrandColors.DashboardNavActiveDark else primaryContainer

val ColorScheme.dashboardActionContainer: Color
    get() = if (isDark) CopperBrandColors.DashboardActionDark else primaryContainer

val ColorScheme.recordingIndicator: Color
    get() = if (isDark) DevToolsColors.RedError else DevToolsColors.RedError


