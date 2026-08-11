#!/usr/bin/env python3

import base64
import copy
import importlib.util
import io
import json
import os
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest import mock


MODULE_PATH = Path(__file__).with_name("refresh-higress-mcp-policy.py")
SPEC = importlib.util.spec_from_file_location("refresh_higress_mcp_policy", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


def policy_document():
    tenant_routes = [
        {
            "name": contract.name,
            "mcpServerName": contract.server_name,
            "higressConsumers": list(contract.consumers),
            "javaHttpPolicy": {
                "allowTools": list(contract.allow_tools),
                "denyTools": sorted(
                    MODULE.CANONICAL_TOOL_INVENTORY.difference(contract.allow_tools)
                ),
            },
        }
        for contract in MODULE.ROUTE_CONTRACTS
    ]
    platform_routes = [
        {
            "name": contract.name,
            "mcpServerName": contract.server_name,
            "higressConsumers": list(contract.consumers),
            "platformHttpPolicy": {
                "endpoint": "/platform/mcp",
                "allowTools": list(contract.allow_tools),
                "denyTools": sorted(
                    MODULE.PLATFORM_TOOL_INVENTORY.difference(contract.allow_tools)
                ),
            },
        }
        for contract in MODULE.PLATFORM_ROUTE_CONTRACTS
    ]
    return {
        "routes": tenant_routes + platform_routes
    }


def raw_configuration(contract, allow_tools):
    allow_lines = "".join(f'- "{tool}"\n' for tool in allow_tools)
    return (
        "server:\n"
        f"  name: {contract.proxy_name}\n"
        "  type: mcp-proxy\n"
        "  transport: http\n"
        f'  mcpServerURL: "{contract.upstream}"\n'
        "  timeout: 5000\n"
        "  securitySchemes:\n"
        f"    - id: {MODULE.EXPECTED_SCHEME_ID}\n"
        "      type: apiKey\n"
        "      in: header\n"
        f"      name: {contract.auth_header}\n"
        '      defaultCredential: "runtime-only"\n'
        "  defaultUpstreamSecurity:\n"
        f"    id: {MODULE.EXPECTED_SCHEME_ID}\n"
        "allowTools:\n"
        f"{allow_lines}"
        "tools: []\n"
    )


def encode_jwt_segment(value):
    raw = json.dumps(value, separators=(",", ":")).encode("utf-8")
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def jwt_bytes(scopes, *, now=None, ttl=300, header_overrides=None, payload_overrides=None):
    issued_at = int(time.time()) if now is None else int(now)
    header = {"alg": "HS256", "typ": "JWT", "kid": MODULE.PLATFORM_JWT_KEY_ID}
    payload = {
        "iss": MODULE.PLATFORM_JWT_ISSUER,
        "aud": [MODULE.PLATFORM_JWT_AUDIENCE],
        "sub": "operator-automation",
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
    return bytearray(
        (
            f"{encode_jwt_segment(header)}.{encode_jwt_segment(payload)}."
            "c2lnbmF0dXJl"
        ).encode("ascii")
    )


def platform_scopes(contract):
    if contract.name == MODULE.PROJECT_BUILD_POLICY_NAME:
        return sorted(MODULE.PLATFORM_BUILD_SCOPES)
    return sorted(MODULE.PLATFORM_READ_SCOPES)


def preserve_default_credential(current_raw, updated_raw):
    current_lines = current_raw.splitlines(keepends=True)
    updated_lines = updated_raw.splitlines(keepends=True)
    current_matches = [
        line
        for line in current_lines
        if line.lstrip().startswith("defaultCredential:")
    ]
    updated_matches = [
        index
        for index, line in enumerate(updated_lines)
        if line.lstrip().startswith("defaultCredential:")
    ]
    if len(current_matches) != 1 or len(updated_matches) != 1:
        raise AssertionError("defaultCredential fixture is invalid")
    updated_lines[updated_matches[0]] = current_matches[0]
    return "".join(updated_lines)


class FakeConsole:
    def __init__(
        self,
        restricted_current=False,
        fail_first_consumer=False,
        include_platform=False,
        retain_platform_credentials=False,
    ):
        self.servers = {}
        self.events = []
        self.fail_first_consumer = fail_first_consumer
        self.consumer_failure_used = False
        self.retain_platform_credentials = retain_platform_credentials
        for contract in MODULE.ROUTE_CONTRACTS:
            current = list(contract.allow_tools)
            if restricted_current:
                current.pop()
                current.append(
                    next(
                        tool
                        for tool in sorted(MODULE.CANONICAL_TOOL_INVENTORY)
                        if tool not in contract.allow_tools
                    )
                )
            self.servers[contract.server_name] = {
                "name": contract.server_name,
                "description": "local route",
                "type": "OPEN_API",
                "rawConfigurations": raw_configuration(contract, current),
                "domains": ["aigw-local.hiclaw.io"],
                "services": [{"name": "local-upstream", "port": 9999, "weight": 100}],
                "consumerAuthInfo": {
                    "type": "key-auth",
                    "enable": True,
                    "allowedConsumers": [],
                },
            }
        if include_platform:
            for contract in MODULE.PLATFORM_ROUTE_CONTRACTS:
                self.servers[contract.server_name] = {
                    "name": contract.server_name,
                    "description": "local platform route",
                    "type": "OPEN_API",
                    "rawConfigurations": MODULE.build_platform_raw_configuration(
                        contract,
                        jwt_bytes(platform_scopes(contract)),
                        contract.allow_tools,
                    ),
                    "domains": [MODULE.EXPECTED_PLATFORM_DOMAIN],
                    "services": [
                        {
                            "name": MODULE.EXPECTED_PLATFORM_SERVICE,
                            "port": 9999,
                            "weight": 100,
                        }
                    ],
                    "consumerAuthInfo": {
                        "type": "key-auth",
                        "enable": True,
                        "allowedConsumers": ["worker-unauthorized"]
                    },
                }

    def request(self, opener, method, url, body=None, expect_json=True):
        del opener, expect_json
        if method == "GET":
            if "/v1/mcpServer?mcpServerName=" in url:
                return {
                    "data": [
                        {"name": server["name"]}
                        for server in copy.deepcopy(list(self.servers.values()))
                    ]
                }
            server_name = url.rsplit("/", 1)[1]
            if server_name not in self.servers:
                raise MODULE.PolicyRefreshError("CONSOLE_HTTP_502")
            server = copy.deepcopy(self.servers[server_name])
            platform_names = {
                contract.server_name for contract in MODULE.PLATFORM_ROUTE_CONTRACTS
            }
            raw = server["rawConfigurations"]
            if (
                server_name in platform_names
                and MODULE.has_top_level_list(raw, "allowTools")
                and MODULE.read_top_level_list(raw, "allowTools") == []
            ):
                server["rawConfigurations"] = MODULE.remove_top_level_list(
                    raw,
                    "allowTools",
                )
            return {"data": server}
        if method in ("PUT", "DELETE") and url.endswith("/v1/mcpServer/consumers"):
            if self.fail_first_consumer and not self.consumer_failure_used:
                self.consumer_failure_used = True
                raise MODULE.PolicyRefreshError("SIMULATED_CONSUMER_FAILURE")
            server_name = body["mcpServerName"]
            consumers = list(body["consumers"])
            current = set(
                self.servers[server_name]["consumerAuthInfo"]["allowedConsumers"]
            )
            if method == "PUT":
                current.update(consumers)
                event = "consumers-add"
            else:
                current.difference_update(consumers)
                event = "consumers-delete"
            self.servers[server_name]["consumerAuthInfo"]["allowedConsumers"] = sorted(
                current
            )
            self.events.append((event, server_name, tuple(consumers)))
            return {}
        if method == "PUT" and url.endswith("/v1/mcpServer"):
            if "mcpServerName" in body:
                raise MODULE.PolicyRefreshError("CONSOLE_HTTP_500")
            auth_info = body.get("consumerAuthInfo")
            if not isinstance(auth_info, dict):
                raise MODULE.PolicyRefreshError("CONSOLE_HTTP_500")
            if auth_info.get("type") != "key-auth" or auth_info.get("enable") is not True:
                raise MODULE.PolicyRefreshError("CONSOLE_HTTP_500")
            server_name = body["name"]
            platform_names = {
                contract.server_name for contract in MODULE.PLATFORM_ROUTE_CONTRACTS
            }
            body = copy.deepcopy(body)
            if (
                self.retain_platform_credentials
                and server_name in platform_names
                and server_name in self.servers
            ):
                body["rawConfigurations"] = preserve_default_credential(
                    self.servers[server_name]["rawConfigurations"],
                    body["rawConfigurations"],
                )
            if server_name not in self.servers:
                self.servers[server_name] = body
            else:
                self.servers[server_name].update(body)
            contracts = {
                contract.server_name: contract
                for contract in MODULE.ROUTE_CONTRACTS + MODULE.PLATFORM_ROUTE_CONTRACTS
            }
            allow_tools = tuple(
                MODULE.read_route_allow_tools(
                    body["rawConfigurations"],
                    contracts[server_name],
                )
            )
            self.events.append(("tools", server_name, allow_tools))
            return {"success": True, "data": copy.deepcopy(self.servers[server_name])}
        raise AssertionError(f"Unexpected request: {method}")

class FakeResponse:
    def __init__(self, payload):
        self.payload = payload
        self.status = 200

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        return False

    def read(self, limit):
        return self.payload[:limit]


class FakeOpener:
    def __init__(self, payload):
        self.payload = payload

    def open(self, request, timeout):
        del request, timeout
        return FakeResponse(self.payload)


class RefreshPolicyContractTest(unittest.TestCase):
    def test_main_unexpected_exception_exits_nonzero_without_details(self):
        stderr = io.StringIO()
        with mock.patch.object(
            MODULE,
            "parse_args",
            side_effect=RuntimeError("sensitive diagnostic"),
        ), mock.patch.object(sys, "stderr", stderr):
            with self.assertRaises(SystemExit) as raised:
                MODULE.main()

        self.assertEqual(raised.exception.code, 1)
        self.assertEqual(
            stderr.getvalue(),
            "Higress policy refresh failed: UNEXPECTED_ERROR\n",
        )

    def test_repository_policy_matches_all_five_route_contracts(self):
        policy_path = (
            MODULE_PATH.parents[2]
            / "examples/goai-agent-delivery/agentteams-v1.2.2/mcp-tool-policies.json"
        )
        MODULE.validate_policy(MODULE.load_policy(policy_path))

    def test_builder_java_contract_classifies_all_tools_and_denies_sql(self):
        policy = policy_document()
        MODULE.validate_policy(policy)

        self.assertEqual(len(MODULE.CANONICAL_TOOL_INVENTORY), 44)
        builder = policy["routes"][1]["javaHttpPolicy"]
        self.assertEqual(len(builder["allowTools"]), 23)
        for tool in ("executeSql", "executeSqlDryRun"):
            self.assertNotIn(tool, builder["allowTools"])
            self.assertIn(tool, builder["denyTools"])

    def test_reviewed_policy_is_exact_and_expansion_is_rejected(self):
        policy = policy_document()
        MODULE.validate_policy(policy)

        expanded = copy.deepcopy(policy)
        expanded["routes"][0]["javaHttpPolicy"]["allowTools"].append("executeSql")
        with self.assertRaisesRegex(MODULE.PolicyRefreshError, "POLICY_ALLOW_TOOLS_MISMATCH"):
            MODULE.validate_policy(expanded)

        build_sql_escalation = copy.deepcopy(policy)
        build_policy = build_sql_escalation["routes"][1]["javaHttpPolicy"]
        build_policy["denyTools"].remove("executeSqlDryRun")
        build_policy["allowTools"].append("executeSqlDryRun")
        with self.assertRaisesRegex(MODULE.PolicyRefreshError, "POLICY_ALLOW_TOOLS_MISMATCH"):
            MODULE.validate_policy(build_sql_escalation)

        wrong_consumer = copy.deepcopy(policy)
        wrong_consumer["routes"][1]["higressConsumers"] = ["worker-nubase-verifier"]
        with self.assertRaisesRegex(MODULE.PolicyRefreshError, "POLICY_CONSUMERS_MISMATCH"):
            MODULE.validate_policy(wrong_consumer)

    def test_platform_policy_uses_exact_policy_and_console_names(self):
        policy = policy_document()
        MODULE.validate_policy(policy)

        build = policy["routes"][3]
        read = policy["routes"][4]
        self.assertEqual(build["name"], "project-build")
        self.assertEqual(build["mcpServerName"], "mcp-project-build")
        self.assertEqual(read["name"], "project-read")
        self.assertEqual(read["mcpServerName"], "mcp-project-read")

        expanded = copy.deepcopy(policy)
        expanded["routes"][4]["platformHttpPolicy"]["allowTools"].append(
            "platformProjectCreate"
        )
        expanded["routes"][4]["platformHttpPolicy"]["denyTools"].remove(
            "platformProjectCreate"
        )
        with self.assertRaisesRegex(
            MODULE.PolicyRefreshError,
            "POLICY_ALLOW_TOOLS_MISMATCH",
        ):
            MODULE.validate_policy(expanded)

    def test_platform_runtime_contract_is_fixed_and_hs256_only(self):
        args = MODULE.parse_args(
            [
                "--policy",
                "/tmp/policy.json",
                "--enable-platform-routes",
                "--project-build-consumer",
                "worker-nubase-builder",
                "--project-read-consumer",
                "worker-nubase-delivery-lead",
                "--project-read-consumer",
                "worker-nubase-verifier",
            ]
        )
        MODULE.validate_platform_runtime_options(args)

        args.project_build_mcp_server_name = "unexpected-project-build"
        with self.assertRaisesRegex(
            MODULE.PolicyRefreshError,
            "PROJECT_BUILD_MCP_SERVER_NAME_INVALID",
        ):
            MODULE.validate_platform_runtime_options(args)

        now = 2_000_000_000
        token = jwt_bytes(sorted(MODULE.PLATFORM_BUILD_SCOPES), now=now)
        MODULE.validate_platform_jwt(
            token,
            "PROJECT_BUILD_TOKEN",
            MODULE.PLATFORM_BUILD_SCOPES,
            now=now,
        )
        replaced_identity = MODULE.validate_platform_jwt(
            jwt_bytes(
                sorted(MODULE.PLATFORM_BUILD_SCOPES),
                now=now,
                payload_overrides={"token_version": 2},
            ),
            "PROJECT_BUILD_TOKEN",
            MODULE.PLATFORM_BUILD_SCOPES,
            now=now,
        )
        self.assertNotEqual(
            MODULE.validate_platform_jwt(
                token,
                "PROJECT_BUILD_TOKEN",
                MODULE.PLATFORM_BUILD_SCOPES,
                now=now,
            ),
            replaced_identity,
        )
        for overrides, expected in (
            ({"header": {"alg": "ES256"}}, "PROJECT_BUILD_TOKEN_JWT_ALGORITHM_INVALID"),
            ({"payload": {"role": "platform_user"}}, "PROJECT_BUILD_TOKEN_JWT_ROLE_INVALID"),
            (
                {"payload": {"approval_binding": None}},
                "PROJECT_BUILD_TOKEN_JWT_APPROVAL_BINDING_INVALID",
            ),
            (
                {"payload": {"scope": "project:status"}},
                "PROJECT_BUILD_TOKEN_JWT_SCOPE_INVALID",
            ),
        ):
            invalid = jwt_bytes(
                sorted(MODULE.PLATFORM_BUILD_SCOPES),
                now=now,
                header_overrides=overrides.get("header"),
                payload_overrides=overrides.get("payload"),
            )
            with self.subTest(expected=expected), self.assertRaisesRegex(
                MODULE.PolicyRefreshError,
                expected,
            ):
                MODULE.validate_platform_jwt(
                    invalid,
                    "PROJECT_BUILD_TOKEN",
                    MODULE.PLATFORM_BUILD_SCOPES,
                    now=now,
                )

    def test_platform_server_accepts_canonical_nullable_service_version(self):
        console = FakeConsole(include_platform=True)
        contract = MODULE.PLATFORM_ROUTE_CONTRACTS[0]
        server = console.servers[contract.server_name]
        server["services"][0]["version"] = None

        MODULE.validate_server(server, contract)

        server["services"].append(copy.deepcopy(server["services"][0]))
        with self.assertRaisesRegex(
            MODULE.PolicyRefreshError,
            "PLATFORM_SERVER_SERVICES_INVALID",
        ):
            MODULE.validate_server(server, contract)

    def test_platform_legacy_service_is_accepted_only_for_reconciliation(self):
        console = FakeConsole(include_platform=True)
        contract = MODULE.PLATFORM_ROUTE_CONTRACTS[0]
        server = console.servers[contract.server_name]
        server["services"][0]["name"] = MODULE.LEGACY_PLATFORM_SERVICE

        MODULE.validate_server(server, contract)
        with self.assertRaisesRegex(
            MODULE.PolicyRefreshError,
            "PLATFORM_SERVER_SERVICE_NOT_CANONICAL",
        ):
            MODULE.require_canonical_platform_service(server)

        server["services"][0]["name"] = MODULE.EXPECTED_PLATFORM_SERVICE
        MODULE.require_canonical_platform_service(server)

    def test_platform_omitted_empty_allow_tools_is_restored_before_tools(self):
        contract = MODULE.PLATFORM_ROUTE_CONTRACTS[0]
        raw = MODULE.build_platform_raw_configuration(
            contract,
            jwt_bytes(platform_scopes(contract)),
            (),
        )
        canonical = MODULE.remove_top_level_list(raw, "allowTools")

        MODULE.validate_upstream_contract(canonical, contract)
        self.assertEqual(MODULE.read_route_allow_tools(canonical, contract), [])

        restored = MODULE.replace_route_allow_tools(
            canonical,
            contract,
            contract.allow_tools,
        )
        self.assertEqual(
            tuple(MODULE.read_route_allow_tools(restored, contract)),
            contract.allow_tools,
        )
        self.assertLess(restored.index("allowTools:"), restored.index("tools: []"))
        self.assertEqual(
            MODULE.remove_route_allow_tools(restored, contract),
            canonical,
        )

    def test_platform_legacy_authorization_header_is_reconciled_to_lowercase(self):
        contract = MODULE.PLATFORM_ROUTE_CONTRACTS[0]
        canonical = MODULE.build_platform_raw_configuration(
            contract,
            jwt_bytes(platform_scopes(contract)),
            contract.allow_tools,
        )
        legacy = canonical.replace("name: authorization", "name: Authorization")

        self.assertEqual(
            MODULE.validate_upstream_contract(legacy, contract),
            "Authorization",
        )
        with self.assertRaisesRegex(
            MODULE.PolicyRefreshError,
            "PLATFORM_SECURITY_SCHEME_HEADER_NOT_CANONICAL",
        ):
            MODULE.require_canonical_platform_auth_header(legacy, contract)

        self.assertEqual(
            MODULE.validate_upstream_contract(canonical, contract),
            "authorization",
        )
        MODULE.require_canonical_platform_auth_header(canonical, contract)

    def test_platform_credential_comparison_rejects_non_ascii_without_echo(self):
        contract = MODULE.PLATFORM_ROUTE_CONTRACTS[0]
        token = jwt_bytes(platform_scopes(contract))
        canonical = MODULE.build_platform_raw_configuration(
            contract,
            token,
            contract.allow_tools,
        )
        non_ascii = canonical.replace(
            f'Bearer {bytes(token).decode("ascii")}',
            "Bearer credential-\N{LATIN SMALL LETTER E WITH ACUTE}",
        )

        with self.assertRaisesRegex(
            MODULE.PolicyRefreshError,
            "^PLATFORM_DEFAULT_CREDENTIAL_MISMATCH$",
        ) as raised:
            MODULE.require_canonical_platform_credential(
                non_ascii,
                contract,
                token,
            )

        self.assertNotIn(bytes(token).decode("ascii"), str(raised.exception))

    def test_allow_tools_omission_rejects_nested_duplicate_and_tenant_missing(self):
        platform_contract = MODULE.PLATFORM_ROUTE_CONTRACTS[0]
        platform_raw = MODULE.build_platform_raw_configuration(
            platform_contract,
            jwt_bytes(platform_scopes(platform_contract)),
            (),
        )
        canonical = MODULE.remove_top_level_list(platform_raw, "allowTools")
        invalid_platform_values = (
            canonical.replace("tools: []", "  allowTools: []\ntools: []"),
            platform_raw.replace("tools: []", "allowTools: []\ntools: []"),
        )
        for invalid in invalid_platform_values:
            with self.subTest(raw=invalid), self.assertRaisesRegex(
                MODULE.PolicyRefreshError,
                "ALLOW_TOOLS_SCOPE_INVALID",
            ):
                MODULE.validate_upstream_contract(invalid, platform_contract)

        tenant_contract = MODULE.ROUTE_CONTRACTS[0]
        tenant_missing = MODULE.remove_top_level_list(
            raw_configuration(tenant_contract, tenant_contract.allow_tools),
            "allowTools",
        )
        with self.assertRaisesRegex(
            MODULE.PolicyRefreshError,
            "ALLOW_TOOLS_SCOPE_INVALID",
        ):
            MODULE.validate_upstream_contract(tenant_missing, tenant_contract)

    def test_platform_token_file_requires_owner_0600_regular_file(self):
        with tempfile.NamedTemporaryFile() as handle:
            os.chmod(handle.name, 0o600)
            handle.write(jwt_bytes(sorted(MODULE.PLATFORM_READ_SCOPES)))
            handle.flush()
            token = MODULE.read_platform_token_file(
                handle.name,
                handle.name,
                "PROJECT_READ_TOKEN",
            )
            self.assertTrue(token)

            os.chmod(handle.name, 0o644)
            with self.assertRaisesRegex(
                MODULE.PolicyRefreshError,
                "PROJECT_READ_TOKEN_PERMISSIONS_INVALID",
            ):
                MODULE.read_platform_token_file(
                    handle.name,
                    handle.name,
                    "PROJECT_READ_TOKEN",
                )

    def test_duplicate_json_keys_and_non_object_policy_fail_closed(self):
        for content, expected in (
            ('{"routes": [], "routes": []}', "POLICY_JSON_DUPLICATE_KEY"),
            ("[]", "POLICY_JSON_OBJECT_REQUIRED"),
        ):
            with self.subTest(expected=expected), tempfile.NamedTemporaryFile() as handle:
                handle.write(content.encode("utf-8"))
                handle.flush()
                with self.assertRaisesRegex(MODULE.PolicyRefreshError, expected):
                    MODULE.load_policy(handle.name)

    def test_console_and_cookie_targets_are_fixed(self):
        MODULE.validate_runtime_options(MODULE.CONSOLE_URL, MODULE.COOKIE_FILE)
        for url in (
            "http://localhost:8001",
            "http://127.0.0.1:8001/",
            "http://127.0.0.1:8001@external.invalid",
            "https://127.0.0.1:8001",
        ):
            with self.subTest(url=url), self.assertRaisesRegex(
                MODULE.PolicyRefreshError, "CONSOLE_URL_NOT_ALLOWED"
            ):
                MODULE.validate_runtime_options(url, MODULE.COOKIE_FILE)
        with self.assertRaisesRegex(MODULE.PolicyRefreshError, "COOKIE_FILE_NOT_ALLOWED"):
            MODULE.validate_runtime_options(MODULE.CONSOLE_URL, "/tmp/other-cookie")

    def test_nested_contract_keys_are_rejected(self):
        contract = MODULE.ROUTE_CONTRACTS[0]
        raw = raw_configuration(contract, contract.allow_tools)
        MODULE.validate_upstream_contract(raw, contract)

        nested_url = raw.replace("  mcpServerURL:", "    mcpServerURL:")
        with self.assertRaisesRegex(MODULE.PolicyRefreshError, "UPSTREAM_URL_SCOPE_INVALID"):
            MODULE.validate_upstream_contract(nested_url, contract)

        nested_allow = raw.replace("allowTools:\n", "  allowTools:\n")
        with self.assertRaisesRegex(MODULE.PolicyRefreshError, "ALLOW_TOOLS_SCOPE_INVALID"):
            MODULE.validate_upstream_contract(nested_allow, contract)

        duplicate_credential = raw.replace(
            "  defaultUpstreamSecurity:\n",
            '  defaultCredential: "shadow"\n  defaultUpstreamSecurity:\n',
        )
        with self.assertRaisesRegex(MODULE.PolicyRefreshError, "DEFAULT_CREDENTIAL_SCOPE_INVALID"):
            MODULE.validate_upstream_contract(duplicate_credential, contract)

    def test_quoted_unindented_allowlist_and_empty_put_are_supported(self):
        contract = MODULE.ROUTE_CONTRACTS[0]
        raw = raw_configuration(contract, contract.allow_tools)
        self.assertEqual(
            MODULE.read_top_level_list(raw, "allowTools"),
            list(contract.allow_tools),
        )
        result = MODULE.request_json(
            FakeOpener(b""),
            "PUT",
            f"{MODULE.CONSOLE_URL}/v1/mcpServer",
            {"name": contract.server_name},
            expect_json=False,
        )
        self.assertEqual(result, {})

    def test_console_canonical_security_scheme_order_is_supported(self):
        contract = MODULE.ROUTE_CONTRACTS[0]
        raw = raw_configuration(contract, contract.allow_tools).replace(
            "  securitySchemes:\n"
            f"    - id: {MODULE.EXPECTED_SCHEME_ID}\n"
            "      type: apiKey\n"
            "      in: header\n"
            "      name: apikey\n"
            '      defaultCredential: "runtime-only"\n',
            "  securitySchemes:\n"
            '  - defaultCredential: "runtime-only"\n'
            f"    id: {MODULE.EXPECTED_SCHEME_ID}\n"
            "    in: header\n"
            "    name: apikey\n"
            "    type: apiKey\n",
        )

        MODULE.validate_upstream_contract(raw, contract)

    def test_shallow_copied_helper_uses_embedded_inventory(self):
        with mock.patch.object(MODULE, "__file__", "/tmp/refresh-higress-mcp-policy.py"):
            MODULE.validate_source_inventory()

    def test_console_json_response_must_be_an_object(self):
        with self.assertRaisesRegex(MODULE.PolicyRefreshError, "CONSOLE_RESPONSE_OBJECT_REQUIRED"):
            MODULE.request_json(
                FakeOpener(b"[]"),
                "GET",
                f"{MODULE.CONSOLE_URL}/v1/mcpServer?mcpServerName=example",
            )


class RefreshPolicyTransitionTest(unittest.TestCase):
    def test_transition_order_is_shrink_then_consumers_then_final_tools(self):
        console = FakeConsole(restricted_current=True)
        with mock.patch.object(MODULE, "request_json", side_effect=console.request):
            states = MODULE.preflight_all(object(), MODULE.CONSOLE_URL)
            MODULE.apply_transitions(object(), MODULE.CONSOLE_URL, states)

        self.assertEqual([event[0] for event in console.events], [
            "tools", "tools", "tools",
            "consumers-add", "consumers-add", "consumers-add",
            "tools", "tools", "tools",
        ])
        for state, event in zip(states, console.events[:3]):
            self.assertEqual(event[1:], (state.contract.server_name, state.safe_allow))
        for contract, event in zip(MODULE.ROUTE_CONTRACTS, console.events[6:]):
            self.assertEqual(event[1:], (contract.server_name, contract.allow_tools))
            server = console.servers[contract.server_name]
            self.assertEqual(
                tuple(MODULE.read_top_level_list(server["rawConfigurations"], "allowTools")),
                contract.allow_tools,
            )
            self.assertEqual(
                tuple(sorted(server["consumerAuthInfo"]["allowedConsumers"])),
                tuple(sorted(contract.consumers)),
            )

    def test_failure_leaves_each_route_at_its_safe_intersection(self):
        console = FakeConsole(restricted_current=True, fail_first_consumer=True)
        with mock.patch.object(MODULE, "request_json", side_effect=console.request):
            states = MODULE.preflight_all(object(), MODULE.CONSOLE_URL)
            with self.assertRaisesRegex(MODULE.PolicyRefreshError, "SIMULATED_CONSUMER_FAILURE"):
                MODULE.apply_transitions(object(), MODULE.CONSOLE_URL, states)

        for state in states:
            server = console.servers[state.contract.server_name]
            self.assertEqual(
                tuple(MODULE.read_top_level_list(server["rawConfigurations"], "allowTools")),
                state.safe_allow,
            )
            self.assertEqual(MODULE.normalized_consumers(server), ())

    def test_legacy_containment_aggregates_failures_and_returns_fixed_code(self):
        console = FakeConsole(restricted_current=True)
        update_attempts = []

        def fail_first_update(_opener, _url, contract, *_args, **_kwargs):
            update_attempts.append(contract.server_name)
            if len(update_attempts) == 1:
                raise MODULE.PolicyRefreshError("SIMULATED_CONTAINMENT_FAILURE")

        with mock.patch.object(MODULE, "request_json", side_effect=console.request):
            states = MODULE.preflight_all(object(), MODULE.CONSOLE_URL)
            with mock.patch.object(
                MODULE,
                "update_tools",
                side_effect=fail_first_update,
            ), mock.patch.object(MODULE, "replace_consumers") as replace_consumers:
                with self.assertRaisesRegex(
                    MODULE.PolicyRefreshError,
                    "^ROUTE_CONTAINMENT_INCOMPLETE$",
                ):
                    MODULE.contain_routes(object(), MODULE.CONSOLE_URL, states)

        self.assertEqual(len(update_attempts), len(MODULE.ROUTE_CONTRACTS))
        self.assertEqual(replace_consumers.call_count, len(MODULE.ROUTE_CONTRACTS))

    def test_platform_transition_replaces_consumers_then_rotates_credentials(self):
        console = FakeConsole(include_platform=True)
        build_token = jwt_bytes(sorted(MODULE.PLATFORM_BUILD_SCOPES))
        read_token = jwt_bytes(sorted(MODULE.PLATFORM_READ_SCOPES))
        with mock.patch.object(MODULE, "request_json", side_effect=console.request):
            states = MODULE.preflight_platform_all(
                object(),
                MODULE.CONSOLE_URL,
                build_token,
                read_token,
            )
            MODULE.apply_platform_transitions(object(), MODULE.CONSOLE_URL, states)

        self.assertEqual(
            [event[0] for event in console.events],
            [
                "tools",
                "tools",
                "consumers-delete",
                "consumers-add",
                "consumers-delete",
                "consumers-add",
                "tools",
                "tools",
            ],
        )
        for state in states:
            server = console.servers[state.contract.server_name]
            self.assertEqual(
                tuple(MODULE.read_route_allow_tools(
                    server["rawConfigurations"],
                    state.contract,
                )),
                state.contract.allow_tools,
            )
            self.assertEqual(
                tuple(sorted(server["consumerAuthInfo"]["allowedConsumers"])),
                tuple(sorted(state.contract.consumers)),
            )
            self.assertNotIn("worker-unauthorized", server["consumerAuthInfo"]["allowedConsumers"])

    def test_platform_transition_rejects_a_retained_old_credential_without_echo(self):
        console = FakeConsole(
            include_platform=True,
            retain_platform_credentials=True,
        )
        build_token = jwt_bytes(
            sorted(MODULE.PLATFORM_BUILD_SCOPES),
            payload_overrides={"jti": "rotated-build"},
        )
        read_token = jwt_bytes(
            sorted(MODULE.PLATFORM_READ_SCOPES),
            payload_overrides={"jti": "rotated-read"},
        )
        with mock.patch.object(MODULE, "request_json", side_effect=console.request):
            states = MODULE.preflight_platform_all(
                object(),
                MODULE.CONSOLE_URL,
                build_token,
                read_token,
            )
            with self.assertRaisesRegex(
                MODULE.PolicyRefreshError,
                "^PLATFORM_DEFAULT_CREDENTIAL_MISMATCH$",
            ) as raised:
                MODULE.apply_platform_transitions(
                    object(),
                    MODULE.CONSOLE_URL,
                    states,
                )

        self.assertNotIn(bytes(build_token).decode("ascii"), str(raised.exception))
        self.assertNotIn(bytes(read_token).decode("ascii"), str(raised.exception))
        for contract in MODULE.PLATFORM_ROUTE_CONTRACTS:
            server = console.servers[contract.server_name]
            self.assertEqual(
                MODULE.read_route_allow_tools(
                    server["rawConfigurations"],
                    contract,
                ),
                [],
            )
            self.assertEqual(MODULE.normalized_consumers(server), ())

    def test_platform_transition_upserts_missing_routes_with_empty_first_state(self):
        console = FakeConsole(include_platform=False)
        build_token = jwt_bytes(sorted(MODULE.PLATFORM_BUILD_SCOPES))
        read_token = jwt_bytes(sorted(MODULE.PLATFORM_READ_SCOPES))
        with mock.patch.object(MODULE, "request_json", side_effect=console.request):
            states = MODULE.preflight_platform_all(
                object(),
                MODULE.CONSOLE_URL,
                build_token,
                read_token,
            )
            self.assertTrue(all(not state.exists for state in states))
            MODULE.apply_platform_transitions(object(), MODULE.CONSOLE_URL, states)

        self.assertEqual(
            [event[0] for event in console.events],
            ["tools", "tools", "consumers-add", "consumers-add", "tools", "tools"],
        )
        self.assertEqual(console.events[0][2], ())
        self.assertEqual(console.events[1][2], ())

    def test_platform_failure_contains_tools_and_consumers_to_empty(self):
        console = FakeConsole(include_platform=True, fail_first_consumer=True)
        build_token = jwt_bytes(sorted(MODULE.PLATFORM_BUILD_SCOPES))
        read_token = jwt_bytes(sorted(MODULE.PLATFORM_READ_SCOPES))
        with mock.patch.object(MODULE, "request_json", side_effect=console.request):
            states = MODULE.preflight_platform_all(
                object(),
                MODULE.CONSOLE_URL,
                build_token,
                read_token,
            )
            with self.assertRaisesRegex(
                MODULE.PolicyRefreshError,
                "SIMULATED_CONSUMER_FAILURE",
            ):
                MODULE.apply_platform_transitions(object(), MODULE.CONSOLE_URL, states)

        for state in states:
            server = console.servers[state.contract.server_name]
            self.assertEqual(
                MODULE.read_route_allow_tools(
                    server["rawConfigurations"],
                    state.contract,
                ),
                [],
            )
            self.assertEqual(server["consumerAuthInfo"]["allowedConsumers"], [])

    def test_explicit_platform_containment_clears_tools_and_consumers(self):
        console = FakeConsole(include_platform=True)
        with mock.patch.object(MODULE, "request_json", side_effect=console.request):
            MODULE.contain_existing_platform_routes(object(), MODULE.CONSOLE_URL)

        for contract in MODULE.PLATFORM_ROUTE_CONTRACTS:
            server = console.servers[contract.server_name]
            self.assertEqual(
                MODULE.read_route_allow_tools(
                    server["rawConfigurations"],
                    contract,
                ),
                [],
            )
            self.assertEqual(server["consumerAuthInfo"]["allowedConsumers"], [])


if __name__ == "__main__":
    unittest.main()
