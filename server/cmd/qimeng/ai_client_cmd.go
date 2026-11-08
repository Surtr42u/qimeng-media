// ai_client_cmd.go：qimeng 本地 AI 客户端管理子命令（ADR-0057 P1）。
//
// 本命令是服务端本机的离线管理入口。2026-11-08 起同一能力另有
// 管理员会话专属 HTTP 端点（/api/v1/ai-clients*、/api/v1/ai-audit，
// 见 httpapi/aiclient_admin.go），本命令继续保留用于本机运维与恢复。
//
// 以下为 P1 时期的历史说明：
// 管理动作只在服务端本机执行。命令直接打开与服务端同一 SQLite，经
// internal/aiauth 创建/列出/撤销/轮换凭据：
//   - 明文 token 只在 create/rotate 的 stdout 输出一次，绝不写日志或文件；
//   - 命令先跑一次幂等 migration，保证刚升级/尚未启动的库也能管理；
//   - 服务端进程内的内存缓存最多 DefaultCacheTTL 后感知撤销/轮换（本进程
//     无缓存，直接读库）。
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"time"

	"qimeng-media/server/internal/aiauth"
	"qimeng-media/server/internal/config"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// aiClientCommand 是本地管理子命令名（main 在 flag.Parse 前按首参数分发）。
const aiClientCommand = "ai-client"

// runAIClientCommand 执行 qimeng ai-client <create|list|revoke|rotate>。
func runAIClientCommand(args []string, stdout io.Writer, logger *slog.Logger) error {
	if len(args) == 0 {
		return fmt.Errorf("用法: qimeng %s <create|list|revoke|rotate> [参数]", aiClientCommand)
	}
	switch args[0] {
	case "create":
		return runAIClientCreate(args[1:], stdout, logger)
	case "list":
		return runAIClientList(args[1:], stdout, logger)
	case "revoke":
		return runAIClientRevoke(args[1:], stdout, logger)
	case "rotate":
		return runAIClientRotate(args[1:], stdout, logger)
	default:
		return fmt.Errorf("未知子命令 %q；用法: qimeng %s <create|list|revoke|rotate>", args[0], aiClientCommand)
	}
}

// newAIClientFlagSet 建子命令 flag 集与通用 -config 参数。flag 自身的错误
// 输出丢弃：main 统一记录错误（错误信息不含 token；参数本身不是秘密）。
func newAIClientFlagSet(action string) (*flag.FlagSet, *string) {
	fs := flag.NewFlagSet(aiClientCommand+" "+action, flag.ContinueOnError)
	fs.SetOutput(io.Discard)
	configPath := fs.String("config", "config.yaml", "配置文件路径（yaml，可不存在；环境变量始终优先于文件）")
	return fs, configPath
}

// runAIClientCreate 创建客户端并输出一次性明文 token。
func runAIClientCreate(args []string, stdout io.Writer, logger *slog.Logger) error {
	fs, configPath := newAIClientFlagSet("create")
	label := fs.String("label", "", "客户端标签（必填，1–120 字符）")
	scopesRaw := fs.String("scopes", "", "逗号分隔 scope（必填），如 read,author.write")
	deviceIDsRaw := fs.String("device-ids", "", "逗号分隔设备标签（可选，仅 device.apply 使用），如 phone-main")
	expiresRaw := fs.String("expires", "", "有效期时长（可选，如 720h；空 = 永不过期）")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if fs.NArg() != 0 {
		return errors.New("create 不接受位置参数")
	}
	scopes, err := aiauth.ParseScopes(splitCommaList(*scopesRaw))
	if err != nil {
		return err
	}
	var expiresAt *time.Time
	if value := strings.TrimSpace(*expiresRaw); value != "" {
		duration, parseErr := time.ParseDuration(value)
		if parseErr != nil {
			return fmt.Errorf("-expires=%q 不是合法时长（如 720h）: %w", value, parseErr)
		}
		if duration <= 0 {
			return fmt.Errorf("-expires 须为正时长，得到 %q", value)
		}
		at := time.Now().UTC().Add(duration)
		expiresAt = &at
	}
	created, closeFn, err := withAIClientService(*configPath, logger, func(service *aiauth.Service) (aiClientCreated, error) {
		client, token, createErr := service.CreateClient(context.Background(), aiauth.CreateClientInput{
			Label: *label, Scopes: scopes,
			DeviceIDs: splitCommaList(*deviceIDsRaw), ExpiresAt: expiresAt,
		})
		return aiClientCreated{Client: client, Token: token}, createErr
	})
	closeAIClientService(closeFn, logger)
	if err != nil {
		return err
	}
	if _, err := io.WriteString(stdout, "已创建 AI 客户端\n"); err != nil {
		return err
	}
	if err := writeAIClientSummary(stdout, created.Client); err != nil {
		return err
	}
	if _, err := io.WriteString(stdout, fmt.Sprintf("token: %s\n", created.Token)); err != nil {
		return err
	}
	_, err = io.WriteString(stdout, "警告：令牌只显示一次，请立即保存到执行端环境变量 QIMENG_API_TOKEN；不会写入日志或数据库。\n")
	return err
}

