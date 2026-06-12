# Functions/Cron 第四轮修复计划

来源：2026-06-12 对 `0430aad..HEAD`（hardening + Studio 源码部署）的 7 角度评审，13 项 CONFIRMED。
原则：每项修复带回归测试；P0 必须先行（其中 #1 是上一轮修复被运行时语义架空，未修等于没修）；
已被驳回不修：`SET LOCAL ROLE` 失效论（JpaTransactionManager 正确绑定 routing DataSource 连接，
/rest/v1 与 cron 两条路径角色切换均有效——顺带应修正 MultiDatabaseConfiguration 里那条误导性注释）。

---

## P0 — 并发正确性（3 项，一个提交）

### 1. updateJob 脏刷新架空定向更新（双实例并发执行竞态）
**问题**：`updateJob` 在事务内 setter 修改托管实体后调 `updateAdminFields` JPQL，但实体仍在持久化
上下文且必脏（`setNextRunAt`/`setUpdatedAt` 无条件执行），提交时 Hibernate 全列 UPDATE 把读取时的
旧 `locked_until/last_status/last_run_at` 快照写回——JPQL 形同虚设，抹锁竞态原样存在。

**修复**（取简洁路线，顺带消灭 14 参数 JPQL）：
1. `ScheduledJob` 加 `@DynamicUpdate`——脏检查只写实际修改的列，admin 路径从不触碰三个 runner
   专属列，它们永远不会出现在 UPDATE 里；
2. `updateJob` 回归普通 `save()`，删除 `updateAdminFields` 及其 14 参数调用（同时消除"新增字段
   漏写 set-list"的漂移隐患——B5/Altitude6 发现）；
3. admin 重锚 `nextRunAt` 与 claim 推进的交叠由既有 `complete` 守卫 + `releaseLock` 兜底（已测）。

**测试**：
- IT（dev DB，TransactionTemplate 手工编排两个事务模拟竞态时序）：事务 A 加载 job → 事务 B 执行
  `claim()` 写入锁 → 事务 A 修改 description 并提交 → 断言行上 `locked_until` 仍为 B 写入的值；
- 既有 `ScheduledJobAdminServiceTest.updateJob` 用例改回 save() 断言。

### 2. 队列等待溢出锁时长 → 同一任务重叠执行
**问题**：8 worker + 100 队列下平均任务 48s 即可让排队等待超过 10 分钟最小锁；锁过期后下一
occurrence 被 claim 并立即执行，与仍在排队的旧 run 并发——违反"运行中不重入"的承诺；出队时也没有
锁有效性复查。

**修复**：
1. `runClaimedJob` 出队后第一步复查：`store` 新增只读查询 `isLockHeld(jobId, lockToken)`
   （`select count > 0 where id=? and lockedUntil=?`），不匹配则不执行目标，写一条
   `status='skipped'`、error=`QUEUE_WAIT_EXCEEDED_LOCK` 的 run 记录后返回（不碰 complete——锁已
   属于别人）；
2. 队列容量默认从 100 降为 `2 × maxConcurrentJobs`（16），并在 `CronProperties` 加
   `@PostConstruct` 校验：`maxJobsPerTick ≤ maxConcurrentJobs + executionQueueCapacity`，违反则
   启动失败并提示三旋钮关系（同时修掉"溢出 claim 被记为 EXECUTOR_REJECTED 失败"的旋钮陷阱）。

**测试**：runner 单测——`isLockHeld` 返回 false 时 target 不执行且 run 记录为 skipped；
CronProperties 校验单测（非法组合抛出）。

### 3. recordRejectedJob 单 try 块 → 拒绝时锁挂死
**修复**：与 `runClaimedJob` 对齐，`recordRun` 与 `completeClaim` 各自独立 try/catch。
**测试**：runner 单测——recordRun 抛出时 completeClaim 仍被调用。

---

## P1 — 部署链路正确性（5 项）

