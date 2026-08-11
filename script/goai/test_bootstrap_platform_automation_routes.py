#!/usr/bin/env python3

import importlib.util
import json
import os
import sys
import tempfile
import unittest
import uuid
from pathlib import Path
from types import SimpleNamespace
from unittest import mock


MODULE_PATH = Path(__file__).with_name("bootstrap-platform-automation-routes.py")
SPEC = importlib.util.spec_from_file_location(
    "bootstrap_platform_automation_routes",
    MODULE_PATH,
)
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)

GRANT_ID = "11111111-1111-4111-8111-111111111111"
ACTOR = "agentteams-local/22222222-2222-4222-8222-222222222222"
REF_PREFIX = "goai_"


def token_value(marker):
    return f"e30.{marker}.c2ln"


def token_result(scopes, marker, grant_id=GRANT_ID):
    return {
        "grantId": grant_id,
        "actor": ACTOR,
        "allowedRefPrefix": REF_PREFIX,
        "maxProjects": 1,
        "scopes": list(scopes),
        "expiresAt": "2033-05-18T03:38:20Z",
        "token": token_value(marker),
    }


def state(status="active", grant_id=GRANT_ID):
    return MODULE.GrantState(
        status=status,
        grant_id=grant_id,
        actor=ACTOR,
        allowed_ref_prefix=REF_PREFIX,
        max_projects=1,
        approval_binding="approval-1",
        grant_ttl_seconds=3600,
        created_at_epoch_seconds=2_000_000_000,
    )


def command_args(state_file, console_file):
    return SimpleNamespace(
        command="bootstrap",
        metadata_root_file=None,
        metadata_root_fd=3,
        console_session_file=str(console_file),
        console_session_fd=None,
        grant_state_file=str(state_file),
        actor_prefix="agentteams-local",
        allowed_ref_prefix=REF_PREFIX,
        max_projects=1,
        approval_binding="approval-1",
        grant_ttl_seconds=3600,
        token_ttl_seconds=600,
        admin_base_url=MODULE.ADMIN_BASE_URL,
        platform_endpoint=MODULE.PLATFORM_ENDPOINT,
    )


class FakeResponse:
    def __init__(self, status, payload):
        self.status = status
        self.payload = payload

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        return False

    def read(self, limit):
        return self.payload[:limit]


class RecordingOpener:
    def __init__(self, responses):
        self.responses = list(responses)
        self.requests = []

    def open(self, request, timeout):
        self.requests.append((request, timeout))
        status, payload = self.responses.pop(0)
        return FakeResponse(status, payload)


class FakeAdminClient:
    def __init__(self, fail_create=False, fail_read=False):
        self.fail_create = fail_create
        self.fail_read = fail_read
        self.created = []
        self.minted = []
        self.revoked = []

    def create_grant(self, request_body):
        self.created.append(request_body)
        if self.fail_create:
            raise MODULE.OperatorError("SIMULATED_CREATE_FAILURE")
        return MODULE.TokenResult(
            GRANT_ID,
            request_body["actor"],
            REF_PREFIX,
            1,
            MODULE.BUILD_SCOPES,
            bytearray(token_value("YnVpbGQ").encode("ascii")),
        )

    def mint_token(self, grant_id, scope, token_ttl_seconds, grant_state):
        self.minted.append((grant_id, scope, token_ttl_seconds, grant_state.actor))
        if self.fail_read and scope == "read":
            raise MODULE.OperatorError("SIMULATED_MINT_FAILURE")
        scopes = MODULE.BUILD_SCOPES if scope == "build" else MODULE.READ_SCOPES
        return MODULE.TokenResult(
            grant_id,
            grant_state.actor,
            grant_state.allowed_ref_prefix,
            grant_state.max_projects,
            scopes,
            bytearray(token_value(scope).encode("ascii")),
        )

    def revoke(self, grant_id):
        self.revoked.append(grant_id)


