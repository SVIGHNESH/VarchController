package dev.varch.controller

import android.content.Context
import androidx.core.content.edit
import dev.varch.controller.net.DEFAULT_PORT
import dev.varch.controller.net.Endpoint
import dev.varch.controller.net.ThemeColors

data class Pairing(val endpoint: Endpoint, val hostName: String, val token: String)

/**
 * The desktop as the widgets last fetched it. [state] and [system] are the
 * daemon's JSON, kept raw so they survive the process, and [art] names the
 * cover that is saved beside them.
 */
data class Snapshot(val state: String = "", val system: String = "", val at: Long = 0, val online: Boolean = true, val art: String = "")

/** Persists the one desktop this phone is paired with, in app-private storage. */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("pairing", Context.MODE_PRIVATE)

    fun load(): Pairing? {
        val host = prefs.getString(HOST, null) ?: return null
        val token = prefs.getString(TOKEN, null) ?: return null
        return Pairing(Endpoint(host, prefs.getInt(PORT, DEFAULT_PORT)), prefs.getString(NAME, null) ?: host, token)
    }

    fun save(pairing: Pairing) {
        prefs.edit {
            putString(HOST, pairing.endpoint.host)
            putInt(PORT, pairing.endpoint.port)
            putString(NAME, pairing.hostName)
            putString(TOKEN, pairing.token)
        }
        // A sync that was still running at unpair time may have written one for the old desktop.
        snapshot = null
    }

    /** Whether the periodic desktop battery check is enabled. */
    var alerts: Boolean
        get() = prefs.getBoolean(ALERTS, false)
        set(value) = prefs.edit { putBoolean(ALERTS, value) }

    /** What the battery check last notified about, so each event notifies once. */
    var lastAlert: String
        get() = prefs.getString(LAST_ALERT, "") ?: ""
        set(value) = prefs.edit { putString(LAST_ALERT, value) }

    /** Whether the app recolours itself to match the desktop's theme. */
    var matchTheme: Boolean
        get() = prefs.getBoolean(MATCH_THEME, true)
        set(value) = prefs.edit { putBoolean(MATCH_THEME, value) }

    /** The desktop theme last seen, so the app opens in the right colours before it connects. */
    var colors: ThemeColors?
        get() {
            val parts = prefs.getString(COLORS, null)?.split(',') ?: return null
            return if (parts.size == 4) ThemeColors(parts[0], parts[1], parts[2], parts[3]) else null
        }
        set(value) = prefs.edit {
            if (value == null) remove(COLORS) else putString(COLORS, listOf(value.background, value.foreground, value.accent, value.red).joinToString(","))
        }

    var snapshot: Snapshot?
        get() {
            if (!prefs.contains(SNAPSHOT_ONLINE)) return null
            return Snapshot(
                prefs.getString(SNAPSHOT_STATE, "") ?: "",
                prefs.getString(SNAPSHOT_SYSTEM, "") ?: "",
                prefs.getLong(SNAPSHOT_AT, 0),
                prefs.getBoolean(SNAPSHOT_ONLINE, true),
                prefs.getString(SNAPSHOT_ART, "") ?: "",
            )
        }
        set(value) = prefs.edit {
            if (value == null) {
                listOf(SNAPSHOT_STATE, SNAPSHOT_SYSTEM, SNAPSHOT_AT, SNAPSHOT_ONLINE, SNAPSHOT_ART).forEach(::remove)
            } else {
                putString(SNAPSHOT_STATE, value.state)
                putString(SNAPSHOT_SYSTEM, value.system)
                putLong(SNAPSHOT_AT, value.at)
                putBoolean(SNAPSHOT_ONLINE, value.online)
                putString(SNAPSHOT_ART, value.art)
            }
        }

    fun clear() {
        prefs.edit { clear() }
    }

    private companion object {
        const val HOST = "host"
        const val PORT = "port"
        const val NAME = "name"
        const val TOKEN = "token"
        const val ALERTS = "alerts"
        const val LAST_ALERT = "last_alert"
        const val MATCH_THEME = "match_theme"
        const val COLORS = "colors"
        const val SNAPSHOT_STATE = "snapshot_state"
        const val SNAPSHOT_SYSTEM = "snapshot_system"
        const val SNAPSHOT_AT = "snapshot_at"
        const val SNAPSHOT_ONLINE = "snapshot_online"
        const val SNAPSHOT_ART = "snapshot_art"
    }
}
