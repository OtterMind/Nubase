#!/usr/bin/env python3

import argparse
import base64
import binascii
import copy
import hmac
import http.cookiejar
import json
import os
import re
import stat
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from dataclasses import dataclass
from pathlib import Path


CONSOLE_URL = "http://127.0.0.1:8001"
COOKIE_FILE = "/tmp/higress-session-cookie-gateway"
EXPECTED_UPSTREAM = "http://host.docker.internal:9999/mcp"
EXPECTED_PLATFORM_UPSTREAM = "http://host.docker.internal:9999/platform/mcp"
EXPECTED_SCHEME_ID = "UpstreamAuth0"
EXPECTED_PLATFORM_DOMAIN = "aigw-local.hiclaw.io"
EXPECTED_PLATFORM_SERVICE = "nubase-goai-sandbox.dns"
LEGACY_PLATFORM_SERVICE = "host.docker.internal"
PROJECT_BUILD_POLICY_NAME = "project-build"
PROJECT_READ_POLICY_NAME = "project-read"
PROJECT_BUILD_MCP_SERVER_NAME = "mcp-project-build"
PROJECT_READ_MCP_SERVER_NAME = "mcp-project-read"
PROJECT_BUILD_TOKEN_FILE = "/tmp/goai-platform-project-build.jwt"
PROJECT_READ_TOKEN_FILE = "/tmp/goai-platform-project-read.jwt"
PROJECT_BUILD_CONSUMERS = ("worker-nubase-builder",)
PROJECT_READ_CONSUMERS = (
    "worker-nubase-delivery-lead",
    "worker-nubase-verifier",
)
PLATFORM_TOOL_INVENTORY = frozenset({
    "platformProjectCreate",
    "platformProjectProvision",
    "platformProjectStatus",
})
PLATFORM_BUILD_SCOPES = frozenset(
    {"project:create", "project:provision", "project:status"}
)
PLATFORM_READ_SCOPES = frozenset({"project:status"})
PLATFORM_JWT_ISSUER = "nubase-platform"
PLATFORM_JWT_AUDIENCE = "nubase-agentteams-provisioning"
PLATFORM_JWT_KEY_ID = "platform-mcp-v1"
MAX_PLATFORM_TOKEN_BYTES = 16 * 1024
MAX_PLATFORM_TOKEN_TTL_SECONDS = 600
MIN_PLATFORM_TOKEN_REMAINING_SECONDS = 60
MAX_PLATFORM_TOKEN_CLOCK_SKEW_SECONDS = 30
MAX_INPUT_BYTES = 1024 * 1024
SAFE_TOOL = re.compile(r"^[a-z][A-Za-z0-9]*$")
SAFE_CLAIM_VALUE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
SAFE_ACTOR = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:@/-]{0,127}$")
SAFE_SCOPE = re.compile(r"^[a-z][a-z0-9:-]{0,63}$")
SAFE_APPROVAL = re.compile(r"^[A-Za-z0-9._:-]{1,128}$")
ALLOWED_PLATFORM_JWT_HEADERS = frozenset({"alg", "typ", "kid"})
ALLOWED_PLATFORM_JWT_CLAIMS = frozenset(
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

# This reviewed inventory is authoritative when the helper is copied into the
# controller. When the source tree is available, it is also checked for drift.
CANONICAL_TOOL_INVENTORY = frozenset({
    "appWorkerDelete",
    "appWorkerStatus",
    "appWorkersList",
    "assetsDelete",
    "assetsList",
    "assetsUpdateSettings",
    "assetsUpload",
    "authCreateUser",
    "authDeleteUser",
    "authListUsers",
    "cronCreate",
    "cronDelete",
    "cronGet",
    "cronList",
    "cronRuns",
    "cronUpdate",
    "deploymentLogs",
    "deploymentRollback",
    "deploymentStageAsset",
    "deploymentStatus",
    "deploymentsList",
    "executeSql",
    "executeSqlDryRun",
    "exportRlsPolicies",
    "functionsCreate",
    "functionsDelete",
    "functionsDeployBundle",
    "functionsList",
    "functionsLogs",
    "functionsSecretsList",
    "functionsSecretsSet",
    "functionsUpdate",
    "gatewayIssueKey",
    "gatewayListKeys",
    "gatewayRevokeKey",
    "getTableStructure",
    "initDatabase",
    "listTables",
    "memoryContext",
    "memorySearch",
    "memoryWrite",
    "storageCreateBucket",
    "storageDeleteBucket",
    "storageListBuckets",
})


@dataclass(frozen=True)
class RouteContract:
    name: str
    server_name: str
    proxy_name: str
    consumers: tuple[str, ...]
    allow_tools: tuple[str, ...]
    upstream: str = EXPECTED_UPSTREAM
    auth_header: str = "apikey"


ROUTE_CONTRACTS = (
    RouteContract(
        name="nubase-read",
        server_name="mcp-nubase-read",
        proxy_name="nubase-read-mcp-server",
        consumers=("worker-nubase-delivery-lead", "worker-nubase-verifier"),
        allow_tools=(
            "memorySearch",
            "memoryContext",
            "listTables",
            "getTableStructure",
            "exportRlsPolicies",
            "deploymentsList",
            "deploymentStatus",
            "deploymentLogs",
            "appWorkersList",
            "appWorkerStatus",
            "storageListBuckets",
            "assetsList",
            "functionsList",
            "functionsLogs",
            "cronList",
            "cronGet",
            "cronRuns",
        ),
    ),
    RouteContract(
        name="nubase-build",
        server_name="mcp-nubase-build",
        proxy_name="nubase-build-mcp-server",
        consumers=("worker-nubase-builder",),
        allow_tools=(
            "memorySearch",
            "memoryContext",
            "memoryWrite",
            "listTables",
            "getTableStructure",
            "exportRlsPolicies",
            "deploymentsList",
            "deploymentStatus",
            "deploymentLogs",
            "deploymentStageAsset",
            "storageListBuckets",
            "assetsList",
            "assetsUpload",
            "functionsList",
            "functionsCreate",
            "functionsUpdate",
            "functionsDeployBundle",
            "functionsLogs",
            "cronList",
            "cronGet",
            "cronCreate",
            "cronUpdate",
            "cronRuns",
        ),
    ),
    RouteContract(
        name="nubase-release",
        server_name="mcp-nubase-release",
        proxy_name="nubase-release-mcp-server",
        consumers=("worker-nubase-release-governor",),
        allow_tools=(
            "memorySearch",
            "memoryContext",
            "memoryWrite",
            "listTables",
            "getTableStructure",
            "exportRlsPolicies",
            "deploymentsList",
            "deploymentStatus",
            "deploymentLogs",
            "deploymentRollback",
            "appWorkersList",
            "appWorkerStatus",
            "assetsList",
            "functionsList",
            "functionsLogs",
            "cronList",
            "cronGet",
            "cronRuns",
        ),
    ),
)

PLATFORM_ROUTE_CONTRACTS = (
    RouteContract(
        name=PROJECT_BUILD_POLICY_NAME,
        server_name=PROJECT_BUILD_MCP_SERVER_NAME,
        proxy_name="project-build-mcp-server",
        consumers=PROJECT_BUILD_CONSUMERS,
        allow_tools=(
            "platformProjectCreate",
            "platformProjectProvision",
            "platformProjectStatus",
        ),
        upstream=EXPECTED_PLATFORM_UPSTREAM,
        # Envoy's proxy-wasm HTTP callout API requires lower-case HTTP/2 header names.
        auth_header="authorization",
    ),
    RouteContract(
        name=PROJECT_READ_POLICY_NAME,
        server_name=PROJECT_READ_MCP_SERVER_NAME,
        proxy_name="project-read-mcp-server",
        consumers=PROJECT_READ_CONSUMERS,
        allow_tools=("platformProjectStatus",),
        upstream=EXPECTED_PLATFORM_UPSTREAM,
        auth_header="authorization",
    ),
)


@dataclass(frozen=True)
class RouteState:
    contract: RouteContract
    current_allow: tuple[str, ...]
    safe_allow: tuple[str, ...]


@dataclass(frozen=True)
class PlatformRouteState:
    contract: RouteContract
    current_allow: tuple[str, ...]
    exists: bool
    token: bytearray


class PolicyRefreshError(Exception):
    def __init__(self, code):
        super().__init__(code)
        self.code = code


class NoRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, file_pointer, code, message, headers, new_url):
        del request, file_pointer, code, message, headers, new_url
        return None


