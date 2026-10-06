#!/usr/bin/env python3
"""Fail-closed visible font-route verifier for legacy and universal payloads."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import posixpath
import re
import stat
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Any


PARTITIONS = (
    "system", "system_ext", "product", "vendor", "odm", "oem", "my_product",
    "my_engineering", "my_company", "my_preload", "my_region", "my_stock",
    "oplus_product", "oplus_engineering", "oplus_version", "oplus_region",
    "mi_ext", "cust", "hw_product", "oplus",
)
XML_NAMES = re.compile(r"(?:font|fonts).*\.xml$", re.IGNORECASE)
CUSTOM_FONT = re.compile(r"(?:LuoShu|SysFont|SysSans|OplusOSUI|OSans-Solid-Digits)", re.IGNORECASE)
ROUTE_ROLE = re.compile(r"(?:cjk|han|noto.*(?:cjk|sc)|sans-serif$|sanssc|simplified|chinese)", re.IGNORECASE)
LATIN_ROLE = re.compile(
    r"(?:latin|english|western|syssans[-_ ]?en|opposans[-_ ]?en|opsans[-_ ]?en|roboto|droid\s*sans)",
    re.IGNORECASE,
)
DIGIT_ROLE = re.compile(r"(?:digit|numeric|clock|osans[-_ ]?solid[-_ ]?digits)", re.IGNORECASE)


class RouteError(RuntimeError):
    pass


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def size(path: Path) -> int:
    return path.stat().st_size


def is_world_readable(path: Path) -> bool:
    return bool(stat.S_IMODE(path.stat().st_mode) & 0o004)


def alias_role(rel: str, family: str) -> str | None:
    value = f"{family} {Path(rel).name}"
    if ROUTE_ROLE.search(value):
        return "cjk"
    if DIGIT_ROLE.search(value):
        return "digit"
    if LATIN_ROLE.search(value):
        return "latin"
    return None


def visible_path(root: Path, logical: str) -> Path:
    return root / logical.lstrip("/")


def mount_points(path: Path) -> set[str]:
    if not path.is_file():
        raise RouteError(f"main-mountinfo-missing:{path}")
    result: set[str] = set()
    for raw in path.read_text(encoding="utf-8", errors="replace").splitlines():
        fields = raw.split()
        if len(fields) < 6:
            continue
        value = fields[4]
        for encoded, decoded in (("\\040", " "), ("\\011", "\t"), ("\\012", "\n"), ("\\134", "\\")):
            value = value.replace(encoded, decoded)
        result.add(value)
    return result


def has_partition_mount(rel: str, points: set[str]) -> bool:
    partition = rel.split("/", 1)[0]
    # On system-as-root devices the system partition is mounted at `/`; the
    # route verifier has already checked the exact visible file and its hash.
    if partition == "system" and "/" in points:
        return True
    if partition == "oplus":
        roots = ("/oplus",)
    else:
        roots = ("/" + partition,)
    for root_path in roots:
        for point in points:
            if point == root_path or point.startswith(root_path + "/"):
                return True
    return False


def xml_font_refs(path: Path) -> set[str]:
    try:
        tree = ET.parse(path)
    except (OSError, ET.ParseError) as error:
        raise RouteError(f"xml-invalid:{path}") from error
    refs: set[str] = set()
    for node in tree.iter():
        tag = node.tag.rsplit("}", 1)[-1].lower() if isinstance(node.tag, str) else ""
        if tag not in {"font", "file", "fontfile"}:
            continue
        values = [node.text or ""]
        values.extend(str(value) for key, value in node.attrib.items() if key.lower() in {"file", "path", "src", "name"})
        for value in values:
            for token in re.split(r"[\s,;]+", value.strip()):
                if not token:
                    continue
                refs.add(Path(token).name)
    return refs


def safe_manifest_path(raw: str) -> str:
    value = raw.strip().lstrip("/")
    parts = Path(value).parts
    if not value or any(part in {"", ".", ".."} for part in parts):
        raise RouteError(f"unsafe-manifest-path:{raw}")
    if parts[0] not in PARTITIONS:
        raise RouteError(f"unsupported-partition:{raw}")
    return value


def conf_value(path: Path, key: str) -> str:
    if not path.is_file():
        return ""
    prefix = key + "="
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        if line.startswith(prefix):
            return line[len(prefix):].strip()
    return ""


def physical_safe_contract(config: Path) -> bool:
    runtime = config / "font_runtime_legacy_v14_4.conf"
    return (
        conf_value(runtime, "enabled") == "true"
        and conf_value(runtime, "core") == "physical-safe-v1"
        and conf_value(config / "font-payload-schema.conf", "schema") == "legacy-physical-safe-v1"
    )


def namespace_file(root: Path, logical: str) -> tuple[Path, str]:
    """Follow absolute Android symlinks inside the supplied namespace root.

    Path.resolve/open on /proc/1/root/... can follow an absolute symlink into
    the caller's namespace. Expand each component explicitly instead.
    """
    logical = posixpath.normpath("/" + logical.lstrip("/"))
    for _ in range(32):
        parts = logical.strip("/").split("/")
        followed = False
        for index in range(len(parts)):
            prefix = "/" + "/".join(parts[:index + 1])
            path = visible_path(root, prefix)
            if not path.is_symlink():
                continue
            target = os.readlink(path)
            if not target.startswith("/"):
                target = posixpath.join(posixpath.dirname(prefix), target)
            logical = posixpath.normpath(posixpath.join(target, *parts[index + 1:]))
            followed = True
            break
        if not followed:
            return visible_path(root, logical), logical.lstrip("/")
    raise RouteError(f"pid1-font-symlink-loop:{logical}")


def physical_roles(name: str, family: str = "", language: str = "") -> set[str]:
    roles: set[str] = set()
    label = family + " " + name
    explicitly_other = LATIN_ROLE.search(name) or DIGIT_ROLE.search(name)
    if not explicitly_other and (re.search(r"(?:han[st]?|cjk|noto.*(?:sc|cjk)|LuoShuSlotCJK|chinese|simplified)", label, re.IGNORECASE) or re.search(r"(?:^|[ ,;])zh(?:[-_]|$)|Hans|Hant", language, re.IGNORECASE)):
        roles.add("cjk")
    # ColorOS declares its composite global UI slot as sans-serif. It is not
    # evidence for a Latin-only Roboto slot serving Chinese.
    if re.fullmatch(r"SysFont(?:-Static)?-Regular\.(?:ttf|otf|ttc)", name, re.IGNORECASE) and family.strip().lower() == "sans-serif":
        roles.add("cjk")
    if LATIN_ROLE.search(label) or re.search(r"SourceSansPro|GoogleSans", name, re.IGNORECASE):
        roles.add("latin")
    if DIGIT_ROLE.search(label) or re.search(r"(?:^|[-_])(?:DIN|OPPODIN)|Mitype", name, re.IGNORECASE):
        roles.add("digit")
    return roles


def verify_physical_routes(
    module: Path, root: Path, font_paths: set[str], verified_fonts: set[str],
    failures: list[str], checked_fonts: int,
) -> dict[str, Any]:
    """Close stock XML -> existing physical slot -> verified PID 1 artifact."""
    roles: dict[str, set[str]] = {"cjk": set(), "latin": set(), "digit": set()}
    routed: set[str] = set()
    checked_xml: set[str] = set()
    for partition in PARTITIONS:
        etc_dir = visible_path(root, f"/{partition}/etc")
        if not etc_dir.is_dir():
            continue
        for candidate in sorted(etc_dir.iterdir()):
            if not XML_NAMES.search(candidate.name):
                continue
            xml_rel = f"{partition}/etc/{candidate.name}"
            path, _resolved_xml = namespace_file(root, xml_rel)
            try:
                document = ET.parse(path)
                if not is_world_readable(path):
                    failures.append(f"stock-xml-not-world-readable:{xml_rel}")
                    continue
            except (OSError, ET.ParseError):
                failures.append(f"stock-xml-invalid:{xml_rel}")
                continue
            checked_xml.add(xml_rel)
            parents = {child: node for node in document.iter() for child in node}
            for node in document.iter():
                tag = node.tag.rsplit("}", 1)[-1].lower() if isinstance(node.tag, str) else ""
                if tag not in {"font", "file", "fontfile"}:
                    continue
                family = ""
                language = ""
                parent = parents.get(node)
                while parent is not None:
                    family = family or parent.get("name", "")
                    language = language or parent.get("lang", "")
                    parent = parents.get(parent)
                declarations = [node.text or ""]
                declarations.extend(value for key, value in node.attrib.items() if key.lower() in {"file", "path", "src", "name"})
                for declaration in declarations:
                    for declared in re.split(r"[\s,;]+", declaration.strip()):
                        if not declared:
                            continue
                        if declared.startswith("/"):
                            candidates = [declared]
                        elif "/" in declared:
                            candidates = [f"/{partition}/fonts/{declared}"]
                        else:
                            ordered = [partition, "system", *PARTITIONS]
                            candidates = [f"/{part}/fonts/{declared}" for part in dict.fromkeys(ordered)]
                        for logical in candidates:
                            resolved, resolved_rel = namespace_file(root, logical)
                            if not resolved.is_file():
                                continue
                            # Match the actual first resolvable Android slot;
                            # another partition's same basename is not proof.
                            rel = logical.lstrip("/")
                            artifact = rel if rel in font_paths else resolved_rel
                            if artifact in verified_fonts:
                                routed.add(artifact)
                                for role in physical_roles(Path(artifact).name, family, language):
                                    roles[role].add(Path(artifact).name)
                            elif artifact in font_paths:
                                failures.append(f"stock-xml-font-artifact-unverified:{xml_rel}:{artifact}")
                            break

    # OEM direct UI/clock slots can bypass XML. Only a ready stock inventory
    # that names this exact existing slot is accepted for Latin/digit proof.
    try:
        inventory = json.loads((module / "config/device_font_inventory.json").read_text(encoding="utf-8"))
    except (OSError, ValueError):
        inventory = {}
    slots = inventory.get("slots", {}) if isinstance(inventory, dict) and inventory.get("state") == "ready" else {}
    if not isinstance(slots, dict):
        slots = {}
    for rel in sorted(font_paths):
        required = physical_roles(Path(rel).name).intersection({"latin", "digit"})
        if not required or rel in routed:
            continue
        slot = slots.get("/" + rel)
        stock_proven = isinstance(slot, dict) and slot.get("path") == "/" + rel and slot.get("source") in {"xml", "heuristic", "verified-scan"}
        if stock_proven and rel in verified_fonts:
            for role in required:
                roles[role].add(Path(rel).name)
        else:
            failures.append(f"physical-slot-route-not-proven:{rel}")
    if not checked_xml:
        failures.append("no-pid1-stock-font-xml-verified")
    if not roles["cjk"]:
        failures.append("cjk-route-not-proven")
    if checked_fonts == 0:
        failures.append("no-pid1-font-artifact-verified")
    return {
        "mode": "physical-safe", "state": "failed" if failures else "verified",
        "namespace": str(root), "checkedFonts": checked_fonts,
        "checkedXml": len(checked_xml), "xmlPolicy": "unchanged-stock",
        "cjkRoutes": sorted(roles["cjk"]), "customRoutes": sorted(Path(rel).name for rel in routed),
        "roleRoutes": {role: sorted(names) for role, names in roles.items()},
        "failures": sorted(set(failures)),
    }


def verify_legacy(module: Path, root: Path, mountinfo: Path, require_mounts: bool = True) -> dict[str, Any]:
    config = module / "config"
    physical = physical_safe_contract(config)
    payload = module / ".luoshu-payload"
    manifest = config / "font-payload-manifest.conf"
    if not manifest.is_file():
        raise RouteError("font-payload-manifest-missing")
    expected: dict[str, str] = {}
    for raw in manifest.read_text(encoding="utf-8", errors="replace").splitlines():
        if not raw.strip():
            continue
        pieces = raw.split("|")
        if len(pieces) < 2:
            raise RouteError("font-payload-manifest-malformed")
        rel = safe_manifest_path(pieces[0])
        expected[rel] = pieces[1].strip().lower()
        if physical and not re.fullmatch(r"[0-9a-f]{64}", expected[rel]):
            raise RouteError(f"physical-safe-manifest-invalid-sha256:{rel}")
    font_paths = {rel for rel in expected if "/fonts/" in f"/{rel}" and Path(rel).suffix.lower() in {".ttf", ".otf", ".ttc", ".font"}}
    xml_paths = {rel for rel in expected if "/etc/" in f"/{rel}" and Path(rel).suffix.lower() == ".xml"}
    if not font_paths:
        raise RouteError("font-payload-manifest-has-no-fonts")

    failures: list[str] = []
    points = mount_points(mountinfo) if require_mounts else set()
    checked_fonts = 0
    checked_xml = 0
    verified_fonts: set[str] = set()
    refs_by_xml: dict[str, set[str]] = {}
    for rel in sorted(expected):
        source = payload / rel
        if not source.is_file():
            source = module / rel
        visible = visible_path(root, "/" + rel)
        if physical:
            visible, _resolved_rel = namespace_file(root, rel)
        if not source.is_file() or size(source) <= 0:
            failures.append(f"source-missing-or-empty:{rel}")
            continue
        expected_hash = expected[rel]
        source_hash = sha256(source)
        if expected_hash and source_hash != expected_hash:
            failures.append(f"source-hash-mismatch:{rel}")
        if not visible.is_file() or size(visible) <= 0:
            failures.append(f"pid1-visible-missing-or-empty:{rel}")
            continue
        if rel in font_paths and not is_world_readable(visible):
            failures.append(f"pid1-font-not-world-readable:{rel}")
        actual_hash = sha256(visible)
        if source_hash != actual_hash:
            failures.append(f"pid1-visible-hash-mismatch:{rel}")
            continue
        has_mount_route = not require_mounts or has_partition_mount(rel, points)
        if not has_mount_route:
            failures.append(f"pid1-mountinfo-route-missing:{rel}")
        if rel in font_paths:
            checked_fonts += 1
            if expected_hash == source_hash and is_world_readable(visible) and has_mount_route:
                verified_fonts.add(rel)
        if rel in xml_paths:
            checked_xml += 1
            refs_by_xml[rel] = xml_font_refs(visible)

    if physical:
        if xml_paths:
            failures.append("physical-safe-payload-must-not-rewrite-xml")
        return verify_physical_routes(module, root, font_paths, verified_fonts, failures, checked_fonts)

    # Resolve aliases and XML references as a closed loop. CJK is detected from
    # Android family names/ColorOS CJK slot names, not from mere file presence.
    alias_file = config / "font-target-aliases.conf"
    aliases: list[tuple[str, str, str, str]] = []
    if alias_file.is_file():
        for raw in alias_file.read_text(encoding="utf-8", errors="replace").splitlines():
            pieces = raw.split("|", 3)
            if len(pieces) == 4:
                aliases.append((pieces[0], pieces[1], pieces[2], pieces[3]))
    all_xml_refs: set[str] = set()
    for refs in refs_by_xml.values():
        all_xml_refs.update(refs)
    # A physical slot may be referenced by an unchanged ROM XML, so include the
    # live ColorOS/OEM font XMLs from PID 1 even when Ziyu did not rewrite them.
    for partition in PARTITIONS:
        etc_dir = root / partition / "etc"
        if not etc_dir.is_dir():
            continue
        try:
            candidates = [path for path in etc_dir.iterdir() if path.is_file() and XML_NAMES.search(path.name)]
        except OSError:
            candidates = []
        for path in candidates:
            try:
                all_xml_refs.update(xml_font_refs(path))
            except RouteError:
                continue

    referenced_custom: set[str] = set()
    for rel in font_paths:
        name = Path(rel).name
        if name in all_xml_refs and CUSTOM_FONT.search(name):
            referenced_custom.add(name)

    cjk_candidates: set[str] = set()
    role_routes: dict[str, set[str]] = {"cjk": set(), "latin": set(), "digit": set()}
    for rel, xml_key, _weight, family in aliases:
        name = Path(rel).name
        family_key = f"{family} {name}"
        role = alias_role(rel, family)
        if not role:
            continue
        if rel not in expected:
            failures.append(f"{role}-alias-not-in-payload:{rel}")
            continue
        partition = rel.split("/", 1)[0]
        xml_name = Path(xml_key).name
        matched_xml = [path for path in refs_by_xml if path.startswith(partition + "/etc/") and Path(path).name == xml_name]
        if matched_xml:
            route_present = any(name in refs_by_xml[path] for path in matched_xml)
        else:
            route_present = name in all_xml_refs
        if route_present:
            role_routes[role].add(name)
            if role == "cjk":
                cjk_candidates.add(name)
        else:
            failures.append(f"{role}-xml-route-missing:{partition}/{xml_name}:{name}")
    if not cjk_candidates:
        for rel in font_paths:
            name = Path(rel).name
            if re.search(r"(?:han|cjk|noto.*(?:sc|cjk)|LuoShuSlot)", name, re.IGNORECASE) and name in all_xml_refs:
                cjk_candidates.add(name)
                role_routes["cjk"].add(name)
    if not cjk_candidates:
        failures.append("cjk-route-not-proven")
    if not referenced_custom:
        failures.append("custom-font-not-referenced-by-visible-xml")
    if checked_fonts == 0:
        failures.append("no-pid1-font-artifact-verified")
    if checked_xml == 0:
        failures.append("no-pid1-font-xml-verified")

    return {
        "mode": "legacy" if require_mounts else "nomount",
        "state": "failed" if failures else "verified",
        "namespace": str(root),
        "checkedFonts": checked_fonts,
        "checkedXml": checked_xml,
        "customRoutes": sorted(referenced_custom),
        "cjkRoutes": sorted(cjk_candidates),
        "roleRoutes": {role: sorted(names) for role, names in role_routes.items()},
        "failures": sorted(set(failures)),
    }


def verify_universal(module: Path, root: Path, mountinfo: Path, require_mounts: bool = True) -> dict[str, Any]:
    deployment_path = module / ".luoshu-payload/.luoshu-runtime/deployment/deployment.json"
    plan_path = module / ".luoshu-payload/.luoshu-runtime/deployment/font-plan.json"
    artifacts_path = module / ".luoshu-payload/.luoshu-runtime/deployment/artifact-manifest.json"
    try:
        deployment = json.loads(deployment_path.read_text(encoding="utf-8"))
        plan = json.loads(plan_path.read_text(encoding="utf-8"))
        artifacts = json.loads(artifacts_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise RouteError("universal-route-contract-missing") from error
    artifacts_by_id = {
        str(item.get("artifactId")): item
        for item in artifacts.get("artifacts", []) if isinstance(item, dict)
    }
    targets = plan.get("targets") if isinstance(plan.get("targets"), dict) else {}
    xml_refs: dict[str, set[str]] = {}
    failures: list[str] = []
    points = mount_points(mountinfo) if require_mounts else set()
    checked_fonts = 0
    checked_xml = 0
    files = deployment.get("files") if isinstance(deployment.get("files"), list) else []
    for item in files:
        if not isinstance(item, dict):
            continue
        logical = str(item.get("logicalPath") or "")
        rel = safe_manifest_path(logical)
        source = module / ".luoshu-payload" / rel
        visible = visible_path(root, logical)
        if not source.is_file() or size(source) <= 0:
            failures.append(f"source-missing-or-empty:{logical}")
            continue
        expected_hash = str(item.get("sha256") or "").lower()
        source_hash = sha256(source)
        if expected_hash and source_hash != expected_hash:
            failures.append(f"source-hash-mismatch:{logical}")
        if not visible.is_file() or size(visible) <= 0:
            failures.append(f"pid1-visible-missing-or-empty:{logical}")
            continue
        if kind := str(item.get("kind") or ""):
            if kind in {"xml-font", "physical-font"} and not is_world_readable(visible):
                failures.append(f"pid1-font-not-world-readable:{logical}")
        visible_hash = sha256(visible)
        if visible_hash != source_hash:
            failures.append(f"pid1-visible-hash-mismatch:{logical}")
            continue
        if require_mounts and not has_partition_mount(rel, points):
            failures.append(f"pid1-mountinfo-route-missing:{logical}")
        if kind == "xml":
            checked_xml += 1
            xml_refs[logical] = xml_font_refs(visible)
        elif kind in {"xml-font", "physical-font"}:
            checked_fonts += 1

    visible_refs: set[str] = set()
    for refs in xml_refs.values():
        visible_refs.update(refs)
    role_files: dict[str, set[str]] = {"cjk": set(), "latin": set(), "digits": set()}
    for item in files:
        if not isinstance(item, dict) or item.get("kind") not in {"xml-font", "physical-font"}:
            continue
        artifact = artifacts_by_id.get(str(item.get("artifactId") or ""), {})
        target_path = str(artifact.get("targetPath") or "")
        target = targets.get(target_path, {})
        role = str(target.get("role") or artifact.get("role") or "").lower()
        basename = Path(str(item.get("logicalPath") or "")).name
        for key in role_files:
            if role == key or (key == "digits" and role in {"digit", "numeric", "clock"}) or (key == "latin" and role in {"ui-sans", "latin"}):
                role_files[key].add(basename)
    for role, names in role_files.items():
        if names and not names.intersection(visible_refs):
            failures.append(f"{role}-route-not-in-visible-xml")
    if not role_files["cjk"]:
        failures.append("cjk-role-artifact-missing")
    if checked_fonts == 0 or checked_xml == 0:
        failures.append("required-font-or-xml-artifact-missing")
    if role_files["cjk"] and not role_files["cjk"].intersection(visible_refs):
        failures.append("cjk-route-not-proven")
    return {
        "mode": "universal" if require_mounts else "nomount-universal",
        "state": "failed" if failures else "verified",
        "namespace": str(root),
        "checkedFonts": checked_fonts,
        "checkedXml": checked_xml,
        "cjkRoutes": sorted(role_files["cjk"].intersection(visible_refs)),
        "latinRoutes": sorted(role_files["latin"].intersection(visible_refs)),
        "digitRoutes": sorted(role_files["digits"].intersection(visible_refs)),
        "failures": sorted(set(failures)),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--module-root", type=Path, required=True)
    parser.add_argument("--visible-root", type=Path, default=Path("/proc/1/root"))
    parser.add_argument("--mode", choices=("auto", "legacy", "universal", "nomount"), default="auto")
    parser.add_argument("--mountinfo", type=Path, default=Path("/proc/1/mountinfo"))
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    try:
        mode = args.mode
        if mode == "auto":
            mode = "universal" if (args.module_root / "config/universal-font-runtime.conf").is_file() else "legacy"
        if mode == "universal" or (mode == "nomount" and (args.module_root / "config/universal-font-runtime.conf").is_file()):
            result = verify_universal(args.module_root, args.visible_root, args.mountinfo, require_mounts=(mode != "nomount"))
        else:
            result = verify_legacy(args.module_root, args.visible_root, args.mountinfo, require_mounts=(mode != "nomount"))
    except (OSError, RouteError) as error:
        result = {"mode": args.mode, "state": "failed", "namespace": str(args.visible_root), "failures": [str(error)]}
    raw = json.dumps(result, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(raw + "\n", encoding="utf-8")
    print(raw)
    return 0 if result.get("state") == "verified" else 1


if __name__ == "__main__":
    raise SystemExit(main())
