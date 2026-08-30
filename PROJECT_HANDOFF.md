# metro-next 项目交接文档

> 本文档用于新对话快速掌握项目全貌。更新于 2026-08-22（代码基线 commit `2dc1604`，数据版本 2026-08-03）。

---

## 1. 项目速览

| 项 | 值 |
|---|---|
| 名称 | metro-next · 北京地铁时刻表 |
| 形态 | **零依赖单文件网页应用** `beijing-metro.html`（~1.15MB，内嵌全部数据） |
| 线上链接 | https://2a0077361ce145d5a0b49a887fe8aae5.gz3.agentos-app.net （CloudStudio 静态托管） |
| GitHub | https://github.com/limboking/metro-next （公开，默认分支 main） |
| 数据源 | [Mick235711/Beijing-Subway-Tools](https://github.com/Mick235711/Beijing-Subway-Tools)（MIT，人工维护） |
| 数据规模 | 28 线 423 站，1080 个方向，~39.5 万时刻 |
| 用户 | 手机浏览器为主（微信/浏览器扫码或直链），关注性能与国内网络可用性 |

**核心价值**：查任意站点的下一班倒计时、全天班次、首末班，支持收藏与网页自助更新；专门适配了国内无代理环境（镜像下载 + 降级检查）。

---

## 2. 架构总览

```
GitHub 数据源（28 个 json5 + metadata）
        │  本地缓存 data/raw/bjst/（git 仓库）
        ▼
scripts/build_timetable.py   ← Python 解析 json5 → 站点索引（delta 行程压缩）
        │  注入 __DATA__ 占位符
        ▼
scripts/app_template.html（前端模板）→ beijing-metro.html（单文件产物）
        │
        ├── 部署：cp 到 deploy/index.html → CloudStudio 静态托管
        └── GitHub：git commit + push（含产物）
```

**关键架构决策**：
1. **单文件**：数据 + 界面 + 前端解析器全在一个 HTML 里，零依赖、易部署、可离线打开
2. **双实现解析**：Python（构建时）与 JS（网页自助更新时）各有一份 json5 → 站点索引的解析逻辑，用 `verify_consistency.js` 防漂移
3. **delta 行程压缩存储**：时刻存行程编码（`["05:08","间隔",N]`），前端加载后展开一次，文件 1.86MB → 1.15MB（含区间车/始发/快车增量数据）
4. **前端自助更新**：不依赖电脑端，浏览器直接镜像下载数据源 → 前端解析 → localStorage 缓存

---

## 3. 目录结构

```
D:\WorkBuddy_Save\手机小程序开发\
├── beijing-metro.html          # 构建产物（单文件，部署/分享用）
├── README.md                   # GitHub 项目说明
├── LICENSE                     # MIT
├── .gitignore
├── 地铁时刻表-二维码.png         # 指向线上链接的二维码（链接域名变了需重新生成）
├── 更新说明.png                 # 一次性分享图（不入库）
├── PROJECT_HANDOFF.md          # 本文档
├── scripts\
│   ├── build_timetable.py      # ★ 构建脚本（Python 解析 + 校验 + 注入）
│   ├── app_template.html       # ★ 前端模板（界面 + JS 解析器 + 自助更新）
│   └── verify_consistency.js   # ★ 一致性测试（Python/JS 双实现对比）
├── data\raw\bjst\              # 数据源 git 克隆缓存（不入库，~200MB）
│   └── data\beijing\*.json5    # 28 个线路文件 + metadata.json5
│   └── docs\specification.md   # 数据源格式规范（filters 语义权威文档）
└── deploy\index.html           # 部署副本（= beijing-metro.html 的拷贝）
```

---

## 4. 数据模型（核心，改代码前必读）

### 4.1 顶层结构

```js
{
  meta:     {...},        // 见 4.2
  lines:    {"1号线":"#A4343A", ...},   // 线路名 → 颜色
  stations: {"苹果园":[rec, rec, ...], ...},  // 站名 → 该站各方向记录
  initial:  {"苹果园":"pgy", ...}        // 拼音首字母索引
}
```

### 4.2 meta 字段

```js
{
  version: "3.1",        // 应用版本（构建时手改）
  fmt: 2,                // ★ 数据格式版本（改数据结构必须 bump，且同步改前端 DATA_FORMAT）
  updated: "2026-08-03", // 北京数据最近更新时间
  commitSha, bjSha,      // 数据源仓库/北京目录提交 sha（更新对比用）
  lines, stations,
  lineAliases: {"4号线大兴线":"4号线", ...},  // 旧线路名迁移映射
  fastSkip: {"6号线|东行|特别快速":["褡裢坡","黄渠",...], ...}  // 快车跳过的站列表
}
```

### 4.3 站点记录 rec（每个方向一条）

```js
rec = {
  l: "1号线",          // 线路名
  d: "东行",           // 方向（东行/西行/进城/出城）
  t: "环球度假区",      // 全程终点（方向卡标题用）
  n: "四惠东",         // 下一站（环线也能正确得出方向）
  g: [ [delta...], [delta...] ],  // ★ 去重后的日期组，存 delta 行程编码（见 4.5）
  gl: ["工作日","双休日"],        // 每组标签
  dow: "0112222",      // 7字符，周一到周日各指向 g 的索引
  // 以下为可选字段（无区间车/始发/快车时为 undefined）：
  xt: [ {"四惠":[525,529,...], ...} | null, ... ],  // 区间车/回库车：终点 → 时刻列表（按组对齐）
  xs: [ {"古城":[540,...] | null, ... }, ... ],     // 始发站：出库车/始发空车
  xf: [ {"快速":[570,...] | null, ... }, ... ],     // 快车名（6号线）
  xr: [ [525, 540, ...] | null, ... ],              // ★ 被跳站需排除的快车经过时刻
  // 以下为前端 expandDataG() 展开后附加（改数据时不要手写）：
  _term:  [{525:"四惠", ...}],   // 由 xt 构建：时刻 → 终点
  _start: [{540:"古城", ...}],   // 由 xs 构建
  _fast:  [{570:"快速", ...}]    // 由 xf 构建
}
```

### 4.4 时间语义（★ 最容易出错的地方）

- **所有时刻是"绝对分钟"**：`0=00:00`，`270=04:30`，`1440=次日00:00`，`1442=次日00:02`
- **凌晨 0:00-4:30（<270 分钟）归入昨天**：
  - `curGi(rec)`：凌晨时 `now-1day` 再取星期 → 选昨天那组班次
  - `nowMin()` 后 `if (base<270) base += 1440`（base 加到 1440-1710 区间，与跨夜末班对齐）
- **跨夜末班 ≥1440**（如 1442 = 次日 00:02），`fmtHMNextDay(m)` 对 ≥1440 标"次日"
- **calcNext(times, base)**：找第一个 `times[i] > base`；**末班已过**返回 `{abs: times[0]+1440, idx:-1, last: times[times.length-1]}`（次日首班标记）
- **nextDateOf(absMin)**：`absMin%1440` 换算当天时刻，若已过去则 +1 天

### 4.5 delta 行程编码（g 的存储格式）

```js
// 展开前（存储）：[首班, "间隔", 重复次数, 变化点, "间隔", 次数, ...]
// 例如 ["05:08","间隔",3, 322,"间隔",2] 表示：05:08, 05:10, 05:12, 05:22, 05:24
// 展开后（内存）：[308, 310, 312, 322, 324, ...] 纯分钟数组
```

前端 `expandDataG()` 加载后展开一次（幂等：已是数字数组则跳过）。

---

## 5. 数据源 filters 语义（区间车/始发/快车的来源）

数据源线路文件结构（见 `data/raw/bjst/docs/specification.md`）：

```json5
train_routes: {
  "东行": {
    "全程":   { ends_with: "环球度假区" },
    "古城交路": { ends_with: "四惠" },          // 区间车终点
    "出库车": { starts_with: "古城" },          // 始发站
    "快速":  { ends_with: "潞城", skip: true }, // 快车（6号线）
  }
},
timetable: {
  "苹果园": {
    "东行": {
      "工作日": {
        schedule: [["05:08","间隔",3], ...],   // 全量时刻（delta 编码）
        filters: [                             // 标记部分班次的属性
          { plan: "古城交路", first_train: "05:15", skip_trains: 2, until: "23:30" },
          // 或 trains: ["06:00","06:30"] 直接指定时刻
        ],
        skip_timetable: true,   // ★ 被快车跳过的站会记录快车经过时刻（需排除！）
      }
    }
  }
}
```

**解析规则**（Python `apply_filters` 与 JS `applyFilters` 双实现一致）：
1. `filters` 命中时刻（trains 直接给，或 first_train 起按 skip_trains+1 步长取，count/until 截止）
2. 命中时刻的 `plan` 去 `train_routes` 查：
   - `ends_with` → 终点 ≠ 全程终点 ⇒ **区间车**（进 xt）
   - `starts_with` → **始发车**（进 xs）
   - 线路是 6 号线 ⇒ **快车**（进 xf）；跳过的站记入 `meta.fastSkip`
3. **被跳站排除**：`skip_timetable:true` 时被跳站（如褡裢坡）时刻表里也有快车时刻，但这些车**不停本站**，必须记入 `xr` 并从 `g[gi]` 中移除（否则用户看到一辆坐不上的"下一班"）

---

## 6. 自助更新机制（国内免代理核心设计）

### 6.1 下载镜像链

`fetchSource(f, timeoutMs)` 依次尝试（单文件独立回退，20s 超时）：
1. `https://gh-proxy.com/https://raw.githubusercontent.com/...`
2. `https://ghfast.top/https://raw.githubusercontent.com/...`
3. `https://raw.githubusercontent.com/...`（直连）
4. `https://api.github.com/repos/.../contents/...`（API 兜底）

### 6.2 检查更新双路径

- **路径 A**（API 可达）：`api.github.com/repos/.../commits?path=data/beijing`（8s 超时）精确 sha 对比 → 有更新弹"立即更新"
- **路径 B**（国内直连失败自动降级）：弹确认"改为通过镜像下载最新数据检查（约 2.5MB）" → `downloadAndCompare()`：下载 28 文件 + 前端解析 + `dataFingerprint()` 内容指纹对比 → 一致提示"已是最新"，不同则应用

### 6.3 缓存规则

| localStorage key | 用途 |
|---|---|
| `bjmetro_data` | 自助更新后的新数据（**存 delta 原件**，刷新时展开） |
| `bjmetro_lastCheck` | 上次检查时间（每周节流） |
| `bjmetro_favs` | 收藏（`站名` 或 `站名||线||向`） |
| `bjmetro_last` | 上次访问的站 |

**采用条件**：`cached.meta.fmt === DATA_FORMAT(2)` **且** `bjSha ≠ 内置 bjSha`。改数据格式必须 bump fmt，旧缓存自动失效回退内置。

---

## 7. 功能清单

| 功能 | 说明 | 关键代码 |
|---|---|---|
| 下一班倒计时 | 秒级刷新；<1h 显示"X分X秒"，≥1h 显示"X小时X分" | `renderDirBody` / `tickFavNext` |
| 末班已过 | 显示"末班 XX:XX 已过" + 次日首班倒计时，保留班次列表和展开按钮 | `calcNext` idx<0 / `renderNextListEnded` |
| 首末班 + 展开全天班次 | 每组单独区块，当前时段高亮 | `renderNextList` / `allTimesHTML` |
| 区间车标记 | 终点 ≠ 全程终点标"区间·果园"橙色 | `termOf` / `marksOf` |
| 始发站标记 | 出库车标"古城始发" | `startOf` / `marksOf` |
| 快车标记 | 6号线标"快车·跳N站"；被跳站排除快车时刻 | `fastOf` / `fastSkipOf` / `xr` |
| 收藏 | 全站 / 指定方向；首页卡片实时下一班 | `tickFavNext` |
| 搜索选站 | 拼音首字母 + 关键词，线路按 1→2→…排序 | `renderPicker` |
| 返回键适配 | History API 逐级返回（详情→选站→首页） | `navTo` / `onPopState` |
| 自助更新 | 镜像下载 + 前端解析 + 缓存 | `checkUpdate` / `downloadAndCompare` |
| 每周自动检查 | 静默，有更新首页亮红点 | `autoCheckUpdate` |

---

## 8. 关键函数地图

### 8.1 Python（scripts/build_timetable.py）

| 函数 | 职责 |
|---|---|
| `sync_repo(force)` | clone/刷新数据源仓库 |
| `expand_delta` / `expand_schedule` | delta 行程编码 → 分钟数组 |
| `apply_filters(times, filters, routes, station)` | ★ 解析区间车/始发/快车/排除，返回 `{term,start,fast,exclude}` |
| `invert_map` | {时刻:站} → {站:[时刻]} |
| `build_index()` | 主解析：逐文件 → rec（含 xt/xs/xf/xr）→ stations |
| `validate(stations, lines)` | 全量校验（时刻非空/递增/班次数/站名/颜色/重复） |
| `get_bj_commit()` | 走 GitHub API 查北京数据目录最近提交 |
| `build()` | 组装 meta + 注入 __DATA__ → beijing-metro.html |

### 8.2 前端（scripts/app_template.html，约 1600 行）

| 函数 | 职责 |
|---|---|
| `expandDataG(d)` | 展开 g 的 delta + 构建 `_term/_start/_fast` + 移除 xr 时刻 |
| `loadBestData()` | 读取内置或 localStorage 缓存（fmt+bjSha 校验），展开，释放内嵌数据文本内存 |
| `fetchSource` / `downloadAll` | 镜像链下载 |
| `checkUpdate` / `downloadAndCompare` / `doUpdate` | 更新双路径 |
| `applyData(nd)` | 缓存 delta 原件 + 展开 + 替换全局 + 重绘 |
| `calcNext` / `curGi` / `dirTimes` | 时间语义核心 |
| `termOf` / `startOf` / `fastOf` / `marksOf` | 班次属性查询（收藏卡片注意用 `n.idx<0 ? n.abs-1440 : n.abs` 查标记） |
| `renderDirBody` / `renderNextList` / `renderNextListEnded` | 详情页渲染 |
| `tickFavNext` / `calcFavNext` | 首页收藏倒计时 |
| `navTo` / `onPopState` | 返回键适配 |

---

## 9. 构建 / 测试 / 部署命令

```bash
# 1. 构建（用缓存数据，无则自动 clone）
python scripts/build_timetable.py
# 强制更新数据仓库后构建
python scripts/build_timetable.py --refresh

# 2. 一致性测试（改双实现后必须跑）
node scripts/verify_consistency.js

# 3. JS 语法检查（快速）
node -e "const fs=require('fs');const h=fs.readFileSync('beijing-metro.html','utf8');[...h.matchAll(/<script>([\s\S]*?)<\/script>/g)].forEach((m,i)=>{new Function(m[1]);});console.log('OK')"

# 4. 本地预览（CDP 测试用）
cp beijing-metro.html deploy/index.html
cd deploy && python -m http.server 8641

# 5. 部署（CloudStudio）
# 用 workbuddy_cloudstudio_deploy 工具，directory = D:\WorkBuddy_Save\手机小程序开发\deploy

# 6. 推 GitHub
git add beijing-metro.html scripts/...
git -c user.name="limboking" -c user.email="limboking@users.noreply.github.com" commit -m "..."
git push "https://limboking:$(cat D:/My/GithubToken/token_exp261231.txt)@github.com/limboking/metro-next.git" main
# 注意：push 完成后 remote 里不要留 token（用临时 URL 方式）
```

**Python 环境**：`C:/Users/King/.workbuddy/binaries/python/envs/default/Scripts/python.exe`（已装 json5、pypinyin）
**Node 环境**：`C:/Users/King/.workbuddy/binaries/node/versions/22.12.0/node.exe`

---

## 10. 已知坑与约定（重要）

1. **双实现必须同步改**：Python `build_index` 与 JS `buildDataFromSources` 是两套独立实现，改解析规则必须两处都改 + 跑 `verify_consistency.js`（它提取前端解析器对比 28 个文件的 delta 原文与展开结果）
2. **改数据格式必须 bump**：同时改 Python `meta["fmt"]` 和前端 `DATA_FORMAT`，否则旧缓存混用会出诡异问题
3. **CDP 测试 mock 时间**：`Emulation.setSystemTime` 在新版 headless Chrome 不存在；用 `Page.addScriptToEvaluateOnNewDocument` 注入完整 MockDate（**必须支持多参数** `new Date(y,m,d,h,min,s)`，否则 nextDateOf 等内部构造会拿到错时间）
4. **跨夜语义**：凌晨 0-4:30 是"昨天"的运营日，改时间逻辑前先读第 4.4 节
5. **部署平台链接域名变过两次**：`.app.workbuddy.link` ↔ `.gz3.agentos-app.net`，部署后要验证链接，二维码需重生成
6. **token 泄露过一次**：曾出现在命令输出里，用户已撤销过一次；新 token 在 `D:/My/GithubToken/`，推送后确保 remote URL 无 token
7. **localStorage 配额**：数据 JSON ~1.7MB，接近 5MB 配额边缘，老设备可能存储失败（已有 try/catch 兜底，本次会话仍生效）
8. **数据准确性依赖上游**：构建已加全量校验（时刻非空/递增/首末班合理），但仍无法核对"真实运营数据"，上游改错需用户反馈

---

## 11. 近期改动时间线（2026-08）

| 日期 | 改动 | 结果 |
|---|---|---|
| 08-18 | 免代理更新（镜像链 + 降级检查）；性能优化批1（delta 压缩 1.86→0.83MB、内存释放、全量校验、一致性测试固化）；手机返回键适配 | 部署 + 发布 GitHub |
| 08-19 | 区间车标记（filters ends_with → xt，509/1080 方向含区间车）；两处调整（展开去橙色、收藏卡片显示）；始发站 + 快车跳站（starts_with/skip → xs/xf/xr + fastSkip，文件 0.98→1.15MB） | 部署 + GitHub |
| 08-22 | 末班已过显示修复（calcNext idx=-1、renderNextListEnded）；展开 NaN 修复；收藏卡片超 1 小时"X小时X分"；code review 3 处（idx=0 重复渲染、收藏标记时刻 mt 修正、删死代码 calcFirstGap） | 部署 + GitHub commit `2dc1604` |

---

## 12. 待办与未来方向（未实施）

1. **GitHub Actions 自动构建**：数据源更新 → 自动 rebuild + 部署（用户提过兴趣）
2. **PWA 化**：manifest + Service Worker，首次打开后真正离线；用 Cache API/IndexedDB 顺带解决 localStorage 配额问题
3. **换乘导航**：数据源 metadata 有全量换乘耗时/距离（transfers），**用户明确暂不做**（工程量大）
4. **站间耗时估算**（dist 字段），锦上添花
5. **数据源增量对比**：构建时输出与上一版差异统计，防上游改动误判

---

## 13. 新对话建议的第一步

1. 先读本文档 + `scripts/app_template.html`（重点 4.3-4.5、6、8.2 节）+ `scripts/build_timetable.py`（重点 5、8.1 节）
2. 如要改数据逻辑：先跑 `node scripts/verify_consistency.js` 确认基线绿，改完 Python+JS 再跑一次
3. 如要改显示：改 `app_template.html` → `python scripts/build_timetable.py` → 本地 http 服务 + CDP 验证（参考第 10.3 条 mock 方法）
4. 验收后：CloudStudio 部署 + git push（第 9 节命令）
