// backups.go：备份热备端点（POST 触发 / GET 列表+调度摘要 / GET 下载 /
// DELETE 删除）。快照的调度、轮转与文件管理在 backup 包（单职责），本层
// 只做鉴权链（全局 Bearer，无 security: [] 豁免——快照含 argon2 口令哈希
// 属敏感数据）、错误映射与协议序列化。
//
// 安全要点（docs/SECURITY.md「备份快照」节）：
//   - {name} 白名单 + os.Root 双防线在 backup.Manager 内实现，本层按
//     哨兵错误映射 400/404；
//   - 下载走 http.ServeContent（支持 Range 断点续传），Content-Disposition
//     attachment；name 已过白名单，字符域仅 [0-9-]，无 header 注入面。
package httpapi

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"time"

	"qimeng-media/server/internal/backup"
	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// backupScheduleIntervalMinHours 是调度摘要 intervalHours 的展示下限：
// 协议字段为整数小时，配置不足 1 小时时向上取整为 1（摘要展示以小时为
// 最小刻度，精确值以服务端配置为准——openapi BackupSchedule.intervalHours
// description 同口径，改动须同步协议侧描述）。
const backupScheduleIntervalMinHours = 1

// hourCeilStep 是 intervalHours 向上取整的步长（1 小时）。
const hourCeilStep = time.Hour

// PostApiV1Backups 手动触发一次快照；进行中 409。
func (s *Server) PostApiV1Backups(w http.ResponseWriter, r *http.Request) {
	if s.backup == nil {
		writeErr(w, http.StatusServiceUnavailable, codeBackupUnavailable, "备份功能未装配")
		return
	}
	info, err := s.backup.Create(r.Context())
	switch {
	case errors.Is(err, backup.ErrInProgress):
		writeErr(w, http.StatusConflict, codeBackupInProgress, "已有快照进行中，请稍后再试")
		return
	case err != nil:
		s.internalErr(w, "创建备份快照", err)
		return
	}
	writeJSON(w, http.StatusCreated, backupInfoToGen(info))
}

// GetApiV1Backups 快照列表 + 调度摘要（schedule 读 Manager 当前生效值——
// 含 PUT /backups/schedule 覆盖值；未覆盖时为启动配置，s.cfg 不再参与回显）。
func (s *Server) GetApiV1Backups(w http.ResponseWriter, r *http.Request) {
	if s.backup == nil {
		writeErr(w, http.StatusServiceUnavailable, codeBackupUnavailable, "备份功能未装配")
		return
	}
	infos, err := s.backup.List()
	if err != nil {
		s.internalErr(w, "列出备份快照", err)
		return
	}
	items := make([]gen.BackupInfo, 0, len(infos))
	for _, info := range infos {
		items = append(items, backupInfoToGen(info))
	}
	// 当前生效值三键（Manager.Schedule 单点）；intervalHours 向上取整
	//（ceil）：90m 启动配置展示为 1h 而非 0h。
	enabled, interval, retention := s.backup.Schedule()
	hours := int((interval + hourCeilStep - 1) / hourCeilStep)
	if hours < backupScheduleIntervalMinHours {
		hours = backupScheduleIntervalMinHours
	}
	writeJSON(w, http.StatusOK, gen.BackupList{
		Items: items,
		Schedule: gen.BackupSchedule{
			Enabled:       enabled,
			IntervalHours: hours,
			Retention:     retention,
		},
	})
}

// backupScheduleKeys 是 PUT 请求体的影子结构（指针形态验键齐全——全量替换
// 惯例同 PutApiV1Config：缺任一键 = 不完整对象，400 而非按零值静默生效）。
type backupScheduleKeys struct {
	Enabled       *bool `json:"enabled"`
	IntervalHours *int  `json:"intervalHours"`
	Retention     *int  `json:"retention"`
}

