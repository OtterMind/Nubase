#!/usr/bin/env bash

set -euo pipefail

TEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "${TEST_DIR}/../.." && pwd)"
CALL_LOG="$(mktemp)"
SCAN_TEST_DIR="$(mktemp -d "${REPO_DIR}/examples/goai-agent-delivery/evidence/.installer-scan.XXXXXX")"
FAIL_HELPER_MISSING=false
FAIL_ASSIGN_WORKER=""
FAIL_PERSIST_WORKER=""
HOST_SHARE_SOURCE=""
WORKER_HOME_OVERRIDE=""
WORKER_SKILLS_SYMLINK=false
WORKER_IDENTITY_MISMATCH=false
PERSISTED_TREE_MISMATCH=false

cleanup() {
    rm -f -- "${CALL_LOG}"
    rm -rf -- "${SCAN_TEST_DIR}"
}
trap cleanup EXIT

export GOAI_INSTALLER_SOURCE_ONLY=true
set --
# shellcheck source=script/goai/install-agentteams.sh
source "${TEST_DIR}/install-agentteams.sh"

CLI_KIND="container"
CLI_CONTAINER="hiclaw-manager"
RUNTIME_FAMILY="hiclaw"
SKILLS_ROOT="${REPO_DIR}/examples/goai-agent-delivery/skills"

docker() {
    printf '%s\n' "$*" >> "${CALL_LOG}"

    if [ -n "${HOST_SHARE_SOURCE}" ] && [ "${1:-}" = "inspect" ]; then
        case "$*" in
            */host-share*)
                printf '%s\n' "${HOST_SHARE_SOURCE}"
                return 0
                ;;
        esac
    fi

    if [ "${FAIL_HELPER_MISSING}" = "true" ] && [ "${1:-}" = "exec" ] \
        && [ "${2:-}" = "hiclaw-manager" ] && [ "${3:-}" = "test" ] \
        && [ "${4:-}" = "-f" ]; then
        return 1
    fi
    if [ -n "${FAIL_ASSIGN_WORKER}" ] && [ "${1:-}" = "exec" ] \
        && [ "${2:-}" = "hiclaw-manager" ] && [ "${3:-}" = "bash" ] \
        && printf '%s\n' "$*" | grep -Fq -- "--worker ${FAIL_ASSIGN_WORKER}"; then
        return 1
    fi
    if [ -n "${FAIL_PERSIST_WORKER}" ] && [ "${1:-}" = "exec" ] \
        && [ "${2:-}" = "hiclaw-worker-${FAIL_PERSIST_WORKER}" ] \
        && [ "${3:-}" = "sh" ] && [ "${4:-}" = "-c" ] \
        && printf '%s\n' "${5:-}" | grep -Fq 'mc mirror'; then
        return 1
    fi
    if [ "${WORKER_IDENTITY_MISMATCH}" = "true" ] \
        && [ "${1:-}" = "exec" ] && [[ "${2:-}" == hiclaw-worker-* ]] \
        && [ "${3:-}" = "sh" ] && [ "${4:-}" = "-c" ] \
        && printf '%s\n' "${5:-}" | grep -Fq 'HICLAW_WORKER_NAME'; then
        return 1
    fi

    if [ "${1:-}" = "exec" ] && [[ "${2:-}" == hiclaw-worker-* ]] \
        && [ "${3:-}" = "sh" ] && [ "${4:-}" = "-c" ] \
        && printf '%s\n' "${5:-}" | grep -Fq 'printf %s "$HOME"'; then
        if [ -n "${WORKER_HOME_OVERRIDE}" ]; then
            printf '%s\n' "${WORKER_HOME_OVERRIDE}"
            return 0
        fi
        printf '/root/hiclaw-fs/agents/%s\n' "${2#hiclaw-worker-}"
        return 0
    fi
    if [ "${1:-}" = "exec" ] && [[ "${2:-}" == hiclaw-worker-* ]] \
        && [ "${3:-}" = "sh" ] && [ "${4:-}" = "-c" ] \
        && printf '%s\n' "${5:-}" | grep -Fq 'readlink -f "$HOME"'; then
        if [ -n "${WORKER_HOME_OVERRIDE}" ]; then
            printf '%s\n' "${WORKER_HOME_OVERRIDE}"
            return 0
        fi
        printf '/root/hiclaw-fs/agents/%s\n' "${2#hiclaw-worker-}"
        return 0
    fi
    if [ "${WORKER_SKILLS_SYMLINK}" = "true" ] \
        && [ "${1:-}" = "exec" ] && [[ "${2:-}" == hiclaw-worker-* ]] \
        && [ "${3:-}" = "test" ] && [ "${4:-}" = "!" ] \
        && [ "${5:-}" = "-L" ] && [[ "${6:-}" == */skills ]]; then
        return 1
    fi
    if [ "${1:-}" = "exec" ] && [[ "${2:-}" == hiclaw-worker-* ]] \
        && [ "${3:-}" = "mktemp" ]; then
        case "${5:-}" in
            /tmp/goai-agent-persisted.*)
                printf '/tmp/goai-agent-persisted.test\n'
                ;;
            *)
                printf '/tmp/goai-agent-skill.test\n'
                ;;
        esac
        return 0
    fi
    if [ "${1:-}" = "exec" ] && [[ "${2:-}" == hiclaw-worker-* ]] \
        && [ "${3:-}" = "sh" ] && [ "${4:-}" = "-c" ] \
        && [ "${6:-}" = "goai-tree-digest" ]; then
        if [ "${PERSISTED_TREE_MISMATCH}" = "true" ] \
            && [[ "${7:-}" == /tmp/goai-agent-persisted.* ]]; then
            printf '%064d  -\n' 1
        else
            printf '%064d  -\n' 0
        fi
        return 0
    fi
    if [ "${1:-}" = "exec" ] && [[ "${2:-}" == hiclaw-worker-* ]] \
        && [ "${3:-}" = "sha256sum" ]; then
        local skill_path="${4:-}"
        local skill_name
        skill_name="$(basename "$(dirname "${skill_path}")")"
        shasum -a 256 "${SKILLS_ROOT}/${skill_name}/SKILL.md" | awk -v path="${skill_path}" '{print $1 "  " path}'
        return 0
    fi

    return 0
}

