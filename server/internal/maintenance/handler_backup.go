package maintenance

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"

	"qimeng-media/server/internal/backup"
)

// backup_export 是首个接入内置 worker 的真实 kind：复用既有 backup.Manager
// 做库文件在线快照（VACUUM INTO），把快照文件名/sha256/大小作为工件索引入账，
// 再回读文件核对哈希。工件正文（快照文件）留在 DataDir/backups，账本只存
// 引用（ADR-0054 的「工件正文不入库」）。
//
// 步骤名/工件 kind/错误码是账本证据的一部分，改名会破坏在途任务的恢复
// （旧步骤名不再匹配），因此集中为常量。
const (
	backupExportInventoryStep = "inventory"
	backupExportCreateStep    = "export"
	backupExportVerifyStep    = "verify"

	// BackupArtifactKind 是备份快照工件的 kind（uri=快照文件名）。
	BackupArtifactKind = "backup_snapshot"

	backupExportCreateFailedCode = "BACKUP_EXPORT_CREATE_FAILED"
	backupExportVerifyFailedCode = "BACKUP_EXPORT_VERIFY_FAILED"
)

// backupExportMeta 是 export 步骤写入 detail_json 的回读证据（verify 步骤
// 依赖它核对同一文件）。字段用短名保持账本可读。
type backupExportMeta struct {
	Name   string `json:"name"`
	SHA256 string `json:"sha256"`
	Bytes  int64  `json:"bytes"`
}

// backupExportHandler 实现 backup_export 的三步流水线：
//
//	inventory 阶段：不产生副作用，只登记本次导出的任务身份（账本证据）；
//	export 阶段：调用 Manager.Create 生成快照，流式计算 sha256 并作为工件返回；
//	verify 阶段：按 export 步骤证据重新打开快照文件复核大小与哈希（回读核验）。
//
// 幂等边界：崩溃可能发生在「快照已落盘、工件/步骤尚未记录」的窗口内，此时
// 恢复会再生成一份快照（同名同秒被 VACUUM INTO 拒绝，跨秒则多一个文件，由
// backup.Manager 轮转按保留份数清理）；不会重复写业务媒体数据。
type backupExportHandler struct {
	manager *backup.Manager
}

// NewBackupExportHandler 构造内置 backup_export handler。manager 必须是 main
// 装配的既有 backup.Manager 实例（与定时/手动备份共用防重入闸）。
func NewBackupExportHandler(manager *backup.Manager) Handler {
	return &backupExportHandler{manager: manager}
}

// Steps 返回固定三步流水线（inventory -> applying -> verifying）。
func (h *backupExportHandler) Steps(Job) []StepSpec {
	return []StepSpec{
		{Name: backupExportInventoryStep, Stage: StatusInventory},
		{Name: backupExportCreateStep, Stage: StatusApplying},
		{Name: backupExportVerifyStep, Stage: StatusVerifying},
	}
}

// Execute 执行单个步骤。prior 用于 verify 步骤读取 export 已提交的证据。
func (h *backupExportHandler) Execute(ctx context.Context, job Job, spec StepSpec, prior map[string]Step) (StepOutcome, error) {
	switch spec.Name {
	case backupExportInventoryStep:
		// 无副作用：只把任务身份写入步骤证据（payload 是自由 JSON，业务校验
		// 不在账本层；backup_export 不需要额外输入）。
		detail, err := json.Marshal(map[string]string{"kind": string(job.Kind)})
		if err != nil {
			return StepOutcome{}, &HandlerError{Code: backupExportCreateFailedCode, Err: err}
		}
		return StepOutcome{Detail: detail}, nil
	case backupExportCreateStep:
		return h.createSnapshot(ctx)
	case backupExportVerifyStep:
		return h.verifySnapshot(prior)
	default:
		return StepOutcome{}, &HandlerError{
			Code: backupExportCreateFailedCode,
			Err:  fmt.Errorf("backup_export: 未知步骤 %q", spec.Name),
		}
	}
}

