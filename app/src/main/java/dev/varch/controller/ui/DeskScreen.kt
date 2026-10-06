package dev.varch.controller.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.varch.controller.RemoteUi
import dev.varch.controller.net.Action
import dev.varch.controller.net.Window

/** Open windows and the app launcher. */
@Composable
fun DeskScreen(state: RemoteUi, actions: RemoteActions, initiallyOpen: String? = null) {
    val live = state.live
    val desktop = state.desktop
    val active = desktop?.workspaces?.active ?: 0
    // The current workspace's windows come first.
    val windows = desktop?.windows.orEmpty().sortedBy { if (it.workspace == active) 0 else it.workspace }
    var open by rememberSaveable { mutableStateOf(initiallyOpen) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 8.dp)) {
        SectionHeader(1, "WINDOWS", Modifier.padding(top = 16.dp), note = if (windows.isEmpty()) null else "${windows.size} OPEN")
        if (windows.isEmpty()) Hint(if (desktop == null) "Waiting for the desktop." else "No windows are open.")
        Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (window in windows) {
                WindowRow(window, live, expanded = open == window.address, onToggle = { open = if (open == window.address) null else window.address }, actions)
            }
        }

        SectionHeader(2, "LAUNCH", Modifier.padding(top = 22.dp))
        val apps = state.catalog.apps
        if (apps.isEmpty()) Hint("Add apps to ~/.config/varchd/apps.json on the desktop.")
        Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (pair in apps.chunked(2)) {
                KeyRow {
                    for (app in pair) {
                        TextKey(app.name.uppercase(), { actions.send(Action.LAUNCH, text = app.id) }, Modifier.weight(1f).height(56.dp), enabled = live)
                    }
                    if (pair.size == 1) Box(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun WindowRow(window: Window, live: Boolean, expanded: Boolean, onToggle: () -> Unit, actions: RemoteActions) {
    Column(Modifier.fillMaxWidth().border(1.dp, if (expanded) Ink.Faint else Ink.Rule)) {
        Key(onToggle, "${window.appClass}, ${window.title}, workspace ${window.workspace}", Modifier.fillMaxWidth().height(60.dp), enabled = live, outline = Ink.Ground) { ink ->
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                // The focused window carries the signal colour on its leading edge.
                Box(Modifier.width(3.dp).fillMaxHeight().background(if (window.focused && live) Ink.Amber else Ink.Ground))
                BasicText(window.workspace.toString(), Modifier.width(34.dp).padding(start = 12.dp), style = Type.Title.copy(color = if (live) Ink.Amber else Ink.Faint))
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    BasicText(window.appClass.ifEmpty { "window" }, style = Type.Key.copy(color = ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    BasicText(
                        window.title,
                        Modifier.padding(top = 2.dp),
                        style = Type.Body.copy(fontSize = Type.Label.fontSize * 1.1f, lineHeight = Type.Label.lineHeight * 1.1f, color = if (ink == Ink.Ground) Ink.Ground else Ink.Dim),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val flags = listOfNotNull("FULL".takeIf { window.fullscreen }, "FLOAT".takeIf { window.floating }).joinToString(" ")
                if (flags.isNotEmpty()) BasicText(flags, Modifier.padding(end = 12.dp), style = Type.Label.copy(color = ink))
            }
        }
        if (expanded) {
            fun act(action: String, value: Double = 0.0) = actions.send(action, value, window.address)
            Column(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                KeyRow {
                    TextKey("FOCUS", { act(Action.WINDOW_FOCUS) }, Modifier.weight(1f).height(46.dp), enabled = live)
                    TextKey("FULL", { act(Action.WINDOW_FULLSCREEN) }, Modifier.weight(1f).height(46.dp), enabled = live, active = window.fullscreen, accent = Ink.Bone)
                    TextKey("FLOAT", { act(Action.WINDOW_FLOAT) }, Modifier.weight(1f).height(46.dp), enabled = live, active = window.floating, accent = Ink.Bone)
                }
                BasicText("MOVE TO WORKSPACE", style = Type.Label)
                for (row in (1..10).chunked(5)) {
                    KeyRow {
                        for (id in row) {
                            TextKey(id.toString(), { act(Action.WINDOW_MOVE, id.toDouble()) }, Modifier.weight(1f).height(44.dp), enabled = live && id != window.workspace, active = id == window.workspace, accent = Ink.Bone)
                        }
                    }
                }
                HoldKey("HOLD TO CLOSE", "Close window", { act(Action.WINDOW_CLOSE) }, Modifier.fillMaxWidth().height(46.dp), enabled = live)
            }
        }
    }
}
