// Metro Next 图标选型渲染脚本
// 3 方向 x 3 配色 = 9 个变体，512px SVG -> PNG（sharp 渲染）
const sharp = require('sharp');
const fs = require('fs');
const path = require('path');

const OUT = 'D:/WorkBuddy_Save/手机小程序开发/.workbuddy/icon_preview';
fs.mkdirSync(OUT, { recursive: true });

// ---------- 背景渐变定义 ----------
const NAVY = ['#2E7BE6', '#1B4FA0'];       // 深蓝渐变
const RED  = ['#F0564F', '#C8102E'];        // 北京地铁红渐变
const INK  = ['#1C3352', '#0E1B2E'];        // 墨蓝渐变
const LIGHT= ['#F2F6FC', '#DCE7F5'];        // 浅底渐变

function bg(grad, id = 'bg') {
  return `<defs><linearGradient id="${id}" x1="0" y1="0" x2="0" y2="1">
    <stop offset="0" stop-color="${grad[0]}"/><stop offset="1" stop-color="${grad[1]}"/>
  </linearGradient></defs><rect x="8" y="8" width="496" height="496" rx="112" fill="url(#${id})"/>`;
}

// ---------- 方向一：车头 + 表盘指针 ----------
// opts: {grad, face(表盘色), tick(刻度色), hour(时针), minute(分针), glass(挡风), lamp(车灯)}
function trainClock(o) {
  const g = o.grad;
  return `<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 512 512">
${bg(g)}
<circle cx="326" cy="168" r="88" fill="${o.face}"/>
<circle cx="326" cy="168" r="74" fill="none" stroke="${o.tick}" stroke-width="5" opacity="0.55"/>
${[0, 90, 180, 270].map(a => `<line x1="326" y1="92" x2="326" y2="112" stroke="${o.tick}" stroke-width="9" stroke-linecap="round" transform="rotate(${a} 326 168)"/>`).join('')}
${[45, 135, 225, 315].map(a => `<line x1="326" y1="96" x2="326" y2="110" stroke="${o.tick}" stroke-width="5" stroke-linecap="round" opacity="0.7" transform="rotate(${a} 326 168)"/>`).join('')}
<line x1="326" y1="168" x2="326" y2="120" stroke="${o.hour}" stroke-width="11" stroke-linecap="round" transform="rotate(-52 326 168)"/>
<line x1="326" y1="168" x2="350" y2="144" stroke="${o.minute}" stroke-width="8" stroke-linecap="round" transform="rotate(62 326 168)"/>
<circle cx="326" cy="168" r="12" fill="${o.minute}"/>
<rect x="104" y="318" width="304" height="130" rx="44" fill="#FFFFFF"/>
<path d="M126 318 L386 318 L374 362 Q256 384 138 362 Z" fill="${o.glass}"/>
<rect x="142" y="398" width="30" height="16" rx="8" fill="${o.lamp}"/>
<rect x="340" y="398" width="30" height="16" rx="8" fill="${o.lamp}"/>
<line x1="128" y1="428" x2="384" y2="428" stroke="#E3E8F0" stroke-width="5" stroke-linecap="round"/>
<line x1="128" y1="438" x2="384" y2="438" stroke="#E3E8F0" stroke-width="5" stroke-linecap="round"/>
</svg>`;
}

