package com.manichord.mgit.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.materialkolor.PaletteStyle
import com.materialkolor.rememberDynamicColorScheme
import me.sheimi.android.utils.Profile

/**
 * Brand color by default (a full tonal palette generated from [BrandSeedColor] via
 * MaterialKolor, so the app always looks like itself), with an opt-in escape hatch to
 * system wallpaper-based Material You color for users who want that instead -- see
 * Settings > "Use wallpaper colors" (Profile.useDynamicColor). Typeface is similarly
 * user-selectable -- see Settings > "App font" (Profile.getAppFont()).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppTheme(
    useDarkTheme: Boolean = isSystemInDarkTheme(),
    useDynamicColor: Boolean = Profile.useDynamicColor(LocalContext.current),
    fontOption: FontOption = FontOption.fromId(Profile.getAppFont(LocalContext.current)),
    content: @Composable () -> Unit
) {
    val systemDynamicColorAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val colors = when {
        useDynamicColor && systemDynamicColorAvailable && useDarkTheme ->
            dynamicDarkColorScheme(LocalContext.current)
        useDynamicColor && systemDynamicColorAvailable && !useDarkTheme ->
            dynamicLightColorScheme(LocalContext.current)
        else ->
            rememberDynamicColorScheme(
                seedColor = BrandSeedColor,
                isDark = useDarkTheme,
                style = PaletteStyle.TonalSpot
            )
    }

    // The XML theme (Theme.AppCompat.Light.DarkActionBar) asks for light status bar icons, made
    // for a dark action bar. From Android 15 screens are drawn edge to edge, so the bars sit on
    // the light Compose background and those icons were white on white. Match the icons to the
    // Compose theme there. Older versions still paint the bars with the theme's dark color, where
    // the light icons are right
    val view = LocalView.current
    if (!view.isInEditMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
        SideEffect {
            view.context.findActivity()?.window?.let { window ->
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !useDarkTheme
                    isAppearanceLightNavigationBars = !useDarkTheme
                }
            }
        }
    }

    MaterialExpressiveTheme(
        colorScheme = colors,
        typography = gitlingTypography(fontOption.fontFamily),
        content = content
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
