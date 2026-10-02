// manager.go：快照管理器（调度 + 防重入 + 轮转 + 目录/文件管理）。
//
// 并发模型：Create 以互斥锁 + running 标志做防重入——定时调度与手动触发
// （httpapi 端点）共用同一 Manager 实例，同一时刻至多一个快照在跑；并发
// 触发时后到者得到 ErrInProgress（httpapi 映射 409），不排队不叠加。
package backup

import (
	"context"
	"errors"
	"fmt"
	"io/fs"
	"log/slog"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"sync"
	"time"
)

// DirName 是备份目录在 DataDir 下的子目录名（main 组装备份目录路径的
// 唯一来源；备份目录必须在 DataDir 内——库文件敏感数据不出私有数据根，
// 既有扫描自噬防线因此天然覆盖它）。
const DirName = "backups"

// snapshotNamePattern 是快照文件名的严格白名单（安全红线：下载/删除端点
// 的 {name} 参数以此校验，非法一律 400——名字完全由服务端生成，任何带
// 路径分隔符/扩展名变体的输入都视为探测攻击）。正则即文档：
// qimeng-YYYYMMDD-HHMMSS.db，与 timeLayout 一一对应。
const snapshotNamePattern = `^qimeng-\d{8}-\d{6}\.db$`

// timeLayout 是快照文件名内嵌的本地时间戳格式（任务书冻结：本地时间命名，
// 用户在文件管理器里直接可读）。
const timeLayout = "20060102-150405"

// nameRe 编译一次全局复用（List/Delete/Open/Create 轮转判定共用）。
var nameRe = regexp.MustCompile(snapshotNamePattern)

// 哨兵错误（httpapi 按错误类型映射状态码：ErrInProgress→409、
// ErrInvalidName→400、ErrNotFound→404）。
var (
	// ErrInProgress 表示已有快照在执行中（防重入闸）。
	ErrInProgress = errors.New("backup: 已有快照进行中")
	// ErrInvalidName 表示快照文件名未过白名单（路径穿越探测面）。
	ErrInvalidName = errors.New("backup: 快照文件名不合法")
	// ErrNotFound 表示目标快照不存在。
	ErrNotFound = errors.New("backup: 快照不存在")
)

// SnapshotFunc 是快照执行器签名（由 main 注入 store.VacuumInto 适配——
// SQL 属 store 边界，本包不碰数据库）。
type SnapshotFunc func(ctx context.Context, destPath string) error

// Info 是一份快照的元信息（httpapi 序列化为协议 BackupInfo）。
type Info struct {
	Name      string // 文件名（qimeng-YYYYMMDD-HHMMSS.db）
	SizeBytes int64  // 文件大小（字节）
	CreatedAt int64  // 完成时刻 Unix 毫秒
}

// Options 是 NewManager 的全部依赖（注入只在组装根发生）。
type Options struct {
	// Dir 备份目录绝对/相对路径（main 用 filepath.Join(cfg.DataDir, DirName) 组装）。
	Dir string
	// Snapshot 快照执行器（必填；nil 时 NewManager 报错——显式优于隐式 panic）。
	Snapshot SnapshotFunc
	// Retention 快照保留份数；<=0 时回落 DefaultBackupRetention。
	Retention int
	// Logger 结构化日志；nil 用 slog.Default。
	Logger *slog.Logger
	// Now 时钟注入（测试传固定时钟驱动命名与轮转，不依赖真实时间）。
	Now func() time.Time
	// OnSuccess 快照成功回调（指标埋点：main 接 sysmon gauge）；nil 可。
	OnSuccess func(at time.Time)
}

// DefaultBackupRetention 是保留份数默认值（7 份 ≈ 一周回滚窗口，运维含义
// 见 config.DefaultBackupRetention——同值不双写语义，backup 包引用 config
// 会引入反向耦合，这里独立成常量并在 NewManager 兜底，两处注释互指）。
// 同步责任：与 config.DefaultBackupRetention 语义一致，调整须两处同步。
const DefaultBackupRetention = 7

