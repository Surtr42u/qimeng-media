// research.go：媒体研究流水线的配置段（research.*，ADR-0053）。
//
// 为什么默认关闭：研究端点会把图片转发给本机 Lens sidecar、并访问外部平台，
// 属于额外的出网面与 CPU 占用；嵌入式/Android 单机形态（ADR-0015）默认不
// 需要它，也不因此新增任何监听面——端点挂在既有服务端上，开关只管"是否
// 提供服务"。
//
// sidecar 凭据纪律：sidecar_token 只允许来自环境变量
// QIMENG_RESEARCH_SIDECAR_TOKEN 或被忽略的本地配置（*.local），不得入库；
// 端点只允许 http://127.0.0.1:<port> 或 http://[::1]:<port>——https 到公网
// 地址、域名或局域网 IP 一律在启动期拒绝（研究流量只走 loopback）。
package config

import (
	"errors"
	"fmt"
	"net"
	"net/url"
	"os"
	"strconv"
	"strings"
)

// 研究流水线预算默认值（与 api/openapi.yaml 的 ResearchLimits /
// ResearchProviderLimits 描述双写联动）。
const (
	// DefaultResearchLensPerHour 是 Lens 每小时默认预算（20 次/小时）：
	// 单帧实测 17–18s、单实例串行，20 次已接近一小时满负荷，再高只会排队。
	DefaultResearchLensPerHour = 20
	// DefaultResearchLensPerDay 是 Lens 每日默认预算（100 次/天）：
	// 覆盖一轮批量核验（数十张）的常规用量，同时限制被误用成爬虫的规模。
	DefaultResearchLensPerDay = 100
	// DefaultResearchProviderPerMinute 是单个 provider 每分钟默认预算
	// （20 次/分钟）：外部平台对自动化访问普遍敏感，默认值只够人工节奏的
	// 批量核验，避免一次性触发平台限流/封禁。
	DefaultResearchProviderPerMinute = 20
)

// ResearchConfig 媒体研究流水线配置（服务端 provider 网关 + 本机 Lens sidecar）。
type ResearchConfig struct {
	// Enabled 研究端点总开关（默认 false）。false 时 context 仍可读（回报
	// enabled=false），provider 查询与 lens 返回 503。
	Enabled bool `yaml:"enabled"`
	// SidecarEndpoint 本机 Lens sidecar 基地址，只允许
	// http://127.0.0.1:<port> 或 http://[::1]:<port>（ParseResearchSidecarEndpoint
	// 校验）。空 = 未配置 sidecar（lens 端点 503 RESEARCH_SIDECAR_DISABLED）。
	SidecarEndpoint string `yaml:"sidecar_endpoint"`
	// SidecarToken 是 sidecar 的 Bearer 凭据；只允许来自环境变量
	// QIMENG_RESEARCH_SIDECAR_TOKEN 或被忽略的 *.local 配置，不得入库。
	// 配置 endpoint 时必须非空，否则启动报错（sidecar 恒定要求 Bearer）。
	SidecarToken string `yaml:"sidecar_token"`
	// EvidenceDir 研究证据落盘目录；空 = DataDir/research-evidence。
	// 证据只记公开字段与哈希（剥会话参数、不存图片字节），不入库。
	EvidenceDir string `yaml:"evidence_dir"`
	// LensPerHour / LensPerDay 是 Lens 的进程内预算（<=0 回落默认值）。
	LensPerHour int `yaml:"lens_per_hour"`
	LensPerDay  int `yaml:"lens_per_day"`
	// ProviderPerMinute 是单个 provider 的进程内每分钟预算（<=0 回落默认值）。
	ProviderPerMinute int `yaml:"provider_per_minute"`
}

// applyResearchDefaults 填充研究段的零值默认（Load 在 yaml/env 之后调用；
// 预算 <=0 一律回落默认值——与 backup/trash 的兜底惯例一致，避免 yaml 显式
// 0 把预算降成"每次都不允许"）。
func (c *ResearchConfig) applyResearchDefaults() {
	if c.LensPerHour <= 0 {
		c.LensPerHour = DefaultResearchLensPerHour
	}
	if c.LensPerDay <= 0 {
		c.LensPerDay = DefaultResearchLensPerDay
	}
	if c.ProviderPerMinute <= 0 {
		c.ProviderPerMinute = DefaultResearchProviderPerMinute
	}
}

