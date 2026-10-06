#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
TMP=$(mktemp -d 2>/dev/null || mktemp -d -t luoshu-legacy-34)
trap 'rm -rf "$TMP"' EXIT HUP INT TERM

MODULE="$TMP/module"
PUBLIC="$TMP/public"
mkdir -p "$MODULE/common" "$MODULE/config" "$MODULE/cache" "$MODULE/logs" "$PUBLIC/fonts"
cp "$ROOT/common/legacy_v14_4/v142_weighted_mix.sh" "$MODULE/common/v142_weighted_mix.sh"
cp "$ROOT/common/background_task.sh" "$MODULE/common/background_task.sh"
cp "$ROOT/common/mix_task_handoff.sh" "$MODULE/common/mix_task_handoff.sh"

cat >"$MODULE/common/util_functions.sh" <<'EOF_UTIL'
detect_font_family() { printf '%s\n' "${1%%-*}"; }
detect_font_weight() { printf 'regular\n'; }
is_variable_font() { return 1; }
EOF_UTIL

cat >"$MODULE/common/font_check.sh" <<'EOF_CHECK'
font_validate() {
    FONT_CHECK_VARIABLE=false
    FONT_CHECK_FORMAT=TTF
    FONT_CHECK_ERROR=''
    return 0
}
EOF_CHECK

# Register the nested task immediately, then deliberately keep the start shell
# alive. The public task must advance beyond 34% without waiting for this shell.
cat >"$MODULE/common/font_mix.sh" <<'EOF_ENGINE'
#!/bin/sh
MODDIR="${MODDIR:-${0%/*}/..}"
TASK="$MODDIR/config/mix_task.conf"
case "${1:-}" in
    start)
        cat >"$TASK" <<EOF_TASK
task=slow-start-inner
state=running
message=正在生成完整复合字体
cjk=$2
latin=$3
digit=$4
started=1
finished=
EOF_TASK
        : > "$MODDIR/config/fixture-start-alive"
        while [ -d "$MODDIR" ] && [ ! -e "$MODDIR/config/fixture-start-release" ]; do sleep 0.2; done
        rm -f "$MODDIR/config/fixture-start-alive"
        cat >"$TASK" <<EOF_TASK
task=slow-start-inner
state=success
message=完整复合字体已准备
cjk=$2
latin=$3
digit=$4
started=1
finished=2
EOF_TASK
        printf '%s\n' '{"status":"ok","data":{"task":"slow-start-inner"}}'
        ;;
    recover) printf '%s\n' '{"status":"ok"}' ;;
