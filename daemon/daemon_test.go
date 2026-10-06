package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/coder/websocket"
)

type fakeRunner struct {
	mu    sync.Mutex
	calls []string
	stdin []string
}

func (f *fakeRunner) record(name string, args []string) string {
	f.mu.Lock()
	defer f.mu.Unlock()
	call := strings.Join(append([]string{name}, args...), " ")
	f.calls = append(f.calls, call)
	return call
}

func (f *fakeRunner) Run(_ context.Context, name string, args ...string) (string, error) {
	switch f.record(name, args) {
	case "wpctl get-volume @DEFAULT_AUDIO_SINK@":
		return "Volume: 0.35 [MUTED]\n", nil
	case "brightnessctl -m":
		return "intel_backlight,backlight,250,15%,1000\n", nil
	case "hyprctl -j activeworkspace":
		return `{"id": 2, "name": "2"}`, nil
	case "hyprctl -j clients":
		return `[
			{"address":"0xb2","class":"kitty","title":"shell","mapped":true,"workspace":{"id":2},"focusHistoryID":0},
			{"address":"0xa1","class":"browser","title":"docs","mapped":true,"workspace":{"id":1},"fullscreen":2,"focusHistoryID":1},
			{"address":"0xc3","class":"scratch","title":"pad","mapped":true,"workspace":{"id":-98},"focusHistoryID":2}]`, nil
	case "pactl -f json list sinks":
		return `[{"name":"speakers","description":"Built-in"},{"name":"headset","description":"Headset"}]`, nil
	case "grim -c -t ppm -":
		// A 4x2 picture whose content changes on every capture.
		f.mu.Lock()
		shade := byte(len(f.calls))
		f.mu.Unlock()
		return "P6\n4 2\n255\n" + string(bytes.Repeat([]byte{shade, 0, 255 - shade}, 8)), nil
	case "wl-paste --no-newline --type text":
		return "from desktop", nil
	}
	return "", nil
}

func (f *fakeRunner) Feed(_ context.Context, stdin, name string, args ...string) error {
	f.record(name, args)
	f.mu.Lock()
	f.stdin = append(f.stdin, stdin)
	f.mu.Unlock()
	return nil
}

func (f *fakeRunner) Spawn(name string, args ...string) error {
	f.record("spawn "+name, args)
	return nil
}

func (f *fakeRunner) last() string {
	f.mu.Lock()
	defer f.mu.Unlock()
	if len(f.calls) == 0 {
		return ""
	}
	return f.calls[len(f.calls)-1]
}

func (f *fakeRunner) ran(call string) bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	for _, c := range f.calls {
		if c == call || strings.HasSuffix(c, call) {
			return true
		}
	}
	return false
}

type fakeMedia struct {
	did    []string
	seeked float64
}

func (m *fakeMedia) State() MediaState {
	return MediaState{Available: true, Player: "mpv", Status: "Playing", Title: "Song", Players: []PlayerRef{{"mpv", "mpv"}}}
}
func (m *fakeMedia) Do(method string) error { m.did = append(m.did, method); return nil }
func (m *fakeMedia) Seek(p float64) error   { m.seeked = p; return nil }
func (m *fakeMedia) Select(string) error    { return nil }
func (m *fakeMedia) Art() ([]byte, error)   { return nil, errNoMedia }

type fakePointer struct{ events []string }

func (p *fakePointer) Move(dx, dy float64) error {
	p.events = append(p.events, fmt.Sprintf("move %v %v", dx, dy))
	return nil
}
func (p *fakePointer) Scroll(dx, dy float64) error {
	p.events = append(p.events, fmt.Sprintf("scroll %v %v", dx, dy))
	return nil
}
func (p *fakePointer) Button(b int, down bool) error {
	p.events = append(p.events, fmt.Sprintf("button %d %v", b, down))
	return nil
}

func newDesktop(t *testing.T) (*Desktop, *fakeRunner, *fakeMedia, *fakePointer) {
	t.Helper()
	path := filepath.Join(t.TempDir(), "apps.json")
	os.WriteFile(path, []byte(`[{"id":"term","name":"Terminal","exec":["kitty","--single-instance"]}]`), 0o600)
	r, m, p := &fakeRunner{}, &fakeMedia{}, &fakePointer{}
	return &Desktop{host: "VARCH", run: r, media: m, pointer: p, apps: &Apps{path: path}, lockCmd: "omarchy-system-lock"}, r, m, p
}

