# HyperOS（澎湃OS）小部件：调研结论与实施方案

> 面向 Metro Next（北京地铁时刻表）现有 Android App + 桌面小部件。
> 调研时间 2026-09-07，依据小米开放平台现行公开文档（dev.mi.com）。

---

## 0. 结论先行

**推荐路线：把现有三个尺寸的原生小部件，升级为「小米小部件」（走小部件开放平台审核上架）。**

核心理由：小米小部件体系提供 **曝光刷新** 能力——用户滑到小部件所在页面时，由系统主动拉起小部件的独立进程执行一次刷新。这恰好直击项目当前 P0 痛点（闹钟被 HyperOS 对齐唤醒推迟、解锁后看到旧画面），而且是**系统级触发，不依赖省电白名单、不弹权限框、不需要常驻通知**，完全符合用户此前明确划定的硬约束。

代价：必须完成一整套上架流程（APP 先上小米应用商店 → 软著 + APP 备案 + 隐私政策页 → 小部件平台上传审核）。

**建议分两阶段推进**：阶段 A 只做技术适配与自测（零资质门槛，约 1~2 天），先在真机上验证「曝光刷新」确实解决痛点；验证通过后再投入阶段 B（资质 + 上架，约 4~8 周，主要卡在软著与备案）。

---

## 1. 项目现状盘点

| 项 | 现状 | 与小米小部件规范的差距 |
|---|---|---|
| 小部件尺寸 | 2×2 / 4×2 / 4×4（`WidgetSmall/Medium/Large`） | 尺寸合规，但 `minWidth/minHeight` 与官方建议值不符（见 3.4） |
| 渲染方式 | 纯 RemoteViews（`MetroWidget.kt`） | 合规 |
| 刷新机制 | `updatePeriodMillis=30min` + 自建分钟级 `setAndAllowWhileIdle` 闹钟链 | **不合规且不生效**：小米 Widget 会去掉系统定时刷新；自建闹钟已被证实被系统推迟 |
| 进程 | 运行在 App 默认进程 | **不合规**：必须在 `:widgetProvider` 独立进程 |
| 小米标识 | 无 | **缺失**：`miuiWidget` / 曝光刷新 meta-data 均未配置 |
| 深色模式 | `values-night/colors.xml` + **代码 `setTextColor` 动态设色** | **不合规**：只能 XML 静态适配，代码动态设色在深色切换后会用缓存 RemoteViews 重建而失效 |
| 小部件名称 | 三个 receiver 共用 `@string/app_name`（= 应用名 Metro Next） | **违规**：名称须 2~10 汉字且不得与应用名相同 |
| 根布局 | `FrameLayout @+id/widget_root` + `@drawable/widget_bg` | **不合规**：根布局 id 必须是 `@android:id/background`，且背景不能全透明 |
| 隐私协议兜底 | 无 | **缺失**：未同意隐私协议时须显示兜底图 |
| 签名 | debug 签名（D:/metro_debug.keystore） | 上架须正式签名（会导致已装 debug 版无法覆盖升级，见 6.2） |
| targetSdk | 34（compileSdk 34） | 满足最低要求（≥30），但 2026 年建议升到 35/36 |

关键文件：
- `android/app/src/main/AndroidManifest.xml`
- `android/app/src/main/java/com/metronext/metro/widget/MetroWidget.kt`
- `android/app/src/main/java/com/metronext/metro/widget/WidgetData.kt`
- `android/app/src/main/res/xml/widget_{small,medium,large}_info.xml`
- `android/app/src/main/res/layout/widget_{small,list3,list6}.xml`
- `android/app/src/main/res/values/colors.xml`、`values-night/colors.xml`

---

## 2. 调研：HyperOS 上的「小部件」到底有几条路

| 路线 | 入口 | 需要审核 | 曝光刷新 | 适合度 |
|---|---|---|---|---|
| **A. 安卓原生小部件**（现状） | 桌面长按 → 小部件 → 支持小部件应用 → **安卓小部件** | 否 | ❌ 无（只有 30min 周期，且被推迟） | 已实现，但无解痛点 |
| **B. 小米小部件**（推荐） | 桌面双指捏合 / 负一屏「+」→ **小部件中心** | 是（应用商店 + 小部件平台双重） | ✅ **有**（最短间隔 10 秒） | ★ 目标路线 |
| **C. 快应用卡片**（负一屏） | 快应用联盟 RPK | 快应用官网 + 各厂商审核 | 依赖快应用自身机制 | ✗ 需重做一套快应用，与现有 Kotlin/H5 资产不复用 |
| **D. 主题 mtz 小部件**（Widget Studio） | 小米主题商城（设计师站） | 主题审核 | 仅支持系统变量（时间/天气/步数），无法承载 40 万条时刻数据 | ✗ 能力不足，排除 |
| **E. 超级岛 / 锁屏小组件**（HyperOS 3 新增） | 屏幕上方岛、锁屏 | 未见三方开放入口 | — | ✗ 2026 年世界杯赛程等均为官方/大厂定制，无公开三方接入 |

