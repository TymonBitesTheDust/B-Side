# B-Side styles

B-Side has six switchable styles: its own calm default and five fan-made tributes to game menus. This page is the
one-page style sheet for each, written the way the game UI theme guide asks for: palette, type, the selection
treatment, rows, headings, panels, motion, the "done" moment, sounds and the app icon.

The styles are fan-made tributes. B-Side isn't affiliated with or endorsed by the makers of NieR: Automata,
Persona, Red Dead Redemption or Cyberpunk 2077, and all their names belong to their owners.

## The method

The guide comes from [Checkmate](https://github.com/TymonBitesTheDust/Checkmate), a to-do app with the same five game
styles. B-Side applies it to a music player:

- **One app, many skins.** The style sits on the root element: `<html data-skin="tide" data-theme="dark">`. Base
  components in `web/app.html` read only tokens (`--bg`, `--surface`, `--text`, `--accent`…). Each style lives in its
  own file under `web/skins/`, and every rule in it is scoped to `[data-skin="…"]`. No page is forked per style.
- **Style-only pieces** (cut-out letters, the star field, the ribbon, the help line, the "2 of 6" counter) live in `web/skins.js` and render nothing, or are hidden, in other styles.
- **A tribute, not a copy.** No game logos, art, screenshots, sound files or commercial fonts. Textures are CSS
  gradients or inline SVG (`feTurbulence` for paper, water and brush streaks), sounds are synthesized with the Web Audio
  API, and the fonts are free (SIL Open Font License) and embedded so everything works offline.
- **Readability beats mood.** Body text keeps at least 4.5:1 contrast, the chosen item included; large, bold menu
  words keep at least 3:1.
- **Respect motion settings.** Every wipe, wiggle and stamp stops with the device's reduced-motion setting. Persona 5,
  which moves the most, also has its own *Animations* setting.
- **Text must fit.** Big decorative words shrink to fit, and no page scrolls sideways on a phone.
- **Only what a music player needs.** The guide's corner date widget is left out: the header holds just the page
  title and its count.
- **Icons are tributes too.** Checkmate's icons were the starting point, but each B-Side icon is drawn fresh from its
  game, around something that says music: a record, a slashed note, a pocket player, a star badge, a glitching note.

How B-Side maps the guide's pieces onto a music player:

| Guide | B-Side |
| --- | --- |
| Main menu (sidebar) | The library tabs: Tracks, Albums, Artists, Genres, Folders, Playlists |
| Selected menu item | The open tab, and the song that's playing |
| Task rows | Song rows, and the rows in sheets and the queue |
| Page title | The big title at the top of each tab, and its count |
| Dialogs, menus, toasts | Dialogs, bottom sheets (menus and settings), toasts |
| "Task done" moment | Adding a song to your favourites |
| Page-change motion | Switching tabs or opening an album, artist, folder or playlist |

## B-Side (`bside`)

The calm default. Soft, rounded cards on a near-white or near-black page, the system font, and an accent colour you
choose (11 presets or any colour code, adjusted for contrast in light and dark). The chosen tab gets a short accent
underline; the playing song turns the accent colour. Its icon is a white note on the accent's gradient.

## NieR: Automata (`archive`)

**Mood:** the YoRHa interface. A beige page on a fine grid, dark-brown ink, calm and precise.

| Role | Paper (light) | Ink (dark) |
| --- | --- | --- |
| Page | `#d1cdb7` | `#1f1e1a` |
| Raised panel | `#dad4bb` | `#2b2a25` |
| Ink | `#4e4b42`, `#57544a` | `#dad4bb`, `#c9c3aa` |
| Plate | `rgba(180,175,154,.46)` | `rgba(180,175,154,.1)` |
| Alert | `#cd664d` | `#e08a73` |

- **Type:** Manrope; titles in wide-tracked capitals (0.22em) with an offset shadow.
- **Selection:** the plate. Tabs, songs and menu items are light-brown plates. Pointing at one fills it dark from the
  left while thin lines slide out above and below; the open tab and the playing song stay filled.
- **Also:** a square marker before each tab, light-brown header bands with a square bullet, dark header bands on
  sheets and dialogs, two-bar footers, faint scanlines and a vignette.
- **Page change:** the title glitches into place and the dashed rule draws out.
- **Favourite:** a square locks on (steps in from 1.8× to 0.9×) and *ARCHIVED* appears in a dark band.
- **Sound:** terminal blips (low-passed square and sine tones).
- **Icon:** a record in YoRHa ink, with an offset shadow and a note on its label, inside a dashed orbit and a lock-on
  reticle with one alert-red square, on the beige grid.

## Persona 5 (`riot`)

**Mood:** a phantom thief's calling card. A blood-red stage, cut-out ransom letters, hard angles. Always dark.

| Role | Colour |
| --- | --- |
| Page | blood `#4b0007` |
| Red | `#e5191c` |
| Black | coal `#0b0a0a` |
| Paper | bone `#d3d6d0`, white `#ffffff` |
| Highlight | cyan `#00e8ff` |
| Today | yellow `#ffe100` |

- **Type:** Anton (display) and Arsenal (body). Cut-out letters mix Anton, Abril Fatface, Bowlby One, Rubik Mono One,
  Special Elite and Arsenal.
- **Selection:** a red and cyan highlight wiggles behind the open tab, the playing song and whatever you point at.
- **Cut-out letters:** page titles and tabs. Each letter gets its own face, box (black with a white edge, white or red),
  size and tilt from a generator seeded by the word, so a word always looks the same. The real text stays for screen
  readers; the letters are `aria-hidden`.
- **Also:** a canvas star field of pulsing red stars, songs on jagged black plates with white edges, the page on a
  black panel over a red slab, black menus, white paper dialogs with a black title band, the count as a big outlined
  number, and the play button as a white-edged red star.
- **Page change:** a red and a black shard sweep across and the screen shakes.
- **Favourite:** a red slash across the screen and *Stolen!* stamped in cut-out letters.
- **Sound:** square-wave blips, a whoosh and a kick on page change, a slash and cymbal ping when you like a song.
- **Icon:** a big white note with a black outline and a hard black shadow, slashed across a red and black split by a
  jagged white seam, with halftone dots, stars and flying shards.
- **Settings:** *Animations* (Match device, Full or Reduced) and *Show the star field* replace the light/dark choice.

## Persona 3 Reload (`tide`)

**Mood:** the pause menu under water. Deep blues, light dancing on the surface, tilted italic command words.

| Role | Colour |
| --- | --- |
| Ocean | `#2fd2ff` → `#1a7ff0` → `#1438c4` → `#0b1c82` (Depths: `#0f4fb8` → `#020a26`) |
| Command words | `#6ae6fb`, `#0bc8ff`, `#68fffc`, in turn |
| Cursor | hot red `#ff1f3d` |
| Rows | near-black `rgba(3,6,18,.9)` |
| Wallet boxes | white with `#050816` ink |

- **Type:** Montserrat 800/900 italic, uppercase, tight tracking.
- **Selection:** the tabs are a tilted stack of words, each at its own angle and in one of the three cyans. The open
  tab turns white with a hard dark shadow, grows, and sits on a red triangle cursor with a white copy offset behind.
  The playing song's black bar turns white with the cursor at its tip.
- **Also:** `feTurbulence` caustics drifting near the surface, slanted light rays, the count in a white wallet box, and on wide screens the page name huge on a white ribbon
  down the left (its size is the window height divided by the word's measured length, so long names fit) and a
  *Command* help line with button prompts, bottom right.
- **Page change:** three rings ripple out.
- **Favourite:** glass shards burst out and *FAVOURITE* slides in on a red underline.
- **Sound:** glassy bells, bubbles and a soft echo.
- **Icon:** an original pocket music player, a nod to the hero who always has music on: a white player with a hard
  dark shadow, the moon on its screen, a red play button and earbuds, glowing under water above the red and white
  cursor.

## Red Dead Redemption 2 (`frontier`)

**Mood:** the game's native menus. Black ink-roller panels, brush banners, a rough red frame around whatever is
chosen. *Journal* is aged paper; *Campfire* is the menu at night.

| Role | Journal | Campfire |
| --- | --- | --- |
| Page | paper `#e6d7b6` | night `#15110d` → `#241810`, a far ridge and a fire's glow |
| Ink | `#1f150c`, `#44331f`, `#5c4829` | `#f5f5f5`, `#d9d4cc`, `#a39d95` |
| Red | `#b3120c`, frame `#cc0000` | `#e0342a` |
| Banner and menus | `#1a1410`, panels `rgba(8,7,6,.97)`, off-white `#f5f1e8` | `#312d29` |

- **Type:** Rye (display) and Libre Caslon Text (body), with small capitals for labels.
- **Selection:** every option sits on its own dark textured box (an SVG mask with ragged edges filled with
  `feTurbulence` streaks). The open tab, the playing song and the item you point at get a rough red brush frame,
  drawn as one 96×96 SVG used as a 9-slice `border-image`.
- **Also:** titles in a black brush banner over a ruled line with a diamond, a grey "2 of 6"
  counter under the tabs, covers as photo prints, black native menus in both variants, paper dialogs
  with a black banner head, toasts on a dark brush swatch, and a wax-seal play button.
- **Page change:** an ink brush sweeps across.
- **Favourite:** a red double-ruled *KEEPER* stamp, with ink spatters.
- **Sound:** plucked guitar, a register bell, a rubber stamp, rustling paper and spurs.
- **Icon:** a brass six-pointed lawman's star with ball tips and a note at its centre, in the rough red brush frame,
  on black ink.

## Cyberpunk 2077 (`neon`)

**Mood:** the game's settings menus. Salmon-red text and hairlines on near-black, cut corners, cyan for what's chosen.
The light variant is the street-poster yellow.

| Role | Neon (dark) | Yellow (light) |
| --- | --- | --- |
| Page | `#0a0b11` | `#fcee0a` |
| Text | `#ff8174`, `#e2695f`, `#c26a62` | `#000000`, `#23200a`, `#4f4906` |
| Red (frames, titles) | `#ff5f56` | `#e4002b` |
| Chosen | cyan `#5ef6ff` | black |
| Tag | yellow `#fcee0a` | black on yellow |

- **Type:** Rajdhani, uppercase, wide tracking on small labels.
- **Selection:** settings-style rows. The row under the pointer gets a red outline with a cut corner, a marker bar and
  a faint fill; the open tab and the playing song get the same frame in cyan (black on the yellow page).
- **Also:** chamfered shapes from `clip-path` tokens, a tiny code label over the title, a hairline with a red segment
  and tick marks under it, bracketed counts `[ 15 ]`, ‹ OFF › / ‹ ON › switches, a seek bar with tick marks and a
  cyan block, scanlines, and black menus on the yellow page.
- **Page change:** red, cyan and yellow slices and a flicker; the title glitches into place.
- **Favourite:** *TRACK SAVED* flickers in on a tag while pixels scatter and a scan sweeps the screen.
- **Sound:** digital blips, saw zaps through a low-pass, glitch grains and a sub drop.
- **Icon:** a note glitching into cyan and red slices on a black cut-corner plate with a cyan offset, on
  street-poster yellow with hazard stripes.

## Adding a style

1. Collect references and write its sheet here first: palette, fonts, the selection treatment, rows, headings,
   panels, page change, the favourite moment, sounds and icon.
2. Add `web/skins/<id>.css` (every rule scoped to `[data-skin="<id>"]`) and list it in `web/build.py`.
3. Add it to `SKINS` in `web/app.html`, its sound bank to `SFX_SETS`, its wipe and favourite word in
   `web/skins.js`, and its icon in `web/icons.js` (then run `android/gen_icons.py` and add a launcher alias).
4. Check every page in light and dark at phone and desktop width, contrast on the chosen item, reduced motion, and that
   no page scrolls sideways.
