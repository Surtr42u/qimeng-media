package mediawrite

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"time"

	"qimeng-media/server/internal/filing"
	"qimeng-media/server/internal/store"
)

const repairRequestLimit = 64 * 1024
const repairBatchLimit = 32
const repairWindowLimit = 48 * time.Hour
const repairArchiveLimit = 4096

// UploadTimeRepairRequest 只由本地运维文件提供，默认预览；不提供HTTP入口。
type UploadTimeRepairRequest struct {
	From          string `json:"from"`
	To            string `json:"to"`
	Keyword       string `json:"keyword"`
	ExpectedCount int    `json:"expectedCount"`
	Apply         bool   `json:"apply"`
}

// RepairUploadTimes 在启动扫描/监听之前执行。只触碰明确时间窗和名称命中的小批原件，
// 以既有 created_at 校正文件 mtime；正常启动扫描将刷新数据库，身份及关联保持。
// report 保留改前元数据；中断后重入接受已校正文件，不把未知文件当作成功。
func RepairUploadTimes(ctx context.Context, conn *sql.DB, requestPath, archiveRoot string) error {
	f, err := os.Open(requestPath)
	if err != nil {
		return err
	}
	raw, readErr := io.ReadAll(io.LimitReader(f, repairRequestLimit+1))
	if err = errors.Join(readErr, f.Close()); err != nil {
		return err
	}
	if len(raw) > repairRequestLimit {
		return errors.New("校正请求过大")
	}
	var req UploadTimeRepairRequest
	if err = json.Unmarshal(raw, &req); err != nil {
		return err
	}
	from, e1 := time.Parse(time.RFC3339Nano, req.From)
	to, e2 := time.Parse(time.RFC3339Nano, req.To)
	if e1 != nil || e2 != nil || !to.After(from) || to.Sub(from) > repairWindowLimit || strings.TrimSpace(req.Keyword) == "" || req.ExpectedCount < 1 || req.ExpectedCount > repairBatchLimit {
		return errors.New("校正需要明确的至多48小时时间窗、名称与1至32项精确数量")
	}
	rows, err := store.UploadTimeRepairRows(ctx, conn, store.FormatTimestamp(from), store.FormatTimestamp(to), req.Keyword)
	if err != nil {
		return err
	}
	report := struct {
		Request UploadTimeRepairRequest
		Before  []store.UploadTimeRepairRow
		Applied bool
	}{req, rows, false}
	// 先持久化改前快照，失败时绝不改文件。外部报告含本机路径，仅留本地。
	writeReport := func() error {
		b, e := json.MarshalIndent(report, "", "  ")
		if e != nil {
			return e
		}
		return os.WriteFile(requestPath+".report.json", b, 0o600)
	}
	if err = writeReport(); err != nil {
		return err
	}
	if len(rows) != req.ExpectedCount {
		return fmt.Errorf("校正匹配数量为%d，要求%d；未修改文件", len(rows), req.ExpectedCount)
	}
	if req.Apply {
		// 先预检全部原件，数量/版本/路径不符时整批不动。
		for _, r := range rows {
			if err = checkRepairFile(r); err != nil {
				return err
			}
		}
		for _, r := range rows {
			if err = repairUploadFile(r, archiveRoot); err != nil {
				return err
			}
		}
		report.Applied = true
		if err = writeReport(); err != nil {
			return err
		}
	}
	return os.Remove(requestPath)
}

func checkRepairFile(r store.UploadTimeRepairRow) error {
	rel, err := filing.NormalizeRelPath(r.Rel)
	if err != nil || rel != r.Rel {
		return errors.New("校正路径不规范")
	}
	name := filepath.Join(r.Root, filepath.FromSlash(rel))
	if !filing.PathWithinRoot(r.Root, name) {
		return errors.New("校正路径含链接或越界")
	}
	info, err := os.Stat(name)
	if err != nil {
		return err
	}
	old, err := time.Parse(time.RFC3339Nano, r.Mtime)
	if err != nil {
		return err
	}
	added, err := time.Parse(time.RFC3339Nano, r.AddedAt)
	if err != nil {
		return err
	}
	if !info.Mode().IsRegular() || info.Size() != r.Size || (!info.ModTime().Equal(old) && !info.ModTime().Equal(added)) {
		return errors.New("校正原件版本变化")
	}
	return nil
}

