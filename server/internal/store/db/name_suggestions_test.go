package db

import (
	"context"
	"reflect"
	"testing"
)

func TestSuggestionFileNameProjectionMatchesReconciliation(t *testing.T) {
	conn := seedFileDateEquivalence(t)
	q := New(conn)
	for _, library := range []string{"a", "b", "off", "missing"} {
		rows, err := q.ListAssetsByLibrary(context.Background(), library)
		if err != nil {
			t.Fatal(err)
		}
		want := make([]string, 0, len(rows))
		for _, row := range rows {
			want = append(want, row.FileName)
		}
		got, err := q.ListFileNamesForSuggestions(context.Background(), library)
		if err != nil {
			t.Fatal(err)
		}
		if len(want) != len(got) || len(got) != 0 && !reflect.DeepEqual(got, want) {
			t.Fatalf("library=%s 文件名投影范围或顺序改变", library)
		}
	}
}

func BenchmarkSuggestionFileNames(b *testing.B) {
	conn := seedRecommendWorkload(b)
	q := New(conn)
	for _, baseline := range []bool{true, false} {
		name := "filename_only"
		if baseline {
			name = "full_rows"
		}
		b.Run(name, func(b *testing.B) {
			b.ReportAllocs()
			for i := 0; i < b.N; i++ {
				var names []string
				if baseline {
					rows, err := q.ListAssetsByLibrary(context.Background(), "recommend")
					if err != nil {
						b.Fatal(err)
					}
					names = make([]string, len(rows))
					for j := range rows {
						names[j] = rows[j].FileName
					}
				} else {
					var err error
					names, err = q.ListFileNamesForSuggestions(context.Background(), "recommend")
					if err != nil {
						b.Fatal(err)
					}
				}
				if len(names) != 20000 {
					b.Fatal("文件名缺项")
				}
			}
		})
	}
}
