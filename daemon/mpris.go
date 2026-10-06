package main

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"net/http"
	"net/url"
	"os"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/godbus/dbus/v5"
)

const (
	mprisPrefix = "org.mpris.MediaPlayer2."
	mprisPath   = "/org/mpris/MediaPlayer2"
	mprisRoot   = "org.mpris.MediaPlayer2"
	mprisPlayer = "org.mpris.MediaPlayer2.Player"
	maxArtBytes = 8 << 20
)

type PlayerRef struct {
	ID   string `json:"id"`
	Name string `json:"name"`
}

type MediaState struct {
	Available bool        `json:"available"`
	Player    string      `json:"player"`
	Name      string      `json:"name"`
	Status    string      `json:"status"`
	Title     string      `json:"title"`
	Artist    string      `json:"artist"`
	Position  float64     `json:"position"`
	Length    float64     `json:"length"`
	CanSeek   bool        `json:"can_seek"`
	Art       string      `json:"art"`
	Players   []PlayerRef `json:"players"`
}

// Media controls MPRIS players. Positions are in seconds.
type Media interface {
	State() MediaState
	Do(method string) error
	Seek(position float64) error
	Select(player string) error
	Art() ([]byte, error)
}

// MPRIS talks to media players over the session bus.
type MPRIS struct {
	conn *dbus.Conn

	mu        sync.Mutex
	preferred string // player id the phone picked, if it is still running
	artURL    string
	art       []byte
}

func NewMPRIS() (*MPRIS, error) {
	conn, err := dbus.ConnectSessionBus()
	if err != nil {
		return nil, err
	}
	return &MPRIS{conn: conn}, nil
}

func (m *MPRIS) players() []string {
	var names []string
	if err := m.conn.BusObject().Call("org.freedesktop.DBus.ListNames", 0).Store(&names); err != nil {
		return nil
	}
	var players []string
	for _, n := range names {
		if strings.HasPrefix(n, mprisPrefix) {
			players = append(players, strings.TrimPrefix(n, mprisPrefix))
		}
	}
	sort.Strings(players)
	return players
}

func (m *MPRIS) object(id string) dbus.BusObject {
	return m.conn.Object(mprisPrefix+id, mprisPath)
}

func (m *MPRIS) property(id, name string) any {
	v, err := m.object(id).GetProperty(name)
	if err != nil {
		return nil
	}
	return v.Value()
}

// pick returns the player to act on: the one the phone chose, else one that
// is playing, else the first by name so the choice is stable between calls.
func (m *MPRIS) pick(players []string) string {
	m.mu.Lock()
	preferred := m.preferred
	m.mu.Unlock()
	first := ""
	for _, p := range players {
		if p == preferred {
			return p
		}
	}
	for _, p := range players {
		if status, _ := m.property(p, mprisPlayer+".PlaybackStatus").(string); status == "Playing" {
			return p
		}
		if first == "" {
			first = p
		}
	}
	return first
}

func (m *MPRIS) State() MediaState {
	players := m.players()
	s := MediaState{Players: []PlayerRef{}}
	for _, p := range players {
		name, _ := m.property(p, mprisRoot+".Identity").(string)
		if name == "" {
			name, _, _ = strings.Cut(p, ".")
		}
		s.Players = append(s.Players, PlayerRef{p, name})
	}
	id := m.pick(players)
	if id == "" {
		return s
	}
	s.Available, s.Player = true, id
	for _, p := range s.Players {
		if p.ID == id {
			s.Name = p.Name
		}
	}
	s.Status, _ = m.property(id, mprisPlayer+".PlaybackStatus").(string)
	s.CanSeek, _ = m.property(id, mprisPlayer+".CanSeek").(bool)
	if pos, ok := m.property(id, mprisPlayer+".Position").(int64); ok {
		s.Position = float64(pos) / 1e6
	}
	meta, _ := m.property(id, mprisPlayer+".Metadata").(map[string]dbus.Variant)
	s.Title, _ = meta["xesam:title"].Value().(string)
	if artists, ok := meta["xesam:artist"].Value().([]string); ok {
		s.Artist = strings.Join(artists, ", ")
	}
	switch length := meta["mpris:length"].Value().(type) {
	case int64:
		s.Length = float64(length) / 1e6
	case uint64:
		s.Length = float64(length) / 1e6
	}
	artURL, _ := meta["mpris:artUrl"].Value().(string)
	m.mu.Lock()
	if artURL != m.artURL {
		m.artURL, m.art = artURL, nil
	}
	m.mu.Unlock()
	if artURL != "" {
		sum := sha256.Sum256([]byte(artURL))
		s.Art = hex.EncodeToString(sum[:6])
	}
	return s
}

