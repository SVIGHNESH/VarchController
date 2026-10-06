# Setup

There are two things to install.
The daemon goes on the desktop you want to control, and the app goes on the phone.

## Desktop daemon

### Requirements

- Go 1.27 or newer to build it.
- A running Hyprland session.
- These commands on the `PATH`: `wpctl`, `pactl`, `brightnessctl`, `hyprctl`, `wtype`, `wl-copy`, `wl-paste`, `grim`, `nmcli`, `bluetoothctl`, `powerprofilesctl`, `notify-send`.
- `avahi-publish` if you want the phone to find the desktop on its own.
- `mpv` if you want to cast the phone's screen to the desktop.

Night light uses `omarchy-toggle-nightlight` and themes use the `omarchy-theme-*` commands.
On a desktop without them the app hides those controls.

The pointer needs nothing extra.
The daemon speaks Hyprland's virtual-pointer protocol itself, so you do not need `ydotool`, root access, or a uinput rule.

### Install

```sh
cd daemon
make install
```

This builds `varchd`, copies it to `~/.local/bin/varchd`, installs a systemd user unit, and starts it.
Run the same command again to update.
Paired phones stay paired across updates.

Check that it is running:

```sh
systemctl --user status varchd
journalctl --user -u varchd -f
```

To remove it, run `make uninstall`.

### Firewall

The daemon listens on TCP port 7421.
If `ufw` or another firewall is active, open the port or the phone will fail to connect.

For a home network:

```sh
sudo ufw allow 7421/tcp
```

For Tailscale only, which keeps the port closed to the rest of the Wi-Fi:

```sh
sudo ufw allow in on tailscale0 to any port 7421 proto tcp
```

To listen on a different port, edit `ExecStart` in `~/.config/systemd/user/varchd.service` and add `-listen :PORT`.

### Files

| Path | Contents |
| --- | --- |
| `~/.config/varchd/devices.json` | Paired phones, stored as token hashes. |
| `~/.config/varchd/apps.json` | The app launcher list. |

## Android app

### Build and install

You need JDK 17 or newer and the Android SDK with platform 37.

```sh
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The app runs on Android 8 (API 26) and newer.

### Pair

1. Open the app.
   If the phone and desktop share a network, the desktop shows up under "On this network".
2. Tap it, or type the desktop's address under "Or by address".
3. A notification on the desktop shows a 6-digit code.
   Type it into the app.

<p>
  <img src="images/setup_found.png" width="230" alt="Setup screen listing a desktop found on the network">
  <img src="images/setup_code.png" width="230" alt="Pairing code entry">
</p>

A code lasts two minutes and allows five attempts.
The phone keeps its pairing until you tap UNPAIR on the SYS tab, which also removes the phone from the desktop.

## Using Tailscale

Tailscale is the better way to connect.
WireGuard encrypts the traffic, and the remote works away from home.

1. Run Tailscale on both the desktop and the phone.
2. Open the port on `tailscale0` as shown above.
3. In the app, type the desktop's Tailscale address or MagicDNS name.

The network scan does not work over Tailscale, because mDNS does not cross it.
You type the address once and the app remembers it.

## App launcher

The DESK tab launches the apps listed in `~/.config/varchd/apps.json`.
The daemon writes a starter list on first run from the launchers it finds.

```json
[
  { "id": "browser", "name": "Browser", "exec": ["omarchy-launch-browser"] },
  { "id": "notes", "name": "Notes", "exec": ["obsidian"] }
]
```

- `id` is lowercase letters, digits, `-` and `_`, and must be unique.
- `name` is the label on the phone.
- `exec` is the program followed by its arguments, one list item each.

Changes apply the next time the phone connects.
If the file is invalid, the daemon logs the reason and the launcher shows no apps.
