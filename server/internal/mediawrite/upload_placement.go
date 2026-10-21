package mediawrite

import (
	"errors"
	"fmt"
	"io"
	"os"
	"path"
	"path/filepath"

	"github.com/google/uuid"

	"qimeng-media/server/internal/filing"
	"qimeng-media/server/internal/store/db"
	"qimeng-media/server/internal/uploadsess"
)

// placeSealedInLibrary 把终结就绪的分片文件搬进库内目标目录（dir 空 = 库根，
// 与直传 dir 参数同语义）并解析冲突名：重活（跨卷拷贝预置）在库锁外、锁内
// 只做只读探测、size 复核与一次 rename——与直传 #9 关键段同构（持库锁时长
// 与文件大小无关）。dir 在 create 时已过 resolveUploadDir 规范化并存入会话
// （服务端持有，complete 不再收客户端路径）；baseDirAbs 的 PathWithinRoot 仍
// 保留为 handler 侧兜底（SECURITY 红线 1 纵深防御，与直传同一道闸）。预置/
// 放置失败清理临时副本；rename 成功后副本已不在（cleanup 报 NotExist 属正常）。
// 返回最终落盘名、库内相对路径与绝对路径（rel = dir/最终名，供入库管线）。
func (s *Service) PlaceSealed(lib db.Library, seal uploadsess.SealedSession) (string, string, string, error) {
	if seal.PlacedRel != "" {
		target := filepath.Join(lib.RootPath, filepath.FromSlash(seal.PlacedRel))
		if !filing.PathWithinRoot(lib.RootPath, target) {
			return "", "", "", ErrTargetEscape
		}
		return path.Base(seal.PlacedRel), seal.PlacedRel, target, nil
	}

	// 目标子目录（含校验兜底）：预置与最终落点同目录才能原子 rename——
	// 与直传「先建目录、临时文件与最终文件同目录」同一顺序（MkdirAll 幂等
	// 且并发安全，在 stageSealedFile 内完成）。
	baseDirAbs := filepath.Join(lib.RootPath, filepath.FromSlash(seal.Dir))
	if !filing.PathWithinRoot(lib.RootPath, baseDirAbs) {
		return "", "", "", ErrTargetEscape
	}
	root, err := os.OpenRoot(lib.RootPath)
	if err != nil {
		return "", "", "", err
	}
	defer filing.CloseRoot(root)
	if err := root.MkdirAll(filepath.FromSlash(path.Join(".", seal.Dir)), dirPerm); err != nil {
		return "", "", "", err
	}
	staging, cleanup, err := s.stageSealedFile(root, seal.Dir, seal.Path)
	if err != nil {
		return "", "", "", err
	}
	var (
		finalName string
		targetRel string
		targetAbs string
	)
	gerr := filing.WithLibraryGate(lib.ID, func() error {
		exists := func(n string) bool {
			_, serr := os.Stat(filepath.Join(baseDirAbs, n))
			return serr == nil
		}
		finalName = filing.ResolveConflict(seal.FileName, exists)
		targetRel = path.Join(seal.Dir, finalName)
		targetAbs = filepath.Join(lib.RootPath, filepath.FromSlash(targetRel))
		if !filing.PathWithinRoot(lib.RootPath, targetAbs) {
			return ErrTargetEscape
		}
		// Seal已禁止后续PATCH；落位前再拒绝外部篡改的大小不一致成品。
		st, serr := os.Stat(staging)
		if serr != nil {
			return fmt.Errorf("复核分片成品大小失败: %w", serr)
		}
		if st.Size() != seal.Size {
			s.logger.Warn("分片成品与会话声明大小不符，拒绝落位", "session", seal.ID,
				"declared", seal.Size, "actual", st.Size())
			return ErrSealedSizeMismatch
		}
		stagingRel, err := filepath.Rel(lib.RootPath, staging)
		if err != nil {
			return err
		}
		if err := root.Rename(stagingRel, filepath.FromSlash(targetRel)); err != nil {
			return err
		}
		return s.uploads.SetPlacement(seal.ID, targetRel)
	})
	if gerr != nil {
		cleanup()
		return "", "", "", gerr
	}
	return finalName, targetRel, targetAbs, nil
}

// ReadUploadHead 读取文件头部 RecommendedHeadBytes 字节（complete 的魔数
// 终检输入；短文件整体返回，与直传收流的 head 读取同语义）。
func ReadUploadHead(path string) ([]byte, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	head := make([]byte, filing.RecommendedHeadBytes)
	n, err := io.ReadFull(f, head)
	if err != nil && err != io.ErrUnexpectedEOF && err != io.EOF {
		if cerr := f.Close(); cerr != nil {
			return nil, errors.Join(err, cerr)
		}
		return nil, err
	}
	if cerr := f.Close(); cerr != nil {
		return nil, cerr
	}
	return head[:n], nil
}

// stageSealedFile 保留会话源，以同卷硬链接或跨卷流式复制预置隐藏临时件。
// 会话成功提交前不移交源所有权，失败后能重试；外部路径换链边界见SECURITY。
func (s *Service) stageSealedFile(root *os.Root, dir, sealedPath string) (string, func(), error) {
	stagingRel := filepath.FromSlash(path.Join(dir, UploadTmpPrefix+uuid.NewString()+uploadTmpSuffix))
	staging := filepath.Join(root.Name(), stagingRel)
	// 同卷硬链接不转移源所有权；封存后PATCH已禁止。无硬链接/跨卷走受控复制。
	if filing.PathWithinRoot(root.Name(), staging) {
		if err := os.Link(sealedPath, staging); err == nil {
			return staging, s.cleanStaging(root, stagingRel), nil
		}
	}

	src, err := os.Open(sealedPath)
	if err != nil {
		return "", nil, err
	}
	defer func() {
		if cerr := src.Close(); cerr != nil {
			s.logger.Warn("关闭分片源文件失败", "path", sealedPath, "err", cerr)
		}
	}()
	dst, err := root.OpenFile(stagingRel, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o600)
	if err != nil {
		return "", nil, err
	}
	if _, err := io.Copy(dst, src); err != nil {
		if cerr := dst.Close(); cerr != nil {
			s.logger.Warn("关闭分片预置文件失败", "path", staging, "err", cerr)
		}
		s.cleanStaging(root, stagingRel)()
		return "", nil, err
	}
	if err := dst.Close(); err != nil {
		s.cleanStaging(root, stagingRel)()
		return "", nil, err
	}
	return staging, s.cleanStaging(root, stagingRel), nil
}
