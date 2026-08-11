#!/usr/bin/env python3

import argparse
import base64
import binascii
import json
import os
import pwd
import re
import signal
import stat
import subprocess
import sys
import time
import urllib.parse
import uuid
from dataclasses import dataclass, replace
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_REFRESH_HELPER = Path(__file__).with_name("refresh-higress-mcp-policy.py")
DEFAULT_POLICY = (
    REPOSITORY_ROOT
    / "examples/goai-agent-delivery/agentteams-v1.2.2/mcp-tool-policies.json"
)

CONTROLLER = "hiclaw-controller"
CONSOLE_URL = "http://127.0.0.1:8001"
CONTROLLER_LOCK_DIR = "/tmp/goai-platform-automation.lock"
CONTROLLER_PROCESS_FILE = f"{CONTROLLER_LOCK_DIR}/process.pid"
CONTROLLER_COOKIE_FILE = "/tmp/higress-session-cookie-gateway"
CONTROLLER_BUILD_TOKEN_FILE = "/tmp/goai-platform-project-build.jwt"
CONTROLLER_READ_TOKEN_FILE = "/tmp/goai-platform-project-read.jwt"
CONTROLLER_REFRESH_HELPER = "/tmp/refresh-higress-mcp-policy.py"
CONTROLLER_POLICY_FILE = "/tmp/goai-mcp-tool-policies.json"

PROJECT_BUILD_POLICY_NAME = "project-build"
PROJECT_READ_POLICY_NAME = "project-read"
PROJECT_BUILD_MCP_SERVER_NAME = "mcp-project-build"
PROJECT_READ_MCP_SERVER_NAME = "mcp-project-read"
PROJECT_BUILD_CONSUMERS = ("worker-nubase-builder",)
PROJECT_READ_CONSUMERS = (
    "worker-nubase-delivery-lead",
    "worker-nubase-verifier",
)
PROJECT_BUILD_SCOPES = frozenset(
    {"project:create", "project:provision", "project:status"}
)
PROJECT_READ_SCOPES = frozenset({"project:status"})

DEFAULT_ISSUER = "nubase-platform"
DEFAULT_AUDIENCE = "nubase-agentteams-provisioning"
DEFAULT_ALGORITHM = "HS256"
DEFAULT_KEY_ID = "platform-mcp-v1"
DEFAULT_MAX_TTL_SECONDS = 600
MIN_REMAINING_TTL_SECONDS = 60
MAX_CLOCK_SKEW_SECONDS = 30
CONTROLLER_REFRESH_TIMEOUT_SECONDS = 45
DOCKER_REFRESH_TIMEOUT_SECONDS = 60
MAX_COOKIE_BYTES = 128 * 1024
MAX_TOKEN_BYTES = 16 * 1024
MAX_SUPPORT_FILE_BYTES = 1024 * 1024
TRUSTED_DOCKER_CANDIDATES = (
    Path("/usr/local/bin/docker"),
    Path("/opt/homebrew/bin/docker"),
    Path("/usr/bin/docker"),
)
DOCKER_SUBPROCESS_PATH = "/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin"
SAFE_REFRESH_ERROR = re.compile(
    rb"Higress policy refresh failed: ([A-Z][A-Z0-9_]{0,127})\n?"
)

SAFE_CLAIM_VALUE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
SAFE_ACTOR = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:@/-]{0,127}$")
SAFE_SCOPE = re.compile(r"^[a-z][a-z0-9:-]{0,63}$")
SAFE_APPROVAL = re.compile(r"^[A-Za-z0-9._:-]{1,128}$")
ALLOWED_JWT_HEADERS = frozenset({"alg", "typ", "kid"})
ALLOWED_JWT_CLAIMS = frozenset(
    {
        "iss",
        "aud",
        "sub",
        "role",
        "actor_type",
        "scope",
        "grant_id",
        "token_version",
        "jti",
        "iat",
        "nbf",
        "exp",
        "approval_binding",
    }
)
REQUIRED_REFRESH_OPTIONS = (
    "--enable-platform-routes",
    "--contain-platform-routes",
    "--platform-endpoint",
    "--project-build-policy-name",
    "--project-read-policy-name",
    "--project-build-mcp-server-name",
    "--project-read-mcp-server-name",
    "--project-build-consumer",
    "--project-read-consumer",
    "--project-build-token-file",
    "--project-read-token-file",
)
CONTROLLER_TEMP_FILES = (
    CONTROLLER_COOKIE_FILE,
    CONTROLLER_BUILD_TOKEN_FILE,
    CONTROLLER_READ_TOKEN_FILE,
    CONTROLLER_REFRESH_HELPER,
    CONTROLLER_POLICY_FILE,
)


