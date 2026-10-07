package dev.varch.controller

import android.appwidget.AppWidgetManager
import android.widget.TextView
import dev.varch.controller.net.Action
import dev.varch.controller.net.ApiException
import dev.varch.controller.net.Endpoint
import dev.varch.controller.net.ServerMessage
import dev.varch.controller.net.VarchClient
import dev.varch.controller.net.actionBody
import dev.varch.controller.net.parseServerMessage
import dev.varch.controller.net.parseState
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlinx.coroutines.runBlocking
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Drives a real varchd with the app's own client. Skipped unless VARCHD_ADDR
 * (host:port) and VARCHD_LOG (the daemon's log file, for the pairing code) are set.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DaemonContractTest {
    private val client = VarchClient()
    private val endpoint by lazy { Endpoint.parse(System.getenv("VARCHD_ADDR"))!! }

    private fun assumeDaemon() = assumeTrue(System.getenv("VARCHD_ADDR") != null && System.getenv("VARCHD_LOG") != null)

    /** Pairs by reading the code from the daemon's log, after checking that a wrong one is refused. */
    private suspend fun pair(): String {
        val pairingId = client.startPairing(endpoint, "contract-test")
        try {
            client.finishPairing(endpoint, pairingId, "000000x")
            fail("a wrong code was accepted")
        } catch (e: ApiException) {
            assertEquals(403, e.status)
        }
        val log = File(System.getenv("VARCHD_LOG")!!).readText()
        val code = Regex("""Code (\d{3}) (\d{3})""").findAll(log).last().destructured.let { (a, b) -> a + b }
        return client.finishPairing(endpoint, pairingId, code)
    }

    /** The widgets' whole path: fetch over HTTP, store the snapshot, and draw it the way a launcher would. */
    @Test
    fun widgetsDrawWhatTheDaemonReports() = runBlocking {
        assumeDaemon()
        val context = RuntimeEnvironment.getApplication()
        val token = pair()
        val host = client.hostName(endpoint)
        Store(context).save(Pairing(endpoint, host, token))
        val launcher = shadowOf(AppWidgetManager.getInstance(context))
        val media = launcher.createWidget(MediaWidget::class.java, R.layout.widget_media)
        val status = launcher.createWidget(SystemWidget::class.java, R.layout.widget_system)
        fun text(widget: Int, view: Int) = launcher.getViewFor(widget).findViewById<TextView>(view).text.toString()
        fun refresh() {
            val done = CountDownLatch(1)
            Widgets.refresh(context) { done.countDown() }
            assertTrue("the widgets never finished fetching", done.await(10, TimeUnit.SECONDS))
        }

        refresh()
        val state = parseState(client.json(endpoint, token, "/v1/state"))
        assertTrue(text(media, R.id.widget_host).startsWith(host.uppercase()))
        assertTrue("a sync time should show", text(media, R.id.widget_sync).any(Char::isDigit))
        val level = if (state.volume.muted) "MUTE" else "${(state.volume.level * 100).roundToInt()}%"
        assertEquals(level, text(media, R.id.widget_volume))
        assertTrue(text(status, R.id.widget_memory_value).matches(Regex("""\d+%""")))

        // A step away and back leaves the volume where it was; which way first depends on the room there is.
        val first = if (state.volume.level > 0.5f) -0.01 else 0.01
        assertTrue(client.action(endpoint, token, Action.VOLUME_STEP, first).ok)
        val moved = parseState(client.json(endpoint, token, "/v1/state")).volume.level
        assertEquals(state.volume.level + first.toFloat(), moved, 0.006f)
        assertTrue(client.action(endpoint, token, Action.VOLUME_STEP, -first).ok)
        assertEquals(state.volume.level, parseState(client.json(endpoint, token, "/v1/state")).volume.level, 0.006f)
        assertFalse(client.action(endpoint, token, Action.VOLUME_STEP).ok)

        // Once the desktop is forgotten on its side, the widgets say they are showing old news.
        client.unpair(endpoint, token)
        refresh()
        assertEquals("OFFLINE", text(media, R.id.widget_sync))
        Store(context).clear()
        Widgets.render(context)
        assertEquals("NOT PAIRED", text(media, R.id.widget_host))
    }

    @Test
    fun pairsConnectsAndControls() = runBlocking {
        assumeDaemon()
        assertTrue(client.hostName(endpoint).isNotEmpty())
        val token = pair()

        val inbox = LinkedBlockingQueue<Any>()
        val socket = client.open(endpoint, token, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                parseServerMessage(text)?.let(inbox::put)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                inbox.put(t)
            }
        })
        fun next() = inbox.poll(5, TimeUnit.SECONDS) ?: error("daemon went quiet")

        // A connection opens with the catalog and both state snapshots, in no fixed order.
        val opening = generateSequence { next() }.take(3).toList()
        val first = opening.filterIsInstance<ServerMessage.StateUpdate>().single()
        assertTrue(first.state.host.isNotEmpty())
        assertTrue(opening.any { it is ServerMessage.SystemUpdate })
        assertTrue(opening.any { it is ServerMessage.CatalogUpdate })

        // Setting the volume to its current level exercises the path without changing anything.
        socket.send(actionBody(Action.VOLUME, first.state.volume.level.toDouble(), id = 1).toString())
        socket.send(actionBody("no.such.action", id = 2).toString())
        val results = generateSequence { next() }.filterIsInstance<ServerMessage.Result>().take(2).toList()
        assertTrue(results[0].ok)
        assertFalse(results[1].ok)

        // The one-shot form used by tiles and the widgets, plus the image and status endpoints.
        assertFalse(client.action(endpoint, token, "no.such.action").ok)
        assertTrue(client.status(endpoint, token).memory > 0)
        val shot = client.bytes(endpoint, token, "/v1/screenshot")
        assertTrue("screenshot should be a JPEG", shot.size > 1000 && shot[0] == 0xFF.toByte() && shot[1] == 0xD8.toByte())

        // The live view delivers at least one JPEG frame of the desktop.
        val frames = LinkedBlockingQueue<ByteString>()
        val view = client.openPath(endpoint, token, "/v1/cast/screen", object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                frames.put(bytes)
            }
        })
        val frame = frames.poll(5, TimeUnit.SECONDS) ?: error("no live frame arrived")
        assertTrue("live frame should be a JPEG", frame.size > 500 && frame[0] == 0xFF.toByte() && frame[1] == 0xD8.toByte())
        view.close(1000, null)
        socket.close(1000, null)

        client.unpair(endpoint, token)
        val rejected = LinkedBlockingQueue<Int>()
        client.open(endpoint, token, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                rejected.put(response.code)
                webSocket.close(1000, null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                rejected.put(response?.code ?: -1)
            }
        })
        assertEquals(401, rejected.poll(5, TimeUnit.SECONDS))
    }
}
