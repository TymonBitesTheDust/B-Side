/* ───────────────────────── game-style pieces ─────────────────────────
   Theme-only components, after the Game UI theme guide: each renders nothing (or is hidden by CSS)
   outside its own style. Persona 5 (riot): cut-out letters, the star field, the wiggling
   highlight. Persona 3 (tide): the page word on a white ribbon, the command help line. Red Dead
   Redemption 2 (frontier): the "2 of 6" counter. Every style: the page-change effect and the
   moment a song is added to favourites. */

/* Animations: Persona 5 has its own setting (Match device / Full / Reduced); the others follow the device. */
function motionReduced() {
  const m = skinOf() === 'riot' ? (S.settings.motion || 'system') : 'system';
  if (m === 'reduced') return true;
  if (m === 'full') return false;
  return matchMedia('(prefers-reduced-motion: reduce)').matches;
}
function applyMotion() { document.documentElement.dataset.motion = motionReduced() ? 'reduced' : 'full'; }

/* Cut-out letters. Each letter gets its own face, box, size and tilt, from a generator seeded by the
   word, so a word always looks the same. The real text stays in .tl for screen readers. */
function rFnv(str) { let h = 2166136261; for (let i = 0; i < str.length; i++) { h ^= str.charCodeAt(i); h = Math.imul(h, 16777619); } return h >>> 0; }
function rXor(seed) { let s = seed || 1; return () => { s ^= s << 13; s ^= s >>> 17; s ^= s << 5; return ((s >>> 0) % 10000) / 10000; }; }
const R_FACES = ['f-anton', 'f-abril', 'f-bowlby', 'f-rubik', 'f-elite', 'f-arsenal'];
function ransomHTML(text, {firstRed = false, seed = ''} = {}) {
  text = String(text || ''); if (!text) return '';
  const rand = rXor(rFnv(seed + text)); let n = 0;
  return '<span class="ransom" aria-hidden="true">' + text.split(/\s+/).filter(Boolean).map(w => '<span class="r-word">' + Array.from(w).map(ch => {
    const first = n++ === 0, face = R_FACES[Math.floor(rand() * R_FACES.length)], pick = rand();
    let look = pick < .6 ? 'blk' : pick < .86 ? 'wht' : 'red'; if (first && firstRed) look = 'red';
    const upper = first || rand() < .55, size = first ? 1.16 : .84 + rand() * .26, rot = (rand() * 2 - 1) * 8, dy = (rand() * 2 - 1) * .06;
    return `<span class="r-ch ${face} ${look}" style="--s:${size.toFixed(2)};--r:${rot.toFixed(1)}deg;--dy:${dy.toFixed(2)}em">${esc(upper ? ch.toUpperCase() : ch.toLowerCase())}</span>`;
  }).join('') + '</span>').join('') + '</span>';
}
const titleHTML = (text, o) => `<span class="tl">${esc(text)}</span>${ransomHTML(text, o)}`;
let heroKey = '';
function setHeroTitle(text) {
  const h = $('#heroTitle'), cut = skinOf() === 'riot', key = cut + '|' + text;
  if (key === heroKey && h.firstChild) return; heroKey = key;
  h.innerHTML = cut ? titleHTML(text, {firstRed: true}) : esc(text);
  updateRibbon(); updateKeyHints();
}
function setHeroSub(n, label) { // "15 songs": a number and a label each style can set apart
  $('#heroSub').innerHTML = n == null ? `<span class="hs-l">${esc(label)}</span>` : `<b class="hs-n">${n}</b> <span class="hs-l">${esc(label)}</span>`;
}

/* Persona 3: the page name huge on a white ribbon down the left of wide windows. Its size comes from the
   window height divided by the word's measured length (Montserrat Black Italic, in em), so long names fit. */
