#!/usr/bin/env python3
"""Verify Android UI/UX, accessibility, and 3-mode @Preview rules across Compose UI files."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ANDROID_MAIN = ROOT / "apps/android/app/src/main"
UI_DIR = ANDROID_MAIN / "java/com/anve/app/ui"
MAIN_ACTIVITY = ANDROID_MAIN / "java/com/anve/app/MainActivity.kt"
RES_DIR = ANDROID_MAIN / "res"

EXPECTED_PREVIEW_FILES = frozenset({
    "brand/AnVeIllustrations.kt",
    "circle/CircleScreen.kt",
    "circle/EmailInvitationSheet.kt",
    "circle/InvitationDialog.kt",
    "circle/ReceivedInvitations.kt",
    "common/ErrorNotice.kt",
    "common/StitchComponents.kt",
    "diagnostics/DiagnosticsScreen.kt",
    "history/HistoryScreen.kt",
    "home/HomeScreen.kt",
    "journey/JourneyOptionsSheet.kt",
    "journey/JourneyScreen.kt",
    "map/LiveMapScreen.kt",
    "onboarding/OnboardingScreen.kt",
    "places/PlacesScreen.kt",
    "signin/SignInScreen.kt",
    "sos/SosScreen.kt",
    "splash/SplashScreen.kt",
    "tracking/TrackingScreen.kt",
    "update/ForceUpdateDialog.kt",
    "avatar/AvatarSheet.kt",
})

HORIZONTAL_GRADIENT_ALLOWLIST = frozenset({
    "brand/AnVeIllustrations.kt",
})

BANNED_SLOP_PATTERN = re.compile(
    r"\b("
    r"elevate|unleash|seamless|next-gen|empower|unlock|game-changer|supercharge|"
    r"lorem\s+ipsum|sample\s+text|todo|"
    r"nâng\s+tầm|đột\s+phá|toàn\s+diện|siêu\s+việt|tối\s+ưu\s+hóa\s+vượt\s+trội"
    r")\b",
    re.IGNORECASE,
)

EMOJI_PATTERN = re.compile(r"[\U0001F300-\U0001FAFF\u2600-\u27BF]")
WILDCARD_IMPORT_RE = re.compile(r"^\s*import\s+[^\s]+\.\*\s*$", re.MULTILINE)
TYPO_DP_RE = re.compile(r"\b(?:fontSize|lineHeight|letterSpacing)\s*=\s*[^,\n)]*?\.dp\b")
INFINITE_TRANSITION_RE = re.compile(r"\brememberInfiniteTransition\s*\(")
BLUR_MODIFIER_RE = re.compile(r"\.blur\s*\(")
FORBIDDEN_BRUSH_RE = re.compile(
    r"\bBrush\.(linearGradient|verticalGradient|radialGradient|sweepGradient)\s*\("
)
HORIZONTAL_BRUSH_RE = re.compile(r"\bBrush\.horizontalGradient\s*\(")
SHADOW_ELEVATION_RE = re.compile(r"\bshadowElevation\s*=\s*(\d+(?:\.\d+)?)\.dp\b")
ALPHA_MODIFIER_RE = re.compile(r"\.alpha\s*\(")
STRING_LITERAL_RE = re.compile(r'"((?:\\.|[^"\\])*)"')
FIXED_HEIGHT_ARG_RE = re.compile(r"\.height\s*\(\s*\d+(?:\.\d+)?\.dp\s*\)")
FIXED_HEIGHT_COMPONENTS = (
    "Button",
    "OutlinedButton",
    "TextButton",
    "FilledTonalButton",
    "ElevatedButton",
    "Card",
    "OutlinedCard",
    "ElevatedCard",
    "Row",
)


@dataclass(frozen=True)
class Violation:
    """Single rule violation with path, optional line number, rule tag, and detail."""

    path: str
    line: int | None
    rule: str
    message: str

    def format(self) -> str:
        """Render the violation as a concise human-readable line."""
        location = f"{self.path}:{self.line}" if self.line is not None else self.path
        return f"[{self.rule}] {location}: {self.message}"


def strip_kotlin_comments(source: str) -> str:
    """Replace comments with newlines/spaces while preserving line numbers and string literals."""
    out: list[str] = []
    i = 0
    n = len(source)
    while i < n:
        ch = source[i]
        nxt = source[i + 1] if i + 1 < n else ""
        if ch == "/" and nxt == "/":
            while i < n and source[i] != "\n":
                out.append(" ")
                i += 1
        elif ch == "/" and nxt == "*":
            out.append("  ")
            i += 2
            while i < n and not (source[i] == "*" and i + 1 < n and source[i + 1] == "/"):
                out.append("\n" if source[i] == "\n" else " ")
                i += 1
            if i < n:
                out.append("  ")
                i += 2
        elif ch == '"' and source[i : i + 3] == '"""':
            out.append('"""')
            i += 3
            while i < n and source[i : i + 3] != '"""':
                out.append(source[i])
                i += 1
            if i < n:
                out.append('"""')
                i += 3
        elif ch == '"':
            out.append(ch)
            i += 1
            while i < n:
                c = source[i]
                out.append(c)
                i += 1
                if c == "\\" and i < n:
                    out.append(source[i])
                    i += 1
                elif c == '"':
                    break
        else:
            out.append(ch)
            i += 1
    return "".join(out)


