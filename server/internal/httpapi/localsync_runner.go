// localsync_runner.go：本机同步通道的周期编排（ADR-0030）。
//
// 每轮流程：根可达性 → 库列表 → 根重叠安全检查 → 目录扫描 → 状态清理 →
// 上传闸解析 → 条目分派（媒体/TXT/other）→ 记录周期时刻。通道级失败
// （目录不存在/查询失败/重叠）只置 lastErr 不动条目；条目级失败标记
// failed 且文件原地保留，下轮自动重试。纯逻辑（匹配/扫描/重叠）在
// internal/localsync；运行态与端点在 localsync.go。
package httpapi

import (
	"bytes"
	"context"
	"database/sql"
	"errors"
	"fmt"
	"os"
	"path"
	"path/filepath"
	"strings"
	"time"
	"unicode/utf8"

	"qimeng-media/server/internal/authoring"
	"qimeng-media/server/internal/config"
	"qimeng-media/server/internal/filing"
	"qimeng-media/server/internal/localsync"
	"qimeng-media/server/internal/scanner"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// localSyncRelSeparator 是条目相对路径的分段分隔符（RelPath 统一斜杠分隔，
// 与平台无关——路径来自 localsync.ScanTree 的 ToSlash 归一）。
const localSyncRelSeparator = '/'

// utf8BOM 是作者表 TXT 的 UTF-8 字节序标记（Windows 记事本等工具常带），
// 读取后剥离再交给 importTxt（解析器按无 BOM 文本处理）。
var utf8BOM = []byte{0xEF, 0xBB, 0xBF}

// StartLocalSyncRunner 组合根（main）调用一次：本机同步通道 daemon，
// ctx 取消即退出（与 StartTrashSweeper 同款生命周期模式）。ticker 与
// trigger 端点的 kick 信号同权触发一轮扫描；启动期记 Info 留痕配置。
func (s *Server) StartLocalSyncRunner(ctx context.Context) {
	interval := s.localSyncInterval()
	s.logger.Info("本机自动同步通道已启动", "root", s.cfg.LocalSync.Root,
		"interval", interval.String(), "stableAge", s.localSyncStableAge().String())
	go func() {
		ticker := time.NewTicker(interval)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				s.runLocalSyncCycleOnce(ctx)
			case <-s.localSync.kick:
				s.runLocalSyncCycleOnce(ctx)
			}
		}
	}()
}

// localSyncInterval 生效轮询周期：<=0 回落 DefaultLocalSyncInterval
// （与 trashSweepInterval 同款兜底链——测试与内嵌形态可能手工构造 Config
// 不走 config.Load 的默认值链，ticker 周期为 0 会 panic）。
func (s *Server) localSyncInterval() time.Duration {
	if s.cfg.LocalSync.Interval > 0 {
		return s.cfg.LocalSync.Interval
	}
	return config.DefaultLocalSyncInterval
}

// localSyncStableAge 生效稳定门槛：<=0 回落 DefaultLocalSyncStableAge
// （同 localSyncInterval 的兜底链；门槛为 0 会让半截拷贝直接入库）。
func (s *Server) localSyncStableAge() time.Duration {
	if s.cfg.LocalSync.StableAge > 0 {
		return s.cfg.LocalSync.StableAge
	}
	return config.DefaultLocalSyncStableAge
}