// runAIClientList 列出全部 AI 客户端（不含任何凭据派生值）。
func runAIClientList(args []string, stdout io.Writer, logger *slog.Logger) error {
	fs, configPath := newAIClientFlagSet("list")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if fs.NArg() != 0 {
		return errors.New("list 不接受位置参数")
	}
	clients, closeFn, err := withAIClientService(*configPath, logger, func(service *aiauth.Service) ([]aiauth.Client, error) {
		return service.ListClients(context.Background())
	})
	closeAIClientService(closeFn, logger)
	if err != nil {
		return err
	}
	if len(clients) == 0 {
		_, err := io.WriteString(stdout, "（没有 AI 客户端）\n")
		return err
	}
	for i := range clients {
		if err := writeAIClientSummary(stdout, clients[i]); err != nil {
			return err
		}
	}
	return nil
}

// runAIClientRevoke 撤销客户端（重复撤销幂等）。
func runAIClientRevoke(args []string, stdout io.Writer, logger *slog.Logger) error {
	fs, configPath := newAIClientFlagSet("revoke")
	id := fs.String("id", "", "客户端 ID（必填）")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if fs.NArg() != 0 {
		return errors.New("revoke 不接受位置参数")
	}
	_, closeFn, err := withAIClientService(*configPath, logger, func(service *aiauth.Service) ([]aiauth.Client, error) {
		return nil, service.RevokeClient(context.Background(), *id)
	})
	closeAIClientService(closeFn, logger)
	if err != nil {
		return err
	}
	_, err = io.WriteString(stdout, fmt.Sprintf("已撤销 AI 客户端 %s（重复撤销幂等）\n", strings.TrimSpace(*id)))
	return err
}

// runAIClientRotate 轮换客户端密钥并输出一次性新明文 token。
func runAIClientRotate(args []string, stdout io.Writer, logger *slog.Logger) error {
	fs, configPath := newAIClientFlagSet("rotate")
	id := fs.String("id", "", "客户端 ID（必填）")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if fs.NArg() != 0 {
		return errors.New("rotate 不接受位置参数")
	}
	rotated, closeFn, err := withAIClientService(*configPath, logger, func(service *aiauth.Service) (aiClientCreated, error) {
		client, token, rotateErr := service.RotateClient(context.Background(), *id)
		return aiClientCreated{Client: client, Token: token}, rotateErr
	})
	closeAIClientService(closeFn, logger)
	if err != nil {
		return err
	}
	if _, err := io.WriteString(stdout, "已轮换 AI 客户端密钥\n"); err != nil {
		return err
	}
	if err := writeAIClientSummary(stdout, rotated.Client); err != nil {
		return err
	}
	if _, err := io.WriteString(stdout, fmt.Sprintf("token: %s\n", rotated.Token)); err != nil {
		return err
	}
	_, err = io.WriteString(stdout, "警告：旧令牌立即失效；新令牌只显示一次，请立即更新执行端环境变量。\n")
	return err
}

