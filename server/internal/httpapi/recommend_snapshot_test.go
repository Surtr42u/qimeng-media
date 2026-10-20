package httpapi

import (
	"net/http"
	"testing"
	"time"

	"qimeng-media/server/internal/events"
	"qimeng-media/server/internal/httpapi/gen"
)

func TestRecommendationSnapshotRechecksPage(t *testing.T) {
	for _, scenario := range []string{"delete", "disable", "media", "cos", "metadata"} {
		t.Run(scenario, func(t *testing.T) {
			env := newTestEnv(t)
			query := "?seed=42&mediaType=image"
			initial := recList(t, env, query)
			if len(initial) != 2 {
				t.Fatal("expected image candidates")
			}
			id := *initial[0].Id
			// 直接修改临时夹具，不发失效事件：模拟排序后到页摘要查询间的并发变更。
			var statement string
			args := []any{id}
			switch scenario {
			case "delete":
				statement = "DELETE FROM assets WHERE asset_id=?"
			case "disable":
				statement = "UPDATE libraries SET enabled=0 WHERE id=?"
				args = []any{env.libID}
			case "media":
				statement = "UPDATE assets SET media_type='video' WHERE asset_id=?"
			case "cos":
				if _, err := env.conn.Exec("INSERT INTO authors(id,display_name,type,created_at) VALUES('cos_snapshot','snapshot','cos','2026-10-20')"); err != nil {
					t.Fatal(err)
				}
				statement = "INSERT INTO asset_authors(asset_id,author_id) VALUES(?,'cos_snapshot')"
			case "metadata":
				statement = "UPDATE assets SET file_name='fresh.jpg' WHERE asset_id=?"
			}
			if _, err := env.conn.Exec(statement, args...); err != nil {
				t.Fatal(err)
			}
			page := recList(t, env, query)
			if scenario == "disable" {
				if len(page) != 0 {
					t.Fatal("disabled library leaked")
				}
				return
			}
			for _, row := range page {
				if row.Id == nil || *row.Id != id {
					continue
				}
				if scenario != "metadata" {
					t.Fatalf("ineligible asset leaked: %s", scenario)
				}
				if row.FileName == nil || *row.FileName != "fresh.jpg" {
					t.Fatal("stale metadata")
				}
			}
			if scenario == "metadata" && len(page) != len(initial) {
				t.Fatal("metadata update changed candidates")
			}
			if scenario != "metadata" && len(page) != len(initial)-1 {
				t.Fatal("expected ineligible candidate removed")
			}
		})
	}
}

func TestImportPublishesContentChangeOnlyOnSuccess(t *testing.T) {
	env := newTestEnv(t)
	sub, err := env.s.bus.Subscribe(events.TopicLibraryChanged)
	if err != nil {
		t.Fatal(err)
	}
	defer sub.Close()
	status, _ := importBackup(t, env, gen.LegacyBackupImport{Format: "wrong", Data: gen.LegacyBackupData{}})
	if status != http.StatusBadRequest {
		t.Fatal("invalid import accepted")
	}
	select {
	case <-sub.C:
		t.Fatal("invalid import published content change")
	default:
	}
	status, _ = importBackup(t, env, gen.LegacyBackupImport{Format: "qimeng_backup", SchemaVersion: 1, Data: gen.LegacyBackupData{}})
	if status != http.StatusOK {
		t.Fatal("valid import failed")
	}
	select {
	case event := <-sub.C:
		if event.Topic != events.TopicLibraryChanged {
			t.Fatal("wrong topic")
		}
	case <-time.After(time.Second):
		t.Fatal("successful import did not notify clients")
	}
}
