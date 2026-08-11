# Nubase Agentic Delivery Team

GOAI 2026 Agent Infra 参赛入口。

## 作品简介

Nubase Agentic Delivery Team 是面向 AI 应用交付的多 Agent 基础设施。Delivery Lead、Builder、Verifier、Release Governor 四个 Agent 基于 AgentTeams 协同，将企业需求拆解为可审计计划，在隔离项目中执行最小权限构建与验证，并在人工审批后形成发布就绪决策或执行补偿回滚。Nubase 提供模型网关、长期记忆、多租户数据面、MCP 工具和部署证据；AgentTeams 提供任务编排、共享状态与人机协作。所有调用以 `runId` 贯通，真实凭据仅存在于运行时 Secret，禁止进入代码、消息、Trace 和演示产物。比赛本地闭环只使用 `deploymentStageAsset` 创建单个 synthetic marker，不把这一演练表述为数据库、Function、Cron 或整应用部署。

## 现有项目与参赛新增贡献

Nubase 是已有 Apache-2.0 开源项目。参赛前已经具备多租户数据库、Auth、Storage、Functions、Assets、Cron、AI Gateway、Memory、MCP 和部署记录。

本分支新增：

- AgentTeams v1.2.2 规范清单及本机 HiClaw v1.1.2 兼容清单；
- 四个 Agent Identity、四个角色化 Skill 和工具权限矩阵；
- 组合既有 Nubase Memory 与新增的 AgentTeams shared-state、Trace 契约；
- 带 artifact digest 的人工审批与补偿回滚协议；
- Java HTTP MCP `deploymentStageAsset` 私有 bounded marker stage 与基于显式 private-storage 门禁、预启用 bucket versioning、精确 `ownershipVersionId` 的真实 asset rollback drill；
- 严格比赛包校验器、无凭据 MCP smoke 和示例应用；
- per-tool MCP allowlist/denylist；
- CORS、Docker context、PostgreSQL 暴露面、健康检查和 CI 安全门禁。

详细架构及决策见 [design document](docs/plans/2026-08-10-goai-agent-infra-design.md)。

## 大赛要求映射

| Requirement | Implementation |
| --- | --- |
| AgentTeams | `examples/goai-agent-delivery/agentteams-v1.2.2/` |
| At least three Agents | Four distinct Workers with one Team Leader |
| Agent Identity | `examples/goai-agent-delivery/identities/` |
| Skill engineering | `examples/goai-agent-delivery/skills/` |
| Memory | Nubase `memory_context`, `memory_search`, `memory_write` |
| Shared state | AgentTeams task workspace and approval artifact |
| Trace | `runId` plus trace/evidence contracts |
| Human approval | Digest-bound, human release-readiness decision |
| Rollback | Bounded marker rollback drill plus explicit compensation evidence |
| Security audit | Tool policy, sandbox credentials, CI and secret scan |

## Quick Verification

这些命令不会读取 `.env`、`.nubase` 或本机 Nubase 凭据：

```bash
cd frontend
pnpm --filter nubase_cli build
pnpm --filter nubase_cli test

cd ..
node examples/goai-agent-delivery/scripts/validate-package.mjs
node examples/goai-agent-delivery/scripts/mcp-smoke.mjs
bash script/goai/install-agentteams.sh
```

`install-agentteams.sh` 默认只验证运行时和清单。只有显式传入 `--apply` 才会向本机 AgentTeams/HiClaw 创建或更新资源。

## Security Boundary

- 不提交或打包 `.env*`、`.nubase/`、`application-dev.yml`、私钥、AgentTeams 工作区、日志或 registry；
- 不从工作目录直接打包参赛材料，使用 Git 跟踪文件生成 artifact；
- Worker 不获得生产密钥，只使用专用 sandbox 项目；
- 比赛 Java `nubase-build` 路由同时拒绝 `executeSql` 与 `executeSqlDryRun`，不提供任何 SQL execution/dry-run；数据库 schema apply 不属于本演示闭环；
- `NUBASE_ASSETS_BOUNDED_PRIVATE_STORAGE_ENABLED` 默认关闭；本地演练只在无 public origin 的专用私有、已启用 versioning bucket 上显式开启，生产 CDN 模式按失败关闭；
- Reserved marker 对 anon/authenticated RLS、通用 Assets API、公共 GET/HEAD 和 SPA fallback 隐藏；只有 service-role Verifier 可通过受控 `assetsList` 查看不含 `publicUrl` 的脱敏 metadata；
- `NUBASE_ALLOW_DANGEROUS_SQL` 必须保持关闭；
- Release Governor 没有有效人工审批时必须拒绝发布；
- Trace 只能保存工具名、脱敏摘要、digest、时间和结果；
- 当前回滚是部分补偿回滚，不对 SQL、Functions、Secrets 和 Memory 做虚假完整性承诺；
- 本地 HiClaw 的 MCP 运行证明必须来自 Worker 身份下的 `mcporter` discovery 和 call result，不能使用清单声明或 CoPaw native MCP registry 计数代替；
- `REJECTED_RECOVERED` 只表示 bounded marker 的精确对象版本已恢复，`APPROVED_FOR_PROMOTION` 只表示 readiness；两者都不是已发布状态；
- 本机 HiClaw v1.1.2 只用于兼容验证，正式提交以 AgentTeams v1.2.2 清单为准。

## Known Integration Boundary

本地 stdio `nubase_cli` 提供 `deploy_app`。远程 Java `/mcp` 提供 `deploymentStageAsset` bounded marker stage 以及 deployment 查询/回滚工具，但没有远程 `deploy_app` 或 `deployment_promote`。演示与证据必须记录实际 transport，不能把 bounded marker、离线验证、readiness decision 或远程原语描述成远程一键部署或已发布。
