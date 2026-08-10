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

未满足以上条件时，只能声明静态清单验证通过，不能声明工具授权、端到端运行或比赛 Demo 就绪。