def main():
    platform_tokens = []
    try:
        args = parse_args()
        validate_runtime_options(args.console_url, args.cookie_file)
        validate_source_inventory()
        policy = load_policy(args.policy)
        validate_policy(policy)
        opener = authenticated_opener(args.cookie_file)
        if args.contain_platform_routes:
            require(not args.enable_platform_routes, "PLATFORM_MODE_CONFLICT")
            validate_platform_runtime_options(args)
            contain_existing_platform_routes(opener, args.console_url)
            print("Contained platform automation routes: tools=0 consumers=0")
            return
        states = preflight_all(opener, args.console_url)
        platform_states = ()
        if args.enable_platform_routes:
            validate_platform_runtime_options(args)
            build_token = read_platform_token_file(
                args.project_build_token_file,
                PROJECT_BUILD_TOKEN_FILE,
                "PROJECT_BUILD_TOKEN",
            )
            read_token = read_platform_token_file(
                args.project_read_token_file,
                PROJECT_READ_TOKEN_FILE,
                "PROJECT_READ_TOKEN",
            )
            platform_tokens.extend((build_token, read_token))
            build_identity = validate_platform_jwt(
                build_token,
                "PROJECT_BUILD_TOKEN",
                PLATFORM_BUILD_SCOPES,
            )
            read_identity = validate_platform_jwt(
                read_token,
                "PROJECT_READ_TOKEN",
                PLATFORM_READ_SCOPES,
            )
            require(build_token != read_token, "PLATFORM_ROUTE_TOKENS_MUST_DIFFER")
            require(
                build_identity == read_identity,
                "PLATFORM_ROUTE_TOKEN_IDENTITY_MISMATCH",
            )
            platform_states = preflight_platform_all(
                opener,
                args.console_url,
                build_token,
                read_token,
            )
        apply_transitions(opener, args.console_url, states)
        if platform_states:
            apply_platform_transitions(opener, args.console_url, platform_states)
        for state in states:
            print(
                f"Refreshed {state.contract.server_name}: "
                f"tools={len(state.contract.allow_tools)} "
                f"consumers={len(state.contract.consumers)}"
            )
        for state in platform_states:
            print(
                f"Refreshed {state.contract.server_name}: "
                f"tools={len(state.contract.allow_tools)} "
                f"consumers={len(state.contract.consumers)}"
            )
    except PolicyRefreshError as error:
        print(f"Higress policy refresh failed: {error.code}", file=sys.stderr)
        raise SystemExit(1) from None
    except Exception:
        print("Higress policy refresh failed: UNEXPECTED_ERROR", file=sys.stderr)
        raise SystemExit(1) from None
    finally:
        for token in platform_tokens:
            wipe(token)


def parse_args(argv=None):
    parser = argparse.ArgumentParser(
        description="Refresh the reviewed local Higress MCP allowlists without exposing credentials."
    )
    parser.add_argument("--policy", required=True)
    parser.add_argument("--console-url", default=CONSOLE_URL)
    parser.add_argument("--cookie-file", default=COOKIE_FILE)
    parser.add_argument("--enable-platform-routes", action="store_true")
    parser.add_argument("--contain-platform-routes", action="store_true")
    parser.add_argument("--platform-endpoint", default=EXPECTED_PLATFORM_UPSTREAM)
    parser.add_argument(
        "--project-build-policy-name",
        default=PROJECT_BUILD_POLICY_NAME,
    )
    parser.add_argument(
        "--project-read-policy-name",
        default=PROJECT_READ_POLICY_NAME,
    )
    parser.add_argument(
        "--project-build-mcp-server-name",
        default=PROJECT_BUILD_MCP_SERVER_NAME,
    )
    parser.add_argument(
        "--project-read-mcp-server-name",
        default=PROJECT_READ_MCP_SERVER_NAME,
    )
    parser.add_argument("--project-build-consumer", action="append")
    parser.add_argument("--project-read-consumer", action="append")
    parser.add_argument(
        "--project-build-token-file",
        default=PROJECT_BUILD_TOKEN_FILE,
    )
    parser.add_argument(
        "--project-read-token-file",
        default=PROJECT_READ_TOKEN_FILE,
    )
    return parser.parse_args(argv)


def validate_runtime_options(console_url, cookie_file):
    require(console_url == CONSOLE_URL, "CONSOLE_URL_NOT_ALLOWED")
    require(cookie_file == COOKIE_FILE, "COOKIE_FILE_NOT_ALLOWED")


def validate_platform_runtime_options(args):
    require(
        args.platform_endpoint == EXPECTED_PLATFORM_UPSTREAM,
        "PLATFORM_ENDPOINT_NOT_ALLOWED",
    )
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
        tuple(args.project_build_consumer or PROJECT_BUILD_CONSUMERS)
        == PROJECT_BUILD_CONSUMERS,
        "PROJECT_BUILD_CONSUMERS_INVALID",
    )
    require(
        tuple(args.project_read_consumer or PROJECT_READ_CONSUMERS)
        == PROJECT_READ_CONSUMERS,
        "PROJECT_READ_CONSUMERS_INVALID",
    )
    require(
        args.project_build_token_file == PROJECT_BUILD_TOKEN_FILE,
        "PROJECT_BUILD_TOKEN_FILE_NOT_ALLOWED",
    )
    require(
        args.project_read_token_file == PROJECT_READ_TOKEN_FILE,
        "PROJECT_READ_TOKEN_FILE_NOT_ALLOWED",
    )


def load_policy(policy_path):
    path = Path(policy_path)
    try:
        data = path.read_bytes()
    except OSError:
        raise PolicyRefreshError("POLICY_UNAVAILABLE") from None
    require(len(data) <= MAX_INPUT_BYTES, "POLICY_TOO_LARGE")

    def reject_duplicate_keys(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, "POLICY_JSON_DUPLICATE_KEY")
            result[key] = value
        return result

    try:
        policy = json.loads(data, object_pairs_hook=reject_duplicate_keys)
    except PolicyRefreshError:
        raise
    except (UnicodeDecodeError, json.JSONDecodeError):
        raise PolicyRefreshError("POLICY_JSON_INVALID") from None
    require(isinstance(policy, dict), "POLICY_JSON_OBJECT_REQUIRED")
    return policy


def validate_policy(policy):
    routes = policy.get("routes")
    require(isinstance(routes, list), "POLICY_ROUTES_INVALID")
    all_contracts = ROUTE_CONTRACTS + PLATFORM_ROUTE_CONTRACTS
    require(len(routes) == len(all_contracts), "POLICY_ROUTE_COUNT_INVALID")
    route_names = []
    for index, (route, contract) in enumerate(zip(routes, all_contracts)):
        require(isinstance(route, dict), "POLICY_ROUTE_INVALID")
        route_names.append(route.get("name"))
        require(route.get("name") == contract.name, "POLICY_ROUTE_NAME_MISMATCH")
        require(route.get("mcpServerName") == contract.server_name, "POLICY_SERVER_NAME_MISMATCH")
        consumers = route.get("higressConsumers")
        require(isinstance(consumers, list), "POLICY_CONSUMERS_INVALID")
        require(tuple(consumers) == contract.consumers, "POLICY_CONSUMERS_MISMATCH")
        if index < len(ROUTE_CONTRACTS):
            validate_java_policy_route(route, contract)
        else:
            validate_platform_policy_route(route, contract)
    require(len(set(route_names)) == len(route_names), "POLICY_ROUTE_DUPLICATED")


