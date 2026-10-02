// backups_test.go：备份热备端点端到端测试（真实临时库跑 VACUUM INTO 全链）。
// 全链：POST 触发（快照真落盘，文件头为 SQLite 魔数）→ GET 列表（含调度
// 摘要回显）→ GET 下载（octet-stream + attachment，字节与快照一致）→
// DELETE → 404 闭环；另覆盖 409 防重入（慢快照闸内二次触发）、白名单
// 400、无 token 401。8420 真库永不触碰——全部流量打在 httptest 隔离实例。
package httpapi

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"qimeng-media/server/internal/backup"
	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/store"
)

// sqliteMagic 是 SQLite 库文件头魔数（快照必须以它开头，证明 VACUUM INTO
// 产出的是可恢复的真库文件而非空壳）。
var sqliteMagic = []byte("SQLite format 3\x00")

// TestBackupEndpointsE2E 触发→列表→下载→删除→404 全链（真实临时库）。
func TestBackupEndpointsE2E(t *testing.T) {
	env := newTestEnv(t)

	// ① 手动触发：201 + Info 字段合法。
	resp := env.do(t, "POST", "/api/v1/backups", "")
	defer closeBody(resp)
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("POST /backups 期望 201, 得到 %d", resp.StatusCode)
	}
	var info gen.BackupInfo
	if err := decodeBody(resp, &info); err != nil {
		t.Fatalf("解析 BackupInfo 失败: %v", err)
	}
	if !strings.HasPrefix(info.Name, "qimeng-") || !strings.HasSuffix(info.Name, ".db") {
		t.Fatalf("快照名形态异常: %q", info.Name)
	}
	if info.SizeBytes <= 0 || info.CreatedAt == 0 {
		t.Fatalf("Info 字段异常: size=%d created=%d", info.SizeBytes, info.CreatedAt)
	}
	// 快照真落盘且是合法 SQLite 库文件头。
	snapPath := filepath.Join(env.dataDir, backup.DirName, info.Name)
	raw, err := os.ReadFile(snapPath)
	if err != nil {
		t.Fatalf("快照文件未落盘: %v", err)
	}
	if len(raw) < len(sqliteMagic) || string(raw[:len(sqliteMagic)]) != string(sqliteMagic) {
		t.Fatalf("快照不是合法 SQLite 库文件（文件头非 %q）", sqliteMagic)
	}

	// ② 列表：items 含该快照（新→旧首位）+ schedule 回显生产默认值。
	resp = env.do(t, "GET", "/api/v1/backups", "")
	defer closeBody(resp)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("GET /backups 期望 200, 得到 %d", resp.StatusCode)
	}
	var list gen.BackupList
	if err := decodeBody(resp, &list); err != nil {
		t.Fatalf("解析 BackupList 失败: %v", err)
	}
	if len(list.Items) != 1 || list.Items[0].Name != info.Name {
		t.Fatalf("列表 = %v, 期望恰含 %q", list.Items, info.Name)
	}
	if !list.Schedule.Enabled || list.Schedule.IntervalHours != 24 || list.Schedule.Retention != 7 {
		t.Fatalf("schedule 回显异常: %+v, 期望 {true 24 7}", list.Schedule)
	}

	// ③ 下载：octet-stream + attachment，字节与快照文件逐字节一致。
	resp = env.do(t, "GET", "/api/v1/backups/"+info.Name+"/file", "")
	defer closeBody(resp)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("下载快照期望 200, 得到 %d", resp.StatusCode)
	}
	if ct := resp.Header.Get("Content-Type"); !strings.HasPrefix(ct, "application/octet-stream") {
		t.Fatalf("Content-Type = %q, 期望 application/octet-stream", ct)
	}
	if cd := resp.Header.Get("Content-Disposition"); !strings.Contains(cd, info.Name) || !strings.Contains(cd, "attachment") {
		t.Fatalf("Content-Disposition 异常: %q", cd)
	}
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		t.Fatalf("读下载体失败: %v", err)
	}
	if string(body) != string(raw) {
		t.Fatalf("下载字节与快照文件不一致（%d vs %d 字节）", len(body), len(raw))
	}

	// ④ 删除 → 204 → 文件消失 → 再删/再下载 404。
	resp = env.do(t, "DELETE", "/api/v1/backups/"+info.Name, "")
	defer closeBody(resp)
	if resp.StatusCode != http.StatusNoContent {
		t.Fatalf("DELETE 期望 204, 得到 %d", resp.StatusCode)
	}
	if _, err := os.Stat(snapPath); !os.IsNotExist(err) {
		t.Fatalf("删除后快照应消失, stat err = %v", err)
	}
	resp = env.do(t, "DELETE", "/api/v1/backups/"+info.Name, "")
	defer closeBody(resp)
	if resp.StatusCode != http.StatusNotFound {
		t.Fatalf("重复 DELETE 期望 404, 得到 %d", resp.StatusCode)
	}
	resp = env.do(t, "GET", "/api/v1/backups/"+info.Name+"/file", "")
	defer closeBody(resp)
	if resp.StatusCode != http.StatusNotFound {
		t.Fatalf("下载已删快照期望 404, 得到 %d", resp.StatusCode)
	}
}

