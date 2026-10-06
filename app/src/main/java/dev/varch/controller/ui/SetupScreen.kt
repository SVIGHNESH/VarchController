package dev.varch.controller.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.varch.controller.CODE_LENGTH
import dev.varch.controller.PairStep
import dev.varch.controller.SetupUi
import dev.varch.controller.net.Endpoint

@Composable
fun SetupScreen(
    state: SetupUi,
    onAddressChange: (String) -> Unit,
    onPairAddress: () -> Unit,
    onPick: (Endpoint) -> Unit,
    onCodeChange: (String) -> Unit,
    onCancel: () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 28.dp)) {
        BasicText("VARCH", style = Type.Wordmark)
        BasicText("CONTROLLER", Modifier.padding(top = 2.dp, bottom = 28.dp), style = Type.Label.copy(color = Ink.Amber, letterSpacing = Type.Wordmark.fontSize * 0.14f))
        Rule()

        val step = state.step
        if (step is PairStep.EnterCode) {
            CodeEntry(step, state.code, state.error, onCodeChange, onCancel)
        } else {
            val busy = step is PairStep.Contacting
            SectionHeader(1, "ON THIS NETWORK", Modifier.padding(top = 22.dp))
            Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.found.isEmpty()) Scanning()
                for (found in state.found) {
                    val contacting = (step as? PairStep.Contacting)?.endpoint == found.endpoint
                    Key(
                        { onPick(found.endpoint) },
                        "Pair with ${found.name}",
                        Modifier.fillMaxWidth().height(60.dp),
                        enabled = !busy || contacting,
                        active = contacting,
                    ) { ink ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            BasicText(found.name, Modifier.weight(1f), style = Type.Title.copy(color = ink), maxLines = 1)
                            BasicText(if (contacting) "CONTACTING" else found.endpoint.label, style = Type.Label.copy(color = ink, letterSpacing = Type.Body.letterSpacing))
                        }
                    }
                }
            }

            SectionHeader(2, "OR BY ADDRESS", Modifier.padding(top = 26.dp))
            KeyRow(Modifier.padding(top = 10.dp)) {
                BasicTextField(
                    value = state.address,
                    onValueChange = onAddressChange,
                    modifier = Modifier.weight(1f).height(52.dp).semantics { contentDescription = "Desktop address" },
                    enabled = !busy,
                    singleLine = true,
                    textStyle = Type.Body.copy(color = Ink.Bone),
                    cursorBrush = SolidColor(Ink.Amber),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onGo = { onPairAddress() }),
                    decorationBox = { field ->
                        Box(Modifier.fillMaxSize().border(1.dp, Ink.Rule).padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
                            if (state.address.isEmpty()) BasicText("192.168.1.10", style = Type.Body.copy(color = Ink.Faint))
                            field()
                        }
                    },
                )
                val manual = (step as? PairStep.Contacting)?.let { c -> state.found.none { it.endpoint == c.endpoint } } == true
                TextKey("PAIR", onPairAddress, Modifier.width(96.dp).height(52.dp), enabled = !busy && state.address.isNotBlank() || manual, active = manual)
            }
            ErrorLine(state.error)

            BasicText(
                "Run varchd on the desktop and keep this phone on the same network. Pairing shows a one-time code on the desktop.",
                Modifier.padding(top = 26.dp),
                style = Type.Body.copy(fontSize = Type.Key.fontSize),
            )
        }
    }
}

@Composable
private fun Scanning() {
    val blink by rememberInfiniteTransition(label = "cursor").animateFloat(
        initialValue = 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = 1000
                1f at 0
                1f at 499
                0f at 500
                0f at 999
            },
            RepeatMode.Restart,
        ),
        label = "cursor",
    )
    Row(Modifier.fillMaxWidth().height(60.dp).border(1.dp, Ink.Rule).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText("SCANNING", style = Type.Key.copy(color = Ink.Dim))
        Box(Modifier.padding(start = 6.dp).width(8.dp).height(15.dp).alpha(blink).background(Ink.Amber))
    }
}

@Composable
private fun CodeEntry(step: PairStep.EnterCode, code: String, error: String?, onCodeChange: (String) -> Unit, onCancel: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    SectionHeader(1, "PAIRING CODE", Modifier.padding(top = 22.dp), note = if (step.verifying) "CHECKING" else null, noteColor = Ink.Amber)
    BasicText(step.hostName, Modifier.padding(top = 14.dp), style = Type.Title)
    BasicText("Enter the 6-digit code now showing on the desktop.", Modifier.padding(top = 4.dp), style = Type.Body)

    BasicTextField(
        value = code,
        onValueChange = onCodeChange,
        modifier = Modifier.padding(top = 20.dp).fillMaxWidth().focusRequester(focus).semantics { contentDescription = "Pairing code" },
        enabled = !step.verifying,
        singleLine = true,
        cursorBrush = SolidColor(Color.Transparent),
        textStyle = Type.Body.copy(color = Color.Transparent),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        decorationBox = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                for (i in 0 until CODE_LENGTH) {
                    if (i == CODE_LENGTH / 2) Spacer(Modifier.width(6.dp))
                    val digit = code.getOrNull(i)
                    Box(
                        Modifier.weight(1f).height(64.dp).background(Ink.Panel).border(1.dp, if (i == code.length && !step.verifying) Ink.Amber else Ink.Rule),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (digit != null) BasicText(digit.toString(), style = Type.Numeral.copy(fontSize = Type.Wordmark.fontSize * 0.64f))
                    }
                }
            }
        },
    )
    ErrorLine(error)
    TextKey("CANCEL", onCancel, Modifier.padding(top = 24.dp).fillMaxWidth().height(52.dp), enabled = !step.verifying)
}

@Composable
fun ErrorLine(error: String?) {
    if (error != null) {
        BasicText(error, Modifier.padding(top = 14.dp), style = Type.Body.copy(fontSize = Type.Key.fontSize, color = Ink.Red))
    }
}