**重要机制**：一旦某小部件以「小米小部件」身份在小部件中心上线，安卓原生小部件池里就不会再显示它（同一应用内按规范开发的 A 会顶掉未按规范的 B、C）。因此改造后，小米用户只能从小部件中心添加——这也说明上架是必选项而非可选项。

---

## 3. 小米小部件技术规范要点（改造依据）

> 来源：《小部件技术规范与系统能力说明》(pId=1584)、《Xiaomi HyperOS 小部件设计规范》(pId=1664)、《小部件审核规范》(pId=1586)、《小部件适配常见问题 Q&A》(pId=1591)

### 3.1 独立进程（强制）
- 进程名**必须**是 `:widgetProvider`；receiver / service / provider 都要声明（Activity 不需要）。
- 该进程：不能拉起其它任何进程（含 App 主进程）、不能 fork、只能跑小部件内容准备与刷新逻辑、**内存 ≤ 35MB**（`adb shell dumpsys meminfo` 验证；审核规范口径为 40MB）。
- 影响面：快照 JSON 的读写（主进程写、widget 进程读）、`WidgetPrefs` 页码 SharedPreferences 都要按跨进程复核。

### 3.2 曝光刷新（本项目的核心价值）
- 小米 Widget 会**去掉系统原有的定时刷新**（`updatePeriodMillis` 基本失效），改为「用户滑动到有 Widget 的页面时，系统判定需要刷新并通知应用」。
- 清单申请：
```xml
<receiver android:name=".widget.WidgetMedium"
          android:process=":widgetProvider">
    <meta-data android:name="miuiWidget" android:value="true" />
    <meta-data android:name="miuiWidgetRefresh" android:value="exposure" />
    <meta-data android:name="miuiWidgetRefreshMinInterval" android:value="20000" /> <!-- 毫秒，最短 10000 -->
    <intent-filter>
        <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
        <action android:name="miui.appwidget.action.APPWIDGET_UPDATE" />
    </intent-filter>
    <meta-data android:name="android.appwidget.provider" android:resource="@xml/widget_medium_info" />
</receiver>
```
- 代码侧必须在 `onReceive` 里显式处理 `miui.appwidget.action.APPWIDGET_UPDATE`（`super.onReceive` 不会分发到 `onUpdate`）：
```kotlin
if (intent.action == "miui.appwidget.action.APPWIDGET_UPDATE") {
    val ids = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)
    onUpdate(ctx, AppWidgetManager.getInstance(ctx), ids)
}
```
- 建议间隔 **15~30 秒**（倒计时是分钟粒度，10 秒过于频繁；锁屏再解锁也要能触发）。

### 3.3 小部件版本号（务必适配，否则每次发版都要重审）
- `application` 节点下（**不是**某个 provider 下）：
```xml
<meta-data android:name="miuiWidgetVersion" android:value="1" />
```
- 整数、从 1 开始、只升不降；任一维件有变动/新增才升版本号。适配后，App 发版而小部件未变时**无需重新提审小部件**；不适配则每次 App 发版不重审会导致线上小部件消失。

### 3.4 尺寸与安全区
| 尺寸 | 官方建议 minWidth × minHeight | 当前值 |
|---|---|---|
| 2×2 | 110 × 110 dp | 125 × 125 |
| 4×2 | 300 × 110 dp | 250 × 125 |
| 4×4 | 300 × 250 dp | 需核对（目标 300×250） |

- 注意 `minWidth/minHeight` 只用于计算占几格，实际显示尺寸由 launcher 格子决定。项目此前踩过「minWidth 294 → 250 才不裁右」的坑，改到 300 后**必须真机复测 4×6 / 5×6 布局**。
- 内容安全区：1080p 屏 ≥40px、2K 屏 ≥54px（2×2/4×2/4×4）。
- 圆角：系统统一裁切，开发者交付资源 1080p 55px / 2K 73px；**预览图按最新规范交直角图，由系统裁 55px**（旧文档口径为 38px，以 HyperOS 版规范为准，提交前建议与官方确认）。