### 4. bindUserDefault 的 `$` 组引用
**修复**：`matcher.replaceFirst("const __userDefault = " + Matcher.quoteReplacement(matcher.group(1)) + ";")`。
**测试**：executor 单测——`export { handler$ as default };` 与 `export { fn$1 as default };` 都
能正确生成 `const __userDefault = handler$;` / `= fn$1;` 且部署成功。

### 5. Studio deploySource 把失败报成成功（含 local no-op）
**修复**：
1. 读取返回的 version DTO：`status === 'failed'` → 把 `errorMessage` 显示为错误（红色），不显示
   成功提示，且不刷新成"已部署"观感；
2. `provider === 'local'` 时在源码卡片常驻提示："local 执行器不接收 Studio 上传的代码，部署仅记录
   版本；请使用本地运行时目录"——并在部署成功提示中注明。
**测试**：studio 无组件测试基建，以 typecheck + 人工验证记录在 PR 描述（与此前惯例一致）。

### 6. Studio 共享源码 buffer 误部署
**修复**：
1. `sourceCode`/`sourceNote` 改为 keyed-by-slug 状态（或切换选中时重置为 SAMPLE_SOURCE 并显式
   标注"模板，非当前线上代码"）；
2. 部署前 `confirm()` 对话框：明确"将覆盖 <slug> 的线上部署，当前编辑器内容共 N 字符"；
3. 移除详情头部与源码卡片的重复部署按钮，只保留编辑器旁一个。
**测试**：同上，typecheck + 人工验证。

### 7. workerName 迁移孤儿（旧 worker 带旧 secrets 永久泄留）
**修复**：deploy 成功后，若前一 active 版本的 `providerDeploymentId` 非空且 != 新 id，调用
`executor.delete(projectRef, slug, oldId)`（delete 已容忍 404，失败仅告警不阻断）。
DeploymentRecorder.record 返回前一 active 版本 id 供调用方使用。
**测试**：admin service 单测——前后 id 不同（命名方案迁移）时 verify executor.delete(oldId)；
相同时 never。

### 8. DeploymentRecorder 错误语义
**修复**：
1. 冲突重试耗尽 → 抛 `EdgeFunctionException(CONFLICT, "DEPLOY_CONFLICT", "Concurrent deploys,
   retry")`，由 advice 渲染（不再裸 500 泄约束细节）；
2. record 整体失败时 `log.error` 带 providerDeploymentId，明确"Cloudflare 已更新而元数据写入
   失败，线上代码与版本记录已分歧"（完整补偿/回滚旧 bundle 不可行——源 bundle 不留存——以可观测性
   止损，并在 docs 限制小节注明）。
**测试**：recorder 单测——3 次冲突后抛出的异常类型与 code。

---

## P2 — 权限与一致性（4 项）

### 9. MCP functions_invoke 无门控
**修复**：`tools.ts` 的 `functions_invoke` 经 `guardedWrite('invoke edge function', ...)`（调用即
以 service_role 执行任意已部署代码，与 deploy/delete 同级），工具描述加 "Write op; disabled
unless NUBASE_ALLOW_ADMIN_WRITE=true"；CLI `cron`/`functions invoke` 子命令维持现状（用户显式
执行，非 agent 自主行为）。
**测试**：tools 测试——未授权时返回 PERMISSION_GATE_OFF；CLI invoke 不受影响。

### 10. setSecrets 半提交
**修复**：重排为三段——(a) 全量校验名称 + 全量加密（无任何写入，失败即 400/500 且零副作用）；
(b) `TransactionTemplate(metadataTransactionManager)` 短事务内批量 save（远程调用仍在事务外）；
(c) evict + sync。`SECRET_SYNC_FAILED` 文案改为"Secrets were saved but not yet applied to the
deployed function; retry or redeploy to apply"。
**测试**：admin service 单测——第二个名称非法时第一个 secret 的 save 从未发生（verify never）；
sync 失败时返回的异常文案含 "saved"。

