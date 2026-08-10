#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
PACKAGE_ROOT="${REPO_ROOT}/examples/goai-agent-delivery"
AGENTTEAMS_ROOT="${PACKAGE_ROOT}/agentteams-v1.2.2"
HICLAW_ROOT="${PACKAGE_ROOT}/compat/hiclaw-v1.1.2"
SKILLS_ROOT="${PACKAGE_ROOT}/skills"
IDENTITIES_ROOT="${PACKAGE_ROOT}/identities"
MCP_POLICY="${AGENTTEAMS_ROOT}/mcp-tool-policies.json"

APPLY=false
MANAGER_WORKSPACE=""
HOST_SHARE_ROOT=""
CLI_KIND=""
CLI_BINARY=""
CLI_CONTAINER=""
RUNTIME_FAMILY=""
RUNTIME_OVERRIDE=""

WORKER_NAMES=(delivery-lead builder-agent verifier-agent release-governor)
SKILL_NAMES=(app-plan app-build release-verify release-govern)
SECRET_PATTERN='-----BEGIN ([A-Z]+ )?PRIVATE KEY-----|AKIA[0-9A-Z]{16}|ASIA[0-9A-Z]{16}|gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|sk-[A-Za-z0-9_-]{20,}|Bearer[[:space:]]+[A-Za-z0-9._~-]{20,}|https?://[^/@[:space:]]+:[^/@[:space:]]+@'

log() {
    printf '[goai-agentteams] %s\n' "$*"
}

warn() {
    printf '[goai-agentteams] WARNING: %s\n' "$*" >&2
}

die() {
    printf '[goai-agentteams] ERROR: %s\n' "$*" >&2
    exit 1
}

usage() {
    printf '%s\n' \
        'Usage: script/goai/install-agentteams.sh [--apply] [--runtime FAMILY] [--manager-workspace PATH] [--host-share-root PATH]' \
        '' \
        'Default behavior is validation only. It never copies files or applies resources.' \
        '' \
        'Options:' \
        '  --apply                     Copy custom Skills and apply the detected runtime manifest.' \
        '  --runtime FAMILY            Select agentteams or hiclaw when detection is ambiguous.' \
        '  --manager-workspace PATH    Override the detected Manager host workspace.' \
        '  --host-share-root PATH      Allow one exact, sanitized demo directory under evidence/.' \
        '  -h, --help                  Show this help.'
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --apply)
            APPLY=true
            shift
            ;;
        --manager-workspace)
            [ "$#" -ge 2 ] || die "--manager-workspace requires a path"
            MANAGER_WORKSPACE="$2"
            shift 2
            ;;
        --runtime)
            [ "$#" -ge 2 ] || die "--runtime requires agentteams or hiclaw"
            case "$2" in
                agentteams|hiclaw)
                    RUNTIME_OVERRIDE="$2"
                    ;;
                *)
                    die "--runtime must be agentteams or hiclaw"
                    ;;
            esac
            shift 2
            ;;
        --host-share-root)
            [ "$#" -ge 2 ] || die "--host-share-root requires an absolute path"
            HOST_SHARE_ROOT="$2"
            shift 2
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            die "unknown argument: $1"
            ;;
    esac
done

required_files() {
    local worker_name
    local skill_name

    printf '%s\n' "${AGENTTEAMS_ROOT}/team.yaml"
    printf '%s\n' "${AGENTTEAMS_ROOT}/README.md"
    printf '%s\n' "${MCP_POLICY}"
    printf '%s\n' "${HICLAW_ROOT}/team.yaml"
    printf '%s\n' "${HICLAW_ROOT}/README.md"
    for worker_name in "${WORKER_NAMES[@]}"; do
        printf '%s\n' "${AGENTTEAMS_ROOT}/workers/${worker_name}/worker.yaml"
        printf '%s\n' "${IDENTITIES_ROOT}/${worker_name}.md"
    done
    for skill_name in "${SKILL_NAMES[@]}"; do
        printf '%s\n' "${SKILLS_ROOT}/${skill_name}/SKILL.md"
    done
}

validate_required_files() {
    local path

    while IFS= read -r path; do
        [ -f "${path}" ] || die "required file is missing: ${path}"
    done < <(required_files)
}

validate_markdown_contracts() {
    local identity_path
    local skill_path
    local heading
    local worker_name
    local skill_name

    for worker_name in "${WORKER_NAMES[@]}"; do
        identity_path="${IDENTITIES_ROOT}/${worker_name}.md"
        for heading in Name Role Capabilities Inputs Outputs Dependencies "Decision Boundary" Trace; do
            grep -Fq -- "## ${heading}" "${identity_path}" \
                || die "identity ${worker_name} is missing heading: ${heading}"
        done
    done

    for skill_name in "${SKILL_NAMES[@]}"; do
        skill_path="${SKILLS_ROOT}/${skill_name}/SKILL.md"
        grep -Fq -- "name: ${skill_name}" "${skill_path}" \
            || die "skill ${skill_name} has an invalid name"
        for heading in Purpose Scenario Inputs Outputs "Call Conditions" Dependencies Procedure "Failure Handling" "Safety Constraints" "Reuse Boundaries" "Agent Relationship" Version; do
            grep -Fq -- "## ${heading}" "${skill_path}" \
                || die "skill ${skill_name} is missing heading: ${heading}"
        done
        for field in description assign_when version; do
            grep -Eq -- "^${field}:" "${skill_path}" \
                || die "skill ${skill_name} is missing frontmatter field: ${field}"
        done
    done
}

validate_secret_hygiene() {
    validate_no_sensitive_content "${PACKAGE_ROOT}" "contest package"
}

validate_no_sensitive_content() {
    local scan_root="$1"
    local scan_label="$2"
    local scan_status

    if grep -REIq --binary-files=without-match -- "${SECRET_PATTERN}" "${scan_root}" >/dev/null 2>&1; then
        die "potential credential material found in ${scan_label}"
    else
        scan_status="$?"
    fi
    [ "${scan_status}" -eq 1 ] \
        || die "sensitive-content scan failed for ${scan_label}"
}

