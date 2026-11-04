# MilkSU Manager

The default app ID is `io.milksu.manager`. Kotlin, JNI and AIDL remain under
`me.weishu.kernelsu` for compatibility. Existing module protocols, module
registry and profile-template sources are preserved.

## Build Configuration

The version is `0.2.0 (32800)` from the repository's `version.properties`.
The Manager, daemon and kernel share this file. Increase its version name
and code together for each release; source-only commits leave it unchanged.

Core updates default to the latest stable Release of
`Xiaogugtfgdfhgrh/MikSU`. `MILKSU_UPDATE_URL` can be configured through a
Gradle property or environment variable. A stable tag such as `v0.2.1` must
contain an uploaded APK named `MilkSU_0.2.1_32801.apk` with a higher version
code. Drafts, prereleases, PR APKs, missing Releases and malformed responses
are ignored. Downloads are constrained to the configured GitHub repository.
Reported size and SHA-256 are checked when supplied, and Android requires a
matching app signer to install an update.

The formally signed `v0.2.0` Release uses `MilkSU_0.2.0_32800.apk`.
Positive update detection, download and installation from a higher-version
Release have not completed device acceptance.
An installed build with version code 32800 requires a higher version code
for the Manager to offer an update.

Release signing continues to use `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`,
`KEY_ALIAS` and `KEY_PASSWORD` through the existing apksign plugin. Secrets
must be supplied by the build environment. `tools/build-signing.sh` defaults
to the `release` profile; `development` is selected explicitly for local
development builds. Release builds require
`MILKSU_SIGNING_PROFILE=release` and `MILKSU_REQUIRE_RELEASE_SIGNING=1`.

The release identity promotes the dedicated RSA-3072 key already used for
device acceptance, preserving the exact public certificate and private key
for APK upgrade compatibility and kernel Manager authentication. Explicit
`--promote-development` conversion changes the container, alias and password
to `out/private/milksu-release.p12`, alias `milksu-release`, and
`out/private/release-password`. It does not regenerate the certificate.

The build workflow references `MILKSU_RELEASE_KEYSTORE_B64` and
`MILKSU_RELEASE_KEYSTORE_PASSWORD`; configure both before running the manual
CI build. Private signing files are excluded from public
source archives and artifact uploads. The workflow builds manually and does
not publish Releases automatically.

Before the Android build, provide the real daemon at
`app/src/main/jniLibs/arm64-v8a/libksud.so`. Do not substitute a placeholder:
the APK needs this executable for all privileged operations.

## KPM And SUSFS

The main pages are Home, Superuser, Modules and Settings. KPM appears between
Home and Superuser only while integration is configured. KPM integration
and SUSFS integration are inline above SELinux Hide and SU Log in Settings.
The KPM page uses the same title hierarchy as other main pages. Its empty
state displays a centered "No modules found" message and the import action.
Enabling SUSFS integration also starts and verifies the guard in the current
boot before saving the autoload configuration.
Home displays SUSFS support, version and variant while integration is enabled.
The AVC log spoofing control is directly below SELinux Hide and reads back
the kernel state after each change before saving its boot configuration.

KPM commands use the `ksud kpm` JSON envelope. SUSFS status uses a direct JSON
object from `ksud susfs status`; SUSFS mutations use plain output and exit
status. Operations run serially and re-query the backend. Failed reads retain
previously confirmed data.

KPM integration is disabled by default. The daemon owns the boot-image
transaction and its backup. The Manager invokes integration once; the normal
reboot action applies the pending kernel change. A failed
transition after a kernel reboot is displayed as an error.

Importing a document stages it in app-private cache under a generated name,
passes the path to the root daemon, and removes the staged file afterward.
Selecting a KPM opens the KPModule confirmation with a temporary-load
checkbox. Confirming loads it and saves autoload only when temporary loading
is unchecked. Loaded modules expose control and unload actions directly on
their cards. Per-KPM and SUSFS autoload states are read from the daemon.

## Verification

Run the Android unit tests with `gradlew.bat :app:testDebugUnitTest` once the
Android toolchain and real daemon are available.

`tools/Test-SystemProtocols.ps1` runs the Android-independent protocol,
state, bounded-import and navigation tests with the cached Kotlin 2.0.21
compiler, serialization 1.7.3, coroutines 1.9.0 and JUnit 4.13.2. It does not
build an APK and does not replace the Android build check. It also parses
the new UI and integration files with the Kotlin PSI parser to check syntax;
that check does not resolve Android or Compose types.
