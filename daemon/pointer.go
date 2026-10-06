package main

import (
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"math"
	"net"
	"os"
	"path/filepath"
	"sync"
	"time"
)

// PointerDevice moves and clicks the desktop's pointer.
type PointerDevice interface {
	Move(dx, dy float64) error
	Scroll(dx, dy float64) error
	Button(button int, pressed bool) error
}

// Linux input event codes for the buttons a remote can press.
var pointerButtons = [...]uint32{0x110, 0x111, 0x112} // left, right, middle

const virtualPointerManager = "zwlr_virtual_pointer_manager_v1"

// Wayland object ids this client allocates. wl_display is always 1.
const (
	wlDisplay  = 1
	wlRegistry = 2
	wlSync     = 3
	wlManager  = 4
	wlPointer  = 5
)

// Request opcodes of zwlr_virtual_pointer_v1.
const (
	opMotion     = 0
	opButton     = 2
	opAxis       = 3
	opFrame      = 4
	opAxisSource = 5
)

const axisSourceContinuous = 2

// WaylandPointer drives a virtual pointer through the compositor's
// wlr-virtual-pointer protocol, so it needs no root access or uinput.
// The connection is opened on first use and reopened if the compositor drops it.
type WaylandPointer struct {
	mu    sync.Mutex
	conn  net.Conn
	start time.Time
}

func (p *WaylandPointer) Move(dx, dy float64) error {
	return p.send(func(t uint32) []byte {
		return append(wlMessage(wlPointer, opMotion, t, wlFixed(dx), wlFixed(dy)), wlMessage(wlPointer, opFrame)...)
	})
}

func (p *WaylandPointer) Scroll(dx, dy float64) error {
	return p.send(func(t uint32) []byte {
		out := wlMessage(wlPointer, opAxisSource, axisSourceContinuous)
		if dy != 0 {
			out = append(out, wlMessage(wlPointer, opAxis, t, 0, wlFixed(dy))...)
		}
		if dx != 0 {
			out = append(out, wlMessage(wlPointer, opAxis, t, 1, wlFixed(dx))...)
		}
		return append(out, wlMessage(wlPointer, opFrame)...)
	})
}

func (p *WaylandPointer) Button(button int, pressed bool) error {
	if button < 0 || button >= len(pointerButtons) {
		return errors.New("unknown pointer button")
	}
	state := uint32(0)
	if pressed {
		state = 1
	}
	return p.send(func(t uint32) []byte {
		return append(wlMessage(wlPointer, opButton, t, pointerButtons[button], state), wlMessage(wlPointer, opFrame)...)
	})
}

func (p *WaylandPointer) send(build func(time uint32) []byte) error {
	p.mu.Lock()
	defer p.mu.Unlock()
	var err error
	// One retry covers a connection the compositor closed while we were idle.
	for range 2 {
		if p.conn == nil {
			if err = p.connect(); err != nil {
				return err
			}
		}
		if _, err = p.conn.Write(build(uint32(time.Since(p.start).Milliseconds()))); err == nil {
			return nil
		}
		p.conn.Close()
		p.conn = nil
	}
	return fmt.Errorf("pointer: %w", err)
}

func (p *WaylandPointer) connect() error {
	path := os.Getenv("WAYLAND_DISPLAY")
	if path == "" {
		path = "wayland-0"
	}
	if !filepath.IsAbs(path) {
		path = filepath.Join(os.Getenv("XDG_RUNTIME_DIR"), path)
	}
	conn, err := net.DialTimeout("unix", path, 2*time.Second)
	if err != nil {
		return fmt.Errorf("pointer: %w", err)
	}
	if err := wlHandshake(conn); err != nil {
		conn.Close()
		return fmt.Errorf("pointer: %w", err)
	}
	p.conn, p.start = conn, time.Now()
	// Events must be read or the compositor's send buffer eventually fills.
	go func() {
		io.Copy(io.Discard, conn)
		conn.Close()
	}()
	return nil
}

// wlHandshake finds the virtual pointer manager and creates one pointer.
func wlHandshake(conn net.Conn) error {
	conn.SetDeadline(time.Now().Add(2 * time.Second))
	defer conn.SetDeadline(time.Time{})

	hello := append(wlMessage(wlDisplay, 1, wlRegistry), wlMessage(wlDisplay, 0, wlSync)...)
	if _, err := conn.Write(hello); err != nil {
		return err
	}
	var global, version uint32
	for done := false; !done; {
		var head [8]byte
		if _, err := io.ReadFull(conn, head[:]); err != nil {
			return err
		}
		object := binary.LittleEndian.Uint32(head[0:])
		word := binary.LittleEndian.Uint32(head[4:])
		size, opcode := int(word>>16), word&0xffff
		if size < 8 {
			return errors.New("malformed message from compositor")
		}
		body := make([]byte, size-8)
		if _, err := io.ReadFull(conn, body); err != nil {
			return err
		}
		switch {
		case object == wlSync:
			done = true
		case object == wlDisplay && opcode == 0:
			return errors.New("compositor rejected the connection")
		case object == wlRegistry && opcode == 0 && len(body) >= 12:
			// wl_registry.global(name, interface, version)
			n := int(binary.LittleEndian.Uint32(body[4:]))
			end := 8 + (n+3)&^3
			if n > 0 && end+4 <= len(body) && string(body[8:8+n-1]) == virtualPointerManager {
				global = binary.LittleEndian.Uint32(body[0:])
				version = binary.LittleEndian.Uint32(body[end:])
			}
		}
	}
	if version == 0 {
		return errors.New("the compositor does not offer virtual pointers")
	}
	// wl_registry.bind(name, interface, version, new_id), then
	// create_virtual_pointer(seat = null, new_id).
	bind := wlMessage(wlRegistry, 0, global)
	bind = append(bind, wlString(virtualPointerManager)...)
	bind = binary.LittleEndian.AppendUint32(bind, 1)
	bind = binary.LittleEndian.AppendUint32(bind, wlManager)
	binary.LittleEndian.PutUint32(bind[4:], uint32(len(bind))<<16)
	_, err := conn.Write(append(bind, wlMessage(wlManager, 0, 0, wlPointer)...))
	return err
}

// wlMessage encodes a request whose arguments are all 32-bit words.
func wlMessage(object, opcode uint32, args ...uint32) []byte {
	out := make([]byte, 8, 8+4*len(args))
	binary.LittleEndian.PutUint32(out[0:], object)
	binary.LittleEndian.PutUint32(out[4:], uint32(8+4*len(args))<<16|opcode)
	for _, a := range args {
		out = binary.LittleEndian.AppendUint32(out, a)
	}
	return out
}

// wlString encodes a string argument: length including NUL, padded to 4 bytes.
func wlString(s string) []byte {
	out := binary.LittleEndian.AppendUint32(nil, uint32(len(s)+1))
	out = append(out, s...)
	return append(out, make([]byte, 4-len(s)%4)...)
}

// wlFixed converts to Wayland's signed 24.8 fixed-point format.
func wlFixed(v float64) uint32 {
	return uint32(int32(math.Round(v * 256)))
}