// runLocalSyncCycleOnce 一轮同步扫描（runner 触发；测试直接调用注入目录）。
// 超函数警戒线（<=100 行）：直线编排，每步已拆小函数；再拆会把 alive 集合
// 与 lastErr 时序状态跨函数传递，得不偿失。
func (s *Server) runLocalSyncCycleOnce(ctx context.Context) {
	root := s.cfg.LocalSync.Root
	if root == "" {
		return // 防御：调度只在 Root 非空时启动
	}
	if _, err := os.Stat(root); err != nil {
		s.lsSetLastErr("同步根目录不存在")
		return
	}
	libs, err := s.q.ListLibraries(ctx)
	if err != nil {
		s.lsSetLastErr("查询库列表失败: " + err.Error())
		return
	}
	libRoots := make([]string, 0, len(libs))
	for _, lib := range libs {
		libRoots = append(libRoots, lib.RootPath)
	}
	if overlap := localsync.RootOverlapError(root, s.cfg.DataDir, libRoots); overlap != "" {
		// 安全红线：重叠即整轮拒绝（不建目录、不动条目），人工修正配置后自愈。
		s.lsSetLastErr("同步根与库根或数据目录重叠（" + overlap + "）")
		return
	}
	entries, ignored, err := localsync.ScanTree(root)
	if err != nil {
		s.lsSetLastErr("扫描同步根失败: " + err.Error())
		return
	}
	// 到这里通道健康：安全校验与扫描全部通过，条目级失败不改变通道状态。
	s.lsSetLastErr("")
	alive := make(map[string]bool, len(entries)+len(ignored))
	for _, e := range entries {
		alive[e.RelPath] = true
	}
	for _, e := range ignored {
		alive[e.RelPath] = true
	}
	s.lsPrune(alive)
	for _, e := range ignored {
		s.lsMarkIgnored(e.RelPath, string(localsync.KindOther), lsScanIgnoredReason(e))
	}
	policy, paused := s.lsResolveUploadGate(ctx)
	s.lsSetPaused(paused)
	for _, entry := range entries {
		s.lsHandleEntry(ctx, entry, libs, policy, paused)
	}
	s.localSync.mu.Lock()
	s.localSync.lastCycleAt = s.now()
	s.localSync.mu.Unlock()
}

// lsResolveUploadGate 汇集本轮媒体处理闸门：客户端配置读取发生硬错误
// （非 ErrNoRows）时按 fail-closed 处理（暂停媒体，不误判文件失败）；
// 读取正常时 paused = 自动接收开关的否（TXT 导入不受此闸约束）。
// 刻意先显式探测一次读取错误：resolveUploadPolicy 内部把读失败与「无记录」
// 合并返回 nil（不设门语义），后台同步通道选择更保守的 fail-closed。
func (s *Server) lsResolveUploadGate(ctx context.Context) (uploadPolicy, bool) {
	if _, err := s.q.GetSetting(ctx, authoring.SettingKeyClientConfig); err != nil && !errors.Is(err, sql.ErrNoRows) {
		s.logger.Warn("读取客户端配置失败，本轮媒体同步按暂停处理（TXT 不受影响）", "err", err)
		return uploadPolicy{}, true
	}
	policy := s.resolveUploadPolicy(ctx)
	return policy, policy.disabled
}

// lsHandleEntry 单条目分派：按分类走 other（忽略）/txt（导入）/media（入库）。
func (s *Server) lsHandleEntry(ctx context.Context, entry localsync.Entry, libs []db.Library, policy uploadPolicy, paused bool) {
	kind := localsync.ClassifyRelPath(entry.RelPath)
	switch kind {
	case localsync.KindOther:
		s.lsMarkIgnored(entry.RelPath, string(kind), lsOtherIgnoredReason(entry.RelPath))
	case localsync.KindTxt:
		if !s.localSyncStable(entry.RelPath, entry.Size, entry.ModTime) {
			s.lsMarkWaiting(entry.RelPath, string(kind), "", entry.Size)
			return
		}
		s.processLocalSyncTxt(ctx, entry)
	case localsync.KindMedia:
		s.lsHandleMedia(ctx, entry, libs, policy, paused)
	}
}

// lsHandleMedia 媒体条目：根级媒体直接忽略；子路径先过稳定门再匹配库，
// 拒绝/暂停不搬运，命中走入库管线。
func (s *Server) lsHandleMedia(ctx context.Context, entry localsync.Entry, libs []db.Library, policy uploadPolicy, paused bool) {
	kind := string(localsync.KindMedia)
	sep := strings.IndexByte(entry.RelPath, localSyncRelSeparator)
	if sep <= 0 {
		// 根级媒体文件：库归属无从谈起（点前缀/符号链接已在扫描侧忽略）。
		s.lsMarkIgnored(entry.RelPath, kind, "媒体文件需放入以库名命名的子文件夹")
		return
	}
	folderName := entry.RelPath[:sep]
	if !s.localSyncStable(entry.RelPath, entry.Size, entry.ModTime) {
		s.lsMarkWaiting(entry.RelPath, kind, "", entry.Size)
		return
	}
	match := localsync.MatchLibraryByDirName(folderName, libs)
	if match.Reject != "" {
		s.lsMarkFailed(entry.RelPath, kind, match.Library.Name, lsRejectReason(match.Reject), entry.Size)
		return
	}
	if paused {
		// 上传闸关闭：媒体保持等待（不算失败），闸开后的下一轮自动尝试。
		s.lsMarkWaiting(entry.RelPath, kind, match.Library.Name, entry.Size)
		return
	}
	s.processLocalSyncMedia(ctx, entry, match.Library, folderName, policy)
}

