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
import time
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Any
from font_digest_cache import FontDigestCache


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


def device_partitions(module: Path) -> tuple[str, ...]:
    """Use the same bounded OEM discovery contract as the inventory scanner."""
    from font_inventory_scan import _safe_dynamic_partition_name, DYNAMIC_PARTITION_LIMIT
    path = module / "config/device_font_partitions.conf"
    extra = []
    if path.is_file():
        for value in path.read_text(encoding="utf-8").splitlines():
            value = value.strip()
            if _safe_dynamic_partition_name(value) and value not in extra:
                extra.append(value)
            if len(extra) >= DYNAMIC_PARTITION_LIMIT:
                break
    return tuple(dict.fromkeys((*PARTITIONS, *extra)))


def safe_manifest_path(raw: str, partitions: tuple[str, ...] = PARTITIONS) -> str:
    value = raw.strip().lstrip("/")
    parts = Path(value).parts
    if not value or any(part in {"", ".", ".."} for part in parts):
        raise RouteError(f"unsafe-manifest-path:{raw}")
    if parts[0] not in partitions:
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
    # Vivo's direct Chinese UI slots also require the measured stock-Han
    # coverage proof below; their filename alone never proves activation.
    if name.lower() in {"droidsansfallbackbbk.ttf", "droidsansfallbackmonster.ttf", "vivofont.ttf"}:
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


