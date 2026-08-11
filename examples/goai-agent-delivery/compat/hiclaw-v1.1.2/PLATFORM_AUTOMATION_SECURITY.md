# Platform Automation 本地安全配置

本文档只描述本地 AgentTeams/HiClaw 演示的 Platform MCP operator 流程。签名密钥始终保留在 Nubase 后端；operator helper 不接受 JWT signing secret，也不会把 Metadata root bearer、automation JWT 或 Console cookie 放入命令参数、环境变量、日志、Worker workspace、`/host-share` 或 Git 文件。

## 前置条件

- Nubase 本机端口固定为 `127.0.0.1:9999`，并显式启用 `/platform/mcp` 与 automation grant admin API。
- Nubase 后端已配置独立的 Platform MCP JWT secret。该 secret 不得复制到 operator 主机文件、Controller 或 Worker。
- Metadata root bearer 属于 `SYSTEM_USER_ID`。普通 Platform super-admin JWT 会被后端拒绝，不能代替 Metadata root bearer。
- HiClaw Controller 名称固定为 `hiclaw-controller`，Console cookie 是 owner-only `0600` Mozilla cookie-jar 文件。
- grant state 的父目录必须位于仓库外，owner-only `0700`，也不能位于任何 Worker 可见的 host-share。
- 当前策略必须精确包含下面两条独立 Platform route；它们不属于 tenant Java 的 44-tool inventory。

| Policy name | Console `mcpServerName` | Consumer | Exact tools |
| --- | --- | --- | --- |
| `project-build` | `mcp-project-build` | `worker-nubase-builder` | `platformProjectCreate`, `platformProjectProvision`, `platformProjectStatus` |
| `project-read` | `mcp-project-read` | `worker-nubase-delivery-lead`, `worker-nubase-verifier` | `platformProjectStatus` |

Release Governor 不绑定任何 Platform route。Worker 只看到 `mcp-project-build` 或 `mcp-project-read` 的网关 URL，看不到上游 automation JWT。

## 静态证据边界

Verifier 必须从 `project-read` 的脱敏 status 中逐项核对 `verificationLevel=STATIC_CONTROL_PLANE`、`state=PROVISIONED`、`readiness.gateway=true` 与 `advertisedEndpoints.gateway` 存在；Governor 在没有 Platform route 的前提下复核同一组证据。只有这些字段与 trace 契约全部通过时才能输出 `PROVISIONED`，否则输出 `BLOCKED`。证据只能保存 endpoint 是否存在，不能保存 endpoint 值或原始响应。

`PROVISIONED` 只证明 schema/RLS、默认 key 注册与 gateway catalog 等静态控制面 provisioning。它不证明 Functions/MCP 外部可达、模型 upstream HTTP/计费调用、应用部署或生产可用。

## 认证契约

operator helper 调用固定的本机 admin API：

- `POST /auth/v1/admin/platform/automation-grants`：以 `scope=build` 创建一个 run-specific actor/grant，并取得 build token；
- `POST /auth/v1/admin/platform/automation-grants/{grantId}/tokens`：从同一个 grant mint `scope=read` 的 read token；
- `DELETE /auth/v1/admin/platform/automation-grants/{grantId}`：撤销 grant 并提升 token version。

build/read 必须来自同一个 `actor` 与 `grant_id`。这是 Builder 创建的 Project 能被 Delivery Lead 和 Verifier 查询的 ownership 边界；不能为两个 route 创建两个 actor 或两个 grant。

自然语言中的项目名只作为 `appName`/`displayName`。local/staging 的 `projectRef` 必须按确定性规则派生：ASCII 项目名转小写，连续非字母数字字符替换为 `_`，去掉首尾 `_`，若尚无 `goai_` 前缀则补上；结果必须满足后端语法且不超过 40 个字符。空结果、非 ASCII、过长或已存在冲突都必须进入 `BLOCKED`，禁止截断、改用随机 ref 或换参数重试。例如 `psx_agent_teams_project` 的 ref 固定为 `goai_psx_agent_teams_project`，因此 grant prefix 继续固定为 `goai_`。

