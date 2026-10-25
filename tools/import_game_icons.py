# -*- coding: utf-8 -*-
"""One-time import of game-icons.net icons into the mod's GUI textures.

    git clone https://github.com/game-icons/icons.git <somewhere outside the repo>
    git -C <somewhere> checkout 82d948812bfe3f269ef8f731dcdb07b08160edc4
    python tools/import_game_icons.py <somewhere>

Reads tools/icon_map.json (concept -> author/name), rasterizes each SVG path with Pillow (needs `svgelements`),
tints it to the GUI palette over a dark outline, and writes
    src/main/resources/assets/dndsheets/textures/gui/icons/<category>/<name>.png   (32x32)
plus the attribution file
    src/main/resources/assets/dndsheets/CREDITS.txt
that the self-test `checkAssetCredits` requires to list every icon.

The PNGs are committed; the build never runs this and never downloads anything (same rule as the other tools/).
Licence: game-icons.net icons are CC BY 3.0 ("Icons made by <author>"), except Zeromancer and Viscious Speed (CC0).
"""
import io
import json
import os
import re
import subprocess
import sys

import numpy as np
from PIL import Image, ImageChops, ImageDraw, ImageFilter
from svgelements import Path

PINNED_COMMIT = '82d948812bfe3f269ef8f731dcdb07b08160edc4'
SIZE = 32
SUPER = 8                       # supersampling factor
BRASS = (0xE9, 0xD8, 0xB4)      # GuiStyle title colour: reads on both the dark panel and the parchment
OUTLINE = (0x2A, 0x21, 0x18, 255)

AUTHORS = {  # folder in the repo -> (display name, licence)
    'lorc': ('Lorc', 'CC BY 3.0'),
    'delapouite': ('Delapouite', 'CC BY 3.0'),
    'skoll': ('Skoll', 'CC BY 3.0'),
    'sbed': ('Sbed', 'CC BY 3.0'),
    'felbrigg': ('Felbrigg', 'CC BY 3.0'),
    'zeromancer': ('Zeromancer', 'CC0'),
    'viscious-speed': ('Viscious Speed', 'CC0'),
}

EXTRA_CREDITS = [  # assets that are not icons but live in the same list
    'font/cinzel.ttf | Cinzel, google/fonts ofl/cinzel (OFL text: font/ofl-cinzel.txt) | The Cinzel Project Authors (NDISCOVER) | SIL OFL 1.1 | https://github.com/NDISCOVER/Cinzel',
]

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
OUT = os.path.join(ROOT, 'src', 'main', 'resources', 'assets', 'dndsheets')


def icon_path_d(svg_text):
    """The icon is the path with fill="#fff"; the first path is the black background square."""
    for m in re.finditer(r'<path\b([^>]*)/?>', svg_text):
        attrs = m.group(1)
        if 'fill="#fff"' in attrs:
            d = re.search(r'\bd="([^"]+)"', attrs)
            if d:
                return d.group(1)
    raise ValueError('no white path found')


def rasterize(d):
    """Even-odd fill of every subpath at SUPER*SIZE, then downsample: holes (opposite winding) cut out correctly."""
    big = SIZE * SUPER
    scale = big / 512.0
    path = Path(d)
    mask = np.zeros((big, big), dtype=bool)
    for sub in path.as_subpaths():
        pts = [(p.x * scale, p.y * scale) for p in Path(*sub).npoint(np.linspace(0, 1, 400))] \
            if False else None
        points = []
        for seg in sub:
            n = 1 if seg.__class__.__name__ == 'Line' else 24
            for t in np.linspace(0, 1, n, endpoint=False):
                p = seg.point(t)
                points.append((p.x * scale, p.y * scale))
        if len(points) < 3:
            continue
        layer = Image.new('1', (big, big), 0)
        ImageDraw.Draw(layer).polygon(points, fill=1)
        mask ^= np.array(layer, dtype=bool)
    alpha = Image.fromarray((mask * 255).astype('uint8'), 'L')
    return alpha.resize((SIZE, SIZE), Image.LANCZOS)


def compose(alpha):
    """Tinted glyph over a 1px dark outline so it stays readable on any panel."""
    glyph = Image.new('RGBA', (SIZE, SIZE), BRASS + (0,))
    glyph.putalpha(alpha)
    halo = alpha.filter(ImageFilter.MaxFilter(3))
    shadow = Image.new('RGBA', (SIZE, SIZE), OUTLINE)
    shadow.putalpha(halo)
    out = Image.new('RGBA', (SIZE, SIZE), (0, 0, 0, 0))
    out.alpha_composite(shadow)
    out.alpha_composite(glyph)
    return out


def main(repo):
    head = subprocess.run(['git', '-C', repo, 'rev-parse', 'HEAD'], capture_output=True, text=True).stdout.strip()
    if head != PINNED_COMMIT:
        print('WARNING: checkout is %s, the pinned commit is %s' % (head, PINNED_COMMIT))
    with io.open(os.path.join(os.path.dirname(os.path.abspath(__file__)), 'icon_map.json'), encoding='utf-8') as f:
        mapping = {k: v for k, v in json.load(f).items() if not k.startswith('_')}

    lines = []
    for key in sorted(mapping):
        source = mapping[key]
        folder, name = source.split('/')
        svg = os.path.join(repo, folder, name + '.svg')
        with io.open(svg, encoding='utf-8') as f:
            image = compose(rasterize(icon_path_d(f.read())))
        target = os.path.join(OUT, 'textures', 'gui', 'icons', key + '.png')
        os.makedirs(os.path.dirname(target), exist_ok=True)
        image.save(target)
        author, licence = AUTHORS[folder]
        lines.append('textures/gui/icons/%s.png | game-icons.net %s/%s.svg | %s | %s | https://game-icons.net/' % (key, folder, name, author, licence))
        print('ok', key)

    header = [
        '# Attribution for third-party assets shipped in this jar. One line per file:',
        '#   path | source | author | licence | url',
        '# game-icons.net icons: "Icons made by <author>" (CC BY 3.0 unless the licence column says CC0).',
        '# Tinted and re-rasterized to 32x32 by tools/import_game_icons.py from commit ' + PINNED_COMMIT + '.',
    ]
    with io.open(os.path.join(OUT, 'CREDITS.txt'), 'w', encoding='utf-8', newline='\n') as f:
        f.write('\n'.join(header + lines + EXTRA_CREDITS) + '\n')
    print('%d icons' % len(lines))


if __name__ == '__main__':
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])
