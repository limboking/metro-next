// Metro Next E 系列：D5 下部图形变体（保留上方时刻点序列）
// 5 个候选：时钟 / 侧视列车 / 铁轨枕木 / 正视车头 / 环线轨道
const sharp = require('sharp');
const fs = require('fs');
const path = require('path');

const OUT = 'D:/WorkBuddy_Save/手机小程序开发/.workbuddy/icon_preview/ai3';
fs.mkdirSync(OUT, { recursive: true });

const NAVY = ['#1C3559', '#0C1626'];
const ORANGE = '#F5A623';
const DARKBLUE = '#16315C';

function bg(grad, id = 'bg') {
  return `<defs><linearGradient id="${id}" x1="0" y1="0" x2="0" y2="1">
    <stop offset="0" stop-color="${grad[0]}"/><stop offset="1" stop-color="${grad[1]}"/>
  </linearGradient></defs><rect x="8" y="8" width="496" height="496" rx="112" fill="url(#${id})"/>`;
}

function ticks(cx, cy, r1, r2, color, w) {
  let s = '';
  for (let a = 0; a < 360; a += 30) {
    const main = a % 90 === 0;
    const rr1 = main ? r1 : r1 + (r2 - r1) * 0.55;
    s += `<line x1="${cx}" y1="${cy - rr1}" x2="${cx}" y2="${cy - r2}" stroke="${color}" stroke-width="${main ? w : w * 0.62}" stroke-linecap="round" transform="rotate(${a} ${cx} ${cy})"/>`;
  }
  return s;
}

function hands(cx, cy, hourLen, minLen, color, w, centerR, centerColor) {
  return `<line x1="${cx}" y1="${cy}" x2="${cx}" y2="${cy - hourLen}" stroke="${color}" stroke-width="${w}" stroke-linecap="round" transform="rotate(-52 ${cx} ${cy})"/>
<line x1="${cx}" y1="${cy}" x2="${cx}" y2="${cy - minLen}" stroke="${color}" stroke-width="${w * 0.72}" stroke-linecap="round" transform="rotate(62 ${cx} ${cy})"/>
<circle cx="${cx}" cy="${cy}" r="${centerR}" fill="${centerColor}"/>`;
}

function wrap(svg, grad) {
  return `<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 512 512">${bg(grad)}${svg}</svg>`;
}

// 上方时刻点序列（与 D5 一致，保留）
const POINTS = `
<line x1="140" y1="212" x2="372" y2="212" stroke="#FFFFFF" stroke-width="6" opacity="0.6" stroke-linecap="round"/>
<circle cx="152" cy="212" r="12" fill="#FFFFFF"/>
<circle cx="218" cy="212" r="12" fill="#FFFFFF"/>
<circle cx="284" cy="212" r="14" fill="${ORANGE}"/>
<circle cx="350" cy="212" r="12" fill="#FFFFFF"/>`;

// 放大版时刻点（用于 E4 整体放大版）
const POINTS_BIG = `
<line x1="128" y1="156" x2="384" y2="156" stroke="#FFFFFF" stroke-width="8" opacity="0.6" stroke-linecap="round"/>
<circle cx="144" cy="156" r="17" fill="#FFFFFF"/>
<circle cx="208" cy="156" r="17" fill="#FFFFFF"/>
<circle cx="272" cy="156" r="20" fill="${ORANGE}"/>
<circle cx="336" cy="156" r="17" fill="#FFFFFF"/>`;

// E4 版时刻点：恢复均匀排布（与原 POINTS 一致），仅整体上移居中
const POINTS_E4 = `
<line x1="140" y1="126" x2="372" y2="126" stroke="#FFFFFF" stroke-width="6" opacity="0.6" stroke-linecap="round"/>
<circle cx="152" cy="126" r="12" fill="#FFFFFF"/>
<circle cx="218" cy="126" r="12" fill="#FFFFFF"/>
<circle cx="284" cy="126" r="14" fill="${ORANGE}"/>
<circle cx="350" cy="126" r="12" fill="#FFFFFF"/>`;

const icons = [];

// E1 下部 = 时钟
icons.push({ id: 'e1-clock', svg: wrap(`${POINTS}
<circle cx="256" cy="332" r="62" fill="#FFFFFF"/>
${ticks(256, 332, 44, 56, DARKBLUE, 5)}
${hands(256, 332, 26, 40, DARKBLUE, 7, 7, ORANGE)}
`, NAVY) });

// E2 下部 = 侧视列车（车窗+车轮+橙灯）
icons.push({ id: 'e2-train', svg: wrap(`${POINTS}
<rect x="124" y="296" width="264" height="88" rx="44" fill="#FFFFFF"/>
<rect x="156" y="318" width="146" height="34" rx="17" fill="${DARKBLUE}"/>
<circle cx="198" cy="370" r="12" fill="${DARKBLUE}"/>
<circle cx="318" cy="370" r="12" fill="${DARKBLUE}"/>
<circle cx="378" cy="318" r="9" fill="${ORANGE}"/>
`, NAVY) });

// E3 下部 = 铁轨枕木 + 橙端头
icons.push({ id: 'e3-track', svg: wrap(`${POINTS}
<rect x="120" y="322" width="272" height="26" rx="13" fill="#FFFFFF"/>
<line x1="164" y1="322" x2="164" y2="348" stroke="${DARKBLUE}" stroke-width="7"/>
<line x1="204" y1="322" x2="204" y2="348" stroke="${DARKBLUE}" stroke-width="7"/>
<line x1="244" y1="322" x2="244" y2="348" stroke="${DARKBLUE}" stroke-width="7"/>
<line x1="284" y1="322" x2="284" y2="348" stroke="${DARKBLUE}" stroke-width="7"/>
<line x1="324" y1="322" x2="324" y2="348" stroke="${DARKBLUE}" stroke-width="7"/>
<circle cx="140" cy="335" r="11" fill="${ORANGE}"/>
<circle cx="372" cy="335" r="11" fill="${ORANGE}"/>
`, NAVY) });

// E4 下部 = 正视车头（参照参考图：双触角+大窗+双车灯；上移居中、无轨道、略缩小）
icons.push({ id: 'e4-front', svg: wrap(`${POINTS_E4}
<circle cx="222" cy="178" r="9" fill="${DARKBLUE}"/>
<circle cx="290" cy="178" r="9" fill="${DARKBLUE}"/>
<rect x="156" y="188" width="200" height="208" rx="40" fill="#FFFFFF"/>
<rect x="222" y="202" width="68" height="11" rx="5.5" fill="${DARKBLUE}"/>
<rect x="182" y="230" width="148" height="76" rx="16" fill="${DARKBLUE}"/>
<circle cx="196" cy="352" r="13" fill="${ORANGE}"/>
<circle cx="316" cy="352" r="13" fill="${ORANGE}"/>
`, NAVY) });

// E5 下部 = 环线轨道 + 橙列车点
icons.push({ id: 'e5-loop', svg: wrap(`${POINTS}
<path d="M256 272 A58 58 0 1 1 255 272 Z M256 294 A36 36 0 1 0 257 294 Z" fill="#FFFFFF" fill-rule="evenodd"/>
<circle cx="300" cy="298" r="13" fill="${ORANGE}"/>
`, NAVY) });

(async () => {
  for (const ic of icons) {
    const p = path.join(OUT, `${ic.id}.png`);
    await sharp(Buffer.from(ic.svg)).resize(512, 512).png().toFile(p);
    console.log('rendered', ic.id);
  }
  console.log('DONE', icons.length);
})();
