package deviceapply

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/google/uuid"

	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

const testFingerprint = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

type testClock struct {
	now time.Time
}

func (c *testClock) Now() time.Time          { return c.now }
func (c *testClock) Advance(d time.Duration) { c.now = c.now.Add(d) }

func newTestService(t *testing.T) (*Service, *testClock) {
	t.Helper()
	conn, err := store.Open(filepath.Join(t.TempDir(), "deviceapply-test.db"))
	if err != nil {
		t.Fatalf("打开临时库失败: %v", err)
	}
	t.Cleanup(func() { _ = conn.Close() })
	if err := store.Migrate(conn); err != nil {
		t.Fatalf("迁移临时库失败: %v", err)
	}
	clock := &testClock{now: time.Date(2026, 11, 7, 2, 0, 0, 0, time.UTC)}
	svc := New(Options{
		Conn: conn, Queries: db.New(conn),
		RootDir: filepath.Join(t.TempDir(), "device-apply-packages"),
		Now:     clock.Now,
		Logger:  slog.New(slog.NewTextHandler(io.Discard, nil)),
	})
	return svc, clock
}

func digest(data []byte) string {
	sum := sha256.Sum256(data)
	return hex.EncodeToString(sum[:])
}

func segmentSpec(kind SegmentKind, data []byte) ManifestSegment {
	return ManifestSegment{Kind: kind, Bytes: int64(len(data)), SHA256: digest(data)}
}

func buildCreateInput(t *testing.T, target string, ttl int, master, backup []byte) CreateInput {
	t.Helper()
	manifest := Manifest{
		TargetDeviceID:   target,
		ProtocolVersion:  CurrentProtocolVersion,
		RulesFingerprint: testFingerprint,
		TTLSeconds:       ttl,
	}
	segments := make(map[SegmentKind]io.Reader)
	if master != nil {
		manifest.Segments = append(manifest.Segments, segmentSpec(SegmentMasterTxt, master))
		segments[SegmentMasterTxt] = bytes.NewReader(master)
	}
	if backup != nil {
		manifest.Segments = append(manifest.Segments, segmentSpec(SegmentV2Backup, backup))
		segments[SegmentV2Backup] = bytes.NewReader(backup)
	}
	raw, err := json.Marshal(manifest)
	if err != nil {
		t.Fatalf("序列化 manifest 失败: %v", err)
	}
	return CreateInput{Manifest: raw, Segments: segments}
}

func createTask(t *testing.T, svc *Service, target string, master, backup []byte) Task {
	t.Helper()
	task, err := svc.Create(context.Background(), buildCreateInput(t, target, 0, master, backup))
	if err != nil {
		t.Fatalf("创建结果包失败: %v", err)
	}
	return task
}

func masterBytes(size int) []byte {
	data := make([]byte, size)
	for i := range data {
		data[i] = byte(i%251) + 1
	}
	return data
}

func assertRootEmpty(t *testing.T, svc *Service) {
	t.Helper()
	entries, err := os.ReadDir(svc.rootDir)
	if errors.Is(err, os.ErrNotExist) {
		return
	}
	if err != nil {
		t.Fatalf("读取结果包根目录失败: %v", err)
	}
	if len(entries) != 0 {
		t.Fatalf("失败创建残留目录: %v", entries)
	}
}

