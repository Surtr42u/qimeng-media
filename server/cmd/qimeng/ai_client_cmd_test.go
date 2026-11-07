package main

// qimeng ai-client 本地管理命令的集成测试：create/list/rotate/revoke 走真实
// 临时 SQLite + 幂等 migration，验证明文 token 只输出一次、库里只存哈希、
// 轮换后旧 token 失效、撤销幂等（ADR-0057 P1）。不访问真实库。

import (
	"bytes"
	"context"
	"database/sql"
	"errors"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"qimeng-media/server/internal/aiauth"
	"qimeng-media/server/internal/auth"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// writeAIClientTestConfig 写一份只指向临时目录的 yaml（路径转正斜杠，
// 避免 Windows 反斜杠在 YAML 双引号里被当转义符）。
func writeAIClientTestConfig(t *testing.T) (configPath, dbPath string) {
	t.Helper()
	dir := t.TempDir()
	dataDir := filepath.ToSlash(filepath.Join(dir, "data"))
	dbPath = filepath.ToSlash(filepath.Join(dir, "qimeng.db"))
	configPath = filepath.Join(dir, "config.yaml")
	content := "data_dir: \"" + dataDir + "\"\ndb_path: \"" + dbPath + "\"\n"
	if err := os.WriteFile(configPath, []byte(content), 0o600); err != nil {
		t.Fatalf("写测试配置失败: %v", err)
	}
	return configPath, dbPath
}

// 开放测试用 logger 与输出缓冲。
func testAIClientLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}

// extractToken/ extractClientID 从人读输出里取一次性 token 与客户端 ID。
func extractToken(t *testing.T, output string) string {
	t.Helper()
	for _, line := range strings.Split(output, "\n") {
		if strings.HasPrefix(line, "token: ") {
			return strings.TrimSpace(strings.TrimPrefix(line, "token: "))
		}
	}
	t.Fatalf("输出中缺少 token 行: %s", output)
	return ""
}

func extractClientID(t *testing.T, output string) string {
	t.Helper()
	for _, line := range strings.Split(output, "\n") {
		if strings.HasPrefix(line, "  id: ") {
			return strings.TrimSpace(strings.TrimPrefix(line, "  id: "))
		}
	}
	t.Fatalf("输出中缺少客户端 id: %s", output)
	return ""
}

// openAIClientTestDB 打开命令写过的同一临时库（验证落库内容）。
func openAIClientTestDB(t *testing.T, dbPath string) (*sql.DB, *db.Queries) {
	t.Helper()
	conn, err := store.Open(dbPath)
	if err != nil {
		t.Fatalf("打开测试库失败: %v", err)
	}
	t.Cleanup(func() { _ = conn.Close() })
	return conn, db.New(conn)
}

func TestAIClientCommandCreateListRotateRevoke(t *testing.T) {
	configPath, dbPath := writeAIClientTestConfig(t)
	logger := testAIClientLogger()
	ctx := context.Background()

	// create：输出一次性明文 token，不输出 token_hash。
	var out bytes.Buffer
	if err := runAIClientCommand([]string{
		"create", "-config", configPath, "-label", "executor-1",
		"-scopes", "read,author.write,read", "-device-ids", "phone-main, phone-b",
	}, &out, logger); err != nil {
		t.Fatalf("create 失败: %v", err)
	}
	token := extractToken(t, out.String())
	clientID := extractClientID(t, out.String())
	if token == "" || clientID == "" {
		t.Fatalf("create 输出异常: %s", out.String())
	}
	if strings.Contains(out.String(), "token_hash") {
		t.Fatal("create 输出不得包含 token_hash")
	}

	_, q := openAIClientTestDB(t, dbPath)
	row, err := q.GetAiClientByTokenHash(ctx, auth.TokenHash(token))
	if err != nil || row.ID != clientID {
		t.Fatalf("创建后的哈希查询失败: id=%s err=%v", row.ID, err)
	}
	if row.TokenHash == token {
		t.Fatal("库内出现了明文 token")
	}
	if !strings.Contains(row.ScopesJson, "author.write") || !strings.Contains(row.DeviceIdsJson, "phone-b") {
		t.Fatalf("scope/device 落库异常: %s / %s", row.ScopesJson, row.DeviceIdsJson)
	}

	// list：输出客户端公开信息，不回显任何 token。
	out.Reset()
	if err := runAIClientCommand([]string{"list", "-config", configPath}, &out, logger); err != nil {
		t.Fatalf("list 失败: %v", err)
	}
	if !strings.Contains(out.String(), clientID) || !strings.Contains(out.String(), "author.write") {
		t.Fatalf("list 输出缺少客户端信息: %s", out.String())
	}
	if strings.Contains(out.String(), token) {
		t.Fatal("list 输出泄露了明文 token")
	}

	// rotate：旧 token 哈希被替换，新 token 只显示一次。
	out.Reset()
	if err := runAIClientCommand([]string{"rotate", "-config", configPath, "-id", clientID}, &out, logger); err != nil {
		t.Fatalf("rotate 失败: %v", err)
	}
	newToken := extractToken(t, out.String())
	if newToken == "" || newToken == token {
		t.Fatalf("rotate 未产生新 token: %q", newToken)
	}
	if _, err := q.GetAiClientByTokenHash(ctx, auth.TokenHash(token)); !errors.Is(err, sql.ErrNoRows) {
		t.Fatalf("旧 token 哈希仍存在: %v", err)
	}
	if _, err := q.GetAiClientByTokenHash(ctx, auth.TokenHash(newToken)); err != nil {
		t.Fatalf("新 token 哈希不存在: %v", err)
	}

	// revoke：撤销后鉴权拒绝；重复撤销幂等。
	out.Reset()
	if err := runAIClientCommand([]string{"revoke", "-config", configPath, "-id", clientID}, &out, logger); err != nil {
		t.Fatalf("revoke 失败: %v", err)
	}
	if err := runAIClientCommand([]string{"revoke", "-config", configPath, "-id", clientID}, io.Discard, logger); err != nil {
		t.Fatalf("重复 revoke 应幂等: %v", err)
	}
	conn, err := store.Open(dbPath)
	if err != nil {
		t.Fatalf("打开测试库失败: %v", err)
	}
	defer func() { _ = conn.Close() }()
	service, err := aiauth.New(aiauth.Options{Conn: conn, Queries: db.New(conn), Logger: logger})
	if err != nil {
		t.Fatalf("组装 aiauth 服务失败: %v", err)
	}
	if _, ok := service.Authenticate(ctx, newToken); ok {
		t.Fatal("撤销后新 token 仍可通过鉴权")
	}
}

func TestAIClientCommandErrors(t *testing.T) {
	configPath, _ := writeAIClientTestConfig(t)
	logger := testAIClientLogger()
	cases := [][]string{
		{},        // 无子命令
		{"bogus"}, // 未知子命令
		{"create", "-config", configPath, "-scopes", "read"},                 // 缺 label
		{"create", "-config", configPath, "-label", "x", "-scopes", ""},      // 缺 scopes
		{"create", "-config", configPath, "-label", "x", "-scopes", "bogus"}, // 未知 scope
		{"revoke", "-config", configPath},                                    // 缺 id
		{"rotate", "-config", configPath, "-id", "missing-id"},               // 客户端不存在
	}
	for i, args := range cases {
		if err := runAIClientCommand(args, io.Discard, logger); err == nil {
			t.Fatalf("用例 %d 期望返回错误: %v", i, args)
		}
	}
}
