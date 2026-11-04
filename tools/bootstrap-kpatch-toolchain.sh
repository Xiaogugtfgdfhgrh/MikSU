#!/usr/bin/env bash
# Match the Arm GNU bare-metal release pinned in vendor/kpatch-next CI.
set -Eeuo pipefail
# shellcheck source=build-common.sh
source "$(dirname -- "${BASH_SOURCE[0]}")/build-common.sh"
milksu_freeze "$@"
milksu_log kpatch-toolchain
milksu_space
name=arm-gnu-toolchain-12.2.rel1-x86_64-aarch64-none-elf
archive="$MILKSU_CACHE/downloads/$name.tar.xz"
directory="$MILKSU_CACHE/toolchains/$name"
url="https://armkeil.blob.core.windows.net/developer/Files/downloads/gnu/12.2.rel1/binrel/$name.tar.xz"
mkdir -p "$MILKSU_CACHE/downloads" "$MILKSU_CACHE/toolchains" "$MILKSU_OUT/toolchain"
if [[ ! -x "$directory/bin/aarch64-none-elf-gcc" ]]; then
    milksu_run curl --silent --show-error --fail --location --retry 3 --continue-at - "$url" -o "$archive"
    milksu_run tar -xJf "$archive" -C "$MILKSU_CACHE/toolchains"
fi
sha256sum "$archive" >"$MILKSU_OUT/toolchain/$name.sha256"
milksu_run "$directory/bin/aarch64-none-elf-gcc" --version
printf 'TARGET_COMPILE=%s/bin/aarch64-none-elf-\n' "$directory"
milksu_space