class OperatorError(Exception):
    def __init__(self, code):
        super().__init__(code)
        self.code = code


@dataclass(frozen=True)
class JwtContract:
    label: str
    scopes: frozenset[str]


@dataclass(frozen=True)
class JwtIdentity:
    actor: str
    grant_id: str
    token_version: int
    approval_binding: str


@dataclass(frozen=True)
class RuntimeContract:
    endpoint: str
    issuer: str
    audience: str
    algorithm: str
    max_ttl_seconds: int
    refresh_helper: Path
    policy: Path


class DockerRunner:
    def __init__(self, docker_binary, environment):
        docker_path = Path(docker_binary)
        require(docker_path.is_absolute(), "DOCKER_BINARY_INVALID")
        self.docker_binary = str(docker_path)
        self.environment = dict(environment)

    def run(self, args, *, input_bytes=None, timeout=30):
        try:
            completed = subprocess.run(
                [self.docker_binary, *args],
                input=input_bytes,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                check=False,
                timeout=timeout,
                env=self.environment,
            )
        except (OSError, subprocess.SubprocessError):
            raise OperatorError("DOCKER_OPERATION_FAILED") from None
        if completed.stderr:
            match = SAFE_REFRESH_ERROR.fullmatch(completed.stderr)
            if match is not None:
                raise OperatorError(match.group(1).decode("ascii"))
            raise OperatorError("DOCKER_OPERATION_FAILED")
        if completed.returncode != 0:
            raise OperatorError("DOCKER_OPERATION_FAILED")


def main(argv=None):
    secrets = []
    try:
        install_signal_handlers()
        args = parse_args(argv)
        contract = validate_runtime_contract(args)
        refresh_helper, policy = validate_supporting_files(
            contract.refresh_helper,
            contract.policy,
        )
        contract = replace(
            contract,
            refresh_helper=refresh_helper,
            policy=policy,
        )
        validate_mode_secret_sources(args)
        docker_runner = DockerRunner(
            resolve_trusted_docker_binary(),
            docker_subprocess_environment(),
        )

        cookie = read_secret_source(
            args.console_session_file,
            args.console_session_fd,
            "CONSOLE_SESSION",
            MAX_COOKIE_BYTES,
        )
        secrets.append(cookie)
        if args.contain_only:
            configure_routes(
                docker_runner,
                contract,
                cookie,
                None,
                None,
                contain_only=True,
            )
            print("Platform automation routes contained.")
            return 0
        build_token = read_secret_source(
            args.project_build_token_file,
            args.project_build_token_fd,
            "PROJECT_BUILD_TOKEN",
            MAX_TOKEN_BYTES,
        )
        read_token = read_secret_source(
            args.project_read_token_file,
            args.project_read_token_fd,
            "PROJECT_READ_TOKEN",
            MAX_TOKEN_BYTES,
        )
        secrets.extend((build_token, read_token))

        build_identity = validate_jwt(
            build_token,
            JwtContract("PROJECT_BUILD_TOKEN", PROJECT_BUILD_SCOPES),
            contract,
        )
        read_identity = validate_jwt(
            read_token,
            JwtContract("PROJECT_READ_TOKEN", PROJECT_READ_SCOPES),
            contract,
        )
        require(build_token != read_token, "PLATFORM_ROUTE_TOKENS_MUST_DIFFER")
        require(
            build_identity == read_identity,
            "PLATFORM_ROUTE_TOKEN_IDENTITY_MISMATCH",
        )

        configure_routes(docker_runner, contract, cookie, build_token, read_token)
        print("Platform automation route refresh completed.")
        return 0
    except OperatorError as error:
        print(
            f"Platform automation route refresh failed: {error.code}",
            file=sys.stderr,
        )
        return 1
    except Exception:
        print(
            "Platform automation route refresh failed: UNEXPECTED_ERROR",
            file=sys.stderr,
        )
        return 1
    finally:
        for secret in secrets:
            wipe(secret)


