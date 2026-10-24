package syncchannel

import (
	"context"
	"database/sql"
	"errors"
	"os"
	"path"
	"strings"
	"time"

	"qimeng-media/server/internal/authoring"
	"qimeng-media/server/internal/config"
	"qimeng-media/server/internal/localsync"
	"qimeng-media/server/internal/mediawrite"
	"qimeng-media/server/internal/safego"
	"qimeng-media/server/internal/store/db"
)

// localSyncRelSeparator 是条目相对路径的分段分隔符（RelPath 统一斜杠分隔，
// 与平台无关——路径来自 localsync.ScanTree 的 ToSlash 归一）。
const localSyncRelSeparator = '/'

// Start 组合根（main）调用一次：本机同步通道 daemon，
// ctx 取消即退出（与 StartTrashSweeper 同款生命周期模式）。ticker 与
// trigger 端点的 kick 信号同权触发一轮扫描；启动期记 Info 留痕配置。
func (s *Service) Start(ctx context.Context) {
	interval := s.Interval()
	s.logger.Info("本机自动同步通道已启动", "root", s.cfg.LocalSync.Root,
		"interval", interval.String(), "stableAge", s.StableAge().String())
	safego.Go(s.logger, "本机同步轮询", func() {
		ticker := time.NewTicker(interval)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				s.RunOnce(ctx)
			case <-s.localSync.kick:
				s.RunOnce(ctx)
			}
		}
	})
}

// Interval 生效轮询周期：<=0 回落 DefaultLocalSyncInterval
// （与 trashSweepInterval 同款兜底链——测试与内嵌形态可能手工构造 Config
// 不走 config.Load 的默认值链，ticker 周期为 0 会 panic）。
func (s *Service) Interval() time.Duration {
	if s.cfg.LocalSync.Interval > 0 {
		return s.cfg.LocalSync.Interval
	}
	return config.DefaultLocalSyncInterval
}

// StableAge 生效稳定门槛：<=0 回落 DefaultLocalSyncStableAge
// （同 Interval 的兜底链；门槛为 0 会让半截拷贝直接入库）。
func (s *Service) StableAge() time.Duration {
	if s.cfg.LocalSync.StableAge > 0 {
		return s.cfg.LocalSync.StableAge
	}
	return config.DefaultLocalSyncStableAge
}

// RunOnce 一轮同步扫描（runner 触发；测试直接调用注入目录）。
// 超函数警戒线（<=100 行）：直线编排，每步已拆小函数；再拆会把 alive 集合
// 与 lastErr 时序状态跨函数传递，得不偿失。
func (s *Service) RunOnce(ctx context.Context) {
	s.cycleMu.Lock()
	defer s.cycleMu.Unlock()
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
	plan, err := localsync.PlanScan(root, s.cfg.DataDir, libRoots)
	if err != nil {
		s.lsSetLastErr(err.Error())
		return
	}
	// 到这里通道健康：安全校验与扫描全部通过，条目级失败不改变通道状态。
	s.lsSetLastErr("")
	s.lsPrune(plan.Alive)
	for _, e := range plan.Ignored {
		s.lsMarkIgnored(e.RelPath, string(localsync.KindOther), lsScanIgnoredReason(e))
	}
	policy, paused := s.lsResolveUploadGate(ctx)
	s.lsSetPaused(paused)
	for _, entry := range plan.Entries {
		if ctx.Err() != nil {
			return
		}
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
func (s *Service) lsResolveUploadGate(ctx context.Context) (mediawrite.UploadPolicy, bool) {
	if _, err := s.q.GetSetting(ctx, authoring.SettingKeyClientConfig); err != nil && !errors.Is(err, sql.ErrNoRows) {
		s.logger.Warn("读取客户端配置失败，本轮媒体同步按暂停处理（TXT 不受影响）", "err", err)
		return mediawrite.UploadPolicy{}, true
	}
	policy := s.writes.UploadPolicy(ctx)
	return policy, policy.Disabled
}

// lsHandleEntry 单条目分派：按分类走 other（忽略）/txt（导入）/media（入库）。
func (s *Service) lsHandleEntry(ctx context.Context, entry localsync.Entry, libs []db.Library, policy mediawrite.UploadPolicy, paused bool) {
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
func (s *Service) lsHandleMedia(ctx context.Context, entry localsync.Entry, libs []db.Library, policy mediawrite.UploadPolicy, paused bool) {
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
func (s *Service) localSyncStable(rel string, size int64, mtime time.Time) bool {
	s.localSync.mu.Lock()
	prev, seen := s.localSync.observations[rel]
	s.localSync.observations[rel] = localSyncObs{size: size, mtime: mtime}
	s.localSync.mu.Unlock()
	if !seen {
		return false
	}
	return prev.size == size && prev.mtime.Equal(mtime) && time.Since(mtime) >= s.StableAge()
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
