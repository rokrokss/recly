package webhook

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"slices"
	"strconv"
	"sync"
	"time"

	"github.com/rokrokss/recly/events/internal/state"
)

// EventName is the one event recly-events publishes.
const EventName = "recording.transcribed"

const (
	inboxAckedKeep   = 7 * 24 * time.Hour
	inboxPendingKeep = 30 * 24 * time.Hour
	giveUpAfter      = 24 * time.Hour
	maxBackoff       = time.Hour
	verifiedFor      = 5 * time.Minute
	rotationOverlap  = 5 * time.Minute
)

// SubscribeError is a refused events/subscribe, mapped to a JSON-RPC error by the MCP layer.
type SubscribeError struct {
	Code    int64
	Reason  string
	Message string
}

func (e *SubscribeError) Error() string { return e.Message }

// SubscribeParams is the events/subscribe request.
type SubscribeParams struct {
	Name      string          `json:"name"`
	Arguments json.RawMessage `json:"arguments"`
	Delivery  struct {
		Mode   string `json:"mode"`
		URL    string `json:"url"`
		Secret string `json:"secret"`
	} `json:"delivery"`
	Cursor *string `json:"cursor"`
	TTLMs  *int64  `json:"ttlMs"`
}

// SubscribeResult is the events/subscribe result.
type SubscribeResult struct {
	ID            string  `json:"id"`
	RefreshBefore *string `json:"refreshBefore"`
	Cursor        *string `json:"cursor"`
	Truncated     bool    `json:"truncated"`
}

// Hub owns subscriptions, the inbox and the delivery queue.
type Hub struct {
	Store *state.Store
	Guard *Guard
	Log   *slog.Logger
	Now   func() time.Time

	wake     chan struct{}
	mu       sync.Mutex
	verified map[string]time.Time
}

func (h *Hub) now() time.Time {
	if h.Now != nil {
		return h.Now()
	}
	return time.Now()
}

func (h *Hub) wakeUp() {
	h.mu.Lock()
	if h.wake == nil {
		h.wake = make(chan struct{}, 1)
	}
	ch := h.wake
	h.mu.Unlock()
	select {
	case ch <- struct{}{}:
	default:
	}
}

// Subscribe validates the request, verifies the callback with a signed challenge and stores
// the subscription. A subscription is granted without expiry unless ChatGPT asks for a TTL:
// it ends on events/unsubscribe or when the callback answers 410.
func (h *Hub) Subscribe(ctx context.Context, p SubscribeParams) (*SubscribeResult, error) {
	if p.Name != EventName {
		return nil, &SubscribeError{Code: -32602, Message: "unknown event " + p.Name}
	}
	if len(p.Arguments) > 0 && !bytes.Equal(bytes.TrimSpace(p.Arguments), []byte("{}")) && !bytes.Equal(bytes.TrimSpace(p.Arguments), []byte("null")) {
		return nil, &SubscribeError{Code: -32602, Message: "this event takes no arguments"}
	}
	if p.Delivery.Mode != "webhook" {
		return nil, &SubscribeError{Code: -32602, Message: "webhook delivery required"}
	}
	if p.Cursor != nil {
		return nil, &SubscribeError{Code: -32602, Message: "this event does not support a cursor"}
	}
	if _, err := signingKey(p.Delivery.Secret); err != nil {
		return nil, &SubscribeError{Code: -32602, Message: "invalid signing secret: " + err.Error()}
	}
	if _, err := h.Guard.Check(p.Delivery.URL); err != nil {
		h.Log.Warn("subscribe.refused", "reason", "callback", "error", err.Error())
		return nil, &SubscribeError{Code: -32015, Reason: "invalid_url", Message: "callback URL not allowed: " + err.Error()}
	}

	id := subscriptionID(p.Delivery.URL)
	now := h.now()
	var existing *state.Subscription
	h.Store.View(func(s *state.State) {
		if sub, ok := s.Subscriptions[id]; ok {
			c := *sub
			existing = &c
		}
	})
	cacheKey := id + "\x00" + p.Delivery.Secret
	stillValid := existing != nil && existing.Secret == p.Delivery.Secret && existing.Active(now)
	if !stillValid && !h.recentlyVerified(cacheKey, now) {
		if err := h.verify(ctx, id, p.Delivery.URL, p.Delivery.Secret); err != nil {
			h.Log.Warn("subscribe.verification.failed", "subscription", id, "error", err.Error())
			return nil, &SubscribeError{Code: -32015, Reason: "challenge_failed", Message: "callback challenge failed"}
		}
		h.markVerified(cacheKey, now)
	}

	sub := &state.Subscription{
		ID: id, Name: p.Name, URL: p.Delivery.URL, Secret: p.Delivery.Secret,
		CreatedAt: now, RefreshedAt: now,
	}
	if existing != nil {
		sub.CreatedAt = existing.CreatedAt
		if existing.Secret != p.Delivery.Secret {
			sub.PreviousSecret = existing.Secret
			sub.RotationUntil = now.Add(rotationOverlap)
		} else if existing.PreviousSecret != "" && now.Before(existing.RotationUntil) {
			sub.PreviousSecret, sub.RotationUntil = existing.PreviousSecret, existing.RotationUntil
		}
	}
	var refreshBefore *string
	if p.TTLMs != nil && *p.TTLMs > 0 {
		exp := now.Add(time.Duration(*p.TTLMs) * time.Millisecond)
		sub.ExpiresAt = &exp
		s := exp.UTC().Format(time.RFC3339Nano)
		refreshBefore = &s
	}
	if err := h.Store.Update(func(s *state.State) error {
		s.Subscriptions[id] = sub
		return nil
	}); err != nil {
		return nil, err
	}
	h.Log.Info("subscribe.ok", "subscription", id, "refresh", existing != nil, "expires", refreshBefore != nil)
	return &SubscribeResult{ID: id, RefreshBefore: refreshBefore}, nil
}