func TestCreateStoresSegmentsAndMetadata(t *testing.T) {
	svc, clock := newTestService(t)
	master := masterBytes(1024)
	backup := masterBytes(2048)
	task := createTask(t, svc, "phone-a", master, backup)

	if task.Status != StatusPending {
		t.Fatalf("初始状态 = %s，期望 pending", task.Status)
	}
	if task.ProtocolVersion != CurrentProtocolVersion || task.RulesFingerprint != testFingerprint {
		t.Fatalf("协议元数据异常: %+v", task)
	}
	if task.TargetDeviceID != "phone-a" || task.SegmentCount != 2 || len(task.Segments) != 2 {
		t.Fatalf("任务/段元数据异常: %+v", task)
	}
	wantExpiry := clock.Now().Add(DefaultTTLSeconds * time.Second)
	if !task.ExpiresAt.Equal(wantExpiry) {
		t.Fatalf("expiresAt = %v，期望 %v", task.ExpiresAt, wantExpiry)
	}
	if task.ClaimedAt != nil || task.FinishedAt != nil || task.Result != nil {
		t.Fatalf("新任务不应有领取/终态字段: %+v", task)
	}
	for _, segment := range task.Segments {
		if segment.RelPath != filepath.ToSlash(filepath.Join(task.ID, segmentFileName(segment.Kind))) {
			t.Fatalf("段相对路径异常: %s", segment.RelPath)
		}
		full := filepath.Join(svc.rootDir, filepath.FromSlash(segment.RelPath))
		data, err := os.ReadFile(full)
		if err != nil {
			t.Fatalf("读取段文件失败: %v", err)
		}
		if int64(len(data)) != segment.Bytes || digest(data) != segment.SHA256 {
			t.Fatalf("段文件字节/摘要与账本不符: kind=%s", segment.Kind)
		}
	}
}

func TestCreateKeepsExpectedReadback(t *testing.T) {
	svc, _ := newTestService(t)
	master := masterBytes(24)
	in := buildCreateInput(t, "phone-a", 0, master, nil)
	var manifest Manifest
	if err := json.Unmarshal(in.Manifest, &manifest); err != nil {
		t.Fatalf("解析 manifest 失败: %v", err)
	}
	manifest.Segments[0].ExpectedReadback = json.RawMessage(`{"authors":3,"nested":{"b":2,"a":1}}`)
	raw, err := json.Marshal(manifest)
	if err != nil {
		t.Fatalf("序列化 manifest 失败: %v", err)
	}
	in.Manifest = raw
	task, err := svc.Create(context.Background(), in)
	if err != nil {
		t.Fatalf("创建失败: %v", err)
	}
	if len(task.Segments) != 1 {
		t.Fatalf("段数 = %d", len(task.Segments))
	}
	want := `{"authors":3,"nested":{"a":1,"b":2}}`
	if string(task.Segments[0].ExpectedReadback) != want {
		t.Fatalf("expectedReadback = %s，期望 %s", task.Segments[0].ExpectedReadback, want)
	}
}

