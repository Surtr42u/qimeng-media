/* 桌面端 UI 复刻原型 —— 数据取自真机抓取（2026-08-31，推荐流） */
const VIDEOS = [
  {
    id: "vid-1NStw6REvu",
    cover: "covers/c-avatar.webp",
    title: "【𝟒𝑲 𝟔𝟎𝑭𝑷𝑺】 当《阿凡达》遇上𝑹𝑻𝑿 𝟓𝟎𝟗𝟎：DLSS 5与RenoDX重构潘多拉星球",
    plays: "4538", danmaku: "9", duration: "11:30",
    up: "GamerWhale", date: "8-28",
  },
  {
    id: "vid-19Ugg61EsQ",
    cover: "covers/c-crab.webp",
    title: "再次挑战顶级黄油蟹，曾经一只卖1000多，你们说会翻车吗？",
    plays: "115.5万", danmaku: "2793", duration: "11:17",
    up: "小文哥吃吃吃", date: "8-16", like: "3万点赞",
  },
  {
    id: "vid-1X28u6oEFg",
    cover: "covers/c-frog.webp",
    title: "从排队2小时，年入上百万，到一年关店7000家！牛蛙为啥没人吃了？",
    plays: "118.6万", danmaku: "3041", duration: "10:24",
    up: "财经不眠姐", date: "8-18",
  },
  {
    id: "vid-1D6gV6UEM4",
    cover: "covers/c-frog-s.webp",
    title: "如何使用支付宝、微信订阅GPT-PLUS会员？",
    plays: "17.1万", danmaku: "143", duration: "",
    up: "柚子站长", date: "8-12", like: "1千点赞", ad: true,
  },
  {
    id: "vid-1geM66QEET",
    cover: "covers/c-jet.webp",
    title: "一个近50年没独立造出新战斗机的国家，却要9年内让六代机服役？六代机有啥难的？",
    plays: "96.7万", danmaku: "4336", duration: "40:36",
    up: "深秋不是鸡腿子", date: "8-5",
  },
  {
    id: "vid-1Qvtp6RE1c",
    cover: "covers/c-dlss.webp",
    title: "DLSS5！国产二游的亲爹，异环和鸣潮的个人看法以及一些问题解答。",
    plays: "9435", danmaku: "38", duration: "47:11",
    up: "某个粉毛屑狐狸", date: "11小时前",
  },
  {
    id: "vid-18Q8C6HEBH",
    cover: "covers/c-tokyo.webp",
    title: "东京和首尔，谁更吸血全国？",
    plays: "36.7万", danmaku: "883", duration: "11:29",
    up: "凯旋official", date: "8-22",
  },
  {
    id: "vid-1bJgc6yEUv",
    cover: "covers/c-box.webp",
    title: "史上最贵自助盒饭 39一位！",
    plays: "55万", danmaku: "1303", duration: "02:41",
    up: "转生成为毛毛", date: "8-14", like: "1万点赞",
  },
  {
    id: "vid-1GJuh6PEop",
    cover: "covers/c-mc.webp",
    title: "不断添加搞笑模组直到MC变成一坨答辩",
    plays: "37.6万", danmaku: "1519", duration: "07:08",
    up: "吾乃肆玖", date: "8-7",
  },
  {
    id: "vid-1v3hg6eE8U",
    cover: "covers/c-mc-s.webp",
    title: "上海约玩，可狼可奶可处cp~",
    plays: "2万", danmaku: "0", duration: "",
    up: "电子赛博月老", date: "8-25", ad: true,
  },
  {
    id: "vid-1tGhV6hEVs",
    cover: "covers/c-milk.webp",
    title: "你喝过化学牛奶吗，印度三鹿用洗衣粉勾兑出了化学牛奶",
    plays: "87.7万", danmaku: "2147", duration: "16:33",
    up: "秋田早上好", date: "8-25",
  },
  {
    id: "vid-1Lcbv6hEw7",
    cover: "covers/c-rural.webp",
    title: "初中没上完的精神小伙现在都怎么样了？【农村青年录】",
    plays: "106.2万", danmaku: "3366", duration: "16:06",
    up: "这是个令人疑惑的星球", date: "8-17",
  },
];

/* 卡片渲染：封面仅保留图片与时长角标；元信息仅保留 UP 主名与日期（用户拍板删除点赞数与头像） */
function renderCard(v) {
  return `
  <div class="card" data-id="${v.id}">
    <div class="card--cover">
      <img src="${v.cover}" alt="${v.title}" loading="lazy">
      ${v.duration ? `<span class="card--duration">${v.duration}</span>` : ""}
    </div>
    <div class="card--title">${v.title}</div>
    <div class="card--meta">
      <span class="card--up">${v.up}</span>
      <span class="dot">·</span>
      <span>${v.date}</span>
    </div>
  </div>`;
}

