package dev.varch.controller.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.varch.controller.R

/** A control surface: graphite ground, bone ink, one amber signal colour. */
object Ink {
    val Ground = Color(0xFF0B0C0A)
    val Panel = Color(0xFF141512)
    val Rule = Color(0xFF2A2C26)
    val Faint = Color(0xFF55574D)
    val Dim = Color(0xFF8F8C7C)
    val Bone = Color(0xFFE9E4D6)
    val Amber = Color(0xFFFFB000)
    val Red = Color(0xFFEF5350)
}

val Mono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
)

object Type {
    val Label = TextStyle(fontFamily = Mono, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 1.6.sp, color = Ink.Dim)
    val Body = TextStyle(fontFamily = Mono, fontSize = 14.sp, lineHeight = 21.sp, color = Ink.Dim)
    val Key = TextStyle(fontFamily = Mono, fontSize = 13.sp, lineHeight = 16.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold)
    val Title = TextStyle(fontFamily = Mono, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold, color = Ink.Bone)
    val Numeral = TextStyle(fontFamily = Mono, fontSize = 40.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, color = Ink.Bone)
    val Wordmark = TextStyle(fontFamily = Mono, fontSize = 44.sp, lineHeight = 46.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold, color = Ink.Bone)
}

@Composable
fun Surface(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Ink.Ground).safeDrawingPadding()) { content() }
}