// createSnapshot 生成快照、计算哈希并返回工件元数据。
func (h *backupExportHandler) createSnapshot(ctx context.Context) (StepOutcome, error) {
	info, err := h.manager.Create(ctx)
	if err != nil {
		return StepOutcome{}, &HandlerError{Code: backupExportCreateFailedCode, Err: err}
	}
	sha, err := h.hashSnapshot(info.Name)
	if err != nil {
		return StepOutcome{}, &HandlerError{Code: backupExportCreateFailedCode, Err: err}
	}
	meta := backupExportMeta{Name: info.Name, SHA256: sha, Bytes: info.SizeBytes}
	detail, err := json.Marshal(meta)
	if err != nil {
		return StepOutcome{}, &HandlerError{Code: backupExportCreateFailedCode, Err: err}
	}
	return StepOutcome{
		Detail: detail,
		Artifacts: []ArtifactInput{{
			Kind:   BackupArtifactKind,
			URI:    info.Name,
			SHA256: sha,
			Bytes:  info.SizeBytes,
		}},
	}, nil
}

// verifySnapshot 按 export 步骤证据回读快照文件，核对大小与 sha256；证据
// 缺失/不符都按失败处理（不能把「没有证据」当成通过）。
func (h *backupExportHandler) verifySnapshot(prior map[string]Step) (StepOutcome, error) {
	exportStep, ok := prior[backupExportCreateStep]
	if !ok || exportStep.Status != StepCompleted {
		return StepOutcome{}, &HandlerError{
			Code: backupExportVerifyFailedCode,
			Err:  errors.New("backup_export: 缺少已完成的 export 步骤证据"),
		}
	}
	var meta backupExportMeta
	if err := json.Unmarshal(exportStep.Detail, &meta); err != nil || meta.Name == "" || meta.SHA256 == "" {
		return StepOutcome{}, &HandlerError{
			Code: backupExportVerifyFailedCode,
			Err:  errors.New("backup_export: export 步骤证据不合法"),
		}
	}
	file, stat, err := h.manager.OpenFile(meta.Name)
	if err != nil {
		return StepOutcome{}, &HandlerError{Code: backupExportVerifyFailedCode, Err: err}
	}
	sha, hashErr := hashAndClose(file)
	if hashErr != nil {
		return StepOutcome{}, &HandlerError{Code: backupExportVerifyFailedCode, Err: hashErr}
	}
	if stat.Size() != meta.Bytes {
		return StepOutcome{}, &HandlerError{
			Code: backupExportVerifyFailedCode,
			Err:  fmt.Errorf("backup_export: 快照 %s 大小 %d 与记录 %d 不一致", meta.Name, stat.Size(), meta.Bytes),
		}
	}
	if sha != meta.SHA256 {
		return StepOutcome{}, &HandlerError{
			Code: backupExportVerifyFailedCode,
			Err:  fmt.Errorf("backup_export: 快照 %s 的 sha256 与记录不一致", meta.Name),
		}
	}
	detail, err := json.Marshal(map[string]any{
		"name": meta.Name, "sha256": sha, "bytes": stat.Size(), "verified": true,
	})
	if err != nil {
		return StepOutcome{}, &HandlerError{Code: backupExportVerifyFailedCode, Err: err}
	}
	return StepOutcome{Detail: detail}, nil
}

// hashSnapshot 打开快照文件流式计算 sha256（正文不入内存，避免大库副本
// 撑爆内存）。
func (h *backupExportHandler) hashSnapshot(name string) (string, error) {
	file, _, err := h.manager.OpenFile(name)
	if err != nil {
		return "", err
	}
	return hashAndClose(file)
}

// hashAndClose 计算 reader 的 sha256 并关闭句柄；关闭失败不影响哈希结果。
func hashAndClose(reader io.ReadCloser) (string, error) {
	defer func() { _ = reader.Close() }()
	hash := sha256.New()
	if _, err := io.Copy(hash, reader); err != nil {
		return "", err
	}
	return hex.EncodeToString(hash.Sum(nil)), nil
}
