#!/usr/bin/env bash
# Full local/CI build after every source owner has declared the inputs ready.
set -Eeuo pipefail
# shellcheck source=build-common.sh
source "$(dirname -- "${BASH_SOURCE[0]}")/build-common.sh"
milksu_freeze "$@"
[[ "${1:-}" == --source-ready ]] || milksu_die "Usage: build-all.sh --source-ready"
milksu_log all
milksu_run bash "$MILKSU_ROOT/tools/bootstrap-milksu.sh" doctor
milksu_run bash "$MILKSU_ROOT/tools/build-signing.sh"
milksu_run bash "$MILKSU_ROOT/tools/build-freeze-kernel.sh" --with-susfs
milksu_run python3 "$MILKSU_ROOT/tools/build-matrix.py" --jobs 1 --stage-assets
milksu_run bash "$MILKSU_ROOT/tools/build-kpatch.sh"
milksu_run bash "$MILKSU_ROOT/tools/build-rust.sh" android-check
milksu_run bash "$MILKSU_ROOT/tools/build-rust.sh" android-clippy
milksu_run bash "$MILKSU_ROOT/tools/build-rust.sh" format-check
milksu_run bash "$MILKSU_ROOT/tools/build-rust.sh" host-tests
milksu_run bash "$MILKSU_ROOT/tools/build-release.sh" snapshot --source-ready
for stage in ksuinit ksud manager repack; do
    milksu_run bash "$MILKSU_ROOT/tools/build-release.sh" "$stage"
done
milksu_space