### 3.5 深色模式（最容易踩）
- **只能在 XML 里静态适配**（`values-night` / `drawable-night`），不支持 RemoteViews 代码动态设色。
- 原因：新版系统切换深色模式时不再回调 `onAppWidgetOptionsChanged`（避免拉起三方进程），而是由宿主用**上一次的 RemoteViews 重建**。因此 `setTextColor` 写进 RemoteViews 的颜色值不会被重新计算。
- 本项目 `MetroWidget.kt` 中 `setTextColor(...accent/danger/secondary/tertiary)` 共 5 处需改造 → 建议改为「布局内预置两套 TextView（常规色 / 紧急色），用 `setViewVisibility` 切换」。

### 3.6 布局与内容
- 根布局必须 `android:id="@android:id/background"`，且**背景必须有颜色、不能全透明**（系统据此加圆角与切换动画）。
- 根布局 `match_parent`，内容居中，用 weight / 相对布局，不用绝对尺寸。
- 禁止：小部件内上下/左右滑动、文字输入。（现有「点击箭头翻页」是点击而非滑动，合规）
- 无内容场景（无网络 / 无数据 / 未授权 / 未添加收藏）须有占位色块 + 说明文字 + 加载态占位符。
- **用户未同意隐私协议时，小部件应显示兜底图**，点击跳 App 并弹授权。

### 3.7 命名与文案
- 展示为「应用名称·小部件名称」；小部件名称 **2~10 个汉字**，且不得与应用名相同。
- 三个尺寸若 `android:label` 相同，会在小部件中心详情页**聚合为一个**（推荐：三者同名，如「地铁时刻」）。
- 小部件介绍 ≤22 个中文字符，**末尾禁止标点**，不得用极限词（最/第一/必备等），不得含「小米」字样。

### 3.8 可选系统能力（后续可加，非必需）
- 判断设备是否支持小米 Widget：`content://com.miui.personalassistant.widget.external` 的 `isMiuiWidgetSupported`。
- App 内引导添加：`requestPinAppWidget(provider, extras, null)`，`extras` 填 `addType="appWidgetDetail"` 可直接拉起小部件中心详情页（需已上线）。
- 负一屏排序优先级：`updateAppWidgetOptions` 传 `miuiWidgetEventCode` / `miuiWidgetTimestamp`（事件码需与商务确认）。
- Push 透传刷新（服务端 → MiPush → 拉起 widget 进程）：需接入 `developer.assistant.miui.com` 开放 API + oaid + 签名，**成本高，本项目用不上**。
- 指定「编辑小部件」页：`updateAppWidgetOptions` 传 `miuiEditUri`。

---

## 4. 开发 → 上架全流程

### 4.1 全景时间线

```
阶段 A  技术适配 + 真机自测（1~2 天，无资质门槛）
   ├─ A1 按第 3 节改造代码，出 debug 包
   ├─ A2 真机验证：解锁滑到小部件屏 → 是否自动刷新
   └─ A3 达标核对：独立进程内存 ≤35M、深色模式、多布局适配
                ↓ 验证通过再继续
阶段 B  邮件报备（官方 10 个工作日内回复）
   └─ 发 miui-widget@xiaomi.com（模板见 4.2），拿到《三方适配全流程指导》+提测大礼包
阶段 C  资质办理（4~8 周，可并行）
   ├─ C1 计算机软件著作权（三选一：软著登记证书 / APP 电子版权证书 / 软著认证证书）
   ├─ C2 工信部 APP 备案（2023 起强制，应用内须展示备案号且可点击跳转）
   ├─ C3 隐私政策网页（独立公网可访问链接 + App 内弹窗同意）
   └─ C4 开发者账号（个人 or 企业）+ 客服邮箱/反馈渠道
阶段 D  应用商店上架（1~3 工作日）
   └─ 正式签名 APK + 图标 512 + 截图 4~5 张（1920×1080）+ 资质提交 → 审核
阶段 E  小部件平台上传与审核（1~3 工作日）
   ├─ E1 应用商店审核通过后，widget.xiaomi.com 出现入口（首次需官方已开通）
   ├─ E2 平台自动解析 APK 内小部件 → 填名称/介绍/预览图/本地化/发布设备 → 提交
   └─ E3 审核通过 → 小部件中心上线
阶段 F  运营与迭代
   ├─ 排序调整：随时可改，平台每 5 分钟推送，无需审核
   ├─ 仅资料更新：已上架的直接「更新资料」
   └─ 小部件有改动：升 App 版本 → 升 miuiWidgetVersion → 重新提交审核
```