sleep() {
    return 0
}

assign_hiclaw_skills

helper_calls="$(grep -c 'push-worker-skills.sh --worker' "${CALL_LOG}")"
[ "${helper_calls}" -eq 4 ] || die "expected four persistent helper assignments, found ${helper_calls}"
for pair in \
    'nubase-delivery-lead app-plan' \
    'nubase-builder app-build' \
    'nubase-verifier release-verify' \
    'nubase-release-governor release-govern'; do
    grep -Fq -- "--worker ${pair% *} --add-skill ${pair#* } --no-notify" "${CALL_LOG}" \
        || die "missing helper assignment for ${pair}"
done

FAIL_HELPER_MISSING=true
if (assign_hiclaw_skills >/dev/null 2>&1); then
    die "missing helper must fail closed"
fi
FAIL_HELPER_MISSING=false

FAIL_ASSIGN_WORKER="nubase-builder"
if (assign_hiclaw_worker_skill nubase-builder app-build /helper /registry >/dev/null 2>&1); then
    die "failed assignment must fail closed"
fi
FAIL_ASSIGN_WORKER=""

FAIL_PERSIST_WORKER="nubase-verifier"
if (persist_hiclaw_worker_skill nubase-verifier release-verify >/dev/null 2>&1); then
    die "failed persistent upload must fail closed"
fi
FAIL_PERSIST_WORKER=""

WORKER_IDENTITY_MISMATCH=true
if (persist_hiclaw_worker_skill nubase-verifier release-verify >/dev/null 2>&1); then
    die "mismatched Worker storage identity must fail closed"
fi
WORKER_IDENTITY_MISMATCH=false

PERSISTED_TREE_MISMATCH=true
if (persist_hiclaw_worker_skill nubase-verifier release-verify >/dev/null 2>&1); then
    die "stale persistent Skill tree must fail closed"
fi
PERSISTED_TREE_MISMATCH=false
grep -Fq -- '--remove --overwrite' "${CALL_LOG}" \
    || die "persistent Skill mirror must remove stale remote objects"

HOST_SHARE_SOURCE="$(dirname "${REPO_DIR}")"
HOST_SHARE_ROOT="${HOST_SHARE_SOURCE}"
if (validate_host_share_scope >/dev/null 2>&1); then
    die "broad host-share mount must fail closed"
fi
HOST_SHARE_SOURCE=""
HOST_SHARE_ROOT=""

HOST_SHARE_SOURCE="${SCAN_TEST_DIR}"
HOST_SHARE_ROOT="${SCAN_TEST_DIR}"
if (find() { return 2; }; validate_host_share_scope >/dev/null 2>&1); then
    die "host-share find scan failure must fail closed"
fi
if (grep() { return 2; }; validate_host_share_scope >/dev/null 2>&1); then
    die "host-share content scan failure must fail closed"
fi

printf '%s\n' 'task-local-closure-20260811t025459z-9e90db' > "${SCAN_TEST_DIR}/task-id.txt"
validate_host_share_scope >/dev/null 2>&1 \
    || die "ordinary task identifiers must not match the secret scanner"
printf '%s\n' 'sk-123456789012345678901234' > "${SCAN_TEST_DIR}/secret.txt"
if (validate_host_share_scope >/dev/null 2>&1); then
    die "standalone secret-shaped values must fail closed"
fi
rm -f -- "${SCAN_TEST_DIR}/task-id.txt" "${SCAN_TEST_DIR}/secret.txt"

HOST_SHARE_SOURCE=""
HOST_SHARE_ROOT=""

if (grep() { return 2; }; validate_secret_hygiene >/dev/null 2>&1); then
    die "contest package scan failure must fail closed"
fi

WORKER_HOME_OVERRIDE='/root/hiclaw-fs/agents/../etc'
if (persist_hiclaw_worker_skill nubase-verifier release-verify >/dev/null 2>&1); then
    die "non-canonical Worker home must fail closed"
fi
WORKER_HOME_OVERRIDE=""

WORKER_SKILLS_SYMLINK=true
if (persist_hiclaw_worker_skill nubase-verifier release-verify >/dev/null 2>&1); then
    die "symbolic-link Worker Skill root must fail closed"
fi
WORKER_SKILLS_SYMLINK=false

printf '%s\n' 'AgentTeams installer semantic tests passed.'
