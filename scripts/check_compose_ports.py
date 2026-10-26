#!/usr/bin/env python3
"""Validate the local Compose stack's network exposure.

The development stack carries a development login that mints sessions for
arbitrary emails. It may only be reachable from the machine itself, so every
published port must bind 127.0.0.1 (or ::1). A mapping like "8080:8080"
binds 0.0.0.0 and must fail this check.
"""

from __future__ import annotations

import sys
from pathlib import Path

import yaml

COMPOSE_FILE = Path(__file__).resolve().parent.parent / "infra" / "docker" / "docker-compose.yml"
LOOPBACK = {"127.0.0.1", "::1"}


def host_ip(mapping: object) -> str | None:
    """Return the host IP of a Compose port mapping, or None for all interfaces."""
    if isinstance(mapping, dict):
        return mapping.get("host_ip")
    text = str(mapping)
    # Long-form short syntax: "[::1]:8080:8080" (IPv6 is bracketed).
    if text.startswith("["):
        return text[1 : text.index("]")]
    parts = text.split(":")
    # "8080:8080" has no host IP; "127.0.0.1:8080:8080" has one.
    if len(parts) >= 3:
        return parts[0]
    return None


def main() -> int:
    with COMPOSE_FILE.open(encoding="utf-8") as handle:
        compose = yaml.safe_load(handle)

    errors: list[str] = []
    published = 0
    for service, spec in (compose.get("services") or {}).items():
        for mapping in (spec or {}).get("ports", []) or []:
            published += 1
            address = host_ip(mapping)
            if address not in LOOPBACK:
                where = address or "all interfaces"
                errors.append(
                    f"{service}: port mapping {mapping!r} is published on {where}; "
                    "bind 127.0.0.1 instead"
                )

    if errors:
        print("Compose exposure check failed:", file=sys.stderr)
        for error in errors:
            print(f"  - {error}", file=sys.stderr)
        return 1

    print(f"Compose exposure OK: {published} published port(s), all loopback-only.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