def parse_args(argv=None):
    parser = argparse.ArgumentParser(
        description=(
            "Stage short-lived platform automation grants in the controller and invoke "
            "the reviewed Higress refresh helper without exposing secret values."
        )
    )
    add_secret_source(parser, "console-session")
    add_secret_source(parser, "project-build-token", required=False)
    add_secret_source(parser, "project-read-token", required=False)
    parser.add_argument("--contain-only", action="store_true")
    parser.add_argument(
        "--platform-endpoint",
        default=os.environ.get("GOAI_PLATFORM_MCP_ENDPOINT"),
        required=os.environ.get("GOAI_PLATFORM_MCP_ENDPOINT") is None,
    )
    parser.add_argument(
        "--project-build-policy-name",
        default=os.environ.get(
            "GOAI_PROJECT_BUILD_POLICY_NAME",
            PROJECT_BUILD_POLICY_NAME,
        ),
    )
    parser.add_argument(
        "--project-read-policy-name",
        default=os.environ.get(
            "GOAI_PROJECT_READ_POLICY_NAME",
            PROJECT_READ_POLICY_NAME,
        ),
    )
    parser.add_argument(
        "--project-build-mcp-server-name",
        default=os.environ.get(
            "GOAI_PROJECT_BUILD_MCP_SERVER_NAME",
            PROJECT_BUILD_MCP_SERVER_NAME,
        ),
    )
    parser.add_argument(
        "--project-read-mcp-server-name",
        default=os.environ.get(
            "GOAI_PROJECT_READ_MCP_SERVER_NAME",
            PROJECT_READ_MCP_SERVER_NAME,
        ),
    )
    parser.add_argument(
        "--issuer",
        default=os.environ.get("GOAI_PLATFORM_JWT_ISSUER", DEFAULT_ISSUER),
    )
    parser.add_argument(
        "--audience",
        default=os.environ.get("GOAI_PLATFORM_JWT_AUDIENCE", DEFAULT_AUDIENCE),
    )
    parser.add_argument(
        "--algorithm",
        default=os.environ.get("GOAI_PLATFORM_JWT_ALGORITHM", DEFAULT_ALGORITHM),
    )
    parser.add_argument(
        "--max-ttl-seconds",
        type=int,
        default=int(
            os.environ.get(
                "GOAI_PLATFORM_JWT_MAX_TTL_SECONDS",
                str(DEFAULT_MAX_TTL_SECONDS),
            )
        ),
    )
    parser.add_argument("--refresh-helper", default=str(DEFAULT_REFRESH_HELPER))
    parser.add_argument("--policy", default=str(DEFAULT_POLICY))
    return parser.parse_args(argv)


def add_secret_source(parser, prefix, required=True):
    group = parser.add_mutually_exclusive_group(required=required)
    group.add_argument(f"--{prefix}-file")
    group.add_argument(f"--{prefix}-fd", type=int)


def validate_mode_secret_sources(args):
    build_sources = (
        args.project_build_token_file,
        args.project_build_token_fd,
    )
    read_sources = (
        args.project_read_token_file,
        args.project_read_token_fd,
    )
    if args.contain_only:
        require(not any(value is not None for value in build_sources), "BUILD_TOKEN_NOT_ALLOWED")
        require(not any(value is not None for value in read_sources), "READ_TOKEN_NOT_ALLOWED")
        return
    require(
        sum(value is not None for value in build_sources) == 1,
        "PROJECT_BUILD_TOKEN_SOURCE_REQUIRED",
    )
    require(
        sum(value is not None for value in read_sources) == 1,
        "PROJECT_READ_TOKEN_SOURCE_REQUIRED",
    )


def validate_runtime_contract(args):
    require(
        args.project_build_policy_name == PROJECT_BUILD_POLICY_NAME,
        "PROJECT_BUILD_POLICY_NAME_INVALID",
    )
    require(
        args.project_read_policy_name == PROJECT_READ_POLICY_NAME,
        "PROJECT_READ_POLICY_NAME_INVALID",
    )
    require(
        args.project_build_mcp_server_name == PROJECT_BUILD_MCP_SERVER_NAME,
        "PROJECT_BUILD_MCP_SERVER_NAME_INVALID",
    )
    require(
        args.project_read_mcp_server_name == PROJECT_READ_MCP_SERVER_NAME,
        "PROJECT_READ_MCP_SERVER_NAME_INVALID",
    )
    require(
        args.issuer == DEFAULT_ISSUER,
        "PLATFORM_JWT_ISSUER_INVALID",
    )
    require(
        args.audience == DEFAULT_AUDIENCE,
        "PLATFORM_JWT_AUDIENCE_INVALID",
    )
    require(
        isinstance(args.algorithm, str)
        and args.algorithm == DEFAULT_ALGORITHM,
        "PLATFORM_JWT_ALGORITHM_INVALID",
    )
    require(
        MIN_REMAINING_TTL_SECONDS
        <= args.max_ttl_seconds
        <= DEFAULT_MAX_TTL_SECONDS,
        "PLATFORM_JWT_MAX_TTL_INVALID",
    )
    validate_platform_endpoint(args.platform_endpoint)
    return RuntimeContract(
        endpoint=args.platform_endpoint,
        issuer=args.issuer,
        audience=args.audience,
        algorithm=args.algorithm,
        max_ttl_seconds=args.max_ttl_seconds,
        refresh_helper=Path(args.refresh_helper),
        policy=Path(args.policy),
    )


