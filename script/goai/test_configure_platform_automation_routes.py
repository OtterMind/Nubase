#!/usr/bin/env python3

import base64
import importlib.util
import json
import os
import sys
import tempfile
import time
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch


MODULE_PATH = Path(__file__).with_name("configure-platform-automation-routes.py")
SPEC = importlib.util.spec_from_file_location(
    "configure_platform_automation_routes",
    MODULE_PATH,
)
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


def encode(value):
    raw = json.dumps(value, separators=(",", ":")).encode("utf-8")
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def jwt_bytes(scopes, *, now=None, ttl=300, payload_overrides=None, header_overrides=None):
    issued_at = int(time.time()) if now is None else int(now)
    header = {"alg": "HS256", "typ": "JWT", "kid": MODULE.DEFAULT_KEY_ID}
    payload = {
        "iss": MODULE.DEFAULT_ISSUER,
        "aud": [MODULE.DEFAULT_AUDIENCE],
        "sub": "service-account-1",
        "role": "platform_automation",
        "actor_type": "automation",
        "scope": " ".join(scopes),
        "grant_id": "11111111-1111-4111-8111-111111111111",
        "token_version": 1,
        "approval_binding": "approval-1",
        "jti": "token-1",
        "iat": issued_at,
        "nbf": issued_at,
        "exp": issued_at + ttl,
    }
    if header_overrides:
        header.update(header_overrides)
    if payload_overrides:
        payload.update(payload_overrides)
    return bytearray(f"{encode(header)}.{encode(payload)}.c2lnbmF0dXJl".encode("ascii"))


def runtime_contract(tmp_path=None):
    root = Path(tmp_path) if tmp_path is not None else Path("/tmp")
    return MODULE.RuntimeContract(
        endpoint="http://host.docker.internal:9999/platform/mcp",
        issuer=MODULE.DEFAULT_ISSUER,
        audience=MODULE.DEFAULT_AUDIENCE,
        algorithm="HS256",
        max_ttl_seconds=600,
        refresh_helper=root / "refresh.py",
        policy=root / "policy.json",
    )


class RecordingRunner:
    def __init__(self, fail_when=None):
        self.calls = []
        self.fail_when = fail_when

    def run(self, args, *, input_bytes=None, timeout=30):
        record = (tuple(args), None if input_bytes is None else bytes(input_bytes), timeout)
        self.calls.append(record)
        if self.fail_when is not None and self.fail_when(tuple(args), len(self.calls)):
            raise MODULE.OperatorError("SIMULATED_DOCKER_FAILURE")


