#!/usr/bin/env python3
"""Safe manual stock-font inventory wrapper for Ziyu private payload mode."""
from __future__ import annotations

import os
import shutil
import subprocess
import tempfile
from pathlib import Path
from font_live_payload import work_root as live_work_root

import font_inventory as inventory
import font_inventory_scan as scanner
from stock_readonly_view import StockReadonlyView


_ACTIVE_OVERLAY_MODULE: Path | None = None
_INSTALL_SNAPSHOTS: list[Path] = []
_READONLY_VIEW = StockReadonlyView()
_SAVED_ROOTS: dict[Path, Path] = {}
_SAVED_SCAN_BASE: Path | None = None


def _saved_stock_root(logical: Path) -> Path | None:
    """Re-read proven bytes on unchanged ROMs without requiring a second EROFS mount.

    A snapshot is a fallback for the existing ROM contract, never evidence for
    an OTA. Require the independent system-file ROM identity and every known
    slot/XML reference in this root, then verify each saved content hash.
    """
    global _SAVED_SCAN_BASE
    if logical in _SAVED_ROOTS:
        return _SAVED_ROOTS[logical]
    module = _ACTIVE_OVERLAY_MODULE
    if module is None or logical.name != 'fonts':
        return None
    from stock_font_snapshot import load, resolve
    from user_storage import props
    saved_rom = props(module / 'config/stock-rom-scan.conf').get('rom_identity', '')
    current_rom = os.environ.get('ZIYU_SCAN_ROM_IDENTITY', '')
    if not current_rom:
        try:
            result = subprocess.run(['sh', str(module / 'common/boot_ota_guard.sh'), 'rom-identity', str(module)],
                                    capture_output=True, text=True, timeout=8)
            current_rom = result.stdout.strip() if result.returncode == 0 else ''
        except (OSError, subprocess.SubprocessError):
            return None
    if not saved_rom or saved_rom != current_rom:
        return None
    snapshot = load(module)
    if snapshot.get('state') != 'complete':
        return None
    existing = inventory._load_json(module / 'config/device_font_inventory.json') or {}
    try:
        inventory.validate_inventory(existing, snapshot['buildKey'])
    except (inventory.InventoryError, KeyError):
        return None
    prefix = str(logical) + '/'
    required = {name for name in existing['slots'] if name.startswith(prefix)}
    for node in existing.get('xmlGraph', {}).get('refs', []):
        if isinstance(node, dict):
            name = node.get('resolvedPath')
            if isinstance(name, str) and name.startswith(prefix):
                required.add(name)
    entries = {name for name in snapshot['files'] if name.startswith(prefix)}
    if not required or not required.issubset(entries):
        return None
    sources = {}
    for name in sorted(entries):
        relative = Path(name).relative_to(logical)
        if '..' in relative.parts or not relative.parts:
            return None
        source = resolve(name, module)
        if source is None:
            return None
        sources[relative] = source
    if _SAVED_SCAN_BASE is None:
        parent = Path('/data/adb/ziyu/stock-snapshot-scan')
        if parent.is_symlink() or any(part.is_symlink() for part in parent.parents):
            raise inventory.InventoryError('原厂快照工作目录包含符号链接')
        parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        _SAVED_SCAN_BASE = Path(tempfile.mkdtemp(prefix=f'{os.getpid()}-', dir=parent))
    target = _SAVED_SCAN_BASE / logical.relative_to('/')
    target.mkdir(parents=True, mode=0o700)
    for relative, source in sources.items():
        output = target / relative
        output.parent.mkdir(parents=True, exist_ok=True)
        os.link(source, output)  # Same /data filesystem: no font copying.
    _SAVED_ROOTS[logical] = target
    return target


def _dynamic_manifest_partitions(module: Path | None) -> list[str]:
    if module is None:
        return []
    manifest = module / "config/device_font_partitions.conf"
    try:
        lines = manifest.read_text(encoding="utf-8").splitlines()
    except OSError:
        return []
    found: list[str] = []
    for value in lines:
        name = value.strip()
        if not name or not name.replace("_", "").isalnum() or name[0].isdigit():
            continue
        if name not in found:
            found.append(name)
    return found


def _logical_font_roots(module: Path | None) -> list[tuple[str, Path]]:
    roots = list(inventory.LOGICAL_FONT_ROOTS)
    known = {partition for partition, _logical in roots}
    for partition in _dynamic_manifest_partitions(module):
        if partition in known:
            continue
        roots.append((partition, Path("/") / partition / "fonts"))
        known.add(partition)
    return roots


