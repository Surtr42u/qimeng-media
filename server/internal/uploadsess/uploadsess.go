package uploadsess

import (
	"errors"
	"fmt"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"
)

// 协议/运维常量：单片上限与无活动回收时限已写进 api/openapi.yaml 的端点
// 描述（协议侧改动须同步此处，反之亦然——AI_README_FIRST 代码卫生约束 3）。
const (
	// MaxChunkBytes 单次 PATCH 分片上限（32MB）：弱网客户端按 1~8MB 切片
	// 足够，上限只为防单请求长期占住会话锁与失控请求体拖垮磁盘。
	MaxChunkBytes int64 = 32 << 20
	// SessionTTL 无追加/终结活动超过该时长的会话连同临时文件一起回收。
	// 断点续传面向"分钟级弱网中断"而非离线暂存，时限兜底防弃传会话的
	// 半成品永久占盘（complete 校验失败保留的可重试会话同此时钟）。
	SessionTTL = 24 * time.Hour
	// SweepInterval 过期清扫巡检间隔：一轮清扫是会话表遍历 + 临时目录
	// 一次读（轻 IO），小时级巡检让"过期"与"实际回收"的误差远小于 TTL
	// 粒度（与回收站清扫 config.DefaultTrashSweepInterval 同粒度惯例）。
	SweepInterval = time.Hour
)

// TempDirName 是临时分片目录名（相对服务端数据根 DataDir）。具名常量：
// DataDir/uploads-tmp 与库目录彻底隔离——未过四道校验的半成品绝不进库
//（ADR-0028），且备份/扫描/直链面都看不到它。导出供 httpapi 测试引用
// 路径时与实现同源（禁止测试手抄目录名字面量）。
const TempDirName = "uploads-tmp"

// 临时分片文件名前后缀：.part 在媒体扩展名白名单之外（filing 白名单），
// 即便目录被误配进库，扫描器也不会把半成品收进媒体面。
const (
	tempPrefix = "qimeng-upload-"
	tempSuffix = ".part"
)

// 会话操作哨兵错误（httpapi handler 据此映射 404/409/400/413）。
var (
	// ErrSessionNotFound 会话不存在或已被过期清扫回收（协议统一 404）。
	ErrSessionNotFound = errors.New("uploadsess: 上传会话不存在或已过期")
	// ErrOffsetMismatch 请求声明的 offset 与服务端权威 offset 不符（409，
	// 响应体为权威 UploadSession，由 handler 现查回填）。
	ErrOffsetMismatch = errors.New("uploadsess: offset 与服务端当前值不符")
	// ErrSizeOverflow 本次追加使累计字节超出会话声明的 size（400）。
	ErrSizeOverflow = errors.New("uploadsess: 累计字节超出会话声明的 size")
	// ErrChunkTooLarge 单次分片超过 MaxChunkBytes（413）。
	ErrChunkTooLarge = errors.New("uploadsess: 单次分片超过上限")
	// ErrNotComplete offset 未达 size 时终结（400，客户端应继续 PATCH）。
	ErrNotComplete = errors.New("uploadsess: 分片未传完")
	// ErrTempRoot 临时分片根目录不可用（创建/写入失败：磁盘满、权限收窄
	// 等部署问题，端点层映射 500——数据面不可用必须显性失败，不静默降级）。
	ErrTempRoot = errors.New("uploadsess: 分片临时目录不可用")
)

// Snapshot 是会话状态的对外只读视图（协议 UploadSession 载荷的直接来源）。
type Snapshot struct {
	ID     string
	Offset int64
	Size   int64
}

// SealedSession 是终结就绪的会话：offset 已达 size、临时文件已截断到权威
// 字节数。Path 供 httpapi 读文件头（魔数终检）并把文件搬进库；Dir 是创建时
// 校验规范化后的目标子目录（空 = 库根，语义与直传 dir 参数逐字一致）。
type SealedSession struct {
	ID        string
	LibraryID string
	FileName  string
	Dir       string
	Size      int64
	Path      string
}