validate_yaml_contracts() {
    command -v ruby >/dev/null 2>&1 \
        || die "ruby is required for safe local YAML validation"

    ruby -ryaml -e '
      worker_paths = ARGV[0, 4]
      team_path = ARGV[4]
      compat_path = ARGV[5]
      expected_names = %w[nubase-delivery-lead nubase-builder nubase-verifier nubase-release-governor]
      expected_skills = %w[app-plan app-build release-verify release-govern]
      expected_mcp_servers = %w[nubase-read nubase-build nubase-read nubase-release]

      workers = worker_paths.map { |path| YAML.safe_load(File.read(path), aliases: false) }
      workers.each_with_index do |worker, index|
        abort "invalid AgentTeams Worker apiVersion" unless worker["apiVersion"] == "agentteams.io/v1beta1"
        abort "invalid AgentTeams Worker kind" unless worker["kind"] == "Worker"
        abort "unexpected AgentTeams Worker name" unless worker.dig("metadata", "name") == expected_names[index]
        abort "AgentTeams Worker model is required" if worker.dig("spec", "model").to_s.empty?
        abort "AgentTeams Worker identity is required" if worker.dig("spec", "identity").to_s.empty?
        abort "unexpected AgentTeams Worker skill" unless worker.dig("spec", "skills") == [expected_skills[index]]
        actual_mcp_servers = (worker.dig("spec", "mcpServers") || []).map { |server| server["name"] }
        abort "unexpected AgentTeams Worker MCP server" unless actual_mcp_servers == [expected_mcp_servers[index]]
      end

      team = YAML.safe_load(File.read(team_path), aliases: false)
      abort "invalid AgentTeams Team apiVersion" unless team["apiVersion"] == "agentteams.io/v1beta1"
      abort "invalid AgentTeams Team kind" unless team["kind"] == "Team"
      members = team.dig("spec", "workerMembers") || []
      abort "AgentTeams Team must reference four Workers" unless members.map { |item| item["name"] } == expected_names
      abort "AgentTeams Team must have exactly one team_leader" unless members.count { |item| item["role"] == "team_leader" } == 1

      compat = YAML.safe_load(File.read(compat_path), aliases: false)
      abort "invalid HiClaw compatibility apiVersion" unless compat["apiVersion"] == "hiclaw.io/v1beta1"
      abort "invalid HiClaw compatibility kind" unless compat["kind"] == "Team"
      abort "HiClaw compatibility leader is required" unless compat.dig("spec", "leader", "name") == expected_names.first
      abort "HiClaw v1.1.2 LeaderSpec must not declare unsupported local skills" if compat.dig("spec", "leader").key?("skills")
      compat_workers = compat.dig("spec", "workers") || []
      abort "HiClaw compatibility Team must contain three Workers" unless compat_workers.map { |item| item["name"] } == expected_names.drop(1)
    ' \
        "${AGENTTEAMS_ROOT}/workers/delivery-lead/worker.yaml" \
        "${AGENTTEAMS_ROOT}/workers/builder-agent/worker.yaml" \
        "${AGENTTEAMS_ROOT}/workers/verifier-agent/worker.yaml" \
        "${AGENTTEAMS_ROOT}/workers/release-governor/worker.yaml" \
        "${AGENTTEAMS_ROOT}/team.yaml" \
        "${HICLAW_ROOT}/team.yaml"
}

