#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
# The current transaction publishes one named reusable artifact. Its concurrency,
# durable task result, preview lookup, cancellation and active-state isolation are
# checked with genuine synthetic fonts rather than fake payload text files.
"${HOST_PYTHON:-python3}" "$ROOT/scripts/mix_workflow_test.py" MixWorkflowTest.test_router_only_reports_success_after_named_library_is_saved