func TestCreateRejectsInvalidManifest(t *testing.T) {
	svc, _ := newTestService(t)
	master := masterBytes(16)
	backup := masterBytes(32)
	valid := func() Manifest {
		return Manifest{
			TargetDeviceID:   "phone-a",
			ProtocolVersion:  CurrentProtocolVersion,
			RulesFingerprint: testFingerprint,
			Segments:         []ManifestSegment{segmentSpec(SegmentMasterTxt, master)},
		}
	}
	marshal := func(manifest Manifest) []byte {
		raw, err := json.Marshal(manifest)
		if err != nil {
			t.Fatalf("序列化失败: %v", err)
		}
		return raw
	}
	cases := []struct {
		name     string
		manifest []byte
		segments map[SegmentKind]io.Reader
	}{
		{"empty", nil, map[SegmentKind]io.Reader{}},
		{"not-json", []byte("{"), map[SegmentKind]io.Reader{}},
		{"unknown-field", []byte(`{"targetDeviceId":"phone-a","protocolVersion":1,"rulesFingerprint":"` + testFingerprint + `","segments":[],"extra":1}`), map[SegmentKind]io.Reader{}},
		{"bad-target", marshal(func() Manifest { m := valid(); m.TargetDeviceID = "bad device!"; return m }()), map[SegmentKind]io.Reader{SegmentMasterTxt: bytes.NewReader(master)}},
		{"empty-target", marshal(func() Manifest { m := valid(); m.TargetDeviceID = " "; return m }()), map[SegmentKind]io.Reader{SegmentMasterTxt: bytes.NewReader(master)}},
		{"long-target", marshal(func() Manifest {
			m := valid()
			m.TargetDeviceID = string(bytes.Repeat([]byte("a"), MaxTargetDeviceIDRunes+1))
			return m
		}()), map[SegmentKind]io.Reader{SegmentMasterTxt: bytes.NewReader(master)}},
		{"protocol", marshal(func() Manifest { m := valid(); m.ProtocolVersion = 2; return m }()), map[SegmentKind]io.Reader{SegmentMasterTxt: bytes.NewReader(master)}},
		{"fingerprint", marshal(func() Manifest { m := valid(); m.RulesFingerprint = "abcd"; return m }()), map[SegmentKind]io.Reader{SegmentMasterTxt: bytes.NewReader(master)}},
		{"ttl-small", marshal(func() Manifest { m := valid(); m.TTLSeconds = MinTTLSeconds - 1; return m }()), map[SegmentKind]io.Reader{SegmentMasterTxt: bytes.NewReader(master)}},
		{"ttl-large", marshal(func() Manifest { m := valid(); m.TTLSeconds = MaxTTLSeconds + 1; return m }()), map[SegmentKind]io.Reader{SegmentMasterTxt: bytes.NewReader(master)}},
		{"no-segments", marshal(func() Manifest { m := valid(); m.Segments = nil; return m }()), map[SegmentKind]io.Reader{}},
		{"duplicate-kind", marshal(func() Manifest {
			m := valid()
			m.Segments = append(m.Segments, segmentSpec(SegmentMasterTxt, master))
			return m
		}()), map[SegmentKind]io.Reader{SegmentMasterTxt: bytes.NewReader(master)}},
		{"unknown-kind", marshal(func() Manifest {
			m := valid()
			m.Segments = []ManifestSegment{{Kind: "bogus", Bytes: 1, SHA256: digest(master)}}
			return m
		}()), map[SegmentKind]io.Reader{}},
		{"zero-bytes", marshal(func() Manifest { m := valid(); m.Segments[0].Bytes = 0; return m }()), map[SegmentKind]io.Reader{SegmentMasterTxt: bytes.NewReader(nil)}},
		{"master-too-large", marshal(func() Manifest {
			m := valid()
			m.Segments[0].Bytes = MaxMasterTxtBytes + 1
			return m
		}()), map[SegmentKind]io.Reader{SegmentMasterTxt: bytes.NewReader(master)}},
		{"backup-too-large", marshal(func() Manifest {
			m := valid()
			m.Segments = []ManifestSegment{{Kind: SegmentV2Backup, Bytes: MaxV2BackupBytes + 1, SHA256: digest(backup)}}
			return m
		}()), map[SegmentKind]io.Reader{SegmentV2Backup: bytes.NewReader(backup)}},
		{"bad-sha", marshal(func() Manifest { m := valid(); m.Segments[0].SHA256 = "zz"; return m }()), map[SegmentKind]io.Reader{SegmentMasterTxt: bytes.NewReader(master)}},
		{"readback-not-object", marshal(func() Manifest {
			m := valid()
			m.Segments[0].ExpectedReadback = json.RawMessage(`[1,2]`)
			return m
		}()), map[SegmentKind]io.Reader{SegmentMasterTxt: bytes.NewReader(master)}},
		{"readback-too-large", marshal(func() Manifest {
			m := valid()
			m.Segments[0].ExpectedReadback = json.RawMessage(`{"blob":"` + string(bytes.Repeat([]byte("x"), MaxExpectedReadbackBytes)) + `"}`)
			return m
		}()), map[SegmentKind]io.Reader{SegmentMasterTxt: bytes.NewReader(master)}},
		{"missing-upload", marshal(func() Manifest {
			m := valid()
			m.Segments = append(m.Segments, segmentSpec(SegmentV2Backup, backup))
			return m
		}()), map[SegmentKind]io.Reader{SegmentMasterTxt: bytes.NewReader(master)}},
		{"extra-upload", marshal(valid()), map[SegmentKind]io.Reader{
			SegmentMasterTxt: bytes.NewReader(master),
			SegmentV2Backup:  bytes.NewReader(backup),
		}},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			_, err := svc.Create(context.Background(), CreateInput{Manifest: tc.manifest, Segments: tc.segments})
			if !errors.Is(err, ErrInvalidInput) {
				t.Fatalf("错误 = %v，期望 ErrInvalidInput", err)
			}
			assertRootEmpty(t, svc)
		})
	}
}