// Manager 管理快照的全生命周期（创建/列出/删除/打开）与定时调度。
type Manager struct {
	dir       string
	snapshot  SnapshotFunc
	retention int
	logger    *slog.Logger
	now       func() time.Time
	onSuccess func(at time.Time)

	// mu 保护 running（防重入闸）与轮转期间的列表一致性；
	// 快照执行（慢 IO）不持锁，只在进出闸时短暂持锁。
	mu      sync.Mutex
	running bool

	// schedMu 保护调度参数（retention/schedEnabled/schedInterval）与调度
	// 循环生命周期——热生效入口（StartScheduling/ApplySchedule/Schedule）
	// 与 rotate 读 retention 共用一把锁；调度循环体本身不持锁跑（与 Create
	// 的防重入闸解耦）。
	schedMu       sync.Mutex
	schedCtx      context.Context    // 调度循环生命周期根；nil = 装配根尚未挂（循环等挂后再起）
	schedCancel   context.CancelFunc // 当前循环的 cancel；nil = 无循环在跑
	schedEnabled  bool
	schedInterval time.Duration
}

// NewManager 组装快照管理器。目录不在此处创建（运行时惰性创建——首次
// 快照/列表时按需 MkdirAll，部署期无需预建）。
func NewManager(opts Options) (*Manager, error) {
	if opts.Dir == "" {
		return nil, errors.New("backup: Options.Dir 必填")
	}
	if opts.Snapshot == nil {
		return nil, errors.New("backup: Options.Snapshot 必填（store.VacuumInto 适配）")
	}
	retention := opts.Retention
	if retention <= 0 {
		retention = DefaultBackupRetention
	}
	logger := opts.Logger
	if logger == nil {
		logger = slog.Default()
	}
	now := opts.Now
	if now == nil {
		now = time.Now
	}
	return &Manager{
		dir:       opts.Dir,
		snapshot:  opts.Snapshot,
		retention: retention,
		logger:    logger,
		now:       now,
		onSuccess: opts.OnSuccess,
	}, nil
}

// Create 执行一次快照（VACUUM INTO → 轮转），返回新快照信息。
// 防重入：已有快照在跑时返回 ErrInProgress。
func (m *Manager) Create(ctx context.Context) (Info, error) {
	m.mu.Lock()
	if m.running {
		m.mu.Unlock()
		return Info{}, ErrInProgress
	}
	m.running = true
	m.mu.Unlock()
	// 快照执行不持锁：列表/删除/下载照常可用；轮转在闸内完成，避免
	// 「轮转删了正在生成的文件」的时序窗。
	defer func() {
		m.mu.Lock()
		m.running = false
		m.mu.Unlock()
	}()

	now := m.now().Local()
	name := "qimeng-" + now.Format(timeLayout) + ".db"
	if !nameRe.MatchString(name) {
		// 服务端自产名字过不了自家白名单 = 格式漂移事故，直接报错暴露。
		return Info{}, fmt.Errorf("backup: 生成的快照名 %q 未过白名单 %s", name, snapshotNamePattern)
	}
	if err := os.MkdirAll(m.dir, 0o755); err != nil {
		return Info{}, fmt.Errorf("backup: 创建备份目录 %s: %w", m.dir, err)
	}
	dest := filepath.Join(m.dir, name)
	if err := m.snapshot(ctx, dest); err != nil {
		return Info{}, fmt.Errorf("backup: 执行快照 %s: %w", name, err)
	}
	st, err := os.Stat(dest)
	if err != nil {
		return Info{}, fmt.Errorf("backup: 校验快照文件 %s: %w", name, err)
	}
	info := Info{Name: name, SizeBytes: st.Size(), CreatedAt: m.now().UnixMilli()}

	if err := m.rotate(); err != nil {
		// 轮转失败不否定本次快照（文件已落盘），记日志交运维处理。
		m.logger.Error("快照轮转失败（快照本身已成功）", "err", err, "dir", m.dir)
	}
	m.logger.Info("库文件快照完成", "name", name, "sizeBytes", info.SizeBytes)
	if m.onSuccess != nil {
		m.onSuccess(m.now())
	}
	return info, nil
}

// rotate 按「文件名字典序 = 时间序」（YYYYMMDD-HHMMSS 定长格式的性质）
// 升序排列后删除最旧的超出份数。在防重入闸内调用，不会碰到进行中的快照。
func (m *Manager) rotate() error {
	// retention 由 schedMu 保护（热生效可随时改），这里持锁快照读一次。
	m.schedMu.Lock()
	retention := m.retention
	m.schedMu.Unlock()
	infos, err := m.list()
	if err != nil {
		return err
	}
	if len(infos) <= retention {
		return nil
	}
	root, err := os.OpenRoot(m.dir)
	if err != nil {
		return fmt.Errorf("backup: 打开备份目录 %s: %w", m.dir, err)
	}
	defer func() { _ = root.Close() }()
	// list 已按名字升序（最旧在前）；从队首删到只剩 retention 份。
	for _, info := range infos[:len(infos)-retention] {
		if err := root.Remove(info.Name); err != nil {
			return fmt.Errorf("backup: 删除超龄快照 %s: %w", info.Name, err)
		}
		m.logger.Info("超龄快照已轮转删除", "name", info.Name, "retention", retention)
	}
	return nil
}