def validate_platform_endpoint(endpoint):
    require(isinstance(endpoint, str) and endpoint, "PLATFORM_ENDPOINT_REQUIRED")
    try:
        parsed = urllib.parse.urlsplit(endpoint)
        port = parsed.port
    except ValueError:
        raise OperatorError("PLATFORM_ENDPOINT_INVALID") from None
    require(parsed.scheme == "http", "PLATFORM_ENDPOINT_SCHEME_INVALID")
    require(parsed.hostname == "host.docker.internal", "PLATFORM_ENDPOINT_HOST_INVALID")
    require(port == 9999, "PLATFORM_ENDPOINT_PORT_INVALID")
    require(parsed.username is None and parsed.password is None, "PLATFORM_ENDPOINT_AUTH_INVALID")
    require(not parsed.query and not parsed.fragment, "PLATFORM_ENDPOINT_SUFFIX_INVALID")
    require(parsed.path == "/platform/mcp", "PLATFORM_ENDPOINT_PATH_INVALID")


def validate_supporting_files(refresh_helper, policy, repository_root=REPOSITORY_ROOT):
    helper = validate_support_file(refresh_helper, repository_root, "REFRESH_HELPER")
    policy_file = validate_support_file(policy, repository_root, "POLICY")
    try:
        helper_source = helper.read_text(encoding="utf-8")
    except (OSError, UnicodeDecodeError):
        raise OperatorError("REFRESH_HELPER_UNAVAILABLE") from None
    for option in REQUIRED_REFRESH_OPTIONS:
        require(option in helper_source, "REFRESH_HELPER_PLATFORM_CONTRACT_MISSING")
    return helper, policy_file


def validate_support_file(path, repository_root, label):
    candidate = Path(path)
    try:
        metadata = candidate.lstat()
        resolved = candidate.resolve(strict=True)
        repository = Path(repository_root).resolve(strict=True)
    except OSError:
        raise OperatorError(f"{label}_UNAVAILABLE") from None
    require(not stat.S_ISLNK(metadata.st_mode), f"{label}_SYMLINK_REJECTED")
    require(stat.S_ISREG(metadata.st_mode), f"{label}_FILE_REQUIRED")
    require(metadata.st_uid == os.geteuid(), f"{label}_OWNER_INVALID")
    require(metadata.st_mode & 0o022 == 0, f"{label}_PERMISSIONS_INVALID")
    require(0 < metadata.st_size <= MAX_SUPPORT_FILE_BYTES, f"{label}_SIZE_INVALID")
    try:
        resolved.relative_to(repository)
    except ValueError:
        raise OperatorError(f"{label}_OUTSIDE_REPOSITORY") from None
    return resolved


def resolve_trusted_docker_binary(candidates=TRUSTED_DOCKER_CANDIDATES):
    trusted_owners = {0, os.geteuid()}
    for raw_candidate in candidates:
        candidate = Path(raw_candidate)
        if not candidate.is_absolute():
            continue
        try:
            entry_metadata = candidate.lstat()
            parent_metadata = candidate.parent.stat()
            resolved = candidate.resolve(strict=True)
            target_metadata = resolved.stat()
        except OSError:
            continue
        if not (stat.S_ISREG(entry_metadata.st_mode) or stat.S_ISLNK(entry_metadata.st_mode)):
            continue
        if entry_metadata.st_uid not in trusted_owners:
            continue
        if stat.S_ISREG(entry_metadata.st_mode) and entry_metadata.st_mode & 0o022:
            continue
        if not stat.S_ISDIR(parent_metadata.st_mode):
            continue
        if parent_metadata.st_uid not in trusted_owners or parent_metadata.st_mode & 0o022:
            continue
        if not stat.S_ISREG(target_metadata.st_mode):
            continue
        if target_metadata.st_uid not in trusted_owners or target_metadata.st_mode & 0o022:
            continue
        if target_metadata.st_mode & 0o111 == 0 or not os.access(candidate, os.X_OK):
            continue
        return str(candidate)
    raise OperatorError("DOCKER_BINARY_UNAVAILABLE")


