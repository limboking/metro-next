// Metro Next 图标 D 系列：地铁+时间 融合设计（矢量扁平）
// 8 个候选，512px SVG -> PNG（sharp 渲染）
const sharp = require('sharp');
const fs = require('fs');
const path = require('path');

const OUT = 'D:/WorkBuddy_Save/手机小程序开发/.workbuddy/icon_preview/ai3';
fs.mkdirSync(OUT, { recursive: true });

const NAVY = ['#1C3559', '#0C1626'];
const BLUE = ['#2E7BE6', '#1B62D9'];
const INK  = ['#131F33', '#0A101C'];
const ORANGE = '#F5A623';
const DARKBLUE = '#16315C';   // 白底上的深蓝元素

function bg(grad, id = 'bg') {
  return `<defs><linearGradient id="${id}" x1="0" y1="0" x2="0" y2="1">
    <stop offset="0" stop-color="${grad[0]}"/><stop offset="1" stop-color="${grad[1]}"/>
  </linearGradient></defs><rect x="8" y="8" width="496" height="496" rx="112" fill="url(#${id})"/>`;
}

// 表盘刻度：12 条线（4 主 8 次），a=0 是 12 点方向
function ticks(cx, cy, r1, r2, color, w) {
  let s = '';
  for (let a = 0; a < 360; a += 30) {
    const main = a % 90 === 0;
    const rr1 = main ? r1 : r1 + (r2 - r1) * 0.55;
    s += `<line x1="${cx}" y1="${cy - rr1}" x2="${cx}" y2="${cy - r2}" stroke="${color}" stroke-width="${main ? w : w * 0.62}" stroke-linecap="round" transform="rotate(${a} ${cx} ${cy})"/>`;
  }
  return s;
}

// 指针 10:10
function hands(cx, cy, hourLen, minLen, color, w, centerR, centerColor) {
  return `<line x1="${cx}" y1="${cy}" x2="${cx}" y2="${cy - hourLen}" stroke="${color}" stroke-width="${w}" stroke-linecap="round" transform="rotate(-52 ${cx} ${cy})"/>
<line x1="${cx}" y1="${cy}" x2="${cx}" y2="${cy - minLen}" stroke="${color}" stroke-width="${w * 0.72}" stroke-linecap="round" transform="rotate(62 ${cx} ${cy})"/>
<circle cx="${cx}" cy="${cy}" r="${centerR}" fill="${centerColor}"/>`;
}

function wrap(svg, grad) {
  return `<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 512 512">${bg(grad)}${svg}</svg>`;
}

const icons = [];

// ---------- D1 MetroClock：侧视列车，车窗=表盘 ----------
icons.push({ id: 'd1-metroclock', svg: wrap(`
<rect x="116" y="196" width="280" height="120" rx="60" fill="#FFFFFF"/>
<circle cx="256" cy="256" r="43" fill="none" stroke="${DARKBLUE}" stroke-width="5"/>
${ticks(256, 256, 30, 38, DARKBLUE, 4)}
${hands(256, 256, 22, 32, DARKBLUE, 6, 5, DARKBLUE)}
<circle cx="196" cy="300" r="12" fill="${DARKBLUE}"/>
<circle cx="316" cy="300" r="12" fill="${DARKBLUE}"/>
<circle cx="256" cy="222" r="8" fill="${ORANGE}"/>
`, NAVY) });

// ---------- D2 TrackClock：表盘夹在上下轨道之间 ----------
icons.push({ id: 'd2-trackclock', svg: wrap(`
<rect x="80" y="106" width="352" height="26" rx="13" fill="#FFFFFF"/>
<rect x="80" y="380" width="352" height="26" rx="13" fill="#FFFFFF"/>
<circle cx="256" cy="256" r="84" fill="#FFFFFF"/>
${ticks(256, 256, 62, 80, DARKBLUE, 5)}
${hands(256, 256, 38, 56, DARKBLUE, 8, 8, DARKBLUE)}
<circle cx="410" cy="119" r="12" fill="${ORANGE}"/>
`, BLUE) });

