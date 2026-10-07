// Package library reads Recly recordings and their transcripts for the read-only MCP tools, from
// Google Drive (internal/drive) or from folders on this computer (Folder), and renders them the
// same way for both servers: the one ChatGPT reaches through the tunnel and the local one on
// standard input and output.
package library

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"regexp"
	"strings"
	"time"
)

// BasePattern is Recly's recording base name, `{yyyyMMdd}T{HHmmss}Z_{source}_{first 8 of
// recordingId}` (docs/recly.md §3 "Naming rules", core MetaWriter.baseName), with three groups:
// the start time, the source and the ID prefix.
const BasePattern = `(\d{8}T\d{6}Z)_(watch|phone|desktop|import)_([0-9A-Z]{8})`

var (
	baseName    = regexp.MustCompile(`^` + BasePattern + `$`)
	recordingID = regexp.MustCompile(`^[0-7][0-9A-HJKMNP-TV-Z]{25}$`)
	partName    = regexp.MustCompile(`^` + BasePattern + `_p\d{3}_(mono|mic|sys|mix)\.m4a$`)
	txtSpeaker  = regexp.MustCompile(`^\[\d{2,}:\d{2}:\d{2}\] (S[1-9][0-9]*): `)
	txtClock    = regexp.MustCompile(`^\[(\d{2,}):(\d{2}):(\d{2})\]`)
)

// File names in a recording folder (docs/recly.md §3 "Drive layout", §8 "Result files").
func MetaName(base string) string           { return base + ".meta.json" }
func TranscriptJSONName(base string) string { return base + ".transcript.json" }
func TranscriptTxtName(base string) string  { return base + ".transcript.txt" }

// FolderPropertiesName is iCloud's stand-in for what Drive keeps on the folder, its description
// (the title) among them (docs/recly.md §3 "Storage location").
func FolderPropertiesName(base string) string { return base + ".folder.json" }

// Size caps: a meta or a folder property file is a few kilobytes; a transcript with word timings
// for a long meeting is a few megabytes. Variables, so tests can lower them.
var (
	MaxMetaBytes       int64 = 1 << 20
	MaxTranscriptBytes int64 = 32 << 20
)

// Base is a parsed base name.
type Base struct {
	Name      string
	StartedAt time.Time
	Source    string
	Prefix    string
}

// ParseBase parses a recording folder's name.
func ParseBase(name string) (Base, bool) {
	m := baseName.FindStringSubmatch(name)
	if m == nil {
		return Base{}, false
	}
	t, err := time.Parse("20060102T150405Z", m[1])
	if err != nil {
		return Base{}, false
	}
	return Base{Name: name, StartedAt: t.UTC(), Source: m[2], Prefix: m[3]}, true
}

// IsRecordingID reports whether s is a Recly recording ID (a ULID).
func IsRecordingID(s string) bool { return recordingID.MatchString(s) }

// IsPartName reports whether name is an audio part of the recording base (§3 "Naming rules").
func IsPartName(base, name string) bool {
	return partName.MatchString(name) && strings.HasPrefix(name, base+"_p")
}

// Errors a Source returns; the tools turn them into answers the agent can act on.
var (
	ErrNotFound     = errors.New("no such recording")
	ErrNoTranscript = errors.New("the recording has no transcript yet")
	ErrTooLarge     = errors.New("the file is too large to read")
)

// Recording is one row of list_recordings: what the recording is, never what was said.
type Recording struct {
	RecordingID string  `json:"recordingId"`
	Title       *string `json:"title"`
	StartedAt   string  `json:"startedAt"`
	// DurationSec is null while the meta has none (a recording still going).
	DurationSec    *float64 `json:"durationSec"`
	Source         string   `json:"source"`
	HasTranscript  bool     `json:"hasTranscript"`
	HighlightCount int      `json:"highlightCount"`

	// base sorts the list and is the cursor of a folder listing.
	base string
}

// Speaker is a transcript speaker; Name is set once the user named them in the app.
type Speaker struct {
	ID   string  `json:"id"`
	Name *string `json:"name"`
}

// Line is one rendered transcript line, `[HH:MM:SS] Name: text`.
type Line struct {
	AtSec float64
	Text  string
}

// Transcript is a recording's transcript as get_transcript returns it.
type Transcript struct {
	RecordingID string
	Title       *string
	StartedAt   string
	// Language is empty when only the .txt was there.
	Language   string
	Speakers   []Speaker
	Highlights []float64
	Lines      []Line
}

// Source is where recordings are read from.
type Source interface {
	// Recordings returns up to limit recordings, newest first, and the cursor of the next page
	// ("" at the end).
	Recordings(ctx context.Context, limit int, cursor string) ([]Recording, string, error)
	// Transcript returns the transcript of a recording, named by its recordingId or its base name.
	Transcript(ctx context.Context, id string) (*Transcript, error)
}

// Meta is what the tools read from `{base}.meta.json` (spec/recording.meta.schema.json).
// Highlights is read when the apps write it; recordings without it have none.
type Meta struct {
	RecordingID string   `json:"recordingId"`
	Title       string   `json:"title"`
	DurationSec *float64 `json:"durationSec"`
	Highlights  []struct {
		AtSec *float64 `json:"atSec"`
	} `json:"highlights"`
	Parts []struct {
		Part           int      `json:"part"`
		Track          string   `json:"track"`
		File           string   `json:"file"`
		Bytes          int64    `json:"bytes"`
		StartOffsetSec float64  `json:"startOffsetSec"`
		DurationSec    *float64 `json:"durationSec"`
	} `json:"parts"`
}

