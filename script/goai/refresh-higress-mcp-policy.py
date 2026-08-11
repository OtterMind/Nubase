#!/usr/bin/env python3

import argparse
import copy
import http.cookiejar
import json
import os
import re
import stat
import sys
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from pathlib import Path


CONSOLE_URL = "http://127.0.0.1:8001"
COOKIE_FILE = "/tmp/higress-session-cookie-gateway"
EXPECTED_UPSTREAM = "http://host.docker.internal:9999/mcp"
EXPECTED_SCHEME_ID = "UpstreamAuth0"
MAX_INPUT_BYTES = 1024 * 1024
SAFE_TOOL = re.compile(r"^[a-z][A-Za-z0-9]*$")

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


@dataclass(frozen=True)
class RouteState:
    contract: RouteContract
    current_allow: tuple[str, ...]
    safe_allow: tuple[str, ...]


class PolicyRefreshError(Exception):
    def __init__(self, code):
        super().__init__(code)
        self.code = code


def main():
    try:
        args = parse_args()
        validate_runtime_options(args.console_url, args.cookie_file)
        validate_source_inventory()
        policy = load_policy(args.policy)
        validate_policy(policy)
        opener = authenticated_opener(args.cookie_file)
        states = preflight_all(opener, args.console_url)
        apply_transitions(opener, args.console_url, states)
        for state in states:
            print(
                f"Refreshed {state.contract.server_name}: "
                f"tools={len(state.contract.allow_tools)} "
                f"consumers={len(state.contract.consumers)}"
            )
    except PolicyRefreshError as error:
        print(f"Higress policy refresh failed: {error.code}", file=sys.stderr)
        raise SystemExit(1) from None


def parse_args():
    parser = argparse.ArgumentParser(
        description="Refresh the reviewed local Higress MCP allowlists without exposing credentials."
    )
    parser.add_argument("--policy", required=True)
    parser.add_argument("--console-url", default=CONSOLE_URL)
    parser.add_argument("--cookie-file", default=COOKIE_FILE)
    return parser.parse_args()


def validate_runtime_options(console_url, cookie_file):
    require(console_url == CONSOLE_URL, "CONSOLE_URL_NOT_ALLOWED")
    require(cookie_file == COOKIE_FILE, "COOKIE_FILE_NOT_ALLOWED")


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
    require(len(routes) == len(ROUTE_CONTRACTS), "POLICY_ROUTE_COUNT_INVALID")
    route_names = []
    for route, contract in zip(routes, ROUTE_CONTRACTS):
        require(isinstance(route, dict), "POLICY_ROUTE_INVALID")
        route_names.append(route.get("name"))
        require(route.get("name") == contract.name, "POLICY_ROUTE_NAME_MISMATCH")
        require(route.get("mcpServerName") == contract.server_name, "POLICY_SERVER_NAME_MISMATCH")
        consumers = route.get("higressConsumers")
        require(isinstance(consumers, list), "POLICY_CONSUMERS_INVALID")
        require(tuple(consumers) == contract.consumers, "POLICY_CONSUMERS_MISMATCH")
        java_policy = route.get("javaHttpPolicy")
        require(isinstance(java_policy, dict), "POLICY_JAVA_HTTP_INVALID")
        allow_tools = java_policy.get("allowTools")
        require(isinstance(allow_tools, list), "POLICY_ALLOW_TOOLS_INVALID")
        deny_tools = java_policy.get("denyTools")
        require(isinstance(deny_tools, list), "POLICY_DENY_TOOLS_INVALID")
        require(tuple(allow_tools) == contract.allow_tools, "POLICY_ALLOW_TOOLS_MISMATCH")
        require(
            set(allow_tools).issubset(CANONICAL_TOOL_INVENTORY),
            "POLICY_UNKNOWN_TOOL",
        )
        require(len(allow_tools) == len(set(allow_tools)), "POLICY_ALLOW_TOOLS_DUPLICATED")
        require(len(deny_tools) == len(set(deny_tools)), "POLICY_DENY_TOOLS_DUPLICATED")
        require(not set(allow_tools).intersection(deny_tools), "POLICY_TOOL_PARTITION_OVERLAP")
        require(
            set(allow_tools).union(deny_tools) == CANONICAL_TOOL_INVENTORY,
            "POLICY_TOOL_PARTITION_INCOMPLETE",
        )
        if contract.name == "nubase-build":
            require(
                {"executeSql", "executeSqlDryRun"}.issubset(deny_tools),
                "POLICY_BUILD_SQL_DENY_MISSING",
            )
    require(len(set(route_names)) == len(route_names), "POLICY_ROUTE_DUPLICATED")


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
    return urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))


