package httpapi

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"reflect"
	"testing"

	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/portablebackup"
)

// 文件 DTO 不是生成 SDK；协议修改须同步文件格式，并在边界逐字段核对可选性。
func TestPortableFileModelsMatchProtocol(t *testing.T) {
	var compare func(reflect.Type, reflect.Type, string)
	seen := map[[2]reflect.Type]bool{}
	compare = func(a, b reflect.Type, path string) {
		t.Helper()
		pair := [2]reflect.Type{a, b}
		if seen[pair] {
			return
		}
		seen[pair] = true
		if a.Kind() != b.Kind() {
			t.Fatalf("%s kind %v != %v", path, a, b)
		}
		switch a.Kind() {
		case reflect.Pointer, reflect.Slice:
			compare(a.Elem(), b.Elem(), path+"[]")
		case reflect.Map:
			compare(a.Key(), b.Key(), path+" key")
			compare(a.Elem(), b.Elem(), path+" value")
		case reflect.Struct:
			if a.NumField() != b.NumField() {
				t.Fatalf("%s fields %v != %v", path, a, b)
			}
			for i := range a.NumField() {
				af, bf := a.Field(i), b.Field(i)
				if af.Name != bf.Name || af.Tag.Get("json") != bf.Tag.Get("json") {
					t.Fatalf("%s field %v != %v", path, af, bf)
				}
				compare(af.Type, bf.Type, path+"."+af.Name)
			}
		}
	}
	compare(reflect.TypeFor[portablebackup.BackupImport](), reflect.TypeFor[gen.LegacyBackupImport](), "import")
	compare(reflect.TypeFor[portablebackup.BackupFile](), reflect.TypeFor[gen.LegacyBackupFile](), "export")
	compare(reflect.TypeFor[portablebackup.ImportResult](), reflect.TypeFor[gen.LegacyImportResult](), "result")
	for _, raw := range []string{`{}`, `null`, `{"version":1,"recommendationPrefs":{"recency":0.5},"unknown":{"x":[1,null]}}`} {
		var domain portablebackup.AppPrefs
		var protocol gen.LegacyAppPrefs
		if err := json.Unmarshal([]byte(raw), &domain); err != nil {
			t.Fatal(err)
		}
		if err := json.Unmarshal([]byte(raw), &protocol); err != nil {
			t.Fatal(err)
		}
		d, err := json.Marshal(domain)
		if err != nil {
			t.Fatal(err)
		}
		p, err := json.Marshal(protocol)
		if err != nil {
			t.Fatal(err)
		}
		if string(d) != string(p) {
			t.Fatalf("open prefs changed: %s != %s", d, p)
		}
	}
}

func TestPortablePartialCommitNotifiesAndRetryKeepsIdentity(t *testing.T) {
	e := newTestEnv(t)
	before, err := e.s.rev.Get(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	if _, err := e.conn.Exec(`CREATE TRIGGER fail_portable_tag BEFORE INSERT ON tags BEGIN SELECT RAISE(ABORT,'synthetic tag failure'); END`); err != nil {
		t.Fatal(err)
	}
	req := gen.LegacyBackupImport{Format: portablebackup.Format, SchemaVersion: portablebackup.SchemaVersion, Data: gen.LegacyBackupData{
		ViewEvents: &[]gen.PortableViewEvent{}, Authors: &[]gen.LegacyAuthor{{AuthorId: "portable-partial", DisplayName: "synthetic"}}, Tags: &[]gen.LegacyTag{{Name: "synthetic-partial", TagId: ptr(int64(42))}},
	}}
	raw, err := json.Marshal(req)
	if err != nil {
		t.Fatal(err)
	}
	response := e.do(t, http.MethodPost, "/api/v1/import/qimeng-backup", string(raw))
	closeBody(response)
	code := response.StatusCode
	if code != http.StatusInternalServerError {
		t.Fatal(code)
	}
	var authors int
	if err := e.conn.QueryRow(`SELECT COUNT(*) FROM authors WHERE id='portable-partial'`).Scan(&authors); err != nil || authors != 1 {
		t.Fatal("committed author lost", authors, err)
	}
	after, err := e.s.rev.Get(t.Context())
	if err != nil || after <= before {
		t.Fatal("partial commit failed to invalidate", after, before, err)
	}
	if _, err := e.conn.Exec(`DROP TRIGGER fail_portable_tag`); err != nil {
		t.Fatal(err)
	}
	for range 2 {
		code, _ = importBackup(t, e, req)
		if code != http.StatusOK {
			t.Fatal(code)
		}
	}
	if err := e.conn.QueryRow(`SELECT COUNT(*) FROM authors WHERE id='portable-partial'`).Scan(&authors); err != nil || authors != 1 {
		t.Fatal("retry duplicated author", authors, err)
	}
}

func TestPortableCanceledBeforeWritesDoesNotNotify(t *testing.T) {
	e := newTestEnv(t)
	before, err := e.s.rev.Get(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(t.Context())
	cancel()
	_, err = e.s.portable.Import(ctx, &portablebackup.BackupImport{Format: portablebackup.Format, SchemaVersion: portablebackup.SchemaVersion, Data: portablebackup.BackupData{ViewEvents: &[]portablebackup.ViewEvent{}}})
	if !errors.Is(err, context.Canceled) {
		t.Fatal("cancellation lost", err)
	}
	after, err := e.s.rev.Get(t.Context())
	if err != nil || after != before {
		t.Fatal("uncommitted cancellation notified", after, before, err)
	}
}
