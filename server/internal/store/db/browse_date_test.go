package db

import (
	"context"
	"database/sql"
	"fmt"
	"slices"
	"strings"
	"testing"
)

func seedFileDateEquivalence(t *testing.T) *sql.DB {
	t.Helper()
	conn := openMigratedDB(t)
	statements := []string{
		`INSERT INTO libraries(id,name,root_path,created_at,enabled) VALUES('a','A','/synthetic-a','2026-01-01',1),('b','B','/synthetic-b','2026-01-01',1),('off','Off','/synthetic-off','2026-01-01',0)`,
		`INSERT INTO authors(id,display_name,type,created_at) VALUES('cos','COS','cos','2026-01-01'),('regular','Regular','regular','2026-01-01')`,
		`WITH RECURSIVE n(x) AS (VALUES(1) UNION ALL SELECT x+1 FROM n WHERE x<96) INSERT INTO assets(asset_id,library_id,rel_path,file_name,media_type,size_bytes,mtime,duration_ms,source,created_at,updated_at) SELECT printf('x%03d',x),CASE x%3 WHEN 0 THEN 'a' WHEN 1 THEN 'b' ELSE 'off' END,x||'.jpg',x||'.jpg','image',x,CASE WHEN x=1 THEN '' ELSE printf('2026-01-%02dT00:00:00.000Z',x%4+1) END,CASE WHEN x%2=0 THEN 1000 ELSE NULL END,CASE WHEN x%2=0 THEN 'synthetic-source' ELSE NULL END,'2026-01-01','2026-01-01' FROM n`,
		`INSERT INTO asset_authors(asset_id,author_id) SELECT asset_id,'cos' FROM assets WHERE CAST(substr(asset_id,2) AS INTEGER)%5=0`,
		`INSERT INTO asset_authors(asset_id,author_id) SELECT asset_id,'regular' FROM assets WHERE CAST(substr(asset_id,2) AS INTEGER)%2=0`,
		`INSERT INTO favorites(asset_id,created_at) SELECT asset_id,'2026-01-01' FROM assets WHERE CAST(substr(asset_id,2) AS INTEGER)%3=0`,
		`INSERT INTO likes(asset_id,day,created_at) SELECT asset_id,'2026-01-01','2026-01-01' FROM assets WHERE CAST(substr(asset_id,2) AS INTEGER)%2=0`,
		`INSERT INTO likes(asset_id,day,created_at) SELECT asset_id,'2026-01-02','2026-01-02' FROM assets WHERE CAST(substr(asset_id,2) AS INTEGER)%4=0`,
	}
	for _, statement := range statements {
		if _, err := conn.Exec(statement); err != nil {
			t.Fatal(err)
		}
	}
	return conn
}

// Compare every page, not just IDs on the first page: timestamp ties, mixed
// normal/COS associations, disabled/missing libraries and enriched fields.
func TestFileDatePageMatchesGeneric(t *testing.T) {
	q := New(seedFileDateEquivalence(t))
	for _, asc := range []bool{false, true} {
		for _, library := range []string{"", "a", "b", "off", "missing"} {
			for _, partition := range []struct {
				name     string
				all, cos int64
			}{{"all", 1, 0}, {"regular", 0, 0}, {"cos", 0, 1}} {
				t.Run(fmt.Sprintf("asc=%v/lib=%s/%s", asc, library, partition.name), func(t *testing.T) {
					p := ListAssetsFileDateDescParams{RowLimit: 7, IncludeCos: partition.all, CosOnly: partition.cos}
					if library != "" {
						p.LibraryID = library
					}
					seen := map[string]bool{}
					for page := 0; page < 20; page++ {
						want := genericDatePage(t, q, asc, p)
						actual, err := q.ListAssetsFileDateDesc(context.Background(), p)
						if asc {
							var rows []ListAssetsFileDateAscRow
							rows, err = q.ListAssetsFileDateAsc(context.Background(), ListAssetsFileDateAscParams(p))
							actual = make([]ListAssetsFileDateDescRow, len(rows))
							for i, row := range rows {
								actual[i] = ListAssetsFileDateDescRow(row)
							}
						}
						if err != nil {
							t.Fatal(err)
						}
						if !slices.Equal(actual, want) {
							t.Fatalf("page %d:\nactual=%+v\nwant=%+v", page, actual, want)
						}
						for _, row := range actual {
							if seen[row.AssetID] {
								t.Fatal("重复资产", row.AssetID)
							}
							seen[row.AssetID] = true
						}
						if len(actual) < int(p.RowLimit) {
							return
						}
						last := actual[len(actual)-1]
						p.CursorKey, p.CursorID = last.Mtime, sql.NullString{String: last.AssetID, Valid: true}
					}
					t.Fatal("分页未终止")
				})
			}
		}
	}
}

