package scanner

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"strings"

	"qimeng-media/server/internal/authoring"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

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
//   - 移动合并路径（applyMoveMerge）不重算：合并条件是 size+mtime 完全
//     一致（rename/move 不改内容），文件名不变则出处/角色匹配结果不变，
//     旧身份的富化数据继续有效；
//   - cos 作者与资产关联经 AddAssetAuthor 幂等 DO NOTHING，重扫不翻倍。
//
// 自定义出处（§4「用户手动添加的分区名自动加入识别」）：Scanner 构造时从
// kv_settings（key=authoring.SettingKeyCustomSources）一次性装载；无记录
// 用空集（内置表仍完整可用）。

// LibraryKind 是 libraries.kind 的两个存储值（migrations/0005 CHECK 约束；
// 富化分派依据，禁止手抄字符串）。
const (
	LibraryKindNormal = "normal"
	LibraryKindCos    = "cos"
)

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

// ingestNormalFile normal 库入库：SourceMatcher 富化 + 资产落库 + 角色覆盖。
func (s *Scanner) ingestNormalFile(ctx context.Context, params db.UpsertAssetParams, fileName string) (db.Asset, error) {
	// MatchAll 输入形态 = 去扩展名前的原始文件名（Matcher 内部自行去扩展名，
	// 见 sourcematcher.MatchAll 注释）；带缓存，批量扫描 O(1) 命中。
	source, chars := s.matcher.MatchAll(fileName)
	// 未命中出处落 NULL（0001 注释：归「其他」由查询/展示层处理）。
	params.Source = sql.NullString{String: source, Valid: source != ""}

	asset, err := s.q.UpsertAsset(ctx, params)
	if err != nil {
		return db.Asset{}, fmt.Errorf("scanner: 资产入库 %s: %w", params.RelPath, err)
	}
	// 角色行覆盖（先删后插）：非单事务——中断窗口内角色缺失，下次该文件
	// size/mtime 变化重 ingest 时自愈；换来的是 ingestFile 不必持有事务。
	if err := s.q.DeleteAssetCharacters(ctx, asset.AssetID); err != nil {
		return db.Asset{}, fmt.Errorf("scanner: 清理角色 %s: %w", params.RelPath, err)
	}
	for _, c := range chars {
		if err := s.q.AddAssetCharacter(ctx, db.AddAssetCharacterParams{
			AssetID: asset.AssetID, CharacterName: c,
		}); err != nil {
			return db.Asset{}, fmt.Errorf("scanner: 写入角色 %s/%s: %w", params.RelPath, c, err)
		}
	}
	return asset, nil
}

// ingestCosFile COS 库入库：rel 首段目录 = 作者名（DOMAIN_RULES §6 COS 目录
// 结构三种形态 `作者/文件`、`作者/作品/文件`、`作者/作品/子目录/文件` 的
// 首段恒为作者），作品名不落库、保留在 relPath 中。
func (s *Scanner) ingestCosFile(ctx context.Context, params db.UpsertAssetParams, rel string) (db.Asset, error) {
	// COS 文件不做 SourceMatcher 匹配（隔离口径见文件头注释）：source 留空。
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
	}); err != nil {
		return db.Asset{}, fmt.Errorf("scanner: upsert COS 作者 %s: %w", authorDir, err)
	}
	if err := s.q.AddAssetAuthor(ctx, db.AddAssetAuthorParams{
		AssetID: asset.AssetID, AuthorID: authorID,
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

// EnrichAsset 单资产重富化：API 移动/重命名（filing）成功后调用。文件名
// 变化会改变出处/角色匹配结果，而移动不改 size+mtime，下次扫描不会重
// ingest（移动合并路径见文件头注释），必须在写入路径显式重算。
// COS 库不重算（作者关联按目录维度，目录变更的映射修正随全量扫描对账）。
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
		return nil
	}
	source, chars := s.matcher.MatchAll(asset.FileName)
	if err := s.q.UpdateAssetSource(ctx, db.UpdateAssetSourceParams{
		Source:    sql.NullString{String: source, Valid: source != ""},
		UpdatedAt: store.FormatTimestamp(s.now()),
		AssetID:   assetID,
	}); err != nil {
		return fmt.Errorf("scanner: 更新出处 %s: %w", asset.FileName, err)
	}
	// 角色覆盖（先删后插，语义同 ingestNormalFile）。
	if err := s.q.DeleteAssetCharacters(ctx, assetID); err != nil {
		return fmt.Errorf("scanner: 清理角色 %s: %w", asset.FileName, err)
	}
	for _, c := range chars {
		if err := s.q.AddAssetCharacter(ctx, db.AddAssetCharacterParams{
			AssetID: assetID, CharacterName: c,
		}); err != nil {
			return fmt.Errorf("scanner: 写入角色 %s/%s: %w", asset.FileName, c, err)
		}
	}
	return nil
}