def _private_root_overlaid(logical: Path) -> bool:
    module = _ACTIVE_OVERLAY_MODULE
    if module is None:
        return False
    relative = logical.relative_to("/")
    for payload in (module / ".ziyu-payload", module / ".ziyu-payload-next", module):
        root = payload / relative
        if not root.is_dir():
            continue
        try:
            if any(path.is_file() for path in root.iterdir()):
                return True
        except OSError:
            continue
    return False


def _private_overlay_risk(module: Path | None) -> bool:
    global _ACTIVE_OVERLAY_MODULE
    _ACTIVE_OVERLAY_MODULE = None
    # Ziyu's early boot hook sets this only immediately before self-mount, while
    # skip_mount/skip_mountify still leave the logical ROM roots stock-visible.
    if os.environ.get("ZIYU_STOCK_VIEW_VERIFIED", "").strip() == "1":
        return False
    if not module or not module.is_dir():
        return False
    try:
        active = (module / "config/active_font.conf").read_text(encoding="utf-8").splitlines()[0].strip()
    except (OSError, IndexError):
        active = ""
    if not active or active == "default":
        return False
    _ACTIVE_OVERLAY_MODULE = module

    payload_roots = (
        module / ".ziyu-payload",
        module / ".ziyu-payload-next",
        module,
    )
    for payload in payload_roots:
        for _partition, logical in _logical_font_roots(module):
            font_dir = payload / logical.relative_to("/")
            if not font_dir.is_dir():
                continue
            try:
                if any(
                    path.is_file() and path.suffix.lower() in inventory.FONT_EXTENSIONS
                    for path in font_dir.iterdir()
                ):
                    return True
            except OSError:
                continue
    # An active non-default selection must still be treated as overlay risk even if
    # its private payload is temporarily hidden from this process namespace.
    return True


def _unescape_mount_path(value: str) -> str:
    return (value.replace(r"\040", " ").replace(r"\011", "\t")
                 .replace(r"\012", "\n").replace(r"\134", "\\"))


def _mount_targets() -> set[str]:
    source = Path(os.environ.get("ZIYU_MOUNTINFO", "/proc/self/mountinfo"))
    try:
        lines = source.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError:
        return set()
    targets: set[str] = set()
    for line in lines:
        fields = line.split()
        if len(fields) >= 5:
            targets.add(_unescape_mount_path(fields[4]))
    return targets


def _child_mount_targets(logical: Path) -> list[str]:
    root = str(logical).rstrip("/")
    targets = _mount_targets()
    # If the directory itself is a mountpoint, a plain bind snapshot would only
    # duplicate that overlay. The recovery below is intentionally for per-file
    # bind layouts where the parent remains the stock filesystem.
    if root in targets:
        return []
    return sorted(target for target in targets if target.startswith(root + "/"))


def _run_mount(*args: str) -> bool:
    mount = shutil.which("mount") or ("/system/bin/mount" if Path("/system/bin/mount").is_file() else "")
    if not mount:
        return False
    try:
        return subprocess.run([mount, *args], check=False, stdout=subprocess.DEVNULL,
                              stderr=subprocess.DEVNULL, timeout=8).returncode == 0
    except (OSError, subprocess.SubprocessError):
        return False


def _run_umount(path: Path) -> None:
    umount = shutil.which("umount") or ("/system/bin/umount" if Path("/system/bin/umount").is_file() else "")
    if not umount:
        return
    try:
        subprocess.run([umount, str(path)], check=False, stdout=subprocess.DEVNULL,
                       stderr=subprocess.DEVNULL, timeout=8)
    except (OSError, subprocess.SubprocessError):
        pass


