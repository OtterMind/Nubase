# GOAI Java HTTP MCP Policy Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 为 GOAI 三条角色路由增加与 Nubase Java `/mcp` 精确匹配的最小权限策略，同时保留现有 stdio bridge 策略。

**Architecture:** 每条路由包含独立的 `stdioBridgePolicy` 与 `javaHttpPolicy`，并区分 AgentTeams Worker 名与带 `worker-` 前缀的 Higress consumer 名。校验器分别从 TypeScript 和 Java 源码提取完整工具清单，要求每个 transport 的 allow/deny 集合形成无遗漏、无交集的精确分区。

**Tech Stack:** JSON, Node.js 22, Ruby YAML/JSON, Bash, Spring AI MCP, Higress MCP proxy

---

### Task 1: Add transport-specific policy contracts

**Files:**
- Modify: `examples/goai-agent-delivery/agentteams-v1.2.2/mcp-tool-policies.json`

**Step 1:** 将现有 `bridgeGuards`、`allowTools` 和 `denyTools` 移入 `stdioBridgePolicy`，不改变原有集合。

**Step 2:** 为三条路由增加 `javaHttpPolicy`，写入经审阅的 43 个 Java 工具精确分区和能力状态。

**Step 3:** 将敏感工具清单按 transport 拆分，避免 snake_case 与 camelCase 混用。

**Step 4:** 将 `agentTeamsWorkers` 与 `higressConsumers` 分开声明，并校验二者是一一对应的前缀映射。

### Task 2: Enforce both inventories

**Files:**
- Modify: `examples/goai-agent-delivery/scripts/validate-package.mjs`
- Modify: `script/goai/install-agentteams.sh`

**Step 1:** 从 Java `*McpTools.java` 的 `@Tool` 方法提取运行时工具名，并断言 inventory 非空且无重复。

**Step 2:** 对 stdio 和 Java policy 分别执行 exact-key、allow/deny 完整分区、敏感工具拒绝和角色关键能力校验。

**Step 3:** 明确拒绝 Builder Java policy 中的 `executeSql`，并要求其 readiness 为 `PARTIAL`。

### Task 3: Add negative regression cases and documentation

**Files:**
- Modify: `examples/goai-agent-delivery/scripts/test-validator.mjs`
- Modify: `examples/goai-agent-delivery/agentteams-v1.2.2/README.md`
- Modify: `examples/goai-agent-delivery/README.md`

**Step 1:** 增加 Java 未分类工具、transport 命名混用和 Builder 危险 SQL 三个负例。

**Step 2:** 说明本地 Java HTTP 路由把 `javaHttpPolicy.allowTools` 写入 Higress 顶层 `allowTools`，并为 HiClaw v1.1.2 添加顶层 `tools: []` 启用标记；stdio wrapper 使用 `stdioBridgePolicy`。

**Step 3:** 运行 package validator、negative cases、installer semantic tests 和 secret scan；预期全部通过。
