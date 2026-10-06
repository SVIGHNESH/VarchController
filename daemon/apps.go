package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
)

// App is one entry of the launcher. Only the id and name leave the desktop.
type App struct {
	ID   string   `json:"id"`
	Name string   `json:"name"`
	Exec []string `json:"exec"`
}

var appID = regexp.MustCompile(`^[a-z0-9][a-z0-9_-]{0,31}$`)

// Apps is the launcher list. It lives in a file the desktop's owner edits, so
// a phone can only ever start something that was put there on purpose.
type Apps struct {
	path string
}

// NewApps writes a starter list on first run, made of whatever common
// launchers this machine actually has.
func NewApps(path string) *Apps {
	a := &Apps{path: path}
	if _, err := os.Stat(path); errors.Is(err, os.ErrNotExist) {
		var starter []App
		for _, c := range []App{
			{"browser", "Browser", []string{"omarchy-launch-browser"}},
			{"terminal", "Terminal", []string{"xdg-terminal-exec"}},
			{"files", "Files", []string{"nautilus", "--new-window"}},
			{"editor", "Editor", []string{"omarchy-launch-editor"}},
			{"spotify", "Spotify", []string{"spotify"}},
		} {
			if have(c.Exec[0]) {
				starter = append(starter, c)
			}
		}
		if data, err := json.MarshalIndent(starter, "", "  "); err == nil && os.MkdirAll(filepath.Dir(path), 0o700) == nil {
			os.WriteFile(path, append(data, '\n'), 0o600)
		}
	}
	return a
}

// List re-reads the file each time so edits apply without a restart.
func (a *Apps) List() ([]App, error) {
	data, err := os.ReadFile(a.path)
	if errors.Is(err, os.ErrNotExist) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	var apps []App
	if err := json.Unmarshal(data, &apps); err != nil {
		return nil, fmt.Errorf("%s: %w", a.path, err)
	}
	seen := map[string]bool{}
	for _, app := range apps {
		if !appID.MatchString(app.ID) || seen[app.ID] {
			return nil, fmt.Errorf("%s: app id %q is invalid or repeated (use lowercase letters, digits, - and _)", a.path, app.ID)
		}
		if app.Name == "" || len(app.Exec) == 0 || app.Exec[0] == "" {
			return nil, fmt.Errorf("%s: app %q needs a name and an exec list", a.path, app.ID)
		}
		seen[app.ID] = true
	}
	return apps, nil
}

func (a *Apps) Find(id string) (App, bool) {
	apps, _ := a.List()
	for _, app := range apps {
		if app.ID == id {
			return app, true
		}
	}
	return App{}, false
}