def _bind_parent_stock_snapshot(logical: Path) -> Path | None:
    """Recover stock bytes hidden only by per-file bind mounts.

    A non-recursive bind of the parent filesystem does not clone child mounts.
    Therefore, when Ziyu's old runtime replaced individual font files, binding
    /system/fonts to a temporary path exposes the underlying stock directory
    without touching the live font mounts. We only trust the snapshot when at
    least one known child mount resolves to a different inode in the snapshot.
    Directory-level overlay mounts are deliberately rejected above.
    """
    children = _child_mount_targets(logical)
    if not children or not logical.is_dir():
        return None

    parts = [part for part in logical.parts if part not in ("/", "")]
    key = "-".join(parts[-2:] or ["root"])
    base = Path(os.environ.get("ZIYU_INSTALL_STOCK_SNAPSHOT_ROOT",
                               f"/data/adb/ziyu/install-stock-scan/{os.getpid()}"))
    snapshot = base / key
    try:
        snapshot.mkdir(parents=True, exist_ok=True)
    except OSError:
        return None

    if not _run_mount("--bind", str(logical), str(snapshot)):
        shutil.rmtree(snapshot, ignore_errors=True)
        return None
    _run_mount("--make-private", str(snapshot))

    proven = False
    for child in children:
        try:
            relative = Path(child).relative_to(logical)
        except ValueError:
            continue
        live = logical / relative
        stock = snapshot / relative
        try:
            live_stat = live.stat()
            stock_stat = stock.stat()
        except OSError:
            continue
        if (live_stat.st_dev, live_stat.st_ino) != (stock_stat.st_dev, stock_stat.st_ino):
            proven = True
            break

    if not proven:
        _run_umount(snapshot)
        shutil.rmtree(snapshot, ignore_errors=True)
        return None

    _INSTALL_SNAPSHOTS.append(snapshot)
    return snapshot


def _cleanup_install_snapshots() -> None:
    seen: set[Path] = set()
    for snapshot in reversed(_INSTALL_SNAPSHOTS):
        if snapshot in seen:
            continue
        seen.add(snapshot)
        _run_umount(snapshot)
    if _INSTALL_SNAPSHOTS:
        shutil.rmtree(_INSTALL_SNAPSHOTS[0].parents[0], ignore_errors=True)
    _INSTALL_SNAPSHOTS.clear()


def _quick_file_signature(path: Path) -> tuple[int, bytes, bytes] | None:
    try:
        size = path.stat().st_size
        if size <= 0:
            return None
        with path.open("rb") as stream:
            head = stream.read(65536)
            tail = b""
            if size > 65536:
                stream.seek(max(0, size - 65536))
                tail = stream.read(65536)
        return (int(size), head, tail)
    except OSError:
        return None


def _mount_namespace_id(pid: str) -> str:
    try:
        return os.readlink(f"/proc/{pid}/ns/mnt")
    except OSError:
        return ""


def _system_namespace_pids() -> list[str]:
    """Return a few long-lived Android processes that should see live font mounts."""
    wanted_exact = {"system_server", "zygote", "zygote64"}
    wanted_suffix = {"/com.android.systemui", ":com.android.systemui"}
    found: list[str] = ["1"]
    proc = Path("/proc")
    try:
        entries = list(proc.iterdir())
    except OSError:
        return found
    for entry in entries:
        if not entry.name.isdigit() or entry.name == str(os.getpid()):
            continue
        try:
            raw = (entry / "cmdline").read_bytes().split(b"\0", 1)[0]
            cmd = raw.decode("utf-8", errors="ignore")
        except OSError:
            continue
        base = Path(cmd).name
        if base in wanted_exact or cmd == "com.android.systemui" or any(cmd.endswith(s) for s in wanted_suffix):
            if entry.name not in found:
                found.append(entry.name)
        if len(found) >= 8:
            break
    return found


def _installer_namespace_is_stock(logical: Path) -> bool:
    """Prove the flash process sees stock while a system process sees Ziyu.

    KernelSU/SukiSU may run module installers in a private mount namespace.
    active_font.conf can still say mix even though this process already sees the
    lower stock tree. Compare actual font bytes against several long-lived Android
    namespaces (init, system_server, zygote, SystemUI) and trust the installer
    tree only when a system namespace matches Ziyu payload while the installer
    sees different bytes at the exact same logical path.
    """
    module = _ACTIVE_OVERLAY_MODULE
    if module is None or not logical.is_dir():
        return False
    self_ns = _mount_namespace_id("self")
    if not self_ns:
        return False

    try:
        relative_root = logical.relative_to("/")
    except ValueError:
        return False

    payload_roots = (
        module / ".ziyu-payload" / relative_root,
        module / ".ziyu-payload-next" / relative_root,
        module / relative_root,
    )
    target_pids = [
        pid for pid in _system_namespace_pids()
        if _mount_namespace_id(pid) and _mount_namespace_id(pid) != self_ns
    ]
    if not target_pids:
        return False

    checked = 0
    for payload_root in payload_roots:
        if not payload_root.is_dir():
            continue
        try:
            payload_files = sorted(
                (path for path in payload_root.rglob("*")
                 if path.is_file() and path.suffix.lower() in inventory.FONT_EXTENSIONS),
                key=lambda path: str(path).lower(),
            )
        except OSError:
            continue
        for payload_file in payload_files[:24]:
            try:
                rel = payload_file.relative_to(payload_root)
            except ValueError:
                continue
            installer_file = logical / rel
            if not installer_file.is_file():
                continue
            payload_sig = _quick_file_signature(payload_file)
            installer_sig = _quick_file_signature(installer_file)
            if payload_sig is None or installer_sig is None:
                continue

            # Seeing even one exact payload file in the installer namespace means
            # it is not a clean stock view. Fail closed.
            if installer_sig == payload_sig:
                return False

            for pid in target_pids:
                live_file = Path(f"/proc/{pid}/root") / relative_root / rel
                if not live_file.is_file():
                    continue
                live_sig = _quick_file_signature(live_file)
                if live_sig is None:
                    continue
                checked += 1
                if live_sig == payload_sig and installer_sig != payload_sig:
                    return True
                if checked >= 16:
                    break
            if checked >= 16:
                break
        if checked >= 16:
            break
    return False


