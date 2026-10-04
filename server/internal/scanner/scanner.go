// scanner.go：扫描主线编排（载入→遍历→差集→移动合并→删除→收尾）与
// Scanner 实例/闸门；富化挂接在 enrich.go，重挂自愈在 relink.go，增量
// 监听在 watch.go。
//
// 超过单文件 600 行警戒线理由：扫描编排流（闸门、变更检测、移动合并
// 启发式与差集对账）是一条端到端的状态机主线，各阶段共享 existing/seen/
// added/res 一组累积状态——拆文件会把同一组状态的读写摊到多处，编排
// 内聚优先于文件行数代价（子职责已按 enrich/relink/watch 拆出）。

package scanner

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"io/fs"
	"log/slog"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/google/uuid"

	"qimeng-media/server/internal/events"
	"qimeng-media/server/internal/sourcematcher"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
	"qimeng-media/server/internal/sysmon"
	"qimeng-media/server/internal/thumbnail"
)

// ErrAlreadyScanning 同一库的全量扫描正在进行中，本次触发被拒绝。
// 调用方（httpapi 触发端点 / 轮询 / Watch）用 errors.Is 区分"忙"与"失败"，
// 对"忙"的正确处理是稍后重试或直接忽略，不是报错给用户。
var ErrAlreadyScanning = errors.New("scanner: 该库扫描进行中")

// ScanPhase 是 scan.progress 事件的阶段字段值。
const (
	phaseWalking     = "walking"     // 遍历文件树+入库
	phaseReconciling = "reconciling" // 差集+移动合并+清理

	// defaultProgressMinEvery 扫描进度事件的最小发射间隔（节流默认值；
	// 调度参数禁止内联，见 AI_README_FIRST 代码卫生约束 5）。
	defaultProgressMinEvery = time.Second
)

// ScanResult 一次全量扫描的计数汇总（同时是 library.changed 事件 payload）。
// 字段与 api/openapi.yaml components/schemas/LibraryChangedEvent 一一对应
// （json tag 即协议字段名），改动双同步。为什么不直接引用 httpapi/gen 生成的
// 类型：依赖方向是 httpapi→业务模块（ARCHITECTURE §5），scanner 反向引用
// 协议生成层会把 openapi 泄漏进离线管线（与 thumbnail 包 Kind 的决策一致）。
type ScanResult struct {
	LibraryID string `json:"libraryId"`
	Added     int    `json:"added"`
	Updated   int    `json:"updated"`
	Moved     int    `json:"moved"`
	Removed   int    `json:"removed"`
	// Scanned 不进事件 payload：进度事件已携带，changed 只关心结果增量。
	Scanned int `json:"-"`
}

// scanProgressPayload scan.progress 事件 payload。
// 字段与 api/openapi.yaml components/schemas/ScanProgressEvent 一一对应，
// 改动双同步（json tag 即协议字段名）；total 是估算值——首扫为 0 起步随
// scanned 增长，复扫以库内记录数为基数。
type scanProgressPayload struct {
	LibraryID string `json:"libraryId"`
	Scanned   int    `json:"scanned"`
	Total     int    `json:"total"`
	Added     int    `json:"added"`
	Updated   int    `json:"updated"`
	Phase     string `json:"phase"`
}

// ProbeFunc 视频元数据探测签名（与 thumbnail.ProbeVideo 一致）。
// 做成类型是为了测试注入替身（计数/阻塞/失败注入），生产默认用真 ffprobe。
type ProbeFunc func(ctx context.Context, path string) (*thumbnail.ProbeResult, error)

