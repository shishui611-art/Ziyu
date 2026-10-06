#!/usr/bin/env python3
"""Fixtures for PID 1 visible XML-to-font route verification."""
from __future__ import annotations

import hashlib
import importlib.util
import json
import os
import argparse
import shutil
import tempfile
from pathlib import Path
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("font_route_verify", ROOT / "common/font_route_verify.py")
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def write(path: Path, content: bytes) -> str:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(content)
    return hashlib.sha256(content).hexdigest()


def fixture(base: Path) -> tuple[Path, Path, Path, Path, str, str]:
    module = base / "module"
    pid1 = base / "pid1"
    su = base / "su"
    mountinfo = base / "mountinfo"
    xml_rel = "system/etc/fonts.xml"
    font_rel = "system/fonts/LuoShuSlotCJK-Regular.ttf"
    latin_rel = "system/fonts/SysSans-En-Regular.ttf"
    digit_rel = "system/fonts/OSans-Solid-Digits-VF.ttf"
    xml = (
        b'<familyset><family name="NotoSansCJKsc"><font>LuoShuSlotCJK-Regular.ttf</font>'
        b'<font>SysSans-En-Regular.ttf</font><font>OSans-Solid-Digits-VF.ttf</font></family></familyset>'
    )
    font = b"fixture-font-data"
    xml_hash = write(module / ".luoshu-payload" / xml_rel, xml)
    font_hash = write(module / ".luoshu-payload" / font_rel, font)
    latin_hash = write(module / ".luoshu-payload" / latin_rel, b"fixture-latin")
    digit_hash = write(module / ".luoshu-payload" / digit_rel, b"fixture-digits")
    write(pid1 / xml_rel, xml)
    write(pid1 / font_rel, font)
    write(pid1 / latin_rel, b"fixture-latin")
    write(pid1 / digit_rel, b"fixture-digits")
    write(su / xml_rel, xml)
    write(su / font_rel, font)
    write(su / latin_rel, b"fixture-latin")
    write(su / digit_rel, b"fixture-digits")
    config = module / "config"
    config.mkdir(parents=True, exist_ok=True)
    (config / "font-payload-manifest.conf").write_text(
        f"{xml_rel}|{xml_hash}\n{font_rel}|{font_hash}\n{latin_rel}|{latin_hash}\n{digit_rel}|{digit_hash}\n",
        encoding="utf-8"
    )
    (config / "font-target-aliases.conf").write_text(
        f"{font_rel}|{xml_rel}|400|NotoSansCJKsc\n"
        f"{latin_rel}|{xml_rel}|400|SysSans-En\n"
        f"{digit_rel}|{xml_rel}|400|osans-solid-digits\n",
        encoding="utf-8"
    )
    mountinfo.write_text("33 22 0:29 / /system rw,relatime - ext4 /dev/block/system rw\n", encoding="utf-8")
    return module, pid1, su, mountinfo, xml_rel, font_rel


def universal_fixture(base: Path) -> tuple[Path, Path, Path, Path, str]:
    module = base / "universal-module"
    pid1 = base / "universal-pid1"
    su = base / "universal-su"
    mountinfo = base / "universal-mountinfo"
    deployment_root = module / ".luoshu-payload/.luoshu-runtime/deployment"
    xml_rel = "system/etc/fonts.xml"
    fonts = {
        "cjk": ("system/fonts/LuoShuSlotCJK-Regular.ttf", b"universal-cjk"),
        "latin": ("system/fonts/SysSans-En-Regular.ttf", b"universal-latin"),
        "digit": ("system/fonts/OSans-Solid-Digits-VF.ttf", b"universal-digits"),
    }
    xml = (
        b'<familyset><family name="NotoSansCJKsc"><font>LuoShuSlotCJK-Regular.ttf</font>'
        b'<font>SysSans-En-Regular.ttf</font><font>OSans-Solid-Digits-VF.ttf</font></family></familyset>'
    )
    files: list[dict[str, str]] = []
    xml_hash = write(module / ".luoshu-payload" / xml_rel, xml)
    files.append({"logicalPath": xml_rel, "sha256": xml_hash, "kind": "xml", "artifactId": "xml"})
    write(pid1 / xml_rel, xml)
    write(su / xml_rel, xml)

    artifacts = [{"artifactId": "xml", "targetPath": xml_rel, "role": "xml"}]
    targets = {xml_rel: {"role": "xml"}}
    roles = {"cjk": "system/fonts/cjk", "latin": "system/fonts/latin", "digit": "system/fonts/digit"}
    for role, (rel, data) in fonts.items():
        digest = write(module / ".luoshu-payload" / rel, data)
        files.append({"logicalPath": rel, "sha256": digest, "kind": "physical-font", "artifactId": role})
        write(pid1 / rel, data)
        write(su / rel, data)
        artifacts.append({"artifactId": role, "targetPath": roles[role], "role": role})
        targets[roles[role]] = {"role": role}

    deployment_root.mkdir(parents=True, exist_ok=True)
    (deployment_root / "deployment.json").write_text(json.dumps({"files": files}), encoding="utf-8")
    (deployment_root / "font-plan.json").write_text(json.dumps({"targets": targets}), encoding="utf-8")
    (deployment_root / "artifact-manifest.json").write_text(json.dumps({"artifacts": artifacts}), encoding="utf-8")
    (module / "config").mkdir(parents=True, exist_ok=True)
    (module / "config/universal-font-runtime.conf").write_text("state=active\n", encoding="utf-8")
    mountinfo.write_text("33 22 0:29 / /system rw,relatime - ext4 /dev/block/system rw\n", encoding="utf-8")
    return module, pid1, su, mountinfo, fonts["cjk"][0]


