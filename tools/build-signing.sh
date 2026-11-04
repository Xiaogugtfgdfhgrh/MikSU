#!/usr/bin/env bash
# Create once, then reuse the same local development key for every component.
set -Eeuo pipefail
# shellcheck source=build-common.sh
source "$(dirname -- "${BASH_SOURCE[0]}")/build-common.sh"
milksu_freeze "$@"
milksu_log signing
umask 077
private="$MILKSU_OUT/private"
mkdir -p "$private"
store="$private/milksu-dev.p12"
password_file="$private/keystore-password"
alias_name=milksu-dev
if [[ ! -f "$store" ]]; then
    [[ ! -e "$password_file" ]] || milksu_die "Password exists without a keystore; inspect $private before proceeding."
    openssl rand -hex 32 >"$password_file"
    # File password providers prevent secrets appearing in command logs or process arguments.
    keytool -genkeypair -noprompt -storetype PKCS12 -keystore "$store" \
        -alias "$alias_name" -keyalg RSA -keysize 3072 -sigalg SHA256withRSA \
        -validity 10000 -dname 'CN=MilkSU' \
        -storepass:file "$password_file" -keypass:file "$password_file"
fi
[[ -f "$password_file" ]] || milksu_die "Keystore password file is missing."
keytool -exportcert -keystore "$store" -alias "$alias_name" \
    -storepass:file "$password_file" -file "$private/milksu-dev.der"
if [[ "${1:-}" == --compact-certificate && $(stat -c%s "$private/milksu-dev.der") -gt 1024 ]]; then
    before="$(openssl x509 -inform DER -in "$private/milksu-dev.der" -pubkey -noout |
        openssl pkey -pubin -outform DER | sha256sum)"
    if [[ ! -e "$private/milksu-dev-oversize-original.p12" ]]; then
        cp "$store" "$private/milksu-dev-oversize-original.p12"
        cp "$private/milksu-dev.der" "$private/milksu-dev-oversize-original.der"
    fi
    keytool -selfcert -keystore "$store" -alias "$alias_name" \
        -dname 'CN=MilkSU' -validity 10000 -sigalg SHA256withRSA \
        -storepass:file "$password_file" -keypass:file "$password_file"
    keytool -exportcert -keystore "$store" -alias "$alias_name" \
        -storepass:file "$password_file" -file "$private/milksu-dev.der"
    after="$(openssl x509 -inform DER -in "$private/milksu-dev.der" -pubkey -noout |
        openssl pkey -pubin -outform DER | sha256sum)"
    [[ "$before" == "$after" ]] || milksu_die "Public key changed during certificate compaction."
    printf 'Certificate DN compacted; existing private/public key identity preserved.\n'
fi
openssl x509 -inform DER -in "$private/milksu-dev.der" -out "$private/milksu-dev.pem"
python3 - "$private" "$MILKSU_PACKAGE_NAME" "$MILKSU_ROOT/kernel/manager/apk_sign.c" <<'PY'
import hashlib, json, os, pathlib, re, sys
private = pathlib.Path(sys.argv[1])
cert = (private / "milksu-dev.der").read_bytes()
metadata = {
    "package_name": sys.argv[2],
    "key_alias": "milksu-dev",
    "certificate_der": str(private / "milksu-dev.der"),
    "keystore": str(private / "milksu-dev.p12"),
    "certificate_size": len(cert),
    "KSU_PACKAGE_NAME": sys.argv[2],
    "KSU_EXPECTED_SIZE": f"0x{len(cert):04x}",
    "KSU_EXPECTED_HASH": hashlib.sha256(cert).hexdigest(),
    "development_only": True,
}
output = private / "certificate.json"
temporary = output.with_suffix(".json.tmp")
temporary.write_text(json.dumps(metadata, indent=2) + "\n")
os.replace(temporary, output)
print(json.dumps({k: metadata[k] for k in (
    "KSU_PACKAGE_NAME", "KSU_EXPECTED_SIZE", "KSU_EXPECTED_HASH"
)}, indent=2))
source = pathlib.Path(sys.argv[3]).read_text()
limit = re.search(r"#define\s+CERT_MAX_LENGTH\s+(\d+)", source)
if limit and len(cert) > int(limit.group(1)):
    raise SystemExit(
        f"Existing key retained, but certificate is {len(cert)} bytes and kernel "
        f"CERT_MAX_LENGTH is {limit.group(1)}. Coordinate the verifier limit before a final build."
    )
PY
chmod 600 "$private"/*
printf 'Certificate metadata: %s\n' "$private/certificate.json"
