package dev.varch.controller.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@Composable
fun Rule(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Ink.Rule))
}

/** "01  NOW PLAYING" with an optional right-aligned note. */
@Composable
fun SectionHeader(index: Int, title: String, modifier: Modifier = Modifier, note: String? = null, noteColor: Color = Ink.Dim) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        BasicText(index.toString().padStart(2, '0'), style = Type.Label.copy(color = Ink.Amber))
        BasicText(title, Modifier.padding(start = 10.dp).weight(1f), style = Type.Label)
        if (note != null) BasicText(note, style = Type.Label.copy(color = noteColor))
    }
}

/**
 * A hard-edged key. It inverts while pressed and fills with [accent] while [active].
 * [content] receives the colour it should draw with.
 */
@Composable
fun Key(
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = false,
    accent: Color = Ink.Amber,
    outline: Color = Ink.Rule,
    content: @Composable (Color) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val fill = when {
        !enabled -> Color.Transparent
        pressed -> Ink.Bone
        active -> accent
        else -> Ink.Panel
    }
    val ink = when {
        !enabled -> Ink.Faint
        pressed || active -> Ink.Ground
        else -> Ink.Bone
    }
    Box(
        modifier
            .background(fill)
            .border(1.dp, if (active && enabled) accent else outline)
            .clickable(source, indication = null, enabled = enabled, role = Role.Button) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { content(ink) }
}

@Composable
fun TextKey(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = false,
    accent: Color = Ink.Amber,
) {
    Key(onClick, text, modifier, enabled, active, accent) { ink ->
        BasicText(text, Modifier.padding(horizontal = 12.dp), style = Type.Key.copy(color = ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

enum class Glyph { Play, Pause, Next, Previous }

/** Transport symbols drawn as geometry so they stay as crisp as the key edges. */
@Composable
fun GlyphIcon(glyph: Glyph, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        fun triangle(left: Float, right: Float) = Path().apply {
            moveTo(left, 0f)
            lineTo(right, h / 2)
            lineTo(left, h)
            close()
        }
        val bar = w * 0.16f
        when (glyph) {
            Glyph.Play -> drawPath(triangle(w * 0.12f, w * 0.94f), color)
            Glyph.Pause -> {
                drawRect(color, Offset(w * 0.14f, 0f), Size(w * 0.26f, h))
                drawRect(color, Offset(w * 0.60f, 0f), Size(w * 0.26f, h))
            }
            Glyph.Next -> {
                drawPath(triangle(0f, w - bar - w * 0.06f), color)
                drawRect(color, Offset(w - bar, 0f), Size(bar, h))
            }
            Glyph.Previous -> {
                drawPath(triangle(w, bar + w * 0.06f), color)
                drawRect(color, Offset(0f, 0f), Size(bar, h))
            }
        }
    }
}

private const val FADER_SEGMENTS = 20

/**
 * A vertical console fader. It moves relative to where the finger lands, so a
 * stray touch never jumps the level.
 */
@Composable
fun Fader(
    label: String,
    value: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    known: Boolean = true,
    muted: Boolean = false,
    onLabelClick: (() -> Unit)? = null,
    labelAction: String? = null,
) {
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    val lit = when {
        !enabled || !known -> Ink.Faint
        muted -> Ink.Dim
        else -> Ink.Amber
    }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText(label, Modifier.weight(1f), style = Type.Key.copy(color = Ink.Dim))
            if (onLabelClick != null && labelAction != null) {
                TextKey(
                    "MUTE",
                    onLabelClick,
                    Modifier.fillMaxHeight().semantics { contentDescription = labelAction },
                    enabled = enabled,
                    active = muted,
                    accent = Ink.Bone,
                )
            }
        }
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.Bottom) {
            BasicText(
                if (known) (value * 100).roundToInt().toString() else "--",
                style = Type.Numeral.copy(color = if (enabled && !muted) Ink.Bone else Ink.Dim),
                maxLines = 1,
            )
            BasicText("%", Modifier.padding(start = 4.dp, bottom = 5.dp), style = Type.Label)
        }
        Canvas(
            Modifier
                .padding(top = 10.dp)
                .fillMaxSize()
                .semantics {
                    contentDescription = label
                    progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..1f)
                    if (enabled) setProgress { change(it.coerceIn(0f, 1f)); true }
                }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val startY = down.position.y
                        val startValue = current
                        drag(down.id) { event ->
                            event.consume()
                            change((startValue + (startY - event.position.y) / size.height).coerceIn(0f, 1f))
                        }
                    }
                },
        ) {
            val gap = 3.dp.toPx()
            val segment = (size.height - gap * (FADER_SEGMENTS - 1)) / FADER_SEGMENTS
            val filled = if (known) (value * FADER_SEGMENTS).roundToInt().coerceAtLeast(if (value > 0f) 1 else 0) else 0
            for (i in 0 until FADER_SEGMENTS) {
                val top = size.height - segment - i * (segment + gap)
                drawRect(if (i < filled) lit else Ink.Rule, Offset(0f, top), Size(size.width, segment))
            }
        }
    }
}