// localSyncStable 稳定性门：与上一轮观测 size/mtime 完全一致 且 mtime
// 年龄 >= StableAge 才放行（防半截拷贝——mtime 不停刷新的文件永远等不到
// 放行）。本轮观测总是覆写记录，与放行与否无关。
func (s *Server) localSyncStable(rel string, size int64, mtime time.Time) bool {
	s.localSync.mu.Lock()
	prev, seen := s.localSync.observations[rel]
	s.localSync.observations[rel] = localSyncObs{size: size, mtime: mtime}
	s.localSync.mu.Unlock()
	if !seen {
		return false
	}
	return prev.size == size && prev.mtime.Equal(mtime) && time.Since(mtime) >= s.localSyncStableAge()
}

// lsScanIgnoredReason 扫描侧忽略条目的状态面板原因（隐藏条目/符号链接）。
func lsScanIgnoredReason(e localsync.Entry) string {
	if e.IsSymlink {
		return "符号链接不跟随同步"
	}
	return "隐藏条目不是同步对象（点前缀）"
}

// lsOtherIgnoredReason 分类为 other 的条目原因：库文件夹内的 .txt 单独提示
// （作者表放错位置是最常见的用户错误），其余统一「非媒体扩展名」。
// TXT 判定复用 localsync.ClassifyRelPath 对基名的分类（.txt 知识单一来源）。
func lsOtherIgnoredReason(rel string) string {
	if localsync.ClassifyRelPath(path.Base(rel)) == localsync.KindTxt {
		return "库文件夹内不支持 TXT（作者表 TXT 请放同步根直接下）"
	}
	return "非媒体扩展名，不支持同步"
}

// lsRejectReason 库匹配拒绝原因码 → 状态面板中文文案（与 localsync 包的
// 拒绝码常量一一对应）。
func lsRejectReason(reject string) string {
	switch reject {
	case localsync.RejectNotFound:
		return "未找到与文件夹同名的库"
	case localsync.RejectAmbiguous:
		return "多个库的库名净化后同为该文件夹名，无法确定目标"
	case localsync.RejectDisabled:
		return "目标库已停用"
	case localsync.RejectCosKind:
		return "COS 库不经同步通道入库"
	default:
		return "库匹配失败: " + reject
	}
}

// lsValidateErrText filing 四道校验哨兵 → 状态面板中文文案（与上传端点的
// 协议错误码映射同源不同形：后台通道无响应码，只给人读文案）。
func lsValidateErrText(err error) string {
	switch {
	case errors.Is(err, filing.ErrUploadExtension):
		return "扩展名不在白名单"
	case errors.Is(err, filing.ErrUploadMimeMismatch):
		return "文件内容与扩展名不符（魔数嗅探失败）"
	case errors.Is(err, filing.ErrUploadTooLarge):
		return "文件超过大小上限"
	case errors.Is(err, filing.ErrUploadFilename):
		return "文件名不合法"
	default:
		return "校验失败: " + err.Error()
	}
}

