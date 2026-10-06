package dev.varch.controller

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.RemoteViews
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
 * tiles and the home-screen widget. Each press is a single HTTP request.
 */
object OneShot {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client by lazy { VarchClient() }

    /** Actions these surfaces may send. */
    val allowed = setOf(Action.PLAY_PAUSE, Action.MUTE, Action.LOCK)

    fun send(context: Context, action: String, onDone: () -> Unit = {}) {
        val app = context.applicationContext
        val pairing = Store(app).load()
        if (pairing == null || action !in allowed) {
            if (pairing == null) toast(app, R.string.shortcut_not_paired)
            onDone()
            return
        }
        scope.launch {
            try {
                if (!client.action(pairing.endpoint, pairing.token, action).ok) toast(app, R.string.shortcut_failed)
            } catch (_: IOException) {
                toast(app, R.string.shortcut_unreachable)
            } finally {
                onDone()
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

/** Receives widget button presses. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.getStringExtra(EXTRA_ACTION) ?: return
        val pending = goAsync()
        OneShot.send(context, action) { pending.finish() }
    }

    companion object {
        const val EXTRA_ACTION = "action"
    }
}

class RemoteWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = RemoteViews(context.packageName, R.layout.widget_remote)
        val buttons = listOf(R.id.widget_play to Action.PLAY_PAUSE, R.id.widget_mute to Action.MUTE, R.id.widget_lock to Action.LOCK)
        buttons.forEachIndexed { index, (view, action) ->
            val intent = Intent(context, ActionReceiver::class.java).putExtra(ActionReceiver.EXTRA_ACTION, action)
            views.setOnClickPendingIntent(
                view,
                PendingIntent.getBroadcast(context, index, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
            )
        }
        ids.forEach { manager.updateAppWidget(it, views) }
    }
}
