# AgentTeams Project Bootstrap Implementation Plan

> **状态说明：** Task 1–5 的源码与静态契约已经实现并通过聚焦测试；Worker 实际 `tools/list` 验证、Task 6 的闭环脚本与真实本地验收仍是待办。本文是实施计划，不是运行时完成证明。

**Goal:** 让用户在 AgentTeams Manager 中只发送一句项目创建意图，即可安全完成 Nubase Project 配置、异步数据库 Provision、默认 AI Gateway 初始化与独立就绪验证。

**Architecture:** 使用独立且默认关闭的 `/platform/mcp` 控制面，不复用浏览器点击或租户 `/mcp`。Builder 只能调用 create/provision/status，Verifier 只能调用 status；后端以 automation grant、Project ownership、数据库幂等账本和异步恢复保证最小权限与可重放性。

**Tech Stack:** Java 17, Spring Boot 3.2, Spring AI MCP, PostgreSQL, Flyway, Python 3, AgentTeams v1.2.2, HiClaw v1.1.2, Higress

---

### Task 1: 固定一句话 Bootstrap 契约

**Files:**
- Modify: `examples/goai-agent-delivery/skills/app-plan/SKILL.md`
- Modify: `examples/goai-agent-delivery/agentteams-v1.2.2/README.md`
- Test: `examples/goai-agent-delivery/scripts/test-validator.mjs`

**Step 1:** 固定 `project-bootstrap-v1` 只接受项目名、local/staging 目标和 `gatewayMode=platform-default`。

**Step 2:** 明确 `psx_agent_teams_project` 作为 display name，并按 local/staging 的确定性规则派生 Project ref `goai_psx_agent_teams_project`；operator grant 固定使用 `goai_` ref prefix。空结果、非 ASCII、过长或冲突必须进入 `BLOCKED`，禁止截断、随机改名或换参数重试。

**Step 3:** 运行契约验证。

```bash
node examples/goai-agent-delivery/scripts/test-validator.mjs
node examples/goai-agent-delivery/scripts/validate-package.mjs
```

**Step 4:** 仅在用户确认提交范围后创建独立提交。

### Task 2: 原子创建 Project 配置

**Files:**
- Modify: `src/main/java/ai/nubase/postgrest/multidb/DatabaseConfigRepository.java`
- Modify: `src/main/java/ai/nubase/auth/service/DatabaseInitService.java`
- Modify: `src/main/java/ai/nubase/platform/mcp/PlatformProjectAutomationFacade.java`
- Test: `src/test/java/ai/nubase/platform/mcp/PlatformProjectAutomationFacadeTest.java`

**Step 1:** 增加 create-only repository 操作，使用数据库唯一约束保证同一 ref 只有一个创建者。

```java
boolean insertIfAbsent(DatabaseConfig config);
```

**Step 2:** Platform MCP 冲突时返回固定 `PROJECT_REF_EXISTS`，不得进入通用 upsert 或覆盖已有凭据与 ownership。

**Step 3:** 增加不同 actor 同 ref 的并发/冲突回归测试。

**Step 4:** 运行聚焦测试。

```bash
mvn -q -Dtest='PlatformProjectAutomationFacadeTest,DatabaseConfigRepositoryCreateOnlyTest,DatabaseInitServiceClaimTest' test
```

### Task 3: 持久化 Create/Provision 与未知结果对账

**Files:**
- Modify: `src/main/java/ai/nubase/metadata/repository/AutomationProjectOperationRepository.java`
- Modify: `src/main/java/ai/nubase/platform/mcp/PlatformProjectAutomationFacade.java`
- Modify: `src/main/java/ai/nubase/platform/mcp/PlatformProvisionOutboxRecovery.java`
- Test: `src/test/java/ai/nubase/platform/mcp/PlatformProjectAutomationFacadeTest.java`
- Test: `src/test/java/ai/nubase/platform/mcp/PlatformProvisionOutboxRecoveryTest.java`

**Step 1:** `platformProjectStatus` 接受同 grant、actor、ref、task、run、spec、approval 下的成功 create 或 provision ledger。

**Step 2:** 未收到 create 响应时先用只读 status 对账；只有 trace 完全一致的已创建 Project 才可继续 provision。

**Step 3:** 为持续失败的 provision outbox 增加有界退避、attempt 与终止状态，避免旧任务长期占用恢复窗口。

**Step 4:** 运行状态机与恢复测试。

```bash
mvn -q -Dtest='PlatformProjectAutomationFacadeTest,PlatformProvisionOutboxRecoveryTest,AutomationProjectOperationRepositoryQueryTest' test
```

