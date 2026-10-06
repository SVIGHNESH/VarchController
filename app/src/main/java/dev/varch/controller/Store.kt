package dev.varch.controller

import android.content.Context
import androidx.core.content.edit
import dev.varch.controller.net.DEFAULT_PORT
import dev.varch.controller.net.Endpoint

data class Pairing(val endpoint: Endpoint, val hostName: String, val token: String)

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
    }

    /** Whether the periodic desktop battery check is enabled. */
    var alerts: Boolean
        get() = prefs.getBoolean(ALERTS, false)
        set(value) = prefs.edit { putBoolean(ALERTS, value) }

    /** What the battery check last notified about, so each event notifies once. */
    var lastAlert: String
        get() = prefs.getString(LAST_ALERT, "") ?: ""
        set(value) = prefs.edit { putString(LAST_ALERT, value) }

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
    }
}