两个 token 均须满足以下 fail-closed 契约：

- `alg=HS256`、`typ=JWT`、`kid=platform-mcp-v1`；
- `iss=nubase-platform`、唯一 audience 为 `nubase-agentteams-provisioning`；
- `role=platform_automation`、`actor_type=automation`；
- 必须包含 `sub`、`grant_id`、`token_version`、`approval_binding`、`jti`、`iat`、`nbf`、`exp`；build/read 的 `sub`、`grant_id`、`token_version` 与 `approval_binding` 必须完全一致；
- build scope 精确为 `project:create project:provision project:status`，read scope 精确为 `project:status`；
- TTL 不超过 600 秒，注入时至少剩余 60 秒；
- 只允许后端定义的 claims/header，拒绝重复 JSON key、额外 privilege claim、数组形式 scope 或共享同一 token。

operator 只验证 JWT 结构与 claims；签名和当前 grant 状态由 Nubase 后端验证。

已进入 durable outbox 但尚未执行的 provision 仍受 grant 生命周期约束。grant 被撤销、过期、替换 token version，或 create lineage 无法精确匹配时，后端必须把该 operation 终止为固定安全错误且不得 dispatch；operator 不能把 revoke 描述成仅阻止新请求。

## 首次 bootstrap

先关闭 shell trace。credential 环境变量只保存文件路径，不保存 credential value；`GOAI_APPROVAL_ID` 是可审计的非秘密 run context。bearer 文件中只能有原始 token，不要写 `Bearer ` 前缀或尾随换行。

```bash
set +x

GOAI_OPERATOR_STATE_DIR="${XDG_STATE_HOME:-$HOME/.local/state}/nubase-goai"
GOAI_GRANT_STATE_FILE="${GOAI_OPERATOR_STATE_DIR}/platform-automation-grant.json"
install -d -m 700 "${GOAI_OPERATOR_STATE_DIR}"
: "${GOAI_APPROVAL_ID:?set GOAI_APPROVAL_ID to the reviewed run approval ID}"

chmod 600 "${NUBASE_METADATA_ROOT_FILE}"
chmod 600 "${HICLAW_CONSOLE_COOKIE_FILE}"

exec 3<"${NUBASE_METADATA_ROOT_FILE}"
exec 4<"${HICLAW_CONSOLE_COOKIE_FILE}"
cleanup_fds() {
  exec 3<&-
  exec 4<&-
}
trap cleanup_fds EXIT HUP INT TERM

PYTHONDONTWRITEBYTECODE=1 python3 \
  script/goai/bootstrap-platform-automation-routes.py bootstrap \
  --metadata-root-fd 3 \
  --console-session-fd 4 \
  --grant-state-file "${GOAI_GRANT_STATE_FILE}" \
  --allowed-ref-prefix goai_ \
  --approval-binding "${GOAI_APPROVAL_ID}" \
  --max-projects 1 \
  --grant-ttl-seconds 3600 \
  --token-ttl-seconds 600
```

helper 会自动生成 run-specific actor，先创建一个 build grant，再从同一个 grant mint read token。admin 响应中的 token 仅存在于内存和随机 `0700` 目录下的两个 `0600` 临时文件；route 配置完成或失败后都会清理。仓库外的 grant state 只保存 grant ID、actor、ref prefix、limit 和 TTL 等非秘密审计数据，不保存 token。

Higress 更新采用以下顺序：

1. 对已存在 route 先把 `allowTools` 收缩为空；新 route 以空 tools、空 consumers upsert。
2. consumer 集合替换为上表的精确集合。
3. build/read JWT 分别注入 Controller 内对应 route 的 `Authorization: Bearer ...` upstream credential。
4. 最后才开放精确的 3/1 tool inventory，并重新读取 Console 状态验证。