// libraryGate 单库的并发闸门（ ARCHITECTURE §5「扫描器独立后台任务」的
// 落地件）。两个入口共用一把互斥锁，但对全量扫描提供 CAS 非阻塞入口：
//
//   - Scan（全量）：busy.CAS(idle→scanning) 抢不到立刻 ErrAlreadyScanning
//     （防重入是任务规格：同一库扫描进行中再触发必须立刻返回，不能排队——
//     轮询 ticker 与 Watch 触发若排队会积压出一串无用扫描）；
//   - processFile（Watch 增量单文件）：mu.Lock 阻塞等待——增量事件丢了
//     这一次就没了（fsnotify 不重放），必须等当前持闸者完成后补处理。
//
// 二者互斥的根因：移动合并依赖"扫描周期差集"的完整视野，增量 upsert 与
// 全量扫描并发写库会让差集计算看到中间态（如合并中途的新旧双记录）。
type libraryGate struct {
	busy atomic.Bool // 全量扫描非阻塞入口
	mu   sync.Mutex  // 全量扫描与增量处理的互斥（含等待语义）
}

// Scanner 媒体库扫描器（包注释见 doc.go）。
//
// 零值不可用，经 New 构造。Scan/Watch/StartBackground 可对同一实例并发调用；
// Scanner 按库隔离闸门，多库扫描互不阻塞。
type Scanner struct {
	q *db.Queries
	// conn 底层数据库连接（事务宿主）。q 只承载单语句，单资产多语句的
	// 原子性（enrich.go 富化事务）需要从 conn.BeginTx 起事务——sqlc 生成的
	// Queries 不导出底层 DBTX，拿不回来，只能显式注入。未接线（nil）时
	// 富化退回逐语句 autocommit（旧行为），保证测试/裁剪形态零破坏。
	conn   *sql.DB
	bus    *events.Bus
	logger *slog.Logger
	// probe 视频元数据探测（默认 thumbnail.ProbeVideo）。仅 VIDEO 类型调用；
	// 图片/动图不探测（见 ingestFile 注释）。
	probe ProbeFunc
	// deleteThumbs 资产内容变更后的缩略图失效钩子（生产装配传
	// thumbnail.Generator.DeleteAssetThumbs，main.go 单点接线）。nil 时跳过：
	// 仅测试与裁剪形态可容忍——生产漏注入不崩，但外部原地换文件后旧内容
	// 缩略图永不自愈（缓存键不含内容信号，见 invalidateThumbs），必须传。
	deleteThumbs func(assetID string)
	// now 时间源（created_at/updated_at/进度节流），测试可注入。
	now func() time.Time
	// progressMinEvery 两次 scan.progress 事件的最小间隔（默认 1s；
	// 大库按文件数节流，小库按时间节流——每文件都发会淹没 SSE）。
	// 0 表示每文件都发（测试用）。
	progressMinEvery time.Duration
	// watchDebounce Watch 防抖窗口（默认 DefaultDebounce，测试缩短用）。
	watchDebounce time.Duration
	// dataDir 服务端数据目录（DB/缩略图缓存/回收站）。若它被配置在某个库
	// 根内部，walk 与 watch 都必须跳过它——否则缩略图缓存（webp 是白名单
	// 格式）会被自己扫进库，系统自噬（M1 集成验收实测发现）。空串=不排除。
	dataDir string
	// matcher 出处/角色匹配引擎实例（DOMAIN_RULES §4「匹配发生在服务端
	// 扫描入库时」的持有方，enrich.go 落地）。构造时从 kv_settings 装载
	// 用户自定义出处；并发安全，按库/路径复用同一实例（匹配结果带缓存）。
	matcher *sourcematcher.Matcher

	gates sync.Map // libraryID -> *libraryGate
}

