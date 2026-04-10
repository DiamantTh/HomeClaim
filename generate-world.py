#!/usr/bin/env python3
"""
Deprecated wrapper for HomeClaim world setup.

World creation/configuration is now handled in-game via `/homeclaim setup`.
"""

import sys


def main() -> int:
    print("⚠️  Der CLI-World-Setup wurde entfernt.")
    print("   Bitte nutze den Ingame-Setup über: /homeclaim setup")
    print("   Paper: neue Plot-Welt + FAWE-Konvertierung möglich")
    print("   Folia: neue Plot-Welt unterstützt, Konvertierung bestehender Welten deaktiviert")
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
