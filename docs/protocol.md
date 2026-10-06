# Protocol

The daemon serves plain HTTP and one WebSocket on port 7421.
Bodies are JSON.
Errors come back as `{"error": "message"}` with a matching status code.

Any request that carries an `Origin` header gets `403`.
Browsers always send that header on cross-site requests, and the app never does.

## Pairing

These two endpoints need no token.

### `GET /v1/info`

```json
{ "name": "VARCH", "version": "0.2.0" }
```

### `POST /v1/pair/start`

Send `{"device": "Pixel 8"}`.
The desktop shows a 6-digit code as a notification, and the response carries the id of this pairing attempt.

```json
{ "pairing_id": "pUq3...", "expires_in": 120 }
```

Starting a second pairing within five seconds returns `429`.

### `POST /v1/pair/finish`

Send `{"pairing_id": "...", "code": "123456"}`.

| Status | Meaning |
| --- | --- |
| `200` | Paired. The body is `{"token": "..."}`. |
| `403` | Wrong code. Try again. |
| `410` | The pairing expired or used up its five attempts. Start over. |

## Authenticated endpoints

Everything below needs `Authorization: Bearer <token>` and returns `401` without a valid one.

| Endpoint | Purpose |
| --- | --- |
| `GET /v1/ws` | The live connection described below. |
| `POST /v1/action` | Runs one action and returns its result. The tiles and widget use this. |
| `GET /v1/status` | Returns the `system` object. The battery alerts use this. |
| `GET /v1/screenshot` | A JPEG of every monitor. |
| `GET /v1/art` | The current track's cover image, or `404`. |
| `POST /v1/unpair` | Revokes the token that made the request. |

## WebSocket

### Messages from the daemon

Each message has a `type`.

`catalog` arrives once, when the connection opens.

```json
{ "type": "catalog", "catalog": {
  "apps": [{ "id": "browser", "name": "Browser" }],
  "themes": ["Tokyo Night", "Nord"]
} }
```

`state` is polled every second and sent when it changes.

```json
{ "type": "state", "state": {
  "host": "VARCH",
  "volume": { "level": 0.35, "muted": false },
  "brightness": 0.15,
  "media": {
    "available": true, "player": "chromium.instance1", "name": "Helium",
    "status": "Playing", "title": "...", "artist": "...",
    "position": 114.2, "length": 184.8, "can_seek": true, "art": "9f2c1ab04e7d",
    "players": [{ "id": "chromium.instance1", "name": "Helium" }]
  },
  "workspaces": { "active": 1, "occupied": [1, 2, 3] },
  "windows": [{
    "address": "0x624a97796f60", "class": "kitty", "title": "~",
    "workspace": 2, "floating": false, "fullscreen": false, "focused": true
  }]
} }
```

`position` and `length` are in seconds.
While a track plays, the daemon does not resend the state every second.
It sends a new position only when the real one differs from the expected one by two seconds or more, so the client advances the clock between updates.
`art` changes whenever the cover does.

`system` is polled every five seconds, and right after any action.

```json
{ "type": "system", "system": {
  "battery": { "percent": 48, "charging": true, "full": false },
  "cpu": 12, "memory": 74, "temperature": 62,
  "night_light": false, "wifi": true, "bluetooth": true, "mic_muted": false,
  "profile": "balanced", "profiles": ["power-saver", "balanced", "performance"],
  "sink": "alsa_output...", "sinks": [{ "name": "alsa_output...", "label": "Built-in Audio" }],
  "theme": "Ethereal",
  "kbd_backlight": null
} }
```

`battery`, `night_light`, `wifi`, `bluetooth` and `kbd_backlight` are `null` when the desktop has no such feature.

`result` answers a request.

```json
{ "type": "result", "id": 7, "ok": false, "error": "that window no longer exists" }
```

`text` is present for actions that read something back.
Pointer and key actions get a result only when they fail.

### Requests from the phone

```json
{ "id": 7, "action": "window.move", "value": 4, "text": "0x624a97796f60" }
```

`id` is echoed in the result.
`value`, `text`, `x` and `y` are optional, and each action uses the ones listed below.

## Actions

### Media, volume, brightness

| Action | Arguments |
| --- | --- |
| `media.play_pause`, `media.next`, `media.previous` | None. |
| `media.seek` | `value` is the position in seconds. |
| `media.select` | `text` is a player id from `media.players`. |
| `volume.set` | `value` from 0 to 1. |
| `volume.mute_toggle` | None. |
| `brightness.set` | `value` from 0 to 1. The daemon never goes below 1%. |

### Workspaces, windows, apps

| Action | Arguments |
| --- | --- |
| `workspace.switch` | `value` is a workspace from 1 to 10. |
| `window.focus`, `window.close`, `window.fullscreen`, `window.float` | `text` is the window address. |
| `window.move` | `text` is the window address and `value` is the target workspace. |
| `app.launch` | `text` is an app id from the catalog. |

### Pointer and keyboard

| Action | Arguments |
| --- | --- |
| `pointer.move` | `x` and `y` in pixels, relative. |
| `pointer.scroll` | `x` and `y`. Positive `y` scrolls down. |
| `pointer.click`, `pointer.down`, `pointer.up` | `value` is 0 for left, 1 for right, 2 for middle. |
| `key.type` | `text` is typed as is. |
| `key.erase` | `value` is the number of backspaces, up to 500. |
| `key.press` | `text` is the key and `value` is a modifier bitmask. |

Modifier bits are 1 for Shift, 2 for Ctrl, 4 for Alt and 8 for Super.

`key.press` accepts single lowercase letters and digits, plus these names: `Return`, `Escape`, `Tab`, `BackSpace`, `Delete`, `space`, `Left`, `Right`, `Up`, `Down`, `Home`, `End`, `Page_Up`, `Page_Down`, and `F1` to `F12`.

### System and power

| Action | Arguments |
| --- | --- |
| `system.nightlight_toggle`, `system.mic_mute_toggle` | None. |
| `system.wifi`, `system.bluetooth` | `value` is 1 for on and 0 for off. |
| `system.profile` | `text` is `power-saver`, `balanced` or `performance`. |
| `system.sink` | `text` is a sink name from `system.sinks`. |
| `system.theme` | `text` is a theme from the catalog. |
| `system.kbd_backlight` | `value` from 0 to 1. |
| `power.lock`, `power.suspend`, `power.reboot`, `power.shutdown` | None. |

### Sharing

| Action | Arguments |
| --- | --- |
| `clipboard.set` | `text` goes to the desktop clipboard. |
| `clipboard.get` | None. The result's `text` holds the desktop clipboard. |
| `open.url` | `text` is an `http` or `https` address. |

Text arguments are limited to 100,000 bytes.
