package dev.varch.controller.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.varch.controller.R
import dev.varch.controller.net.ThemeColors

/**
 * The app's eight colours. The default is a control surface: graphite
 * ground, bone ink and one amber signal colour. When the desktop reports its
 * theme, [from] builds a matching palette so the phone looks like part of it.
 */
data class Palette(
    val ground: Color,
    val panel: Color,
    val rule: Color,
    val faint: Color,
    val dim: Color,
    val bone: Color,
    val amber: Color,
    val red: Color,
) {
    /** Light grounds need dark status-bar icons. */
    val light get() = ground.luminance() > 0.5f

    companion object {
        val Default = Palette(
            ground = Color(0xFF0B0C0A),
            panel = Color(0xFF141512),
            rule = Color(0xFF2A2C26),
            faint = Color(0xFF55574D),
            dim = Color(0xFF8F8C7C),
            bone = Color(0xFFE9E4D6),
            amber = Color(0xFFFFB000),
            red = Color(0xFFEF5350),
        )

        /**
         * Builds a palette from the three colours every desktop theme has.
         * The in-between tones are mixed from ground and text rather than
         * read from the theme, because themes disagree on which extras exist.
         * Colours a theme picked with too little contrast are pushed toward
         * the text colour until they are readable.
         */
        fun from(colors: ThemeColors): Palette? {
            val ground = parse(colors.background) ?: return null
            val foreground = parse(colors.foreground) ?: return null
            val extreme = if (ground.luminance() > 0.5f) Color.Black else Color.White
            val text = readable(foreground, ground, TEXT_CONTRAST, extreme)
            val accent = readable(parse(colors.accent) ?: text, ground, MARK_CONTRAST, text)
            // Some themes reuse the "red" slot for a neutral; a warning key must still look like one.
            val themeRed = parse(colors.red)?.takeIf { it.red > it.green * 1.3f && it.red > it.blue * 1.3f }
            val red = readable(themeRed ?: if (extreme == Color.Black) Color(0xFFC62828) else Default.red, ground, MARK_CONTRAST, text)
            return Palette(
                ground = ground,
                panel = lerp(ground, text, 0.05f),
                rule = lerp(ground, text, 0.16f),
                faint = lerp(ground, text, 0.34f),
                dim = lerp(ground, text, 0.62f),
                bone = text,
                amber = accent,
                red = red,
            )
        }

        fun contrast(a: Color, b: Color): Float {
            val (hi, lo) = a.luminance().let { la -> b.luminance().let { lb -> maxOf(la, lb) to minOf(la, lb) } }
            return (hi + 0.05f) / (lo + 0.05f)
        }

        private fun readable(color: Color, ground: Color, target: Float, toward: Color): Color {
            var result = color
            var mix = 0f
            while (contrast(result, ground) < target && mix < 1f) {
                mix += 0.1f
                result = lerp(color, toward, mix)
            }
            return result
        }

        private fun parse(hex: String): Color? {
            if (hex.length != 7 || hex[0] != '#') return null
            val rgb = hex.substring(1).toLongOrNull(16) ?: return null
            return Color(0xFF000000 or rgb)
        }

        private const val TEXT_CONTRAST = 7f
        private const val MARK_CONTRAST = 3f
    }
}

/** The colours in use right now. Reads are tracked, so changing the palette redraws everything. */
object Ink {
    private var palette by mutableStateOf(Palette.Default)

    val Ground get() = palette.ground
    val Panel get() = palette.panel
    val Rule get() = palette.rule
    val Faint get() = palette.faint
    val Dim get() = palette.dim
    val Bone get() = palette.bone
    val Amber get() = palette.amber
    val Red get() = palette.red
    val light get() = palette.light

    fun use(next: Palette) {
        palette = next
    }
}

val Mono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
)

// Getters, so each style picks up the current palette.
object Type {
    val Label get() = TextStyle(fontFamily = Mono, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 1.6.sp, color = Ink.Dim)
    val Body get() = TextStyle(fontFamily = Mono, fontSize = 14.sp, lineHeight = 21.sp, color = Ink.Dim)
    val Key get() = TextStyle(fontFamily = Mono, fontSize = 13.sp, lineHeight = 16.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold)
    val Title get() = TextStyle(fontFamily = Mono, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold, color = Ink.Bone)
    val Numeral get() = TextStyle(fontFamily = Mono, fontSize = 40.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, color = Ink.Bone)
    val Wordmark get() = TextStyle(fontFamily = Mono, fontSize = 44.sp, lineHeight = 46.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold, color = Ink.Bone)
}

@Composable
fun Surface(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Ink.Ground).safeDrawingPadding()) { content() }
}