class AdminClientBoundaryTest(unittest.TestCase):
    def test_create_mint_and_revoke_keep_root_only_in_authorization_header(self):
        responses = [
            (201, json.dumps(token_result(MODULE.BUILD_SCOPES, "YnVpbGQ")).encode()),
            (200, json.dumps(token_result(MODULE.READ_SCOPES, "cmVhZA")).encode()),
            (204, b""),
        ]
        opener = RecordingOpener(responses)
        root = bytearray(b"metadata-root-sensitive-sentinel")
        client = MODULE.AdminClient(root, opener=opener)
        create_body = {
            "actor": ACTOR,
            "scope": "build",
            "allowedRefPrefix": REF_PREFIX,
            "maxProjects": 1,
            "approvalBinding": "approval-1",
            "grantTtlSeconds": 3600,
            "tokenTtlSeconds": 600,
        }

        build = client.create_grant(create_body)
        mint_state = state()
        read = client.mint_token(GRANT_ID, "read", 600, mint_state)
        client.revoke(GRANT_ID)

        self.assertEqual(build.grant_id, read.grant_id)
        self.assertEqual(len(opener.requests), 3)
        for request, _timeout in opener.requests:
            self.assertEqual(
                request.get_header("Authorization"),
                "Bearer metadata-root-sensitive-sentinel",
            )
            self.assertNotIn("metadata-root-sensitive-sentinel", request.full_url)
            if request.data is not None:
                self.assertNotIn(b"metadata-root-sensitive-sentinel", request.data)
        self.assertTrue(opener.requests[1][0].full_url.endswith(f"/{GRANT_ID}/tokens"))

    def test_token_response_must_match_same_grant_actor_and_exact_scopes(self):
        wrong_grant = token_result(MODULE.READ_SCOPES, "cmVhZA", grant_id="33333333-3333-4333-8333-333333333333")
        with self.assertRaisesRegex(MODULE.OperatorError, "ADMIN_TOKEN_GRANT_ID_MISMATCH"):
            MODULE.validate_token_result(
                wrong_grant,
                ACTOR,
                REF_PREFIX,
                1,
                MODULE.READ_SCOPES,
                expected_grant_id=GRANT_ID,
            )