def docker_subprocess_environment():
    try:
        account = pwd.getpwuid(os.geteuid())
        home = Path(account.pw_dir)
        metadata = home.stat()
    except (KeyError, OSError):
        raise OperatorError("DOCKER_ENVIRONMENT_INVALID") from None
    require(home.is_absolute(), "DOCKER_ENVIRONMENT_INVALID")
    require(stat.S_ISDIR(metadata.st_mode), "DOCKER_ENVIRONMENT_INVALID")
    require(metadata.st_uid == os.geteuid(), "DOCKER_ENVIRONMENT_INVALID")
    return {
        "HOME": str(home),
        "LOGNAME": account.pw_name,
        "PATH": DOCKER_SUBPROCESS_PATH,
        "USER": account.pw_name,
    }


def read_secret_source(file_path, fd_number, label, max_bytes):
    if (file_path is None) == (fd_number is None):
        raise OperatorError(f"{label}_SOURCE_INVALID")
    descriptor = None
    try:
        if file_path is not None:
            try:
                path_metadata = os.lstat(file_path)
            except OSError:
                raise OperatorError(f"{label}_UNAVAILABLE") from None
            require(not stat.S_ISLNK(path_metadata.st_mode), f"{label}_SYMLINK_REJECTED")
            flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
            descriptor = os.open(file_path, flags)
        else:
            require(fd_number is not None and fd_number >= 3, f"{label}_FD_INVALID")
            descriptor = os.dup(fd_number)
        metadata = os.fstat(descriptor)
        require(stat.S_ISREG(metadata.st_mode), f"{label}_FILE_REQUIRED")
        require(metadata.st_uid == os.geteuid(), f"{label}_OWNER_INVALID")
        require(stat.S_IMODE(metadata.st_mode) == 0o600, f"{label}_PERMISSIONS_INVALID")
        data = bytearray()
        while len(data) <= max_bytes:
            chunk = os.read(descriptor, min(8192, max_bytes + 1 - len(data)))
            if not chunk:
                break
            data.extend(chunk)
        require(data, f"{label}_EMPTY")
        require(len(data) <= max_bytes, f"{label}_TOO_LARGE")
        return data
    except OperatorError:
        raise
    except OSError:
        raise OperatorError(f"{label}_UNAVAILABLE") from None
    finally:
        if descriptor is not None:
            try:
                os.close(descriptor)
            except OSError:
                pass


