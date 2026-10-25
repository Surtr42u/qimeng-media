package portablebackup

import (
	"qimeng-media/server/internal/store/db"
	"reflect"
	"testing"
)

func TestRecordKeysStableAndUniqueEvenWithLiteralSuffix(t *testing.T) {
	rows := []db.ExportListAssetsRow{
		{AssetID: "a", FileName: "same.jpg", RelPath: "folder/same.jpg"},
		{AssetID: "b", FileName: "same.jpg", RelPath: "folder/same.jpg"},
		{AssetID: "c", FileName: "same.jpg", RelPath: "folder/same.jpg"},
		{AssetID: "d", FileName: "same.jpg @ folder#b#1", RelPath: "same.jpg @ folder#b#1"},
	}
	keys := func(rows []db.ExportListAssetsRow) map[string]string {
		seen := map[string]bool{}
		result := map[string]string{}
		for _, a := range assignRecordKeys(rows) {
			if seen[a.recordKey] {
				t.Fatal("collision")
			}
			seen[a.recordKey] = true
			result[a.row.AssetID] = a.recordKey
		}
		return result
	}
	want := keys(rows)
	for i, j := 0, len(rows)-1; i < j; i, j = i+1, j-1 {
		rows[i], rows[j] = rows[j], rows[i]
	}
	if !reflect.DeepEqual(want, keys(rows)) {
		t.Fatal("input ordering changed keys")
	}
}
