package authoring

import (
	"path/filepath"
	"strings"
)

// MediaFile 是作品名匹配域中的一个库内文件（normal 库资产投影）。
type MediaFile struct {
	AssetID  string
	FileName string
}

// mediaExts 是「带扩展名」判定集合 = 媒体索引扩展名（scanner 白名单，
// DOMAIN_RULES §9：图片 jpg/jpeg/png/gif/webp/avif + 视频 mp4/mkv/webm/
// mov/m4v/avi）∪ 非索引媒体扩展名（wmv/bmp/svg/tiff/tif，旧项目口径——
// 它们是真实媒体但新架构不索引，作品名带这些扩展名时仍视为带扩展名，
// 防止 "X.wmv" 被当无扩展名基础名而错误命中 "X.wmv (1).jpg"）。
// txt/doc/pdf 等文档扩展名不在集合内 → 视为不带扩展名。
// 行为由旧项目测试 findMatchingMediaLight 四例锁定。
var mediaExts = map[string]struct{}{
	".jpg": {}, ".jpeg": {}, ".png": {}, ".gif": {}, ".webp": {}, ".avif": {},
	".mp4": {}, ".mkv": {}, ".webm": {}, ".mov": {}, ".m4v": {}, ".avi": {},
	".wmv": {}, ".bmp": {}, ".svg": {}, ".tiff": {}, ".tif": {},
}

// hasMediaExt 报告 name 的最后一个扩展名是否属于可索引/媒体扩展名集合
// （大小写不敏感；'.' 为首字符的隐藏文件名视为无扩展名，与 sourcematcher
// stripExtension 口径一致）。
func hasMediaExt(name string) bool {
	ext := strings.ToLower(filepath.Ext(name))
	if ext == "" {
		return false
	}
	_, ok := mediaExts[ext]
	return ok
}

// allHaveMediaExt 报告列表非空且每一项都带媒体扩展名（数字开头文件名
// 误判编号行的修复判定条件）。
func allHaveMediaExt(names []string) bool {
	if len(names) == 0 {
		return false
	}
	for _, n := range names {
		if !hasMediaExt(n) {
			return false
		}
	}
	return true
}

// workKey 是作品名归一结果（MatchWorks 与 FileIndex 共用同一归一，防漂移）。
type workKey struct {
	base        string // 去扩展名（仅媒体扩展名时剥）+ 去全部半角空格的比较域
	ext         string // 小写扩展名；无媒体扩展名时空串不参与比较
	hasExt      bool
	seqTolerant bool // 规则 2 触发条件：不带媒体扩展名且自身不含 (N)
}

// normalizeWork 把作品名归一为 workKey（原 MatchWorks 内联逻辑逐字搬运；
// 调用方须先 TrimSpace 并排除空串）。
func normalizeWork(work string) workKey {
	hasExt := hasMediaExt(work)
	// 比较域 = 去扩展名（仅带媒体扩展名时剥）+ 去全部半角空格；全角空格不折叠
	//（与 sourcematcher collapse 的保真决策一致，规则权威同源）。
	base := work
	if hasExt {
		base = strings.TrimSuffix(work, filepath.Ext(work))
	}
	base = strings.ReplaceAll(base, " ", "")
	// 扩展名检查域（小写）：hasMediaExt=false 时为空串且不参与比较
	ext := strings.ToLower(filepath.Ext(work))
	// 规则 2 触发条件：作品名自身不含序号括号（在去空格域检测——去空格
	// 不影响 (N) 形态）。带扩展名的作品名不触发规则 2（GUIDE_AUTHOR：
	// "写 守望先锋 天使 4.jpg（带扩展名）不会触发规则 2，只做精确匹配"）。
	return workKey{base: base, ext: ext, hasExt: hasExt, seqTolerant: !hasExt && !workSuffixRe.MatchString(base)}
}

// fileBaseOf 文件侧比较域：去扩展名 + 去全部半角空格（文件名不判定媒体
// 扩展名，一律剥尾扩展名——与原 MatchWorks 循环体逐字一致）。
func fileBaseOf(name string) string {
	return strings.ReplaceAll(strings.TrimSuffix(name, filepath.Ext(name)), " ", "")
}

// MatchWorks 把一个 TXT 作品名与匹配域（normal 库资产）中的文件名匹配
// （GUIDE_AUTHOR「文件名匹配规则」，两层按优先级；逐字对齐旧项目
// AuthorImportUseCase.findMatchingMediaLight）：
//
//   - 规则 1 精确匹配：基础名（去扩展名、去全部半角空格）一致；作品名带
//     媒体扩展名时文件扩展名必须同名（旧算法的扩展名检查 mediaExt == ext
//     ——写 "雾子 3.mp4" 不得命中 "雾子 3.png"，png/mp4 各归各）。比较
//     大小写不敏感（旧 equals ignoreCase）。
//   - 规则 2 序号括号容错（仅当作品名不带媒体扩展名且自身不含 (N)）：文件名
//     比较串去掉尾部 (N) 后再比较——TXT 写基础名、实际文件带分页序号的
//     情况，会把同一基础名的全部序号变体都关联上。
//
// 返回顺序：先规则 1 命中（保持输入顺序），再规则 2 命中——与输入文件的
// 顺序无关地满足「精确在前、序号在后」的稳定次序。同名文件（不同目录）
// 全部命中；找不到的文件不创建关联（由调用方跳过）。
func MatchWorks(work string, files []MediaFile) []MediaFile {
	work = strings.TrimSpace(work)
	if work == "" {
		return nil
	}
	wk := normalizeWork(work)

	var exact, seq []MediaFile
	for _, f := range files {
		fileBase := fileBaseOf(f.FileName)
		if wk.hasExt {
			// 规则 1（带媒体扩展名作品）：基础名一致 + 扩展名一致
			if strings.EqualFold(fileBase, wk.base) && strings.ToLower(filepath.Ext(f.FileName)) == wk.ext {
				exact = append(exact, f)
				continue
			}
		} else if strings.EqualFold(fileBase, wk.base) {
			// 规则 1（无媒体扩展名作品）：基础名一致即命中（任意扩展名/无扩展名）
			exact = append(exact, f)
			continue
		}
		if wk.seqTolerant && strings.EqualFold(fileSuffixTailRe.ReplaceAllString(fileBase, ""), wk.base) {
			seq = append(seq, f)
		}
	}
	return append(exact, seq...)
}
