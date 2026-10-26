/* ───────────────────────── app icons ─────────────────────────
   One generator for every icon: the browser tab, the previews in Settings and the
   Android launcher (build-time PNGs). Drawn on a 108-unit adaptive-icon canvas: the
   background fills it, the foreground stays inside the central 66-unit safe zone,
   and the "full" icon is the central 72 units with rounded corners. */
const ACCENTS = [
  {id: 'cobalt', name: 'Cobalt', hex: '#2f6bff'},
  {id: 'pine', name: 'Pine', hex: '#1f7a5c'},
  {id: 'teal', name: 'Teal', hex: '#13808f'},
  {id: 'indigo', name: 'Indigo', hex: '#4c5bd4'},
  {id: 'iris', name: 'Iris', hex: '#7353cf'},
  {id: 'plum', name: 'Plum', hex: '#9a46b8'},
  {id: 'rose', name: 'Rose', hex: '#c4325f'},
  {id: 'coral', name: 'Coral', hex: '#d9534a'},
  {id: 'tangerine', name: 'Tangerine', hex: '#d96a0b'},
  {id: 'moss', name: 'Moss', hex: '#5f7d1e'},
  {id: 'graphite', name: 'Graphite', hex: '#4a535c'},
];
const ICON_SKINS = ['archive', 'riot', 'tide', 'frontier', 'neon'];
const LAUNCHER_ICONS = ACCENTS.map(a => 'bside_' + a.id).concat(ICON_SKINS);

