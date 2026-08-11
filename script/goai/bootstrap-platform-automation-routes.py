#!/usr/bin/env python3

from __future__ import annotations

import argparse
import json
import os
import re
import signal
import stat
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import uuid
from dataclasses import dataclass
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
CONFIGURE_HELPER = Path(__file__).with_name("configure-platform-automation-routes.py")
ADMIN_BASE_URL = "http://127.0.0.1:9999"
ADMIN_GRANTS_PATH = "/auth/v1/admin/platform/automation-grants"
PLATFORM_ENDPOINT = "http://host.docker.internal:9999/platform/mcp"
MAX_ROOT_TOKEN_BYTES = 16 * 1024
MAX_ADMIN_RESPONSE_BYTES = 64 * 1024
MAX_STATE_BYTES = 16 * 1024
CONFIGURE_TIMEOUT_SECONDS = 180
CONFIGURE_TERMINATION_GRACE_SECONDS = 90
CONFIGURE_KILL_GRACE_SECONDS = 10
SAFE_PROCESS_PATH = "/usr/local/bin:/usr/bin:/bin:/opt/homebrew/bin"
MIN_GRANT_TTL_SECONDS = 600
MAX_GRANT_TTL_SECONDS = 7 * 24 * 60 * 60
MIN_TOKEN_TTL_SECONDS = 60
MAX_TOKEN_TTL_SECONDS = 600
SAFE_ACTOR_PREFIX = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:@/-]{0,80}$")
SAFE_ACTOR = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:@/-]{0,127}$")
SAFE_REF_PREFIX = re.compile(r"^[a-z][a-z0-9_]{0,39}$")
SAFE_APPROVAL = re.compile(r"^[A-Za-z0-9._:-]{1,128}$")
BUILD_SCOPES = frozenset(
    {"project:create", "project:provision", "project:status"}
)
READ_SCOPES = frozenset({"project:status"})
TOKEN_RESULT_KEYS = frozenset(
    {
        "grantId",
        "actor",
        "allowedRefPrefix",
        "maxProjects",
        "scopes",
        "expiresAt",
        "token",
    }
)
STATE_KEYS = frozenset(
    {
        "version",
        "status",
        "grantId",
        "actor",
        "allowedRefPrefix",
        "maxProjects",
        "approvalBinding",
        "grantTtlSeconds",
        "createdAtEpochSeconds",
    }
)


class OperatorError(Exception):
    def __init__(self, code):
        super().__init__(code)
        self.code = code


@dataclass(frozen=True)
class TokenResult:
    grant_id: str
    actor: str
    allowed_ref_prefix: str
    max_projects: int
    scopes: frozenset[str]
    token: bytearray


@dataclass(frozen=True)
class GrantState:
    status: str
    grant_id: str | None
    actor: str
    allowed_ref_prefix: str
    max_projects: int
    approval_binding: str | None
    grant_ttl_seconds: int
    created_at_epoch_seconds: int


class NoRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, file_pointer, code, message, headers, new_url):
        del request, file_pointer, code, message, headers, new_url
        return None


