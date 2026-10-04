package scanner

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"strconv"
	"strings"
	"sync/atomic"

	"golang.org/x/sync/errgroup"

	"qimeng-media/server/internal/authoring"
	"qimeng-media/server/internal/events"
	"qimeng-media/server/internal/sourcematcher"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// enrichRecomputeConcurrency 存量富化重算的并发度（RecomputeEnrichment）。
// SQLite 单写者，全靠并发只会放大争用；4 路已覆盖本地库的写耗时。
const enrichRecomputeConcurrency = 4

// EnrichmentEngineVersion 是出处/角色匹配引擎的语义版本（DOMAIN_RULES §4
// 「引擎版本自愈重算」）：kv 标记 authoring.SettingKeyEnrichmentEngineVersion
// 落后于它时，启动自愈对全部常规库重算富化。
//
// 何时 +1：引擎的匹配语义发生变化、导致同一文件名会得出不同出处/角色结果时
// 手动 +1（如兜底提取规则、停用词基线、别名归一规则的变更）。第 1 代 = 命名
// 规约兜底提取（extractTokens）+ 停用词层（builtinStopWords）。纯增补别名/
// 词条不改既有结果的算不升版。bump 时在本注释追加一行「N：改了什么」。
// 2：内置基线固化用户首批审定词条（跨部署一致，ADR-0033 补记三）——各部署
// 自愈重算后新词条生效。
const EnrichmentEngineVersion = 2

// enrich.go：扫描入库时的作者体系富化挂接（M3，DOMAIN_RULES §4/§6）。
//
// 两条互斥路径（按 libraries.kind 分派，0005 CHECK 只允许两种取值）：
//   - normal 库：SourceMatcher 按文件名匹配出处与角色（§4「匹配发生在
//     服务端扫描入库时」的落地），source 列随 UpsertAsset 写入、角色经
//     先删后插覆盖；
//   - cos 库：按目录结构建 cos_ 作者并直接记录 author→files 映射
//     （§6「COS 作者关联方式 = 扫描时记录映射」）；**不做** SourceMatcher
//     匹配——COS 文件出处走作者维度，与常规出处体系隔离（§6 隔离口径），
//     cos 库资产的 source 列保持为空。
//
// 覆盖语义（重扫描/重入库时富化结果如何刷新）：
//   - source 列：UpsertAsset 的 ON CONFLICT DO UPDATE 含 source =
//     excluded.source，mtime 变化重 ingest 天然按新文件名重算覆盖；
//   - 角色行：DeleteAssetCharacters + AddAssetCharacter 先删后插，
//     每次重 ingest 全量重算；
//   - 移动合并路径（applyMoveMerge）：normal 库不重算——合并条件是
//     size+mtime 完全一致（rename/move 不改内容），文件名不变则出处/角色
//     匹配结果不变，旧身份的富化数据继续有效；cos 库重算作者关联（作者
//     目录整体改名时首段目录变化，不重算会留下旧作者挂载、新作者缺失）；
//   - API 移动/重命名（filing）后 EnrichAsset 显式重算：normal=出处/角色
//     （文件名变而 size+mtime 不变，扫描不会重 ingest）、cos=作者关联与
//     cos_work（migration 0008，作品名同样由 rel_path 推导，语义同上）；
//   - cos 作者与资产关联经 AddAssetAuthor 幂等 DO NOTHING，重扫不翻倍。
//
// 自定义出处（§4「用户手动添加的分区名自动加入识别」）：Scanner 构造时从
// kv_settings（key=authoring.SettingKeyCustomSources）一次性装载；无记录
// 用空集（内置表仍完整可用）。运行期经 UpdateCustomSources 整体替换（写入
// 端点先持久化再同步），并靠 RecomputeEnrichment 对常规库存量资产显式重算
// ——资产 size+mtime 未变时全量扫描只会跳过（见 scanner.go 变更检测），
// 自定义出处变更不会自然传导到已入库的 source 列。

// LibraryKind 是 libraries.kind 的两个存储值（migrations/0005 CHECK 约束；
// 富化分派依据，禁止手抄字符串）。
const (
	LibraryKindNormal = "normal"
	LibraryKindCos    = "cos"
)