### 11. CLI redeploy 重置函数名
**修复**：`functionsDeploy` 的 FUNCTION_EXISTS 分支只在 manifest 显式含 `name` 时才在 PATCH 里带
name（其余字段已是 undefined 即不发）；顺带删除恒真的 `typeof client.functionsUpdate ===
'function'` 守卫。
**测试**：functions.test.ts——无 manifest redeploy 时 update 调用不含 name；有 manifest.name 时含。

### 12. Studio bundle entrypoint 硬编码
**修复**：`buildSourceBundle(source, entrypoint)`——路径取 `current.entrypoint`，若以 `.ts` 结尾
则使用其 `.js` sibling 名（Studio 编辑器内容是 JS）；后端 `loadEntrypoint` 的 sibling 规则已兼容。
**测试**：typecheck + 人工（custom entrypoint 函数从 Studio 部署成功）。

---

## P3 — 性能与防腐（批量，单独提交）

13. **snippet 流式截断**（OOM 隐患，优先级在 P3 之首）：逐行序列化进 StringBuilder，超过
    RESULT_SNIPPET_MAX 即停，附 `... (N rows total)`。单测：多行大结果只保留截断前缀。
14. **syncSecrets 只传变更键**：`setSecrets` 把 `request.secrets()` 的键集传给 sync，不再全量
    decryptedEnv；单测 verify 只 PUT 变更键。
15. **PostgrestRequestContext GUC 合并单语句**：5 个 `set_config` 合并为一条
    `SELECT set_config(...), set_config(...), ...`；同时把 MultiDatabaseConfiguration 那条不准确
    的注释改写为实际语义。现有 PostgrestControllerTest 回归。
16. **DeploymentRecorder 冗余 SELECT**：`getReferenceById` 取 FK，省去重查。
17. **CLI redeploy 探测反转**：update-first、404 时 create（每次迭代省 2 次往返）。
18. **Studio 局部刷新**：patchFunction 合并 PATCH 响应进列表状态；invoke 后只刷 invocations。
19. **后端 bundle 校验**：`DeployFunctionRequest.sourceBundleBase64` 加大小上限（复用
    maxRequestBytes 级别配置）；deploy 时服务端重算 sourceHash 并以服务端值入库（客户端值仅作
    参考），消除"版本哈希说谎"。单测：超限 400；入库 hash 与 bundle 一致。
20. **防腐打包**（一个提交，纯机械）：
    - `tickMs`/`runHistoryRetentionScanMs`/`invocationLogRetentionScanMs` 死字段删除（占位符
      即真值，注释指向 yml）；
    - CLI `required()`/`requiredString` 收敛到一个共享模块（4 处→1）；标识符正则收敛为
      `IdentifierPatterns` 常量类（5 处 Java→1，SQL CHECK 加注释互链）；
    - `projectRef()`/`truncate()` 提取到公共 util；CronException/EdgeFunctionException 合并为
      共享基类 + 单一 advice；
    - Studio `formatDate`/`Info` 提取 `lib/format.ts` + 共享组件（10 处→1）；
    - tools.ts 改 dispatch 表（schema 与 handler 同处声明，杜绝"schema 声明了、switch 漏接"）；
    - 删除死代码：`InvokeResult.headers`、`EdgeFunction.privileged`（前端未读）、grid-cols-3
      残留、telescoping 构造器（调用点显式传 null）。

---

## 执行与验收

- 提交切分：P0 一个提交（并发语义）→ P1 一个（部署链路）→ P2 一个（权限/一致性）→ P3 两个
  （性能 / 防腐）。
- 每段结束跑全量：`mvn test`（含 dev DB ITs）、`node --test`（dispatcher）、
  `pnpm --filter nubase_cli test`、`pnpm --filter studio typecheck`。
- 验收红线：P0#1 的两事务竞态 IT 必须真实复现旧行为失败、新行为通过（先写测试证明能抓到 bug，
  再修）；P0#2 的 skipped 语义在 docs/scheduled-jobs.md 补充说明。
