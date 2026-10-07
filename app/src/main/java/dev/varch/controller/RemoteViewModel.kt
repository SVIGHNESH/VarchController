package dev.varch.controller

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.SystemClock
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.varch.controller.net.Action
import dev.varch.controller.net.ApiException
import dev.varch.controller.net.Catalog
import dev.varch.controller.net.DesktopState
import dev.varch.controller.net.Discovery
import dev.varch.controller.net.Endpoint
import dev.varch.controller.net.Found
import dev.varch.controller.net.ServerMessage
import dev.varch.controller.net.SystemState
import dev.varch.controller.net.ThemeColors
import dev.varch.controller.net.VarchClient
import dev.varch.controller.net.actionBody
import dev.varch.controller.net.parseServerMessage
import dev.varch.controller.ui.Ink
import dev.varch.controller.ui.Palette
import dev.varch.controller.ui.RemoteActions
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

enum class Link { Connecting, Online, Offline }

data class RemoteUi(
    val hostName: String,
    val address: String,
    val link: Link = Link.Connecting,
    val desktop: DesktopState? = null,
    val system: SystemState? = null,
    val catalog: Catalog = Catalog(),
    val notice: String? = null,
    /** [SystemClock.elapsedRealtime] at which the media position was last reported. */
    val mediaAt: Long = 0,
    val art: ImageBitmap? = null,
    val screenshot: ImageBitmap? = null,
    val capturing: Boolean = false,
    /** Text last fetched from the desktop clipboard. */
    val clip: String? = null,
    val alerts: Boolean = false,
    val matchTheme: Boolean = true,
    /** Whether the live view of the desktop is open, and its latest frame. */
    val watching: Boolean = false,
    val frame: ImageBitmap? = null,
) {
    val live get() = link == Link.Online && desktop != null

}

sealed interface PairStep {
    data object Idle : PairStep
    data class Contacting(val endpoint: Endpoint) : PairStep
    data class EnterCode(
        val endpoint: Endpoint,
        val hostName: String,
        val pairingId: String,
        val verifying: Boolean = false,
    ) : PairStep
}

data class SetupUi(
    val found: List<Found> = emptyList(),
    val address: String = "",
    val code: String = "",
    val step: PairStep = PairStep.Idle,
    val error: String? = null,
)

const val CODE_LENGTH = 6

class RemoteViewModel(app: Application) : AndroidViewModel(app), RemoteActions {
    private val store = Store(app)
    private val client = VarchClient()
    private val discovery = Discovery(app) { setup = setup.copy(found = it) }

    var setup by mutableStateOf(SetupUi())
        private set
    var remote by mutableStateOf(
        store.load()?.let { RemoteUi(it.hostName, it.endpoint.label, alerts = store.alerts, matchTheme = store.matchTheme) },
    )
        private set

    init {
        // Open in the colours the desktop had last time, before the connection is up.
        if (remote != null) applyTheme(store.colors)
    }

    /** While true, the phone's volume buttons flip slides instead of changing volume. */
    private var slides = false

    private var started = false
    private var socket: WebSocket? = null
    private var generation = 0
    private var attempt = 0
    private var reconnect: Job? = null
    private var noticeJob: Job? = null
    private var nextId = 1L
    private val awaiting = mutableMapOf<Long, (ServerMessage.Result) -> Unit>()
    private var artId = ""
    private var viewer: WebSocket? = null
    private var viewerGeneration = 0
    private var frames: Job? = null
    private var pendingShare: String? = null

    // Pointer motion is summed and flushed at a steady rate rather than sent per touch event.
    private var moveX = 0f
    private var moveY = 0f
    private var scrollX = 0f
    private var scrollY = 0f
    private var pointerJob: Job? = null

    // Fader drags are coalesced so the desktop gets the newest value, not a backlog.
    private val pendingLevels = mutableMapOf<String, Float>()
    private val levelJobs = mutableMapOf<String, Job>()
    private val heldUntil = mutableMapOf<String, Long>()

    /** Called while the app is visible. */
    fun start() {
        started = true
        if (remote == null) discovery.start() else connect()
    }

    fun stop() {
        started = false
        discovery.stop()
        setWatching(false)
        disconnect()
        // The widgets are what the user sees next, so they should not show what was true before the app opened.
        Widgets.refresh(getApplication())
    }

    override fun onCleared() = stop()

    // Pairing

    fun onAddressChange(text: String) {
        setup = setup.copy(address = text, error = null)
    }

