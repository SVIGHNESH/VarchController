package dev.varch.controller

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.varch.controller.net.AppRef
import dev.varch.controller.net.BatteryInfo
import dev.varch.controller.net.Catalog
import dev.varch.controller.net.DesktopState
import dev.varch.controller.net.Endpoint
import dev.varch.controller.net.Found
import dev.varch.controller.net.Media
import dev.varch.controller.net.PlayerRef
import dev.varch.controller.net.Sink
import dev.varch.controller.net.SystemState
import dev.varch.controller.net.ThemeColors
import dev.varch.controller.net.Volume
import dev.varch.controller.net.Window
import dev.varch.controller.net.Workspaces
import dev.varch.controller.ui.DeskScreen
import dev.varch.controller.ui.Ink
import dev.varch.controller.ui.PadScreen
import dev.varch.controller.ui.Palette
import dev.varch.controller.ui.RemoteActions
import dev.varch.controller.ui.RemoteShell
import dev.varch.controller.ui.SetupScreen
import dev.varch.controller.ui.Surface
import dev.varch.controller.ui.SystemScreen
import dev.varch.controller.ui.Tab
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

object NoActions : RemoteActions {
    override fun send(action: String, value: Double, text: String) {}
    override fun setVolume(level: Float) {}
    override fun setBrightness(level: Float) {}
    override fun movePointer(dx: Float, dy: Float) {}
    override fun scrollPointer(dx: Float, dy: Float) {}
    override fun sendClipboard(text: String) {}
    override fun fetchClipboard() {}
    override fun share(text: String) {}
    override fun capture() {}
    override fun setAlerts(enabled: Boolean) {}
    override fun setSlides(enabled: Boolean) {}
    override fun setMatchTheme(enabled: Boolean) {}
    override fun setWatching(enabled: Boolean) {}
    override fun unpair() {}
}

