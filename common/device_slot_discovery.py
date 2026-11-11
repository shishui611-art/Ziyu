"""Device font-slot discovery for Ziyu.

Purpose: enumerate the font slots a ROM actually declares (fonts.xml families),
so slot coverage and the App home page reflect the device rather than only an
imported name list. Output is a line-oriented cache consumed by
common/slot_coverage.sh.

This is an independent implementation; the slot *concept* follows
custom-font-switcher (see THIRD_PARTY_NOTICES.md), no upstream code is copied.
"""
from __future__ import annotations

import hashlib
import os
import re
import sys
import xml.etree.ElementTree as ET

CJK_LANGS = ("zh", "cmn", "yue")
SKIP_LANG_PREFIXES = ("ja", "ko", "ar", "he", "th", "hi", "bn", "ta", "te", "ml",
                      "gu", "kn", "mr", "pa", "ur", "fa", "vi", "km", "lo", "my",
                      "si", "bo", "am", "ka", "hy", "el", "ru", "uk", "bg", "sr")
DIGIT_RE = re.compile(r"(?i)(clock|digit|dinpro|osans-solid|number|numeric|num-)")
CJK_FILE_RE = re.compile(r"(?i)(hans|hant|cjk|hei|song|kai|fang|misans|opposans|"
                         r"sysfont|syssans|sourcehan|notosans(cjk|sc|tc|hans|hant)|harmony)")
LANG_FILE_RE = re.compile(r"(?i)(kana|hangul|arabic|hebrew|thai|devanagari|bengali|"
                          r"tamil|telugu|malayalam|gujarati|kannada|myanmar|khmer|lao|"
                          r"sinhala|tibetan|ethiopic|georgian|armenian|cherokee|"
                          r"notonaskh|notosans(sinhala|tamil|telugu|kannada|malayalam|"
                          r"gujarati|bengali|devanagari|thai|lao|khmer|myanmar|hebrew|"
                          r"arabic|ethiopic|georgian|armenian))")
# 只有这些“文本家族”才算系统 UI 真正使用的槽位；装饰字体、各语种 Noto 不算。
CORE_FAMILY_RE = re.compile(r"(?i)^(sans-serif|sans-serif-condensed|sans-serif-medium|"
                            r"sans-serif-light|sans-serif-black|sans-serif-small|serif|monospace|"
                            r"clock|sans|fangsong|kaiti|songti|heiti)")
CORE_FILE_RE = re.compile(r"(?i)^(roboto|google ?sans|source ?sans|sysfont|syssans|"
                          r"opposans|oppo ?din|oplus|misans|notosans(cjk|-|_)|notoserif(cjk|-|_)|"
                          r"notosans-|notoserif-|droidsans|androidclock|osans-solid|dinpro)")

ROOTS = ("/system/etc/fonts.xml", "/system_ext/etc/fonts.xml",
         "/product/etc/fonts.xml", "/vendor/etc/fonts.xml", "/odm/etc/fonts.xml")


def classify(filename: str, family: str, lang: str) -> str:
    name = f"{filename} {family}".lower()
    if DIGIT_RE.search(name):
        return "digit"
    if lang and any(lang.lower().startswith(p) for p in CJK_LANGS):
        return "cjk"
    if CJK_FILE_RE.search(name):
        return "cjk"
    # 只把系统 UI 的文本家族当槽位；其它字体（装饰/各语种 Noto）默认不覆盖。
    if family and CORE_FAMILY_RE.match(family.strip()):
        return "latin"
    if CORE_FILE_RE.match(filename):
        return "latin"
    if lang and any(lang.lower().startswith(p) for p in SKIP_LANG_PREFIXES):
        return "lang"
    if LANG_FILE_RE.search(filename):
        return "lang"
    return "lang"


def weight_kind(weight: str) -> str:
    try:
        value = int(str(weight).strip())
    except (TypeError, ValueError):
        return ""
    if value >= 800:
        return "heavy"
    if value >= 700:
        return "bold"
    if value >= 600:
        return "semibold"
    if value >= 450:
        return "medium"
    if value >= 300:
        return "regular"
    if value >= 200:
        return "light"
    return "thin"


