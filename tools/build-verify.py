#!/usr/bin/env python3
"""Verify final APK identity, signer, and embedded native daemon inputs."""
import configparser
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import zipfile


def main() -> None:
    apk = Path(sys.argv[1]).resolve()
    out = Path(os.environ.get("MILKSU_OUT", Path(__file__).resolve().parents[1] / "out"))
    certificate = json.loads((out / "private/certificate.json").read_text())
    root = Path(os.environ.get("MILKSU_ROOT", Path(__file__).resolve().parents[1]))
    release = configparser.ConfigParser(interpolation=None)
    release.read_string("[release]\n" + (root / "version.properties").read_text())
    version_name = release["release"]["MILKSU_VERSION_NAME"]
    version_code = release["release"].getint("MILKSU_VERSION_CODE")
    verifier = (root / "kernel/manager/apk_sign.c").read_text()
    limit = re.search(r"#define\s+CERT_MAX_LENGTH\s+(\d+)", verifier)
    if limit and certificate["certificate_size"] > int(limit.group(1)):
        raise SystemExit("Development certificate exceeds the kernel APK verifier limit")
    module_command = [sys.executable, str(root / "tools/build-verify-modules.py")]
    if os.environ.get("MILKSU_REQUIRE_FULL_MATRIX", "1") != "1":
        module_command += ["--kmi", "android14-6.1", "--kmi", "android12-5.10"]
    modules = subprocess.run(module_command, check=False)
    if modules.returncode:
        raise SystemExit("Final APK blocked: the KMI module set is incomplete or uses stale certificates")
    embedded_assets = json.loads((out / "manifests/embedded-assets-aarch64.json").read_text())
    by_name = {item["name"]: item for item in embedded_assets}
    for module in (out / "aarch64").glob("*.ko"):
        expected = by_name.get(module.name)
        if expected is None or expected["sha256"] != hashlib.sha256(module.read_bytes()).hexdigest():
            raise SystemExit(f"Final APK blocked: daemon embedded asset is stale: {module.name}")
    signing = subprocess.check_output(
        ["apksigner", "verify", "--print-certs", str(apk)], text=True
    )
    match = re.search(r"certificate SHA-256 digest:\s*([0-9a-fA-F]+)", signing)
    if not match or match.group(1).lower() != certificate["KSU_EXPECTED_HASH"]:
        raise SystemExit("APK signing certificate does not match the kernel certificate")
    badging = subprocess.check_output(["aapt2", "dump", "badging", str(apk)], text=True)
    if f"name='{certificate['KSU_PACKAGE_NAME']}'" not in badging.splitlines()[0]:
        raise SystemExit("Unexpected APK applicationId")
    package_line = badging.splitlines()[0]
    if (f"versionCode='{version_code}'" not in package_line or
            f"versionName='{version_name}'" not in package_line):
        raise SystemExit("APK release version does not match version.properties")
    manifest_xml = subprocess.check_output(
        ["aapt2", "dump", "xmltree", "--file", "AndroidManifest.xml", str(apk)], text=True
    )
    if re.search(r"android:testOnly[^\\n]*=true", manifest_xml):
        raise SystemExit("Final APK must install normally; testOnly is still enabled")
    expected = out / "ksud/arm64-v8a/ksud"
    with zipfile.ZipFile(apk) as archive:
        daemon = archive.read("lib/arm64-v8a/libksud.so")
        if hashlib.sha256(daemon).digest() != hashlib.sha256(expected.read_bytes()).digest():
            raise SystemExit("APK libksud.so differs from the source-built release daemon")
        if daemon[:4] != b"\x7fELF" or int.from_bytes(daemon[18:20], "little") != 183:
            raise SystemExit("Embedded ksud is not an AArch64 ELF")
    manifest = {
        "apk": str(apk),
        "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
        "size": apk.stat().st_size,
        "package_name": certificate["KSU_PACKAGE_NAME"],
        "version_name": version_name,
        "version_code": version_code,
        "certificate_sha256": match.group(1).lower(),
        "ksud_sha256": hashlib.sha256(daemon).hexdigest(),
    }
    output = out / "manifests/final-apk.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(manifest, indent=2) + "\n")
    print(json.dumps(manifest, indent=2))


if __name__ == "__main__":
    main()
