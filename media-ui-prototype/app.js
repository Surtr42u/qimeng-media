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

/* 顶栏 tab 切换：热门 tab 下显示二级导航（综合热门/排行榜），其余隐藏 */
const subnav = document.getElementById("subnav");
const rankPanel = document.getElementById("rankPanel");

function resetSubnav() {
  document.querySelectorAll(".subtab").forEach((s) =>
    s.classList.toggle("active", s.dataset.sub === "hot"),
  );
  rankPanel.hidden = true;
}

document.querySelectorAll(".tab").forEach((tab) => {
  tab.addEventListener("click", () => {
    document.querySelectorAll(".tab").forEach((t) => t.classList.remove("active"));
    tab.classList.add("active");
    const isHot = tab.dataset.tab === "hot";
    subnav.hidden = !isHot;
    if (isHot) resetSubnav(); // 切回热门时恢复默认二级页
  });
});

/* 二级导航：排行榜 → 展开 日/月/周/年 面板（静态原型：仅样式） */
document.querySelectorAll(".subtab").forEach((s) => {
  s.addEventListener("click", () => {
    document.querySelectorAll(".subtab").forEach((t) => t.classList.remove("active"));
    s.classList.add("active");
    rankPanel.hidden = s.dataset.sub !== "rank";
  });
});

document.querySelectorAll(".rank-tab").forEach((t) => {
  t.addEventListener("click", () => {
    document.querySelectorAll(".rank-tab").forEach((x) => x.classList.remove("active"));
    t.classList.add("active");
  });
});

/* 侧边栏导航切换：active 高亮 + 对应页面显示/隐藏（页面由 data-page 映射到 #page-*）；
   顶栏「推荐/cos/热门」与热门二级导航只在首页显示 */
function showPage(pageId) {
  const page = document.getElementById(`page-${pageId}`);
  if (page) {
    document.querySelectorAll(".page").forEach((p) => (p.hidden = p !== page));
    document.querySelector(".content").scrollTop = 0;
    document.querySelector(".tabs").hidden = pageId !== "home";
    // 首页时 subnav 跟随顶栏 tab 激活态（热门才显示），其余页面一律隐藏
    document.getElementById("subnav").hidden =
      pageId !== "home" || document.querySelector(".tab.active")?.dataset.tab !== "hot";
    if (pageId === "home" && document.querySelector(".tab.active")?.dataset.tab === "hot") {
      document.querySelectorAll(".subtab").forEach((s) =>
        s.classList.toggle("active", s.dataset.sub === "hot"),
      );
      document.getElementById("rankPanel").hidden = true;
    }
  }
}

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

/* 历史词点击 → 填入搜索框并收起（原型不做真实搜索） */
document.querySelectorAll(".pop-chip").forEach((el) => {
  el.addEventListener("click", (e) => {
    e.preventDefault();
    searchInput.value = el.textContent.trim();
    closeSearchPop();
  });
});

/* 清空搜索历史（隐藏历史区与「展开更多」） */
document.getElementById("popClear").addEventListener("click", (e) => {
  e.stopPropagation();
  document.getElementById("popHistory").hidden = true;
  document.getElementById("popMore").hidden = true;
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