func TestCreateVerifiesDeclaredDigestAndSize(t *testing.T) {
	svc, _ := newTestService(t)
	master := masterBytes(48)
	wrong := digest(masterBytes(49))

	manifest := Manifest{
		TargetDeviceID: "phone-a", ProtocolVersion: CurrentProtocolVersion,
		RulesFingerprint: testFingerprint,
		Segments:         []ManifestSegment{{Kind: SegmentMasterTxt, Bytes: int64(len(master)), SHA256: wrong}},
	}
	raw, _ := json.Marshal(manifest)
	_, err := svc.Create(context.Background(), CreateInput{
		Manifest: raw, Segments: map[SegmentKind]io.Reader{SegmentMasterTxt: bytes.NewReader(master)},
	})
	if !errors.Is(err, ErrInvalidInput) {
		t.Fatalf("摘要不符错误 = %v，期望 ErrInvalidInput", err)
	}
	assertRootEmpty(t, svc)

	manifest.Segments[0].SHA256 = digest(master)
	manifest.Segments[0].Bytes = int64(len(master)) - 1
	raw, _ = json.Marshal(manifest)
	_, err = svc.Create(context.Background(), CreateInput{
		Manifest: raw, Segments: map[SegmentKind]io.Reader{SegmentMasterTxt: bytes.NewReader(master)},
	})
	if !errors.Is(err, ErrInvalidInput) {
		t.Fatalf("字节不符错误 = %v，期望 ErrInvalidInput", err)
	}
	assertRootEmpty(t, svc)

	manifest.Segments[0].Bytes = int64(len(master)) + 1
	raw, _ = json.Marshal(manifest)
	_, err = svc.Create(context.Background(), CreateInput{
		Manifest: raw, Segments: map[SegmentKind]io.Reader{SegmentMasterTxt: bytes.NewReader(master)},
	})
	if !errors.Is(err, ErrInvalidInput) {
		t.Fatalf("字节不足错误 = %v，期望 ErrInvalidInput", err)
	}
	assertRootEmpty(t, svc)
}

func TestListFiltersAndCursor(t *testing.T) {
	svc, clock := newTestService(t)
	first := createTask(t, svc, "phone-a", masterBytes(16), nil)
	clock.Advance(time.Second)
	second := createTask(t, svc, "phone-a", masterBytes(16), nil)
	clock.Advance(time.Second)
	third := createTask(t, svc, "phone-a", masterBytes(16), nil)
	clock.Advance(time.Second)
	createTask(t, svc, "phone-b", masterBytes(16), nil)

	result, err := svc.List(context.Background(), ListOptions{TargetDeviceID: "phone-a"})
	if err != nil {
		t.Fatalf("列表失败: %v", err)
	}
	if len(result.Items) != 3 || result.NextCursor != "" {
		t.Fatalf("列表异常: %+v", result)
	}
	if result.Items[0].ID != third.ID || result.Items[2].ID != first.ID {
		t.Fatalf("列表顺序异常: %+v", result.Items)
	}
	if result.Items[0].SegmentCount != 1 {
		t.Fatalf("segmentCount = %d，期望 1", result.Items[0].SegmentCount)
	}

	if _, err := svc.Claim(context.Background(), second.ID); err != nil {
		t.Fatalf("领取失败: %v", err)
	}
	claimed, err := svc.List(context.Background(), ListOptions{TargetDeviceID: "phone-a", Status: StatusClaimed})
	if err != nil || len(claimed.Items) != 1 || claimed.Items[0].ID != second.ID {
		t.Fatalf("状态过滤异常: %+v err=%v", claimed, err)
	}

	page, err := svc.List(context.Background(), ListOptions{TargetDeviceID: "phone-a", Limit: 2})
	if err != nil {
		t.Fatalf("分页失败: %v", err)
	}
	if len(page.Items) != 2 || page.NextCursor == "" {
		t.Fatalf("首页异常: %+v", page)
	}
	next, err := svc.List(context.Background(), ListOptions{TargetDeviceID: "phone-a", Limit: 2, Cursor: page.NextCursor})
	if err != nil {
		t.Fatalf("第二页失败: %v", err)
	}
	if len(next.Items) != 1 || next.Items[0].ID != first.ID || next.NextCursor != "" {
		t.Fatalf("第二页异常: %+v", next)
	}

	other, err := svc.List(context.Background(), ListOptions{TargetDeviceID: "phone-b"})
	if err != nil || len(other.Items) != 1 {
		t.Fatalf("设备过滤异常: %+v err=%v", other, err)
	}

	for name, opts := range map[string]ListOptions{
		"bad-target":  {TargetDeviceID: "bad target"},
		"zero-limit":  {TargetDeviceID: "phone-a", Limit: -1},
		"large-limit": {TargetDeviceID: "phone-a", Limit: MaxListLimit + 1},
		"bad-status":  {TargetDeviceID: "phone-a", Status: "bogus"},
	} {
		if _, err := svc.List(context.Background(), opts); !errors.Is(err, ErrInvalidInput) {
			t.Fatalf("%s 错误 = %v，期望 ErrInvalidInput", name, err)
		}
	}
	if _, err := svc.List(context.Background(), ListOptions{TargetDeviceID: "phone-a", Cursor: "not-a-cursor"}); !errors.Is(err, ErrInvalidCursor) {
		t.Fatalf("游标错误 = %v，期望 ErrInvalidCursor", err)
	}
}

