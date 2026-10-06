package main

import (
	"context"
	"errors"
	"fmt"
	"math"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"unicode/utf8"
)

// Request is one thing a paired phone asks the desktop to do.
type Request struct {
	ID     int64   `json:"id"`
	Action string  `json:"action"`
	Value  float64 `json:"value"`
	X      float64 `json:"x"`
	Y      float64 `json:"y"`
	Text   string  `json:"text"`
}

type VolumeState struct {
	Level float64 `json:"level"`
	Muted bool    `json:"muted"`
}

type WorkspaceState struct {
	Active   int   `json:"active"`
	Occupied []int `json:"occupied"`
}

// State is the part of the desktop that changes from second to second.
type State struct {
	Host       string         `json:"host"`
	Volume     VolumeState    `json:"volume"`
	Brightness float64        `json:"brightness"`
	Media      MediaState     `json:"media"`
	Workspaces WorkspaceState `json:"workspaces"`
	Windows    []Window       `json:"windows"`
}

const (
	maxWorkspace = 10
	maxText      = 100_000
	maxPointer   = 600
)

// Desktop maps the fixed set of remote actions onto the local system.
// Nothing a client sends is ever passed to a shell.
type Desktop struct {
	host    string
	run     Runner
	media   Media
	pointer PointerDevice
	apps    *Apps
	lockCmd string

	cpuMu   sync.Mutex
	cpuPrev cpuSample
}

func NewDesktop(host string, run Runner, media Media, pointer PointerDevice, apps *Apps) *Desktop {
	lock := "loginctl"
	if have("omarchy-system-lock") {
		lock = "omarchy-system-lock"
	}
	return &Desktop{host: host, run: run, media: media, pointer: pointer, apps: apps, lockCmd: lock}
}

func unit(v float64) (float64, error) {
	if math.IsNaN(v) || v < 0 || v > 1 {
		return 0, errors.New("value must be between 0 and 1")
	}
	return v, nil
}

func onOff(v float64) (string, error) {
	switch v {
	case 0:
		return "off", nil
	case 1:
		return "on", nil
	}
	return "", errors.New("value must be 0 or 1")
}

func workspace(v float64) (int, error) {
	n := int(v)
	if float64(n) != v || n < 1 || n > maxWorkspace {
		return 0, fmt.Errorf("workspace must be an integer between 1 and %d", maxWorkspace)
	}
	return n, nil
}

func clampPointer(v float64) float64 {
	if math.IsNaN(v) {
		return 0
	}
	return math.Max(-maxPointer, math.Min(maxPointer, v))
}

// Quiet reports whether an action is high-frequency input that changes no
// reported state, so it needs neither a reply nor a state refresh.
func (r Request) Quiet() bool {
	return strings.HasPrefix(r.Action, "pointer.") || strings.HasPrefix(r.Action, "key.")
}