class PlatformAutomationJwtTest(unittest.TestCase):
    def test_accepts_only_exact_build_and_read_scopes(self):
        now = 2_000_000_000
        contract = runtime_contract()
        build = jwt_bytes(sorted(MODULE.PROJECT_BUILD_SCOPES), now=now)
        read = jwt_bytes(sorted(MODULE.PROJECT_READ_SCOPES), now=now)

        MODULE.validate_jwt(
            build,
            MODULE.JwtContract("PROJECT_BUILD_TOKEN", MODULE.PROJECT_BUILD_SCOPES),
            contract,
            now=now,
        )
        MODULE.validate_jwt(
            read,
            MODULE.JwtContract("PROJECT_READ_TOKEN", MODULE.PROJECT_READ_SCOPES),
            contract,
            now=now,
        )

        with self.assertRaisesRegex(MODULE.OperatorError, "PROJECT_READ_TOKEN_JWT_SCOPE_INVALID"):
            MODULE.validate_jwt(
                build,
                MODULE.JwtContract("PROJECT_READ_TOKEN", MODULE.PROJECT_READ_SCOPES),
                contract,
                now=now,
            )

    def test_identity_includes_token_version_and_approval_binding(self):
        now = 2_000_000_000
        contract = runtime_contract()
        token_contract = MODULE.JwtContract(
            "PROJECT_READ_TOKEN",
            MODULE.PROJECT_READ_SCOPES,
        )
        identity = MODULE.validate_jwt(
            jwt_bytes(sorted(MODULE.PROJECT_READ_SCOPES), now=now),
            token_contract,
            contract,
            now=now,
        )
        replaced = MODULE.validate_jwt(
            jwt_bytes(
                sorted(MODULE.PROJECT_READ_SCOPES),
                now=now,
                payload_overrides={"token_version": 2},
            ),
            token_contract,
            contract,
            now=now,
        )

        self.assertEqual(identity.approval_binding, "approval-1")
        self.assertEqual(identity.token_version, 1)
        self.assertNotEqual(identity, replaced)

        with self.assertRaisesRegex(
            MODULE.OperatorError,
            "PROJECT_READ_TOKEN_JWT_APPROVAL_BINDING_INVALID",
        ):
            MODULE.validate_jwt(
                jwt_bytes(
                    sorted(MODULE.PROJECT_READ_SCOPES),
                    now=now,
                    payload_overrides={"approval_binding": None},
                ),
                token_contract,
                contract,
                now=now,
            )

    def test_rejects_long_lived_or_nearly_expired_tokens(self):
        now = 2_000_000_000
        contract = runtime_contract()
        token_contract = MODULE.JwtContract("PROJECT_READ_TOKEN", MODULE.PROJECT_READ_SCOPES)
        for token, expected in (
            (
                jwt_bytes(sorted(MODULE.PROJECT_READ_SCOPES), now=now, ttl=601),
                "PROJECT_READ_TOKEN_JWT_TTL_TOO_LONG",
            ),
            (
                jwt_bytes(sorted(MODULE.PROJECT_READ_SCOPES), now=now - 250, ttl=300),
                "PROJECT_READ_TOKEN_JWT_TTL_TOO_SHORT",
            ),
        ):
            with self.subTest(expected=expected), self.assertRaisesRegex(
                MODULE.OperatorError,
                expected,
            ):
                MODULE.validate_jwt(token, token_contract, contract, now=now)

    def test_rejects_human_or_wrong_audience_tokens(self):
        now = 2_000_000_000
        contract = runtime_contract()
        token_contract = MODULE.JwtContract("PROJECT_READ_TOKEN", MODULE.PROJECT_READ_SCOPES)
        cases = (
            ({"role": "platform_user"}, "PROJECT_READ_TOKEN_JWT_ROLE_INVALID"),
            ({"actor_type": "human"}, "PROJECT_READ_TOKEN_JWT_ACTOR_INVALID"),
            ({"aud": "another-service"}, "PROJECT_READ_TOKEN_JWT_AUDIENCE_INVALID"),
            ({"platform_role": "super_admin"}, "PROJECT_READ_TOKEN_JWT_CLAIMS_INVALID"),
        )
        for overrides, expected in cases:
            token = jwt_bytes(
                sorted(MODULE.PROJECT_READ_SCOPES),
                now=now,
                payload_overrides=overrides,
            )
            with self.subTest(expected=expected), self.assertRaisesRegex(
                MODULE.OperatorError,
                expected,
            ):
                MODULE.validate_jwt(token, token_contract, contract, now=now)

    def test_rejects_duplicate_json_claims_and_malformed_signature(self):
        now = 2_000_000_000
        header = encode({"alg": "HS256", "typ": "JWT", "kid": "key-1"})
        payload = (
            '{"iss":"nubase-platform","iss":"shadow","aud":"nubase-agentteams-provisioning"}'
        )
        encoded_payload = base64.urlsafe_b64encode(payload.encode("utf-8")).rstrip(b"=").decode("ascii")
        duplicate = bytearray(f"{header}.{encoded_payload}.c2ln".encode("ascii"))
        with self.assertRaisesRegex(MODULE.OperatorError, "JWT_JSON_DUPLICATE_KEY"):
            MODULE.validate_jwt(
                duplicate,
                MODULE.JwtContract("PROJECT_READ_TOKEN", MODULE.PROJECT_READ_SCOPES),
                runtime_contract(),
                now=now,
            )

        malformed = jwt_bytes(sorted(MODULE.PROJECT_READ_SCOPES), now=now)
        malformed[-1] = ord("!")
        with self.assertRaisesRegex(
            MODULE.OperatorError,
            "PROJECT_READ_TOKEN_JWT_SIGNATURE_ENCODING_INVALID",
        ):
            MODULE.validate_jwt(
                malformed,
                MODULE.JwtContract("PROJECT_READ_TOKEN", MODULE.PROJECT_READ_SCOPES),
                runtime_contract(),
                now=now,
            )