// session 是单条上传会话。mu 串行化该会话的全部字节追加与终结（tus 的
// 单会话串行语义）；Manager.mu 只护注册表 map 本身，绝不嵌套 session.mu
// 之外的长时间持锁。
type session struct {
	mu           sync.Mutex
	id           string
	libraryID    string
	fileName     string // 已清洗（调用方经 filing.SanitizeFilename 后传入）
	dir          string // 已规范化（调用方经与直传同一校验后传入；空 = 库根）
	size         int64
	offset       int64 // 已提交的已收字节数（mu 内变更）
	tempPath     string
	lastActivity time.Time // 过期时钟基准（mu 内变更）
}

// Manager 是全部在途上传会话的持有者。now 注入时钟（测试拨动验证过期清扫，
// 与 httpapi 的 Now 注入同惯例）。
type Manager struct {
	mu       sync.Mutex
	sessions map[string]*session
	dataDir  string // 服务端私有数据根（分片临时根 = dataDir/tempDirName）
	now      func() time.Time
	logger   *slog.Logger
}

// New 构造 Manager。临时目录在首次 Create 时惰性创建——构造期零磁盘副作用
//（测试与内嵌形态组装不碰文件系统）；目录不可用时 Create 返回 ErrTempRoot。
func New(dataDir string, now func() time.Time, logger *slog.Logger) *Manager {
	if now == nil {
		now = time.Now
	}
	if logger == nil {
		logger = slog.Default()
	}
	return &Manager{sessions: map[string]*session{}, dataDir: dataDir, now: now, logger: logger}
}

// Create 新建会话并落一个空的临时分片文件，返回创建时快照（offset=0）。
// size/fileName/dir 的协议校验（白名单/上限/库检查/dir 规范化）由调用方
// 完成后才走到这里——dir 进会话后 complete 落位只认服务端持有的值，
// 不再收客户端路径（SECURITY 红线 1：会话内固定，无重放篡改面）。
func (m *Manager) Create(libraryID, fileName, dir string, size int64) (Snapshot, error) {
	root := m.tempRoot()
	// MkdirAll 幂等且并发安全：目录不可写/创建失败在此显性失败（ErrTempRoot）。
	if err := os.MkdirAll(root, 0o755); err != nil {
		return Snapshot{}, fmt.Errorf("%w: %s: %v", ErrTempRoot, root, err)
	}
	f, err := os.CreateTemp(root, tempPrefix+"*"+tempSuffix)
	if err != nil {
		return Snapshot{}, fmt.Errorf("%w: %s: %v", ErrTempRoot, root, err)
	}
	path := f.Name()
	if err := f.Close(); err != nil {
		// 半成品空文件即弃：留只会成为无主垃圾（孤儿清扫兜底，但能当场清就当场清）
		if rmErr := os.Remove(path); rmErr != nil && !os.IsNotExist(rmErr) {
			m.logger.Warn("清理创建失败的空分片文件失败", "path", path, "err", rmErr)
		}
		return Snapshot{}, fmt.Errorf("%w: %s: %v", ErrTempRoot, path, err)
	}
	s := &session{
		id:           uuid.NewString(),
		libraryID:    libraryID,
		fileName:     fileName,
		dir:          dir,
		size:         size,
		tempPath:     path,
		lastActivity: m.now(),
	}
	m.mu.Lock()
	m.sessions[s.id] = s
	m.mu.Unlock()
	return Snapshot{ID: s.id, Offset: 0, Size: s.size}, nil
}

