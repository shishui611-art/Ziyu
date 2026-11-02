#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import sys
import tempfile
from copy import deepcopy
from pathlib import Path

from fontTools.fontBuilder import FontBuilder
from fontTools.pens.ttGlyphPen import TTGlyphPen

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "common"))

import universal_font_runtime_verify as runtime_verify


def make_font(path: Path, family: str, weight: int = 400, *, drop_digit: str = "") -> None:
    points = [*range(ord("0"), ord("9") + 1), ord("A"), ord("Z"), ord("a"), ord("z")]
    if drop_digit:
        points = [cp for cp in points if cp != ord(drop_digit)]
    cmap = {cp: f"u{cp:04X}" for cp in points}
    order = [".notdef", *cmap.values()]
    builder = FontBuilder(1000, isTTF=True)
    builder.setupGlyphOrder(order)
    builder.setupCharacterMap(cmap)

    glyphs = {}
    for name in order:
        pen = TTGlyphPen(None)
        if name != ".notdef":
            pen.moveTo((50, 0))
            pen.lineTo((550, 0))
            pen.lineTo((550, 700))
            pen.lineTo((50, 700))
            pen.closePath()
        glyphs[name] = pen.glyph()
    builder.setupGlyf(glyphs)
    builder.setupHorizontalMetrics({name: (600, 50) for name in order})
    builder.setupHorizontalHeader(ascent=900, descent=-200)
    builder.setupOS2(
        usWeightClass=weight,
        sTypoAscender=900,
        sTypoDescender=-200,
        sTypoLineGap=0,
        usWinAscent=900,
        usWinDescent=200,
    )
    builder.setupNameTable({
        "familyName": family,
        "styleName": "Regular",
        "fullName": family,
        "psName": family.replace(" ", "") + "-Regular",
    })
    builder.setupPost()
    builder.setupMaxp()
    path.parent.mkdir(parents=True, exist_ok=True)
    builder.save(path)


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def artifact(artifact_id: str, target_path: str, role: str, digest: str) -> dict:
    return {
        "artifactId": artifact_id,
        "targetPath": target_path,
        "role": role,
        "deploymentKinds": ["physical-slot"],
        "status": "ready",
        "sha256": digest,
        "mode": "source-as-base",
        "contract": {
            "requiredFaceIndex": 0,
            "requiredPostScriptName": "",
            "requiredAxes": [],
            "requiredWeight": 400,
            "requiredStyle": "normal",
        },
        "report": {
            "validation": {
                "alignment": {
                    "status": "ready",
                    "issues": [],
                    "anchorLimitEm": 0.08,
                    "heightDeltaLimit": 0.18,
                }
            }
        },
    }


def target(path: str, role: str, family: str) -> dict:
    return {
        "path": path,
        "families": [family],
        "role": role,
        "action": "compile",
        "status": "ready",
        "targetContract": {
            "weight": 400,
            "variable": False,
            "coverage": {},
        },
    }