class AdminClient:
    def __init__(self, root_token, opener=None):
        self.root_token = root_token
        self.opener = opener or urllib.request.build_opener(
            urllib.request.ProxyHandler({}),
            NoRedirectHandler(),
        )

    def create_grant(self, request_body):
        response = self._request_json("POST", ADMIN_GRANTS_PATH, request_body, 201)
        return validate_token_result(
            response,
            request_body["actor"],
            request_body["allowedRefPrefix"],
            request_body["maxProjects"],
            BUILD_SCOPES,
        )

    def mint_token(self, grant_id, scope, token_ttl_seconds, state):
        require(valid_uuid(grant_id), "GRANT_ID_INVALID")
        require(scope in ("build", "read"), "TOKEN_SCOPE_PROFILE_INVALID")
        response = self._request_json(
            "POST",
            f"{ADMIN_GRANTS_PATH}/{grant_id}/tokens",
            {"scope": scope, "tokenTtlSeconds": token_ttl_seconds},
            200,
        )
        expected_scopes = BUILD_SCOPES if scope == "build" else READ_SCOPES
        return validate_token_result(
            response,
            state.actor,
            state.allowed_ref_prefix,
            state.max_projects,
            expected_scopes,
            expected_grant_id=grant_id,
        )

    def revoke(self, grant_id):
        require(valid_uuid(grant_id), "GRANT_ID_INVALID")
        self._request_json(
            "DELETE",
            f"{ADMIN_GRANTS_PATH}/{grant_id}",
            None,
            204,
        )

    def _request_json(self, method, path, body, expected_status):
        require(path.startswith(ADMIN_GRANTS_PATH), "ADMIN_PATH_INVALID")
        data = None
        if body is not None:
            data = json.dumps(body, separators=(",", ":")).encode("utf-8")
        request = urllib.request.Request(
            ADMIN_BASE_URL + path,
            data=data,
            method=method,
        )
        try:
            root_text = bytes(self.root_token).decode("ascii")
        except UnicodeDecodeError:
            raise OperatorError("METADATA_ROOT_ENCODING_INVALID") from None
        request.add_header("Authorization", f"Bearer {root_text}")
        if data is not None:
            request.add_header("Content-Type", "application/json")
        try:
            with self.opener.open(request, timeout=15) as response:
                status_code = response.status
                payload = response.read(MAX_ADMIN_RESPONSE_BYTES + 1)
        except urllib.error.HTTPError as error:
            raise OperatorError(f"ADMIN_HTTP_{error.code}") from None
        except (OSError, TimeoutError, urllib.error.URLError):
            raise OperatorError("ADMIN_REQUEST_FAILED") from None
        require(status_code == expected_status, "ADMIN_STATUS_INVALID")
        require(len(payload) <= MAX_ADMIN_RESPONSE_BYTES, "ADMIN_RESPONSE_TOO_LARGE")
        if expected_status == 204:
            require(not payload, "ADMIN_RESPONSE_UNEXPECTED_BODY")
            return None
        try:
            parsed = json.loads(payload, object_pairs_hook=reject_duplicate_json_keys)
        except OperatorError:
            raise
        except (UnicodeDecodeError, json.JSONDecodeError):
            raise OperatorError("ADMIN_RESPONSE_INVALID") from None
        require(isinstance(parsed, dict), "ADMIN_RESPONSE_INVALID")
        return parsed


def main(argv=None):
    root_token = None
    try:
        install_signal_handlers()
        args = parse_args(argv)
        validate_arguments(args)
        validate_helper(CONFIGURE_HELPER)
        validate_secret_sources_are_distinct(args)
        root_token = read_secret_source(
            args.metadata_root_file,
            args.metadata_root_fd,
            "METADATA_ROOT",
            MAX_ROOT_TOKEN_BYTES,
        )
        validate_root_token(root_token)
        client = AdminClient(root_token)
        if args.command == "bootstrap":
            bootstrap(client, args)
            print("Platform automation bootstrap completed.")
        elif args.command == "rotate":
            rotate(client, args)
            print("Platform automation token rotation completed.")
        else:
            revoke(client, args)
            print("Platform automation grant revoked.")
        return 0
    except OperatorError as error:
        print(f"Platform automation bootstrap failed: {error.code}", file=sys.stderr)
        return 1
    except Exception:
        print("Platform automation bootstrap failed: UNEXPECTED_ERROR", file=sys.stderr)
        return 1
    finally:
        if root_token is not None:
            wipe(root_token)


def parse_args(argv=None):
    parser = argparse.ArgumentParser(
        description=(
            "Create, rotate, or revoke a local Nubase platform automation grant "
            "without placing root or route tokens in argv, environment variables, or logs."
        )
    )
    parser.add_argument("command", choices=("bootstrap", "rotate", "revoke"))
    add_secret_source(parser, "metadata-root")
    console_group = parser.add_mutually_exclusive_group()
    console_group.add_argument("--console-session-file")
    console_group.add_argument("--console-session-fd", type=int)
    parser.add_argument("--grant-state-file", required=True)
    parser.add_argument("--actor-prefix", default="agentteams-local")
    parser.add_argument("--allowed-ref-prefix")
    parser.add_argument("--max-projects", type=int, default=1)
    parser.add_argument("--approval-binding")
    parser.add_argument("--grant-ttl-seconds", type=int, default=3600)
    parser.add_argument("--token-ttl-seconds", type=int, default=600)
    parser.add_argument("--admin-base-url", default=ADMIN_BASE_URL)
    parser.add_argument("--platform-endpoint", default=PLATFORM_ENDPOINT)
    return parser.parse_args(argv)


