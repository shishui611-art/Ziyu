#!/usr/bin/env python3
"""Overlay reviewed runtime files onto an exact, SHA-verified 1.1.1 release.

Does not re-sign/rebuild the APK or publish a release. Produces a test ZIP and
byte-comparison manifest; no fonts or user data are included.
"""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
BASE_SHA256 = 'c5c8fa86af4ac196107ba05c5ee8cc1944140ae033cc9e763cec8c76f40c848a'
RUNTIME = ('common/background_task.sh', 'common/font_config_overlay.py', 'common/font_config_runtime.sh', 'common/font_config_targets.py', 'common/font_config_weights.sh', 'common/font_finalize_hotfix.sh', 'common/font_inventory.py', 'common/font_inventory_scan.py', 'common/font_role_policy.py', 'common/font_safety.sh', 'common/font_slot_coverage.py', 'common/font_slot_weight.py', 'common/google_font_provider_service.sh', 'common/hyperos_metrics_batch.py', 'common/hyperos_physical_policy.py', 'common/legacy_v14_4/font_switch_safe.sh', 'common/legacy_v14_4/hyperos_clock_compat.sh', 'common/legacy_v14_4/hyperos_full_coverage.sh', 'common/task_scope.py', 'common/task_scope.sh')


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('baseline', type=Path)
    parser.add_argument('output', type=Path)
    args=parser.parse_args()
    if hashlib.sha256(args.baseline.read_bytes()).hexdigest()!=BASE_SHA256:
        raise SystemExit('Baseline SHA-256 mismatch; refusing to build')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    payload={name:(ROOT/name).read_bytes() for name in RUNTIME}
    try:
        commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
        if subprocess.run(['git','diff','--quiet'],cwd=ROOT).returncode:
            commit='local-uncommitted'
    except subprocess.CalledProcessError:
        commit='local-uncommitted'
    report={'baselineCommit':'be39f598bfb921526851c5c4a2e92905dacf4ea7',
            'sourceCommit':commit,'profile':'stable111-one-shot-test2',
            'androidDeviceValidated':False,
            'runtimeFiles':{n:hashlib.sha256(b).hexdigest() for n,b in payload.items()}}
    with zipfile.ZipFile(args.baseline) as baseline:
        prop=baseline.read('module.prop').decode()
        prop=prop.replace('name=洛书·重构版','name=洛书·1.1.1无常驻测试2')
        prop=prop.replace('description=Android 全局字体引擎：系统、OEM 与 Google 字体统一覆盖与复合',
                          'description=1.1.1专项测试：等宽保留、真实字重和系统槽补齐；未完成真机验收')
        payload['module.prop']=prop.encode()
        payload['config/stable111-repair-build.json']=(json.dumps(report,ensure_ascii=False,indent=2)+'\n').encode()
        payload['docs/stable111-oneshot-test2.md']=(ROOT/'docs/stable111-oneshot-test2.md').read_bytes()
        with zipfile.ZipFile(args.output,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=9) as result:
            for info in baseline.infolist():
                if info.filename not in payload:
                    result.writestr(copy.copy(info),baseline.read(info.filename))
            for name,data in payload.items():
                info=zipfile.ZipInfo(name)
                info.compress_type=zipfile.ZIP_DEFLATED
                mode=0o755 if name.endswith(('.sh','.py')) else 0o644
                info.external_attr=(0o100000|mode)<<16
                result.writestr(info,data)
        with zipfile.ZipFile(args.output) as result:
            assert result.testzip() is None
            assert len(result.namelist()) == len(set(result.namelist()))
            for info in baseline.infolist():
                if info.filename not in payload:
                    assert result.read(info.filename)==baseline.read(info.filename),info.filename
            for name,data in payload.items():
                assert result.read(name)==data,name
            report['unchangedApkSha256']=hashlib.sha256(result.read('bundled/Ziyu-App.apk')).hexdigest()
    report['zipSha256']=hashlib.sha256(args.output.read_bytes()).hexdigest()
    report['zipBytes']=args.output.stat().st_size
    report['zipIntegrityVerified']=True
    report['allUnmodifiedBaselineEntriesByteEqual']=True
    args.output.with_suffix('.verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(report,ensure_ascii=False,indent=2))

if __name__=='__main__': main()
