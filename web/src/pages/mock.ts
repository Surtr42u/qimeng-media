/**
 * UI 移植阶段 A 的 mock 数据（2026-09-02）。
 *
 * 来源：media-ui-prototype/app.js（用户逐页验收的原型数据，原样搬运）。
 * 生命周期：阶段 B 接真实数据时逐个替换为 TanStack Query hooks，
 * 本文件随之删减——新增真实数据源后请同步删除对应 mock，避免双源漂移。
 */

/** 首页卡片（真机抓取的推荐流 12 张全量搬运；已删播放/互动/点赞数/头像字段——用户拍板。
 *  广告卡与普通卡同构渲染：原型 renderCard 本就不输出任何广告标记，仅数据存档） */
export interface MockCardItem {
  id: string
  cover: string
  title: string
  duration?: string
  up: string
  date: string
}

export const MOCK_HOME_CARDS: MockCardItem[] = [
  {
    id: 'vid-1NStw6REvu',
    cover: '/covers/c-avatar.webp',
    title: '【𝟒𝑲 𝟔𝟎𝑭𝑷𝑺】 当《阿凡达》遇上𝑹𝑻𝑿 𝟓𝟎𝟗𝟎：DLSS 5与RenoDX重构潘多拉星球',
    duration: '11:30',
    up: 'GamerWhale',
    date: '8-28',
  },
  {
    id: 'vid-19Ugg61EsQ',
    cover: '/covers/c-crab.webp',
    title: '再次挑战顶级黄油蟹，曾经一只卖1000多，你们说会翻车吗？',
    duration: '11:17',
    up: '小文哥吃吃吃',
    date: '8-16',
  },
  {
    id: 'vid-1X28u6oEFg',
    cover: '/covers/c-frog.webp',
    title: '从排队2小时，年入上百万，到一年关店7000家！牛蛙为啥没人吃了？',
    duration: '10:24',
    up: '财经不眠姐',
    date: '8-18',
  },
  {
    id: 'vid-1D6gV6UEM4',
    cover: '/covers/c-frog-s.webp',
    title: '如何使用支付宝、微信订阅GPT-PLUS会员？',
    up: '柚子站长',
    date: '8-12',
  },
  {
    id: 'vid-1geM66QEET',
    cover: '/covers/c-jet.webp',
    title: '一个近50年没独立造出新战斗机的国家，却要9年内让六代机服役？六代机有啥难的？',
    duration: '40:36',
    up: '深秋不是鸡腿子',
    date: '8-5',
  },
  {
    id: 'vid-1Qvtp6RE1c',
    cover: '/covers/c-dlss.webp',
    title: 'DLSS5！国产二游的亲爹，异环和鸣潮的个人看法以及一些问题解答。',
    duration: '47:11',
    up: '某个粉毛屑狐狸',
    date: '11小时前',
  },
  {
    id: 'vid-18Q8C6HEBH',
    cover: '/covers/c-tokyo.webp',
    title: '东京和首尔，谁更吸血全国？',
    duration: '11:29',
    up: '凯旋official',
    date: '8-22',
  },
  {
    id: 'vid-1bJgc6yEUv',
    cover: '/covers/c-box.webp',
    title: '史上最贵自助盒饭 39一位！',
    duration: '02:41',
    up: '转生成为毛毛',
    date: '8-14',
  },
  {
    id: 'vid-1GJuh6PEop',
    cover: '/covers/c-mc.webp',
    title: '不断添加搞笑模组直到MC变成一坨答辩',
    duration: '07:08',
    up: '吾乃肆玖',
    date: '8-7',
  },
  {
    id: 'vid-1v3hg6eE8U',
    cover: '/covers/c-mc-s.webp',
    title: '上海约玩，可狼可奶可处cp~',
    up: '电子赛博月老',
    date: '8-25',
  },
  {
    id: 'vid-1tGhV6hEVs',
    cover: '/covers/c-milk.webp',
    title: '你喝过化学牛奶吗，印度三鹿用洗衣粉勾兑出了化学牛奶',
    duration: '16:33',
    up: '秋田早上好',
    date: '8-25',
  },
  {
    id: 'vid-1Lcbv6hEw7',
    cover: '/covers/c-rural.webp',
    title: '初中没上完的精神小伙现在都怎么样了？【农村青年录】',
    duration: '16:06',
    up: '这是个令人疑惑的星球',
    date: '8-17',
  },
]

