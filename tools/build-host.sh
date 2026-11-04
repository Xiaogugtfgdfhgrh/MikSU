#!/usr/bin/env bash
# Build the Linux offline image tool from current sources and real Android assets.
set -Eeuo pipefail
# shellcheck source=build-common.sh
source "$(dirname -- "${BASH_SOURCE[0]}")/build-common.sh"
milksu_freeze "$@"
milksu_log host-ksud
milksu_space
milksu_heavy_lock
work="$MILKSU_CACHE/build/host/source"
mkdir -p "$(dirname "$work")" "$MILKSU_OUT/tools"
exec {host_lock}>"$MILKSU_CACHE/build/host.lock"
flock "$host_lock"
if [[ ! -d "$work/.git" ]]; then
    milksu_run git clone --shared --no-hardlinks "$MILKSU_ROOT" "$work"
fi
milksu_run rsync -a --delete --exclude=.git --exclude=out --exclude=target \
    --exclude=build --exclude=.gradle --exclude=.kotlin --exclude=.cxx \
    --exclude=node_modules --exclude=local.properties --exclude=dist \
    "$MILKSU_ROOT/" "$work/"
cd "$work"
export KSU_PACKAGE_NAME="$MILKSU_PACKAGE_NAME"
# shellcheck source=/dev/null
source <(sed 's/\r$//' "$work/.github/scripts/setup-rust-build.sh") aarch64-linux-android "$MILKSU_ANDROID_API"
builtins="$("$CLANG_PATH" --print-resource-dir)/lib/linux/libclang_rt.builtins-aarch64-android.a"
export RUSTFLAGS="-C target-feature=+crt-static -C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wno-unused-command-line-argument -C link-arg=$builtins"
milksu_run cargo build --locked --package ksuinit --target aarch64-linux-android --release
unset RUSTFLAGS
assets="$work/userspace/ksud/bin/aarch64"
mkdir -p "$assets"
cp "$CARGO_TARGET_DIR/aarch64-linux-android/release/ksuinit" "$assets/ksuinit"
for module in "$MILKSU_OUT/aarch64/"*.ko; do
    [[ -f "$module" ]] || continue
    cp "$module" "$assets/"
done
for name in kpimg kptools; do
    [[ -s "$MILKSU_OUT/kpatch/aarch64/$name" ]] || milksu_die "Missing source-built $name."
    cp "$MILKSU_OUT/kpatch/aarch64/$name" "$assets/$name"
done
for kmi in android14-6.1 android12-5.10; do
    for suffix in kernelsu susfs_guard_lkm; do
        [[ -s "$assets/${kmi}_${suffix}.ko" ]] || milksu_die "Missing real asset: ${kmi}_${suffix}.ko."
    done
done
milksu_run cargo clean --package ksud --target x86_64-unknown-linux-gnu
milksu_run cargo build --locked --package ksud --target x86_64-unknown-linux-gnu
cp "$CARGO_TARGET_DIR/x86_64-unknown-linux-gnu/debug/ksud" "$MILKSU_OUT/tools/ksud"
milksu_run "$MILKSU_OUT/tools/ksud" --help
milksu_run file "$MILKSU_OUT/tools/ksud"
sha256sum "$MILKSU_OUT/tools/ksud" >"$MILKSU_OUT/tools/ksud.sha256"
milksu_space
