package dev.varch.controller

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.text.format.DateFormat
import android.view.View
import android.widget.RemoteViews
import androidx.core.net.toUri
import dev.varch.controller.net.Action
import dev.varch.controller.net.ApiException
import dev.varch.controller.net.DesktopState
import dev.varch.controller.net.SystemState
import dev.varch.controller.net.VarchClient
import dev.varch.controller.net.parseState
import dev.varch.controller.net.parseSystem
import java.io.File
import java.io.IOException
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONException
import org.json.JSONObject

/**
 * The desktop as a widget last saw it. [state] is null until the first fetch,
 * and stays null against a daemon too old to have `GET /v1/state`.
 */
class WidgetData(
    val host: String,
    val state: DesktopState? = null,
    val system: SystemState? = null,
    val at: Long = 0,
    val online: Boolean = true,
    val art: Bitmap? = null,
)

/**
 * Home-screen widgets. A widget holds no connection, so it draws from the
 * last snapshot in [Store] and fetches a new one when it is added, after each
 * key press, when the app is left, and every half hour.
 */
object Widgets {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client by lazy { VarchClient() }
    private val periodic = AtomicBoolean()
    private val stateful = listOf(MediaWidget::class.java, WorkspaceWidget::class.java, SystemWidget::class.java)

    /** A player takes a moment to report the track or status a key just changed. */
    const val SETTLE_MS = 400L

    // A broadcast receiver that runs longer than ten seconds is killed.
    private const val SYNC_TIMEOUT_MS = 8_000L
    private const val TALL_DP = 100
    private const val ART_PX = 128
    private val workspaceKeys = listOf(
        R.id.widget_ws_1, R.id.widget_ws_2, R.id.widget_ws_3, R.id.widget_ws_4, R.id.widget_ws_5,
        R.id.widget_ws_6, R.id.widget_ws_7, R.id.widget_ws_8, R.id.widget_ws_9, R.id.widget_ws_10,
    )

    /** Fetches the desktop's state and redraws every widget. Does nothing when none is placed. */
    fun refresh(context: Context, settle: Long = 0, onDone: () -> Unit = {}) {
        val app = context.applicationContext
        if (stateful.none { ids(app, it).isNotEmpty() }) {
            onDone()
            return
        }
        scope.launch {
            try {
                delay(settle)
                withTimeoutOrNull(SYNC_TIMEOUT_MS) { sync(app) }
            } finally {
                render(app)
                onDone()
            }
        }
    }

    /** For the half-hourly update: the widgets wake together, and one fetch serves them all. */
    fun refreshOnce(context: Context, onDone: () -> Unit) {
        if (!periodic.compareAndSet(false, true)) {
            onDone()
            return
        }
        refresh(context) {
            periodic.set(false)
            onDone()
        }
    }

    /** Marks the snapshot stale after a key press that never reached the desktop. */
    fun unreachable(context: Context) {
        val store = Store(context)
        if (store.load() != null) store.snapshot = (store.snapshot ?: Snapshot()).copy(online = false)
        render(context)
    }