def _safe_pick_actual_root(logical: Path, explicit: Path | None, overlay_risk: bool) -> Path:
    if explicit is not None:
        return explicit
    if not overlay_risk:
        return logical
    # Overlay risk is per partition, not global. A custom system/fonts payload
    # does not make an untouched vendor/fonts tree unsafe to scan directly.
    if _ACTIVE_OVERLAY_MODULE is not None and not _private_root_overlaid(logical):
        return logical

    parts = logical.parts
    if len(parts) >= 3 and parts[0] == "/":
        state_root = live_work_root()
        lower = state_root / "lower" / f"{parts[1]}-{parts[2]}"
        if lower.is_dir():
            return lower

    for prefix in inventory.MIRROR_PREFIXES:
        candidate = prefix / logical.relative_to("/")
        if candidate.is_dir():
            return candidate

    # KernelSU/SukiSU installers may be isolated from the active system mount
    # namespace. If PID 1 still sees Ziyu's payload while this process sees
    # different bytes at the same font path, the installer-visible tree is a
    # verified stock view and can be scanned immediately.
    if _installer_namespace_is_stock(logical):
        return logical

    # Old ColorOS/OPlus Ziyu builds often used per-file bind mounts and did not
    # persist a lower directory. Recover the parent stock filesystem in-place by
    # taking a non-recursive bind snapshot; this leaves the live mounted font
    # untouched and makes install-time universal scanning possible.
    snapshot = _bind_parent_stock_snapshot(logical)
    if snapshot is not None:
        return snapshot

    # The same-ROM saved baseline is already independent of NoMount's redirected
    # inodes. It also works on devices that refuse loop-backed EROFS re-mounts.
    snapshot = _saved_stock_root(logical)
    if snapshot is not None:
        return snapshot

    # Inode redirection (e.g. NoMount) survives bind snapshots. A separate
    # read-only block view preserves the provider and the live font namespace.
    snapshot = _READONLY_VIEW.pick(logical)
    if snapshot is not None:
        return snapshot

    # A ROM is not required to expose every optional OEM partition. Missing logical
    # roots are harmless; an existing root without a verifiable stock view is not.
    if not logical.exists():
        return logical
    raise inventory.InventoryError(f"字体覆盖仍在活动且没有可验证的原厂 lower/mirror/snapshot：{logical}")


def main() -> int:
    inventory._overlay_risk = _private_overlay_risk
    inventory._pick_actual_root = _safe_pick_actual_root
    try:
        result = scanner.main()
        if result == 0 and _SAVED_ROOTS and _ACTIVE_OVERLAY_MODULE is not None:
            from user_storage import atomic
            atomic(_ACTIVE_OVERLAY_MODULE / 'config/stock-scan-source.conf',
                   'schema=ziyu-stock-scan-source-v1\nsource=verified-same-rom-snapshot\n' +
                   'roots=' + ','.join(map(str, _SAVED_ROOTS)) + '\n')
        return result
    finally:
        try:
            _cleanup_install_snapshots()
        finally:
            try:
                _READONLY_VIEW.close()
            finally:
                if _SAVED_SCAN_BASE is not None:
                    shutil.rmtree(_SAVED_SCAN_BASE)


if __name__ == "__main__":
    raise SystemExit(main())
