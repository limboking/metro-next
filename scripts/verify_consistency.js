#!/usr/bin/env node
/*
 * 一致性验证：前端 JS 解析器（parseJSON5/delta/索引构建）vs Python build_index 结果
 * 用途：防止 Python（build_timetable.py）与前端（app_template.html）双实现漂移
 * 用法：先运行 build_timetable.py 生成 beijing-metro.html，再 node scripts/verify_consistency.js
 */
const fs = require('fs');
const path = require('path');
const BASE = path.join(__dirname, '..');

// 1. 提取内置 DATA（Python 构建基线，delta 格式 fmt=2）
const html = fs.readFileSync(path.join(BASE, 'beijing-metro.html'), 'utf8');
const dm = html.match(/<script id="metro-data" type="application\/json">([\s\S]*?)<\/script>/);
if (!dm) { console.error('未找到内置数据'); process.exit(1); }
const BUILTIN = JSON.parse(dm[1]);
console.log('内置数据: fmt=%s lines=%d stations=%d bjSha=%s',
  BUILTIN.meta && BUILTIN.meta.fmt, Object.keys(BUILTIN.lines).length,
  Object.keys(BUILTIN.stations).length, (BUILTIN.meta && BUILTIN.meta.bjSha || '').slice(0, 8));

// 2. 提取并定义前端解析器代码（SRC_FILES 到 工具 之间）
const tpl = fs.readFileSync(path.join(BASE, 'scripts/app_template.html'), 'utf8');
const start = tpl.indexOf('var SRC_FILES');
const end = tpl.indexOf('/* ================= 工具 ================= */');
if (start < 0 || end < 0) { console.error('未定位到解析器代码段'); process.exit(1); }
const parserCode = tpl.slice(start, end);
var DATA = BUILTIN;   // buildDataFromSources 引用 DATA（复用内置拼音/meta）
var DATA_FORMAT = 2;  // 前端全局常量（buildDataFromSources meta.fmt 引用）
eval(parserCode);
console.log('解析器已加载: %d 个源文件, buildDataFromSources=%s, expandSchedule=%s',
  SRC_FILES.length, typeof buildDataFromSources, typeof expandSchedule);

// 3. 读 28 个真实 json5
const BEI = path.join(BASE, 'data/raw/bjst/data/beijing');
if (!fs.existsSync(BEI)) { console.error('数据仓库不存在: ' + BEI + '（先运行 build 脚本）'); process.exit(1); }
const texts = SRC_FILES.map(f => fs.readFileSync(path.join(BEI, f), 'utf8'));

// 4. JS 构建（delta 格式）
let jsData;
try {
  jsData = buildDataFromSources(texts, 'TEST_SHA', '2026-08-18');
} catch (e) {
  console.error('JS 构建失败:', e.message);
  process.exit(1);
}
console.log('JS 构建: lines=%d stations=%d fmt=%s',
  Object.keys(jsData.lines).length, Object.keys(jsData.stations).length, jsData.meta.fmt);

// 5. 对比 delta 原文（Python g vs JS g，逐站逐 rec 全字段）
const jsSt = jsData.stations, pySt = BUILTIN.stations;
let diffSt = 0, diffRec = 0, firstDiff = null;
for (const st of Object.keys(pySt)) {
  if (!jsSt[st]) { diffSt++; if (!firstDiff) firstDiff = '缺站: ' + st; continue; }
  if (jsSt[st].length !== pySt[st].length) { diffRec++; if (!firstDiff) firstDiff = '站[' + st + '] rec数: JS=' + jsSt[st].length + ' PY=' + pySt[st].length; continue; }
  for (let i = 0; i < pySt[st].length; i++) {
    const a = JSON.stringify(jsSt[st][i]), b = JSON.stringify(pySt[st][i]);
    if (a !== b) { diffRec++; if (!firstDiff) firstDiff = '站[' + st + '] rec[' + i + '] 不一致:\n  JS=' + a.slice(0, 200) + '\n  PY=' + b.slice(0, 200); }
  }
}
for (const st of Object.keys(jsSt)) {
  if (!pySt[st]) { diffSt++; if (!firstDiff) firstDiff = 'JS多余站: ' + st; }
}
console.log('delta 原文对比: lines 一致=%s | 差异站=%d 差异rec=%d',
  JSON.stringify(jsData.lines) === JSON.stringify(BUILTIN.lines), diffSt, diffRec);
if (firstDiff) console.log('  首个差异:', firstDiff);

// 6. 展开结果抽查（前端 expandSchedule 展开两边，对比时刻一致）
function expandG(d){
  const out = {};
  for (const st in d.stations){
    out[st] = d.stations[st].map(r => ({ k: r.l + '|' + r.d, t: (r.g || []).map(grp => expandSchedule(grp).join(',')) }));
  }
  return out;
}
const jsExp = expandG(jsData), pyExp = expandG(BUILTIN);
let expDiff = 0;
for (const st of Object.keys(pyExp)){
  if (JSON.stringify(jsExp[st]) !== JSON.stringify(pyExp[st])) { expDiff++; if (expDiff <= 3) console.log('  展开差异站: ' + st); }
}
console.log('展开结果抽查: 差异站=%d', expDiff);

const pass = diffSt === 0 && diffRec === 0 && expDiff === 0
  && JSON.stringify(jsData.lines) === JSON.stringify(BUILTIN.lines);
console.log('\n' + (pass ? '★★★ 一致性验证通过（Python/JS 完全一致）★★★' : '✗ 一致性验证失败'));
process.exit(pass ? 0 : 1);
