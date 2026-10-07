#!/usr/bin/env python3
"""Regenerate PureBrowser launcher icons from the approved logo direction B
("下载即主角" — download arrow with white play cutout, browser-tab step, baseline).

Source of truth: docs/design/logo/B-download-video-source.png (the approved
generated mark). The coverage/mask extraction below is lifted verbatim from
artifacts/ui-exploration/logo-video-focus-v2/build_comparison.py so this repo
copy reproduces the same shape the approved presentation was built from.

Outputs (idempotent, run from anywhere):
  app/src/main/res/drawable-{mdpi..xxxhdpi}/ic_launcher_foreground.png  108dp*d, transparent, white mark inside 66dp safe circle
  app/src/main/res/drawable-{mdpi..xxxhdpi}/ic_launcher_monochrome.png  same art (Android 13 themed icon layer)
  app/src/main/res/drawable/ic_launcher_background.xml                 solid #356DE8 shape
  app/src/main/res/mipmap-anydpi-v26/ic_launcher{,_round}.xml          adaptive refs (bg/fg/monochrome)
  app/src/main/res/mipmap-{mdpi..xxxhdpi}/ic_launcher.png              48dp*d rounded square, blue bg + white mark
  app/src/main/res/mipmap-{mdpi..xxxhdpi}/ic_launcher_round.png        48dp*d circle, blue bg + white mark
  docs/design/logo/icon.png                                           512px README/marketing icon
  docs/design/logo/B-download-video-mask.png                           extracted mask (reference)

Regenerate with:  python3 docs/design/logo/generate_launcher_icons.py
"""
from pathlib import Path
from PIL import Image, ImageChops, ImageDraw

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
RES = ROOT / 'app/src/main/res'
ARTIFACTS = ROOT / 'artifacts/ui-exploration/logo-video-focus-v2'

BLUE = (53, 109, 232, 255)  # #356DE8 from the approved design
WHITE = (255, 255, 255, 255)

# Adaptive icon geometry: 108dp canvas, guaranteed-visible safe zone = 66dp circle.
CANVAS_DP = 108
SAFE_DP = 66
LEGACY_DP = 48
DENSITIES = [('mdpi', 1.0), ('hdpi', 1.5), ('xhdpi', 2.0), ('xxhdpi', 3.0), ('xxxhdpi', 4.0)]


def extract_mask(source: Image.Image) -> Image.Image:
    """Verbatim coverage/threshold logic from build_comparison.py."""
    alpha = source.getchannel('A')
    if alpha.getextrema()[0] < 255:
        coverage = alpha.point(lambda p: 255 if p >= 230 else (0 if p < 8 else p))
    else:
        r, g, b = source.convert('RGB').split()
        minimum = ImageChops.darker(ImageChops.darker(r, g), b)
        coverage = minimum.point(lambda p: max(0, min(255, round((255 - p) * 255 / 202))))
    cutoff = coverage.point(lambda p: 255 if p > 28 else 0)
    box = cutoff.getbbox()
    assert box is not None, 'logo mark not found in source image'
    x0, y0, x1, y1 = box
    box = (max(0, x0 - 4), max(0, y0 - 4), min(source.width, x1 + 4), min(source.height, y1 + 4))
    return coverage.crop(box)


def max_radius(mask: Image.Image) -> float:
    """Largest distance from mask center to any filled (>28) pixel."""
    cx, cy = (mask.width - 1) / 2, (mask.height - 1) / 2
    far = 0.0
    px = mask.load()
    step = 2  # sample every 2nd pixel; edges are broad so no radius is missed
    for y in range(0, mask.height, step):
        for x in range(0, mask.width, step):
            if px[x, y] > 28:
                d = ((x - cx) ** 2 + (y - cy) ** 2) ** 0.5
                if d > far:
                    far = d
    return far + step  # compensate for skipped pixels between samples


def mark_layer(sized_mask: Image.Image, color=WHITE) -> Image.Image:
    layer = Image.new('RGBA', sized_mask.size, color)
    layer.putalpha(sized_mask)
    return layer


