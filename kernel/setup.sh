#!/bin/sh
set -eu

die() {
    printf '[ERROR] %s\n' "$*" >&2
    exit 1
}

display_usage() {
    cat <<'USAGE'
Usage: sh /path/to/MilkSU/kernel/setup.sh [--cleanup]

Run from the target ARM64 GKI source root. The default source is the local
MilkSU checkout containing this script. Set MILKSU_SOURCE_ROOT to select a
different local MilkSU checkout, including when invoking the script via stdin.

This script never clones, pulls, stashes, checks out, or deletes a repository.
Remote sources must be obtained separately and passed as MILKSU_SOURCE_ROOT.
KernelSU, KernelSU-Next, KPatch-Next, and KPatch-Next-Module are read-only.

--cleanup removes only this script's exact integration lines and matching
drivers/milksu symlink. It keeps all source trees and other integrations.
USAGE
}

assert_not_reference() {
    checked_path=$(printf '/%s/' "$1" | tr '[:upper:]' '[:lower:]')
    case "$checked_path" in
        */kernelsu/*|*/kernelsu-next/*|*/kpatch-next/*|*/kpatch-next-module/*)
            die "Reference repository is read-only: $1"
            ;;
    esac
}

assert_target_path() {
    assert_not_reference "$1"
    case "$1" in
        "$GKI_ROOT"/*) ;;
        *) die "Integration path escapes the target GKI tree: $1" ;;
    esac
}

mode=setup
case "${1-}" in
    -h|--help)
        display_usage
        exit 0
        ;;
    --cleanup)
        mode=cleanup
        ;;
    '')
        ;;
    *)
        die "Unsupported argument '$1'; select a local checkout with MILKSU_SOURCE_ROOT."
        ;;
esac
[ "$#" -le 1 ] || die "Only --cleanup or --help is accepted."
command -v realpath >/dev/null 2>&1 || die "realpath is required."

GKI_ROOT=$(realpath -e -- "$(pwd -P)") || die "Cannot resolve the target GKI tree."
assert_not_reference "$GKI_ROOT"

if [ -n "${MILKSU_SOURCE_ROOT-}" ]; then
    SOURCE_ROOT=$(realpath -e -- "$MILKSU_SOURCE_ROOT") ||
        die "MILKSU_SOURCE_ROOT does not exist."
elif [ -f "$0" ]; then
    script_path=$(realpath -e -- "$0") || die "Cannot resolve this script."
    SOURCE_ROOT=$(realpath -e -- "$(dirname -- "$script_path")/..")
else
    die "Set MILKSU_SOURCE_ROOT to an existing local MilkSU checkout."
fi
assert_not_reference "$SOURCE_ROOT"
[ -f "$SOURCE_ROOT/kernel/core/root_ref.c" ] &&
    [ -f "$SOURCE_ROOT/uapi/supercall.h" ] &&
    [ -f "$SOURCE_ROOT/kernel/MILKSU_HOOK_PROVENANCE.json" ] ||
    die "MILKSU_SOURCE_ROOT is not a MilkSU source tree."
grep -Eq '"product"[[:space:]]*:[[:space:]]*"MilkSU"' \
    "$SOURCE_ROOT/kernel/MILKSU_HOOK_PROVENANCE.json" ||
    die "The selected source does not identify itself as MilkSU."
SOURCE_KERNEL=$(realpath -e -- "$SOURCE_ROOT/kernel")
assert_not_reference "$SOURCE_KERNEL"

if [ -d "$GKI_ROOT/common/drivers" ]; then
    DRIVER_DIR=$(realpath -e -- "$GKI_ROOT/common/drivers")
elif [ -d "$GKI_ROOT/drivers" ]; then
    DRIVER_DIR=$(realpath -e -- "$GKI_ROOT/drivers")
else
    die "No common/drivers or drivers directory in the target GKI tree."
fi
assert_target_path "$DRIVER_DIR"
DRIVER_MAKEFILE=$(realpath -e -- "$DRIVER_DIR/Makefile") ||
    die "Target drivers/Makefile is missing."
DRIVER_KCONFIG=$(realpath -e -- "$DRIVER_DIR/Kconfig") ||
    die "Target drivers/Kconfig is missing."
assert_target_path "$DRIVER_MAKEFILE"
assert_target_path "$DRIVER_KCONFIG"
DRIVER_LINK="$DRIVER_DIR/milksu"
MAKE_LINE='obj-$(CONFIG_KSU) += milksu/ # MilkSU setup'
KCONFIG_LINE='source "drivers/milksu/Kconfig" # MilkSU setup'

# Never take over a different checkout or remove an unowned directory/link.
if [ -L "$DRIVER_LINK" ]; then
    link_target=$(realpath -e -- "$DRIVER_LINK") ||
        die "drivers/milksu is a dangling symlink; inspect it manually."
    [ "$link_target" = "$SOURCE_KERNEL" ] ||
        die "drivers/milksu belongs to a different source tree."
elif [ -e "$DRIVER_LINK" ]; then
    die "drivers/milksu already exists and is not a managed symlink."
fi

make_tmp=
kconfig_tmp=
cleanup_temps() {
    [ -z "$make_tmp" ] || rm -f -- "$make_tmp"
    [ -z "$kconfig_tmp" ] || rm -f -- "$kconfig_tmp"
}
trap cleanup_temps EXIT
trap 'exit 1' HUP INT TERM

make_tmp=$(mktemp "$DRIVER_MAKEFILE.milksu.XXXXXX")
kconfig_tmp=$(mktemp "$DRIVER_KCONFIG.milksu.XXXXXX")

if [ "$mode" = cleanup ]; then
    awk -v line="$MAKE_LINE" '$0 != line' "$DRIVER_MAKEFILE" >"$make_tmp"
    awk -v line="$KCONFIG_LINE" '$0 != line' "$DRIVER_KCONFIG" >"$kconfig_tmp"
else
    # Another root integration must be removed deliberately by its owner.
    [ ! -e "$DRIVER_DIR/kernelsu" ] && [ ! -L "$DRIVER_DIR/kernelsu" ] ||
        die "Existing drivers/kernelsu integration must be handled separately."
    if grep -Fv "$MAKE_LINE" "$DRIVER_MAKEFILE" |
        grep -Eq '^[[:space:]]*obj-.*CONFIG_KSU[)]'; then
        die "drivers/Makefile already contains another CONFIG_KSU integration."
    fi
    if grep -Fv "$KCONFIG_LINE" "$DRIVER_KCONFIG" |
        grep -Eq '^[[:space:]]*source[[:space:]]+"drivers/(milksu|kernelsu|KernelSU|KernelSU-Next)/Kconfig"'; then
        die "drivers/Kconfig already contains another root integration."
    fi
    awk '{ print }' "$DRIVER_MAKEFILE" >"$make_tmp"
    awk '{ print }' "$DRIVER_KCONFIG" >"$kconfig_tmp"
    if ! grep -Fqx "$MAKE_LINE" "$DRIVER_MAKEFILE"; then
        printf '\n%s\n' "$MAKE_LINE" >>"$make_tmp"
    fi
    if ! grep -Fqx "$KCONFIG_LINE" "$DRIVER_KCONFIG"; then
        printf '\n%s\n' "$KCONFIG_LINE" >>"$kconfig_tmp"
    fi
fi

chmod --reference="$DRIVER_MAKEFILE" "$make_tmp"
chmod --reference="$DRIVER_KCONFIG" "$kconfig_tmp"
if [ "$mode" = setup ] && [ ! -L "$DRIVER_LINK" ]; then
    ln -s -- "$SOURCE_KERNEL" "$DRIVER_LINK"
fi
mv -- "$make_tmp" "$DRIVER_MAKEFILE"
make_tmp=
mv -- "$kconfig_tmp" "$DRIVER_KCONFIG"
kconfig_tmp=
if [ "$mode" = cleanup ] && [ -L "$DRIVER_LINK" ]; then
    rm -- "$DRIVER_LINK"
fi
printf '[+] MilkSU %s complete: %s (source: %s)\n' "$mode" "$GKI_ROOT" "$SOURCE_ROOT"
