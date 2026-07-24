package com.pombo.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.pombo.android.R

/**
 * Pombo web palette (single dark theme), taken from the MOBILE viewport.
 *
 * The desktop stylesheet uses a zinc palette, but the `@media (max-width:767px)`
 * block overrides every full-screen surface to true black — the CSS comment says
 * why: "Force true black on mobile for Samsung/Android nav bar"
 * (components.css:1895). This app replicates only the mobile viewport, so black
 * is the value used here. Using zinc made the chat header and composer read as
 * visibly lighter bands framing the message list, where the web is one
 * seamless black field.
 */
object PomboColors {
    // components.css:1897,1934,1974,2005,2009,2013 — all `#000000 !important`
    val Background = Color(0xFF000000)
    /** Chat header and composer: also true black on mobile, NOT a raised surface. */
    val Surface = Color(0xFF000000)
    /** Raised fills (menus, fields) that the mobile block leaves on the dark scale. */
    val SurfaceHigh = Color(0xFF18181B)
    // components.css:1978 — mobile overrides structural borders to white 8%.
    val Border = Color(0xFFFFFFFF).copy(alpha = 0.08f)
    // #F6851B, the same value as res/values/colors.xml `pombo_accent` and the
    // `text-[#F6851B]` the web uses for links. The web's own CSS variable is the
    // hotter #FF5C00, which reads neon on a true-black field (Accent drives
    // links, sender names, icons and badges).
    val Accent = Color(0xFFF6851B)
    val AccentLight = Color(0xFFFFA333)  // hover / filled state
    val AccentDim = Color(0xFFAC5D13)    // darker tone of the same hue
    val Text = Color(0xFFE4E4E7)
    /** Web uses `text-white/40` for every secondary label — zinc-400 was too bright. */
    val TextDim = Color(0xFFFFFFFF).copy(alpha = 0.40f)
    val Danger = Color(0xFFEF4444)
    val Success = Color(0xFF22C55E)
    // Message bubbles (exact web values: components.css .message-bubble)
    val BubbleOwn = Color(0xFF272A2E)
    val BubbleOther = Color(0xFF1E1E1E)
    val BubbleOwnText = Color(0xFFF0F0F0)
    val BubbleOtherText = Color(0xFFD1D5DB)
}

private val DarkScheme = darkColorScheme(
    primary = PomboColors.Accent,
    onPrimary = Color.Black,
    secondary = PomboColors.AccentDim,
    background = PomboColors.Background,
    onBackground = PomboColors.Text,
    surface = PomboColors.Surface,
    onSurface = PomboColors.Text,
    surfaceVariant = PomboColors.SurfaceHigh,
    onSurfaceVariant = PomboColors.TextDim,
    outline = PomboColors.Border,
    error = PomboColors.Danger
)

/**
 * Inter, bundled — the same family the web loads from Google Fonts
 * (index.html:53, weights 300–700).
 *
 * Bundled rather than fetched at runtime: a downloadable font arrives after
 * first paint, so every screen would flash in Roboto and reflow. It also makes
 * the app's text metrics deterministic and offline-proof, which matters here
 * because line breaking depends on glyph widths — with Roboto the same message
 * wrapped at different words than the web.
 */
private val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
    Font(R.font.inter_extrabold, FontWeight.ExtraBold)
)

/**
 * Every text style uses Inter. `includeFontPadding` is switched off to match
 * CSS box behaviour — with it on, Android adds ascent/descent padding that CSS
 * has no equivalent for, which inflates every line box.
 */
private val PomboTypography = Typography().run {
    val base = TextStyle(
        fontFamily = Inter,
        platformStyle = PlatformTextStyle(includeFontPadding = false)
    )
    copy(
        displayLarge = displayLarge.merge(base), displayMedium = displayMedium.merge(base),
        displaySmall = displaySmall.merge(base), headlineLarge = headlineLarge.merge(base),
        headlineMedium = headlineMedium.merge(base), headlineSmall = headlineSmall.merge(base),
        titleLarge = titleLarge.merge(base), titleMedium = titleMedium.merge(base),
        titleSmall = titleSmall.merge(base), bodyLarge = bodyLarge.merge(base),
        bodyMedium = bodyMedium.merge(base), bodySmall = bodySmall.merge(base),
        labelLarge = labelLarge.merge(base), labelMedium = labelMedium.merge(base),
        labelSmall = labelSmall.merge(base)
    )
}

@Composable
fun PomboTheme(content: @Composable () -> Unit) {
    // The web only has a dark theme — we ignore the system light mode.
    MaterialTheme(colorScheme = DarkScheme, typography = PomboTypography, content = content)
}