const grid = document.getElementById("grid");
grid.innerHTML = VIDEOS.map(renderCard).join("");

/* 顶栏 tab 切换：排行榜 tab 下显示榜单周期行（日榜/月榜/周榜/年榜），其余隐藏 */
const rankPanel = document.getElementById("rankPanel");

document.querySelectorAll(".tab").forEach((tab) => {
  tab.addEventListener("click", () => {
    document.querySelectorAll(".tab").forEach((t) => t.classList.remove("active"));
    tab.classList.add("active");
    const isRank = tab.dataset.tab === "hot";
    // 排行榜态：tab 行保持可见，榜单周期行显示在顶栏下方独立行
    rankPanel.hidden = !isRank;
    if (isRank) {
      // 进入排行榜默认日榜
      document.querySelectorAll(".rank-tab").forEach((t, i) =>
        t.classList.toggle("active", i === 0),
      );
    }
  });
});

document.querySelectorAll(".rank-tab").forEach((t) => {
  t.addEventListener("click", () => {
    document.querySelectorAll(".rank-tab").forEach((x) => x.classList.remove("active"));
    t.classList.add("active");
  });
});

/* 侧边栏导航切换：active 高亮 + 对应页面显示/隐藏（页面由 data-page 映射到 #page-*）；
   顶栏「推荐/cos/排行榜」与榜单周期行只在首页显示 */
let currentPageId = "home";
const navHistory = []; // 页面历史栈：返回按钮用

function showPage(pageId, isBack = false) {
  const page = document.getElementById(`page-${pageId}`);
  if (page) {
    if (!isBack && pageId !== currentPageId) navHistory.push(currentPageId);
    currentPageId = pageId;
    document.querySelectorAll(".page").forEach((p) => (p.hidden = p !== page));
    document.querySelector(".content").scrollTop = 0;
    // 顶栏 tab 只在首页显示；榜单周期行跟随顶栏 tab 激活态（仅首页+排行榜 tab 显示）
    document.querySelector(".tabs").hidden = pageId !== "home";
    const showRank =
      pageId === "home" && document.querySelector(".tab.active")?.dataset.tab === "hot";
    document.getElementById("rankPanel").hidden = !showRank;
  }
}

/* 侧栏顶部返回按钮：回退到上一个页面（搜索页/普通切页均可），无历史时不动 */
document.querySelector(".sidebar--back").addEventListener("click", () => {
  const prev = navHistory.pop();
  if (prev) showPage(prev, true);
});

/* 数据页排行卡「查看全部」→ 对应榜单的完整子页（进入历史栈，侧栏返回按钮回退）；
   子页只显示点进来的那一个榜（data-panel 匹配） */
const RANK_PAGE_TITLES = { content: "内容榜", tags: "标签榜", authors: "作者榜" };
document.querySelectorAll(".rank-more").forEach((a) => {
  a.addEventListener("click", (e) => {
    e.preventDefault();
    const which = a.dataset.rank;
    document.getElementById("rankPageTitle").textContent = RANK_PAGE_TITLES[which] ?? "完整榜单";
    document.querySelectorAll("#page-ranks .rank-card").forEach((c) => {
      c.hidden = c.dataset.panel !== which;
    });
    showPage("ranks");
  });
});

/* 数据页「作者总览」卡「管理」→ 作者管理页（全部作者 + 分区胶囊 + 搜索），侧栏高亮保持「数据」 */
document.getElementById("authorManage").addEventListener("click", (e) => {
  e.preventDefault();
  showPage("authors");
});

/* 作者管理页（mock）：全量作者列表，体系胶囊（常规/COS）+ 名字搜索 + 旧版排序
   （默认=数组序 / 经常浏览=浏览数降序 / 文件数量=作品数降序），关注按钮内存态 toggle */
