package recommend

import "time"

// Item 是推荐/排行算法的单个资产输入。
//
// 字段全部来自 ListAssetsRecommendInput 一行：Stats 是行为聚合，
// Tags 是库内手动标签（文件名推断标签由算法内部 tagsFor 叠加，
// 与旧项目语义一致：推断只作用于评分，不改输入结构）。
type Item struct {
	AssetID    string
	FileName   string
	MediaType  string
	CreatedAt  time.Time // 首次入库时间（freshness 维度基准，0001 「首次入库不更新」）
	ModifiedAt time.Time // 文件 mtime（rank 同分解法排序键）
	Stats      Stats
	Tags       []string // 库内标签（手动标签池，DOMAIN_RULES §7）
	LikeCount  int
	ShownToday int // 今日已被推荐展示次数（dailyPenalty 输入，零点后查询侧自然归零）
}

// Stats 是行为聚合（view/play/dwell 事件与最近浏览时间）。
type Stats struct {
	ViewCount     int
	PlayCount     int
	BrowseSeconds int64
	LastViewedAt  *time.Time // nil = 从未浏览（recency 走默认分 0.3）
}

// Weights 是九维权重 + 随机扰动上限（随机项不进权重合计，公式见
// DOMAIN_RULES §1.1：Σ(维度分×权重) + randomFactor − dailyPenalty）。
type Weights struct {
	TagRelevance  float64
	TagCollection float64
	Engagement    float64
	Recency       float64
	LikeScore     float64
	Discovery     float64
	Freshness     float64
	BrowseDepth   float64
	MaxRandom     float64
}

// Params 是一次推荐调用的参数。Now 由调用方注入（服务端用统一的
// s.now()，测试用固定时间），保证算法在任意时间点可回放。
type Params struct {
	Limit int      // <=0 = 返回全部（调用方缺省语义：库全量）
	Seed  int      // 0=稳定排序（跳过同分桶打散）；>0=确定性打散（刷新语义）
	Prefs *Weights // 用户自定义偏好；nil = 使用设计默认权重（DOMAIN_RULES §1.3）
	Now   time.Time
}

// mediaTypeVideo 是资产表中视频类型的存储值（media_type CHECK 约束
// 见 migrations/0001_init.up.sql）；balanceVideoImage 只按它区分
// 视频/图片两类，其余类型（image/animated_image）都归图片组。
const mediaTypeVideo = "video"
