// assets_media_url.go：媒体直链/缩略图 URL 与 sqlc 标量抹平小助手。
// 从 assets.go 按职责拆出：签名 URL 生成与库内标量→协议字段的转换
// 被列表/详情/推荐/统计多端点共用，与「GET /assets handler」无绑定。
package httpapi

import (
	"database/sql"
	"strconv"
	"strings"
	"time"

	"github.com/google/uuid"
	openapi_types "github.com/oapi-codegen/runtime/types"

	"qimeng-media/server/internal/auth"
	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/thumbnail"
)

// mediaURLExpiry 计算签名直链 exp 的窗口对齐上界（ADR-0027）。
//
// exp = 下一个窗口上界 + ttl，窗长与直链有效期同值（一个旋钮，URL 每日至多
// 轮换 4 次）：
//   - 同一窗口内（对齐 Unix 纪元整点）所有响应生成相同 exp → URL 字符串
//     逐字节恒定，浏览器 HTTP 缓存与 ETag/304 终于能命中。旧实现
//     exp=now+TTL 逐响应轮换，同资产同尺寸的 URL 每次响应都不同，击穿全部
//     HTTP 缓存（Web 端零补偿全量重下；Android 端被迫自建剥签名键栈）。
//   - 有效期恒 ∈ (ttl, 2*ttl]：最短仍是一个完整 ttl，绝不出现"取到即近
//     过期"的 URL（纯取窗口上界不叠加 ttl 的备选方案会在窗口尾产出几分钟
//     内过期的 URL，已否决，见 ADR-0027）。
//   - 纯函数（只依赖 now 与 ttl）：进程重启/并发请求结果确定，无需状态。
//
// ttl 必须传实际生效的直链有效期（Server.ttl，配置 token_ttl，缺省
// DefaultTokenTTL）而不是硬编码 DefaultTokenTTL——窗口必须跟随配置值，
// 否则配置长 TTL 时签出的 URL 会在 TTL 内提前过期，违反上界不变量。
// time.Truncate 按绝对时间（Unix 纪元零点）对齐，与时区无关，无需转换。
func mediaURLExpiry(now time.Time, ttl time.Duration) int64 {
	boundary := now.Add(ttl).Truncate(ttl)
	return boundary.Add(ttl).Unix()
}

// signedMediaURL 生成带 exp/sig 的签名直链（auth 包协议）。
// path 必须与校验侧 r.URL.Path 同形态——纯路径（不含查询串），uuid 与
// hex 签名字符集不含需转义的字符，两形态天然一致。查询参数（如 thumb
// 的 size）不参与签名：size 属于缓存选择而非授权面，签名始终只锚定路径。
// exp 走窗口对齐（mediaURLExpiry，ADR-0027）：签名消息构造与验签侧
// （auth 包）零改动，只改 exp 数值的取法。
func (s *Server) signedMediaURL(path string) string {
	expAt := time.Unix(mediaURLExpiry(s.now(), s.ttl), 0)
	exp, sig := auth.SignMediaURL(path, expAt, s.secret)
	return path + "?exp=" + strconv.FormatInt(exp, 10) + "&sig=" + sig
}

// thumbURL 生成缩略图签名直链（size 作为普通查询参数附在签名之后）。
//
// size 档位（openapi sm/md/lg）到像素的换算见 thumbSize；档位像素的
// 单一来源是 thumbnail 包的 Size 常量（cachekey.go），md 档像素由
// Thumbnail 配置 LongSide 决定（未配置回落 SizeGrid）。
func (s *Server) thumbURL(assetID, size string) string {
	return s.signedMediaURL(mediaPathThumb+assetID) + "&size=" + size
}

// thumbSize 把 openapi size 枚举映射到 thumbnail.Size：
// sm→SizeSmall、lg→SizePreview；md（默认档）→ Generator.GridLongSide()
// （LongSide 配置的接线出口，保证请求缓存键与生成尺寸一致）。
func (s *Server) thumbSize(size gen.GetMediaThumbAssetIdParamsSize) thumbnail.Size {
	switch size {
	case gen.Sm:
		return thumbnail.SizeSmall
	case gen.Lg:
		return thumbnail.SizePreview
	default:
		return thumbnail.Size(s.thumbs.GridLongSide()) // md（默认档）
	}
}

// parseStoreTime 解析库内时间戳文本；失败返回零值（脏数据不炸接口）。
func parseStoreTime(s string) time.Time {
	t, err := time.Parse(store.TimestampLayout, s)
	if err != nil {
		return time.Time{}
	}
	return t
}

// sourceOtherLabel 「其他」出处桶的用户面名称（DOMAIN_RULES §4：未匹配出处的
// 归「其他」）。GET /assets 与 GET /assets/facets 的 source 参数用它回传
// source_is_other 标志，displaySource 用它兜底 NULL 显示——三处同一常量，
// 改词必须三处同步（代码卫生约束第 2/3 条）。
const sourceOtherLabel = "其他"

// displaySource 把 NULL 出处显示为"其他"（DOMAIN_RULES §4 分区语义）。
func displaySource(src sql.NullString) string {
	if src.Valid && src.String != "" {
		return src.String
	}
	return sourceOtherLabel
}

// uuidOrNil 解析资产 ID；库中主键由服务端生成，解析失败属数据损坏——
// 零 UUID 兜底让响应仍可序列化。
func uuidOrNil(s string) openapi_types.UUID {
	u, err := uuid.Parse(s)
	if err != nil {
		return openapi_types.UUID{}
	}
	// openapi_types.UUID 是 uuid.UUID 的类型别名（oapi-codegen uuid.TYPE），unconvert 判定直返即可
	return u
}

// dirOf 取库内相对路径的目录部分（'/' 分隔；根目录文件返回空串）。
func dirOf(rel string) string {
	if i := strings.LastIndex(rel, "/"); i >= 0 {
		return rel[:i]
	}
	return ""
}

// toString 抹平 sqlc interface{} 列（sort_key 恒为 TEXT 非空——
// 分支全部指向 NOT NULL 列或 printf 输出，见 browse.sql）。
func toString(v any) string {
	if s, ok := v.(string); ok {
		return s
	}
	return ""
}

// toInt 抹平 sqlc interface{} 标量（SUM/MAX 的整型返回）。
func toInt(v any) int {
	switch n := v.(type) {
	case int64:
		return int(n)
	case float64:
		return int(n)
	default:
		return 0
	}
}

// ptr 是小型取址助手（Go 无泛型字面量取址，逐处 var 太啰嗦）。
func ptr[T any](v T) *T { return &v }
