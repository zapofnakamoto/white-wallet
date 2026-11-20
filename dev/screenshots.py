#!/usr/bin/env python3
"""What a change did to the screens: compares a brand's rendered previews with an earlier render.

Standard library only (docs/DEVELOPMENT.md, "Screenshots"). <out> is screenshots/build/screenshots/<set>/<brand>/.
--render files a new render there: it becomes current/ and the last one previous/, unless it shows the same. Then this
compares current/ with previous/ (or with --previous, in CI the render of the push before, from the cache) and writes
a picture of what differs for each changed image into <out>/diff/, the changed names into <out>/changed.txt and
everything into <out>/index.html. A comparison never fails: it shows.
"""

from __future__ import annotations

import argparse
import html
import os
import shutil
import struct
import sys
import zlib
from pathlib import Path

# A pixel that differs, in the picture of what changed; the rest is the new image, faded.
CHANGED = (255, 0, 0, 255)
FADE = bytes(v // 3 + 170 for v in range(256))
SIGNATURE = b"\x89PNG\r\n\x1a\n"


def read_png(data: bytes) -> tuple[int, int, list[bytes]]:
    """An 8-bit RGB or RGBA PNG (what Android writes) as its width, height and rows of RGBA bytes."""
    if not data.startswith(SIGNATURE):
        raise ValueError("not a PNG")
    pos, idat, header = len(SIGNATURE), [], None
    while pos < len(data):
        length, kind = struct.unpack(">I4s", data[pos : pos + 8])
        body = data[pos + 8 : pos + 8 + length]
        pos += 12 + length
        if kind == b"IHDR":
            header = struct.unpack(">IIBBBBB", body)
        elif kind == b"IDAT":
            idat.append(body)
        elif kind == b"IEND":
            break
    width, height, depth, color, _, _, interlace = header
    if depth != 8 or color not in (2, 6) or interlace:
        raise ValueError(f"unsupported PNG (depth {depth}, color type {color}, interlace {interlace})")
    channels = 4 if color == 6 else 3
    stride = width * channels
    raw = zlib.decompress(b"".join(idat))
    rows, previous = [], bytearray(stride)
    for y in range(height):
        kind, line = raw[y * (stride + 1)], bytearray(raw[y * (stride + 1) + 1 : (y + 1) * (stride + 1)])
        unfilter(kind, line, previous, channels)
        rows.append(bytes(line) if channels == 4 else rgba(line))
        previous = line
    return width, height, rows


def unfilter(kind: int, line: bytearray, previous: bytearray, bpp: int):
    if kind == 0:
        return
    for i in range(len(line)):
        left = line[i - bpp] if i >= bpp else 0
        up = previous[i]
        if kind == 1:
            line[i] = (line[i] + left) & 0xFF
        elif kind == 2:
            line[i] = (line[i] + up) & 0xFF
        elif kind == 3:
            line[i] = (line[i] + (left + up) // 2) & 0xFF
        elif kind == 4:
            corner = previous[i - bpp] if i >= bpp else 0
            p = left + up - corner
            pa, pb, pc = abs(p - left), abs(p - up), abs(p - corner)
            line[i] = (line[i] + (left if pa <= pb and pa <= pc else up if pb <= pc else corner)) & 0xFF
        else:
            raise ValueError(f"unknown PNG filter {kind}")


def rgba(line: bytearray) -> bytes:
    out = bytearray(len(line) // 3 * 4)
    out[0::4], out[1::4], out[2::4], out[3::4] = line[0::3], line[1::3], line[2::3], b"\xff" * (len(line) // 3)
    return bytes(out)


def write_png(width: int, height: int, rows: list[bytes], level: int = 6) -> bytes:
    def chunk(kind: bytes, body: bytes) -> bytes:
        return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body))

    raw = b"".join(b"\x00" + row for row in rows)
    return SIGNATURE + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw, level)) + chunk(b"IEND", b"")


def diff_png(before: bytes, after: bytes) -> bytes | None:
    """The new image faded, with every pixel that differs in [CHANGED]; None when the sizes differ."""
    w1, h1, old = read_png(before)
    w2, h2, new = read_png(after)
    if (w1, h1) != (w2, h2):
        return None
    rows = []
    for a, b in zip(old, new):
        row = bytearray(b.translate(FADE))
        if a != b:
            for x in range(0, len(b), 4):
                if a[x : x + 4] != b[x : x + 4]:
                    row[x : x + 4] = bytes(CHANGED)
        rows.append(bytes(row))
    return write_png(w2, h2, rows)


def same_pixels(a: Path, b: Path) -> bool:
    old, new = a.read_bytes(), b.read_bytes()
    # The same pixels may be compressed differently on another machine: only the pixels count.
    return old == new or read_png(old) == read_png(new)