func TestDesktopActions(t *testing.T) {
	d, r, m, p := newDesktop(t)
	cases := []struct {
		req  Request
		want string
	}{
		{Request{Action: "volume.set", Value: 0.5}, "wpctl set-volume @DEFAULT_AUDIO_SINK@ 0.50"},
		{Request{Action: "volume.mute_toggle"}, "wpctl set-mute @DEFAULT_AUDIO_SINK@ toggle"},
		{Request{Action: "brightness.set", Value: 0.4}, "brightnessctl -q set 40%"},
		{Request{Action: "brightness.set"}, "brightnessctl -q set 1%"},
		{Request{Action: "workspace.switch", Value: 3}, `hyprctl dispatch hl.dsp.focus({ workspace = "3" })`},
		{Request{Action: "window.focus", Text: "0xa1"}, `hyprctl dispatch hl.dsp.focus({ window = "address:0xa1" })`},
		{Request{Action: "window.close", Text: "0xa1"}, `hyprctl dispatch hl.dsp.window.close({ window = "address:0xa1" })`},
		{Request{Action: "window.move", Text: "0xb2", Value: 4}, `hyprctl dispatch hl.dsp.window.move({ window = "address:0xb2", workspace = "4", follow = false })`},
		{Request{Action: "key.press", Text: "Return"}, "wtype -k Return"},
		{Request{Action: "key.press", Text: "c", Value: 2}, "wtype -M ctrl -k c -m ctrl"},
		{Request{Action: "key.press", Text: "Tab", Value: 5}, "wtype -M shift -M alt -k Tab -m alt -m shift"},
		{Request{Action: "key.erase", Value: 2}, "wtype -k BackSpace -k BackSpace"},
		{Request{Action: "system.wifi", Value: 0}, "nmcli radio wifi off"},
		{Request{Action: "system.bluetooth", Value: 1}, "bluetoothctl power on"},
		{Request{Action: "system.profile", Text: "performance"}, "powerprofilesctl set performance"},
		{Request{Action: "system.sink", Text: "headset"}, "pactl set-default-sink headset"},
		{Request{Action: "system.mic_mute_toggle"}, "wpctl set-mute @DEFAULT_AUDIO_SOURCE@ toggle"},
		{Request{Action: "power.lock"}, "omarchy-system-lock"},
		{Request{Action: "power.suspend"}, "systemctl suspend"},
		{Request{Action: "power.reboot"}, "systemctl reboot"},
		{Request{Action: "power.shutdown"}, "systemctl poweroff"},
		{Request{Action: "app.launch", Text: "term"}, "kitty --single-instance"},
		{Request{Action: "open.url", Text: " https://example.com/a?b=c "}, "xdg-open https://example.com/a?b=c"},
	}
	for _, c := range cases {
		if _, err := d.Do(context.Background(), c.req); err != nil {
			t.Errorf("%s: %v", c.req.Action, err)
		}
		if !r.ran(c.want) {
			t.Errorf("%s: last ran %q, want %q", c.req.Action, r.last(), c.want)
		}
	}

	d.Do(context.Background(), Request{Action: "media.next"})
	d.Do(context.Background(), Request{Action: "media.seek", Value: 42})
	if m.did[0] != "Next" || m.seeked != 42 {
		t.Errorf("media: did=%v seeked=%v", m.did, m.seeked)
	}

	d.Do(context.Background(), Request{Action: "pointer.move", X: 3, Y: 9999})
	d.Do(context.Background(), Request{Action: "pointer.click", Value: 1})
	want := []string{"move 3 600", "button 1 true", "button 1 false"}
	if strings.Join(p.events, "|") != strings.Join(want, "|") {
		t.Errorf("pointer events = %v", p.events)
	}
}

func TestTextNeverBecomesAnArgument(t *testing.T) {
	d, r, _, _ := newDesktop(t)
	for _, action := range []string{"key.type", "clipboard.set"} {
		if _, err := d.Do(context.Background(), Request{Action: action, Text: "--help; rm -rf ~"}); err != nil {
			t.Fatal(err)
		}
	}
	if len(r.stdin) != 2 || r.stdin[0] != "--help; rm -rf ~" {
		t.Errorf("stdin = %q", r.stdin)
	}
	if r.calls[0] != "wtype -" || r.calls[1] != "wl-copy" {
		t.Errorf("calls = %q", r.calls)
	}
	if text, err := d.Do(context.Background(), Request{Action: "clipboard.get"}); err != nil || text != "from desktop" {
		t.Errorf("clipboard.get = %q, %v", text, err)
	}
}