// list 读取备份目录全部合法快照（目录不存在 = 零快照，惰性创建语义），
// 按名字升序（最旧在前）返回。
func (m *Manager) list() ([]Info, error) {
	entries, err := os.ReadDir(m.dir)
	if err != nil {
		if errors.Is(err, fs.ErrNotExist) {
			return nil, nil
		}
		return nil, fmt.Errorf("backup: 读取备份目录 %s: %w", m.dir, err)
	}
	infos := make([]Info, 0, len(entries))
	for _, e := range entries {
		name := e.Name()
		if e.IsDir() || !nameRe.MatchString(name) {
			continue // 目录内非快照文件（外来文件）不碰不管，避免误伤
		}
		st, err := e.Info()
		if err != nil {
			return nil, fmt.Errorf("backup: 读取快照信息 %s: %w", name, err)
		}
		infos = append(infos, Info{Name: name, SizeBytes: st.Size(), CreatedAt: st.ModTime().UnixMilli()})
	}
	sort.Slice(infos, func(i, j int) bool { return infos[i].Name < infos[j].Name })
	return infos, nil
}

// List 返回快照列表（新→旧倒序，协议口径）。
func (m *Manager) List() ([]Info, error) {
	infos, err := m.list()
	if err != nil {
		return nil, err
	}
	// 倒序翻转：list 升序（最旧在前）→ 协议要新在前。
	for i, j := 0, len(infos)-1; i < j; i, j = i+1, j-1 {
		infos[i], infos[j] = infos[j], infos[i]
	}
	return infos, nil
}

// checkName 校验白名单并返回错误（Create 之外的三个文件访问入口共用）。
func checkName(name string) error {
	if !nameRe.MatchString(name) {
		return ErrInvalidName
	}
	return nil
}

// Delete 删除单份快照（物理删除；快照可再生，不走回收站——铁律 4 的
// 「媒体文件」语义不覆盖服务端自产的快照副本）。
// 双防线：白名单 + os.Root 锚定（即使白名单漏了路径段形态，Root 也拒绝逃逸）。
func (m *Manager) Delete(name string) error {
	if err := checkName(name); err != nil {
		return err
	}
	root, err := os.OpenRoot(m.dir)
	if err != nil {
		if errors.Is(err, fs.ErrNotExist) {
			return ErrNotFound // 目录都没建过 = 必然没有这份快照
		}
		return fmt.Errorf("backup: 打开备份目录 %s: %w", m.dir, err)
	}
	defer func() { _ = root.Close() }()
	if err := root.Remove(name); err != nil {
		if errors.Is(err, fs.ErrNotExist) {
			return ErrNotFound
		}
		return fmt.Errorf("backup: 删除快照 %s: %w", name, err)
	}
	m.logger.Info("快照已删除", "name", name)
	return nil
}

// OpenFile 打开单份快照供流式读取（httpapi 下载端点用），返回文件句柄与
// Stat 信息；调用方负责 Close。白名单 + os.Root 双防线同 Delete。
func (m *Manager) OpenFile(name string) (*os.File, fs.FileInfo, error) {
	if err := checkName(name); err != nil {
		return nil, nil, err
	}
	root, err := os.OpenRoot(m.dir)
	if err != nil {
		if errors.Is(err, fs.ErrNotExist) {
			return nil, nil, ErrNotFound
		}
		return nil, nil, fmt.Errorf("backup: 打开备份目录 %s: %w", m.dir, err)
	}
	// Root 只锚定"打开"动作：打开成功后立即关闭（见函数内注释），
	// 返回的 *os.File 生命周期归调用方。
	f, err := root.Open(name)
	if err != nil {
		_ = root.Close()
		if errors.Is(err, fs.ErrNotExist) {
			return nil, nil, ErrNotFound
		}
		return nil, nil, fmt.Errorf("backup: 打开快照 %s: %w", name, err)
	}
	st, err := f.Stat()
	if err != nil {
		_ = f.Close()
		_ = root.Close()
		return nil, nil, fmt.Errorf("backup: 读取快照信息 %s: %w", name, err)
	}
	// Root 句柄用完即关：它只锚定"打开"这一动作，已返回的 *os.File 独立
	// 有效（Windows 下不关会挂住目录句柄，TempDir/删除目录时撞占用）。
	_ = root.Close()
	return f, st, nil
}