func TestClaimIsIdempotentAndExpires(t *testing.T) {
	svc, clock := newTestService(t)
	task := createTask(t, svc, "phone-a", masterBytes(16), nil)

	claimed, err := svc.Claim(context.Background(), task.ID)
	if err != nil {
		t.Fatalf("领取失败: %v", err)
	}
	if claimed.Status != StatusClaimed || claimed.ClaimedAt == nil {
		t.Fatalf("领取结果异常: %+v", claimed)
	}
	claimedAt := *claimed.ClaimedAt

	clock.Advance(time.Minute)
	again, err := svc.Claim(context.Background(), task.ID)
	if err != nil {
		t.Fatalf("重复领取应幂等: %v", err)
	}
	if again.Status != StatusClaimed || again.ClaimedAt == nil || !again.ClaimedAt.Equal(claimedAt) {
		t.Fatalf("重复领取改变了 claimedAt: %+v", again)
	}

	if _, err := svc.Claim(context.Background(), uuid.NewString()); !errors.Is(err, ErrNotFound) {
		t.Fatalf("缺失任务错误 = %v，期望 ErrNotFound", err)
	}
	if _, err := svc.Claim(context.Background(), "not-a-uuid"); !errors.Is(err, ErrInvalidInput) {
		t.Fatalf("非法 ID 错误 = %v，期望 ErrInvalidInput", err)
	}

	expiring := createTask(t, svc, "phone-a", masterBytes(16), nil)
	last, err := svc.Create(context.Background(), buildCreateInput(t, "phone-a", MinTTLSeconds, masterBytes(8), nil))
	if err != nil {
		t.Fatalf("创建短 TTL 任务失败: %v", err)
	}
	clock.Advance(MinTTLSeconds * time.Second)
	if _, err := svc.Claim(context.Background(), last.ID); !errors.Is(err, ErrStateConflict) {
		t.Fatalf("过期领取错误 = %v，期望 ErrStateConflict", err)
	}
	expired, err := svc.Get(context.Background(), last.ID)
	if err != nil || expired.Status != StatusExpired || expired.FinishedAt == nil {
		t.Fatalf("惰性过期异常: %+v err=%v", expired, err)
	}
	_ = expiring
}

