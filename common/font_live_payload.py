#!/usr/bin/env python3
"""Immutable live font generations and durable runtime transaction records."""
import hashlib
import errno
import json
import os
from pathlib import Path
import re
import shutil
import stat
import sys


RECORDS = (
    'self-mount.conf', 'self-mount-required.conf', 'mount-backend.conf',
    'mount-backend-verification.json', 'font-apply-result.conf',
    'font-payload-manifest.conf', 'device-font-load-verification.conf',
    'device-font-load-route-verification.json', 'font-mount-warnings.conf', 'font-ui-cache.json', 'font-live.conf', 'font-live-previous.conf',
)


def values(path):
    try:
        return dict(line.split('=', 1) for line in Path(path).read_text().splitlines() if '=' in line)
    except OSError:
        return {}


def atomic(path, data):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + f'.tmp.{os.getpid()}')
    with temporary.open('w', encoding='utf-8') as stream:
        stream.write(data)
        stream.flush()
        os.fsync(stream.fileno())
    os.chmod(temporary, 0o600)
    os.replace(temporary, path)
    fd = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def conf(path, data):
    if any('\n' in str(v) or '\r' in str(v) for v in data.values()):
        raise ValueError('invalid runtime record')
    atomic(path, ''.join(f'{key}={value}\n' for key, value in data.items()))


def current(module):
    module = Path(module)
    record = values(module / 'config/font-live.conf')
    if record.get('schema') != 'ziyu-live-font-v1' or record.get('state') != 'mounted':
        return {}
    if not record.get('font') or not record.get('request_id'):
        return {}
    try:
        boot = Path('/proc/sys/kernel/random/boot_id').read_text().strip()
    except OSError:
        return {}
    if record.get('schema') != 'ziyu-live-font-v1' or record.get('state') != 'mounted' or record.get('boot_id') != boot:
        return {}
    source, work = Path(record.get('source', '')), Path(record.get('work_root', ''))
    if source != module / '.luoshu-payload':
        if source.parent != module / '.luoshu-state/cache/live' / boot or not re.fullmatch(r'generation-[0-9a-f]{64}', source.name):
            return {}
    if work.parent != Path('/data/adb/luoshu/live-mount') or not work.name.startswith(boot + '.'):
        return {}
    if source.is_symlink() or work.is_symlink() or not source.is_dir() or not work.is_dir():
        return {}
    try:
        if (work / 'boot-id').read_text().strip() != boot or not (work / 'mounts.list').stat().st_size:
            return {}
    except OSError:
        return {}
    return record


def work_root(module=None):
    explicit = os.environ.get('LUOSHU_SELF_MOUNT_STATE_ROOT')
    if explicit:
        return Path(explicit)
    module = module or os.environ.get('MODULE_DIR') or os.environ.get('MODDIR') or Path(__file__).resolve().parent.parent
    record = current(module)
    return Path(record['work_root']) if record else Path('/data/adb/luoshu/self-mount')


def label(path):
    try:
        return os.getxattr(path, 'security.selinux', follow_symlinks=False)
    except OSError as error:
        if error.errno in (errno.ENODATA, errno.ENOTSUP):
            return None
        raise


def copy_label(source, destination):
    value = label(source)
    if value is not None:
        os.setxattr(destination, 'security.selinux', value, follow_symlinks=False)
        if label(destination) != value:
            raise ValueError('generation SELinux label mismatch')