helper 成功只证明 Console 控制面已保存并回读目标配置，不证明 Higress 数据面或 Worker 已发现工具。分配任务前仍必须从对应 Worker 的 `mcporter` 视角验证 `project-build` 精确为 3 个工具、`project-read` 精确为 1 个工具；验证只能保留 route 状态和工具名，禁止输出 config、header 或原始响应。

任何失败都会尝试把两条 route 的 tools 与 consumers 收缩为空，再撤销新 grant。若 grant create 的网络结果不确定，state 会保留 `status=pending` 和 run-specific actor；此时必须先在 Metadata DB 审计该 actor，不能删除 state 后盲目重跑。

## Token rotation

automation JWT 最长只有 10 分钟。进入剩余 60 秒门限前，使用同一个 grant state mint 新的 build/read token：

```bash
set +x

exec 3<"${NUBASE_METADATA_ROOT_FILE}"
exec 4<"${HICLAW_CONSOLE_COOKIE_FILE}"
cleanup_fds() {
  exec 3<&-
  exec 4<&-
}
trap cleanup_fds EXIT HUP INT TERM

PYTHONDONTWRITEBYTECODE=1 python3 \
  script/goai/bootstrap-platform-automation-routes.py rotate \
  --metadata-root-fd 3 \
  --console-session-fd 4 \
  --grant-state-file "${GOAI_GRANT_STATE_FILE}" \
  --token-ttl-seconds 600
```

rotation 任一步失败都会收空 tools/consumers。若 Console containment 也失败，helper 会撤销 grant，使 Higress 中可能残留的 JWT 立即失效，并删除本地 state，要求重新 bootstrap。

## Revoke

演示结束后必须同时清除 route 授权并撤销 grant：

```bash
set +x

exec 3<"${NUBASE_METADATA_ROOT_FILE}"
exec 4<"${HICLAW_CONSOLE_COOKIE_FILE}"
cleanup_fds() {
  exec 3<&-
  exec 4<&-
}
trap cleanup_fds EXIT HUP INT TERM

PYTHONDONTWRITEBYTECODE=1 python3 \
  script/goai/bootstrap-platform-automation-routes.py revoke \
  --metadata-root-fd 3 \
  --console-session-fd 4 \
  --grant-state-file "${GOAI_GRANT_STATE_FILE}"
```

revoke 先尝试清空两条 Platform route 的 tools/consumers，再调用后端撤销 grant，最后删除本地 state。即使 Console 暂时不可用，后端 revoke 仍会执行；命令会以固定错误码报告 containment 未完成，且旧 token 已因 grant version 变化而不可用。

## 直接 route helper 契约

一般只应使用上面的 lifecycle helper。需要审计或排障时，底层 route wrapper 的非秘密参数契约如下：

- endpoint 固定为 `http://host.docker.internal:9999/platform/mcp`；
- policy name 固定为 `project-build` / `project-read`；
- Console entity 固定为 `mcp-project-build` / `mcp-project-read`；
- token 只能通过 owner-only `0600` regular file 或预打开 FD 提供；
- wrapper 只把 cookie/token 经 `docker exec -i` stdin 写入 Controller 的 `0600` 临时文件；
- wrapper 不向任何 Worker 执行命令，也不写 Worker environment、mcporter config 或 host-share；
- Controller lock 已存在或任一固定临时文件已存在时会失败关闭，不删除其他 operator 的文件；
- success、failure、signal 和 containment 路径都会清理 Controller cookie、JWT、policy/helper staging files 与 host token files。

不要保存或展示 Console `rawConfigurations`，因为本地演示的 `defaultCredential` 包含短期 bearer。生产环境必须改用经验证的 secret-reference 机制，不能把本地持久 credential 方案直接用于生产。

## 静态验证

以下命令不读取 credential，也不操作运行中的容器：

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest \
  script/goai/test_bootstrap_platform_automation_routes.py \
  script/goai/test_configure_platform_automation_routes.py \
  script/goai/test_refresh_higress_mcp_policy.py

git diff --check
```