/** 相册页四维筛选（分区/作品/角色/类型），值 = [名称, 计数] */
export type AlbumDimKey = 'partition' | 'work' | 'character' | 'type'

export const ALBUM_DIMS: Record<AlbumDimKey, { label: string; values: [string, number][] }> = {
  partition: {
    label: '分区',
    values: [
      ['全部', 7347], ['样例', 2841], ['摄影', 1206], ['同人', 988], ['Cosplay', 862],
      ['风景', 641], ['日常', 533], ['创作', 276], ['舞台', 218], ['回忆', 190],
    ],
  },
  work: {
    label: '作品',
    values: [
      ['全部', 7347], ['样例 2017', 893], ['舞台摄影', 762], ['角色特写', 715], ['场外随拍', 640],
      ['返场演出', 588], ['展台搭建', 512], ['互动环节', 466], ['夜景机位', 401], ['同人社', 332], ['舞台巡礼', 280],
    ],
  },
  character: {
    label: '角色',
    values: [
      ['全部', 7347], ['小祥', 245], ['虚空行者', 210], ['长颈鹿', 180], ['青鸟', 172],
      ['苍火', 140], ['银影', 122], ['荒野', 96], ['蜜霜', 88], ['岚', 74],
    ],
  },
  type: {
    label: '类型',
    values: [['全部', 7347], ['视频', 4821], ['图片', 2213], ['音频', 313]],
  },
}

/** 相册维度行渲染顺序（原型固定：类型前插入分隔线） */
export const ALBUM_DIM_ORDER: AlbumDimKey[] = ['partition', 'work', 'character', 'type']

/** 相册/搜索/收藏共用的内容池（tags 四维供筛选） */
export interface MockAlbumFile {
  name: string
  cover: string
  duration: string
  up: string
  date: string
  views: string
  tags: Record<AlbumDimKey, string>
}

export const MOCK_ALBUM_FILES: MockAlbumFile[] = [
  { name: '样例 2017 实录 · 高画质合集', cover: '/covers/c-avatar.webp', duration: '34:00', up: '@ 电子赛博月老', date: '8-28', views: '3,241', tags: { partition: '样例', work: '样例 2017', character: '小祥', type: '视频' } },
  { name: '舞台摄影 · 第一组', cover: '/covers/c-crab.webp', duration: '48 张', up: '@ 夜空机位', date: '8-26', views: '1,982', tags: { partition: '摄影', work: '舞台摄影', character: '虚空行者', type: '图片' } },
  { name: '场外随拍 · 机动展区', cover: '/covers/c-frog.webp', duration: '12:00', up: '@ 展会实录', date: '8-24', views: '1,540', tags: { partition: '样例', work: '样例 2017', character: '长颈鹿', type: '视频' } },
  { name: '角色特写 · 第三辑', cover: '/covers/c-frog-s.webp', duration: '26 张', up: '@ 舞台捕手', date: '8-22', views: '1,207', tags: { partition: 'Cosplay', work: '角色特写', character: '小祥', type: '图片' } },
  { name: '返场演出 · 完整版', cover: '/covers/c-jet.webp', duration: '52:00', up: '@ 展会实录', date: '8-20', views: '986', tags: { partition: '同人', work: '返场演出', character: '青鸟', type: '视频' } },
  { name: '展台搭建花絮', cover: '/covers/c-dlss.webp', duration: '15 张', up: '@ 样例日记', date: '8-18', views: '774', tags: { partition: '摄影', work: '样例 2017', character: '长颈鹿', type: '图片' } },
  { name: '互动环节 · 玩家现场', cover: '/covers/c-tokyo.webp', duration: '28:00', up: '@ 展会实录', date: '8-16', views: '645', tags: { partition: '样例', work: '互动环节', character: '虚空行者', type: '视频' } },
  { name: '夜景机位 · 收场', cover: '/covers/c-box.webp', duration: '19 张', up: '@ 夜空机位', date: '8-14', views: '523', tags: { partition: '风景', work: '夜景机位', character: '小祥', type: '图片' } },
  { name: '同人摊位 · 第二区', cover: '/covers/c-mc.webp', duration: '22 张', up: '@ 同人社', date: '8-12', views: '466', tags: { partition: '同人', work: '同人社', character: '小祥', type: '图片' } },
  { name: 'Cosplay 舞台巡礼', cover: '/covers/c-mc-s.webp', duration: '35:00', up: '@ 舞台捕手', date: '8-10', views: '402', tags: { partition: 'Cosplay', work: '舞台巡礼', character: '青鸟', type: '视频' } },
  { name: '创作整理 · B 卷', cover: '/covers/c-milk.webp', duration: '17 张', up: '@ 绮梦', date: '8-08', views: '355', tags: { partition: '创作', work: '舞台摄影', character: '小祥', type: '图片' } },
  { name: '日常随拍 · 一周', cover: '/covers/c-rural.webp', duration: '9:00', up: '@ 绮梦', date: '8-06', views: '298', tags: { partition: '日常', work: '日常随拍', character: '小祥', type: '视频' } },
  { name: '现场录音 · 安可段', cover: '/covers/c-jet.webp', duration: '03:45', up: '@ 绮梦', date: '8-02', views: '210', tags: { partition: '样例', work: '返场演出', character: '青鸟', type: '音频' } },
]