def prepare(source, cache, boot, temporary):
    source, cache, temporary = map(Path, (source, cache, temporary))
    if source.is_symlink() or not source.is_dir() or cache.is_symlink() or temporary.exists():
        raise ValueError('unsafe generation directory')
    cache.mkdir(parents=True, exist_ok=True)
    entries, hashes, needed, directories = [], {}, 0, []
    digest = hashlib.sha256()
    for directory, subdirs, files in os.walk(source, followlinks=False):
        directories.append((Path(directory), Path(directory).relative_to(source)))
        subdirs.sort()
        if any((Path(directory) / name).is_symlink() for name in subdirs):
            raise ValueError('payload directory symlink')
        for name in sorted(files):
            if Path(directory) == source and name.startswith('.luoshu-'):
                continue
            path = Path(directory) / name
            info = path.lstat()
            if not stat.S_ISREG(info.st_mode):
                raise ValueError('payload contains non-regular file')
            identity = info.st_dev, info.st_ino
            if identity not in hashes:
                with path.open('rb') as stream:
                    hashes[identity] = hashlib.file_digest(stream, 'sha256').digest()
                needed += info.st_size
            relative = path.relative_to(source)
            digest.update(str(relative).encode() + b'\0' + hashes[identity])
            entries.append((path, relative, identity))
    if not entries:
        raise ValueError('empty generation')
    generation = cache / ('generation-' + digest.hexdigest())
    if generation.exists():
        if generation.is_symlink() or values(generation / '.generation.conf').get('boot_id') != boot:
            raise ValueError('generation identity mismatch')
        verified = {}
        for _, relative, identity in entries:
            path = generation / relative
            if path.is_symlink() or not path.is_file():
                raise ValueError('generation file changed')
            if label(path) != label(source / relative):
                raise ValueError('generation SELinux label changed')
            info = path.stat()
            stored_identity = (info.st_dev, info.st_ino, info.st_size,
                               info.st_mtime_ns, info.st_ctime_ns)
            if stored_identity not in verified:
                with path.open('rb') as stream:
                    verified[stored_identity] = hashlib.file_digest(stream, 'sha256').digest()
            if verified[stored_identity] != hashes[identity]:
                raise ValueError('generation content changed')
        return generation
    # Current-boot generations and mirror files may still be mmap'ed by apps.
    # Never reclaim or overwrite them to make room for a hot switch.
    if shutil.disk_usage(cache).free < needed * 2 + 64 * 1024 * 1024:
        raise ValueError('insufficient space for generation and mount working copy')
    temporary.mkdir(mode=0o755)
    copied = {}
    try:
        for directory, relative in directories:
            destination = temporary / relative
            destination.mkdir(parents=True, exist_ok=True, mode=0o755)
            os.chmod(destination, 0o755)
            copy_label(directory, destination)
        for path, relative, identity in entries:
            destination = temporary / relative
            destination.parent.mkdir(parents=True, exist_ok=True, mode=0o755)
            if identity in copied:
                os.link(copied[identity], destination)
            else:
                shutil.copyfile(path, destination)
                os.chmod(destination, 0o644)
                copy_label(path, destination)
                copied[identity] = destination
        conf(temporary / '.generation.conf', dict(boot_id=boot, digest=digest.hexdigest(), bytes=needed))
        os.chmod(temporary / '.generation.conf', 0o644)
        os.rename(temporary, generation)
    finally:
        if temporary.exists():
            shutil.rmtree(temporary)
    return generation