const RIB_EM = {A: .8, B: .73, C: .68, D: .79, E: .63, F: .6, G: .73, H: .76, I: .31, J: .53, K: .73, L: .58, M: .91, N: .76, O: .81, P: .69, Q: .81, R: .71, S: .62, T: .62, U: .73, V: .75, W: 1.16, X: .72, Y: .68, Z: .65, '&': .74, ' ': .25, '/': .33, '-': .36};
function ribbonEms(text) { let n = 0; for (const c of String(text || '').toUpperCase()) n += c in RIB_EM ? RIB_EM[c] : /[0-9]/.test(c) ? .62 : /[\u2e80-\u9fff]/.test(c) ? 1.1 : .72; return Math.max(1, +(n * 1.04).toFixed(2)); }
function pageWord() { return document.body.dataset.mode === 'detail' && S.detail ? S.detail.kind : document.body.dataset.mode === 'search' ? 'Search' : (tabTitle[S.tab] || 'Tracks'); }
function updateRibbon() {
  const el = $('#ribbon'); if (skinOf() !== 'tide') { if (el.textContent) el.textContent = ''; return; }
  const w = pageWord(); if (el.textContent === w) return;
  el.textContent = w; el.style.setProperty('--len', ribbonEms(w));
  el.classList.remove('in'); void el.offsetWidth; el.classList.add('in');
}
/* Persona 3: the Command help line, bottom right like the pause menu (wide screens with a mouse). */
const PAGE_HELP = {tracks: 'View / Play your songs', albums: 'Browse your albums', artists: 'Find an artist', genres: 'Browse by genre', folders: 'Explore your folders', playlists: 'View / Edit playlists', detail: 'Play / Queue these songs', search: 'Find a song'};
function updateKeyHints() {
  const el = $('#keyHints'); if (skinOf() !== 'tide') { if (el.innerHTML) el.innerHTML = ''; return; }
  const mode = document.body.dataset.mode, label = PAGE_HELP[mode === 'tabs' ? S.tab : mode] || 'What will you play today?';
  el.innerHTML = `<span class="kh-label">${esc(label)}</span><span class="kh-cmd">Command</span><span class="kh-keys"><span><kbd>␣</kbd>Play</span><span><kbd>/</kbd>Search</span><span><kbd>⎋</kbd>Back</span></span>`;
}
/* Red Dead Redemption 2: the native menu's counter, "2 of 6", under the tabs. */
function updateFrCounter() {
  const el = $('#frCounter'); if (skinOf() !== 'frontier') { el.textContent = ''; return; }
  const v = visibleTabs(), i = v.indexOf(S.tab); el.textContent = i < 0 ? '' : `${i + 1} of ${v.length}`;
}