def validate_java_policy_route(route, contract):
    require("platformHttpPolicy" not in route, "POLICY_PLATFORM_JAVA_OVERLAP")
    java_policy = route.get("javaHttpPolicy")
    require(isinstance(java_policy, dict), "POLICY_JAVA_HTTP_INVALID")
    allow_tools = java_policy.get("allowTools")
    deny_tools = java_policy.get("denyTools")
    validate_tool_partition(
        allow_tools,
        deny_tools,
        contract.allow_tools,
        CANONICAL_TOOL_INVENTORY,
    )
    if contract.name == "nubase-build":
        require(
            {"executeSql", "executeSqlDryRun"}.issubset(deny_tools),
            "POLICY_BUILD_SQL_DENY_MISSING",
        )


def validate_platform_policy_route(route, contract):
    require("javaHttpPolicy" not in route, "POLICY_PLATFORM_JAVA_OVERLAP")
    require("stdioBridgePolicy" not in route, "POLICY_PLATFORM_STDIO_OVERLAP")
    platform_policy = route.get("platformHttpPolicy")
    require(isinstance(platform_policy, dict), "POLICY_PLATFORM_HTTP_INVALID")
    require(
        platform_policy.get("endpoint") == "/platform/mcp",
        "POLICY_PLATFORM_ENDPOINT_MISMATCH",
    )
    validate_tool_partition(
        platform_policy.get("allowTools"),
        platform_policy.get("denyTools"),
        contract.allow_tools,
        PLATFORM_TOOL_INVENTORY,
    )


def validate_tool_partition(allow_tools, deny_tools, expected_allow, inventory):
    require(isinstance(allow_tools, list), "POLICY_ALLOW_TOOLS_INVALID")
    require(isinstance(deny_tools, list), "POLICY_DENY_TOOLS_INVALID")
    require(
        all(isinstance(tool, str) and SAFE_TOOL.fullmatch(tool) for tool in allow_tools),
        "POLICY_ALLOW_TOOLS_INVALID",
    )
    require(
        all(isinstance(tool, str) and SAFE_TOOL.fullmatch(tool) for tool in deny_tools),
        "POLICY_DENY_TOOLS_INVALID",
    )
    require(tuple(allow_tools) == expected_allow, "POLICY_ALLOW_TOOLS_MISMATCH")
    require(set(allow_tools).issubset(inventory), "POLICY_UNKNOWN_TOOL")
    require(len(allow_tools) == len(set(allow_tools)), "POLICY_ALLOW_TOOLS_DUPLICATED")
    require(len(deny_tools) == len(set(deny_tools)), "POLICY_DENY_TOOLS_DUPLICATED")
    require(not set(allow_tools).intersection(deny_tools), "POLICY_TOOL_PARTITION_OVERLAP")
    require(
        set(allow_tools).union(deny_tools) == inventory,
        "POLICY_TOOL_PARTITION_INCOMPLETE",
    )


def validate_source_inventory():
    require(len(CANONICAL_TOOL_INVENTORY) == 44, "CANONICAL_TOOL_COUNT_INVALID")
    script_path = Path(__file__).resolve()
    if len(script_path.parents) <= 2:
        return
    repository_root = script_path.parents[2]
    tools_root = repository_root / "src/main/java/ai/nubase/mcp/tools"
    if not tools_root.is_dir():
        return
    source_files = sorted(tools_root.glob("*McpTools.java"))
    require(source_files, "SOURCE_TOOL_FILES_MISSING")
    extracted = []
    for source_path in source_files:
        try:
            source = source_path.read_text(encoding="utf-8")
        except OSError:
            raise PolicyRefreshError("SOURCE_TOOL_FILE_UNAVAILABLE") from None
        awaiting_method = False
        for line in source.splitlines():
            if re.match(r"^\s*@Tool\b", line):
                require(not awaiting_method, "SOURCE_TOOL_ANNOTATION_INVALID")
                awaiting_method = True
            if not awaiting_method:
                continue
            method = re.match(
                r"^\s*public\s+(?!class\b|interface\b|enum\b|record\b)"
                r".*\b([A-Za-z_$][A-Za-z0-9_$]*)\s*\(",
                line,
            )
            if method:
                extracted.append(method.group(1))
                awaiting_method = False
        require(not awaiting_method, "SOURCE_TOOL_ANNOTATION_INVALID")
    require(len(extracted) == len(set(extracted)), "SOURCE_TOOL_DUPLICATED")
    require(len(extracted) == 44, "SOURCE_TOOL_COUNT_MISMATCH")
    require(frozenset(extracted) == CANONICAL_TOOL_INVENTORY, "SOURCE_TOOL_INVENTORY_DRIFT")


def authenticated_opener(cookie_file):
    path = Path(cookie_file)
    try:
        metadata = os.lstat(path)
    except OSError:
        raise PolicyRefreshError("CONSOLE_SESSION_UNAVAILABLE") from None
    require(stat.S_ISREG(metadata.st_mode), "CONSOLE_SESSION_FILE_INVALID")
    require(metadata.st_uid == os.geteuid(), "CONSOLE_SESSION_OWNER_INVALID")
    require(metadata.st_mode & 0o077 == 0, "CONSOLE_SESSION_PERMISSIONS_INVALID")

    jar = http.cookiejar.MozillaCookieJar(cookie_file)
    try:
        jar.load(ignore_discard=True, ignore_expires=True)
    except Exception:
        raise PolicyRefreshError("CONSOLE_SESSION_INVALID") from None
    cookies = list(jar)
    require(cookies, "CONSOLE_SESSION_EMPTY")
    for cookie in cookies:
        require(cookie.domain.lstrip(".") == "127.0.0.1", "CONSOLE_SESSION_DOMAIN_INVALID")
        cookie.expires = None
        cookie.discard = True
    return urllib.request.build_opener(
        urllib.request.ProxyHandler({}),
        NoRedirectHandler(),
        urllib.request.HTTPCookieProcessor(jar),
    )


def read_platform_token_file(token_file, expected_file, label):
    require(token_file == expected_file, f"{label}_FILE_NOT_ALLOWED")
    descriptor = None
    try:
        metadata = os.lstat(token_file)
        require(not stat.S_ISLNK(metadata.st_mode), f"{label}_SYMLINK_REJECTED")
        flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
        descriptor = os.open(token_file, flags)
        metadata = os.fstat(descriptor)
        require(stat.S_ISREG(metadata.st_mode), f"{label}_FILE_INVALID")
        require(metadata.st_uid == os.geteuid(), f"{label}_OWNER_INVALID")
        require(stat.S_IMODE(metadata.st_mode) == 0o600, f"{label}_PERMISSIONS_INVALID")
        token = bytearray()
        while len(token) <= MAX_PLATFORM_TOKEN_BYTES:
            chunk = os.read(
                descriptor,
                min(8192, MAX_PLATFORM_TOKEN_BYTES + 1 - len(token)),
            )
            if not chunk:
                break
            token.extend(chunk)
        require(token, f"{label}_EMPTY")
        require(len(token) <= MAX_PLATFORM_TOKEN_BYTES, f"{label}_TOO_LARGE")
        return token
    except PolicyRefreshError:
        raise
    except OSError:
        raise PolicyRefreshError(f"{label}_UNAVAILABLE") from None
    finally:
        if descriptor is not None:
            try:
                os.close(descriptor)
            except OSError:
                pass


