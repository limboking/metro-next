# metro-next · 北京地铁时刻表

零依赖、单文件的北京地铁时刻表网页应用。查任意站点的「下一班」倒计时、全天班次、首末班，支持收藏与网页自助更新。

## 功能

- 28 条线路、423 个站点（含机场线、市郊线）
- 下一班到站倒计时（秒级刷新）
- 工作日 / 双休日两套班次（机场线含特殊日期组）
- 首末班 + 全天班次展开 + 当前时段高亮
- 收藏（全站 / 指定方向）
- 网页自助更新：检查数据源更新，镜像下载 + 前端解析，无需电脑端
- 手机返回键 / 手势返回逐级返回上一级

## 数据源

时刻数据来自 [Beijing-Subway-Tools](https://github.com/Mick235711/Beijing-Subway-Tools)（MIT 许可，源自北京地铁公开数据）。时刻为计划时间，实际以现场为准。

## 使用

直接浏览器打开 `beijing-metro.html`，或部署为静态站点。

## 本地构建

```bash
# 依赖：Python 3 + json5（pip install json5），pypinyin 可选（拼音搜索索引）
python scripts/build_timetable.py           # 用缓存数据仓库构建（无则自动 clone）
python scripts/build_timetable.py --refresh # 强制更新数据仓库后构建
node scripts/verify_consistency.js          # 一致性校验（Python/JS 双实现对比）
```

## 目录结构

- `scripts/build_timetable.py` — 数据构建脚本（解析数据源 json5 → 站点索引，delta 行程压缩）
- `scripts/app_template.html` — 前端模板（界面 + 前端 json5 解析器，自助更新）
- `scripts/verify_consistency.js` — 一致性测试（防止 Python/JS 双实现漂移）
- `beijing-metro.html` — 构建产物（单文件，可直接部署）

## 许可

MIT
