package authorattach

// 上传条目元数据（重导入保护）读写测试。

import (
	"context"
	"reflect"
	"testing"

	"qimeng-media/server/internal/authoring"
)

func TestUploadEntriesNoRecord(t *testing.T) {
	q := newTestDB(t)
	m, err := LoadUploadEntries(context.Background(), q)
	if err != nil {
		t.Fatalf("读取上传条目失败: %v", err)
	}
	if m == nil {
		t.Error("无记录应返回空 map 非 nil（调用方直接下标读写）")
	}
	if len(m) != 0 {
		t.Errorf("无记录 m=%v, want 空", m)
	}
}

func TestUploadEntriesRoundtrip(t *testing.T) {
	q := newTestDB(t)
	ctx := context.Background()
	want := map[string][]authoring.UploadEntry{
		"上传自动挂靠.txt": {
			{AuthorID: "night", DisplayName: "Night / Cry", Names: []string{"Night", "Cry"},
				Works: []string{"a.png"}, Sources: []string{"site-a"}},
			// Names 为空的旧数据形态（字段引入前落库）：缺省 nil 不报错。
			{AuthorID: "bamhor", DisplayName: "bamhor", Works: []string{"b.png"}},
		},
	}
	if err := SaveUploadEntries(ctx, q, testNow, want); err != nil {
		t.Fatalf("写上传条目失败: %v", err)
	}
	got, err := LoadUploadEntries(ctx, q)
	if err != nil {
		t.Fatalf("读上传条目失败: %v", err)
	}
	if !reflect.DeepEqual(got, want) {
		t.Errorf("roundtrip=%+v, want %+v", got, want)
	}
}