def add_secret_source(parser, prefix):
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument(f"--{prefix}-file")
    group.add_argument(f"--{prefix}-fd", type=int)


def validate_arguments(args):
    require(args.admin_base_url == ADMIN_BASE_URL, "ADMIN_BASE_URL_INVALID")
    require(args.platform_endpoint == PLATFORM_ENDPOINT, "PLATFORM_ENDPOINT_INVALID")
    require(
        MIN_TOKEN_TTL_SECONDS <= args.token_ttl_seconds <= MAX_TOKEN_TTL_SECONDS,
        "TOKEN_TTL_INVALID",
    )
    state_path = Path(args.grant_state_file)
    require(state_path.is_absolute(), "GRANT_STATE_PATH_NOT_ABSOLUTE")
    validate_state_parent(state_path)
    if args.command == "bootstrap":
        require(
            (args.console_session_file is None) != (args.console_session_fd is None),
            "CONSOLE_SESSION_SOURCE_REQUIRED",
        )
        require(args.allowed_ref_prefix is not None, "ALLOWED_REF_PREFIX_REQUIRED")
        require(
            SAFE_ACTOR_PREFIX.fullmatch(args.actor_prefix) is not None,
            "ACTOR_PREFIX_INVALID",
        )
        require(
            SAFE_REF_PREFIX.fullmatch(args.allowed_ref_prefix) is not None
            and args.allowed_ref_prefix.endswith("_"),
            "ALLOWED_REF_PREFIX_INVALID",
        )
        require(1 <= args.max_projects <= 100, "MAX_PROJECTS_INVALID")
        require(args.approval_binding is not None, "APPROVAL_BINDING_REQUIRED")
        require(
            SAFE_APPROVAL.fullmatch(args.approval_binding) is not None,
            "APPROVAL_BINDING_INVALID",
        )
        require(
            MIN_GRANT_TTL_SECONDS
            <= args.grant_ttl_seconds
            <= MAX_GRANT_TTL_SECONDS,
            "GRANT_TTL_INVALID",
        )
        require(not state_path.exists(), "GRANT_STATE_ALREADY_EXISTS")
    elif args.command == "rotate":
        require(
            (args.console_session_file is None) != (args.console_session_fd is None),
            "CONSOLE_SESSION_SOURCE_REQUIRED",
        )
        require(state_path.exists(), "GRANT_STATE_UNAVAILABLE")
    else:
        require(
            (args.console_session_file is None) != (args.console_session_fd is None),
            "CONSOLE_SESSION_SOURCE_REQUIRED",
        )
        require(state_path.exists(), "GRANT_STATE_UNAVAILABLE")


