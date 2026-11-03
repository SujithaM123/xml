#!/usr/bin/env python3
"""Reconstruct a directory tree from a codebase packed by pack_codebase.py.

Accepts, transparently and in any combination:
  - a plain text file (codebase.txt)
  - a zip containing that text file (codebase.txt.zip)
  - either of those split into numbered byte parts (codebase.txt.zip.part001,
    .part002, ...) when the plain file isn't found; parts are auto-detected
    by globbing "<input>.part*" and concatenated in order before use.

Zip vs. plain text is auto-detected by file signature, not extension.
"""
import argparse
import base64
import glob
import io
import re
import zipfile
from pathlib import Path

LINE_RE = re.compile(r"^@@@=== FILE START(?P<mode> \(base64\))?: (?P<path>.*) ===$")
END_RE = re.compile(r"^@@@=== FILE END ===$")
ZIP_MAGIC = b"PK"


def is_safe_rel_path(rel_path: str) -> bool:
    p = Path(rel_path)
    if p.is_absolute():
        return False
    return ".." not in p.parts


def gather_bytes(input_path: Path) -> bytes:
    if input_path.is_file():
        return input_path.read_bytes()

    parts = sorted(glob.glob(f"{input_path}.part*"))
    if not parts:
        raise SystemExit(f"Not a file, and no parts found matching '{input_path}.part*': {input_path}")

    print(f"Reassembling {len(parts)} parts: {', '.join(Path(p).name for p in parts)}")
    data = bytearray()
    for part in parts:
        data += Path(part).read_bytes()
    return bytes(data)


def load_text(input_path: Path) -> str:
    data = gather_bytes(input_path)

    if data[:2] == ZIP_MAGIC:
        with zipfile.ZipFile(io.BytesIO(data)) as zf:
            names = zf.namelist()
            txt_names = [n for n in names if n.endswith(".txt")]
            name = txt_names[0] if txt_names else names[0]
            print(f"Unzipping '{name}' from archive")
            return zf.read(name).decode("utf-8")

    return data.decode("utf-8")


def unpack(input_file: Path, dest: Path) -> int:
    dest = dest.resolve()
    dest.mkdir(parents=True, exist_ok=True)

    text = load_text(input_file)
    lines = text.split("\n")

    count = 0
    i = 0
    n = len(lines)
    while i < n:
        m = LINE_RE.match(lines[i])
        if not m:
            i += 1
            continue

        is_b64 = bool(m.group("mode"))
        rel_path = m.group("path")
        i += 1

        content_lines = []
        while i < n and not END_RE.match(lines[i]):
            content_lines.append(lines[i])
            i += 1
        i += 1  # skip the END marker line

        if not is_safe_rel_path(rel_path):
            print(f"Skipping unsafe path: {rel_path}")
            continue

        target = dest / rel_path
        target.parent.mkdir(parents=True, exist_ok=True)

        if is_b64:
            b64_str = "".join(content_lines)
            target.write_bytes(base64.b64decode(b64_str))
        else:
            content = "\n".join(content_lines)
            target.write_text(content, encoding="utf-8", newline="\n")
        count += 1

    return count


def main():
    parser = argparse.ArgumentParser(description="Restore a folder from a packed codebase (.txt, .txt.zip, or split .part files of either).")
    parser.add_argument("input", nargs="?", default="codebase.txt", help="Packed codebase file, e.g. codebase.txt or codebase.txt.zip (default: codebase.txt)")
    parser.add_argument("dest", nargs="?", default="unpacked", help="Destination folder (default: unpacked)")
    args = parser.parse_args()

    count = unpack(Path(args.input), Path(args.dest))
    print(f"Unpacked {count} files into '{Path(args.dest).resolve()}'")


if __name__ == "__main__":
    main()
