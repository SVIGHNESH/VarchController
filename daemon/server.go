package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"log"
	"math"
	"net/http"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/coder/websocket"
)

const (
	version      = "0.3.0"
	pollInterval = time.Second
	systemEvery  = 5 // system state is polled every this many ticks
	writeTimeout = 5 * time.Second
	readLimit    = 256 << 10
	// A playing track's position is only re-sent when it strays this far
	// from where the phone would expect it to be by now.
	positionDrift = 2.0
)

type client struct {
	send chan []byte
}

type Server struct {
	desk   *Desktop
	auth   *Auth
	notify func(title, body string)
	viewer Viewer
	// Only one phone may cast at a time; a second would fight over the viewer.
	casting atomic.Bool

	mu         sync.Mutex
	clients    map[*client]struct{}
	lastState  []byte
	lastSystem []byte
	sentState  State
	sentAt     time.Time
	kick       chan struct{}
}

func NewServer(desk *Desktop, auth *Auth, notify func(title, body string)) *Server {
	return &Server{
		desk:    desk,
		auth:    auth,
		notify:  notify,
		viewer:  mpvViewer,
		clients: map[*client]struct{}{},
		kick:    make(chan struct{}, 1),
	}
}

func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /v1/info", s.handleInfo)
	mux.HandleFunc("POST /v1/pair/start", s.handlePairStart)
	mux.HandleFunc("POST /v1/pair/finish", s.handlePairFinish)
	mux.HandleFunc("POST /v1/unpair", s.paired(s.handleUnpair))
	mux.HandleFunc("POST /v1/action", s.paired(s.handleAction))
	mux.HandleFunc("GET /v1/status", s.paired(s.handleStatus))
	mux.HandleFunc("GET /v1/screenshot", s.paired(s.handleScreenshot))
	mux.HandleFunc("GET /v1/art", s.paired(s.handleArt))
	mux.HandleFunc("GET /v1/cast/screen", s.paired(s.handleCastScreen))
	mux.HandleFunc("GET /v1/cast/phone", s.paired(s.handleCastPhone))
	mux.HandleFunc("GET /v1/ws", s.paired(s.handleWS))
	return noBrowsers(mux)
}

// noBrowsers rejects anything carrying an Origin header, so a web page open
// on some machine in the network cannot drive the daemon.
func noBrowsers(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Origin") != "" {
			writeError(w, http.StatusForbidden, "browser requests are not allowed")
			return
		}
		next.ServeHTTP(w, r)
	})
}

func bearer(r *http.Request) string {
	token, _ := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
	return token
}

// paired only lets requests through that carry a paired device's token.
func (s *Server) paired(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if !s.auth.Check(bearer(r)) {
			writeError(w, http.StatusUnauthorized, "not paired")
			return
		}
		next(w, r)
	}
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(v)
}

func writeError(w http.ResponseWriter, status int, msg string) {
	writeJSON(w, status, map[string]string{"error": msg})
}

func readJSON(w http.ResponseWriter, r *http.Request, v any) bool {
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, readLimit)).Decode(v); err != nil {
		writeError(w, http.StatusBadRequest, "invalid JSON body")
		return false
	}
	return true
}

func (s *Server) handleInfo(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]string{"name": s.desk.host, "version": version})
}

func (s *Server) handlePairStart(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Device string `json:"device"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	name := strings.TrimSpace(req.Device)
	if name == "" || len(name) > 64 {
		writeError(w, http.StatusBadRequest, "device name is required (max 64 chars)")
		return
	}
	id, code, err := s.auth.StartPairing(name)
	if err != nil {
		writeError(w, http.StatusTooManyRequests, err.Error())
		return
	}
	log.Printf("pairing requested by %q from %s", name, r.RemoteAddr)
	s.notify("Varch Controller pairing", "Code "+code[:3]+" "+code[3:]+" for "+name)
	writeJSON(w, http.StatusOK, map[string]any{"pairing_id": id, "expires_in": int(pairingTTL.Seconds())})
}

func (s *Server) handlePairFinish(w http.ResponseWriter, r *http.Request) {
	var req struct {
		PairingID string `json:"pairing_id"`
		Code      string `json:"code"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	token, err := s.auth.FinishPairing(req.PairingID, req.Code)
	switch {
	case errors.Is(err, errPairingCode):
		writeError(w, http.StatusForbidden, err.Error())
	case errors.Is(err, errPairingInvalid):
		writeError(w, http.StatusGone, err.Error())
	case err != nil:
		log.Printf("pairing: %v", err)
		writeError(w, http.StatusInternalServerError, "could not save pairing")
	default:
		log.Printf("paired new device from %s", r.RemoteAddr)
		writeJSON(w, http.StatusOK, map[string]string{"token": token})
	}
}

func (s *Server) handleUnpair(w http.ResponseWriter, r *http.Request) {
	if err := s.auth.Revoke(bearer(r)); err != nil {
		log.Printf("unpair: %v", err)
		writeError(w, http.StatusInternalServerError, "could not remove pairing")
		return
	}
	log.Printf("device unpaired from %s", r.RemoteAddr)
	writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
}

type resultMsg struct {
	Type  string `json:"type"`
	ID    int64  `json:"id"`
	OK    bool   `json:"ok"`
	Error string `json:"error,omitempty"`
	Text  string `json:"text,omitempty"`
}

