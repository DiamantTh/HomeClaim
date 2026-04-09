#!/usr/bin/env python3
"""
Sort CHANGELOG.md entries in descending timestamp order.

Supports a --check mode to fail fast when the changelog is out of order.
Timestamp format per heading: YYYY-MM-DD:HH:MM:SS
"""

from __future__ import annotations

import argparse
import re
import sys
from datetime import datetime
from pathlib import Path

ENTRY_RE = re.compile(r"^## (\d{4}-\d{2}-\d{2}:\d{2}:\d{2}:\d{2})$")


def parse_entries(lines: list[str]) -> tuple[str, list[tuple[str, list[str]]]]:
    if not lines:
        raise SystemExit("CHANGELOG.md is empty")
    header = lines[0].rstrip("\n")
    entries: list[tuple[str, list[str]]] = []
    current_ts: str | None = None
    current_block: list[str] = []

    for raw_line in lines[1:]:
        line = raw_line.rstrip("\n")
        match = ENTRY_RE.match(line)
        if match:
            if current_ts is not None:
                entries.append((current_ts, current_block))
            current_ts = match.group(1)
            current_block = []
        else:
            if current_ts is not None:
                current_block.append(line)
    if current_ts is not None:
        entries.append((current_ts, current_block))
    return header, entries


def normalize_block(block: list[str]) -> list[str]:
    while block and block[0] == "":
        block = block[1:]
    while block and block[-1] == "":
        block = block[:-1]
    return block


def sort_entries(entries: list[tuple[str, list[str]]]) -> list[tuple[datetime, str, list[str]]]:
    parsed: list[tuple[datetime, str, list[str]]] = []
    for ts, block in entries:
        parsed.append((datetime.strptime(ts, "%Y-%m-%d:%H:%M:%S"), ts, normalize_block(block)))
    parsed.sort(key=lambda item: item[0], reverse=True)
    return parsed


def render(header: str, sorted_entries: list[tuple[datetime, str, list[str]]]) -> str:
    out: list[str] = [header, ""]
    for idx, (_, ts, block) in enumerate(sorted_entries):
        out.append(f"## {ts}")
        out.extend(block)
        if idx != len(sorted_entries) - 1:
            out.append("")
    out.append("")
    return "\n".join(out)


def main() -> None:
    parser = argparse.ArgumentParser(description="Sort CHANGELOG.md entries descending by timestamp.")
    parser.add_argument(
        "--file",
        default="CHANGELOG.md",
        help="Path to changelog (default: CHANGELOG.md)",
    )
    parser.add_argument(
        "--check",
        action="store_true",
        help="Only check order; exit non-zero if sorting is needed.",
    )
    args = parser.parse_args()

    changelog_path = Path(args.file)
    if not changelog_path.exists():
        raise SystemExit(f"File not found: {changelog_path}")

    lines = changelog_path.read_text(encoding="utf-8").splitlines()
    header, entries = parse_entries(lines)
    sorted_entries = sort_entries(entries)
    new_text = render(header, sorted_entries)

    if args.check:
        if "\n".join(lines) + "\n" != new_text:
            print(f"{changelog_path} is not sorted; run without --check to fix.")
            raise SystemExit(1)
        print(f"{changelog_path} is sorted.")
        return

    changelog_path.write_text(new_text, encoding="utf-8")
    print(f"Sorted {changelog_path}")


if __name__ == "__main__":
    main()