// Unsubscribe removes the subscription for the given callback URL, if any.
func (h *Hub) Unsubscribe(p SubscribeParams) error {
	if p.Name != EventName {
		return &SubscribeError{Code: -32602, Message: "unknown event " + p.Name}
	}
	id := subscriptionID(p.Delivery.URL)
	return h.Store.Update(func(s *state.State) error {
		if _, ok := s.Subscriptions[id]; ok {
			delete(s.Subscriptions, id)
			s.Outbox = slices.DeleteFunc(s.Outbox, func(o *state.OutboxItem) bool { return o.SubscriptionID == id })
			h.Log.Info("unsubscribe.ok", "subscription", id)
		}
		return nil
	})
}

// subscriptionID is stable per callback URL: one owner, one event, no arguments.
func subscriptionID(url string) string {
	sum := sha256.Sum256([]byte(EventName + "\x00" + url))
	return "sub_" + hex.EncodeToString(sum[:16])
}

func (h *Hub) recentlyVerified(key string, now time.Time) bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	return now.Before(h.verified[key])
}

func (h *Hub) markVerified(key string, now time.Time) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.verified == nil {
		h.verified = map[string]time.Time{}
	}
	h.verified[key] = now.Add(verifiedFor)
}

// verify posts a signed challenge that the callback must echo. Like every delivery it names
// the subscription in X-MCP-Subscription-Id; OpenAI's callback answers 400 without it.
func (h *Hub) verify(ctx context.Context, subID, url, secret string) error {
	buf := make([]byte, 32)
	if _, err := rand.Read(buf); err != nil {
		return err
	}
	challenge := base64.RawURLEncoding.EncodeToString(buf)
	body, _ := json.Marshal(map[string]string{"type": "verification", "challenge": challenge})
	status, respBody, _, err := h.post(ctx, url, []string{secret}, "msg_verification_"+challenge[:16], subID, body)
	if err != nil {
		return err
	}
	if status < 200 || status > 299 {
		return fmt.Errorf("challenge answered HTTP %d", status)
	}
	var echoed struct {
		Challenge string `json:"challenge"`
	}
	if json.Unmarshal(respBody, &echoed) != nil || subtle.ConstantTimeCompare([]byte(echoed.Challenge), []byte(challenge)) != 1 {
		return errors.New("challenge not echoed")
	}
	return nil
}

