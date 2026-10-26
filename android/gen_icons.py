#!/usr/bin/env python3
"""Renders the switchable launcher icons from web/icons.js into res-gen/.

For every variant (11 B-Side colours + 5 styles) it writes:
  mipmap-xxxhdpi/ic_l_<id>_bg.png   adaptive background (432 px = 108 dp); the B-Side colours
                                    use vector gradients instead (drawable/ic_l_<id>_bg.xml)
  mipmap-xxxhdpi/ic_l_<id>_fg.png   adaptive foreground, transparent (B-Side: shared vector note)
  mipmap-xxxhdpi/ic_l_<id>.png      legacy icon for Android 7.x (192 px)
  mipmap-anydpi-v26/ic_l_<id>.xml   adaptive icon (+ themed monochrome layer)
Needs Playwright with Chromium and Pillow."""
import asyncio, base64, io, json, pathlib, shutil
from PIL import Image
from playwright.async_api import async_playwright

HERE = pathlib.Path(__file__).resolve().parent
OUT = HERE / 'res-gen'
JS = (HERE.parent / 'web' / 'icons.js').read_text(encoding='utf-8')

PAGE = """<!doctype html><meta charset=utf-8><body style="margin:0;background:transparent"><script>%s
window.variants = ACCENTS.map(a => ({id: 'bside_' + a.id, kind: 'bside', hex: a.hex})).concat(ICON_SKINS.map(k => ({id: k, kind: k, hex: null})));
window.render = async (kind, hex, layer, px) => {
  const img = new Image(); img.src = iconURL(kind, hex, layer, px); await img.decode();
  const c = document.createElement('canvas'); c.width = c.height = px; c.getContext('2d').drawImage(img, 0, 0, px, px);
  return c.toDataURL('image/png');
};
</script>""" % JS

ADAPTIVE = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="{bg}" />
    <foreground android:drawable="{fg}" />
    <monochrome android:drawable="@drawable/ic_launcher_fg" />
</adaptive-icon>
"""


def argb(hex_, alpha='FF'):
    return '#' + alpha + hex_.lstrip('#').upper()


VEC_BG = """<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android" xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr name="android:fillColor">
            <gradient android:type="linear" android:startX="0" android:startY="0" android:endX="108" android:endY="108">
                <item android:offset="0" android:color="{a}" />
                <item android:offset="0.55" android:color="{b}" />
                <item android:offset="1" android:color="{c}" />
            </gradient>
        </aapt:attr>
    </path>
    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr name="android:fillColor">
            <gradient android:type="radial" android:centerX="32.4" android:centerY="23.76" android:gradientRadius="64.8">
                <item android:offset="0" android:color="#38FFFFFF" />
                <item android:offset="1" android:color="#00FFFFFF" />
            </gradient>
        </aapt:attr>
    </path>
</vector>
"""

NOTE_PATHS = """<path android:pathData="M9,17.5V6.2l10,-2.2v11.3" android:strokeColor="{c}" android:strokeWidth="2.3" android:strokeLineJoin="round" android:strokeLineCap="round" />
        <path android:pathData="M3.6,17.5a2.9,2.9 0,1 0,5.8 0a2.9,2.9 0,1 0,-5.8 0z" android:fillColor="{c}" />
        <path android:pathData="M13.6,15.3a2.9,2.9 0,1 0,5.8 0a2.9,2.9 0,1 0,-5.8 0z" android:fillColor="{c}" />"""

VEC_FG = """<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
    <group android:scaleX="2.1" android:scaleY="2.1" android:translateX="29.85" android:translateY="31.24">
        """ + NOTE_PATHS.format(c="#24000000") + """
    </group>
    <group android:scaleX="2.1" android:scaleY="2.1" android:translateX="29.85" android:translateY="29.64">
        """ + NOTE_PATHS.format(c="#FFFFFFFF") + """
    </group>
</vector>
"""


def save_png(data_url, path, palette):
    im = Image.open(io.BytesIO(base64.b64decode(data_url.split(',', 1)[1]))).convert('RGBA')
    if palette:  # 256-colour PNG with dithering keeps the files small without visible banding
        im = im.quantize(colors=256, method=Image.Quantize.FASTOCTREE, dither=Image.Dither.FLOYDSTEINBERG)
    im.save(path, optimize=True)


async def main():
    if OUT.exists():
        shutil.rmtree(OUT)
    (OUT / 'mipmap-xxxhdpi').mkdir(parents=True)
    (OUT / 'mipmap-anydpi-v26').mkdir(parents=True)
    (OUT / 'drawable').mkdir(parents=True)
    (OUT / 'drawable' / 'ic_l_bside_fg.xml').write_text(VEC_FG, encoding='utf-8')
    async with async_playwright() as p:
        browser = await p.chromium.launch()
        page = await browser.new_page()
        await page.set_content(PAGE)
        variants = await page.evaluate('variants')
        for v in variants:
            vid, vector = v['id'], v['kind'] == 'bside'
            layers = (('full', 192, ''),) if vector else (('bg', 432, '_bg'), ('fg', 432, '_fg'), ('full', 192, ''))
            for layer, px, suffix in layers:
                url = await page.evaluate('([k, h, l, s]) => render(k, h, l, s)', [v['kind'], v['hex'], layer, px])
                # Persona 3's soft glow bands when cut to 256 colours, so it stays full colour
                save_png(url, OUT / 'mipmap-xxxhdpi' / f"ic_l_{vid}{suffix}.png", palette=not vector and vid != 'tide')
            if vector:
                a, b, c = await page.evaluate('h => iconGradient(h)', v['hex'])
                (OUT / 'drawable' / f'ic_l_{vid}_bg.xml').write_text(VEC_BG.format(a=argb(a), b=argb(b), c=argb(c)), encoding='utf-8')
                bg, fg = f'@drawable/ic_l_{vid}_bg', '@drawable/ic_l_bside_fg'
            else:
                bg, fg = f'@mipmap/ic_l_{vid}_bg', f'@mipmap/ic_l_{vid}_fg'
            (OUT / 'mipmap-anydpi-v26' / f'ic_l_{vid}.xml').write_text(ADAPTIVE.format(bg=bg, fg=fg), encoding='utf-8')
        await browser.close()
    total = sum(f.stat().st_size for f in OUT.rglob('*.png'))
    print(f'{len(variants)} icon variants, {total / 1024:.0f} KB of PNG')


asyncio.run(main())
