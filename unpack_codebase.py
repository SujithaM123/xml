#!/usr/bin/env python3
"""Reconstruct a directory tree from a text file created by pack_codebase.py.

Also handles codebases split into numbered parts (e.g. codebase.txt.part001,
codebase.txt.part002, ...) by auto-detecting and concatenating them when the
plain input file isn't found.
"""
import argparse
import base64
import glob
import re
from pathlib import Path

LINE_RE = re.compile(r"^@@@=== FILE START(?P<mode> \(base64\))?: (?P<path>.*) ===$")
END_RE = re.compile(r"^@@@=== FILE END ===$")


def is_safe_rel_path(rel_path: str) -> bool:
    p = Path(rel_path)
    if p.is_absolute():
        return False
    return ".." not in p.parts


def load_text(input_path: Path) -> str:
    if input_path.is_file():
        return input_path.read_text(encoding="utf-8")

    parts = sorted(glob.glob(f"{input_path}.part*"))
    if not parts:
        raise SystemExit(f"Not a file, and no parts found matching '{input_path}.part*': {input_path}")

    print(f"Reassembling {len(parts)} parts: {', '.join(Path(p).name for p in parts)}")
    chunks = []
    for part in parts:
        chunks.append(Path(part).read_text(encoding="utf-8"))
    return "".join(chunks)


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
    parser = argparse.ArgumentParser(description="Unpack a text file back into a folder.")
    parser.add_argument("input", nargs="?", default="codebase.txt", help="Input text file (default: codebase.txt)")
    parser.add_argument("dest", nargs="?", default="unpacked", help="Destination folder (default: unpacked)")
    args = parser.parse_args()

    count = unpack(Path(args.input), Path(args.dest))
    print(f"Unpacked {count} files into '{Path(args.dest).resolve()}'")


if __name__ == "__main__":
    main()