func repairUploadFile(r store.UploadTimeRepairRow, archiveRoot string) error {
	if err := checkRepairFile(r); err != nil {
		return err
	}
	root, err := os.OpenRoot(r.Root)
	if err != nil {
		return err
	}
	defer filing.CloseRoot(root)
	stamp, err := time.Parse(time.RFC3339Nano, r.AddedAt)
	if err != nil {
		return err
	}
	if archiveRoot != "" {
		if err = repairArchiveTimes(r, archiveRoot, stamp); err != nil {
			return err
		}
	}
	if err = checkRepairFile(r); err != nil {
		return err
	}
	return root.Chtimes(filepath.FromSlash(r.Rel), stamp, stamp)
}

func repairArchiveTimes(r store.UploadTimeRepairRow, archiveRoot string, stamp time.Time) error {
	// 与 Android InboxFileStore.sanitizeLibraryDirName 同步：归档只在对应库一级目录。
	library := strings.Map(func(c rune) rune {
		if strings.ContainsRune("\\/:*?\"<>|", c) {
			return '_'
		}
		return c
	}, strings.TrimSpace(r.Library))
	if library == "" || library == "." || library == ".." || strings.HasSuffix(library, ".") {
		return errors.New("非法归档库名")
	}
	// Android /sdcard 是系统别名；解析归档根后，子路径仍按现有文件策略拒绝链接。
	base, err := filepath.EvalSymlinks(archiveRoot)
	if err != nil {
		return err
	}
	dir := filepath.Join(base, library)
	if !filing.PathWithinRoot(base, dir) {
		return errors.New("归档库目录含链接")
	}
	f, err := os.Open(dir)
	if os.IsNotExist(err) {
		return nil
	}
	if err != nil {
		return err
	}
	entries, readErr := f.ReadDir(repairArchiveLimit + 1)
	if errors.Is(readErr, io.EOF) {
		readErr = nil
	}
	if err = errors.Join(readErr, f.Close()); err != nil {
		return err
	}
	if len(entries) > repairArchiveLimit {
		return errors.New("归档库过大，拒绝不完整校正")
	}
	var sourceHash [sha256.Size]byte
	hashed := false
	for _, entry := range entries {
		if !entry.Type().IsRegular() {
			continue
		}
		info, e := entry.Info()
		if e != nil {
			return e
		}
		if info.Size() != r.Size {
			continue
		}
		name := filepath.Join(dir, entry.Name())
		if !filing.PathWithinRoot(base, name) {
			return errors.New("归档文件含链接")
		}
		if !hashed {
			sourceHash, e = repairFileHash(filepath.Join(r.Root, filepath.FromSlash(r.Rel)))
			if e != nil {
				return e
			}
			hashed = true
		}
		digest, e := repairFileHash(name)
		if e != nil {
			return e
		}
		if digest != sourceHash {
			continue
		}
		archive, e := os.OpenRoot(base)
		if e != nil {
			return e
		}
		e = archive.Chtimes(filepath.Join(library, entry.Name()), stamp, stamp)
		filing.CloseRoot(archive)
		if e != nil {
			return e
		}
	}
	return nil
}

func repairFileHash(name string) ([sha256.Size]byte, error) {
	var digest [sha256.Size]byte
	f, err := os.Open(name)
	if err != nil {
		return digest, err
	}
	hash := sha256.New()
	_, readErr := io.Copy(hash, f)
	if err = errors.Join(readErr, f.Close()); err != nil {
		return digest, err
	}
	copy(digest[:], hash.Sum(nil))
	return digest, nil
}
