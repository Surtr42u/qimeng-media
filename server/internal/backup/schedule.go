// schedule.go：调度参数的持久化载荷定义与解析（纯定义，无 IO——kv_settings
// 的读写 SQL 在调用方：httpapi 写、main 读。backup 包不碰数据库的边界不变）。
//
// 优先级语义（与 openapi PUT /api/v1/backups/schedule 描述一致）：
// 本键存在且合法 > env QIMENG_BACKUP_* > yaml backup: > 默认——UI 保存一次后
// env/yaml 对调度三键不再生效，重新 PUT 即改写。
package backup

import "encoding/json"

// SettingKey 是调度覆盖值在 kv_settings 表的键（migrations/0003 既有 KV 机制；
// 0003 文件头「后续设置项加一行键即可」惯例的直接兑现，不另造配置系统）。
// 值为 ScheduleSetting 的 JSON 文本。
const SettingKey = "backup_schedule"

// 调度参数合法范围。与 openapi BackupSchedule 的 minimum/maximum 双同步
// （协议侧改动须同步此处，反之亦然）；web 端 lib/backup.ts 同值另注。
const (
	// MinIntervalHours 间隔下限：1 小时（协议 integer minimum）。
	MinIntervalHours = 1
	// MaxIntervalHours 间隔上限：8760 小时 = 一年（防误填 87600 这类手滑值）。
	MaxIntervalHours = 8760
	// MinRetention 保留份数下限：至少留 1 份（0 份 = 备份无意义）。
	MinRetention = 1
	// MaxRetention 保留份数上限：365 份（每天一份封顶一年）。
	MaxRetention = 365
)

// ScheduleSetting 是持久化的调度覆盖值（三键与 openapi BackupSchedule 同形）。
// 键存在即整体生效（全量替换语义，不存部分键）。
type ScheduleSetting struct {
	Enabled       bool `json:"enabled"`
	IntervalHours int  `json:"intervalHours"`
	Retention     int  `json:"retention"`
}

// ParseScheduleSetting 解析并校验 kv 里的持久化值；任何不合法（坏 JSON/越界）
// 返回 false——覆盖值损坏时回落启动配置（yaml/env），绝不让坏数据锁死调度。
func ParseScheduleSetting(raw string) (ScheduleSetting, bool) {
	var s ScheduleSetting
	if err := json.Unmarshal([]byte(raw), &s); err != nil {
		return ScheduleSetting{}, false
	}
	switch {
	case s.IntervalHours < MinIntervalHours || s.IntervalHours > MaxIntervalHours:
		return ScheduleSetting{}, false
	case s.Retention < MinRetention || s.Retention > MaxRetention:
		return ScheduleSetting{}, false
	}
	return s, true
}
