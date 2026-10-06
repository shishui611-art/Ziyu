#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
TMP=$(mktemp -d 2>/dev/null || mktemp -d -t luoshu-mix-handoff)
trap 'rm -rf "$TMP"' EXIT HUP INT TERM

. "$ROOT/common/mix_task_handoff.sh"

RESPONSE="$TMP/response.json"
TASK="$TMP/mix_task.conf"

printf '%s\n' '{"status":"ok","data":{"task":"from-output"}}' >"$RESPONSE"
cat >"$TASK" <<'EOF_TASK'
task=stale
state=success
cjk=LuoShuMixCJK
latin=LuoShuMixLatin
digit=LuoShuMixDigit
EOF_TASK
test "$(luoshu_resolve_nested_mix_task "$RESPONSE" "$TASK" stale LuoShuMixCJK LuoShuMixLatin LuoShuMixDigit)" = from-output

: >"$RESPONSE"
cat >"$TASK" <<'EOF_TASK'
task=from-state
state=running
message=正在生成
cjk=LuoShuMixCJK
latin=LuoShuMixLatin
digit=LuoShuMixDigit
EOF_TASK
test "$(luoshu_resolve_nested_mix_task "$RESPONSE" "$TASK" stale LuoShuMixCJK LuoShuMixLatin LuoShuMixDigit)" = from-state

if luoshu_resolve_nested_mix_task "$RESPONSE" "$TASK" from-state LuoShuMixCJK LuoShuMixLatin LuoShuMixDigit >/dev/null 2>&1; then
    echo 'stale task was accepted' >&2
    exit 1
fi

sed 's/^latin=.*/latin=WrongLatin/' "$TASK" >"$TASK.tmp"
mv -f "$TASK.tmp" "$TASK"
if luoshu_resolve_nested_mix_task "$RESPONSE" "$TASK" stale LuoShuMixCJK LuoShuMixLatin LuoShuMixDigit >/dev/null 2>&1; then
    echo 'mismatched task was accepted' >&2
    exit 1
fi

printf '%s\n' '{"status":"error","message":"真实启动错误"}' >"$RESPONSE"
test "$(luoshu_mix_task_message_from_response "$RESPONSE")" = 真实启动错误

# Actual controller handoff and retained startup-pipe regression use the current
# selected-slot worker. Library publication is independently verified in Python.
sh "$ROOT/scripts/legacy_mix_34_progress_test.sh"

grep -q 'mix_task_handoff.sh' "$ROOT/common/legacy_v14_4/v142_weighted_mix.sh"
grep -q 'luoshu_resolve_nested_mix_task' "$ROOT/common/legacy_v14_4/v142_weighted_mix.sh"
grep -q '_response_file=' "$ROOT/common/legacy_v14_4/v142_weighted_mix.sh"
! grep -q '_output=$(LUOSHU_PUBLIC_DIR=' "$ROOT/common/legacy_v14_4/v142_weighted_mix.sh"

echo 'Nested mix task handoff survives missing startup output.'