// ---------- 方向二：表盘 + 轨道 ----------
// opts: {grad, face, ring(轨道环色), hour, minute, dial(表盘圈)}
function dialTrack(o) {
  return `<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 512 512">
${bg(o.grad)}
<circle cx="256" cy="256" r="206" fill="none" stroke="${o.ring}" stroke-width="10" stroke-linecap="round" stroke-dasharray="2 30"/>
<circle cx="256" cy="256" r="164" fill="${o.face}"/>
<circle cx="256" cy="256" r="140" fill="none" stroke="${o.dial}" stroke-width="5" opacity="0.5"/>
${[0, 90, 180, 270].map(a => `<line x1="256" y1="102" x2="256" y2="128" stroke="${o.dial}" stroke-width="10" stroke-linecap="round" transform="rotate(${a} 256 256)"/>`).join('')}
${[30, 60, 120, 150, 210, 240, 300, 330].map(a => `<line x1="256" y1="106" x2="256" y2="124" stroke="${o.dial}" stroke-width="5" stroke-linecap="round" opacity="0.65" transform="rotate(${a} 256 256)"/>`).join('')}
<line x1="256" y1="256" x2="256" y2="188" stroke="${o.hour}" stroke-width="15" stroke-linecap="round" transform="rotate(-52 256 256)"/>
<line x1="256" y1="256" x2="292" y2="222" stroke="${o.minute}" stroke-width="11" stroke-linecap="round" transform="rotate(62 256 256)"/>
<circle cx="256" cy="256" r="16" fill="${o.minute}"/>
<circle cx="256" cy="256" r="7" fill="${o.face}"/>
</svg>`;
}

// ---------- 方向三：字母 M 变形 ----------
// opts: {grad, m(字母M色), arrow(箭头色)}
function letterM(o) {
  return `<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 512 512">
${bg(o.grad)}
<polyline points="114,346 114,158 256,296 398,158 398,346" fill="none" stroke="${o.m}" stroke-width="60" stroke-linecap="round" stroke-linejoin="round"/>
<path d="M256 322 L256 376 M256 376 L230 350 M256 376 L282 350" fill="none" stroke="${o.arrow}" stroke-width="26" stroke-linecap="round" stroke-linejoin="round"/>
</svg>`;
}

const icons = [
  // 方向一：车头 + 指针
  { id: '1a', name: 'train-navy',   svg: trainClock({ grad: NAVY, face: '#FFFFFF', tick: '#B9D2F7', hour: '#1B4FA0', minute: '#C8102E', glass: '#16315C', lamp: '#F5B942' }) },
  { id: '1b', name: 'train-red',    svg: trainClock({ grad: RED,  face: '#FFFFFF', tick: '#F6C4C2', hour: '#C8102E', minute: '#8C0E20', glass: '#8C0E20', lamp: '#FFE9A8' }) },
  { id: '1c', name: 'train-ink',    svg: trainClock({ grad: INK,  face: '#FFFFFF', tick: '#8CA3C4', hour: '#0E1B2E', minute: '#F5B942', glass: '#0A1420', lamp: '#F5B942' }) },
  // 方向二：表盘 + 轨道
  { id: '2a', name: 'dial-navy',    svg: dialTrack({ grad: NAVY, face: '#FFFFFF', ring: '#FFFFFF', dial: '#1B4FA0', hour: '#1B4FA0', minute: '#C8102E' }) },
  { id: '2b', name: 'dial-light',   svg: dialTrack({ grad: LIGHT, face: '#16315C', ring: '#16315C', dial: '#FFFFFF', hour: '#FFFFFF', minute: '#C8102E' }) },
  { id: '2c', name: 'dial-red',     svg: dialTrack({ grad: RED,  face: '#FFFFFF', ring: '#FFFFFF', dial: '#C8102E', hour: '#C8102E', minute: '#8C0E20' }) },
  // 方向三：字母 M 变形
  { id: '3a', name: 'm-navy',       svg: letterM({ grad: NAVY, m: '#FFFFFF', arrow: '#F5B942' }) },
  { id: '3b', name: 'm-red',        svg: letterM({ grad: RED,  m: '#FFFFFF', arrow: '#FFE9A8' }) },
  { id: '3c', name: 'm-light',      svg: letterM({ grad: LIGHT, m: '#16315C', arrow: '#C8102E' }) },
];

(async () => {
  for (const ic of icons) {
    const p = path.join(OUT, `${ic.id}-${ic.name}.png`);
    await sharp(Buffer.from(ic.svg)).resize(512, 512).png().toFile(p);
    console.log('rendered', p);
  }
  console.log('DONE', icons.length);
})();