def compose_foreground(mask: Image.Image, canvas_px: int, r_max: float) -> Image.Image:
    """Transparent canvas, white mark fully inside the 66dp safe circle."""
    canvas = Image.new('RGBA', (canvas_px, canvas_px), (0, 0, 0, 0))
    budget = (SAFE_DP / CANVAS_DP) * canvas_px / 2  # safe-circle radius in px
    scale = budget / r_max
    mw = max(1, round(mask.width * scale))
    mh = max(1, round(mask.height * scale))
    mark = mark_layer(mask.resize((mw, mh), Image.Resampling.LANCZOS))
    canvas.alpha_composite(mark, ((canvas_px - mw) // 2, (canvas_px - mh) // 2))
    return canvas


def compose_legacy(mask: Image.Image, canvas_px: int, circle: bool) -> Image.Image:
    """Full-bleed blue icon (rounded square or circle) with centered white mark."""
    img = Image.new('RGBA', (canvas_px, canvas_px), BLUE)
    mh = round(canvas_px * 0.60)
    mw = max(1, round(mask.width * mh / mask.height))
    if mw > canvas_px * 0.72:  # never let the mark kiss the edges
        mw = round(canvas_px * 0.72)
        mh = round(mask.height * mw / mask.width)
    mark = mark_layer(mask.resize((mw, mh), Image.Resampling.LANCZOS))
    img.alpha_composite(mark, ((canvas_px - mw) // 2, (canvas_px - mh) // 2))
    shape = Image.new('L', (canvas_px, canvas_px), 0)
    d = ImageDraw.Draw(shape)
    if circle:
        d.ellipse((0, 0, canvas_px - 1, canvas_px - 1), fill=255)
    else:
        d.rounded_rectangle((0, 0, canvas_px - 1, canvas_px - 1),
                            radius=round(canvas_px * 0.16), fill=255)
    out = Image.new('RGBA', (canvas_px, canvas_px), (0, 0, 0, 0))
    out.paste(img, (0, 0), shape)
    return out


BACKGROUND_XML = '''<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by docs/design/logo/generate_launcher_icons.py — approved logo direction B. -->
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="#356DE8" />
</shape>
'''

ADAPTIVE_XML = '''<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by docs/design/logo/generate_launcher_icons.py — approved logo direction B. -->
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
'''


def assert_safe_zone(img: Image.Image, label: str) -> None:
    """Every opaque pixel must sit inside the centered 66dp safe circle."""
    n = img.width
    budget = (SAFE_DP / CANVAS_DP) * n / 2 + 1.0  # +1px AA tolerance
    cx = cy = (n - 1) / 2
    alpha = img.getchannel('A')
    cutoff = alpha.point(lambda p: 255 if p > 28 else 0)
    assert cutoff.getbbox() is not None, f'{label}: empty foreground'
    for y in range(0, n, 2):
        for x in range(0, n, 2):
            if alpha.getpixel((x, y)) > 28:
                assert ((x - cx) ** 2 + (y - cy) ** 2) ** 0.5 <= budget, f'{label}: pixel outside safe circle at {x},{y}'


def verify(paths_sizes: dict) -> None:
    for path, expected in sorted(paths_sizes.items()):
        img = Image.open(path)
        assert img.size == (expected, expected), f'{path}: {img.size} != {expected}px'
        assert img.mode == 'RGBA', f'{path}: mode {img.mode}'
    print(f'PASS: {len(paths_sizes)} PNGs verified (size/mode/alpha)')


def main() -> None:
    src_path = HERE / 'B-download-video-source.png'
    if not src_path.exists():
        src_path = ARTIFACTS / 'B-download-video-source.png'
    source = Image.open(src_path).convert('RGBA')
    mask = extract_mask(source)
    mask.save(HERE / 'B-download-video-mask.png')
    reference = ARTIFACTS / 'B-download-video-mask.png'
    if reference.exists():
        ref = Image.open(reference)
        assert abs(ref.width - mask.width) <= 8 and abs(ref.height - mask.height) <= 8, \
            f'mask deviates from approved exploration mask: {mask.size} vs {ref.size}'
    r_max = max_radius(mask)
    print(f'mask {mask.size}px, max radius {r_max:.1f}px (source: {src_path.name})')

    produced = {}

    # Adaptive foreground + monochrome layers at every density.
    for bucket, dpi in DENSITIES:
        n = round(CANVAS_DP * dpi)
        for name in ('ic_launcher_foreground', 'ic_launcher_monochrome'):
            out = RES / f'drawable-{bucket}' / f'{name}.png'
            out.parent.mkdir(parents=True, exist_ok=True)
            img = compose_foreground(mask, n, r_max)
            assert_safe_zone(img, out.name)
            img.save(out)
            produced[out] = n

    # Legacy launcher PNGs (and README icon reuse the legacy composition).
    for bucket, dpi in DENSITIES:
        n = round(LEGACY_DP * dpi)
        d = RES / f'mipmap-{bucket}'
        d.mkdir(parents=True, exist_ok=True)
        square = compose_legacy(mask, n, circle=False)
        square.save(d / 'ic_launcher.png')
        produced[d / 'ic_launcher.png'] = n
        round_icon = compose_legacy(mask, n, circle=True)
        round_icon.save(d / 'ic_launcher_round.png')
        produced[d / 'ic_launcher_round.png'] = n
        for stale in ('ic_launcher.webp', 'ic_launcher_round.webp'):
            (d / stale).unlink(missing_ok=True)

    icon = compose_legacy(mask, 512, circle=False)
    icon.save(HERE / 'icon.png')
    produced[HERE / 'icon.png'] = 512

    # Replace the template vector foreground/background/adaptive XMLs.
    (RES / 'drawable/ic_launcher_foreground.xml').unlink(missing_ok=True)
    (RES / 'drawable/ic_launcher_background.xml').write_text(BACKGROUND_XML)
    for name in ('ic_launcher.xml', 'ic_launcher_round.xml'):
        (RES / 'mipmap-anydpi-v26' / name).write_text(ADAPTIVE_XML)

    # Legacy blue must be the exact brand blue; check a definitely-interior bg pixel.
    for bucket, dpi in DENSITIES:
        n = round(LEGACY_DP * dpi)
        img = Image.open(RES / f'mipmap-{bucket}' / 'ic_launcher.png').convert('RGBA')
        px = img.getpixel((n // 2, max(1, round(n * 0.06))))
        assert all(abs(a - b) <= 2 for a, b in zip(px, BLUE)), f'{bucket} bg {px} != {BLUE}'

    verify(produced)
    print('Layers: adaptive fg/monochrome @drawable, bg #356DE8 shape, legacy square+round @mipmap')


if __name__ == '__main__':
    main()