### Task 4: 收口为静态控制面验证

**Files:**
- Modify: `src/main/java/ai/nubase/platform/mcp/PlatformMcpReadinessChecker.java`
- Modify: `src/main/java/ai/nubase/platform/mcp/PlatformMcpProjectRepository.java`
- Modify: `src/main/java/ai/nubase/ai/gateway/platform/PlatformUpstreamRepository.java`
- Test: `src/test/java/ai/nubase/platform/mcp/PlatformProjectAutomationFacadeTest.java`
- Test: `src/test/java/ai/nubase/ai/gateway/platform/PlatformUpstreamRepositoryReadinessTest.java`

**Step 1:** 只读验证目标租户数据库可连接及必需 schema/table 存在。

**Step 2:** 验证默认 Gateway key hash 已注册，不读取或返回 key 值。

**Step 3:** 平台 upstream 必须具备有效地址、加密凭据和非空模型目录；这些只形成静态 catalog 配置证据，不执行或推断 upstream HTTP/计费调用。无法证明配置存在时保持 `BLOCKED`。

**Step 4:** Verifier 使用独立 read route 再次核对 trace、`verificationLevel=STATIC_CONTROL_PLANE`、`state=PROVISIONED`、全部静态 readiness 布尔值、`readiness.gateway=true` 与 `advertisedEndpoints.gateway`；不得把结果表述为 Functions/MCP 外部可达、模型调用成功、应用已部署或生产可用。

### Task 5: 收口短期授权与运行时 Route

**Files:**
- Modify: `script/goai/bootstrap-platform-automation-routes.py`
- Modify: `script/goai/configure-platform-automation-routes.py`
- Modify: `examples/goai-agent-delivery/compat/hiclaw-v1.1.2/PLATFORM_AUTOMATION_SECURITY.md`
- Test: `script/goai/test_bootstrap_platform_automation_routes.py`
- Test: `script/goai/test_configure_platform_automation_routes.py`

**Step 1:** 在用户请求开始前 mint/rotate 600 秒短期 build/read token；route JWT 过期必须失败关闭，不得误报 transport 故障。

**Step 2:** 将 grant 的 ref prefix 与 approval binding 固定到本次 Project bootstrap，并通过非秘密 run context 交给 Manager。

**Step 3:** 从 Worker 身份验证 build route 精确 3 tools、read route 精确 1 tool，同时验证越权调用失败。该步骤必须以实际 Worker 的 `tools/list` 结果作为运行时门禁，Console 配置成功不能替代它。

**Step 4:** 演示结束后先收空 route，再 revoke grant。

### Task 6: 本地真实闭环验收

**Files:**
- Create: `examples/goai-agent-delivery/scripts/run-project-bootstrap-closure.mjs`
- Create: `examples/goai-agent-delivery/evidence/project-bootstrap/.gitignore`
- Modify: `examples/goai-agent-delivery/README.md`

**Step 1:** 在专用 local sandbox 中发送一句：`创建个项目名称为 psx_agent_teams_project`。

**Step 2:** 验证四角色使用同一 `taskId`、`runId`、`specDigest`、`approvalId`，且 create/provision 各只有一个逻辑操作。

**Step 3:** 验证最终只有一个 Project、一个物理数据库、一个默认 Gateway key，Studio 显示同一 Project。

**Step 4:** 验证证据不含 Project key、JWT secret、JDBC credential、automation JWT、upstream token 或原始控制面响应。

**Step 5:** 只有 `verificationLevel=STATIC_CONTROL_PLANE`、`state=PROVISIONED`、`readiness.gateway=true`、`advertisedEndpoints.gateway` 和 Verifier 独立检查同时通过时输出 `PROVISIONED`；其余情况输出 `BLOCKED` 并保留真实残留状态。`PROVISIONED` 只证明 schema/RLS、默认 key 与 catalog 等静态配置，不证明 Functions/MCP 外部可达、模型 upstream HTTP/计费调用、应用部署或生产可用。

**Step 6:** 运行完整验证。

```bash
git diff --check
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest \
  script/goai/test_bootstrap_platform_automation_routes.py \
  script/goai/test_configure_platform_automation_routes.py \
  script/goai/test_refresh_higress_mcp_policy.py
node examples/goai-agent-delivery/scripts/test-validator.mjs
node examples/goai-agent-delivery/scripts/validate-package.mjs
mvn test
```

**Step 7:** 仅在用户确认后按功能边界分批提交，不使用全量暂存。