class PlatformAutomationSecretSourceTest(unittest.TestCase):
    def test_reads_only_owner_0600_regular_files_and_descriptors(self):
        with tempfile.NamedTemporaryFile() as handle:
            os.chmod(handle.name, 0o600)
            handle.write(b"secret-value")
            handle.flush()

            from_file = MODULE.read_secret_source(
                handle.name,
                None,
                "TEST_SECRET",
                128,
            )
            self.assertEqual(from_file, b"secret-value")

            descriptor = os.open(handle.name, os.O_RDONLY)
            try:
                from_fd = MODULE.read_secret_source(None, descriptor, "TEST_SECRET", 128)
            finally:
                os.close(descriptor)
            self.assertEqual(from_fd, b"secret-value")

    def test_rejects_broad_permissions_symlinks_and_pipe_descriptors(self):
        with tempfile.TemporaryDirectory() as directory:
            secret_path = Path(directory) / "secret"
            secret_path.write_bytes(b"secret-value")
            os.chmod(secret_path, 0o644)
            with self.assertRaisesRegex(MODULE.OperatorError, "TEST_SECRET_PERMISSIONS_INVALID"):
                MODULE.read_secret_source(str(secret_path), None, "TEST_SECRET", 128)

            os.chmod(secret_path, 0o600)
            link_path = Path(directory) / "secret-link"
            link_path.symlink_to(secret_path)
            with self.assertRaisesRegex(MODULE.OperatorError, "TEST_SECRET_SYMLINK_REJECTED"):
                MODULE.read_secret_source(str(link_path), None, "TEST_SECRET", 128)

        read_fd, write_fd = os.pipe()
        try:
            os.write(write_fd, b"secret-value")
            os.close(write_fd)
            write_fd = -1
            with self.assertRaisesRegex(MODULE.OperatorError, "TEST_SECRET_FILE_REQUIRED"):
                MODULE.read_secret_source(None, read_fd, "TEST_SECRET", 128)
        finally:
            os.close(read_fd)
            if write_fd >= 0:
                os.close(write_fd)


