#!/usr/bin/env python3
"""Dependabot config guard.

The dependency bot is part of the software supply chain, so a config typo must
not land on main unseen: Dependabot silently ignores an unknown ecosystem or a
misspelled option, which turns it into a quiet behaviour change — an update
stream that stops running without anybody noticing.

This guard validates .github/dependabot.yml against the official Dependabot
JSON schema bundled at scripts/dependabot-schema.json, plus the repository
invariants (every update targets an existing directory, and the ecosystems the
repository actually uses are covered).

The schema coverage is deliberately a subset rather than a full JSON Schema
implementation: option names at any depth, the enumerations that decide
whether an update stream runs at all, and the required fields. oneOf/anyOf
unions are not resolved.

Refresh the bundled schema when Dependabot publishes a new one:

    curl -fsSL https://json.schemastore.org/dependabot-2.0.json \\
      -o scripts/dependabot-schema.json
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parent.parent
CONFIG_FILE = ROOT / ".github" / "dependabot.yml"
SCHEMA_FILE = ROOT / "scripts" / "dependabot-schema.json"

# The ecosystems this repository actually has. A missing one means an update
# stream that never runs, which is exactly the silent failure this guard
# exists to catch.
EXPECTED_ECOSYSTEMS = {"gomod", "gradle", "github-actions", "docker-compose", "docker"}


def load_yaml(path: Path) -> Any:
    """Parses a YAML file, returning None when PyYAML is unavailable."""
    try:
        import yaml
    except ImportError:
        return None
    with path.open(encoding="utf-8") as handle:
        try:
            return yaml.safe_load(handle)
        except yaml.YAMLError as error:
            raise ValueError(f"{path.name} is not valid YAML: {error}") from error


def resolve_ref(schema: dict, node: Any) -> Any:
    """Follows a local $ref chain until it reaches a concrete node."""
    seen: set[str] = set()
    while isinstance(node, dict) and "$ref" in node:
        ref = node["$ref"]
        if ref in seen:
            return None
        seen.add(ref)
        if not ref.startswith("#/"):
            return None
        target: Any = schema
        for part in ref[2:].split("/"):
            if not isinstance(target, dict) or part not in target:
                return None
            target = target[part]
        node = target
    return node


def enum_values(schema: dict, node: Any) -> list[str] | None:
    """Returns the enum of a node, resolving $ref, array items and anyOf."""
    node = resolve_ref(schema, node)
    if not isinstance(node, dict):
        return None
    if "enum" in node:
        return node["enum"]
    # An array of strings carries its enumeration on the items schema.
    items = node.get("items")
    if isinstance(items, dict) and "enum" in items:
        return items["enum"]
    for variant in node.get("anyOf", []):
        resolved = resolve_ref(schema, variant)
        if isinstance(resolved, dict) and "enum" in resolved:
            return resolved["enum"]
    return None


def check_known_keys(schema: dict, node: dict, allowed: set[str], where: str, violations: list[str]) -> None:
    """Rejects options that the schema does not define."""
    for key in node:
        if key not in allowed:
            violations.append(f"{where}: unknown option '{key}'")


def check_update(schema: dict, update: dict, index: int, violations: list[str]) -> None:
    """Validates a single entry of the updates list."""
    where = f"updates[{index}]"

    if not isinstance(update, dict):
        violations.append(f"{where}: expected a mapping")
        return

    update_schema = schema["definitions"]["update"]
    check_known_keys(schema, update, set(update_schema["properties"]), where, violations)

    for required in update_schema.get("required", []):
        if required not in update:
            violations.append(f"{where}: missing required option '{required}'")

    ecosystems = enum_values(schema, update_schema["properties"]["package-ecosystem"])
    ecosystem = update.get("package-ecosystem")
    if ecosystem is not None and ecosystems is not None and ecosystem not in ecosystems:
        violations.append(f"{where}: unknown package-ecosystem '{ecosystem}'")

    directory = update.get("directory")
    if directory is not None:
        if not isinstance(directory, str):
            violations.append(f"{where}: 'directory' must be a string")
        elif not directory.startswith("/"):
            violations.append(f"{where}: 'directory' must start with '/'")
        elif not (ROOT / directory.lstrip("/")).is_dir():
            violations.append(f"{where}: directory '{directory}' does not exist")

    schedule = update.get("schedule")
    if schedule is not None:
        if not isinstance(schedule, dict):
            violations.append(f"{where}: 'schedule' must be a mapping")
        else:
            schedule_schema = update_schema["properties"]["schedule"]
            check_known_keys(schema, schedule, set(schedule_schema["properties"]), f"{where}.schedule", violations)
            for required in schedule_schema.get("required", []):
                if required not in schedule:
                    violations.append(f"{where}.schedule: missing required option '{required}'")

            for option in ("interval", "day"):
                if option not in schedule:
                    continue
                allowed = enum_values(schema, schedule_schema["properties"][option])
                if allowed is not None and schedule[option] not in allowed:
                    violations.append(f"{where}.schedule: unknown {option} '{schedule[option]}'")

            time = schedule.get("time")
            if time is not None and not re.match(r"^([01][0-9]|2[0-3]):[0-5][0-9]$", str(time)):
                violations.append(f"{where}.schedule: 'time' must be formatted as hh:mm")

    commit_message = update.get("commit-message")
    if commit_message is not None:
        if not isinstance(commit_message, dict):
            violations.append(f"{where}: 'commit-message' must be a mapping")
        else:
            prefix = commit_message.get("prefix")
            if prefix is not None and (not isinstance(prefix, str) or len(prefix) > 50):
                violations.append(f"{where}.commit-message: 'prefix' must be a string of at most 50 characters")

    groups = update.get("groups")
    if groups is not None:
        if not isinstance(groups, dict):
            violations.append(f"{where}: 'groups' must be a mapping")
        else:
            group_schema = update_schema["properties"]["groups"]["additionalProperties"]
            for name, group in groups.items():
                if not isinstance(group, dict):
                    violations.append(f"{where}.groups.{name}: expected a mapping")
                    continue
                check_known_keys(schema, group, set(group_schema["properties"]), f"{where}.groups.{name}", violations)
                patterns = group.get("patterns")
                if patterns is not None and (
                    not isinstance(patterns, list) or not all(isinstance(item, str) for item in patterns)
                ):
                    violations.append(f"{where}.groups.{name}: 'patterns' must be a list of strings")
                check_update_types(
                    schema,
                    group_schema["properties"].get("update-types"),
                    group.get("update-types"),
                    f"{where}.groups.{name}",
                    violations,
                )

    for option in ("ignore", "allow"):
        entries = update.get(option)
        if entries is None:
            continue
        if not isinstance(entries, list):
            violations.append(f"{where}: '{option}' must be a list")
            continue
        entry_schema = update_schema["properties"][option]["items"]
        for position, entry in enumerate(entries):
            if not isinstance(entry, dict):
                violations.append(f"{where}.{option}[{position}]: expected a mapping")
                continue
            check_known_keys(schema, entry, set(entry_schema["properties"]), f"{where}.{option}[{position}]", violations)
            check_update_types(
                schema,
                entry_schema["properties"].get("update-types"),
                entry.get("update-types"),
                f"{where}.{option}[{position}]",
                violations,
            )


def check_update_types(schema: dict, node: Any, update_types: Any, where: str, violations: list[str]) -> None:
    """Validates a list of update-type identifiers against its schema node.

    The schema uses two different vocabularies: groups spell the levels
    `major`/`minor`/`patch`, while ignore and allow spell them
    `version-update:semver-*`. The allowed values therefore come from the
    schema node of the option being checked, not from a shared list.
    """
    if update_types is None:
        return
    if not isinstance(update_types, list) or not update_types:
        violations.append(f"{where}: 'update-types' must be a non-empty list")
        return
    allowed = enum_values(schema, node)
    for update_type in update_types:
        if allowed is not None and update_type not in allowed:
            violations.append(f"{where}: unknown update-type '{update_type}'")


def run_checks(project_root: Path) -> list[str]:
    """Returns the list of violations found in the Dependabot config."""
    violations: list[str] = []

    schema = json.loads(SCHEMA_FILE.read_text(encoding="utf-8"))
    try:
        config = load_yaml(CONFIG_FILE)
    except ValueError as error:
        return [str(error)]
    if config is None:
        return [
            "PyYAML is required to validate the Dependabot config. "
            "Install it with 'python3 -m pip install pyyaml'."
        ]

    if not isinstance(config, dict):
        return ["dependabot.yml: expected a mapping at the top level"]

    check_known_keys(schema, config, set(schema["properties"]), "dependabot.yml", violations)

    for required in schema.get("required", []):
        if required not in config:
            violations.append(f"dependabot.yml: missing required option '{required}'")

    if config.get("version") != 2:
        violations.append(f"dependabot.yml: version must be 2, got {config.get('version')!r}")

    updates = config.get("updates")
    if not isinstance(updates, list) or not updates:
        return violations + ["dependabot.yml: 'updates' must be a non-empty list"]

    seen: set[str] = set()
    for index, update in enumerate(updates):
        check_update(schema, update, index, violations)
        if isinstance(update, dict) and isinstance(update.get("package-ecosystem"), str):
            seen.add(update["package-ecosystem"])

    missing = EXPECTED_ECOSYSTEMS - seen
    if missing:
        violations.append(
            "dependabot.yml: no update configured for the ecosystem(s): " + ", ".join(sorted(missing))
        )

    return violations


def main() -> int:
    violations = run_checks(ROOT)
    if violations:
        sys.stderr.write("Dependabot config check failed:\n")
        for violation in violations:
            sys.stderr.write(f"- {violation}\n")
        return 1
    sys.stdout.write("Dependabot config check passed.\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