// New 构造扫描器。q/bus 必填（nil 会 panic 于首次使用，构造期不校验以保持
// 签名简单——M1 唯一调用方 cmd 传的都非 nil）；logger 为 nil 用 slog.Default()；
// dataDir 传服务端数据目录（防御嵌套配置，注册层另有互斥校验，这里是第二道防线）；
// probe 传视频元数据探测函数，nil = 默认 thumbnail.ProbeVideo（裸命令名走
// PATH 自动发现，行为零变化）——装配层传 Generator.ProbeVideo 使 ffprobe
// 路径配置（thumbnail.ffprobe_path）对扫描探测同样生效。
func New(q *db.Queries, bus *events.Bus, logger *slog.Logger, dataDir string, probe ProbeFunc) *Scanner {
	if logger == nil {
		logger = slog.Default()
	}
	if probe == nil {
		probe = thumbnail.ProbeVideo
	}
	s := &Scanner{
		q:                q,
		bus:              bus,
		logger:           logger,
		probe:            probe,
		now:              time.Now,
		progressMinEvery: defaultProgressMinEvery,
		watchDebounce:    DefaultDebounce,
		dataDir:          dataDir,
		matcher:          sourcematcher.New(0),
	}
	// 自定义出处装载（DOMAIN_RULES §4 + ADR-0033 出处组）：构造期一次性读取
	// kv_settings；无记录/失败用空集（内置表完整可用），见 enrich.go
	// loadCustomSources/loadCustomGroups。
	if names := loadCustomSources(context.Background(), q, logger); len(names) > 0 {
		s.matcher.UpdateCustomSources(names)
	}
	if groups := loadCustomGroups(context.Background(), q, logger); len(groups) > 0 {
		s.matcher.UpdateCustomGroups(groups)
	}
	if words := loadStopWords(context.Background(), q, logger); len(words) > 0 {
		s.matcher.UpdateStopWords(words)
	}
	return s
}

// SetThumbsInvalidator 接线内容变更的缩略图失效钩子（生产装配传
// thumbnail.Generator.DeleteAssetThumbs）。为什么后置 setter 而非 New 参数：
// 保持既有五参签名稳定（测试装配点不因新依赖变动），且与装配层既有的
// apiSrv.SetScanner 同款模式（main.go：先构造、后单点接线）。
// 不接线（nil）的后果：外部原地换文件后旧内容缩略图永不自愈——生产装配
// 必须调用（漏调是 F1 缺陷复现，见 invalidateThumbs）。
func (s *Scanner) SetThumbsInvalidator(deleteThumbs func(assetID string)) {
	s.deleteThumbs = deleteThumbs
}

// SetConn 接线事务宿主连接（生产装配传 store.Open 的返回值）。后置 setter
// 而非 New 参数的理由同 SetThumbsInvalidator：五参签名稳定，测试装配点不
// 因新依赖变动。不接线（nil）的后果：富化写入退回逐语句 autocommit
// （旧行为，见 conn 字段注释），生产装配必须调用。
func (s *Scanner) SetConn(conn *sql.DB) {
	s.conn = conn
}

// gate 取（或建）某库的闸门。sync.Map 而非锁+map：Scan 是热路径上的
// 只读访问，LoadOrStore 无锁快路径。
func (s *Scanner) gate(libraryID string) *libraryGate {
	v, _ := s.gates.LoadOrStore(libraryID, &libraryGate{})
	return v.(*libraryGate)
}

// beginScan 全量扫描的非阻塞入口：抢不到返回 false（→ ErrAlreadyScanning）。
func (g *libraryGate) beginScan() bool {
	if !g.busy.CompareAndSwap(false, true) {
		return false
	}
	g.mu.Lock()
	return true
}

// endScan 释放闸门（必须与成功 beginScan 配对，defer 调用）。
func (g *libraryGate) endScan() {
	g.mu.Unlock()
	g.busy.Store(false)
}

