package store

import "testing"

// 从 0018 的真实历史无键事件升级，保留原文并建立稳定身份；新无键写入同样覆盖。
func TestPortableEventIdentityMigrationPreservesHistory(t *testing.T) {
	conn, _ := openTestDB(t)
	if err := MigrateDown(conn, 1); err != nil {
		t.Fatal(err)
	}
	if _, err := conn.Exec(`INSERT INTO view_events(asset_id,kind,session_id,started_at,seconds) VALUES ('synthetic-asset','dwell','synthetic-session','2026-10-19T00:00:00.000Z',17)`); err != nil {
		t.Fatal(err)
	}
	if err := Migrate(conn); err != nil {
		t.Fatal(err)
	}
	var key string
	var secs int
	var isNull bool
	if err := conn.QueryRow(`SELECT p.stable_id,v.seconds,v.client_event_id IS NULL FROM view_events v JOIN portable_event_identity p ON v.id=p.event_id`).Scan(&key, &secs, &isNull); err != nil {
		t.Fatal(err)
	}
	if key == "" || secs != 17 || !isNull {
		t.Fatal("migration changed historical truth")
	}
	if err := Migrate(conn); err != nil {
		t.Fatal(err)
	}
	var again string
	if err := conn.QueryRow(`SELECT stable_id FROM portable_event_identity`).Scan(&again); err != nil {
		t.Fatal(err)
	}
	if again != key {
		t.Fatal("identity changed on restart")
	}
	if _, err := conn.Exec(`INSERT INTO view_events(asset_id,kind,session_id,started_at) VALUES ('synthetic-asset','open','new-session','2026-10-19T00:00:00.000Z')`); err != nil {
		t.Fatal(err)
	}
	var count int
	if err := conn.QueryRow(`SELECT COUNT(DISTINCT stable_id) FROM portable_event_identity`).Scan(&count); err != nil {
		t.Fatal(err)
	}
	if count != 2 {
		t.Fatal("trigger did not allocate independent identity")
	}
}