def line_of_offset(text: str, offset: int) -> int:
    """Convert a zero-based character offset to a 1-based line number."""
    return text.count("\n", 0, offset) + 1


def extract_balanced_calls(text: str, callee: str) -> list[tuple[int, str]]:
    """Find balanced `<callee>(...)` argument substrings and their start offsets."""
    results: list[tuple[int, str]] = []
    pattern = re.compile(rf"\b{re.escape(callee)}\s*\(")
    for match in pattern.finditer(text):
        start = match.end()
        depth = 1
        i = start
        n = len(text)
        in_str = False
        while i < n and depth > 0:
            c = text[i]
            if c == '"' and (i == 0 or text[i - 1] != "\\"):
                in_str = not in_str
            elif not in_str:
                if c == "(":
                    depth += 1
                elif c == ")":
                    depth -= 1
            i += 1
        if depth == 0:
            results.append((match.start(), text[start : i - 1]))
    return results


def check_string_resources(res_dir: Path, repo_root: Path) -> list[Violation]:
    """Scan Android XML string resources for banned AI slop words and decorative emojis."""
    violations: list[Violation] = []
    if not res_dir.exists():
        return violations
    for xml_path in sorted(res_dir.glob("values*/strings.xml")):
        rel = str(xml_path.relative_to(repo_root))
        try:
            tree = ET.parse(xml_path)
        except ET.ParseError as exc:
            violations.append(Violation(rel, None, "XML", f"Invalid strings.xml: {exc}"))
            continue
        for elem in tree.getroot().iter():
            if elem.tag not in ("string", "item"):
                continue
            text = "".join(elem.itertext())
            name = elem.get("name", "<unnamed>")
            slop = BANNED_SLOP_PATTERN.search(text)
            if slop:
                violations.append(
                    Violation(rel, None, "AI Slop", f"Resource '{name}' contains banned copy {slop.group(0)!r}")
                )
            emoji = EMOJI_PATTERN.search(text)
            if emoji:
                violations.append(
                    Violation(rel, None, "AI Slop", f"Resource '{name}' contains decorative emoji {emoji.group(0)!r}")
                )
    return violations


def check_kotlin_ui_file(path: Path, rel_ui: str, rel_repo: str) -> list[Violation]:
    """Check one Kotlin UI source file against AI Slop, Play/Insets, Accessibility, and Preview rules."""
    raw = path.read_text(encoding="utf-8")
    code = strip_kotlin_comments(raw)
    violations: list[Violation] = []

    for m in WILDCARD_IMPORT_RE.finditer(code):
        violations.append(Violation(rel_repo, line_of_offset(code, m.start()), "Conventions", "Wildcard import forbidden"))

    for m in STRING_LITERAL_RE.finditer(code):
        literal = m.group(1)
        slop = BANNED_SLOP_PATTERN.search(literal)
        if slop:
            violations.append(
                Violation(rel_repo, line_of_offset(code, m.start()), "AI Slop", f"String contains banned copy {slop.group(0)!r}")
            )
        emoji = EMOJI_PATTERN.search(literal)
        if emoji:
            violations.append(
                Violation(rel_repo, line_of_offset(code, m.start()), "AI Slop", f"String contains decorative emoji {emoji.group(0)!r}")
            )

    for pattern, rule, message in (
        (TYPO_DP_RE, "Accessibility", "Typography spacing/size must use .sp, never .dp"),
        (INFINITE_TRANSITION_RE, "AI Slop", "Avoid rememberInfiniteTransition distracting animations"),
        (BLUR_MODIFIER_RE, "AI Slop", "Avoid Modifier.blur() visual slop"),
        (FORBIDDEN_BRUSH_RE, "AI Slop", "Avoid decorative gradient Brush calls"),
        (ALPHA_MODIFIER_RE, "Accessibility", "Avoid .alpha(...) dimming modifiers on UI components"),
    ):
        for m in pattern.finditer(code):
            violations.append(Violation(rel_repo, line_of_offset(code, m.start()), rule, message))

    for component in FIXED_HEIGHT_COMPONENTS:
        for offset, args in extract_balanced_calls(code, component):
            if FIXED_HEIGHT_ARG_RE.search(args):
                violations.append(
                    Violation(
                        rel_repo,
                        line_of_offset(code, offset),
                        "Accessibility",
                        f"Avoid fixed .height(<n>.dp) on {component}; use heightIn(min = ...)",
                    )
                )

    if rel_ui not in HORIZONTAL_GRADIENT_ALLOWLIST:
        for m in HORIZONTAL_BRUSH_RE.finditer(code):
            violations.append(
                Violation(rel_repo, line_of_offset(code, m.start()), "AI Slop", "Brush.horizontalGradient is allowlisted only for vector/raster illustrations")
            )

    for m in SHADOW_ELEVATION_RE.finditer(code):
        if float(m.group(1)) > 6.0:
            violations.append(
                Violation(rel_repo, line_of_offset(code, m.start()), "AI Slop", f"shadowElevation {m.group(1)}.dp exceeds 6.dp limit")
            )

    for callee in ("items", "itemsIndexed"):
        for offset, args in extract_balanced_calls(code, callee):
            if not re.search(r"\bkey\s*=", args):
                violations.append(
                    Violation(rel_repo, line_of_offset(code, offset), "Play/Performance", f"Lazy list {callee}(...) call must provide a stable key")
                )

    for offset, args in extract_balanced_calls(code, "Text"):
        if re.search(r"\bmaxLines\s*=\s*1\b", args) and "TextOverflow.Ellipsis" in args:
            violations.append(
                Violation(rel_repo, line_of_offset(code, offset), "Accessibility", "Text must not use maxLines = 1 with TextOverflow.Ellipsis")
            )

    violations.extend(check_previews(code, rel_ui, rel_repo))
    return violations


