package dev.varch.controller

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import dev.varch.controller.net.Action
import dev.varch.controller.net.VarchClient
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * One-shot actions for surfaces that live outside the app: quick-settings
 * tiles and the home-screen widgets. Each press is a single HTTP request.
 */
object OneShot {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client by lazy { VarchClient() }

    /** Actions these surfaces may send. */
    val allowed = setOf(
        Action.PLAY_PAUSE, Action.NEXT, Action.PREVIOUS, Action.MUTE, Action.VOLUME_STEP,
        Action.WORKSPACE, Action.MIC_MUTE, Action.NIGHT_LIGHT, Action.LOCK,
    )

    /** [onDone] hears whether the desktop answered at all. */
    fun send(context: Context, action: String, value: Double = 0.0, onDone: (reached: Boolean) -> Unit = {}) {
        val app = context.applicationContext
        val pairing = Store(app).load()
        if (pairing == null || action !in allowed) {
            if (pairing == null) toast(app, R.string.shortcut_not_paired)
            onDone(pairing != null)
            return
        }
        scope.launch {
            var reached = true
            try {
                if (!client.action(pairing.endpoint, pairing.token, action, value).ok) toast(app, R.string.shortcut_failed)
            } catch (_: IOException) {
                reached = false
                toast(app, R.string.shortcut_unreachable)
            } finally {
                onDone(reached)
            }
        }
    }

    private fun toast(context: Context, message: Int) {
        Handler(Looper.getMainLooper()).post { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
    }
}

abstract class ActionTile(private val action: String) : TileService() {
    override fun onStartListening() {
        val tile = qsTile ?: return
        tile.state = if (Store(this).load() == null) Tile.STATE_UNAVAILABLE else Tile.STATE_INACTIVE
        tile.updateTile()
    }

    override fun onClick() = OneShot.send(this, action)
}

class PlayPauseTile : ActionTile(Action.PLAY_PAUSE)

class MuteTile : ActionTile(Action.MUTE)

class LockTile : ActionTile(Action.LOCK)

/** Receives widget key presses. One without an action only asks for fresh state. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val action = intent.getStringExtra(EXTRA_ACTION)
        if (action == null) {
            Widgets.refresh(context) { pending.finish() }
            return
        }
        OneShot.send(context, action, intent.getDoubleExtra(EXTRA_VALUE, 0.0)) { reached ->
            if (reached) {
                Widgets.refresh(context, Widgets.SETTLE_MS) { pending.finish() }
            } else {
                Widgets.unreachable(context)
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_ACTION = "action"
        const val EXTRA_VALUE = "value"
    }
}
