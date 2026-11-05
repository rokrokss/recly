package library

import (
	"cmp"
	"encoding/json"
	"slices"
	"sort"
	"strings"
)

// SummaryFiles is what get_summary reads for a recording: its summary (`{base}.notes.json`,
// spec/notes.schema.json) and the user's own notes (`{base}.memo.json`, spec/memo.schema.json) as
// stored, each nil when it is not there, and the transcript's speakers, which name a to-do's owner.
type SummaryFiles struct {
	RecordingID string
	Title       *string
	StartedAt   string
	Notes       []byte
	Memo        []byte
	Speakers    []Speaker
}

type notesDoc struct {
	Overview         string `json:"overview"`
	OverviewEditedAt string `json:"overviewEditedAt"`
	ModelName        string `json:"modelName"`
	UpdatedAt        string `json:"updatedAt"`
	Sections         []struct {
		ID          string `json:"id"`
		Name        string `json:"name"`
		Description string `json:"description"`
		Kind        string `json:"kind"`
	} `json:"sections"`
	Items []noteItem `json:"items"`
}

type noteItem struct {
	Kind     string `json:"kind"`
	Text     string `json:"text"`
	UserText string `json:"userText"`
	Owner    string `json:"owner"`
	Due      string `json:"due"`
	Section  string `json:"section"`
	EditedAt string `json:"editedAt"`
	Evidence struct {
		AtSec float64 `json:"atSec"`
	} `json:"evidence"`
}

// words are the item's words as the app shows them: the user's, once they edited it.
func (i noteItem) words() string {
	if strings.TrimSpace(i.UserText) != "" {
		return i.UserText
	}
	return i.Text
}

// group is the section an item is listed under: a section of the user's own by its id, a built-in
// group by its kind.
func (i noteItem) group() string {
	if i.Kind == "section" {
		return i.Section
	}
	return i.Kind
}

type memoDoc struct {
	Text      string `json:"text"`
	UpdatedAt string `json:"updatedAt"`
}

// builtIns are the built-in groups in the app's order, with the app's English names, which head
// a group the user did not rename (docs/recly.md §8 "Organizing" — Settings).
var builtIns = []struct{ kind, name string }{
	{"point", "Key points"}, {"decision", "Decisions"}, {"todo", "To-dos"}, {"question", "Questions"},
}

// parseNotes reads a summary file; one that does not parse, still being written, counts as absent.
func parseNotes(b []byte) *notesDoc {
	if b == nil {
		return nil
	}
	var d notesDoc
	if json.Unmarshal(b, &d) != nil {
		return nil
	}
	return &d
}

// parseMemo reads the user's notes; a blank memo shows nothing in the apps and counts as absent.
func parseMemo(b []byte) *memoDoc {
	if b == nil {
		return nil
	}
	var d memoDoc
	if json.Unmarshal(b, &d) != nil || strings.TrimSpace(d.Text) == "" {
		return nil
	}
	return &d
}

// NeedsSpeakers reports whether a summary file names a to-do's owner, whose name is the
// transcript's.
func NeedsSpeakers(notes []byte) bool {
	d := parseNotes(notes)
	return d != nil && slices.ContainsFunc(d.Items, func(i noteItem) bool { return i.Owner != "" })
}

// SpeakersOf reads the speakers of a JSON transcript, with `me`.
func SpeakersOf(transcript []byte) []Speaker {
	var d struct {
		Speakers []struct {
			ID   string  `json:"id"`
			Name *string `json:"name"`
			Me   bool    `json:"me"`
		} `json:"speakers"`
	}
	_ = json.Unmarshal(transcript, &d)
	var out []Speaker
	for _, s := range d.Speakers {
		out = append(out, Speaker{ID: s.ID, Name: s.Name, Me: s.Me})
	}
	return out
}

// summaryAnswer is get_summary's answer. The field names say who wrote what: aiSummary is the
// model's, userNotes the user's own words.
type summaryAnswer struct {
	RecordingID string     `json:"recordingId"`
	Title       *string    `json:"title"`
	StartedAt   string     `json:"startedAt"`
	AISummary   *aiSummary `json:"aiSummary,omitempty"`
	UserNotes   *userNotes `json:"userNotes,omitempty"`
}

type aiSummary struct {
	Overview             string           `json:"overview,omitempty"`
	OverviewEditedByUser bool             `json:"overviewEditedByUser,omitempty"`
	Sections             []summarySection `json:"sections"`
	Model                string           `json:"model,omitempty"`
	UpdatedAt            string           `json:"updatedAt,omitempty"`
	// Truncated says the items latest in the recording did not fit in the answer (fitSummary).
	Truncated bool `json:"truncated,omitempty"`
}

type summarySection struct {
	ID          string        `json:"id"`
	Kind        string        `json:"kind"`
	Name        string        `json:"name"`
	Description string        `json:"description,omitempty"`
	Items       []summaryItem `json:"items"`
}

type summaryItem struct {
	Text      string `json:"text"`
	Owner     string `json:"owner,omitempty"`
	OwnerName string `json:"ownerName,omitempty"`
	// OwnerIsUser marks a to-do of the person who made the recording, the speaker the transcript
	// marks `me`: the app shows them as "Me", which an agent could take for itself.
	OwnerIsUser  bool    `json:"ownerIsUser,omitempty"`
	Due          string  `json:"due,omitempty"`
	AtSec        float64 `json:"atSec"`
	Clock        string  `json:"clock"`
	EditedByUser bool    `json:"editedByUser,omitempty"`
}

type userNotes struct {
	Text      string `json:"text"`
	UpdatedAt string `json:"updatedAt,omitempty"`
}