const AUTHORS = [
  { name: "绮梦", type: "常规", works: 126, browse: 9200, followed: true },
  { name: "夜空机位", type: "常规", works: 88, browse: 6100, followed: true },
  { name: "舞台捕手", type: "常规", works: 57, browse: 4400, followed: true },
  { name: "展会实录", type: "常规", works: 41, browse: 3900, followed: false },
  { name: "样例日记", type: "常规", works: 33, browse: 5200, followed: false },
  { name: "快门手", type: "常规", works: 29, browse: 2800, followed: false },
  { name: "镜头后", type: "常规", works: 24, browse: 3500, followed: false },
  { name: "夜行者", type: "cos", works: 21, browse: 1900, followed: false },
  { name: "布光师", type: "常规", works: 18, browse: 2600, followed: false },
  { name: "场记", type: "常规", works: 15, browse: 1500, followed: false },
  { name: "追焦", type: "常规", works: 12, browse: 3100, followed: false },
  { name: "侧台", type: "cos", works: 10, browse: 900, followed: false },
  { name: "观众席", type: "cos", works: 8, browse: 1200, followed: false },
  { name: "通宵剪", type: "常规", works: 6, browse: 760, followed: false },
  { name: "档案员", type: "常规", works: 4, browse: 420, followed: false },
];
let aZone = "全部";
let aKeyword = "";
let aSort = "default";
const A_SORTERS = {
  default: () => 0, // 默认 = 数组原序（作者榜热度序）
  browse: (a, b) => b.browse - a.browse, // 经常浏览 = 浏览数降序
  works: (a, b) => b.works - a.works, // 文件数量 = 作品数降序
};

function renderAuthors() {
  const kw = aKeyword.trim();
  const rows = AUTHORS
    .filter((a) => (aZone === "全部" || a.type === aZone) && (!kw || a.name.includes(kw)))
    .slice()
    .sort(A_SORTERS[aSort]);
  document.getElementById("aList").innerHTML = rows.map((a) => `
    <li>
      <span class="rank-name">${a.name}</span>
      <button class="follow-btn${a.followed ? "" : " follow-btn--idle"}" type="button">${a.followed ? "已关注" : "关注"}</button>
    </li>`).join("");
  document.getElementById("aEmpty").hidden = rows.length > 0;
  document.querySelectorAll("#aList .follow-btn").forEach((btn, i) => {
    btn.addEventListener("click", () => {
      rows[i].followed = !rows[i].followed;
      renderAuthors();
    });
  });
}
renderAuthors();

document.querySelectorAll("#aZones .stype").forEach((b) => {
  b.addEventListener("click", () => {
    document.querySelectorAll("#aZones .stype").forEach((x) => x.classList.remove("active"));
    b.classList.add("active");
    aZone = b.dataset.zone;
    renderAuthors();
  });
});
document.querySelectorAll("#aSort .sort-pill").forEach((b) => {
  b.addEventListener("click", () => {
    document.querySelectorAll("#aSort .sort-pill").forEach((x) => x.classList.remove("active"));
    b.classList.add("active");
    aSort = b.dataset.sort;
    renderAuthors();
  });
});
document.getElementById("aSearch").addEventListener("input", (e) => {
  aKeyword = e.target.value;
  renderAuthors();
});

document.querySelectorAll(".nav-item").forEach((item) => {
  item.addEventListener("click", () => {
    document.querySelectorAll(".nav-item").forEach((i) => i.classList.remove("active"));
    item.classList.add("active");
    showPage(item.dataset.page);
  });
});

/* 底部图标组（维护 / 设置）：切页并清除主导航高亮 */
document.querySelectorAll(".settings-item[data-page]").forEach((item) => {
  item.addEventListener("click", () => {
    document.querySelectorAll(".nav-item").forEach((i) => i.classList.remove("active"));
    showPage(item.dataset.page);
  });
});

/* 明暗主题切换（底部下沉区月亮图标） */
document.getElementById("themeToggle").addEventListener("click", () => {
  document.documentElement.classList.toggle("dark");
});

/* 相册筛选（mock 数据驱动）：点维度行快捷切值行，点值即时过滤内容网格；
   值行超过两行默认收起，点「展开/收起」切换（文件多时长列表不占屏） */
const ALBUM_DIMS = {
  partition: {
    label: "分区",
    values: [["全部", 7347], ["样例", 2841], ["摄影", 1206], ["同人", 988], ["Cosplay", 862], ["风景", 641], ["日常", 533], ["创作", 276], ["舞台", 218], ["回忆", 190]],
  },
  work: {
    label: "作品",
    values: [["全部", 7347], ["样例 2017", 893], ["舞台摄影", 762], ["角色特写", 715], ["场外随拍", 640], ["返场演出", 588], ["展台搭建", 512], ["互动环节", 466], ["夜景机位", 401], ["同人社", 332], ["舞台巡礼", 280]],
  },
  character: {
    label: "角色",
    values: [["全部", 7347], ["小祥", 245], ["虚空行者", 210], ["长颈鹿", 180], ["青鸟", 172], ["苍火", 140], ["银影", 122], ["荒野", 96], ["蜜霜", 88], ["岚", 74]],
  },
  type: {
    label: "类型",
    values: [["全部", 7347], ["视频", 4821], ["图片", 2213], ["音频", 313]],
  },
};
const DIM_ORDER = ["partition", "work", "character", "type"];

