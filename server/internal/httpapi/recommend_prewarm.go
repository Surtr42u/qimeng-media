// recommend_prewarm.go：推荐流缓存开机预热（2026-09-18 性能批二段）。
//
// 为什么：缓存是进程级的，冷启动首击必 miss——App 冷启动到首页的首条请求
// 仍要现场跑一遍全库聚合+打分（手机 SoC 秒级）。起监听即后台预热同键默认
// 流，与首条请求经单飞（recommendCache.do）共享同一次计算：计算与 App 自身
// 冷启动并行，用户感知的首屏等待被压缩掉相应一段。
//
// 口径边界（DOMAIN_RULES §1.4）：预热只算不服务，「当日展示计数」一概不写
// ——§1.4.3 计数语义=真实展示后 +1，后台计算不是展示；真实服务路径
// （GetApiV1Recommendations）取得结果后照常回写。
//
// 键同源约束：预热键必须与 App 端首屏请求完全一致，否则预热白做——
// seed=1（App 端 feature/home HomeViewModel.INITIAL_SEED）、limit=200
// （core/model RecommendPaging.PULL_LIMIT）、offset=0、mediaType 空、
// cosOnly=false。任一侧改动须双向同步。
package httpapi

import (
	"context"
	"database/sql"
	"time"

	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/store"
)

const (
	// recommendPrewarmSeed 预热键 seed：App 首屏初始请求恒为 1（打散档起步值）。
	recommendPrewarmSeed = 1
	// recommendPrewarmLimit 预热键 limit：App 首屏一次拉满 200。
	recommendPrewarmLimit = 200
)

// StartRecommendPrewarm 组合根（main）调用一次：起监听后立即后台预热一轮
// 默认推荐流。与缩略图回填的开机静默窗不同——本预热是单次 SQLite 聚合+纯
// 函数打分（非 ffmpeg 批量转码），且直接服务首条请求，不延后。
func (s *Server) StartRecommendPrewarm() {
	go s.prewarmRecommend()
}

// prewarmRecommend 预热一轮：构造与 App 首屏请求同键的缓存键并走单飞计算。
// 失败只警告——预热是纯优化，失败后退化为既有「首条请求现场计算」路径。
func (s *Server) prewarmRecommend() {
	day := store.FormatDay(s.now())
	prefs := s.recommendPrefsFromSettings(context.Background())
	key := recommendCacheKey{
		rev:       s.recommendCache.revision(),
		day:       day,
		seed:      recommendPrewarmSeed,
		mediaType: "", // 默认流（App 首屏不传 mediaType）
		cosOnly:   false,
		prefs:     recommendPrefsFingerprint(prefs),
		offset:    0,
		limit:     recommendPrewarmLimit,
	}
	start := time.Now()
	_, _, err := s.recommendCache.do(key, func() ([]gen.AssetSummary, []string, error) {
		return s.computeRecommendPage(context.Background(), day, recommendPrewarmSeed,
			sql.NullString{}, 0, 0, recommendPrewarmLimit, prefs)
	})
	if err != nil {
		s.logger.Warn("推荐流缓存开机预热失败（首条请求将现场计算）", "err", err)
		return
	}
	s.logger.Info("推荐流缓存开机预热完成", "elapsed", time.Since(start).Round(time.Millisecond).String())
}
