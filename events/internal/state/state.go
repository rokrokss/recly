// Package state keeps everything recly-events must remember across restarts in one JSON file:
// the Drive change cursor, the event subscriptions, the event inbox and the delivery queue.
// One owner, tens of rows: a file written atomically is enough, and it can be read by
// `recly-events status` while `serve` runs.
package state

import (
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"sync"
	"time"
)

// State is the whole persisted document.
type State struct {
	Drive         Drive                    `json:"drive"`
	Subscriptions map[string]*Subscription `json:"subscriptions"`
	Inbox         []*InboxEvent            `json:"inbox"`
	Outbox        []*OutboxItem            `json:"outbox"`
	LastDelivery  *DeliveryResult          `json:"lastDelivery,omitempty"`
}

// Drive is the change-feed position and what has already been announced.
type Drive struct {
	// PageToken is the Drive changes cursor. Empty until the first poll, which records the
	// current position without announcing older transcripts.
	PageToken     string    `json:"pageToken,omitempty"`
	LastPollAt    time.Time `json:"lastPollAt,omitzero"`
	LastSuccessAt time.Time `json:"lastSuccessAt,omitzero"`
	LastError     string    `json:"lastError,omitempty"`
	// Seen maps a transcript file ID to the content version already announced, so a change
	// that touches only metadata does not announce the same transcript twice.
	Seen map[string]string `json:"seen"`
}

// Subscription is one events/subscribe from ChatGPT.
type Subscription struct {
	ID             string          `json:"id"`
	Name           string          `json:"name"`
	URL            string          `json:"url"`
	Secret         string          `json:"secret"`
	PreviousSecret string          `json:"previousSecret,omitempty"`
	RotationUntil  time.Time       `json:"rotationUntil,omitzero"`
	Arguments      json.RawMessage `json:"arguments,omitempty"`
	CreatedAt      time.Time       `json:"createdAt"`
	RefreshedAt    time.Time       `json:"refreshedAt"`
	// ExpiresAt is nil for a subscription granted without expiry.
	ExpiresAt *time.Time `json:"expiresAt,omitempty"`
}

// Active reports whether the subscription should still receive events at now.
func (s *Subscription) Active(now time.Time) bool {
	return s.ExpiresAt == nil || now.Before(*s.ExpiresAt)
}

// InboxEvent is an event as the agent reads it back with get_pending_events.
type InboxEvent struct {
	EventID   string          `json:"eventId"`
	Name      string          `json:"name"`
	Timestamp time.Time       `json:"timestamp"`
	Data      json.RawMessage `json:"data"`
	AckedAt   *time.Time      `json:"ackedAt,omitempty"`
}

// OutboxItem is one event still to be delivered to one subscription.
type OutboxItem struct {
	EventID        string    `json:"eventId"`
	SubscriptionID string    `json:"subscriptionId"`
	Attempts       int       `json:"attempts"`
	FirstAt        time.Time `json:"firstAt"`
	NextAt         time.Time `json:"nextAt"`
}

// DeliveryResult is the outcome of the most recent delivery attempt, for status.
type DeliveryResult struct {
	At             time.Time `json:"at"`
	EventID        string    `json:"eventId"`
	SubscriptionID string    `json:"subscriptionId"`
	Status         int       `json:"status"`
	Error          string    `json:"error,omitempty"`
}

// Store guards the State and writes it to disk after every change.
type Store struct {
	path string
	mu   sync.Mutex
	st   *State
}

// Open loads the state file, or starts empty when it does not exist yet.
func Open(path string) (*Store, error) {
	st, err := Read(path)
	if err != nil {
		return nil, err
	}
	return &Store{path: path, st: st}, nil
}

// Read loads a state file without taking ownership of it (for status).
func Read(path string) (*State, error) {
	st := &State{}
	b, err := os.ReadFile(path)
	switch {
	case errors.Is(err, fs.ErrNotExist):
	case err != nil:
		return nil, err
	default:
		if err := json.Unmarshal(b, st); err != nil {
			return nil, fmt.Errorf("read %s: %w", path, err)
		}
	}
	if st.Subscriptions == nil {
		st.Subscriptions = map[string]*Subscription{}
	}
	if st.Drive.Seen == nil {
		st.Drive.Seen = map[string]string{}
	}
	return st, nil
}

// View runs fn with the state locked. fn must not keep references past its return.
func (s *Store) View(fn func(*State)) {
	s.mu.Lock()
	defer s.mu.Unlock()
	fn(s.st)
}

// Update runs fn with the state locked and saves the result when fn returns nil.
// Never call the network from fn: the lock would be held across it.
func (s *Store) Update(fn func(*State) error) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if err := fn(s.st); err != nil {
		return err
	}
	return s.save()
}

func (s *Store) save() error {
	b, err := json.MarshalIndent(s.st, "", "  ")
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(s.path), 0o700); err != nil {
		return err
	}
	tmp := s.path + ".tmp"
	if err := os.WriteFile(tmp, b, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, s.path)
}
