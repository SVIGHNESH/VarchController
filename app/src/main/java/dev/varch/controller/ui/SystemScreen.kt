package dev.varch.controller.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.varch.controller.BatteryAlerts
import dev.varch.controller.RemoteUi
import dev.varch.controller.net.Action
import dev.varch.controller.net.SystemState
import kotlinx.coroutines.delay

private val PROFILE_LABELS = mapOf("power-saver" to "SAVER", "balanced" to "BALANCED", "performance" to "FAST")

/** Status readouts, system toggles and power controls. */
@Composable
fun SystemScreen(state: RemoteUi, actions: RemoteActions, themesOpen: Boolean = false) {
    val live = state.live && state.system != null
    val system = state.system ?: SystemState()
    var themes by rememberSaveable { mutableStateOf(themesOpen) }

    // Sections for features this desktop lacks are skipped, so numbers are handed out as they appear.
    var section = 0
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 8.dp)) {
        SectionHeader(++section, "STATUS", Modifier.padding(top = 16.dp))
        KeyRow(Modifier.padding(top = 10.dp)) {
            val known = state.system != null
            fun figure(value: Int) = if (known) value.toString() else "--"
            val battery = system.battery
            if (battery != null) {
                val low = !battery.charging && battery.percent <= BatteryAlerts.LOW_PERCENT
                Readout(if (battery.charging) "CHRG" else "BAT", figure(battery.percent), Modifier.weight(1f), "%", if (low) Ink.Red else Ink.Bone)
            }
            Readout("CPU", figure(system.cpu), Modifier.weight(1f), "%")
            Readout("MEM", figure(system.memory), Modifier.weight(1f), "%")
            Readout("TEMP", figure(system.temperature), Modifier.weight(1f), "°", if (system.temperature >= 85) Ink.Red else Ink.Bone)
        }

        SectionHeader(++section, "TOGGLES", Modifier.padding(top = 22.dp))
        val toggles = buildList<@Composable (Modifier) -> Unit> {
            system.nightLight?.let { on ->
                add { m -> TextKey("NIGHT LIGHT", { actions.send(Action.NIGHT_LIGHT) }, m, enabled = live, active = on) }
            }
            add { m -> TextKey(if (system.micMuted) "MIC MUTED" else "MIC LIVE", { actions.send(Action.MIC_MUTE) }, m, enabled = live, active = system.micMuted, accent = Ink.Bone) }
            system.bluetooth?.let { on ->
                add { m -> TextKey("BLUETOOTH", { actions.send(Action.BLUETOOTH, if (on) 0.0 else 1.0) }, m, enabled = live, active = on) }
            }
            system.wifi?.let { on ->
                // Turning Wi-Fi off usually cuts this remote off too, so it takes a deliberate hold.
                add { m ->
                    if (on) {
                        HoldKey("WI-FI: HOLD TO CUT", "Turn Wi-Fi off", { actions.send(Action.WIFI, 0.0) }, m, enabled = live)
                    } else {
                        TextKey("WI-FI OFF", { actions.send(Action.WIFI, 1.0) }, m, enabled = live)
                    }
                }
            }
        }
        Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (pair in toggles.chunked(2)) {
                KeyRow {
                    for (toggle in pair) toggle(Modifier.weight(1f).height(52.dp))
                    if (pair.size == 1) Box(Modifier.weight(1f))
                }
            }
        }

        if (system.profiles.isNotEmpty()) {
            SectionHeader(++section, "POWER PROFILE", Modifier.padding(top = 22.dp))
            KeyRow(Modifier.padding(top = 10.dp)) {
                for (profile in system.profiles) {
                    TextKey(PROFILE_LABELS[profile] ?: profile.uppercase(), { actions.send(Action.PROFILE, text = profile) }, Modifier.weight(1f).height(52.dp), enabled = live, active = profile == system.profile)
                }
            }
        }

        if (system.sinks.isNotEmpty()) {
            SectionHeader(++section, "AUDIO OUTPUT", Modifier.padding(top = 22.dp))
            Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (sink in system.sinks) {
                    TextKey(sink.label.uppercase(), { actions.send(Action.SINK, text = sink.name) }, Modifier.fillMaxWidth().height(52.dp), enabled = live, active = sink.name == system.sink)
                }
            }
        }

        system.kbdBacklight?.let { level ->
            SectionHeader(++section, "KEYBOARD LIGHT", Modifier.padding(top = 22.dp))
            KeyRow(Modifier.padding(top = 10.dp)) {
                for ((text, value) in listOf("OFF" to 0f, "LOW" to 0.5f, "HIGH" to 1f)) {
                    val current = if (level < 0.25f) 0f else if (level < 0.75f) 0.5f else 1f
                    TextKey(text, { actions.send(Action.KBD_BACKLIGHT, value.toDouble()) }, Modifier.weight(1f).height(52.dp), enabled = live, active = current == value)
                }
            }
        }

        if (state.catalog.themes.isNotEmpty()) {
            SectionHeader(++section, "THEME", Modifier.padding(top = 22.dp), note = if (themes) "TAP TO APPLY" else null)
            TextKey(
                if (themes) "CLOSE LIST" else system.theme.uppercase().ifEmpty { "CHOOSE" },
                { themes = !themes },
                Modifier.padding(top = 10.dp).fillMaxWidth().height(52.dp),
                enabled = live,
            )
            if (themes) {
                Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (pair in state.catalog.themes.chunked(2)) {
                        KeyRow {
                            for (theme in pair) {
                                TextKey(theme.uppercase(), { actions.send(Action.THEME, text = theme) }, Modifier.weight(1f).height(46.dp), enabled = live, active = theme == system.theme)
                            }
                            if (pair.size == 1) Box(Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        SectionHeader(++section, "POWER", Modifier.padding(top = 22.dp))
        KeyRow(Modifier.padding(top = 10.dp)) {
            TextKey("LOCK", { actions.send(Action.LOCK) }, Modifier.weight(1f).height(52.dp), enabled = state.live)
            HoldKey("HOLD: SUSPEND", "Suspend", { actions.send(Action.SUSPEND) }, Modifier.weight(1.4f).height(52.dp), enabled = state.live)
        }
        KeyRow(Modifier.padding(top = 8.dp)) {
            HoldKey("HOLD: REBOOT", "Reboot", { actions.send(Action.REBOOT) }, Modifier.weight(1f).height(52.dp), enabled = state.live)
            HoldKey("HOLD: SHUT DOWN", "Shut down", { actions.send(Action.SHUTDOWN) }, Modifier.weight(1f).height(52.dp), enabled = state.live)
        }

        SectionHeader(++section, "THIS PHONE", Modifier.padding(top = 22.dp))
        PhoneSettings(state, actions)
    }
}

@Composable
private fun PhoneSettings(state: RemoteUi, actions: RemoteActions) {
    // Android 13+ asks before an app may post notifications.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) actions.setAlerts(true)
    }
    var confirming by remember { mutableStateOf(false) }
    LaunchedEffect(confirming) {
        if (confirming) {
            delay(3000)
            confirming = false
        }
    }
    KeyRow(Modifier.padding(top = 10.dp)) {
        TextKey(
            "BATTERY ALERTS",
            {
                when {
                    state.alerts -> actions.setAlerts(false)
                    Build.VERSION.SDK_INT >= 33 -> permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else -> actions.setAlerts(true)
                }
            },
            Modifier.weight(1.4f).height(52.dp),
            active = state.alerts,
        )
        TextKey(
            if (confirming) "SURE?" else "UNPAIR",
            { if (confirming) actions.unpair() else confirming = true },
            Modifier.weight(1f).height(52.dp),
            active = confirming,
            accent = Ink.Red,
        )
    }
    TextKey(
        "MATCH DESKTOP THEME",
        { actions.setMatchTheme(!state.matchTheme) },
        Modifier.padding(top = 8.dp).fillMaxWidth().height(52.dp),
        active = state.matchTheme,
    )
    Hint("Alerts check the desktop every 15 minutes and notify when its battery is low or full.")
}
