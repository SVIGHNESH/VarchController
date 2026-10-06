package main

import (
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"math/big"
	"os"
	"path/filepath"
	"sync"
	"time"
)

const (
	pairingTTL      = 2 * time.Minute
	pairingAttempts = 5
	pairingCooldown = 5 * time.Second
)

var (
	errPairingBusy    = errors.New("another pairing was just started, try again in a few seconds")
	errPairingInvalid = errors.New("pairing expired or not found, start again")
	errPairingCode    = errors.New("wrong code")
)

type device struct {
	Name      string    `json:"name"`
	TokenHash string    `json:"token_hash"`
	Paired    time.Time `json:"paired"`
}

type pairing struct {
	id       string
	code     string
	device   string
	expires  time.Time
	attempts int
}

// Auth owns paired-device tokens and the single in-flight pairing.
// Only token hashes are written to disk.
type Auth struct {
	path string
	now  func() time.Time

	mu      sync.Mutex
	devices []device
	pending *pairing
	started time.Time
}

func NewAuth(path string) (*Auth, error) {
	a := &Auth{path: path, now: time.Now}
	data, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return a, nil
	}
	if err != nil {
		return nil, err
	}
	if err := json.Unmarshal(data, &a.devices); err != nil {
		return nil, fmt.Errorf("%s: %w", path, err)
	}
	return a, nil
}

func hashToken(token string) string {
	sum := sha256.Sum256([]byte(token))
	return hex.EncodeToString(sum[:])
}

func randomString(n int) string {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	return base64.RawURLEncoding.EncodeToString(b)
}

func randomCode() string {
	n, err := rand.Int(rand.Reader, big.NewInt(1_000_000))
	if err != nil {
		panic(err)
	}
	return fmt.Sprintf("%06d", n.Int64())
}

// Check reports whether token belongs to a paired device.
func (a *Auth) Check(token string) bool {
	if token == "" {
		return false
	}
	h := []byte(hashToken(token))
	a.mu.Lock()
	defer a.mu.Unlock()
	ok := false
	for _, d := range a.devices {
		if subtle.ConstantTimeCompare(h, []byte(d.TokenHash)) == 1 {
			ok = true
		}
	}
	return ok
}

// StartPairing begins a pairing and returns its id and the code that must be
// shown to the person sitting at the laptop.
func (a *Auth) StartPairing(deviceName string) (id, code string, err error) {
	a.mu.Lock()
	defer a.mu.Unlock()
	now := a.now()
	if now.Sub(a.started) < pairingCooldown {
		return "", "", errPairingBusy
	}
	a.started = now
	a.pending = &pairing{id: randomString(16), code: randomCode(), device: deviceName, expires: now.Add(pairingTTL)}
	return a.pending.id, a.pending.code, nil
}

// FinishPairing exchanges a correct code for a new device token.
func (a *Auth) FinishPairing(id, code string) (string, error) {
	a.mu.Lock()
	defer a.mu.Unlock()
	p := a.pending
	if p == nil || a.now().After(p.expires) || subtle.ConstantTimeCompare([]byte(id), []byte(p.id)) != 1 {
		return "", errPairingInvalid
	}
	if subtle.ConstantTimeCompare([]byte(code), []byte(p.code)) != 1 {
		p.attempts++
		if p.attempts >= pairingAttempts {
			a.pending = nil
			return "", errPairingInvalid
		}
		return "", errPairingCode
	}
	a.pending = nil
	token := randomString(32)
	devices := append(a.devices, device{Name: p.device, TokenHash: hashToken(token), Paired: a.now().UTC()})
	if err := a.save(devices); err != nil {
		return "", err
	}
	a.devices = devices
	return token, nil
}

// Revoke forgets the device that owns token.
func (a *Auth) Revoke(token string) error {
	h := hashToken(token)
	a.mu.Lock()
	defer a.mu.Unlock()
	kept := make([]device, 0, len(a.devices))
	for _, d := range a.devices {
		if d.TokenHash != h {
			kept = append(kept, d)
		}
	}
	if err := a.save(kept); err != nil {
		return err
	}
	a.devices = kept
	return nil
}

func (a *Auth) save(devices []device) error {
	data, err := json.MarshalIndent(devices, "", "  ")
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(a.path), 0o700); err != nil {
		return err
	}
	tmp := a.path + ".tmp"
	if err := os.WriteFile(tmp, data, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, a.path)
}
