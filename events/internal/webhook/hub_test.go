package webhook

import (
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/rokrokss/recly/events/internal/state"
)

// The Standard Webhooks reference vector (also what OpenAI MCP Events verifies).
func TestSignatureMatchesStandardWebhooksVector(t *testing.T) {
	key, err := signingKey("whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw")
	if err != nil {
		t.Fatal(err)
	}
	got := signature(key, "msg_p5jXN8AQM9LWM0D4loKWxJek", time.Unix(1614265330, 0), []byte(`{"test": 2432232314}`))
	if want := "v1,g0hM9SsE+OTPJTGt/tmIKtSyZlE3uFJELVlNIOLJ1OE="; got != want {
		t.Fatalf("signature = %s, want %s", got, want)
	}
}

func TestSigningKeyRejectsBadSecrets(t *testing.T) {
	for _, s := range []string{"", "whsec_", "whsec_short", "nope_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw"} {
		if _, err := signingKey(s); err == nil {
			t.Errorf("signingKey(%q) accepted", s)
		}
	}
}

const secret = "whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw"

// receiver is a callback endpoint that checks signatures, echoes challenges and answers
// deliveries with the next queued status.
type receiver struct {
	t        *testing.T
	mu       sync.Mutex
	statuses []int
	events   []map[string]any
	headers  []http.Header
}

func (rc *receiver) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	body, _ := io.ReadAll(r.Body)
	key, _ := signingKey(secret)
	unix, _ := strconv.ParseInt(r.Header.Get("webhook-timestamp"), 10, 64)
	want := signature(key, r.Header.Get("webhook-id"), time.Unix(unix, 0), body)
	if !strings.Contains(r.Header.Get("webhook-signature"), want) {
		rc.t.Errorf("bad signature %q, want %q", r.Header.Get("webhook-signature"), want)
	}
	var msg map[string]any
	_ = json.Unmarshal(body, &msg)
	if !strings.HasPrefix(r.Header.Get("X-MCP-Subscription-Id"), "sub_") {
		rc.t.Errorf("%v without X-MCP-Subscription-Id", msg["type"])
	}
	if msg["type"] == "verification" {
		_ = json.NewEncoder(w).Encode(map[string]any{"challenge": msg["challenge"]})
		return
	}
	rc.mu.Lock()
	defer rc.mu.Unlock()
	rc.events = append(rc.events, msg)
	rc.headers = append(rc.headers, r.Header.Clone())
	status := http.StatusNoContent
	if len(rc.statuses) > 0 {
		status, rc.statuses = rc.statuses[0], rc.statuses[1:]
	}
	if status == http.StatusTooManyRequests {
		w.Header().Set("Retry-After", "120")
	}
	w.WriteHeader(status)
}

type fixture struct {
	hub   *Hub
	store *state.Store
	rc    *receiver
	url   string
	now   time.Time
	path  string
}

func newFixture(t *testing.T) *fixture {
	t.Helper()
	rc := &receiver{t: t}
	srv := httptest.NewTLSServer(rc)
	t.Cleanup(srv.Close)
	path := filepath.Join(t.TempDir(), "state.json")
	store, err := state.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	f := &fixture{store: store, rc: rc, url: srv.URL + "/webhook/mcp-events/abc", now: time.Date(2026, 10, 5, 6, 0, 0, 0, time.UTC), path: path}
	f.hub = &Hub{
		Store: store,
		Guard: &Guard{Hosts: []string{"127.0.0.1"}, AllowPrivate: true, Transport: srv.Client().Transport.(*http.Transport)},
		Log:   slog.New(slog.NewTextHandler(io.Discard, nil)),
		Now:   func() time.Time { return f.now },
	}
	return f
}

func (f *fixture) subscribe(t *testing.T) *SubscribeResult {
	t.Helper()
	var p SubscribeParams
	p.Name = EventName
	p.Arguments = json.RawMessage(`{}`)
	p.Delivery.Mode, p.Delivery.URL, p.Delivery.Secret = "webhook", f.url, secret
	res, err := f.hub.Subscribe(context.Background(), p)
	if err != nil {
		t.Fatalf("subscribe: %v", err)
	}
	return res
}

func TestSubscribeVerifiesAndGrantsNoExpiry(t *testing.T) {
	f := newFixture(t)
	res := f.subscribe(t)
	if !strings.HasPrefix(res.ID, "sub_") || res.RefreshBefore != nil || res.Cursor != nil {
		t.Fatalf("result = %+v", res)
	}
	again := f.subscribe(t)
	if again.ID != res.ID {
		t.Fatalf("refresh changed the id: %s vs %s", again.ID, res.ID)
	}
	f.store.View(func(s *state.State) {
		if len(s.Subscriptions) != 1 || s.Subscriptions[res.ID].ExpiresAt != nil {
			t.Fatalf("subscriptions = %+v", s.Subscriptions)
		}
	})
}

