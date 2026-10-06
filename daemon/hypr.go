package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"image"
	"sort"
)

type Window struct {
	Address    string `json:"address"`
	Class      string `json:"class"`
	Title      string `json:"title"`
	Workspace  int    `json:"workspace"`
	Floating   bool   `json:"floating"`
	Fullscreen bool   `json:"fullscreen"`
	Focused    bool   `json:"focused"`
}

type hyprClient struct {
	Address   string `json:"address"`
	Class     string `json:"class"`
	Title     string `json:"title"`
	Mapped    bool   `json:"mapped"`
	Hidden    bool   `json:"hidden"`
	Floating  bool   `json:"floating"`
	Workspace struct {
		ID int `json:"id"`
	} `json:"workspace"`
	Fullscreen     int `json:"fullscreen"`
	FocusHistoryID int `json:"focusHistoryID"`
}

func (d *Desktop) clients(ctx context.Context) ([]hyprClient, error) {
	out, err := d.run.Run(ctx, "hyprctl", "-j", "clients")
	if err != nil {
		return nil, err
	}
	var all []hyprClient
	if err := json.Unmarshal([]byte(out), &all); err != nil {
		return nil, err
	}
	return all, nil
}

// hyprland reports workspaces and the windows on them. Windows on special
// workspaces (scratchpads) are left out.
func (d *Desktop) hyprland(ctx context.Context) (WorkspaceState, []Window) {
	ws := WorkspaceState{Occupied: []int{}}
	windows := []Window{}
	if out, err := d.run.Run(ctx, "hyprctl", "-j", "activeworkspace"); err == nil {
		var active struct {
			ID int `json:"id"`
		}
		if json.Unmarshal([]byte(out), &active) == nil {
			ws.Active = active.ID
		}
	}
	all, err := d.clients(ctx)
	if err != nil {
		return ws, windows
	}
	occupied := map[int]bool{}
	for _, c := range all {
		id := c.Workspace.ID
		if !c.Mapped || c.Hidden || id < 1 || id > maxWorkspace {
			continue
		}
		occupied[id] = true
		windows = append(windows, Window{
			Address:    c.Address,
			Class:      c.Class,
			Title:      c.Title,
			Workspace:  id,
			Floating:   c.Floating,
			Fullscreen: c.Fullscreen > 0,
			Focused:    c.FocusHistoryID == 0,
		})
	}
	for id := range occupied {
		ws.Occupied = append(ws.Occupied, id)
	}
	sort.Ints(ws.Occupied)
	sort.SliceStable(windows, func(i, j int) bool {
		if windows[i].Workspace != windows[j].Workspace {
			return windows[i].Workspace < windows[j].Workspace
		}
		return windows[i].Address < windows[j].Address
	})
	return ws, windows
}

// window acts on one window by address. The address must belong to a window
// that exists right now: Hyprland applies some dispatchers to the focused
// window when it cannot resolve the one that was named.
func (d *Desktop) window(ctx context.Context, r Request) error {
	if !windowAddr.MatchString(r.Text) {
		return errors.New("invalid window address")
	}
	all, err := d.clients(ctx)
	if err != nil {
		return err
	}
	found := false
	for _, c := range all {
		found = found || c.Address == r.Text
	}
	if !found {
		return errors.New("that window no longer exists")
	}
	target := fmt.Sprintf(`window = "address:%s"`, r.Text)
	var call string
	switch r.Action {
	case "window.focus":
		call = fmt.Sprintf(`hl.dsp.focus({ %s })`, target)
	case "window.close":
		call = fmt.Sprintf(`hl.dsp.window.close({ %s })`, target)
	case "window.fullscreen":
		call = fmt.Sprintf(`hl.dsp.window.fullscreen({ %s, mode = "fullscreen" })`, target)
	case "window.float":
		call = fmt.Sprintf(`hl.dsp.window.float({ %s })`, target)
	case "window.move":
		n, err := workspace(r.Value)
		if err != nil {
			return err
		}
		call = fmt.Sprintf(`hl.dsp.window.move({ %s, workspace = "%d", follow = false })`, target, n)
	}
	_, err = d.run.Run(ctx, "hyprctl", "dispatch", call)
	return err
}

// Screenshot captures every output as a JPEG.
func (d *Desktop) Screenshot(ctx context.Context) ([]byte, error) {
	out, err := d.run.Run(ctx, "grim", "-t", "jpeg", "-q", "85", "-")
	return []byte(out), err
}

// liveWidth is the widest frame the live view sends. Wider screens are
// halved, which keeps the stream within what a phone link carries.
const liveWidth = 1600

// Frame captures the screen for the live view, with the pointer drawn so the
// phone can see where it is. It asks grim for raw pixels: skipping grim's own
// compression is faster than decoding it again to shrink the picture.
func (d *Desktop) Frame(ctx context.Context) (*image.RGBA, error) {
	out, err := d.run.Run(ctx, "grim", "-c", "-t", "ppm", "-")
	if err != nil {
		return nil, err
	}
	return shrinkPPM([]byte(out), liveWidth)
}

// shrinkPPM decodes a binary PPM (P6, 8 bits per channel) and averages 2x2
// blocks while the picture is wider than maxWidth.
func shrinkPPM(data []byte, maxWidth int) (*image.RGBA, error) {
	var magic string
	var w, h, depth, header int
	// Sscan, unlike Sscanf, accepts the header's mix of spaces and newlines.
	if _, err := fmt.Sscan(string(data[:min(len(data), 32)]), &magic, &w, &h, &depth); err != nil || magic != "P6" || depth != 255 || w <= 0 || h <= 0 {
		return nil, errors.New("unexpected screen capture format")
	}
	// The pixels start one whitespace byte after the third header field.
	for fields := 0; header < len(data) && fields < 4; header++ {
		if c := data[header]; c == ' ' || c == '\n' {
			fields++
		}
	}
	pix := data[header:]
	if len(pix) < w*h*3 {
		return nil, errors.New("truncated screen capture")
	}
	step := 1
	for w/step > maxWidth {
		step *= 2
	}
	ow, oh := w/step, h/step
	img := image.NewRGBA(image.Rect(0, 0, ow, oh))
	n := step * step
	for y := range oh {
		row := img.Pix[y*img.Stride:]
		for x := range ow {
			var r, g, b int
			for dy := range step {
				src := pix[((y*step+dy)*w+x*step)*3:]
				for dx := range step {
					r += int(src[dx*3])
					g += int(src[dx*3+1])
					b += int(src[dx*3+2])
				}
			}
			row[x*4], row[x*4+1], row[x*4+2], row[x*4+3] = byte(r/n), byte(g/n), byte(b/n), 255
		}
	}
	return img, nil
}
