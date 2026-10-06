package dev.varch.controller.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.varch.controller.RemoteUi
import dev.varch.controller.net.Action
import dev.varch.controller.net.Mod

/** Pointer and keyboard input, or slide controls in presentation mode. */
@Composable
fun PadScreen(state: RemoteUi, actions: RemoteActions, startInSlides: Boolean = false) {
    var slides by rememberSaveable { mutableStateOf(startInSlides) }
    DisposableEffect(slides) {
        actions.setSlides(slides)
        onDispose { actions.setSlides(false) }
    }
    Column(Modifier.fillMaxSize()) {
        KeyRow(Modifier.padding(top = 14.dp)) {
            TextKey("TRACKPAD", { slides = false }, Modifier.weight(1f).height(40.dp), active = !slides, accent = Ink.Bone)
            TextKey("SLIDES", { slides = true }, Modifier.weight(1f).height(40.dp), active = slides, accent = Ink.Bone)
        }
        if (slides) Slides(state.live, actions) else Pad(state.live, actions)
    }
}

@Composable
private fun Pad(live: Boolean, actions: RemoteActions) {
    // Modifiers latch for one key, like sticky keys.
    var mods by remember { mutableIntStateOf(0) }
    fun press(key: String) {
        actions.send(Action.KEY_PRESS, mods.toDouble(), key)
        mods = 0
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        FillColumn(
            minFill = 150.dp,
            modifier = Modifier.verticalScroll(rememberScrollState()).heightIn(min = maxHeight),
            top = { SectionHeader(1, "POINTER", Modifier.padding(top = 16.dp), note = "2 FINGERS: SCROLL, RIGHT TAP") },
            fill = {
                Trackpad(
                    onMove = actions::movePointer,
                    onScroll = actions::scrollPointer,
                    onClick = { actions.send(Action.POINTER_CLICK, it.toDouble()) },
                    modifier = Modifier.padding(top = 10.dp),
                    enabled = live,
                )
            },
            bottom = {
                KeyRow(Modifier.padding(top = 8.dp)) {
                    // Held, not tapped, so a second finger on the pad can drag.
                    PressKey("LEFT", "Left button", { actions.send(Action.POINTER_DOWN, 0.0) }, { actions.send(Action.POINTER_UP, 0.0) }, Modifier.weight(1.4f).height(52.dp), live)
                    PressKey("MID", "Middle button", { actions.send(Action.POINTER_DOWN, 2.0) }, { actions.send(Action.POINTER_UP, 2.0) }, Modifier.weight(0.8f).height(52.dp), live)
                    PressKey("RIGHT", "Right button", { actions.send(Action.POINTER_DOWN, 1.0) }, { actions.send(Action.POINTER_UP, 1.0) }, Modifier.weight(1.4f).height(52.dp), live)
                }

                SectionHeader(2, "KEYBOARD", Modifier.padding(top = 18.dp))
                TypeField(live, mods, { mods = 0 }, actions)
                KeyRow(Modifier.padding(top = 8.dp)) {
                    for ((text, key) in listOf("ESC" to "Escape", "TAB" to "Tab", "BKSP" to "BackSpace", "ENTER" to "Return")) {
                        TextKey(text, { press(key) }, Modifier.weight(1f).height(46.dp), enabled = live)
                    }
                }
                KeyRow(Modifier.padding(top = 8.dp)) {
                    for ((text, key) in listOf("←" to "Left", "↓" to "Down", "↑" to "Up", "→" to "Right")) {
                        Key({ press(key) }, "$key arrow", Modifier.weight(1f).height(46.dp), enabled = live) { ink ->
                            BasicText(text, style = Type.Title.copy(color = ink))
                        }
                    }
                }
                KeyRow(Modifier.padding(top = 8.dp)) {
                    for ((text, bit) in listOf("CTRL" to Mod.CTRL, "ALT" to Mod.ALT, "SUPER" to Mod.SUPER, "SHIFT" to Mod.SHIFT)) {
                        TextKey(text, { mods = mods xor bit }, Modifier.weight(1f).height(46.dp), enabled = live, active = mods and bit != 0)
                    }
                }
            },
        )
    }
}

/**
 * Types on the desktop as the phone keyboard does. The field keeps what was
 * typed so that autocorrect and backspace can be mirrored as edits.
 */
@Composable
private fun TypeField(live: Boolean, mods: Int, clearMods: () -> Unit, actions: RemoteActions) {
    var buffer by remember { mutableStateOf("") }
    BasicTextField(
        value = buffer,
        onValueChange = { next ->
            val keep = buffer.commonPrefixWith(next).length
            val erased = buffer.length - keep
            val typed = next.substring(keep)
            if (mods != 0 && erased == 0 && typed.length == 1 && typed[0].lowercaseChar() in "abcdefghijklmnopqrstuvwxyz0123456789") {
                // A latched modifier turns the next character into a shortcut, such as CTRL then C.
                actions.send(Action.KEY_PRESS, mods.toDouble(), typed.lowercase())
                clearMods()
                return@BasicTextField
            }
            if (erased > 0) actions.send(Action.KEY_ERASE, erased.toDouble())
            if (typed.isNotEmpty()) actions.send(Action.KEY_TYPE, text = typed)
            // Start over at a word boundary now and then so the field never grows without bound.
            buffer = if (next.length > 120 && next.endsWith(' ')) "" else next
        },
        modifier = Modifier.padding(top = 10.dp).fillMaxWidth().height(52.dp).semantics { contentDescription = "Type on the desktop" },
        enabled = live,
        singleLine = true,
        textStyle = Type.Body.copy(color = Ink.Bone),
        cursorBrush = SolidColor(Ink.Amber),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Send,
        ),
        keyboardActions = KeyboardActions(onSend = {
            actions.send(Action.KEY_PRESS, 0.0, "Return")
            buffer = ""
        }),
        decorationBox = { field ->
            Box(Modifier.fillMaxSize().border(1.dp, Ink.Rule).padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
                if (buffer.isEmpty()) BasicText(if (live) "Tap here and type" else "Offline", style = Type.Body.copy(color = Ink.Faint))
                field()
            }
        },
    )
}

@Composable
private fun Slides(live: Boolean, actions: RemoteActions) {
    // A dimmed phone mid-talk is the last thing a presenter needs.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    fun press(key: String) = actions.send(Action.KEY_PRESS, 0.0, key)
    Column(Modifier.fillMaxSize()) {
        SectionHeader(1, "SLIDES", Modifier.padding(top = 16.dp), note = "VOLUME KEYS ALSO FLIP")
        Row(Modifier.weight(1f).fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Key({ press("Left") }, "Previous slide", Modifier.weight(1f).fillMaxSize(), enabled = live) { ink ->
                BasicText("PREV", style = Type.Numeral.copy(color = ink))
            }
            Key({ press("Right") }, "Next slide", Modifier.weight(1.5f).fillMaxSize(), enabled = live, active = live) { ink ->
                BasicText("NEXT", style = Type.Numeral.copy(color = ink))
            }
        }
        KeyRow(Modifier.padding(top = 8.dp)) {
            TextKey("START", { press("F5") }, Modifier.weight(1f).height(52.dp), enabled = live)
            TextKey("BLANK", { press("b") }, Modifier.weight(1f).height(52.dp), enabled = live)
            TextKey("END", { press("Escape") }, Modifier.weight(1f).height(52.dp), enabled = live)
        }
    }
}