// aiClientCreated 是 create/rotate 的结果组合（明文 token 只经 stdout 输出）。
type aiClientCreated struct {
	Client aiauth.Client
	Token  string
}

// writeAIClientSummary 输出客户端公开字段（绝不输出 token_hash）；写失败
// 返回错误（stdout 管道断开等真实 I/O 边界不静默忽略）。
func writeAIClientSummary(stdout io.Writer, client aiauth.Client) error {
	lines := []string{
		"  id: " + client.ID,
		"  label: " + client.Label,
		"  scopes: " + joinScopes(client.Scopes),
		"  deviceIds: " + strings.Join(client.DeviceIDs, ","),
		"  createdAt: " + client.CreatedAt.Format(time.RFC3339),
		"  expiresAt: " + formatOptionalTime(client.ExpiresAt),
		"  revokedAt: " + formatOptionalTime(client.RevokedAt),
		"  lastUsedAt: " + formatOptionalTime(client.LastUsedAt),
	}
	_, err := io.WriteString(stdout, strings.Join(lines, "\n")+"\n")
	return err
}

// withAIClientService 是"打开服务 → 执行动作 → 返回结果 + 关闭函数"的
// 统一包装，避免每个子命令重复装配/关闭逻辑。
func withAIClientService[T any](configPath string, logger *slog.Logger, action func(*aiauth.Service) (T, error)) (T, func() error, error) {
	var zero T
	service, closeFn, err := openAIClientAuthService(configPath, logger)
	if err != nil {
		return zero, nil, err
	}
	result, actionErr := action(service)
	return result, closeFn, actionErr
}

// closeAIClientService 关闭库连接；关闭失败只告警（管理动作结果已产生）。
func closeAIClientService(closeFn func() error, logger *slog.Logger) {
	if closeFn == nil {
		return
	}
	if err := closeFn(); err != nil {
		logger.Warn("关闭 AI 客户端管理库连接失败", "error", err)
	}
}

// openAIClientAuthService 打开与服务端同一 SQLite 并返回凭据服务。
// 先跑一次幂等 migration：新装/刚升级的库可能尚未被服务端迁移。
func openAIClientAuthService(configPath string, logger *slog.Logger) (*aiauth.Service, func() error, error) {
	cfg, err := config.Load(configPath)
	if err != nil {
		return nil, nil, err
	}
	if err := os.MkdirAll(cfg.DataDir, 0o755); err != nil {
		return nil, nil, fmt.Errorf("创建数据目录失败: %w", err)
	}
	dbPath := cfg.DbPath
	if dbPath == "" {
		dbPath = filepath.Join(cfg.DataDir, "qimeng.db")
	}
	conn, err := store.Open(dbPath)
	if err != nil {
		return nil, nil, err
	}
	closeFn := func() error { return conn.Close() }
	if err := store.Migrate(conn); err != nil {
		_ = conn.Close()
		return nil, nil, fmt.Errorf("迁移数据库失败: %w", err)
	}
	service, err := aiauth.New(aiauth.Options{
		Conn: conn, Queries: db.New(conn), Now: time.Now, Logger: logger,
	})
	if err != nil {
		_ = conn.Close()
		return nil, nil, err
	}
	return service, closeFn, nil
}

// splitCommaList 把逗号分隔参数拆成裁剪后的非空列表（去重交给 aiauth 层）。
func splitCommaList(raw string) []string {
	parts := strings.Split(raw, ",")
	out := make([]string, 0, len(parts))
	for _, part := range parts {
		if value := strings.TrimSpace(part); value != "" {
			out = append(out, value)
		}
	}
	return out
}

// joinScopes 输出 scope 列表（CLI 可读格式）。
func joinScopes(scopes []aiauth.Scope) string {
	values := make([]string, 0, len(scopes))
	for _, scope := range scopes {
		values = append(values, string(scope))
	}
	return strings.Join(values, ",")
}

// formatOptionalTime 格式化可空时间；nil 输出 "（无）"。
func formatOptionalTime(value *time.Time) string {
	if value == nil {
		return "（无）"
	}
	return value.Format(time.RFC3339)
}
