package syncchannel

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"os"
	"path"
	"path/filepath"
	"unicode/utf8"

	"qimeng-media/server/internal/authorimport"
	"qimeng-media/server/internal/localsync"
	"qimeng-media/server/internal/store"
)

// Windows 记事本等文本常带 UTF-8 BOM，去除后复用同一导入解析器。
var utf8BOM = []byte{0xEF, 0xBB, 0xBF}

// processLocalSyncTxt 作者表 TXT 导入：读文件（护栏上限内）→ 剥 BOM →
// UTF-8 校验 → importTxt（keep 语义，同文件名重导幂等）→ 归档移动到
// .synced/。导入成功但归档移动失败只标记 failed 备注——下轮重导入（覆盖
// 同名片段）+ 重移动自愈，绝不回滚已提交的导入事务。
func (s *Service) processLocalSyncTxt(ctx context.Context, entry localsync.Entry) {
	kind := string(localsync.KindTxt)
	if entry.Size > localSyncMaxTxtBytes {
		s.lsMarkFailed(entry.RelPath, kind, "", fmt.Sprintf("TXT 超过 %dMB 上限", localSyncMaxTxtBytes>>20), entry.Size)
		return
	}
	src := filepath.Join(s.cfg.LocalSync.Root, filepath.FromSlash(entry.RelPath))
	data, err := readSyncTXT(s.cfg.LocalSync.Root, entry.RelPath)
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
	res, err := s.txt.Import(ctx, &filename, string(data), authorimport.Keep, store.OriginLocalSync)
	if err != nil {
		s.lsMarkFailed(entry.RelPath, kind, "", "导入失败: "+err.Error(), entry.Size)
		return
	}
	// 镜像刷新（尽力而为，与手动导入同口径；2026-10-11 补——此前本通道导入
	// 不刷镜像，无人值守路径下桌面镜像会长期落后于片段真相）。
	s.mirror.Refresh(ctx, s.q)
	syncedDir := filepath.Join(s.cfg.LocalSync.Root, localsync.ReservedInternalDirName)
	if err := os.MkdirAll(syncedDir, dirPerm); err != nil {
		s.lsMarkFailed(entry.RelPath, kind, "", "导入成功但创建归档目录失败（内容已导入，下轮将重试归档）", entry.Size)
		return
	}
	// 同名归档只留最新（2026-10-11 用户定规，ADR-0030 修订1）：重导同名 TXT 时
	// 覆盖旧归档副本，不再按「同名加序号」堆积 (2)/(3)… 副本——旧副本按设计让位
	// （内容已入库，最新版即真相）；清理失败与移动失败同走 failed 备注，源文件
	// 留在同步根，下轮幂等重导 + 重归档自愈。
	archived := filepath.Join(syncedDir, filename)
	if _, serr := os.Stat(archived); serr == nil {
		if rerr := os.Remove(archived); rerr != nil {
			s.lsMarkFailed(entry.RelPath, kind, "", "导入成功但清理旧归档失败（内容已导入，下轮将重试归档）", entry.Size)
			return
		}
	}
	if err := s.moveFile(src, archived); err != nil {
		s.lsMarkFailed(entry.RelPath, kind, "", "导入成功但归档移动失败（内容已导入，下轮将重试移动）", entry.Size)
		return
	}
	s.lsMarkSynced(entry.RelPath, kind, "", "", entry.Size)
	s.logger.Info("本机同步 TXT 导入完成", "rel", entry.RelPath,
		"authorsImported", res.AuthorsImported, "filesMatched", res.FilesMatched)
}

// 扫描后文件仍可能增长；实际读取也限流，不能只相信扫描时的 Size。
func readSyncTXT(root, rel string) ([]byte, error) {
	f, err := os.OpenInRoot(root, filepath.FromSlash(rel))
	if err != nil {
		return nil, err
	}
	data, readErr := io.ReadAll(io.LimitReader(f, localSyncMaxTxtBytes+1))
	closeErr := f.Close()
	if readErr != nil {
		return nil, readErr
	}
	if closeErr != nil {
		return nil, closeErr
	}
	if int64(len(data)) > localSyncMaxTxtBytes {
		return nil, fmt.Errorf("TXT 超过 %dMB 上限", localSyncMaxTxtBytes>>20)
	}
	return data, nil
}
