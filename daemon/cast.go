package main

import (
	"bytes"
	"context"
	"fmt"
	"image/jpeg"
	"io"
	"log"
	"net/http"
	"os/exec"
	"time"

	"github.com/coder/websocket"
)

const (
	// Shortest gap between two frames of the desktop's screen.
	castInterval = 100 * time.Millisecond
	castQuality  = 55
	// Largest single message a casting phone may send; a keyframe fits easily.
	castReadLimit = 4 << 20
)

// Viewer shows a phone's screen on the desktop. It consumes an H.264
// elementary stream; done closes when the viewer goes away by itself.
type Viewer func(ctx context.Context, title string) (stream io.WriteCloser, done <-chan struct{}, err error)

// mpvViewer plays the stream in an mpv window, tuned to show frames as they
// arrive rather than buffering them.
func mpvViewer(ctx context.Context, title string) (io.WriteCloser, <-chan struct{}, error) {
	cmd := exec.CommandContext(ctx, "mpv",
		"--no-terminal",
		"--profile=low-latency",
		"--untimed",
		"--no-cache",
		"--demuxer-lavf-format=h264",
		"--force-window=immediate",
		"--wayland-app-id=varch-cast",
		"--title="+title,
		"-",
	)
	stdin, err := cmd.StdinPipe()
	if err != nil {
		return nil, nil, err
	}
	if err := cmd.Start(); err != nil {
		return nil, nil, err
	}
	done := make(chan struct{})
	go func() {
		cmd.Wait()
		close(done)
	}()
	return stdin, done, nil
}

// handleCastScreen streams the desktop's screen to a phone as JPEG frames.
// Each frame is captured only after the previous one has been written, so a
// slow link lowers the frame rate instead of building a backlog.
func (s *Server) handleCastScreen(w http.ResponseWriter, r *http.Request) {
	conn, err := websocket.Accept(w, r, nil)
	if err != nil {
		return
	}
	defer conn.CloseNow()
	ctx := conn.CloseRead(r.Context())
	log.Printf("screen view started by %s", r.RemoteAddr)
	defer log.Printf("screen view ended for %s", r.RemoteAddr)

	var last []byte
	var buf bytes.Buffer
	for ctx.Err() == nil {
		start := time.Now()
		frame, err := s.desk.Frame(ctx)
		if err != nil {
			if ctx.Err() == nil {
				log.Printf("screen view: %v", err)
				conn.Close(websocket.StatusInternalError, "could not capture the screen")
			}
			return
		}
		// An idle desktop costs no bandwidth: only changed frames are sent.
		if !bytes.Equal(frame.Pix, last) {
			last = frame.Pix
			buf.Reset()
			if err := jpeg.Encode(&buf, frame, &jpeg.Options{Quality: castQuality}); err != nil {
				return
			}
			wctx, cancel := context.WithTimeout(ctx, writeTimeout)
			err = conn.Write(wctx, websocket.MessageBinary, buf.Bytes())
			cancel()
			if err != nil {
				return
			}
		}
		if wait := castInterval - time.Since(start); wait > 0 {
			select {
			case <-ctx.Done():
			case <-time.After(wait):
			}
		}
	}
}

// handleCastPhone receives a phone's screen as H.264 and shows it in a
// viewer window. Closing that window ends the cast from the desktop side.
func (s *Server) handleCastPhone(w http.ResponseWriter, r *http.Request) {
	if !s.casting.CompareAndSwap(false, true) {
		writeError(w, http.StatusConflict, "another phone is already casting")
		return
	}
	defer s.casting.Store(false)

	device := s.auth.Name(bearer(r))
	ctx, cancel := context.WithCancel(r.Context())
	defer cancel()
	stream, gone, err := s.viewer(ctx, fmt.Sprintf("%s screen", device))
	if err != nil {
		log.Printf("phone cast: %v", err)
		writeError(w, http.StatusServiceUnavailable, "the desktop could not open a viewer (is mpv installed?)")
		return
	}
	defer stream.Close()

	conn, err := websocket.Accept(w, r, nil)
	if err != nil {
		return
	}
	defer conn.CloseNow()
	conn.SetReadLimit(castReadLimit)
	go func() {
		// The viewer window was closed: stop reading so the phone stops too.
		select {
		case <-gone:
			cancel()
		case <-ctx.Done():
		}
	}()

	log.Printf("%s started casting its screen", device)
	s.notify("Varch Controller", device+" is casting its screen")
	defer log.Printf("%s stopped casting", device)
	for {
		kind, data, err := conn.Read(ctx)
		if err != nil {
			break
		}
		if kind != websocket.MessageBinary {
			continue
		}
		if _, err := stream.Write(data); err != nil {
			break
		}
	}
	conn.Close(websocket.StatusNormalClosure, "")
}