def validate_platform_jwt(token_bytes, label, expected_scopes, now=None):
    try:
        token = bytes(token_bytes).decode("ascii")
    except UnicodeDecodeError:
        raise PolicyRefreshError(f"{label}_JWT_ENCODING_INVALID") from None
    require(token == token.strip() and not re.search(r"\s", token), f"{label}_JWT_WHITESPACE_INVALID")
    parts = token.split(".")
    require(len(parts) == 3 and all(parts), f"{label}_JWT_SHAPE_INVALID")
    require(
        re.fullmatch(r"[A-Za-z0-9_-]+", parts[2]) is not None,
        f"{label}_JWT_SIGNATURE_ENCODING_INVALID",
    )
    header = decode_platform_jwt_segment(parts[0], label, "HEADER")
    payload = decode_platform_jwt_segment(parts[1], label, "PAYLOAD")
    require(header.get("alg") == "HS256", f"{label}_JWT_ALGORITHM_INVALID")
    require(header.get("typ") == "JWT", f"{label}_JWT_TYPE_INVALID")
    require(set(header) == ALLOWED_PLATFORM_JWT_HEADERS, f"{label}_JWT_HEADER_INVALID")
    require(header.get("kid") == PLATFORM_JWT_KEY_ID, f"{label}_JWT_KID_INVALID")
    require(payload.get("iss") == PLATFORM_JWT_ISSUER, f"{label}_JWT_ISSUER_INVALID")
    require(
        exact_platform_audience(payload.get("aud")),
        f"{label}_JWT_AUDIENCE_INVALID",
    )
    require(
        set(payload).issubset(ALLOWED_PLATFORM_JWT_CLAIMS),
        f"{label}_JWT_CLAIMS_INVALID",
    )
    require(payload.get("role") == "platform_automation", f"{label}_JWT_ROLE_INVALID")
    require(payload.get("actor_type") == "automation", f"{label}_JWT_ACTOR_INVALID")
    require(
        isinstance(payload.get("sub"), str)
        and SAFE_ACTOR.fullmatch(payload["sub"]) is not None,
        f"{label}_JWT_SUB_INVALID",
    )
    require(safe_claim(payload.get("jti")), f"{label}_JWT_JTI_INVALID")
    require(valid_uuid(payload.get("grant_id")), f"{label}_JWT_GRANT_ID_INVALID")
    require(
        isinstance(payload.get("token_version"), int)
        and not isinstance(payload.get("token_version"), bool)
        and payload["token_version"] >= 0,
        f"{label}_JWT_TOKEN_VERSION_INVALID",
    )
    require("platform_role" not in payload, f"{label}_JWT_PRIVILEGE_INVALID")
    require(
        isinstance(payload.get("approval_binding"), str)
        and SAFE_APPROVAL.fullmatch(payload["approval_binding"]) is not None,
        f"{label}_JWT_APPROVAL_BINDING_INVALID",
    )
    scopes = parse_platform_scopes(payload.get("scope"), label)
    require(scopes == expected_scopes, f"{label}_JWT_SCOPE_INVALID")

    issued_at = platform_integer_claim(payload, "iat", label)
    not_before = platform_integer_claim(payload, "nbf", label)
    expires_at = platform_integer_claim(payload, "exp", label)
    current_time = int(time.time()) if now is None else int(now)
    require(
        issued_at <= current_time + MAX_PLATFORM_TOKEN_CLOCK_SKEW_SECONDS,
        f"{label}_JWT_IAT_INVALID",
    )
    require(
        not_before >= issued_at - MAX_PLATFORM_TOKEN_CLOCK_SKEW_SECONDS,
        f"{label}_JWT_NBF_INVALID",
    )
    require(
        not_before <= current_time + MAX_PLATFORM_TOKEN_CLOCK_SKEW_SECONDS,
        f"{label}_JWT_NOT_ACTIVE",
    )
    require(expires_at > not_before, f"{label}_JWT_EXPIRY_INVALID")
    require(
        expires_at - issued_at <= MAX_PLATFORM_TOKEN_TTL_SECONDS,
        f"{label}_JWT_TTL_TOO_LONG",
    )
    require(
        expires_at - current_time >= MIN_PLATFORM_TOKEN_REMAINING_SECONDS,
        f"{label}_JWT_TTL_TOO_SHORT",
    )
    return (
        payload["sub"],
        payload["grant_id"],
        payload["token_version"],
        payload["approval_binding"],
    )


def decode_platform_jwt_segment(segment, label, section):
    require(
        re.fullmatch(r"[A-Za-z0-9_-]+", segment) is not None,
        f"{label}_JWT_{section}_ENCODING_INVALID",
    )
    padded = segment + "=" * (-len(segment) % 4)

    def reject_duplicate_keys(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, f"{label}_JWT_{section}_DUPLICATE_KEY")
            result[key] = value
        return result

    try:
        raw = base64.b64decode(padded, altchars=b"-_", validate=True)
        parsed = json.loads(raw, object_pairs_hook=reject_duplicate_keys)
    except PolicyRefreshError:
        raise
    except (binascii.Error, UnicodeDecodeError, json.JSONDecodeError):
        raise PolicyRefreshError(f"{label}_JWT_{section}_INVALID") from None
    require(isinstance(parsed, dict), f"{label}_JWT_{section}_INVALID")
    return parsed


def parse_platform_scopes(value, label):
    require(isinstance(value, str) and value, f"{label}_JWT_SCOPE_INVALID")
    scopes = value.split(" ")
    require(all(SAFE_SCOPE.fullmatch(scope) for scope in scopes), f"{label}_JWT_SCOPE_INVALID")
    require(len(scopes) == len(set(scopes)), f"{label}_JWT_SCOPE_INVALID")
    return frozenset(scopes)


def platform_integer_claim(payload, name, label):
    value = payload.get(name)
    require(
        isinstance(value, int) and not isinstance(value, bool),
        f"{label}_JWT_{name.upper()}_INVALID",
    )
    return value


def safe_claim(value):
    return isinstance(value, str) and SAFE_CLAIM_VALUE.fullmatch(value) is not None


def exact_platform_audience(value):
    return value == PLATFORM_JWT_AUDIENCE or value == [PLATFORM_JWT_AUDIENCE]


def valid_uuid(value):
    if not isinstance(value, str):
        return False
    try:
        return str(uuid.UUID(value)) == value.lower()
    except ValueError:
        return False


def wipe(secret):
    for index in range(len(secret)):
        secret[index] = 0


def preflight_all(opener, console_url):
    states = []
    for contract in ROUTE_CONTRACTS:
        server = fetch_server(opener, console_url, contract)
        current_allow = tuple(read_route_allow_tools(server["rawConfigurations"], contract))
        current_set = set(current_allow)
        safe_allow = tuple(tool for tool in contract.allow_tools if tool in current_set)
        states.append(RouteState(contract, current_allow, safe_allow))
    return tuple(states)


def preflight_platform_all(opener, console_url, build_token, read_token):
    states = []
    for contract, token in zip(
        PLATFORM_ROUTE_CONTRACTS,
        (build_token, read_token),
    ):
        server = fetch_optional_server(opener, console_url, contract)
        if server is None:
            states.append(PlatformRouteState(contract, (), False, token))
            continue
        current_allow = tuple(read_route_allow_tools(server["rawConfigurations"], contract))
        states.append(PlatformRouteState(contract, current_allow, True, token))
    return tuple(states)


def apply_transitions(opener, console_url, states):
    try:
        for state in states:
            update_tools(
                opener,
                console_url,
                state.contract,
                state.current_allow,
                state.safe_allow,
            )
        for state in states:
            update_consumers(opener, console_url, state.contract)
        for state in states:
            update_tools(
                opener,
                console_url,
                state.contract,
                state.safe_allow,
                state.contract.allow_tools,
                expected_consumers=state.contract.consumers,
            )
    except PolicyRefreshError:
        contain_routes(opener, console_url, states)
        raise


