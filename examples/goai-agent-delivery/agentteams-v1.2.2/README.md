# AgentTeams v1.2.2 运行清单

本目录是大赛提交使用的主清单，固定使用 `agentteams.io/v1beta1` 和 AgentTeams v1.2.2。四个 Worker 是独立资源，`team.yaml` 只负责成员引用和协作关系。

## 默认安全行为

运行 `script/goai/install-agentteams.sh` 只执行静态校验和运行时探测，不复制文件、不创建 Worker、不创建 Team，也不修改 MCP 路由。只有显式传入 `--apply` 才会把四个 Skill 复制到 Manager workspace，并按 Worker 在前、Team 在后的顺序调用 `agt apply`。

清单中的 `mcpServers` 只是期望的网关地址，不会创建 Higress MCP Server，也不会证明 consumer 已获授权。如果 `nubase-read`、`nubase-build` 或 `nubase-release` 路由尚不存在，AgentTeams 资源仍可能创建成功，但工具状态必须标记为 `UNAVAILABLE`，不得宣称工具已授权或端到端可用。

## MCP 权限门禁

`mcp-tool-policies.json` 是角色工具权限的可审阅来源。每条路由同时声明两套不能混用的工具集合：

- `stdioBridgePolicy` 对应 TypeScript stdio bridge 的 snake_case 工具，原有 65 个工具分区及 `bridgeGuards` 保持不变。
- `javaHttpPolicy` 对应 Nubase Java `POST /mcp` 的 camelCase 工具，三条路由分别对源码注册的 43 个 `@Tool` 方法做完整 allow/deny 分区。

当前本地 HiClaw/Higress 上游是 Java HTTP MCP，因此必须把 `javaHttpPolicy.allowTools` 原样写入 Higress MCP 原始配置的顶层 `allowTools`，不能放在 `server` 下。只有在另行部署受控 stdio wrapper 时，才使用 `stdioBridgePolicy.allowTools`、`stdioBridgePolicy.denyTools` 和 `stdioBridgePolicy.bridgeGuards`；两套工具名不得交叉复制。

HiClaw v1.1.2 的 Console SDK 还会用原始配置是否包含字面量 `tools:` 判断 MCP 插件是否启用。代理配置必须加入顶层 `tools: []` 兼容标记；该空数组不声明代理工具，实际发现和调用边界仍由顶层 `allowTools` 控制。配置后必须确认插件实例未被标记为 disabled，并以实际 `tools/list` 完全相等作为门禁。

`rawConfigurations` 的层级必须类似下面这样；`defaultCredential` 只能在本地控制面请求的内存中替换，不能写入仓库文件或命令参数：

```yaml
server:
  name: nubase-read-mcp-server
  type: mcp-proxy
  transport: http
  mcpServerURL: "http://host.docker.internal:9999/mcp"
  timeout: 5000
  securitySchemes:
    - id: UpstreamAuth0
      type: apiKey
      in: header
      name: apikey
      defaultCredential: "<runtime-injected>"
  defaultUpstreamSecurity:
    id: UpstreamAuth0
allowTools:
  - memorySearch
  - memoryContext
tools: []
```

角色边界如下：

- `nubase-read` 只授权 Delivery Lead 和 Verifier，拒绝凭据、用户列表和所有写工具。
- `nubase-build` 只授权 Builder，Java HTTP 路由允许专用 sandbox 中的 Memory、Assets、Functions、Cron 构建和 `executeSqlDryRun`，但拒绝 `executeSql`、项目密钥、Secret、用户管理、删除和 rollback。
- `nubase-release` 只授权 Release Governor，允许发布证据查询、共享状态记录和受控 rollback，拒绝构建、SQL、Secret、Key 和用户管理工具。

AgentTeams Worker 资源名与 Higress consumer 身份是两个不同命名域，policy 显式保存一一映射：

| `agentTeamsWorkers` | `higressConsumers` |
| --- | --- |
| `nubase-delivery-lead` | `worker-nubase-delivery-lead` |
| `nubase-builder` | `worker-nubase-builder` |
| `nubase-verifier` | `worker-nubase-verifier` |
| `nubase-release-governor` | `worker-nubase-release-governor` |

Console API 的 consumer 授权 `REPLACE` 操作只能使用 `higressConsumers` 中带 `worker-` 前缀的实际身份，不能使用无前缀 Worker 资源名。对于 Java HTTP MCP 路由，Higress 顶层 `allowTools` 与 consumer 授权是权威运行时边界。

`NUBASE_ALLOWED_TOOLS` 和 `NUBASE_DENIED_TOOLS` 只约束 TypeScript stdio MCP bridge 的 `tools/list` 与 call dispatch，可作为该运行形态的第二层防御；它们不覆盖直接 CLI，也不覆盖远程 Java `/mcp`，因此不能替代本目录声明的 Higress policy。

Java Builder 的 `readiness` 固定为 `PARTIAL`。Java `executeSql` 当前只报告风险、不会在服务端强制拒绝危险 SQL，所以它必须保留在 deny 集合，数据库 schema apply 尚未打通。Java `executeSqlDryRun` 会在事务中执行后回滚，并非纯文本 classifier 或绝对无副作用检查；它只能连接专用、可丢弃的 sandbox 项目。生产项目和真实客户数据不属于本演示范围。

任何上游 `apikey`、Bearer Token 或其他凭据只能在运行时注入本地 Higress 控制面，且本演示只能使用专用 sandbox 项目。凭据不得进入已提交 YAML/JSON、Skill、日志、录屏命令、Git 历史或 Agent 可读取的共享目录；不得读取或展示包含 `defaultCredential` 的原始控制面响应。生产环境仍需接入经验证的 Secret 引用机制，不能照搬本地持久凭据方案。

完成路由配置后，必须分别执行工具发现、允许工具调用和拒绝工具调用验证，并保存脱敏证据。只有以下条件全部满足后才能标记 `AUTHORIZED`：

1. MCP 路由存在且健康。
2. Java 路由的顶层 `allowTools` 与 `javaHttpPolicy.allowTools` 完全一致，且兼容标记为顶层 `tools: []`。
3. Console API 中的 consumer 身份与 `higressConsumers` 完全一致。
4. `allowTools` 可发现并可调用，`denyTools` 返回拒绝或完全不可发现。
5. Trace 中存在相同的 `taskId`、`runId` 和 `agentId`。

远程 Java `/mcp` 不暴露 `deploy_app`，当前工具面也没有独立的 `deployment_promote`。Release Governor 只能签发 digest-bound readiness decision、阻止发布并执行已支持的有限 rollback，不能宣称远程部署或完整 promote 闭环已经实现。该缺口在 policy 的 `requiredToolsNotYetExposed` 中显式记录。

## 应用顺序

```bash
script/goai/install-agentteams.sh
script/goai/install-agentteams.sh --apply
```

`--apply` 只表示 AgentTeams 资源请求已提交，不表示 Worker 已就绪、MCP 已授权或业务流程已经通过验证。应用后还需要检查四个 Worker、Team、三条 MCP policy 和一条人工审批/回滚演示链路。

Skill readiness 也必须单独验证：Manager workspace 中的四个 Skill 是持久 source，controller/helper 完成分发后，四个 Worker 必须分别能读取对应 `SKILL.md`，并与 source digest 一致。只看到 `spec.skills`、registry 记录或 `Running` 状态都不足以证明 Skill 可用。
