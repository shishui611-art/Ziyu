"""Read old metadata without altering immutable font bytes or their hash ledgers."""
from pathlib import Path

_SCHEMAS = {
    'luoshu-google-font-fallback-v1': 'ziyu-google-font-fallback-v1',
    'luoshu-slot-metrics-v1': 'ziyu-slot-metrics-v1',
    'luoshu-coloros-flipfont-v1': 'ziyu-coloros-flipfont-v1',
}


def schema(value):
    return _SCHEMAS.get(value, value) if isinstance(value, str) else value


def owned_path(path):
    """Prefer current names; an existing old artifact stays immutable until replaced."""
    path = Path(path)
    if path.exists() or path.is_symlink():
        return path
    old = Path(path.anchor) if path.is_absolute() else Path()
    for part in path.parts[1:] if path.is_absolute() else path.parts:
        candidate = old / part
        if not candidate.exists() and not candidate.is_symlink() and '.ziyu' in part:
            candidate = old / part.replace('.ziyu', '.luoshu')
        old = candidate
    return old if old.exists() or old.is_symlink() else path


def generated_names(value):
    """Aliases for checking a preserved old generated font, never for new output."""
    return (value, value.replace('Ziyu', 'LuoShu').replace('ziyu', 'luoshu'))