def apply_platform_transitions(opener, console_url, states):
    try:
        for state in states:
            if state.exists:
                update_tools(
                    opener,
                    console_url,
                    state.contract,
                    state.current_allow,
                    (),
                )
            else:
                create_empty_platform_route(
                    opener,
                    console_url,
                    state.contract,
                    state.token,
                )
        for state in states:
            update_consumers(opener, console_url, state.contract)
        for state in states:
            update_platform_credential_and_tools(
                opener,
                console_url,
                state.contract,
                state.token,
            )
    except PolicyRefreshError:
        contain_existing_platform_routes(opener, console_url)
        raise


def contain_routes(opener, console_url, states):
    incomplete = False
    for state in states:
        try:
            server = fetch_server(opener, console_url, state.contract)
            current = tuple(read_route_allow_tools(server["rawConfigurations"], state.contract))
            current_set = set(current)
            containment = tuple(tool for tool in state.safe_allow if tool in current_set)
            update_tools(opener, console_url, state.contract, current, containment)
        except PolicyRefreshError:
            incomplete = True
        try:
            replace_consumers(opener, console_url, state.contract, ())
        except PolicyRefreshError:
            incomplete = True
    for state in states:
        try:
            server = fetch_server(opener, console_url, state.contract)
            contained_tools = tuple(
                read_route_allow_tools(
                    server["rawConfigurations"],
                    state.contract,
                )
            )
            require(
                set(contained_tools).issubset(set(state.safe_allow)),
                "ROUTE_CONTAINMENT_VERIFY_FAILED",
            )
            require(
                normalized_consumers(server) == (),
                "ROUTE_CONTAINMENT_VERIFY_FAILED",
            )
        except PolicyRefreshError:
            incomplete = True
    require(not incomplete, "ROUTE_CONTAINMENT_INCOMPLETE")


def contain_existing_platform_routes(opener, console_url):
    incomplete = False
    for contract in PLATFORM_ROUTE_CONTRACTS:
        should_clear_consumers = True
        try:
            server = fetch_optional_server(opener, console_url, contract)
            if server is None:
                should_clear_consumers = False
            else:
                current = tuple(read_route_allow_tools(server["rawConfigurations"], contract))
                update_tools(opener, console_url, contract, current, ())
        except PolicyRefreshError:
            incomplete = True
        if not should_clear_consumers:
            continue
        try:
            replace_consumers(opener, console_url, contract, ())
        except PolicyRefreshError:
            incomplete = True
    for contract in PLATFORM_ROUTE_CONTRACTS:
        try:
            server = fetch_optional_server(opener, console_url, contract)
            if server is None:
                continue
            require(
                read_route_allow_tools(server["rawConfigurations"], contract) == [],
                "PLATFORM_CONTAINMENT_VERIFY_FAILED",
            )
            require(
                normalized_consumers(server) == (),
                "PLATFORM_CONTAINMENT_VERIFY_FAILED",
            )
        except PolicyRefreshError:
            incomplete = True
    require(not incomplete, "PLATFORM_CONTAINMENT_INCOMPLETE")


def create_empty_platform_route(opener, console_url, contract, token):
    request_json(
        opener,
        "PUT",
        f"{console_url}/v1/mcpServer",
        build_platform_server_payload(contract, token, (), ()),
    )
    verified = fetch_server(opener, console_url, contract)
    require_canonical_platform_credential(
        verified["rawConfigurations"],
        contract,
        token,
    )
    require_canonical_platform_service(verified)
    require(
        tuple(read_route_allow_tools(verified["rawConfigurations"], contract)) == (),
        "PLATFORM_CREATE_ALLOW_TOOLS_VERIFY_FAILED",
    )
    require(normalized_consumers(verified) == (), "PLATFORM_CREATE_CONSUMERS_VERIFY_FAILED")


def update_platform_credential_and_tools(opener, console_url, contract, token):
    current = fetch_server(opener, console_url, contract)
    require(
        tuple(read_route_allow_tools(current["rawConfigurations"], contract)) == (),
        "ROUTE_STATE_CHANGED",
    )
    require(
        normalized_consumers(current) == tuple(sorted(contract.consumers)),
        "CONSUMER_VERIFY_FAILED",
    )
    request_json(
        opener,
        "PUT",
        f"{console_url}/v1/mcpServer",
        build_platform_server_payload(
            contract,
            token,
            contract.allow_tools,
            contract.consumers,
        ),
    )
    verified = fetch_server(opener, console_url, contract)
    require_canonical_platform_credential(
        verified["rawConfigurations"],
        contract,
        token,
    )
    require_canonical_platform_service(verified)
    require(
        tuple(read_route_allow_tools(verified["rawConfigurations"], contract))
        == contract.allow_tools,
        "ALLOW_TOOLS_VERIFY_FAILED",
    )
    require(
        normalized_consumers(verified) == tuple(sorted(contract.consumers)),
        "CONSUMER_VERIFY_FAILED",
    )


def build_platform_server_payload(contract, token, allow_tools, consumers):
    return {
        "name": contract.server_name,
        "description": "Nubase local platform automation route",
        "type": "OPEN_API",
        "rawConfigurations": build_platform_raw_configuration(
            contract,
            token,
            allow_tools,
        ),
        "domains": [EXPECTED_PLATFORM_DOMAIN],
        "services": [
            {
                "name": EXPECTED_PLATFORM_SERVICE,
                "port": 9999,
                "weight": 100,
            }
        ],
        "consumerAuthInfo": {
            "type": "key-auth",
            "enable": True,
            "allowedConsumers": list(consumers),
        },
    }


def build_platform_raw_configuration(contract, token, allow_tools):
    token_text = bytes(token).decode("ascii")
    require(
        re.fullmatch(r"[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+", token_text)
        is not None,
        "PLATFORM_TOKEN_SHAPE_INVALID",
    )
    require(
        tuple(allow_tools) in ((), contract.allow_tools),
        "PLATFORM_TARGET_TOOLS_INVALID",
    )
    allow_lines = "".join(f'  - "{tool}"\n' for tool in allow_tools)
    return (
        "server:\n"
        f"  name: {contract.proxy_name}\n"
        "  type: mcp-proxy\n"
        "  transport: http\n"
        f'  mcpServerURL: "{contract.upstream}"\n'
        "  timeout: 5000\n"
        "  securitySchemes:\n"
        f"    - id: {EXPECTED_SCHEME_ID}\n"
        "      type: apiKey\n"
        "      in: header\n"
        f"      name: {contract.auth_header}\n"
        f'      defaultCredential: "Bearer {token_text}"\n'
        "  defaultUpstreamSecurity:\n"
        f"    id: {EXPECTED_SCHEME_ID}\n"
        "allowTools:\n"
        f"{allow_lines}"
        "tools: []\n"
    )


def update_tools(
    opener,
    console_url,
    contract,
    expected_before,
    target_tools,
    expected_consumers=None,
):
    server = fetch_server(opener, console_url, contract)
    raw = server["rawConfigurations"]
    current = tuple(read_route_allow_tools(raw, contract))
    require(current == tuple(expected_before), "ROUTE_STATE_CHANGED")
    require(all(tool in CANONICAL_TOOL_INVENTORY for tool in target_tools), "TARGET_TOOL_INVALID")
    updated_raw = replace_route_allow_tools(raw, contract, target_tools)
    require(
        remove_route_allow_tools(raw, contract)
        == remove_route_allow_tools(updated_raw, contract),
        "UNEXPECTED_RAW_CONFIGURATION_CHANGE",
    )
    payload = build_server_update(server, contract, updated_raw)
    request_json(opener, "PUT", f"{console_url}/v1/mcpServer", payload)
    verified = fetch_server(opener, console_url, contract)
    require(
        tuple(read_route_allow_tools(verified["rawConfigurations"], contract))
        == tuple(target_tools),
        "ALLOW_TOOLS_VERIFY_FAILED",
    )
    if expected_consumers is not None:
        require(
            normalized_consumers(verified) == tuple(sorted(expected_consumers)),
            "CONSUMER_VERIFY_FAILED",
        )


