#!/usr/bin/env python3
"""Pack a directory tree into a single text file.

Each file becomes a marked block:

    @@@=== FILE START: relative/path.py ===
    <file content>
    @@@=== FILE END ===

Files that aren't valid UTF-8 text are stored base64-encoded instead:

    @@@=== FILE START (base64): relative/image.png ===
    <base64 data>
    @@@=== FILE END ===

Use unpack_codebase.py to reverse this.
"""
import argparse
import base64
from pathlib import Path

START_TEXT = "@@@=== FILE START: {path} ==="
START_B64 = "@@@=== FILE START (base64): {path} ==="
END_MARK = "@@@=== FILE END ==="

DEFAULT_EXCLUDES = {
    ".git", "node_modules", "__pycache__", ".venv", "venv",
    "dist", "build", ".idea", ".vscode", ".pytest_cache", ".mypy_cache",
}


def is_excluded(rel_parts, excludes):
    return any(part in excludes for part in rel_parts)


def pack(root: Path, output: Path, excludes: set) -> int:
    root = root.resolve()
    output_resolved = output.resolve()
    files = sorted(p for p in root.rglob("*") if p.is_file())

    count = 0
    with output.open("w", encoding="utf-8", newline="\n") as out:
        for f in files:
            if f.resolve() == output_resolved:
                continue
            rel = f.relative_to(root)
            if is_excluded(rel.parts, excludes):
                continue
            rel_str = rel.as_posix()

            try:
                with f.open("r", encoding="utf-8", newline="") as fh:
                    content = fh.read()
            except (UnicodeDecodeError, ValueError):
                data = f.read_bytes()
                b64 = base64.b64encode(data).decode("ascii")
                out.write(START_B64.format(path=rel_str) + "\n")
                out.write(b64 + "\n")
                out.write(END_MARK + "\n")
            else:
                out.write(START_TEXT.format(path=rel_str) + "\n")
                out.write(content)
                out.write("\n")  # separator before END marker (always added, stripped back out on unpack)
                out.write(END_MARK + "\n")
            count += 1
    return count


def main():
    parser = argparse.ArgumentParser(description="Pack a folder into one text file.")
    parser.add_argument("source", nargs="?", default=".", help="Folder to pack (default: current directory)")
    parser.add_argument("output", nargs="?", default="codebase.txt", help="Output text file (default: codebase.txt)")
    parser.add_argument("--exclude", action="append", default=[], help="Extra folder/file names to exclude (repeatable)")
    parser.add_argument("--all", action="store_true", help="Include everything, ignoring the default excludes (.git, node_modules, build/, etc.)")
    args = parser.parse_args()

    excludes = (set() if args.all else DEFAULT_EXCLUDES) | set(args.exclude)
    root = Path(args.source)
    output = Path(args.output)

    if not root.is_dir():
        raise SystemExit(f"Not a directory: {root}")

    count = pack(root, output, excludes)
    print(f"Packed {count} files from '{root.resolve()}' into '{output.resolve()}'")


if __name__ == "__main__":
    main()