def file_render(render: Path, out: Path):
    """The render becomes current/ and the last one previous/, unless it shows the same: an up-to-date or cached render
    keeps the last change on the page."""
    current = out / "current"
    if current.is_dir():
        drawn = {p.name: p for p in render.glob("*.png")}
        kept = {p.name: p for p in current.glob("*.png")}
        if drawn.keys() == kept.keys() and all(same_pixels(kept[n], drawn[n]) for n in drawn):
            return
        shutil.rmtree(out / "previous", ignore_errors=True)
        current.rename(out / "previous")
    shutil.copytree(render, current)


def report(out: Path) -> str:
    current = out / "current"
    previous = out / "previous"
    now = {p.name: p for p in sorted(current.glob("*.png"))}
    before = {p.name: p for p in sorted(previous.glob("*.png"))} if previous.is_dir() else None
    diff_dir = out / "diff"
    shutil.rmtree(diff_dir, ignore_errors=True)
    changed, new, removed = [], [], []
    if before is not None:
        new = [n for n in now if n not in before]
        removed = [n for n in before if n not in now]
        for name in now:
            if name not in before:
                continue
            if not same_pixels(before[name], now[name]):
                changed.append(name)
                picture = diff_png(before[name].read_bytes(), now[name].read_bytes())
                if picture:
                    diff_dir.mkdir(exist_ok=True)
                    (diff_dir / name).write_bytes(picture)
    (out / "changed.txt").write_text("".join(f"{n}\n" for n in changed))
    summary = (
        f"{len(changed)} changed, {len(new)} new, {len(removed)} removed"
        if before is not None
        else "no earlier render to compare with"
    )
    (out / "index.html").write_text(page(out, now, before or {}, changed, new, removed, summary))
    return summary


def page(out: Path, now: dict, before: dict, changed: list, new: list, removed: list, summary: str) -> str:
    def src(path: Path) -> str:
        return html.escape(os.path.relpath(path, out))

    def figure(path: Path, caption: str) -> str:
        return f'<figure><a href="{src(path)}"><img src="{src(path)}" loading="lazy"></a><figcaption>{html.escape(caption)}</figcaption></figure>'

    brand = html.escape(out.name)
    parts = [
        f"<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width, initial-scale=1'><title>Screenshots: {brand}</title>",
        "<style>:root{color-scheme:light dark;font-family:system-ui,sans-serif}body{margin:16px}"
        "section{display:flex;flex-wrap:wrap;gap:12px}figure{margin:0;width:200px}img{width:100%;border:1px solid #8884}"
        "figcaption{font-size:12px;word-break:break-all}.row{display:flex;gap:12px;margin-bottom:16px}</style>",
        f"<h1>Screenshots: {brand}</h1><p>{html.escape(summary)}</p>",
    ]
    if changed:
        parts.append("<h2>Changed</h2>")
        for name in changed:
            pictures = [figure(before[name], f"before: {name}"), figure(now[name], f"after: {name}")]
            if (out / "diff" / name).exists():
                pictures.append(figure(out / "diff" / name, "what differs"))
            parts.append(f"<div class=row>{''.join(pictures)}</div>")
    if new:
        parts.append("<h2>New</h2><section>" + "".join(figure(now[n], n) for n in new) + "</section>")
    if removed:
        parts.append("<h2>Removed</h2><section>" + "".join(figure(before[n], n) for n in removed) + "</section>")
    screens: dict[str, list[str]] = {}
    for name in now:
        screens.setdefault(name.split(".")[0], []).append(name)
    for screen, names in screens.items():
        parts.append(f"<h2>{html.escape(screen)}</h2><section>" + "".join(figure(now[n], n.removeprefix(screen + ".")) for n in names) + "</section>")
    return "\n".join(parts) + "\n"


def main(argv: list[str]):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("out", type=Path, help="screenshots/build/screenshots/<set>/<brand>")
    parser.add_argument("--render", type=Path, help="a new render (screenshots/build/screenshots/render) to file under <out> first")
    parser.add_argument("--previous", type=Path, help="an earlier render to compare with (CI: from its cache), copied to <out>/previous")
    args = parser.parse_args(argv)
    if args.render:
        file_render(args.render, args.out)
    if args.previous:
        shutil.rmtree(args.out / "previous", ignore_errors=True)
        if args.previous.is_dir():
            shutil.copytree(args.previous, args.out / "previous")
    print(f"Screenshots, {args.out.name}: {report(args.out)}. file://{(args.out / 'index.html').resolve()}")


if __name__ == "__main__":
    main(sys.argv[1:])
