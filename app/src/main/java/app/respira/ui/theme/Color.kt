package app.respira.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The single source of truth for the app's palette. Screens should prefer
 * [MaterialTheme.colorScheme] where it fits, and these tokens for the
 * atmospheric layers that Material does not model.
 */
object RespiraColors {
    val Ink = Color(0xFF05070D)
    val Night = Color(0xFF080D16)
    val Deep = Color(0xFF0D1524)
    val Surface = Color(0xFF111B2C)
    val SurfaceHigh = Color(0xFF18243A)

    val Mist = Color(0xFFF1F6FD)
    val SecondaryText = Color(0xFFA7B6CC)
    val TertiaryText = Color(0xFF71829B)

    val Aqua = Color(0xFF72E8D5)
    val AquaDim = Color(0xFF2FB9A6)
    val Indigo = Color(0xFF6C7CFF)
    val Violet = Color(0xFFB49CFF)
    val Amber = Color(0xFFFFCB8F)
    val Rose = Color(0xFFFF9DB2)

    val Line = Color(0x14FFFFFF)
    val LineStrong = Color(0x26FFFFFF)

    /** Hairline separators and quiet outlines. */
    val Hairline = Color(0x14FFFFFF)
    /** Default outline on pills, chips and inputs. */
    val Border = Color(0x1FFFFFFF)
    /** Outline on interactive chrome that must read clearly. */
    val BorderStrong = Color(0x38FFFFFF)

    /** Hero gradient for the breathing card. */
    val HeroStart = Color(0xFF14303F)
    val HeroMid = Color(0xFF161A38)
    val HeroEnd = Color(0xFF1B1830)
}