class PlatformAutomationContractTest(unittest.TestCase):
    def test_containment_mode_forbids_route_tokens(self):
        args = type(
            "Arguments",
            (),
            {
                "contain_only": True,
                "project_build_token_file": None,
                "project_build_token_fd": None,
                "project_read_token_file": None,
                "project_read_token_fd": None,
            },
        )()
        MODULE.validate_mode_secret_sources(args)
        args.project_build_token_file = "/tmp/unexpected.jwt"
        with self.assertRaisesRegex(MODULE.OperatorError, "BUILD_TOKEN_NOT_ALLOWED"):
            MODULE.validate_mode_secret_sources(args)

    def test_runtime_contract_accepts_only_hs256(self):
        arguments = type(
            "Arguments",
            (),
            {
                "project_build_policy_name": MODULE.PROJECT_BUILD_POLICY_NAME,
                "project_read_policy_name": MODULE.PROJECT_READ_POLICY_NAME,
                "project_build_mcp_server_name": MODULE.PROJECT_BUILD_MCP_SERVER_NAME,
                "project_read_mcp_server_name": MODULE.PROJECT_READ_MCP_SERVER_NAME,
                "issuer": MODULE.DEFAULT_ISSUER,
                "audience": MODULE.DEFAULT_AUDIENCE,
                "algorithm": "HS256",
                "max_ttl_seconds": MODULE.DEFAULT_MAX_TTL_SECONDS,
                "platform_endpoint": "http://host.docker.internal:9999/platform/mcp",
                "refresh_helper": "/tmp/refresh.py",
                "policy": "/tmp/policy.json",
            },
        )()
        MODULE.validate_runtime_contract(arguments)

        for algorithm in ("ES256", "EdDSA", "none"):
            arguments.algorithm = algorithm
            with self.subTest(algorithm=algorithm), self.assertRaisesRegex(
                MODULE.OperatorError,
                "PLATFORM_JWT_ALGORITHM_INVALID",
            ):
                MODULE.validate_runtime_contract(arguments)

        arguments.algorithm = "HS256"
        arguments.issuer = "unexpected-issuer"
        with self.assertRaisesRegex(
            MODULE.OperatorError,
            "PLATFORM_JWT_ISSUER_INVALID",
        ):
            MODULE.validate_runtime_contract(arguments)

        arguments.issuer = MODULE.DEFAULT_ISSUER
        arguments.max_ttl_seconds = 601
        with self.assertRaisesRegex(
            MODULE.OperatorError,
            "PLATFORM_JWT_MAX_TTL_INVALID",
        ):
            MODULE.validate_runtime_contract(arguments)

    def test_endpoint_is_fixed_to_the_local_nubase_platform_path_boundary(self):
        MODULE.validate_platform_endpoint("http://host.docker.internal:9999/platform/mcp")
        invalid = (
            "https://host.docker.internal:9999/platform/mcp",
            "http://external.invalid:9999/platform/mcp",
            "http://host.docker.internal:9998/platform/mcp",
            "http://user@host.docker.internal:9999/platform/mcp",
            "http://host.docker.internal:9999/platform/../mcp",
            "http://host.docker.internal:9999/platform/mcp?token=value",
        )
        for endpoint in invalid:
            with self.subTest(endpoint=endpoint), self.assertRaises(MODULE.OperatorError):
                MODULE.validate_platform_endpoint(endpoint)

    def test_supporting_helper_must_declare_the_platform_contract(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            helper = root / "refresh.py"
            policy = root / "policy.json"
            helper.write_text("\n".join(MODULE.REQUIRED_REFRESH_OPTIONS), encoding="utf-8")
            policy.write_text("{}", encoding="utf-8")
            os.chmod(helper, 0o644)
            os.chmod(policy, 0o644)
            MODULE.validate_supporting_files(helper, policy, repository_root=root)

            helper.write_text("--platform-endpoint", encoding="utf-8")
            with self.assertRaisesRegex(
                MODULE.OperatorError,
                "REFRESH_HELPER_PLATFORM_CONTRACT_MISSING",
            ):
                MODULE.validate_supporting_files(helper, policy, repository_root=root)

    def test_docker_binary_must_be_absolute_executable_and_not_writable(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            docker = root / "docker"
            docker.write_text("#!/bin/sh\n", encoding="utf-8")
            os.chmod(docker, 0o755)

            self.assertEqual(
                MODULE.resolve_trusted_docker_binary((docker,)),
                str(docker),
            )
            for mode in (0o644, 0o775):
                os.chmod(docker, mode)
                with self.subTest(mode=oct(mode)), self.assertRaisesRegex(
                    MODULE.OperatorError,
                    "DOCKER_BINARY_UNAVAILABLE",
                ):
                    MODULE.resolve_trusted_docker_binary((docker,))
            with self.assertRaisesRegex(
                MODULE.OperatorError,
                "DOCKER_BINARY_UNAVAILABLE",
            ):
                MODULE.resolve_trusted_docker_binary((Path("docker"),))

    def test_docker_environment_is_minimal_and_ignores_caller_secrets(self):
        with patch.dict(
            os.environ,
            {"UNRELATED_SECRET_SENTINEL": "must-not-be-forwarded"},
            clear=False,
        ):
            environment = MODULE.docker_subprocess_environment()

        self.assertEqual(
            set(environment),
            {"HOME", "LOGNAME", "PATH", "USER"},
        )
        self.assertEqual(environment["PATH"], MODULE.DOCKER_SUBPROCESS_PATH)
        self.assertNotIn("UNRELATED_SECRET_SENTINEL", environment)


class PlatformAutomationDockerBoundaryTest(unittest.TestCase):
    def setUp(self):
        self.contract = runtime_contract()
        self.cookie = bytearray(b"cookie-sensitive-sentinel")
        self.build_token = bytearray(b"build-token-sensitive-sentinel")
        self.read_token = bytearray(b"read-token-sensitive-sentinel")

    def test_success_streams_secrets_only_to_controller_stdin_and_cleans_everything(self):
        runner = RecordingRunner()
        MODULE.configure_routes(
            runner,
            self.contract,
            self.cookie,
            self.build_token,
            self.read_token,
        )

        argument_text = "\n".join(" ".join(call[0]) for call in runner.calls)
        for secret in (self.cookie, self.build_token, self.read_token):
            self.assertNotIn(bytes(secret).decode("ascii"), argument_text)
        self.assertNotIn("hiclaw-worker", argument_text)

        streamed = [call[1] for call in runner.calls if call[1] is not None]
        self.assertEqual(
            streamed,
            [bytes(self.cookie), bytes(self.build_token), bytes(self.read_token)],
        )
        refresh_calls = [
            call
            for call in runner.calls
            if MODULE.CONTROLLER_REFRESH_HELPER in call[0] and "python3" in call[0]
        ]
        self.assertEqual(len(refresh_calls), 1)
        refresh_args = refresh_calls[0][0]
        self.assertIn("timeout", refresh_args)
        self.assertIn(str(MODULE.CONTROLLER_REFRESH_TIMEOUT_SECONDS), refresh_args)
        self.assertEqual(refresh_calls[0][2], MODULE.DOCKER_REFRESH_TIMEOUT_SECONDS)
        self.assertIn(MODULE.PROJECT_BUILD_POLICY_NAME, refresh_args)
        self.assertIn(MODULE.PROJECT_READ_POLICY_NAME, refresh_args)
        self.assertIn(MODULE.PROJECT_BUILD_MCP_SERVER_NAME, refresh_args)
        self.assertIn(MODULE.PROJECT_READ_MCP_SERVER_NAME, refresh_args)
        for consumer in MODULE.PROJECT_BUILD_CONSUMERS + MODULE.PROJECT_READ_CONSUMERS:
            self.assertIn(consumer, refresh_args)
        self.assert_controller_cleanup_was_attempted(runner)

        quiesce_index = next(
            index
            for index, call in enumerate(runner.calls)
            if MODULE.CONTROLLER_PROCESS_FILE in call[0]
            and any("kill -TERM" in argument for argument in call[0])
        )
        cleanup_index = next(
            index
            for index, call in enumerate(runner.calls)
            if call[0][0:4] == ("exec", MODULE.CONTROLLER, "rm", "-f")
        )
        self.assertLess(quiesce_index, cleanup_index)

    def test_docker_runner_propagates_only_a_fixed_refresh_error_code(self):
        environment = {
            "HOME": "/trusted/home",
            "LOGNAME": "operator",
            "PATH": MODULE.DOCKER_SUBPROCESS_PATH,
            "USER": "operator",
        }
        runner = MODULE.DockerRunner("/trusted/docker", environment)
        with patch.object(MODULE.subprocess, "run") as run:
            run.return_value = SimpleNamespace(
                returncode=1,
                stderr=b"Higress policy refresh failed: SERVER_NOT_FOUND\n",
            )
            with self.assertRaisesRegex(MODULE.OperatorError, "SERVER_NOT_FOUND"):
                runner.run(["exec", MODULE.CONTROLLER, "false"])
            self.assertEqual(run.call_args.args[0][0], "/trusted/docker")
            self.assertEqual(run.call_args.kwargs["env"], environment)

            run.return_value = SimpleNamespace(
                returncode=1,
                stderr=b"unsafe diagnostic content",
            )
            with self.assertRaisesRegex(
                MODULE.OperatorError,
                "DOCKER_OPERATION_FAILED",
            ):
                runner.run(["exec", MODULE.CONTROLLER, "false"])

    def test_docker_runner_rejects_stderr_even_when_exit_code_is_zero(self):
        runner = MODULE.DockerRunner(
            "/trusted/docker",
            {
                "HOME": "/trusted/home",
                "LOGNAME": "operator",
                "PATH": MODULE.DOCKER_SUBPROCESS_PATH,
                "USER": "operator",
            },
        )
        with patch.object(MODULE.subprocess, "run") as run:
            run.return_value = SimpleNamespace(
                returncode=0,
                stderr=b"Higress policy refresh failed: UNEXPECTED_ERROR\n",
            )
            with self.assertRaisesRegex(MODULE.OperatorError, "UNEXPECTED_ERROR"):
                runner.run(["exec", MODULE.CONTROLLER, "refresh"])

            run.return_value = SimpleNamespace(
                returncode=0,
                stderr=b"unsafe diagnostic content",
            )
            with self.assertRaisesRegex(
                MODULE.OperatorError,
                "DOCKER_OPERATION_FAILED",
            ):
                runner.run(["exec", MODULE.CONTROLLER, "refresh"])

    def test_refresh_failure_contains_before_cleaning_controller_secrets_and_lock(self):
        refresh_attempts = 0

        def fail_first_refresh(args, _count):
            nonlocal refresh_attempts
            if MODULE.CONTROLLER_REFRESH_HELPER not in args or "python3" not in args:
                return False
            refresh_attempts += 1
            return refresh_attempts == 1

        runner = RecordingRunner(fail_when=fail_first_refresh)
        with self.assertRaisesRegex(MODULE.OperatorError, "SIMULATED_DOCKER_FAILURE"):
            MODULE.configure_routes(
                runner,
                self.contract,
                self.cookie,
                self.build_token,
                self.read_token,
            )
        refresh_calls = [
            call
            for call in runner.calls
            if MODULE.CONTROLLER_REFRESH_HELPER in call[0] and "python3" in call[0]
        ]
        self.assertEqual(len(refresh_calls), 2)
        self.assertIn("--enable-platform-routes", refresh_calls[0][0])
        self.assertIn("--contain-platform-routes", refresh_calls[1][0])
        self.assert_controller_cleanup_was_attempted(runner)

    def test_containment_streams_only_cookie_and_never_stages_route_tokens(self):
        runner = RecordingRunner()
        MODULE.configure_routes(
            runner,
            self.contract,
            self.cookie,
            None,
            None,
            contain_only=True,
        )

        streamed = [call[1] for call in runner.calls if call[1] is not None]
        self.assertEqual(streamed, [bytes(self.cookie)])
        refresh_call = next(
            call
            for call in runner.calls
            if MODULE.CONTROLLER_REFRESH_HELPER in call[0] and "python3" in call[0]
        )
        self.assertIn("--contain-platform-routes", refresh_call[0])
        self.assertNotIn("--enable-platform-routes", refresh_call[0])
        self.assertNotIn(MODULE.CONTROLLER_BUILD_TOKEN_FILE, refresh_call[0])
        self.assertNotIn(MODULE.CONTROLLER_READ_TOKEN_FILE, refresh_call[0])
        self.assert_controller_cleanup_was_attempted(runner)

    def test_cleanup_failure_overrides_success_and_fails_closed(self):
        runner = RecordingRunner(
            fail_when=lambda args, _count: len(args) >= 3
            and args[0:3] == ("exec", MODULE.CONTROLLER, "rmdir")
        )
        with self.assertRaisesRegex(MODULE.OperatorError, "CONTROLLER_CLEANUP_FAILED"):
            MODULE.configure_routes(
                runner,
                self.contract,
                self.cookie,
                self.build_token,
                self.read_token,
            )

    def test_process_fence_failure_preserves_controller_lock_and_temp_files(self):
        runner = RecordingRunner(
            fail_when=lambda args, _count: MODULE.CONTROLLER_PROCESS_FILE in args
            and any("kill -TERM" in argument for argument in args)
        )
        with self.assertRaisesRegex(
            MODULE.OperatorError,
            "CONTROLLER_PROCESS_FENCE_FAILED",
        ):
            MODULE.configure_routes(
                runner,
                self.contract,
                self.cookie,
                self.build_token,
                self.read_token,
            )

        self.assertFalse(
            any(
                call[0][0:4] == ("exec", MODULE.CONTROLLER, "rm", "-f")
                for call in runner.calls
            )
        )
        self.assertFalse(
            any(
                call[0][0:3] == ("exec", MODULE.CONTROLLER, "rmdir")
                for call in runner.calls
            )
        )

    def test_lock_failure_never_removes_another_operator_files(self):
        runner = RecordingRunner(fail_when=lambda _args, count: count == 1)
        with self.assertRaisesRegex(MODULE.OperatorError, "SIMULATED_DOCKER_FAILURE"):
            MODULE.configure_routes(
                runner,
                self.contract,
                self.cookie,
                self.build_token,
                self.read_token,
            )
        self.assertEqual(len(runner.calls), 1)

    def test_preexisting_controller_temp_file_is_never_removed(self):
        runner = RecordingRunner(
            fail_when=lambda args, _count: args[0:4]
            == ("exec", MODULE.CONTROLLER, "test", "!")
        )
        with self.assertRaisesRegex(
            MODULE.OperatorError,
            "CONTROLLER_TEMP_FILES_PRESENT",
        ):
            MODULE.configure_routes(
                runner,
                self.contract,
                self.cookie,
                self.build_token,
                self.read_token,
            )

        self.assertFalse(
            any(
                call[0][0:4] == ("exec", MODULE.CONTROLLER, "rm", "-f")
                for call in runner.calls
            )
        )
        self.assertTrue(
            any(
                call[0][0:3] == ("exec", MODULE.CONTROLLER, "rmdir")
                for call in runner.calls
            )
        )

    def assert_controller_cleanup_was_attempted(self, runner):
        removal_calls = [
            call
            for call in runner.calls
            if call[0][0:4] == ("exec", MODULE.CONTROLLER, "rm", "-f")
        ]
        self.assertEqual(len(removal_calls), 1)
        for temporary in MODULE.CONTROLLER_TEMP_FILES:
            self.assertIn(temporary, removal_calls[-1][0])
        self.assertTrue(
            any(
                call[0][0:3] == ("exec", MODULE.CONTROLLER, "rmdir")
                and MODULE.CONTROLLER_LOCK_DIR in call[0]
                for call in runner.calls
            )
        )


if __name__ == "__main__":
    unittest.main()