def main() -> int:
    with tempfile.TemporaryDirectory(prefix="ziyu-phase8-") as raw:
        temp = Path(raw)
        visible = temp / "visible"
        physical_path = "/system/fonts/SystemLatin.ttf"
        dynamic_path = "/data/fonts/files/RuntimeDigits.ttf"
        physical = visible / physical_path.lstrip("/")
        dynamic = visible / dynamic_path.lstrip("/")
        make_font(physical, "System Latin")
        make_font(dynamic, "Runtime Digits")
        physical_sha = sha256(physical)
        dynamic_sha = sha256(dynamic)

        plan = {
            "schema": "universal-font-plan-v1",
            "planId": "sha256:phase8-plan",
            "targets": {
                physical_path: target(physical_path, "latin", "System Latin"),
                dynamic_path: target(dynamic_path, "numeric", "Runtime Digits"),
            },
        }
        artifacts = {
            "schema": "universal-font-artifacts-v1",
            "manifestId": "sha256:phase8-artifacts",
            "fontPlanId": plan["planId"],
            "artifacts": [
                artifact("ufc:physical", physical_path, "latin", physical_sha),
                {
                    **artifact("ufc:dynamic", dynamic_path, "numeric", dynamic_sha),
                    "deploymentKinds": ["dynamic-slot"],
                },
            ],
        }
        deployment = {
            "schema": "universal-font-deployment-v1",
            "deploymentId": "sha256:phase8-deployment",
            "fontPlanId": plan["planId"],
            "artifactManifestId": artifacts["manifestId"],
            "payloadDigest": "sha256:phase8-payload",
            "files": [{
                "kind": "physical-font",
                "logicalPath": physical_path,
                "payloadPath": physical_path.lstrip("/"),
                "sha256": physical_sha,
                "artifactId": "ufc:physical",
            }],
            "dynamicMounts": [{
                "targetPath": dynamic_path,
                "sourcePayloadPath": ".ziyu-dynamic/ufc-dynamic.ttf",
                "sha256": dynamic_sha,
                "artifactId": "ufc:dynamic",
                "readOnly": True,
            }],
        }
        runtime_conf = {
            "state": "active",
            "deploymentId": deployment["deploymentId"],
            "payloadDigest": deployment["payloadDigest"],
        }
        mount_state = {
            "state": "mounted",
            "deploymentId": deployment["deploymentId"],
            "payloadDigest": deployment["payloadDigest"],
            "dynamicMounted": "1",
        }
        mountinfo = {
            str(dynamic): {
                "readOnly": True,
                "source": str(temp / "payload/.ziyu-dynamic/ufc-dynamic.ttf"),
                "options": ["ro"],
                "superOptions": ["ro"],
            }
        }
        dump = "SystemLatin.ttf System Latin RuntimeDigits.ttf Runtime Digits"

        result = runtime_verify.verify(
            plan,
            artifacts,
            deployment,
            runtime_conf=runtime_conf,
            mount_state=mount_state,
            font_dump=dump,
            mountinfo=mountinfo,
            active_font="Test Family",
            visible_root=visible,
            boot_id="boot-pass",
        )
        assert result["grade"] == "PASS", result
        assert result["summary"]["fontManagerHits"] == 2, result
        assert result["summary"]["dynamicMounted"] == 1, result

        warning = runtime_verify.verify(
            plan,
            artifacts,
            deployment,
            runtime_conf=runtime_conf,
            mount_state=mount_state,
            font_dump="",
            mountinfo=mountinfo,
            active_font="Test Family",
            visible_root=visible,
            boot_id="boot-warn",
        )
        assert warning["grade"] == "WARN", warning
        assert "font-manager-dump-unavailable" in warning["warnings"], warning

        bad_deployment = deepcopy(deployment)
        bad_deployment["files"][0]["sha256"] = "0" * 64
        failed = runtime_verify.verify(
            plan,
            artifacts,
            bad_deployment,
            runtime_conf=runtime_conf,
            mount_state=mount_state,
            font_dump=dump,
            mountinfo=mountinfo,
            active_font="Test Family",
            visible_root=visible,
            boot_id="boot-fail",
        )
        assert failed["grade"] == "FAIL", failed
        assert any(reason.startswith("visible-file-hash-mismatch:") for reason in failed["failures"]), failed

        partial_dynamic = visible / "data/fonts/files/PartialDigits.ttf"
        make_font(partial_dynamic, "Partial Digits", drop_digit="7")
        partial_sha = sha256(partial_dynamic)
        partial_plan = deepcopy(plan)
        partial_artifacts = deepcopy(artifacts)
        partial_deployment = deepcopy(deployment)
        partial_target = partial_plan["targets"].pop(dynamic_path)
        partial_target["path"] = "/data/fonts/files/PartialDigits.ttf"
        partial_target["families"] = ["Partial Digits"]
        partial_plan["targets"]["/data/fonts/files/PartialDigits.ttf"] = partial_target
        partial_artifact = partial_artifacts["artifacts"][1]
        partial_artifact["targetPath"] = "/data/fonts/files/PartialDigits.ttf"
        partial_artifact["sha256"] = partial_sha
        partial_deployment["dynamicMounts"][0]["targetPath"] = "/data/fonts/files/PartialDigits.ttf"
        partial_deployment["dynamicMounts"][0]["sha256"] = partial_sha
        partial_mounts = {
            str(partial_dynamic): {
                "readOnly": True,
                "source": str(temp / "payload/.ziyu-dynamic/ufc-dynamic.ttf"),
                "options": ["ro"],
                "superOptions": ["ro"],
            }
        }
        partial_failed = runtime_verify.verify(
            partial_plan,
            partial_artifacts,
            partial_deployment,
            runtime_conf=runtime_conf,
            mount_state=mount_state,
            font_dump="PartialDigits.ttf Partial Digits",
            mountinfo=partial_mounts,
            active_font="Test Family",
            visible_root=visible,
            boot_id="boot-partial-digits",
        )
        assert partial_failed["grade"] == "FAIL", partial_failed
        assert any(reason.startswith("coverage-digits-incomplete:") for reason in partial_failed["failures"]), partial_failed

        cjk_plan = deepcopy(plan)
        cjk_plan["targets"][physical_path]["role"] = "cjk"
        cjk_failed = runtime_verify.verify(
            cjk_plan,
            artifacts,
            deployment,
            runtime_conf=runtime_conf,
            mount_state=mount_state,
            font_dump=dump,
            mountinfo=mountinfo,
            active_font="Test Family",
            visible_root=visible,
            boot_id="boot-cjk",
        )
        assert cjk_failed["grade"] == "FAIL", cjk_failed
        assert any(reason.startswith("coverage-cjk-missing:") for reason in cjk_failed["failures"]), cjk_failed

        mountinfo_file = temp / "mountinfo"
        mountinfo_file.write_text(
            f"36 25 0:32 / {dynamic} ro,relatime - tmpfs tmpfs ro\n",
            encoding="utf-8",
        )
        parsed = runtime_verify._mounts(mountinfo_file)
        assert str(dynamic) in parsed and parsed[str(dynamic)]["readOnly"] is True

    print("universal_font_runtime_verify_test: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