// SupportsAuthorAttach 报告库类型是否支持上传挂靠作者/来源：normal=true，
// cos=false（COS 作者由目录结构派生，挂靠无意义，REQ §3.2）。
// 这是「是否支持作者挂靠」能力声明的单一来源（ADR-0012 接入清单项）：
// 新增库类型在此注册；协议侧 Library.capabilities.authorAttach 的产出与
// 上传端点的挂靠参数校验都调用本函数，禁止在 handler 写死 kind 字符串。
func SupportsAuthorAttach(kind string) bool {
	return kind == LibraryKindNormal
}

// loadCustomSources 读取用户自定义出处名列表（JSON 字符串数组）。
// 任何失败（无记录/损坏 JSON）降级为空集——自定义出处是增强能力，
// 不允许它阻断扫描；损坏情况 warn 留痕便于排查。
func loadCustomSources(ctx context.Context, q *db.Queries, logger *slog.Logger) []string {
	v, err := q.GetSetting(ctx, authoring.SettingKeyCustomSources)
	if errors.Is(err, sql.ErrNoRows) {
		return nil
	}
	if err != nil {
		logger.Warn("scanner: 读取自定义出处失败（按空集处理）", "err", err)
		return nil
	}
	var names []string
	if err := json.Unmarshal([]byte(v), &names); err != nil {
		logger.Warn("scanner: 自定义出处 JSON 损坏（按空集处理）", "err", err)
		return nil
	}
	return names
}

// loadCustomGroups 读取用户自定义出处组（JSON 数组，元素 = 引擎输入形态
// sourcematcher.SourceGroup，ADR-0033）。任何失败（无记录/损坏 JSON）降级
// 为空集——同 loadCustomSources：自定义层是增强能力，不允许它阻断扫描；
// 损坏情况 warn 留痕便于排查。
func loadCustomGroups(ctx context.Context, q *db.Queries, logger *slog.Logger) []sourcematcher.SourceGroup {
	v, err := q.GetSetting(ctx, authoring.SettingKeyCustomSourceGroups)
	if errors.Is(err, sql.ErrNoRows) {
		return nil
	}
	if err != nil {
		logger.Warn("scanner: 读取自定义出处组失败（按空集处理）", "err", err)
		return nil
	}
	var groups []sourcematcher.SourceGroup
	if err := json.Unmarshal([]byte(v), &groups); err != nil {
		logger.Warn("scanner: 自定义出处组 JSON 损坏（按空集处理）", "err", err)
		return nil
	}
	return groups
}

// loadStopWords 读取停用词追加层（JSON 字符串数组，ADR-0033 端点 stopWords
// 字段；内置冻结基线在 sourcematcher.builtinStopWords，不入库）。任何失败
// （无记录/损坏 JSON）降级为空集——同 loadCustomSources：追加层是增强能力，
// 不允许它阻断扫描；损坏情况 warn 留痕便于排查。
func loadStopWords(ctx context.Context, q *db.Queries, logger *slog.Logger) []string {
	v, err := q.GetSetting(ctx, authoring.SettingKeyCustomStopWords)
	if errors.Is(err, sql.ErrNoRows) {
		return nil
	}
	if err != nil {
		logger.Warn("scanner: 读取停用词追加层失败（按空集处理）", "err", err)
		return nil
	}
	var words []string
	if err := json.Unmarshal([]byte(v), &words); err != nil {
		logger.Warn("scanner: 停用词追加层 JSON 损坏（按空集处理）", "err", err)
		return nil
	}
	return words
}

// runAssetTx 单事务执行 fn（qx = 事务绑定的 Queries；Commit/Rollback 由本
// 函数统一管理）。为什么需要它：原先多语句各自 autocommit 存在中间态窗口
// （资产已入库而角色行被清空/半写，注释自述靠自愈兜底），包事务后单资产
// 原子，语义只强不弱——重算/重 ingest 幂等路径不变。
// conn 未接线（nil，测试/裁剪形态）时退回逐语句 autocommit：事务化是
// 原子性增强而非前置条件，缺连接保底旧行为，零行为破坏。
func (s *Scanner) runAssetTx(ctx context.Context, fn func(qx *db.Queries) error) error {
	if s.conn == nil {
		return fn(s.q)
	}
	tx, err := s.conn.BeginTx(ctx, nil)
	if err != nil {
		return fmt.Errorf("scanner: 开启单资产事务: %w", err)
	}
	defer func() { _ = tx.Rollback() }() // 提交后 Rollback 是无害 no-op
	if err := fn(s.q.WithTx(tx)); err != nil {
		return err
	}
	if err := tx.Commit(); err != nil {
		return fmt.Errorf("scanner: 提交单资产事务: %w", err)
	}
	return nil
}