def stock_link_routes(
    inventory: dict[str, Any], verified_fonts: set[str], routed: set[str],
    evidence: dict[str, dict[str, str]], roles: dict[str, set[str]],
    root: Path, partitions: tuple[str, ...],
) -> tuple[dict[str, Any], dict[str, str]]:
    """Close recorded stock aliases against an independently proven route.

    Stock views supply the first hop. ColorOS can route that hop through a
    mutable /data bridge and back to a system slot. Read only bridge symlink
    metadata inside the supplied namespace; never open external font contents.
    Equal basenames or generated bytes do not establish an OEM relationship.
    """
    summary = {"bridgeLinksRead": 0, "bridgeLinksReused": 0,
               "terminalResultsReused": 0, "readErrors": 0,
               "snapshotStable": True, "aliasesConfirmed": 0}
    reasons: dict[str, str] = {}
    if not isinstance(inventory, dict):
        return summary, reasons
    links = inventory.get("stockFontLinks", {})
    if inventory.get("state") != "ready" or not isinstance(links, dict):
        return summary, reasons

    font_roots = tuple(f"/{part}/fonts/" for part in partitions)
    bridge_root = "/data/format_unclear/font/"
    # All caches are local to this verification pass. A theme link may change
    # between switches, so no external link text is persisted as stock data.
    bridge_cache: dict[str, str] = {}
    snapshots: dict[Path, tuple[int, ...]] = {}
    terminals: dict[str, tuple[str | None, str, tuple[str, ...]]] = {}

    def snapshot(path: Path) -> tuple[int, ...]:
        value = path.lstat()
        if stat.S_ISDIR(value.st_mode):
            # Unrelated /data entries can change directory times; only parent
            # replacement or a change of type invalidates namespace traversal.
            return (value.st_dev, value.st_ino, value.st_mode)
        return (value.st_dev, value.st_ino, value.st_mode, value.st_size,
                value.st_mtime_ns, value.st_ctime_ns)

    def bridge_target(logical: str) -> str:
        if logical in bridge_cache:
            summary["bridgeLinksReused"] += 1
            return bridge_cache[logical]
        parts = logical.strip("/").split("/")
        # Reject directory symlinks, rather than letting an absolute component
        # escape /proc/1/root into the caller's mount namespace.
        for index in range(len(parts)):
            path = visible_path(root, "/" + "/".join(parts[:index + 1]))
            before = snapshot(path)
            expected = snapshots.setdefault(path, before)
            if before != expected:
                raise RouteError("stock-bridge-changed-during-read")
            last = index == len(parts) - 1
            if not last and not stat.S_ISDIR(before[2]):
                raise RouteError("stock-bridge-parent-not-directory")
            if last:
                if not stat.S_ISLNK(before[2]):
                    raise RouteError("stock-bridge-target-not-symlink")
                target = os.readlink(path)
                if snapshot(path) != before:
                    raise RouteError("stock-bridge-changed-during-read")
        if not target or any(c in target for c in ("\x00", "\n", "\r")):
            raise RouteError("stock-bridge-invalid-target")
        if not target.startswith("/"):
            target = posixpath.join(posixpath.dirname(logical), target)
        target = posixpath.normpath(target)
        if not target.startswith((*font_roots, bridge_root)):
            raise RouteError("stock-bridge-target-outside-font-roots")
        bridge_cache[logical] = target
        summary["bridgeLinksRead"] += 1
        return target

    def terminal(rel: str) -> tuple[str | None, str, tuple[str, ...]]:
        if rel in terminals:
            summary["terminalResultsReused"] += 1
            return terminals[rel]
        current = "/" + rel.lstrip("/")
        seen = set()
        chain = []
        reason = "stock-link-depth-exceeded"
        for _ in range(32):
            if current in seen:
                reason = "stock-link-cycle"
                break
            seen.add(current)
            cached = terminals.get(current.lstrip("/"))
            if cached:
                summary["terminalResultsReused"] += 1
                end, reason, suffix = cached
                if len(chain) + len(suffix) > 32:
                    reason = "stock-link-depth-exceeded"
                    break
                terminals[rel] = (end, reason, (*chain, *suffix))
                return terminals[rel]
            chain.append(current)
            if current in links:
                target = links[current]
                if not isinstance(target, str) or not target.startswith("/") or any(c in target for c in ("\x00", "\n", "\r")):
                    reason = "stock-link-invalid-target"
                    break
            elif current.startswith(bridge_root):
                try:
                    target = bridge_target(current)
                except (OSError, RouteError) as error:
                    summary["readErrors"] += 1
                    reason = str(error) if isinstance(error, RouteError) else "stock-bridge-unreadable"
                    break
            elif current.startswith(font_roots):
                terminals[rel] = (current, "", tuple(chain))
                return terminals[rel]
            else:
                reason = "stock-link-terminal-outside-font-roots"
                break
            current = posixpath.normpath(target)
        terminals[rel] = (None, reason, tuple(chain))
        return terminals[rel]

    peers: dict[str, list[str]] = {}
    for rel in sorted(routed):
        end, _reason, _chain = terminal(rel)
        if end:
            peers.setdefault(end, []).append(rel)
    candidates = []
    for rel in sorted(verified_fonts.difference(routed)):
        # The candidate itself must be a recorded stock link, not an alias
        # created by the module's compatibility generator.
        if "/" + rel not in links:
            continue
        end, reason, chain = terminal(rel)
        if reason:
            reasons[rel] = reason
        candidates.append((rel, end, chain))
    # Recheck all observed links and their parents before accepting any bridge
    # proof. A concurrent theme switch keeps the route pending confirmation.
    try:
        summary["snapshotStable"] = all(snapshot(path) == expected for path, expected in snapshots.items())
    except OSError:
        summary["readErrors"] += 1
        summary["snapshotStable"] = False
    for rel, end, chain in candidates:
        if not summary["snapshotStable"]:
            reasons[rel] = "stock-bridge-changed-during-read"
            continue
        required = physical_roles(Path(rel).name)
        for peer in peers.get(end, []):
            proven_roles = set(evidence[peer].get("roles", "").split(","))
            accepted = required.intersection(proven_roles)
            if not accepted:
                continue
            routed.add(rel)
            evidence[rel] = {"kind": "stock-link-alias", "path": "/" + rel,
                             "peer": "/" + peer, "stockTerminal": end,
                             "stockChain": " -> ".join(chain),
                             "bridgeNamespace": str(root),
                             "peerEvidence": evidence[peer]["kind"],
                             "roles": ",".join(sorted(accepted))}
            for role in accepted:
                roles[role].add(Path(rel).name)
            summary["aliasesConfirmed"] += 1
            break
        if rel not in routed and rel not in reasons:
            reasons[rel] = "stock-link-no-confirmed-peer-with-matching-role"
    return summary, reasons