const ALBUM_FILES = [
  { name: "样例 2017 实录 · 高画质合集", cover: "covers/c-avatar.webp", duration: "34:00", up: "@ 电子赛博月老", date: "8-28", views: "3,241", tags: { partition: "样例", work: "样例 2017", character: "小祥", type: "视频" } },
  { name: "舞台摄影 · 第一组", cover: "covers/c-crab.webp", duration: "48 张", up: "@ 夜空机位", date: "8-26", views: "1,982", tags: { partition: "摄影", work: "舞台摄影", character: "虚空行者", type: "图片" } },
  { name: "场外随拍 · 机动展区", cover: "covers/c-frog.webp", duration: "12:00", up: "@ 展会实录", date: "8-24", views: "1,540", tags: { partition: "样例", work: "样例 2017", character: "长颈鹿", type: "视频" } },
  { name: "角色特写 · 第三辑", cover: "covers/c-frog-s.webp", duration: "26 张", up: "@ 舞台捕手", date: "8-22", views: "1,207", tags: { partition: "Cosplay", work: "角色特写", character: "小祥", type: "图片" } },
  { name: "返场演出 · 完整版", cover: "covers/c-jet.webp", duration: "52:00", up: "@ 展会实录", date: "8-20", views: "986", tags: { partition: "同人", work: "返场演出", character: "青鸟", type: "视频" } },
  { name: "展台搭建花絮", cover: "covers/c-dlss.webp", duration: "15 张", up: "@ 样例日记", date: "8-18", views: "774", tags: { partition: "摄影", work: "样例 2017", character: "长颈鹿", type: "图片" } },
  { name: "互动环节 · 玩家现场", cover: "covers/c-tokyo.webp", duration: "28:00", up: "@ 展会实录", date: "8-16", views: "645", tags: { partition: "样例", work: "互动环节", character: "虚空行者", type: "视频" } },
  { name: "夜景机位 · 收场", cover: "covers/c-box.webp", duration: "19 张", up: "@ 夜空机位", date: "8-14", views: "523", tags: { partition: "风景", work: "夜景机位", character: "小祥", type: "图片" } },
  { name: "同人摊位 · 第二区", cover: "covers/c-mc.webp", duration: "22 张", up: "@ 同人社", date: "8-12", views: "466", tags: { partition: "同人", work: "同人社", character: "小祥", type: "图片" } },
  { name: "Cosplay 舞台巡礼", cover: "covers/c-mc-s.webp", duration: "35:00", up: "@ 舞台捕手", date: "8-10", views: "402", tags: { partition: "Cosplay", work: "舞台巡礼", character: "青鸟", type: "视频" } },
  { name: "创作整理 · B 卷", cover: "covers/c-milk.webp", duration: "17 张", up: "@ 绮梦", date: "8-08", views: "355", tags: { partition: "创作", work: "舞台摄影", character: "小祥", type: "图片" } },
  { name: "日常随拍 · 一周", cover: "covers/c-rural.webp", duration: "9:00", up: "@ 绮梦", date: "8-06", views: "298", tags: { partition: "日常", work: "日常随拍", character: "小祥", type: "视频" } },
  { name: "现场录音 · 安可段", cover: "covers/c-jet.webp", duration: "03:45", up: "@ 绮梦", date: "8-02", views: "210", tags: { partition: "样例", work: "返场演出", character: "青鸟", type: "音频" } },
];

/** 媒体卡模板：与首页卡片同款（封面 + 时长角标 + 标题 + 作者·日期） */
function mediaCardHtml(f) {
  return `
  <div class="card">
    <div class="card--cover">
      <img src="${f.cover}" alt="${f.name}" loading="lazy">
      ${f.duration ? `<span class="card--duration">${f.duration}</span>` : ""}
    </div>
    <div class="card--title">${f.name}</div>
    <div class="card--meta">
      <span class="card--up">${f.up}</span>
      <span class="dot">·</span>
      <span>${f.date}</span>
    </div>
  </div>`;
}

const albumState = { dim: "partition", value: "全部", sort: "精选", expanded: false };
const albumDims = document.getElementById("albumDims");
const albumValues = document.getElementById("albumValues");
const albumGrid = document.getElementById("albumGrid");
const albumToggle = document.getElementById("albumValuesToggle");