esac
EOF_ENGINE
# Only publication is mocked in this handoff latency test; real saved-byte and
# preparation transaction checks are covered by mix_workflow_test.py.
mkdir -p "$MODULE/common/legacy_v14_4"
cat >"$MODULE/common/legacy_v14_4/mix_router.sh" <<'EOF_PREPARE'
#!/bin/sh
[ "$1" = finalize ] || exit 1
printf 'result=prepared\ngeneratedFontId=handoff-fixture\n' >> "$MODDIR/config/axes_task.conf"
printf '%s\n' '{"status":"ok"}'
EOF_PREPARE
chmod 0755 "$MODULE/common"/*.sh

# The worker now subsets static donors too. This lifecycle fixture deliberately
# stubs only font preparation; real outline preservation is covered separately.
mkdir -p "$MODULE/common/python/bin"
cat >"$MODULE/common/python/bin/luoshu-python" <<'EOF_INSTANCE'
#!/bin/sh
shift
while [ "$#" -gt 0 ]; do
    case "$1" in --input) input="$2";; --output) output="$2";; esac
    shift 2
done
[ -z "${LUOSHU_FIXTURE_PREPARE_SLEEP:-}" ] || sleep "$LUOSHU_FIXTURE_PREPARE_SLEEP"
cp "$input" "$output"
printf '%s\n' '{"status":"ok"}'
EOF_INSTANCE
chmod 0755 "$MODULE/common/python/bin/luoshu-python"

printf 'font-data\n' >"$PUBLIC/fonts/CJK-Regular.ttf"
printf 'font-data\n' >"$PUBLIC/fonts/Latin-Regular.ttf"
printf 'font-data\n' >"$PUBLIC/fonts/Digit-Regular.ttf"

START=$(MODDIR="$MODULE" LUOSHU_PUBLIC_DIR="$PUBLIC" \
    sh "$MODULE/common/v142_weighted_mix.sh" start CJK Latin Digit wght=400 wght=400 wght=400)
OUTER=$(printf '%s\n' "$START" | sed -n 's/^.*"task":"\([^"]*\)".*$/\1/p' | tail -n1)
test -n "$OUTER"

# Measure the async start handoff after source preparation, not its wall time.
COUNT=0
while [ ! -s "$MODULE/config/mix_task.conf" ] && [ "$COUNT" -lt 20 ]; do
    sleep 1
    COUNT=$((COUNT + 1))
done
test -s "$MODULE/config/mix_task.conf"

COUNT=0
PERCENT=0
while [ "$COUNT" -lt 20 ]; do
    PERCENT=$(sed -n 's/^percent=//p' "$MODULE/config/axes_task.conf" 2>/dev/null | head -n1)
    case "$PERCENT" in ''|*[!0-9]*) PERCENT=0 ;; esac
    [ "$PERCENT" -ge 36 ] 2>/dev/null && break
    sleep 1
    COUNT=$((COUNT + 1))
done
PERCENT=$(sed -n 's/^percent=//p' "$MODULE/config/axes_task.conf" 2>/dev/null | head -n1)
case "$PERCENT" in ''|*[!0-9]*) PERCENT=0 ;; esac
if [ "$PERCENT" -lt 36 ] 2>/dev/null; then
    echo "legacy composite task stayed at ${PERCENT}% while nested start was alive" >&2
    cat "$MODULE/config/axes_task.conf" >&2 2>/dev/null || true
    exit 1
fi
test -e "$MODULE/config/fixture-start-alive"
: > "$MODULE/config/fixture-start-release"

COUNT=0
while [ "$COUNT" -lt 10 ]; do
    STATE=$(sed -n 's/^state=//p' "$MODULE/config/axes_task.conf" 2>/dev/null | head -n1)
    [ "$STATE" = success ] && break
    sleep 1
    COUNT=$((COUNT + 1))
done
test "${STATE:-}" = success

grep -q 'background_task.sh' "$ROOT/common/legacy_v14_4/mix_router.sh"
grep -q 'mix_task_handoff.sh' "$ROOT/common/legacy_v14_4/mix_router.sh"
grep -q 'mix-engine-start.*json' "$ROOT/common/legacy_v14_4/font_mix_runtime.sh"
! grep -q '_output=$(LUOSHU_PUBLIC_DIR=' "$ROOT/common/legacy_v14_4/v142_weighted_mix.sh"
! sed -n '/^payload_stage_begin()/,/^}/p' "$ROOT/common/legacy_v14_4/font_mix_engine.sh" | grep -q 'cp -af'
grep -q 'hyperos_metrics_batch.py' "$ROOT/common/hyperos_stage_complete.sh"

# Replacing a task must also stop its old completion monitor promptly. It must
# neither poll for the former twelve-minute budget nor finalize the new task.
printf 'task=newer-task\nstate=running\n' > "$MODULE/config/mix_task.conf"
timeout 3 env MODDIR="$MODULE" LUOSHU_REAL_MODDIR="$MODULE" \
    sh "$ROOT/common/legacy_v14_4/font_mix_runtime.sh" monitor superseded-task
test ! -f "$MODULE/config/mix-finalize-state.conf"

# Cancel while donor preparation children are alive. The controller must drain
# them before deleting the owned input tree and must never start/publish an engine.
START=$(MODDIR="$MODULE" LUOSHU_PUBLIC_DIR="$PUBLIC" LUOSHU_FIXTURE_PREPARE_SLEEP=3 \
    sh "$MODULE/common/v142_weighted_mix.sh" start CJK Latin Digit wght=400 wght=400 wght=400)
CANCEL_TASK=$(printf '%s\n' "$START" | sed -n 's/^.*"task":"\([^"]*\)".*$/\1/p' | tail -n1)
COUNT=0
while [ "$COUNT" -lt 30 ]; do
    [ "$(sed -n 's/^state=//p' "$MODULE/config/axes_task.conf")" = running ] && break
    sleep 1; COUNT=$((COUNT + 1))
done
printf 'task=%s\n' "$CANCEL_TASK" >"$MODULE/config/mix_task.cancel"
COUNT=0
while [ "$COUNT" -lt 40 ]; do
    [ "$(sed -n 's/^state=//p' "$MODULE/config/axes_task.conf")" = cancelled ] && break
    sleep 1; COUNT=$((COUNT + 1))
done
grep -q '^state=cancelled$' "$MODULE/config/axes_task.conf"
test ! -d "$MODULE/cache/axes-mix/$CANCEL_TASK"
grep -q '^task=newer-task$' "$MODULE/config/mix_task.conf"
CANCEL_STATUS=$(MODDIR="$MODULE" sh "$ROOT/common/legacy_v14_4/mix_router.sh" status "$CANCEL_TASK")
printf '%s\n' "$CANCEL_STATUS" | grep -q '"state":"cancelled"'
printf '%s\n' "$CANCEL_STATUS" | grep -q '"result":""'
printf '%s\n' "$CANCEL_STATUS" | grep -q '"generatedFontId":""'

echo 'Legacy composite start advances past 34% before the nested start shell exits.'