def bootstrap(client, args):
    actor = f"{args.actor_prefix}/{uuid.uuid4()}"
    require(SAFE_ACTOR.fullmatch(actor) is not None, "ACTOR_INVALID")
    request_body = {
        "actor": actor,
        "scope": "build",
        "allowedRefPrefix": args.allowed_ref_prefix,
        "maxProjects": args.max_projects,
        "approvalBinding": args.approval_binding,
        "grantTtlSeconds": args.grant_ttl_seconds,
        "tokenTtlSeconds": args.token_ttl_seconds,
    }
    pending_state = GrantState(
        status="pending",
        grant_id=None,
        actor=actor,
        allowed_ref_prefix=args.allowed_ref_prefix,
        max_projects=args.max_projects,
        approval_binding=args.approval_binding,
        grant_ttl_seconds=args.grant_ttl_seconds,
        created_at_epoch_seconds=int(time.time()),
    )
    state_path = Path(args.grant_state_file)
    write_grant_state(state_path, pending_state)
    build_result = None
    read_result = None
    primary_error = None
    try:
        build_result = client.create_grant(request_body)
        state = GrantState(
            status="active",
            grant_id=build_result.grant_id,
            actor=build_result.actor,
            allowed_ref_prefix=build_result.allowed_ref_prefix,
            max_projects=build_result.max_projects,
            approval_binding=args.approval_binding,
            grant_ttl_seconds=args.grant_ttl_seconds,
            created_at_epoch_seconds=pending_state.created_at_epoch_seconds,
        )
        write_grant_state(state_path, state, replace=True)
        read_result = client.mint_token(
            state.grant_id,
            "read",
            args.token_ttl_seconds,
            state,
        )
        require(build_result.token != read_result.token, "PLATFORM_ROUTE_TOKENS_MUST_DIFFER")
        configure_routes(args, build_result.token, read_result.token)
    except OperatorError as error:
        primary_error = error
    finally:
        if build_result is not None:
            wipe(build_result.token)
        if read_result is not None:
            wipe(read_result.token)
    if primary_error is not None:
        if build_result is not None:
            containment_failed = False
            try:
                contain_routes(args)
            except OperatorError:
                containment_failed = True
            try:
                client.revoke(build_result.grant_id)
            except OperatorError:
                raise OperatorError("GRANT_REVOCATION_FAILED") from None
            try:
                state_path.unlink()
            except OSError:
                raise OperatorError("GRANT_STATE_CLEANUP_FAILED") from None
            if containment_failed:
                raise OperatorError("PLATFORM_CONTAINMENT_FAILED") from None
        raise primary_error


def rotate(client, args):
    state = read_grant_state(Path(args.grant_state_file))
    require(state.status == "active", "GRANT_STATE_PENDING_AUDIT")
    require(state.grant_id is not None, "GRANT_STATE_INVALID")
    build_result = None
    read_result = None
    primary_error = None
    try:
        build_result = client.mint_token(
            state.grant_id,
            "build",
            args.token_ttl_seconds,
            state,
        )
        read_result = client.mint_token(
            state.grant_id,
            "read",
            args.token_ttl_seconds,
            state,
        )
        require(build_result.token != read_result.token, "PLATFORM_ROUTE_TOKENS_MUST_DIFFER")
        configure_routes(args, build_result.token, read_result.token)
    except OperatorError as error:
        primary_error = error
    finally:
        if build_result is not None:
            wipe(build_result.token)
        if read_result is not None:
            wipe(read_result.token)
    if primary_error is not None:
        containment_failed = False
        try:
            contain_routes(args)
        except OperatorError:
            containment_failed = True
        try:
            client.revoke(state.grant_id)
        except OperatorError:
            raise OperatorError("GRANT_REVOCATION_FAILED") from None
        try:
            Path(args.grant_state_file).unlink()
        except OSError:
            raise OperatorError("GRANT_STATE_CLEANUP_FAILED") from None
        if containment_failed:
            raise OperatorError("PLATFORM_CONTAINMENT_FAILED") from None
        raise primary_error


def revoke(client, args):
    state_path = Path(args.grant_state_file)
    state = read_grant_state(state_path)
    require(state.status == "active", "GRANT_STATE_PENDING_AUDIT")
    require(state.grant_id is not None, "GRANT_STATE_INVALID")
    containment_failed = False
    try:
        contain_routes(args)
    except OperatorError:
        containment_failed = True
    client.revoke(state.grant_id)
    try:
        state_path.unlink()
    except OSError:
        raise OperatorError("GRANT_STATE_CLEANUP_FAILED") from None
    if containment_failed:
        raise OperatorError("PLATFORM_CONTAINMENT_FAILED")