// ParseMeta reads a meta file. A file still being written does not parse, and is treated as absent.
func ParseMeta(b []byte) (*Meta, error) {
	var m Meta
	if err := json.Unmarshal(b, &m); err != nil {
		return nil, err
	}
	return &m, nil
}

// HighlightTimes returns the highlights' offsets, in seconds on the recording's timeline.
func (m *Meta) HighlightTimes() []float64 {
	if m == nil {
		return nil
	}
	var out []float64
	for _, h := range m.Highlights {
		if h.AtSec != nil && *h.AtSec >= 0 {
			out = append(out, *h.AtSec)
		}
	}
	return out
}

// FolderTitle reads the title from iCloud's `{base}.folder.json`.
func FolderTitle(b []byte) string {
	var p struct {
		Description string `json:"description"`
	}
	if json.Unmarshal(b, &p) != nil {
		return ""
	}
	return p.Description
}

// TitleOf is the first non-blank title, or nil.
func TitleOf(candidates ...string) *string {
	for _, c := range candidates {
		if t := strings.TrimSpace(c); t != "" {
			return &c
		}
	}
	return nil
}

// StartedAtOf is the start time the event data and every tool use: the base name's, in UTC seconds.
func StartedAtOf(b Base) string { return b.StartedAt.Format(time.RFC3339) }

type transcriptDoc struct {
	RecordingID string    `json:"recordingId"`
	Language    string    `json:"language"`
	Speakers    []Speaker `json:"speakers"`
	Segments    []struct {
		Start   float64 `json:"start"`
		End     float64 `json:"end"`
		Speaker string  `json:"speaker"`
		Text    string  `json:"text"`
	} `json:"segments"`
}

// lineSec is the longest a line runs before it is broken, as in the app's `.transcript.txt`.
const lineSec = 60.0

// FromJSON renders `{base}.transcript.json` (spec/transcript.schema.json) into lines the way the app
// renders `.transcript.txt` (core TranscriptNormalizer.text): a new line when the speaker changes
// or a line passes 60 seconds — with a speaker's name where the user gave one.
func FromJSON(b []byte) (*Transcript, error) {
	var doc transcriptDoc
	if err := json.Unmarshal(b, &doc); err != nil {
		return nil, err
	}
	names := map[string]string{}
	for _, s := range doc.Speakers {
		if s.Name != nil && strings.TrimSpace(*s.Name) != "" {
			names[s.ID] = oneLine(strings.TrimSpace(*s.Name))
		}
	}
	t := &Transcript{RecordingID: doc.RecordingID, Language: doc.Language, Speakers: doc.Speakers}
	var cur *strings.Builder
	var at, lineStart float64
	speaker := ""
	flush := func() {
		if cur != nil {
			t.Lines = append(t.Lines, Line{AtSec: at, Text: cur.String()})
		}
	}
	for i, seg := range doc.Segments {
		if i == 0 || seg.Speaker != speaker || seg.End-lineStart > lineSec {
			flush()
			cur = &strings.Builder{}
			speaker, lineStart, at = seg.Speaker, seg.Start, seg.Start
			cur.WriteString("[" + Clock(seg.Start) + "] ")
			if seg.Speaker != "" {
				label := seg.Speaker
				if n, ok := names[seg.Speaker]; ok {
					label = n
				}
				cur.WriteString(label + ": ")
			}
		} else {
			cur.WriteByte(' ')
		}
		cur.WriteString(oneLine(strings.TrimSpace(seg.Text)))
	}
	flush()
	if t.Speakers == nil {
		t.Speakers = []Speaker{}
	}
	return t, nil
}

// FromText reads `{base}.transcript.txt`, for a recording whose JSON transcript is not there.
func FromText(b []byte) *Transcript {
	t := &Transcript{Speakers: []Speaker{}}
	seen := map[string]bool{}
	for _, l := range strings.Split(strings.ReplaceAll(string(b), "\r\n", "\n"), "\n") {
		if strings.TrimSpace(l) == "" {
			continue
		}
		var at float64
		if m := txtClock.FindStringSubmatch(l); m != nil {
			var h, mi, s int
			fmt.Sscan(m[1], &h)
			fmt.Sscan(m[2], &mi)
			fmt.Sscan(m[3], &s)
			at = float64(h*3600 + mi*60 + s)
		}
		if m := txtSpeaker.FindStringSubmatch(l); m != nil && !seen[m[1]] {
			seen[m[1]] = true
			t.Speakers = append(t.Speakers, Speaker{ID: m[1]})
		}
		t.Lines = append(t.Lines, Line{AtSec: at, Text: l})
	}
	return t
}

// Clock is `01:02:03`; hours are not wrapped at 24, a recording is not a clock.
func Clock(sec float64) string {
	total := max(int64(sec), 0)
	return fmt.Sprintf("%02d:%02d:%02d", total/3600, total%3600/60, total%60)
}

func oneLine(s string) string {
	return strings.NewReplacer("\r\n", " ", "\n", " ", "\r", " ").Replace(s)
}
