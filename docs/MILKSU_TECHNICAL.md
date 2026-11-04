# MilkSU Technical Notes

[Return to the MilkSU overview](../README.md).

MilkSU combines the KernelSU userspace and Android manager with the
KernelSU-Next ARM64 hook backend, an optional SUSFS guard LKM, and an optional
KPatch-Next engine for kernel patch modules.

Source repository: https://github.com/Xiaogugtfgdfhgrh/MikSU

The current source version is **0.2.0 (32800)**. Manager, daemon and root
modules read `version.properties`; source commits do not alter the version.
The formally signed `v0.2.0` APK is named `MilkSU_0.2.0_32800.apk`.
Stable APKs are distributed through this repository's GitHub Releases.

The Manager restores its confirmed integration setting before drawing the
navigation bar. Background state refreshes preserve the settings controls
and KPM import action. The KPM page has its own title and direct module
control cards, with a temporary-load confirmation after file selection.

Device validation currently covers one physical ARM64 tablet running GKI
`android14-6.1`. Other kernel targets require their own device validation.

## Validation Scope

On November 4, 2026, MilkSU **0.2.0 (32800)** completed these checks on that
single `android14-6.1` device:

- The 0.2.0 Manager and matching new root module started successfully.
- AVC log spoofing passed 24 checks using real SELinux AVC audit records.
- Temporary SU disable passed 18 checks.
- SUSFS integration off/on through the Manager UI passed 21 checks.
- ARM64 SUSFS native acceptance passed 10 checks.
- Manager UI root authorization, a profile using GID 2000, and authorization
  revocation were verified. The foreground root-policy probe passed after
  authorization; root log access and the foreground probe are used where
  log visibility or OEM background broadcast delivery affects collection.
- KPM fixture behavior and error handling passed 35 checks.
- The repaired KPM document-picker flow passed 15 UI checks, including
  returning to the KPM page after selection or cancellation and removing only
  the new unloaded registration when its loading confirmation is cancelled.

KPM integration completed a Manager UI off/on cycle and both reboots:

- After disabling integration and the first reboot, the disabled state passed
  18 checks.
- Enabling integration through the UI reproduced the previous boot image
  byte for byte. After the second reboot, 12 checks confirmed KPM was active,
  the existing user driver's loaded and automatic-loading state matched the
  baseline, SUSFS was loaded with automatic loading enabled, and AVC remained
  enabled with its persisted setting and shared root/SUSFS service verified.
- A final five-check comparison confirmed that the user driver's KPM state
  and ordinary module configuration were unchanged from the baseline.

These results apply to one device and kernel configuration. No compatible
32-bit ABI was available, so 32-bit behavior was not tested. The first stable
Release is available, but a higher-version Manager update download and
installation have not completed device acceptance. Local builds and host tests establish compilation
and protocol behavior, not compatibility with other vendor kernels.

KPM callback-section metadata uses inline character arrays so the movable
flat payload contains no unadjusted string pointers. The engine build checks
this ARM64 relocation constraint. The bundled ordinary-module installer
normalizes Windows line endings before embedding.

Public deliverables are prepared in `out/delivery/` by
`tools/package-release.py`: source, the signed release APK, native assets
and selected validation conclusions. Both public archives exclude partition
images, including stock-derived development images, exact device backups,
raw device logs, device identifiers and signing material. The artifact archive
retains the legacy filename `MilkSU-build-and-images.zip`, but contains no
partition images; its image manifest records `images_included=false`.
Validation reports contribute only selected, sanitized conclusions.
The root ramdisk used for the current validation retained `bundled=1`.
`allow_shell=1` is an explicit opt-in, not a default root configuration
requirement. Device images, backups and raw evidence remain local recovery
and validation inputs.

## Build

Run the scripts in `tools/` from Ubuntu or WSL Ubuntu. Build dependencies and
caches are kept outside the source tree. `tools/bootstrap-milksu.sh` prepares
the toolchains; the `tools/build-*.sh` scripts build the individual components.
Release signing retains the dedicated RSA-3072 private key and exact public
certificate used in device acceptance. Promotion changes the keystore
container, alias and password while preserving the signer identity, so APK
upgrades and the kernel's Manager authentication continue to use the same
certificate. This is the dedicated MilkSU identity, not Android's debug key.

`tools/build-signing.sh` defaults to the `release` profile. Explicit
`--promote-development` conversion retains the tested identity in
`out/private/milksu-release.p12`, with alias `milksu-release` and its new password
in `out/private/release-password`. The `development` profile is an explicit
local-build option. Release builds set `MILKSU_SIGNING_PROFILE=release` and
`MILKSU_REQUIRE_RELEASE_SIGNING=1`; the Manager signer and kernel certificate
parameters must match the selected profile.

CI references `MILKSU_RELEASE_KEYSTORE_B64` and
`MILKSU_RELEASE_KEYSTORE_PASSWORD`. Configure both repository secrets before
running the manual CI build. Local builds use the files under `out/private/`;
changing the workflow does not upload the local private key. Signing material is
excluded by the public source and artifact packaging checks.

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
The first `v0.2.0` Release establishes the public signed baseline;
publishing it does not demonstrate an update to a higher version.
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