def preflight_all(opener, console_url):
    states = []
    for contract in ROUTE_CONTRACTS:
        server = fetch_server(opener, console_url, contract)
        current_allow = tuple(read_top_level_list(server["rawConfigurations"], "allowTools"))
        current_set = set(current_allow)
        safe_allow = tuple(tool for tool in contract.allow_tools if tool in current_set)
        states.append(RouteState(contract, current_allow, safe_allow))
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


def contain_routes(opener, console_url, states):
    for state in states:
        try:
            server = fetch_server(opener, console_url, state.contract)
            current = tuple(read_top_level_list(server["rawConfigurations"], "allowTools"))
            current_set = set(current)
            containment = tuple(tool for tool in state.safe_allow if tool in current_set)
            update_tools(opener, console_url, state.contract, current, containment)
        except PolicyRefreshError:
            continue


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
    current = tuple(read_top_level_list(raw, "allowTools"))
    require(current == tuple(expected_before), "ROUTE_STATE_CHANGED")
    require(all(tool in CANONICAL_TOOL_INVENTORY for tool in target_tools), "TARGET_TOOL_INVALID")
    updated_raw = replace_top_level_list(raw, "allowTools", target_tools)
    require(
        remove_top_level_list(raw, "allowTools")
        == remove_top_level_list(updated_raw, "allowTools"),
        "UNEXPECTED_RAW_CONFIGURATION_CHANGE",
    )
    payload = build_server_update(server, contract, updated_raw)
    request_json(opener, "PUT", f"{console_url}/v1/mcpServer", payload, expect_json=False)
    verified = fetch_server(opener, console_url, contract)
    require(
        tuple(read_top_level_list(verified["rawConfigurations"], "allowTools"))
        == tuple(target_tools),
        "ALLOW_TOOLS_VERIFY_FAILED",
    )
    if expected_consumers is not None:
        require(
            normalized_consumers(verified) == tuple(sorted(expected_consumers)),
            "CONSUMER_VERIFY_FAILED",
        )


def update_consumers(opener, console_url, contract):
    request_json(
        opener,
        "PUT",
        f"{console_url}/v1/mcpServer/consumers",
        {"mcpServerName": contract.server_name, "consumers": list(contract.consumers)},
        expect_json=False,
    )
    verified = fetch_server(opener, console_url, contract)
    require(
        normalized_consumers(verified) == tuple(sorted(contract.consumers)),
        "CONSUMER_VERIFY_FAILED",
    )


def fetch_server(opener, console_url, contract):
    encoded_name = urllib.parse.quote(contract.server_name, safe="")
    response = request_json(opener, "GET", f"{console_url}/v1/mcpServer/{encoded_name}")
    server = select_server(response, contract.server_name)
    validate_server(server, contract)
    return server


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
    auth_info = server.get("consumerAuthInfo")
    require(isinstance(auth_info, dict), "SERVER_AUTH_INVALID")
    allowed = auth_info.get("allowedConsumers", [])
    require(isinstance(allowed, list), "SERVER_CONSUMERS_INVALID")
    require(all(isinstance(value, str) for value in allowed), "SERVER_CONSUMER_INVALID")
    require(len(allowed) == len(set(allowed)), "SERVER_CONSUMER_DUPLICATED")
    raw = server.get("rawConfigurations")
    require(isinstance(raw, str) and raw, "RAW_CONFIGURATION_MISSING")
    require(len(raw.encode("utf-8")) <= MAX_INPUT_BYTES, "RAW_CONFIGURATION_TOO_LARGE")
    validate_upstream_contract(raw, contract)
    read_top_level_list(raw, "allowTools")


def normalized_consumers(server):
    allowed = server["consumerAuthInfo"].get("allowedConsumers", [])
    return tuple(sorted(allowed))


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
        "mcpServerName": contract.server_name,
        "domains": copy.deepcopy(server["domains"]),
        "services": services,
        "consumerAuthInfo": copy.deepcopy(server["consumerAuthInfo"]),
    }


def validate_upstream_contract(raw, contract):
    lines = yaml_lines(raw)
    require_single_scoped_key(lines, "server", 0, "SERVER_SCOPE_INVALID")
    require_single_scoped_key(lines, "allowTools", 0, "ALLOW_TOOLS_SCOPE_INVALID")
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
        == EXPECTED_UPSTREAM,
        "UPSTREAM_URL_MISMATCH",
    )
    validate_security_scheme(lines, server_start, server_end)
    require(
        read_top_level_list(raw, "tools", require_explicit_empty=True) == [],
        "HICLAW_TOOLS_MARKER_MISSING",
    )


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


def validate_security_scheme(lines, server_start, server_end):
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
    require(fields["name"] == "apikey", "SECURITY_SCHEME_HEADER_MISMATCH")
    require(bool(fields["defaultCredential"]), "DEFAULT_CREDENTIAL_MISSING")

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
