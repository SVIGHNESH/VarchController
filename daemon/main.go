// varchd lets a paired phone control this desktop over the local network.
package main

import (
	"context"
	"errors"
	"flag"
	"log"
	"net"
	"net/http"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"strconv"
	"syscall"
	"time"
)

const serviceType = "_varch._tcp"

func main() {
	listen := flag.String("listen", ":7421", "address to listen on")
	flag.Parse()
	log.SetFlags(0)

	host, err := os.Hostname()
	if err != nil {
		host = "desktop"
	}
	cfg, err := os.UserConfigDir()
	if err != nil {
		log.Fatal(err)
	}
	auth, err := NewAuth(filepath.Join(cfg, "varchd", "devices.json"))
	if err != nil {
		log.Fatal(err)
	}

	var media Media = noMedia{}
	if m, err := NewMPRIS(); err != nil {
		log.Printf("media control disabled: %v", err)
	} else {
		media = m
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	apps := NewApps(filepath.Join(cfg, "varchd", "apps.json"))
	if _, err := apps.List(); err != nil {
		log.Printf("app launcher disabled: %v", err)
	}
	desk := NewDesktop(host, execRunner{}, media, &WaylandPointer{}, apps)
	srv := NewServer(desk, auth, notify)
	go srv.Poll(ctx)

	ln, err := net.Listen("tcp", *listen)
	if err != nil {
		log.Fatal(err)
	}
	port := ln.Addr().(*net.TCPAddr).Port
	go advertise(ctx, host, port)

	hs := &http.Server{Handler: srv.Handler(), ReadHeaderTimeout: 5 * time.Second}
	go func() {
		<-ctx.Done()
		sctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		defer cancel()
		hs.Shutdown(sctx)
	}()
	log.Printf("varchd %s on %s, listening on %s", version, host, ln.Addr())
	if err := hs.Serve(ln); err != nil && !errors.Is(err, http.ErrServerClosed) {
		log.Fatal(err)
	}
}

// notify shows the pairing code on the desktop and in the log.
func notify(title, body string) {
	log.Printf("%s: %s", title, body)
	if err := exec.Command("notify-send", "-a", "varchd", "-u", "critical", title, body).Run(); err != nil {
		log.Printf("notify-send: %v", err)
	}
}

// advertise publishes the service over mDNS through avahi for as long as ctx lives.
func advertise(ctx context.Context, host string, port int) {
	cmd := exec.CommandContext(ctx, "avahi-publish", "-s", host, serviceType, strconv.Itoa(port))
	if err := cmd.Run(); err != nil && ctx.Err() == nil {
		log.Printf("mDNS advertising unavailable, phones must enter the address manually: %v", err)
	}
}