// ingestNormalFile normal 库入库：SourceMatcher 富化 + 资产落库 + 角色覆盖。
func (s *Scanner) ingestNormalFile(ctx context.Context, params db.UpsertAssetParams, fileName string) (db.Asset, error) {
	// MatchAll 输入形态 = 去扩展名前的原始文件名（Matcher 内部自行去扩展名，
	// 见 sourcematcher.MatchAll 注释）；带缓存，批量扫描 O(1) 命中。
	source, chars := s.matcher.MatchAll(fileName)
	// 未命中出处落 NULL（0001 注释：归「其他」由查询/展示层处理）。
	params.Source = sql.NullString{String: source, Valid: source != ""}

	var asset db.Asset
	// 资产 + 角色行单事务（原先逐语句 autocommit 的中间态窗口见
	// runAssetTx 注释）；保持单文件粒度，禁止整库一事务（扫描可取消，
	// 大事务既不可中断回吐进度也会长时间持写锁）。
	err := s.runAssetTx(ctx, func(qx *db.Queries) error {
		var err error
		asset, err = qx.UpsertAsset(ctx, params)
		if err != nil {
			return fmt.Errorf("scanner: 资产入库 %s: %w", params.RelPath, err)
		}
		// 角色行覆盖（先删后插）。
		if err := qx.DeleteAssetCharacters(ctx, asset.AssetID); err != nil {
			return fmt.Errorf("scanner: 清理角色 %s: %w", params.RelPath, err)
		}
		for _, c := range chars {
			if err := qx.AddAssetCharacter(ctx, db.AddAssetCharacterParams{
				AssetID: asset.AssetID, CharacterName: c,
				// 溯源章（ADR-0032）：角色行是扫描器派生数据，created_at 语义
				// = 本次重算时刻（先删后插，重算即刷新）。
				CreatedAt: store.NullTimestamp(store.FormatTimestamp(s.now())),
				Origin:    store.OriginScanner,
			}); err != nil {
				return fmt.Errorf("scanner: 写入角色 %s/%s: %w", params.RelPath, c, err)
			}
		}
		return nil
	})
	if err != nil {
		return db.Asset{}, err
	}
	return asset, nil
}

// ingestCosFile COS 库入库：rel 首段目录 = 作者名（DOMAIN_RULES §6 COS 目录
// 结构三种形态 `作者/文件`、`作者/作品/文件`、`作者/作品/子目录/文件` 的
// 首段恒为作者），第二段作品目录名落 cos_work 列（migration 0008——旧版
// 手机端把作品名当 COS 的角色维度用，GUIDE_ALGORITHM「COS 角色 = 作品名」；
// NULL = 无作品子目录，显示层兜底「其他」）。
func (s *Scanner) ingestCosFile(ctx context.Context, params db.UpsertAssetParams, rel string) (db.Asset, error) {
	// COS 文件不做 SourceMatcher 匹配（隔离口径见文件头注释）：source 留空。
	params.CosWork = nullCosWork(cosWorkOf(rel))
	asset, err := s.q.UpsertAsset(ctx, params)
	if err != nil {
		return db.Asset{}, fmt.Errorf("scanner: 资产入库 %s: %w", params.RelPath, err)
	}
	authorDir := firstDirSegment(rel)
	if authorDir == "" {
		// 库根直放文件没有作者目录语义，不建作者关联（正常 COS 库不会
		// 出现；宽容处理不阻断扫描）。
		return asset, nil
	}
	authorID := authoring.GenerateCosAuthorID(authorDir)
	if err := s.q.UpsertAuthor(ctx, db.UpsertAuthorParams{
		ID:          authorID,
		DisplayName: authorDir,
		Type:        authoring.AuthorTypeCos,
		CreatedAt:   params.CreatedAt,
		Origin:      store.OriginScanner, // 溯源章（ADR-0032）：COS 作者由目录结构派生
	}); err != nil {
		return db.Asset{}, fmt.Errorf("scanner: upsert COS 作者 %s: %w", authorDir, err)
	}
	if err := s.q.AddAssetAuthor(ctx, db.AddAssetAuthorParams{
		AssetID: asset.AssetID, AuthorID: authorID,
		CreatedAt: store.NullTimestamp(store.FormatTimestamp(s.now())),
		Origin:    store.OriginScanner,
	}); err != nil {
		return db.Asset{}, fmt.Errorf("scanner: 关联 COS 作者 %s: %w", authorDir, err)
	}
	return asset, nil
}