// TestBackup409WhileInProgress 防重入 409 路径：闸内快照未完成时再触发 → 409
// BACKUP_IN_PROGRESS；完成后恢复正常。
func TestBackup409WhileInProgress(t *testing.T) {
	env := newTestEnv(t)

	// 用慢快照执行器替换 Manager 内部执行器（同包测试直改字段）：闸保持
	// 打开状态直到测试放行。
	release := make(chan struct{})
	entered := make(chan struct{})
	env.s.backup = func() *backup.Manager {
		m, err := backup.NewManager(backup.Options{
			Dir: filepath.Join(env.dataDir, backup.DirName),
			Snapshot: func(_ context.Context, dest string) error {
				close(entered)
				<-release
				return os.WriteFile(dest, []byte("SQLite format 3\x00"), 0o644)
			},
			Retention: 7,
		})
		if err != nil {
			t.Fatalf("组装慢快照管理器失败: %v", err)
		}
		return m
	}()

	done := make(chan struct{})
	go func() {
		defer close(done)
		resp := env.do(t, "POST", "/api/v1/backups", "")
		defer closeBody(resp)
		if resp.StatusCode != http.StatusCreated {
			t.Errorf("被放行的首次触发期望 201, 得到 %d", resp.StatusCode)
		}
	}()
	<-entered // 确认首次触发已进闸

	// 闸内并发二次触发 → 409。
	resp := env.do(t, "POST", "/api/v1/backups", "")
	defer closeBody(resp)
	if resp.StatusCode != http.StatusConflict {
		t.Fatalf("闸内二次触发期望 409, 得到 %d", resp.StatusCode)
	}
	var errBody struct {
		Code string `json:"code"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&errBody); err != nil || errBody.Code != "BACKUP_IN_PROGRESS" {
		t.Fatalf("409 错误码 = %+v (err %v), 期望 BACKUP_IN_PROGRESS", errBody, err)
	}

	close(release) // 放行首次触发
	<-done
}

// TestBackupNameValidation 白名单 400：非法 name 一律 400 INVALID_PARAM
// （含扩展名变体/目录段/前缀伪装），且不在备份目录产生任何文件。
func TestBackupNameValidation(t *testing.T) {
	env := newTestEnv(t)
	for _, name := range []string{
		"qimeng-20260919-100000.db.bak",
		"foo.db",
		"qimeng-20260919-100000", // 缺扩展名
	} {
		resp := env.do(t, "GET", "/api/v1/backups/"+name+"/file", "")
		defer closeBody(resp)
		if resp.StatusCode != http.StatusBadRequest {
			t.Fatalf("下载 %q 期望 400, 得到 %d", name, resp.StatusCode)
		}
		resp = env.do(t, "DELETE", "/api/v1/backups/"+name, "")
		defer closeBody(resp)
		if resp.StatusCode != http.StatusBadRequest {
			t.Fatalf("删除 %q 期望 400, 得到 %d", name, resp.StatusCode)
		}
	}
}

// TestBackupRequiresAuth 无 token 访问备份端点 → 401（快照含口令哈希，
// 全端点必须在 Bearer 门禁之内）。
func TestBackupRequiresAuth(t *testing.T) {
	env := newTestEnv(t)
	for _, req := range []struct{ method, path string }{
		{"POST", "/api/v1/backups"},
		{"GET", "/api/v1/backups"},
		{"GET", "/api/v1/backups/qimeng-20260919-100000.db/file"},
		{"DELETE", "/api/v1/backups/qimeng-20260919-100000.db"},
	} {
		httpReq, err := http.NewRequest(req.method, env.ts.URL+req.path, nil)
		if err != nil {
			t.Fatalf("构造请求失败: %v", err)
		}
		resp, err := http.DefaultClient.Do(httpReq)
		if err != nil {
			t.Fatalf("请求失败: %v", err)
		}
		closeBody(resp)
		if resp.StatusCode != http.StatusUnauthorized {
			t.Fatalf("无 token %s %s 期望 401, 得到 %d", req.method, req.path, resp.StatusCode)
		}
	}
}

// TestBackupRotationOverTime 轮转经端点面验证：retention=3 连续触发 5 次
// （时钟推进由 fakeClock 驱动），列表只剩最新 3 份。
func TestBackupRotationOverTime(t *testing.T) {
	env := newTestEnv(t)
	// 换用 retention=3 的管理器（env 默认装配对齐生产值 7，不触发轮转），
	// 快照执行器与生产同款 = store.VacuumInto（真库）。
	m, err := backup.NewManager(backup.Options{
		Dir:       filepath.Join(env.dataDir, backup.DirName),
		Snapshot:  func(ctx context.Context, dest string) error { return store.VacuumInto(env.conn, dest) },
		Retention: 3,
		Now:       env.clock.Now,
	})
	if err != nil {
		t.Fatalf("组装轮转管理器失败: %v", err)
	}
	env.s.backup = m

	for i := 0; i < 5; i++ {
		resp := env.do(t, "POST", "/api/v1/backups", "")
		if resp.StatusCode != http.StatusCreated {
			closeBody(resp)
			t.Fatalf("第 %d 次触发期望 201, 得到 %d", i+1, resp.StatusCode)
		}
		var info gen.BackupInfo
		if err := decodeBody(resp, &info); err != nil {
			closeBody(resp)
			t.Fatalf("解析 BackupInfo 失败: %v", err)
		}
		closeBody(resp)
		// 推进时钟 1 小时：快照名秒级时间戳不撞名（Manager 与服务共享
		// 同一 fakeClock）。
		env.clock.advance(time.Hour)
	}
	resp := env.do(t, "GET", "/api/v1/backups", "")
	defer closeBody(resp)
	var list gen.BackupList
	if err := decodeBody(resp, &list); err != nil {
		t.Fatalf("解析列表失败: %v", err)
	}
	if len(list.Items) != 3 {
		t.Fatalf("轮转后列表 = %d 份, 期望 3", len(list.Items))
	}
}

// TestPutBackupSchedule 端点行为锁定（2026-10-03 热生效批）：
// ① 合法 PUT → 200 回显提交值；② kv_settings 落库（backup.SettingKey，
// 重启后覆盖 env/yaml 的锚）；③ Manager 热生效（Schedule() 立即可见，
// GET /backups 回显切到新值）；④ 键缺失 400、越界 400 且不落库不生效。
func TestPutBackupSchedule(t *testing.T) {
	env := newTestEnv(t)

	// ① 合法提交：关开关 + 缩短间隔 + 减保留（三键全量替换语义）。
	resp := env.do(t, "PUT", "/api/v1/backups/schedule",
		`{"enabled":false,"intervalHours":6,"retention":3}`)
	defer closeBody(resp)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("PUT schedule 期望 200, 得到 %d", resp.StatusCode)
	}
	var echoed gen.BackupSchedule
	if err := decodeBody(resp, &echoed); err != nil {
		t.Fatalf("解析回显失败: %v", err)
	}
	if echoed.Enabled || echoed.IntervalHours != 6 || echoed.Retention != 3 {
		t.Fatalf("回显 = %+v, 期望 {false 6 3}", echoed)
	}

	// ② kv 落库验证（值可被 ParseScheduleSetting 还原 = 重启路径可用）。
	raw, err := env.q.GetSetting(context.Background(), backup.SettingKey)
	if err != nil {
		t.Fatalf("kv 未落库: %v", err)
	}
	if s, ok := backup.ParseScheduleSetting(raw); !ok || s.Enabled || s.IntervalHours != 6 || s.Retention != 3 {
		t.Fatalf("kv 值 = %q 解析 %+v ok=%v, 期望 {false 6 3}", raw, s, ok)
	}

	// ③ Manager 热生效 + GET 回显切换。
	if enabled, interval, retention := env.s.backup.Schedule(); enabled || interval != 6*time.Hour || retention != 3 {
		t.Fatalf("Manager.Schedule() = (%v, %v, %d), 期望 (false, 6h, 3)", enabled, interval, retention)
	}
	resp2 := env.do(t, "GET", "/api/v1/backups", "")
	defer closeBody(resp2)
	var list gen.BackupList
	if err := decodeBody(resp2, &list); err != nil {
		t.Fatalf("解析列表失败: %v", err)
	}
	if list.Schedule.Enabled || list.Schedule.IntervalHours != 6 || list.Schedule.Retention != 3 {
		t.Fatalf("GET 回显 = %+v, 期望 {false 6 3}", list.Schedule)
	}

	// ④ 键缺失（不完整对象）→ 400，且不落库不生效。
	resp3 := env.do(t, "PUT", "/api/v1/backups/schedule", `{"enabled":true}`)
	defer closeBody(resp3)
	if resp3.StatusCode != http.StatusBadRequest {
		t.Fatalf("缺键 PUT 期望 400, 得到 %d", resp3.StatusCode)
	}
	if enabled, _, _ := env.s.backup.Schedule(); enabled {
		t.Fatal("400 后 Manager 参数不得变化")
	}

	// ⑤ 越界 → 400（intervalHours=0 / retention=366），kv 值不被覆盖。
	for _, body := range []string{
		`{"enabled":true,"intervalHours":0,"retention":7}`,
		`{"enabled":true,"intervalHours":8761,"retention":7}`,
		`{"enabled":true,"intervalHours":24,"retention":0}`,
		`{"enabled":true,"intervalHours":24,"retention":366}`,
	} {
		resp4 := env.do(t, "PUT", "/api/v1/backups/schedule", body)
		closeBody(resp4)
		if resp4.StatusCode != http.StatusBadRequest {
			t.Fatalf("越界 PUT %s 期望 400, 得到 %d", body, resp4.StatusCode)
		}
	}
	raw2, err := env.q.GetSetting(context.Background(), backup.SettingKey)
	if err != nil || raw2 != raw {
		t.Fatalf("400 后 kv 值不得变化: raw2=%q err=%v", raw2, err)
	}
}