/** Renders every screen state to build/outputs/roborazzi for visual review. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val endpoint = Endpoint("192.168.1.10")
    private val desktop = DesktopState(
        host = "VARCH",
        volume = Volume(0.35f, false),
        brightness = 0.6f,
        media = Media(
            available = true, player = "chromium.instance1", name = "Helium", status = "Paused",
            title = "Tailscale, Clearly Explained (Beginner's Guide)", artist = "David Ondrej",
            position = 114f, length = 184f, canSeek = true,
            players = listOf(PlayerRef("chromium.instance1", "Helium"), PlayerRef("spotify", "Spotify")),
        ),
        workspaces = Workspaces(2, setOf(1, 2, 3)),
        windows = listOf(
            Window("0xa1", "helium", "Tailscale, Clearly Explained - YouTube", 1, fullscreen = true),
            Window("0xb2", "com.t3tools.T3Code", "VarchController", 2, focused = true),
            Window("0xc3", "kitty", "~/Projects/VarchController", 2, floating = true),
            Window("0xd4", "org.gnome.Nautilus", "Downloads", 3),
        ),
    )
    private val system = SystemState(
        battery = BatteryInfo(48, charging = true, full = false),
        cpu = 12, memory = 74, temperature = 62,
        nightLight = false, wifi = true, bluetooth = true, micMuted = false,
        profile = "balanced", profiles = listOf("power-saver", "balanced", "performance"),
        sink = "speakers", sinks = listOf(Sink("speakers", "Built-in Audio Analog Stereo"), Sink("buds", "Nothing Ear (a)")),
        theme = "Ethereal",
    )
    private val catalog = Catalog(
        apps = listOf(AppRef("browser", "Browser"), AppRef("terminal", "Terminal"), AppRef("files", "Files"), AppRef("editor", "Editor"), AppRef("spotify", "Spotify")),
        themes = listOf("Aether", "Catppuccin", "Ethereal", "Everforest", "Gruvbox", "Kanagawa", "Matte Black", "Nord", "Osaka Jade", "Rose Pine", "Tokyo Night"),
    )
    private val online = RemoteUi("VARCH", endpoint.label, Link.Online, desktop, system, catalog)

    // The palette is process-wide, so a themed test must not leak into the next one.
    @After
    fun resetPalette() = Ink.use(Palette.Default)

    private fun themed(colors: ThemeColors) = Ink.use(Palette.from(colors)!!)

    private val tokyoNight = ThemeColors("#1a1b26", "#a9b1d6", "#7aa2f7", "#f7768e")
    private val catppuccinLatte = ThemeColors("#eff1f5", "#4c4f69", "#1e66f5", "#d20f39")
    private val white = ThemeColors("#ffffff", "#000000", "#6e6e6e", "#2a2a2a")

    @Test
    fun deckTokyoNight() {
        themed(tokyoNight)
        shell("theme_tokyo_night_deck", online)
    }

    @Test
    fun systemCatppuccinLatte() {
        themed(catppuccinLatte)
        shell("theme_latte_system", online, Tab.System)
    }

    @Test
    fun padWhite() {
        themed(white)
        shell("theme_white_pad", online, Tab.Pad)
    }

    @Test
    fun deskTokyoNight() {
        themed(tokyoNight)
        shoot("theme_tokyo_night_desk") { DeskScreen(online, NoActions, initiallyOpen = "0xc3") }
    }

    private fun shoot(name: String, content: @Composable () -> Unit) {
        compose.setContent { Surface(content) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    private fun shell(name: String, state: RemoteUi, tab: Tab = Tab.Deck) = shoot(name) { RemoteShell(state, NoActions, tab) }

    @Composable
    private fun Setup(state: SetupUi) = SetupScreen(state, {}, {}, {}, {}, {})

    @Test
    fun deck() = shell("deck", online)

    @Test
    fun deckMutedIdle() = shell(
        "deck_muted_idle",
        online.copy(
            desktop = desktop.copy(volume = Volume(0.8f, true), media = Media(), workspaces = Workspaces(7, setOf(1, 7))),
            system = system.copy(battery = BatteryInfo(14, charging = false, full = false)),
            notice = "No media player is running",
        ),
    )

    @Test
    @Config(qualifiers = "w360dp-h640dp-xhdpi")
    fun deckSmallPhone() = shell("deck_small", online)

    @Test
    @Config(qualifiers = "w360dp-h640dp-xhdpi", fontScale = 1.3f)
    fun deckSmallPhoneLargeText() = shell("deck_small_large_text", online.copy(desktop = desktop.copy(volume = Volume(1f, true))))

    @Test
    fun deckOffline() = shell("deck_offline", online.copy(link = Link.Offline))

    @Test
    fun deckConnecting() = shell("deck_connecting", RemoteUi("VARCH", endpoint.label, Link.Connecting))

    @Test
    fun pad() = shell("pad", online, Tab.Pad)

    @Test
    @Config(qualifiers = "w360dp-h640dp-xhdpi")
    fun padSmallPhone() = shell("pad_small", online, Tab.Pad)

    @Test
    fun slides() = shoot("slides") { PadScreen(online, NoActions, startInSlides = true) }

    @Test
    fun desk() = shell("desk", online, Tab.Desk)

    @Test
    fun deskWindowOpen() = shoot("desk_window_open") { DeskScreen(online, NoActions, initiallyOpen = "0xc3") }

    @Test
    fun deskEmpty() = shell("desk_empty", online.copy(desktop = desktop.copy(windows = emptyList()), catalog = Catalog()), Tab.Desk)

    @Test
    fun system() = shell("system", online, Tab.System)

    @Test
    fun systemThemes() = shoot("system_themes") { SystemScreen(online.copy(alerts = true), NoActions, themesOpen = true) }

    /** Tall enough to show the whole column, down to the credits. */
    @Test
    @Config(qualifiers = "w411dp-h1180dp-xxhdpi")
    fun systemCredits() = shoot("system_credits") { SystemScreen(online, NoActions) }

    @Test
    fun share() = shell("share", online.copy(clip = "git clone https://github.com/SVIGHNESH/VarchController"), Tab.Share)

    /** The live view is a dialog, which is its own window, so the whole screen is captured. */
    @Test
    fun liveView() {
        val picture = Bitmap.createBitmap(960, 540, Bitmap.Config.ARGB_8888).apply {
            val canvas = Canvas(this)
            canvas.drawColor(0xFF1A1B26.toInt())
            canvas.drawRect(40f, 40f, 620f, 500f, Paint().apply { color = 0xFF24283B.toInt() })
            canvas.drawRect(660f, 40f, 920f, 500f, Paint().apply { color = 0xFF7AA2F7.toInt() })
        }
        compose.setContent { Surface { RemoteShell(online.copy(watching = true, frame = picture.asImageBitmap()), NoActions, Tab.Share) } }
        compose.waitForIdle()
        captureScreenRoboImage("build/outputs/roborazzi/live_view.png")
    }

    @Test
    fun setupScanning() = shoot("setup_scanning") { Setup(SetupUi()) }

    @Test
    fun setupFound() = shoot("setup_found") {
        Setup(SetupUi(found = listOf(Found("VARCH", endpoint)), address = "10.0.0.4:7421", error = "Can't reach 10.0.0.4:7421. Check that varchd is running and this phone is on the same network."))
    }

    @Test
    fun setupCode() = shoot("setup_code") {
        Setup(SetupUi(step = PairStep.EnterCode(endpoint, "VARCH", "id"), code = "639", error = "Wrong code."))
    }
}
