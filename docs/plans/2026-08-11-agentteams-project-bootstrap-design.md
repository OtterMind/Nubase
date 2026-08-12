# AgentTeams Project Bootstrap Design

## 目标

用户只需要在 AgentTeams 的 Manager 会话中描述项目目标。团队随后创建一个 Nubase Project，完成物理数据库与平台内置服务的 Provision，并在 Studio 中呈现同一个 Project。用户不需要手工重复 Studio 的创建步骤。

该流程创建的是 Nubase 基础设施 Project，不是应用发布，也不包含 SQL、Secret、自定义 AI 上游或生产 Promote。

## 边界

现有 `/mcp` 使用 Project `apikey` 建立租户上下文，只能操作一个已经存在且已启用的 Project。Project 创建属于平台控制面，因此必须使用独立的 `/platform/mcp`：

- 不复用 tenant service-role key；
- 不向 Worker 注入 metadata service-role key 或人类 Platform JWT；
- 不把 Project keys、JWT secret、JDBC credentials 或原始初始化错误返回模型；
- 默认关闭，只有显式配置 automation grant 后才启用；
- 不提供删除、密钥读取、上游 Token、SQL 或任意跨租户工具。

## 控制面工具

平台 MCP 只注册三个工具：

1. `platformProjectCreate`
   - 输入：`taskId`、`runId`、`idempotencyKey`、`specDigest`、`projectRef`、`displayName`、可选描述与 approval reference。
   - 服务端派生数据库名、角色、Pool 和 Project ownership。
   - 返回 operation/project 标识与安全状态，不返回任何凭据。
2. `platformProjectProvision`
   - 对同一 operation 提交两阶段 Provision。
   - 复用已有 lease、heartbeat 和 fencing，不创建第二个物理数据库。
3. `platformProjectStatus`
   - 返回固定状态、`verificationLevel`、运行标志、安全时间戳、readiness 布尔值和公布的 endpoint 名称。
   - 不返回 `initMessage`、异常、连接串或初始化步骤原文。

同一 automation grant、actor、action 和 idempotency key 只能对应一个 canonical request digest。重复相同请求返回原 operation；重复 key 配合不同参数返回 `IDEMPOTENCY_KEY_REUSED`。Grant 被替换后不得跨授权生命周期回放旧 operation。

## 身份与授权

平台 MCP 只接受专用 automation JWT。JWT 必须验证固定 issuer/audience/algorithm，以及 `actor_type=automation`、`grant_id`、`token_version`、`jti`、`iat`、`nbf`、`exp` 和精确 scope。

Grant 在 Metadata DB 中持久化，包含启用状态、Token 版本、Project 数量上限、允许的 Project ref 前缀和审批边界。禁用 Grant 或提升 Token 版本必须立即撤销旧 Token。

尚未 dispatch 的 provision outbox 必须在每次恢复前重新验证 grant、token version 与成功 create lineage。grant 失效或 lineage 不匹配时固定终止，不继续写入；暂时性 dispatch 失败只能有界退避，耗尽后进入固定失败状态，不能无限重试或饿死后续 operation。

Gateway/Controller 负责注入 Token。Worker、Matrix 消息、Skill、证据和 Git 工作区都不能看到 Token。Operator helper 只能从权限为 `0600` 的文件或文件描述符读取签名材料，并在成功或失败后清理临时文件。

## AgentTeams 编排

一条自然语言请求进入 Manager 后使用 `project-bootstrap-v1`：

1. Delivery Lead 固定 Project spec、task/run、资源上限、默认 Gateway 模式和验收条件，并按唯一规则派生 ref：local/staging 的 ASCII display name 转小写，将连续非字母数字字符替换为 `_`，去掉首尾 `_`，补 `goai_` 前缀；空结果、非 ASCII、超过 40 个字符或冲突时进入 `BLOCKED`，禁止截断或随机改名。`psx_agent_teams_project` 因此映射为 `goai_psx_agent_teams_project`。
2. Builder 通过 `project-build` route 各执行一个逻辑 create 和 provision；create 响应未知时，status 可以依据同一 create ledger 返回 `PENDING`，确认 trace 完全一致后才继续 provision；provision 响应未知时依据 provision ledger 对账，禁止换 key 或参数盲目重试。
3. Verifier 通过只读 `project-read` route 独立轮询 status，验证数据库、RLS、Auth、Storage、Memory、Assets 和 AI Gateway 的静态控制面 provisioning，并要求 `verificationLevel=STATIC_CONTROL_PLANE`、`state=PROVISIONED`、`readiness.gateway=true` 与 `advertisedEndpoints.gateway` 存在。
4. Release Governor 只消费脱敏证据并复核同一组静态字段；全部通过输出 `PROVISIONED`，否则输出 `BLOCKED`。

`PROVISIONED` 只证明 schema/RLS、默认 key 注册和 gateway catalog 等静态控制面配置。它不证明 Functions/MCP 外部可达，不证明模型 upstream HTTP 或计费调用，不证明应用已部署，也不表示生产可用。`advertisedEndpoints.gateway` 的存在只表示入口已公布，不是运行时健康探测。

## 验收

- Element 中只有一条不含凭据的创建请求；
- 四个角色使用同一 `taskId` 和 `runId`；
- create 与 provision 各只产生一个 canonical operation，并且只产生一个 Project 和一个物理数据库；
- Platform status 最终满足 `verificationLevel=STATIC_CONTROL_PLANE`、`state=PROVISIONED`、`enabled=true`、`running=false`；
- 必需 schema、RLS 与默认 AI Gateway key 均就绪，但 key 值不可见；
- `readiness.gateway=true` 且 `advertisedEndpoints.gateway` 存在，但 endpoint 值不进入 evidence；
- Builder 看不到 SQL、Project keys、Secret、删除和上游 Token 工具；
- Verifier 没有写工具；
- Studio 的 Projects 页面能看到同一个 Project；
- evidence 只包含安全状态、Project ref、工具名、时间和非秘密 digest。
