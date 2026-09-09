# Metro Next（北京地铁）项目说明与问题求助

> 本文档供外部 AI Agent 阅读：目标是在**无系统权限、无省电白名单、无常驻通知**
> 的约束下，为「安卓桌面小部件无法后台自动刷新」找到可行方案。文档末尾是
> 完整的问题陈述与已排除路径。

## 一、项目概述

- **产品**：北京地铁到站时刻查询（网页 + Android App + 桌面小部件三端同源）。
- **形态**：Android App（Kotlin）是 WebView 壳，渲染完全由单文件网页
  `beijing-metro.html` 承担（App 与网页天然一致）；桌面小部件（2×2/4×2/4×4）
  由原生 RemoteViews 渲染，读取网页侧导出的精简快照。
- **数据源**：上游 Mick235711/Beijing-Subway-Tools（MIT），网页内嵌编译好的
  JSON 数据（含全部线路/站点/班次，平峰/高峰/双休分组，delta 压缩班次表）。
- **部署**：网页版部署于 agentos-app.net/workbuddy.link（beijing-metro.html）；
  App 内通过 WebViewAssetLoader 以正规 https origin 提供本地 assets。
- **用户设备**：小米（澎湃OS / HyperOS），**Android 14+**（关键约束之一）。

## 二、功能需求

1. 查询任意线路任一方向的站点时刻表（平日/双休分组、快慢车标记、始发/区间标记）。
2. 收藏站点/线路方向（localStorage 持久化），首页卡片显示「下一班」倒计时。
3. 桌面小部件（用户核心场景）：
   - 2×2：单站，显示 站名 / 线路·方向·标记 / 下一站 / 最近 3 班（时刻·倒计时）；
   - 4×2：3 行收藏站（每页 3 站，可翻页）；4×4：6 行（每页 6 站，可翻页）；
   - 每行显示：线路色条、站名、线路·方向·标记、发车时刻、距发车倒计时；
   - 4×2 / 4×4 翻页列顶部有**手动刷新按钮**（↻，v1.0.17）：点击只重渲染当前
     小部件（重读最新快照 + 按当前时间重算倒计时）；单页时翻页箭头隐藏但
     刷新按钮常显；2×2 无此按钮；
   - **核心期望：每次解锁手机滑到小部件时，看到的是最新班次信息。**
     （v1.0.17 起用户已退一步接受现状：自动刷新被小米推迟可容忍，
     顶多解锁后点一下 ↻ 手动刷新；但仍欢迎零代价的解锁触发方案。）
4. App 内「小部件诊断日志」面板（无 adb 场景定位问题）+ 清空日志按钮。

## 三、实现方式与核心逻辑

### 3.1 网页层（beijing-metro.html，约 2100 行单文件）
- 内嵌 JSON 数据 → `expandDataG` 展开 delta 班次 → `STATIONS[站名]=[{l,d,t,n,g,dow}]`
  （g 为分钟数组或 {first_train,delta}；dow 为「周一~周日各指向 g 的哪一组」；
  **n 为数据预计算的「下一站」，环线按 reversed 标志环形取邻站**）。
- 收藏：`favs[站名]` 或 `favs["站|线路|方向"]`，排序 sortFavKeys 自定义。
- `buildWidgetSnapshot()`：把收藏站的班次导出为精简 JSON（见 3.3），
  通过 JS 桥 `window.MetroWidget.onSnapshot(json)` 推给原生。
- 数据加载 `loadBestData()`：localStorage 中自助更新缓存须「比内置新且带
  lineOrder」才启用，否则用内置（旧缓存曾导致站序排序错乱）。
- 「小部件诊断日志」按钮 → `getWidgetLog()` / `clearWidgetLog()`（仅 App 内）。

### 3.2 App 壳（MainActivity.kt，约 190 行）
- WebViewAssetLoader 提供 `https://appassets.androidplatform.net/assets/public/metro.html`
  （正规 origin，否则 localStorage 不可用、fetch 被拦）。
- `WidgetBridge`（@JavascriptInterface）：onSnapshot（存快照+排闹钟+刷新全部
  小部件）、getWidgetLog（日志顶部拼 TickState 自检结论）、clearWidgetLog。
- `onStop` → refreshAllWidgets（切后台/回桌面即刷新——**这是目前唯一可靠的
  刷新触发点之一**）。