function renderAlbumDims() {
  albumDims.innerHTML = DIM_ORDER.map((key) => {
    const d = ALBUM_DIMS[key];
    const divider = key === "type" ? '<span class="pill-divider" aria-hidden="true"></span>' : "";
    return `${divider}<button class="pill ${albumState.dim === key ? "active" : ""}" data-dim="${key}" type="button">${d.label} <span class="pill-count">${d.values.length - 1}</span></button>`;
  }).join("");
}

function renderAlbumValues() {
  const vals = ALBUM_DIMS[albumState.dim].values;
  albumValues.innerHTML = vals.map(([v, c]) =>
    `<button class="pill ${albumState.value === v ? "active" : ""}" data-value="${v}" type="button">${v} <span class="pill-count">${c.toLocaleString("zh-CN")}</span></button>`,
  ).join("");
  // 超过 9 项（含「全部」才有必要展开）时默认收起两行
  albumValues.classList.toggle("expanded", albumState.expanded);
  albumToggle.hidden = vals.length <= 9;
  albumToggle.textContent = albumState.expanded ? "收起 ⌃" : "展开 ⌄";
}

function renderAlbumGrid() {
  let files = albumState.value === "全部"
    ? [...ALBUM_FILES]
    : ALBUM_FILES.filter((f) => f.tags[albumState.dim] === albumState.value);
  if (albumState.sort === "最新") files = [...files].reverse();
  else if (albumState.sort === "按名称") files = [...files].sort((a, b) => a.name.localeCompare(b.name, "zh-Hans-CN"));
  albumGrid.innerHTML = files.length
    ? files.map(mediaCardHtml).join("")
    : '<p class="grid-empty">该筛选组合下暂无内容，换个胶囊试试。</p>';
}

albumDims.addEventListener("click", (e) => {
  const btn = e.target.closest("[data-dim]");
  if (!btn) return;
  albumState.dim = btn.dataset.dim;
  albumState.value = "全部";
  albumState.expanded = false;
  renderAlbumDims();
  renderAlbumValues();
  renderAlbumGrid();
});
albumValues.addEventListener("click", (e) => {
  const btn = e.target.closest("[data-value]");
  if (!btn) return;
  albumState.value = btn.dataset.value;
  renderAlbumValues();
  renderAlbumGrid();
});
albumToggle.addEventListener("click", () => {
  albumState.expanded = !albumState.expanded;
  renderAlbumValues();
});
document.getElementById("albumSort").addEventListener("click", (e) => {
  const btn = e.target.closest(".pill");
  if (!btn) return;
  document.querySelectorAll("#albumSort .pill").forEach((p) => p.classList.remove("active"));
  btn.classList.add("active");
  albumState.sort = btn.textContent.trim();
  renderAlbumGrid();
});

renderAlbumDims();
renderAlbumValues();
renderAlbumGrid();

/* 我的页 · 收藏作品：与相册共用媒体卡模板（首页卡片同款） */
const FAV_WORK_INDEXES = [0, 1, 3, 4, 5, 7];
document.getElementById("favGrid").innerHTML = FAV_WORK_INDEXES.map(
  (i) => mediaCardHtml(ALBUM_FILES[i]),
).join("");

/* 页面内交互（静态原型，仅样式/提示）：胶囊与分段单选、我的页 Tabs、保存提示 */
document.querySelectorAll(".page").forEach((page) => {
  const soloGroups = page.querySelectorAll(".pill-row, .seg-row");
  soloGroups.forEach((row) => {
    row.querySelectorAll(".pill, .seg").forEach((btn) => {
      btn.addEventListener("click", () => {
        row.querySelectorAll(".pill, .seg").forEach((b) => b.classList.remove("active"));
        btn.classList.add("active");
      });
    });
  });
});

/* 我的页 Tabs：关注作者 / 收藏作品 / 浏览历史 */
const M_PANES = { follow: "mpane-follow", fav: "mpane-fav", history: "mpane-history" };
document.querySelectorAll(".tab-btn").forEach((btn) => {
  btn.addEventListener("click", () => {
    document.querySelectorAll(".tab-btn").forEach((b) => b.classList.remove("active"));
    btn.classList.add("active");
    Object.values(M_PANES).forEach((id) => {
      document.getElementById(id).hidden = id !== M_PANES[btn.dataset.mtab];
    });
  });
});

/* 设置页：保存按钮显示提示（原型不写真实配置） */
document.getElementById("settingsSave").addEventListener("click", () => {
  document.getElementById("saveTip").hidden = false;
});

/* 搜索下拉面板：聚焦显示、点击面板外收起 */
const searchInput = document.getElementById("searchInput");
const searchPop = document.getElementById("searchPop");

function openSearchPop() { searchPop.hidden = false; }
function closeSearchPop() { searchPop.hidden = true; }