def configure_routes(args, build_token, read_token):
    temporary_directory = Path(tempfile.mkdtemp(prefix="goai-platform-tokens-"))
    build_path = temporary_directory / "build.jwt"
    read_path = temporary_directory / "read.jwt"
    primary_error = None
    try:
        os.chmod(temporary_directory, 0o700)
        write_secret_file(build_path, build_token)
        write_secret_file(read_path, read_token)
        command, pass_fds = configure_command_prefix(args)
        command.extend(
            (
                "--project-build-token-file",
                str(build_path),
                "--project-read-token-file",
                str(read_path),
                "--platform-endpoint",
                PLATFORM_ENDPOINT,
            )
        )
        run_configure_command(command, pass_fds)
    except OperatorError as error:
        primary_error = error
    finally:
        cleanup_failed = False
        for path in (build_path, read_path):
            try:
                path.unlink(missing_ok=True)
            except OSError:
                cleanup_failed = True
        try:
            temporary_directory.rmdir()
        except OSError:
            cleanup_failed = True
        if cleanup_failed:
            raise OperatorError("HOST_TOKEN_CLEANUP_FAILED")
    if primary_error is not None:
        raise primary_error


def contain_routes(args):
    command, pass_fds = configure_command_prefix(args)
    command.extend(
        (
            "--contain-only",
            "--platform-endpoint",
            PLATFORM_ENDPOINT,
        )
    )
    run_configure_command(command, pass_fds)


def configure_command_prefix(args):
    command = [sys.executable, str(CONFIGURE_HELPER)]
    if args.console_session_file is not None:
        command.extend(("--console-session-file", args.console_session_file))
        return command, ()
    command.extend(("--console-session-fd", str(args.console_session_fd)))
    return command, (args.console_session_fd,)


def run_configure_command(command, pass_fds):
    environment = {
        "PATH": SAFE_PROCESS_PATH,
        "PYTHONDONTWRITEBYTECODE": "1",
    }
    process = None
    try:
        process = subprocess.Popen(
            command,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            pass_fds=pass_fds,
            env=environment,
            start_new_session=True,
        )
        _stdout, stderr_bytes = process.communicate(
            timeout=CONFIGURE_TIMEOUT_SECONDS,
        )
    except subprocess.TimeoutExpired:
        terminate_configure_process(process)
        raise OperatorError("ROUTE_CONFIGURATION_TIMEOUT") from None
    except KeyboardInterrupt:
        terminate_configure_process(process)
        raise OperatorError("ROUTE_CONFIGURATION_INTERRUPTED") from None
    except OperatorError:
        if process is not None and process.poll() is None:
            terminate_configure_process(process)
        raise
    except (OSError, subprocess.SubprocessError):
        if process is not None and process.poll() is None:
            terminate_configure_process(process)
        raise OperatorError("ROUTE_CONFIGURATION_FAILED") from None
    if process.returncode != 0:
        try:
            stderr = stderr_bytes.decode("ascii").strip()
        except (AttributeError, UnicodeDecodeError):
            raise OperatorError("ROUTE_CONFIGURATION_FAILED") from None
        match = re.fullmatch(
            r"Platform automation route refresh failed: ([A-Z][A-Z0-9_]*)",
            stderr,
        )
        if match is not None:
            child_code = match.group(1)
            if child_code.startswith("ROUTE_"):
                raise OperatorError(child_code) from None
            raise OperatorError(f"ROUTE_{child_code}") from None
        raise OperatorError("ROUTE_CONFIGURATION_FAILED") from None


def terminate_configure_process(process):
    if process is None:
        return
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        return
    except OSError:
        raise OperatorError("ROUTE_CONFIGURATION_TERMINATION_FAILED") from None
    try:
        process.communicate(timeout=CONFIGURE_TERMINATION_GRACE_SECONDS)
        return
    except subprocess.TimeoutExpired:
        pass
    try:
        os.killpg(process.pid, signal.SIGKILL)
        process.communicate(timeout=CONFIGURE_KILL_GRACE_SECONDS)
    except (OSError, subprocess.SubprocessError):
        raise OperatorError("ROUTE_CONFIGURATION_TERMINATION_FAILED") from None


