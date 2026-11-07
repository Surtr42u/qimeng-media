package aiauth

import (
	"errors"
	"fmt"
	"strings"
)

// Scope 是 AI 客户端的最小授权范围，取值与
// docs/AI_PLATFORM_API_GAPS.md §4.2、ADR-0057 决策 3 对齐。
//
// 新增 scope 必须同时更新：httpapi 的 method+path 映射（未映射写请求默认
// 拒绝，新 scope 不接入就等于不可用）、SECURITY 授权表与本文件的已知集合。
type Scope string

const (
	// ScopeRead 允许只读查询（库/资产/作者/标签/统计等 GET 默认面）。
	ScopeRead Scope = "read"
	// ScopeResearch 允许调用研究网关（服务端仍默认关闭，scope 只表达允许）。
	ScopeResearch Scope = "research"
	// ScopeAuthorWrite 允许作者档案/来源区等可回读写入（不含删除/合并）。
	ScopeAuthorWrite Scope = "author.write"
	// ScopeAuthorDestructive 允许删除/合并作者等不可逆动作，需单独授予。
	ScopeAuthorDestructive Scope = "author.destructive"
	// ScopeAssetAuthorWrite 允许替换资产作者关联。
	ScopeAssetAuthorWrite Scope = "asset.author.write"
	// ScopeMediaOrganize 允许资产移动/命名整理（不删除原件）。
	ScopeMediaOrganize Scope = "media.organize"
	// ScopeBackupExport 允许备份导出/列表/快照管理（不含恢复）。
	ScopeBackupExport Scope = "backup.export"
	// ScopeBackupRestore 允许备份恢复/导入（高风险，默认不授予 AI）。
	ScopeBackupRestore Scope = "backup.restore"
	// ScopeMaintenanceRead 允许读取维护任务账本。
	ScopeMaintenanceRead Scope = "maintenance.read"
	// ScopeMaintenanceWrite 允许创建/暂停/恢复/取消维护任务。
	ScopeMaintenanceWrite Scope = "maintenance.write"
	// ScopeDeviceApply 允许参与设备结果包（无前台维护执行器）；具体可访问
	// 哪些设备由 ai_clients.device_ids 绑定，隔离在接入层二次校验。
	ScopeDeviceApply Scope = "device.apply"
	// ScopeAdmin 是管理面保留 scope。本轮没有 AI 可调用的管理端点，未映射
	// 的写请求对 AI 一律拒绝（包括持有本 scope 的客户端），避免把"预留"
	// 变成事实上的管理员通道。
	ScopeAdmin Scope = "admin"
)

// knownScopes 是 ParseScope 的唯一白名单（未知 scope 默认拒绝）。
var knownScopes = map[Scope]struct{}{
	ScopeRead: {}, ScopeResearch: {}, ScopeAuthorWrite: {},
	ScopeAuthorDestructive: {}, ScopeAssetAuthorWrite: {}, ScopeMediaOrganize: {},
	ScopeBackupExport: {}, ScopeBackupRestore: {}, ScopeMaintenanceRead: {},
	ScopeMaintenanceWrite: {}, ScopeDeviceApply: {}, ScopeAdmin: {},
}

var (
	// ErrUnknownScope：scope 字符串不在白名单内（含拼写错误）。
	ErrUnknownScope = errors.New("aiauth: 未知 scope")
	// ErrNoScopes：AI 客户端未声明任何 scope（拒绝创建/加载）。
	ErrNoScopes = errors.New("aiauth: 至少需要一个 scope")
)

// ParseScope 解析单个 scope 字符串并按白名单校验。
func ParseScope(raw string) (Scope, error) {
	scope := Scope(strings.TrimSpace(raw))
	if _, ok := knownScopes[scope]; !ok {
		return "", fmt.Errorf("%w: %q", ErrUnknownScope, raw)
	}
	return scope, nil
}

// ParseScopes 解析 scope 列表：裁剪空白、去重并保持输入顺序；空列表或含
// 未知值都返回错误（默认拒绝未知，配置错误必须显式暴露而不是静默收窄）。
func ParseScopes(raw []string) ([]Scope, error) {
	if len(raw) == 0 {
		return nil, ErrNoScopes
	}
	seen := make(map[Scope]struct{}, len(raw))
	scopes := make([]Scope, 0, len(raw))
	for _, item := range raw {
		scope, err := ParseScope(item)
		if err != nil {
			return nil, err
		}
		if _, ok := seen[scope]; ok {
			continue
		}
		seen[scope] = struct{}{}
		scopes = append(scopes, scope)
	}
	return scopes, nil
}

// HasScope 报告集合是否包含 scope。
func (s ScopeSet) Has(scope Scope) bool {
	_, ok := s[scope]
	return ok
}

// ScopeSet 是 scope 匹配用的集合。
type ScopeSet map[Scope]struct{}

// NewScopeSet 由 scope 列表构造集合。
func NewScopeSet(scopes []Scope) ScopeSet {
	set := make(ScopeSet, len(scopes))
	for _, scope := range scopes {
		set[scope] = struct{}{}
	}
	return set
}

// List 返回集合内 scope 的稳定顺序列表（按白名单顺序，便于日志/CLI 输出）。
func (s ScopeSet) List() []Scope {
	order := []Scope{ScopeRead, ScopeResearch, ScopeAuthorWrite, ScopeAuthorDestructive,
		ScopeAssetAuthorWrite, ScopeMediaOrganize, ScopeBackupExport, ScopeBackupRestore,
		ScopeMaintenanceRead, ScopeMaintenanceWrite, ScopeDeviceApply, ScopeAdmin}
	out := make([]Scope, 0, len(s))
	for _, scope := range order {
		if s.Has(scope) {
			out = append(out, scope)
		}
	}
	return out
}