func TestDesktopRejectsBadInput(t *testing.T) {
	d, r, _, p := newDesktop(t)
	bad := []Request{
		{Action: "volume.set", Value: 1.5}, {Action: "volume.set", Value: -0.1}, {Action: "brightness.set", Value: 2},
		{Action: "workspace.switch"}, {Action: "workspace.switch", Value: 11}, {Action: "workspace.switch", Value: 2.5},
		{Action: "window.close", Text: `0x1" }) os.exit(`}, {Action: "window.move", Text: "0xa1", Value: 99},
		{Action: "key.press", Text: "XF86PowerOff"}, {Action: "key.press", Text: "-M"}, {Action: "key.press", Text: "a", Value: 64},
		{Action: "key.type"}, {Action: "key.erase", Value: 9999},
		{Action: "system.wifi", Value: 3}, {Action: "system.profile", Text: "turbo"},
		{Action: "system.sink", Text: "nope"}, {Action: "system.theme", Text: "nope"},
		{Action: "app.launch", Text: "rm"}, {Action: "open.url", Text: "file:///etc/passwd"},
		{Action: "open.url", Text: "javascript:alert(1)"}, {Action: "pointer.click", Value: 7},
		{Action: "media.seek", Value: -1}, {Action: "clipboard.set"},
		{Action: "shell.exec"}, {},
	}
	for _, req := range bad {
		if _, err := d.Do(context.Background(), req); err == nil {
			t.Errorf("%s(%v, %q): expected an error", req.Action, req.Value, req.Text)
		}
	}
	for _, c := range r.calls {
		// Lookups made while validating are fine; nothing may have acted.
		if c != "hyprctl -j clients" && c != "pactl -f json list sinks" && c != "omarchy-theme-list" {
			t.Errorf("rejected input still ran %q", c)
		}
	}
	if len(p.events) != 0 {
		t.Errorf("rejected input moved the pointer: %v", p.events)
	}
}

func TestWindowMustExist(t *testing.T) {
	d, r, _, _ := newDesktop(t)
	if _, err := d.Do(context.Background(), Request{Action: "window.close", Text: "0xdead"}); err == nil {
		t.Error("closing a window that is gone should fail, not fall through to the focused one")
	}
	if r.ran("hl.dsp.window.close") {
		t.Errorf("dispatched anyway: %v", r.calls)
	}
}

func TestDesktopState(t *testing.T) {
	d, _, _, _ := newDesktop(t)
	s := d.State(context.Background())
	if s.Volume.Level != 0.35 || !s.Volume.Muted {
		t.Errorf("volume = %+v", s.Volume)
	}
	if s.Brightness != 0.25 {
		t.Errorf("brightness = %v", s.Brightness)
	}
	if s.Workspaces.Active != 2 || len(s.Workspaces.Occupied) != 2 || s.Workspaces.Occupied[0] != 1 {
		t.Errorf("workspaces = %+v", s.Workspaces)
	}
	if len(s.Windows) != 2 || s.Windows[0].Address != "0xa1" || !s.Windows[0].Fullscreen || !s.Windows[1].Focused {
		t.Errorf("windows = %+v", s.Windows)
	}
	if c := d.Catalog(context.Background()); len(c.Apps) != 1 || c.Apps[0] != (AppRef{"term", "Terminal"}) {
		t.Errorf("catalog = %+v", c)
	}
}

func TestPositionDriftIsNotAChange(t *testing.T) {
	sent := State{Media: MediaState{Status: "Playing", Position: 10}}
	now := sent
	now.Media.Position = 13
	if stateChanged(sent, now, 3) {
		t.Error("a position that advanced with the clock should not be re-sent")
	}
	now.Media.Position = 60
	if !stateChanged(sent, now, 3) {
		t.Error("a seek should be re-sent")
	}
	now = sent
	now.Volume.Level = 0.5
	if !stateChanged(sent, now, 0) {
		t.Error("a volume change should be re-sent")
	}
}