// firstDirSegment 取 rel 的首段目录名；无目录段（库根直放）返回空串。
func firstDirSegment(rel string) string {
	if i := strings.IndexByte(rel, '/'); i > 0 {
		return rel[:i]
	}
	return ""
}

// cosWorkOf 取 rel 的第二段目录名（COS 作品，migration 0008 列语义：
// `作者/作品/...` 去掉首段作者后的第一段）；`作者/文件` 与库根直放返回
// 空串（落库为 NULL）。与 0008 迁移回填 SQL 的推导口径逐字一致
// （instr/substr 两段定位），两处必须同步改。
func cosWorkOf(rel string) string {
	i := strings.IndexByte(rel, '/')
	if i < 0 {
		return ""
	}
	rest := rel[i+1:]
	j := strings.IndexByte(rest, '/')
	if j <= 0 {
		return ""
	}
	return rest[:j]
}

// nullCosWork 把作品名包成可空列值：空串 = 无作品子目录 = NULL（不是空串）。
func nullCosWork(work string) sql.NullString {
	return sql.NullString{String: work, Valid: work != ""}
}

// EnrichAsset 单资产重富化：API 移动/重命名（filing）成功后调用。文件名
// 变化会改变出处/角色匹配结果，而移动不改 size+mtime，下次扫描不会重
// ingest（移动合并路径见文件头注释），必须在写入路径显式重算。
// normal 库按文件名重算出处/角色；cos 库按当前 rel 首段目录重算作者关联。
func (s *Scanner) EnrichAsset(ctx context.Context, libraryID, assetID string) error {
	asset, err := s.q.GetAsset(ctx, assetID)
	if err != nil {
		return fmt.Errorf("scanner: 查询待富化资产 %s: %w", assetID, err)
	}
	lib, err := s.q.GetLibrary(ctx, libraryID)
	if err != nil {
		return fmt.Errorf("scanner: 查询待富化库 %s: %w", libraryID, err)
	}
	if lib.Kind == LibraryKindCos {
		return s.recomputeCosAuthor(ctx, asset.AssetID, asset.RelPath)
	}
	return s.recomputeNormalEnrichment(ctx, asset.AssetID, asset.FileName)
}

// recomputeNormalEnrichment 按文件名重算单资产的出处/角色（覆盖语义：
// 先删后插角色 + UpdateAssetSource，与 ingestNormalFile 的结果形态一致）。
// EnrichAsset（API 移动/改名）与 RecomputeEnrichment（自定义出处变更）
// 共用。单资产单事务（原先逐语句 autocommit 的中断窗口内角色缺失靠下次
// 重算自愈，事务化后窗口消除，见 runAssetTx 注释）。
func (s *Scanner) recomputeNormalEnrichment(ctx context.Context, assetID, fileName string) error {
	source, chars := s.matcher.MatchAll(fileName)
	// 保持单资产粒度事务：本函数被 RecomputeEnrichment/SelfHeal 的并发循环
	// 调用，整库一事务既不可中断也会长时间持写锁（SQLite 单写者）。
	return s.runAssetTx(ctx, func(qx *db.Queries) error {
		if err := qx.UpdateAssetSource(ctx, db.UpdateAssetSourceParams{
			Source:    sql.NullString{String: source, Valid: source != ""},
			UpdatedAt: store.FormatTimestamp(s.now()),
			AssetID:   assetID,
		}); err != nil {
			return fmt.Errorf("scanner: 更新出处 %s: %w", fileName, err)
		}
		if err := qx.DeleteAssetCharacters(ctx, assetID); err != nil {
			return fmt.Errorf("scanner: 清理角色 %s: %w", fileName, err)
		}
		for _, c := range chars {
			if err := qx.AddAssetCharacter(ctx, db.AddAssetCharacterParams{
				AssetID: assetID, CharacterName: c,
				CreatedAt: store.NullTimestamp(store.FormatTimestamp(s.now())),
				Origin:    store.OriginScanner,
			}); err != nil {
				return fmt.Errorf("scanner: 写入角色 %s/%s: %w", fileName, c, err)
			}
		}
		return nil
	})
}