def process_font_routes(
    root: Path, unresolved: set[str],
) -> tuple[dict[str, dict[str, str]], dict[str, Any]]:
    """Observe current font objects loaded by core Android processes.

    A maps name is only a candidate hint. map_files, the process-visible path
    and PID 1 must all identify the same current file object. Old mmaps after a
    hot switch cannot prove the new route. Evidence is bounded and read-only.
    """
    found: dict[str, dict[str, str]] = {}
    summary = {"processesChecked": 0, "mappingsChecked": 0, "readErrors": 0,
               "deadlineExceeded": False}
    if not unresolved or root != Path("/proc/1/root"):
        return found, summary

    def identity(path: Path) -> tuple[int, int, int]:
        value = path.stat()
        return value.st_dev, value.st_ino, value.st_size

    def start_time(proc: Path) -> str:
        return (proc / "stat").read_text().rsplit(")", 1)[1].split()[19]

    targets: dict[str, list[tuple[str, tuple[int, int, int]]]] = {}
    for rel in sorted(unresolved):
        try:
            visible, resolved = namespace_file(root, rel)
            current = identity(visible)
            for name in {Path(rel).name, Path(resolved).name}:
                targets.setdefault(name, []).append((rel, current))
        except (OSError, RouteError):
            summary["readErrors"] += 1
    deadline = time.monotonic() + 2.0
    try:
        processes = Path("/proc").iterdir()
        for proc in processes:
            if time.monotonic() >= deadline:
                summary["deadlineExceeded"] = True
                break
            if not proc.name.isdigit():
                continue
            try:
                with (proc / "cmdline").open("rb") as stream:
                    command = stream.read(128).split(b"\0", 1)[0].decode(errors="replace")
                if command not in {"com.android.systemui", "system_server", "zygote", "zygote64"}:
                    continue
                start = start_time(proc)
                summary["processesChecked"] += 1
                visible_targets = {}
                observations = {}
                with (proc / "maps").open() as stream:
                    for line in stream:
                        if time.monotonic() >= deadline:
                            summary["deadlineExceeded"] = True
                            break
                        pieces = line.split(None, 5)
                        if len(pieces) != 6:
                            continue
                        mapped_path = pieces[5].strip().removesuffix(" (deleted)")
                        candidates = targets.get(posixpath.basename(mapped_path), [])
                        if not candidates or not re.fullmatch(r"[0-9a-f]+-[0-9a-f]+", pieces[0]):
                            continue
                        try:
                            mapped = identity(proc / "map_files" / pieces[0])
                            summary["mappingsChecked"] += 1
                            for rel, current in candidates:
                                if rel in found or mapped != current:
                                    continue
                                if rel not in visible_targets:
                                    visible, _ = namespace_file(proc / "root", rel)
                                    visible_targets[rel] = identity(visible)
                                latest, _ = namespace_file(root, rel)
                                if visible_targets[rel] == mapped == identity(latest):
                                    observations[rel] = {
                                        "kind": "process-mapping", "process": command,
                                        "pid": proc.name, "startTime": start,
                                        "mappedPath": mapped_path, "path": "/" + rel,
                                        "fileIdentity": ":".join(map(str, mapped)),
                                    }
                        except (OSError, RouteError):
                            summary["readErrors"] += 1
                if start_time(proc) == start:
                    found.update(observations)
                else:
                    summary["readErrors"] += 1
                if unresolved.issubset(found):
                    break
            except (OSError, IndexError, RouteError):
                summary["readErrors"] += 1
    except OSError:
        summary["readErrors"] += 1
    return found, summary