def validate_jwt(token_bytes, token_contract, runtime_contract, now=None):
    try:
        token = bytes(token_bytes).decode("ascii")
    except UnicodeDecodeError:
        raise OperatorError(f"{token_contract.label}_JWT_ENCODING_INVALID") from None
    require(token == token.strip(), f"{token_contract.label}_JWT_WHITESPACE_INVALID")
    require(not re.search(r"\s", token), f"{token_contract.label}_JWT_WHITESPACE_INVALID")
    parts = token.split(".")
    require(len(parts) == 3 and all(parts), f"{token_contract.label}_JWT_SHAPE_INVALID")
    require(
        re.fullmatch(r"[A-Za-z0-9_-]+", parts[2]) is not None,
        f"{token_contract.label}_JWT_SIGNATURE_ENCODING_INVALID",
    )
    header = decode_json_segment(parts[0], token_contract.label, "HEADER")
    payload = decode_json_segment(parts[1], token_contract.label, "PAYLOAD")
    require(
        header.get("alg") == runtime_contract.algorithm,
        f"{token_contract.label}_JWT_ALGORITHM_INVALID",
    )
    require(header.get("typ") == "JWT", f"{token_contract.label}_JWT_TYPE_INVALID")
    require(set(header) == ALLOWED_JWT_HEADERS, f"{token_contract.label}_JWT_HEADER_INVALID")
    require(header.get("kid") == DEFAULT_KEY_ID, f"{token_contract.label}_JWT_KID_INVALID")

    require(payload.get("iss") == runtime_contract.issuer, f"{token_contract.label}_JWT_ISSUER_INVALID")
    require(
        exact_audience(payload.get("aud"), runtime_contract.audience),
        f"{token_contract.label}_JWT_AUDIENCE_INVALID",
    )
    require(
        set(payload).issubset(ALLOWED_JWT_CLAIMS),
        f"{token_contract.label}_JWT_CLAIMS_INVALID",
    )
    require(payload.get("role") == "platform_automation", f"{token_contract.label}_JWT_ROLE_INVALID")
    require(payload.get("actor_type") == "automation", f"{token_contract.label}_JWT_ACTOR_INVALID")
    require(
        isinstance(payload.get("sub"), str)
        and SAFE_ACTOR.fullmatch(payload["sub"]) is not None,
        f"{token_contract.label}_JWT_SUB_INVALID",
    )
    require(safe_claim(payload.get("jti")), f"{token_contract.label}_JWT_JTI_INVALID")
    require(valid_uuid(payload.get("grant_id")), f"{token_contract.label}_JWT_GRANT_ID_INVALID")
    require(
        isinstance(payload.get("token_version"), int)
        and not isinstance(payload.get("token_version"), bool)
        and payload["token_version"] >= 0,
        f"{token_contract.label}_JWT_TOKEN_VERSION_INVALID",
    )
    require(
        "platform_role" not in payload and payload.get("role") != "super_admin",
        f"{token_contract.label}_JWT_PRIVILEGE_INVALID",
    )
    require(
        isinstance(payload.get("approval_binding"), str)
        and SAFE_APPROVAL.fullmatch(payload["approval_binding"]) is not None,
        f"{token_contract.label}_JWT_APPROVAL_BINDING_INVALID",
    )

    scopes = parse_scopes(payload.get("scope"), token_contract.label)
    require(scopes == token_contract.scopes, f"{token_contract.label}_JWT_SCOPE_INVALID")

    issued_at = integer_claim(payload, "iat", token_contract.label)
    not_before = integer_claim(payload, "nbf", token_contract.label)
    expires_at = integer_claim(payload, "exp", token_contract.label)
    current_time = int(time.time()) if now is None else int(now)
    require(issued_at <= current_time + MAX_CLOCK_SKEW_SECONDS, f"{token_contract.label}_JWT_IAT_INVALID")
    require(not_before >= issued_at - MAX_CLOCK_SKEW_SECONDS, f"{token_contract.label}_JWT_NBF_INVALID")
    require(not_before <= current_time + MAX_CLOCK_SKEW_SECONDS, f"{token_contract.label}_JWT_NOT_ACTIVE")
    require(expires_at > not_before, f"{token_contract.label}_JWT_EXPIRY_INVALID")
    require(
        expires_at - issued_at <= runtime_contract.max_ttl_seconds,
        f"{token_contract.label}_JWT_TTL_TOO_LONG",
    )
    require(
        expires_at - current_time >= MIN_REMAINING_TTL_SECONDS,
        f"{token_contract.label}_JWT_TTL_TOO_SHORT",
    )
    return JwtIdentity(
        payload["sub"],
        payload["grant_id"],
        payload["token_version"],
        payload["approval_binding"],
    )


def decode_json_segment(segment, label, section):
    require(
        re.fullmatch(r"[A-Za-z0-9_-]+", segment) is not None,
        f"{label}_JWT_{section}_ENCODING_INVALID",
    )
    padded = segment + "=" * (-len(segment) % 4)
    try:
        raw = base64.b64decode(padded, altchars=b"-_", validate=True)
        parsed = json.loads(raw, object_pairs_hook=reject_duplicate_json_keys)
    except OperatorError:
        raise
    except (binascii.Error, UnicodeDecodeError, json.JSONDecodeError):
        raise OperatorError(f"{label}_JWT_{section}_INVALID") from None
    require(isinstance(parsed, dict), f"{label}_JWT_{section}_INVALID")
    return parsed


