#!/usr/bin/env python3
"""Read-only font path audit for one Android process.

This only reads /proc/<pid>/maps, file identities and the module's active payload.
It never changes mounts, app settings or font files.  The App-facing locator
turns this low-level report into bounded, user-readable findings.
"""
from __future__ import annotations

import os
import re
from pathlib import Path
from typing import Any

FONT_SUFFIXES = (".ttf", ".otf", ".ttc", ".otc")
MAX_MAPS_BYTES = 2 * 1024 * 1024
MAX_MAP_ENTRIES = 256
MAX_PAYLOAD_FILES = 4096
MAX_PAYLOAD_DIRS = 512
MAX_PAYLOAD_ENTRIES = 16384
_MAPS_RE = re.compile(
    r"^\s*([0-9a-fA-F]+)-([0-9a-fA-F]+)\s+(\S+)\s+([0-9a-fA-F]+)\s+"
    r"([0-9a-fA-F]+):([0-9a-fA-F]+)\s+(\d+)\s*(.*)$"
)
_PARTITION_FONT_RE = re.compile(
    r"^/(?:system(?:/(?:product|system_ext|vendor))?|system_ext|product|vendor|odm|oem|mi_ext|cust"
    r"|my_[a-z_]+|oplus_[a-z_]+|hw_product|apex/[^/]+)/(?:.*/)?fonts?/[^/]+$"
)


def is_font_path(path: str) -> bool:
    return path.split(" (deleted)", 1)[0].strip().lower().endswith(FONT_SUFFIXES)


def parse_maps(text: str) -> tuple[list[dict[str, Any]], int]:
    """Parse only regular file-backed font mappings; return entries and font memfds."""
    entries: dict[tuple[str, int, int], dict[str, Any]] = {}
    memfd = 0
    for line in (text or "").splitlines():
        match = _MAPS_RE.match(line)
        if not match:
            continue
        path = match.group(8).strip()
        lowered = path.lower()
        if ("memfd:" in lowered or "ashmem" in lowered) and "font" in lowered:
            memfd += 1
            continue
        if not path.startswith("/") or not is_font_path(path):
            continue
        deleted = path.endswith(" (deleted)")
        clean = path[:-10] if deleted else path
        ino = int(match.group(7))
        dev = os.makedev(int(match.group(5), 16), int(match.group(6), 16))
        key = (clean, dev, ino)
        if key not in entries and len(entries) < MAX_MAP_ENTRIES:
            entries[key] = {
                "path": clean,
                "identity": (dev, ino) if ino else None,
                "deleted": deleted,
            }
    return list(entries.values()), memfd


class PayloadIndex:
    """Bounded index of active font payload files by path and inode."""

    def __init__(self, module: Path) -> None:
        self.module = module
        self.root = active_payload_root(module)
        self.root_identity = None
        self.ids: set[tuple[int, int]] = set()
        self.by_rel: dict[str, tuple[int, int]] = {}
        if not self.root.is_dir() or self.root.is_symlink():
            return
        root_stat = self.root.stat()
        self.root_identity = (root_stat.st_dev, root_stat.st_ino,
                              root_stat.st_mtime_ns, root_stat.st_ctime_ns)

        count = 0
        visited_dirs = 0
        visited_entries = 0
        for dirpath, dirs, files in os.walk(self.root, followlinks=False):
            visited_dirs += 1
            if visited_dirs > MAX_PAYLOAD_DIRS:
                break
            dirs[:] = sorted(d for d in dirs if not (Path(dirpath) / d).is_symlink())
            for name in sorted(files):
                visited_entries += 1
                if visited_entries > MAX_PAYLOAD_ENTRIES:
                    return
                if not name.lower().endswith(FONT_SUFFIXES):
                    continue
                path = Path(dirpath) / name
                try:
                    if path.is_symlink():
                        continue
                    st = path.stat()
                except OSError:
                    continue
                identity = (st.st_dev, st.st_ino)
                rel = path.relative_to(self.root).as_posix()
                self.ids.add(identity)
                self.by_rel[rel] = identity
                count += 1
                if count >= MAX_PAYLOAD_FILES:
                    return

    def __len__(self) -> int:
        return len(self.by_rel)

    def current(self) -> bool:
        """The immutable generation used for attribution must still be active."""
        if self.root_identity is None or active_payload_root(self.module) != self.root:
            return False
        try:
            if self.root.is_symlink():
                return False
            value = self.root.stat()
            return self.root_identity == (value.st_dev, value.st_ino,
                                          value.st_mtime_ns, value.st_ctime_ns)
        except OSError:
            return False

    def slot_for(self, logical_path: str) -> bool:
        rel = logical_path.lstrip("/")
        variants = [rel]
        for prefix in ("system/product/", "system/system_ext/", "system/vendor/"):
            if rel.startswith(prefix):
                variants.append(rel[len("system/"):])
        for partition in ("product/", "system_ext/", "vendor/"):
            if rel.startswith(partition):
                variants.append("system/" + rel)
        return any(item in self.by_rel for item in variants)


