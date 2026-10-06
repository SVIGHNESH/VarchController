# Development

## Layout

```
daemon/                      Go daemon (varchd)
  main.go                    flags, startup, mDNS advertising
  server.go                  HTTP endpoints, WebSocket, state polling
  auth.go                    pairing codes and device tokens
  desktop.go                 the action list and its validation
  hypr.go                    workspaces, windows, screen capture
  cast.go                    screen casting in both directions
  pointer.go                 Wayland virtual-pointer client
  mpris.go                   media players over D-Bus
  system.go                  status readouts and system toggles
  apps.go                    launcher list
  run.go                     the one place that starts other programs
packaging/aur/               PKGBUILD for the varchd AUR package
.github/workflows/           CI and the release pipeline, see releasing.md
app/src/main/java/dev/varch/controller/
  MainActivity.kt            entry point, share intents, volume keys
  RemoteViewModel.kt         pairing, connection, and all app state
  Shortcuts.kt               tiles and one-shot actions
  Widgets.kt                 home-screen widgets and their snapshot of the desktop
  BatteryAlerts.kt           periodic battery check
  CastService.kt             casts the phone's screen (MediaProjection, H.264)
  net/                       protocol types, HTTP client, mDNS discovery
  ui/                        one file per tab, plus shared controls and theme
```

## Daemon

```sh
cd daemon
make test       # go vet and go test -race
make build      # ./varchd
make install    # install and restart the user service
```

Run a second copy for testing without touching the installed one.
`XDG_CONFIG_HOME` gives it its own pairing and launcher files.

```sh
XDG_CONFIG_HOME=/tmp/varchd-test ./varchd -listen 127.0.0.1:17421
```

The pairing code is printed to the log as well as shown as a notification.

Tests replace the command runner, the media backend and the pointer with fakes, so they never touch the real desktop.

### Hyprland notes

Hyprland 0.5x with a Lua config takes Lua expressions in `hyprctl dispatch`, for example `hl.dsp.focus({ workspace = "3" })`.
The older `workspace 3` form fails there.
The daemon tries the Lua form first and falls back for workspace switching.

Some window dispatchers act on the focused window when they cannot resolve the window you named, or when they do not recognise an argument.
That is why `hypr.go` confirms the address exists before it dispatches.
When you test window actions by hand, spawn a throwaway window and always pass `window = "address:0x..."`.

## App

Requires JDK 17 or newer and Android SDK platform 37.

```sh
./gradlew :app:assembleDebug          # APK in app/build/outputs/apk/debug
./gradlew :app:testDebugUnitTest      # unit tests
./gradlew :app:lintDebug              # lint, warnings are errors
./gradlew :app:recordRoborazziDebug   # render every screen to PNG
```

The UI is drawn with Compose foundation primitives and no Material components.
`Ink` in `ui/Theme.kt` holds the current palette as Compose state, so every screen redraws when the desktop theme changes.
`Palette.from` derives that palette from the theme's base colours.
Colours and type live in `ui/Theme.kt`, and the shared keys, faders and trackpad live in `ui/Controls.kt`.

### Looking at the UI without a device

`ScreenshotTest` renders each screen state with Roborazzi.
The PNGs land in `app/build/outputs/roborazzi`.
The images in `docs/images` are copies of those.

### Testing the app against a real daemon

`DaemonContractTest` drives a running daemon with the app's own client.
It is skipped unless both variables are set.

```sh
VARCHD_ADDR=127.0.0.1:17421 VARCHD_LOG=/tmp/varchd.log ./gradlew :app:testDebugUnitTest
```

`VARCHD_LOG` is the daemon's log file, which the test reads to get the pairing code.

## Adding an action

1. Add a case to `Desktop.Do` in `daemon/desktop.go`, and validate every argument before acting.
2. Add it to `TestDesktopActions` and its bad inputs to `TestDesktopRejectsBadInput`.
3. Add a constant to `Action` in `net/Protocol.kt` and call `actions.send` from the screen.
4. Document it in [protocol.md](protocol.md).

## Known gaps

- Casting the phone's screen has never run on a phone.
  The desktop half is tested with a synthetic H.264 stream, and `CastService` is written against the Android API without a device to run it on.
  That includes following the phone's rotation: the viewer is checked against a stream that changes shape midway, and the phone's side of it is untested.
- The live view of the desktop is tested from the daemon to the app's network client, and its screen is rendered, but it has not been used on a phone.
- The 0.2.0 features have been tested on the desktop side and in rendered screenshots, and not yet on a physical phone.
  That covers trackpad feel, live typing through a phone keyboard, the tiles, the widget, the share sheet and battery alerts.
- The home-screen widgets are rendered by `ScreenshotTest` and have not been placed on a real launcher.
  Their fetch after a key press, the half-hourly update and the bundled font inside a launcher are untested.
- Theme switching, the Wi-Fi and Bluetooth toggles, reboot, shut down and `open.url` have unit tests for their command mapping and have not been fired on a real desktop.
- Keyboard backlight control exists and is untested, because the development laptop has no backlight LED.
- Wake-on-LAN is not implemented.
  A magic packet cannot reach a sleeping machine over Tailscale.
- `targetSdk` is 36.
  Android 37 enforces a local-network permission that needs testing on a device first.
- The connection is unencrypted unless you use Tailscale.
- The daemon polls the desktop once a second while a phone is connected.
  Listening to Hyprland's event socket and to D-Bus signals would be lighter.