// Snapshot 返回会话当前快照（断点探测 GET）。只读，不触碰过期时钟——
// 空轮询不能给弃传会话续命。
func (m *Manager) Snapshot(id string) (Snapshot, error) {
	s, ok := m.lookup(id)
	if !ok {
		return Snapshot{}, ErrSessionNotFound
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	return Snapshot{ID: s.id, Offset: s.offset, Size: s.size}, nil
}

// Append 从 offset 追加分片字节（r 为请求体流）。offset 不等于权威值 →
// ErrOffsetMismatch（一个字节都不写）；成功提交后 offset 前进并刷新过期时钟。
// 读流中断/写盘失败：回滚本片已写字节、会话保留（客户端可整片重试），
// 错误原样返回由 handler 决定协议映射。
func (m *Manager) Append(id string, offset int64, r io.Reader) (Snapshot, error) {
	s, ok := m.lookup(id)
	if !ok {
		return Snapshot{}, ErrSessionNotFound
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if offset != s.offset {
		return Snapshot{}, ErrOffsetMismatch
	}
	remaining := s.size - s.offset
	chunkCap := MaxChunkBytes
	if remaining < chunkCap {
		chunkCap = remaining
	}
	// 追加依赖不变式"成功路径下文件长度 == 已提交 offset"：全部追加被会话
	// 锁串行化、失败路径一律截断回滚到 s.offset，文件末尾即下一次追加起点，
	// 无需 seek。O_APPEND 让内核保证写到末尾，防御并发写句柄错位。
	f, err := os.OpenFile(s.tempPath, os.O_WRONLY|os.O_APPEND, 0o600)
	if err != nil {
		return Snapshot{}, fmt.Errorf("打开分片临时文件失败: %w", err)
	}
	// LimitReader(chunkCap+1)：多读 1 字节只为探测"源里还有更多"——超出
	// chunkCap 时按 chunkCap 形态区分两种协议语义（单片超限 413 / 累计超
	// 声明 size 400），多读的那 1 字节永不落盘。
	n, err := io.Copy(f, io.LimitReader(r, chunkCap+1))
	if err != nil {
		// 读流中断或写盘失败（含磁盘满）：会话保留可重试（ADR-0028），
		// 本片半途字节回滚，不留残余。
		if terr := f.Truncate(s.offset); terr != nil {
			m.logger.Warn("分片写入失败后回滚截断失败", "id", s.id, "err", terr)
		}
		if cerr := f.Close(); cerr != nil {
			m.logger.Warn("关闭分片临时文件失败", "id", s.id, "err", cerr)
		}
		return Snapshot{}, fmt.Errorf("写入分片失败: %w", err)
	}
	if cerr := f.Close(); cerr != nil {
		return Snapshot{}, fmt.Errorf("关闭分片临时文件失败: %w", cerr)
	}
	if n > chunkCap {
		if terr := os.Truncate(s.tempPath, s.offset); terr != nil {
			m.logger.Warn("超限分片回滚截断失败", "id", s.id, "err", terr)
		}
		if remaining >= MaxChunkBytes {
			return Snapshot{}, ErrChunkTooLarge
		}
		return Snapshot{}, ErrSizeOverflow
	}
	s.offset += n
	s.lastActivity = m.now()
	return Snapshot{ID: s.id, Offset: s.offset, Size: s.size}, nil
}

// Touch 刷新会话活动时刻。complete 尝试即使失败也计入过期时钟（可重试
// 会话保留到 SessionTTL 兜底回收为止，ADR-0028）。
func (m *Manager) Touch(id string) error {
	s, ok := m.lookup(id)
	if !ok {
		return ErrSessionNotFound
	}
	s.mu.Lock()
	s.lastActivity = m.now()
	s.mu.Unlock()
	return nil
}

// Seal 把会话推进到"终结就绪"：校验 offset == size，并把临时文件截断到
// 权威字节数（防御半途失败的 PATCH 在 offset 之后留下残余字节混进成品）。
// 会话保留——入库成败由调用方裁决何时 Drop。
func (m *Manager) Seal(id string) (SealedSession, error) {
	s, ok := m.lookup(id)
	if !ok {
		return SealedSession{}, ErrSessionNotFound
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.offset != s.size {
		return SealedSession{}, ErrNotComplete
	}
	f, err := os.OpenFile(s.tempPath, os.O_WRONLY, 0o600)
	if err != nil {
		return SealedSession{}, fmt.Errorf("打开分片临时文件失败: %w", err)
	}
	if err := f.Truncate(s.size); err != nil {
		if cerr := f.Close(); cerr != nil {
			m.logger.Warn("关闭分片临时文件失败", "id", s.id, "err", cerr)
		}
		return SealedSession{}, fmt.Errorf("截断分片临时文件失败: %w", err)
	}
	if err := f.Close(); err != nil {
		return SealedSession{}, fmt.Errorf("关闭分片临时文件失败: %w", err)
	}
	return SealedSession{ID: s.id, LibraryID: s.libraryID, FileName: s.fileName, Dir: s.dir, Size: s.size, Path: s.tempPath}, nil
}

// Drop 放弃会话：删临时文件（不存在不算错——complete 成功后文件已被移走）
// 并从注册表移除。会话不存在返回 ErrSessionNotFound（协议 404）。
func (m *Manager) Drop(id string) error {
	m.mu.Lock()
	s, ok := m.sessions[id]
	if ok {
		delete(m.sessions, id)
	}
	m.mu.Unlock()
	if !ok {
		return ErrSessionNotFound
	}
	if err := os.Remove(s.tempPath); err != nil && !os.IsNotExist(err) {
		return fmt.Errorf("删除分片临时文件失败: %w", err)
	}
	return nil
}

// SweepOnce 一轮过期清扫：回收无活动超 SessionTTL 的会话（连同临时文件），
// 另清孤儿分片文件（进程崩溃等留下的无主 .part，按文件 mtime 同 TTL 判定
// 且不属于任何在途会话）。清扫不产生资产，永远不 bump 库修订号。
// 返回（回收会话数, 清理孤儿文件数）供调用方记日志/指标。
func (m *Manager) SweepOnce() (expired, orphans int) {
	cutoff := m.now().Add(-SessionTTL)
	m.mu.Lock()
	victims := make([]*session, 0, len(m.sessions))
	for id, s := range m.sessions {
		if s.lastActivity.Before(cutoff) {
			delete(m.sessions, id)
			victims = append(victims, s)
		}
	}
	m.mu.Unlock()
	expired = len(victims)
	for _, s := range victims {
		if err := os.Remove(s.tempPath); err != nil && !os.IsNotExist(err) {
			// 文件删不掉（被占用等）只记日志：会话已出表，文件成为孤儿，
			// 由后续轮次的孤儿清理再试。
			m.logger.Warn("过期上传会话临时文件删除失败", "id", s.id, "err", err)
		}
	}
	// 孤儿清理：在途会话的文件受 live 表保护，其余按 mtime 判定。
	live := map[string]bool{}
	m.mu.Lock()
	for _, s := range m.sessions {
		live[s.tempPath] = true
	}
	m.mu.Unlock()
	entries, err := os.ReadDir(m.tempRoot())
	if err != nil {
		if !os.IsNotExist(err) {
			m.logger.Warn("分片临时目录遍历失败（本轮跳过孤儿清理）", "err", err)
		}
		return expired, orphans
	}
	for _, e := range entries {
		if !strings.HasPrefix(e.Name(), tempPrefix) || !strings.HasSuffix(e.Name(), tempSuffix) {
			continue
		}
		p := filepath.Join(m.tempRoot(), e.Name())
		if live[p] {
			continue
		}
		info, err := e.Info()
		if err != nil {
			m.logger.Warn("读取分片临时文件信息失败", "path", p, "err", err)
			continue
		}
		if !info.ModTime().Before(cutoff) {
			continue
		}
		if err := os.Remove(p); err != nil && !os.IsNotExist(err) {
			m.logger.Warn("孤儿分片临时文件删除失败", "path", p, "err", err)
			continue
		}
		orphans++
	}
	return expired, orphans
}

// Close 尽力清理全部在途会话与其临时文件（服务端优雅关停时调用）。会话
// 只在内存、进程退出即消失，这里清的是磁盘半成品；删不掉的（文件被占用）
// 汇总返回，由调用方记日志——遗留文件由下轮孤儿清理兜底。
func (m *Manager) Close() error {
	m.mu.Lock()
	remaining := m.sessions
	m.sessions = map[string]*session{}
	m.mu.Unlock()
	var errs []error
	for id, s := range remaining {
		if err := os.Remove(s.tempPath); err != nil && !os.IsNotExist(err) {
			errs = append(errs, fmt.Errorf("会话 %s: %w", id, err))
		}
	}
	return errors.Join(errs...)
}

// lookup 取会话（不锁 session）。
func (m *Manager) lookup(id string) (*session, bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	s, ok := m.sessions[id]
	return s, ok
}

// tempRoot 返回分片临时根目录（DataDir/TempDirName）。
func (m *Manager) tempRoot() string {
	return filepath.Join(m.dataDir, TempDirName)
}