// Scan 对单个库做一次全量扫描：遍历 → 变更检测入库 → 差集 → 移动合并/清理。
// 全流程概览（各步细节在对应函数注释）：
//
//	载入库内现存记录（内存做变更检测，0 次逐文件 SQL）
//	→ filepath.WalkDir 逐文件：size+mtime 一致跳过（adr/0004 变更检测），
//	  否则探测元数据 + UpsertAsset（保身份语义在 UpsertAsset SQL 内实现）
//	→ 差集：现存记录 - 本周期见到的路径 = 消失集
//	  → 移动合并启发式（ARCHITECTURE §6）：消失记录 vs 本周期新增，
//	    size+mtime 完全一致判定为移动，保留旧 asset_id 只改路径属性
//	  → 无候选则删除记录（view_events 故意无外键，事件流天然保留，adr/0005）
//	→ 发 library.changed
//
// 超函数警戒线（>100 行）理由：扫描主线为顺序阶段流（载入→遍历→差集
// →移动合并→删除→收尾），阶段间共享 existing/seen/added/res 一组累积
// 状态；子步骤已拆 ingestFile/reconcile/deleteGone/applyMoveMerge，
// 主线剩余的是阶段编排本身。
func (s *Scanner) Scan(ctx context.Context, lib db.Library) (ScanResult, error) {
	start := s.now()
	g := s.gate(lib.ID)
	if !g.beginScan() {
		return ScanResult{}, fmt.Errorf("库 %s(%s): %w", lib.Name, lib.ID, ErrAlreadyScanning)
	}
	defer g.endScan()

	res := ScanResult{LibraryID: lib.ID}

	// 库根自动重挂（ADR-0025，relink.go）：根被改名/移动后先按资产样本找回；
	// 失败则保持既有失败行为——返回遍历错误（轮询下一周期再试，API 触发向
	// 调用方报错）。成功时 lib（本地副本）已持新根，本轮扫描继续正常执行。
	if err := s.ensureLibraryRoot(ctx, &lib); err != nil {
		return res, fmt.Errorf("scanner: 遍历库 %s: %w", lib.RootPath, err)
	}

	// 库内现存记录快照：变更检测查内存（每文件 0 次 SQL），差集也算它。
	existing, err := s.q.ListAssetsByLibrary(ctx, lib.ID)
	if err != nil {
		return res, fmt.Errorf("scanner: 载入库 %s 现存资产: %w", lib.ID, err)
	}
	known := make(map[string]db.Asset, len(existing))
	for _, a := range existing {
		known[a.RelPath] = a
	}

	// progress total 的估算基数：复扫时库内记录数就是文件数的下界估计；
	// 首扫为 0，随 scanned 增长修正（"可估"，任务规格）。
	totalHint := len(existing)
	seen := make(map[string]struct{}, len(existing))
	added := make([]db.Asset, 0, 16) // 本周期新插入（移动合并候选池）
	lastProgress := time.Time{}      // 零值：首个文件必发一次

	err = filepath.WalkDir(lib.RootPath, func(p string, d fs.DirEntry, walkErr error) error {
		if walkErr != nil {
			// 根目录都打不开（库路径没了/权限）：致命，中止让调用方看见。
			// 子项错误（单个目录权限毛刺）记日志跳过——NAS 上拒绝访问的
			// 角落目录不该毁掉整次扫描。
			if p == lib.RootPath {
				return walkErr
			}
			s.logger.Warn("scanner: 遍历失败，跳过", "path", p, "err", walkErr)
			if d != nil && d.IsDir() {
				return filepath.SkipDir
			}
			return nil
		}
		if err := ctx.Err(); err != nil {
			return err // WalkDir 不感知 ctx，在此主动检查（大库可取消）
		}
		if d.IsDir() {
			if p != lib.RootPath && SkipDir(d.Name()) {
				return filepath.SkipDir
			}
			// 数据目录（DB/缩略图/回收站）绝不能被扫进库（自噬防御，
			// 见 Scanner.dataDir 注释）。
			if s.dataDir != "" && samePath(p, s.dataDir) {
				return filepath.SkipDir
			}
			return nil
		}
		if IsHiddenName(d.Name()) {
			return nil
		}
		mediaType, ok := ClassifyMedia(d.Name())
		if !ok {
			return nil
		}
		info, err := d.Info()
		if err != nil {
			s.logger.Warn("scanner: stat 失败，跳过", "path", p, "err", err)
			return nil
		}

		rel, ok := relWithin(lib.RootPath, p)
		if !ok {
			s.logger.Warn("scanner: 路径规范化失败，跳过", "path", p)
			return nil
		}

		res.Scanned++
		// 变更检测（adr/0004）：size+mtime 都一致 → 完全跳过（连 ffprobe
		// 都不跑）。mtime 用同一函数格式化后比较，杜绝精度/时区差异误判。
		if cur, hit := known[rel]; hit &&
			cur.SizeBytes == info.Size() &&
			cur.Mtime == store.FormatTimestamp(info.ModTime()) {
			seen[rel] = struct{}{}
			return nil
		}

		asset, err := s.ingestFile(ctx, lib, p, rel, mediaType, info)
		if err != nil {
			// 单文件入库失败（探测除外）不应中止整库扫描：记录后继续。
			s.logger.Error("scanner: 入库失败，跳过", "path", p, "err", err)
			return nil
		}
		seen[rel] = struct{}{}
		if _, wasKnown := known[rel]; !wasKnown {
			added = append(added, asset)
			res.Added++
		} else {
			// 走到这里的已知路径必然经历了 size/mtime 变化（未变在上方变更
			// 检测已短路）＝外部原地替换文件成功重入库：失效旧内容缩略图。
			s.invalidateThumbs(asset.AssetID, rel)
			res.Updated++
		}

		if time.Since(lastProgress) >= s.progressMinEvery {
			s.publishProgress(lib.ID, res, max(totalHint, res.Scanned), phaseWalking)
			lastProgress = s.now()
		}
		return nil
	})
	if err != nil {
		return res, fmt.Errorf("scanner: 遍历库 %s: %w", lib.RootPath, err)
	}

	// 差集阶段进度（任务规格 phase 字段；此阶段无计数器可报，沿用累计值）。
	s.publishProgress(lib.ID, res, max(totalHint, res.Scanned), phaseReconciling)

	if err := s.reconcile(ctx, lib, existing, seen, added, &res); err != nil {
		return res, err
	}
	// 收尾清理：作者目录文件全消失后残留的孤立 COS 作者（随删空目录/
	// 改名目录一起消失的旧作者行），与 reconcile 的资产删除联动。
	s.cleanupOrphanCosAuthors(ctx)
	// COS 库收尾自愈：零关联资产重挂作者（入库/关联两语句的半途失败形态，
	// 漏进常规流的根因，为什么必须显式兜底见 relinkOrphanCosAssets 注释）。
	if lib.Kind == LibraryKindCos {
		s.relinkOrphanCosAssets(ctx, lib)
	}

	// 只在有实际变更时发 library.changed：轮询兜底每 5 分钟一次全量扫描，
	// 无条件广播会驱动所有在线端做无意义刷新。
	if res.Added+res.Updated+res.Moved+res.Removed > 0 {
		s.publish(events.TopicLibraryChanged, res)
	}
	// 扫描成功返回才更新 scan_duration_seconds（help「上次全量扫描耗时」；
	// 失败路径保留上次成功值，不污染语义）。API 触发与 watch 轮询两条入口
	// 都汇聚在本函数，此处单点覆盖。Set 而非 Observe：它是 Gauge。
	sysmon.Default.SetScanDuration(time.Since(start).Seconds())
	return res, nil
}

