package dev.varch.controller.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.varch.controller.Link
import dev.varch.controller.RemoteUi

enum class Tab(val label: String) {
    Deck("DECK"),
    Pad("PAD"),
    Desk("DESK"),
    System("SYS"),
    Share("SHARE"),
}

/** The paired remote: a status header, the selected section, and the section tabs. */
@Composable
fun RemoteShell(state: RemoteUi, actions: RemoteActions, initialTab: Tab = Tab.Deck) {
    var selected by rememberSaveable { mutableIntStateOf(initialTab.ordinal) }
    val tab = Tab.entries[selected]
    Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 20.dp)) {
        Header(state)
        Rule()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                Tab.Deck -> DeckScreen(state, actions)
                Tab.Pad -> PadScreen(state, actions)
                Tab.Desk -> DeskScreen(state, actions)
                Tab.System -> SystemScreen(state, actions)
                Tab.Share -> ShareScreen(state, actions)
            }
        }
        TabBar(Tab.entries.map { it.label }, selected, { selected = it }, Modifier.padding(top = 10.dp, bottom = 12.dp))
    }
}

@Composable
private fun Header(state: RemoteUi) {
    Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            BasicText(state.hostName, style = Type.Title.copy(letterSpacing = Type.Key.letterSpacing), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                val (text, color) = when (state.link) {
                    Link.Online -> "ONLINE" to Ink.Amber
                    Link.Connecting -> "CONNECTING" to Ink.Dim
                    Link.Offline -> "OFFLINE" to Ink.Red
                }
                Box(Modifier.size(7.dp).background(color))
                BasicText(text, Modifier.padding(start = 8.dp), style = Type.Label.copy(color = color))
                // A notice (a failed action, a finished transfer) briefly takes the address's place.
                BasicText(
                    state.notice ?: state.address,
                    Modifier.padding(start = 12.dp),
                    style = Type.Label.copy(letterSpacing = Type.Body.letterSpacing, color = if (state.notice != null) Ink.Bone else Ink.Dim),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        val battery = state.system?.battery
        if (battery != null) {
            val low = !battery.charging && battery.percent <= 20
            Column(Modifier.padding(start = 12.dp), horizontalAlignment = Alignment.End) {
                BasicText("${battery.percent}%", style = Type.Title.copy(color = if (low) Ink.Red else Ink.Bone))
                BasicText(
                    when {
                        battery.full -> "FULL"
                        battery.charging -> "CHARGING"
                        else -> "BATTERY"
                    },
                    Modifier.padding(top = 4.dp),
                    style = Type.Label.copy(color = if (battery.charging) Ink.Amber else Ink.Dim),
                )
            }
        }
    }
}
