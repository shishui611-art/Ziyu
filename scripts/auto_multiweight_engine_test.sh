#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
for script in "$ROOT/common/multiweight_mix_task.sh" "$ROOT/common/legacy_v14_4/v143_auto_multiweight_mix.sh"; do
    sh -n "$script"
    ! grep -q 'for _weight in 100 200' "$script"
    ! grep -q 'build_composite_cached' "$script"
done
grep -q 'exec sh.*weighted_mix_task.sh' "$ROOT/common/multiweight_mix_task.sh"
grep -q 'exec sh.*v142_weighted_mix.sh' "$ROOT/common/legacy_v14_4/v143_auto_multiweight_mix.sh"
! grep -q 'infer_mix_weight_mode' "$ROOT/common/legacy_v14_4/v14_mix.sh"
echo 'Combination entries use the selected slot weights without automatic family expansion.'