// invalidateThumbs 资产内容变更后的缩略图失效联动（DOMAIN_RULES §11 删除
// 时机联动的变更扩展，2026-10-01）：缩略图缓存键 = SHA-256(v2:assetID:size)，
// 不含内容信号，生成侧"存在即命中"+ HTTP immutable 一年——外部原地替换
// 文件（NAS 文件管理器直改，ADR-0004 支持的工作流）后重入库了元数据，
// 三端却会永远拿到旧内容的海报帧，永不自愈。重入库成功的瞬间删掉该资产
// 全部档位缓存，下次请求按新内容重建（键不变、内容换血）。DeleteAssetThumbs
// 本身幂等且尽力而为（失败只内部记日志），此处不重复错误处理。
// 全量扫描重探测与增量处理两条更新路径都必须走这里，漏一边就是增量窗口。
func (s *Scanner) invalidateThumbs(assetID, rel string) {
	if s.deleteThumbs == nil {
		return // 未装配（测试/裁剪形态）：生产装配必须注入，见字段注释
	}
	s.deleteThumbs(assetID)
	s.logger.Info("scanner: 资产内容变更，已失效缩略图缓存（下次请求按新内容重建）",
		"assetId", assetID, "relPath", rel)
}

// ingestFile 探测并入库单个文件（UpsertAsset 的身份保持语义——冲突时
// asset_id/created_at 不覆盖——在 SQL 内实现，这里只负责组装参数），并按
// 库类型分派作者体系富化（enrich.go：normal=SourceMatcher 出处/角色，
// cos=COS 作者目录映射）。全量扫描（Scan）与增量处理（watch.processFile）
// 共用本路径，两条入口的富化行为天然一致。
//
// 元数据探测策略（M1）：
//   - 仅 VIDEO 调 ffprobe（时长/宽高来自视频流，列表页与播放器立即要用）；
//   - 图片/动图不探测宽高：图片解码成本高（万级库首扫会拖慢一个数量级），
//     且缩略图管线（thumbnail 包）生成本就依赖解码、事后对账回填即可；
//   - 探测失败（损坏/半下载文件）不入错误：资产照常入库、元数据留空，
//     下次 size/mtime 变化时自然重探——下载中的文件频繁触发这种瞬态。
func (s *Scanner) ingestFile(ctx context.Context, lib db.Library, absPath, rel, mediaType string, info fs.FileInfo) (db.Asset, error) {
	nowStr := store.FormatTimestamp(s.now())
	params := db.UpsertAssetParams{
		AssetID:   newAssetID(),
		LibraryID: lib.ID,
		RelPath:   rel,
		FileName:  info.Name(),
		MediaType: mediaType,
		SizeBytes: info.Size(),
		Mtime:     store.FormatTimestamp(info.ModTime()),
		CreatedAt: nowStr,
		UpdatedAt: nowStr,
	}
	if mediaType == MediaTypeVideo {
		probeRes, err := s.probe(ctx, absPath)
		switch {
		case err != nil:
			s.logger.Warn("scanner: 视频元数据探测失败，元数据留空待重探",
				"path", rel, "err", err)
		case probeRes != nil:
			params.DurationMs = sql.NullInt64{Int64: probeRes.Duration.Milliseconds(), Valid: true}
			params.Width = sql.NullInt64{Int64: int64(probeRes.Width), Valid: true}
			params.Height = sql.NullInt64{Int64: int64(probeRes.Height), Valid: true}
			// 编码名（migration 0006）：空串转 NULL（消费侧 null = 未知）
			if probeRes.VideoCodec != "" {
				params.VideoCodec = sql.NullString{String: probeRes.VideoCodec, Valid: true}
			}
			if probeRes.AudioCodec != "" {
				params.AudioCodec = sql.NullString{String: probeRes.AudioCodec, Valid: true}
			}
		}
	}
	// 作者体系富化分派（enrich.go；kind 由 0005 CHECK 约束只可能为
	// normal/cos，default 按 normal 兜底）。
	if lib.Kind == LibraryKindCos {
		return s.ingestCosFile(ctx, params, rel)
	}
	return s.ingestNormalFile(ctx, params, info.Name())
}