    /** Redraws every widget from the stored snapshot. */
    fun render(context: Context) {
        val app = context.applicationContext
        val manager = AppWidgetManager.getInstance(app)
        val data = load(app)
        ids(app, MediaWidget::class.java).takeIf { it.isNotEmpty() }?.let { manager.updateAppWidget(it, media(app, data)) }
        ids(app, SystemWidget::class.java).takeIf { it.isNotEmpty() }?.let { manager.updateAppWidget(it, system(app, data)) }
        for (id in ids(app, WorkspaceWidget::class.java)) {
            val tall = manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT) >= TALL_DP
            manager.updateAppWidget(id, workspaces(app, data, tall))
        }
    }

    private fun ids(context: Context, provider: Class<*>): IntArray =
        AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, provider))

    private fun artFile(context: Context) = File(context.cacheDir, "widget_art.png")

    private fun load(context: Context): WidgetData? {
        val store = Store(context)
        val pairing = store.load() ?: return null
        val snapshot = store.snapshot ?: return WidgetData(pairing.hostName)
        fun <T> parse(json: String, parser: (JSONObject) -> T): T? = try {
            if (json.isEmpty()) null else parser(JSONObject(json))
        } catch (_: JSONException) {
            null
        }
        val state = parse(snapshot.state, ::parseState)
        val showsArt = state != null && state.media.available && state.media.art.isNotEmpty() && state.media.art == snapshot.art
        return WidgetData(
            host = pairing.hostName,
            state = state,
            system = parse(snapshot.system, ::parseSystem),
            at = snapshot.at,
            online = snapshot.online,
            art = if (showsArt) BitmapFactory.decodeFile(artFile(context).path) else null,
        )
    }

    private suspend fun sync(context: Context) {
        val store = Store(context)
        val pairing = store.load() ?: return
        val old = store.snapshot ?: Snapshot()
        store.snapshot = try {
            val system = client.json(pairing.endpoint, pairing.token, "/v1/status")
            val state = try {
                client.json(pairing.endpoint, pairing.token, "/v1/state")
            } catch (e: ApiException) {
                // A daemon from before the widgets has no such endpoint; the keys still work.
                if (e.status != 404) throw e
                null
            }
            val wanted = state?.optJSONObject("media")?.takeIf { it.optBoolean("available") }?.optString("art").orEmpty()
            val art = when {
                wanted == old.art -> old.art
                wanted.isNotEmpty() && fetchArt(context, pairing) -> wanted
                else -> ""
            }
            Snapshot(state?.toString().orEmpty(), system.toString(), System.currentTimeMillis(), online = true, art = art)
        } catch (_: IOException) {
            old.copy(online = false)
        }
    }

    /** Saves the cover scaled down, because a widget's bitmap crosses to the launcher in one small transaction. */
    private suspend fun fetchArt(context: Context, pairing: Pairing): Boolean = try {
        val bytes = client.bytes(pairing.endpoint, pairing.token, "/v1/art")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val scaled = BitmapFactory.Options().apply { inSampleSize = maxOf(1, minOf(bounds.outWidth, bounds.outHeight) / ART_PX) }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, scaled)
        if (bitmap != null) artFile(context).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap != null
    } catch (_: IOException) {
        false
    }

    // The data URI is what tells one key's intent from another's; extras alone do not.
    private fun broadcast(context: Context, key: String, fill: Intent.() -> Unit = {}): PendingIntent {
        val intent = Intent(context, ActionReceiver::class.java).setData("varch:$key".toUri()).apply(fill)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun press(context: Context, action: String, value: Double = 0.0) = broadcast(context, "$action/$value") {
        putExtra(ActionReceiver.EXTRA_ACTION, action)
        putExtra(ActionReceiver.EXTRA_VALUE, value)
    }

    private fun open(context: Context): PendingIntent =
        PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    private fun RemoteViews.lit(context: Context, id: Int, on: Boolean) {
        setInt(id, "setBackgroundResource", if (on) R.drawable.widget_key_lit else R.drawable.widget_key)
        setTextColor(id, context.getColor(if (on) R.color.ground else R.color.bone))
    }

    private fun RemoteViews.show(id: Int, visible: Boolean) = setViewVisibility(id, if (visible) View.VISIBLE else View.GONE)

    /** The strip every widget opens with: the desktop's name, and when it was last heard from. */
    private fun RemoteViews.header(context: Context, data: WidgetData?, detail: String = "") {
        setTextViewText(R.id.widget_host, if (data == null) "NOT PAIRED" else (data.host + detail).uppercase())
        setOnClickPendingIntent(R.id.widget_host, open(context))
        val stale = data != null && !data.online
        setTextViewText(
            R.id.widget_sync,
            when {
                data == null || (data.at == 0L && !stale) -> ""
                stale -> "OFFLINE"
                else -> DateFormat.getTimeFormat(context).format(Date(data.at)).uppercase()
            },
        )
        setTextColor(R.id.widget_sync, context.getColor(if (stale) R.color.red else R.color.faint))
        setOnClickPendingIntent(R.id.widget_sync, broadcast(context, "sync"))
    }

    fun remote(context: Context) = RemoteViews(context.packageName, R.layout.widget_remote).apply {
        setOnClickPendingIntent(R.id.widget_play, press(context, Action.PLAY_PAUSE))
        setOnClickPendingIntent(R.id.widget_mute, press(context, Action.MUTE))
        setOnClickPendingIntent(R.id.widget_lock, press(context, Action.LOCK))
    }

    fun media(context: Context, data: WidgetData?) = RemoteViews(context.packageName, R.layout.widget_media).apply {
        val state = data?.state
        val media = state?.media?.takeIf { it.available }
        header(context, data, if (media == null) "" else " / ${media.name} / ${media.status}")

        val (title, line) = when {
            data == null -> "Not paired" to "Open Varch Controller to pair"
            media != null -> media.title.ifEmpty { "Untitled" } to media.artist
            state != null -> "Nothing playing" to ""
            !data.online -> "Can't reach the desktop" to ""
            data.at == 0L -> "Waiting for desktop" to ""
            else -> "Update varchd" to "This widget needs a newer daemon"
        }
        setTextViewText(R.id.widget_title, title)
        setTextColor(R.id.widget_title, context.getColor(if (media != null) R.color.bone else R.color.faint))
        setTextViewText(R.id.widget_artist, line)
        show(R.id.widget_artist, line.isNotEmpty())
        show(R.id.widget_art, data?.art != null)
        data?.art?.let { setImageViewBitmap(R.id.widget_art, it) }
        setOnClickPendingIntent(R.id.widget_info, open(context))

        val playing = media?.playing == true
        setImageViewResource(R.id.widget_play, if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        setInt(R.id.widget_play, "setBackgroundResource", if (playing) R.drawable.widget_key_lit else R.drawable.widget_key)
        setInt(R.id.widget_play, "setColorFilter", context.getColor(if (playing) R.color.ground else R.color.bone))
        val muted = state?.volume?.muted == true
        setTextViewText(
            R.id.widget_volume,
            when {
                state == null -> "VOL"
                muted -> "MUTE"
                else -> "${(state.volume.level * 100).roundToInt()}%"
            },
        )
        lit(context, R.id.widget_volume, muted)

        setOnClickPendingIntent(R.id.widget_previous, press(context, Action.PREVIOUS))
        setOnClickPendingIntent(R.id.widget_play, press(context, Action.PLAY_PAUSE))
        setOnClickPendingIntent(R.id.widget_next, press(context, Action.NEXT))
        setOnClickPendingIntent(R.id.widget_volume_down, press(context, Action.VOLUME_STEP, -VOLUME_STEP))
        setOnClickPendingIntent(R.id.widget_volume, press(context, Action.MUTE))
        setOnClickPendingIntent(R.id.widget_volume_up, press(context, Action.VOLUME_STEP, VOLUME_STEP))
    }

    /** [tall] picks the two-row form with a header over the single row of ten keys. */
    fun workspaces(context: Context, data: WidgetData?, tall: Boolean): RemoteViews {
        val views = RemoteViews(context.packageName, if (tall) R.layout.widget_workspaces else R.layout.widget_workspaces_row)
        if (tall) views.header(context, data)
        val workspaces = data?.state?.workspaces
        workspaceKeys.forEachIndexed { index, id ->
            val n = index + 1
            val active = workspaces?.active == n
            views.lit(context, id, active)
            val dot = if (workspaces != null && n in workspaces.occupied && !active) R.drawable.widget_dot else R.drawable.widget_dot_none
            views.setTextViewCompoundDrawables(id, 0, 0, 0, dot)
            views.setOnClickPendingIntent(id, press(context, Action.WORKSPACE, n.toDouble()))
        }
        return views
    }

    fun system(context: Context, data: WidgetData?) = RemoteViews(context.packageName, R.layout.widget_system).apply {
        val system = data?.system
        header(context, data)
        fun figure(id: Int, value: Int?, unit: String) = setTextViewText(id, if (value == null) "--" else "$value$unit")

        val battery = system?.battery
        // A desktop without a battery has no use for the box.
        show(R.id.widget_battery, system == null || battery != null)
        setTextViewText(R.id.widget_battery_label, if (battery?.charging == true) "CHG" else "BAT")
        figure(R.id.widget_battery_value, battery?.percent, "%")
        val low = battery != null && BatteryAlerts.eventFor(battery) == "low"
        setTextColor(R.id.widget_battery_value, context.getColor(if (low) R.color.red else R.color.bone))
        figure(R.id.widget_cpu_value, system?.cpu, "%")
        figure(R.id.widget_memory_value, system?.memory, "%")
        figure(R.id.widget_temperature_value, system?.temperature, "°")

        val micOff = system?.micMuted == true
        setTextViewText(R.id.widget_mic, if (micOff) "MIC OFF" else "MIC")
        lit(context, R.id.widget_mic, micOff)
        show(R.id.widget_night, system == null || system.nightLight != null)
        lit(context, R.id.widget_night, system?.nightLight == true)

        setOnClickPendingIntent(R.id.widget_lock, press(context, Action.LOCK))
        setOnClickPendingIntent(R.id.widget_mic, press(context, Action.MIC_MUTE))
        setOnClickPendingIntent(R.id.widget_night, press(context, Action.NIGHT_LIGHT))
    }

    private const val VOLUME_STEP = 0.05
}

/** The original three keys. It shows no state, so it never fetches any. */
class RemoteWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) =
        manager.updateAppWidget(ids, Widgets.remote(context))
}

/** A widget that shows desktop state: it draws what it has at once, then fetches. */
abstract class StateWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        Widgets.render(context)
        // Null when the launcher's update is not what called this, as in tests.
        val pending: BroadcastReceiver.PendingResult? = goAsync()
        Widgets.refreshOnce(context) { pending?.finish() }
    }

    // Resizing can move the workspace widget between its one-row and two-row forms.
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) =
        Widgets.render(context)
}

class MediaWidget : StateWidget()

class WorkspaceWidget : StateWidget()

class SystemWidget : StateWidget()