/* ── Persona 5: the star-ripple field, concentric five-point stars pulsing in a slanted panel ── */
const starfield = (() => {
  let cv = null, ctx = null, W = 0, H = 0, dpr = 1, stars = [], region = [], raf = 0, last = 0, t0 = 0, bootAt = 0, on = false;
  const inside = (x, y, poly) => { let c = false; for (let i = 0, j = poly.length - 1; i < poly.length; j = i++) { const [xi, yi] = poly[i], [xj, yj] = poly[j]; if (yi > y !== yj > y && x < ((xj - xi) * (y - yi)) / (yj - yi) + xi) c = !c; } return c; };
  const starSub = r => { for (let k = 0; k < 10; k++) { const a = -Math.PI / 2 + k * Math.PI / 5, rr = k % 2 ? r * .47 : r, x = Math.cos(a) * rr, y = Math.sin(a) * rr; if (k) ctx.lineTo(x, y); else ctx.moveTo(x, y); } ctx.closePath(); };
  const layout = () => {
    let s = 5150; const rnd = () => { s ^= s << 13; s >>>= 0; s ^= s >>> 17; s ^= s << 5; s >>>= 0; return s / 4294967296; };
    dpr = Math.min(window.devicePixelRatio || 1, 1.5); W = window.innerWidth; H = window.innerHeight;
    cv.width = Math.round(W * dpr); cv.height = Math.round(H * dpr);
    const narrow = W < 900, fr = narrow ? [[.5, .32], [1.02, .1], [1.02, 1.02], [-.02, 1.02]] : [[.62, -.02], [1.02, -.02], [1.02, 1.02], [.42, 1.02]];
    region = fr.map(([x, y]) => [x * W, y * H]); const big = Math.max(W, H), want = narrow ? 30 : 46; stars = []; let guard = 0;
    while (stars.length < want && guard++ < 5000) { const x = rnd() * W, y = rnd() * H; if (!inside(x, y, region)) continue; const layer = stars.length < want * .4 ? 1 : 0; stars.push({x, y, layer, R: big * (layer ? .05 + rnd() * .05 : .03 + rnd() * .045), rot: rnd() * Math.PI * 2, speed: (layer ? -1 : 1) * (.35 + rnd() * .25)}); }
  };
  const draw = ts => {
    const t = (ts - t0) / 1000, b = Math.min(1, (ts - bootAt) / 520), k = b >= 1 ? 1 : 1 - Math.pow(1 - b, 3) * Math.cos(b * 7.5);
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0); ctx.clearRect(0, 0, W, H); ctx.save(); ctx.beginPath();
    region.forEach(([x, y], i) => (i ? ctx.lineTo(x, y) : ctx.moveTo(x, y))); ctx.closePath(); ctx.fillStyle = '#250006'; ctx.fill(); ctx.clip();
    for (const s of stars) {
      const R = s.R * Math.max(0, k); if (R < 1) continue;
      ctx.save(); ctx.translate(s.x, s.y); ctx.rotate(s.rot); ctx.beginPath(); starSub(R); ctx.clip();
      ctx.fillStyle = s.layer ? '#7a0910' : '#0b0002'; ctx.fillRect(-R, -R, R * 2, R * 2);
      const w = R / 4.6, ph = (((t * s.speed) % 1) + 1) % 1; ctx.beginPath();
      for (let rr = R + 2 * w - ph * 2 * w; rr > 0; rr -= 2 * w) { starSub(rr); starSub(Math.max(.1, rr - w)); }
      ctx.fillStyle = s.layer ? '#e5191c' : '#c8141b'; ctx.fill('evenodd'); ctx.restore();
    }
    ctx.restore(); ctx.beginPath(); ctx.moveTo(region[0][0], region[0][1]); ctx.lineTo(region[3][0], region[3][1]);
    ctx.lineWidth = 10; ctx.strokeStyle = '#0b0a0a'; ctx.stroke(); ctx.lineWidth = 3.5; ctx.strokeStyle = '#d3d6d0'; ctx.stroke();
  };
  const loop = ts => { raf = 0; if (!on || document.hidden) return; if (ts - last >= 33) { last = ts; draw(ts); } if (!motionReduced()) raf = requestAnimationFrame(loop); };
  const restart = () => { cancelAnimationFrame(raf); raf = 0; if (!on) return; if (motionReduced()) { bootAt = -1e9; draw(performance.now()); } else raf = requestAnimationFrame(loop); };
  let rT = 0;
  window.addEventListener('resize', () => { if (!on) return; clearTimeout(rT); rT = setTimeout(() => { layout(); restart(); }, 120); });
  document.addEventListener('visibilitychange', () => { if (on && !document.hidden) restart(); });
  return {
    sync() {
      const want = skinOf() === 'riot' && S.settings.stars !== false;
      cv = cv || $('#starfield');
      if (want === on) { if (on) restart(); return; }
      on = want; cv.hidden = !on;
      if (!on) { cancelAnimationFrame(raf); raf = 0; return; }
      ctx = ctx || cv.getContext('2d'); if (!ctx) { on = false; return; }
      t0 = bootAt = performance.now(); layout(); restart();
    },
  };
})();

/* ── page change: each style's own transition (not on the first page, not with reduced motion) ── */
let fxFirst = true;
function pageFx() {
  const first = fxFirst; fxFirst = false;
  const h = $('#hero'); h.classList.remove('anim'); void h.offsetWidth; h.classList.add('anim');
  updateRibbon(); updateKeyHints(); updateFrCounter();
  const skin = skinOf(); if (first || motionReduced() || !['riot', 'tide', 'frontier', 'neon'].includes(skin)) return;
  const w = document.createElement('div'); w.className = 'wipe wipe-' + skin; w.setAttribute('aria-hidden', 'true');
  w.innerHTML = '<i></i><i></i><i></i>'; document.body.appendChild(w); setTimeout(() => w.remove(), 900);
  if (skin === 'riot') { const v = document.body; v.classList.remove('shake'); void v.offsetWidth; v.classList.add('shake'); setTimeout(() => v.classList.remove('shake'), 260); }
}

/* ── the "done" moment: a song added to favourites ── */
const DONE_WORD = {riot: 'Stolen!', tide: 'FAVOURITE', frontier: 'KEEPER', neon: 'TRACK SAVED', archive: 'ARCHIVED'};
function doneFx() {
  const skin = skinOf(); if (!DONE_WORD[skin] || motionReduced()) return;
  const old = $('.done-fx'); if (old) old.remove();
  const el = document.createElement('div'); el.className = 'done-fx done-' + skin; el.setAttribute('aria-hidden', 'true');
  const word = DONE_WORD[skin];
  el.innerHTML = skin === 'riot' ? `<span class="df-slash"></span>${ransomHTML(word, {seed: 'stamp'})}`
    : `<span class="df-burst">${'<i></i>'.repeat(8)}</span><span class="df-word">${esc(word)}</span>`;
  document.body.appendChild(el); setTimeout(() => el.remove(), 1100);
}