/** A key that only fires after being held, for actions that are costly to trigger by accident. */
@Composable
fun HoldKey(text: String, label: String, onConfirmed: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }
    val confirm by rememberUpdatedState(onConfirmed)
    val accent = if (enabled) Ink.Red else Ink.Faint
    Box(
        modifier
            .border(1.dp, if (enabled) Ink.Red.copy(alpha = 0.55f) else Ink.Rule)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = label
                if (enabled) onClick { confirm(); true }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onPress = {
                    val hold = scope.launch {
                        progress.animateTo(1f, tween(HOLD_MS, easing = LinearEasing))
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        confirm()
                    }
                    tryAwaitRelease()
                    hold.cancel()
                    progress.snapTo(0f)
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(progress.value).align(Alignment.CenterStart).background(Ink.Red.copy(alpha = 0.35f)))
        BasicText(text, style = Type.Key.copy(color = accent, textAlign = TextAlign.Center))
    }
}

private const val HOLD_MS = 900

/**
 * Stacks [top], [fill] and [bottom]. [fill] takes whatever height the minimum
 * constraint leaves over, but never less than [minFill], so inside a scroll
 * container the stack fills the viewport when it can and scrolls when it can't.
 */
@Composable
fun FillColumn(
    minFill: Dp,
    modifier: Modifier = Modifier,
    top: @Composable ColumnScope.() -> Unit,
    fill: @Composable () -> Unit,
    bottom: @Composable ColumnScope.() -> Unit,
) {
    Layout(
        content = {
            Column(content = top)
            Box(propagateMinConstraints = true) { fill() }
            Column(content = bottom)
        },
        modifier = modifier,
    ) { (topItem, fillItem, bottomItem), constraints ->
        val loose = Constraints(minWidth = constraints.maxWidth, maxWidth = constraints.maxWidth)
        val above = topItem.measure(loose)
        val below = bottomItem.measure(loose)
        val height = maxOf(minFill.roundToPx(), constraints.minHeight - above.height - below.height)
        val middle = fillItem.measure(Constraints.fixed(constraints.maxWidth, height))
        layout(constraints.maxWidth, above.height + height + below.height) {
            above.place(0, 0)
            middle.place(0, above.height)
            below.place(0, above.height + height)
        }
    }
}

@Composable
fun KeyRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

/** A key that reports press and release separately, so it can be held like a mouse button. */
@Composable
fun PressKey(text: String, label: String, onDown: () -> Unit, onUp: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val haptics = LocalHapticFeedback.current
    val down by rememberUpdatedState(onDown)
    val up by rememberUpdatedState(onUp)
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier
            .background(if (pressed) Ink.Bone else if (enabled) Ink.Panel else Color.Transparent)
            .border(1.dp, Ink.Rule)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = label
                if (enabled) onClick { down(); up(); true }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onPress = {
                    pressed = true
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    down()
                    tryAwaitRelease()
                    up()
                    pressed = false
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text, style = Type.Key.copy(color = if (pressed) Ink.Ground else if (enabled) Ink.Bone else Ink.Faint))
    }
}

/** A labelled figure, such as "CPU 12%". */
@Composable
fun Readout(label: String, value: String, modifier: Modifier = Modifier, unit: String = "", color: Color = Ink.Bone) {
    Column(modifier.border(1.dp, Ink.Rule).padding(horizontal = 10.dp, vertical = 10.dp)) {
        BasicText(label, style = Type.Label, maxLines = 1)
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.Bottom) {
            BasicText(value, style = Type.Title.copy(fontSize = Type.Title.fontSize * 1.2f, color = color), maxLines = 1)
            if (unit.isNotEmpty()) BasicText(unit, Modifier.padding(start = 2.dp, bottom = 3.dp), style = Type.Label)
        }
    }
}

fun clock(seconds: Float): String {
    val total = seconds.toInt().coerceAtLeast(0)
    val hours = total / 3600
    val rest = "%d:%02d".format(total % 3600 / 60, total % 60)
    return if (hours > 0) "$hours:" + rest.padStart(5, '0') else rest
}