// Validate 校验研究段（Load 末尾调用，失败即拒绝启动）。
// 关闭时也校验显式配置：写错端点不应等到"打开开关的那一刻"才暴露。
func (c ResearchConfig) Validate() error {
	if c.SidecarEndpoint != "" {
		if _, err := ParseResearchSidecarEndpoint(c.SidecarEndpoint); err != nil {
			return fmt.Errorf("research.sidecar_endpoint 不合法: %w", err)
		}
		if strings.TrimSpace(c.SidecarToken) == "" {
			return errors.New("research.sidecar_endpoint 已配置时必须提供 sidecar_token（仅从 QIMENG_RESEARCH_SIDECAR_TOKEN 或被忽略的 *.local 配置读取）")
		}
	}
	if c.LensPerHour <= 0 || c.LensPerDay <= 0 || c.ProviderPerMinute <= 0 {
		return errors.New("research 预算须为正整数（lens_per_hour/lens_per_day/provider_per_minute）")
	}
	return nil
}

// ParseResearchSidecarEndpoint 解析并规范化 sidecar 基地址。
//
// 只接受 http + 明确端口的回环字面量（127.0.0.1 或 ::1）：sidecar 是本机
// 进程，用 https、域名、局域网 IP 或 0.0.0.0 都意味着"以为在本机其实不
// 一定"，而 Bearer token 会随请求发出去——启动期直接拒绝，错误信息不回显
// 完整原值之外的敏感内容（端点本身不是机密）。
func ParseResearchSidecarEndpoint(raw string) (string, error) {
	trimmed := strings.TrimSpace(raw)
	if trimmed == "" {
		return "", errors.New("地址为空")
	}
	u, err := url.Parse(trimmed)
	if err != nil {
		return "", errors.New("不是合法 URL")
	}
	if u.Scheme != "http" {
		return "", errors.New("只允许 http://127.0.0.1:<port> 或 http://[::1]:<port>（sidecar 只走本机回环）")
	}
	if u.User != nil || u.RawQuery != "" || u.Fragment != "" || (u.Path != "" && u.Path != "/") {
		return "", errors.New("只允许基地址（不得带凭据、路径、查询或片段）")
	}
	host := u.Hostname()
	if host != "127.0.0.1" && host != "::1" {
		return "", errors.New("主机只允许 127.0.0.1 或 [::1]")
	}
	port := u.Port()
	if port == "" {
		return "", errors.New("必须显式给出端口")
	}
	n, err := strconv.Atoi(port)
	if err != nil || n < 1 || n > 65535 {
		return "", errors.New("端口不合法")
	}
	return "http://" + net.JoinHostPort(host, strconv.Itoa(n)), nil
}

// applyResearchEnv 覆盖研究段环境变量（QIMENG_RESEARCH_*）：空值 = 未设置、
// 保留 yaml/默认；非法值返回错误而非静默忽略（否则"以为改了其实没生效"）。
func applyResearchEnv(cfg *Config) error {
	if v := os.Getenv("QIMENG_RESEARCH_ENABLED"); v != "" {
		b, err := strconv.ParseBool(v)
		if err != nil {
			return fmt.Errorf("环境变量 QIMENG_RESEARCH_ENABLED=%q 不是合法布尔值（1/true/0/false）: %w", v, err)
		}
		cfg.Research.Enabled = b
	}
	if v := os.Getenv("QIMENG_RESEARCH_SIDECAR_ENDPOINT"); v != "" {
		cfg.Research.SidecarEndpoint = strings.TrimSpace(v)
	}
	// 凭据只从环境变量与被忽略的本地配置读取（SECURITY：不入库、不写日志）。
	if v := os.Getenv("QIMENG_RESEARCH_SIDECAR_TOKEN"); v != "" {
		cfg.Research.SidecarToken = v
	}
	if v := os.Getenv("QIMENG_RESEARCH_EVIDENCE_DIR"); v != "" {
		cfg.Research.EvidenceDir = strings.TrimSpace(v)
	}
	for name, target := range map[string]*int{
		"QIMENG_RESEARCH_LENS_PER_HOUR":       &cfg.Research.LensPerHour,
		"QIMENG_RESEARCH_LENS_PER_DAY":        &cfg.Research.LensPerDay,
		"QIMENG_RESEARCH_PROVIDER_PER_MINUTE": &cfg.Research.ProviderPerMinute,
	} {
		if v := os.Getenv(name); v != "" {
			n, err := strconv.Atoi(v)
			if err != nil || n < 1 {
				return fmt.Errorf("环境变量 %s 须为正整数", name)
			}
			*target = n
		}
	}
	return nil
}