// Do performs a request. The returned text is only used by actions that
// read something back, such as clipboard.get.
func (d *Desktop) Do(ctx context.Context, r Request) (string, error) {
	var err error
	v := r.Value
	switch r.Action {
	case "media.play_pause":
		return "", d.media.Do("PlayPause")
	case "media.next":
		return "", d.media.Do("Next")
	case "media.previous":
		return "", d.media.Do("Previous")
	case "media.seek":
		if math.IsNaN(v) || v < 0 {
			return "", errors.New("position must not be negative")
		}
		return "", d.media.Seek(v)
	case "media.select":
		return "", d.media.Select(r.Text)

	case "volume.set":
		if v, err = unit(v); err != nil {
			return "", err
		}
		_, err = d.run.Run(ctx, "wpctl", "set-volume", "@DEFAULT_AUDIO_SINK@", strconv.FormatFloat(v, 'f', 2, 64))
	case "volume.mute_toggle":
		_, err = d.run.Run(ctx, "wpctl", "set-mute", "@DEFAULT_AUDIO_SINK@", "toggle")
	case "brightness.set":
		if v, err = unit(v); err != nil {
			return "", err
		}
		// Never go to 0%: on most panels that turns the backlight off entirely.
		pct := max(1, int(math.Round(v*100)))
		_, err = d.run.Run(ctx, "brightnessctl", "-q", "set", strconv.Itoa(pct)+"%")

	case "workspace.switch":
		var n int
		if n, err = workspace(v); err != nil {
			return "", err
		}
		// Hyprland's Lua config (0.5x+) takes a Lua expression; older releases
		// take the classic "workspace N" form.
		if _, err = d.run.Run(ctx, "hyprctl", "dispatch", fmt.Sprintf(`hl.dsp.focus({ workspace = "%d" })`, n)); err != nil {
			_, err = d.run.Run(ctx, "hyprctl", "dispatch", "workspace", strconv.Itoa(n))
		}
	case "window.focus", "window.close", "window.fullscreen", "window.float", "window.move":
		return "", d.window(ctx, r)

	case "app.launch":
		return "", d.launch(ctx, r.Text)

	case "pointer.move":
		return "", d.pointer.Move(clampPointer(r.X), clampPointer(r.Y))
	case "pointer.scroll":
		return "", d.pointer.Scroll(clampPointer(r.X), clampPointer(r.Y))
	case "pointer.down", "pointer.up", "pointer.click":
		button := int(v)
		if float64(button) != v || button < 0 || button >= len(pointerButtons) {
			return "", errors.New("unknown pointer button")
		}
		if r.Action != "pointer.up" {
			err = d.pointer.Button(button, true)
		}
		if err == nil && r.Action != "pointer.down" {
			err = d.pointer.Button(button, false)
		}
	case "key.type":
		if r.Text == "" || len(r.Text) > maxText || !utf8.ValidString(r.Text) {
			return "", errors.New("text to type is empty, too long or not valid UTF-8")
		}
		err = d.run.Feed(ctx, r.Text, "wtype", "-")
	case "key.press":
		var args []string
		if args, err = keyArgs(r.Text, int(v)); err != nil {
			return "", err
		}
		_, err = d.run.Run(ctx, "wtype", args...)
	case "key.erase":
		n := int(v)
		if n < 1 || n > 500 {
			return "", errors.New("erase count must be between 1 and 500")
		}
		args := make([]string, 0, 2*n)
		for range n {
			args = append(args, "-k", "BackSpace")
		}
		_, err = d.run.Run(ctx, "wtype", args...)

	case "system.nightlight_toggle", "system.mic_mute_toggle", "system.wifi", "system.bluetooth",
		"system.profile", "system.sink", "system.theme", "system.kbd_backlight":
		return "", d.system(ctx, r)

	case "power.lock":
		if d.lockCmd == "loginctl" {
			_, err = d.run.Run(ctx, "loginctl", "lock-session")
		} else {
			_, err = d.run.Run(ctx, d.lockCmd)
		}
	case "power.suspend":
		_, err = d.run.Run(ctx, "systemctl", "suspend")
	case "power.reboot":
		_, err = d.run.Run(ctx, "systemctl", "reboot")
	case "power.shutdown":
		_, err = d.run.Run(ctx, "systemctl", "poweroff")

	case "clipboard.set":
		if r.Text == "" || len(r.Text) > maxText {
			return "", errors.New("clipboard text is empty or too long")
		}
		err = d.run.Feed(ctx, r.Text, "wl-copy")
	case "clipboard.get":
		// wl-paste exits non-zero when the clipboard is empty or not text.
		out, perr := d.run.Run(ctx, "wl-paste", "--no-newline", "--type", "text")
		if perr != nil {
			return "", errors.New("the desktop clipboard has no text")
		}
		if len(out) > maxText {
			return "", errors.New("the desktop clipboard is too large to send")
		}
		return out, nil
	case "open.url":
		u, perr := url.Parse(strings.TrimSpace(r.Text))
		if perr != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" {
			return "", errors.New("only http and https links can be opened")
		}
		err = d.spawn("xdg-open", u.String())
	default:
		return "", fmt.Errorf("unknown action %q", r.Action)
	}
	return "", err
}

