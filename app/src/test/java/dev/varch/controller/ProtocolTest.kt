package dev.varch.controller

import dev.varch.controller.net.BatteryInfo
import dev.varch.controller.net.Endpoint
import dev.varch.controller.net.ServerMessage
import dev.varch.controller.net.actionBody
import dev.varch.controller.net.parseServerMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProtocolTest {
    @Test
    fun parsesStateExactlyAsTheDaemonSendsIt() {
        val message = parseServerMessage(
            """{"type":"state","state":{"host":"VARCH","volume":{"level":0.35,"muted":true},"brightness":0.15,
               "media":{"available":true,"player":"chromium","status":"Playing","title":"T","artist":"A"},
               "workspaces":{"active":2,"occupied":[1,2,3]}}}""",
        ) as ServerMessage.StateUpdate
        val state = message.state
        assertEquals("VARCH", state.host)
        assertEquals(0.35f, state.volume.level, 0.0001f)
        assertTrue(state.volume.muted)
        assertEquals(0.15f, state.brightness, 0.0001f)
        assertTrue(state.media.playing)
        assertEquals(2, state.workspaces.active)
        assertEquals(setOf(1, 2, 3), state.workspaces.occupied)
    }

    @Test
    fun parsesResultsAndIgnoresJunk() {
        assertEquals(
            ServerMessage.Result(7, false, "unknown action", ""),
            parseServerMessage("""{"type":"result","id":7,"ok":false,"error":"unknown action"}"""),
        )
        assertNull(parseServerMessage("not json"))
        assertNull(parseServerMessage("""{"type":"other"}"""))
    }

    @Test
    fun buildsActionMessages() {
        val json = actionBody("pointer.move", x = 3.0, y = -2.5, id = 3)
        assertEquals(3, json.getLong("id"))
        assertEquals("pointer.move", json.getString("action"))
        assertEquals(-2.5, json.getDouble("y"), 0.0)
        assertEquals("hi", actionBody("key.type", text = "hi").getString("text"))
    }

    @Test
    fun parsesSystemAndCatalog() {
        val system = (parseServerMessage(
            """{"type":"system","system":{"battery":{"percent":48,"charging":true,"full":false},"cpu":3,"memory":74,
               "temperature":62,"night_light":false,"wifi":true,"bluetooth":null,"mic_muted":true,"profile":"balanced",
               "profiles":["power-saver","balanced"],"sink":"a","sinks":[{"name":"a","label":"Speakers"}],"theme":"Nord",
               "kbd_backlight":null}}""",
        ) as ServerMessage.SystemUpdate).system
        assertEquals(48, system.battery?.percent)
        assertEquals(false, system.nightLight)
        assertNull("a desktop without Bluetooth must not show the toggle", system.bluetooth)
        assertNull(system.kbdBacklight)
        assertTrue(system.micMuted)
        assertEquals("Speakers", system.sinks.single().label)

        val catalog = (parseServerMessage(
            """{"type":"catalog","catalog":{"apps":[{"id":"term","name":"Terminal"}],"themes":["Nord","Gruvbox"]}}""",
        ) as ServerMessage.CatalogUpdate).catalog
        assertEquals("term", catalog.apps.single().id)
        assertEquals(listOf("Nord", "Gruvbox"), catalog.themes)
    }

    @Test
    fun parsesWindowsAndMediaDetails() {
        val state = (parseServerMessage(
            """{"type":"state","state":{"host":"VARCH","media":{"available":true,"player":"mpv","name":"mpv","status":"Playing",
               "position":12.5,"length":200,"can_seek":true,"art":"abc","players":[{"id":"mpv","name":"mpv"}]},
               "windows":[{"address":"0xa1","class":"kitty","title":"shell","workspace":2,"floating":true,"focused":true}]}}""",
        ) as ServerMessage.StateUpdate).state
        assertEquals(12.5f, state.media.position, 0.001f)
        assertTrue(state.media.canSeek)
        assertEquals("kitty", state.windows.single().appClass)
        assertTrue(state.windows.single().floating)
    }

    @Test
    fun tellsLinksFromText() {
        assertTrue(RemoteViewModel.isLink("https://example.com/a?b=c"))
        assertTrue(!RemoteViewModel.isLink("see https://example.com"))
        assertTrue(!RemoteViewModel.isLink("example.com"))
    }

    @Test
    fun batteryAlertsFireOnLowAndFullOnly() {
        assertEquals("low", BatteryAlerts.eventFor(BatteryInfo(15, charging = false, full = false)))
        assertEquals("", BatteryAlerts.eventFor(BatteryInfo(15, charging = true, full = false)))
        assertEquals("", BatteryAlerts.eventFor(BatteryInfo(60, charging = false, full = false)))
        assertEquals("full", BatteryAlerts.eventFor(BatteryInfo(100, charging = false, full = true)))
    }

    @Test
    fun parsesTypedAddresses() {
        assertEquals(Endpoint("192.168.1.10", 7421), Endpoint.parse(" 192.168.1.10 "))
        assertEquals(Endpoint("varch.local", 9000), Endpoint.parse("http://varch.local:9000/"))
        assertEquals("http://192.168.1.10:7421/v1/info", Endpoint("192.168.1.10").http("/v1/info"))
        assertNull(Endpoint.parse(""))
        assertNull(Endpoint.parse("host:notaport"))
        assertNull(Endpoint.parse("host:70000"))
        assertNull(Endpoint.parse("two words"))
    }
}
