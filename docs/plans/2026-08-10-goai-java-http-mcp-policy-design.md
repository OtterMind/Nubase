# GOAI Java HTTP MCP Policy Design

## 背景与结论

比赛清单中的三条 MCP 路由实际指向 Nubase Java `POST /mcp`，但现有策略只分类 TypeScript stdio bridge 的 snake_case 工具。两套 transport 的工具名和能力不相同，直接复用会让 Higress 暴露零工具；移除 `allowTools` 又会把密钥、用户管理和删除类工具暴露给 Worker。

采用“双 transport、同角色边界”的策略模型：每条角色路由同时声明 `stdioBridgePolicy` 和 `javaHttpPolicy`。两者分别对各自完整工具清单做精确 allow/deny 分区，共享同一个 `mcpServerName`，但不允许跨 transport 混用工具名。路由同时区分无前缀的 `agentTeamsWorkers` 与 Higress 实际 consumer 名 `higressConsumers`；后者带 `worker-` 前缀，是 Console API REPLACE 授权的唯一输入。现有 stdio 策略保持行为不变，本地 HiClaw/Higress 使用 Java HTTP 策略。

没有选择的方案：直接把现有策略替换成 Java 工具名会丢失 stdio bridge 的已实现防御；新增 TypeScript HTTP 包装服务会扩大运行组件和凭据面，不符合当前本地演示的最小改造原则。

## 权限边界

`nubase-read` 只提供 Memory、schema、deployment、worker、storage、assets、functions 和 cron 的只读工具。`nubase-build` 允许 sandbox 内的 Memory、Assets、Functions 和 Cron 构建能力，并暂时保留 `executeSqlDryRun`；由于 Java 端 `executeSql` 尚未强制拒绝危险 SQL，Builder 不获得 schema apply 权限，状态必须标记为 `PARTIAL`。`nubase-release` 只增加受控 `deploymentRollback`，不允许构建、SQL、Secret、Key 或用户管理工具。

三条 Java policy 必须对源码注册的 43 个工具形成完整分区，且敏感与破坏性工具全部位于 deny 集合。Higress MCP 原始配置的顶层 `allowTools` 是 Java HTTP transport 的权威运行时边界；stdio 环境变量只约束 stdio bridge，不能替代网关白名单。HiClaw v1.1.2 还需要顶层 `tools: []` 兼容标记才能启用代理插件，但该空标记不参与工具授权。

## 验证与失败语义

校验器从 `frontend/packages/mcp-bridge/src/tools.ts` 提取 stdio inventory，并从 `src/main/java/ai/nubase/mcp/tools/*McpTools.java` 的 `@Tool` 方法提取 Java inventory。每条路由分别验证唯一性、无交集、完整并集、敏感工具拒绝和关键角色能力，任何未知或未分类工具都 fail closed。

本地配置完成后，必须分别验证三条路由的 `tools/list` 与 Java allow 集合完全一致，交叉 consumer 被拒绝，敏感工具不可发现。Builder 只能声明 `PARTIAL`，不得把 SQL dry-run 描述为绝对无副作用，也不得声称完整 staging schema 部署已经打通。
