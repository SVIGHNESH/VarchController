package dev.varch.controller.ui

import android.app.Activity
import android.content.ClipboardManager
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.varch.controller.CastService
import dev.varch.controller.RemoteUi
import dev.varch.controller.RemoteViewModel
import dev.varch.controller.net.Action

/** Moves text, links and screenshots between the phone and the desktop. */
@Composable
fun ShareScreen(state: RemoteUi, actions: RemoteActions) {
    val live = state.live
    val context = LocalContext.current
    var draft by rememberSaveable { mutableStateOf("") }
    var viewing by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 8.dp)) {
        SectionHeader(1, "CLIPBOARD", Modifier.padding(top = 16.dp))
        KeyRow(Modifier.padding(top = 10.dp)) {
            TextKey(
                "PHONE → DESK",
                {
                    val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
                    actions.sendClipboard(clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty())
                },
                Modifier.weight(1f).height(52.dp),
                enabled = live,
            )
            TextKey("DESK → PHONE", actions::fetchClipboard, Modifier.weight(1f).height(52.dp), enabled = live)
        }
        state.clip?.let { clip ->
            BasicText(
                clip.ifEmpty { "(empty)" },
                Modifier.padding(top = 8.dp).fillMaxWidth().border(1.dp, Ink.Rule).padding(12.dp),
                style = Type.Body.copy(fontSize = Type.Key.fontSize, color = Ink.Bone),
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }

        SectionHeader(2, "SEND TEXT OR A LINK", Modifier.padding(top = 22.dp))
        BasicTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.padding(top = 10.dp).fillMaxWidth().heightIn(min = 84.dp).semantics { contentDescription = "Text to send" },
            textStyle = Type.Body.copy(color = Ink.Bone),
            cursorBrush = SolidColor(Ink.Amber),
            maxLines = 5,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            decorationBox = { field ->
                Box(Modifier.fillMaxWidth().border(1.dp, Ink.Rule).padding(14.dp)) {
                    if (draft.isEmpty()) BasicText("Paste or type here", style = Type.Body.copy(color = Ink.Faint))
                    field()
                }
            },
        )
        KeyRow(Modifier.padding(top = 8.dp)) {
            TextKey("TO CLIPBOARD", { actions.sendClipboard(draft) }, Modifier.weight(1f).height(52.dp), enabled = live && draft.isNotBlank())
            TextKey("OPEN LINK", { actions.share(draft) }, Modifier.weight(1f).height(52.dp), enabled = live && RemoteViewModel.isLink(draft.trim()))
        }

        SectionHeader(3, "DESKTOP SCREEN", Modifier.padding(top = 22.dp), note = if (state.screenshot != null) "TAP IMAGE TO ZOOM" else null)
        KeyRow(Modifier.padding(top = 10.dp)) {
            TextKey(if (state.capturing) "CAPTURING" else "CAPTURE", actions::capture, Modifier.weight(1f).height(52.dp), enabled = live && !state.capturing)
            TextKey("WATCH LIVE", { actions.setWatching(true) }, Modifier.weight(1f).height(52.dp), enabled = live)
        }
        state.screenshot?.let { shot ->
            Image(
                shot,
                contentDescription = "Desktop screenshot",
                modifier = Modifier.padding(top = 8.dp).fillMaxWidth().border(1.dp, Ink.Rule).clickable { viewing = true },
                contentScale = ContentScale.FillWidth,
            )
        }

        SectionHeader(4, "THIS PHONE'S SCREEN", Modifier.padding(top = 22.dp))
        CastKey(live)
    }

    if (state.watching) LiveView(state, actions)

    val shot = state.screenshot
    if (viewing && shot != null) Viewer(shot) { viewing = false }
}

/** Starts and stops casting this phone's screen to the desktop. */
@Composable
private fun CastKey(live: Boolean) {
    val context = LocalContext.current
    // Android shows its own prompt before any app may capture the screen.
    val prompt = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val grant = result.data
        if (result.resultCode == Activity.RESULT_OK && grant != null) CastService.start(context, grant)
    }
    val casting = CastService.casting
    TextKey(
        if (casting) "STOP CASTING" else "CAST TO DESKTOP",
        {
            if (casting) {
                CastService.stop(context)
            } else {
                prompt.launch(context.getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
            }
        },
        Modifier.padding(top = 10.dp).fillMaxWidth().height(52.dp),
        enabled = live || casting,
        active = casting,
    )
    val error = CastService.error
    if (error != null) ErrorLine(error) else Hint("Opens a window on the desktop showing this screen. Sound is not sent.")
}

/**
 * The desktop's screen, live. The whole picture is a trackpad, so the phone
 * can point and click at what it shows. Turn the phone sideways for a bigger view.
 */
@Composable
private fun LiveView(state: RemoteUi, actions: RemoteActions) {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    Dialog({ actions.setWatching(false) }, DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Ink.Ground).safeDrawingPadding()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val frame = state.frame
                if (frame == null) {
                    BasicText("WAITING FOR THE DESKTOP", style = Type.Label)
                } else {
                    Image(frame, contentDescription = "Live desktop screen", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                }
                Trackpad(
                    onMove = actions::movePointer,
                    onScroll = actions::scrollPointer,
                    onClick = { actions.send(Action.POINTER_CLICK, it.toDouble()) },
                    modifier = Modifier.fillMaxSize(),
                    bare = true,
                )
            }
            KeyRow(Modifier.padding(8.dp)) {
                PressKey("LEFT", "Left button", { actions.send(Action.POINTER_DOWN, 0.0) }, { actions.send(Action.POINTER_UP, 0.0) }, Modifier.weight(1f).height(48.dp))
                TextKey("CLOSE", { actions.setWatching(false) }, Modifier.weight(1f).height(48.dp))
                PressKey("RIGHT", "Right button", { actions.send(Action.POINTER_DOWN, 1.0) }, { actions.send(Action.POINTER_UP, 1.0) }, Modifier.weight(1f).height(48.dp))
            }
        }
    }
}

/** Full-screen image with pinch zoom and pan. A tap closes it. */
@Composable
private fun Viewer(image: ImageBitmap, onClose: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Ink.Ground)
                .pointerInput(Unit) { detectTapGestures(onTap = { onClose() }) }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 8f)
                        offset = if (scale == 1f) Offset.Zero else offset + pan
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                image,
                contentDescription = "Desktop screenshot",
                modifier = Modifier.fillMaxWidth().graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
                contentScale = ContentScale.FillWidth,
            )
        }
    }
}
