#!/usr/bin/env python3
"""Reproduce the OneShot Test2 host gate using the released module's FontTools.

Usage: python3 scripts/check_stable111_round2.py --runtime /path/to/extracted/module \
           --output /path/to/host-validation
No device, system font or network access is needed; font fixtures are synthetic.
"""
from __future__ import annotations
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parents[1]
UNIT_MODULES = (
    'stable111_repair_test', 'stable111_round2_test', 'hyperos_cjk_routing_test',
    'hyperos_metrics_batch_test', 'coloros_metrics_batch_test',
    'hyperos_latin_policy_regression_test', 'stock_metric_contract_test',
    'font_inventory_symlink_test',
)
SCRIPT_CHECKS = (
    'font_config_overlay_test.py', 'font_config_monospace_test.py',
    'font_config_targets_test.py', 'font_config_batch_test.py',
    'font_inventory_test.py', 'font_inventory_scan_test.py',
    'font_config_runtime_test.sh', 'font_config_mono_coverage_test.sh',
    'mix_finalize_performance_test.sh', 'font_config_transaction_rollback_test.sh',
)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--section', choices=('all','unit','scripts'), default='all')
    args = parser.parse_args()
    site = args.runtime.resolve() / 'common/python/lib/python3.14/site-packages'
    if not (site / 'fontTools/__init__.py').is_file():
        parser.error('--runtime must contain the extracted 1.1.1 module runtime')
    args.output.mkdir(parents=True, exist_ok=True)
    env = {**os.environ, 'PYTHONPATH': os.pathsep.join(map(str, (site, ROOT/'common', ROOT/'scripts')))}
    records = []

    def run(name: str, command: list[str], limit: int = 100) -> None:
        before = time.monotonic()
        try:
            result = subprocess.run(command, cwd=ROOT, env=env, capture_output=True,
                                    text=True, timeout=limit)
            rc, text = result.returncode, result.stdout + result.stderr
        except subprocess.TimeoutExpired as error:
            rc, text = 124, f'Timeout after {limit}s: {error}\n'
        logfile = args.output / f'{name}.log'
        logfile.write_text(text, encoding='utf-8')
        counts = re.findall(r'Ran (\d+) tests?', text)
        record = {'name': name, 'returncode': rc,
                  'unittestCount': sum(map(int, counts)),
                  'seconds': round(time.monotonic()-before, 3),
                  'logSha256': hashlib.sha256(logfile.read_bytes()).hexdigest()}
        records.append(record)
        print(json.dumps(record), flush=True)

    # Parse all edited runtime Python and shell files, without executing them.
    from package_stable111_repair import RUNTIME
    syntax_errors = []
    for name in RUNTIME:
        path = ROOT/name
        if name.endswith('.py'):
            try:
                compile(path.read_text(), str(path), 'exec')
            except SyntaxError as error:
                syntax_errors.append(f'{name}: {error}')
        elif name.endswith('.sh'):
            result = subprocess.run(['sh','-n',str(path)],capture_output=True,text=True)
            if result.returncode:
                syntax_errors.append(name+': '+result.stderr)
    (args.output/'syntax.json').write_text(json.dumps({'checkedFiles':list(RUNTIME),
                                                     'errors':syntax_errors},indent=2)+'\n')
    if args.section != 'scripts':
        run('unit-regressions', [sys.executable, '-m', 'unittest', '-v', *UNIT_MODULES])
    if args.section != 'unit':
        with tempfile.TemporaryDirectory(prefix='ziyu-round2-gate-') as temp:
            font = Path(temp)/'synthetic.ttf'
            create = ('from pathlib import Path;from hyperos_cjk_routing_test import make_font;'
                      'import sys;make_font(Path(sys.argv[1]),tuple(range(32,128)))')
            subprocess.run([sys.executable,'-c',create,str(font)],cwd=ROOT,env=env,check=True,timeout=10)
            for name in SCRIPT_CHECKS:
                command = [sys.executable if name.endswith('.py') else 'sh', str(ROOT/'scripts'/name)]
                if name in ('font_inventory_test.py','font_inventory_scan_test.py'):
                    command += ['--font',str(font)]
                run(name, command)
    result = {
        'profile': 'stable111-one-shot-test2',
        'dateUTC': datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'baselineCommit':'be39f598bfb921526851c5c4a2e92905dacf4ea7',
        'sourceState': 'local candidate; not pushed to GitHub',
        'hostPython':sys.version.split()[0],
        'fontTools':'4.63.0 from immutable 1.1.1 release module',
        'unittestCount': sum(r['unittestCount'] for r in records),
        'additionalScriptChecks':len(SCRIPT_CHECKS) if args.section != 'unit' else 0,
        'section':args.section,
        'runtimeFiles':{n:hashlib.sha256((ROOT/n).read_bytes()).hexdigest() for n in RUNTIME},
        'syntaxErrors':syntax_errors,
        'allFocusedChecksPassed':not syntax_errors and all(r['returncode']==0 for r in records),
        'checks':records,
        'androidDeviceValidated':False,
        'performanceBenchmark':'not performed; no first-switch time guarantee',
        'fullRepositoryGate': {
            'passed':False,
            'lastAttempt':'check.sh stops in google_font_provider_patch_test.py at the old service fixture, which does not install task_scope.sh',
            'note':'focused gate exercises the actual one-shot service and descendant cleanup separately; full CI not claimed'
        },
        'notResolvedOrNotValidated':[
            'real-device process responsible for the reported sustained CPU was not captured',
            'actual statusbar/lockscreen rendering and reboot persistence require device verification',
            'Chrome crash and China Sports Lottery network/crash reports not diagnosed',
            'static one-weight donors cannot supply missing real weights; fallback is recorded',
            'SVG variable donors retain their variable/SVG data; not statically instanced',
            'stock TTC/OTC and nonzero-face containers remain stock, not flattened',
            'new unknown partitions beyond the ten existing runtime partitions not enabled',
            'lazy downloaded Google fonts are not watched after the one-shot pass exits'
        ]
    }
    (args.output/(args.section+'-TestReport.json')).write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    return 0 if result['allFocusedChecksPassed'] else 1

if __name__ == '__main__':
    raise SystemExit(main())
