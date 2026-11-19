/* ───────────────────────── lock screen player ─────────────────────────
   The same page, opened in "lock mode". Android's lock screen player (LockScreenActivity) opens it with a
   BSideLock bridge, on an origin of its own that can't see your library. The preview in Settings › Lock screen
   opens it as #lockpreview and feeds it with postMessage. Either way the player itself never starts here (see
   the first line of the main script): this only draws the song that's playing, with the Now Playing screen's
   markup so every style dresses it the way it dresses the app, and sends taps back.

   Look (from the player's settings): {skin, dark, vars: {CSS custom properties}, show: {clock, date, cover,
   artist, album, progress, controls, fav, lyrics, hint, bgArt}}. Song: {title, artist, album, duration and
   position in ms, playing, speed, tint "r,g,b", fav, tid, cover URL}. Lyrics: {id, lines: [[seconds, text]…]}. */
(() => {
'use strict';
const BRIDGE = window.BSideLock || null;
const MODE = BRIDGE || location.hash === '#lock' ? 'lock' : location.hash === '#lockpreview' ? 'preview' : '';
if (!MODE) return;
window.__bsideLock = MODE;

const D = document.documentElement, B = document.body;
const $ = s => document.querySelector(s);
const esc = s => String(s == null ? '' : s).replace(/[&<>"']/g, c => ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[c]));
const ic = n => `<svg class="ico"><use href="#i-${n}"/></svg>`;
const fmt = ms => { let s = Math.max(0, Math.floor((+ms || 0) / 1000)); const h = Math.floor(s / 3600), m = Math.floor(s % 3600 / 60); s %= 60; return (h ? h + ':' + String(m).padStart(2, '0') : m) + ':' + String(s).padStart(2, '0'); };
/*@RANSOM@*/

const SHOW = {clock: true, date: true, cover: true, artist: true, album: true, progress: true, controls: true, fav: false, lyrics: false, hint: true, bgArt: false};
let look = {skin: 'bside', dark: true, vars: {}, show: SHOW}, song = null, lyrics = null, h24 = null, base = {pos: 0, at: 0};
const parse = s => { try { return s ? JSON.parse(s) : null; } catch (e) { return null; } };

// The app's own screens never run here: keep only the icon sprite.
for (const el of [...B.children]) if (el.tagName.toLowerCase() !== 'svg') el.remove();
B.removeAttribute('data-mode'); B.className = 'lock-mode';
B.insertAdjacentHTML('beforeend', `<section class="np open lock" id="np" aria-label="Lock screen player">
  <div class="deco np-deco" aria-hidden="true"></div>
  <div class="np-bg"></div>
  <div class="lk-bgart" id="lkBgArt" aria-hidden="true"></div>
  <div class="lk-move" id="lkMove">
    <header class="lk-clock"><div class="lk-time" id="lkTime"></div><div class="lk-date" id="lkDate"></div></header>
    <div class="np-body">
      <div class="np-stage" id="npStage"><button class="np-art" id="npArt" aria-label="Unlock and open B-Side"></button></div>
      <div class="lk-lyric" id="lkLyric"></div>
      <div class="np-panel">
        <div class="np-head">
          <div class="np-titles"><div class="np-title" id="npTitleWrap"><span id="npTitle"></span></div><div class="np-artist" id="npArtist"></div></div>
          <button class="ib" id="npFav" aria-label="Favourite">${ic('heart')}</button>
        </div>
        <div class="lk-prog">
          <div class="seek"><input id="seek" type="range" min="0" max="1000" step="1" value="0" aria-label="Seek"></div>
          <div class="times"><span id="tCur">0:00</span><span id="tDur">0:00</span></div>
        </div>
        <div class="ctrls">
          <button class="ib big" id="btnPrev" aria-label="Previous">${ic('prev')}</button>
          <button class="play" id="btnPlay" aria-label="Play or pause"><svg class="ico i-play"><use href="#i-play"/></svg><svg class="ico i-pause"><use href="#i-pause"/></svg></button>
          <button class="ib big" id="btnNext" aria-label="Next">${ic('next')}</button>
        </div>
      </div>
    </div>
    <button class="lk-hint" id="lkHint"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 14.5l6-6 6 6"/></svg><span>Swipe up to unlock</span></button>
  </div>
</section>`);
const np = $('#np'), move = $('#lkMove'), seek = $('#seek');

/* ── drawing ── */
function applyLook() {
  D.dataset.skin = look.skin || 'bside';
  D.dataset.theme = look.dark === false ? 'light' : 'dark';
  D.dataset.motion = matchMedia('(prefers-reduced-motion: reduce)').matches ? 'reduced' : 'full';
  for (const k of [...D.style]) if (k.startsWith('--') && k !== '--tint' && k !== '--sat' && k !== '--sab') D.style.removeProperty(k);
  for (const [k, v] of Object.entries(look.vars || {})) if (k.startsWith('--')) D.style.setProperty(k, v);
  const sh = Object.assign({}, SHOW, look.show || {});
  for (const k of Object.keys(SHOW)) np.classList.toggle('lk-no-' + k, !sh[k]);
  np.classList.toggle('lk-bg', !!sh.bgArt);
  return sh;
}
function draw() {
  const sh = applyLook(), s = song || {};
  const title = s.title || 'Nothing playing', sub = [sh.artist ? s.artist : '', sh.album ? s.album : ''].filter(Boolean);
  if ($('#npTitle').textContent !== title) { $('#npTitle').textContent = title; marquee(); }
  $('#npArtist').textContent = sub.filter((x, i) => sub.indexOf(x) === i).join(' · ');
  $('#npArtist').hidden = !sub.length;
  D.style.setProperty('--tint', s.tint || '110,130,255');
  const art = $('#npArt'), key = (s.cover || '') + '|' + (s.tint || '');
  if (art.dataset.key !== key) {
    art.dataset.key = key;
    art.innerHTML = s.cover ? `<img src="${esc(s.cover)}" alt="">` : `<div class="ph" style="width:100%;height:100%;background:linear-gradient(135deg,rgb(${esc(s.tint || '110,130,255')}),rgba(${esc(s.tint || '110,130,255')},.45))">${ic('note')}</div>`;
    $('#lkBgArt').style.backgroundImage = s.cover ? `url("${String(s.cover).replace(/"/g, '%22')}")` : '';
  }
  $('#npFav').classList.toggle('on', !!s.fav);
  B.classList.toggle('is-playing', !!s.playing);
  $('#tDur').textContent = fmt(s.duration);
  base = {pos: +s.position || 0, at: performance.now()};
  clock(true); tick();
}
function posNow() {
  const s = song; if (!s) return 0;
  const p = base.pos + (s.playing ? (performance.now() - base.at) * (+s.speed || 1) : 0);
  return s.duration ? Math.min(p, s.duration) : p;
}
let seeking = false, lastLine = -2;
function tick() {
  const s = song || {}, d = +s.duration || 0, p = posNow();
  if (!seeking) { const f = d ? Math.max(0, Math.min(1, p / d)) : 0; seek.value = Math.round(f * 1000); seek.style.setProperty('--p', (f * 100).toFixed(2) + '%'); $('#tCur').textContent = fmt(p); }
  // the line being sung now, and the next one
  const ly = $('#lkLyric'), lines = lyrics && song && lyrics.id === song.tid && Array.isArray(lyrics.lines) ? lyrics.lines : null;
  let i = -1; if (lines) for (let k = 0; k < lines.length; k++) { if (lines[k][0] <= p / 1000 + .25) i = k; else break; }
  if (!lines) { if (lastLine !== -2) { lastLine = -2; ly.innerHTML = ''; ly.classList.remove('has'); } return; }
  if (i === lastLine) return; lastLine = i;
  const cur = i >= 0 ? lines[i][1] || '♪' : '', nx = lines[i + 1] ? lines[i + 1][1] : '';
  ly.innerHTML = `<p class="on">${esc(cur || '♪')}</p>${nx ? `<p>${esc(nx)}</p>` : ''}`;
  ly.classList.add('has');
}
let lastMinute = '';
function clock(force) {
  const now = new Date(), hh = now.getHours(), mm = String(now.getMinutes()).padStart(2, '0');
  const t24 = h24 != null ? h24 : !/[ap]m|午|오/i.test(now.toLocaleTimeString([], {hour: 'numeric'}));
  const text = (t24 ? hh : (hh % 12 || 12)) + ':' + mm;
  if (text === lastMinute && !force) return; lastMinute = text;
  const t = $('#lkTime');
  t.innerHTML = D.dataset.skin === 'riot' ? `<span class="tl">${esc(text)}</span>${ransomHTML(text, {firstRed: true, seed: 'lock'})}` : esc(text);
  $('#lkDate').textContent = now.toLocaleDateString([], {weekday: 'long', day: 'numeric', month: 'long'});
}
function marquee() {
  const w = $('#npTitleWrap'), s = $('#npTitle'); w.classList.remove('marq');
  requestAnimationFrame(() => { const over = s.scrollWidth - w.clientWidth; if (over > 4) { w.style.setProperty('--d', -(over + 28) + 'px'); w.style.setProperty('--mdur', Math.max(8, over / 18) + 's'); w.classList.add('marq'); } });
}
setInterval(() => { if (!document.hidden) { tick(); clock(); } }, 250);

/* ── what it's given ── */
function pull() { // lock mode: read everything again from the app
  if (!BRIDGE) return;
  look = parse(BRIDGE.config()) || look;
  song = parse(BRIDGE.state());
  const ly = parse(BRIDGE.lyrics && BRIDGE.lyrics()); if (ly) lyrics = ly;
  if (h24 == null && BRIDGE.clock24) try { h24 = !!BRIDGE.clock24(); } catch (e) {}
  draw();
}
window.bsideLockUpdate = pull;
window.bsideInsets = (top, bottom) => { D.style.setProperty('--sat', (+top || 0) + 'px'); D.style.setProperty('--sab', (+bottom || 0) + 'px'); };
if (BRIDGE) { try { const i = JSON.parse(BRIDGE.insets()); window.bsideInsets(i.top, i.bottom); } catch (e) {} pull(); }
else if (MODE === 'lock') draw();
window.addEventListener('message', e => { // the preview in Settings
  if (MODE !== 'preview' || e.source !== window.parent || !e.data || !e.data.bsideLock) return;
  const m = e.data.bsideLock;
  if (m.look) look = m.look;
  if ('song' in m) song = m.song;
  if ('lyrics' in m) lyrics = m.lyrics;
  if ('h24' in m) h24 = m.h24;
  draw();
});
if (MODE === 'preview') { np.classList.add('lk-preview'); try { window.parent.postMessage({bsideLockReady: true}, '*'); } catch (e) {} }

/* ── taps and the swipe up ── */
const send = (cmd, v) => { if (BRIDGE) { try { cmd === 'seek' ? BRIDGE.seek(v) : BRIDGE.command(cmd); } catch (e) {} } };
const unlock = open => { if (!BRIDGE) return; np.classList.add('leaving'); try { BRIDGE.unlock(!!open); } catch (e) {} };
let dragged = false;
np.addEventListener('click', e => {
  if (dragged) { e.preventDefault(); e.stopPropagation(); dragged = false; return; }
  const b = e.target.closest('button'); if (!b || MODE !== 'lock') return;
  if (b.id === 'btnPlay') { song && (song.playing = !song.playing, base = {pos: posNow(), at: performance.now()}); B.classList.toggle('is-playing', !!(song && song.playing)); send(song && song.playing ? 'play' : 'pause'); }
  else if (b.id === 'btnPrev') send('prev');
  else if (b.id === 'btnNext') send('next');
  else if (b.id === 'npFav') { if (song) { song.fav = !song.fav; b.classList.toggle('on', song.fav); } send('fav'); }
  else if (b.id === 'npArt') unlock(true);
  else if (b.id === 'lkHint') unlock(false);
}, true);
seek.addEventListener('input', () => { seeking = true; const d = song ? +song.duration || 0 : 0; seek.style.setProperty('--p', seek.value / 10 + '%'); $('#tCur').textContent = fmt(d * seek.value / 1000); });
seek.addEventListener('change', () => {
  seeking = false; const d = song ? +song.duration || 0 : 0; if (!d || MODE !== 'lock') return;
  const ms = Math.round(d * seek.value / 1000); base = {pos: ms, at: performance.now()}; send('seek', ms);
});
let drag = null;
np.addEventListener('pointerdown', e => {
  if (MODE !== 'lock' || e.target.closest('.seek') || np.classList.contains('leaving')) return;
  drag = {id: e.pointerId, y: e.clientY, x: e.clientX, t: performance.now(), on: false};
});
np.addEventListener('pointermove', e => {
  if (!drag || e.pointerId !== drag.id) return;
  const dy = e.clientY - drag.y, dx = e.clientX - drag.x;
  if (!drag.on && -dy > 14 && Math.abs(dy) > Math.abs(dx) * 1.3) { drag.on = true; np.classList.add('dragging'); try { np.setPointerCapture(e.pointerId); } catch (er) {} }
  if (drag.on) { const off = Math.min(0, dy); move.style.transform = `translateY(${off}px)`; move.style.opacity = Math.max(.25, 1 + off / (innerHeight * .55)); }
});
const end = e => {
  if (!drag || e.pointerId !== drag.id) return;
  const d = drag; drag = null; if (!d.on) return;
  dragged = true; setTimeout(() => { dragged = false; }, 60);
  np.classList.remove('dragging');
  const dy = e.clientY - d.y, v = dy / Math.max(1, performance.now() - d.t);
  if (e.type === 'pointerup' && (-dy > innerHeight * .16 || v < -.9)) { move.style.transform = `translateY(${-innerHeight * .35}px)`; move.style.opacity = 0; setTimeout(() => unlock(false), 160); }
  else { move.style.transform = ''; move.style.opacity = ''; }
};
np.addEventListener('pointerup', end); np.addEventListener('pointercancel', end);
window.bsideLockReset = () => { np.classList.remove('leaving', 'dragging'); move.style.transform = ''; move.style.opacity = ''; }; // unlocking was cancelled
})();