def phone_readback_fixture(readback: Path) -> dict:
    """Replay real ROM XML/inventory with synthetic matching slot bytes.

    This verifies route policy only; it does not claim device mount or actual
    FontTools/font data validation. Private phone data stays outside the repo.
    """
    names = [
        "OPSans-En-Regular.ttf", "Roboto-Regular.ttf", "RobotoFlex-Regular.ttf",
        "RobotoStatic-Regular.ttf", "SourceSansPro-Regular.ttf",
        "SourceSansPro-SemiBold.ttf", "SourceSansPro-Bold.ttf",
        "SysFont-Regular.ttf", "SysFont-Static-Regular.ttf",
        "SysFont-Hans-Regular.ttf", "SysFont-Hant-Regular.ttf",
        "SysSans-En-Regular.ttf", "SysSans-Hans-Regular.ttf",
        "SysSans-Hant-Regular.ttf",
    ]
    with tempfile.TemporaryDirectory(prefix="ziyu-physical-phone-replay-") as tmp:
        base = Path(tmp)
        module, pid1 = base / "module", base / "pid1"
        config = module / "config"
        config.mkdir(parents=True)
        shutil.copy2(readback / "inventory.json", config / "device_font_inventory.json")
        for name, partition in (("fonts.xml", "system"), ("fonts_base.xml", "system_ext"), ("fonts_ule.xml", "system_ext")):
            destination = pid1 / partition / "etc" / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(readback / name, destination)
        records = []
        for name in names:
            rel = "system/fonts/" + name
            data = ("synthetic matching physical slot: " + name).encode()
            digest = write(module / ".luoshu-payload" / rel, data)
            write(pid1 / rel, data)
            records.append(f"{rel}|{digest}\n")
        (config / "font-payload-manifest.conf").write_text("".join(records))
        (config / "font_runtime_legacy_v14_4.conf").write_text("enabled=true\ncore=physical-safe-v1\n")
        (config / "font-payload-schema.conf").write_text("schema=legacy-physical-safe-v1\n")
        mountinfo = base / "mountinfo"
        mountinfo.write_text("33 22 0:29 / /system rw - ext4 /dev/block/system rw\n34 22 0:30 / /system_ext rw - ext4 /dev/block/system_ext rw\n")
        result = MODULE.verify_legacy(module, pid1, mountinfo)
        assert result["state"] == "verified", result
        assert result["checkedFonts"] == 14, result
        assert result["checkedXml"] == 3, result
        return result


