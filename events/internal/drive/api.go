package drive

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"

	"github.com/rokrokss/recly/events/internal/library"
)

// API is the handful of Drive v3 calls recly-events makes: metadata, and the content of a
// recording's meta and transcript when the agent asks for them (Source).
type API struct {
	Client *http.Client
	// Base defaults to https://www.googleapis.com/drive/v3. Tests point it at a fake.
	Base string
}

// File is the metadata recly-events reads.
type File struct {
	ID          string   `json:"id"`
	Name        string   `json:"name"`
	MimeType    string   `json:"mimeType"`
	Description string   `json:"description"`
	Parents     []string `json:"parents"`
	Trashed     bool     `json:"trashed"`
	MD5         string   `json:"md5Checksum"`
	Size        string   `json:"size"`
	Modified    string   `json:"modifiedTime"`
	WebViewLink string   `json:"webViewLink"`
	// AppProperties are visible only to clients of the project that wrote them: Recly's own
	// client sees the recordingId Recly puts on each recording folder, and the mark the app puts on
	// each transcript version it writes.
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

// fetch GETs path and returns the body, refusing one longer than limit.
func (a *API) fetch(ctx context.Context, path string, q url.Values, limit int64) ([]byte, error) {
	u := a.base() + path
	if len(q) > 0 {
		u += "?" + q.Encode()
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return nil, err
	}
	resp, err := a.Client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	b, err := io.ReadAll(io.LimitReader(resp.Body, limit+1))
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		return nil, &StatusError{Status: resp.StatusCode, Body: strings.TrimSpace(string(b[:min(len(b), 300)]))}
	}
	if err != nil {
		return nil, err
	}
	if int64(len(b)) > limit {
		return nil, fmt.Errorf("%w: more than %d bytes", library.ErrTooLarge, limit)
	}
	return b, nil
}

func (a *API) get(ctx context.Context, path string, q url.Values, out any) error {
	b, err := a.fetch(ctx, path, q, 4<<20)
	if err != nil {
		return err
	}
	return json.Unmarshal(b, out)
}

// Download returns a file's content, refusing one longer than limit.
func (a *API) Download(ctx context.Context, id string, limit int64) ([]byte, error) {
	return a.fetch(ctx, "/files/"+url.PathEscape(id), url.Values{"alt": {"media"}}, limit)
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

const fileFields = "id,name,mimeType,description,parents,trashed,md5Checksum,size,modifiedTime,webViewLink,appProperties"

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
	files, _, err := a.ListPage(ctx, query, "createdTime desc", pageSize, "")
	return files, err
}

// ListPage returns one page of the files matching a Drive query, in orderBy order, and the token
// of the next page ("" after the last).
func (a *API) ListPage(ctx context.Context, query, orderBy string, pageSize int, pageToken string) ([]File, string, error) {
	var out struct {
		Files         []File `json:"files"`
		NextPageToken string `json:"nextPageToken"`
	}
	q := url.Values{
		"q":        {query},
		"pageSize": {fmt.Sprint(pageSize)},
		"orderBy":  {orderBy},
		"fields":   {"nextPageToken,files(" + fileFields + ")"},
	}
	if pageToken != "" {
		q.Set("pageToken", pageToken)
	}
	err := a.get(ctx, "/files", q, &out)
	return out.Files, out.NextPageToken, err
}

// AccountID returns the signed-in account's opaque Drive identifier: `init` confirms Drive with it,
// and no email, name or profile is read (docs/recly.md §15 §9).
func (a *API) AccountID(ctx context.Context) (string, error) {
	var out struct {
		User struct {
			PermissionID string `json:"permissionId"`
		} `json:"user"`
	}
	if err := a.get(ctx, "/about", url.Values{"fields": {"user(permissionId)"}}, &out); err != nil {
		return "", err
	}
	if out.User.PermissionID == "" {
		return "", errors.New("drive: about answered without a permissionId")
	}
	return out.User.PermissionID, nil
}

// quote escapes a value for a Drive query string literal.
func quote(s string) string {
	return "'" + strings.ReplaceAll(strings.ReplaceAll(s, `\`, `\\`), `'`, `\'`) + "'"
}