// processLocalSyncMedia 媒体条目入库：与直传完全一致的校验（读头魔数 +
// 四道 ValidateUpload）与入库管线（ingestPlacedUpload），差异只有两点——
// 字节搬运用 MoveFile（同步根与库根可能跨卷）且落位前后各做一次 size 复核。
// 失败一律 lsMarkFailed（文件原地保留下轮重试）；落位后复核不符绝不删除
// 库位文件（那是全项目唯一副本），处置见 lsPlaceMediaInLibrary。
func (s *Server) processLocalSyncMedia(ctx context.Context, entry localsync.Entry, lib db.Library, folderName string, policy uploadPolicy) {
	kind := string(localsync.KindMedia)
	src := filepath.Join(s.cfg.LocalSync.Root, filepath.FromSlash(entry.RelPath))
	baseName := path.Base(entry.RelPath)
	head, err := readUploadHead(src)
	if err != nil {
		s.lsMarkFailed(entry.RelPath, kind, lib.Name, "读取文件头失败: "+err.Error(), entry.Size)
		return
	}
	if verr := filing.ValidateUpload(baseName, entry.Size, policy.maxBytes, head); verr != nil {
		s.lsMarkFailed(entry.RelPath, kind, lib.Name, lsValidateErrText(verr), entry.Size)
		return
	}
	mediaType, ok := scanner.ClassifyMedia(baseName)
	if !ok {
		// 防御分支：ValidateUpload 第①道已过白名单，理论不可达。
		s.lsMarkFailed(entry.RelPath, kind, lib.Name, "扩展名不在白名单", entry.Size)
		return
	}
	// 目标子路径 = 条目相对路径去掉首段（库文件夹），过红线 #1 规范化。
	subRel, err := filing.NormalizeRelPath(strings.TrimPrefix(entry.RelPath, folderName+string(localSyncRelSeparator)))
	if err != nil {
		s.lsMarkFailed(entry.RelPath, kind, lib.Name, "子路径非法: "+err.Error(), entry.Size)
		return
	}
	targetRel, targetAbs, place, perr := s.lsPlaceMediaInLibrary(lib, subRel, src, entry.Size)
	if perr != nil {
		switch {
		case errors.Is(perr, errUploadTargetEscape):
			s.lsMarkFailed(entry.RelPath, kind, lib.Name, "目标路径不合法", entry.Size)
		default:
			s.lsMarkFailed(entry.RelPath, kind, lib.Name, "移动文件入库失败: "+perr.Error(), entry.Size)
		}
		return
	}
	switch place {
	case lsPlaceSrcChanged:
		// 预复核不符：未搬动，源文件原地保留，下轮重新观测后再处理。
		s.lsMarkFailed(entry.RelPath, kind, lib.Name, "文件在处理间隙发生变化，本轮跳过", entry.Size)
		return
	case lsPlaceMovedBack:
		// 落位后复核不符：已移回同步源，源文件原地，下轮重新观测后再处理。
		s.lsMarkFailed(entry.RelPath, kind, lib.Name, "文件在搬移过程中发生变化，已移回同步源", entry.Size)
		return
	case lsPlaceKeptInLibrary:
		// 落位后复核不符且移回失败：库位文件是合法媒体件，删除即销毁唯一
		// 副本——保留交扫描器注册（复核不符的 Warn 已在复核内记录）。
		s.lsMarkSynced(entry.RelPath, kind, lib.Name, "落位后大小不一致且移回失败，保留库位待扫描器注册", entry.Size)
		return
	}
	if _, ierr := s.ingestPlacedUpload(ctx, lib.ID, targetAbs, targetRel, path.Base(targetRel), mediaType); ierr != nil {
		// 文件已在库位：注册失败不回滚 move（回滚丢库归属），交扫描器兜底
		//（与上传通道「DB 失败但文件已落盘」同语义，重试 move 会产生重复件）。
		s.logger.Warn("同步文件已落库位但注册失败，等待扫描器兜底注册",
			"rel", entry.RelPath, "library", lib.Name, "err", ierr)
		s.lsMarkSynced(entry.RelPath, kind, lib.Name, "已入库位，注册将由扫描器补齐", entry.Size)
		return
	}
	s.lsMarkSynced(entry.RelPath, kind, lib.Name, "", entry.Size)
	s.logger.Info("本机同步媒体入库完成", "rel", entry.RelPath, "library", lib.Name, "size", entry.Size)
}

// lsPlaceResult 落位链路（预复核 → 搬移 → 落位后复核）的处置结果：err
// 之外的软结果，决定条目最终落 failed（源还在同步根）还是 synced（文件已
// 成库内合法件）。
type lsPlaceResult int

const (
	// lsPlaceProceed 预复核与落位后复核全部通过，可继续入库。
	lsPlaceProceed lsPlaceResult = iota
	// lsPlaceSrcChanged 落位前预复核不符：未搬动，源文件原地保留。
	lsPlaceSrcChanged
	// lsPlaceMovedBack 落位后复核不符：已移回同步源，源文件原地保留。
	lsPlaceMovedBack
	// lsPlaceKeptInLibrary 落位后复核不符且移回失败：保留库位交扫描器注册。
	lsPlaceKeptInLibrary
)