func TestShrinkPPM(t *testing.T) {
	// 4x2, left half black and right half white.
	pix := []byte{0, 0, 0, 0, 0, 0, 255, 255, 255, 255, 255, 255, 0, 0, 0, 0, 0, 0, 255, 255, 255, 255, 255, 255}
	img, err := shrinkPPM(append([]byte("P6\n4 2\n255\n"), pix...), 2)
	if err != nil {
		t.Fatal(err)
	}
	if b := img.Bounds(); b.Dx() != 2 || b.Dy() != 1 {
		t.Fatalf("size = %v", b)
	}
	if img.Pix[0] != 0 || img.Pix[4] != 255 || img.Pix[3] != 255 {
		t.Errorf("pixels = %v", img.Pix)
	}
	if same, _ := shrinkPPM(append([]byte("P6\n4 2\n255\n"), pix...), 4); same.Bounds().Dx() != 4 {
		t.Error("a picture within the limit should keep its size")
	}
	if _, err := shrinkPPM([]byte("P6\n4 2\n255\nshort"), 4); err == nil {
		t.Error("a truncated capture should be rejected")
	}
	if _, err := shrinkPPM([]byte("\x89PNG"), 4); err == nil {
		t.Error("a non-PPM capture should be rejected")
	}
}

func TestParseColors(t *testing.T) {
	c := parseColors("mode = \"dark\"\naccent = \"#7AA2F7\"\n\nbackground = \"#1a1b26\"\nforeground = \"#a9b1d6\"\nred=\"#f7768e\"\nbad = \"blue\"\n")
	if c == nil || *c != (Colors{"#1a1b26", "#a9b1d6", "#7aa2f7", "#f7768e"}) {
		t.Errorf("colors = %+v", c)
	}
	if parseColors("accent = \"#ffffff\"") != nil {
		t.Error("a theme without background and foreground has no usable palette")
	}
}

func TestWaylandEncoding(t *testing.T) {
	if got := wlString("abc"); len(got) != 8 || got[0] != 4 || got[7] != 0 {
		t.Errorf("wlString(abc) = %v", got)
	}
	if got := wlString("abcd"); len(got) != 12 || got[0] != 5 {
		t.Errorf("wlString(abcd) = %v", got)
	}
	if wlFixed(1.5) != 384 || int32(wlFixed(-2)) != -512 {
		t.Errorf("wlFixed: %d %d", wlFixed(1.5), int32(wlFixed(-2)))
	}
	msg := wlMessage(5, 4)
	if len(msg) != 8 || msg[0] != 5 || msg[4] != 4 || msg[6] != 8 {
		t.Errorf("wlMessage = %v", msg)
	}
}

func newAuth(t *testing.T) *Auth {
	t.Helper()
	a, err := NewAuth(filepath.Join(t.TempDir(), "varchd", "devices.json"))
	if err != nil {
		t.Fatal(err)
	}
	return a
}

func TestPairing(t *testing.T) {
	a := newAuth(t)
	now := time.Now()
	a.now = func() time.Time { return now }

	id, code, err := a.StartPairing("phone")
	if err != nil {
		t.Fatal(err)
	}
	if _, _, err := a.StartPairing("phone"); !errors.Is(err, errPairingBusy) {
		t.Errorf("second start: %v, want cooldown", err)
	}
	if _, err := a.FinishPairing(id, "nope"); !errors.Is(err, errPairingCode) {
		t.Errorf("wrong code: %v", err)
	}
	token, err := a.FinishPairing(id, code)
	if err != nil {
		t.Fatal(err)
	}
	if !a.Check(token) || a.Check("other") || a.Check("") {
		t.Error("token check is wrong")
	}
	if _, err := a.FinishPairing(id, code); !errors.Is(err, errPairingInvalid) {
		t.Errorf("code reuse: %v", err)
	}

	// Tokens survive a restart, and only as hashes.
	b, err := NewAuth(a.path)
	if err != nil || !b.Check(token) {
		t.Errorf("reload: err=%v", err)
	}
	if data, _ := os.ReadFile(a.path); bytes.Contains(data, []byte(token)) {
		t.Error("raw token was written to disk")
	}
	if err := b.Revoke(token); err != nil || b.Check(token) {
		t.Errorf("revoke: err=%v", err)
	}
}