def verify_physical_routes(
    module: Path, root: Path, font_paths: set[str], verified_fonts: set[str],
    failures: list[str], checked_fonts: int, partitions: tuple[str, ...] | None = None,
) -> dict[str, Any]:
    """Close stock XML -> existing physical slot -> verified PID 1 artifact."""
    roles: dict[str, set[str]] = {"cjk": set(), "latin": set(), "digit": set()}
    routed: set[str] = set()
    evidence: dict[str, dict[str, str]] = {}
    checked_xml: set[str] = set()
    partitions = partitions if partitions is not None else device_partitions(module)
    declarations_checked = 0
    declarations_reused = 0
    for partition in partitions:
        try:
            etc_dir, _resolved_etc = namespace_file(root, f"/{partition}/etc")
            if not etc_dir.is_dir():
                continue
            xml_candidates = sorted(etc_dir.iterdir())
        except (OSError, RouteError):
            failures.append(f"stock-font-etc-unreadable:{partition}")
            continue
        for candidate in xml_candidates:
            if not XML_NAMES.search(candidate.name):
                continue
            xml_rel = f"{partition}/etc/{candidate.name}"
            try:
                path, _resolved_xml = namespace_file(root, xml_rel)
                document = ET.parse(path)
                if not is_world_readable(path):
                    failures.append(f"stock-xml-not-world-readable:{xml_rel}")
                    continue
            except (OSError, RouteError, ET.ParseError):
                failures.append(f"stock-xml-invalid:{xml_rel}")
                continue
            checked_xml.add(xml_rel)
            parents = {child: node for node in document.iter() for child in node}
            # Weight/style declarations often repeat one VF pathname dozens of
            # times. Route proof depends on path, family and language, not axes.
            seen_declarations: set[tuple[str, str, str]] = set()
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
                        declaration_key = (declared, family, language)
                        if declaration_key in seen_declarations:
                            declarations_reused += 1
                            continue
                        seen_declarations.add(declaration_key)
                        declarations_checked += 1
                        if declared.startswith("/"):
                            candidates = [declared]
                        else:
                            ordered = [partition, "system", *partitions]
                            candidates = [f"/{part}/fonts/{declared}" for part in dict.fromkeys(ordered)]
                        for logical in candidates:
                            try:
                                resolved, resolved_rel = namespace_file(root, logical)
                                if not resolved.is_file():
                                    continue
                            except (OSError, RouteError):
                                failures.append(f"stock-font-path-unreadable:{logical}")
                                continue
                            # Match the actual first resolvable Android slot;
                            # another partition's same basename is not proof.
                            rel = logical.lstrip("/")
                            artifact = rel if rel in font_paths else resolved_rel
                            if artifact in verified_fonts:
                                routed.add(artifact)
                                evidence.setdefault(artifact, {
                                    "kind": "stock-xml", "xml": xml_rel,
                                    "declared": declared, "resolved": resolved_rel,
                                })
                                declared_roles = physical_roles(Path(artifact).name, family, language)
                                prior_roles = set(filter(None, evidence[artifact].get("roles", "").split(",")))
                                evidence[artifact]["roles"] = ",".join(sorted(prior_roles | declared_roles))
                                for role in declared_roles:
                                    roles[role].add(Path(artifact).name)
                            elif artifact in font_paths:
                                failures.append(f"stock-xml-font-artifact-unverified:{xml_rel}:{artifact}")
                            break

    # OEM direct UI slots can bypass XML, including Chinese faces on ColorOS.
    # Chinese proof additionally requires measured stock Han coverage. A name
    # in the live candidate list or a generated compatibility alias is not enough.
    try:
        inventory = json.loads((module / "config/device_font_inventory.json").read_text(encoding="utf-8"))
    except (OSError, ValueError):
        inventory = {}
    slots = inventory.get("slots", {}) if isinstance(inventory, dict) and inventory.get("state") == "ready" else {}
    if not isinstance(slots, dict):
        slots = {}
    for rel in sorted(font_paths):
        required = physical_roles(Path(rel).name)
        if not required or rel in routed or rel not in verified_fonts:
            continue
        slot = slots.get("/" + rel)
        stock_proven = isinstance(slot, dict) and slot.get("path") == "/" + rel and (
            slot.get("source") in {"xml", "heuristic", "verified-scan"}
            or (slot.get("source") == "hyperos-physical"
                and slot.get("validatedBy") == "fontTools-stock-metrics"
                and slot.get("validatedFormat") in {"TTF", "OTF"}
                and isinstance(slot.get("metrics"), dict))
        )
        accepted_roles = required.intersection({"latin", "digit"})
        metrics = slot.get("metrics", {}) if isinstance(slot, dict) else {}
        coverage = metrics.get("coverage", {}) if isinstance(metrics, dict) else {}
        han_count = coverage.get("hanCount", 0) if isinstance(coverage, dict) else 0
        if (stock_proven and "cjk" in required
                and slot.get("format", slot.get("validatedFormat")) in {"TTF", "OTF", "TTC"}
                and slot.get("style", "normal") == "normal"
                and isinstance(han_count, (int, float)) and han_count >= 512):
            accepted_roles.add("cjk")
        if stock_proven and accepted_roles and rel in verified_fonts:
            routed.add(rel)
            evidence[rel] = {"kind": "stock-direct-slot", "source": slot["source"], "path": "/" + rel,
                             "roles": ",".join(sorted(accepted_roles))}
            for role in accepted_roles:
                roles[role].add(Path(rel).name)
    # Stock symlink aliases and observed current process mappings can close
    # routes which are absent from both the XML and the stock metric slots.
    stock_link_scan, stock_link_reasons = stock_link_routes(inventory, verified_fonts, routed, evidence, roles, root, partitions)
    observed, process_scan = process_font_routes(root, verified_fonts.intersection(font_paths).difference(routed))
    for rel, proof in observed.items():
        routed.add(rel)
        evidence[rel] = proof
        observed_roles = physical_roles(Path(rel).name)
        evidence[rel]["roles"] = ",".join(sorted(observed_roles))
        for role in observed_roles:
            roles[role].add(Path(rel).name)
    if not checked_xml:
        failures.append("no-pid1-stock-font-xml-verified")
    if not roles["cjk"]:
        failures.append("cjk-route-not-proven")
    if checked_fonts == 0:
        failures.append("no-pid1-font-artifact-verified")
    # Partial activation is meaningful only for immutable physical fonts with
    # unchanged ROM XML and at least one proven XML/direct stock route. Never
    # turn a damaged source ledger or a rewritten XML into a successful result.
    component_warnings = module / "config/font-mount-warnings.conf"
    boot = Path("/proc/sys/kernel/random/boot_id").read_text().strip()
    if conf_value(component_warnings, "boot_id") == boot:
        failures.extend(line[8:] for line in component_warnings.read_text(encoding="utf-8").splitlines() if line.startswith("warning="))
    failures.extend(
        f"physical-slot-route-not-proven:{rel}"
        for rel in sorted(verified_fonts.intersection(font_paths).difference(routed))
    )
    fatal = [value for value in failures if value.startswith((
        "source-", "physical-safe-payload-must-not-rewrite-xml",
    ))]
    applied = sorted(routed.intersection(verified_fonts))
    if not applied:
        failures.append("no-proven-custom-font-route")
    warnings = sorted(set(failures).difference(fatal)) if applied and not fatal else []
    mounted = verified_fonts.intersection(font_paths)
    unavailable = sorted(font_paths.difference(mounted))
    unconfirmed = sorted(mounted.difference(routed))
    return {
        "mode": "physical-safe", "state": "failed" if fatal or not applied else ("partial" if warnings else "verified"),
        "namespace": str(root), "checkedFonts": checked_fonts,
        "checkedXml": len(checked_xml), "xmlPolicy": "unchanged-stock",
        "cjkRoutes": sorted(roles["cjk"]), "customRoutes": sorted(Path(rel).name for rel in routed),
        "roleRoutes": {role: sorted(names) for role, names in roles.items()},
        "appliedFonts": applied, "appliedCount": len(applied),
        "mountedFonts": sorted(mounted), "mountedCount": len(mounted),
        "unconfirmedFonts": unconfirmed, "routeEvidence": evidence,
        "unconfirmedDetails": {
            rel: {"reason": stock_link_reasons.get(rel, "no-xml-stock-alias-or-current-process-mapping"),
                  "stockSlotRecorded": isinstance(slots.get("/" + rel), dict),
                  "stockSource": (slots["/" + rel].get("source", "")
                                  if isinstance(slots.get("/" + rel), dict) else "")}
            for rel in unconfirmed
        },
        "routeScan": {"declarationsChecked": declarations_checked, "declarationsReused": declarations_reused},
        "processRouteScan": process_scan,
        "stockLinkScan": stock_link_scan,
        "unappliedFonts": unavailable, "warnings": warnings,
        "failures": sorted(set(fatal if applied and not fatal else failures)),
    }


