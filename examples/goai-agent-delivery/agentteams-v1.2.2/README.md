# AgentTeams v1.2.2 运行清单

本目录是大赛提交使用的主清单，固定使用 `agentteams.io/v1beta1` 和 AgentTeams v1.2.2。四个 Worker 是独立资源，`team.yaml` 只负责成员引用和协作关系。

## 默认安全行为

运行 `script/goai/install-agentteams.sh` 只执行静态校验和运行时探测，不复制文件、不创建 Worker、不创建 Team，也不修改 MCP 路由。只有显式传入 `--apply` 才会把四个 Skill 复制到 Manager workspace，并按 Worker 在前、Team 在后的顺序调用 `agt apply`。

清单中的 `mcpServers` 只是期望的网关地址，不会创建 Higress MCP Server，也不会证明 consumer 已获授权。如果 `nubase-read`、`nubase-build` 或 `nubase-release` 路由尚不存在，AgentTeams 资源仍可能创建成功，但工具状态必须标记为 `UNAVAILABLE`，不得宣称工具已授权或端到端可用。

## MCP 权限门禁

`mcp-tool-policies.json` 是唯一的角色工具白名单：

- `nubase-read` 只授权 Delivery Lead 和 Verifier，拒绝凭据、用户列表和所有写工具。
- `nubase-build` 只授权 Builder，允许 staging 构建所需工具，拒绝项目密钥、Secret、用户管理、删除和 rollback。
- `nubase-release` 只授权 Release Governor，允许发布证据查询和受控 rollback，拒绝构建、数据写入和 Secret 工具。

创建路由时必须把对应 `allowTools` 配置到 Higress，consumer 列表必须与 policy 中的 `workerConsumers` 完全一致。对于 AgentTeams 的 HTTP MCP 路由，Higress 的工具白名单和 consumer 授权是权威边界。

`NUBASE_ALLOWED_TOOLS` 和 `NUBASE_DENIED_TOOLS` 只约束 TypeScript stdio MCP bridge 的 `tools/list` 与 call dispatch，可作为该运行形态的第二层防御；它们不覆盖直接 CLI，也不覆盖远程 Java `/mcp`，因此不能替代本目录声明的 Higress policy。

当路由上游是 TypeScript stdio bridge 的受控服务包装时，还必须使用 policy 中的 `bridgeGuards`。远程 Java `/mcp` 不读取这些环境开关，必须提供等价的服务端限制或继续保持路由为 `UNAVAILABLE`。`nubase-build` 只能连接专用 sandbox 项目，并保持危险 SQL 禁用；生产项目和真实客户数据不属于本演示范围。

任何上游 `apikey`、Bearer Token 或其他凭据只能在运行时由 Higress/Secret 管理机制注入。凭据不得进入 YAML、JSON、Skill、日志、录屏命令、Git 历史或 Agent 可读取的共享目录。

完成路由配置后，必须分别执行工具发现、允许工具调用和拒绝工具调用验证，并保存脱敏证据。只有以下条件全部满足后才能标记 `AUTHORIZED`：

1. MCP 路由存在且健康。
2. Worker consumer 身份与 policy 一致。
3. `allowTools` 可发现并可调用。
4. `denyTools` 返回拒绝或完全不可发现。
5. Trace 中存在相同的 `taskId`、`runId` 和 `agentId`。

当前 bridge 尚未暴露独立的 `deployment_promote` 工具，因此 Release Governor 只能阻止发布并执行已支持的有限 rollback，不能宣称完整 promote 闭环已实现。该缺口在 policy 的 `requiredToolsNotYetExposed` 中显式记录。

## 应用顺序

```bash
script/goai/install-agentteams.sh
script/goai/install-agentteams.sh --apply
```

`--apply` 只表示 AgentTeams 资源请求已提交，不表示 Worker 已就绪、MCP 已授权或业务流程已经通过验证。应用后还需要检查四个 Worker、Team、三条 MCP policy 和一条人工审批/回滚演示链路。

Skill readiness 也必须单独验证：Manager workspace 中的四个 Skill 是持久 source，controller/helper 完成分发后，四个 Worker 必须分别能读取对应 `SKILL.md`，并与 source digest 一致。只看到 `spec.skills`、registry 记录或 `Running` 状态都不足以证明 Skill 可用。