def validate_token_result(
    response,
    expected_actor,
    expected_ref_prefix,
    expected_max_projects,
    expected_scopes,
    expected_grant_id=None,
):
    require(set(response) == TOKEN_RESULT_KEYS, "ADMIN_TOKEN_RESPONSE_FIELDS_INVALID")
    grant_id = response.get("grantId")
    require(valid_uuid(grant_id), "ADMIN_TOKEN_GRANT_ID_INVALID")
    if expected_grant_id is not None:
        require(grant_id == expected_grant_id, "ADMIN_TOKEN_GRANT_ID_MISMATCH")
    require(response.get("actor") == expected_actor, "ADMIN_TOKEN_ACTOR_MISMATCH")
    require(
        response.get("allowedRefPrefix") == expected_ref_prefix,
        "ADMIN_TOKEN_REF_PREFIX_MISMATCH",
    )
    require(
        response.get("maxProjects") == expected_max_projects,
        "ADMIN_TOKEN_MAX_PROJECTS_MISMATCH",
    )
    scopes = response.get("scopes")
    require(
        isinstance(scopes, list)
        and all(isinstance(scope, str) for scope in scopes)
        and len(scopes) == len(set(scopes))
        and frozenset(scopes) == expected_scopes,
        "ADMIN_TOKEN_SCOPES_MISMATCH",
    )
    require(
        isinstance(response.get("expiresAt"), str)
        and 1 <= len(response["expiresAt"]) <= 64,
        "ADMIN_TOKEN_EXPIRY_INVALID",
    )
    token = response.get("token")
    require(isinstance(token, str), "ADMIN_TOKEN_INVALID")
    try:
        token_bytes = token.encode("ascii")
    except UnicodeEncodeError:
        raise OperatorError("ADMIN_TOKEN_INVALID") from None
    require(
        0 < len(token_bytes) <= MAX_ROOT_TOKEN_BYTES
        and token == token.strip()
        and re.fullmatch(
            r"[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+",
            token,
        )
        is not None,
        "ADMIN_TOKEN_INVALID",
    )
    return TokenResult(
        grant_id=grant_id,
        actor=expected_actor,
        allowed_ref_prefix=expected_ref_prefix,
        max_projects=expected_max_projects,
        scopes=expected_scopes,
        token=bytearray(token_bytes),
    )


def read_secret_source(file_path, fd_number, label, max_bytes):
    descriptor = open_secret_source(file_path, fd_number, label)
    try:
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
        try:
            os.close(descriptor)
        except OSError:
            pass


def open_secret_source(file_path, fd_number, label):
    require((file_path is None) != (fd_number is None), f"{label}_SOURCE_INVALID")
    descriptor = None
    try:
        if file_path is not None:
            metadata = os.lstat(file_path)
            require(not stat.S_ISLNK(metadata.st_mode), f"{label}_SYMLINK_REJECTED")
            flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
            descriptor = os.open(file_path, flags)
        else:
            require(fd_number is not None and fd_number >= 3, f"{label}_FD_INVALID")
            descriptor = os.dup(fd_number)
        metadata = os.fstat(descriptor)
        require(stat.S_ISREG(metadata.st_mode), f"{label}_FILE_REQUIRED")
        require(metadata.st_uid == os.geteuid(), f"{label}_OWNER_INVALID")
        require(stat.S_IMODE(metadata.st_mode) == 0o600, f"{label}_PERMISSIONS_INVALID")
        return descriptor
    except OperatorError:
        if descriptor is not None:
            os.close(descriptor)
        raise
    except OSError:
        if descriptor is not None:
            os.close(descriptor)
        raise OperatorError(f"{label}_UNAVAILABLE") from None


def validate_secret_sources_are_distinct(args):
    root_fd = open_secret_source(
        args.metadata_root_file,
        args.metadata_root_fd,
        "METADATA_ROOT",
    )
    console_fd = open_secret_source(
        args.console_session_file,
        args.console_session_fd,
        "CONSOLE_SESSION",
    )
    try:
        root_metadata = os.fstat(root_fd)
        console_metadata = os.fstat(console_fd)
        require(
            (root_metadata.st_dev, root_metadata.st_ino)
            != (console_metadata.st_dev, console_metadata.st_ino),
            "SECRET_SOURCES_MUST_DIFFER",
        )
    finally:
        os.close(root_fd)
        os.close(console_fd)