// recomputeCosAuthor 按当前 rel 首段目录重载单资产的 COS 作者关联
// （先删后插覆盖，语义同角色行）：目录变了就挂新作者、清旧作者；
// 库根直放（无目录段）只清不挂——文件不属于任何作者目录。
// cos_work 列（migration 0008）随作者关联一起刷新：作品名由 rel_path
// 推导，移动/改名改变了 rel_path 却不改变 size+mtime（不会重 ingest），
// 必须在此显式覆盖（含清空情形——文件移出作品目录即解除关联）。
func (s *Scanner) recomputeCosAuthor(ctx context.Context, assetID, rel string) error {
	if err := s.q.UpdateAssetCosWork(ctx, db.UpdateAssetCosWorkParams{
		CosWork:   nullCosWork(cosWorkOf(rel)),
		UpdatedAt: store.FormatTimestamp(s.now()),
		AssetID:   assetID,
	}); err != nil {
		return fmt.Errorf("scanner: 更新 COS 作品 %s: %w", rel, err)
	}
	if err := s.q.DeleteAssetAuthorsByAssetID(ctx, assetID); err != nil {
		return fmt.Errorf("scanner: 清理 COS 作者关联 %s: %w", assetID, err)
	}
	authorDir := firstDirSegment(rel)
	if authorDir == "" {
		return nil
	}
	authorID := authoring.GenerateCosAuthorID(authorDir)
	if err := s.q.UpsertAuthor(ctx, db.UpsertAuthorParams{
		ID:          authorID,
		DisplayName: authorDir,
		Type:        authoring.AuthorTypeCos,
		CreatedAt:   store.FormatTimestamp(s.now()),
		Origin:      store.OriginScanner, // 溯源章（ADR-0032）
	}); err != nil {
		return fmt.Errorf("scanner: upsert COS 作者 %s: %w", authorDir, err)
	}
	if err := s.q.AddAssetAuthor(ctx, db.AddAssetAuthorParams{
		AssetID: assetID, AuthorID: authorID,
		CreatedAt: store.NullTimestamp(store.FormatTimestamp(s.now())),
		Origin:    store.OriginScanner,
	}); err != nil {
		return fmt.Errorf("scanner: 关联 COS 作者 %s: %w", authorDir, err)
	}
	return nil
}

// UpdateCustomSources 运行期整体替换用户自定义出处（写入端点调用；
// 构造期装载见 New/loadCustomSources）。落 matcher 即对后续全部匹配生效
// （含缓存清空），持久化由写入端点负责——此处保持无 IO。
func (s *Scanner) UpdateCustomSources(_ context.Context, names []string) {
	s.matcher.UpdateCustomSources(names)
}

// UpdateCustomGroups 运行期整体替换用户自定义出处组（ADR-0033 词表维护
// 端点调用；构造期装载见 New/loadCustomGroups）。落 matcher 即对后续全部
// 匹配生效（含缓存清空），持久化由写入端点负责——此处保持无 IO。
func (s *Scanner) UpdateCustomGroups(_ context.Context, groups []sourcematcher.SourceGroup) {
	s.matcher.UpdateCustomGroups(groups)
}

// UpdateStopWords 运行期整体替换停用词追加层（词表端点 stopWords 字段调用；
// 构造期装载见 New/loadStopWords）。落 matcher 即生效（含缓存清空），持久化
// 由写入端点负责——此处保持无 IO。
func (s *Scanner) UpdateStopWords(_ context.Context, words []string) {
	s.matcher.UpdateStopWords(words)
}