// perform runs one request and logs failures. Typed text and pointer input
// are never logged.
func (s *Server) perform(ctx context.Context, req Request) resultMsg {
	res := resultMsg{Type: "result", ID: req.ID, OK: true}
	text, err := s.desk.Do(ctx, req)
	if err != nil {
		res.OK, res.Error = false, err.Error()
		log.Printf("action %s: %v", req.Action, err)
	}
	res.Text = text
	if !req.Quiet() {
		s.refresh()
	}
	return res
}

// handleAction is the one-shot form of a WebSocket action, for callers such
// as widgets and quick-settings tiles that do not hold a connection open.
func (s *Server) handleAction(w http.ResponseWriter, r *http.Request) {
	var req Request
	if !readJSON(w, r, &req) {
		return
	}
	writeJSON(w, http.StatusOK, s.perform(r.Context(), req))
}

func (s *Server) handleStatus(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, s.desk.System(r.Context()))
}

func (s *Server) handleScreenshot(w http.ResponseWriter, r *http.Request) {
	img, err := s.desk.Screenshot(r.Context())
	if err != nil {
		log.Printf("screenshot: %v", err)
		writeError(w, http.StatusInternalServerError, "could not capture the screen")
		return
	}
	w.Header().Set("Content-Type", "image/jpeg")
	w.Write(img)
}

func (s *Server) handleArt(w http.ResponseWriter, r *http.Request) {
	img, err := s.desk.media.Art()
	if err != nil {
		writeError(w, http.StatusNotFound, "no cover art")
		return
	}
	w.Header().Set("Content-Type", http.DetectContentType(img))
	w.Write(img)
}

func (s *Server) handleWS(w http.ResponseWriter, r *http.Request) {
	conn, err := websocket.Accept(w, r, nil)
	if err != nil {
		return
	}
	defer conn.CloseNow()
	conn.SetReadLimit(readLimit)

	ctx, cancel := context.WithCancel(r.Context())
	defer cancel()
	c := &client{send: make(chan []byte, 32)}
	c.push(envelope("catalog", "catalog", s.desk.Catalog(ctx)))
	s.add(c)
	defer s.remove(c)

	go func() {
		defer cancel()
		for {
			select {
			case <-ctx.Done():
				return
			case msg := <-c.send:
				wctx, wcancel := context.WithTimeout(ctx, writeTimeout)
				err := conn.Write(wctx, websocket.MessageText, msg)
				wcancel()
				if err != nil {
					return
				}
			}
		}
	}()

	for {
		_, data, err := conn.Read(ctx)
		if err != nil {
			return
		}
		var req Request
		if err := json.Unmarshal(data, &req); err != nil {
			continue
		}
		res := s.perform(ctx, req)
		// Pointer and key input arrives in bursts; only failures are worth a reply.
		if req.Quiet() && res.OK {
			continue
		}
		out, _ := json.Marshal(res)
		if !c.push(out) {
			return
		}
	}
}

func envelope(kind, key string, v any) []byte {
	out, _ := json.Marshal(map[string]any{"type": kind, key: v})
	return out
}

// push queues a message, reporting false if the client is too far behind.
func (c *client) push(msg []byte) bool {
	select {
	case c.send <- msg:
		return true
	default:
		return false
	}
}

func (s *Server) add(c *client) {
	s.mu.Lock()
	s.clients[c] = struct{}{}
	if s.lastState != nil {
		c.push(s.lastState)
	}
	if s.lastSystem != nil {
		c.push(s.lastSystem)
	}
	s.mu.Unlock()
	s.refresh()
}

func (s *Server) remove(c *client) {
	s.mu.Lock()
	delete(s.clients, c)
	s.mu.Unlock()
}

// refresh asks the poller to take a snapshot now instead of at the next tick.
func (s *Server) refresh() {
	select {
	case s.kick <- struct{}{}:
	default:
	}
}

// Poll pushes desktop state to connected clients whenever it changes.
// It does no work while nobody is connected.
func (s *Server) Poll(ctx context.Context) {
	tick := time.NewTicker(pollInterval)
	defer tick.Stop()
	for n := 0; ; n++ {
		kicked := false
		select {
		case <-ctx.Done():
			return
		case <-tick.C:
		case <-s.kick:
			kicked = true
		}
		s.mu.Lock()
		idle := len(s.clients) == 0
		if idle {
			s.lastState, s.lastSystem = nil, nil
		}
		wantSystem := kicked || n%systemEvery == 0 || s.lastSystem == nil
		s.mu.Unlock()
		if idle {
			continue
		}

		state := s.desk.State(ctx)
		var system []byte
		if wantSystem {
			system = envelope("system", "system", s.desk.System(ctx))
		}

		s.mu.Lock()
		now := time.Now()
		if s.lastState == nil || stateChanged(s.sentState, state, now.Sub(s.sentAt).Seconds()) {
			s.lastState, s.sentState, s.sentAt = envelope("state", "state", state), state, now
			s.broadcast(s.lastState)
		}
		if system != nil && !bytes.Equal(system, s.lastSystem) {
			s.lastSystem = system
			s.broadcast(system)
		}
		s.mu.Unlock()
	}
}

func (s *Server) broadcast(msg []byte) {
	for c := range s.clients {
		c.push(msg)
	}
}

// stateChanged ignores a track position that simply advanced with the clock.
func stateChanged(sent, now State, elapsed float64) bool {
	expected := sent.Media.Position
	if sent.Media.Status == "Playing" {
		expected += elapsed
	}
	if math.Abs(now.Media.Position-expected) < positionDrift {
		now.Media.Position = sent.Media.Position
	}
	a, _ := json.Marshal(sent)
	b, _ := json.Marshal(now)
	return !bytes.Equal(a, b)
}
