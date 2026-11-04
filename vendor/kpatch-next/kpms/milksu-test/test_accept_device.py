"""Host tests for the acceptance runner, never installed on the device."""

import json
import subprocess
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

from accept_device import AcceptanceFailure, Device, probe, run_acceptance


class DeviceRunnerTests(unittest.TestCase):
    def device(self):
        report = {"commands": []}
        return Device("adb", "TEST-SERIAL", "/data/local/tmp/ksud", 10, report)

    @patch("accept_device.subprocess.run")
    def test_missing_native_backend_is_a_failure(self, run):
        run.return_value = subprocess.CompletedProcess([], 1, "", "unrecognized subcommand 'kpm'")
        with self.assertRaises(AcceptanceFailure):
            probe(self.device())

    @patch("accept_device.subprocess.run")
    def test_hung_unload_never_passes(self, run):
        run.side_effect = subprocess.TimeoutExpired("adb", 10)
        device = self.device()
        with self.assertRaises(AcceptanceFailure):
            device.kpm("unload", "test-id", ok=False, code="reboot_required")
        self.assertTrue(device.report["commands"][0]["timed_out"])

    @patch("accept_device.subprocess.run")
    def test_json_failure_must_match_process_failure(self, run):
        run.return_value = subprocess.CompletedProcess(
            [], 0, json.dumps({"ok": False, "data": None,
                              "error": {"code": "reboot_required", "message": "pending"}}), "")
        with self.assertRaises(AcceptanceFailure):
            self.device().kpm("unload", "test-id", ok=False, code="reboot_required")

    @patch("accept_device.subprocess.run")
    def test_control_arguments_are_quoted_at_both_remote_shell_boundaries(self, run):
        literal = "'single' \"double\" ; $(touch /TEST_ONLY)\nline2"
        run.return_value = subprocess.CompletedProcess(
            [], 0, json.dumps({"ok": True, "data": {"output": literal}, "error": None}), "")
        device = self.device()
        device.kpm("control", "test-id", "--args", literal)
        command = run.call_args.args[0]
        import shlex
        outer = shlex.split(command[-1])
        self.assertEqual(outer[:2], ["su", "-c"])
        inner = shlex.split(outer[2])
        self.assertEqual(inner[-1], literal)
        self.assertEqual(command[:4], ["adb", "-s", "TEST-SERIAL", "shell"])

    @patch("accept_device.subprocess.run")
    def test_returned_pending_unload_is_an_expected_failure(self, run):
        envelope = {"ok": False, "data": None,
                    "error": {"code": "reboot_required", "message": "resident"}}
        run.return_value = subprocess.CompletedProcess([], 1, json.dumps(envelope), "")
        self.assertEqual(
            self.device().kpm("unload", "test-id", ok=False, code="reboot_required"),
            envelope,
        )

    def test_foreign_engine_is_blocked_before_any_push_or_import(self):
        device = Mock()
        device.kpm.return_value = {"data": {
            "supported": False, "active": True,
            "configured_enabled": False, "provider": "unmanaged",
            "version": "0.13.6",
        }}
        with self.assertRaises(AcceptanceFailure):
            run_acceptance(device, Path("out/test-modules"), {"checks": []})
        device.kpm.assert_called_once_with("status")
        device.command.assert_not_called()

    @patch("accept_device.time.sleep")
    @patch("accept_device.subprocess.run")
    def test_readonly_file_check_recovers_from_transport_disconnect(self, run, sleep):
        run.side_effect = [
            subprocess.CompletedProcess([], 1, "", "adb: device 'TEST-SERIAL' not found"),
            subprocess.CompletedProcess([], 0, "", ""),
        ]
        self.device().stored("test-id")
        self.assertEqual(run.call_count, 2)
        sleep.assert_called_once_with(1)

    @patch("accept_device.subprocess.run")
    def test_real_missing_file_is_not_retried(self, run):
        run.return_value = subprocess.CompletedProcess([], 1, "", "")
        with self.assertRaisesRegex(AcceptanceFailure, "Stored fixture"):
            self.device().stored("test-id")
        self.assertEqual(run.call_count, 1)


if __name__ == "__main__":
    unittest.main()