const cHex = h => { const m = /^#?([0-9a-f]{6})$/i.exec(String(h || '').trim()); if (!m) return null; const v = m[1]; return [0, 2, 4].map(i => parseInt(v.slice(i, i + 2), 16)); };
const cStr = c => '#' + c.map(v => Math.round(Math.max(0, Math.min(255, v))).toString(16).padStart(2, '0')).join('');
function cHsl([r, g, b]) {
  r /= 255; g /= 255; b /= 255;
  const mx = Math.max(r, g, b), mn = Math.min(r, g, b), l = (mx + mn) / 2; let h = 0, s = 0;
  if (mx !== mn) { const d = mx - mn; s = l > .5 ? d / (2 - mx - mn) : d / (mx + mn); h = mx === r ? (g - b) / d + (g < b ? 6 : 0) : mx === g ? (b - r) / d + 2 : (r - g) / d + 4; h /= 6; }
  return [h, s, l];
}
function cRgb([h, s, l]) {
  if (!s) return [l * 255, l * 255, l * 255];
  const q = l < .5 ? l * (1 + s) : l + s - l * s, p = 2 * l - q;
  const f = t => { if (t < 0) t += 1; if (t > 1) t -= 1; return t < 1 / 6 ? p + (q - p) * 6 * t : t < 1 / 2 ? q : t < 2 / 3 ? p + (q - p) * (2 / 3 - t) * 6 : p; };
  return [f(h + 1 / 3) * 255, f(h) * 255, f(h - 1 / 3) * 255];
}
const cLum = c => { const v = c.map(x => { x /= 255; return x <= .03928 ? x / 12.92 : Math.pow((x + .055) / 1.055, 2.4); }); return .2126 * v[0] + .7152 * v[1] + .0722 * v[2]; };
const cContrast = (a, b) => { const x = cLum(a), y = cLum(b); return (Math.max(x, y) + .05) / (Math.min(x, y) + .05); };
const cMix = (a, b, t) => a.map((v, i) => v + (b[i] - v) * t);
function cToward(c, bg, target, dir) { const hsl = cHsl(c); let out = c; for (let i = 0; i < 100 && cContrast(out, bg) < target; i++) { hsl[2] = Math.max(0, Math.min(1, hsl[2] + dir * .01)); out = cRgb(hsl); } return out; }
function nearestAccent(hex) {
  const c = cHex(hex) || cHex(ACCENTS[0].hex);
  let best = ACCENTS[0], bd = Infinity;
  for (const a of ACCENTS) { const d = cHex(a.hex), r = (c[0] + d[0]) / 2; const dist = (2 + r / 256) * (c[0] - d[0]) ** 2 + 4 * (c[1] - d[1]) ** 2 + (2 + (255 - r) / 256) * (c[2] - d[2]) ** 2; if (dist < bd) { bd = dist; best = a; } }
  return best;
}
/* The three-stop gradient used behind the note in the B-Side style. */
function iconGradient(hex) {
  if (String(hex).toLowerCase() === ACCENTS[0].hex) return ['#6e8dff', '#3d5cf5', '#7a4cf2'];
  const [h, s, l] = cHsl(cHex(hex) || cHex(ACCENTS[0].hex)), ls = Math.min(1, s * 1.05);
  const mid = Math.max(.3, Math.min(.52, l));
  return [cStr(cRgb([h, ls, Math.min(.74, mid + .16)])), cStr(cRgb([h, ls, mid])), cStr(cRgb([(h + .075) % 1, ls, Math.max(.24, mid - .04)]))];
}

let iconSeq = 0;
/* Game-style icons follow the theme guide, drawn on a 64-unit grid with B-Side's note as the mark:
   NieR: Automata, the picked menu plate with its square marker between thin rules, on the grid;
   Persona 5, a white-edged red star with a white note, on black; Persona 3, deep water with the
   white ribbon, the red and white cursor under a black italic note, and the moon; Red Dead
   Redemption 2, the rough red brush frame round an off-white ink note, on black ink; Cyberpunk 2077,
   a yellow note splitting into cyan and red, on a black plate with cut corners. */
function icBurst(cx, cy, outer, inner, points, rot) {
  const pts = [];
  for (let i = 0; i < points * 2; i++) { const r = i % 2 ? inner : outer, a = Math.PI * i / points - Math.PI / 2 + (rot || 0) * Math.PI / 180; pts.push((cx + r * Math.cos(a)).toFixed(1) + ',' + (cy + r * Math.sin(a)).toFixed(1)); }
  return pts.join(' ');
}
function icRng(seed) { let s = seed >>> 0; return () => (s = (s * 1664525 + 1013904223) >>> 0) / 4294967296; }
function icRough(x, y, w, h, amp, seed, step) {
  step = step || 3; const r = icRng(seed), j = () => (r() * 2 - 1) * amp, pts = [];
  for (let t = 0; t <= w; t += step) pts.push([x + t, y + j()]);
  for (let t = 0; t <= h; t += step) pts.push([x + w + j(), y + t]);
  for (let t = w; t >= 0; t -= step) pts.push([x + t, y + h + j()]);
  for (let t = h; t >= 0; t -= step) pts.push([x + j(), y + t]);
  return 'M' + pts.map(p => p[0].toFixed(1) + ' ' + p[1].toFixed(1)).join('L') + 'Z';
}
/* the B-Side note (24-unit art) placed at cx,cy, scale k, filled with colour c; edge adds an outline */
function icNote(cx, cy, k, c, extra, edge) {
  const body = (col, sw, r) => `<path d="M9 17.5V6.2l10-2.2v11.3" fill="none" stroke="${col}" stroke-width="${sw}" stroke-linejoin="round" stroke-linecap="round"/><circle cx="6.5" cy="17.5" r="${r}" fill="${col}"/><circle cx="16.5" cy="15.3" r="${r}" fill="${col}"/>`;
  return `<g transform="translate(${cx} ${cy}) ${extra || ''} scale(${k}) translate(-11.5 -11.6)">${edge ? body(edge, 2.3 + 2.6, 2.9 + 1.3) : ''}${body(c, 2.3, 2.9)}</g>`;
}
function gameIcon(kind, u) {
  switch (kind) {
    case 'archive': return {
      bg: shape => `<rect width="64" height="64" rx="${shape === 'rounded' ? 6 : 0}" fill="#d1cdb7"/><path d="${Array.from({length: 15}, (_, i) => `M${4 + i * 4} 0V64M0 ${4 + i * 4}H64`).join('')}" stroke="#c4bfa6" stroke-width=".4"/>`,
      fg: '<rect x="9" y="12.6" width="36" height="1.2" fill="#57544a"/><rect x="49" y="11.5" width="3.4" height="3.4" fill="#57544a"/>' +
        '<rect x="9" y="18" width="46" height="1.6" fill="#57544a"/><rect x="9" y="44.4" width="46" height="1.6" fill="#57544a"/>' +
        '<rect x="9" y="22.5" width="46" height="19" fill="#57544a"/><rect x="5" y="25.5" width="2.2" height="13" fill="#57544a"/>' +
        '<rect x="14" y="27.5" width="9" height="9" fill="#dad4bb"/>' + icNote(39.5, 32, .78, '#dad4bb'),
    };
    case 'riot': return {
      bg: shape => `<rect width="64" height="64" rx="${shape === 'rounded' ? 6 : 0}" fill="#0b0a0a"/>`,
      fg: `<polygon points="${icBurst(32, 34, 29, 13.6, 5, -6)}" fill="#ffffff"/><polygon points="${icBurst(32, 34, 25, 11.6, 5, -6)}" fill="#e5191c"/>` + icNote(31.5, 33.5, 1.22, '#ffffff', 'rotate(-8)', '#0b0a0a'),
    };
    case 'tide': return {
      bg: shape => `<defs><linearGradient id="${u}g" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#5ff0ff"/><stop offset=".32" stop-color="#1a8ff0"/><stop offset=".72" stop-color="#1438c4"/><stop offset="1" stop-color="#0b1c82"/></linearGradient><clipPath id="${u}c"><rect width="64" height="64" rx="${shape === 'rounded' ? 14 : 0}"/></clipPath></defs>` +
        `<g clip-path="url(#${u}c)"><rect width="64" height="64" fill="url(#${u}g)"/><path d="M0 6c6-3 10 2 16 0s9-4 15-1 10 2 15 0 12-2 18 1v5c-6-2-11 1-17 0s-9-3-15-1-10 3-16 1-10-2-16 1z" fill="#ffffff" opacity=".22"/>` +
        `<g${shape === 'rounded' ? '' : ' transform="translate(11 0)"'}><path d="M-2 0h11L1 64h-5z" fill="#ffffff"/><path d="M11 0h2.2L5.2 64H3z" fill="#ff1f3d"/></g></g>`,
      fg: '<path d="M49.5 6.5a6 6 0 1 0 6 6a4.8 4.8 0 0 1-6-6z" fill="#effbff"/>' +
        '<polygon points="8,50 60,26 49,41" fill="#ffffff" transform="translate(2.5 -2.5) rotate(-3 34 38)"/><polygon points="8,50 60,26 49,41" fill="#ff1f3d"/>' +
        icNote(34, 30, 1.2, '#050816', 'skewX(-14)'),
    };
    case 'frontier': return {
      bg: shape => { const r = icRng(11), streaks = Array.from({length: 14}, (_, i) => `<rect x="${(r() * 10).toFixed(1)}" y="${(2 + i * 4.4 + r() * 1.5).toFixed(1)}" width="${(30 + r() * 34).toFixed(1)}" height="${(.5 + r() * 1.2).toFixed(1)}" opacity="${(.04 + r() * .07).toFixed(2)}"/>`).join(''); return `<clipPath id="${u}c"><rect width="64" height="64" rx="${shape === 'rounded' ? 10 : 0}"/></clipPath><g clip-path="url(#${u}c)"><rect width="64" height="64" fill="#0d0b09"/><g fill="#ffffff">${streaks}</g></g>`; },
      fg: `<path d="${icRough(9, 13, 46, 38, 1.4, 5)}${icRough(14.5, 18.5, 35, 27, 1.2, 9)}" fill="#cc0000" fill-rule="evenodd"/>` +
        '<path d="M10 16.2h18M33 15.4h20M12 48.6h25M40 47.8h13M11.2 30v6M52.6 27v9" stroke="#0d0b09" stroke-width=".7" opacity=".8"/>' +
        icNote(32, 32, 1.02, '#f2ead8', 'rotate(-4)') + '<path d="M26 24.5l9-2" stroke="#0d0b09" stroke-width=".5" opacity=".35"/>',
    };
    case 'neon': return {
      bg: shape => shape === 'rounded' ? '<path d="M0 0H50L64 14V64H14L0 50Z" fill="#07080e"/>' : '<rect width="64" height="64" fill="#07080e"/>',
      fg: icNote(29.5, 32, 1.3, '#00f0ff') + icNote(34.5, 32, 1.3, '#ff2a4d') + icNote(32, 32, 1.3, '#fcee0a') +
        '<path d="M22 28.5h6M36 23h6" stroke="#07080e" stroke-width="1.6"/><path d="M9 53h17" stroke="#fcee0a" stroke-width="3"/><path d="M44 9h8" stroke="#ff2a4d" stroke-width="3"/>',
    };
  }
  return null;
}
function iconSVG(kind, hex, layer, size) {
  layer = layer || 'full'; size = size || 64;
  const u = 'ci' + (++iconSeq) + '_';
  const g64 = gameIcon(kind, u);
  if (g64) {
    if (layer === 'bg') return `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" viewBox="0 0 64 64">${g64.bg('full')}</svg>`;
    if (layer === 'fg') return `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" viewBox="-20 -20 104 104">${g64.fg}</svg>`;
    return `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" viewBox="0 0 64 64">${g64.bg('rounded')}${g64.fg}</svg>`;
  }
  const note = (c, tf) => `<g transform="translate(54 54) ${tf || ''} scale(2.1) translate(-11.5 -11.6)"><path d="M9 17.5V6.2l10-2.2v11.3" fill="none" stroke="${c}" stroke-width="2.3" stroke-linejoin="round" stroke-linecap="round"/><circle cx="6.5" cy="17.5" r="2.9" fill="${c}"/><circle cx="16.5" cy="15.3" r="2.9" fill="${c}"/></g>`;
  const g = iconGradient(hex || ACCENTS[0].hex);
  const defs = `<linearGradient id="${u}g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="${g[0]}"/><stop offset=".55" stop-color="${g[1]}"/><stop offset="1" stop-color="${g[2]}"/></linearGradient><radialGradient id="${u}h" cx=".3" cy=".22" r=".6"><stop offset="0" stop-color="#fff" stop-opacity=".22"/><stop offset="1" stop-color="#fff" stop-opacity="0"/></radialGradient>`;
  const bg = `<rect width="108" height="108" fill="url(#${u}g)"/><rect width="108" height="108" fill="url(#${u}h)"/>`;
  const fg = `${note('#000', 'translate(0 1.6)').replace('<g ', '<g opacity=".14" ')}${note('#fff')}`;
  if (layer === 'bg') return `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" viewBox="0 0 108 108"><defs>${defs}</defs>${bg}</svg>`;
  if (layer === 'fg') return `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" viewBox="0 0 108 108"><defs>${defs}</defs>${fg}</svg>`;
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" viewBox="18 18 72 72"><defs>${defs}<clipPath id="${u}c"><rect x="18" y="18" width="72" height="72" rx="17"/></clipPath></defs><g clip-path="url(#${u}c)">${bg}${fg}</g></svg>`;
}
const iconURL = (kind, hex, layer, size) => 'data:image/svg+xml,' + encodeURIComponent(iconSVG(kind, hex, layer, size));
