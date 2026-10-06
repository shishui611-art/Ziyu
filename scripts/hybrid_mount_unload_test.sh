#!/usr/bin/env bash
set -euo pipefail

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
assert_eq() { [ "$1" = "$2" ] || fail "$3 (expected '$2', got '$1')"; }

MOD="$TMP/module"
META="$TMP/hybrid"
mkdir -p "$MOD/common" "$MOD/config" "$MOD/logs" "$META"
cp "$ROOT/common/mount_backend_runtime.sh" "$MOD/common/"
cp "$ROOT/common/private_payload.sh" "$MOD/common/"
cp "$ROOT/common/hybrid_mount_runtime.py" "$MOD/common/"
printf 'id=LuoShu\n' > "$MOD/module.prop"

cat > "$META/hybrid-mount" <<'SH'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$CALL_LOG"
if [ "$1" != runtime ]; then exit 2; fi
case "$2" in
  status)
    if [ "${INVALID_STATUS:-0}" = 1 ]; then printf 'not-json\n'; else cat "$STATE_FILE"; fi
    ;;
  unload)
    [ "$3" = LuoShu ] || exit 3
    [ "${UNLOAD_RC:-0}" -eq 0 ] || exit "$UNLOAD_RC"
    if [ "${STICK_ACTIVE:-0}" != 1 ]; then
      printf '{"supported":true,"modules":[{"id":"LuoShu","active":false,"eligible":true}]}\n' > "$STATE_FILE"
    fi
    ;;
  *) exit 2 ;;
esac
SH
chmod +x "$META/hybrid-mount"
cat > "$TMP/status-parser" <<'SH'
#!/usr/bin/env bash
status=$(cat)
[[ "$status" == *'"supported":true'* ]] || exit 2
[[ "$status" == *'"id":"LuoShu"'* ]] || exit 2
if [[ "$status" == *'"active":true'* ]]; then echo active; exit 0; fi
if [[ "$status" == *'"active":false'* ]]; then echo inactive; exit 0; fi
exit 2
SH
chmod +x "$TMP/status-parser"

export MODDIR="$MOD" MODULE_DIR="$MOD" META_MODULE_DIR="$META"
export LUOSHU_BACKEND_TEST_MODE=1 LUOSHU_PYTHON="$TMP/status-parser"
export STATE_FILE="$TMP/status.json" CALL_LOG="$TMP/calls.log"
. "$MOD/common/mount_backend_runtime.sh"
unset LUOSHU_BACKEND_TEST_MODE

write_status() {
  printf '{"supported":true,"modules":[{"id":"LuoShu","active":%s,"eligible":true}]}\n' "$1" > "$STATE_FILE"
}

# Active pure-VFS module: issue the scoped unload, then require inactive readback.
write_status true
_lbr_hybrid_unload_luoshu || fail 'Hybrid pure-VFS unload should pass after inactive readback'
assert_eq "$(tail -n1 "$CALL_LOG")" 'runtime status' 'post-unload status readback'
grep -q '^runtime unload LuoShu$' "$CALL_LOG" || fail 'did not unload the exact LuoShu module ID'

# Already inactive is safe and must not issue a redundant unload.
: > "$CALL_LOG"
write_status false
_lbr_hybrid_unload_luoshu || fail 'already inactive module should be safe'
if grep -q '^runtime unload ' "$CALL_LOG"; then fail 'unloaded a module reported inactive'; fi

# Invalid status, failed command, or active readback all fail closed.
write_status true
if INVALID_STATUS=1 _lbr_hybrid_unload_luoshu; then fail 'accepted malformed runtime status'; fi
if UNLOAD_RC=7 _lbr_hybrid_unload_luoshu; then fail 'accepted a failed unload command'; fi
write_status true
if STICK_ACTIVE=1 _lbr_hybrid_unload_luoshu; then fail 'accepted active state after unload'; fi

printf 'Hybrid Mount scoped unload and readback checks passed\n'
