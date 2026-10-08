/*
 * Copyright 2026 Locus Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:Suppress("MagicNumber")

package com.locus.app.theme

import androidx.compose.ui.graphics.Color

val md_theme_light_primary = Color(0xFF006874)
val md_theme_light_onPrimary = Color(0xFFFFFFFF)
val md_theme_light_primaryContainer = Color(0xFF97F0FF)
val md_theme_light_onPrimaryContainer = Color(0xFF001F24)
val md_theme_light_secondary = Color(0xFF4A6267)
val md_theme_light_onSecondary = Color(0xFFFFFFFF)
val md_theme_light_secondaryContainer = Color(0xFFCDE7EC)
val md_theme_light_onSecondaryContainer = Color(0xFF051F23)
val md_theme_light_tertiary = Color(0xFF525E7D)
val md_theme_light_onTertiary = Color(0xFFFFFFFF)
val md_theme_light_tertiaryContainer = Color(0xFFDAE2FF)
val md_theme_light_onTertiaryContainer = Color(0xFF0E1A37)
val md_theme_light_error = Color(0xFFBA1A1A)
val md_theme_light_errorContainer = Color(0xFFFFDAD6)
val md_theme_light_onError = Color(0xFFFFFFFF)
val md_theme_light_onErrorContainer = Color(0xFF410002)
val md_theme_light_background = Color(0xFFFAFDFB)
val md_theme_light_onBackground = Color(0xFF191C1D)
val md_theme_light_surface = Color(0xFFFAFDFB)
val md_theme_light_onSurface = Color(0xFF191C1D)
val md_theme_light_surfaceVariant = Color(0xFFDBE4E6)
val md_theme_light_onSurfaceVariant = Color(0xFF3F484A)
val md_theme_light_outline = Color(0xFF6F797A)
val md_theme_light_inverseOnSurface = Color(0xFFEFF1F1)
val md_theme_light_inverseSurface = Color(0xFF2E3132)
val md_theme_light_inversePrimary = Color(0xFF4FD8EB)
val md_theme_light_surfaceTint = Color(0xFF006874)
val md_theme_light_outlineVariant = Color(0xFFBFC8CA)
val md_theme_light_scrim = Color(0xFF000000)

val md_theme_dark_primary = Color(0xFF4FD8EB)
val md_theme_dark_onPrimary = Color(0xFF00363D)
val md_theme_dark_primaryContainer = Color(0xFF004F58)
val md_theme_dark_onPrimaryContainer = Color(0xFF97F0FF)
val md_theme_dark_secondary = Color(0xFFB1CBD0)
val md_theme_dark_onSecondary = Color(0xFF1C3438)
val md_theme_dark_secondaryContainer = Color(0xFF334B4F)
val md_theme_dark_onSecondaryContainer = Color(0xFFCDE7EC)
val md_theme_dark_tertiary = Color(0xFFBAC6EA)
val md_theme_dark_onTertiary = Color(0xFF24304D)
val md_theme_dark_tertiaryContainer = Color(0xFF3B4664)
val md_theme_dark_onTertiaryContainer = Color(0xFFDAE2FF)
val md_theme_dark_error = Color(0xFFFFB4AB)
val md_theme_dark_errorContainer = Color(0xFF93000A)
val md_theme_dark_onError = Color(0xFF690005)
val md_theme_dark_onErrorContainer = Color(0xFFFFDAD6)
val md_theme_dark_background = Color(0xFF191C1D)
val md_theme_dark_onBackground = Color(0xFFE1E3E3)
val md_theme_dark_surface = Color(0xFF191C1D)
val md_theme_dark_onSurface = Color(0xFFE1E3E3)
val md_theme_dark_surfaceVariant = Color(0xFF3F484A)
val md_theme_dark_onSurfaceVariant = Color(0xFFBFC8CA)
val md_theme_dark_outline = Color(0xFF899294)
val md_theme_dark_inverseOnSurface = Color(0xFF191C1D)
val md_theme_dark_inverseSurface = Color(0xFFE1E3E3)
val md_theme_dark_inversePrimary = Color(0xFF006874)
val md_theme_dark_surfaceTint = Color(0xFF4FD8EB)
val md_theme_dark_outlineVariant = Color(0xFF3F484A)
val md_theme_dark_scrim = Color(0xFF000000)

/**
 * Canonical 8 Google Keep-style note colors (N-6).
 *
 * Exact hex values chosen:
 * 1. Coral (Red):    #F28B82  (Dark mode: #77172E)
 * 2. Peach (Orange): #FBBC04  (Dark mode: #692B17)
 * 3. Sand (Yellow):  #FFF475  (Dark mode: #7C4A03)
 * 4. Mint (Green):   #CCFF90  (Dark mode: #264D3B)
 * 5. Sage (Teal):    #A7FFEB  (Dark mode: #0C625D)
 * 6. Fog (Blue):     #CBF0F8  (Dark mode: #256377)
 * 7. Dusk (Purple):  #D7AEFB  (Dark mode: #472E5B)
 * 8. Blossom (Pink): #FDCFE8  (Dark mode: #5C2B47)
 */
data class NoteColorSwatch(
    val name: String,
    val hex: String,
    val lightColor: Color,
    val darkColor: Color,
)

val KeepNoteColorSwatches: List<NoteColorSwatch> =
    listOf(
        NoteColorSwatch("Coral", "#F28B82", Color(0xFFF28B82), Color(0xFF77172E)),
        NoteColorSwatch("Peach", "#FBBC04", Color(0xFFFBBC04), Color(0xFF692B17)),
        NoteColorSwatch("Sand", "#FFF475", Color(0xFFFFF475), Color(0xFF7C4A03)),
        NoteColorSwatch("Mint", "#CCFF90", Color(0xFFCCFF90), Color(0xFF264D3B)),
        NoteColorSwatch("Sage", "#A7FFEB", Color(0xFFA7FFEB), Color(0xFF0C625D)),
        NoteColorSwatch("Fog", "#CBF0F8", Color(0xFFCBF0F8), Color(0xFF256377)),
        NoteColorSwatch("Dusk", "#D7AEFB", Color(0xFFD7AEFB), Color(0xFF472E5B)),
        NoteColorSwatch("Blossom", "#FDCFE8", Color(0xFFFDCFE8), Color(0xFF5C2B47)),
    )

fun parseHexColor(hex: String): Color? =
    runCatching {
        val cleanHex = hex.removePrefix("#")
        val colorInt =
            when (cleanHex.length) {
                6 -> 0xFF000000.toInt() or cleanHex.toLong(16).toInt()
                8 -> cleanHex.toLong(16).toInt()
                else -> return null
            }
        Color(colorInt)
    }.getOrNull()

fun resolveNoteColor(
    colorHex: String?,
    isDark: Boolean,
): Color? {
    if (colorHex.isNullOrBlank()) return null
    val swatch = KeepNoteColorSwatches.firstOrNull { it.hex.equals(colorHex, ignoreCase = true) }
    return if (swatch != null) {
        if (isDark) swatch.darkColor else swatch.lightColor
    } else {
        parseHexColor(colorHex)
    }
}