searchInput.addEventListener("focus", openSearchPop);
document.addEventListener("click", (e) => {
  if (!e.target.closest(".search")) closeSearchPop();
});

/* 历史词点击 → 直接进入搜索结果页 */
document.querySelectorAll(".pop-chip").forEach((el) => {
  el.addEventListener("click", (e) => {
    e.preventDefault();
    openSearchPage(el.textContent.trim());
  });
});

/* 清空搜索历史（整个历史块隐藏，本会话内保持为空） */
document.getElementById("popClear").addEventListener("click", (e) => {
  e.stopPropagation();
  document.getElementById("popHistoryBlock").hidden = true;
});

/* 展开/收起更多历史词 */
const popMore = document.getElementById("popMore");
popMore.addEventListener("click", (e) => {
  e.stopPropagation();
  const more = document.querySelectorAll(".pop-chip--more");
  const expanded = popMore.dataset.expanded === "1";
  more.forEach((chip) => (chip.hidden = expanded));
  popMore.dataset.expanded = expanded ? "0" : "1";
  popMore.querySelector(".pop-more-text").textContent = expanded ? "展开更多" : "收起";
});

/* 右下角悬浮刷新（静态原型）：点击图标旋转一圈作反馈，未接真实刷新 */
const refreshFab = document.getElementById("refreshFab");
refreshFab.addEventListener("click", () => {
  refreshFab.classList.remove("spinning");
  void refreshFab.offsetWidth; // 重置动画，连点可重复触发
  refreshFab.classList.add("spinning");
});
refreshFab.addEventListener("animationend", () => refreshFab.classList.remove("spinning"));

/* ===== 搜索结果页：顶栏搜索框回车（或点历史词）进入 =====
   排序行只保留 综合排序/最多点击（用户拍板）；「更多筛选」面板精简为（用户拍板 2026-09-02）：
   顺位/播放次数/文件大小/时间范围（「按年份区间」展开起止年下拉）/标签（模糊·精确/多选）。
   区间类筛选（播放/大小/时间/年份）原型无对应 mock 数据，仅切换样式不改变结果。 */
const SEARCH_STATE = {
  type: "综合",
  sort: "综合排序",
  order: "降序", plays: "全部", size: "全部", time: "全部",
  tagMode: "模糊", tags: [],
  yearFrom: "2016", yearTo: "2026",
};
const SEARCH_TAGS = ["样例", "摄影", "同人", "Cosplay", "风景", "日常", "创作", "舞台"];

/* 基础池 = 标签叠加后的集合；类型 tabs 计数与结果网格都基于它 */
function searchPool() {
  const s = SEARCH_STATE;
  const hitTag = (f, t) => Object.values(f.tags).includes(t) || f.name.includes(t);
  return ALBUM_FILES.filter(
    (f) =>
      s.tags.length === 0 ||
      (s.tagMode === "精确" ? s.tags.every((t) => hitTag(f, t)) : s.tags.some((t) => hitTag(f, t))),
  );
}

function renderSearchTypes() {
  const pool = searchPool();
  document.querySelectorAll("#stypeRow .stype").forEach((btn) => {
    const t = btn.dataset.stype;
    const n = t === "综合" ? pool.length : pool.filter((f) => f.tags.type === t).length;
    btn.classList.toggle("active", SEARCH_STATE.type === t);
    const badge = btn.querySelector(".stype-count"); // 「综合」无计数徽标（参照截图）
    if (badge) badge.textContent = n;
  });
}

function renderSearchGrid() {
  let files = searchPool();
  if (SEARCH_STATE.type !== "综合") files = files.filter((f) => f.tags.type === SEARCH_STATE.type);
  if (SEARCH_STATE.sort === "最多点击")
    files = [...files].sort(
      (a, b) => parseInt(b.views.replace(/,/g, ""), 10) - parseInt(a.views.replace(/,/g, ""), 10),
    );
  if (SEARCH_STATE.order === "升序") files = [...files].reverse();
  document.getElementById("searchGrid").innerHTML = files.length
    ? files.map(mediaCardHtml).join("")
    : '<p class="grid-empty">没有匹配的内容，放宽一点筛选条件试试。</p>';
}

const fPill = (value, key, current) =>
  `<button class="pill ${current === value ? "active" : ""}" data-fk="${key}" data-fv="${value}" type="button">${value}</button>`;

/* 标签池管理（DOMAIN_RULES §7 口径）：添加=浏览中新标签自动入池；删除=级联清理筛选选中态。
   原型为内存态，刷新即还原；筛选面板展示按名称升序（DOMAIN_RULES 标签排序口径） */
