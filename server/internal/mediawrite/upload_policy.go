package mediawrite

import (
	"context"
	"encoding/json"

	"qimeng-media/server/internal/authoring"
	"qimeng-media/server/internal/config"
)

// UploadPolicy 为上传及本机同步共用的实时配置投影，不缓存、不放大部署上限。
type UploadPolicy struct {
	MaxBytes int64
	Disabled bool
}

// storedClientConfig 仅解码既有 kv 持久化格式，不是网络 SDK；字段与
// OpenAPI ClientConfig 双向同步。Scan 也保留类型检查，坏配置整体回落的语义不变。
type storedClientConfig struct {
	Scan struct {
		Workers   int `json:"workers"`
		ThumbEdge int `json:"thumbEdge"`
	} `json:"scan"`
	Upload struct {
		MaxBytesMb int  `json:"maxBytesMb"`
		AutoAccept bool `json:"autoAccept"`
	} `json:"upload"`
}

func (s *Service) UploadPolicy(ctx context.Context) UploadPolicy {
	maxBytes := s.cfg.Upload.MaxBytes
	if maxBytes <= 0 {
		maxBytes = config.DefaultUploadMaxBytes
	}
	p := UploadPolicy{MaxBytes: maxBytes}
	value, err := s.q.GetSetting(ctx, authoring.SettingKeyClientConfig)
	if err != nil {
		return p
	} // 与原旁路读配置一致：失败按无覆盖处理。
	var stored storedClientConfig
	if json.Unmarshal([]byte(value), &stored) != nil {
		return p
	}
	p.Disabled = !stored.Upload.AutoAccept
	if limit := int64(stored.Upload.MaxBytesMb) << 20; limit > 0 && limit < maxBytes {
		p.MaxBytes = limit
	}
	return p
}