// reconcile 扫描收尾对账：对"库内有记录但文件树没看到"的消失集执行
// 移动合并或删除，计数直接累计进 res（合并会把 walk 阶段预记的 Added
// 回退——被合并的新路径不是"新增"，是老身份换了住址）。
func (s *Scanner) reconcile(ctx context.Context, lib db.Library, existing []db.Asset, seen map[string]struct{}, added []db.Asset, res *ScanResult) error {
	// 无新增 → 不存在合并候选，全部消失走删除（常见稳态的快路径）。
	if len(added) == 0 {
		for _, a := range existing {
			if _, ok := seen[a.RelPath]; !ok {
				if err := s.deleteGone(ctx, lib, a); err != nil {
					return err
				}
				res.Removed++
			}
		}
		return nil
	}

	// 候选池用 slice 拷贝：被合并消费的候选要移除（一个新资产只能承接一个
	// 消失身份；两份相同文件消失+一份新文件出现的反向歧义按先到先得，
	// 多余的消失记录走删除）。
	candidates := make([]db.Asset, len(added))
	copy(candidates, added)

	for _, gone := range existing {
		if _, ok := seen[gone.RelPath]; ok {
			continue
		}
		idx, nMatch := pickMergeCandidate(candidates, gone)
		if idx < 0 {
			if err := s.deleteGone(ctx, lib, gone); err != nil {
				return err
			}
			res.Removed++
			continue
		}
		// 歧义审计（任务规格 + adr/0004 误判兜底）：多个候选与消失记录
		// size+mtime 一致（如批量拷贝同一文件后原件消失），无法确证承接者，
		// 取 created_at 最新者并 warn 留痕，事后可追溯/人工修正。
		if nMatch > 1 {
			s.logger.Warn("scanner: 移动合并候选歧义，取 created_at 最新者",
				"oldPath", gone.RelPath, "chosenNewPath", candidates[idx].RelPath,
				"candidates", nMatch)
		}
		cand := candidates[idx]
		candidates = append(candidates[:idx], candidates[idx+1:]...)
		if err := s.applyMoveMerge(ctx, lib, gone, cand); err != nil {
			return err
		}
		res.Moved++
		res.Added--
	}
	return nil
}

