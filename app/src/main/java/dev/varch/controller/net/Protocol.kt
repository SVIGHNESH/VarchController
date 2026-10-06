package dev.varch.controller.net

import org.json.JSONException
import org.json.JSONObject

const val DEFAULT_PORT = 7421

data class Endpoint(val host: String, val port: Int = DEFAULT_PORT) {
    private val authority get() = if (':' in host) "[$host]:$port" else "$host:$port"
    val label get() = authority

    fun http(path: String) = "http://$authority$path"

    companion object {
        /** Parses "host" or "host:port" as typed by a person. */
        fun parse(text: String): Endpoint? {
            val input = text.trim().removePrefix("http://").trimEnd('/')
            if (input.isEmpty() || input.any { it.isWhitespace() || it == '/' }) return null
            val colon = input.lastIndexOf(':')
            if (colon < 0) return Endpoint(input)
            val port = input.substring(colon + 1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            val host = input.substring(0, colon).takeIf { it.isNotEmpty() } ?: return null
            return Endpoint(host, port)
        }
    }
}

data class Volume(val level: Float = 0f, val muted: Boolean = false)

data class PlayerRef(val id: String, val name: String)

/** [position] and [length] are in seconds; [art] changes whenever the cover does. */
data class Media(
    val available: Boolean = false,
    val player: String = "",
    val name: String = "",
    val status: String = "",
    val title: String = "",
    val artist: String = "",
    val position: Float = 0f,
    val length: Float = 0f,
    val canSeek: Boolean = false,
    val art: String = "",
    val players: List<PlayerRef> = emptyList(),
) {
    val playing get() = status == "Playing"
}

data class Workspaces(val active: Int = 0, val occupied: Set<Int> = emptySet())

data class Window(
    val address: String,
    val appClass: String,
    val title: String,
    val workspace: Int,
    val floating: Boolean = false,
    val fullscreen: Boolean = false,
    val focused: Boolean = false,
)

data class DesktopState(
    val host: String = "",
    val volume: Volume = Volume(),
    val brightness: Float = 0f,
    val media: Media = Media(),
    val workspaces: Workspaces = Workspaces(),
    val windows: List<Window> = emptyList(),
)

data class BatteryInfo(val percent: Int, val charging: Boolean, val full: Boolean)

data class Sink(val name: String, val label: String)

/** The desktop theme's base colours, each "#rrggbb". [red] may be empty. */
data class ThemeColors(val background: String, val foreground: String, val accent: String, val red: String = "")

/** Slower-moving desktop state. A null field means the desktop lacks that feature. */
data class SystemState(
    val battery: BatteryInfo? = null,
    val cpu: Int = 0,
    val memory: Int = 0,
    val temperature: Int = 0,
    val nightLight: Boolean? = null,
    val wifi: Boolean? = null,
    val bluetooth: Boolean? = null,
    val micMuted: Boolean = false,
    val profile: String = "",
    val profiles: List<String> = emptyList(),
    val sink: String = "",
    val sinks: List<Sink> = emptyList(),
    val theme: String = "",
    val colors: ThemeColors? = null,
    val kbdBacklight: Float? = null,
)

data class AppRef(val id: String, val name: String)

data class Catalog(val apps: List<AppRef> = emptyList(), val themes: List<String> = emptyList())

object Action {
    const val PLAY_PAUSE = "media.play_pause"
    const val NEXT = "media.next"
    const val PREVIOUS = "media.previous"
    const val SEEK = "media.seek"
    const val SELECT_PLAYER = "media.select"
    const val VOLUME = "volume.set"
    const val VOLUME_STEP = "volume.step"
    const val MUTE = "volume.mute_toggle"
    const val BRIGHTNESS = "brightness.set"
    const val WORKSPACE = "workspace.switch"
    const val WINDOW_FOCUS = "window.focus"
    const val WINDOW_CLOSE = "window.close"
    const val WINDOW_FULLSCREEN = "window.fullscreen"
    const val WINDOW_FLOAT = "window.float"
    const val WINDOW_MOVE = "window.move"
    const val LAUNCH = "app.launch"
    const val POINTER_MOVE = "pointer.move"
    const val POINTER_SCROLL = "pointer.scroll"
    const val POINTER_DOWN = "pointer.down"
    const val POINTER_UP = "pointer.up"
    const val POINTER_CLICK = "pointer.click"
    const val KEY_TYPE = "key.type"
    const val KEY_PRESS = "key.press"
    const val KEY_ERASE = "key.erase"
    const val NIGHT_LIGHT = "system.nightlight_toggle"
    const val MIC_MUTE = "system.mic_mute_toggle"
    const val WIFI = "system.wifi"
    const val BLUETOOTH = "system.bluetooth"
    const val PROFILE = "system.profile"
    const val SINK = "system.sink"
    const val THEME = "system.theme"
    const val KBD_BACKLIGHT = "system.kbd_backlight"
    const val LOCK = "power.lock"
    const val SUSPEND = "power.suspend"
    const val REBOOT = "power.reboot"
    const val SHUTDOWN = "power.shutdown"
    const val CLIPBOARD_SET = "clipboard.set"
    const val CLIPBOARD_GET = "clipboard.get"
    const val OPEN_URL = "open.url"
}

/** Modifier bits for [Action.KEY_PRESS]. */
object Mod {
    const val SHIFT = 1
    const val CTRL = 2
    const val ALT = 4
    const val SUPER = 8
}

sealed interface ServerMessage {
    data class StateUpdate(val state: DesktopState) : ServerMessage
    data class SystemUpdate(val system: SystemState) : ServerMessage
    data class CatalogUpdate(val catalog: Catalog) : ServerMessage
    data class Result(val id: Long, val ok: Boolean, val error: String, val text: String = "") : ServerMessage
}

fun actionBody(action: String, value: Double = 0.0, text: String = "", x: Double = 0.0, y: Double = 0.0, id: Long = 0): JSONObject {
    val json = JSONObject().put("id", id).put("action", action)
    if (value != 0.0) json.put("value", value)
    if (text.isNotEmpty()) json.put("text", text)
    if (x != 0.0) json.put("x", x)
    if (y != 0.0) json.put("y", y)
    return json
}

fun parseServerMessage(text: String): ServerMessage? = try {
    val json = JSONObject(text)
    when (json.optString("type")) {
        "state" -> ServerMessage.StateUpdate(parseState(json.getJSONObject("state")))
        "system" -> ServerMessage.SystemUpdate(parseSystem(json.getJSONObject("system")))
        "catalog" -> ServerMessage.CatalogUpdate(parseCatalog(json.getJSONObject("catalog")))
        "result" -> parseResult(json)
        else -> null
    }
} catch (_: JSONException) {
    null
}

fun parseResult(json: JSONObject) =
    ServerMessage.Result(json.optLong("id"), json.optBoolean("ok"), json.optString("error"), json.optString("text"))

private fun JSONObject.objects(key: String): List<JSONObject> {
    val array = optJSONArray(key) ?: return emptyList()
    return (0 until array.length()).mapNotNull(array::optJSONObject)
}

private fun JSONObject.strings(key: String): List<String> {
    val array = optJSONArray(key) ?: return emptyList()
    return (0 until array.length()).map(array::optString)
}

private fun JSONObject.booleanOrNull(key: String) = if (isNull(key)) null else optBoolean(key)

fun parseState(json: JSONObject): DesktopState {
    val volume = json.optJSONObject("volume") ?: JSONObject()
    val media = json.optJSONObject("media") ?: JSONObject()
    val workspaces = json.optJSONObject("workspaces") ?: JSONObject()
    val occupied = workspaces.optJSONArray("occupied")
    return DesktopState(
        host = json.optString("host"),
        volume = Volume(volume.optDouble("level", 0.0).toFloat(), volume.optBoolean("muted")),
        brightness = json.optDouble("brightness", 0.0).toFloat(),
        media = Media(
            available = media.optBoolean("available"),
            player = media.optString("player"),
            name = media.optString("name"),
            status = media.optString("status"),
            title = media.optString("title"),
            artist = media.optString("artist"),
            position = media.optDouble("position", 0.0).toFloat(),
            length = media.optDouble("length", 0.0).toFloat(),
            canSeek = media.optBoolean("can_seek"),
            art = media.optString("art"),
            players = media.objects("players").map { PlayerRef(it.optString("id"), it.optString("name")) },
        ),
        workspaces = Workspaces(
            active = workspaces.optInt("active"),
            occupied = buildSet { if (occupied != null) for (i in 0 until occupied.length()) add(occupied.optInt(i)) },
        ),
        windows = json.objects("windows").map {
            Window(
                address = it.optString("address"),
                appClass = it.optString("class"),
                title = it.optString("title"),
                workspace = it.optInt("workspace"),
                floating = it.optBoolean("floating"),
                fullscreen = it.optBoolean("fullscreen"),
                focused = it.optBoolean("focused"),
            )
        },
    )
}

fun parseSystem(json: JSONObject): SystemState {
    val battery = json.optJSONObject("battery")
    return SystemState(
        battery = battery?.let { BatteryInfo(it.optInt("percent"), it.optBoolean("charging"), it.optBoolean("full")) },
        cpu = json.optInt("cpu"),
        memory = json.optInt("memory"),
        temperature = json.optInt("temperature"),
        nightLight = json.booleanOrNull("night_light"),
        wifi = json.booleanOrNull("wifi"),
        bluetooth = json.booleanOrNull("bluetooth"),
        micMuted = json.optBoolean("mic_muted"),
        profile = json.optString("profile"),
        profiles = json.strings("profiles"),
        sink = json.optString("sink"),
        sinks = json.objects("sinks").map { Sink(it.optString("name"), it.optString("label")) },
        theme = json.optString("theme"),
        colors = json.optJSONObject("colors")?.let {
            ThemeColors(it.optString("background"), it.optString("foreground"), it.optString("accent"), it.optString("red"))
        },
        kbdBacklight = if (json.isNull("kbd_backlight")) null else json.optDouble("kbd_backlight").toFloat(),
    )
}

private fun parseCatalog(json: JSONObject) = Catalog(
    apps = json.objects("apps").map { AppRef(it.optString("id"), it.optString("name")) },
    themes = json.strings("themes"),
)