func TestResultTerminalAndIdempotent(t *testing.T) {
	svc, _ := newTestService(t)
	task := createTask(t, svc, "phone-a", masterBytes(32), masterBytes(64))
	if _, err := svc.Result(context.Background(), task.ID, ResultInput{Status: StatusApplied}); !errors.Is(err, ErrStateConflict) {
		t.Fatalf("未领取回传错误 = %v，期望 ErrStateConflict", err)
	}
	if _, err := svc.Claim(context.Background(), task.ID); err != nil {
		t.Fatalf("领取失败: %v", err)
	}
	input := ResultInput{
		Status:            StatusApplied,
		Readback:          json.RawMessage(`{"authors":1,"nested":{"b":2,"a":1}}`),
		DeviceLocalHashes: []LocalHash{{Kind: SegmentV2Backup, SHA256: digest(masterBytes(64))}, {Kind: SegmentMasterTxt, SHA256: digest(masterBytes(32))}},
		Message:           "ok",
	}
	finished, err := svc.Result(context.Background(), task.ID, input)
	if err != nil {
		t.Fatalf("回传失败: %v", err)
	}
	if finished.Status != StatusApplied || finished.FinishedAt == nil || finished.Result == nil {
		t.Fatalf("回传结果异常: %+v", finished)
	}
	if finished.Result.Status != StatusApplied || finished.Result.Message != "ok" || len(finished.Result.DeviceLocalHashes) != 2 {
		t.Fatalf("结果字段异常: %+v", finished.Result)
	}
	if !finished.Result.ReportedAt.Equal(*finished.FinishedAt) {
		t.Fatalf("reportedAt = %v，期望 %v", finished.Result.ReportedAt, *finished.FinishedAt)
	}
	finishedAt := *finished.FinishedAt

	replay := input
	replay.Readback = json.RawMessage(`{"nested":{"a":1,"b":2},"authors":1}`)
	replay.DeviceLocalHashes = []LocalHash{{Kind: SegmentMasterTxt, SHA256: digest(masterBytes(32))}, {Kind: SegmentV2Backup, SHA256: digest(masterBytes(64))}}
	again, err := svc.Result(context.Background(), task.ID, replay)
	if err != nil {
		t.Fatalf("相同结果重放应幂等: %v", err)
	}
	if again.FinishedAt == nil || !again.FinishedAt.Equal(finishedAt) {
		t.Fatalf("重放改变了 finishedAt: %+v", again.FinishedAt)
	}

	changed := input
	changed.Status = StatusPartial
	if _, err := svc.Result(context.Background(), task.ID, changed); !errors.Is(err, ErrStateConflict) {
		t.Fatalf("不同结果重放错误 = %v，期望 ErrStateConflict", err)
	}

	partialTask := createTask(t, svc, "phone-a", masterBytes(8), nil)
	if _, err := svc.Claim(context.Background(), partialTask.ID); err != nil {
		t.Fatalf("领取失败: %v", err)
	}
	partial, err := svc.Result(context.Background(), partialTask.ID, ResultInput{Status: StatusPartial, Message: "部分应用"})
	if err != nil || partial.Status != StatusPartial {
		t.Fatalf("partial 回传异常: %+v err=%v", partial, err)
	}
	if string(partial.Result.Readback) != "{}" || partial.Result.DeviceLocalHashes == nil {
		t.Fatalf("空 readback/hashes 归一化异常: %+v", partial.Result)
	}

	failedTask := createTask(t, svc, "phone-a", masterBytes(8), nil)
	if _, err := svc.Claim(context.Background(), failedTask.ID); err != nil {
		t.Fatalf("领取失败: %v", err)
	}
	failed, err := svc.Result(context.Background(), failedTask.ID, ResultInput{Status: StatusFailed, Readback: json.RawMessage(`{"stage":"import"}`)})
	if err != nil || failed.Status != StatusFailed {
		t.Fatalf("failed 回传异常: %+v err=%v", failed, err)
	}

	if _, err := svc.Result(context.Background(), uuid.NewString(), ResultInput{Status: StatusApplied}); !errors.Is(err, ErrNotFound) {
		t.Fatalf("缺失任务错误 = %v，期望 ErrNotFound", err)
	}
}