func genericDatePage(t *testing.T, q *Queries, asc bool, p ListAssetsFileDateDescParams) []ListAssetsFileDateDescRow {
	t.Helper()
	params := ListAssetsFilteredDescParams{Sort: "fileDate", LibraryID: p.LibraryID, IncludeCos: p.IncludeCos,
		CosOnly: p.CosOnly, SourceIsOther: int64(0), CursorKey: p.CursorKey, CursorID: p.CursorID, RowLimit: p.RowLimit}
	raw, err := q.ListAssetsFilteredDesc(context.Background(), params)
	if asc {
		var rows []ListAssetsFilteredAscRow
		rows, err = q.ListAssetsFilteredAsc(context.Background(), ListAssetsFilteredAscParams(params))
		raw = make([]ListAssetsFilteredDescRow, len(rows))
		for i, row := range rows {
			raw[i] = ListAssetsFilteredDescRow(row)
		}
	}
	if err != nil {
		t.Fatal(err)
	}
	result := make([]ListAssetsFileDateDescRow, len(raw))
	for i, row := range raw {
		result[i] = ListAssetsFileDateDescRow{AssetID: row.AssetID, FileName: row.FileName, MediaType: row.MediaType,
			SizeBytes: row.SizeBytes, Mtime: row.Mtime, DurationMs: row.DurationMs, Source: row.Source,
			CreatedAt: row.CreatedAt, IsFavorite: row.IsFavorite, LikeCount: row.LikeCount}
	}
	return result
}

func TestFileDatePageUsesIndexBeforeEnrichment(t *testing.T) {
	conn := openMigratedDB(t)
	for _, query := range []string{listAssetsFileDateAsc, listAssetsFileDateDesc} {
		for _, cursor := range []any{nil, "2026-01-02T00:00:00.000Z"} {
			plan := explainPlan(t, conn, query, int64(61), cursor, nil, int64(1), int64(0), sql.NullString{String: "x", Valid: cursor != nil})
			assertPlanShape(t, plan, []string{"CO-ROUTINE page", "MERGE (UNION ALL)", "SCAN a USING INDEX idx_assets_mtime", "SEARCH a USING INDEX idx_assets_mtime"}, nil)
			joined := strings.Join(plan, "\n")
			sortAt := strings.Index(joined, "USE TEMP B-TREE")
			if strings.Count(joined, "USE TEMP B-TREE") > 1 || (sortAt >= 0 && sortAt < strings.Index(joined, "SCAN page")) {
				t.Fatalf("候选应由索引排序，临时排序只能发生在限页之后: %v", plan)
			}
		}
	}
}

func TestGenericBrowseEnrichesOnlySelectedPage(t *testing.T) {
	conn := openMigratedDB(t)
	args := make([]any, 26)
	args[0], args[4], args[5], args[6], args[24], args[25] = "fileDate", int64(0), int64(1), int64(0), sql.NullString{}, int64(61)
	for _, query := range []string{listAssetsFilteredAsc, listAssetsFilteredDesc} {
		plan := explainPlan(t, conn, query, args...)
		joined := strings.Join(plan, "\n")
		assertPlanShape(t, plan, []string{"CO-ROUTINE page", "SCAN page"}, nil)
		page := strings.Index(joined, "SCAN page")
		for _, enrichment := range []string{"SEARCH fv USING", "SEARCH l USING"} {
			if strings.Index(joined, enrichment) < page {
				t.Fatalf("富化发生在限页之前: %v", plan)
			}
		}
	}
}