// relinkOrphanCosAssets 扫描收尾自愈：重挂零关联 COS 资产的作者。
//
// 为什么必须兜底：入库（UpsertAsset）与作者关联（AddAssetAuthor /
// recomputeCosAuthor）是两条独立语句，进程死亡或单语句失败会留下「有资产
// 无关联」的永久漏网者——隔离判定按作者关联走（DOMAIN_RULES §6），无关联
// 即漏进常规流（首页推荐）且从 COS tab 消失；而轮询扫描对已存在文件跳过
// re-ingest，永不自愈（2026-09-29 实证：蠢沫沫/水色/138.jpg 增量入库后进程
// 被杀，作者关联未落，出现在首页推荐）。applyMoveMerge 注释里的「重扫自愈」
// 由本函数兑现（重算失败的历史漏网者同样在此重挂）。
//
// 重算失败不阻断扫描：warn 后留待下轮扫描重试（幂等，零关联查询下一轮
// 仍会列出它）。成功即广播 library.changed——隔离判定变化（漏网者回归
// COS tab / 退出常规流）对客户端是结构性变更，推荐缓存需作废。
// 库根直放文件（rel 无目录段）合法无关联，SQL 侧已排除。
func (s *Scanner) relinkOrphanCosAssets(ctx context.Context, lib db.Library) {
	rows, err := s.q.ListCosAssetsWithoutAuthor(ctx, lib.ID)
	if err != nil {
		s.logger.Warn("scanner: 零关联 COS 资产查询失败（下轮重试）", "libraryId", lib.ID, "err", err)
		return
	}
	if len(rows) == 0 {
		return
	}
	relinked := 0
	for _, r := range rows {
		if err := s.recomputeCosAuthor(ctx, r.AssetID, r.RelPath); err != nil {
			s.logger.Warn("scanner: 零关联 COS 资产重挂作者失败（下轮重试）",
				"assetId", r.AssetID, "relPath", r.RelPath, "err", err)
			continue
		}
		relinked++
	}
	if relinked > 0 {
		s.logger.Info("scanner: 零关联 COS 资产已重挂作者",
			"libraryId", lib.ID, "relinked", relinked, "candidates", len(rows))
		s.publish(events.TopicLibraryChanged, ScanResult{LibraryID: lib.ID, Updated: relinked})
	}
}

// RecomputeEnrichment 对单库全部资产重算富化（自定义出处变更后的存量
// 重算：资产 size+mtime 未变，全量扫描只跳过不会重 ingest，必须显式触发）。
// normal 库重算出处/角色；cos 库无来源匹配语义，跳过。
// 与库扫描并发时不取闸门：两者写入的富化列同源（同一 matcher 实例），
// 交错不会破坏最终一致性；这是低频管理操作，不值得为它阻塞扫描。
func (s *Scanner) RecomputeEnrichment(ctx context.Context, libraryID string) error {
	lib, err := s.q.GetLibrary(ctx, libraryID)
	if err != nil {
		return fmt.Errorf("scanner: 查询待重算库 %s: %w", libraryID, err)
	}
	if lib.Kind == LibraryKindCos {
		return nil
	}
	rows, err := s.q.ListAssetsForEnrichmentByLibrary(ctx, libraryID)
	if err != nil {
		return fmt.Errorf("scanner: 载入库 %s 待重算资产: %w", libraryID, err)
	}
	var updated atomic.Int64
	g := errgroup.Group{}
	g.SetLimit(enrichRecomputeConcurrency)
	for _, r := range rows {
		r := r
		g.Go(func() error {
			if err := ctx.Err(); err != nil {
				return err
			}
			if err := s.recomputeNormalEnrichment(ctx, r.AssetID, r.FileName); err != nil {
				s.logger.Warn("scanner: 重算富化失败，跳过", "assetId", r.AssetID, "err", err)
				return nil
			}
			updated.Add(1)
			return nil
		})
	}
	if err := g.Wait(); err != nil {
		return err
	}
	if n := updated.Load(); n > 0 {
		s.publish(events.TopicLibraryChanged, ScanResult{LibraryID: libraryID, Updated: int(n)})
	}
	return nil
}

