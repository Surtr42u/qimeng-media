// 推荐顺序启动预热覆盖 App(seed=1) 与 Web(seed=0) 常规首屏。
// 分页大小不影响顺序键，预热不写展示计数；实际取页仍现读并校验资格。
package httpapi

import (
	"context"
	"errors"
	"time"

	"qimeng-media/server/internal/recommendfeed"
	"qimeng-media/server/internal/safego"
	"qimeng-media/server/internal/store"
)

const (
	// recommendPrewarmSeed 预热键 seed：App 首屏初始请求恒为 1（打散档起步值）。
	recommendPrewarmSeed = 1
	// Web HomePage 的初始 seed；分页大小不再进入缓存键。
	recommendWebPrewarmSeed = 0
)

// StartRecommendPrewarm 组合根（main）调用一次：起监听后立即后台预热一轮
// 默认推荐流。与缩略图回填的开机静默窗不同——每种 seed 仅 SQLite 聚合+纯
// 函数打分（非 ffmpeg 批量转码），且直接服务首条请求，不延后。
func (s *Server) StartRecommendPrewarm() {
	safego.Go(s.logger, "推荐流预热", s.prewarmRecommend)
}

// prewarmRecommend 预热一轮：构造与 App 首屏请求同键的缓存键并走单飞计算。
// 失败只警告——预热是纯优化，失败后退化为既有「首条请求现场计算」路径。
func (s *Server) prewarmRecommend() {
	now := s.now()
	day := store.FormatDay(now)
	prefs := s.recommendPrefsFromSettings(context.Background())
	start := time.Now()
	// App 初始 1/200，Web HomePage 初始 0/DEFAULT_PAGE_SIZE=60；不改变客户端语义。
	// 顺序预热不保存页摘要，真实请求仍校验资格并且只对返回页计数。
	if err := s.recommendCache.order.WarmSeeds(context.Background(), s.q, recommendfeed.Key{
		Revision: s.recommendCache.revision(), Day: day, Prefs: recommendPrefsFingerprint(prefs),
	}, []int{recommendPrewarmSeed, recommendWebPrewarmSeed}, prefs, now); err != nil {
		if errors.Is(err, recommendfeed.ErrWarmupInvalidated) {
			s.logger.Debug("推荐预热因库变更中止，按需重算")
		} else {
			s.logger.Warn("推荐顺序预热失败", "err", err)
		}
		return
	}
	s.logger.Info("推荐顺序预热完成", "elapsed", time.Since(start).Round(time.Millisecond).String())
}
