#!/usr/bin/env python3
"""Chinese App integration. Keep the standalone v1 transaction implementation intact."""
from __future__ import annotations
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
from google_font_fallback_core import *


def describe(backend: Android, journal: Journal, module: Path) -> dict:
    """Read actual component + compatible v1 undo record; never change Android."""
    current = backend.snapshot(journal.user)
    saved = journal.read()
    valid = saved is not None and same_install(saved, current)
    supported = current['declared'] and current['packageState'] in (0, 1)
    disabled = current['componentState'] == 2
    updater_disabled = current.get('updaterState', 0) == 2
    seal_checker = getattr(backend, 'sealed_cache_count', None)
    sealed_caches = seal_checker(journal.user) if callable(seal_checker) else 0
    ready = module_ready(module)
    can_restore = bool(valid and current['declared'] and
                       current['componentState'] in (2, saved['original']))
    can_enforce = bool(valid and supported and ready)
    if saved is not None and (not valid or not can_restore):
        state, title = 'conflict', '需要检查恢复记录'
        message = '组件状态或 GMS 安装身份已经变化，未覆盖其他操作。请先查看诊断信息。'
    elif not supported:
        state, title = 'unavailable', '当前环境不支持开启'
        message = '未确认字体提供组件可用，或 Google Play 服务整包已停用；不会修改整个谷歌服务。'
    elif disabled and valid:
        state, title = 'enabled', '已开启 Google 字体兼容'
        extra = []
        if updater_disabled:
            extra.append('更新调度已停用')
        if sealed_caches > 0:
            extra.append(f'已封堵 {sealed_caches} 处字体缓存')
        suffix = ('（' + '，'.join(extra) + '）') if extra else ''
        message = f'已停用 Google 字体服务{suffix}；开机将自动复检防恢复。'
    elif disabled:
        state, title = 'external', '字体组件已由其他方式停用'
        message = '没有字域的恢复记录，不能猜测原状态；请通过原操作恢复。'
    elif saved is not None:
        state, title = 'changed', 'Google 字体组件已被系统重新启用'
        message = 'GMS 后台更新可能恢复了字体组件；点击“重新加固”可再次停用并封堵缓存。'
    else:
        state, title = 'off', '尚未开启 Google 字体兼容'
        message = ('遇到 Google 应用恢复 Google Sans 时，可手动开启此兼容选项。'
                   if ready else '请先启用字域模块并应用自定义字体，再开启此兼容选项。')
    return {
        'status': 'diagnostic',
        'state': state,
        'title': title,
        'message': message,
        'user': journal.user,
        'managed': bool(valid),
        'componentDisabled': disabled,
        'updaterDisabled': updater_disabled,
        'sealedCaches': sealed_caches,
        'canEnable': bool(supported and ready and saved is None and not disabled),
        'canEnforce': can_enforce,
        'canRestore': can_restore,
        'snapshot': current,
    }


def enforce_owned(backend: Android, directory: Path, module: Path) -> dict:
    """Boot-time re-enforcement: only users with our validated journal, only when custom font is active."""
    if not directory.exists() or not module_ready(module):
        return {'status': 'unchanged', 'users': [], 'errors': [], 'message': '无需加固。'}
    enforced, errors = [], []
    with locked_store(directory):
        for path in sorted(directory.glob('user-*.json')):
            match = re.fullmatch(r'user-(\d+)[.]json', path.name)
            if not match or not 0 <= int(match[1]) <= 21474:
                continue
            user = int(match[1])
            try:
                enforce(backend, Journal(directory, user))
                enforced.append(user)
            except (FallbackError, OSError, ValueError) as error:
                errors.append({'user': user, 'message': str(error)})
    return {
        'status': 'error' if errors else ('enforced' if enforced else 'unchanged'),
        'users': enforced,
        'errors': errors,
        'message': '开机加固部分失败。' if errors else ('已重新加固已开启用户的 Google 字体兼容。' if enforced else '没有待加固记录。'),
    }


def restore_owned(backend: Android, directory: Path) -> dict:
    """Uninstall cleanup: only users with our validated journal, never all users."""
    if not directory.exists():
        return {'status': 'unchanged', 'message': '没有本功能的恢复记录。'}
    restored, errors = [], []
    with locked_store(directory):
        for path in sorted(directory.glob('user-*.json')):
            match = re.fullmatch(r'user-(\d+)[.]json', path.name)
            if not match or not 0 <= int(match[1]) <= 21474:
                continue
            user = int(match[1])
            try:
                restore(backend, Journal(directory, user))
                restored.append(user)
            except (FallbackError, OSError, ValueError) as error:
                errors.append({'user': user, 'message': str(error)})
    return {'status': 'error' if errors else 'restored', 'users': restored,
            'errors': errors, 'message': '卸载前恢复未全部完成；保留失败记录。' if errors else '已核验并恢复有记录的用户设置。'}


def main() -> int:
    parser = argparse.ArgumentParser(description='字域 Google 字体兼容：双组件停用、缓存封堵、开机加固，可恢复。')
    parser.add_argument('action', choices=('status', 'enable', 'enforce', 'enforce-owned', 'restore', 'restore-owned'), nargs='?', default='status')
    parser.add_argument('--user', type=int, help='指定 Android 用户；不处理全部用户。')
    parser.add_argument('--json', action='store_true', help='仅输出结构化结果，供内置中文界面使用。')
    args = parser.parse_args()
    try:
        if os.geteuid() != 0:
            raise FallbackError('需要 Root；未执行任何修改。')
        backend = Android()
        if args.action == 'restore-owned':
            result = restore_owned(backend, STORE)
            print(json.dumps(result, ensure_ascii=False))
            return 1 if result['status'] == 'error' else 0
        if args.action == 'enforce-owned':
            result = enforce_owned(backend, STORE, MODULE)
            print(json.dumps(result, ensure_ascii=False))
            return 1 if result['status'] == 'error' else 0
        user = args.user if args.user is not None else backend.current_user()
        if not 0 <= user <= 21474:
            raise FallbackError('无效的 Android 用户编号。')
        if args.action in ('enable', 'enforce') and not module_ready(MODULE):
            raise FallbackError('请先启用字域并应用自定义字体；未停用 Google 字体提供组件。')
        with locked_store(STORE):
            journal = Journal(STORE, user)
            if args.action == 'status':
                result = describe(backend, journal, MODULE)
            else:
                if args.action == 'enable':
                    result = enable(backend, journal)
                elif args.action == 'enforce':
                    result = enforce(backend, journal)
                else:
                    result = restore(backend, journal)
                if args.json:
                    try:
                        result['current'] = describe(backend, journal, MODULE)
                    except (FallbackError, OSError, ValueError):
                        result['refreshNeeded'] = True
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0
    except (FallbackError, OSError, ValueError) as error:
        print(json.dumps({'status': 'error', 'message': str(error)}, ensure_ascii=False),
              file=sys.stdout if args.json else sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