func TestResultRejectsInvalidInput(t *testing.T) {
	svc, _ := newTestService(t)
	task := createTask(t, svc, "phone-a", masterBytes(8), nil)
	if _, err := svc.Claim(context.Background(), task.ID); err != nil {
		t.Fatalf("领取失败: %v", err)
	}
	cases := map[string]ResultInput{
		"bad-status":       {Status: StatusPending},
		"readback-array":   {Status: StatusApplied, Readback: json.RawMessage(`[1]`)},
		"too-many-hashes":  {Status: StatusApplied, DeviceLocalHashes: []LocalHash{{Kind: SegmentMasterTxt, SHA256: digest(masterBytes(1))}, {Kind: SegmentV2Backup, SHA256: digest(masterBytes(1))}, {Kind: SegmentMasterTxt, SHA256: digest(masterBytes(1))}}},
		"duplicate-hashes": {Status: StatusApplied, DeviceLocalHashes: []LocalHash{{Kind: SegmentMasterTxt, SHA256: digest(masterBytes(1))}, {Kind: SegmentMasterTxt, SHA256: digest(masterBytes(1))}}},
		"bad-kind":         {Status: StatusApplied, DeviceLocalHashes: []LocalHash{{Kind: "bogus", SHA256: digest(masterBytes(1))}}},
		"bad-sha":          {Status: StatusApplied, DeviceLocalHashes: []LocalHash{{Kind: SegmentMasterTxt, SHA256: "abc"}}},
		"long-message":     {Status: StatusFailed, Message: string(bytes.Repeat([]byte("m"), MaxResultMessageRunes+1))},
	}
	for name, input := range cases {
		if _, err := svc.Result(context.Background(), task.ID, input); !errors.Is(err, ErrInvalidInput) {
			t.Fatalf("%s 错误 = %v，期望 ErrInvalidInput", name, err)
		}
	}
	current, err := svc.Get(context.Background(), task.ID)
	if err != nil || current.Status != StatusClaimed {
		t.Fatalf("非法回传不应改变状态: %+v err=%v", current, err)
	}
}

func TestCancelIdempotentAndTerminal(t *testing.T) {
	svc, clock := newTestService(t)
	pending := createTask(t, svc, "phone-a", masterBytes(8), nil)
	cancelled, err := svc.Cancel(context.Background(), pending.ID)
	if err != nil || cancelled.Status != StatusCancelled || cancelled.FinishedAt == nil {
		t.Fatalf("取消 pending 异常: %+v err=%v", cancelled, err)
	}
	finishedAt := *cancelled.FinishedAt
	clock.Advance(time.Second)
	again, err := svc.Cancel(context.Background(), pending.ID)
	if err != nil || again.FinishedAt == nil || !again.FinishedAt.Equal(finishedAt) {
		t.Fatalf("重复取消应幂等: %+v err=%v", again, err)
	}

	claimed := createTask(t, svc, "phone-a", masterBytes(8), nil)
	if _, err := svc.Claim(context.Background(), claimed.ID); err != nil {
		t.Fatalf("领取失败: %v", err)
	}
	lateCancel, err := svc.Cancel(context.Background(), claimed.ID)
	if err != nil || lateCancel.Status != StatusCancelled {
		t.Fatalf("取消 claimed 异常: %+v err=%v", lateCancel, err)
	}

	applied := createTask(t, svc, "phone-a", masterBytes(8), nil)
	if _, err := svc.Claim(context.Background(), applied.ID); err != nil {
		t.Fatalf("领取失败: %v", err)
	}
	if _, err := svc.Result(context.Background(), applied.ID, ResultInput{Status: StatusApplied}); err != nil {
		t.Fatalf("回传失败: %v", err)
	}
	if _, err := svc.Cancel(context.Background(), applied.ID); !errors.Is(err, ErrStateConflict) {
		t.Fatalf("取消终态错误 = %v，期望 ErrStateConflict", err)
	}
	if _, err := svc.Cancel(context.Background(), uuid.NewString()); !errors.Is(err, ErrNotFound) {
		t.Fatalf("缺失任务错误 = %v，期望 ErrNotFound", err)
	}
}