def check_previews(code: str, rel_ui: str, rel_repo: str) -> list[Violation]:
    """Verify @Preview annotations cover Standard Light, Dark Mode, and Elderly 200% font scale."""
    violations: list[Violation] = []
    preview_matches = list(re.finditer(r"@Preview\b", code))
    if not preview_matches and rel_ui not in EXPECTED_PREVIEW_FILES:
        return violations
    if not preview_matches and rel_ui in EXPECTED_PREVIEW_FILES:
        violations.append(
            Violation(rel_repo, None, "Preview", "Expected 3-mode @Preview functions (Light, Dark, Elderly 200%), found none")
        )
        return violations

    has_light = False
    has_dark = False
    has_elderly = False
    for match in preview_matches:
        start = match.end()
        args = ""
        if start < len(code) and code[start] == "(":
            depth = 1
            i = start + 1
            in_str = False
            while i < len(code) and depth > 0:
                c = code[i]
                if c == '"' and code[i - 1] != "\\":
                    in_str = not in_str
                elif not in_str:
                    if c == "(":
                        depth += 1
                    elif c == ")":
                        depth -= 1
                i += 1
            args = code[start + 1 : i - 1]
        is_dark = "UI_MODE_NIGHT_YES" in args
        is_elderly = bool(re.search(r"\bfontScale\s*=\s*2(?:\.0+)?f?\b", args))
        if is_dark:
            has_dark = True
        if is_elderly:
            has_elderly = True
        if not is_dark and not is_elderly:
            has_light = True

    missing: list[str] = []
    if not has_light:
        missing.append("Standard Light")
    if not has_dark:
        missing.append("Dark Mode (UI_MODE_NIGHT_YES)")
    if not has_elderly:
        missing.append("Elderly 200% Font (fontScale = 2.0f)")
    if missing:
        violations.append(
            Violation(rel_repo, None, "Preview", f"Missing @Preview mode(s): {', '.join(missing)}")
        )
    return violations


def run_checks(root: Path = ROOT) -> list[Violation]:
    """Run all Android UI/UX & Accessibility checks against the given repository root."""
    android_main = root / "apps/android/app/src/main"
    ui_dir = android_main / "java/com/anve/app/ui"
    main_activity = android_main / "java/com/anve/app/MainActivity.kt"
    res_dir = android_main / "res"

    violations = check_string_resources(res_dir, root)

    if main_activity.exists():
        ma_code = strip_kotlin_comments(main_activity.read_text(encoding="utf-8"))
        rel_ma = str(main_activity.relative_to(root))
        if "enableEdgeToEdge(" not in ma_code:
            violations.append(Violation(rel_ma, None, "Play/Insets", "MainActivity must invoke enableEdgeToEdge()"))
        if WILDCARD_IMPORT_RE.search(ma_code):
            violations.append(Violation(rel_ma, None, "Conventions", "Wildcard import forbidden"))

    seen_ui_rel: set[str] = set()
    if ui_dir.exists():
        for kt_path in sorted(ui_dir.rglob("*.kt")):
            rel_ui = kt_path.relative_to(ui_dir).as_posix()
            seen_ui_rel.add(rel_ui)
            rel_repo = str(kt_path.relative_to(root))
            violations.extend(check_kotlin_ui_file(kt_path, rel_ui, rel_repo))

        for expected in sorted(EXPECTED_PREVIEW_FILES):
            if expected not in seen_ui_rel:
                violations.append(
                    Violation(str((ui_dir / expected).relative_to(root)), None, "Preview", "Expected preview file is missing")
                )

    return violations


def main(argv: list[str] | None = None) -> int:
    """CLI entrypoint for validating Android UI rules."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT, help="Repository root path")
    args = parser.parse_args(argv)

    violations = run_checks(args.root.resolve())
    if violations:
        print(f"Android UI rules check failed with {len(violations)} violation(s):", file=sys.stderr)
        for violation in violations:
            print(f"  - {violation.format()}", file=sys.stderr)
        return 1

    print("Android UI rules check passed: 0 violations.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