def update_consumers(opener, console_url, contract):
    replace_consumers(opener, console_url, contract, contract.consumers)


def replace_consumers(opener, console_url, contract, target_consumers):
    server = fetch_server(opener, console_url, contract)
    current = set(normalized_consumers(server))
    target_values = tuple(target_consumers)
    target = set(target_values)
    require(len(target) == len(target_values), "TARGET_CONSUMERS_DUPLICATED")
    removed = sorted(current - target)
    added = sorted(target - current)
    if removed:
        request_json(
            opener,
            "DELETE",
            f"{console_url}/v1/mcpServer/consumers",
            {"mcpServerName": contract.server_name, "consumers": removed},
            expect_json=False,
        )
    if added:
        request_json(
            opener,
            "PUT",
            f"{console_url}/v1/mcpServer/consumers",
            {"mcpServerName": contract.server_name, "consumers": added},
            expect_json=False,
        )
    verified = fetch_server(opener, console_url, contract)
    require(
        normalized_consumers(verified) == tuple(sorted(target)),
        "CONSUMER_VERIFY_FAILED",
    )


def fetch_server(opener, console_url, contract):
    encoded_name = urllib.parse.quote(contract.server_name, safe="")
    response = request_json(
        opener,
        "GET",
        f"{console_url}/v1/mcpServer/{encoded_name}",
    )
    server = select_server(response, contract.server_name)
    validate_server(server, contract)
    return server


def fetch_optional_server(opener, console_url, contract):
    encoded_name = urllib.parse.quote(contract.server_name, safe="")
    try:
        response = request_json(
            opener,
            "GET",
            f"{console_url}/v1/mcpServer?mcpServerName={encoded_name}",
        )
        select_server(response, contract.server_name)
    except PolicyRefreshError as error:
        if error.code in {"CONSOLE_HTTP_404", "SERVER_NOT_FOUND"}:
            return None
        raise
    return fetch_server(opener, console_url, contract)


def select_server(response, server_name):
    require(isinstance(response, dict), "CONSOLE_RESPONSE_OBJECT_REQUIRED")
    data = response.get("data", response)
    if isinstance(data, list):
        matches = [
            server
            for server in data
            if isinstance(server, dict) and server.get("name") == server_name
        ]
        require(len(matches) == 1, "SERVER_NOT_FOUND")
        return matches[0]
    require(isinstance(data, dict), "SERVER_RESPONSE_INVALID")
    require(data.get("name") == server_name, "SERVER_NOT_FOUND")
    return data


def validate_server(server, contract):
    require(isinstance(server, dict), "SERVER_RESPONSE_INVALID")
    require(server.get("name") == contract.server_name, "SERVER_NAME_INVALID")
    require(server.get("type") == "OPEN_API", "SERVER_TYPE_INVALID")
    require(isinstance(server.get("description", ""), str), "SERVER_DESCRIPTION_INVALID")
    require(isinstance(server.get("domains"), list), "SERVER_DOMAINS_INVALID")
    require(all(isinstance(domain, str) for domain in server["domains"]), "SERVER_DOMAIN_INVALID")
    services = server.get("services")
    require(isinstance(services, list) and services, "SERVER_SERVICES_INVALID")
    for service in services:
        require(isinstance(service, dict), "SERVER_SERVICE_INVALID")
        require(isinstance(service.get("name"), str) and service["name"], "SERVER_SERVICE_NAME_INVALID")
        require(isinstance(service.get("port"), int), "SERVER_SERVICE_PORT_INVALID")
        require(isinstance(service.get("weight"), (int, float)), "SERVER_SERVICE_WEIGHT_INVALID")
    if contract in PLATFORM_ROUTE_CONTRACTS:
        require(
            server["domains"] == [EXPECTED_PLATFORM_DOMAIN],
            "PLATFORM_SERVER_DOMAINS_INVALID",
        )
        require(
            len(services) == 1
            and services[0]["name"] in {EXPECTED_PLATFORM_SERVICE, LEGACY_PLATFORM_SERVICE}
            and services[0]["port"] == 9999
            and services[0]["weight"] == 100,
            "PLATFORM_SERVER_SERVICES_INVALID",
        )
    auth_info = server.get("consumerAuthInfo")
    require(isinstance(auth_info, dict), "SERVER_AUTH_INVALID")
    allowed = auth_info.get("allowedConsumers", [])
    require(isinstance(allowed, list), "SERVER_CONSUMERS_INVALID")
    require(all(isinstance(value, str) for value in allowed), "SERVER_CONSUMER_INVALID")
    require(len(allowed) == len(set(allowed)), "SERVER_CONSUMER_DUPLICATED")
    if contract in PLATFORM_ROUTE_CONTRACTS:
        require(auth_info.get("type") == "key-auth", "PLATFORM_AUTH_TYPE_INVALID")
        require(auth_info.get("enable") is True, "PLATFORM_AUTH_ENABLE_INVALID")
    raw = server.get("rawConfigurations")
    require(isinstance(raw, str) and raw, "RAW_CONFIGURATION_MISSING")
    require(len(raw.encode("utf-8")) <= MAX_INPUT_BYTES, "RAW_CONFIGURATION_TOO_LARGE")
    validate_upstream_contract(raw, contract)
    read_route_allow_tools(raw, contract)


def normalized_consumers(server):
    allowed = server["consumerAuthInfo"].get("allowedConsumers", [])
    return tuple(sorted(allowed))


def require_canonical_platform_service(server):
    services = server["services"]
    require(
        len(services) == 1
        and services[0]["name"] == EXPECTED_PLATFORM_SERVICE
        and services[0]["port"] == 9999
        and services[0]["weight"] == 100,
        "PLATFORM_SERVER_SERVICE_NOT_CANONICAL",
    )


def build_server_update(server, contract, updated_raw):
    services = [
        {
            "name": service["name"],
            "port": service["port"],
            "weight": service["weight"],
        }
        for service in server["services"]
    ]
    return {
        "name": contract.server_name,
        "description": server.get("description", ""),
        "type": "OPEN_API",
        "rawConfigurations": updated_raw,
        "domains": copy.deepcopy(server["domains"]),
        "services": services,
        "consumerAuthInfo": copy.deepcopy(server["consumerAuthInfo"]),
    }


def validate_upstream_contract(raw, contract, expected_default_credential=None):
    lines = yaml_lines(raw)
    require_single_scoped_key(lines, "server", 0, "SERVER_SCOPE_INVALID")
    validate_allow_tools_scope(lines, contract)
    require_single_scoped_key(lines, "tools", 0, "TOOLS_SCOPE_INVALID")
    require_single_scoped_key(lines, "mcpServerURL", 2, "UPSTREAM_URL_SCOPE_INVALID")
    server_start, server_end = mapping_block(lines, "server", 0)

    require(
        direct_scalar(lines, server_start, server_end, "name", 2) == contract.proxy_name,
        "PROXY_NAME_MISMATCH",
    )
    require(
        direct_scalar(lines, server_start, server_end, "type", 2) == "mcp-proxy",
        "PROXY_TYPE_MISMATCH",
    )
    require(
        direct_scalar(lines, server_start, server_end, "transport", 2) == "http",
        "PROXY_TRANSPORT_MISMATCH",
    )
    require(
        direct_scalar(lines, server_start, server_end, "mcpServerURL", 2)
        == contract.upstream,
        "UPSTREAM_URL_MISMATCH",
    )
    auth_header = validate_security_scheme(
        lines,
        server_start,
        server_end,
        contract,
        expected_default_credential,
    )
    require(
        read_top_level_list(raw, "tools", require_explicit_empty=True) == [],
        "HICLAW_TOOLS_MARKER_MISSING",
    )
    return auth_header


def require_canonical_platform_auth_header(raw, contract):
    require(
        validate_upstream_contract(raw, contract) == contract.auth_header,
        "PLATFORM_SECURITY_SCHEME_HEADER_NOT_CANONICAL",
    )