def validate_root_token(token):
    try:
        text = bytes(token).decode("ascii")
    except UnicodeDecodeError:
        raise OperatorError("METADATA_ROOT_ENCODING_INVALID") from None
    require(text == text.strip() and not re.search(r"\s", text), "METADATA_ROOT_INVALID")
    require(not text.lower().startswith("bearer"), "METADATA_ROOT_INVALID")


def validate_helper(path):
    try:
        metadata = path.lstat()
        resolved = path.resolve(strict=True)
        repository = REPOSITORY_ROOT.resolve(strict=True)
    except OSError:
        raise OperatorError("CONFIGURE_HELPER_UNAVAILABLE") from None
    require(not stat.S_ISLNK(metadata.st_mode), "CONFIGURE_HELPER_SYMLINK_REJECTED")
    require(stat.S_ISREG(metadata.st_mode), "CONFIGURE_HELPER_FILE_REQUIRED")
    require(metadata.st_uid == os.geteuid(), "CONFIGURE_HELPER_OWNER_INVALID")
    require(metadata.st_mode & 0o022 == 0, "CONFIGURE_HELPER_PERMISSIONS_INVALID")
    try:
        resolved.relative_to(repository)
    except ValueError:
        raise OperatorError("CONFIGURE_HELPER_OUTSIDE_REPOSITORY") from None


def validate_state_parent(state_path):
    try:
        parent = state_path.parent.resolve(strict=True)
        metadata = parent.stat()
        repository = REPOSITORY_ROOT.resolve(strict=True)
    except OSError:
        raise OperatorError("GRANT_STATE_PARENT_INVALID") from None
    require(stat.S_ISDIR(metadata.st_mode), "GRANT_STATE_PARENT_INVALID")
    require(metadata.st_uid == os.geteuid(), "GRANT_STATE_PARENT_OWNER_INVALID")
    require(stat.S_IMODE(metadata.st_mode) & 0o077 == 0, "GRANT_STATE_PARENT_PERMISSIONS_INVALID")
    try:
        parent.relative_to(repository)
    except ValueError:
        return
    raise OperatorError("GRANT_STATE_INSIDE_REPOSITORY")


def write_secret_file(path, secret):
    descriptor = None
    try:
        descriptor = os.open(
            path,
            os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_CLOEXEC", 0),
            0o600,
        )
        write_all(descriptor, secret)
        os.fsync(descriptor)
    except OSError:
        raise OperatorError("HOST_TOKEN_STAGE_FAILED") from None
    finally:
        if descriptor is not None:
            os.close(descriptor)


def write_grant_state(path, state, replace=False):
    payload = {
        "version": 1,
        "status": state.status,
        "grantId": state.grant_id,
        "actor": state.actor,
        "allowedRefPrefix": state.allowed_ref_prefix,
        "maxProjects": state.max_projects,
        "approvalBinding": state.approval_binding,
        "grantTtlSeconds": state.grant_ttl_seconds,
        "createdAtEpochSeconds": state.created_at_epoch_seconds,
    }
    data = json.dumps(payload, separators=(",", ":"), sort_keys=True).encode("utf-8")
    target = path
    temporary = None
    if replace:
        temporary = path.with_name(f".{path.name}.tmp.{os.getpid()}.{uuid.uuid4().hex}")
        target = temporary
    descriptor = None
    try:
        descriptor = os.open(
            target,
            os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_CLOEXEC", 0),
            0o600,
        )
        write_all(descriptor, data)
        os.fsync(descriptor)
        os.close(descriptor)
        descriptor = None
        if replace:
            os.replace(target, path)
    except OSError:
        raise OperatorError("GRANT_STATE_WRITE_FAILED") from None
    finally:
        if descriptor is not None:
            os.close(descriptor)
        if temporary is not None:
            try:
                temporary.unlink(missing_ok=True)
            except OSError:
                pass