// deleteGone 删除一条消失记录（view_events 无外键，事件流天然保留）。
func (s *Scanner) deleteGone(ctx context.Context, lib db.Library, gone db.Asset) error {
	if err := s.q.DeleteAsset(ctx, gone.AssetID); err != nil {
		return fmt.Errorf("scanner: 删除消失资产 %s: %w", gone.RelPath, err)
	}
	s.logger.Info("scanner: 文件消失，删除记录（view_events 事件流保留）",
		"libraryId", lib.ID, "relPath", gone.RelPath, "assetId", gone.AssetID)
	return nil
}

// applyMoveMerge 把消失记录的身份搬到新路径（ARCHITECTURE §6 启发式）。
// 顺序严格：先删新插入的记录释放 (library_id, rel_path) 唯一索引，
// 再 UPDATE 旧身份的路径属性；颠倒会撞唯一约束。
// cos 库在改路径后按新 rel 重算作者关联（作者目录整体改名是移动合并的
// 典型场景：旧目录文件消失+新目录文件出现且 size+mtime 一致）；normal
// 库不重算——文件名不变，出处/角色匹配结果不变（文件头注释）。
func (s *Scanner) applyMoveMerge(ctx context.Context, lib db.Library, gone, cand db.Asset) error {
	if err := s.q.DeleteAsset(ctx, cand.AssetID); err != nil {
		return fmt.Errorf("scanner: 移动合并-删除新记录 %s: %w", cand.RelPath, err)
	}
	_, err := s.q.MoveAssetPath(ctx, db.MoveAssetPathParams{
		RelPath:   cand.RelPath,
		FileName:  cand.FileName,
		UpdatedAt: store.FormatTimestamp(s.now()),
		AssetID:   gone.AssetID,
	})
	if err != nil {
		return fmt.Errorf("scanner: 移动合并-改路径 %s→%s: %w", gone.RelPath, cand.RelPath, err)
	}
	if lib.Kind == LibraryKindCos {
		// 新路径的作者目录可能不同于旧目录：重算保证映射正确；失败只
		// 警告——重扫自愈（重扫按目录重建关联，见 ingestCosFile）。
		if err := s.recomputeCosAuthor(ctx, gone.AssetID, cand.RelPath); err != nil {
			s.logger.Warn("scanner: 移动合并后重算 COS 作者失败（重扫自愈）",
				"assetId", gone.AssetID, "err", err)
		}
	}
	// 审计日志（adr/0004「合并操作记录审计日志（可追溯）」）。
	s.logger.Info("scanner: 移动合并（身份保留，路径属性更新）",
		"assetId", gone.AssetID, "oldPath", gone.RelPath, "newPath", cand.RelPath)
	return nil
}