def parse_xml(path: str, slots: dict[str, tuple[str, str, str, str]]) -> None:
    try:
        parser = ET.iterparse(path, events=("start", "end"))
    except (OSError, ET.ParseError):
        return
    family_name = ""
    family_lang = ""
    for event, element in parser:
        tag = element.tag.rsplit("}", 1)[-1]
        if event == "start" and tag == "family":
            family_name = element.get("name", "")
            family_lang = element.get("lang", "")
        elif event == "end" and tag == "font":
            text = (element.text or "").strip()
            if text and "/" not in text:
                filename = text
                category = classify(filename, family_name, family_lang)
                weight = weight_kind(element.get("weight", ""))
                style = (element.get("style", "") or "").lower()
                if style == "italic":
                    weight = (weight + "-italic") if weight else "italic"
                if filename not in slots or (not slots[filename][2] and weight):
                    slots[filename] = (category, family_name, weight, family_lang)
            element.clear()
        elif event == "end" and tag == "family":
            element.clear()


def parse_targets(path: str, slots: dict[str, tuple[str, str, str, str]]) -> None:
    """Merge the imported per-ROM name list as the 'model list' part."""
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as handle:
            section = "main"
            for line in handle:
                line = line.strip().lstrip("\ufeff")
                if not line or line.startswith("#"):
                    continue
                if line.startswith("[") and line.endswith("]"):
                    section = line[1:-1].strip().lower()
                    continue
                if "/" in line or " " in line or line in slots:
                    continue
                if section == "clock":
                    category = "digit"
                elif section in ("lang", "kana", "hangul"):
                    category = "lang"
                else:
                    category = classify(line, "", "")
                # 机型清单里的名称一律保留，并按其分区归类（覆盖 fonts.xml 的默认判断）。
                slots[line] = (category, section, "", "")
    except OSError:
        return


def main() -> int:
    if len(sys.argv) < 4:
        print("usage: device_slot_discovery.py <module> <output> <targets-list>", file=sys.stderr)
        return 2
    module, output, targets = sys.argv[1], sys.argv[2], sys.argv[3]
    classifier_version = "2"
    module_version = ""
    try:
        with open(os.path.join(module, "module.prop"), "r", encoding="utf-8", errors="replace") as handle:
            for line in handle:
                if line.startswith("versionCode="):
                    module_version = line.split("=", 1)[1].strip()
                    break
    except OSError:
        module_version = ""
    slots: dict[str, tuple[str, str, str, str]] = {}
    digest = hashlib.sha256()
    found_roots = 0
    override = os.environ.get("ZIYU_SLOT_XML_ROOTS", "")
    roots = tuple(p for p in override.split(":") if p) if override else ROOTS
    for root in roots:
        if not os.path.isfile(root):
            continue
        found_roots += 1
        with open(root, "rb") as handle:
            digest.update(handle.read())
        parse_xml(root, slots)
    if os.path.isfile(targets):
        parse_targets(targets, slots)
    lines = ["schema=ziyu-device-slots-v1",
             "classifier=%s" % classifier_version,
             "module_version=%s" % module_version,
             "xml_roots=%d" % found_roots,
             "xml_sha256=%s" % digest.hexdigest()[:32]]
    counts = {"cjk": 0, "digit": 0, "latin": 0, "lang": 0}
    for filename in sorted(slots):
        category, family, weight, lang = slots[filename]
        counts[category] = counts.get(category, 0) + 1
        family = re.sub(r"[^A-Za-z0-9._+-]", "_", family)[:64]
        lines.append("slot=%s|%s|%s|%s" % (filename, category, weight or "-", family))
    lines.append("count_total=%d" % sum(counts.values()))
    lines.append("count_cjk=%d" % counts["cjk"])
    lines.append("count_digit=%d" % counts["digit"])
    lines.append("count_latin=%d" % counts["latin"])
    lines.append("count_lang=%d" % counts["lang"])
    tmp = output + ".tmp.%d" % os.getpid()
    try:
        os.makedirs(os.path.dirname(output), exist_ok=True)
        with open(tmp, "w", encoding="utf-8", newline="\n") as handle:
            handle.write("\n".join(lines) + "\n")
        os.replace(tmp, output)
    except OSError as error:
        print("discovery write failed: %s" % error, file=sys.stderr)
        try:
            os.unlink(tmp)
        except OSError:
            pass
        return 1
    print("device-slots total=%d cjk=%d digit=%d latin=%d lang=%d roots=%d" % (
        sum(counts.values()), counts["cjk"], counts["digit"], counts["latin"],
        counts["lang"], found_roots))
    return 0


if __name__ == "__main__":
    sys.exit(main())