validate_mcp_policy() {
    ruby -rjson -e '
      policy_path = ARGV[0]
      stdio_source_path = ARGV[1]
      java_tools_root = ARGV[2]
      policy = JSON.parse(File.read(policy_path))
      stdio_source = File.read(stdio_source_path)
      known_stdio_tools = stdio_source.scan(/^  ([a-z][a-z0-9_]+): \{/).flatten
      abort "empty stdio MCP tool inventory" if known_stdio_tools.empty?
      abort "duplicate stdio MCP source tool name" unless known_stdio_tools.uniq == known_stdio_tools

      java_sources = Dir.glob(File.join(java_tools_root, "*McpTools.java")).sort
      abort "empty Java MCP tool source inventory" if java_sources.empty?
      known_java_tools = []
      java_sources.each do |source_path|
        awaiting_method = false
        annotation_line = 0
        File.readlines(source_path).each_with_index do |line, index|
          if line.match?(/^\s*@Tool\b/)
            abort "#{source_path}:#{annotation_line} @Tool has no public method" if awaiting_method
            awaiting_method = true
            annotation_line = index + 1
          end
          next unless awaiting_method

          method = line.match(
            /^\s*public\s+(?!class\b|interface\b|enum\b|record\b).*\b([A-Za-z_$][A-Za-z0-9_$]*)\s*\(/
          )
          next unless method

          known_java_tools << method[1]
          awaiting_method = false
        end
        abort "#{source_path}:#{annotation_line} @Tool has no public method" if awaiting_method
      end
      abort "empty Java MCP tool inventory" if known_java_tools.empty?
      abort "duplicate Java MCP source tool name" unless known_java_tools.uniq == known_java_tools

      expected_consumer_by_worker = {
        "nubase-delivery-lead" => "worker-nubase-delivery-lead",
        "nubase-builder" => "worker-nubase-builder",
        "nubase-verifier" => "worker-nubase-verifier",
        "nubase-release-governor" => "worker-nubase-release-governor"
      }
      expected = {
        "nubase-read" => {
          "server" => "mcp-nubase-read",
          "workers" => %w[nubase-delivery-lead nubase-verifier],
          "consumers" => %w[worker-nubase-delivery-lead worker-nubase-verifier],
          "guards" => {
            "NUBASE_ALLOW_SQL_EXECUTE" => false,
            "NUBASE_ALLOW_DANGEROUS_SQL" => false,
            "NUBASE_ALLOW_ADMIN_WRITE" => false
          },
          "stdio_allow" => %w[
            fetch_docs nubase_capabilities nubase_instructions memory_context memory_search
            rest_select sql_dry_run db_export_schema db_list_migrations deployments_list
            deployment_status deployment_logs app_workers_list app_worker_status
            storage_list_buckets storage_list_objects auth_get_settings gateway_usage
            gateway_usage_daily gateway_usage_by_model gateway_usage_logs gateway_pricing
            assets_list functions_list functions_logs cron_list cron_get cron_runs
          ],
          "java_readiness" => "READY_AFTER_ROUTE_AND_AUTH_VERIFICATION",
          "java_allow" => %w[
            memorySearch memoryContext listTables getTableStructure exportRlsPolicies
            deploymentsList deploymentStatus deploymentLogs appWorkersList appWorkerStatus
            storageListBuckets assetsList functionsList functionsLogs cronList cronGet cronRuns
          ]
        },
        "nubase-build" => {
          "server" => "mcp-nubase-build",
          "workers" => %w[nubase-builder],
          "consumers" => %w[worker-nubase-builder],
          "guards" => {
            "NUBASE_ALLOW_SQL_EXECUTE" => true,
            "NUBASE_ALLOW_DANGEROUS_SQL" => false,
            "NUBASE_ALLOW_ADMIN_WRITE" => true
          },
          "stdio_allow" => %w[
            fetch_docs nubase_capabilities nubase_instructions memory_context memory_search
            memory_write rest_select sql_dry_run sql_execute db_export_schema db_list_migrations
            deploy_app deployments_list deployment_status deployment_logs storage_list_buckets
            storage_list_objects auth_get_settings gateway_usage gateway_usage_by_model
            gateway_pricing assets_list assets_upload functions_list functions_deploy
            functions_invoke functions_logs cron_list cron_get cron_create cron_update cron_runs
          ],
          "java_readiness" => "PARTIAL",
          "java_allow" => %w[
            memorySearch memoryContext memoryWrite listTables getTableStructure exportRlsPolicies
            executeSqlDryRun deploymentsList deploymentStatus deploymentLogs storageListBuckets
            assetsList assetsUpload functionsList functionsCreate functionsUpdate
            functionsDeployBundle functionsLogs cronList cronGet cronCreate cronUpdate cronRuns
          ]
        },
        "nubase-release" => {
          "server" => "mcp-nubase-release",
          "workers" => %w[nubase-release-governor],
          "consumers" => %w[worker-nubase-release-governor],
          "guards" => {
            "NUBASE_ALLOW_SQL_EXECUTE" => false,
            "NUBASE_ALLOW_DANGEROUS_SQL" => false,
            "NUBASE_ALLOW_ADMIN_WRITE" => true
          },
          "stdio_allow" => %w[
            fetch_docs nubase_capabilities nubase_instructions memory_context memory_search
            memory_write sql_dry_run db_export_schema db_list_migrations deployments_list
            deployment_status deployment_logs deployment_rollback app_workers_list
            app_worker_status gateway_usage gateway_usage_daily gateway_usage_by_model
            gateway_usage_logs gateway_pricing assets_list functions_list functions_logs
            cron_list cron_get cron_runs
          ],
          "java_readiness" => "READY_AFTER_ROUTE_AND_AUTH_VERIFICATION",
          "java_allow" => %w[
            memorySearch memoryContext memoryWrite listTables getTableStructure exportRlsPolicies
            deploymentsList deploymentStatus deploymentLogs deploymentRollback appWorkersList
            appWorkerStatus assetsList functionsList functionsLogs cronList cronGet cronRuns
          ]
        }
      }

      expected_policy_keys = %w[
        schemaVersion agentTeamsVersion enforcement optionalDefenseInDepth secretInjection
        deniedSensitiveToolsByTransport routes requiredToolsNotYetExposed
      ]
      abort "unexpected MCP policy fields" unless policy.keys.sort == expected_policy_keys.sort
      abort "unexpected MCP policy schema version" unless policy["schemaVersion"] == "2.0.0"
      abort "unexpected AgentTeams policy version" unless policy["agentTeamsVersion"] == "v1.2.2"
      abort "MCP secrets must be runtime-only" unless policy["secretInjection"] == "runtime-only"
      abort "unexpected Higress enforcement declarations" unless policy["enforcement"] == [
        "Higress MCP server allowTools",
        "Higress consumer authorization"
      ]
      abort "deployment_promote gap must stay explicit" unless policy["requiredToolsNotYetExposed"] == [
        "deployment_promote"
      ]

      sensitive_by_transport = policy["deniedSensitiveToolsByTransport"] || {}
      abort "unexpected sensitive tool transport fields" unless sensitive_by_transport.keys.sort == %w[
        javaHttpPolicy stdioBridgePolicy
      ].sort
      stdio_sensitive = sensitive_by_transport["stdioBridgePolicy"] || []
      java_sensitive = sensitive_by_transport["javaHttpPolicy"] || []
      abort "empty stdio sensitive tool inventory" if stdio_sensitive.empty?
      abort "empty Java sensitive tool inventory" if java_sensitive.empty?
      abort "duplicate stdio sensitive tool" unless stdio_sensitive.uniq == stdio_sensitive
      abort "duplicate Java sensitive tool" unless java_sensitive.uniq == java_sensitive
      abort "unknown stdio sensitive tool" unless (stdio_sensitive - known_stdio_tools).empty?
      abort "unknown Java sensitive tool" unless (java_sensitive - known_java_tools).empty?

      validate_partition = lambda do |transport_policy, known_tools, sensitive_tools, route_name, transport_name, name_pattern|
        allow_tools = transport_policy["allowTools"] || []
        deny_tools = transport_policy["denyTools"] || []
        abort "empty allowTools for #{route_name}/#{transport_name}" if allow_tools.empty?
        abort "duplicate allowTools for #{route_name}/#{transport_name}" unless allow_tools.uniq == allow_tools
        abort "duplicate denyTools for #{route_name}/#{transport_name}" unless deny_tools.uniq == deny_tools
        abort "allowTools and denyTools overlap for #{route_name}/#{transport_name}" unless (allow_tools & deny_tools).empty?
        abort "invalid tool naming for #{route_name}/#{transport_name}" unless (allow_tools | deny_tools).all? {
          |tool| tool.is_a?(String) && tool.match?(name_pattern)
        }
        abort "unknown tool in #{route_name}/#{transport_name}" unless ((allow_tools | deny_tools) - known_tools).empty?
        abort "incomplete exact tool policy for #{route_name}/#{transport_name}" unless (
          allow_tools | deny_tools
        ).sort == known_tools.sort
        abort "sensitive tool not denied for #{route_name}/#{transport_name}" unless (
          sensitive_tools - deny_tools
        ).empty?
      end

      routes = policy["routes"] || []
      abort "unexpected MCP policy routes" unless routes.map { |route| route["name"] }.sort == expected.keys.sort
      all_workers = []
      all_consumers = []

      routes.each do |route|
        route_name = route["name"]
        route_expected = expected.fetch(route_name)
        abort "unexpected MCP route fields for #{route_name}" unless route.keys.sort == %w[
          name mcpServerName agentTeamsWorkers higressConsumers stdioBridgePolicy javaHttpPolicy
        ].sort
        abort "unexpected MCP server name for #{route_name}" unless route["mcpServerName"] == route_expected["server"]

        workers = route["agentTeamsWorkers"] || []
        consumers = route["higressConsumers"] || []
        abort "unexpected AgentTeams Workers for #{route_name}" unless workers == route_expected["workers"]
        abort "unexpected Higress consumers for #{route_name}" unless consumers == route_expected["consumers"]
        abort "Worker/consumer cardinality mismatch for #{route_name}" unless workers.length == consumers.length
        workers.zip(consumers).each do |worker, consumer|
          expected_consumer = expected_consumer_by_worker.fetch(worker)
          abort "unexpected Worker/consumer mapping for #{route_name}" unless consumer == expected_consumer
        end
        all_workers.concat(workers)
        all_consumers.concat(consumers)

        stdio_policy = route["stdioBridgePolicy"] || {}
        abort "unexpected stdioBridgePolicy fields for #{route_name}" unless stdio_policy.keys.sort == %w[
          bridgeGuards allowTools denyTools
        ].sort
        abort "unexpected stdio bridge guards for #{route_name}" unless (
          stdio_policy["bridgeGuards"] == route_expected["guards"]
        )
        validate_partition.call(
          stdio_policy,
          known_stdio_tools,
          stdio_sensitive,
          route_name,
          "stdioBridgePolicy",
          /\A[a-z][a-z0-9_]*\z/
        )
        abort "unexpected stdio allowTools for #{route_name}" unless (
          stdio_policy["allowTools"].sort == route_expected["stdio_allow"].sort
        )

        java_policy = route["javaHttpPolicy"] || {}
        abort "unexpected javaHttpPolicy fields for #{route_name}" unless java_policy.keys.sort == %w[
          readiness readinessReason allowTools denyTools
        ].sort
        abort "unexpected Java readiness for #{route_name}" unless (
          java_policy["readiness"] == route_expected["java_readiness"]
        )
        abort "missing Java readiness reason for #{route_name}" if java_policy["readinessReason"].to_s.strip.empty?
        validate_partition.call(
          java_policy,
          known_java_tools,
          java_sensitive,
          route_name,
          "javaHttpPolicy",
          /\A[a-z][A-Za-z0-9]*\z/
        )
        abort "unexpected Java allowTools for #{route_name}" unless (
          java_policy["allowTools"].sort == route_expected["java_allow"].sort
        )

        next unless route_name == "nubase-build"

        abort "Java Builder readiness must remain PARTIAL" unless java_policy["readiness"] == "PARTIAL"
        abort "Java Builder must deny executeSql" unless (
          java_policy["denyTools"].include?("executeSql") && !java_policy["allowTools"].include?("executeSql")
        )
        abort "Java Builder must allow executeSqlDryRun" unless java_policy["allowTools"].include?(
          "executeSqlDryRun"
        )
        abort "Java Builder must disclose unavailable schema apply" unless java_policy["readinessReason"].match?(
          /schema apply is therefore unavailable/i
        )
      end

      abort "duplicate AgentTeams Worker route assignment" unless all_workers.uniq == all_workers
      abort "duplicate Higress consumer route assignment" unless all_consumers.uniq == all_consumers
    ' \
        "$MCP_POLICY" \
        "$REPO_ROOT/frontend/packages/mcp-bridge/src/tools.ts" \
        "$REPO_ROOT/src/main/java/ai/nubase/mcp/tools"
}

detect_cli() {
    local agentteams_container="false"
    local hiclaw_container="false"
    local agentteams_host="false"
    local hiclaw_host="false"

    if command -v docker >/dev/null 2>&1; then
        docker ps --format '{{.Names}}' | grep -Fxq 'agentteams-manager' && agentteams_container="true"
        docker ps --format '{{.Names}}' | grep -Fxq 'hiclaw-manager' && hiclaw_container="true"
    fi
    command -v agt >/dev/null 2>&1 && agentteams_host="true"
    command -v hiclaw >/dev/null 2>&1 && hiclaw_host="true"

    if [ -n "${RUNTIME_OVERRIDE}" ]; then
        if [ "${RUNTIME_OVERRIDE}" = "agentteams" ] && [ "${agentteams_container}" = "true" ]; then
            select_container_runtime agentteams agt agentteams-manager
            return
        fi
        if [ "${RUNTIME_OVERRIDE}" = "hiclaw" ] && [ "${hiclaw_container}" = "true" ]; then
            select_container_runtime hiclaw hiclaw hiclaw-manager
            return
        fi
        if [ "${RUNTIME_OVERRIDE}" = "agentteams" ] && [ "${agentteams_host}" = "true" ]; then
            select_host_runtime agentteams agt
            return
        fi
        if [ "${RUNTIME_OVERRIDE}" = "hiclaw" ] && [ "${hiclaw_host}" = "true" ]; then
            select_host_runtime hiclaw hiclaw
            return
        fi
        die "requested runtime is not available: ${RUNTIME_OVERRIDE}"
    fi

    if [ "${agentteams_container}" = "true" ] && [ "${hiclaw_container}" = "true" ]; then
        die "both AgentTeams and HiClaw Manager containers are running; select one with --runtime"
    fi
    if [ "${agentteams_container}" = "true" ]; then
        select_container_runtime agentteams agt agentteams-manager
        return
    fi
    if [ "${hiclaw_container}" = "true" ]; then
        select_container_runtime hiclaw hiclaw hiclaw-manager
        return
    fi
    if [ "${agentteams_host}" = "true" ] && [ "${hiclaw_host}" = "true" ]; then
        die "both agt and hiclaw host CLIs are available; select one with --runtime"
    fi
    if [ "${agentteams_host}" = "true" ]; then
        select_host_runtime agentteams agt
        return
    fi
    if [ "${hiclaw_host}" = "true" ]; then
        select_host_runtime hiclaw hiclaw
        return
    fi

    die "no running AgentTeams or HiClaw Manager was detected"
}

select_container_runtime() {
    RUNTIME_FAMILY="$1"
    CLI_BINARY="$2"
    CLI_CONTAINER="$3"
    CLI_KIND="container"
}

select_host_runtime() {
    RUNTIME_FAMILY="$1"
    CLI_BINARY="$2"
    CLI_CONTAINER=""
    CLI_KIND="host"
}

run_cli() {
    if [ "${CLI_KIND}" = "container" ]; then
        docker exec "${CLI_CONTAINER}" "${CLI_BINARY}" "$@"
    else
        "${CLI_BINARY}" "$@"
    fi
}

validate_runtime_version() {
    local expected_version
    local marker_path
    local actual_version

    if [ "${RUNTIME_FAMILY}" = "agentteams" ]; then
        expected_version="v1.2.2"
        marker_path="/opt/agentteams/agent/.builtin-version"
    else
        expected_version="v1.1.2"
        marker_path="/opt/hiclaw/agent/.builtin-version"
    fi

    if [ "${CLI_KIND}" = "container" ]; then
        actual_version="$(docker exec "${CLI_CONTAINER}" sh -c 'test -s "$1" && tr -d "\r\n" < "$1"' sh "${marker_path}")" \
            || die "runtime version marker is unavailable: ${marker_path}"
    else
        actual_version="$(run_cli version 2>&1)"
        printf '%s' "${actual_version}" | grep -Fq "${expected_version}" \
            || die "host CLI version does not prove required runtime ${expected_version}"
        actual_version="${expected_version}"
    fi

    [ "${actual_version}" = "${expected_version}" ] \
        || die "runtime version mismatch: expected ${expected_version}, found ${actual_version}"
    log "Verified runtime version ${actual_version}"
}

detect_manager_workspace() {
    local detected=""
    local user_home_dir

    if [ -n "${MANAGER_WORKSPACE}" ]; then
        return
    fi

    if [ "${CLI_KIND}" = "container" ]; then
        detected="$(docker inspect --format '{{range .Mounts}}{{if eq .Destination "/root/manager-workspace"}}{{.Source}}{{end}}{{end}}' "${CLI_CONTAINER}")"
    fi

    if [ -n "${detected}" ]; then
        MANAGER_WORKSPACE="${detected}"
        return
    fi

    user_home_dir="$(cd && pwd)"
    if [ "${RUNTIME_FAMILY}" = "agentteams" ] && [ -d "${user_home_dir}/agentteams-manager" ]; then
        MANAGER_WORKSPACE="${user_home_dir}/agentteams-manager"
        return
    fi
    if [ "${RUNTIME_FAMILY}" = "hiclaw" ] && [ -d "${user_home_dir}/hiclaw-manager" ]; then
        MANAGER_WORKSPACE="${user_home_dir}/hiclaw-manager"
        return
    fi

    die "Manager workspace was not detected; pass --manager-workspace PATH"
}

validate_workspace_target() {
    local user_home_dir
    local canonical_workspace
    local detected_bind_source=""
    user_home_dir="$(cd && pwd)"

    [ -d "${MANAGER_WORKSPACE}" ] \
        || die "Manager workspace does not exist: ${MANAGER_WORKSPACE}"
    [ ! -L "${MANAGER_WORKSPACE}" ] \
        || die "Manager workspace must not be a symbolic link: ${MANAGER_WORKSPACE}"
    canonical_workspace="$(realpath "${MANAGER_WORKSPACE}")"
    case "${canonical_workspace}" in
        /|"${user_home_dir}")
            die "refusing to use a broad Manager workspace target: ${canonical_workspace}"
            ;;
    esac

    if [ "${CLI_KIND}" = "container" ]; then
        detected_bind_source="$(docker inspect --format '{{range .Mounts}}{{if eq .Destination "/root/manager-workspace"}}{{.Source}}{{end}}{{end}}' "${CLI_CONTAINER}")"
        [ -n "${detected_bind_source}" ] \
            || die "Manager container has no /root/manager-workspace bind mount"
        [ "$(realpath "${detected_bind_source}")" = "${canonical_workspace}" ] \
            || die "Manager workspace does not match the active container bind source"
    fi

    MANAGER_WORKSPACE="${canonical_workspace}"
}

validate_host_share_scope() {
    local canonical_evidence_root
    local canonical_host_share_root
    local canonical_host_share_source
    local cursor
    local find_match
    local host_share_source

    [ "${CLI_KIND}" = "container" ] || return
    host_share_source="$(docker inspect --format '{{range .Mounts}}{{if eq .Destination "/host-share"}}{{.Source}}{{end}}{{end}}' "${CLI_CONTAINER}")"
    [ -z "${host_share_source}" ] && return

    [ -n "${HOST_SHARE_ROOT}" ] \
        || die "Manager /host-share requires an explicit --host-share-root under ${PACKAGE_ROOT}/evidence"
    case "${HOST_SHARE_ROOT}" in
        /*)
            ;;
        *)
            die "--host-share-root must be an absolute path"
            ;;
    esac
    case "/${HOST_SHARE_ROOT#/}/" in
        */../*|*/./*)
            die "--host-share-root must not contain dot path segments"
            ;;
    esac

    [ -d "${HOST_SHARE_ROOT}" ] \
        || die "--host-share-root does not exist: ${HOST_SHARE_ROOT}"
    canonical_evidence_root="$(realpath "${PACKAGE_ROOT}/evidence")"
    canonical_host_share_root="$(realpath "${HOST_SHARE_ROOT}")"
    canonical_host_share_source="$(realpath "${host_share_source}")"
    case "${canonical_host_share_root}" in
        "${canonical_evidence_root}"/*)
            ;;
        *)
            die "--host-share-root must be a dedicated directory below ${canonical_evidence_root}"
            ;;
    esac
    [ "${canonical_host_share_source}" = "${canonical_host_share_root}" ] \
        || die "Manager /host-share does not match the exact --host-share-root"

    cursor="${HOST_SHARE_ROOT%/}"
    while [ "${cursor}" != "${canonical_evidence_root}" ]; do
        [ ! -L "${cursor}" ] \
            || die "Manager /host-share path must not contain symbolic links"
        cursor="$(dirname "${cursor}")"
        case "${cursor}" in
            "${canonical_evidence_root}"|"${canonical_evidence_root}"/*)
                ;;
            *)
                die "Manager /host-share path escaped the evidence boundary"
                ;;
        esac
    done
    if find_match="$(find "${canonical_host_share_root}" -type l -print -quit 2>/dev/null)"; then
        :
    else
        die "symbolic-link scan failed for Manager /host-share"
    fi
    if [ -n "${find_match}" ]; then
        die "Manager /host-share tree must not contain symbolic links"
    fi
    if find_match="$(find "${canonical_host_share_root}" \
        \( -name '.env' -o -name '.env.*' -o -name '.nubase' \
        -o -name 'application-dev.yml' -o -name 'application-local.yml' \
        -o -name '*.pem' -o -name '*.key' -o -name '*.p12' \
        -o -name '*.pfx' -o -name '*.jks' \) -print -quit 2>/dev/null)"; then
        :
    else
        die "sensitive-filename scan failed for Manager /host-share"
    fi
    if [ -n "${find_match}" ]; then
        die "Manager /host-share contains a forbidden sensitive filename"
    fi
    validate_no_sensitive_content "${canonical_host_share_root}" "Manager /host-share"
}

install_skills() {
    local skill_name
    local source_dir
    local target_dir
    local target_root="${MANAGER_WORKSPACE}/worker-skills"
    local container_target_dir
    local staged_dir
    local backup_dir

    mkdir -p "${target_root}"
    [ ! -L "${target_root}" ] || die "Worker Skill root must not be a symbolic link: ${target_root}"
    for skill_name in "${SKILL_NAMES[@]}"; do
        source_dir="${SKILLS_ROOT}/${skill_name}"
        target_dir="${target_root}/${skill_name}"
        [ ! -L "${source_dir}" ] || die "Skill source must not be a symbolic link: ${source_dir}"
        if find "${source_dir}" -type l -print -quit | grep -q .; then
            die "Skill source tree must not contain symbolic links: ${skill_name}"
        fi
        [ ! -L "${target_dir}" ] || die "Skill target must not be a symbolic link: ${target_dir}"

        staged_dir="$(mktemp -d "${target_root}/.${skill_name}.staged.XXXXXX")"
        backup_dir="${target_root}/.${skill_name}.previous.$$"
        cp -R "${source_dir}/." "${staged_dir}/"
        [ -s "${staged_dir}/SKILL.md" ] || die "staged Skill is incomplete: ${skill_name}"
        if [ -e "${target_dir}" ]; then
            [ ! -e "${backup_dir}" ] || die "unexpected Skill backup path exists: ${backup_dir}"
            mv "${target_dir}" "${backup_dir}"
        fi
        mv "${staged_dir}" "${target_dir}"
        if [ -d "${backup_dir}" ]; then
            rm -rf -- "${backup_dir}"
        fi
        log "Installed Skill ${skill_name} into ${target_dir}"

        if [ "${RUNTIME_FAMILY}" = "hiclaw" ] && [ "${CLI_KIND}" = "container" ]; then
            container_target_dir="/opt/hiclaw/agent/worker-skills/${skill_name}"
            docker exec "${CLI_CONTAINER}" test ! -L "${container_target_dir}" \
                || die "Manager Skill target must not be a symbolic link: ${container_target_dir}"
            docker exec "${CLI_CONTAINER}" rm -rf -- "${container_target_dir}"
            docker exec "${CLI_CONTAINER}" mkdir -p "${container_target_dir}"
            docker cp "${source_dir}/." "${CLI_CONTAINER}:${container_target_dir}/" >/dev/null
            docker exec "${CLI_CONTAINER}" test -s "${container_target_dir}/SKILL.md" \
                || die "Manager Skill staging verification failed: ${skill_name}"
        fi
    done
}

apply_file() {
    local source_path="$1"
    local staged_name="$2"
    local container_path

    if [ "${CLI_KIND}" = "container" ]; then
        container_path="/tmp/goai-agent-delivery/${staged_name}"
        docker exec "${CLI_CONTAINER}" mkdir -p /tmp/goai-agent-delivery
        docker cp "${source_path}" "${CLI_CONTAINER}:${container_path}"
        run_cli apply -f "${container_path}"
    else
        run_cli apply -f "${source_path}"
    fi
}

apply_agentteams() {
    apply_file "${AGENTTEAMS_ROOT}/workers/delivery-lead/worker.yaml" delivery-lead-worker.yaml
    apply_file "${AGENTTEAMS_ROOT}/workers/builder-agent/worker.yaml" builder-agent-worker.yaml
    apply_file "${AGENTTEAMS_ROOT}/workers/verifier-agent/worker.yaml" verifier-agent-worker.yaml
    apply_file "${AGENTTEAMS_ROOT}/workers/release-governor/worker.yaml" release-governor-worker.yaml
    apply_file "${AGENTTEAMS_ROOT}/team.yaml" team.yaml
}

assign_hiclaw_skills() {
    local helper_path="/opt/hiclaw/agent/skills/worker-management/scripts/push-worker-skills.sh"
    local registry_path="/root/manager-workspace/workers-registry.json"
    local attempt
    local registry_ready="false"

    if [ "${CLI_KIND}" != "container" ]; then
        die "HiClaw v1.1.2 Skill persistence requires a container-managed compatibility runtime"
    fi

    if ! docker exec "${CLI_CONTAINER}" test -f "${helper_path}"; then
        die "HiClaw Skill assignment helper is unavailable: ${helper_path}"
    fi

    for attempt in $(seq 1 15); do
        if docker exec "${CLI_CONTAINER}" jq -e '
          .workers["nubase-delivery-lead"] and
          .workers["nubase-builder"] and
          .workers["nubase-verifier"] and
          .workers["nubase-release-governor"]
        ' "${registry_path}" >/dev/null 2>&1; then
            registry_ready="true"
            break
        fi
        sleep 2
    done

    [ "${registry_ready}" = "true" ] \
        || die "HiClaw Workers did not appear in the Manager registry before the Skill assignment deadline"

    assign_hiclaw_worker_skill nubase-delivery-lead app-plan "${helper_path}" "${registry_path}"
    assign_hiclaw_worker_skill nubase-builder app-build "${helper_path}" "${registry_path}"
    assign_hiclaw_worker_skill nubase-verifier release-verify "${helper_path}" "${registry_path}"
    assign_hiclaw_worker_skill nubase-release-governor release-govern "${helper_path}" "${registry_path}"
}

assign_hiclaw_worker_skill() {
    local worker_name="$1"
    local skill_name="$2"
    local helper_path="$3"
    local registry_path="$4"

    docker exec "${CLI_CONTAINER}" bash "${helper_path}" \
        --worker "${worker_name}" --add-skill "${skill_name}" --no-notify >/dev/null 2>&1 \
        || die "HiClaw Manager failed to assign Skill ${skill_name} to ${worker_name}"
    if [ "${worker_name}" != "nubase-delivery-lead" ]; then
        docker exec "${CLI_CONTAINER}" jq -e \
            --arg worker "${worker_name}" --arg skill "${skill_name}" \
            '.workers[$worker].skills | index($skill) != null' \
            "${registry_path}" >/dev/null \
            || die "HiClaw Manager did not persist Skill assignment: ${worker_name}/${skill_name}"
    fi

    persist_hiclaw_worker_skill "${worker_name}" "${skill_name}"
}

hiclaw_worker_tree_digest() {
    local worker_container="$1"
    local tree_root="$2"
    local digest_output
    local digest

    digest_output="$(docker exec "${worker_container}" sh -c '
      set -eu
      tree_root="$1"
      test -d "$tree_root"
      test ! -L "$tree_root"
      file_list="$(mktemp /tmp/goai-tree-files.XXXXXX)"
      manifest="$(mktemp /tmp/goai-tree-manifest.XXXXXX)"
      trap '\''rm -f -- "$file_list" "$manifest"'\'' EXIT
      cd "$tree_root"
      link_match="$(find . -type l -print -quit)"
      test -z "$link_match"
      find . -type f -print >"$file_list"
      LC_ALL=C sort -o "$file_list" "$file_list"
      : >"$manifest"
      while IFS= read -r file_path; do
        test -r "$file_path"
        sha256sum "$file_path" >>"$manifest"
      done <"$file_list"
      sha256sum "$manifest"
    ' goai-tree-digest "${tree_root}" 2>/dev/null)" \
        || return 1
    digest="${digest_output%% *}"
    [[ "${digest}" =~ ^[a-f0-9]{64}$ ]] || return 1
    printf '%s\n' "${digest}"
}

persist_hiclaw_worker_skill() {
    local worker_name="$1"
    local skill_name="$2"
    local worker_container="hiclaw-worker-${worker_name}"
    local worker_home
    local canonical_worker_home
    local expected_worker_home
    local target_dir
    local staged_root
    local staged_skill
    local persisted_root
    local source_digest
    local persisted_digest
    local runtime_digest
    local attempt
    local runtime_ready="false"

    docker inspect "${worker_container}" >/dev/null 2>&1 \
        || die "HiClaw Worker container is not ready: ${worker_container}"
    expected_worker_home="/root/hiclaw-fs/agents/${worker_name}"
    worker_home="$(docker exec "${worker_container}" sh -c 'printf %s "$HOME"')"
    [ "${worker_home}" = "${expected_worker_home}" ] \
        || die "Refusing unexpected HiClaw Worker home: ${worker_home}"
    canonical_worker_home="$(docker exec "${worker_container}" sh -c 'readlink -f "$HOME"')"
    [ "${canonical_worker_home}" = "${expected_worker_home}" ] \
        || die "Refusing non-canonical HiClaw Worker home: ${worker_home}"
    docker exec "${worker_container}" test -d "${worker_home}" \
        || die "HiClaw Worker home is unavailable: ${worker_name}"
    docker exec "${worker_container}" test ! -L "${worker_home}" \
        || die "HiClaw Worker home must not be a symbolic link: ${worker_name}"
    docker exec "${worker_container}" test ! -L "${worker_home}/skills" \
        || die "HiClaw Worker Skill root must not be a symbolic link: ${worker_name}"
    docker exec "${worker_container}" mkdir -p "${worker_home}/skills"
    docker exec "${worker_container}" test -d "${worker_home}/skills" \
        || die "HiClaw Worker Skill root is unavailable: ${worker_name}"
    docker exec "${worker_container}" sh -c '[ "$HICLAW_WORKER_NAME" = "$1" ]' sh "${worker_name}" \
        || die "HiClaw Worker storage identity mismatch: ${worker_name}"

    staged_root="$(docker exec "${worker_container}" mktemp -d /tmp/goai-agent-skill.XXXXXX)"
    case "${staged_root}" in
        /tmp/goai-agent-skill.*)
            ;;
        *)
            die "Refusing unexpected Worker staging path: ${staged_root}"
            ;;
    esac
    staged_skill="${staged_root}/${skill_name}"
    docker exec "${worker_container}" mkdir -p "${staged_skill}"
    docker cp "${SKILLS_ROOT}/${skill_name}/." "${worker_container}:${staged_skill}/" >/dev/null

    source_digest="$(hiclaw_worker_tree_digest "${worker_container}" "${staged_skill}")" \
        || die "HiClaw staged Skill tree verification failed: ${worker_name}/${skill_name}"
    docker exec "${worker_container}" sh -c '
      mc mirror "$1/" "$HICLAW_STORAGE_PREFIX/agents/$HICLAW_WORKER_NAME/skills/$2/" --remove --overwrite >/dev/null &&
      mc stat "$HICLAW_STORAGE_PREFIX/agents/$HICLAW_WORKER_NAME/skills/$2/SKILL.md" >/dev/null
    ' sh "${staged_skill}" "${skill_name}" \
        || die "HiClaw persistent Skill upload failed: ${worker_name}/${skill_name}"
    persisted_root="$(docker exec "${worker_container}" mktemp -d /tmp/goai-agent-persisted.XXXXXX)"
    case "${persisted_root}" in
        /tmp/goai-agent-persisted.*)
            ;;
        *)
            die "Refusing unexpected persisted Skill verification path: ${persisted_root}"
            ;;
    esac
    docker exec "${worker_container}" sh -c '
      mc mirror "$HICLAW_STORAGE_PREFIX/agents/$HICLAW_WORKER_NAME/skills/$1/" "$2/" --remove --overwrite >/dev/null
    ' sh "${skill_name}" "${persisted_root}" \
        || die "HiClaw persistent Skill download failed: ${worker_name}/${skill_name}"
    persisted_digest="$(hiclaw_worker_tree_digest "${worker_container}" "${persisted_root}")" \
        || die "HiClaw persistent Skill tree verification failed: ${worker_name}/${skill_name}"
    [ "${persisted_digest}" = "${source_digest}" ] \
        || die "HiClaw persistent Skill tree digest mismatch: ${worker_name}/${skill_name}"

    target_dir="${worker_home}/skills/${skill_name}"
    docker exec "${worker_container}" test ! -L "${target_dir}" \
        || die "Worker Skill target must not be a symbolic link: ${worker_name}/${skill_name}"
    docker exec "${worker_container}" rm -rf -- "${target_dir}"
    docker exec "${worker_container}" mkdir -p "${target_dir}"
    docker cp "${SKILLS_ROOT}/${skill_name}/." "${worker_container}:${target_dir}/" >/dev/null
    docker exec "${worker_container}" rm -rf -- "${staged_root}" "${persisted_root}"

    for attempt in $(seq 1 15); do
        if docker exec "${worker_container}" test -s "${target_dir}/SKILL.md"; then
            if runtime_digest="$(hiclaw_worker_tree_digest "${worker_container}" "${target_dir}")" \
                && [ "${runtime_digest}" = "${source_digest}" ]; then
                runtime_ready="true"
                break
            fi
        fi
        sleep 2
    done
    [ "${runtime_ready}" = "true" ] \
        || die "HiClaw runtime Skill verification failed: ${worker_name}/${skill_name}"
    log "Persisted and verified runtime Skill ${skill_name} for ${worker_name}"
}

apply_hiclaw() {
    apply_file "${HICLAW_ROOT}/team.yaml" team.yaml
    assign_hiclaw_skills
}

main() {
    log "Validating contest package"
    validate_required_files
    validate_markdown_contracts
    validate_secret_hygiene
    validate_yaml_contracts
    validate_mcp_policy
    detect_cli
    validate_runtime_version

    log "Detected ${RUNTIME_FAMILY} via ${CLI_KIND} CLI ${CLI_BINARY}"

    if [ "${APPLY}" = false ]; then
        log "Validation passed; no files were copied and no resources were applied"
        log "Run with --apply only after the three least-privilege Nubase MCP gateway endpoints are configured"
        log "Manifest declarations do not prove MCP route existence or consumer authorization"
        return
    fi

    detect_manager_workspace
    validate_workspace_target
    validate_host_share_scope
    install_skills

    if [ "${RUNTIME_FAMILY}" = "agentteams" ]; then
        apply_agentteams
    else
        apply_hiclaw
    fi

    log "Resource apply completed for ${RUNTIME_FAMILY}"
    warn "MCP route existence and consumer authorization were not verified; do not claim tool readiness yet"
    log "Verify Worker readiness, exact MCP allow/deny policy, consumer authorization, and Team status before assigning a task"
}

if [ "${GOAI_INSTALLER_SOURCE_ONLY:-false}" != "true" ]; then
    main
fi
