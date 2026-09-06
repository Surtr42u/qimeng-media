// config.go：客户端配置读写端点（设置页扫描/上传卡的持久化后端）。
//
// 存储：kv_settings 表，键 authoring.SettingKeyClientConfig，值为
// gen.ClientConfig 的 JSON（与 openapi ClientConfig 结构一致）。
//
// 生效范围（诚实口径，openapi description 同步写明）：
//   - upload.maxBytesMb / upload.autoAccept：实时生效——上传端点
//     （upload.go）每次请求时读取覆盖值，不做任何缓存；
//   - scan.workers / scan.thumbEdge：预留字段——全库无任何消费点
//     （尚未接入扫描器/缩略图管线），保存后暂不生效（含重启也不生效，
//     接入管线前不得向用户宣传"重启后生效"）。本文件不实现任何重配。
package httpapi

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"

	"qimeng-media/server/internal/authoring"
	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// 客户端配置缺省值（openapi ClientConfig 各字段 default 的服务端单一来源；
// Web 端 use-config.ts 的 DEFAULT_CLIENT_CONFIG 与此对齐）。
const (
	defaultScanWorkers   = 2
	defaultScanThumbEdge = 800
	defaultUploadMaxMB   = 2048
)

// 客户端配置允许范围（openapi 各字段 minimum/maximum 的服务端校验锚点）。
const (
	scanWorkersMin, scanWorkersMax     = 1, 4
	scanThumbEdgeMin, scanThumbEdgeMax = 200, 1600
	uploadMaxMbMin, uploadMaxMbMax     = 64, 8192
)

// clientConfigKeys 是 PUT 请求体的键存在性影子结构：组与叶子全用指针，
// 区分「键缺失」与「零值」。为什么必须有它：decodeJSON 直解 gen.ClientConfig
// 时缺 autoAccept 键会得到 bool 零值 false 落库 → 上传被静默关闸——
// 缺键必须 400（协议：须提交完整对象），显式 false 才是合法关闸。
// JSON tag 与 gen.ClientConfig 逐字一致；类型不符（如 workers:"2"）也在
// 本结构 Unmarshal 时报错拦截。
type clientConfigKeys struct {
	Scan *struct {
		Workers   *int `json:"workers"`
		ThumbEdge *int `json:"thumbEdge"`
	} `json:"scan"`
	Upload *struct {
		MaxBytesMb *int  `json:"maxBytesMb"`
		AutoAccept *bool `json:"autoAccept"`
	} `json:"upload"`
}

// defaultClientConfig 组装缺省配置（GET 无记录 / 存储不可解析时返回它）。
func defaultClientConfig() gen.ClientConfig {
	return gen.ClientConfig{
		Scan:   gen.ClientConfigScan{Workers: defaultScanWorkers, ThumbEdge: defaultScanThumbEdge},
		Upload: gen.ClientConfigUpload{MaxBytesMb: defaultUploadMaxMB, AutoAccept: true},
	}
}

// storedClientConfig 读 kv 中已持久化的客户端配置；无记录或 JSON 解析失败
// 返回 nil（调用方据此回落缺省/配置文件值——解析失败按"没有配置"处理，
// 宁可回到出厂行为也不让坏数据堵死上传主链路）。
func (s *Server) storedClientConfig(ctx context.Context) *gen.ClientConfig {
	v, err := s.q.GetSetting(ctx, authoring.SettingKeyClientConfig)
	if err != nil {
		if !errors.Is(err, sql.ErrNoRows) {
			// 读失败同样回落缺省：配置通道故障不阻塞业务端点，
			// internalErr 语义留给端点自身（这里是旁路读取）。
			return nil
		}
		return nil
	}
	var cfg gen.ClientConfig
	if err := json.Unmarshal([]byte(v), &cfg); err != nil {
		return nil
	}
	return &cfg
}

