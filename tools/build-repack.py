#!/usr/bin/env python3
"""Run the repository repacker with password-file providers and no secret argv."""
import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import tempfile


def main() -> int:
    source = Path(sys.argv.pop(1)).resolve()
    out = Path(os.environ.get("MILKSU_OUT", source / "out"))
    password_file = out / "private/keystore-password"
    if not password_file.is_file():
        raise SystemExit("Development signing password file is missing")
    spec = importlib.util.spec_from_file_location("milksu_repack", source / "repack_apk.py")
    if spec is None or spec.loader is None:
        raise SystemExit("Cannot load the repository APK repacker")
    repacker = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(repacker)

    def run_command(arguments, fail_message):
        # apksigner caches password-file readers and consumes a line per option.
        # Give each option its own file so a shared password is read twice.
        with tempfile.TemporaryDirectory(prefix="apksigner-", dir=password_file.parent) as temporary:
            safe_arguments = []
            for index, item in enumerate(arguments):
                if item == "pass:__MILKSU_PASSWORD_FILE__":
                    secret = Path(temporary) / f"password-{index}"
                    secret.write_bytes(password_file.read_bytes())
                    secret.chmod(0o600)
                    safe_arguments.append(f"file:{secret}")
                else:
                    safe_arguments.append(item)
            result = subprocess.run(
                safe_arguments, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True
            )
        if result.returncode:
            # Passwords are passed via files; preserve tool diagnostics without argv expansion.
            raise RuntimeError(f"{fail_message}\n{result.stdout.strip()}")

    repacker.run_cmd = run_command
    return repacker.main()


if __name__ == "__main__":
    sys.exit(main())