// Emit stores an event in the inbox and queues it for every active subscription. An event ID
// already in the inbox is ignored, so announcing the same transcript twice is harmless.
func (h *Hub) Emit(eventID string, data any) (bool, error) {
	raw, err := json.Marshal(data)
	if err != nil {
		return false, err
	}
	now := h.now()
	added := false
	err = h.Store.Update(func(s *state.State) error {
		pruneInbox(s, now)
		if slices.ContainsFunc(s.Inbox, func(e *state.InboxEvent) bool { return e.EventID == eventID }) {
			return nil
		}
		added = true
		s.Inbox = append(s.Inbox, &state.InboxEvent{EventID: eventID, Name: EventName, Timestamp: now, Data: raw})
		for _, sub := range s.Subscriptions {
			if sub.Active(now) {
				s.Outbox = append(s.Outbox, &state.OutboxItem{EventID: eventID, SubscriptionID: sub.ID, FirstAt: now, NextAt: now})
			}
		}
		return nil
	})
	if err != nil {
		return false, err
	}
	if added {
		h.Log.Info("event.emit", "event", eventID)
		h.wakeUp()
	}
	return added, nil
}

// Pending returns unacknowledged events, oldest first.
func (h *Hub) Pending(limit int) []state.InboxEvent {
	var out []state.InboxEvent
	h.Store.View(func(s *state.State) {
		for _, e := range s.Inbox {
			if e.AckedAt == nil && len(out) < limit {
				out = append(out, *e)
			}
		}
	})
	return out
}

// Acknowledge marks events processed; it returns how many were newly acknowledged and how
// many remain pending.
func (h *Hub) Acknowledge(ids []string) (acked, pending int, err error) {
	now := h.now()
	err = h.Store.Update(func(s *state.State) error {
		for _, e := range s.Inbox {
			if e.AckedAt == nil && slices.Contains(ids, e.EventID) {
				t := now
				e.AckedAt = &t
				acked++
			}
		}
		pruneInbox(s, now)
		for _, e := range s.Inbox {
			if e.AckedAt == nil {
				pending++
			}
		}
		return nil
	})
	return acked, pending, err
}

func pruneInbox(s *state.State, now time.Time) {
	s.Inbox = slices.DeleteFunc(s.Inbox, func(e *state.InboxEvent) bool {
		if e.AckedAt != nil {
			return now.Sub(*e.AckedAt) > inboxAckedKeep
		}
		return now.Sub(e.Timestamp) > inboxPendingKeep
	})
}

// Run delivers queued events until ctx is done. It is woken by Emit and otherwise checks the
// queue every few seconds for retries that came due.
func (h *Hub) Run(ctx context.Context) {
	h.wakeUp()
	h.mu.Lock()
	wake := h.wake
	h.mu.Unlock()
	tick := time.NewTicker(5 * time.Second)
	defer tick.Stop()
	for {
		h.deliverDue(ctx)
		select {
		case <-ctx.Done():
			return
		case <-wake:
		case <-tick.C:
		}
	}
}

type job struct {
	item  state.OutboxItem
	sub   state.Subscription
	event state.InboxEvent
}

func (h *Hub) deliverDue(ctx context.Context) {
	now := h.now()
	var jobs []job
	_ = h.Store.Update(func(s *state.State) error {
		s.Outbox = slices.DeleteFunc(s.Outbox, func(o *state.OutboxItem) bool {
			sub := s.Subscriptions[o.SubscriptionID]
			idx := slices.IndexFunc(s.Inbox, func(e *state.InboxEvent) bool { return e.EventID == o.EventID })
			return sub == nil || idx < 0
		})
		for _, o := range s.Outbox {
			if o.NextAt.After(now) {
				continue
			}
			sub := s.Subscriptions[o.SubscriptionID]
			idx := slices.IndexFunc(s.Inbox, func(e *state.InboxEvent) bool { return e.EventID == o.EventID })
			jobs = append(jobs, job{item: *o, sub: *sub, event: *s.Inbox[idx]})
		}
		return nil
	})
	for _, j := range jobs {
		if ctx.Err() != nil {
			return
		}
		h.deliver(ctx, j)
	}
}

