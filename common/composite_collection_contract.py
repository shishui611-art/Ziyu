#!/usr/bin/env python3
"""Validate collection structure, OEM face count, and per-face build proof.

This validates structure and preserves the existing target's face count.
It neither rewrites fonts nor certifies
glyph coverage, metrics, variable axes, or an OEM's complete rendering contract.
"""
import argparse
import hashlib
import json
from pathlib import Path
import struct
from fontTools.ttLib import TTLibError

import stock_font_snapshot

SFNT = (b'\x00\x01\x00\x00', b'OTTO', b'true')
MAX_FACES = 256


def _sha256(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def _source_facts(source):
    if source is None or not Path(source).is_file() or Path(source).is_symlink():
        raise ValueError('逐面证明校验缺少当前组合字体来源')
    source = Path(source)
    from fontTools.ttLib import TTFont
    import font_source_profile
    from composite_font import _pick_face
    source_face = _pick_face(source, 'cjk', 400, None)
    kwargs = {'fontNumber': source_face} if source_face >= 0 else {}
    with TTFont(source, lazy=True, recalcTimestamp=False, **kwargs) as donor:
        return {'sha256': _sha256(source), 'face': source_face,
                'weight': font_source_profile._weight(donor),
                'axes': font_source_profile._axes(donor)}


def verify_build_report(payload, path, stock_path, relative, request, count, stock_hash,
                        require_proof=False, source_facts=None):
    proof_root = Path(payload) / '.luoshu-collection-proofs'
    if proof_root.is_symlink():
        raise ValueError('集合生成证明目录不能是符号链接')
    key = hashlib.sha256(relative.encode('utf-8')).hexdigest() + '.json'
    sidecar = proof_root / key
    if not sidecar.exists():
        if require_proof:
            raise ValueError('集合缺少本次请求的逐面生成证明')
        return False
    if sidecar.is_symlink() or not sidecar.is_file():
        raise ValueError('集合生成证明不是受信任的普通文件')
    if stock_path is None:
        raise ValueError('集合生成证明缺少可验证的原厂快照')
    with sidecar.open('rb') as stream:
        raw = stream.read(1024 * 1024 + 1)
    if len(raw) > 1024 * 1024:
        raise ValueError('集合生成证明过大')
    def unique(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError('集合生成证明包含重复字段')
            result[key] = value
        return result
    proof = json.loads(raw, object_pairs_hook=unique)
    if (not isinstance(proof, dict) or proof.get('schema') != 'composite-collection-build-v1'
            or proof.get('result') != 'PASS' or proof.get('requestId') != request
            or proof.get('target') != '/' + relative or proof.get('stockFaces') != count
            or proof.get('stockSha256') != stock_hash
            or not isinstance(proof.get('faces'), list) or len(proof['faces']) != count
            or any(not isinstance(face, dict) or face.get('index') != index
                   for index, face in enumerate(proof['faces']))):
        raise ValueError('集合生成证明与本次请求、路径或字体面索引不符')
    if proof.get('selectionModel') != 'selected-fixed-weight':
        raise ValueError('集合证明未说明静态组合字重策略')
    if (proof.get('selectedAxesTraceable') is not False
            or not isinstance(proof.get('selectedAxesNote'), str)
            or not proof['selectedAxesNote']):
        raise ValueError('集合证明未说明组合源选轴值的可追溯状态')
    if require_proof and source_facts is None:
        raise ValueError('逐面证明校验缺少当前组合字体来源')
    if source_facts is not None:
        if source_facts['sha256'] != proof.get('sourceSha256'):
            raise ValueError('集合证明所绑定的组合来源哈希已变化')
        if source_facts['face'] != proof.get('sourceFace'):
            raise ValueError('集合证明所绑定的组合来源面索引已变化')
        actual_weight = source_facts['weight']
        actual_axes = source_facts['axes']
        if (not isinstance(proof.get('donor'), dict)
                or proof['donor'].get('weight') != actual_weight
                or proof['donor'].get('axes') != actual_axes):
            raise ValueError('集合证明中的 donor 字重或轴与实际组合来源不符')
    for face in proof['faces']:
        if (not isinstance(face.get('contract'), dict)
                or not isinstance(face['contract'].get('tables'), dict)
                or not isinstance(face['contract'].get('glyphOrder'), str)
                or not isinstance(face['contract'].get('frame'), list)
                or len(face['contract']['frame']) != 5):
            raise ValueError('集合证明缺少逐面原厂布局契约')
        if face.get('targetStylePreserved') is not True:
            raise ValueError('集合证明未确认原厂字体面样式保留')
        if (not isinstance(face.get('targetWeight'), int)
                or not isinstance(face.get('targetStyle'), str)
                or not isinstance(face.get('donorWeight'), int)
                or not isinstance(face.get('donorAxes'), list)
                or face.get('selectedAxesTraceable') is not False):
            raise ValueError('集合证明缺少准确的 target/donor 字重、样式或轴信息')
        if source_facts is not None and (
                face['donorWeight'] != actual_weight or face['donorAxes'] != actual_axes):
            raise ValueError('逐面证明的 donor 字重或轴与本次组合来源不符')
        if face.get('mode') == 'compiled' and face.get('replacementVariation') != 'selected-fixed-weight':
            raise ValueError('集合证明的字重策略与组合输入不一致')
        if face.get('mode') == 'compiled' and (
                not isinstance(face.get('targetWeight'), int)
                or not isinstance(face.get('donorWeight'), int)
                or not isinstance(face.get('donorAxes'), list)):
            raise ValueError('集合证明缺少目标或来源字重/轴信息')
    if proof.get('outputSha256') != _sha256(path):
        raise ValueError('集合字节与生成证明不符')
    # Re-open the final collection and compare every face to its independently
    # recorded protected-table contract. A well-shaped TTC and matching face
    # count alone do not prove that layout, UVS, style, or metric tables survived.
    from composite_collection_build import contract_matches
    from fontTools.ttLib import TTFont
    import font_source_profile
    for index, face in enumerate(proof['faces']):
        # Bind the per-face contract to the independently resolved, byte-
        # verified OEM snapshot. Otherwise a damaged sidecar could be edited
        # to describe an arbitrary output and its matching hash.
        with TTFont(stock_path, fontNumber=index, lazy=True,
                    recalcTimestamp=False) as stock_face:
            if not contract_matches(stock_face, face['contract'], face.get('rawTables')):
                raise ValueError(f'集合面 {index} 的契约与已验证原厂快照不符')
            if (font_source_profile._weight(stock_face) != face['targetWeight']
                    or font_source_profile._best_subfamily(stock_face) != face['targetStyle']):
                raise ValueError(f'集合面 {index} 的目标字重或样式与原厂快照不符')
        with TTFont(path, fontNumber=index, lazy=True, recalcTimestamp=False) as font:
            if not contract_matches(font, face['contract'], face.get('rawTables')):
                raise ValueError(f'集合面 {index} 与本次原厂布局契约不符')
            if (font_source_profile._weight(font) != face['targetWeight']
                    or font_source_profile._best_subfamily(font) != face['targetStyle']):
                raise ValueError(f'集合面 {index} 的目标字重或样式与证明不符')
    return True


def collection_faces(path):
    size = path.stat().st_size
    with path.open('rb') as stream:
        head = stream.read(12)
        if len(head) != 12 or head[:4] != b'ttcf':
            raise ValueError('TTC/OTC 路径被单字体或损坏文件覆盖')
        version, count = struct.unpack('>II', head[4:])
        if version not in (0x10000, 0x20000) or not 1 <= count <= MAX_FACES:
            raise ValueError('字体集合版本或字体面数量无效')
        offsets = stream.read(4 * count)
        if len(offsets) != 4 * count:
            raise ValueError('字体集合索引被截断')
        header_size = 12 + 4 * count + (12 if version == 0x20000 else 0)
        if size < header_size:
            raise ValueError('字体集合头被截断')
        for offset in struct.unpack('>' + 'I' * count, offsets):
            if offset < header_size or offset > size - 12:
                raise ValueError('字体集合索引越界')
            stream.seek(offset)
            face = stream.read(12)
            if face[:4] not in SFNT:
                raise ValueError('字体集合包含无效字体面')
            tables = struct.unpack('>H', face[4:6])[0]
            if not 1 <= tables <= 4096 or offset + 12 + tables * 16 > size:
                raise ValueError('字体集合表目录无效')
            directory = stream.read(tables * 16)
            for index in range(tables):
                _, _, start, length = struct.unpack('>4sIII', directory[index * 16:(index + 1) * 16])
                if start > size or length > size - start:
                    raise ValueError('字体集合数据表越界')
    return count


def validate(payload, request, module, require_proof=False, source=None):
    payload = Path(payload)
    module = Path(module)
    if not payload.is_dir() or payload.is_symlink():
        raise ValueError('字体集合暂存负载不存在或包含链接')
    if not request or len(request) > 160:
        raise ValueError('字体集合请求标识无效')
    report = {'schema': 'composite-collection-contract-v1', 'requestId': request,
              'result': 'PASS', 'collections': [], 'errors': []}
    proof_root = payload / '.luoshu-collection-proofs'
    if proof_root.exists():
        if proof_root.is_symlink() or not proof_root.is_dir():
            report['errors'].append({'path': '.luoshu-collection-proofs',
                                     'reason': '集合证明目录不是受信任的普通目录'})
        else:
            proof_entries = list(proof_root.iterdir())
            if len(proof_entries) > 16 or any(
                    item.is_symlink() or not item.is_file() or item.suffix != '.json'
                    or item.stat().st_size > 1024 * 1024 for item in proof_entries):
                report['errors'].append({'path': '.luoshu-collection-proofs',
                                         'reason': '集合证明目录包含越界、链接或过大文件'})
    required = set()
    if require_proof:
        import font_inventory
        inventory_path = module / 'config/device_font_inventory.json'
        inventory = font_inventory.load_inventory(inventory_path)
        for logical, entry in inventory['slots'].items():
            if (Path(logical).suffix.lower() in ('.ttc', '.otc')
                    and (entry.get('uiEligible') is True or entry.get('source') == 'verified-scan')):
                required.add(Path(logical.lstrip('/')).as_posix())
    try:
        source_facts = _source_facts(source) if source is not None else None
    except (OSError, ValueError, RuntimeError, TTLibError) as error:
        source_facts = None
        report['errors'].append({'path': '.luoshu-collection-proofs',
                                 'reason': str(error)})
    if require_proof and source_facts is None:
        report['errors'].append({'path': '.luoshu-collection-proofs',
                                 'reason': '逐面证明校验缺少当前组合字体来源'})
    seen = set()
    for path in sorted(payload.rglob('*')):
        if path.suffix.lower() not in ('.ttc', '.otc'):
            continue
        relative = path.relative_to(payload).as_posix()
        try:
            if path.is_symlink() or not path.is_file():
                raise ValueError('字体集合是符号链接或不是普通文件')
            count = collection_faces(path)
            logical = '/' + relative
            stock = stock_font_snapshot.resolve(logical, module)
            if stock is None:
                if relative in required:
                    raise ValueError('本机原厂字体快照不可验证')
                stock_count, stock_hash = None, ''
            else:
                stock_count = collection_faces(stock)
                snapshot_entry = stock_font_snapshot.load(module).get('files', {}).get(logical)
                snapshot_hash = (snapshot_entry.get('sha256', '')
                                 if isinstance(snapshot_entry, dict) else '')
                stock_hash = (snapshot_hash if isinstance(snapshot_hash, str)
                              and len(snapshot_hash) == 64
                              and all(char in '0123456789abcdef' for char in snapshot_hash)
                              and snapshot_entry.get('size') == stock.stat().st_size
                              else _sha256(stock))
                if count != stock_count:
                    raise ValueError(f'字体集合字体面数量变化：生成 {count}，原厂 {stock_count}')
            built = verify_build_report(payload, path, stock, relative, request, count,
                                        stock_hash, require_proof=relative in required,
                                        source_facts=source_facts)
            if relative in required:
                seen.add(relative)
            report['collections'].append({'path': relative, 'faces': count,
                                          'stockFaces': stock_count,
                                          'generatedContractVerified': built})
        except (OSError, ValueError, RuntimeError, TTLibError, struct.error) as error:
            report['errors'].append({'path': relative, 'reason': str(error)})
    for relative in sorted(required - seen):
        report['errors'].append({'path': relative, 'reason': '本次安全切换缺少目标集合或逐面证明'})
    if report['errors']:
        report['result'] = 'FAIL'
    return report


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--payload', type=Path, required=True)
    parser.add_argument('--module', type=Path, required=True)
    parser.add_argument('--request', required=True)
    parser.add_argument('--require-proof', action='store_true')
    parser.add_argument('--source', type=Path)
    args = parser.parse_args()
    report = validate(args.payload, args.request, args.module,
                      require_proof=args.require_proof, source=args.source)
    if report['result'] != 'PASS':
        for error in report['errors']:
            print('[MIX] 集合格式核查失败：' + error['path'] + '：' + error['reason'], flush=True)
        return 1
    print(json.dumps(report, ensure_ascii=False, separators=(',', ':')))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