def transaction(module, action, *args):
    module = Path(module)
    config = module / 'config'
    backup = module / '.luoshu-state/backup/live-transaction'
    journal = config / 'font-live-transaction.conf'
    if action == 'begin':
        if journal.exists():
            raise ValueError('live transaction requires recovery')
        backup.mkdir(parents=True, exist_ok=True)
        for name in RECORDS:
            saved = backup / name
            saved.unlink(missing_ok=True)
            if (config / name).exists():
                atomic(saved, (config / name).read_text())
        old_source, old_font, old_work, new_source, font, work, mode, boot, request = args
        conf(journal, dict(schema='ziyu-live-transaction-v1', old_source=old_source, old_font=old_font,
                           old_work=old_work, source=new_source, font=font, work_root=work,
                           mode=mode, boot_id=boot, request_id=request))
    elif action == 'restore':
        if values(journal).get('schema') != 'ziyu-live-transaction-v1':
            raise ValueError('invalid live journal')
        for name in RECORDS:
            if (backup / name).exists():
                atomic(config / name, (backup / name).read_text())
            else:
                (config / name).unlink(missing_ok=True)
    elif action == 'work':
        record = values(journal)
        if record.get('schema') != 'ziyu-live-transaction-v1':
            raise ValueError('invalid live journal')
        record['work_root'] = args[0]
        conf(journal, record)
    elif action == 'record':
        source, font, work, boot, request = args
        previous = values(journal)
        if previous.get('font') == font and previous.get('request_id') == request:
            conf(config / 'font-live-previous.conf', dict(schema='ziyu-live-previous-v1', boot_id=boot,
                 font=previous['old_font'], source=previous['old_source']))
        conf(config / 'font-live.conf', dict(schema='ziyu-live-font-v1', state='mounted',
             source=source, font=font, work_root=work, boot_id=boot, request_id=request))
    elif action == 'proof':
        font, boot = args
        result = json.loads((config / 'mount-backend-verification.json').read_text())
        state = result.get('state')
        if state not in ('verified', 'partial'):
            raise ValueError('route verification did not pass')
        backend = values(config / 'mount-backend.conf')
        backend.update(active_backend='self', verification='partial' if state == 'partial' else 'passed',
                       last_error='', boot_id=boot, stage='live',
                       active_self_backend=values(config / 'self-mount.conf').get('backend', 'unknown'),
                       mount_warning=values(config / 'font-apply-result.conf').get('warning', ''))
        conf(config / 'mount-backend.conf', backend)
        conf(config / 'device-font-load-verification.conf', dict(state=state,
             mode='mount-partial' if state == 'partial' else 'mount-verified', activeFont=font,
             reason='backend-pid1-route-partial:self' if state == 'partial' else 'backend-pid1-route-verified:self', bootId=boot))
    elif action == 'finish':
        journal.unlink(missing_ok=True)
    elif action == 'preserve-undo':
        pending = values(config / 'font-payload-next.conf')
        pointer = values(config / 'font-live.conf')
        previous = values(config / 'font-live-previous.conf')
        target, request = pending.get('previousFont'), pending.get('requestId')
        if not target or target == 'default' or not request:
            return
        source = None
        if pointer.get('schema') == 'ziyu-live-font-v1' and pointer.get('font') == target:
            source = pointer.get('source')
        elif previous.get('schema') == 'ziyu-live-previous-v1' and previous.get('font') == target and previous.get('boot_id') == pointer.get('boot_id'):
            source = previous.get('source')
        if not source:
            return
        old_boot = pointer.get('boot_id', '')
        if not re.fullmatch(r'[0-9a-f-]{36}', old_boot):
            raise ValueError('previous live boot identity invalid')
        source = Path(source)
        if source != module / '.luoshu-payload' and (source.parent != module / '.luoshu-state/cache/live' / old_boot or not re.fullmatch(r'generation-[0-9a-f]{64}', source.name)):
            raise ValueError('unsafe live undo source')
        if not source.is_dir() or source.is_symlink():
            raise ValueError('live undo source unavailable')
        record_file = config / 'font-live-boot-previous.conf'
        saved = values(record_file)
        retired = module / '.luoshu-retired'
        destination = Path(saved.get('source', ''))
        if saved.get('request_id') == request and saved.get('font') == target and destination.parent == retired and destination.is_dir() and not destination.is_symlink():
            return
        retired.mkdir(parents=True, exist_ok=True)
        destination = retired / f'payload-live-{args[0]}-{os.getpid()}'
        # Generations are immutable. Links retain their bytes after old-boot GC
        # without copying another full font tree or changing mapped inodes.
        shutil.copytree(source, destination, copy_function=os.link, symlinks=True)
        conf(record_file, dict(schema='ziyu-live-boot-previous-v1', source=str(destination),
             font=target, request_id=request, boot_id=args[0]))
    elif action == 'prune':
        boot = args[0]
        # Only a changed kernel boot ID proves that old application mmaps ended.
        tables = Path('/proc/1/mountinfo').read_text() + Path('/proc/self/mountinfo').read_text()
        def mounted(path):
            text = str(path)
            return text in tables or text.removeprefix('/data') in tables
        cache = module / '.luoshu-state/cache/live'
        if cache.is_dir() and not cache.is_symlink():
            for directory in cache.iterdir():
                if directory.name == boot or not re.fullmatch(r'[0-9a-f-]{36}', directory.name) or directory.is_symlink() or not directory.is_dir() or mounted(directory):
                    continue
                for generation in directory.iterdir():
                    if generation.is_symlink() or not generation.is_dir() or mounted(generation):
                        continue
                    if re.fullmatch(r'generation-[0-9a-f]{64}', generation.name) and values(generation / '.generation.conf').get('boot_id') == directory.name:
                        shutil.rmtree(generation)
                if not any(directory.iterdir()):
                    directory.rmdir()
        working = Path('/data/adb/luoshu/live-mount')
        if working.is_dir() and not working.is_symlink():
            for directory in working.iterdir():
                saved_boot = directory.name.split('.', 1)[0]
                if saved_boot == boot or not re.fullmatch(r'[0-9a-f-]{36}', saved_boot) or directory.is_symlink() or not directory.is_dir() or mounted(directory):
                    continue
                record = directory / 'boot-id'
                if record.is_file() and not record.is_symlink() and record.read_text().strip() == saved_boot:
                    shutil.rmtree(directory)
    else:
        raise ValueError('unknown transaction action')


if __name__ == '__main__':
    try:
        if sys.argv[1] == '--lock-exec':
            import fcntl
            descriptor = os.open(sys.argv[2], os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
            fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
            os.set_inheritable(descriptor, True)
            os.environ['ZIYU_LIVE_LOCK_FD'] = str(descriptor)
            os.execvp(sys.argv[3], sys.argv[3:])
        elif sys.argv[1] == 'prepare':
            print(prepare(*sys.argv[2:]))
        else:
            transaction(*sys.argv[1:])
    except (OSError, ValueError, TypeError, KeyError) as error:
        print(str(error), file=sys.stderr)
        raise SystemExit(1)
