#!/usr/bin/env python3
"""Classify Android font slots by semantic role and emit a read-only shadow plan.

Phase 2 of the Ziyu universal font engine. This module never modifies fonts,
XML, mount state or /data/fonts. It only consumes device_font_topology.json and
writes two diagnostic/planning JSON files.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import time
from pathlib import Path
from typing import Any
from font_slot_overrides import VENDOR_CLOCK, exclusion, load as load_overrides, digest as overrides_digest, validate as validate_overrides

ROLE_SCHEMA = "device-font-roles-v1"
PLAN_SCHEMA = "device-font-shadow-plan-v1"
ROLE_REVISION = 3
PLAN_REVISION = 1
COLOROS_POLICY_REVISION = 1
COLOROS_DIGIT_NAME = re.compile(r"^OSans-[A-Za-z0-9_.-]*Digits[A-Za-z0-9_.-]*\.(?:ttf|otf)$", re.IGNORECASE)

PROTECTED_ROLES = {"emoji", "symbol-icon", "serif", "monospace", "special-fallback"}
CANDIDATE_ROLES = {"ui-sans", "cjk", "latin"}
SPECIALIZED_ROLES = {"numeric", "clock"}

EMOJI_TOKENS = (
    "emoji", "emojione", "twemoji", "noto-color-emoji", "notocoloremoji",
)
SYMBOL_TOKENS = (
    "symbol", "icon", "material", "dingbat", "math", "music", "awesome",
    "glyph", "weather", "fontello", "barcode", "qrcode", "braille",
)
CLOCK_TOKENS = (
    "clock", "clockopia", "lockscreen", "lock-screen", "numeral",
    "mitypemono", "mitype-mono",
)
NUMERIC_TOKENS = ("numeric", "number-font", "numberfont", "digit-font", "digitfont")
MONO_FAMILIES = (
    "monospace", "sans-serif-monospace", "serif-monospace", "ui-monospace",
    "roboto-mono", "noto-mono", "noto-sans-mono", "noto-serif-mono",
    "droid-sans-mono", "courier", "monaco",
)
UI_FAMILIES = ("sans-serif", "system-ui", "ui-sans", "sans")
CJK_TOKENS = (
    "hans", "hant", "zh-cn", "zh-tw", "zh-hk", "zh-hans", "zh-hant",
    "cjk-sc", "cjk-tc", "notosanssc", "notosanstc", "sourcehansans",
    "sourcehan-sans-sc", "sourcehan-sans-tc", "droidsansfallback",
)
LATIN_TOKENS = ("latin", "latn")
CJK_LANG_PREFIXES = ("zh", "cmn", "yue", "wuu", "hak", "nan", "hans", "hant")
LATIN_LANG_PREFIXES = (
    "en", "fr", "de", "es", "it", "pt", "nl", "sv", "no", "da", "fi",
    "pl", "cs", "sk", "sl", "hr", "hu", "ro", "tr", "vi", "id", "ms", "latn",
)
SPECIAL_LANG_PREFIXES = (
    "ja", "ko", "ar", "fa", "ur", "he", "iw", "th", "lo", "km", "my",
    "hi", "bn", "gu", "kn", "ml", "mr", "ne", "pa", "si", "ta", "te",
    "bo", "ka", "hy", "am", "ethi", "deva", "arab", "hebr", "thai", "jpan", "kore",
)
SPECIAL_SCRIPT_TOKENS = (
    "arabic", "hebrew", "thai", "devanagari", "bengali", "tamil", "telugu",
    "malayalam", "gujarati", "gurmukhi", "kannada", "khmer", "lao",
    "tibetan", "myanmar", "sinhala", "ethiopic", "georgian", "armenian",
    "japanese", "korean", "hangul", "hiragana", "katakana", "odia", "oriya",
    "adlam", "jpan", "kore", "arab", "hebr", "thai", "deva",
)


class RoleError(RuntimeError):
    pass


def normalize(value: str) -> str:
    value = value.strip().lower().replace("_", "-")
    value = re.sub(r"[^a-z0-9.+-]+", "-", value)
    value = re.sub(r"-+", "-", value)
    return value.strip("-")


def _tokens(value: str) -> set[str]:
    normalized = normalize(value)
    return {item for item in re.split(r"[^a-z0-9]+", normalized) if item}


def _contains_phrase(haystack: str, phrases: tuple[str, ...]) -> bool:
    value = normalize(haystack)
    return any(normalize(phrase) in value for phrase in phrases)


def _load(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise RoleError(f"无法读取字体拓扑：{path}") from error
    if not isinstance(value, dict):
        raise RoleError("字体拓扑根节点无效")
    return value


def _atomic_write(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_name(path.name + f".tmp.{os.getpid()}")
    temp.write_text(json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    os.replace(temp, path)


def _families(slot: dict[str, Any]) -> list[str]:
    raw = slot.get("families")
    if not isinstance(raw, list):
        return []
    result: list[str] = []
    for value in raw:
        text = str(value).strip()
        if text and text not in result:
            result.append(text)
    return result


def _xml_refs(slot: dict[str, Any]) -> list[dict[str, Any]]:
    raw = slot.get("xmlRefs")
    if not isinstance(raw, list):
        return []
    return [item for item in raw if isinstance(item, dict)]


def _xml_semantics(slot: dict[str, Any]) -> dict[str, list[str]]:
    result = {"lang": [], "variant": [], "fallbackFor": []}
    for ref in _xml_refs(slot):
        attrs = ref.get("familyAttributes")
        if not isinstance(attrs, dict):
            continue
        for raw_key, raw_value in attrs.items():
            key = str(raw_key).strip().lower()
            value = str(raw_value).strip()
            if not value:
                continue
            if key in {"lang", "language", "locale"}:
                bucket = result["lang"]
            elif key in {"fallbackfor", "fallback-for"}:
                bucket = result["fallbackFor"]
            elif key == "variant":
                bucket = result["variant"]
            else:
                continue
            if value not in bucket:
                bucket.append(value)
    return result


def _language_kind(values: list[str]) -> str:
    tokens: list[str] = []
    for value in values:
        normalized = normalize(value)
        for separator in (",", ";", ":"):
            normalized = normalized.replace(separator, "-")
        tokens.extend(item for item in normalized.split("-") if item)
        if normalized:
            tokens.append(normalized)
    for token in tokens:
        if token.startswith(SPECIAL_LANG_PREFIXES):
            return "special"
    for token in tokens:
        if token.startswith(CJK_LANG_PREFIXES):
            return "cjk"
    for token in tokens:
        if token.startswith(LATIN_LANG_PREFIXES):
            return "latin"
    return ""


def _coverage(slot: dict[str, Any]) -> dict[str, Any]:
    metrics = slot.get("metrics")
    if not isinstance(metrics, dict):
        return {}
    coverage = metrics.get("coverage")
    return coverage if isinstance(coverage, dict) else {}


def _coverage_bool(coverage: dict[str, Any], key: str) -> bool:
    return coverage.get(key) is True


def _coverage_count(coverage: dict[str, Any], key: str) -> int:
    try:
        return max(0, int(coverage.get(key) or 0))
    except (TypeError, ValueError):
        return 0


def _explicit_mono_family(families: list[str]) -> bool:
    normalized = [normalize(family) for family in families]
    for family in normalized:
        for mono in MONO_FAMILIES:
            target = normalize(mono)
            if family == target or family.startswith(target + "-"):
                return True
    return False


def _code_mono_identity(path: str, families: list[str]) -> bool:
    if _explicit_mono_family(families):
        return True
    filename = normalize(Path(path).name)
    if _contains_phrase(filename, ("mitypemono", "mitype-mono")):
        return False
    return _contains_phrase(
        filename,
        ("mono", "monospace", "courier", "consolas", "sourcecode", "source-code"),
    )


def _serif_family(families: list[str]) -> bool:
    for raw in families:
        family = normalize(raw)
        if family == "serif" or family.startswith("serif-"):
            return True
        if family.startswith("sans-serif"):
            continue
        if "serif" in _tokens(family):
            return True
    return False


def _ui_family(families: list[str]) -> bool:
    for raw in families:
        family = normalize(raw)
        if family in UI_FAMILIES:
            return True
        if family.startswith("sans-serif-") and not any(
            token in family for token in ("mono", "serif")
        ):
            return True
        if family.startswith("system-ui-") or family.startswith("ui-sans-"):
            return True
    return False


def _evidence_text(path: str, slot: dict[str, Any]) -> str:
    name = str(slot.get("slotName") or Path(path).name)
    return " ".join([name, *(_families(slot))])


def _is_special_script(text: str) -> bool:
    return _contains_phrase(text, SPECIAL_SCRIPT_TOKENS)


def _is_cjk_identity(text: str) -> bool:
    normalized = normalize(text)
    if _contains_phrase(normalized, CJK_TOKENS):
        return True
    tokens = _tokens(normalized)
    return bool(tokens.intersection({"cjk", "han", "chinese"}))


def _is_latin_identity(text: str) -> bool:
    return _contains_phrase(text, LATIN_TOKENS)


def _runtime_confirmed(slot: dict[str, Any]) -> bool:
    evidence = slot.get("runtimeEvidence")
    return isinstance(evidence, dict) and any(value is True for value in evidence.values())


def _trusted_physical_stock_metadata(slot: dict[str, Any]) -> bool:
    """Require a known stock scanner, a matching format, and valid design metrics."""
    if slot.get("validatedBy") not in {
        "fontTools-stock-metrics",
        "fontTools-generic-stock-scan",
        "font_check.sh",
    }:
        return False
    font_format = str(slot.get("format") or "").upper()
    if font_format not in {"TTF", "OTF", "TTC"}:
        return False
    if str(slot.get("validatedFormat") or "").upper() != font_format:
        return False
    metrics = slot.get("metrics")
    if not isinstance(metrics, dict):
        return False

    def metric_int(value: Any) -> int | None:
        if isinstance(value, bool):
            return None
        if isinstance(value, int):
            return value
        if isinstance(value, str) and re.fullmatch(r"[+-]?\d+", value.strip()):
            return int(value)
        return None

    hhea = metrics.get("hhea")
    if not isinstance(hhea, dict):
        return False
    upem = metric_int(metrics.get("upem"))
    ascent = metric_int(hhea.get("ascent"))
    descent = metric_int(hhea.get("descent"))
    if upem is None or not 16 <= upem <= 16384:
        return False
    if ascent is None or not 0 < ascent <= 32767:
        return False
    if descent is None or not -32768 <= descent <= 0:
        return False

    head = metrics.get("head")
    if head is not None:
        if not isinstance(head, dict):
            return False
        bounds = [metric_int(head.get(name)) for name in ("xMin", "yMin", "xMax", "yMax")]
        if any(value is None or not -32768 <= value <= 32767 for value in bounds):
            return False
        x_min, y_min, x_max, y_max = bounds
        if x_min > x_max or y_min > y_max:
            return False
    return True


def _coloros_groups(path: Path | None) -> frozenset[str]:
    """Read the small optional ColorOS slot-group file; digits are on by default."""
    enabled = {"osans_digits": True}
    if path is not None:
        try:
            lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
        except OSError:
            lines = []
        for line in lines:
            line = line.split("#", 1)[0].strip()
            key, separator, value = line.partition("=")
            key, value = key.strip(), value.strip()
            if separator and key == "osans_digits" and value in ("0", "1"):
                enabled[key] = value == "1"
    return frozenset(name for name, active in enabled.items() if active)


def _coloros_osans_kind(path: str, slot: dict[str, Any]) -> str:
    """Return a narrow ColorOS policy class based on the physical basename."""
    name = str(slot.get("slotName") or Path(path).name)
    lowered = name.lower()
    if lowered.startswith(("osans-rc", "osans-ext")):
        return "keep"
    if COLOROS_DIGIT_NAME.fullmatch(name):
        return "digits"
    return ""


def _classification(
    path: str,
    slot: dict[str, Any],
    *,
    coloros: bool = False,
    enabled_groups: frozenset[str] = frozenset(),
) -> dict[str, Any]:
    families = _families(slot)
    text = _evidence_text(path, slot)
    coverage = _coverage(slot)
    semantics = _xml_semantics(slot)
    language_kind = _language_kind(semantics["lang"])
    has_han = _coverage_bool(coverage, "hasHan") or _coverage_count(coverage, "hanCount") > 0
    has_latin = _coverage_bool(coverage, "hasLatin") or _coverage_count(coverage, "latinCount") > 0
    has_digits = _coverage_bool(coverage, "hasDigits") or _coverage_count(coverage, "digitCount") > 0
    runtime = _runtime_confirmed(slot)
    osans_kind = _coloros_osans_kind(path, slot) if coloros else ""
    physical_stock_metadata_trusted = (
        coloros
        and str(slot.get("source") or "") == "physical-scan"
        and _trusted_physical_stock_metadata(slot)
    )

    reasons: list[str] = []
    evidence: dict[str, Any] = {
        "families": families,
        "xmlSemantics": semantics,
        "coverage": {
            "han": has_han,
            "latin": has_latin,
            "digits": has_digits,
            "hanCount": _coverage_count(coverage, "hanCount"),
            "latinCount": _coverage_count(coverage, "latinCount"),
            "digitCount": _coverage_count(coverage, "digitCount"),
        },
        "runtimeConfirmed": runtime,
        "source": str(slot.get("source") or ""),
        "stockMetadataVerified": physical_stock_metadata_trusted,
        "sourceXmls": list(slot.get("sourceXmls") or []),
    }

    role = "unknown-protected"
    confidence = 35

    # Explicit XML monospace semantics beat OEM filenames such as MitypeMono.
    if osans_kind == "keep":
        role = "special-fallback"
        confidence = 100
        reasons.append("coloros-osans-rare-or-placeholder-stock")
    elif _explicit_mono_family(families):
        role = "monospace"
        confidence = 100
        reasons.append("explicit-monospace-family")
    elif _contains_phrase(text, EMOJI_TOKENS):
        role = "emoji"
        confidence = 100
        reasons.append("emoji-identity")
    elif _contains_phrase(text, SYMBOL_TOKENS):
        role = "symbol-icon"
        confidence = 100
        reasons.append("symbol-or-icon-identity")
    elif _serif_family(families):
        role = "serif"
        confidence = 100
        reasons.append("explicit-serif-family")
    elif _contains_phrase(Path(path).name, ("serif",)) and not _contains_phrase(Path(path).name, ("sansserif", "sans-serif")):
        role = "serif"
        confidence = 85
        reasons.append("serif-filename")
    elif _code_mono_identity(path, families):
        role = "monospace"
        confidence = 88
        reasons.append("monospace-file-identity")
    elif osans_kind == "digits":
        role = "numeric"
        confidence = 96
        reasons.append("coloros-osans-digit-family")
    elif VENDOR_CLOCK.fullmatch(str(slot.get("slotName") or Path(path).name)):
        role = "clock"
        confidence = 100
        reasons.append("redmagic-nubia-dedicated-clock")
    elif _contains_phrase(text, CLOCK_TOKENS):
        role = "clock"
        confidence = 95 if families else 85
        reasons.append("clock-or-numeral-identity")
    elif _contains_phrase(text, NUMERIC_TOKENS) or (has_digits and not has_latin and not has_han):
        role = "numeric"
        confidence = 90 if _contains_phrase(text, NUMERIC_TOKENS) else 75
        reasons.append("numeric-identity" if confidence >= 90 else "digits-only-coverage")
    elif language_kind == "special":
        role = "special-fallback"
        confidence = 100
        reasons.append("xml-language-special-fallback")
    elif language_kind == "cjk":
        role = "cjk"
        confidence = 100 if has_han else 90
        reasons.append("xml-language-cjk")
        if has_han:
            reasons.append("han-coverage")
    elif language_kind == "latin":
        role = "latin"
        confidence = 100 if has_latin else 90
        reasons.append("xml-language-latin")
    elif semantics["fallbackFor"]:
        role = "special-fallback"
        confidence = 92
        reasons.append("xml-fallbackfor-without-supported-script")
    elif _is_special_script(text):
        role = "special-fallback"
        confidence = 95
        reasons.append("script-specific-fallback")
    elif _is_cjk_identity(text):
        role = "cjk"
        confidence = 95 if has_han else 82
        reasons.append("cjk-family-identity")
        if has_han:
            reasons.append("han-coverage")
    elif _ui_family(families):
        role = "ui-sans"
        confidence = 100
        reasons.append("explicit-ui-family")
        if has_han:
            reasons.append("han-coverage")
        if has_latin:
            reasons.append("latin-coverage")
        if has_digits:
            reasons.append("digit-coverage")
    elif _is_latin_identity(text):
        role = "latin"
        confidence = 92 if has_latin else 78
        reasons.append("latin-family-identity")
    elif has_han and has_latin:
        # Coverage alone cannot distinguish a global UI face from a broad CJK
        # fallback, so keep it review-only instead of inventing a role.
        role = "unknown-protected"
        confidence = 55
        reasons.append("broad-text-coverage-without-family-semantics")
    elif has_han:
        role = "cjk"
        confidence = 70
        reasons.append("han-coverage-only")
    elif has_latin:
        role = "latin"
        confidence = 68
        reasons.append("latin-coverage-only")
    elif has_digits:
        role = "numeric"
        confidence = 65
        reasons.append("digit-coverage-only")
    else:
        reasons.append("insufficient-semantic-evidence")

    if runtime:
        confidence = min(100, confidence + 3)
        reasons.append("runtime-confirmed")

    if role in CANDIDATE_ROLES:
        action = "replace" if role == "ui-sans" else "conditional"
    elif role in SPECIALIZED_ROLES:
        action = "specialized"
    elif role in PROTECTED_ROLES:
        action = "preserve"
    else:
        action = "review"

    if osans_kind == "digits" and "osans_digits" not in enabled_groups:
        action = "preserve"
        reasons.append("coloros-osans-digit-group-disabled")
    if "redmagic-nubia-dedicated-clock" in reasons:
        action = "preserve"
    if (coloros and str(slot.get("source") or "") == "physical-scan"
            and not physical_stock_metadata_trusted):
        # Candidate discovery can retain a dangling/cyclic pathname for topology,
        # but without a known scanner, format, and valid metrics it has no trusted
        # stock content contract. Keep it visible without treating the link as proof.
        action = "preserve"
        reasons.append("coloros-physical-slot-stock-content-unverified")

    current_replaceable = slot.get("legacyReplaceable")
    if not isinstance(current_replaceable, bool):
        current_replaceable = slot.get("replaceable")
    comparable = isinstance(current_replaceable, bool)
    shadow_candidate = action in {"replace", "conditional", "specialized"}
    if not comparable:
        comparison = "not-comparable"
    elif current_replaceable and not shadow_candidate:
        comparison = "current-overreach"
    elif not current_replaceable and shadow_candidate:
        comparison = "current-gap"
    else:
        comparison = "agree"

    return {
        "role": role,
        "confidence": confidence,
        "action": action,
        "reasons": reasons,
        "evidence": evidence,
        "currentReplaceable": current_replaceable if comparable else None,
        "comparison": comparison,
    }


def validate_role_map(
    value: dict[str, Any],
    expected_build_key: str | None = None,
    expected_coloros_policy: dict[str, Any] | None = None,
) -> None:
    if value.get("schema") != ROLE_SCHEMA or value.get("state") != "ready":
        raise RoleError("字体角色映射格式无效")
    if int(value.get("roleRevision", 0)) != ROLE_REVISION:
        raise RoleError("字体角色映射版本无效")
    if expected_build_key and expected_build_key != "unknown" and value.get("buildKey") != expected_build_key:
        raise RoleError("字体角色映射与当前系统构建不匹配")
    slots = value.get("slots")
    if not isinstance(slots, dict):
        raise RoleError("字体角色映射缺少 slots")
    if expected_coloros_policy is not None and value.get("colorosPolicy") != expected_coloros_policy:
        raise RoleError("ColorOS 字体分组策略已变化，请重新生成角色映射")


def validate_plan(value: dict[str, Any], expected_build_key: str | None = None) -> None:
    if value.get("schema") != PLAN_SCHEMA or value.get("state") != "shadow":
        raise RoleError("Shadow 替换计划格式无效")
    if int(value.get("planRevision", 0)) != PLAN_REVISION:
        raise RoleError("Shadow 替换计划版本无效")
    if expected_build_key and expected_build_key != "unknown" and value.get("buildKey") != expected_build_key:
        raise RoleError("Shadow 替换计划与当前系统构建不匹配")
    if value.get("mutatesSystem") is not False:
        raise RoleError("Shadow 计划不得修改系统")


def build(
    topology: dict[str, Any],
    coloros_groups: frozenset[str] | None = None,
    slot_overrides: dict | None = None,
) -> tuple[dict[str, Any], dict[str, Any]]:
    if topology.get("schema") != "device-font-topology-v1" or topology.get("state") != "ready":
        raise RoleError("设备字体拓扑尚未就绪")
    raw_slots = topology.get("slots")
    if not isinstance(raw_slots, dict):
        raise RoleError("设备字体拓扑缺少 slots")

    coloros = str(topology.get("romKind") or "generic").lower() == "coloros"
    groups = coloros_groups if coloros_groups is not None else frozenset({"osans_digits"})
    coloros_policy = ({
        "schema": "coloros-latin-policy-v1",
        "revision": COLOROS_POLICY_REVISION,
        "enabledGroups": sorted(groups),
    } if coloros else None)

    rules = validate_overrides(slot_overrides if slot_overrides is not None else {"schema": "ziyu-font-slot-overrides-v1", "revision": 1})
    role_slots: dict[str, dict[str, Any]] = {}
    counts: dict[str, int] = {}
    action_counts: dict[str, int] = {}
    comparison_counts: dict[str, int] = {}

    for path in sorted(raw_slots):
        slot = raw_slots[path]
        if not isinstance(slot, dict):
            continue
        classified = _classification(path, slot, coloros=coloros, enabled_groups=groups)
        excluded = exclusion(path, rules)
        if excluded and classified["action"] in {"replace", "conditional", "specialized"}:
            classified["action"] = "preserve"
            classified["reasons"].append(excluded)
            if classified["currentReplaceable"] is True:
                classified["comparison"] = "current-overreach"
        role_slots[path] = classified
        counts[classified["role"]] = counts.get(classified["role"], 0) + 1
        action_counts[classified["action"]] = action_counts.get(classified["action"], 0) + 1
        comparison_counts[classified["comparison"]] = comparison_counts.get(classified["comparison"], 0) + 1

    build_key = str(topology.get("buildKey") or "unknown")
    rom_kind = str(topology.get("romKind") or "generic")
    role_map = {
        "schema": ROLE_SCHEMA,
        "roleRevision": ROLE_REVISION,
        "slotOverridesDigest": overrides_digest(rules),
        "state": "ready",
        "generatedAt": int(time.time()),
        "buildKey": build_key,
        "romKind": rom_kind,
        **({"colorosPolicy": coloros_policy} if coloros_policy is not None else {}),
        "topologyRevision": topology.get("topologyRevision"),
        "summary": {
            "slotCount": len(role_slots),
            "roleCounts": dict(sorted(counts.items())),
            "runtimeConfirmedSlotCount": sum(
                1 for item in role_slots.values() if item["evidence"]["runtimeConfirmed"]
            ),
        },
        "slots": role_slots,
    }

    plan_slots: dict[str, dict[str, Any]] = {}
    for path, classified in role_slots.items():
        plan_slots[path] = {
            "role": classified["role"],
            "confidence": classified["confidence"],
            "action": classified["action"],
            "comparison": classified["comparison"],
            "currentReplaceable": classified["currentReplaceable"],
            "reasons": classified["reasons"],
        }

    shadow_plan = {
        "schema": PLAN_SCHEMA,
        "planRevision": PLAN_REVISION,
        "slotOverridesDigest": overrides_digest(rules),
        "state": "shadow",
        "mutatesSystem": False,
        "generatedAt": int(time.time()),
        "buildKey": build_key,
        "romKind": rom_kind,
        "summary": {
            "slotCount": len(plan_slots),
            "actionCounts": dict(sorted(action_counts.items())),
            "comparisonCounts": dict(sorted(comparison_counts.items())),
            "candidateCount": sum(
                1 for item in plan_slots.values()
                if item["action"] in {"replace", "conditional", "specialized"}
            ),
            "protectedCount": sum(
                1 for item in plan_slots.values()
                if item["action"] in {"preserve", "review"}
            ),
        },
        "slots": plan_slots,
    }

    validate_role_map(role_map, build_key)
    validate_plan(shadow_plan, build_key)
    return role_map, shadow_plan


def _summary(role_map: dict[str, Any], plan: dict[str, Any]) -> dict[str, Any]:
    return {
        "status": "ok",
        "buildKey": role_map["buildKey"],
        "romKind": role_map["romKind"],
        "slotCount": role_map["summary"]["slotCount"],
        "roleCounts": role_map["summary"]["roleCounts"],
        "actionCounts": plan["summary"]["actionCounts"],
        "comparisonCounts": plan["summary"]["comparisonCounts"],
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--topology", type=Path, required=True)
    parser.add_argument("--roles-output", type=Path, required=True)
    parser.add_argument("--plan-output", type=Path, required=True)
    parser.add_argument("--slot-groups", type=Path)
    parser.add_argument("--slot-overrides", type=Path)
    parser.add_argument("--validate", action="store_true")
    args = parser.parse_args()

    try:
        topology = _load(args.topology)
        rules = load_overrides(args.slot_overrides or args.topology.parent / "font-slot-overrides.json")
        build_key = str(topology.get("buildKey") or "unknown")
        if args.validate:
            role_map = _load(args.roles_output)
            plan = _load(args.plan_output)
            current_coloros_policy = None
            if str(topology.get("romKind") or "generic").lower() == "coloros":
                current_coloros_policy = {
                    "schema": "coloros-latin-policy-v1",
                    "revision": COLOROS_POLICY_REVISION,
                    "enabledGroups": sorted(_coloros_groups(args.slot_groups)),
                }
            validate_role_map(role_map, build_key, current_coloros_policy)
            validate_plan(plan, build_key)
            if (role_map.get("slotOverridesDigest") != overrides_digest(rules)
                    or plan.get("slotOverridesDigest") != overrides_digest(rules)):
                raise RoleError("字体槽位规则已变化，请重新生成角色计划")
        else:
            role_map, plan = build(topology, _coloros_groups(args.slot_groups), rules)
            _atomic_write(args.roles_output, role_map)
            _atomic_write(args.plan_output, plan)
    except (RoleError, OSError, ValueError) as error:
        print(json.dumps({"status": "error", "message": str(error)}, ensure_ascii=False))
        return 1

    print(json.dumps(_summary(role_map, plan), ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