function addTag(raw) {
  const v = raw.trim().replace(/[<>&"']/g, "");
  if (!v || SEARCH_TAGS.includes(v)) return;
  SEARCH_TAGS.push(v);
}

function removeTag(tag) {
  const i = SEARCH_TAGS.indexOf(tag);
  if (i < 0) return;
  SEARCH_TAGS.splice(i, 1);
  const j = SEARCH_STATE.tags.indexOf(tag); // 级联：从筛选选中态里一并清除
  if (j >= 0) SEARCH_STATE.tags.splice(j, 1);
}

const sortedTags = () => [...SEARCH_TAGS].sort((a, b) => a.localeCompare(b, "zh-Hans-CN"));

const tagPill = (t, active) =>
  `<button class="pill pill-tag ${active ? "active" : ""}" data-fk="tag" data-fv="${t}" type="button">${t}<span class="tag-x" data-tag-x data-tag-name="${t}" title="删除标签">×</span></button>`;

const SEARCH_YEARS = Array.from({ length: 11 }, (_, i) => String(2026 - i));

/* 「按年份区间」选中时展开起止年下拉（原型无年份 mock 数据，仅样式） */
function yearRangeHtml(s) {
  const opts = (sel) =>
    SEARCH_YEARS.map((y) => `<option value="${y}" ${y === sel ? "selected" : ""}>${y} 年</option>`).join("");
  return `<span class="f-years">
    <select data-fy="yearFrom" aria-label="起始年份">${opts(s.yearFrom)}</select>
    <span class="f-years-sep">至</span>
    <select data-fy="yearTo" aria-label="结束年份">${opts(s.yearTo)}</select>
  </span>`;
}

function renderSearchFilters() {
  const s = SEARCH_STATE;
  const rows = [
    ["顺位", ["降序", "升序"].map((v) => fPill(v, "order", s.order))],
    ["播放次数", ["全部", "未播放", "1-5", "5-20", ">20"].map((v) => fPill(v, "plays", s.plays))],
    ["文件大小", ["全部", "<1MB", "1-10MB", "10-50MB", ">50MB"].map((v) => fPill(v, "size", s.size))],
    ["时间范围", [
      ...["全部", "今天", "本周", "本月", "近三月", "本年", "按年份区间"].map((v) => fPill(v, "time", s.time)),
      ...(s.time === "按年份区间" ? [yearRangeHtml(s)] : []),
    ]],
    ["标签模式", ["模糊", "精确"].map((v) => fPill(v, "tagMode", s.tagMode))],
    ["标签", [
      ...sortedTags().map((t) => tagPill(t, s.tags.includes(t))),
      `<button class="pill pill-add" data-fk="tagAdd" type="button">+ 添加</button>`,
      `<input class="tag-input" data-tag-input type="text" placeholder="新标签，回车添加" maxlength="12" hidden>`,
    ]],
  ];
  document.getElementById("filterRows").innerHTML = rows
    .map(([label, pills]) => `<div class="f-row"><span class="f-label">${label}</span><div class="f-opts">${pills.join("")}</div></div>`)
    .join("");
}

document.getElementById("filterRows").addEventListener("click", (e) => {
  // 标签删除 ×（优先于筛选切换）
  const x = e.target.closest("[data-tag-x]");
  if (x) {
    removeTag(x.dataset.tagName);
    renderSearchFilters();
    renderSearchTypes();
    renderSearchGrid();
    return;
  }
  // 「+ 添加」→ 切换为内联输入
  const addBtn = e.target.closest('[data-fk="tagAdd"]');
  if (addBtn) {
    addBtn.hidden = true;
    const input = document.querySelector("[data-tag-input]");
    input.hidden = false;
    input.focus();
    return;
  }
  const btn = e.target.closest("[data-fk]");
  if (!btn) return;
  const { fk, fv } = btn.dataset;
  if (fk === "tag") {
    const i = SEARCH_STATE.tags.indexOf(fv);
    if (i >= 0) SEARCH_STATE.tags.splice(i, 1);
    else SEARCH_STATE.tags.push(fv);
  } else {
    SEARCH_STATE[fk] = fv;
  }
  renderSearchFilters();
  renderSearchTypes();
  renderSearchGrid();
});

/* 标签内联输入：回车/失焦有词即入池，Esc 或失焦空词还原为「+ 添加」 */
function collapseTagInput() {
  const input = document.querySelector("[data-tag-input]");
  if (input) input.hidden = true;
  const add = document.querySelector('[data-fk="tagAdd"]');
  if (add) add.hidden = false;
}

document.getElementById("filterRows").addEventListener("keydown", (e) => {
  const input = e.target.closest("[data-tag-input]");
  if (!input) return;
  if (e.key === "Enter") {
    addTag(input.value);
    renderSearchFilters();
    renderSearchTypes();
    renderSearchGrid();
  } else if (e.key === "Escape") {
    collapseTagInput();
  }
});

document.getElementById("filterRows").addEventListener("focusout", (e) => {
  if (!e.target.closest || !e.target.closest("[data-tag-input]")) return;
  if (e.target.value.trim()) {
    addTag(e.target.value);
    renderSearchFilters();
    renderSearchTypes();
    renderSearchGrid();
  } else {
    collapseTagInput();
  }
});

document.getElementById("filterRows").addEventListener("change", (e) => {
  const sel = e.target.closest("[data-fy]");
  if (!sel) return;
  SEARCH_STATE[sel.dataset.fy] = sel.value; // 年份区间仅记录，不参与 mock 过滤
});

document.getElementById("stypeRow").addEventListener("click", (e) => {
  const btn = e.target.closest(".stype");
  if (!btn) return;
  SEARCH_STATE.type = btn.dataset.stype;
  renderSearchTypes();
  renderSearchGrid();
});

document.getElementById("sSort").addEventListener("click", (e) => {
  const btn = e.target.closest(".sort-pill");
  if (!btn) return;
  SEARCH_STATE.sort = btn.dataset.sort;
  document.querySelectorAll("#sSort .sort-pill").forEach((b) => b.classList.toggle("active", b === btn));
  renderSearchGrid();
});

document.getElementById("moreFilter").addEventListener("click", () => {
  const panel = document.getElementById("searchFilters");
  panel.hidden = !panel.hidden;
  document.getElementById("moreFilter").classList.toggle("open", !panel.hidden);
});

/* 每次新搜索重置筛选态与工具栏 UI（真实客户端语义：新查询不继承旧筛选） */
function resetSearchState() {
  Object.assign(SEARCH_STATE, {
    type: "综合", sort: "综合排序",
    order: "降序", plays: "全部", size: "全部", time: "全部",
    tagMode: "模糊", tags: [],
    yearFrom: "2016", yearTo: "2026",
  });
  document.querySelectorAll("#sSort .sort-pill").forEach((b) =>
    b.classList.toggle("active", b.dataset.sort === "综合排序"),
  );
  document.getElementById("searchFilters").hidden = true;
  document.getElementById("moreFilter").classList.remove("open");
}

/* 进入搜索结果页：query 写回搜索框（显示清除按钮），默认综合 tab + 综合排序、筛选面板收起 */
function openSearchPage(q) {
  if (!q) return;
  resetSearchState();
  searchInput.value = q;
  searchClear.hidden = false;
  closeSearchPop();
  searchInput.blur();
  renderSearchTypes();
  renderSearchFilters();
  renderSearchGrid();
  showPage("search");
}

searchInput.addEventListener("keydown", (e) => {
  if (e.key === "Enter") openSearchPage(searchInput.value.trim());
});

/* 搜索框清除按钮：有词显示，点击清空并回焦 */
const searchClear = document.getElementById("searchClear");
searchInput.addEventListener("input", () => {
  searchClear.hidden = searchInput.value.length === 0;
});
searchClear.addEventListener("click", () => {
  searchInput.value = "";
  searchClear.hidden = true;
  searchInput.focus();
});

/* ===== 我的页交互 ===== */
/* 关注按钮：已关注（灰描边）↔ 关注（主色实底）切换 */
document.getElementById("mpane-follow").addEventListener("click", (e) => {
  const btn = e.target.closest(".follow-btn");
  if (!btn) return;
  const isIdle = btn.classList.contains("follow-btn--idle"); // 当前=未关注
  btn.classList.toggle("follow-btn--idle", !isIdle);         // 点击后状态取反
  btn.textContent = !isIdle ? "关注" : "已关注";
});

/* 历史搜索：按标题/作者子串过滤历史卡（不区分大小写），整组无命中连组标题隐藏 */
const histSearch = document.getElementById("histSearch");
histSearch.addEventListener("input", () => {
  const q = histSearch.value.trim().toLowerCase();
  document.querySelectorAll("#mpane-history .hist-group2").forEach((group) => {
    let visible = 0;
    group.querySelectorAll(".hist-card").forEach((card) => {
      const text =
        card.querySelector(".hc-title").textContent + " " + card.querySelector(".hc-up").textContent;
      const hit = !q || text.toLowerCase().includes(q);
      card.hidden = !hit;
      if (hit) visible++;
    });
    group.hidden = visible === 0;
  });
});
