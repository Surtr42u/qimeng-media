package db

import (
	"context"
	"database/sql"
	"fmt"
	"testing"
)

// 共享作品归属于多位作者；无作品作者保留，play/dwell 和无关联事件不计。
func TestListAuthorsCountsSharedAssets(t *testing.T) {
	conn := openMigratedDB(t)
	statements := []string{
		`INSERT INTO libraries(id,name,root_path,created_at) VALUES('lib','synthetic','/synthetic','2026-01-01')`,
		`INSERT INTO authors(id,display_name,type,followed,created_at) VALUES('a','A','regular',1,'2026-01-01'),('b','B','regular',0,'2026-01-01'),('c','C','regular',0,'2026-01-01')`,
		`INSERT INTO assets(asset_id,library_id,rel_path,file_name,media_type,size_bytes,mtime,created_at,updated_at) VALUES('x','lib','x.jpg','x.jpg','image',1,'2026-01-01','2026-01-01','2026-01-01'),('y','lib','y.jpg','y.jpg','image',1,'2026-01-01','2026-01-01','2026-01-01')`,
		`INSERT INTO asset_authors(asset_id,author_id) VALUES('x','a'),('y','a'),('x','b')`,
		`INSERT INTO view_events(asset_id,kind,session_id,started_at) VALUES('x','open','s1','2026-01-01'),('x','open','s2','2026-01-02'),('y','open','s3','2026-01-01'),('x','play','s4','2026-01-01'),('missing','open','s5','2026-01-01')`,
	}
	for _, statement := range statements {
		if _, err := conn.Exec(statement); err != nil {
			t.Fatal(err)
		}
	}
	rows, err := New(conn).ListAuthors(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	want := []struct {
		id    string
		files int64
		opens string
	}{{"a", 2, "3"}, {"b", 1, "2"}, {"c", 0, "0"}}
	if len(rows) != len(want) {
		t.Fatalf("rows=%d", len(rows))
	}
	for i, row := range rows {
		if row.ID != want[i].id || row.FileCount != want[i].files || fmt.Sprint(row.ViewCount) != want[i].opens {
			t.Fatalf("row=%+v want=%+v", row, want[i])
		}
	}
}

func TestListAuthorsPlanPreaggregatesAssets(t *testing.T) {
	conn := openMigratedDB(t)
	plan := explainPlan(t, conn, listAuthors)
	t.Logf("plan: %v", plan)
	assertPlanShape(t, plan, []string{"MATERIALIZE asset_views"}, []string{"CORRELATED SCALAR SUBQUERY"})
}

// 固定合成负载，比较同口径的旧相关子查询与按资产预聚合；不设机器相关耗时断言。
func BenchmarkListAuthorsSharedWorks(b *testing.B) {
	conn := seedSharedAuthorWorks(b)
	const previous = `SELECT au.id,au.display_name,au.type,au.followed,COUNT(aa.asset_id),COALESCE(SUM(CASE WHEN aa.asset_id IS NULL THEN 0 ELSE (SELECT COUNT(*) FROM view_events ve WHERE ve.asset_id=aa.asset_id AND ve.kind='open') END),0) FROM authors au LEFT JOIN asset_authors aa ON aa.author_id=au.id GROUP BY au.id ORDER BY au.display_name`
	benchmarkAuthorListQueries(b, conn, previous)
}

func seedSharedAuthorWorks(b testing.TB) *sql.DB {
	b.Helper()
	conn := openMigratedDB(b)
	statements := []string{
		`INSERT INTO libraries(id,name,root_path,created_at) VALUES('bench','synthetic','/synthetic-bench','2026-01-01')`,
		`WITH RECURSIVE n(x) AS (VALUES(1) UNION ALL SELECT x+1 FROM n WHERE x<150) INSERT INTO authors(id,display_name,type,followed,created_at) SELECT 'a'||x,'Author '||x,'regular',0,'2026-01-01' FROM n`,
		`WITH RECURSIVE n(x) AS (VALUES(1) UNION ALL SELECT x+1 FROM n WHERE x<500) INSERT INTO assets(asset_id,library_id,rel_path,file_name,media_type,size_bytes,mtime,created_at,updated_at) SELECT 'x'||x,'bench',x||'.jpg',x||'.jpg','image',1,'2026-01-01','2026-01-01','2026-01-01' FROM n`,
		`INSERT INTO asset_authors(asset_id,author_id) SELECT a.asset_id,au.id FROM assets a CROSS JOIN authors au WHERE CAST(substr(a.asset_id,2) AS INTEGER)%5=CAST(substr(au.id,2) AS INTEGER)%5`,
		`WITH RECURSIVE n(x) AS (VALUES(1) UNION ALL SELECT x+1 FROM n WHERE x<50000) INSERT INTO view_events(asset_id,kind,session_id,started_at) SELECT 'x'||(x%500+1),'open','s'||x,'2026-01-01T00:00:00.000Z' FROM n`,
	}
	for _, statement := range statements {
		if _, err := conn.Exec(statement); err != nil {
			b.Fatal(err)
		}
	}
	return conn
}

func benchmarkAuthorListQueries(b *testing.B, conn *sql.DB, previous string) {
	for _, candidate := range []struct{ name, query string }{{"previous", previous}, {"preaggregated", listAuthors}} {
		b.Run(candidate.name, func(b *testing.B) {
			b.ReportAllocs()
			for i := 0; i < b.N; i++ {
				rows, err := conn.Query(candidate.query)
				if err != nil {
					b.Fatal(err)
				}
				count := 0
				for rows.Next() {
					count++
				}
				if err := rows.Err(); err != nil {
					b.Fatal(err)
				}
				if err := rows.Close(); err != nil {
					b.Fatal(err)
				}
				if count != 150 {
					b.Fatalf("authors=%d", count)
				}
			}
		})
	}
}