### 3.3 小部件原生层（widget 包，纯 RemoteViews）
- **WidgetData.kt**：解析快照（v2：`{v,ts,f:[{n,it:[{l,c,t,d,dirDesc,dow,g,mk,ns}]}]}`）
  —— `g`=去重班次组（分钟）、`mk`=与 g 平行的 {分钟:始发/快车·跳N站/区间·YY}、
  `ns`=下一站；`rows()` 与网页版同逻辑计算每个收藏站「下一班」（凌晨 4:30 前归
  前一运营日，base+=1440；末班后返回次日首班 +1440）；`upcoming3`=最近 3 班。
  文件读写：**原子写（tmp+rename）+ 成功快照备份**（曾因 writeText 非原子导致
  渲染线程读到半截 JSON → 小部件退化成单行占位）。
- **MetroWidget.kt**（BaseWidget）：
  - `render()`：行数据 → `bindListRow`/`bindSmall`；
  - 布局要点（历次踩坑沉淀）：FrameLayout 根 + 翻页列 `layout_gravity="end"`
    钉右缘（LinearLayout 权重布局会被裁）；行 `height=0dp weight=1` 均分；
    色条是 **TextView + setBackgroundColor**（setImageViewBitmap 位图会被小米
    launcher 丢弃，翻页后色条消失）；倒计时纯文本（Chronometer 曾致
    「496579:35:38」假值，见 4.1）；
  - `WidgetTick.schedule()`：每分钟对齐闹钟链 —— nextTick = min(下一分钟边界,
    下一班发车)；收班后 = min(次日4:30, now+30min)；桌面无小部件则停排。
    统一用 `setAndAllowWhileIdle`（**不申请 SCHEDULE_EXACT_ALARM**）。
  - `WidgetTickReceiver`：触发 → refreshAllWidgets + 续排；监听 BOOT_COMPLETED。
  - 手动刷新（v1.0.17，v1.0.19 扩展到三尺寸）：`ACTION_REFRESH` 显式广播 +
    `refreshIntent`（requestCode = id*10+3，与翻页 +1/+2 区分）；onReceive 收到后
    只 render 该 widgetId。v1.0.19 起 widget_small/list3/list6 控件 id 完全一致
    （w_refresh + pager_prev/num/next），render() 统一处理：翻页控件按页数显隐、
    刷新按钮常显。注意 fallbackRender 兜底视图中 pager_box 整体 GONE（无刷新钮，
    可接受）。
  - `refreshAllWidgets()`：广播 ACTION_APPWIDGET_UPDATE **必须带
    EXTRA_APPWIDGET_IDS**（不带则 AppWidgetProvider.onUpdate 不会执行——
    曾致自刷静默空操作）。
  - `TickState`：SharedPreferences 记录 sched_at/sched_ts/fire_ts，
    App 日志面板顶部显示「后台刷新自检」结论。
- 三种尺寸：WidgetSmall(perPage=1)/WidgetMedium(3)/WidgetLarge(6)，共享逻辑，
  布局 widget_small.xml / widget_list3.xml / widget_list6.xml。
- 预览图：drawable-nodpi 三张独立（2×2=292² 方图；4×2、4×4=588×292 宽图）。

## 四、当前问题（求助核心）

### 4.1 P0 → 已降级：小部件无法后台自动刷新（v1.0.17 起用户暂接受现状）
> **2026-09-04 状态更新**：用户表示「暂时不需要特别实时的刷新了，现在这种就
> 挺好」，并在小部件上加了手动刷新按钮（↻）作为日常补偿。以下问题陈述保留，
> 供仍有兴趣探索「零代价解锁触发」方案的 Agent 参考，但**已非阻塞项**。
- **现象**：小部件只在以下时机刷新：打开 App 后退出、点翻页箭头、系统 30 分钟
  周期（updatePeriodMillis）。放桌面上不操作 → 永远停在旧画面（例如过了末班仍
  显示旧倒计时）。
- **已确证根因**：小米（澎湃OS）对**非省电白名单应用**执行「对齐唤醒」——
  AlarmManager 闹钟被无限期推迟，**直到 App 自己运行时才补发**。App 内诊断日志
  面板的「后台刷新自检」直接证实：有调度记录（sched_at）但无触发记录（fire_ts）。
- **用户真实需求**：不是「每分钟准点刷新」，而是**每次解锁手机、滑到小部件所在
  屏幕时，看到最新班次信息**即可（解锁频次低，可接受秒级~1 分钟延迟）。
- **硬约束（用户明确拒绝）**：
  1. 不开「省电策略=无限制 / 自启动」白名单；
  2. 不接受任何权限申请弹窗（含 SCHEDULE_EXACT_ALARM —— Android 14+ 默认拒绝，
     申请即弹框）；
  3. 不接受常驻通知（前台服务方案被拒）；
  4. 不接受状态栏常驻闹钟图标（setAlarmClock 方案被拒）。

