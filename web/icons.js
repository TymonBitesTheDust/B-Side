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
/* Game-style icons: a tribute to each game, drawn on a 64-unit grid, with Checkmate's icons as a starting point only.
   NieR: Automata, a record in YoRHa ink under a lock-on reticle, on the beige grid;
   Persona 5, a big white note slashed across a red and black split, with halftone dots, stars and shards;
   Persona 3 Reload, an original pocket music player with earbuds, the moon on its screen, under water;
   Red Dead Redemption 2, a brass lawman's star in the rough red brush frame, on black ink;
   Cyberpunk 2077, a note glitching into cyan and red on a cut-corner black plate, on street-poster yellow.
   Each returns its full-icon outline (shape), the background (bg) and the foreground (fg). */
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
function icPts(a) { return a.map(p => p[0].toFixed(1) + ',' + p[1].toFixed(1)).join(' '); }
function icStar(cx, cy, n, ro, ri, rot) { const p = []; for (let i = 0; i < n * 2; i++) { const r = i % 2 ? ri : ro, a = Math.PI * i / n - Math.PI / 2 + (rot || 0); p.push([cx + r * Math.cos(a), cy + r * Math.sin(a)]); } return icPts(p); }
/* halftone dots that shrink away from cx,cy */
function icHalftone(cx, cy, step, rmax, reach, fill, op) { let s = '', row = 0; for (let y = -20; y <= 84; y += step, row++) for (let x = -20 + (row % 2 ? step / 2 : 0); x <= 84; x += step) { const r = rmax * (1 - Math.hypot(x - cx, y - cy) / reach); if (r > .18) s += `<circle cx="${x.toFixed(1)}" cy="${y.toFixed(1)}" r="${r.toFixed(2)}"/>`; } return `<g fill="${fill}" opacity="${op}">${s}</g>`; }
/* Persona 3 Reload: the ocean, its light rays, the bright surface and a few bubbles */
const icOcean = u => `<defs><linearGradient id="${u}o" gradientUnits="userSpaceOnUse" x1="0" y1="-4" x2="0" y2="68"><stop offset="0" stop-color="#2fd2ff"/><stop offset=".28" stop-color="#1a7ff0"/><stop offset=".62" stop-color="#1438c4"/><stop offset=".86" stop-color="#0b1c82"/><stop offset="1" stop-color="#071052"/></linearGradient></defs>` +
  `${EXT} fill="url(#${u}o)"/><g fill="#fff" opacity=".07"><path d="M-6 -20H10L-4 84H-20Z"/><path d="M18 -20H25L14 84H4Z"/><path d="M38 -20H44L44 84H32Z"/></g>` +
  `<path d="M-20 1c6-3 10 2 16 0s9-4 15-1 10 2 15 0 12-2 18 1 9 2 14 0 10-2 16 1v5c-6-2-11 1-17 0s-9-3-15-1-10 3-16 1-10-2-16 1-9 1-14-1-10-1-16 1z" fill="#fff" opacity=".18"/>` +
  `<g fill="none" stroke="#fff" stroke-width=".45" opacity=".45"><circle cx="9" cy="47" r="1.4"/><circle cx="12" cy="41" r=".8"/><circle cx="55" cy="44" r="1.1"/><circle cx="52" cy="53" r=".7"/></g>`;