def active_payload_root(module: Path) -> Path:
    """Resolve a validated current-boot immutable generation, else canonical payload."""
    canonical = module / ".ziyu-payload"
    unavailable = Path("/dev/null/ziyu-active-payload-unavailable")
    record_path = module / "config/font-live.conf"
    try:
        if record_path.stat().st_size > 8192:
            return unavailable
        values: dict[str, str] = {}
        for line in record_path.read_text(encoding="utf-8", errors="replace").splitlines():
            if "=" not in line:
                continue
            key, value = line.split("=", 1)
            if key in values:
                return unavailable
            if key in {"schema", "state", "boot_id", "source", "work_root"}:
                values[key] = value.strip()
    except OSError:
        return canonical

    if values.get("schema") != "ziyu-live-font-v1" or values.get("state") != "mounted":
        return canonical
    try:
        boot = Path("/proc/sys/kernel/random/boot_id").read_text(encoding="ascii").strip()
    except OSError:
        return unavailable
    if not boot:
        return unavailable
    if values.get("boot_id") != boot:
        return canonical

    source = Path(values.get("source", ""))
    if source != canonical:
        live_parent = module / ".ziyu-state/cache/live" / boot
        if source.parent != live_parent or not re.fullmatch(r"generation-[0-9a-f]{64}", source.name):
            return unavailable
    work = Path(values.get("work_root", ""))
    work_parent = Path("/data/adb/ziyu/live-mount")
    if work.parent != work_parent or not work.name.startswith(boot + "."):
        return unavailable
    try:
        source_chain = (module, module / ".ziyu-state", module / ".ziyu-state/cache",
                        module / ".ziyu-state/cache/live", module / ".ziyu-state/cache/live" / boot, source)
        work_chain = (work_parent, work,)
        if (any(path.is_symlink() for path in source_chain + work_chain)
                or source == canonical and canonical.is_symlink()
                or not source.is_dir() or not work.is_dir()
                or (work / "boot-id").read_text(encoding="ascii").strip() != boot
                or (work / "mounts.list").stat().st_size <= 0):
            return unavailable
    except OSError:
        return unavailable
    return source


def location_class(path: str) -> str:
    if path.startswith("/data/fonts/"):
        return "updatable"
    if path.startswith(("/data/system/theme/", "/data/system/fonts/")):
        return "theme"
    if _PARTITION_FONT_RE.match(path):
        return "stock"
    return "other"


def inspect_process(pid: str, visible_root: Path, payload: PayloadIndex,
                    proc_root: Path = Path("/proc")) -> dict[str, Any]:
    """Return compact audit facts for a previously validated numeric PID."""
    if not pid.isdigit() or len(pid) > 10:
        return {"state": "invalid-pid", "items": [], "memfd": 0}
    maps_path = proc_root / pid / "maps"
    try:
        with maps_path.open("rb") as stream:
            raw = stream.read(MAX_MAPS_BYTES + 1)
    except OSError:
        return {"state": "maps-unreadable", "items": [], "memfd": 0}
    truncated = len(raw) > MAX_MAPS_BYTES
    text = raw[:MAX_MAPS_BYTES].decode("utf-8", "replace")
    entries, memfd = parse_maps(text)
    items: list[dict[str, Any]] = []
    for entry in entries:
        path = entry["path"]
        try:
            current = (visible_root / path.lstrip("/")).stat()
            current_id = (current.st_dev, current.st_ino)
        except OSError:
            current_id = None
        mapped_id = entry.get("identity")
        # Attribute a loaded font from its maps inode. The target process's own
        # mount namespace is stat'ed separately so a host-side route is never
        # mistaken for the file this PID opened.
        mapped_is_payload = mapped_id in payload.ids if mapped_id else False
        current_is_payload = current_id in payload.ids if current_id else False
        target_matches_map = mapped_id is not None and current_id == mapped_id
        if mapped_is_payload:
            kind = "payload"
        elif target_matches_map:
            kind = location_class(path)
        else:
            kind = "unknown"
        payload_slot = payload.slot_for(path)
        stale_cache = bool(payload_slot and current_is_payload and mapped_id is not None and mapped_id != current_id)
        route_not_visible = bool(payload_slot and target_matches_map and not current_is_payload)
        items.append({
            "kind": kind,
            "name": Path(path).name[:96],
            "staleCache": stale_cache,
            "routeNotVisible": route_not_visible,
            "deleted": bool(entry.get("deleted")),
            "targetPathMatchesMap": target_matches_map,
            "targetPathReadable": current_id is not None,
        })
    return {"state": "ok", "items": items, "memfd": memfd, "truncated": truncated}
