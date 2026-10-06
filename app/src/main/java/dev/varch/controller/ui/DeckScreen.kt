package dev.varch.controller.ui

import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.varch.controller.RemoteUi
import dev.varch.controller.net.Action
import dev.varch.controller.net.DesktopState
import dev.varch.controller.net.Media
import dev.varch.controller.net.Workspaces
import kotlinx.coroutines.delay

private const val WORKSPACE_COUNT = 10

/** Media, levels and workspaces: the controls reached for most often. */
@Composable
fun DeckScreen(state: RemoteUi, actions: RemoteActions) {
    val desktop = state.desktop
    val live = state.live
    val shown = desktop ?: DesktopState()

    // Fills the screen when there is room and scrolls when there is not
    // (small phones, large text, landscape).
    BoxWithConstraints(Modifier.fillMaxSize()) {
        FillColumn(
            minFill = 230.dp,
            modifier = Modifier.verticalScroll(rememberScrollState()).heightIn(min = maxHeight),
            top = {
                SectionHeader(1, "NOW PLAYING", Modifier.padding(top = 16.dp), note = shown.media.name.uppercase().ifEmpty { null })
                NowPlaying(state, shown.media, live, known = desktop != null, actions)
                SectionHeader(2, "LEVELS", Modifier.padding(top = 20.dp))
            },
            fill = {
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Fader(
                        label = "VOL",
                        value = shown.volume.level.coerceIn(0f, 1f),
                        onChange = actions::setVolume,
                        modifier = Modifier.weight(1f),
                        enabled = live,
                        known = desktop != null,
                        muted = shown.volume.muted,
                        onLabelClick = { actions.send(Action.MUTE) },
                        labelAction = if (shown.volume.muted) "Unmute" else "Mute",
                    )
                    Fader(
                        label = "BRT",
                        value = shown.brightness.coerceIn(0f, 1f),
                        onChange = actions::setBrightness,
                        modifier = Modifier.weight(1f),
                        enabled = live,
                        known = desktop != null,
                    )
                }
            },
            bottom = {
                SectionHeader(3, "WORKSPACE", Modifier.padding(top = 20.dp))
                WorkspaceGrid(shown.workspaces, live) { actions.send(Action.WORKSPACE, it.toDouble()) }
            },
        )
    }
}

@Composable
private fun NowPlaying(state: RemoteUi, media: Media, live: Boolean, known: Boolean, actions: RemoteActions) {
    val has = media.available
    Row(Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        val art = state.art
        if (has && art != null) {
            Image(
                art,
                contentDescription = null,
                modifier = Modifier.padding(end = 12.dp).size(52.dp).border(1.dp, Ink.Rule),
                contentScale = ContentScale.Crop,
            )
        }
        Column(Modifier.weight(1f)) {
            BasicText(
                when {
                    has -> media.title.ifEmpty { "Untitled" }
                    known -> "Nothing playing"
                    else -> "Waiting for desktop"
                },
                style = Type.Title.copy(color = if (has) Ink.Bone else Ink.Faint),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            BasicText(
                when {
                    has -> media.artist.ifEmpty { media.status }
                    known -> "Start something on the desktop"
                    else -> "Controls unlock once it answers"
                },
                Modifier.padding(top = 2.dp),
                style = Type.Body.copy(fontSize = Type.Key.fontSize, color = if (has) Ink.Dim else Ink.Faint),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    val enabled = live && has
    if (has && media.length > 0f) {
        // The desktop reports the position occasionally; the phone advances it in between.
        var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
        val ticking = media.playing && state.mediaAt > 0
        LaunchedEffect(ticking) {
            while (ticking) {
                now = SystemClock.elapsedRealtime()
                delay(500)
            }
        }
        val advance = if (ticking) (now - state.mediaAt).coerceAtLeast(0) / 1000f else 0f
        SeekBar(
            position = (media.position + advance).coerceIn(0f, media.length),
            length = media.length,
            onSeek = { actions.send(Action.SEEK, it.toDouble()) },
            modifier = Modifier.padding(top = 6.dp),
            enabled = enabled && media.canSeek,
        )
    }
    KeyRow(Modifier.padding(top = 8.dp)) {
        Key({ actions.send(Action.PREVIOUS) }, "Previous track", Modifier.weight(1f).height(56.dp), enabled) { GlyphIcon(Glyph.Previous, it) }
        Key(
            { actions.send(Action.PLAY_PAUSE) },
            if (media.playing) "Pause" else "Play",
            Modifier.weight(1.6f).height(56.dp),
            enabled,
            active = media.playing,
        ) { GlyphIcon(if (media.playing) Glyph.Pause else Glyph.Play, it) }
        Key({ actions.send(Action.NEXT) }, "Next track", Modifier.weight(1f).height(56.dp), enabled) { GlyphIcon(Glyph.Next, it) }
    }
    if (media.players.size > 1) {
        Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (player in media.players) {
                TextKey(
                    player.name.uppercase(),
                    { actions.send(Action.SELECT_PLAYER, text = player.id) },
                    Modifier.height(36.dp),
                    enabled = live,
                    active = player.id == media.player,
                    accent = Ink.Bone,
                )
            }
        }
    }
}

@Composable
private fun WorkspaceGrid(workspaces: Workspaces, live: Boolean, onWorkspace: (Int) -> Unit) {
    Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in (1..WORKSPACE_COUNT).chunked(WORKSPACE_COUNT / 2)) {
            KeyRow {
                for (id in row) {
                    val active = workspaces.active == id
                    Key(
                        { onWorkspace(id) },
                        "Workspace $id" + if (id in workspaces.occupied) ", has windows" else "",
                        Modifier.weight(1f).height(48.dp),
                        enabled = live,
                        active = active,
                    ) { ink ->
                        Box(Modifier.fillMaxSize()) {
                            BasicText(id.toString(), Modifier.align(Alignment.Center), style = Type.Key.copy(color = ink, fontSize = Type.Title.fontSize))
                            if (id in workspaces.occupied && !active) {
                                Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(5.dp).background(if (live) Ink.Amber else Ink.Faint))
                            }
                        }
                    }
                }
            }
        }
    }
}
