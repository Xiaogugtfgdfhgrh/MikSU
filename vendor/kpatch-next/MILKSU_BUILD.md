# Building the vendored engine and tools

Run from the MilkSU root in Linux/WSL. Do not build in the original repositories.
The engine uses GNU AArch64 bare-metal tools (upstream CI uses GNU 12.2.rel1).

```sh
make -C vendor/kpatch-next/kernel TARGET_COMPILE=/path/to/bin/aarch64-none-elf-
```

This produces `kernel/kpimg` and `kernel/kpimg.elf`. The `hdr` prerequisite
generates `user/uapi`, `user/version`, and `tools/preset.h`; run it before
building userspace tools separately:

```sh
make -C vendor/kpatch-next/kernel TARGET_COMPILE=aarch64-none-elf- hdr
cmake -S vendor/kpatch-next/tools -B vendor/kpatch-next/tools/build/android \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
  -DCMAKE_BUILD_TYPE=Release -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-33
cmake --build vendor/kpatch-next/tools/build/android --parallel
```

The Makefile explicitly disables outlined atomics, stack protectors, PIC, and
PIE for the freestanding payload. Keep those flags when using a distribution
`aarch64-linux-gnu-` compiler instead of the bare-metal toolchain. Early map
code must have no unresolved memcpy/stack-guard symbol or GOT dependency.

The Android tool build requires NDK zlib. Host kptools requires the host zlib
development package. The original all-in-one build script also builds the
legacy CLI; MilkSU's native Rust KPM backend does not require that executable.

Kernel prerequisites: little-endian ARM64, `CONFIG_KALLSYMS=y`,
`CONFIG_KALLSYMS_ALL=y`, no existing `CONFIG_KPM=y`, supported image/symbol
layout, and writable kernel text/page-table mappings. GKI/KMI identity alone
does not establish compatibility with PAC/BTI/CFI or protected-memory variants.
The kpimg payload is not a `.ko` and is installed in the kernel-bearing boot
image. The root ramdisk may reside in a different same-slot partition.

Host regressions (compile the actual loader with isolated host shims):

```sh
cc -std=gnu11 -pthread -Wall -Wextra -Werror -Wno-unused-parameter \
  -Wno-sign-compare \
  vendor/kpatch-next/tests/loader_host.c -o /tmp/milksu-kpm-loader-test
/tmp/milksu-kpm-loader-test
cargo test --manifest-path vendor/kpatch-next/tests/native/Cargo.toml
cc -std=gnu11 -O1 -g -fsanitize=address,undefined \
  -fno-sanitize-recover=undefined -Wno-unused-parameter -Wno-format \
  vendor/kpatch-next/tests/relocation_host.c -o /tmp/milksu-kpm-relocation-test
/tmp/milksu-kpm-relocation-test
```

The relocator harness compiles the production `relo.c` arithmetic; it does not
execute ARM64 module instructions on the host. It reproduced a 64-bit shift of
a 32-bit int in ABS64 before the fix. A passing host test does not establish the
cause of a device reboot. Diagnostic engines emit bounded `KPM_LOAD checkpoint=`
lines for file IO, ELF validation, allocation, relocation and callback entry/
return. Capture `dmesg -w` independently through the manual device load; the
normal startup log collector stops before late runtime tests.

Production Rust dependency requested from the main agent: `goblin = "0.10.7"`.
The backend otherwise uses existing anyhow, clap (derive), serde_json, sha256,
tempfile, libc, and log dependencies. No serde derive dependency is required.
