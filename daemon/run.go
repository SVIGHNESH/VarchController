package main

import (
	"context"
	"errors"
	"fmt"
	"os/exec"
	"strings"
	"syscall"
	"time"
)

// Runner is how the daemon touches the rest of the system. Arguments are
// always passed as an argv, never through a shell.
type Runner interface {
	// Run executes a command and returns its stdout.
	Run(ctx context.Context, name string, args ...string) (string, error)
	// Feed executes a command with stdin and waits for it, discarding output.
	Feed(ctx context.Context, stdin, name string, args ...string) error
	// Spawn starts a long-lived command detached from the daemon.
	Spawn(name string, args ...string) error
}

type execRunner struct{}

const runTimeout = 4 * time.Second

func describe(name string, err error) error {
	var ee *exec.ExitError
	if errors.As(err, &ee) && len(ee.Stderr) > 0 {
		return fmt.Errorf("%s: %s", name, strings.TrimSpace(string(ee.Stderr)))
	}
	return fmt.Errorf("%s: %w", name, err)
}

func (execRunner) Run(ctx context.Context, name string, args ...string) (string, error) {
	ctx, cancel := context.WithTimeout(ctx, runTimeout)
	defer cancel()
	out, err := exec.CommandContext(ctx, name, args...).Output()
	if err != nil {
		return "", describe(name, err)
	}
	return string(out), nil
}

// Feed leaves stdout and stderr unattached: tools such as wl-copy fork a
// child that keeps them open, which would otherwise block until it exits.
func (execRunner) Feed(ctx context.Context, stdin, name string, args ...string) error {
	ctx, cancel := context.WithTimeout(ctx, runTimeout)
	defer cancel()
	cmd := exec.CommandContext(ctx, name, args...)
	cmd.Stdin = strings.NewReader(stdin)
	if err := cmd.Run(); err != nil {
		return describe(name, err)
	}
	return nil
}

func (execRunner) Spawn(name string, args ...string) error {
	cmd := exec.Command(name, args...)
	cmd.SysProcAttr = &syscall.SysProcAttr{Setsid: true}
	if err := cmd.Start(); err != nil {
		return describe(name, err)
	}
	go cmd.Wait()
	return nil
}

func have(name string) bool {
	_, err := exec.LookPath(name)
	return err == nil
}
