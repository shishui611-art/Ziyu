#!/usr/bin/env python3
"""Build missing-glyph-preserving OEM payloads inside the safe-switch transaction.

No XML rewrite, system write, publication mount or background worker.
Missing OEM inputs are acquired only through owned read-only views during apply.
Only static donors are admitted; variable fonts are never silently flattened.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import shutil
import sys
import tempfile
import unicodedata

from fontTools.ttLib import TTFont

import font_coverage

SCHEMA = 'ziyu-script-payload-v2'
REPORT = '.ziyu-script-report.json'
from ziyu_limits import limit_bytes as _limit_bytes
MAX_BYTES = max(384 * 1024 * 1024, _limit_bytes())
MAX_TARGETS = 128
MAX_FACES = 512
MAX_SOURCE = _limit_bytes()
JAPANESE = ((0x3040, 0x30FF), (0x31F0, 0x31FF), (0xFF61, 0xFF9F),
            (0x1AFF0, 0x1AFFF), (0x1B000, 0x1B16F))
KOREAN = ((0x1100, 0x11FF), (0x3130, 0x318F), (0xA960, 0xA97F),
          (0xAC00, 0xD7A3), (0xD7B0, 0xD7FF), (0xFFA0, 0xFFDC))
HAN = ((0x3400, 0x9FFF), (0xF900, 0xFAFF), (0x20000, 0x323AF))


def identity(path):
    value = path.stat()
    return (value.st_dev, value.st_ino, value.st_size, value.st_mtime_ns, value.st_ctime_ns)


def collection(path):
    with path.open('rb') as stream:
        return stream.read(4) == b'ttcf'


def script_filter(languages, regional_han=False):
    if 'fallback' in languages:
        # Only normal writing characters. Private-use icons, emoji and complex
        # script shaping remain OEM-owned; changing an outline alone cannot
        # safely transfer their semantics or shaping programs.
        def writing(cp):
            if cp < 0x20 or cp > 0x323AF:
                return None
            category = unicodedata.category(chr(cp))
            if category[0] == 'C':
                return None
            ordinary_symbol = (category == 'Sc' or 0x2100 <= cp <= 0x22FF
                               or 0x2460 <= cp <= 0x25FF)
            if category[0] == 'S' and not ordinary_symbol:
                return None
            if 0x30 <= cp <= 0x39:
                return 'digit'
            if (0x20 <= cp <= 0x024F or 0x1E00 <= cp <= 0x1EFF):
                return 'latin'
            return 'cjk' if ordinary_symbol or any(lo <= cp <= hi for lo, hi in
                                HAN + JAPANESE + KOREAN + ((0x2000, 0x206F),
                                (0x2E80, 0x303F), (0xFF00, 0xFFEF))) else None
        return writing
    ranges = (() if 'japanese' not in languages else JAPANESE)
    ranges += (() if 'korean' not in languages else KOREAN)
    if regional_han:
        ranges += HAN + ((0x3000, 0x303F), (0xFF01, 0xFF60))
    return lambda cp: 'cjk' if any(lo <= cp <= hi for lo, hi in ranges) else None


def plan(source):
    if source.is_symlink() or not 0 < source.stat().st_size <= MAX_SOURCE:
        raise ValueError('字体文件无效或超过大小限制')
    before = identity(source)
    info = font_coverage.inspect(source)
    languages = info['scriptLanguages']
    if info['safe']:
        return {'mode': 'global', 'languages': [], 'sourceFace': info['face']}
    # Use the face carrying the actual Chinese/Latin text, not whichever face
    # happens to have the strongest Japanese/Korean classification.
    index = info['face']
    if not (languages or info['coreHan'] >= 32
            or info['coverage'].get('latin', 0) >= 95
            or info['coverage'].get('digits', 0) == 100):
        raise ValueError('这款字体缺少可替换的常用文字，请选择其他字体')
    kwargs = {'fontNumber': index} if collection(source) else {}
    with TTFont(source, lazy=True, recalcTimestamp=False, **kwargs) as font:
        if 'fvar' in font or 'CFF2' in font:
            raise ValueError('这款可变字体暂不支持缺字补齐，当前字体保持不变；未固定字重')
        if any(tag in font for tag in ('COLR', 'CBDT', 'sbix', 'SVG ')):
            raise ValueError('彩色字体暂不支持此应用方式')
    if identity(source) != before:
        raise ValueError('字体在检查期间已变化，请重新应用')
    return {'mode': 'fallback', 'languages': languages, 'sourceFace': index}


def fallback_target(logical, data, refs):
    """Do not restyle a specialized script merely because it includes ASCII."""
    label = logical.rsplit('/', 1)[-1].lower()
    if any(word in label for word in ('emoji', 'symbol', 'icon', 'clock', 'math',
                                      'music', 'mono', 'dingbat', 'barcode')):
        return False
    if logical in data['slots']:
        return True
    if any(word in label for word in ('cjkjp', 'cjkkr', 'cjk-', 'cjk.', 'sanscjk')):
        return True
    allowed = {'zh', 'en', 'ja', 'ko', 'und-latn', 'und-hani', 'und-jpan', 'und-kore'}
    for node in refs:
        if node.get('resolvedPath') != logical:
            continue
        tags = re.split(r'[,\s]+', node.get('familyAttributes', {}).get('lang', '').lower())
        if not any(tags) or any(tag in allowed or tag.split('-')[0] in allowed for tag in tags):
            return True
    return False


def logical_path(value):
    from font_slot_overrides import logical_path as checked
    result = checked(value)
    parts = Path(result).parts
    if len(parts) < 4 or parts[2] != 'fonts' or any(part.startswith('.') for part in parts[3:]):
        raise ValueError('原厂字体路径无效')
    return result


def _load_context(module, *, prepare=False):
    import font_inventory
    import stock_font_snapshot
    inventory_path = module / 'config/device_font_inventory.json'
    if inventory_path.is_symlink() or inventory_path.stat().st_size > 8 * 1024 * 1024:
        raise ValueError('原厂字体清单无效')
    data = font_inventory.load_inventory(inventory_path)
    refs = data.get('xmlGraph', {}).get('refs', [])
    if not isinstance(refs, list) or len(refs) > 16384:
        raise ValueError('原厂字体引用清单无效')
    if prepare:
        from font_slot_overrides import exclusion, load as rules_load
        rules = rules_load(module / 'config/font-slot-overrides.json')
        requested = set()
        values = [*data['slots'], *(node.get('resolvedPath') for node in refs)]
        for value in values:
            if not isinstance(value, str) or not value:
                continue
            try:
                logical = logical_path(value)
            except ValueError:
                continue
            if fallback_target(logical, data, refs) and not exclusion(logical, rules):
                requested.add(logical)
        if len(requested) > MAX_TARGETS:
            raise ValueError('本机文字字体数量超出处理上限，当前字体保持不变')
        stock_font_snapshot.ensure_expansion(module, sorted(requested))
    snapshot = stock_font_snapshot.load(module)
    if not snapshot.get('files') or len(snapshot['files']) > 2048:
        raise ValueError('缺少可信原厂字体，请先在开机保护中重新扫描')
    if data.get('buildKey') != snapshot.get('buildKey'):
        raise ValueError('系统已变化，请先重新扫描原厂字体')
    return data, snapshot, refs


def _check_stage(module, stage):
    from font_role_policy import assert_isolated
    assert_isolated(module, stage)
    resolved = stage.resolve()
    if (stage.is_symlink() or not stage.is_dir() or not resolved.is_relative_to(module.resolve())
            or resolved in ((module / '.ziyu-payload-next').resolve(), module.resolve())):
        raise ValueError('字体只能在本次事务暂存目录生成')


def preserved_paths(data, snapshot):
    # A partial snapshot must not grant boot repair permission to fill other
    # languages with this donor. Include known references even without saved bytes.
    paths = set(snapshot['files']) | set(data['slots'])
    for node in data.get('xmlGraph', {}).get('refs', []):
        value = node.get('resolvedPath') if isinstance(node, dict) else None
        if not isinstance(value, str) or not value:
            continue
        try:
            paths.add(logical_path(value))
        except ValueError:
            pass  # Non-deployable XML references remain outside this publisher.
    return paths


def build(module, stage, source):
    from composite_collection_build import (_compile_face_task, _save_collection,
                                           contract_matches, specialized, verify_dependency_copies)
    from composite_collection_contract import collection_faces
    from font_digest_cache import FontDigestCache
    from font_role_policy import record_preserved
    from font_slot_overrides import load as rules_load, exclusion, digest as rules_digest
    from font_structure import validate_font, table_directory_tags
    import stock_font_snapshot

    _check_stage(module, stage)
    source_before = identity(source)
    selection = plan(source)
    if selection['mode'] != 'fallback':
        raise ValueError('此字体应使用完整替换路径')
    data, snapshot, refs = _load_context(module, prepare=True)
    rules = rules_load(module / 'config/font-slot-overrides.json')
    digests = FontDigestCache()
    source_hash = digests.sha256(source)
    source_kwargs = {'fontNumber': selection['sourceFace']} if collection(source) else {}
    report = {'schema': SCHEMA, 'sourceSha256': source_hash, 'sourceFace': selection['sourceFace'],
              'languages': selection['languages'], 'applicationMode': 'fallback',
              'buildKey': snapshot['buildKey'],
              'rulesSha256': rules_digest(rules), 'files': [], 'preserved': [],
              'mode': 'static-donor-original-oem-layout', 'result': 'BUILDING'}
    total_bytes = total_faces = 0
    with tempfile.TemporaryDirectory(prefix='.script-build-', dir=stage) as tmp:
        tmp = Path(tmp)
        donor_path = source
        if collection(source):
            donor_path = tmp / 'donor.font'
            with TTFont(source, lazy=True, recalcTimestamp=False, **source_kwargs) as donor:
                donor.save(donor_path, reorderTables=False)
        with TTFont(donor_path, lazy=True, recalcTimestamp=False) as donor:
            validate_font(donor, donor_path.stat().st_size, decode_all=False)
            donor_points = set(donor.getBestCmap() or {})
            recordings, caches, generated = {}, {}, []
            for logical in sorted(snapshot['files']):
                logical = logical_path(logical)
                if (exclusion(logical, rules)
                        or not fallback_target(logical, data, refs)):
                    continue
                # Read existing captured font headers/cmaps only, never partitions.
                stock = stock_font_snapshot.resolve(logical, module, digests)
                if stock is None:
                    raise ValueError('原厂字体快照已变化，请重新扫描')
                stock_before = identity(stock)
                count = collection_faces(stock) if collection(stock) else 1
                if count > MAX_FACES:
                    raise ValueError('原厂字体集合过大，本次未应用')
                tasks = []
                for index in range(count):
                    with TTFont(stock, fontNumber=index, lazy=True, recalcTimestamp=False) as font:
                        region = False
                        allowed = script_filter(['fallback'])
                        points = font.getBestCmap() or {}
                        eligible = not specialized(font) and any(cp in donor_points and allowed(cp) for cp in points)
                    tasks.append({'face': index, 'stock': str(stock), 'donor': str(donor_path),
                                  'output': str(tmp / f'face-{index}.font'),
                                  'scriptScope': ['fallback'], 'regionalHan': region,
                                  'scriptRetain': not eligible})
                if not any(not task['scriptRetain'] for task in tasks):
                    continue
                if len(generated) >= MAX_TARGETS or total_faces + count > MAX_FACES:
                    raise ValueError('本机字体数量超出本次处理上限，当前字体保持不变')
                faces = []
                intermediate_bytes = 0
                for task in tasks:
                    key = ('fallback',)
                    cache = caches.setdefault(key, {})
                    item = _compile_face_task(task, recordings, cache, donor)
                    intermediate_bytes += Path(task['output']).stat().st_size
                    if total_bytes + intermediate_bytes > MAX_BYTES:
                        raise ValueError('字体暂存文件超出空间预算，当前字体保持不变')
                    item['regionalHan'] = task['regionalHan']
                    faces.append(item)
                if not any(item['details']['mode'] == 'compiled' for item in faces):
                    continue
                output = tmp / f'output-{len(generated)}.font'
                if count == 1 and not collection(stock):
                    shutil.copyfile(tasks[0]['output'], output)
                else:
                    fonts = [TTFont(task['output'], lazy=True, recalcBBoxes=False, recalcTimestamp=False) for task in tasks]
                    try:
                        _save_collection(fonts, output, min(MAX_BYTES - total_bytes, count * MAX_SOURCE))
                    finally:
                        for font in fonts:
                            font.close()
                total_bytes += output.stat().st_size
                total_faces += count
                if total_bytes > MAX_BYTES:
                    raise ValueError('字体输出超出空间预算')
                table_directory_tags(output)
                for item in faces:
                    with TTFont(stock, fontNumber=item['index'], lazy=True, recalcTimestamp=False) as original:
                        stock_order = list(original.getGlyphOrder())
                    with TTFont(output, fontNumber=item['index'], lazy=True, recalcTimestamp=False) as font:
                        if not contract_matches(font, item['contract'], item['rawTables'], stock_order,
                                                allow_bounds_expansion=item['details'].get('boundsExpansion') is True):
                            raise ValueError('字体输出未保留原厂字符映射或排版信息')
                        verify_dependency_copies(font, item['details'].get('dependencyProof'))
                if identity(stock) != stock_before:
                    raise ValueError('原厂字体在处理期间已变化')
                receipt = {'path': logical, 'stockSha256': digests.sha256(stock),
                           'sha256': digests.sha256(output), 'bytes': output.stat().st_size,
                           'faces': faces}
                generated.append((output, logical))
                report['files'].append(receipt)
                for task in tasks:
                    Path(task['output']).unlink(missing_ok=True)
        if not generated:
            raise ValueError('本机没有可安全替换的字体，当前字体保持不变')
        if identity(source) != source_before or digests.sha256(source) != source_hash:
            raise ValueError('源字体在处理期间已变化')
        current_data, current_snapshot, _ = _load_context(module)
        if (current_snapshot != snapshot or current_data != data
                or rules_digest(rules_load(module / 'config/font-slot-overrides.json')) != report['rulesSha256']):
            raise ValueError('原厂清单或保留规则在处理期间已变化')
        for output, logical in generated:
            destination = stage / logical.lstrip('/')
            destination.parent.mkdir(parents=True, exist_ok=True)
            if destination.is_symlink() or not destination.resolve().is_relative_to(stage.resolve()):
                raise ValueError('字体输出目录包含外部链接')
            os.replace(output, destination)
            destination.chmod(0o644)
        changed = {logical for _, logical in generated}
        report['preserved'] = record_preserved(stage, sorted(preserved_paths(data, snapshot) - changed))
    report.update(result='PASS', outputBytes=total_bytes)
    target = stage / REPORT
    receipt = json.dumps(report, ensure_ascii=False, separators=(',', ':')) + '\n'
    if len(receipt.encode('utf-8')) > 4 * 1024 * 1024:
        raise ValueError('字体校验记录超出限制，当前字体保持不变')
    target.write_text(receipt, encoding='utf-8')
    target.chmod(0o644)
    # The outer safe-switch stage_verify reopens the output immediately before
    # cache publication/transaction commit; do not repeat that batch here.
    return {'status': 'ok', 'languages': selection['languages'], 'files': len(report['files']),
            'sourceSha256': source_hash,
            'replacements': [{ 'path': item['path'], 'sha256': item['sha256'],
                              'replaced': sum(sum(face['details'].get('replaced', {}).values())
                                              for face in item['faces']) } for item in report['files']],
            'message': '字体已准备，缺字保留系统字体；重启后生效'}


def validate(module, stage, source):
    from composite_collection_build import contract_matches, verify_dependency_copies
    from composite_collection_contract import collection_faces
    from font_digest_cache import FontDigestCache
    from font_slot_overrides import load as rules_load, digest as rules_digest, exclusion
    import stock_font_snapshot

    _check_stage(module, stage)
    target = stage / REPORT
    if target.is_symlink() or not 0 < target.stat().st_size <= 4 * 1024 * 1024:
        raise ValueError('字体校验记录无效')
    report = json.loads(target.read_text(encoding='utf-8'))
    data, snapshot, refs = _load_context(module)
    digests = FontDigestCache()
    rules = rules_load(module / 'config/font-slot-overrides.json')
    selection = plan(source)
    if (report.get('schema') != SCHEMA or report.get('result') != 'PASS'
            or report.get('buildKey') != snapshot['buildKey']
            or report.get('languages') != selection['languages']
            or selection['mode'] != 'fallback' or report.get('applicationMode') != 'fallback'
            or report.get('sourceFace') != selection['sourceFace']
            or report.get('sourceSha256') != digests.sha256(source)
            or report.get('rulesSha256') != rules_digest(rules)):
        raise ValueError('字体缓存与源字体、系统或保留规则不一致')
    files = report.get('files', [])
    if not isinstance(files, list) or not 0 < len(files) <= MAX_TARGETS:
        raise ValueError('字体输出清单无效')
    changed = set()
    for entry in files:
        logical = logical_path(entry['path'])
        if (logical in changed or exclusion(logical, rules)
                or not fallback_target(logical, data, refs)):
            raise ValueError('字体输出重复或违反保留规则')
        changed.add(logical)
        stock = stock_font_snapshot.resolve(logical, module, digests)
        output = stage / logical.lstrip('/')
        if (stock is None or output.is_symlink() or not output.resolve().is_relative_to(stage.resolve())
                or digests.sha256(stock) != entry['stockSha256']
                or digests.sha256(output) != entry['sha256'] or output.stat().st_size != entry['bytes']):
            raise ValueError('字体输出或原厂快照校验失败')
        count = collection_faces(stock) if collection(stock) else 1
        if (collection(output) != collection(stock)
                or (collection(output) and collection_faces(output) != count)
                or len(entry['faces']) != count):
            raise ValueError('字体输出的原厂字体面数量已变化')
        for index, item in enumerate(entry['faces']):
            if item['index'] != index:
                raise ValueError('字体面顺序已变化')
            with TTFont(stock, fontNumber=index, lazy=True, recalcTimestamp=False) as font:
                stock_order = list(font.getGlyphOrder())
                if (len(stock_order) != item.get('stockGlyphCount')
                        or not contract_matches(font, item['contract'], item['rawTables'], stock_order)):
                    raise ValueError('字体证明与原厂排版信息不一致')
                if item['regionalHan'] is not False:
                    raise ValueError('字体替换范围已变化')
            with TTFont(output, fontNumber=index, lazy=True, recalcTimestamp=False) as font:
                if not contract_matches(font, item['contract'], item['rawTables'], stock_order,
                                        allow_bounds_expansion=item['details'].get('boundsExpansion') is True):
                    raise ValueError('字体输出排版信息校验失败')
                verify_dependency_copies(font, item['details'].get('dependencyProof'))
    preserved = stage / '.ziyu-stock-preserved.paths'
    if preserved.is_symlink() or preserved.read_text(encoding='utf-8').splitlines() != report['preserved']:
        raise ValueError('字体的原厂回退范围校验失败')
    if set(report['preserved']) != preserved_paths(data, snapshot) - changed:
        raise ValueError('字体缺少原厂回退记录')
    actual = set()
    for root in [*stage.glob('*/fonts'), *stage.glob('system/*/fonts')]:
        for output in root.rglob('*'):
            if output.is_symlink():
                raise ValueError('字体负载包含未记录的链接')
            if output.is_file():
                actual.add('/' + output.relative_to(stage).as_posix())
    if actual != changed:
        raise ValueError('字体负载存在未记录或缺失的文件')
    return {'status': 'ok', 'files': len(files)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('plan', 'build', 'validate'))
    parser.add_argument('source', type=Path)
    parser.add_argument('--module', type=Path)
    parser.add_argument('--stage', type=Path)
    args = parser.parse_args()
    if args.action == 'plan':
        print(plan(args.source)['mode'])
    else:
        if args.module is None or args.stage is None:
            parser.error('module and stage are required')
        result = (build if args.action == 'build' else validate)(args.module, args.stage, args.source)
        print(json.dumps(result, ensure_ascii=False))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
