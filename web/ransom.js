/* Persona 5's cut-out letters, shared by the player and the lock screen player (lock.js). */
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