def reject_duplicate_json_keys(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise OperatorError("JWT_JSON_DUPLICATE_KEY")
        result[key] = value
    return result


def parse_scopes(value, label):
    require(isinstance(value, str) and value, f"{label}_JWT_SCOPE_INVALID")
    parts = value.split(" ")
    require(all(SAFE_SCOPE.fullmatch(scope) for scope in parts), f"{label}_JWT_SCOPE_INVALID")
    require(len(parts) == len(set(parts)), f"{label}_JWT_SCOPE_INVALID")
    return frozenset(parts)


def integer_claim(payload, name, label):
    value = payload.get(name)
    require(
        isinstance(value, int) and not isinstance(value, bool),
        f"{label}_JWT_{name.upper()}_INVALID",
    )
    return value


def safe_claim(value):
    return isinstance(value, str) and SAFE_CLAIM_VALUE.fullmatch(value) is not None


def exact_audience(value, expected):
    return value == expected or value == [expected]


def valid_uuid(value):
    if not isinstance(value, str):
        return False
    try:
        return str(uuid.UUID(value)) == value.lower()
    except ValueError:
        return False


def configure_routes(
    runner,
    contract,
    cookie,
    build_token,
    read_token,
    contain_only=False,
):
    lock_acquired = False
    owns_temp_files = False
    refresh_attempted = False
    primary_error = None
    try:
        runner.run(
            [
                "exec",
                CONTROLLER,
                "sh",
                "-eu",
                "-c",
                'umask 077; mkdir "$1"',
                "sh",
                CONTROLLER_LOCK_DIR,
            ]
        )
        lock_acquired = True
        require_controller_temp_files_absent(runner)
        owns_temp_files = True
        stream_secret(runner, CONTROLLER_COOKIE_FILE, cookie)
        if not contain_only:
            stream_secret(runner, CONTROLLER_BUILD_TOKEN_FILE, build_token)
            stream_secret(runner, CONTROLLER_READ_TOKEN_FILE, read_token)
        stage_support_file(runner, contract.refresh_helper, CONTROLLER_REFRESH_HELPER, "500")
        stage_support_file(runner, contract.policy, CONTROLLER_POLICY_FILE, "400")
        refresh_attempted = True
        invoke_refresh_helper(runner, contract, contain_only)
    except OperatorError as error:
        primary_error = error
        if refresh_attempted and not contain_only:
            try:
                invoke_refresh_helper(runner, contract, contain_only=True)
            except OperatorError:
                primary_error = OperatorError("PLATFORM_CONTAINMENT_FAILED")
    finally:
        cleanup_error = None
        if lock_acquired:
            process_quiesced = False
            try:
                quiesce_controller_process(runner)
                process_quiesced = True
            except OperatorError:
                cleanup_error = OperatorError("CONTROLLER_PROCESS_FENCE_FAILED")
            if owns_temp_files and process_quiesced:
                try:
                    remove_controller_temp_files(runner)
                except OperatorError:
                    cleanup_error = OperatorError("CONTROLLER_CLEANUP_FAILED")
            if process_quiesced:
                try:
                    runner.run(["exec", CONTROLLER, "rmdir", "--", CONTROLLER_LOCK_DIR])
                except OperatorError:
                    cleanup_error = OperatorError("CONTROLLER_CLEANUP_FAILED")
        if cleanup_error is not None:
            raise cleanup_error
    if primary_error is not None:
        raise primary_error


def require_controller_temp_files_absent(runner):
    try:
        for temporary in CONTROLLER_TEMP_FILES:
            runner.run(["exec", CONTROLLER, "test", "!", "-e", temporary])
    except OperatorError:
        raise OperatorError("CONTROLLER_TEMP_FILES_PRESENT") from None


def remove_controller_temp_files(runner):
    runner.run(["exec", CONTROLLER, "rm", "-f", "--", *CONTROLLER_TEMP_FILES])


def stream_secret(runner, destination, secret):
    script = """
pid_file=$1
destination=$2
temporary="${destination}.tmp.$$"
cleanup() { rm -f -- "${temporary}" "${pid_file}"; }
trap cleanup EXIT HUP INT TERM
umask 077
test ! -e "${pid_file}"
printf '%s\n' "$$" > "${pid_file}"
chmod 600 "${pid_file}"
cat > "${temporary}"
test -s "${temporary}"
chmod 600 "${temporary}"
mv -f -- "${temporary}" "${destination}"
trap - EXIT HUP INT TERM
rm -f -- "${pid_file}"
""".strip()
    runner.run(
        [
            "exec",
            "-i",
            CONTROLLER,
            "setsid",
            "sh",
            "-eu",
            "-c",
            script,
            "sh",
            CONTROLLER_PROCESS_FILE,
            destination,
        ],
        input_bytes=secret,
    )
    runner.run(
        [
            "exec",
            CONTROLLER,
            "sh",
            "-eu",
            "-c",
            'test -f "$1"; test ! -L "$1"; test "$(stat -c %a "$1")" = 600',
            "sh",
            destination,
        ]
    )


def stage_support_file(runner, source, destination, mode):
    runner.run(["cp", str(source), f"{CONTROLLER}:{destination}"])
    runner.run(["exec", CONTROLLER, "chmod", mode, destination])


def invoke_refresh_helper(runner, contract, contain_only=False):
    fenced_command = """
pid_file=$1
shift
cleanup() { rm -f -- "${pid_file}"; }
trap cleanup EXIT HUP INT TERM
umask 077
test ! -e "${pid_file}"
printf '%s\n' "$$" > "${pid_file}"
chmod 600 "${pid_file}"
"$@"
""".strip()
    args = [
        "exec",
        CONTROLLER,
        "setsid",
        "sh",
        "-eu",
        "-c",
        fenced_command,
        "sh",
        CONTROLLER_PROCESS_FILE,
        "env",
        "PYTHONDONTWRITEBYTECODE=1",
        "timeout",
        "-s",
        "KILL",
        str(CONTROLLER_REFRESH_TIMEOUT_SECONDS),
        "python3",
        CONTROLLER_REFRESH_HELPER,
        "--policy",
        CONTROLLER_POLICY_FILE,
        "--console-url",
        CONSOLE_URL,
        "--cookie-file",
        CONTROLLER_COOKIE_FILE,
        "--platform-endpoint",
        contract.endpoint,
        "--project-build-policy-name",
        PROJECT_BUILD_POLICY_NAME,
        "--project-read-policy-name",
        PROJECT_READ_POLICY_NAME,
        "--project-build-mcp-server-name",
        PROJECT_BUILD_MCP_SERVER_NAME,
        "--project-read-mcp-server-name",
        PROJECT_READ_MCP_SERVER_NAME,
    ]
    for consumer in PROJECT_BUILD_CONSUMERS:
        args.extend(("--project-build-consumer", consumer))
    for consumer in PROJECT_READ_CONSUMERS:
        args.extend(("--project-read-consumer", consumer))
    if contain_only:
        args.append("--contain-platform-routes")
    else:
        args.extend(
            (
                "--enable-platform-routes",
                "--project-build-token-file",
                CONTROLLER_BUILD_TOKEN_FILE,
                "--project-read-token-file",
                CONTROLLER_READ_TOKEN_FILE,
            )
        )
    runner.run(args, timeout=DOCKER_REFRESH_TIMEOUT_SECONDS)


def quiesce_controller_process(runner):
    script = r"""
pid_file=$1
test ! -L "${pid_file}"
if test ! -e "${pid_file}"; then
  exit 0
fi
test -f "${pid_file}"
test "$(stat -c %a "${pid_file}")" = 600
pid=$(cat "${pid_file}")
case "${pid}" in
  ''|*[!0-9]*) exit 1 ;;
esac
test "${pid}" -gt 1
if kill -0 "${pid}" 2>/dev/null; then
  tr '\000' '\n' < "/proc/${pid}/cmdline" | grep -Fqx -- "${pid_file}"
  kill -TERM -- "-${pid}" 2>/dev/null || true
  attempts=0
  while kill -0 "${pid}" 2>/dev/null && test "${attempts}" -lt 50; do
    sleep 0.1
    attempts=$((attempts + 1))
  done
  if kill -0 "${pid}" 2>/dev/null; then
    kill -KILL -- "-${pid}" 2>/dev/null || true
    attempts=0
    while kill -0 "${pid}" 2>/dev/null && test "${attempts}" -lt 20; do
      sleep 0.1
      attempts=$((attempts + 1))
    done
  fi
  test ! -e "/proc/${pid}"
fi
rm -f -- "${pid_file}"
""".strip()
    runner.run(
        [
            "exec",
            CONTROLLER,
            "sh",
            "-eu",
            "-c",
            script,
            "sh",
            CONTROLLER_PROCESS_FILE,
        ],
        timeout=10,
    )


def install_signal_handlers():
    def interrupt(_signal_number, _frame):
        raise OperatorError("INTERRUPTED")

    for name in ("SIGHUP", "SIGINT", "SIGTERM"):
        signal_number = getattr(signal, name, None)
        if signal_number is not None:
            signal.signal(signal_number, interrupt)


def wipe(value):
    for index in range(len(value)):
        value[index] = 0


def require(condition, code):
    if not condition:
        raise OperatorError(code)


if __name__ == "__main__":
    raise SystemExit(main())