def require_canonical_platform_credential(raw, contract, token):
    try:
        expected_credential = f"Bearer {bytes(token).decode('ascii')}"
    except UnicodeDecodeError:
        raise PolicyRefreshError("PLATFORM_TOKEN_ENCODING_INVALID") from None
    require(
        validate_upstream_contract(
            raw,
            contract,
            expected_default_credential=expected_credential,
        )
        == contract.auth_header,
        "PLATFORM_SECURITY_SCHEME_HEADER_NOT_CANONICAL",
    )


def validate_allow_tools_scope(lines, contract):
    occurrences = [
        (line_indent, content)
        for _, line_indent, content, _ in lines
        if re.match(r"^(?:-\s+)?allowTools\s*:", content)
    ]
    if not occurrences and contract in PLATFORM_ROUTE_CONTRACTS:
        return False
    require(len(occurrences) == 1, "ALLOW_TOOLS_SCOPE_INVALID")
    require(occurrences[0][0] == 0, "ALLOW_TOOLS_SCOPE_INVALID")
    require(not occurrences[0][1].startswith("- "), "ALLOW_TOOLS_SCOPE_INVALID")
    return True


def yaml_lines(raw):
    result = []
    for index, raw_line in enumerate(raw.splitlines()):
        require("\t" not in raw_line, "RAW_CONFIGURATION_TAB_INVALID")
        if not raw_line.strip() or raw_line.lstrip().startswith("#"):
            continue
        indent = len(raw_line) - len(raw_line.lstrip(" "))
        result.append((index, indent, raw_line[indent:], raw_line))
    require(result, "RAW_CONFIGURATION_EMPTY")
    return result


def require_single_scoped_key(lines, key, indent, code):
    occurrences = []
    pattern = re.compile(rf"^(?:-\s+)?{re.escape(key)}\s*:")
    for position, line_indent, content, _ in lines:
        if pattern.match(content):
            occurrences.append((position, line_indent, content))
    require(len(occurrences) == 1, code)
    require(occurrences[0][1] == indent, code)
    require(not occurrences[0][2].startswith("- "), code)


def mapping_block(lines, key, indent):
    matches = [
        index
        for index, (_, line_indent, content, _) in enumerate(lines)
        if line_indent == indent and re.fullmatch(rf"{re.escape(key)}:\s*", content)
    ]
    require(len(matches) == 1, f"{key.upper()}_BLOCK_INVALID")
    start = matches[0]
    end = len(lines)
    for index in range(start + 1, len(lines)):
        _, line_indent, content, _ = lines[index]
        if line_indent <= indent and not content.startswith("- "):
            end = index
            break
    return start, end


def direct_scalar(lines, block_start, block_end, key, indent):
    matches = []
    pattern = re.compile(rf"{re.escape(key)}:\s*(.*?)\s*$")
    for index in range(block_start + 1, block_end):
        _, line_indent, content, _ = lines[index]
        match = pattern.fullmatch(content)
        if match and line_indent == indent:
            matches.append((line_indent, match.group(1)))
    require(len(matches) == 1, f"{key.upper()}_COUNT_INVALID")
    return parse_scalar(matches[0][1], f"{key.upper()}_SCALAR_INVALID")


def validate_security_scheme(
    lines,
    server_start,
    server_end,
    contract,
    expected_default_credential=None,
):
    schemes_start = direct_mapping_index(
        lines,
        server_start,
        server_end,
        "securitySchemes",
        2,
    )
    schemes_end = next_direct_server_key(lines, schemes_start, server_end, 2)
    entries = []
    for index in range(schemes_start + 1, schemes_end):
        _, indent, content, _ = lines[index]
        match = re.fullmatch(
            r"-\s+([A-Za-z][A-Za-z0-9]*):\s*(.*?)\s*",
            content,
        )
        if match:
            entries.append((index, indent, match.group(1), match.group(2)))
    require(len(entries) == 1, "SECURITY_SCHEME_COUNT_INVALID")
    entry_index, entry_indent, first_key, first_value = entries[0]
    require(entry_indent in (2, 4), "SECURITY_SCHEME_INDENT_INVALID")
    credential_occurrences = [
        index
        for index, (_, _, content, _) in enumerate(lines)
        if re.match(r"^(?:-\s+)?defaultCredential\s*:", content)
    ]
    require(len(credential_occurrences) == 1, "DEFAULT_CREDENTIAL_SCOPE_INVALID")
    credential_index = credential_occurrences[0]
    require(entry_index <= credential_index < schemes_end, "DEFAULT_CREDENTIAL_SCOPE_INVALID")
    require(
        (credential_index == entry_index and lines[credential_index][1] == entry_indent)
        or lines[credential_index][1] == entry_indent + 2,
        "DEFAULT_CREDENTIAL_SCOPE_INVALID",
    )
    fields = {
        first_key: parse_scalar(first_value, "SECURITY_SCHEME_VALUE_INVALID")
    }
    for index in range(entry_index + 1, schemes_end):
        _, indent, content, _ = lines[index]
        require(indent == entry_indent + 2, "SECURITY_SCHEME_FIELD_SCOPE_INVALID")
        match = re.fullmatch(r"([A-Za-z][A-Za-z0-9]*):\s*(.*?)\s*", content)
        require(match is not None, "SECURITY_SCHEME_FIELD_INVALID")
        key, raw_value = match.groups()
        require(key not in fields, "SECURITY_SCHEME_FIELD_DUPLICATED")
        fields[key] = parse_scalar(raw_value, "SECURITY_SCHEME_VALUE_INVALID")
    require(
        set(fields) == {"id", "type", "in", "name", "defaultCredential"},
        "UPSTREAM_AUTH_CONTRACT_INVALID",
    )
    require(fields["id"] == EXPECTED_SCHEME_ID, "SECURITY_SCHEME_ID_MISMATCH")
    require(fields["type"] == "apiKey", "SECURITY_SCHEME_TYPE_MISMATCH")
    require(fields["in"] == "header", "SECURITY_SCHEME_LOCATION_MISMATCH")
    accepted_headers = {contract.auth_header}
    if contract in PLATFORM_ROUTE_CONTRACTS:
        # Accept the previously emitted spelling only while reconciling an existing route.
        accepted_headers.add("Authorization")
    require(fields["name"] in accepted_headers, "SECURITY_SCHEME_HEADER_MISMATCH")
    require(bool(fields["defaultCredential"]), "DEFAULT_CREDENTIAL_MISSING")
    if expected_default_credential is not None:
        try:
            actual_credential = fields["defaultCredential"].encode("ascii")
            expected_credential = expected_default_credential.encode("ascii")
        except (AttributeError, UnicodeEncodeError):
            raise PolicyRefreshError(
                "PLATFORM_DEFAULT_CREDENTIAL_MISMATCH"
            ) from None
        require(
            hmac.compare_digest(actual_credential, expected_credential),
            "PLATFORM_DEFAULT_CREDENTIAL_MISMATCH",
        )
    auth_header = fields["name"]

    security_start = direct_mapping_index(
        lines,
        server_start,
        server_end,
        "defaultUpstreamSecurity",
        2,
    )
    security_end = next_direct_server_key(lines, security_start, server_end, 2)
    fields = {}
    for index in range(security_start + 1, security_end):
        _, indent, content, _ = lines[index]
        require(indent == 4, "DEFAULT_SECURITY_FIELD_SCOPE_INVALID")
        match = re.fullmatch(r"([A-Za-z][A-Za-z0-9]*):\s*(.*?)\s*", content)
        require(match is not None, "DEFAULT_SECURITY_FIELD_INVALID")
        key, raw_value = match.groups()
        require(key not in fields, "DEFAULT_SECURITY_FIELD_DUPLICATED")
        fields[key] = parse_scalar(raw_value, "DEFAULT_SECURITY_VALUE_INVALID")
    require(fields == {"id": EXPECTED_SCHEME_ID}, "DEFAULT_SECURITY_CONTRACT_INVALID")
    return auth_header