### 4.2 已尝试并排除的路径（供参考，勿重复建议）
| 方案 | 结果 / 排除原因 |
|---|---|
| setExactAndAllowWhileIdle 精确闹钟 | Android 14 默认拒绝 SCHEDULE_EXACT_ALARM；弹权限框被用户拒绝 |
| setAndAllowWhileIdle 每分钟链 | 被小米推迟到 App 运行时才补发（实测确认） |
| RemoteViews Chronometer 秒级实时 | 小米 launcher 时间基准异常，显示 56 年量级假值（496579:35:38） |
| 清单注册接收 USER_PRESENT/SCREEN_ON | Android 8+ 静态广播收不到（不在豁免名单），进程又常死 |
| WorkManager / JobScheduler | 同样受 Doze/MIUI 推迟 |
| updatePeriodMillis=30min | 系统闹钟实现，同样被推迟（且 30 分钟粒度太粗） |
| 前台服务常驻 + 动态解锁广播 | 需通知权限（弹框）+ 常驻通知条（用户拒绝） |
| setAlarmClock 闹钟级定时 | 无需权限且不被推迟，但状态栏常驻闹钟图标（用户拒绝） |
| 请求电池优化豁免（ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS） | 即「无限制」弹窗，被用户拒绝 |

### 4.3 期望的解决方向（开放探索）
- 在不触发任何上述「用户可见代价」的前提下，寻找 Android 14+ / MIUI 上
  「屏幕解锁/亮屏」或「小部件可见」事件可用的低代价触发机制；
- 或设计「**不依赖后台刷新也能保证正确**」的展示方案（例如：只展示随时间
  自动正确的内容——绝对时刻？launcher 自渲染视图？）；用户反馈过
  「最方便快捷在桌面小部件看到下一个班次时刻」的需求，可往此方向发散；
- 或验证某个被低估的系统机制（如 RemoteViews 集合型 ListView/StackView +
  RemoteViewsService 在 launcher rebind/unlock 时是否重新拉取数据）。

## 五、关键文件路径

```
D:\WorkBuddy_Save\手机小程序开发\
├── beijing-metro.html                 # 网页版（App 同源，改后需同步 assets）
├── android\
│   ├── build_apk.sh                   # 构建脚本（沙箱外运行，产物 MetroNext-debug.apk）
│   └── app\src\main\
│       ├── AndroidManifest.xml
│       ├── assets\public\metro.html   # 内置网页（= beijing-metro.html 同步副本）
│       ├── java\com\metronext\metro\
│       │   ├── MainActivity.kt        # WebView 壳 + WidgetBridge
│       │   └── widget\
│       │       ├── MetroWidget.kt     # 渲染逻辑 + WidgetTick/Receiver/TickState
│       │       ├── WidgetData.kt      # 快照解析 + 下一班计算
│       │       └── WidgetLog.kt       # 调试日志（外置文件，无 adb 用）
│       └── res\
│           ├── layout\widget_small/list3/list6.xml
│           ├── drawable-nodpi\widget_preview_{small,medium,large}.png
│           └── xml\widget_{small,medium,large}_info.xml
├── scripts\gen_widget_preview.py      # 预览图生成
└── MetroNext-debug.apk                # 最新构建产物
```

## 六、版本演进记录（小部件刷新专题）

- v1.0.10 及以前：精确闹钟唤醒——Android 14 权限默认拒绝 → 失效。
- v1.0.11：改为每分钟 setAndAllowWhileIdle 自续链（无权限）；
  修复：广播不带 EXTRA_APPWIDGET_IDS 导致 onUpdate 空操作；
  开机重排（RECEIVE_BOOT_COMPLETED）；App onStop 即刷新。
- v1.0.12：快照原子写 + 成功备份回退（修复长按后单行占位）。
- v1.0.13：尝试 RemoteViews Chronometer 秒级实时倒计时（删每分钟链）。
- v1.0.14：Chronometer 过零后从 0 累加（0:00→0:22→2:26 无规律）。
- v1.0.15：Chronometer 废弃（496579:35:38 假值），回纯文本 + 分钟链 + 日志清空。
- v1.0.16：TickState 后台刷新自检（证实小米推迟闹钟，App 运行时才补发）。
- v1.0.17：4×2/4×4 翻页列顶部新增手动刷新按钮（↻）；预览图同步加图标；
  预览脚本 draw_pager 加 refresh 参数。另：D:\metro_debug.keystore 曾丢失，
  已确认与 ~/.android/debug.keystore 同钥（证书 SHA-256 一致）并恢复，
  备份在 D:\metro_apk_build\metro_debug.keystore.bak。