    fun pairWithAddress() {
        val endpoint = Endpoint.parse(setup.address)
        if (endpoint == null) {
            setup = setup.copy(error = "Enter an address like 192.168.1.10 or 192.168.1.10:7421.")
        } else {
            beginPairing(endpoint)
        }
    }

    fun beginPairing(endpoint: Endpoint) {
        if (setup.step != PairStep.Idle) return
        setup = setup.copy(step = PairStep.Contacting(endpoint), error = null, code = "")
        viewModelScope.launch {
            setup = try {
                val name = client.hostName(endpoint).ifEmpty { endpoint.host }
                val id = client.startPairing(endpoint, Build.MODEL ?: "Android phone")
                setup.copy(step = PairStep.EnterCode(endpoint, name, id))
            } catch (e: IOException) {
                setup.copy(step = PairStep.Idle, error = describe(e, endpoint))
            }
        }
    }

    fun onCodeChange(text: String) {
        val step = setup.step as? PairStep.EnterCode ?: return
        if (step.verifying) return
        val code = text.filter(Char::isDigit).take(CODE_LENGTH)
        setup = setup.copy(code = code, error = null)
        if (code.length == CODE_LENGTH) finishPairing(step, code)
    }

    private fun finishPairing(step: PairStep.EnterCode, code: String) {
        setup = setup.copy(step = step.copy(verifying = true))
        viewModelScope.launch {
            try {
                val token = client.finishPairing(step.endpoint, step.pairingId, code)
                store.save(Pairing(step.endpoint, step.hostName, token))
                Widgets.refresh(getApplication())
                setup = SetupUi()
                remote = RemoteUi(step.hostName, step.endpoint.label)
                discovery.stop()
                if (started) connect()
            } catch (e: IOException) {
                // 403 is a wrong code and can be retried; anything else ends this pairing.
                val retry = e is ApiException && e.status == 403
                setup = setup.copy(
                    step = if (retry) step else PairStep.Idle,
                    code = "",
                    error = describe(e, step.endpoint),
                )
            }
        }
    }

    fun cancelPairing() {
        setup = setup.copy(step = PairStep.Idle, code = "", error = null)
    }

    override fun unpair() {
        val pairing = store.load()
        forget(null)
        if (pairing != null) {
            // Best effort: the phone forgets the desktop even if it can't be reached.
            viewModelScope.launch { runCatching { client.unpair(pairing.endpoint, pairing.token) } }
        }
    }

    private fun forget(reason: String?) {
        setWatching(false)
        CastService.stop(getApplication())
        disconnect()
        BatteryAlerts.cancel(getApplication())
        store.clear()
        Widgets.render(getApplication())
        Ink.use(Palette.Default)
        remote = null
        setup = SetupUi(error = reason)
        if (started) discovery.start()
    }

    private fun describe(e: IOException, endpoint: Endpoint) = when (e) {
        is ApiException -> e.message!!.replaceFirstChar(Char::uppercase) + "."
        else -> "Can't reach ${endpoint.label}. Check that varchd is running and this phone is on the same network."
    }

    // Connection