func TestSubscribeRefusesUnknownHostAndBadSecret(t *testing.T) {
	f := newFixture(t)
	var p SubscribeParams
	p.Name = EventName
	p.Delivery.Mode, p.Delivery.URL, p.Delivery.Secret = "webhook", "https://evil.example/hook", secret
	if _, err := f.hub.Subscribe(context.Background(), p); err == nil || !strings.Contains(err.Error(), "not in callbackHosts") {
		t.Fatalf("unknown host: %v", err)
	}
	p.Delivery.URL, p.Delivery.Secret = f.url, "whsec_short"
	if _, err := f.hub.Subscribe(context.Background(), p); err == nil {
		t.Fatal("short secret accepted")
	}
}

func TestEmitDeliversSignedEventOnceAndKeepsInbox(t *testing.T) {
	f := newFixture(t)
	res := f.subscribe(t)
	added, err := f.hub.Emit("evt_1", map[string]string{"recording": "r1"})
	if err != nil || !added {
		t.Fatalf("emit: %v %v", added, err)
	}
	if again, _ := f.hub.Emit("evt_1", map[string]string{"recording": "r1"}); again {
		t.Fatal("duplicate event id was queued again")
	}
	f.hub.deliverDue(context.Background())
	if len(f.rc.events) != 1 || f.rc.events[0]["eventId"] != "evt_1" || f.rc.events[0]["name"] != EventName {
		t.Fatalf("delivered = %+v", f.rc.events)
	}
	if got := f.rc.headers[0].Get("X-MCP-Subscription-Id"); got != res.ID {
		t.Fatalf("subscription header = %q", got)
	}
	f.store.View(func(s *state.State) {
		if len(s.Outbox) != 0 || s.LastDelivery.Status != http.StatusNoContent {
			t.Fatalf("outbox = %+v last = %+v", s.Outbox, s.LastDelivery)
		}
	})
	pending := f.hub.Pending(10)
	if len(pending) != 1 {
		t.Fatalf("pending = %+v", pending)
	}
	acked, left, err := f.hub.Acknowledge([]string{"evt_1"})
	if err != nil || acked != 1 || left != 0 || len(f.hub.Pending(10)) != 0 {
		t.Fatalf("ack = %d %d %v", acked, left, err)
	}
}

func TestRetryHonorsRetryAfterAndSurvivesRestart(t *testing.T) {
	f := newFixture(t)
	f.subscribe(t)
	f.rc.statuses = []int{http.StatusTooManyRequests}
	if _, err := f.hub.Emit("evt_1", map[string]string{}); err != nil {
		t.Fatal(err)
	}
	f.hub.deliverDue(context.Background())
	var next time.Time
	f.store.View(func(s *state.State) { next = s.Outbox[0].NextAt })
	if want := f.now.Add(120 * time.Second); !next.Equal(want) {
		t.Fatalf("next attempt %v, want %v", next, want)
	}

	// A new process reading the same file finds the queue and delivers when it comes due.
	reopened, err := state.Open(f.path)
	if err != nil {
		t.Fatal(err)
	}
	f.hub.Store = reopened
	f.now = f.now.Add(2 * time.Minute)
	f.hub.deliverDue(context.Background())
	if len(f.rc.events) != 2 {
		t.Fatalf("deliveries = %d", len(f.rc.events))
	}
	reopened.View(func(s *state.State) {
		if len(s.Outbox) != 0 {
			t.Fatalf("outbox = %+v", s.Outbox)
		}
	})
}

func TestGoneEndsSubscription(t *testing.T) {
	f := newFixture(t)
	f.subscribe(t)
	f.rc.statuses = []int{http.StatusGone}
	if _, err := f.hub.Emit("evt_1", map[string]string{}); err != nil {
		t.Fatal(err)
	}
	f.hub.deliverDue(context.Background())
	f.store.View(func(s *state.State) {
		if len(s.Subscriptions) != 0 || len(s.Outbox) != 0 {
			t.Fatalf("subs = %d outbox = %d", len(s.Subscriptions), len(s.Outbox))
		}
	})
}

func TestUnsubscribeStopsDelivery(t *testing.T) {
	f := newFixture(t)
	f.subscribe(t)
	var p SubscribeParams
	p.Name, p.Delivery.URL = EventName, f.url
	if err := f.hub.Unsubscribe(p); err != nil {
		t.Fatal(err)
	}
	if _, err := f.hub.Emit("evt_1", map[string]string{}); err != nil {
		t.Fatal(err)
	}
	f.hub.deliverDue(context.Background())
	if len(f.rc.events) != 0 {
		t.Fatalf("delivered after unsubscribe: %+v", f.rc.events)
	}
}

func TestGuardRefusesPrivateAddressesAndOtherPorts(t *testing.T) {
	g := &Guard{Hosts: []string{"connectors.api.openai.com", "localhost"}}
	if _, err := g.Check("https://connectors.api.openai.com:8443/x"); err == nil {
		t.Error("port 8443 accepted")
	}
	if _, err := g.Check("http://connectors.api.openai.com/x"); err == nil {
		t.Error("http accepted")
	}
	if _, err := g.Client(time.Second).Get("https://localhost/"); err == nil || !strings.Contains(err.Error(), "non-public") {
		t.Errorf("loopback dial: %v", err)
	}
}