def write_all(descriptor, data):
    view = memoryview(data)
    offset = 0
    while offset < len(view):
        written = os.write(descriptor, view[offset:])
        if written <= 0:
            raise OSError("short write")
        offset += written


def read_grant_state(path):
    descriptor = open_secret_source(str(path), None, "GRANT_STATE")
    try:
        data = os.read(descriptor, MAX_STATE_BYTES + 1)
        require(len(data) <= MAX_STATE_BYTES, "GRANT_STATE_TOO_LARGE")
    except OSError:
        raise OperatorError("GRANT_STATE_UNAVAILABLE") from None
    finally:
        os.close(descriptor)
    try:
        parsed = json.loads(data, object_pairs_hook=reject_duplicate_json_keys)
    except OperatorError:
        raise
    except (UnicodeDecodeError, json.JSONDecodeError):
        raise OperatorError("GRANT_STATE_INVALID") from None
    require(isinstance(parsed, dict) and set(parsed) == STATE_KEYS, "GRANT_STATE_INVALID")
    require(parsed.get("version") == 1, "GRANT_STATE_INVALID")
    require(parsed.get("status") in ("pending", "active"), "GRANT_STATE_INVALID")
    if parsed["status"] == "active":
        require(valid_uuid(parsed.get("grantId")), "GRANT_STATE_INVALID")
    else:
        require(parsed.get("grantId") is None, "GRANT_STATE_INVALID")
    require(
        isinstance(parsed.get("actor"), str)
        and SAFE_ACTOR.fullmatch(parsed["actor"]) is not None,
        "GRANT_STATE_INVALID",
    )
    require(
        isinstance(parsed.get("allowedRefPrefix"), str)
        and SAFE_REF_PREFIX.fullmatch(parsed["allowedRefPrefix"]) is not None
        and parsed["allowedRefPrefix"].endswith("_"),
        "GRANT_STATE_INVALID",
    )
    require(
        isinstance(parsed.get("maxProjects"), int)
        and not isinstance(parsed["maxProjects"], bool)
        and 1 <= parsed["maxProjects"] <= 100,
        "GRANT_STATE_INVALID",
    )
    require(
        parsed.get("approvalBinding") is None
        or isinstance(parsed["approvalBinding"], str)
        and SAFE_APPROVAL.fullmatch(parsed["approvalBinding"]) is not None,
        "GRANT_STATE_INVALID",
    )
    require(
        isinstance(parsed.get("grantTtlSeconds"), int)
        and MIN_GRANT_TTL_SECONDS
        <= parsed["grantTtlSeconds"]
        <= MAX_GRANT_TTL_SECONDS,
        "GRANT_STATE_INVALID",
    )
    require(
        isinstance(parsed.get("createdAtEpochSeconds"), int)
        and parsed["createdAtEpochSeconds"] > 0,
        "GRANT_STATE_INVALID",
    )
    return GrantState(
        status=parsed["status"],
        grant_id=parsed["grantId"],
        actor=parsed["actor"],
        allowed_ref_prefix=parsed["allowedRefPrefix"],
        max_projects=parsed["maxProjects"],
        approval_binding=parsed["approvalBinding"],
        grant_ttl_seconds=parsed["grantTtlSeconds"],
        created_at_epoch_seconds=parsed["createdAtEpochSeconds"],
    )


def reject_duplicate_json_keys(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, "JSON_DUPLICATE_KEY")
        result[key] = value
    return result


def valid_uuid(value):
    if not isinstance(value, str):
        return False
    try:
        return str(uuid.UUID(value)) == value.lower()
    except ValueError:
        return False


def install_signal_handlers():
    def interrupt(_signal_number, _frame):
        raise OperatorError("INTERRUPTED")

    for name in ("SIGHUP", "SIGINT", "SIGTERM"):
        signal_number = getattr(signal, name, None)
        if signal_number is not None:
            signal.signal(signal_number, interrupt)


def wipe(secret):
    for index in range(len(secret)):
        secret[index] = 0


def require(condition, code):
    if not condition:
        raise OperatorError(code)


if __name__ == "__main__":
    raise SystemExit(main())