// ---------- D3 LoopDial：白色刻度环（地铁环线），橙列车点 ----------
icons.push({ id: 'd3-loopdial', svg: wrap(`
<path d="M256 74 A182 182 0 1 1 255 74 Z M256 132 A124 124 0 1 0 257 132 Z" fill="#FFFFFF" fill-rule="evenodd"/>
${ticks(256, 256, 136, 168, DARKBLUE, 6)}
${hands(256, 256, 48, 74, '#FFFFFF', 10, 12, ORANGE)}
<circle cx="346" cy="176" r="24" fill="${ORANGE}"/>
`, NAVY) });

// ---------- D4 MClock：白色 M + 头顶表盘 ----------
icons.push({ id: 'd4-mclock', svg: wrap(`
<path d="M110 362 L110 188 M110 188 L256 302 M256 302 L402 188 M402 188 L402 362" stroke="#FFFFFF" stroke-width="66" stroke-linecap="round" stroke-linejoin="round" fill="none"/>
<circle cx="256" cy="122" r="42" fill="#FFFFFF"/>
${ticks(256, 122, 28, 38, '#0A101C', 4)}
${hands(256, 122, 20, 30, '#0A101C', 5, 5, '#0A101C')}
`, INK) });

// ---------- D5 NextPoints：列车 + 时刻点排（橙点=下一班） ----------
icons.push({ id: 'd5-nextpoints', svg: wrap(`
<line x1="140" y1="212" x2="372" y2="212" stroke="#FFFFFF" stroke-width="6" opacity="0.6" stroke-linecap="round"/>
<circle cx="152" cy="212" r="12" fill="#FFFFFF"/>
<circle cx="218" cy="212" r="12" fill="#FFFFFF"/>
<circle cx="284" cy="212" r="14" fill="${ORANGE}"/>
<circle cx="350" cy="212" r="12" fill="#FFFFFF"/>
<rect x="128" y="284" width="256" height="100" rx="50" fill="#FFFFFF"/>
<circle cx="342" cy="324" r="10" fill="${DARKBLUE}"/>
`, NAVY) });

// ---------- D6 StationDial：站台上的时钟 ----------
icons.push({ id: 'd6-stationdial', svg: wrap(`
<circle cx="256" cy="214" r="82" fill="#FFFFFF"/>
${ticks(256, 214, 60, 78, DARKBLUE, 5)}
${hands(256, 214, 38, 56, DARKBLUE, 8, 8, DARKBLUE)}
<rect x="108" y="330" width="296" height="28" rx="14" fill="#FFFFFF"/>
<circle cx="122" cy="344" r="10" fill="${ORANGE}"/>
<circle cx="390" cy="344" r="10" fill="${ORANGE}"/>
`, BLUE) });

// ---------- D7 FrontClock：正视车头 + 胸前表盘 + 车灯 ----------
icons.push({ id: 'd7-frontclock', svg: wrap(`
<rect x="131" y="216" width="250" height="136" rx="50" fill="#FFFFFF"/>
<circle cx="256" cy="264" r="42" fill="none" stroke="${DARKBLUE}" stroke-width="5"/>
${ticks(256, 264, 30, 38, DARKBLUE, 4)}
${hands(256, 264, 22, 32, DARKBLUE, 6, 6, ORANGE)}
<circle cx="178" cy="322" r="11" fill="${ORANGE}"/>
<circle cx="334" cy="322" r="11" fill="${ORANGE}"/>
`, NAVY) });

// ---------- D8 HandTrack：轨道 + 时针指针 + 橙点（极简抽象） ----------
icons.push({ id: 'd8-handtrack', svg: wrap(`
<rect x="92" y="356" width="328" height="28" rx="14" fill="#FFFFFF"/>
<line x1="256" y1="356" x2="206" y2="216" stroke="#FFFFFF" stroke-width="34" stroke-linecap="round"/>
<circle cx="206" cy="216" r="17" fill="${ORANGE}"/>
`, INK) });

(async () => {
  for (const ic of icons) {
    const p = path.join(OUT, `${ic.id}.png`);
    await sharp(Buffer.from(ic.svg)).resize(512, 512).png().toFile(p);
    console.log('rendered', ic.id);
  }
  console.log('DONE', icons.length);
})();