func (h *Hub) deliver(ctx context.Context, j job) {
	now := h.now()
	body, _ := json.Marshal(map[string]any{
		"eventId": j.event.EventID, "name": j.event.Name,
		"timestamp": j.event.Timestamp.UTC().Format(time.RFC3339Nano), "data": j.event.Data, "cursor": nil,
	})
	secrets := []string{j.sub.Secret}
	if j.sub.PreviousSecret != "" && now.Before(j.sub.RotationUntil) {
		secrets = append(secrets, j.sub.PreviousSecret)
	}
	status, _, retryAfter, err := h.post(ctx, j.sub.URL, secrets, j.event.EventID, j.sub.ID, body)
	result := &state.DeliveryResult{At: now, EventID: j.event.EventID, SubscriptionID: j.sub.ID, Status: status}
	if err != nil {
		result.Error = err.Error()
	}
	same := func(o *state.OutboxItem) bool {
		return o.EventID == j.item.EventID && o.SubscriptionID == j.item.SubscriptionID
	}
	_ = h.Store.Update(func(s *state.State) error {
		s.LastDelivery = result
		switch {
		case err == nil && status >= 200 && status <= 299:
			s.Outbox = slices.DeleteFunc(s.Outbox, same)
			h.Log.Info("delivery.ok", "event", j.event.EventID, "subscription", j.sub.ID, "status", status)
		case err == nil && status == http.StatusGone:
			delete(s.Subscriptions, j.sub.ID)
			s.Outbox = slices.DeleteFunc(s.Outbox, func(o *state.OutboxItem) bool { return o.SubscriptionID == j.sub.ID })
			h.Log.Warn("delivery.gone", "subscription", j.sub.ID)
		case err == nil && !retryable(status):
			s.Outbox = slices.DeleteFunc(s.Outbox, same)
			h.Log.Warn("delivery.rejected", "event", j.event.EventID, "subscription", j.sub.ID, "status", status)
		default:
			idx := slices.IndexFunc(s.Outbox, same)
			if idx < 0 {
				return nil
			}
			o := s.Outbox[idx]
			o.Attempts++
			if now.Sub(o.FirstAt) > giveUpAfter {
				s.Outbox = slices.Delete(s.Outbox, idx, idx+1)
				h.Log.Warn("delivery.abandoned", "event", j.event.EventID, "subscription", j.sub.ID, "attempts", o.Attempts)
				return nil
			}
			o.NextAt = now.Add(backoff(o.Attempts, retryAfter))
			h.Log.Warn("delivery.retry", "event", j.event.EventID, "subscription", j.sub.ID, "status", status, "attempts", o.Attempts, "error", result.Error)
		}
		return nil
	})
}

func retryable(status int) bool {
	return status == http.StatusRequestTimeout || status == http.StatusTooEarly ||
		status == http.StatusTooManyRequests || status >= 500
}

// backoff doubles from 5 s up to an hour; a longer Retry-After from the receiver wins, still
// capped at an hour.
func backoff(attempts int, retryAfter time.Duration) time.Duration {
	d := 5 * time.Second << min(attempts-1, 10)
	return min(max(d, retryAfter), maxBackoff)
}

// post sends one signed request without following redirects and returns the status, up to
// 64 KiB of the body and the receiver's Retry-After, if any.
func (h *Hub) post(ctx context.Context, url string, secrets []string, msgID, subID string, body []byte) (int, []byte, time.Duration, error) {
	if _, err := h.Guard.Check(url); err != nil {
		return 0, nil, 0, err
	}
	ts := h.now()
	sig, err := signatures(secrets, msgID, ts, body)
	if err != nil {
		return 0, nil, 0, err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewReader(body))
	if err != nil {
		return 0, nil, 0, err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("User-Agent", "recly-events")
	req.Header.Set("webhook-id", msgID)
	req.Header.Set("webhook-timestamp", strconv.FormatInt(ts.Unix(), 10))
	req.Header.Set("webhook-signature", sig)
	if subID != "" {
		req.Header.Set("X-MCP-Subscription-Id", subID)
	}
	resp, err := h.Guard.Client(15 * time.Second).Do(req)
	if err != nil {
		return 0, nil, 0, err
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(io.LimitReader(resp.Body, 64<<10))
	var retryAfter time.Duration
	if secs, err := strconv.Atoi(resp.Header.Get("Retry-After")); err == nil && secs > 0 {
		retryAfter = time.Duration(secs) * time.Second
	}
	return resp.StatusCode, b, retryAfter, nil
}