// PutApiV1BackupsSchedule 修改定时快照调度参数：先持久化（kv_settings 键
// backup.ScheduleSetting）再热生效（Manager.ApplySchedule，不重启进程），
// 200 回显生效值。持久化失败返回 500 且**不**热生效——避免「看起来生效、
// 重启回退」的半态；两步合败不拆。校验失败 400（既不持久化也不生效）。
//
// 为什么不走 decodeJSON：同 PutApiV1Config——body 消费后无法再做键齐全检查，
// MaxBytesReader 限额下 ReadAll 后双 Unmarshal（先影子结构验键，成功后取值）。
func (s *Server) PutApiV1BackupsSchedule(w http.ResponseWriter, r *http.Request) {
	if s.backup == nil {
		writeErr(w, http.StatusServiceUnavailable, codeBackupUnavailable, "备份功能未装配")
		return
	}
	raw, err := io.ReadAll(http.MaxBytesReader(w, r.Body, maxJSONBody))
	if err != nil {
		writeErr(w, http.StatusBadRequest, codeInvalidBody, "请求体不是合法 JSON")
		return
	}
	var keys backupScheduleKeys
	if err := json.Unmarshal(raw, &keys); err != nil || keys.Enabled == nil ||
		keys.IntervalHours == nil || keys.Retention == nil {
		writeErr(w, http.StatusBadRequest, codeInvalidParam,
			"须提交完整调度参数对象（enabled/intervalHours/retention 三键）")
		return
	}
	switch {
	case *keys.IntervalHours < backup.MinIntervalHours || *keys.IntervalHours > backup.MaxIntervalHours:
		writeErr(w, http.StatusBadRequest, codeInvalidParam,
			fmt.Sprintf("intervalHours 须在 %d–%d 小时", backup.MinIntervalHours, backup.MaxIntervalHours))
		return
	case *keys.Retention < backup.MinRetention || *keys.Retention > backup.MaxRetention:
		writeErr(w, http.StatusBadRequest, codeInvalidParam,
			fmt.Sprintf("retention 须在 %d–%d 份", backup.MinRetention, backup.MaxRetention))
		return
	}
	// 持久化先行（写入即生效语义的锚——重启后 kv 覆盖 env/yaml，见
	// backup.SettingKey 注释），成功后才热生效。
	payload, err := json.Marshal(backup.ScheduleSetting{
		Enabled:       *keys.Enabled,
		IntervalHours: *keys.IntervalHours,
		Retention:     *keys.Retention,
	})
	if err != nil {
		s.internalErr(w, "序列化备份调度参数", err)
		return
	}
	if err := s.q.UpsertSetting(r.Context(), db.UpsertSettingParams{
		Key:       backup.SettingKey,
		Value:     string(payload),
		UpdatedAt: store.FormatTimestamp(s.now()),
	}); err != nil {
		s.internalErr(w, "持久化备份调度参数", err)
		return
	}
	s.backup.ApplySchedule(*keys.Enabled, time.Duration(*keys.IntervalHours)*time.Hour, *keys.Retention)
	writeJSON(w, http.StatusOK, gen.BackupSchedule{
		Enabled:       *keys.Enabled,
		IntervalHours: *keys.IntervalHours,
		Retention:     *keys.Retention,
	})
}

// GetApiV1BackupsNameFile 下载单份快照（application/octet-stream 附件流，
// http.ServeContent 支持 Range——大库断点续传）。
func (s *Server) GetApiV1BackupsNameFile(w http.ResponseWriter, r *http.Request, name gen.BackupName) {
	if s.backup == nil {
		writeErr(w, http.StatusServiceUnavailable, codeBackupUnavailable, "备份功能未装配")
		return
	}
	f, st, err := s.backup.OpenFile(name)
	switch {
	case errors.Is(err, backup.ErrInvalidName):
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "快照文件名不合法")
		return
	case errors.Is(err, backup.ErrNotFound):
		writeErr(w, http.StatusNotFound, codeNotFound, "快照不存在")
		return
	case err != nil:
		s.internalErr(w, "打开备份快照", err)
		return
	}
	defer func() { _ = f.Close() }()
	w.Header().Set("Content-Type", "application/octet-stream")
	// name 已过 ^qimeng-\d{8}-\d{6}\.db$ 白名单，字符域受限，无注入面。
	w.Header().Set("Content-Disposition", fmt.Sprintf("attachment; filename=%q", name))
	http.ServeContent(w, r, name, st.ModTime(), f)
}

// DeleteApiV1BackupsName 删除单份快照（物理删除管理操作）。
func (s *Server) DeleteApiV1BackupsName(w http.ResponseWriter, r *http.Request, name gen.BackupName) {
	if s.backup == nil {
		writeErr(w, http.StatusServiceUnavailable, codeBackupUnavailable, "备份功能未装配")
		return
	}
	err := s.backup.Delete(name)
	switch {
	case errors.Is(err, backup.ErrInvalidName):
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "快照文件名不合法")
		return
	case errors.Is(err, backup.ErrNotFound):
		writeErr(w, http.StatusNotFound, codeNotFound, "快照不存在")
		return
	case err != nil:
		s.internalErr(w, "删除备份快照", err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// backupInfoToGen 领域 Info → 协议 BackupInfo（字段一一对应，独立小函数
// 让两处消费点共享同一映射）。
func backupInfoToGen(info backup.Info) gen.BackupInfo {
	return gen.BackupInfo{Name: info.Name, SizeBytes: info.SizeBytes, CreatedAt: info.CreatedAt}
}
