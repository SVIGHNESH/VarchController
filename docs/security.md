# Security

Pairing a phone is close to handing someone your keyboard.
Read this before you pair a phone you do not control, or run the daemon on a network you do not trust.

## What a paired phone can do

- Type any text and press keys, which includes typing commands into an open terminal.
- Move the pointer and click.
- Read and replace the clipboard.
- Capture the screen, and watch it live.
- Open a window on the desktop showing the phone's screen.
- Close windows, launch the apps in the launcher list, and open web links.
- Lock, suspend, reboot and shut down the desktop.

## What it cannot do

The daemon has a fixed list of actions and rejects anything else.
It never builds a shell command from what the phone sends.

- Typed text and clipboard text reach `wtype` and `wl-copy` on standard input, never as arguments.
- Key names, power profiles, audio outputs, themes and app ids are checked against a list before use.
- Window actions run only against a window address that exists at that moment.
- The launcher starts only the programs listed in `apps.json`, which the phone cannot edit.
- `open.url` accepts `http` and `https` addresses and nothing else.

## Pairing

- A pairing code is six digits, lasts two minutes, and allows five attempts.
- Only one pairing can be in progress, and a new one cannot start within five seconds of the last.
- The code appears on the desktop, so pairing needs someone who can see that screen.
- The desktop stores a SHA-256 hash of each token in `~/.config/varchd/devices.json`, never the token.
- UNPAIR in the app revokes the token on the desktop.

To revoke a phone you no longer have, delete its entry from `devices.json` and restart the daemon with `systemctl --user restart varchd`.

## Network

Traffic is plain HTTP and WebSocket.
Anyone who can capture packets on the same network can read the token and then control the desktop.

Use Tailscale to close that gap.
WireGuard encrypts everything between the phone and the desktop, and you can open the port on `tailscale0` alone so the daemon is unreachable from the Wi-Fi.
[Setup](setup.md#using-tailscale) has the commands.

The daemon refuses requests that carry an `Origin` header.
A web page open in a browser on your network therefore cannot call it.

## On the phone

- The token sits in the app's private storage and is excluded from cloud backup and device transfer.
- The quick-settings tiles and the widget can send three actions only: play/pause, mute and lock.
- Text shared to the app from other apps goes to the desktop, so do not share secrets to it by accident.
