#!/usr/bin/env python3
"""ColorOS 17 digit slot regression from the verified fonts_base.xml layout."""

from __future__ import annotations

import sys
import tempfile
from pathlib import Path

COMMON = Path(__file__).resolve().parents[1] / "common"
sys.path.insert(0, str(COMMON))

import font_config_overlay as overlay
import font_inventory as inventory
import font_inventory_scan as inventory_scan


def main() -> int:
    name = "OSans-Solid-Digits-VF.ttf"
    family = "osans-solid-digits"
    assert inventory._heuristic_candidate(name), "digit-only OEM slot was not inventoried"
    assert inventory._is_ui_family(family), "digit family was not marked as a replaceable UI slot"
    assert inventory_scan._is_ui_family(family), "stock scanner dropped the digit family"

    with tempfile.TemporaryDirectory() as temporary:
        xml = Path(temporary) / "fonts_base.xml"
        rows = "".join(
            f'<font weight="{weight}" postScriptName="OPlusSansSolidDigitsVF">'
            f'{name}<axis tag="wght" stylevalue="{axis}"/></font>'
            for weight, axis in ((100, 312), (400, 486), (500, 656), (600, 890), (700, 1000))
        )
        xml.write_text(
            f'<familyset><family name="{family}">{rows}</family>'
            '<family name="emoji"><font weight="400">NotoColorEmoji.ttf</font></family>'
            '</familyset>',
            encoding="utf-8",
        )
        tree = overlay.parse_xml(xml)
        report = overlay.rewrite_tree(tree, "LuoShu")
        digit_family, emoji_family = list(tree.getroot())
        assert report["changed_fonts"] == 5, report
        assert [font.get("weight") for font in digit_family] == ["100", "400", "500", "600", "700"]
        assert [font.text for font in digit_family] == [
            "LuoShu-100.ttf", "LuoShu-400.ttf", "LuoShu-500.ttf",
            "LuoShu-600.ttf", "LuoShu-700.ttf",
        ]
        assert all(not list(font) and "postScriptName" not in font.attrib for font in digit_family)
        assert list(emoji_family)[0].text == "NotoColorEmoji.ttf"

    print("ColorOS 17 font target tests passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
