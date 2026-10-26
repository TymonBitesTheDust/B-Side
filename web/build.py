#!/usr/bin/env python3
"""Builds the single-file player: app.html + fonts.css + skins/*.css + skins.js + icons.js -> index.html.

Each game style lives in its own file under skins/ (every rule scoped to [data-skin="…"]); they are
inlined in a fixed order after the base styles."""
import pathlib

here = pathlib.Path(__file__).resolve().parent
SKINS = ['archive', 'riot', 'tide', 'frontier', 'neon']


def inline(text, marker, content):
    assert text.count(marker) == 1, f'{marker} marker missing'
    return text.replace(marker, content)


out = (here / 'app.html').read_text(encoding='utf-8')
out = inline(out, '/*@FONTS@*/', (here / 'fonts.css').read_text(encoding='utf-8'))
out = inline(out, '/*@SKINS@*/', '\n'.join((here / 'skins' / f'{k}.css').read_text(encoding='utf-8') for k in SKINS))
out = inline(out, '/*@SKINJS@*/', (here / 'skins.js').read_text(encoding='utf-8'))
out = inline(out, '/*@ICONS@*/', (here / 'icons.js').read_text(encoding='utf-8'))
(here / 'index.html').write_text(out, encoding='utf-8')
print(f'index.html  {len(out.encode()) / 1024:.0f} KB')
