package main

import (
	"context"
	"encoding/json"
	"errors"
	"math"
	"os"
	"path/filepath"
	"regexp"
	"slices"
	"strconv"
	"strings"
)

type Battery struct {
	Percent  int  `json:"percent"`
	Charging bool `json:"charging"`
	Full     bool `json:"full"`
}

type Sink struct {
	Name  string `json:"name"`
	Label string `json:"label"`
}

// SystemState is the slower-moving half of the desktop. A nil pointer means
// this machine does not have that feature, so the phone hides its control.
type SystemState struct {
	Battery      *Battery `json:"battery"`
	CPU          int      `json:"cpu"`
	Memory       int      `json:"memory"`
	Temperature  int      `json:"temperature"`
	NightLight   *bool    `json:"night_light"`
	Wifi         *bool    `json:"wifi"`
	Bluetooth    *bool    `json:"bluetooth"`
	MicMuted     bool     `json:"mic_muted"`
	Profile      string   `json:"profile"`
	Profiles     []string `json:"profiles"`
	Sink         string   `json:"sink"`
	Sinks        []Sink   `json:"sinks"`
	Theme        string   `json:"theme"`
	Colors       *Colors  `json:"colors"`
	KbdBacklight *float64 `json:"kbd_backlight"`
}

// Colors are the few colours every Omarchy theme defines. The phone derives
// the rest of its palette from them. Each is "#rrggbb"; Red may be empty.
type Colors struct {
	Background string `json:"background"`
	Foreground string `json:"foreground"`
	Accent     string `json:"accent"`
	Red        string `json:"red"`
}

var colorLine = regexp.MustCompile(`(?m)^\s*(\w+)\s*=\s*"(#[0-9a-fA-F]{6})"`)

// parseColors reads a theme's colors.toml. It returns nil unless the three
// colours a palette cannot do without are all present.
func parseColors(toml string) *Colors {
	found := map[string]string{}
	for _, m := range colorLine.FindAllStringSubmatch(toml, -1) {
		found[m[1]] = strings.ToLower(m[2])
	}
	c := &Colors{found["background"], found["foreground"], found["accent"], found["red"]}
	if c.Background == "" || c.Foreground == "" || c.Accent == "" {
		return nil
	}
	return c
}

// themeColors returns the active Omarchy theme's colours, if there is one.
func themeColors() *Colors {
	state := os.Getenv("XDG_STATE_HOME")
	if state == "" {
		home, err := os.UserHomeDir()
		if err != nil {
			return nil
		}
		state = filepath.Join(home, ".local", "state")
	}
	data, err := os.ReadFile(filepath.Join(state, "omarchy", "current", "theme", "colors.toml"))
	if err != nil {
		return nil
	}
	return parseColors(string(data))
}

// Catalog is what the phone needs once per connection rather than per poll.
type Catalog struct {
	Apps   []AppRef `json:"apps"`
	Themes []string `json:"themes"`
}

type AppRef struct {
	ID   string `json:"id"`
	Name string `json:"name"`
}

type cpuSample struct{ busy, total uint64 }

var powerProfiles = []string{"power-saver", "balanced", "performance"}

func (d *Desktop) Catalog(ctx context.Context) Catalog {
	c := Catalog{Apps: []AppRef{}, Themes: d.themes(ctx)}
	apps, err := d.apps.List()
	if err != nil {
		// A broken launcher file should not take the rest of the remote down.
		return c
	}
	for _, a := range apps {
		c.Apps = append(c.Apps, AppRef{a.ID, a.Name})
	}
	return c
}

func (d *Desktop) themes(ctx context.Context) []string {
	themes := []string{}
	if !have("omarchy-theme-list") {
		return themes
	}
	out, err := d.run.Run(ctx, "omarchy-theme-list")
	if err != nil {
		return themes
	}
	for line := range strings.Lines(out) {
		if t := strings.TrimSpace(line); t != "" {
			themes = append(themes, t)
		}
	}
	return themes
}

func (d *Desktop) sinks(ctx context.Context) []Sink {
	sinks := []Sink{}
	out, err := d.run.Run(ctx, "pactl", "-f", "json", "list", "sinks")
	if err != nil {
		return sinks
	}
	var raw []struct {
		Name        string `json:"name"`
		Description string `json:"description"`
	}
	if json.Unmarshal([]byte(out), &raw) == nil {
		for _, s := range raw {
			sinks = append(sinks, Sink{s.Name, s.Description})
		}
	}
	return sinks
}

func (d *Desktop) kbdBacklight() string {
	matches, _ := filepath.Glob("/sys/class/leds/*kbd_backlight*")
	if len(matches) == 0 {
		return ""
	}
	return filepath.Base(matches[0])
}