class GrantLifecycleTest(unittest.TestCase):
    def test_bootstrap_requires_a_run_specific_approval_binding(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            os.chmod(root, 0o700)
            args = command_args(root / "grant.json", root / "console.cookie")
            args.approval_binding = None

            with self.assertRaisesRegex(
                MODULE.OperatorError,
                "APPROVAL_BINDING_REQUIRED",
            ):
                MODULE.validate_arguments(args)

    def test_bootstrap_persists_active_token_free_state_before_route_success(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            os.chmod(root, 0o700)
            state_file = root / "grant.json"
            console_file = root / "console.cookie"
            console_file.write_bytes(b"cookie")
            os.chmod(console_file, 0o600)
            args = command_args(state_file, console_file)
            client = FakeAdminClient()

            def assert_state_precedes_routes(_args, _build, _read):
                persisted_before_route = MODULE.read_grant_state(state_file)
                self.assertEqual(persisted_before_route.status, "active")
                self.assertEqual(persisted_before_route.grant_id, GRANT_ID)

            with mock.patch.object(
                MODULE.uuid,
                "uuid4",
                side_effect=(
                    uuid.UUID("22222222-2222-4222-8222-222222222222"),
                    uuid.UUID("33333333-3333-4333-8333-333333333333"),
                ),
            ), mock.patch.object(
                MODULE,
                "configure_routes",
                side_effect=assert_state_precedes_routes,
            ) as configure:
                MODULE.bootstrap(client, args)

            configure.assert_called_once()
            persisted = MODULE.read_grant_state(state_file)
            self.assertEqual(persisted.status, "active")
            self.assertEqual(persisted.grant_id, GRANT_ID)
            self.assertEqual(stat_mode(state_file), 0o600)
            state_text = state_file.read_text(encoding="utf-8")
            self.assertNotIn("YnVpbGQ", state_text)
            self.assertNotIn("cmVhZA", state_text)
            self.assertEqual(client.minted[0][0:2], (GRANT_ID, "read"))

    def test_route_failure_contains_and_revokes_same_grant(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            os.chmod(root, 0o700)
            state_file = root / "grant.json"
            console_file = root / "console.cookie"
            console_file.write_bytes(b"cookie")
            os.chmod(console_file, 0o600)
            args = command_args(state_file, console_file)
            client = FakeAdminClient()

            with mock.patch.object(
                MODULE.uuid,
                "uuid4",
                side_effect=(
                    uuid.UUID("22222222-2222-4222-8222-222222222222"),
                    uuid.UUID("33333333-3333-4333-8333-333333333333"),
                ),
            ), mock.patch.object(
                MODULE,
                "configure_routes",
                side_effect=MODULE.OperatorError("SIMULATED_ROUTE_FAILURE"),
            ), mock.patch.object(MODULE, "contain_routes") as contain:
                with self.assertRaisesRegex(MODULE.OperatorError, "SIMULATED_ROUTE_FAILURE"):
                    MODULE.bootstrap(client, args)

            contain.assert_called_once_with(args)
            self.assertEqual(client.revoked, [GRANT_ID])
            self.assertFalse(state_file.exists())

    def test_unknown_create_result_leaves_pending_actor_for_audit(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            os.chmod(root, 0o700)
            state_file = root / "grant.json"
            console_file = root / "console.cookie"
            console_file.write_bytes(b"cookie")
            os.chmod(console_file, 0o600)
            args = command_args(state_file, console_file)
            client = FakeAdminClient(fail_create=True)

            with mock.patch.object(
                MODULE.uuid,
                "uuid4",
                return_value=uuid.UUID("22222222-2222-4222-8222-222222222222"),
            ):
                with self.assertRaisesRegex(MODULE.OperatorError, "SIMULATED_CREATE_FAILURE"):
                    MODULE.bootstrap(client, args)

            pending = MODULE.read_grant_state(state_file)
            self.assertEqual(pending.status, "pending")
            self.assertIsNone(pending.grant_id)
            self.assertEqual(pending.actor, ACTOR)

    def test_rotation_failure_contains_routes_revokes_grant_and_deletes_state(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            os.chmod(root, 0o700)
            state_file = root / "grant.json"
            MODULE.write_grant_state(state_file, state())
            console_file = root / "console.cookie"
            console_file.write_bytes(b"cookie")
            os.chmod(console_file, 0o600)
            args = command_args(state_file, console_file)
            args.command = "rotate"
            client = FakeAdminClient(fail_read=True)

            with mock.patch.object(MODULE, "contain_routes") as contain:
                with self.assertRaisesRegex(MODULE.OperatorError, "SIMULATED_MINT_FAILURE"):
                    MODULE.rotate(client, args)

            contain.assert_called_once_with(args)
            self.assertEqual(client.minted[0][0:2], (GRANT_ID, "build"))
            self.assertEqual(client.minted[1][0:2], (GRANT_ID, "read"))
            self.assertEqual(client.revoked, [GRANT_ID])
            self.assertFalse(state_file.exists())

    def test_rotation_containment_failure_revokes_grant_as_auth_backstop(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            os.chmod(root, 0o700)
            state_file = root / "grant.json"
            MODULE.write_grant_state(state_file, state())
            console_file = root / "console.cookie"
            console_file.write_bytes(b"cookie")
            os.chmod(console_file, 0o600)
            args = command_args(state_file, console_file)
            args.command = "rotate"
            client = FakeAdminClient(fail_read=True)

            with mock.patch.object(
                MODULE,
                "contain_routes",
                side_effect=MODULE.OperatorError("SIMULATED_CONTAINMENT_FAILURE"),
            ):
                with self.assertRaisesRegex(
                    MODULE.OperatorError,
                    "PLATFORM_CONTAINMENT_FAILED",
                ):
                    MODULE.rotate(client, args)

            self.assertEqual(client.revoked, [GRANT_ID])
            self.assertFalse(state_file.exists())


class SecretAndSubprocessBoundaryTest(unittest.TestCase):
    def test_secret_sources_require_owner_0600_regular_files(self):
        with tempfile.NamedTemporaryFile() as handle:
            handle.write(b"root-token")
            handle.flush()
            os.chmod(handle.name, 0o600)
            value = MODULE.read_secret_source(handle.name, None, "METADATA_ROOT", 128)
            self.assertEqual(value, b"root-token")
            os.chmod(handle.name, 0o644)
            with self.assertRaisesRegex(MODULE.OperatorError, "METADATA_ROOT_PERMISSIONS_INVALID"):
                MODULE.read_secret_source(handle.name, None, "METADATA_ROOT", 128)

    def test_route_subprocess_receives_only_token_file_paths(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            os.chmod(root, 0o700)
            console_file = root / "console.cookie"
            console_file.write_bytes(b"cookie")
            os.chmod(console_file, 0o600)
            args = command_args(root / "grant.json", console_file)
            build = bytearray(b"e30.YnVpbGQ.c2ln")
            read = bytearray(b"e30.cmVhZA.c2ln")
            captured_paths = []

            def fake_popen(command, **kwargs):
                command_text = " ".join(command)
                self.assertNotIn(bytes(build).decode(), command_text)
                self.assertNotIn(bytes(read).decode(), command_text)
                build_path = Path(command[command.index("--project-build-token-file") + 1])
                read_path = Path(command[command.index("--project-read-token-file") + 1])
                captured_paths.extend((build_path, read_path))
                self.assertEqual(stat_mode(build_path), 0o600)
                self.assertEqual(stat_mode(read_path), 0o600)
                self.assertEqual(kwargs["stdin"], MODULE.subprocess.DEVNULL)
                self.assertEqual(
                    kwargs["env"],
                    {
                        "PATH": MODULE.SAFE_PROCESS_PATH,
                        "PYTHONDONTWRITEBYTECODE": "1",
                    },
                )
                self.assertTrue(kwargs["start_new_session"])
                return SimpleNamespace(
                    returncode=0,
                    communicate=lambda timeout: (b"", b""),
                )

            with mock.patch.object(MODULE.subprocess, "Popen", side_effect=fake_popen):
                MODULE.configure_routes(args, build, read)

            self.assertTrue(all(not path.exists() for path in captured_paths))

    def test_route_subprocess_surfaces_only_fixed_child_error_code(self):
        process = SimpleNamespace(
            returncode=1,
            communicate=lambda timeout: (
                b"",
                b"Platform automation route refresh failed: SERVER_NOT_FOUND\n",
            ),
        )
        with mock.patch.object(MODULE.subprocess, "Popen", return_value=process):
            with self.assertRaisesRegex(
                MODULE.OperatorError,
                "ROUTE_SERVER_NOT_FOUND",
            ):
                MODULE.run_configure_command(["python3", "helper.py"], ())

        containment_process = SimpleNamespace(
            returncode=1,
            communicate=lambda timeout: (
                b"",
                b"Platform automation route refresh failed: "
                b"ROUTE_CONTAINMENT_INCOMPLETE\n",
            ),
        )
        with mock.patch.object(
            MODULE.subprocess,
            "Popen",
            return_value=containment_process,
        ):
            with self.assertRaisesRegex(
                MODULE.OperatorError,
                "^ROUTE_CONTAINMENT_INCOMPLETE$",
            ):
                MODULE.run_configure_command(["python3", "helper.py"], ())

    def test_route_timeout_terminates_process_group_and_waits_for_cleanup(self):
        communicate_calls = 0

        def communicate(timeout):
            nonlocal communicate_calls
            communicate_calls += 1
            if communicate_calls == 1:
                raise MODULE.subprocess.TimeoutExpired("configure", timeout)
            return b"", b""

        process = SimpleNamespace(
            pid=12345,
            returncode=1,
            communicate=communicate,
        )
        with mock.patch.object(
            MODULE.subprocess,
            "Popen",
            return_value=process,
        ), mock.patch.object(MODULE.os, "killpg") as killpg:
            with self.assertRaisesRegex(
                MODULE.OperatorError,
                "ROUTE_CONFIGURATION_TIMEOUT",
            ):
                MODULE.run_configure_command(["python3", "helper.py"], ())

        killpg.assert_called_once_with(process.pid, MODULE.signal.SIGTERM)
        self.assertEqual(communicate_calls, 2)


def stat_mode(path):
    return os.stat(path).st_mode & 0o777


if __name__ == "__main__":
    unittest.main()