// renderSummary lists the summary's items under their sections in the order the notes were made
// with, each section in the order of the recording, and leaves out a section without items.
func renderSummary(f *SummaryFiles) summaryAnswer {
	out := summaryAnswer{RecordingID: f.RecordingID, Title: f.Title, StartedAt: f.StartedAt}
	if m := parseMemo(f.Memo); m != nil {
		out.UserNotes = &userNotes{Text: m.Text, UpdatedAt: m.UpdatedAt}
	}
	d := parseNotes(f.Notes)
	if d == nil {
		return out
	}
	names := map[string]string{}
	me := map[string]bool{}
	for _, s := range f.Speakers {
		if s.Name != nil && strings.TrimSpace(*s.Name) != "" {
			names[s.ID] = strings.TrimSpace(*s.Name)
		}
		me[s.ID] = s.Me
	}
	// The sections the notes were made with, then any group an item names that they do not.
	var sections []summarySection
	index := map[string]int{}
	add := func(id, kind, name, description string) {
		if _, ok := index[id]; ok || id == "" {
			return
		}
		if kind != "section" && strings.TrimSpace(name) == "" {
			for _, b := range builtIns {
				if b.kind == kind {
					name = b.name
				}
			}
		}
		index[id] = len(sections)
		sections = append(sections, summarySection{ID: id, Kind: kind, Name: name, Description: description})
	}
	for _, s := range d.Sections {
		add(s.ID, s.Kind, s.Name, s.Description)
	}
	for _, b := range builtIns {
		add(b.kind, b.kind, "", "")
	}
	for _, it := range d.Items {
		add(it.group(), it.Kind, "", "")
	}
	for _, it := range d.Items {
		i, ok := index[it.group()]
		if !ok {
			continue
		}
		item := summaryItem{
			Text: it.words(), Owner: it.Owner, OwnerName: names[it.Owner], OwnerIsUser: it.Owner != "" && me[it.Owner], Due: it.Due,
			AtSec: it.Evidence.AtSec, Clock: Clock(it.Evidence.AtSec), EditedByUser: it.EditedAt != "",
		}
		sections[i].Items = append(sections[i].Items, item)
	}
	ai := &aiSummary{
		Overview: d.Overview, OverviewEditedByUser: d.Overview != "" && d.OverviewEditedAt != "",
		Sections: []summarySection{}, Model: d.ModelName, UpdatedAt: d.UpdatedAt,
	}
	for _, s := range sections {
		if len(s.Items) == 0 {
			continue
		}
		slices.SortStableFunc(s.Items, func(a, b summaryItem) int {
			switch {
			case a.AtSec < b.AtSec:
				return -1
			case a.AtSec > b.AtSec:
				return 1
			}
			return 0
		})
		ai.Sections = append(ai.Sections, s)
	}
	out.AISummary = ai
	return out
}

// fitSummary keeps a get_summary answer within PageBytes, as search_recordings is kept: when the
// items do not all fit, the latest in the recording are left out, from every section alike, and
// aiSummary.truncated says so. The answer then covers the recording up to its last item's time,
// and get_transcript has the rest. The overview and the user's notes are always given; they are a
// few kilobytes at most.
func fitSummary(out summaryAnswer) summaryAnswer {
	if out.AISummary == nil || resultBytes(out) <= PageBytes {
		return out
	}
	full := *out.AISummary
	type ref struct{ section, item int }
	var order []ref
	for s, sec := range full.Sections {
		for i := range sec.Items {
			order = append(order, ref{s, i})
		}
	}
	// Earliest first; items at the same time keep the order they are shown in.
	slices.SortStableFunc(order, func(a, b ref) int {
		return cmp.Compare(full.Sections[a.section].Items[a.item].AtSec, full.Sections[b.section].Items[b.item].AtSec)
	})
	keep := func(n int) summaryAnswer {
		kept := map[ref]bool{}
		for _, r := range order[:n] {
			kept[r] = true
		}
		ai := full
		ai.Sections, ai.Truncated = []summarySection{}, true
		for s, sec := range full.Sections {
			var items []summaryItem
			for i, it := range sec.Items {
				if kept[ref{s, i}] {
					items = append(items, it)
				}
			}
			if len(items) > 0 {
				sec.Items = items
				ai.Sections = append(ai.Sections, sec)
			}
		}
		fitted := out
		fitted.AISummary = &ai
		return fitted
	}
	// The answer grows with every item kept: the first count that does not fit, less one.
	n := sort.Search(len(order), func(n int) bool { return resultBytes(keep(n)) > PageBytes })
	return keep(max(n-1, 0))
}

// summaryText is one searchable text of a summary: the overview, without a time, or an item.
type summaryText struct {
	AtSec *float64
	Text  string
}

func summaryTexts(notes []byte) []summaryText {
	d := parseNotes(notes)
	if d == nil {
		return nil
	}
	var out []summaryText
	if strings.TrimSpace(d.Overview) != "" {
		out = append(out, summaryText{Text: d.Overview})
	}
	for _, it := range d.Items {
		at := it.Evidence.AtSec
		out = append(out, summaryText{AtSec: &at, Text: it.words()})
	}
	return out
}

// memoLines are the user's notes, line by line.
func memoLines(memo []byte) []string {
	d := parseMemo(memo)
	if d == nil {
		return nil
	}
	var out []string
	for _, l := range strings.Split(strings.ReplaceAll(d.Text, "\r\n", "\n"), "\n") {
		if strings.TrimSpace(l) != "" {
			out = append(out, l)
		}
	}
	return out
}
