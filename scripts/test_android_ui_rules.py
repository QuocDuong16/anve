"""Unit tests for scripts/check_android_ui_rules.py."""

from __future__ import annotations

from pathlib import Path
import tempfile
import unittest

import check_android_ui_rules as checker


def _scaffold_clean_repo(root: Path) -> None:
    """Create a minimal synthetic Android UI tree that passes every rule cleanly."""
    main = root / "apps/android/app/src/main"
    (main / "res/values").mkdir(parents=True, exist_ok=True)
    (main / "res/values/strings.xml").write_text(
        '<resources><string name="app_name">AnVe</string></resources>\n',
        encoding="utf-8",
    )
    ma = main / "java/com/anve/app/MainActivity.kt"
    ma.parent.mkdir(parents=True, exist_ok=True)
    ma.write_text(
        "package com.anve.app\nfun onCreate() { enableEdgeToEdge() }\n",
        encoding="utf-8",
    )
    ui_dir = main / "java/com/anve/app/ui"
    for rel in checker.EXPECTED_PREVIEW_FILES:
        target = ui_dir / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(
            """package com.anve.app.ui
import android.content.res.Configuration
import androidx.compose.ui.tooling.preview.Preview

@Preview(name = "1. Standard Light", showBackground = true)
@Preview(name = "2. Dark Mode", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Preview(name = "3. Elderly 200% Font", fontScale = 2.0f, showBackground = true)
fun SamplePreview() {}
""",
            encoding="utf-8",
        )


class AndroidUiRulesTests(unittest.TestCase):
    """Verify positive passing trees and negative regression catches."""

    def test_clean_tree_passes_with_zero_violations(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            _scaffold_clean_repo(root)
            self.assertEqual(checker.run_checks(root), [])

    def test_rejects_ai_slop_emojis_and_todo_in_strings_and_kotlin_without_todouble_false_positive(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            _scaffold_clean_repo(root)
            strings = root / "apps/android/app/src/main/res/values/strings.xml"
            strings.write_text(
                '<resources>'
                '<string name="good">Share live location</string>'
                '<string name="slop">Unlock seamless safety ✨</string>'
                '<string name="vi_slop">Giải pháp đột phá</string>'
                '<string name="placeholder">TODO replace copy</string>'
                '</resources>\n',
                encoding="utf-8",
            )
            sample = root / "apps/android/app/src/main/java/com/anve/app/ui/home/HomeScreen.kt"
            sample.write_text(
                sample.read_text(encoding="utf-8")
                + '\nfun parseCoord(v: String) = v.toDouble()\n'
                + 'fun badLabel() = "toDouble() is fine, Elevate is not"\n',
                encoding="utf-8",
            )
            violations = checker.run_checks(root)
            rules_messages = [v.message for v in violations]
            self.assertTrue(any("'slop'" in m and "banned copy" in m for m in rules_messages))
            self.assertTrue(any("'slop'" in m and "emoji" in m for m in rules_messages))
            self.assertTrue(any("'vi_slop'" in m for m in rules_messages))
            self.assertTrue(any("'placeholder'" in m for m in rules_messages))
            self.assertTrue(any("'Elevate'" in m for m in rules_messages))
            self.assertFalse(any("toDouble" in m for m in rules_messages))

    def test_rejects_visual_slop_and_respects_illustration_and_shadow_allowances(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            _scaffold_clean_repo(root)
            ill = root / "apps/android/app/src/main/java/com/anve/app/ui/brand/AnVeIllustrations.kt"
            ill.write_text(
                ill.read_text(encoding="utf-8")
                + "\nval okBrush = Brush.horizontalGradient(listOf())\nval okShadow = 4.dp\n",
                encoding="utf-8",
            )
            screen = root / "apps/android/app/src/main/java/com/anve/app/ui/sos/SosScreen.kt"
            screen.write_text(
                screen.read_text(encoding="utf-8")
                + """
fun BadVisuals() {
    val t = rememberInfiniteTransition()
    val g = Brush.linearGradient(listOf())
    val h = Brush.horizontalGradient(listOf())
    Modifier.blur(4.dp)
    Surface(shadowElevation = 8.dp) {}
}
""",
                encoding="utf-8",
            )
            violations = checker.run_checks(root)
            msgs = "\n".join(v.format() for v in violations)
            self.assertNotIn("AnVeIllustrations.kt", msgs)
            self.assertIn("rememberInfiniteTransition", msgs)
            self.assertIn("Modifier.blur()", msgs)
            self.assertIn("Brush calls", msgs)
            self.assertIn("allowlisted only", msgs)
            self.assertIn("exceeds 6.dp", msgs)

    def test_rejects_accessibility_font_dp_fixed_height_alpha_and_maxlines1_ellipsis(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            _scaffold_clean_repo(root)
            screen = root / "apps/android/app/src/main/java/com/anve/app/ui/circle/CircleScreen.kt"
            screen.write_text(
                screen.read_text(encoding="utf-8")
                + """
fun BadA11y() {
    Text("ok2lines", maxLines = 2, overflow = TextOverflow.Ellipsis)
    Text("bad1line", fontSize = 14.dp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.alpha(0.4f))
    Button(onClick = {}, modifier = Modifier.height(48.dp)) { Text("Save") }
}
""",
                encoding="utf-8",
            )
            violations = checker.run_checks(root)
            msgs = "\n".join(v.format() for v in violations)
            self.assertIn("never .dp", msgs)
            self.assertIn("maxLines = 1 with TextOverflow.Ellipsis", msgs)
            self.assertIn(".alpha(", msgs)
            self.assertIn("fixed .height(", msgs)
            self.assertNotIn("ok2lines", msgs)

    def test_rejects_missing_edge_to_edge_unkeyed_lazy_items_and_missing_preview_modes(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            _scaffold_clean_repo(root)
            ma = root / "apps/android/app/src/main/java/com/anve/app/MainActivity.kt"
            ma.write_text("package com.anve.app\nimport com.example.*\n", encoding="utf-8")

            hist = root / "apps/android/app/src/main/java/com/anve/app/ui/history/HistoryScreen.kt"
            hist.write_text(
                """package com.anve.app.ui
import androidx.compose.ui.tooling.preview.Preview

@Preview(showBackground = true)
fun OnlyLightPreview() {}

fun BadList() {
    items(listOf(1, 2)) { item -> Text(item.toString()) }
}
""",
                encoding="utf-8",
            )
            violations = checker.run_checks(root)
            msgs = "\n".join(v.format() for v in violations)
            self.assertIn("enableEdgeToEdge()", msgs)
            self.assertIn("Wildcard import", msgs)
            self.assertIn("stable key", msgs)
            self.assertIn("Dark Mode (UI_MODE_NIGHT_YES)", msgs)
            self.assertIn("Elderly 200% Font (fontScale = 2.0f)", msgs)


if __name__ == "__main__":
    unittest.main()
