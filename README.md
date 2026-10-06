# Varch Controller

A phone remote for a Hyprland desktop.
`varchd` is a small Go daemon that runs on the desktop, and the Android app pairs with it over your home network or Tailscale.

<p>
  <img src="docs/images/deck.png" width="230" alt="Deck tab with media, volume, brightness and workspaces">
  <img src="docs/images/pad.png" width="230" alt="Pad tab with trackpad and keyboard">
  <img src="docs/images/system.png" width="230" alt="System tab with status and toggles">
</p>

## What it does

The app has five tabs.

- **DECK** has media controls with seek and cover art, volume, brightness, and workspaces 1-10.
- **PAD** is a trackpad and keyboard, with a SLIDES mode for presentations.
- **DESK** lists open windows and launches apps.
- **SYS** shows battery, CPU, memory and temperature, and holds the system toggles and power keys.
- **SHARE** moves clipboard text, links and screenshots between the phone and the desktop, and casts either screen to the other.

The app recolours itself to match the desktop's Omarchy theme, and follows along when you change it.

It also adds three quick-settings tiles, a home-screen widget, a share-sheet target, and optional desktop battery alerts.

## Quick start

On the desktop, build and start the daemon:

```sh
cd daemon
make install
```

On the phone, install the signed APK from the [latest release](https://github.com/SVIGHNESH/VarchController/releases/latest) and open the app.

Each release also carries a prebuilt daemon, and [Setup](docs/setup.md) covers installing from it or from the AUR.

Pick the desktop from the list or type its address, then enter the 6-digit code that appears as a desktop notification.

If the phone cannot connect, the firewall is the usual cause.
[Setup](docs/setup.md) covers that, along with Tailscale.

## Documentation

- [Setup](docs/setup.md): requirements, installing both halves, firewall, Tailscale, pairing.
- [Using the app](docs/guide.md): every tab, the tiles, the widget, and sharing.
- [Protocol](docs/protocol.md): the HTTP and WebSocket API and the full action list.
- [Security](docs/security.md): what a paired phone can do and what protects the daemon.
- [Development](docs/development.md): layout, building, tests, and known gaps.
- [Releasing](docs/releasing.md): the CI and release pipeline, and its one-time setup.

## Requirements

- A Linux desktop running Hyprland, with PipeWire audio.
  Night light and theme switching need Omarchy.
- An Android phone on Android 8 or newer.

## Licenses

Varch Controller is released under the [MIT License](LICENSE).
The app bundles JetBrains Mono (Nerd Font build) under the SIL Open Font License 1.1, included in [licenses/JetBrainsMono-OFL.txt](licenses/JetBrainsMono-OFL.txt).
