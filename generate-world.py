#!/usr/bin/env python3
"""
Repo-root wrapper for HomeClaim world generation.

Allows running the generator directly from the repository root:
    ./generate-world.py --name MyWorld
"""

from pathlib import Path
import os
import sys


def main() -> int:
    repo_root = Path(__file__).resolve().parent
    source_script = repo_root / "homeclaim-platform-paper" / "src" / "main" / "resources" / "scripts" / "generate-world.py"

    if not source_script.exists():
        print(f"Error: script not found: {source_script}", file=sys.stderr)
        return 1

    os.execv(sys.executable, [sys.executable, str(source_script), *sys.argv[1:]])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
