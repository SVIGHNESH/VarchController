package dev.varch.controller

import dev.varch.controller.net.Action
import dev.varch.controller.net.ApiException
import dev.varch.controller.net.Endpoint
import dev.varch.controller.net.ServerMessage
import dev.varch.controller.net.VarchClient
import dev.varch.controller.net.actionBody
import dev.varch.controller.net.parseServerMessage
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
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
import org.robolectric.annotation.Config

/**
 * Drives a real varchd with the app's own client. Skipped unless VARCHD_ADDR
 * (host:port) and VARCHD_LOG (the daemon's log file, for the pairing code) are set.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DaemonContractTest {
    @Test
    fun pairsConnectsAndControls() = runBlocking {
        val address = System.getenv("VARCHD_ADDR")
        val log = System.getenv("VARCHD_LOG")
        assumeTrue(address != null && log != null)
        val endpoint = Endpoint.parse(address!!)!!
        val client = VarchClient()

        assertTrue(client.hostName(endpoint).isNotEmpty())
        val pairingId = client.startPairing(endpoint, "contract-test")
        try {
            client.finishPairing(endpoint, pairingId, "000000x")
            fail("a wrong code was accepted")
        } catch (e: ApiException) {
            assertEquals(403, e.status)
        }
        val code = Regex("""Code (\d{3}) (\d{3})""").findAll(File(log!!).readText()).last().destructured.let { (a, b) -> a + b }
        val token = client.finishPairing(endpoint, pairingId, code)

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

        // The one-shot form used by tiles and the widget, plus the image and status endpoints.
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
