# MilkSU

MilkSU combines the KernelSU userspace and Android manager with the
KernelSU-Next ARM64 hook backend, an optional SUSFS guard LKM, and an optional
KPatch-Next engine for kernel patch modules.

Source repository: https://github.com/Xiaogugtfgdfhgrh/MikSU

The current source version is **0.2.0 (32800)**. Manager, daemon and root
modules read `version.properties`; source commits do not alter the version.
This repository currently publishes source only.

The Manager restores its confirmed integration setting before drawing the
navigation bar. Background state refreshes preserve the settings controls
and KPM import action. The KPM page has its own title and direct module
control cards, with a temporary-load confirmation after file selection.

The first device validation target is ARM64 GKI `android14-6.1`. Other kernel
targets require their own build and device validation.

## Validation Scope

An earlier November 3, 2026 acceptance run completed on `android14-6.1`: application
root grants, persistence and revocation; ordinary module installation,
enable/disable, boot scripts and removal; KPM import, load, control and error
handling; and SUSFS runtime path filtering. KPM and SUSFS automatic loading
were checked across reboots, including disabling both settings afterward.
Those results describe the earlier build. Changes made for 0.2.0, including
the AVC feature and update checks, require a separate device acceptance run.
Local builds and host tests establish compilation and protocol behavior,
not compatibility with every vendor kernel.

KPM callback-section metadata uses inline character arrays so the movable
flat payload contains no unadjusted string pointers. The engine build checks
this ARM64 relocation constraint. The bundled ordinary-module installer
normalizes Windows line endings before embedding.

Development deliverables are written to `out/delivery/` by
`tools/package-release.py`. They contain source, the signed development APK,
native assets, stock-derived development images and selected validation
conclusions. The root image uses `allow_shell=1 bundled=1`. Exact device
backups and signing material stay in their local recovery directories.

## Build

Run the scripts in `tools/` from Ubuntu or WSL Ubuntu. Build dependencies and
caches are kept outside the source tree. `tools/bootstrap-milksu.sh` prepares
the toolchains; the `tools/build-*.sh` scripts build the individual components.
The development certificate generated under `out/private/` must be used for
both the manager APK and the kernel's manager-certificate parameters.

Build the root and SUSFS kernel modules, KPatch engine and tools, and ksuinit
before embedding them in ksud and packaging the Android manager. A manager APK
without matching embedded native assets is not an installable root release.

## Manager

The primary pages are Home, Superuser, Modules, and Settings. Enabling KPM
integration inserts KPM between Home and Superuser. KPM and SUSFS integration
switches are in Settings above SELinux Hide and SU Log.
Enabling SUSFS integration starts the guard immediately and saves the boot
setting only after verifying that the guard is operational.

KPM integration is off by default. Installing or removing its kernel engine
requires a full device reboot. Importing a `.kpm` registers a file; loading it
and enabling automatic loading are separate operations. SUSFS automatic
loading is also off on a new installation.

Home shows SUSFS support, version and variant only while its integration is
enabled. The AVC log spoofing setting is directly below SELinux Hide. It uses
the root domain defined by MilkSU, checks the live function prototype through
BTF, and reports failures instead of claiming that an uninstalled hook works.
SUSFS delegates to the same AVC service when the root extension is available.

The Manager checks the latest stable Release of this repository by default.
A future update must publish a non-draft, non-prerelease tag such as `v0.2.1`
with an APK named `MilkSU_0.2.1_32801.apk`, built with an increased version code.
Update assets must use the same application ID and signing identity. Missing
Releases, source-only commits and failed checks do not trigger an update.
Module repositories and KernelSU module/WebUI interfaces retain their
existing compatibility identifiers.

The MilkSU build workflow runs manually and uploads workflow artifacts only.
It does not create Releases, publish packages or deploy the upstream website.

## Image Recovery

MilkSU maintains two different recovery inputs: exact device partition
backups for returning to the previous installation, and the original kernel
saved when KPM is enabled for removing KPM while retaining MilkSU root.
Image transactions verify every backup and staged image before writing,
verify partition contents after writing, and retain a recovery journal.

The optional engine uses the kernel in `boot`; the root ramdisk can be in
`init_boot`, `boot`, or a selected vendor ramdisk. These are separate targets.

Core state retains `/data/adb/ksu`, with the daemon at `/data/adb/ksud`.
MilkSU-specific data uses `/data/adb/ksu/MilkSU`, including KPM state under
`/data/adb/ksu/MilkSU/kpm`. Ordinary modules retain `/data/adb/modules`.
When a running MilkSU KPM engine has lost its integration record, the daemon
verifies the boot kernel against its bundled engine before rebuilding the
record. Status recovery and enabling the already active engine were verified
without changing any boot partition.

The first root backend remains resident after publishing kernel callbacks.
Its hot-unload command rejects the operation before stopping Android services.
Removing root therefore requires restoring the appropriate image and rebooting.
A KPM whose code cannot be proven quiescent also remains resident until reboot;
the manager reports the pending removal instead of freeing active code.

## Sources and Licenses

| Component | Pinned Source Commit |
| --- | --- |
| KernelSU base | `28db74f6f4692e97006ce9ebd8b19b9cc614b5b7` |
| KernelSU-Next hooks and SUSFS | `a6c81fa4a8b7341d0bc5c3e4507531459df871ae` |
| KPatch-Next | `456744b29efb9989445463ab29e368fa59a103c4` |
| KPatch-Next-Module reference | `b5612adf4d4085a253cb0be30fa1be36e11d09db` |

Source provenance is recorded alongside migrated kernel and vendor code.
Kernel components retain their GPLv2 licenses; the original userspace and
manager retain GPLv3-or-later. Each vendored component retains its notices
and license. MilkSU does not change the identity of the upstream authors.