// lsPlaceMediaInLibrary 把同步源文件搬进库内目标路径（subRel 已过
// NormalizeRelPath）：建子目录（锁外，幂等）→ WithLibraryGate 内「冲突
// 解析 → 落位前预复核 → MoveFile → 落位后 size 复核」关键段（与上传通道
// 同构，持锁时长与文件大小无关）。返回库内相对路径、绝对路径与处置结果；
// err 非空 = 硬失败（路径逃逸/搬移失败），此时文件未被搬动或搬移失败未落位。
func (s *Server) lsPlaceMediaInLibrary(lib db.Library, subRel, src string, expectSize int64) (string, string, lsPlaceResult, error) {
	dirRel := path.Dir(subRel) // "." = 库根
	if dirRel != "." {
		if err := os.MkdirAll(filepath.Join(lib.RootPath, filepath.FromSlash(dirRel)), dirPerm); err != nil {
			return "", "", lsPlaceProceed, err
		}
	}
	var targetRel, targetAbs string
	place := lsPlaceProceed
	gerr := filing.WithLibraryGate(lib.ID, func() error {
		targetDir := lib.RootPath
		if dirRel != "." {
			targetDir = filepath.Join(lib.RootPath, filepath.FromSlash(dirRel))
		}
		if !filing.PathWithinRoot(lib.RootPath, targetDir) {
			return errUploadTargetEscape
		}
		exists := func(n string) bool {
			_, serr := os.Stat(filepath.Join(targetDir, n))
			return serr == nil
		}
		finalName := filing.ResolveConflict(path.Base(subRel), exists)
		targetRel = path.Join(dirRel, finalName)
		targetAbs = filepath.Join(lib.RootPath, filepath.FromSlash(targetRel))
		if !filing.PathWithinRoot(lib.RootPath, targetAbs) {
			return errUploadTargetEscape
		}
		// 落位前预复核（对齐分片通道 placeSealedInLibrary 的 Seal 后落位前
		// 复核语义）：扫描观测/读头/校验与搬移之间源文件可能被改，size 不符
		// 或已消失就不搬动——此刻源文件还在同步根，条目按 failed 留待下轮
		// 重新观测。stat 与 MoveFile 相邻执行把竞态窗压到两条系统调用之间。
		st, serr := os.Stat(src)
		if serr != nil {
			s.logger.Warn("落位前预复核同步源失败，本轮跳过", "src", src, "err", serr.Error())
			place = lsPlaceSrcChanged
			return nil
		}
		if st.Size() != expectSize {
			place = lsPlaceSrcChanged
			return nil
		}
		if err := filing.MoveFile(src, targetAbs); err != nil {
			return err
		}
		// 落位后 size 复核（stat 与 MoveFile 两条系统调用间隙的极小竞态）：
		// 不符时绝不删除已落位文件——move 成功后源已不在，库位件是全项目
		// 唯一副本；移回同步源或保留库位，处置见 lsReconcilePlacedSize。
		// 忽略的第二返回值（不符原因 error）在该函数的非 proceed 分支内
		// 已 s.logger.Warn 记录（携 reason/moveBackErr），此处只消费处置
		// 枚举，原因在日志里不丢失。
		rplace, _ := s.lsReconcilePlacedSize(src, targetAbs, expectSize)
		switch rplace {
		case lsPlaceMovedBack:
			place = lsPlaceMovedBack
		case lsPlaceKeptInLibrary:
			place = lsPlaceKeptInLibrary
		}
		return nil
	})
	if gerr != nil {
		return targetRel, targetAbs, lsPlaceProceed, gerr
	}
	return targetRel, targetAbs, place, nil
}

