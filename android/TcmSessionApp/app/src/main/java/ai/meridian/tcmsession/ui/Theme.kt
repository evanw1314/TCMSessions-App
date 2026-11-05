package ai.meridian.tcmsession.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import ai.meridian.tcmsession.R

/**
 * Bridges the XML design tokens into Compose for the View -> Compose migration.
 *
 * These values are READ from values/colors.xml, values-night/colors.xml and
 * values/dimens.xml rather than restated as Kotlin literals, on purpose: during
 * the migration a converted screen and an un-converted one sit side by side in
 * the same app, and two hand-maintained copies of the palette WILL drift. Going
 * through colorResource() also means dark mode keeps working the way it already
 * does — Android picks values-night automatically, so there is no second dark
 * palette to keep in sync here either.
 *
 * Consequence worth knowing: the token list below must stay in step with
 * colors.xml, same as values-night/colors.xml already has to. Adding a token is
 * a three-file change (light, night, here).
 */
@Immutable
data class AppColors(
    val bg: Color,
    val surface1: Color,
    val surface2: Color,
    val surface3: Color,
    val border: Color,
    val borderStrong: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val accent: Color,
    val accentBg: Color,
    val accentBorder: Color,
    val onAccent: Color,
    val success: Color,
    val successBg: Color,
    val warning: Color,
    val warningBg: Color,
    val critical: Color,
    val criticalBg: Color,
)

/**
 * The tokens Material3's ColorScheme has no slot for (surface_2/3, the
 * text hierarchy, the success/warning/critical *_bg pairs). Everything that
 * DOES map to a Material role is also on MaterialTheme.colorScheme below, so
 * stock components pick the palette up without being told.
 */
val LocalAppColors = staticCompositionLocalOf<AppColors> {
    error("LocalAppColors accessed outside AppTheme — wrap the screen in AppTheme { }")
}

@Composable
private fun appColors(): AppColors = AppColors(
    bg = colorResource(R.color.bg),
    surface1 = colorResource(R.color.surface_1),
    surface2 = colorResource(R.color.surface_2),
    surface3 = colorResource(R.color.surface_3),
    border = colorResource(R.color.border),
    borderStrong = colorResource(R.color.border_strong),
    textPrimary = colorResource(R.color.text_primary),
    textSecondary = colorResource(R.color.text_secondary),
    textMuted = colorResource(R.color.text_muted),
    accent = colorResource(R.color.accent),
    accentBg = colorResource(R.color.accent_bg),
    accentBorder = colorResource(R.color.accent_border),
    onAccent = colorResource(R.color.on_accent),
    success = colorResource(R.color.success),
    successBg = colorResource(R.color.success_bg),
    warning = colorResource(R.color.warning),
    warningBg = colorResource(R.color.warning_bg),
    critical = colorResource(R.color.critical),
    criticalBg = colorResource(R.color.critical_bg),
)

/** Same radius scale themes.xml/dimens.xml already use, mapped onto Material's
 *  small/medium/large so Card and Button default to the wireframe's corners. */
@Composable
private fun appShapes(): Shapes = Shapes(
    small = RoundedCornerShape(dimensionResource(R.dimen.radius_sm)),
    medium = RoundedCornerShape(dimensionResource(R.dimen.radius_lg)),
    large = RoundedCornerShape(dimensionResource(R.dimen.radius_xl)),
)

/** themes.xml sets android:fontFamily="@font/roboto" app-wide; Compose does not
 *  read that attribute, so the same family is restated here. */
@Composable
private fun appTypography(): Typography {
    val roboto = FontFamily(Font(R.font.roboto))
    val d = Typography()
    return Typography(
        displayLarge = d.displayLarge.copy(fontFamily = roboto),
        displayMedium = d.displayMedium.copy(fontFamily = roboto),
        displaySmall = d.displaySmall.copy(fontFamily = roboto),
        headlineLarge = d.headlineLarge.copy(fontFamily = roboto),
        headlineMedium = d.headlineMedium.copy(fontFamily = roboto),
        headlineSmall = d.headlineSmall.copy(fontFamily = roboto),
        titleLarge = d.titleLarge.copy(fontFamily = roboto),
        titleMedium = d.titleMedium.copy(fontFamily = roboto),
        titleSmall = d.titleSmall.copy(fontFamily = roboto),
        bodyLarge = d.bodyLarge.copy(fontFamily = roboto),
        bodyMedium = d.bodyMedium.copy(fontFamily = roboto),
        bodySmall = d.bodySmall.copy(fontFamily = roboto),
        labelLarge = d.labelLarge.copy(fontFamily = roboto),
        labelMedium = d.labelMedium.copy(fontFamily = roboto),
        labelSmall = d.labelSmall.copy(fontFamily = roboto),
    )
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val colors = appColors()
    // The colors above are already night-aware (values-night), so this only
    // picks which Material base fills in the roles nothing above overrides.
    val base = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = colors.accent,
        onPrimary = colors.onAccent,
        primaryContainer = colors.accentBg,
        onPrimaryContainer = colors.accent,
        secondary = colors.accent,
        onSecondary = colors.onAccent,
        background = colors.bg,
        onBackground = colors.textPrimary,
        surface = colors.surface1,
        onSurface = colors.textPrimary,
        surfaceVariant = colors.surface2,
        onSurfaceVariant = colors.textSecondary,
        outline = colors.border,
        outlineVariant = colors.borderStrong,
        error = colors.critical,
        onError = colors.onAccent,
        errorContainer = colors.criticalBg,
        onErrorContainer = colors.critical,
    )
    CompositionLocalProvider(LocalAppColors provides colors) {
        MaterialTheme(
            colorScheme = scheme,
            typography = appTypography(),
            shapes = appShapes(),
            content = content,
        )
    }
}