def direct_mapping_index(lines, block_start, block_end, key, indent):
    pattern = re.compile(rf"{re.escape(key)}:\s*")
    matches = [
        index
        for index in range(block_start + 1, block_end)
        if lines[index][1] == indent and pattern.fullmatch(lines[index][2])
    ]
    require(len(matches) == 1, f"{key.upper()}_BLOCK_INVALID")
    all_occurrences = [
        index
        for index in range(len(lines))
        if re.match(rf"^(?:-\s+)?{re.escape(key)}\s*:", lines[index][2])
    ]
    require(len(all_occurrences) == 1, f"{key.upper()}_SCOPE_INVALID")
    return matches[0]


def next_direct_server_key(lines, start, server_end, indent):
    for index in range(start + 1, server_end):
        _, line_indent, content, _ = lines[index]
        if line_indent == indent and not content.startswith("- "):
            return index
    return server_end


def parse_scalar(raw_value, code):
    value = raw_value.strip()
    require(value, code)
    if value.startswith('"') or value.endswith('"'):
        require(len(value) >= 2 and value.startswith('"') and value.endswith('"'), code)
        inner = value[1:-1]
        require('"' not in inner and "\\" not in inner, code)
        return inner
    if value.startswith("'") or value.endswith("'"):
        require(len(value) >= 2 and value.startswith("'") and value.endswith("'"), code)
        inner = value[1:-1]
        require("'" not in inner, code)
        return inner
    require(not re.search(r"[\s#{}\[\],&*!|>@`]", value), code)
    return value


def read_top_level_list(raw, key, require_explicit_empty=False):
    lines = raw.splitlines()
    start, end = top_level_block(lines, key)
    header = lines[start].strip()
    require(header in (f"{key}:", f"{key}: []"), f"{key.upper()}_SHAPE_INVALID")
    if header == f"{key}: []":
        require(
            all(not line.strip() or line.lstrip().startswith("#") for line in lines[start + 1:end]),
            f"{key.upper()}_ITEM_INVALID",
        )
        return []
    require(not require_explicit_empty, f"{key.upper()}_EXPLICIT_EMPTY_REQUIRED")
    values = []
    for line in lines[start + 1:end]:
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        match = re.fullmatch(
            r'''(?:  )?-\s+(?:"([a-z][A-Za-z0-9]*)"|'([a-z][A-Za-z0-9]*)'|([a-z][A-Za-z0-9]*))\s*''',
            line,
        )
        require(match is not None, f"{key.upper()}_ITEM_INVALID")
        value = next(group for group in match.groups() if group is not None)
        require(SAFE_TOOL.fullmatch(value) is not None, f"{key.upper()}_ITEM_INVALID")
        values.append(value)
    require(len(values) == len(set(values)), f"{key.upper()}_ITEM_DUPLICATED")
    return values


def read_route_allow_tools(raw, contract):
    if not validate_allow_tools_scope(yaml_lines(raw), contract):
        return []
    return read_top_level_list(raw, "allowTools")


def replace_route_allow_tools(raw, contract, values):
    current = read_route_allow_tools(raw, contract)
    if current or has_top_level_list(raw, "allowTools"):
        return replace_top_level_list(raw, "allowTools", values)
    require(contract in PLATFORM_ROUTE_CONTRACTS, "ALLOW_TOOLS_SCOPE_INVALID")
    require(all(SAFE_TOOL.fullmatch(value) for value in values), "ALLOWTOOLS_ITEM_INVALID")
    require(len(values) == len(set(values)), "ALLOWTOOLS_ITEM_DUPLICATED")
    if not values:
        return raw
    lines = raw.splitlines()
    tools_start, _ = top_level_block(lines, "tools")
    replacement = ["allowTools:"]
    replacement.extend(f'  - "{value}"' for value in values)
    updated = lines[:tools_start] + replacement + lines[tools_start:]
    return "\n".join(updated) + ("\n" if raw.endswith("\n") else "")


def remove_route_allow_tools(raw, contract):
    read_route_allow_tools(raw, contract)
    if not has_top_level_list(raw, "allowTools"):
        return raw
    return remove_top_level_list(raw, "allowTools")


def has_top_level_list(raw, key):
    return any(
        re.fullmatch(rf"{re.escape(key)}:\s*(?:\[\])?\s*", line) is not None
        for line in raw.splitlines()
    )


def replace_top_level_list(raw, key, values):
    require(all(SAFE_TOOL.fullmatch(value) for value in values), f"{key.upper()}_ITEM_INVALID")
    require(len(values) == len(set(values)), f"{key.upper()}_ITEM_DUPLICATED")
    lines = raw.splitlines()
    start, end = top_level_block(lines, key)
    list_lines = [line for line in lines[start + 1:end] if line.strip() and not line.lstrip().startswith("#")]
    item_prefix = "- "
    quote = True
    if list_lines:
        item_prefix = "  - " if list_lines[0].startswith("  - ") else "- "
        quote = re.match(r'(?:  )?-\s+["\']', list_lines[0]) is not None
    replacement = [f"{key}:"]
    replacement.extend(
        f'{item_prefix}"{value}"' if quote else f"{item_prefix}{value}"
        for value in values
    )
    updated = lines[:start] + replacement + lines[end:]
    return "\n".join(updated) + ("\n" if raw.endswith("\n") else "")


def remove_top_level_list(raw, key):
    lines = raw.splitlines()
    start, end = top_level_block(lines, key)
    return "\n".join(lines[:start] + lines[end:])


def top_level_block(lines, key):
    candidates = [
        index
        for index, line in enumerate(lines)
        if re.fullmatch(rf"{re.escape(key)}:\s*(?:\[\])?\s*", line)
    ]
    require(len(candidates) == 1, f"{key.upper()}_TOP_LEVEL_COUNT_INVALID")
    occurrences = [
        line
        for line in lines
        if re.match(rf"^\s*(?:-\s+)?{re.escape(key)}\s*:", line)
    ]
    require(len(occurrences) == 1, f"{key.upper()}_SCOPE_INVALID")
    start = candidates[0]
    end = len(lines)
    for index in range(start + 1, len(lines)):
        line = lines[index]
        if line and not line[0].isspace() and not line.startswith(("#", "- ")):
            end = index
            break
    return start, end


def request_json(opener, method, url, body=None, expect_json=True):
    data = None if body is None else json.dumps(body, separators=(",", ":")).encode("utf-8")
    request = urllib.request.Request(url, data=data, method=method)
    if data is not None:
        request.add_header("Content-Type", "application/json")
    try:
        with opener.open(request, timeout=15) as response:
            payload = response.read(MAX_INPUT_BYTES + 1)
            status_code = response.status
    except urllib.error.HTTPError as error:
        raise PolicyRefreshError(f"CONSOLE_HTTP_{error.code}") from None
    except (OSError, TimeoutError, urllib.error.URLError):
        raise PolicyRefreshError("CONSOLE_REQUEST_FAILED") from None
    require(200 <= status_code < 300, "CONSOLE_HTTP_ERROR")
    require(len(payload) <= MAX_INPUT_BYTES, "CONSOLE_RESPONSE_TOO_LARGE")
    if not expect_json:
        return {}
    try:
        parsed = json.loads(payload)
    except (UnicodeDecodeError, json.JSONDecodeError):
        raise PolicyRefreshError("CONSOLE_RESPONSE_INVALID") from None
    require(isinstance(parsed, dict), "CONSOLE_RESPONSE_OBJECT_REQUIRED")
    require(parsed.get("success", True) is not False, "CONSOLE_OPERATION_REJECTED")
    return parsed


def require(condition, code):
    if not condition:
        raise PolicyRefreshError(code)


if __name__ == "__main__":
    main()
