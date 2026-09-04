#!/usr/bin/env python3
"""Pack PNGs into a Windows .ico, and check one back.

    python3 scripts/make-ico.py --out path/to/icon.ico 16.png 24.png ...
    python3 scripts/make-ico.py --check path/to/icon.ico

Standard library only: there is no Pillow on the development host (scripts/render-icons.swift,
which is what normally calls this). Every entry is stored PNG-compressed, which Windows has
accepted since Vista and which keeps the file a container rather than a second encoder.
"""

import argparse
import struct
import sys

PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
ICONDIR = struct.Struct("<HHH")  # reserved, type, count
ICONDIRENTRY = struct.Struct("<BBBBHHII")  # w, h, colours, reserved, planes, bpp, bytes, offset


def png_size(data, source):
    """The (width, height) in a PNG's IHDR, which must be its first chunk."""
    if not data.startswith(PNG_SIGNATURE):
        raise ValueError(f"{source}: not a PNG")
    length, kind = struct.unpack_from(">I4s", data, 8)
    if kind != b"IHDR" or length < 8:
        raise ValueError(f"{source}: first chunk is {kind!r}, not IHDR")
    return struct.unpack_from(">II", data, 16)


def build(out, sources):
    images = []
    for source in sources:
        with open(source, "rb") as handle:
            data = handle.read()
        width, height = png_size(data, source)
        if width != height:
            raise ValueError(f"{source}: {width}x{height} is not square")
        if not 1 <= width <= 256:
            raise ValueError(f"{source}: {width}px is outside the 1..256 an .ico can name")
        images.append((width, data))

    images.sort(key=lambda image: image[0])
    header = ICONDIR.pack(0, 1, len(images))
    offset = ICONDIR.size + ICONDIRENTRY.size * len(images)
    entries = bytearray()
    for width, data in images:
        # 256 is written as 0: the field is one byte wide.
        side = 0 if width == 256 else width
        entries += ICONDIRENTRY.pack(side, side, 0, 0, 1, 32, len(data), offset)
        offset += len(data)

    with open(out, "wb") as handle:
        handle.write(header)
        handle.write(entries)
        for _, data in images:
            handle.write(data)
    print(f"{out}: {len(images)} entries, {offset} bytes")


def check(path):
    with open(path, "rb") as handle:
        blob = handle.read()
    reserved, kind, count = ICONDIR.unpack_from(blob, 0)
    if reserved != 0 or kind != 1:
        raise ValueError(f"{path}: not an icon directory (reserved={reserved}, type={kind})")
    if count == 0:
        raise ValueError(f"{path}: no entries")
    print(f"{path}: {count} entries, {len(blob)} bytes")
    for index in range(count):
        width, height, _, _, planes, bpp, size, offset = ICONDIRENTRY.unpack_from(
            blob, ICONDIR.size + ICONDIRENTRY.size * index
        )
        named = 256 if width == 0 else width
        if offset + size > len(blob):
            raise ValueError(f"{path}: entry {index} runs past the end of the file")
        data = blob[offset:offset + size]
        actual = png_size(data, f"{path} entry {index}")
        if actual != (named, 256 if height == 0 else height):
            raise ValueError(f"{path}: entry {index} declares {named}px but holds {actual[0]}px")
        print(f"  {named:>3}x{named:<3} PNG  {planes} plane  {bpp}bpp  {size} bytes @ {offset}")


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", help="the .ico to write")
    parser.add_argument("--check", help="an .ico to parse back and describe")
    parser.add_argument("pngs", nargs="*", help="square PNGs, one per size")
    args = parser.parse_args(argv)

    if args.check:
        check(args.check)
        return 0
    if not args.out or not args.pngs:
        parser.error("--out needs at least one PNG")
    build(args.out, args.pngs)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