func TestPairingLimits(t *testing.T) {
	a := newAuth(t)
	now := time.Now()
	a.now = func() time.Time { return now }

	id, code, _ := a.StartPairing("phone")
	for range pairingAttempts {
		a.FinishPairing(id, "000000x")
	}
	if _, err := a.FinishPairing(id, code); !errors.Is(err, errPairingInvalid) {
		t.Errorf("after too many attempts: %v", err)
	}

	now = now.Add(pairingCooldown)
	id, code, _ = a.StartPairing("phone")
	now = now.Add(pairingTTL + time.Second)
	if _, err := a.FinishPairing(id, code); !errors.Is(err, errPairingInvalid) {
		t.Errorf("after expiry: %v", err)
	}
}

func TestServerEndToEnd(t *testing.T) {
	desk, r, _, _ := newDesktop(t)
	var code string
	srv := NewServer(
		desk,
		newAuth(t),
		func(_, body string) {
			code = strings.ReplaceAll(strings.Fields(body)[1]+strings.Fields(body)[2], " ", "")
		},
	)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go srv.Poll(ctx)
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	post := func(path string, body any, out any) int {
		t.Helper()
		b, _ := json.Marshal(body)
		res, err := http.Post(ts.URL+path, "application/json", bytes.NewReader(b))
		if err != nil {
			t.Fatal(err)
		}
		defer res.Body.Close()
		json.NewDecoder(res.Body).Decode(out)
		return res.StatusCode
	}

	wsURL := "ws" + strings.TrimPrefix(ts.URL, "http") + "/v1/ws"
	if _, res, err := websocket.Dial(ctx, wsURL, nil); err == nil || res.StatusCode != http.StatusUnauthorized {
		t.Fatalf("unpaired dial: err=%v", err)
	}

	var start struct {
		PairingID string `json:"pairing_id"`
	}
	if st := post("/v1/pair/start", map[string]string{"device": "phone"}, &start); st != 200 {
		t.Fatalf("pair/start: %d", st)
	}
	var fin struct {
		Token string `json:"token"`
	}
	if st := post("/v1/pair/finish", map[string]string{"pairing_id": start.PairingID, "code": code}, &fin); st != 200 {
		t.Fatalf("pair/finish: %d (code %q)", st, code)
	}

	req, _ := http.NewRequest("GET", ts.URL+"/v1/info", nil)
	req.Header.Set("Origin", "http://evil.example")
	if res, _ := http.DefaultClient.Do(req); res.StatusCode != http.StatusForbidden {
		t.Errorf("browser origin: %d, want 403", res.StatusCode)
	}

	conn, _, err := websocket.Dial(ctx, wsURL, &websocket.DialOptions{
		HTTPHeader: http.Header{"Authorization": {"Bearer " + fin.Token}},
	})
	if err != nil {
		t.Fatal(err)
	}
	defer conn.CloseNow()

	read := func() map[string]any {
		t.Helper()
		rctx, rcancel := context.WithTimeout(ctx, 3*time.Second)
		defer rcancel()
		_, data, err := conn.Read(rctx)
		if err != nil {
			t.Fatal(err)
		}
		var m map[string]any
		json.Unmarshal(data, &m)
		return m
	}

	// A connection opens with the catalog, then state and system snapshots.
	seen := map[string]map[string]any{}
	for len(seen) < 3 {
		m := read()
		seen[m["type"].(string)] = m
	}
	if seen["state"]["state"].(map[string]any)["host"] != "VARCH" {
		t.Fatalf("state: %v", seen["state"])
	}
	if apps := seen["catalog"]["catalog"].(map[string]any)["apps"].([]any); len(apps) != 1 {
		t.Fatalf("catalog: %v", seen["catalog"])
	}
	if _, ok := seen["system"]["system"].(map[string]any)["sinks"]; !ok {
		t.Fatalf("system: %v", seen["system"])
	}

	// result waits for the reply to a request, skipping state pushes.
	result := func() map[string]any {
		t.Helper()
		for {
			if m := read(); m["type"] == "result" {
				return m
			}
		}
	}
	conn.Write(ctx, websocket.MessageText, []byte(`{"id":7,"action":"workspace.switch","value":4}`))
	if m := result(); m["id"] != float64(7) || m["ok"] != true {
		t.Fatalf("result: %v", m)
	}
	conn.Write(ctx, websocket.MessageText, []byte(`{"id":9,"action":"clipboard.get"}`))
	if m := result(); m["text"] != "from desktop" {
		t.Fatalf("clipboard result: %v", m)
	}
	// Pointer input is acknowledged only when it fails.
	conn.Write(ctx, websocket.MessageText, []byte(`{"action":"pointer.move","x":2,"y":1}`))
	conn.Write(ctx, websocket.MessageText, []byte(`{"id":8,"action":"rm -rf","value":0}`))
	if m := result(); m["id"] != float64(8) || m["ok"] != false || m["error"] == "" {
		t.Fatalf("bad action result: %v", m)
	}
	if !r.ran(`hyprctl dispatch hl.dsp.focus({ workspace = "4" })`) {
		t.Error("workspace switch never reached the runner")
	}

	// The one-shot HTTP form used by widgets and tiles.
	call := func(token string) int {
		req, _ := http.NewRequest("POST", ts.URL+"/v1/action", strings.NewReader(`{"action":"power.lock"}`))
		req.Header.Set("Authorization", "Bearer "+token)
		res, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		res.Body.Close()
		return res.StatusCode
	}
	if st := call(fin.Token); st != 200 || !r.ran("omarchy-system-lock") {
		t.Errorf("POST /v1/action: %d", st)
	}
	if st := call("wrong"); st != http.StatusUnauthorized {
		t.Errorf("POST /v1/action without pairing: %d", st)
	}
}