func (m *MPRIS) current() (string, error) {
	id := m.pick(m.players())
	if id == "" {
		return "", errors.New("no media player is running")
	}
	return id, nil
}

func (m *MPRIS) Do(method string) error {
	id, err := m.current()
	if err != nil {
		return err
	}
	return m.object(id).Call(mprisPlayer+"."+method, 0).Err
}

func (m *MPRIS) Seek(position float64) error {
	id, err := m.current()
	if err != nil {
		return err
	}
	meta, _ := m.property(id, mprisPlayer+".Metadata").(map[string]dbus.Variant)
	track, ok := meta["mpris:trackid"].Value().(dbus.ObjectPath)
	if !ok {
		return errors.New("this player cannot seek")
	}
	return m.object(id).Call(mprisPlayer+".SetPosition", 0, track, int64(position*1e6)).Err
}

func (m *MPRIS) Select(player string) error {
	for _, p := range m.players() {
		if p == player {
			m.mu.Lock()
			m.preferred = player
			m.mu.Unlock()
			return nil
		}
	}
	return errors.New("that player is no longer running")
}

// Art returns the current track's cover image. Players publish it either as
// a local file or as a web address.
func (m *MPRIS) Art() ([]byte, error) {
	m.State() // picks up the current track's art address
	m.mu.Lock()
	artURL, cached := m.artURL, m.art
	m.mu.Unlock()
	if cached != nil {
		return cached, nil
	}
	u, err := url.Parse(artURL)
	if err != nil || artURL == "" {
		return nil, errors.New("no cover art")
	}
	var data []byte
	switch u.Scheme {
	case "file":
		f, err := os.Open(u.Path)
		if err != nil {
			return nil, err
		}
		defer f.Close()
		data, err = io.ReadAll(io.LimitReader(f, maxArtBytes))
		if err != nil {
			return nil, err
		}
	case "http", "https":
		client := http.Client{Timeout: 5 * time.Second}
		res, err := client.Get(artURL)
		if err != nil {
			return nil, err
		}
		defer res.Body.Close()
		if res.StatusCode != http.StatusOK {
			return nil, errors.New("cover art is not reachable")
		}
		data, err = io.ReadAll(io.LimitReader(res.Body, maxArtBytes))
		if err != nil {
			return nil, err
		}
	default:
		return nil, errors.New("no cover art")
	}
	if !strings.HasPrefix(http.DetectContentType(data), "image/") {
		return nil, errors.New("cover art is not an image")
	}
	m.mu.Lock()
	if m.artURL == artURL {
		m.art = data
	}
	m.mu.Unlock()
	return data, nil
}

// noMedia is used when the session bus is unreachable.
type noMedia struct{}

var errNoMedia = errors.New("media control unavailable")

func (noMedia) State() MediaState    { return MediaState{Players: []PlayerRef{}} }
func (noMedia) Do(string) error      { return errNoMedia }
func (noMedia) Seek(float64) error   { return errNoMedia }
func (noMedia) Select(string) error  { return errNoMedia }
func (noMedia) Art() ([]byte, error) { return nil, errNoMedia }