def verify_legacy(module: Path, root: Path, mountinfo: Path, require_mounts: bool = True, payload_root: Path | None = None) -> dict[str, Any]:
    hashes = FontDigestCache()
    config = module / "config"
    physical = physical_safe_contract(config)
    payload = payload_root or module / ".luoshu-payload"
    manifest = config / "font-payload-manifest.conf"
    if not manifest.is_file():
        raise RouteError("font-payload-manifest-missing")
    expected: dict[str, str] = {}
    partitions = device_partitions(module)
    for raw in manifest.read_text(encoding="utf-8", errors="replace").splitlines():
        if not raw.strip():
            continue
        pieces = raw.split("|")
        if len(pieces) < 2:
            raise RouteError("font-payload-manifest-malformed")
        rel = safe_manifest_path(pieces[0], partitions)
        expected[rel] = pieces[1].strip().lower()
        if physical and not re.fullmatch(r"[0-9a-f]{64}", expected[rel]):
            raise RouteError(f"physical-safe-manifest-invalid-sha256:{rel}")
    font_paths = {rel for rel in expected if "/fonts/" in f"/{rel}" and Path(rel).suffix.lower() in {".ttf", ".otf", ".ttc", ".font"}}
    source_only: set[str] = set()
    if physical:
        def payload_file(rel: str) -> Path:
            path = payload / rel
            return path if path.is_file() else module / rel
        # Legacy generators create hard-linked, regular .ttf/.otf aliases from
        # hidden .font anchors. The anchor is a build/source integrity artifact,
        # not an Android pathname to mount. If a symlink needs it at runtime it
        # stays required; never exempt a symlink's actual backing store.
        linked_targets = {payload_file(rel).resolve() for rel in font_paths if payload_file(rel).is_symlink()}
        source_only = {rel for rel in font_paths if "/fonts/.luoshu-font-store/" in "/" + rel
                       and Path(rel).suffix.lower() == ".font"
                       and payload_file(rel).resolve() not in linked_targets}
        font_paths -= source_only
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
        check_phase = "source"
        try:
            source = payload / rel
            if not source.is_file():
                source = module / rel
            if not source.is_file() or size(source) <= 0:
                failures.append(f"source-missing-or-empty:{rel}")
                continue
            expected_hash = expected[rel]
            source_hash = hashes.sha256(source)
            if expected_hash and source_hash != expected_hash:
                failures.append(f"source-hash-mismatch:{rel}")
                continue
            if rel in source_only:
                continue
            check_phase = "pid1"
            visible = visible_path(root, "/" + rel)
            if physical:
                visible, _resolved_rel = namespace_file(root, rel)
            if not visible.is_file() or size(visible) <= 0:
                failures.append(f"pid1-visible-missing-or-empty:{rel}")
                continue
            if rel in font_paths and not is_world_readable(visible):
                failures.append(f"pid1-font-not-world-readable:{rel}")
            actual_hash = hashes.sha256(visible)
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
        except (OSError, RouteError) as error:
            failures.append(f"{check_phase}-artifact-read-failed:{rel}:{error}")

    if physical:
        if xml_paths:
            failures.append("physical-safe-payload-must-not-rewrite-xml")
        result = verify_physical_routes(module, root, font_paths, verified_fonts, failures, checked_fonts, partitions)
        result["sourceOnlyArtifacts"] = sorted(source_only)
        return result

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
    for partition in partitions:
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
    hashes = FontDigestCache()
    partitions = device_partitions(module)
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
        rel = safe_manifest_path(logical, partitions)
        source = module / ".luoshu-payload" / rel
        visible = visible_path(root, logical)
        if not source.is_file() or size(source) <= 0:
            failures.append(f"source-missing-or-empty:{logical}")
            continue
        expected_hash = str(item.get("sha256") or "").lower()
        source_hash = hashes.sha256(source)
        if expected_hash and source_hash != expected_hash:
            failures.append(f"source-hash-mismatch:{logical}")
        if not visible.is_file() or size(visible) <= 0:
            failures.append(f"pid1-visible-missing-or-empty:{logical}")
            continue
        if kind := str(item.get("kind") or ""):
            if kind in {"xml-font", "physical-font"} and not is_world_readable(visible):
                failures.append(f"pid1-font-not-world-readable:{logical}")
        visible_hash = hashes.sha256(visible)
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
    parser.add_argument("--payload-root", type=Path)
    parser.add_argument("--active-font")
    args = parser.parse_args()
    live = {}
    try:
        from font_live_payload import current
        live = current(args.module_root)
        mode = args.mode
        if mode == "auto":
            mode = "universal" if (args.module_root / "config/universal-font-runtime.conf").is_file() else "legacy"
        if mode == "universal" or (mode == "nomount" and (args.module_root / "config/universal-font-runtime.conf").is_file()):
            result = verify_universal(args.module_root, args.visible_root, args.mountinfo, require_mounts=(mode != "nomount"))
        else:
            payload = args.payload_root or (Path(live['source']) if live else None)
            result = verify_legacy(args.module_root, args.visible_root, args.mountinfo, require_mounts=(mode != "nomount"), payload_root=payload)
    except (OSError, RouteError) as error:
        result = {"mode": args.mode, "state": "failed", "namespace": str(args.visible_root), "failures": [str(error)]}
    raw = json.dumps(result, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(raw + "\n", encoding="utf-8")
        if args.output.name in {"mount-backend-verification.json", "device-font-load-route-verification.json"}:
            warnings = result.get("warnings", [])
            reasons = {
                "pid1-visible-missing-or-empty": "未挂载或不可读取",
                "pid1-visible-hash-mismatch": "仍为原厂字体或被其他模块覆盖",
                "pid1-font-not-world-readable": "字体读取权限不足",
                "physical-slot-route-not-proven": "文件已挂载，系统加载用途尚未确认",
                "pid1-mountinfo-route-missing": "分区挂载路由尚未确认",
                "pid1-artifact-read-failed": "无法读取或解析系统字体路径",
            }
            labels = []
            for warning in warnings[:6]:
                kind, _, target = warning.partition(":")
                labels.append(f"/{target}（{reasons[kind]}）" if kind in reasons else warning)
            detail = "；".join(labels) + (f"；另有 {len(warnings)-6} 项，详见日志" if len(warnings) > 6 else "")
            applied = int(result.get("appliedCount", 0))
            mounted = int(result.get("mountedCount", applied))
            unapplied = len(result.get("unappliedFonts", []))
            unconfirmed = len(result.get("unconfirmedFonts", []))
            message = ""
            if result.get("state") == "partial":
                message = f"字体应用成功，已确认挂载 {mounted} 个槽位，其中 {applied} 个加载路径已确认"
                if unapplied:
                    message += f"；{unapplied} 个槽位未通过挂载验证，其余字体继续生效"
                if unconfirmed:
                    message += f"；{unconfirmed} 个槽位的文件已挂载，系统加载用途待确认"
                message += f"。详细提示：{detail}"
            config = args.module_root / "config/font-apply-result.conf"
            config.parent.mkdir(parents=True, exist_ok=True)
            font_file = args.module_root / "config/active_font.conf"
            font = font_file.read_text(encoding="utf-8").strip() if font_file.is_file() else "unknown"
            font = args.active_font or (live.get('font') if live else None) or font
            values = {"boot_id": Path("/proc/sys/kernel/random/boot_id").read_text().strip(),
                      "font": font, "state": result.get("state", "failed"),
                      "applied_count": applied, "unapplied_count": len(result.get("unappliedFonts", [])),
                      "mounted_count": mounted, "unconfirmed_count": unconfirmed,
                      "warning_count": len(warnings), "warning": message.replace("\n", " ").replace("\r", " ")}
            temp = config.with_name(config.name + f".tmp.{os.getpid()}")
            temp.write_text("".join(f"{key}={value}\n" for key, value in values.items()), encoding="utf-8")
            os.replace(temp, config)
    print(raw)
    # Exit 0 includes explicitly reported partial physical application. Callers
    # must preserve this distinction, not replace it with a generic PASS.
    return 0 if result.get("state") in {"verified", "partial"} else 1


if __name__ == "__main__":
    raise SystemExit(main())
