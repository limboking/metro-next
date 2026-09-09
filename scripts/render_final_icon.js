// Metro Next 定稿图标（E4 v3）源渲染：完整图 + 透明前景图
const sharp = require('sharp');
const fs = require('fs');
const path = require('path');

const OUT = 'D:/WorkBuddy_Save/手机小程序开发/.workbuddy/icon_preview/final';
fs.mkdirSync(OUT, { recursive: true });

const NAVY = ['#1C3559', '#0C1626'];
const ORANGE = '#F5A623';
const DARKBLUE = '#16315C';

const GRAPHIC = `
<circle cx="222" cy="178" r="9" fill="${DARKBLUE}"/>
<circle cx="290" cy="178" r="9" fill="${DARKBLUE}"/>
<rect x="156" y="188" width="200" height="208" rx="40" fill="#FFFFFF"/>
<rect x="222" y="202" width="68" height="11" rx="5.5" fill="${DARKBLUE}"/>
<rect x="182" y="230" width="148" height="76" rx="16" fill="${DARKBLUE}"/>
<circle cx="196" cy="352" r="13" fill="${ORANGE}"/>
<circle cx="316" cy="352" r="13" fill="${ORANGE}"/>
<line x1="140" y1="126" x2="372" y2="126" stroke="#FFFFFF" stroke-width="6" opacity="0.6" stroke-linecap="round"/>
<circle cx="152" cy="126" r="12" fill="#FFFFFF"/>
<circle cx="218" cy="126" r="12" fill="#FFFFFF"/>
<circle cx="284" cy="126" r="14" fill="${ORANGE}"/>
<circle cx="350" cy="126" r="12" fill="#FFFFFF"/>`;

const FULL = `<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024" viewBox="0 0 1024 1024">
<defs><linearGradient id="bg" x1="0" y1="0" x2="0" y2="1">
<stop offset="0" stop-color="#1C3559"/><stop offset="1" stop-color="#0C1626"/>
</linearGradient></defs>
<rect x="16" y="16" width="992" height="992" rx="224" fill="url(#bg)"/>
<g transform="translate(26, 29) scale(1.9)">${GRAPHIC}</g>
</svg>`;

// 透明前景（图形居中，四周留白，用于自适应图标）
const FG = `<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024" viewBox="0 0 1024 1024">
<g transform="translate(26, 29) scale(1.9)">${GRAPHIC}</g>
</svg>`;

(async () => {
  await sharp(Buffer.from(FULL)).resize(1024, 1024).png().toFile(path.join(OUT, 'full_1024.png'));
  await sharp(Buffer.from(FG)).resize(1024, 1024).png().toFile(path.join(OUT, 'fg_1024.png'));
  console.log('rendered full_1024.png / fg_1024.png');
})();