// spawn starts a desktop program outside the daemon's own service unit so
// it outlives a daemon restart.
func (d *Desktop) spawn(name string, args ...string) error {
	if have("uwsm-app") {
		return d.run.Spawn("uwsm-app", append([]string{"--", name}, args...)...)
	}
	return d.run.Spawn("systemd-run", append([]string{"--user", "--collect", "--quiet", "--", name}, args...)...)
}

func (d *Desktop) launch(_ context.Context, id string) error {
	app, ok := d.apps.Find(id)
	if !ok {
		return errors.New("that app is not in the launcher list")
	}
	return d.spawn(app.Exec[0], app.Exec[1:]...)
}

// Keys a remote may press by name. Single letters and digits are also allowed.
var namedKeys = map[string]bool{
	"Return": true, "Escape": true, "Tab": true, "BackSpace": true, "Delete": true, "space": true,
	"Left": true, "Right": true, "Up": true, "Down": true,
	"Home": true, "End": true, "Page_Up": true, "Page_Down": true,
	"F1": true, "F2": true, "F3": true, "F4": true, "F5": true, "F6": true,
	"F7": true, "F8": true, "F9": true, "F10": true, "F11": true, "F12": true,
}

var (
	plainKey   = regexp.MustCompile(`^[a-z0-9]$`)
	modifiers  = [...]string{"shift", "ctrl", "alt", "logo"}
	windowAddr = regexp.MustCompile(`^0x[0-9a-f]{1,16}$`)
)

// keyArgs builds the wtype arguments for one key with a modifier bitmask
// (1 shift, 2 ctrl, 4 alt, 8 super).
func keyArgs(key string, mods int) ([]string, error) {
	if !namedKeys[key] && !plainKey.MatchString(key) {
		return nil, fmt.Errorf("key %q is not allowed", key)
	}
	if mods < 0 || mods >= 1<<len(modifiers) {
		return nil, errors.New("unknown modifier")
	}
	var args []string
	for i, m := range modifiers {
		if mods&(1<<i) != 0 {
			args = append(args, "-M", m)
		}
	}
	args = append(args, "-k", key)
	for i := len(modifiers) - 1; i >= 0; i-- {
		if mods&(1<<i) != 0 {
			args = append(args, "-m", modifiers[i])
		}
	}
	return args, nil
}

// State reads the fast-changing desktop state. A failing probe leaves its
// section at the zero value rather than failing the whole snapshot.
func (d *Desktop) State(ctx context.Context) State {
	s := State{Host: d.host, Media: d.media.State()}
	if out, err := d.run.Run(ctx, "wpctl", "get-volume", "@DEFAULT_AUDIO_SINK@"); err == nil {
		s.Volume = parseVolume(out)
	}
	if out, err := d.run.Run(ctx, "brightnessctl", "-m"); err == nil {
		s.Brightness = parseBrightness(out)
	}
	s.Workspaces, s.Windows = d.hyprland(ctx)
	return s
}

// parseVolume parses `wpctl get-volume` output: "Volume: 0.35 [MUTED]".
func parseVolume(out string) VolumeState {
	f := strings.Fields(out)
	if len(f) < 2 {
		return VolumeState{}
	}
	level, _ := strconv.ParseFloat(f[1], 64)
	return VolumeState{Level: level, Muted: strings.Contains(out, "[MUTED]")}
}

// parseBrightness parses `brightnessctl -m` output: "dev,class,250,15%,1666".
func parseBrightness(out string) float64 {
	f := strings.Split(strings.TrimSpace(out), ",")
	if len(f) < 5 {
		return 0
	}
	cur, err1 := strconv.ParseFloat(f[2], 64)
	maxv, err2 := strconv.ParseFloat(f[4], 64)
	if err1 != nil || err2 != nil || maxv <= 0 {
		return 0
	}
	return cur / maxv
}