// lsReconcilePlacedSize 落位后 size 复核与处置（可独立调用，函数级测试
// 锁行为）：目标与声明不符时优先 MoveFile 移回同步源（条目下轮重新观测），
// 移回失败则保留库位（扫描器会注册该合法媒体文件）。任何分支禁止删除——
// move 成功后库位件是全项目唯一副本，删除即销毁用户数据。返回处置结果；
// 结果非 lsPlaceProceed 时第二返回值携带不符原因（已记日志，供测试/调用方
// 诊断）。
func (s *Server) lsReconcilePlacedSize(srcAbs, targetAbs string, expect int64) (lsPlaceResult, error) {
	st, serr := os.Stat(targetAbs)
	if serr == nil && st.Size() == expect {
		return lsPlaceProceed, nil
	}
	var reason error
	if serr != nil {
		reason = fmt.Errorf("复核落位文件失败: %w", serr)
	} else {
		reason = fmt.Errorf("落位文件实际 %d 字节，与同步源观测 %d 字节不符", st.Size(), expect)
	}
	if mbErr := filing.MoveFile(targetAbs, srcAbs); mbErr != nil {
		s.logger.Warn("落位后大小不一致且移回同步源失败，保留库位待扫描器注册",
			"target", targetAbs, "src", srcAbs, "reason", reason.Error(), "moveBackErr", mbErr.Error())
		return lsPlaceKeptInLibrary, reason
	}
	s.logger.Warn("落位后大小不一致，已移回同步源", "target", targetAbs, "reason", reason.Error())
	return lsPlaceMovedBack, reason
}

// processLocalSyncTxt 作者表 TXT 导入：读文件（护栏上限内）→ 剥 BOM →
// UTF-8 校验 → importTxt（keep 语义，同文件名重导幂等）→ 归档移动到
// .synced/。导入成功但归档移动失败只标记 failed 备注——下轮重导入（覆盖
// 同名片段）+ 重移动自愈，绝不回滚已提交的导入事务。
func (s *Server) processLocalSyncTxt(ctx context.Context, entry localsync.Entry) {
	kind := string(localsync.KindTxt)
	if entry.Size > localSyncMaxTxtBytes {
		s.lsMarkFailed(entry.RelPath, kind, "", fmt.Sprintf("TXT 超过 %dMB 上限", localSyncMaxTxtBytes>>20), entry.Size)
		return
	}
	src := filepath.Join(s.cfg.LocalSync.Root, filepath.FromSlash(entry.RelPath))
	data, err := os.ReadFile(src)
	if err != nil {
		s.lsMarkFailed(entry.RelPath, kind, "", "读取 TXT 失败: "+err.Error(), entry.Size)
		return
	}
	data = bytes.TrimPrefix(data, utf8BOM)
	if !utf8.Valid(data) {
		s.lsMarkFailed(entry.RelPath, kind, "", "非 UTF-8 编码的 TXT 不支持", entry.Size)
		return
	}
	filename := path.Base(entry.RelPath)
	// 溯源章（ADR-0032）：无人值守自动导入通道，与手动 import-txt 区分。
	res, err := s.importTxt(ctx, &filename, string(data), resolutionKeep, store.OriginLocalSync)
	if err != nil {
		s.lsMarkFailed(entry.RelPath, kind, "", "导入失败: "+err.Error(), entry.Size)
		return
	}
	syncedDir := filepath.Join(s.cfg.LocalSync.Root, localsync.ReservedInternalDirName)
	if err := os.MkdirAll(syncedDir, dirPerm); err != nil {
		s.lsMarkFailed(entry.RelPath, kind, "", "导入成功但创建归档目录失败（内容已导入，下轮将重试归档）", entry.Size)
		return
	}
	exists := func(n string) bool {
		_, serr := os.Stat(filepath.Join(syncedDir, n))
		return serr == nil
	}
	archived := filing.ResolveConflict(filename, exists)
	if err := filing.MoveFile(src, filepath.Join(syncedDir, archived)); err != nil {
		s.lsMarkFailed(entry.RelPath, kind, "", "导入成功但归档移动失败（内容已导入，下轮将重试移动）", entry.Size)
		return
	}
	s.lsMarkSynced(entry.RelPath, kind, "", "", entry.Size)
	s.logger.Info("本机同步 TXT 导入完成", "rel", entry.RelPath,
		"authorsImported", lsIntDeref(res.AuthorsImported), "filesMatched", lsIntDeref(res.FilesMatched))
}

// lsIntDeref 协议导入结果的可空计数解引（nil 视为 0，日志用）。
func lsIntDeref(v *int) int {
	if v == nil {
		return 0
	}
	return *v
}
