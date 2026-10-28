package config

import "math"

// 与OpenAPI ClientConfig双向同步；workers约束同时全量扫描的媒体库数。
const (
	ClientScanWorkersDefault = 0 // 0为自动；手动1–32。
	ClientScanWorkersMin     = 0
	ClientScanWorkersMax     = 32
	// LegacyUnlimitedUploadMB保留旧SDK的必填int字段；新服务不再设单文件大小上限。
	// 正数哨兵兼容仍用size>maxBytesMb*MiB判断的旧客户端，避免0把所有文件拦下。
	LegacyUnlimitedUploadMB = math.MaxInt32
)