/** 作者管理页全量作者（mock 12 常规 + 3 COS） */
export interface MockAuthor {
  name: string
  type: '常规' | 'cos'
  works: number
  browse: number
  followed: boolean
}

export const MOCK_AUTHORS: MockAuthor[] = [
  { name: '绮梦', type: '常规', works: 126, browse: 9200, followed: true },
  { name: '夜空机位', type: '常规', works: 88, browse: 6100, followed: true },
  { name: '舞台捕手', type: '常规', works: 57, browse: 4400, followed: true },
  { name: '展会实录', type: '常规', works: 41, browse: 3900, followed: false },
  { name: '样例日记', type: '常规', works: 33, browse: 5200, followed: false },
  { name: '快门手', type: '常规', works: 29, browse: 2800, followed: false },
  { name: '镜头后', type: '常规', works: 24, browse: 3500, followed: false },
  { name: '夜行者', type: 'cos', works: 21, browse: 1900, followed: false },
  { name: '布光师', type: '常规', works: 18, browse: 2600, followed: false },
  { name: '场记', type: '常规', works: 15, browse: 1500, followed: false },
  { name: '追焦', type: '常规', works: 12, browse: 3100, followed: false },
  { name: '侧台', type: 'cos', works: 10, browse: 900, followed: false },
  { name: '观众席', type: 'cos', works: 8, browse: 1200, followed: false },
  { name: '通宵剪', type: '常规', works: 6, browse: 760, followed: false },
  { name: '档案员', type: '常规', works: 4, browse: 420, followed: false },
]

/** 完整榜单页标题映射（数据页「查看全部」按 data-rank 进入对应榜） */
/** 榜单键唯一来源：DataPage 入口与 RanksPage 路由段共用，禁再散写字面量 */
export const RANK_CONTENT = 'content'
export const RANK_TAGS = 'tags'
export const RANK_AUTHORS = 'authors'

export const RANK_PAGE_TITLES: Record<string, string> = {
  [RANK_CONTENT]: '内容榜',
  [RANK_TAGS]: '标签榜',
  [RANK_AUTHORS]: '作者榜',
}

/** 搜索面板历史词（阶段 A mock；阶段 B 接真实搜索历史） */
export const MOCK_SEARCH_HISTORY: string[] = [
  '请不要带走我',
  '如懿传吐槽',
  '双瞳完整版',
  '异环',
  '4K HDR',
  'vid-1Rk4y1977w',
  '罗通扫北大鼓书…',
  '罗通扫北',
  '京韵大鼓',
  '罗通扫北大鼓书',
  '三体原声',
  '黑神话悟空',
]

/** 搜索页标签池初值（DOMAIN_RULES §7：新增自动入池/删除级联，阶段 A 内存态） */
export const MOCK_SEARCH_TAGS: string[] = [
  '样例', '摄影', '同人', 'Cosplay', '风景', '日常', '创作', '舞台',
]

/** 搜索历史默认展示条数（超出部分收进「展开更多」） */
export const SEARCH_HISTORY_VISIBLE_COUNT = 8