### 4.2 首次报备邮件模板（官方要求，复制到 miui-widget@xiaomi.com）

```
开发者微信：
开发者联系电话：
适配小部件类型：
应用包名：com.metronext.metro

我方已阅读小部件产品设计规范与技术规范，准备按照审核要求适配小部件，
希望进一步沟通审核流程和相关规范。
```

回复后官方会提供 **《三方适配全流程指导》**（含未上架小部件的自测方法）与 **提测大礼包**（静置功耗测试、内存测试的执行步骤与报告模板）。

### 4.3 资质与物料清单

| 项 | 要求 | 备注 |
|---|---|---|
| 软著 / APP 电子版权证书 | 三选一，必交，名称须与应用名一致 | 个人可申请；普通 30~60 工作日，加急 5~15 工作日 |
| APP 备案 | 必交（工信部），应用内显著位置展示备案号且可跳转查询 | 个人可备案，5~15 工作日 |
| 隐私政策 | 独立公网链接 + App 首屏弹窗（必须有同意/拒绝按钮） | 需覆盖权限、第三方 SDK、数据存储、注销 |
| 应用图标 | 512×512 PNG，无透明通道 | — |
| 应用截图 | 4~5 张，1920×1080 | 无第三方水印 |
| 客服渠道 | 应用内须有付费/问题反馈渠道（邮箱即可） | 分级规范里 L2-1 明确检查 |
| 测试账号 | 有登录/付费/权限功能时提供 | 本项目无需 |
| targetSdk | ≥30（建议升到 35/36） | 当前 34 |

### 4.4 自测（阶段 A 的关键，未上架如何验证）

小部件中心只展示**已审核上线**的小部件，审核中的无法在线上看到。验证手段：
1. **首选**：debug 版加一个临时按钮，调用 `AppWidgetManager.requestPinAppWidget()` 直接把小部件 pin 到桌面，绕开小部件中心；
2. 桌面长按 → 小部件 → 支持小部件应用 → 安卓小部件入口（加了 `miuiWidget` 标识后是否仍出现，需实测）；
3. 邮件联系官方拿到《三方适配全流程指导》里的官方自测流程；
4. 抓日志验证曝光刷新：`adb logcat` 过滤 `miui.appwidget.action.APPWIDGET_UPDATE`，或在 `onReceive` 里记一笔（复用现有 `WidgetLog` debug 通道）。
5. 内存验证：`adb shell dumpsys meminfo com.metronext.metro:widgetProvider`。

### 4.5 提测（阶段 B 之后，官方流程）

- 需提交：静置功耗测试报告（4G 与 WiFi **都要测**）、内存测试报告（官方要求 Linux 环境，可用 WSL / adb + 脚本替代，需与官方确认可接受度）。
- 测试环境审核通过 → 上传小部件开放平台 → 再次测试通过 → 正式上架。
- 设备要求：MIUI 13 / HyperOS 及以上中高端机型。

---

## 5. 代码改造清单（阶段 A，WBS）