// SelfHealEnrichmentIfNeeded 引擎版本自愈重算（DOMAIN_RULES §4「引擎版本自愈
// 重算」）：kv 标记落后于 EnrichmentEngineVersion（含无标记的存量部署）时，
// 后台顺次对全部常规库重算富化并落新标记；持平则只做一次 kv 读直接返回。
// 幂等：与词表端点触发的 RecomputeEnrichment 同源同覆盖语义，重复触发无害。
// 由 cmd 启动接线调用（goroutine 内），错误只 warn 不阻断启动。
//
// 为什么必须自愈：存量资产的 size+mtime 未变，全量扫描只跳过不重 ingest，
// 引擎升级（如新增兜底提取/停用词层）后旧富化结果不会自然刷新；内嵌形态的
// 词表端点受一次性密钥保护、外部不可调，PUT 触发重算的通道对它不成立——
// 只有启动期自动传导才能覆盖内嵌/桌面壳/NAS 各形态。
func (s *Scanner) SelfHealEnrichmentIfNeeded(ctx context.Context) error {
	raw, err := s.q.GetSetting(ctx, authoring.SettingKeyEnrichmentEngineVersion)
	switch {
	case errors.Is(err, sql.ErrNoRows):
		// 无标记 = 存量部署（老版本从未写过）：按 0 处理，走全量重算。
		raw = "0"
	case err != nil:
		return fmt.Errorf("scanner: 读取引擎版本标记: %w", err)
	}
	cur, perr := strconv.Atoi(raw)
	if perr != nil {
		// 标记损坏按 0 处理：重算幂等，多算一次无害，少算才留脏数据。
		s.logger.Warn("scanner: 引擎版本标记损坏（按 0 处理，触发全量重算）",
			"raw", raw, "err", perr)
		cur = 0
	}
	if cur >= EnrichmentEngineVersion {
		return nil // 标记持平或超前（超前不该发生，宽容跳过）：零额外开销
	}
	s.logger.Info("scanner: 富化引擎版本落后，启动自愈重算",
		"stored", cur, "engine", EnrichmentEngineVersion)

	libs, err := s.q.ListLibraries(ctx)
	if err != nil {
		return fmt.Errorf("scanner: 列举待自愈库: %w", err)
	}
	// 逐库顺次重算（RecomputeEnrichment 内部自跳过 cos 库）：单库失败 warn
	// 继续——自愈是尽力而为的补偿动作，一个坏库不该挡住其余库与标记推进
	//（标记仍落：重算幂等，下版启动对失败库再来一遍）。
	for _, lib := range libs {
		if err := ctx.Err(); err != nil {
			return ctx.Err()
		}
		if err := s.RecomputeEnrichment(ctx, lib.ID); err != nil {
			s.logger.Warn("scanner: 自愈重算单库失败（跳过继续）",
				"libraryId", lib.ID, "err", err)
		}
	}
	if err := s.q.UpsertSetting(ctx, db.UpsertSettingParams{
		Key:       authoring.SettingKeyEnrichmentEngineVersion,
		Value:     strconv.Itoa(EnrichmentEngineVersion),
		UpdatedAt: store.FormatTimestamp(s.now()),
	}); err != nil {
		return fmt.Errorf("scanner: 落引擎版本标记: %w", err)
	}
	s.logger.Info("scanner: 富化引擎自愈重算完成", "engine", EnrichmentEngineVersion)
	return nil
}

// cleanupOrphanCosAuthors 清理孤立 COS 作者（作者目录的文件全部消失后
// 残留的零关联作者行）。全库范围：COS 作者只可能由 COS 库扫描产生，且
// 隔离口径下正常库资产不会关联 cos_ 作者，无需按库限定——
// 旧项目 deleteOrphanCosAuthors 即扫描后全库清理（GUIDE_DATA 语义）。
// 扫描收尾与增量删除路径都会调用（目录改名成孤立的作者立即被清，
// 不依赖轮询周期）。
func (s *Scanner) cleanupOrphanCosAuthors(ctx context.Context) {
	n, err := s.q.DeleteOrphanCosAuthors(ctx)
	if err != nil {
		s.logger.Warn("scanner: 清理孤立 COS 作者失败", "err", err)
		return
	}
	if n > 0 {
		s.logger.Info("scanner: 清理孤立 COS 作者", "count", n)
	}
}
