package drive

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
)

// API is the handful of Drive v3 calls recly-events makes, all metadata-only.
type API struct {
	Client *http.Client
	// Base defaults to https://www.googleapis.com/drive/v3. Tests point it at a fake.
	Base string
}

// File is the metadata recly-events reads.
type File struct {
	ID          string   `json:"id"`
	Name        string   `json:"name"`
	Description string   `json:"description"`
	Parents     []string `json:"parents"`
	Trashed     bool     `json:"trashed"`
	MD5         string   `json:"md5Checksum"`
	Size        string   `json:"size"`
	Modified    string   `json:"modifiedTime"`
	WebViewLink string   `json:"webViewLink"`
	// AppProperties are visible only to clients of the project that wrote them: Recly's own
	// client sees the recordingId Recly puts on each recording folder.
	AppProperties map[string]string `json:"appProperties"`
}

// Change is one entry of the changes feed.
type Change struct {
	FileID  string `json:"fileId"`
	Removed bool   `json:"removed"`
	File    *File  `json:"file"`
}

// ChangePage is one page of the changes feed.
type ChangePage struct {
	NextPageToken     string   `json:"nextPageToken"`
	NewStartPageToken string   `json:"newStartPageToken"`
	Changes           []Change `json:"changes"`
}

// StatusError is a non-2xx answer from Drive.
type StatusError struct {
	Status int
	Body   string
}

func (e *StatusError) Error() string { return fmt.Sprintf("drive: HTTP %d: %s", e.Status, e.Body) }

func (a *API) base() string {
	if a.Base != "" {
		return a.Base
	}
	return "https://www.googleapis.com/drive/v3"
}

func (a *API) get(ctx context.Context, path string, q url.Values, out any) error {
	u := a.base() + path
	if len(q) > 0 {
		u += "?" + q.Encode()
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return err
	}
	resp, err := a.Client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(io.LimitReader(resp.Body, 4<<20))
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		return &StatusError{Status: resp.StatusCode, Body: strings.TrimSpace(string(b[:min(len(b), 300)]))}
	}
	return json.Unmarshal(b, out)
}

// StartPageToken returns the current end of the changes feed.
func (a *API) StartPageToken(ctx context.Context) (string, error) {
	var out struct {
		StartPageToken string `json:"startPageToken"`
	}
	if err := a.get(ctx, "/changes/startPageToken", nil, &out); err != nil {
		return "", err
	}
	return out.StartPageToken, nil
}

const fileFields = "id,name,description,parents,trashed,md5Checksum,size,modifiedTime,webViewLink,appProperties"

// Changes returns one page of changes after token.
func (a *API) Changes(ctx context.Context, token string) (ChangePage, error) {
	var out ChangePage
	q := url.Values{
		"pageToken":      {token},
		"pageSize":       {"1000"},
		"spaces":         {"drive"},
		"includeRemoved": {"false"},
		"fields":         {"nextPageToken,newStartPageToken,changes(fileId,removed,file(" + fileFields + "))"},
	}
	return out, a.get(ctx, "/changes", q, &out)
}

// Get returns one file's metadata.
func (a *API) Get(ctx context.Context, id string) (File, error) {
	var out File
	return out, a.get(ctx, "/files/"+url.PathEscape(id), url.Values{"fields": {fileFields}}, &out)
}

// List returns the files matching a Drive query, newest first.
func (a *API) List(ctx context.Context, query string, pageSize int) ([]File, error) {
	var out struct {
		Files []File `json:"files"`
	}
	q := url.Values{
		"q":        {query},
		"pageSize": {fmt.Sprint(pageSize)},
		"orderBy":  {"createdTime desc"},
		"fields":   {"files(" + fileFields + ")"},
	}
	return out.Files, a.get(ctx, "/files", q, &out)
}

// Account returns the signed-in account's email address.
func (a *API) Account(ctx context.Context) (string, error) {
	var out struct {
		User struct {
			EmailAddress string `json:"emailAddress"`
		} `json:"user"`
	}
	return out.User.EmailAddress, a.get(ctx, "/about", url.Values{"fields": {"user(emailAddress)"}}, &out)
}

// quote escapes a value for a Drive query string literal.
func quote(s string) string {
	return "'" + strings.ReplaceAll(strings.ReplaceAll(s, `\`, `\\`), `'`, `\'`) + "'"
}