def main() -> None:
    with tempfile.TemporaryDirectory(prefix="ziyu-route-verify-") as tmp:
        base = Path(tmp)
        module, pid1, _su, mountinfo, _xml_rel, font_rel = fixture(base)
        good = MODULE.verify_legacy(module, pid1, mountinfo)
        assert good["state"] == "verified", good
        assert good["cjkRoutes"] == [Path(font_rel).name], good
        assert good["roleRoutes"] == {"cjk": [Path(font_rel).name], "latin": ["SysSans-En-Regular.ttf"], "digit": ["OSans-Solid-Digits-VF.ttf"]}, good

        # NoMount verifies the same PID 1 file hashes and font routes while
        # intentionally requiring no traditional mount-table entries.
        empty_mountinfo = base / "empty-mountinfo"
        empty_mountinfo.write_text("", encoding="utf-8")
        nomount = MODULE.verify_legacy(module, pid1, empty_mountinfo, require_mounts=False)
        assert nomount["state"] == "verified", nomount
        assert nomount["mode"] == "nomount", nomount
        assert not any(item.startswith("pid1-mountinfo-route-missing:") for item in nomount["failures"]), nomount

        # System-as-root reports `/` as the system mountpoint instead of `/system`.
        mountinfo.write_text("33 22 0:29 / / rw,relatime - ext4 /dev/block/system rw\n", encoding="utf-8")
        sar = MODULE.verify_legacy(module, pid1, mountinfo)
        assert sar["state"] == "verified", sar

        # Physical-safe replaces existing ROM font slots, deliberately leaving
        # XML stock. Only its explicit two-file contract permits that topology.
        physical = base / "physical"
        pm, pr, _ps, pi, px, pf = fixture(physical)
        (pm / "config/font_runtime_legacy_v14_4.conf").write_text("enabled=true\ncore=physical-safe-v1\n")
        (pm / "config/font-payload-schema.conf").write_text("schema=legacy-physical-safe-v1\n")
        (pm / ".luoshu-payload" / px).unlink()
        manifest = pm / "config/font-payload-manifest.conf"
        manifest.write_text("".join(line + "\n" for line in manifest.read_text().splitlines() if not line.startswith(px + "|")))
        (pm / "config/font-target-aliases.conf").unlink()
        physical_ok = MODULE.verify_legacy(pm, pr, pi)
        assert physical_ok["state"] == "verified", physical_ok
        assert physical_ok["mode"] == "physical-safe", physical_ok
        assert physical_ok["checkedXml"] >= 1, physical_ok
        assert physical_ok["roleRoutes"]["cjk"] == [Path(pf).name], physical_ok

        # Merely deleting XML from a legacy payload never opts into physical.
        (pm / "config/font-payload-schema.conf").unlink()
        ordinary_no_xml = MODULE.verify_legacy(pm, pr, pi)
        assert ordinary_no_xml["state"] == "failed", ordinary_no_xml
        assert "no-pid1-font-xml-verified" in ordinary_no_xml["failures"], ordinary_no_xml
        (pm / "config/font-payload-schema.conf").write_text("schema=legacy-physical-safe-v1\n")

        # Stock XML cannot point at a missing or old Chinese artifact in PID 1.
        (pr / pf).unlink()
        missing_physical = MODULE.verify_legacy(pm, pr, pi)
        assert missing_physical["state"] == "failed", missing_physical
        assert "pid1-visible-missing-or-empty:" + pf in missing_physical["failures"], missing_physical
        write(pr / pf, b"old-stock-cjk")
        stale_physical = MODULE.verify_legacy(pm, pr, pi)
        assert stale_physical["state"] == "failed", stale_physical
        assert "pid1-visible-hash-mismatch:" + pf in stale_physical["failures"], stale_physical
        write(pr / pf, (pm / ".luoshu-payload" / pf).read_bytes())
        stock_xml = (pr / px).read_bytes()
        write(pr / px, b'<familyset><family name="sans-serif"><font>SysSans-En-Regular.ttf</font></family></familyset>')
        no_cjk_route = MODULE.verify_legacy(pm, pr, pi)
        assert no_cjk_route["state"] == "failed", no_cjk_route
        assert "cjk-route-not-proven" in no_cjk_route["failures"], no_cjk_route
        write(pr / px, b"malformed xml")
        malformed_stock = MODULE.verify_legacy(pm, pr, pi)
        assert malformed_stock["state"] == "failed", malformed_stock
        assert any(item.startswith("stock-xml-invalid:") for item in malformed_stock["failures"]), malformed_stock
        write(pr / px, stock_xml)

        # Explicit physical contract never excuses a rewritten XML from
        # payload verification, nor accepts it as unchanged stock topology.
        xml_digest = write(pm / ".luoshu-payload" / px, stock_xml)
        original_manifest = manifest.read_text()
        manifest.write_text(original_manifest + f"{px}|{xml_digest}\n")
        write(pr / px, b'<familyset><family name="sans-serif"/></familyset>')
        rewritten_xml = MODULE.verify_legacy(pm, pr, pi)
        assert rewritten_xml["state"] == "failed", rewritten_xml
        assert "pid1-visible-hash-mismatch:" + px in rewritten_xml["failures"], rewritten_xml
        assert "physical-safe-payload-must-not-rewrite-xml" in rewritten_xml["failures"], rewritten_xml
        (pm / ".luoshu-payload" / px).unlink()
        manifest.write_text(original_manifest)
        write(pr / px, stock_xml)

        # OEM direct clock/Latin slots require an exact ready stock inventory;
        # a matched basename in an unrelated partition is not sufficient.
        direct_xml = b'<familyset><family name="NotoSansCJKsc"><font>LuoShuSlotCJK-Regular.ttf</font></family></familyset>'
        write(pr / px, direct_xml)
        no_direct_evidence = MODULE.verify_legacy(pm, pr, pi)
        assert no_direct_evidence["state"] == "failed", no_direct_evidence
        assert "physical-slot-route-not-proven:system/fonts/OSans-Solid-Digits-VF.ttf" in no_direct_evidence["failures"], no_direct_evidence
        inventory_file = pm / "config/device_font_inventory.json"
        direct_slots = {
            "/system/fonts/SysSans-En-Regular.ttf": {"path": "/system/fonts/SysSans-En-Regular.ttf", "source": "xml"},
            "/system/fonts/OSans-Solid-Digits-VF.ttf": {"path": "/system/fonts/OSans-Solid-Digits-VF.ttf", "source": "heuristic"},
        }
        inventory_file.write_text(json.dumps({"state": "ready", "slots": direct_slots}))
        direct_ok = MODULE.verify_legacy(pm, pr, pi)
        assert direct_ok["state"] == "verified", direct_ok
        assert direct_ok["roleRoutes"]["digit"] == ["OSans-Solid-Digits-VF.ttf"], direct_ok
        inventory_file.write_text(json.dumps({"state": "pending", "slots": direct_slots}))
        pending_inventory = MODULE.verify_legacy(pm, pr, pi)
        assert pending_inventory["state"] == "failed", pending_inventory
        inventory_file.unlink()
        write(pr / px, stock_xml)

        # The global ColorOS composite slot is named SysFont-Regular, unlike a
        # dedicated Latin Roboto; it still needs a real stock XML route.
        coloros_rel = "system/fonts/SysFont-Regular.ttf"
        coloros_digest = write(pm / ".luoshu-payload" / coloros_rel, b"coloros-composite")
        write(pr / coloros_rel, b"coloros-composite")
        manifest.write_text(f"{coloros_rel}|{coloros_digest}\n")
        write(pr / px, b'<familyset><family name="sans-serif"><font>SysFont-Regular.ttf</font></family></familyset>')
        coloros_ok = MODULE.verify_legacy(pm, pr, pi)
        assert coloros_ok["state"] == "verified", coloros_ok
        assert coloros_ok["cjkRoutes"] == ["SysFont-Regular.ttf"], coloros_ok
        # A product XML pointing at product/fonts/SysFont-Regular cannot prove
        # a system/fonts replacement with the same basename.
        (pr / px).unlink()
        write(pr / "product/etc/fonts.xml", b'<familyset><family name="sans-serif"><font>SysFont-Regular.ttf</font></family></familyset>')
        write(pr / "product/fonts/SysFont-Regular.ttf", b"unrelated-stock")
        cross_partition = MODULE.verify_legacy(pm, pr, pi)
        assert cross_partition["state"] == "failed", cross_partition
        assert "cjk-route-not-proven" in cross_partition["failures"], cross_partition

        # This mock runs on Windows too, so namespace-relative absolute link
        # expansion is covered even where creating Unix links is unavailable.
        alias = pr / "product/fonts/StockCJK.ttf"
        with patch.object(Path, "is_symlink", lambda path: path == alias), patch.object(MODULE.os, "readlink", return_value="/system/fonts/SysFont-Regular.ttf"):
            actual, logical = MODULE.namespace_file(pr, "/product/fonts/StockCJK.ttf")
            assert actual == pr / coloros_rel, (actual, logical)
            assert logical == coloros_rel, (actual, logical)

        # Check absolute symlink expansion without depending on host /system.
        if os.name != "nt":
            (pr / "product/fonts/SysFont-Regular.ttf").unlink()
            (pr / "product/fonts/SysFont-Regular.ttf").symlink_to("/system/fonts/SysFont-Regular.ttf")
            linked = MODULE.verify_legacy(pm, pr, pi)
            assert linked["state"] == "verified", linked

        # Malformed canonical physical hashes are an error, not an exemption.
        manifest.write_text(f"{coloros_rel}|\n")
        try:
            MODULE.verify_legacy(pm, pr, pi)
        except MODULE.RouteError as error:
            assert str(error).startswith("physical-safe-manifest-invalid-sha256:"), error
        else:
            raise AssertionError("physical manifest accepted an empty checksum")

        missing_cjk = base / "missing-cjk"
        missing_cjk.mkdir()
        visible_missing = base / "missing-cjk-visible"
        visible_missing.mkdir()
        module2, _pid1, _su2, mountinfo2, _xml2, font_rel2 = fixture(missing_cjk)
        (visible_missing / font_rel2).parent.mkdir(parents=True, exist_ok=True)
        # Keep the XML visible while the CJK artifact is absent from PID 1.
        write(visible_missing / "system/etc/fonts.xml", (module2 / ".luoshu-payload/system/etc/fonts.xml").read_bytes())
        bad_cjk = MODULE.verify_legacy(module2, visible_missing, mountinfo2)
        assert bad_cjk["state"] == "failed", bad_cjk
        assert any("pid1-visible-missing-or-empty:" in item for item in bad_cjk["failures"]), bad_cjk

        namespace = base / "namespace"
        namespace.mkdir()
        module3, _pid1, su3, mountinfo3, _xml3, font_rel3 = fixture(namespace)
        su_namespace = MODULE.verify_legacy(module3, su3, mountinfo3)
        assert su_namespace["state"] == "verified", su_namespace
        # The su namespace sees both artifacts, but the PID 1 namespace lacks
        # the Chinese font, so passing the su root must not mask the failure.
        assert (su3 / font_rel3).is_file()
        pid1_root = namespace / "pid1-only-xml"
        write(pid1_root / "system/etc/fonts.xml", (module3 / ".luoshu-payload/system/etc/fonts.xml").read_bytes())
        bad_namespace = MODULE.verify_legacy(module3, pid1_root, mountinfo3)
        assert bad_namespace["state"] == "failed", bad_namespace
        assert "pid1-visible-missing-or-empty:" + font_rel3 in bad_namespace["failures"], bad_namespace

        # The Universal deployment contract must close the same XML-to-font
        # loop in PID 1, including role-specific CJK, Latin and digit slots.
        universal_root = base / "universal"
        module_u, pid1_u, su_u, mountinfo_u, cjk_u = universal_fixture(universal_root)
        good_u = MODULE.verify_universal(module_u, pid1_u, mountinfo_u)
        assert good_u["state"] == "verified", good_u
        assert good_u["cjkRoutes"] == [Path(cjk_u).name], good_u
        assert good_u["latinRoutes"] == ["SysSans-En-Regular.ttf"], good_u
        assert good_u["digitRoutes"] == ["OSans-Solid-Digits-VF.ttf"], good_u

        missing_u = base / "universal-missing-cjk"
        module_um, _pid1_um, _su_um, mountinfo_um, cjk_um = universal_fixture(missing_u)
        pid1_missing_u = missing_u / "pid1-missing-cjk"
        write(pid1_missing_u / "system/etc/fonts.xml", (module_um / ".luoshu-payload/system/etc/fonts.xml").read_bytes())
        bad_u = MODULE.verify_universal(module_um, pid1_missing_u, mountinfo_um)
        assert bad_u["state"] == "failed", bad_u
        assert "pid1-visible-missing-or-empty:" + cjk_um in bad_u["failures"], bad_u

        # Even with a complete su-visible tree, the main namespace fails when
        # its own route lacks the CJK file.
        assert (su_u / cjk_u).is_file()
        pid1_u_only_xml = universal_root / "pid1-only-xml"
        write(pid1_u_only_xml / "system/etc/fonts.xml", (module_u / ".luoshu-payload/system/etc/fonts.xml").read_bytes())
        bad_u_namespace = MODULE.verify_universal(module_u, pid1_u_only_xml, mountinfo_u)
        assert bad_u_namespace["state"] == "failed", bad_u_namespace
        assert "pid1-visible-missing-or-empty:" + cjk_u in bad_u_namespace["failures"], bad_u_namespace

        if os.name != "nt":
            unreadable = base / "unreadable"
            module4, pid1_unreadable, _su4, mountinfo4, _xml4, font_rel4 = fixture(unreadable)
            (pid1_unreadable / font_rel4).chmod(0o600)
            bad_permissions = MODULE.verify_legacy(module4, pid1_unreadable, mountinfo4)
            assert bad_permissions["state"] == "failed", bad_permissions
            assert "pid1-font-not-world-readable:" + font_rel4 in bad_permissions["failures"], bad_permissions

    print("font route closure and PID 1 namespace tests passed")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--phone-readback", type=Path)
    args = parser.parse_args()
    main()
    if args.phone_readback:
        print(json.dumps(phone_readback_fixture(args.phone_readback), ensure_ascii=False, sort_keys=True))
