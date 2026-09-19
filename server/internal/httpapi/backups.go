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
	"errors"
	"fmt"
	"net/http"
	"time"

	"qimeng-media/server/internal/backup"
	"qimeng-media/server/internal/httpapi/gen"
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

// GetApiV1Backups 快照列表 + 调度摘要（schedule 从 config 只读回显——
// v1 无备份设置端点，调整走 yaml/env + 重启）。
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
	// intervalHours 向上取整（ceil）：90m 配置展示为 1h 而非 0h。
	hours := int((s.cfg.Backup.Interval + hourCeilStep - 1) / hourCeilStep)
	if hours < backupScheduleIntervalMinHours {
		hours = backupScheduleIntervalMinHours
	}
	writeJSON(w, http.StatusOK, gen.BackupList{
		Items: items,
		Schedule: gen.BackupSchedule{
			Enabled:       s.cfg.Backup.Enabled,
			IntervalHours: hours,
			Retention:     s.cfg.Backup.Retention,
		},
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
