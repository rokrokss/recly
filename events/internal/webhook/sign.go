// Package webhook delivers MCP events to subscribers as Standard Webhooks
// (https://www.standardwebhooks.com/), the delivery format of OpenAI MCP Events.
package webhook

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"errors"
	"strconv"
	"strings"
	"time"
)

// signingKey decodes a `whsec_` secret. OpenAI sends 32-byte secrets; Standard Webhooks
// allows 24 to 64.
func signingKey(secret string) ([]byte, error) {
	raw, ok := strings.CutPrefix(secret, "whsec_")
	if !ok {
		return nil, errors.New("signing secret must start with whsec_")
	}
	key, err := base64.StdEncoding.DecodeString(raw)
	if err != nil {
		return nil, errors.New("signing secret is not base64")
	}
	if len(key) < 24 || len(key) > 64 {
		return nil, errors.New("signing secret must be 24 to 64 bytes")
	}
	return key, nil
}

// signature returns the `webhook-signature` value for one secret.
func signature(key []byte, msgID string, ts time.Time, body []byte) string {
	mac := hmac.New(sha256.New, key)
	mac.Write([]byte(msgID + "." + strconv.FormatInt(ts.Unix(), 10) + "."))
	mac.Write(body)
	return "v1," + base64.StdEncoding.EncodeToString(mac.Sum(nil))
}

// signatures signs with every given secret; during a rotation both the new and the previous
// secret sign, space separated, so the receiver accepts either.
func signatures(secrets []string, msgID string, ts time.Time, body []byte) (string, error) {
	var out []string
	for _, s := range secrets {
		if s == "" {
			continue
		}
		key, err := signingKey(s)
		if err != nil {
			return "", err
		}
		out = append(out, signature(key, msgID, ts, body))
	}
	if len(out) == 0 {
		return "", errors.New("no signing secret")
	}
	return strings.Join(out, " "), nil
}