// StartScheduling 挂调度循环生命周期根并应用初始调度参数（装配根 main 在
// ctx 建好后调用一次；enabled/interval/retention 来自启动配置叠加 kv 覆盖值，
// 三键裁决在调用方，本包不读 config 不碰数据库）。之后的热生效走 ApplySchedule。
func (m *Manager) StartScheduling(ctx context.Context, enabled bool, interval time.Duration, retention int) {
	m.schedMu.Lock()
	defer m.schedMu.Unlock()
	m.schedCtx = ctx
	m.applyScheduleLocked(enabled, interval, retention)
}

// ApplySchedule 使调度参数热生效（PUT /api/v1/backups/schedule 持久化成功后
// 调用；不重启进程）：
//   - retention 立即生效（下次轮转按新值）；
//   - enabled=false 停止定时调度（手动触发 POST /backups 不受影响）；
//   - interval 变化重置周期计时——首个快照在生效后一个新间隔触发，周期从
//     生效时刻重新起算（诚实口径：不追补「按旧周期本应发生的快照」）。
//
// 未挂生命周期根（装配根未调 StartScheduling）时只记参数不起循环——防御
// 装配时序，等挂根后再起（StartScheduling 内部同走 applyScheduleLocked）。
func (m *Manager) ApplySchedule(enabled bool, interval time.Duration, retention int) {
	m.schedMu.Lock()
	defer m.schedMu.Unlock()
	m.applyScheduleLocked(enabled, interval, retention)
}

// Schedule 返回当前生效的调度参数（GET /backups 的 schedule 回显来源；
// 未 StartScheduling 过的零值 Manager 返回零参数——装配根负责初始化）。
func (m *Manager) Schedule() (enabled bool, interval time.Duration, retention int) {
	m.schedMu.Lock()
	defer m.schedMu.Unlock()
	return m.schedEnabled, m.schedInterval, m.retention
}

// applyScheduleLocked 应用三键并让调度循环与新参数匹配（须持 schedMu）。
func (m *Manager) applyScheduleLocked(enabled bool, interval time.Duration, retention int) {
	if retention > 0 {
		m.retention = retention
	}
	m.schedEnabled = enabled
	if interval > 0 {
		m.schedInterval = interval
	}
	// 停旧循环（如有）：无论新参数为何，一律按当前参数重判是否起新循环——
	// 幂等语义「当前调度 = 最近一次 ApplySchedule 的参数，计时从生效起算」。
	if m.schedCancel != nil {
		m.schedCancel()
		m.schedCancel = nil
	}
	if !m.schedEnabled || m.schedInterval <= 0 || m.schedCtx == nil {
		return
	}
	loopCtx, cancel := context.WithCancel(m.schedCtx)
	m.schedCancel = cancel
	go m.scheduleLoop(loopCtx, m.schedInterval)
	m.logger.Info("备份调度已应用", "enabled", m.schedEnabled, "interval", m.schedInterval, "retention", m.retention)
}

// scheduleLoop 定时快照循环（goroutine，随 ctx 取消退出）。首个快照在生效后
// 一个间隔触发（不立即跑——启动期扫描/回填已在忙，立即快照抢 IO；要立即
// 备份走手动端点）。用 Timer 而非 Ticker：每轮完成后重置下一周期——慢快照
// 不会背靠背触发（Ticker 按固定节拍，快照耗时会吞拍；此差异语义更安全）。
// 调度失败只记日志不退出循环（下个周期自愈重试）。
func (m *Manager) scheduleLoop(ctx context.Context, interval time.Duration) {
	timer := time.NewTimer(interval)
	defer timer.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-timer.C:
			if _, err := m.Create(ctx); err != nil {
				// ErrInProgress 理论不可达（调度串行），出现说明有并发
				// 手动触发在跑——跳过本周期即可，不当作故障刷日志。
				if !errors.Is(err, ErrInProgress) {
					m.logger.Error("定时快照失败（下个周期自动重试）", "err", err)
				}
			}
			timer.Reset(interval)
		}
	}
}