type sink struct {
	mu     sync.Mutex
	data   bytes.Buffer
	closed bool
}

func (k *sink) Write(p []byte) (int, error) {
	k.mu.Lock()
	defer k.mu.Unlock()
	return k.data.Write(p)
}

func (k *sink) Close() error {
	k.mu.Lock()
	defer k.mu.Unlock()
	k.closed = true
	return nil
}

func TestCasting(t *testing.T) {
	desk, _, _, _ := newDesktop(t)
	auth := newAuth(t)
	id, code, _ := auth.StartPairing("Pixel")
	token, _ := auth.FinishPairing(id, code)

	got := &sink{}
	gone := make(chan struct{})
	srv := NewServer(desk, auth, func(string, string) {})
	srv.viewer = func(_ context.Context, title string) (io.WriteCloser, <-chan struct{}, error) {
		if title != "Pixel screen" {
			t.Errorf("viewer title = %q", title)
		}
		return got, gone, nil
	}
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	dial := func(path, tok string) (*websocket.Conn, *http.Response, error) {
		return websocket.Dial(ctx, "ws"+strings.TrimPrefix(ts.URL, "http")+path, &websocket.DialOptions{
			HTTPHeader: http.Header{"Authorization": {"Bearer " + tok}},
		})
	}

	if _, res, err := dial("/v1/cast/phone", "wrong"); err == nil || res.StatusCode != http.StatusUnauthorized {
		t.Fatalf("unpaired cast: %v", err)
	}

	// Phone to desktop: bytes reach the viewer in order.
	phone, _, err := dial("/v1/cast/phone", token)
	if err != nil {
		t.Fatal(err)
	}
	if _, res, err := dial("/v1/cast/phone", token); err == nil || res.StatusCode != http.StatusConflict {
		t.Errorf("second cast should be refused: %v", err)
	}
	phone.Write(ctx, websocket.MessageBinary, []byte("frame-1 "))
	phone.Write(ctx, websocket.MessageBinary, []byte("frame-2"))
	for deadline := time.Now().Add(2 * time.Second); ; time.Sleep(5 * time.Millisecond) {
		got.mu.Lock()
		n := got.data.Len()
		got.mu.Unlock()
		if n == len("frame-1 frame-2") || time.Now().After(deadline) {
			break
		}
	}
	// Closing the viewer window ends the cast for the phone.
	close(gone)
	if _, _, err := phone.Read(ctx); err == nil {
		t.Error("the phone should be disconnected when the viewer closes")
	}
	deadline := time.Now().Add(2 * time.Second)
	for {
		got.mu.Lock()
		data, closed := got.data.String(), got.closed
		got.mu.Unlock()
		if data == "frame-1 frame-2" && closed {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("viewer got %q, closed=%v", data, closed)
		}
		time.Sleep(10 * time.Millisecond)
	}

	// Desktop to phone: frames keep coming until the phone leaves.
	view, _, err := dial("/v1/cast/screen", token)
	if err != nil {
		t.Fatal(err)
	}
	for range 2 {
		kind, _, err := view.Read(ctx)
		if err != nil || kind != websocket.MessageBinary {
			t.Fatalf("frame: kind=%v err=%v", kind, err)
		}
	}
	view.Close(websocket.StatusNormalClosure, "")
}
