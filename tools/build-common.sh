#!/usr/bin/env bash
# Shared paths and logging for the MilkSU Linux build tools.
set -Eeuo pipefail

MILKSU_ROOT="${MILKSU_ROOT:-$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)}"
export MILKSU_ROOT
export SOURCE_DATE_EPOCH="${SOURCE_DATE_EPOCH:-$(git -C "$MILKSU_ROOT" show -s --format=%ct HEAD)}"
export TZ=UTC
export MILKSU_CACHE="${MILKSU_CACHE:-/opt/milksu}"
export MILKSU_OUT="${MILKSU_OUT:-$MILKSU_ROOT/out}"
export MILKSU_JOBS="${MILKSU_JOBS:-2}"
export CARGO_BUILD_JOBS="${CARGO_BUILD_JOBS:-$MILKSU_JOBS}"
export CARGO_INCREMENTAL="${CARGO_INCREMENTAL:-0}"
export MILKSU_DDK_RELEASE="${MILKSU_DDK_RELEASE:-20260828}"
export MILKSU_RUST_VERSION="${MILKSU_RUST_VERSION:-1.93.1}"
export MILKSU_NDK_VERSION="${MILKSU_NDK_VERSION:-29.0.14206865}"
export MILKSU_ANDROID_API="${MILKSU_ANDROID_API:-26}"
export MILKSU_PACKAGE_NAME="${MILKSU_PACKAGE_NAME:-io.milksu.manager}"
export CARGO_HOME="${CARGO_HOME:-$MILKSU_CACHE/cargo}"
export RUSTUP_HOME="${RUSTUP_HOME:-$MILKSU_CACHE/rustup}"
export CARGO_TARGET_DIR="${CARGO_TARGET_DIR:-$MILKSU_CACHE/cargo-target}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$MILKSU_CACHE/gradle}"
export ANDROID_HOME="${ANDROID_HOME:-$MILKSU_CACHE/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_USER_HOME="${ANDROID_USER_HOME:-$MILKSU_CACHE/android-user}"
export XDG_CACHE_HOME="${XDG_CACHE_HOME:-$MILKSU_CACHE/xdg-cache}"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/$MILKSU_NDK_VERSION"
export ANDROID_NDK_ROOT="$ANDROID_NDK_HOME"
export TMPDIR="${MILKSU_TMPDIR:-$MILKSU_CACHE/tmp}"
export DOCKER_HOST="${MILKSU_DOCKER_HOST:-unix://$MILKSU_CACHE/docker.sock}"
export DOCKER_CONFIG="${MILKSU_DOCKER_CONFIG:-$MILKSU_CACHE/docker-config}"
export PATH="$CARGO_HOME/bin:$ANDROID_HOME/cmdline-tools/23.0/bin:$ANDROID_HOME/build-tools/37.0.0:$PATH"
if [[ -f "$MILKSU_CACHE/gitconfig" ]]; then
    export GIT_CONFIG_SYSTEM="$MILKSU_CACHE/gitconfig"
fi

milksu_die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

milksu_freeze() {
    # Bash reads long scripts incrementally; isolate running jobs from concurrent edits.
    [[ "${MILKSU_FROZEN_SCRIPT:-}" != "${BASH_SOURCE[1]}" ]] || return 0
    milksu_init
    local snapshot original
    original="${BASH_SOURCE[1]}"
    snapshot="$(mktemp -d "$TMPDIR/tool-script.XXXXXX")"
    cp "$original" "$snapshot/$(basename "$original")"
    cp "$(dirname "$original")/build-common.sh" "$snapshot/build-common.sh"
    MILKSU_FROZEN_SCRIPT="$snapshot/$(basename "$original")"
    export MILKSU_FROZEN_SCRIPT
    exec bash "$MILKSU_FROZEN_SCRIPT" "$@"
}

milksu_init() {
    local path
    for path in "$MILKSU_CACHE" "$CARGO_HOME" "$RUSTUP_HOME" "$CARGO_TARGET_DIR" "$GRADLE_USER_HOME"; do
        [[ "$path" != /mnt/d && "$path" != /mnt/d/* ]] ||
            milksu_die "Build caches must not use D: ($path)."
    done
    if grep -qi microsoft /proc/sys/kernel/osrelease && [[ "$MILKSU_CACHE" == /opt/milksu ]]; then
        if ! mountpoint -q "$MILKSU_CACHE"; then
            bash "$MILKSU_ROOT/tools/bootstrap-milksu.sh" cache
        fi
        mountpoint -q "$MILKSU_CACHE" || milksu_die "C: cache volume could not be mounted."
    fi
    mkdir -p "$MILKSU_OUT/build-logs"
    if [[ ! -e "$MILKSU_OUT/.gitignore" ]]; then
        printf '*\n' >"$MILKSU_OUT/.gitignore"
    fi
    mkdir -p "$MILKSU_CACHE" "$TMPDIR" "$DOCKER_CONFIG"
}

milksu_log() {
    milksu_init
    local label="${1:?log label required}"
    MILKSU_LOG="$MILKSU_OUT/build-logs/$(date -u +%Y%m%dT%H%M%S)-${label}-$$.log"
    export MILKSU_LOG
    exec > >(tee -a "$MILKSU_LOG") 2>&1
    printf 'UTC: %s\nLOG: %s\nROOT: %s\nCACHE: %s\n' \
        "$(date -u +%FT%TZ)" "$MILKSU_LOG" "$MILKSU_ROOT" "$MILKSU_CACHE"
    trap 'milksu_finish "$?"' EXIT
}

milksu_finish() {
    local result="$1"
    trap - EXIT
    printf '\nEXIT: %s UTC: %s\n' "$result" "$(date -u +%FT%TZ)"
    exit "$result"
}

milksu_run() {
    printf '\nCOMMAND:'
    printf ' %q' "$@"
    printf '\n'
    "$@"
}

milksu_heavy_lock() {
    mkdir -p "$MILKSU_CACHE/build"
    exec {MILKSU_HEAVY_LOCK_FD}>"$MILKSU_CACHE/build/heavy-build.lock"
    printf 'Waiting for the single heavy-build slot (memory-limited WSL).\n'
    flock "$MILKSU_HEAVY_LOCK_FD"
    printf 'Heavy-build slot acquired; jobs=%s.\n' "$MILKSU_JOBS"
}

milksu_space() {
    df -h "$MILKSU_CACHE" / /mnt/c /mnt/d 2>/dev/null || df -h "$MILKSU_CACHE" /
    local available
    available="$(df -Pk "$MILKSU_CACHE" | awk 'END {print $4}')"
    (( available >= 3 * 1024 * 1024 )) || milksu_die "Less than 3 GiB remains in build cache."
    if [[ -d /mnt/c ]]; then
        available="$(df -Pk /mnt/c | awk 'END {print $4}')"
        (( available >= 12 * 1024 * 1024 )) || milksu_die "C: has less than 12 GiB available."
    fi
}