- v1.0.18：修复 v1.0.17 引入的回归——2×2 翻页列被误改为无条件隐藏（多收藏时
  上下箭头消失）；恢复 showPager = pages > 1 判定（2×2 无 w_refresh，仍不碰）。
- v1.0.19：2×2 也加手动刷新按钮（用户要求）：widget_small.xml 翻页列顶部加
  w_refresh，三布局控件 id 完全一致，render() 按尺寸的分支合并为统一逻辑
  （翻页控件按页数显隐、刷新按钮常显）；2×2 预览图同步加 ↻。
- v1.0.20：release 版去调试功能——「小部件诊断日志」入口仅 debug 构建显示
  （原生 debug 构建加载网页时附加 ?debug=1，网页端据此显隐按钮）；release 与
  debug 同签名（signingConfig=debug，可直接覆盖升级）；build_apk.sh 支持
  `bash build_apk.sh [debug|release]` 变体。
- 当前构建 versionCode 24 / versionName 1.0.24。
- v1.0.24：末页补齐——收藏数非每页整数倍时（如 8 站/每页 6 站），末页原来
  只剩 1-2 行且 weight 均分导致大片留白（用户误以为站点丢失）；现末页从
  队尾回退取满（`start = min(page*perPage, total-perPage)`），与前一页少量
  重叠但每页满行。
- v1.0.23（诊断增强，无行为变化）：buildWidgetSnapshot 把被丢弃的收藏
  （站名不在当前数据/方向匹配失败/班次组为空）记入快照顶层 drop 字段
  （k + 原因）；原生 WidgetData.save() 读到 drop 非空时经 WidgetLog 打印
  「快照丢弃收藏(N): ...」——仅 debug 构建落盘。用于定位「小部件只剩
  N 行」：rows 行给快照实际站点，drop 行给被丢站点及原因。
- v1.0.22（纯打包修复，功能与 v1.0.21 一致）：build_apk.sh 的 tar 排除模式
  原先 `*_old_*` 匹配不到 `metro.html.old_*`（点号形态），导致 sync 时改名
  留下的旧 metro.html 残留被打进 APK（包体虚大 ~0.35MB）。已补
  `*.old_*`/`*.bak`/`*.tmp` 等排除项并清理源树残留。
- v1.0.21：release 构建彻底停写诊断日志——WidgetLog.append 入口处
  按 `applicationInfo.flags & FLAG_DEBUGGABLE` 判定（项目未开
  buildFeatures.buildConfig，没有 BuildConfig 类，勿用 BuildConfig.DEBUG），
  debug 版不受影响。
- 对抗式审查记录（2026-09-04，三 Agent 独立结论）：v1.0.19→v1.0.22 安卓
  渲染/数据代码零改动；「4×2/4×4 只显示 2 行」唯一代码层解释是快照里只剩
  2 个收藏站（rows.size==2），需用 debug 版诊断日志的 `rows:` 行定位是哪
  环节丢的；「小部件从选择器消失」在 APK/清单层面无任何异常（aapt dump
  xmltree 验证 receiver/provider 完好），判定为 HyperOS 桌面缓存问题，
  重启手机可恢复。
- 发布：Release APK 通过 GitHub Releases 发布（scripts/gh_release.py 自动建
  Release + 传 APK）；git push 被沙箱干扰时用 scripts/push_github.py 兜底。

## 七、附：小部件相关既有修复清单（防回归提示）

1. MIUI 预览槽按高度截断：方形大图会被缩小，2:1 宽图可铺满；三尺寸预览图
   各自独立、比例与 widget 声明匹配。
2. widget_info minWidth 若 > launcher 实际格子宽会被裁右（曾 294dp → 250dp）。
3. 快照必须带版本号（v2），原生拒绝 v<2 旧缓存（曾致环线无方向、始发不显示）。
4. 环线「下一站」取数据预计算 rec.n（勿用 LINE_ORDER 反推，环线会丢）。
5. 站序排序依赖 lineOrder：App WebView 旧版自助更新缓存会顶掉内置新数据
   （loadBestData 已加「比内置新且带 lineOrder」判定）。
6. RemoteViews 只能操作目标布局真实存在的 id：v1.0.19 起三布局 id 已统一
   （w_refresh + pager_*），但 fallbackRender 用的 widget_small 中 pager_box
   会被整体 GONE——新增控件时三布局与 fallback 路径都要核对。
7. debug 签名库 = C:\Users\King\.android\debug.keystore（D: 盘那份是副本）；
   丢失时从前者恢复，勿重新生成（否则无法覆盖安装，会丢收藏）。
