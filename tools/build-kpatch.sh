#!/usr/bin/env bash
# Build kpimg and host/Android kptools from the vendored, fixed KPatch source.
set -Eeuo pipefail
# shellcheck source=build-common.sh
source "$(dirname -- "${BASH_SOURCE[0]}")/build-common.sh"
milksu_freeze "$@"
milksu_log kpatch
milksu_space
milksu_heavy_lock
source_root="${MILKSU_SOURCE_ROOT:-$MILKSU_ROOT}"
vendor="$source_root/vendor/kpatch-next"
[[ -f "$vendor/kernel/Makefile" && -f "$vendor/tools/CMakeLists.txt" ]] ||
    milksu_die "Vendored KPatch source is not ready."
work="$MILKSU_CACHE/build/kpatch-next"
mkdir -p "$work" "$MILKSU_OUT/kpatch/aarch64" "$MILKSU_OUT/tools"
exec {build_lock}>"$MILKSU_CACHE/build/kpatch-next.lock"
flock "$build_lock"
milksu_run rsync -a --delete --exclude=.git --exclude=target --exclude=build \
    --exclude='*.o' --exclude='*.elf' --exclude=kpimg --exclude=kptools --exclude=kpatch \
    "$vendor/" "$work/source/"
target_compile="${MILKSU_KPATCH_TARGET_COMPILE:-$MILKSU_CACHE/toolchains/arm-gnu-toolchain-12.2.rel1-x86_64-aarch64-none-elf/bin/aarch64-none-elf-}"
if [[ ! -x "${target_compile}gcc" ]]; then
    bash "$MILKSU_ROOT/tools/bootstrap-kpatch-toolchain.sh"
fi
milksu_run make -C "$work/source/kernel" "TARGET_COMPILE=$target_compile" clean
milksu_run make -C "$work/source/kernel" -j"$MILKSU_JOBS" \
    "TARGET_COMPILE=$target_compile"
mkdir -p "$MILKSU_OUT/manifests"
milksu_run python3 "$vendor/tests/check_loader_relocations.py" \
    --object "$work/source/kernel/patch/module/module.o" \
    --readelf "${target_compile}readelf" \
    --report "$MILKSU_OUT/manifests/kpatch-loader-relocations.json"
milksu_run cmake -S "$work/source/tools" -B "$work/host" -G Ninja \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_POLICY_VERSION_MINIMUM=3.5
milksu_run cmake --build "$work/host" -j"$MILKSU_JOBS"
milksu_run cmake -S "$work/source/tools" -B "$work/android" -G Ninja \
    "-DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
    -DANDROID_PLATFORM=android-26 -DANDROID_ABI=arm64-v8a \
    -DCMAKE_EXE_LINKER_FLAGS=-Wl,-z,max-page-size=16384
milksu_run cmake --build "$work/android" -j"$MILKSU_JOBS"
cp "$work/source/kernel/kpimg" "$MILKSU_OUT/kpatch/aarch64/kpimg"
cp "$work/source/kernel/kpimg.elf" "$MILKSU_OUT/kpatch/aarch64/kpimg.elf"
cp "$work/android/kptools" "$MILKSU_OUT/kpatch/aarch64/kptools"
cp "$work/host/kptools" "$MILKSU_OUT/tools/kptools"
milksu_run file "$MILKSU_OUT/kpatch/aarch64/kptools" "$MILKSU_OUT/kpatch/aarch64/kpimg.elf"
milksu_run "$MILKSU_OUT/tools/kptools" --help
sha256sum "$MILKSU_OUT/kpatch/aarch64/"* "$MILKSU_OUT/tools/kptools" \
    >"$MILKSU_OUT/kpatch/SHA256SUMS"
cat "$MILKSU_OUT/kpatch/SHA256SUMS"
python3 - "$vendor" "$MILKSU_OUT/kpatch/source-manifest.json" "$target_compile" <<'PY'
import hashlib, json, pathlib, subprocess, sys
root, output = map(pathlib.Path, sys.argv[1:3])
sources = []
for directory in ("kernel", "tools", "user"):
    for path in sorted((root / directory).rglob("*")):
        if path.is_file() and path.suffix in (".c", ".h", ".S", ".lds", ".txt"):
            sources.append({"path": str(path.relative_to(root)), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
compiler = subprocess.check_output([sys.argv[3] + "gcc", "--version"], text=True).splitlines()[0]
import os
output.write_text(json.dumps({
    "compiler": compiler, "source_date_epoch": os.environ["SOURCE_DATE_EPOCH"],
    "source_files": sources,
}, indent=2) + "\n")
PY
milksu_space
