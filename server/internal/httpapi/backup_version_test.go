package httpapi

import (
	"database/sql"
	"net/http"
	"reflect"
	"strconv"
	"testing"

	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// 退役版本须在TXT/作者/标签/偏好写入及失效通知前拒绝。
// 既有历史事件身份与旧批次元信息必须保留，不能借退役清理数据。
func TestBackupUnsupportedVersionsRejectBeforeAnyWrite(t *testing.T) {
	for _, version := range []int{-1, 0, 1, 3} {
		t.Run(strconv.Itoa(version), func(t *testing.T) {
			e := newTestEnv(t)
			asset := queryAssetID(t, e, "a.jpg")
			if _, err := e.q.InsertViewEventIdempotent(t.Context(), db.InsertViewEventIdempotentParams{
				AssetID: asset, Kind: "open", SessionID: "legacy-import",
				StartedAt:     store.FormatTimestamp(e.clock.Now()),
				ClientEventID: sql.NullString{String: "legacy:historic", Valid: true},
			}); err != nil {
				t.Fatal(err)
			}
			if err := e.q.UpsertSetting(t.Context(), db.UpsertSettingParams{Key: "legacy_import_batch", Value: "historic", UpdatedAt: store.FormatTimestamp(e.clock.Now())}); err != nil {
				t.Fatal(err)
			}
			before := portablePayload(t, e)
			revision, err := e.s.rev.Get(t.Context())
			if err != nil {
				t.Fatal(err)
			}
			req := newBackupReq(gen.LegacyBackupData{
				Authors:      &[]gen.LegacyAuthor{{AuthorId: "must-not-write", DisplayName: "synthetic"}},
				Tags:         &[]gen.LegacyTag{{Name: "must-not-write"}},
				TxtFragments: &[]gen.LegacyTxtFragment{{Filename: "reject.txt", Content: "1  reject\nwork\na.jpg\n", ImportedAtMillis: 1}},
				AppPrefs:     &gen.LegacyAppPrefs{RecommendationPrefs: &gen.RecommendPrefs{MaxRandom: ptr(0.91)}},
			})
			req.SchemaVersion = version
			code, _ := importBackup(t, e, req)
			if code != http.StatusBadRequest {
				t.Fatalf("version %d accepted: %d", version, code)
			}
			if after := portablePayload(t, e); !reflect.DeepEqual(before, after) {
				t.Fatal("rejected backup changed persisted data")
			}
			after, err := e.s.rev.Get(t.Context())
			if err != nil || after != revision {
				t.Fatal("rejected backup invalidated library", after, err)
			}
			batch, err := e.q.GetSetting(t.Context(), "legacy_import_batch")
			if err != nil || batch != "historic" {
				t.Fatal("historical batch value was changed", batch, err)
			}
		})
	}
}