// pickMergeCandidate 在候选池中为消失记录找移动合并候选。
// 纯函数（领域规则，便于单测）：匹配条件 = size 与 mtime 字符串完全一致
// （rename/move 不改文件内容与 mtime；任何一项变了都视为"删旧+增新"）。
// 返回 (选中下标, 匹配数)；无候选时下标为 -1。
// 多候选歧义（同名同尺寸批量拷贝后原件消失）：取 (created_at, asset_id)
// 字典序最新者——created_at 同毫秒时 UUIDv7 的时间前缀保证 asset_id
// 字典序仍是时间序，排序确定性可复现。匹配数>1 由调用方 warn 留痕。
func pickMergeCandidate(candidates []db.Asset, gone db.Asset) (best, matches int) {
	best = -1
	for i, c := range candidates {
		if c.SizeBytes != gone.SizeBytes || c.Mtime != gone.Mtime {
			continue
		}
		matches++
		if best < 0 || c.CreatedAt > candidates[best].CreatedAt ||
			(c.CreatedAt == candidates[best].CreatedAt && c.AssetID > candidates[best].AssetID) {
			best = i
		}
	}
	return best, matches
}

// publishProgress 发一次进度事件（尽力而为：bus 关闭只 warn 不影响扫描）。
func (s *Scanner) publishProgress(libraryID string, res ScanResult, total int, phase string) {
	s.publish(events.TopicScanProgress, scanProgressPayload{
		LibraryID: libraryID,
		Scanned:   res.Scanned,
		Total:     total,
		Added:     res.Added,
		Updated:   res.Updated,
		Phase:     phase,
	})
}

func (s *Scanner) publish(topic string, payload any) {
	if err := s.bus.Publish(events.Event{Topic: topic, Payload: payload}); err != nil {
		s.logger.Warn("scanner: 进度事件发布失败（尽力而为，不影响扫描）",
			"topic", topic, "err", err)
	}
}

// relWithin 把库内绝对路径转成规范化相对路径（'/' 分隔，
// migrations/0001 assets.rel_path 约定）。ok=false 表示路径逃逸出库根
// （WalkDir 天然不会，此检查是防御性的红线兜底）。
func relWithin(root, abs string) (string, bool) {
	rel, err := filepath.Rel(root, abs)
	if err != nil || rel == ".." || strings.HasPrefix(rel, ".."+string(filepath.Separator)) || rel == "." {
		return "", false
	}
	return filepath.ToSlash(rel), true
}

// samePath 判断两个路径是否指向同一位置（大小写不敏感——Windows/NAS
// 文件系统通常不区分；Clean 收敛分隔符与冗余段差异）。dataDir 排除
// 判定用，误判的代价是自噬（见 Scanner.dataDir 注释），宁严勿松。
func samePath(a, b string) bool {
	return strings.EqualFold(filepath.Clean(a), filepath.Clean(b))
}

// pathInsideDataDir 判断绝对路径是否位于数据目录内（含等于），
// watch 事件过滤用（walk 侧用目录级 SkipDir，无需此函数）。
func (s *Scanner) pathInsideDataDir(abs string) bool {
	rel, err := filepath.Rel(s.dataDir, filepath.Clean(abs))
	if err != nil {
		return false
	}
	return rel != ".." && !strings.HasPrefix(rel, ".."+string(filepath.Separator))
}

// newAssetID 生成 UUIDv7（时间有序，migrations/0001 assets.asset_id 注释
// 的约定；v7 前缀含毫秒时间戳，批量插入的排序性与 keyset 分页的稳定性受益）。
func newAssetID() string {
	id, err := uuid.NewV7()
	if err != nil {
		// NewV7 仅在系统时钟倒退且随机源异常时失败，实践不可达；
		// 防御性回退 v4 保证扫描不因 ID 生成中断。
		return uuid.NewString()
	}
	return id.String()
}