func TestOpenSegment(t *testing.T) {
	svc, _ := newTestService(t)
	master := masterBytes(128)
	task := createTask(t, svc, "phone-a", master, nil)

	segment, reader, err := svc.OpenSegment(context.Background(), task.ID, SegmentMasterTxt)
	if err != nil {
		t.Fatalf("打开段失败: %v", err)
	}
	data, err := io.ReadAll(reader)
	_ = reader.Close()
	if err != nil {
		t.Fatalf("读取段失败: %v", err)
	}
	if !bytes.Equal(data, master) || segment.SHA256 != digest(master) || segment.Bytes != int64(len(master)) {
		t.Fatalf("段内容/元数据异常: %+v", segment)
	}
	if _, _, err := svc.OpenSegment(context.Background(), task.ID, SegmentV2Backup); !errors.Is(err, ErrSegmentNotFound) {
		t.Fatalf("缺失段错误 = %v，期望 ErrSegmentNotFound", err)
	}
	if _, _, err := svc.OpenSegment(context.Background(), uuid.NewString(), SegmentMasterTxt); !errors.Is(err, ErrNotFound) {
		t.Fatalf("缺失任务错误 = %v，期望 ErrNotFound", err)
	}
	if _, _, err := svc.OpenSegment(context.Background(), task.ID, "bogus"); !errors.Is(err, ErrInvalidInput) {
		t.Fatalf("非法段类型错误 = %v，期望 ErrInvalidInput", err)
	}

	if _, err := svc.Claim(context.Background(), task.ID); err != nil {
		t.Fatalf("领取失败: %v", err)
	}
	if _, err := svc.Result(context.Background(), task.ID, ResultInput{Status: StatusApplied}); err != nil {
		t.Fatalf("回传失败: %v", err)
	}
	if _, _, err := svc.OpenSegment(context.Background(), task.ID, SegmentMasterTxt); !errors.Is(err, ErrStateConflict) {
		t.Fatalf("终态下载错误 = %v，期望 ErrStateConflict", err)
	}
}

func TestOpenSegmentPathSafety(t *testing.T) {
	svc, _ := newTestService(t)
	task := createTask(t, svc, "phone-a", masterBytes(16), nil)
	ctx := context.Background()

	escape := []string{
		"../../etc/passwd",
		filepath.ToSlash(filepath.Join(task.ID, "..", "..", "escape.bin")),
		filepath.ToSlash(filepath.Join("other", "masterTxt.bin")),
	}
	for _, rel := range escape {
		if _, err := svc.conn.ExecContext(ctx, "UPDATE device_apply_segments SET rel_path = ? WHERE task_id = ? AND kind = 'masterTxt'", rel, task.ID); err != nil {
			t.Fatalf("篡改 rel_path 失败: %v", err)
		}
		if _, _, err := svc.OpenSegment(ctx, task.ID, SegmentMasterTxt); err == nil {
			t.Fatalf("恶意 rel_path %q 未被拒绝", rel)
		}
	}

	if _, err := svc.conn.ExecContext(ctx, "UPDATE device_apply_segments SET rel_path = ? WHERE task_id = ? AND kind = 'masterTxt'", filepath.ToSlash(filepath.Join(task.ID, "masterTxt.bin")), task.ID); err != nil {
		t.Fatalf("恢复 rel_path 失败: %v", err)
	}
	full := filepath.Join(svc.rootDir, task.ID, "masterTxt.bin")
	if err := os.Remove(full); err != nil {
		t.Fatalf("删除段文件失败: %v", err)
	}
	if _, _, err := svc.OpenSegment(ctx, task.ID, SegmentMasterTxt); err == nil {
		t.Fatal("缺失段文件未被拒绝")
	}

	task2 := createTask(t, svc, "phone-a", masterBytes(16), nil)
	if _, err := svc.conn.ExecContext(ctx, "UPDATE device_apply_segments SET bytes = bytes + 1 WHERE task_id = ?", task2.ID); err != nil {
		t.Fatalf("篡改 bytes 失败: %v", err)
	}
	if _, _, err := svc.OpenSegment(ctx, task2.ID, SegmentMasterTxt); err == nil {
		t.Fatal("账本字节数与文件不符未被拒绝")
	}
}

func TestStateMachineEdges(t *testing.T) {
	if !StatusPending.CanTransitionTo(StatusClaimed) {
		t.Fatal("pending -> claimed 应允许")
	}
	if !StatusClaimed.CanTransitionTo(StatusApplied) || !StatusClaimed.CanTransitionTo(StatusPartial) || !StatusClaimed.CanTransitionTo(StatusFailed) {
		t.Fatal("claimed -> 结果终态应允许")
	}
	if StatusPending.CanTransitionTo(StatusApplied) {
		t.Fatal("pending -> applied 不应允许")
	}
	if StatusApplied.CanTransitionTo(StatusClaimed) || StatusCancelled.CanTransitionTo(StatusClaimed) || StatusExpired.CanTransitionTo(StatusClaimed) {
		t.Fatal("终态不应有出边")
	}
	if StatusPending.CanTransitionTo(StatusPending) {
		t.Fatal("同态迁移不应允许")
	}
}