/* the background layer is drawn past the 64-unit grid, out to the edge of Android's 108 dp canvas */
const EXT = '<rect x="-20" y="-20" width="104" height="104"';
function gameIcon(kind, u) {
  switch (kind) {
    case 'archive': {
      const INK = '#4e4b42', grid = Array.from({length: 27}, (_, i) => `M${-20 + i * 4} -20V84M-20 ${-20 + i * 4}H84`).join('');
      const rings = [15.6, 14, 12.4, 10.8, 9.2].map(r => `<circle cx="32" cy="32" r="${r}" fill="none" stroke="#d1cdb7" stroke-width=".3" opacity=".3"/>`).join('');
      const br = (x, y, dx, dy) => `M${x + dx * 6.5} ${y}H${x}V${y + dy * 6.5}`;
      return {
        shape: '<rect width="64" height="64" rx="6"/>',
        bg: `${EXT} fill="#d1cdb7"/><path d="${grid}" stroke="#c4bfa6" stroke-width=".35"/>`,
        fg: `<circle cx="32" cy="32" r="22" fill="none" stroke="${INK}" stroke-width=".55" stroke-dasharray="1.3 1.5"/>` +
          `<circle cx="34.6" cy="34.2" r="17.6" fill="#b4af9a"/><circle cx="32" cy="32" r="17.6" fill="${INK}"/>` + rings +
          `<path d="M19.4 25.2A13.8 13.8 0 0 1 25.4 19.6" fill="none" stroke="#dad4bb" stroke-width="1" stroke-linecap="round" opacity=".6"/>` +
          `<circle cx="32" cy="32" r="7" fill="#dad4bb"/>` + icNote(32, 32.2, .44, INK) +
          `<path d="${br(10.5, 10.5, 1, 1)}${br(53.5, 10.5, -1, 1)}${br(10.5, 53.5, 1, -1)}${br(53.5, 53.5, -1, -1)}" fill="none" stroke="${INK}" stroke-width="1.5"/>` +
          `<path d="M32 6.5V9.5M32 54.5V57.5M6.5 32H9.5M54.5 32H57.5" stroke="${INK}" stroke-width="1"/>` +
          `<rect x="48.2" y="13.2" width="2.4" height="2.4" fill="#cd664d"/>`,
      };
    }
    case 'riot': {
      const seam = icPts([[-20, 66], [6, 54], [12, 57], [26, 46], [31, 49], [48, 36], [52, 39], [84, 16]]);
      return { shape: '<rect width="64" height="64" rx="6"/>',
        bg: `${EXT} fill="#e5191c"/>${icHalftone(4, 4, 3, 1.4, 40, '#0b0a0a', .5)}<polygon points="${seam} 84,84 -20,84" fill="#0b0a0a"/><polyline points="${seam}" fill="none" stroke="#fff" stroke-width="1.5" stroke-linejoin="miter"/>`,
        fg: icNote(34.4, 34.6, 1.95, '#0b0a0a', 'rotate(-11)', '#0b0a0a') + icNote(32, 32, 1.95, '#fff', 'rotate(-11)', '#0b0a0a') +
          `<polygon points="47,12 55,9 51,16" fill="#fff" stroke="#0b0a0a" stroke-width=".8"/><polygon points="9,22 15,25 8,27" fill="#0b0a0a" stroke="#fff" stroke-width=".8"/><polygon points="52,48 58,52 50,53" fill="#fff" stroke="#0b0a0a" stroke-width=".8"/>` +
          `<polygon points="${icStar(15, 13, 5, 4.6, 2, -.2)}" fill="#fff" stroke="#0b0a0a" stroke-width="1"/><polygon points="${icStar(53, 27, 5, 2.6, 1.1, .3)}" fill="#0b0a0a" stroke="#fff" stroke-width=".8"/>`,
      };
    }
    case 'tide': {
      const tri = (x, y, w, h, dir) => dir > 0 ? `M${x} ${y - h / 2}L${x + w} ${y}L${x} ${y + h / 2}Z` : `M${x + w} ${y - h / 2}L${x} ${y}L${x + w} ${y + h / 2}Z`;
      return { shape: '<rect width="64" height="64" rx="14"/>',
        bg: icOcean(u),
        fg: `<defs><radialGradient id="${u}g"><stop offset=".35" stop-color="#bff6ff" stop-opacity=".55"/><stop offset="1" stop-color="#bff6ff" stop-opacity="0"/></radialGradient><linearGradient id="${u}d" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#1a5fe0"/><stop offset="1" stop-color="#050c3a"/></linearGradient></defs>` +
          `<circle cx="34" cy="30" r="25" fill="url(#${u}g)"/>` +
          `<polygon points="4,58 50,46 40,56" fill="#fff" transform="translate(1.6 -1.9)"/><polygon points="4,58 50,46 40,56" fill="#ff1f3d"/>` +
          `<g transform="rotate(-11 32 31)" fill="none" stroke="#fff" stroke-width="1.05" stroke-linecap="round"><path d="M35.5 7.5C35.5 2.6 28 1.4 22.6 4"/><path d="M22.6 4C18.6 6 15.6 8.6 13.4 11.6"/><path d="M22.6 4C20 8.6 18.4 13 17.6 17.4"/></g>` +
          `<g transform="rotate(-11 32 31)">` +
          `<rect x="24.6" y="11.6" width="23" height="40" rx="4.6" fill="#050816"/><rect x="22" y="9" width="23" height="40" rx="4.6" fill="#f4fbff"/><rect x="22" y="9" width="23" height="40" rx="4.6" fill="none" stroke="#050816" stroke-width="1"/>` +
          `<rect x="24.8" y="12" width="17.4" height="17.6" rx="1.6" fill="url(#${u}d)"/>` +
          `<circle cx="33.5" cy="19" r="4.4" fill="#f2f8d8"/><path d="M26.4 25.2h14.2" stroke="#6ae6fb" stroke-width=".6" opacity=".7"/><rect x="26.4" y="26.9" width="14.2" height="1" fill="#0b1c82"/><rect x="26.4" y="26.9" width="8.6" height="1" fill="#6ae6fb"/>` +
          `<circle cx="33.5" cy="39.2" r="4.6" fill="#ff1f3d"/><path d="${tri(32.2, 39.2, 3.6, 4, 1)}" fill="#fff"/>` +
          `<path d="${tri(24.4, 39.2, 2.2, 2.8, -1)}${tri(26.4, 39.2, 2.2, 2.8, -1)}${tri(38.4, 39.2, 2.2, 2.8, 1)}${tri(40.4, 39.2, 2.2, 2.8, 1)}" fill="#050816"/>` +
          `<path d="M27 45.2h13" stroke="#050816" stroke-width=".8" stroke-linecap="round" opacity=".35"/>` +
          `<path d="M35.5 9.2V7.2" stroke="#050816" stroke-width="2.2"/></g>` +
          `<g transform="rotate(-11 32 31)"><g transform="rotate(35 11.8 14.2)"><rect x="11" y="9.6" width="1.6" height="3.6" rx=".8" fill="#050816"/><circle cx="11.8" cy="14.2" r="3.1" fill="#050816"/><circle cx="11.8" cy="14.2" r="1.5" fill="#0bc8ff"/></g>` +
          `<g transform="rotate(10 17 21.6)"><rect x="16.2" y="17" width="1.6" height="3.6" rx=".8" fill="#050816"/><circle cx="17" cy="21.6" r="3.1" fill="#050816"/><circle cx="17" cy="21.6" r="1.5" fill="#0bc8ff"/></g></g>` +
          icNote(52.5, 22, .5, '#fff') + icNote(51, 51.5, .32, '#e6fdff'),
      };
    }
    case 'frontier': {
      const r = icRng(11), streaks = Array.from({length: 22}, (_, i) => `<rect x="${(-20 + r() * 14).toFixed(1)}" y="${(-18 + i * 4.8 + r() * 1.5).toFixed(1)}" width="${(50 + r() * 60).toFixed(1)}" height="${(.5 + r() * 1.3).toFixed(1)}" opacity="${(.04 + r() * .07).toFixed(2)}"/>`).join('');
      const tips = Array.from({length: 6}, (_, i) => { const a = i * Math.PI / 3 - Math.PI / 2; return `<circle cx="${(32 + 21.5 * Math.cos(a)).toFixed(2)}" cy="${(32 + 21.5 * Math.sin(a)).toFixed(2)}" r="2.6"/>`; }).join('');
      return {
        shape: '<rect width="64" height="64" rx="10"/>',
        bg: `${EXT} fill="#0d0b09"/><g fill="#fff">${streaks}</g>`,
        fg: `<defs><linearGradient id="${u}b" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#f1d995"/><stop offset=".5" stop-color="#c49a48"/><stop offset="1" stop-color="#7e5a1e"/></linearGradient></defs>` +
          `<path d="${icRough(9.5, 9.5, 45, 45, 1.4, 5)}${icRough(14.5, 14.5, 35, 35, 1.2, 9)}" fill="#cc0000" fill-rule="evenodd"/>` +
          `<g fill="url(#${u}b)" stroke="#4d3510" stroke-width=".7"><polygon points="${icStar(32, 32, 6, 21.5, 11.6)}"/>${tips}</g>` +
          `<polygon points="${icStar(32, 32, 6, 17.6, 9.6)}" fill="none" stroke="#fbe7b0" stroke-width=".55" opacity=".7"/>` +
          `<circle cx="32" cy="32" r="7.4" fill="#22170c" stroke="#fbe7b0" stroke-width=".6"/>` + icNote(32, 32.2, .44, '#e8cf8a'),
      };
    }
    case 'neon': {
      const plate = 'M17 9H55V47L47 55H9V17Z';
      const haz = Array.from({length: 10}, (_, i) => `<path d="M${-30 + i * 6} 84L${-6 + i * 6} 60h3L${-27 + i * 6} 84Z"/>`).join('');
      const slices = [[8, 22, 0], [22, 27.5, 3.2], [27.5, 37, -1.2], [37, 40, 2.4], [40, 60, 0]];
      const note = c => icNote(31.5, 31.8, 1.45, c);
      const sliced = slices.map(([y0, y1, dx], i) => `<clipPath id="${u}s${i}"><rect x="0" y="${y0}" width="64" height="${y1 - y0}"/></clipPath><g transform="translate(${dx} 0)"><g clip-path="url(#${u}s${i})"><g transform="translate(-1.6 0)">${note('#ff2a4d')}</g><g transform="translate(1.6 0)">${note('#00f0ff')}</g>${note('#fcee0a')}</g></g>`).join('');
      return {
        shape: '<path d="M0 0H50L64 14V64H14L0 50Z"/>',
        bg: `${EXT} fill="#fcee0a"/><g fill="#07080e">${haz}</g>`,
        fg: `<path d="${plate}" fill="#00f0ff" transform="translate(2.6 2.6)"/><path d="${plate}" fill="#07080e"/>` +
          `<path d="M10.5 18.5V10.5H18.5" fill="none" stroke="#ff2a4d" stroke-width="1"/><rect x="21" y="12.6" width="9" height="2.4" fill="#fcee0a"/><rect x="31.5" y="12.6" width="2.4" height="2.4" fill="#ff2a4d"/>` +
          sliced + `<rect x="12" y="34.8" width="16" height=".9" fill="#00f0ff"/><path d="M38 50.5h8" stroke="#fcee0a" stroke-width="1.6"/><path d="M50 50.5h2" stroke="#ff2a4d" stroke-width="1.6"/>`,
      };
    }
  }
  return null;
}
function iconSVG(kind, hex, layer, size) {
  layer = layer || 'full'; size = size || 64;
  const u = 'ci' + (++iconSeq) + '_';
  const g64 = gameIcon(kind, u);
  if (g64) { // the adaptive layers are 108 dp, the 64-unit grid is its central 66 dp
    if (layer === 'bg' || layer === 'fg') return `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" viewBox="-20 -20 104 104">${g64[layer]}</svg>`;
    return `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" viewBox="0 0 64 64"><defs><clipPath id="${u}s">${g64.shape}</clipPath></defs><g clip-path="url(#${u}s)">${g64.bg}${g64.fg}</g></svg>`;
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