    private fun connect() {
        val pairing = store.load() ?: return
        disconnect()
        val gen = generation
        remote = remote?.copy(link = Link.Connecting)
        socket = client.open(pairing.endpoint, pairing.token, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = post(gen) {
                attempt = 0
                remote = remote?.copy(link = Link.Online)
                pendingShare?.let { pendingShare = null; share(it) }
            }

            override fun onMessage(webSocket: WebSocket, text: String) = post(gen) { handle(text) }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = post(gen) { dropped() }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = post(gen) {
                if (response?.code == 401) {
                    forget("This phone is no longer paired with the desktop. Pair again.")
                } else {
                    dropped()
                }
            }
        })
    }

    /** Runs [block] on the main thread unless the socket it came from was replaced. */
    private fun post(gen: Int, block: () -> Unit) {
        viewModelScope.launch { if (gen == generation) block() }
    }

    private fun disconnect() {
        generation++
        reconnect?.cancel()
        socket?.close(1000, null)
        socket = null
        awaiting.clear()
    }

    private fun dropped() {
        socket = null
        remote = remote?.copy(link = Link.Offline)
        if (!started) return
        val wait = RETRY_DELAYS_MS[attempt.coerceAtMost(RETRY_DELAYS_MS.lastIndex)]
        attempt++
        reconnect = viewModelScope.launch {
            delay(wait)
            connect()
        }
    }

    private fun handle(text: String) {
        when (val message = parseServerMessage(text)) {
            is ServerMessage.StateUpdate -> {
                val current = remote?.desktop
                var next = message.state
                // While a fader is being dragged the desktop's echo lags behind the finger.
                if (current != null) {
                    if (held(Action.VOLUME)) next = next.copy(volume = next.volume.copy(level = current.volume.level))
                    if (held(Action.BRIGHTNESS)) next = next.copy(brightness = current.brightness)
                }
                remote = remote?.copy(
                    desktop = next,
                    hostName = next.host.ifEmpty { remote!!.hostName },
                    mediaAt = SystemClock.elapsedRealtime(),
                )
                if (next.media.art != artId) loadArt(next.media.art)
            }
            is ServerMessage.SystemUpdate -> {
                remote = remote?.copy(system = message.system)
                if (message.system.colors != store.colors) {
                    store.colors = message.system.colors
                    applyTheme(message.system.colors)
                }
            }
            is ServerMessage.CatalogUpdate -> remote = remote?.copy(catalog = message.catalog)
            is ServerMessage.Result -> {
                val waiter = awaiting.remove(message.id)
                if (!message.ok) notify(message.error.replaceFirstChar(Char::uppercase)) else waiter?.invoke(message)
            }
            null -> {}
        }
    }

    private fun applyTheme(colors: ThemeColors?) {
        val matched = colors?.takeIf { store.matchTheme }?.let(Palette::from)
        Ink.use(matched ?: Palette.Default)
    }

    override fun setMatchTheme(enabled: Boolean) {
        store.matchTheme = enabled
        remote = remote?.copy(matchTheme = enabled)
        applyTheme(store.colors)
    }

    // Live view of the desktop

    override fun setWatching(enabled: Boolean) {
        if (enabled == (viewer != null)) return
        viewer?.close(1000, null)
        viewer = null
        frames?.cancel()
        frames = null
        remote = remote?.copy(watching = enabled, frame = if (enabled) remote?.frame else null)
        val pairing = store.load()
        if (!enabled || pairing == null) return

        // Frames can arrive faster than the phone decodes them; only the newest is kept.
        val inbox = Channel<ByteString>(Channel.CONFLATED)
        frames = viewModelScope.launch {
            for (bytes in inbox) {
                val image = withContext(Dispatchers.Default) {
                    val data = bytes.toByteArray()
                    BitmapFactory.decodeByteArray(data, 0, data.size)?.asImageBitmap()
                }
                if (image != null) remote = remote?.copy(frame = image)
            }
        }
        val gen = ++viewerGeneration
        fun ended(message: String?) = viewModelScope.launch {
            if (gen != viewerGeneration || viewer == null) return@launch
            setWatching(false)
            if (message != null) notify(message)
        }
        viewer = client.openPath(pairing.endpoint, pairing.token, "/v1/cast/screen", object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                inbox.trySend(bytes)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                ended(reason.replaceFirstChar(Char::uppercase).ifEmpty { null })
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                ended("Live view lost its connection")
            }
        })
    }

    private fun loadArt(id: String) {
        artId = id
        if (id.isEmpty()) {
            remote = remote?.copy(art = null)
            return
        }
        viewModelScope.launch {
            val image = fetchImage("/v1/art")
            if (artId == id) remote = remote?.copy(art = image)
        }
    }

    private suspend fun fetchImage(path: String): ImageBitmap? {
        val pairing = store.load() ?: return null
        return try {
            val data = client.bytes(pairing.endpoint, pairing.token, path)
            withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(data, 0, data.size)?.asImageBitmap() }
        } catch (_: IOException) {
            null
        }
    }

    private fun held(action: String) = SystemClock.elapsedRealtime() < (heldUntil[action] ?: 0)

    private fun notify(text: String) {
        remote = remote?.copy(notice = text)
        noticeJob?.cancel()
        noticeJob = viewModelScope.launch {
            delay(3000)
            remote = remote?.copy(notice = null)
        }
    }

    // Actions

    override fun send(action: String, value: Double, text: String) = dispatch(action, value, text)

    /** Sends an action. [onResult] runs only if the desktop reports success. */
    private fun dispatch(
        action: String,
        value: Double = 0.0,
        text: String = "",
        x: Double = 0.0,
        y: Double = 0.0,
        onResult: ((ServerMessage.Result) -> Unit)? = null,
    ) {
        val id = nextId++
        val sent = socket?.send(actionBody(action, value, text, x, y, id).toString()) == true
        if (sent && onResult != null) awaiting[id] = onResult
    }

    override fun movePointer(dx: Float, dy: Float) {
        moveX += dx
        moveY += dy
        flushPointer()
    }

    override fun scrollPointer(dx: Float, dy: Float) {
        scrollX += dx
        scrollY += dy
        flushPointer()
    }

    private fun flushPointer() {
        if (pointerJob?.isActive == true) return
        pointerJob = viewModelScope.launch {
            while (moveX != 0f || moveY != 0f || scrollX != 0f || scrollY != 0f) {
                if (moveX != 0f || moveY != 0f) dispatch(Action.POINTER_MOVE, x = moveX.toDouble(), y = moveY.toDouble())
                if (scrollX != 0f || scrollY != 0f) dispatch(Action.POINTER_SCROLL, x = scrollX.toDouble(), y = scrollY.toDouble())
                moveX = 0f
                moveY = 0f
                scrollX = 0f
                scrollY = 0f
                delay(POINTER_INTERVAL_MS)
            }
        }
    }

    override fun setSlides(enabled: Boolean) {
        slides = enabled
    }

    /**
     * Handles the phone's hardware volume buttons: they flip slides in
     * presentation mode and otherwise move the desktop volume. Returns false
     * when the press should fall through to the phone's own volume.
     */
    fun onVolumeKey(up: Boolean): Boolean {
        val current = remote?.takeIf { it.live }?.desktop ?: return false
        if (slides) {
            send(Action.KEY_PRESS, 0.0, if (up) "Right" else "Left")
        } else {
            setVolume((current.volume.level + if (up) VOLUME_STEP else -VOLUME_STEP).coerceIn(0f, 1f))
        }
        return true
    }

    // Sharing

    /** Handles text shared to the app: links open on the desktop, anything else goes to its clipboard. */
    override fun share(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        if (remote?.link != Link.Online) {
            // Shared before the connection was up; sent as soon as it is.
            pendingShare = trimmed
            return
        }
        if (isLink(trimmed)) {
            dispatch(Action.OPEN_URL, text = trimmed) { notify("Opened on the desktop") }
        } else {
            sendClipboard(trimmed)
        }
    }

    override fun sendClipboard(text: String) {
        if (text.isEmpty()) {
            notify("Nothing to send")
            return
        }
        dispatch(Action.CLIPBOARD_SET, text = text) { notify("Copied to the desktop clipboard") }
    }

    /** Copies the desktop clipboard onto this phone. */
    override fun fetchClipboard() {
        dispatch(Action.CLIPBOARD_GET) { result ->
            val clipboard = getApplication<Application>().getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("Desktop clipboard", result.text))
            remote = remote?.copy(clip = result.text)
            notify("Copied to this phone")
        }
    }

    override fun capture() {
        if (remote?.capturing == true) return
        remote = remote?.copy(capturing = true)
        viewModelScope.launch {
            val image = fetchImage("/v1/screenshot")
            remote = remote?.copy(capturing = false, screenshot = image ?: remote?.screenshot)
            if (image == null) notify("Could not capture the screen")
        }
    }

    override fun setAlerts(enabled: Boolean) {
        store.alerts = enabled
        if (enabled) BatteryAlerts.schedule(getApplication()) else BatteryAlerts.cancel(getApplication())
        remote = remote?.copy(alerts = enabled)
    }

    override fun setVolume(level: Float) = setLevel(Action.VOLUME, level) { it.copy(volume = it.volume.copy(level = level)) }

    override fun setBrightness(level: Float) = setLevel(Action.BRIGHTNESS, level) { it.copy(brightness = level) }

    private fun setLevel(action: String, level: Float, apply: (DesktopState) -> DesktopState) {
        remote = remote?.let { it.copy(desktop = it.desktop?.let(apply)) }
        heldUntil[action] = SystemClock.elapsedRealtime() + ECHO_HOLD_MS
        pendingLevels[action] = level
        if (levelJobs[action]?.isActive == true) return
        levelJobs[action] = viewModelScope.launch {
            while (true) {
                val next = pendingLevels.remove(action) ?: break
                send(action, next.toDouble())
                delay(LEVEL_INTERVAL_MS)
            }
        }
    }

    companion object {
        fun isLink(text: String) = (text.startsWith("http://") || text.startsWith("https://")) && text.none(Char::isWhitespace)

        private val RETRY_DELAYS_MS = longArrayOf(500, 1000, 2000, 4000)
        private const val LEVEL_INTERVAL_MS = 60L
        private const val POINTER_INTERVAL_MS = 12L
        private const val VOLUME_STEP = 0.05f
        private const val ECHO_HOLD_MS = 600L
    }
}
