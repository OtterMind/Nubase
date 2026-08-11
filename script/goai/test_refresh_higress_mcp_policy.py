#!/usr/bin/env python3

import copy
import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock


MODULE_PATH = Path(__file__).with_name("refresh-higress-mcp-policy.py")
SPEC = importlib.util.spec_from_file_location("refresh_higress_mcp_policy", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


def policy_document():
    return {
        "routes": [
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
    }


def raw_configuration(contract, allow_tools):
    allow_lines = "".join(f'- "{tool}"\n' for tool in allow_tools)
    return (
        "server:\n"
        f"  name: {contract.proxy_name}\n"
        "  type: mcp-proxy\n"
        "  transport: http\n"
        f'  mcpServerURL: "{MODULE.EXPECTED_UPSTREAM}"\n'
        "  timeout: 5000\n"
        "  securitySchemes:\n"
        f"    - id: {MODULE.EXPECTED_SCHEME_ID}\n"
        "      type: apiKey\n"
        "      in: header\n"
        "      name: apikey\n"
        '      defaultCredential: "runtime-only"\n'
        "  defaultUpstreamSecurity:\n"
        f"    id: {MODULE.EXPECTED_SCHEME_ID}\n"
        "allowTools:\n"
        f"{allow_lines}"
        "tools: []\n"
    )


class FakeConsole:
    def __init__(self, restricted_current=False, fail_first_consumer=False):
        self.servers = {}
        self.events = []
        self.fail_first_consumer = fail_first_consumer
        self.consumer_failure_used = False
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
                "consumerAuthInfo": {"allowedConsumers": []},
            }

    def request(self, opener, method, url, body=None, expect_json=True):
        del opener, expect_json
        if method == "GET":
            server_name = url.rsplit("/", 1)[1]
            return {"data": copy.deepcopy(self.servers[server_name])}
        if method == "PUT" and url.endswith("/v1/mcpServer/consumers"):
            if self.fail_first_consumer and not self.consumer_failure_used:
                self.consumer_failure_used = True
                raise MODULE.PolicyRefreshError("SIMULATED_CONSUMER_FAILURE")
            server_name = body["mcpServerName"]
            consumers = list(body["consumers"])
            self.servers[server_name]["consumerAuthInfo"]["allowedConsumers"] = consumers
            self.events.append(("consumers", server_name, tuple(consumers)))
            return {}
        if method == "PUT" and url.endswith("/v1/mcpServer"):
            server_name = body["name"]
            self.servers[server_name].update(copy.deepcopy(body))
            allow_tools = tuple(
                MODULE.read_top_level_list(body["rawConfigurations"], "allowTools")
            )
            self.events.append(("tools", server_name, allow_tools))
            return {}
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
                f"{MODULE.CONSOLE_URL}/v1/mcpServer/example",
            )


class RefreshPolicyTransitionTest(unittest.TestCase):
    def test_transition_order_is_shrink_then_consumers_then_final_tools(self):
        console = FakeConsole(restricted_current=True)
        with mock.patch.object(MODULE, "request_json", side_effect=console.request):
            states = MODULE.preflight_all(object(), MODULE.CONSOLE_URL)
            MODULE.apply_transitions(object(), MODULE.CONSOLE_URL, states)

        self.assertEqual([event[0] for event in console.events], [
            "tools", "tools", "tools",
            "consumers", "consumers", "consumers",
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


if __name__ == "__main__":
    unittest.main()