/** A thin scrub bar. It reports the new position once, when the finger lifts. */
@Composable
fun SeekBar(position: Float, length: Float, onSeek: (Float) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val seek by rememberUpdatedState(onSeek)
    val total by rememberUpdatedState(length)
    val shown = dragging ?: position
    val fraction = if (length > 0f) (shown / length).coerceIn(0f, 1f) else 0f
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        BasicText(clock(shown), style = Type.Label.copy(letterSpacing = Type.Body.letterSpacing))
        Canvas(
            Modifier
                .weight(1f)
                .height(28.dp)
                .padding(horizontal = 10.dp)
                .semantics {
                    contentDescription = "Position"
                    progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                    if (enabled) setProgress { seek(it.coerceIn(0f, 1f) * total); true }
                }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        fun at(x: Float) = (x / size.width).coerceIn(0f, 1f) * total
                        dragging = at(down.position.x)
                        drag(down.id) { event ->
                            event.consume()
                            dragging = at(event.position.x)
                        }
                        dragging?.let(seek)
                        dragging = null
                    }
                },
        ) {
            val y = size.height / 2
            val thickness = 3.dp.toPx()
            drawRect(Ink.Rule, Offset(0f, y - thickness / 2), Size(size.width, thickness))
            val lit = if (enabled) Ink.Amber else Ink.Faint
            drawRect(lit, Offset(0f, y - thickness / 2), Size(size.width * fraction, thickness))
            if (enabled) drawRect(lit, Offset(size.width * fraction - thickness / 2, y - 7.dp.toPx()), Size(thickness, 14.dp.toPx()))
        }
        BasicText(clock(length), style = Type.Label.copy(letterSpacing = Type.Body.letterSpacing))
    }
}

/**
 * A touchpad surface: one finger moves, a tap clicks, two fingers scroll and
 * a two-finger tap right-clicks. Deltas are reported in desktop pixels.
 */
@Composable
fun Trackpad(
    onMove: (Float, Float) -> Unit,
    onScroll: (Float, Float) -> Unit,
    onClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    // Bare pads are laid over something else, such as the live view of the desktop.
    bare: Boolean = false,
) {
    val haptics = LocalHapticFeedback.current
    val move by rememberUpdatedState(onMove)
    val scroll by rememberUpdatedState(onScroll)
    val click by rememberUpdatedState(onClick)
    Box(
        modifier
            .background(if (enabled && !bare) Ink.Panel else Color.Transparent)
            .border(1.dp, if (bare) Color.Transparent else Ink.Rule)
            .semantics { contentDescription = "Trackpad" }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var fingers = 1
                    var travelled = 0f
                    var lastTime = down.uptimeMillis
                    do {
                        val event = awaitPointerEvent()
                        val held = event.changes.filter { it.pressed }
                        fingers = maxOf(fingers, held.size)
                        if (held.isNotEmpty()) {
                            lastTime = held[0].uptimeMillis
                            val dx = held.map { it.positionChange().x }.average().toFloat() / density
                            val dy = held.map { it.positionChange().y }.average().toFloat() / density
                            travelled += (kotlin.math.abs(dx) + kotlin.math.abs(dy)) * density
                            if (travelled > slop) {
                                if (held.size >= 2) {
                                    // Content follows the fingers, as on a touchscreen.
                                    scroll(-dx * SCROLL_GAIN, -dy * SCROLL_GAIN)
                                } else if (fingers == 1) {
                                    // Faster strokes travel further, so the whole desktop is reachable.
                                    val gain = POINTER_GAIN * (1f + (kotlin.math.hypot(dx, dy) / 6f).coerceAtMost(2.5f))
                                    move(dx * gain, dy * gain)
                                }
                            }
                        }
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                    if (travelled <= slop && lastTime - down.uptimeMillis < TAP_MS) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        click(if (fingers >= 2) 1 else 0)
                    }
                }
            },
    )
}

private const val POINTER_GAIN = 1.5f
private const val SCROLL_GAIN = 1.2f
private const val TAP_MS = 250

/** The bottom row of section tabs. */
@Composable
fun TabBar(tabs: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        tabs.forEachIndexed { index, tab ->
            Key({ onSelect(index) }, tab, Modifier.weight(1f).height(48.dp), active = index == selected) { ink ->
                BasicText(tab, style = Type.Key.copy(color = ink, letterSpacing = Type.Body.letterSpacing), maxLines = 1)
            }
        }
    }
}

/** One line of explanatory text for an empty section. */
@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    BasicText(text, modifier.padding(top = 10.dp), style = Type.Body.copy(fontSize = Type.Key.fontSize, color = Ink.Dim))
}