| # | 任务 | 文件 | 说明 |
|---|---|---|---|
| A1 | 三个 receiver 迁到 `:widgetProvider` 进程 | `AndroidManifest.xml` | 含 `WidgetTickReceiver`；`MainActivity` 保持主进程 |
| A2 | 加小米标识 + 曝光刷新 meta-data + `miui.appwidget.action.APPWIDGET_UPDATE` | `AndroidManifest.xml` | 三个 receiver 各一套 |
| A3 | `application` 下加 `miuiWidgetVersion=1` | `AndroidManifest.xml` | 只升不降 |
| A4 | `onReceive` 处理曝光刷新广播 | `MetroWidget.kt` | 分发到 `onUpdate` |
| A5 | 去掉代码动态 `setTextColor` | `MetroWidget.kt`（5 处） | 改「双 TextView 切显隐」，颜色全部 XML 静态化 |
| A6 | 根布局 id 改 `@android:id/background` + 不透明背景 | 三个 layout xml | 同时加 `drawable-night/widget_bg.xml` |
| A7 | minWidth/minHeight 调至官方建议值 | 三个 `widget_*_info.xml` | 2×2=110×110、4×2=300×110、4×4=300×250；真机复测是否裁切 |
| A8 | 小部件名称独立（≠ 应用名） | `strings.xml` | 如「地铁时刻」，三者同名以便聚合 |
| A9 | 隐私协议未同意时的兜底 RemoteViews | `MetroWidget.kt` + 新增 layout | 状态标志建议用文件（跨进程可靠）而非 SharedPreferences |
| A10 | 无内容场景占位符改造 | 三个 layout xml | 占位色块 + 说明文字 |
| A11 | 跨进程数据一致性复核 | `WidgetData.kt`、`WidgetPrefs` | 快照文件读写路径、页码 SP 多进程 |
| A12 | 预览图重新生成（直角 + 深色版） | `scripts/gen_widget_preview.py` | 2×2 方图、4×2/4×4 宽图 |
| A13 | 自测入口：debug 版「添加小部件」按钮 | `MainActivity.kt` | 用 `requestPinAppWidget` |
| A14 | 升级 compileSdk/targetSdk 到 35 或 36 | `build.gradle.kts` | 上架要求，也影响 WebView 行为需回归 |

---

## 6. 风险与未确认项

### 6.1 已识别风险
1. **曝光刷新的生效前提**：文档只说需配置标识 + 曝光刷新，未明说是否必须「已上线」。阶段 A 自测就是为了在真机上确认这一点——**这是整个方案的成败关键，必须先验证**。
2. **个人开发者能否上传小部件**：《小部件开放平台协议》明确发布者包括自然人，但《小部件提交审核与上传操作指南》提到「企业开发者账号注册流程」。存在个人主体不受理的可能 → 建议先注册个人开发者账号，若受阻再评估个体工商户/公司主体。
3. **上架后安卓原生入口消失**：改造后小米用户只能从小部件中心添加；若审核被驳回，回退成本需评估（可保留未加标识的旧版本 APK 作为兜底分发）。
4. **提测门槛**：静置功耗（4G + WiFi 双测）、内存测试（官方要求 Linux 环境）需要额外精力，文档说要邮件要「提测大礼包」。
5. **文档口径冲突**：圆角/预览图规范在《MIUI 小部件规范》(38px 圆角) 与《Xiaomi HyperOS 小部件设计规范》(直角 + 系统裁 55px) 不一致 → 以 HyperOS 版为准，提交前与官方确认。
6. **官方响应周期**：首次邮件 10 个工作日内联系，加上两次 1~3 工作日审核，整体节奏不可压缩。

### 6.2 对现有用户的影响
- 上架必须用**正式签名**，与当前 debug 签名不同 → 手机上已装的 debug 版**无法覆盖升级，会丢收藏**。需提前给一个「导出/备份收藏」的出口，或接受一次性数据丢失。

---

## 7. 需要你确认的第一件事

**这次的首要目标，是 A「先解决我自己手机上解锁即刷新」还是 B「正式上架小部件中心，让所有小米用户都能用」？**

- 选 A：我只做第 5 节的 A1~A14，出 debug 包自测，预计 1~2 天，零资质成本；曝光刷新若验证有效，痛点即解决。
- 选 B（或 A 验证通过后转 B）：再启动邮件报备 + 软著/备案 + 应用商店上架 + 小部件审核，周期 4~8 周。

我的建议是 **A → B 渐进**：先用最低成本验证曝光刷新这个核心假设，避免投入两个月资质流程后才发现机制不符合预期。

---

## 附录：官方文档索引

| 文档 | 链接 |
|---|---|
| 小部件技术规范与系统能力说明 | https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1584 |
| Xiaomi HyperOS 小部件设计规范 | https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1664 |
| 小部件审核规范 | https://dev.mi.com/distribute/doc/details?pId=1586 |
| 小部件提交审核与上传操作指南 | https://dev.mi.com/distribute/doc/details?pId=1588 |
| 小部件适配常见问题 Q&A | https://dev.mi.com/distribute/doc/details?pId=1591 |
| 小部件开放平台协议 | https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1589 |
| 应用资质 FAQ（软著/备案） | https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2251 |
| 应用和开发者分级管理制度 | https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1501 |
| 小部件开放平台 | https://widget.xiaomi.com/ |
| 负一屏（商业合作） | https://dev.mi.com/xiaomihyperos/appvault |
