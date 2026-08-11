# HiClaw v1.1.2 Compatibility Runtime

本目录只用于本机 HiClaw v1.1.2 兼容验证，正式提交仍以 AgentTeams v1.2.2 清单为准。版本依据是 Manager 内的 `.builtin-version` marker；旧 CLI 可能只显示 `Controller: dev`，不能单独作为版本证据。

`script/goai/install-agentteams.sh --apply --runtime hiclaw` 使用以下 fail-closed 链路：

1. 将四个已校验 Skill 原子复制到专用 Manager workspace。
2. 应用 inline Team，并等待四个 Worker 出现在 Manager registry。
3. 对四个角色调用 v1.1.2 assignment helper；三个普通 Worker 校验 registry 分配，Leader 因 `LeaderSpec` 不支持本地 `skills` 字段而不把 registry 作为持久证据。
4. 验证 Worker 存储身份后，由每个 Worker 使用自己的最小权限身份把对应 Skill 精确镜像到自己的 MinIO namespace，并删除旧对象。
5. 对 staging、持久副本和 Worker 运行目录的完整文件清单及 SHA-256 分别校验。

Manager helper 在部分旧镜像中可能隐藏 `mc mirror` 错误，因此安装器不会把 helper 的零退出码当作持久化证据。任何 assignment、MinIO、digest 或运行态校验失败都会终止 `--apply`。

HiClaw v1.1.2 的 Leader Skill 由 installer 直接写入 Leader 自身的 MinIO namespace，并以持久对象和 runtime digest 作为验收证据。正式 AgentTeams v1.2.2 清单仍通过独立 Worker 的 `spec.skills` 声明 `app-plan`。

兼容运行还要求：

- `/root/manager-workspace` 必须绑定到一个专用、非符号链接目录；
- `/host-share` 必须精确映射到 `examples/goai-agent-delivery/evidence/` 下的专用脱敏目录，并通过 `--host-share-root` 显式声明；
- 三个 Nubase MCP 路由必须使用 sandbox credential 和精确 allowlist；
- Team 为 `Active`、四个 Worker 为 `Running`，且四个 Skill 的持久副本和运行副本均通过完整树摘要校验。

HiClaw Worker 通过启用的 `mcporter` Skill 使用 MCP。运行验收必须由每个 Worker 执行实际 `mcporter` server/tool discovery，并分别证明允许调用成功、拒绝调用失败。CoPaw native MCP registry 计数不是本兼容链路的 readiness 证据；清单存在、URL 可达和路由配置成功也不能代替 Worker 身份下的调用证明。

本机 Java MCP 工具集合变化后，只能使用仓库内的 reviewed refresh helper 更新三条路由。该 helper 固定本地 Console、三条 route、44 个工具、17/23/18 allowlist 和 2/1/1 consumer，并按“先收窄、再替换 consumer、最后开放目标集合”执行。`nubase-build` 的 Java route 同时拒绝 `executeSql` 与 `executeSqlDryRun`，不提供任何 SQL execution/dry-run；stdio bridge policy 保持独立且不受此次收敛影响：

```bash
cleanup_higress_refresh() {
  docker exec hiclaw-controller rm -f \
    /tmp/refresh-higress-mcp-policy.py \
    /tmp/goai-mcp-tool-policies.json \
    /tmp/higress-session-cookie-gateway
}
trap cleanup_higress_refresh EXIT HUP INT TERM

docker exec hiclaw-controller chmod 600 /tmp/higress-session-cookie-gateway
docker cp script/goai/refresh-higress-mcp-policy.py \
  hiclaw-controller:/tmp/refresh-higress-mcp-policy.py
docker cp examples/goai-agent-delivery/agentteams-v1.2.2/mcp-tool-policies.json \
  hiclaw-controller:/tmp/goai-mcp-tool-policies.json
docker exec hiclaw-controller env PYTHONDONTWRITEBYTECODE=1 \
  python3 /tmp/refresh-higress-mcp-policy.py \
  --policy /tmp/goai-mcp-tool-policies.json
```

Session cookie 必须通过不进入 argv、shell history 或仓库的受控通道写入 controller，权限固定为 `0600`。无论刷新成功还是失败，上面的 trap 都会删除 cookie、helper 和 policy 临时副本；刷新后必须再次确认三者均不存在。

不要运行会把上游 credential 放入 argv、把 Manager/全部 Worker 一次性授权的通用 setup helper。

本地 bounded closure 只允许 Builder 通过 `deploymentStageAsset` 创建服务端生成的 `__goai_e2e/{runId}/marker.json`，随后由 Verifier 核对成功响应和 `assets_upload` step 的同一 `ownershipVersionId`、Release Governor 执行经批准的 `deploymentRollback`、Verifier 复核唯一成功 action 为该版本的 `asset_version_deleted`。`NUBASE_ASSETS_BOUNDED_PRIVATE_STORAGE_ENABLED` 默认 `false`；本地专用私有 global bucket 必须显式设为 `true`，保持 `NUBASE_ASSETS_BUCKET`、`NUBASE_ASSETS_PUBLIC_BASE_URL`、`R2_PUBLIC_URL` 全空并预先启用 versioning。生产 CDN 模式、公开 origin 或无法核验 versioning 时按失败关闭，Nubase 不自动修改共享 bucket。Reserved metadata 由 anon/authenticated RLS、通用 list/get、公共 GET/HEAD 和 SPA fallback 隐藏；service-role Verifier 只通过受控 `assetsList` 检查不含 `publicUrl` 的脱敏 metadata。完整 rollback drill 的最终结论只能是 `REJECTED_RECOVERED`；正向路径只能输出 `APPROVED_FOR_PROMOTION` readiness。两者都不得称为 `deploy_app`、`deployment_promote` 或已发布。

未满足以上条件时，只能声明静态清单验证通过，不能声明工具授权、端到端运行或比赛 Demo 就绪。