// GetApiV1Config 读取客户端配置：无存储记录返回缺省值（字段全部填充，
// 客户端可盲读）；有记录回显存储值。
func (s *Server) GetApiV1Config(w http.ResponseWriter, r *http.Request) {
	cfg := defaultClientConfig()
	if stored := s.storedClientConfig(r.Context()); stored != nil {
		cfg = *stored
	}
	writeJSON(w, http.StatusOK, cfg)
}

// PutApiV1Config 全量替换客户端配置：先验四叶子键齐全（缺任一 400
// INVALID_PARAM），再验范围（超范围 400 INVALID_PARAM），通过后整体
// 序列化落 kv_settings，返回存储后全量（提交值即存储值，200 回显）。
//
// 为什么不走 decodeJSON：它边读边解，body 消费后无法再做键存在性检查；
// 这里改为 MaxBytesReader 限额下 ReadAll 后双 Unmarshal（先影子结构验
// 键齐全与类型，成功后直接取值），限额语义（maxJSONBody）与 decodeJSON
// 一致。
func (s *Server) PutApiV1Config(w http.ResponseWriter, r *http.Request) {
	raw, err := io.ReadAll(http.MaxBytesReader(w, r.Body, maxJSONBody))
	if err != nil {
		writeErr(w, http.StatusBadRequest, codeInvalidBody, "请求体不是合法 JSON")
		return
	}
	var keys clientConfigKeys
	if err := json.Unmarshal(raw, &keys); err != nil {
		writeErr(w, http.StatusBadRequest, codeInvalidParam,
			"须提交完整 ClientConfig 对象（scan.workers/thumbEdge、upload.maxBytesMb/autoAccept）")
		return
	}
	if keys.Scan == nil || keys.Scan.Workers == nil || keys.Scan.ThumbEdge == nil ||
		keys.Upload == nil || keys.Upload.MaxBytesMb == nil || keys.Upload.AutoAccept == nil {
		writeErr(w, http.StatusBadRequest, codeInvalidParam,
			"须提交完整 ClientConfig 对象（scan.workers/thumbEdge、upload.maxBytesMb/autoAccept）")
		return
	}
	body := gen.ClientConfig{
		Scan:   gen.ClientConfigScan{Workers: *keys.Scan.Workers, ThumbEdge: *keys.Scan.ThumbEdge},
		Upload: gen.ClientConfigUpload{MaxBytesMb: *keys.Upload.MaxBytesMb, AutoAccept: *keys.Upload.AutoAccept},
	}
	switch {
	case body.Scan.Workers < scanWorkersMin || body.Scan.Workers > scanWorkersMax:
		writeErr(w, http.StatusBadRequest, codeInvalidParam,
			fmt.Sprintf("scan.workers 须在 %d–%d", scanWorkersMin, scanWorkersMax))
		return
	case body.Scan.ThumbEdge < scanThumbEdgeMin || body.Scan.ThumbEdge > scanThumbEdgeMax:
		writeErr(w, http.StatusBadRequest, codeInvalidParam,
			fmt.Sprintf("scan.thumbEdge 须在 %d–%d", scanThumbEdgeMin, scanThumbEdgeMax))
		return
	case body.Upload.MaxBytesMb < uploadMaxMbMin || body.Upload.MaxBytesMb > uploadMaxMbMax:
		writeErr(w, http.StatusBadRequest, codeInvalidParam,
			fmt.Sprintf("upload.maxBytesMb 须在 %d–%d", uploadMaxMbMin, uploadMaxMbMax))
		return
	}
	out, err := json.Marshal(body)
	if err != nil {
		s.internalErr(w, "序列化客户端配置", err)
		return
	}
	if err := s.q.UpsertSetting(r.Context(), db.UpsertSettingParams{
		Key:       authoring.SettingKeyClientConfig,
		Value:     string(out),
		UpdatedAt: store.FormatTimestamp(s.now()),
	}); err != nil {
		s.internalErr(w, "保存客户端配置", err)
		return
	}
	writeJSON(w, http.StatusOK, body)
}