func (d *Desktop) System(ctx context.Context) SystemState {
	s := SystemState{
		Battery:     readBattery(),
		CPU:         d.cpu(),
		Memory:      readMemory(),
		Temperature: readTemperature(),
		Profiles:    []string{},
		Sinks:       d.sinks(ctx),
		Colors:      themeColors(),
	}
	if have("omarchy-toggle-nightlight") {
		if out, err := d.run.Run(ctx, "omarchy-toggle-nightlight", "--status"); err == nil {
			var status struct {
				Enabled bool `json:"enabled"`
			}
			if json.Unmarshal([]byte(out), &status) == nil {
				s.NightLight = &status.Enabled
			}
		}
	}
	if out, err := d.run.Run(ctx, "nmcli", "radio", "wifi"); err == nil {
		on := strings.TrimSpace(out) == "enabled"
		s.Wifi = &on
	}
	if out, err := d.run.Run(ctx, "bluetoothctl", "show"); err == nil && strings.Contains(out, "Powered:") {
		on := strings.Contains(out, "Powered: yes")
		s.Bluetooth = &on
	}
	if out, err := d.run.Run(ctx, "wpctl", "get-volume", "@DEFAULT_AUDIO_SOURCE@"); err == nil {
		s.MicMuted = parseVolume(out).Muted
	}
	if out, err := d.run.Run(ctx, "powerprofilesctl", "get"); err == nil {
		s.Profile = strings.TrimSpace(out)
		if list, err := d.run.Run(ctx, "powerprofilesctl", "list"); err == nil {
			for _, p := range powerProfiles {
				if strings.Contains(list, p+":") {
					s.Profiles = append(s.Profiles, p)
				}
			}
		}
	}
	if out, err := d.run.Run(ctx, "pactl", "get-default-sink"); err == nil {
		s.Sink = strings.TrimSpace(out)
	}
	if have("omarchy-theme-current") {
		if out, err := d.run.Run(ctx, "omarchy-theme-current"); err == nil {
			s.Theme = strings.TrimSpace(out)
		}
	}
	if led := d.kbdBacklight(); led != "" {
		if out, err := d.run.Run(ctx, "brightnessctl", "-m", "--device="+led); err == nil {
			level := parseBrightness(out)
			s.KbdBacklight = &level
		}
	}
	return s
}

func (d *Desktop) system(ctx context.Context, r Request) error {
	var err error
	switch r.Action {
	case "system.nightlight_toggle":
		_, err = d.run.Run(ctx, "omarchy-toggle-nightlight")
	case "system.mic_mute_toggle":
		_, err = d.run.Run(ctx, "wpctl", "set-mute", "@DEFAULT_AUDIO_SOURCE@", "toggle")
	case "system.wifi":
		var state string
		if state, err = onOff(r.Value); err == nil {
			_, err = d.run.Run(ctx, "nmcli", "radio", "wifi", state)
		}
	case "system.bluetooth":
		var state string
		if state, err = onOff(r.Value); err == nil {
			_, err = d.run.Run(ctx, "bluetoothctl", "power", state)
		}
	case "system.profile":
		if !slices.Contains(powerProfiles, r.Text) {
			return errors.New("unknown power profile")
		}
		_, err = d.run.Run(ctx, "powerprofilesctl", "set", r.Text)
	case "system.sink":
		if !slices.ContainsFunc(d.sinks(ctx), func(s Sink) bool { return s.Name == r.Text }) {
			return errors.New("that audio output is not available")
		}
		_, err = d.run.Run(ctx, "pactl", "set-default-sink", r.Text)
	case "system.theme":
		if !slices.Contains(d.themes(ctx), r.Text) {
			return errors.New("unknown theme")
		}
		// Applying a theme restarts parts of the desktop and can outlast a request.
		err = d.run.Spawn("omarchy-theme-set", r.Text)
	case "system.kbd_backlight":
		led := d.kbdBacklight()
		if led == "" {
			return errors.New("this machine has no keyboard backlight")
		}
		var level float64
		if level, err = unit(r.Value); err == nil {
			_, err = d.run.Run(ctx, "brightnessctl", "-q", "--device="+led, "set", strconv.Itoa(int(math.Round(level*100)))+"%")
		}
	}
	return err
}

func readTrimmed(path string) string {
	data, err := os.ReadFile(path)
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(data))
}

func readBattery() *Battery {
	dirs, _ := filepath.Glob("/sys/class/power_supply/BAT*")
	for _, dir := range dirs {
		pct, err := strconv.Atoi(readTrimmed(filepath.Join(dir, "capacity")))
		if err != nil {
			continue
		}
		status := readTrimmed(filepath.Join(dir, "status"))
		return &Battery{Percent: pct, Charging: status == "Charging", Full: status == "Full"}
	}
	return nil
}

// cpu returns the busy share since the previous call, in percent.
func (d *Desktop) cpu() int {
	line, _, _ := strings.Cut(readTrimmed("/proc/stat"), "\n")
	fields := strings.Fields(line)
	if len(fields) < 6 || fields[0] != "cpu" {
		return 0
	}
	var now cpuSample
	for i, f := range fields[1:] {
		n, _ := strconv.ParseUint(f, 10, 64)
		now.total += n
		if i != 3 && i != 4 { // idle, iowait
			now.busy += n
		}
	}
	d.cpuMu.Lock()
	prev := d.cpuPrev
	d.cpuPrev = now
	d.cpuMu.Unlock()
	if prev.total == 0 || now.total <= prev.total {
		return 0
	}
	return int(math.Round(100 * float64(now.busy-prev.busy) / float64(now.total-prev.total)))
}

func readMemory() int {
	var total, available float64
	for line := range strings.Lines(readTrimmed("/proc/meminfo")) {
		f := strings.Fields(line)
		if len(f) < 2 {
			continue
		}
		n, _ := strconv.ParseFloat(f[1], 64)
		switch f[0] {
		case "MemTotal:":
			total = n
		case "MemAvailable:":
			available = n
		}
	}
	if total == 0 {
		return 0
	}
	return int(math.Round(100 * (total - available) / total))
}

// readTemperature prefers the CPU package sensor and otherwise reports the
// hottest zone, in degrees Celsius.
func readTemperature() int {
	zones, _ := filepath.Glob("/sys/class/thermal/thermal_zone*")
	hottest := 0
	for _, zone := range zones {
		milli, err := strconv.Atoi(readTrimmed(filepath.Join(zone, "temp")))
		if err != nil {
			continue
		}
		c := int(math.Round(float64(milli) / 1000))
		if readTrimmed(filepath.Join(zone, "type")) == "x86_pkg_temp" {
			return c
		}
		hottest = max(hottest, c)
	}
	return hottest
}
