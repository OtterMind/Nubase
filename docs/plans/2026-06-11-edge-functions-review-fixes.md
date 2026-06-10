# Edge Functions 评审问题修复计划

来源：2026-06-11 对未提交 edge-functions 变更的深度评审（7 角度 + 逐项验证）。
原则：每个修复必须附带单元测试；P0 → P1 → P2 → P3 顺序执行；V4 迁移尚未提交发布，可直接修改 V4 而无需新增 V5。

---

## P0 — 安全（必须最先修，未修复前不得发布）

### 1. 跨租户签名漏洞（dispatcher 路由头未签名）
**问题**：`cloudflare/functions-dispatcher/worker.js` 仅按攻击者可控的 `x-nubase-deployment-id` 路由（`env.NUBASE_DISPATCH.get(deploymentId)`），该值不在 HMAC payload 内；且 dispatcher 用 `new Request(targetUrl, request)` 把含 `x-nubase-signature`/`x-nubase-timestamp` 的原始请求原样转发给租户 Worker。worker 名可预测（`nubase-<ref>-<slug>`）→ 换头重放即可跨租户执行并窃取 secret 绑定。

**修复**（三件事都做，纵深防御）：
1. dispatcher 不再信任 `x-nubase-deployment-id`：从**已签名**的 `x-nubase-project-ref` / `x-nubase-function-slug` 推导 worker 名（与 Java `workerName()` 同规则），删除 deployment-id 路由；
2. Java `CloudflareEdgeFunctionExecutor.sign()` 与 worker `verifySignature()` 的 payload 同步加入 `providerDeploymentId`（即使按 1 推导，也签进去防止规则漂移）；
3. dispatcher 转发前剥除全部 `x-nubase-signature` / `x-nubase-timestamp` / `x-nubase-deployment-id` 等内部头，只保留约定传给函数的头。

**测试**：
- Java：`CloudflareEdgeFunctionExecutorTest` 新增——签名 payload 包含 deploymentId；workerName 推导规则固定（防止与 worker.js 漂移的快照断言）。
- JS：在 `cloudflare/functions-dispatcher/` 增加 `worker.test.js`（node:test 或 vitest，mock env/fetch）：
  - 合法签名 + 被换掉的 deployment-id → 401；
  - 转发到目标 worker 的请求不含任何 `x-nubase-signature`/`x-nubase-timestamp` 头；
  - 时间窗、错误签名、缺头各一例。

### 2. worker.js 路径签名不对称（编码 / base path）
**问题**：worker 用未解码的 `url.pathname.replace(/^\/[^/]+\/[^/]+/, '')` 取路径参与 HMAC，Java 端签的是 servlet 解码后的后缀；后缀含 `%20` 等编码字符或 dispatcher URL 带 base path 时合法请求 401。

**修复**：与 #1 一起重构——双方约定对**原始未解码**的后缀签名（Java 端从 `request.getRequestURI()` 截取原始编码形式），worker 端按 `x-nubase-function-slug` 之后的位置（而非"剥前两段"）切分；dispatcher URL 带 path 的形态要么显式支持要么启动时校验拒绝。

**测试**：worker.test.js 增加编码字符后缀用例；Java 侧 sign() 对含空格/中文后缀的快照测试，与 JS 测试共用同一组向量（写进两边测试的常量，保证跨语言一致）。

---

## P1 — 核心功能不可用

### 3. Secrets 永远到不了 local 执行器；Cloudflare 改密钥不生效
**问题**：secrets 只在 deploy 时传入 `executor.deploy()`，而 `LocalHttpEdgeFunctionExecutor.deploy()` 是 no-op；invoke 时只传 `defaultEnv`（2 个内置变量）。Cloudflare 下 `setSecrets` 只入库不重新部署。

**修复**：
1. `EdgeFunctionInvocationService.invoke()` 构造 env 时合并解密后的 secrets（`defaultEnv + decryptedSecrets`），由各 executor 决定传输方式：local 经 `x-nubase-env-*` 头；cloudflare 的 invoke 忽略（其 secrets 走 deploy 时绑定）；
2. `EdgeFunctionAdminService.setSecrets()`：若函数已有 active 部署且 provider=cloudflare，触发重新部署（或调 CF secrets API 热更新，首版先重部署，简单正确）；
3. 每次 invoke 解密 secrets 的开销：先接受（一次按 function_id 的索引查询 + AES 解密），在 invoke 路径加 TODO 注明可加短 TTL 缓存，不在本计划内做。

**测试**：
- `EdgeFunctionInvocationServiceTest`（新建）：mock secret repo + executor，断言 invoke 请求 env 同时含内置变量与解密 secrets；
- `EdgeFunctionAdminServiceTest`：setSecrets 在"已部署 + cloudflare"时调用 executor.deploy，本地/未部署时不调用。

### 4. LazyInitializationException（activeVersion 懒加载越过事务边界）
**问题**：`EdgeFunction.activeVersion` 为 `@OneToOne(LAZY)`，controller 在事务关闭后调用 `EdgeFunctionResponse.from()` 读 versionNo → 任何部署过的函数使 list/get/patch 全部 500（open-in-view: false，且自定义 metadata EMF 也不受 OSIV 覆盖）。

**修复**：DTO 映射移进事务内——`EdgeFunctionAdminService` 的读方法直接返回 `EdgeFunctionResponse`（service 层完成 `from()` 映射），controller 不再接触实体。比 `@EntityGraph` 更优：顺带消除实体泄漏到 controller 的问题；list 场景为避免 N+1 再给 `findByProjectRefOrderByCreatedAtDesc` 加 `@EntityGraph(attributePaths = "activeVersion")`。

**测试**：`@DataJpaTest`（指向 metadata EMF 配置）+ service 切片：保存 function+version 并设 activeVersion，事务外调用 service 返回的 DTO 断言 versionNo 可读不抛 LazyInitializationException。

### 5 + 6. 失败调用日志被回滚 & 事务横跨 30s HTTP 调用占满连接池（一并修）
**问题**：`invoke()` 标注 `@Transactional("metadataTransactionManager")`：(a) 重抛 RuntimeException 使 finally 里 save 的日志行回滚——失败调用全部无日志；(b) metadata 连接（Hikari max 10）被持有横跨整个 executor HTTP 调用（默认 30s 超时），10 个并发慢调用即可饿死全部控制面。

**修复**：
1. 去掉 `invoke()` 上的 `@Transactional`；函数查询用 repository 默认只读事务（单条 SELECT 不需要外层事务）；
2. 日志落库抽成独立组件方法 `EdgeFunctionInvocationLogWriter.write(...)`，标注 `@Transactional(transactionManager = "metadataTransactionManager", propagation = REQUIRES_NEW)`，并 catch 落库自身异常只打 log（日志失败不应让调用失败）；
3. executor.invoke() 在任何事务之外执行。

**测试**：
- 单测：mock repo，invoke 抛 `EdgeFunctionException` 时 logWriter 仍被调用且携带正确 status/errorCode；
- 集成测（@SpringBootTest + H2/PG metadata 数据源）：调用不存在的函数 → 抛异常后 `edge_function_invocations` 表中存在 1 行失败记录（这是回滚 bug 的直接回归测试，纯 mock 测不出事务语义）。

### 7. entrypoint 被丢弃 + 服务端 TS 伪转译
**问题**：`EdgeFunctionDeploymentRequest` 不携带用户设置的 entrypoint，`loadEntrypoint()` 硬编码 index.ts/index.js；`transpileEntrypoint` 只有 3 个字符串 replace，真实 TS 上传即得非法 JS。

**修复**：
1. `EdgeFunctionDeploymentRequest` 增加 `entrypoint` 字段，`EdgeFunctionAdminService.deploy()` 传入，`loadEntrypoint(bundle, entrypoint)` 按其查找；
2. 删除 `transpileEntrypoint`/`stripEsmDefault` 的伪转译：entrypoint 为 `.ts` 且未经打包时，部署直接失败并返回明确错误 `TYPESCRIPT_REQUIRES_BUNDLE`（提示 CLI 使用 `--bundle`）；
3. CLI（`functions.ts`）：当检测到 entrypoint 是 `.ts` 时**默认**走 esbuild 打包（`--no-bundle` 可关闭），打包产物即 `index.js`，与 2 配合形成闭环；
4. 同步清理 worker 模块生成里的死代码（`__module`/`__exports`/userDefault 不可达分支）。

**测试**：
- `CloudflareEdgeFunctionExecutorTest`：自定义 entrypoint=main.js 可部署；entrypoint=index.ts 未打包 → 抛 TYPESCRIPT_REQUIRES_BUNDLE；
- `functions.test.ts`：`.ts` 入口默认触发 esbuild 打包路径（mock esbuild）。

### 8. Redis 限流 INCR/EXPIRE 非原子 + 失败时重复计数
**问题**：INCR 与 EXPIRE 两次调用，中间崩溃/出错则 key 永无 TTL → 永久 429；expire 抛错时 catch 落入本地计数器，同一请求被计两次。

**修复**：
1. 改用单条 Lua 脚本（`DefaultRedisScript<Long>`：INCR，若返回 1 则 PEXPIRE，返回计数），原子完成；
2. fallback 逻辑收紧：仅当 Redis 调用**整体失败**（脚本执行抛异常）才落本地计数，消除双计数。

**测试**：扩展 `EdgeFunctionRateLimiterTest`：mock RedisTemplate.execute 验证走脚本且只调一次；脚本抛异常时本地计数仅 +1；本地 fallback 限流语义保持原有用例通过。

### 9. CLI `functions invoke` 对函数正常 4xx/5xx 直接崩溃
**问题**：`rawRequest` 对任何 `!response.ok` 抛错，信封 `{status, headers, data}` 永不可达；`index.ts` functions 分支无 try/catch → 未处理 rejection。

**修复**：`rawRequest` 增加 `throwOnError: false` 选项（或新 `rawRequestAllowError`），`functionsInvoke` 用之，任何状态码都返回信封；`index.ts` 的 functions 分支包 try/catch，错误打印 JSON 后 `process.exit(1)`。

**测试**：`functions.test.ts`/`nubase-client` 测试：mock fetch 返回 422 → functionsInvoke 返回 `{status: 422, ...}` 不抛；网络错误仍抛。

### 10. 审计列 platformUserId 恒为 NULL
**问题**：唯一的 `setAttribute("platformUserId",...)` 在 `AdminInitAuthFilter`，只对 `/auth/v1/admin/*` 生效；`/functions/admin/v1/**` 走 `UnifiedMultiTenancyFilter`，attribute 永远为 null。

**修复**：先调查 Studio/CLI 调 `/functions/admin/v1/**` 时是否随 service_role apikey 一并携带平台用户 JWT：
- 若有：通过现有 `SecurityUtil`（或扩展 UnifiedMultiTenancyFilter）解析平台用户并提供统一访问器，`EdgeFunctionAdminController` 改用它，删除魔法字符串 attribute 读取；
- 若无：本期把三个 `*_by_platform_user_id` 入参与 DTO 字段标记为可空保留、controller 删除死读取，并在 V4 中删掉无意义的 `idx_edge_functions_project_created_by` 索引（列保留备将来）。

**测试**：按调查结果——有 JWT 路径则 controller 测试断言审计字段写入；否则断言创建流程不再依赖该 attribute。

### 11. `nubase.functions.enabled` 是死开关
**问题**：无任何代码绑定/读取该属性，SecurityConfig 无条件 permitAll `/functions/**`。

**修复**：`ai.nubase.functions` 包内所有 `@Service`/`@RestController`/`@Scheduled`（retention）/executor 配置类统一挂 `@ConditionalOnProperty(name = "nubase.functions.enabled", havingValue = "true", matchIfMissing = true)`（放在一个 `@Configuration` + `@ComponentScan` 入口类上最简洁，避免逐类标注）。禁用时 controller 不注册 → permitAll 路径返回 404，可接受。

**测试**：`ApplicationContextRunner` 测试：`nubase.functions.enabled=false` 时 `EdgeFunctionGatewayController`/`EdgeFunctionAdminService`/retention bean 不存在；缺省/true 时存在。
⚠️ 记忆教训（JSqlParser 启动崩溃）：条件装配这类问题编译期与普通单测发现不了，必须有 context 级测试。

---

## P2 — 性能 / 运维（发布前应修）

### 12. 保留期清理：派生删除 → 批量删除 + 索引
- `EdgeFunctionInvocationRepository.deleteByCreatedAtBefore` 改为 `@Modifying @Query("delete from EdgeFunctionInvocation i where i.createdAt < :cutoff")`（JPQL bulk delete，无 ON CONFLICT 风险）；retention service 改为分批循环（每批 LIMIT，native）或先接受单条 JPQL bulk（已消除逐实体加载与 OOM）；
- V4 直接补 `idx_edge_invocations_created_at ON edge_function_invocations (created_at)`。
- **测试**：@DataJpaTest 插入新旧数据，prune 后只剩新数据；repository 方法标注 @Modifying 的编译期保障 + context 加载测试覆盖启动。

### 13. 索引修正（直接改 V4，未发布）
- `idx_edge_invocations_project_function_caller (project_ref, function_slug, caller_user_id, created_at DESC)` → `(project_ref, function_slug, created_at DESC)`；
- 删除两个无查询使用的 caller 索引、删除与唯一约束重复的 `idx_edge_functions_project_slug`。
- **测试**：迁移由 Flyway context 测试覆盖（应用可启动、表/索引存在）。

### 14. CLI 退出码
- `index.ts`：functions 命令结果若 `result?.success === false` → `process.exit(1)`；配合 #9 的 try/catch。
- **测试**：单测 runFunctionsCommand 返回 PERMISSION_GATE_OFF 时主流程以非零码退出（抽出可测的 `resolveExitCode(result)` 纯函数测它）。

### 15. esbuild 移出硬依赖
- `package.json`：esbuild 从 `dependencies` 移到 `optionalDependencies`（代码已按可选动态导入处理，行为不变）。
- **测试**：现有 fallback 错误信息用例保留；package.json 断言可加一个简单 shape 测试（可选）。

### 16. `listFiles` 遍历剪枝
- `functions.ts`：`readdir(dir, { withFileTypes: true })`，在递归**之前**跳过 `node_modules`/`.git`，去掉逐项 `stat`。
- **测试**：临时目录造 node_modules 大量文件，断言产物不含且（用 spy/计数）未被遍历。

### 17. Studio functions 页重复拉取
- `page.tsx`：`load` 的 deps 去掉 `selected`，自动选中改 `setSelected(prev => prev ?? fns[0]?.slug ?? null)`；选中变化不再触发全量 refetch。
- **测试**：该 app 若无组件测试基建则以人工验证 + 代码评审为准（在 PR 描述注明）；有 vitest/RTL 则补一条"点击侧栏不触发 fetch"用例。

---

## P3 — 重复 / 简化（随手修，防止腐化）

18. **执行器去重**：抽 `AbstractHttpEdgeFunctionExecutor`（或包级 util）容纳 httpClient DCL、buildUrl、buildRequestBody、readBody、toHeaderMap、firstHeader；hop-by-hop 头黑名单收敛为 `HeaderSanitizer` 暴露的唯一常量，4 处引用同一来源。现有两个 executor 的测试保持绿即回归。
19. **网关错误路径**：删除 catch 块手拼 JSON 与 `escape()`，统一抛 `EdgeFunctionException` 由 `EdgeFunctionExceptionHandler` 渲染（413 同样改为抛异常）；请求体读取改用 `RequestUtil.readRawRequestBody(request, maxBytes)`（流式限长 + 缓存回退），catch 其 `IllegalArgumentException` 映射为 413 异常。测试：`EdgeFunctionGatewayControllerTest` 改为断言异常抛出/advice 渲染，新增"错误消息含换行仍为合法 JSON"。
20. **MetadataJpaConfig 收窄**：basePackages 回到精确列表 `{"ai.nubase.metadata.repository", "ai.nubase.metadata.edge.repository"}`，entity 包同理。测试：context 启动 + 两个 EMF 各自 repo 可用（现有集成测试覆盖则不另写）。
21. **死代码清理**：删 `ListResponse` 记录、`EdgeFunctionInvocation.callerPlatformUserId`（含 V4 列与 DTO 字段——V4 未发布可直接删）、统一 `callerType`/`callerRole` 为单一字段单一词表（V4 同步删列）、删 `EdgeFunctionDeploymentRequest` 中无人读取的 `sourceHash`/`artifactUri`（确认 Studio placeholder 用到 artifactType 则保留它）、删 invoke 路径上多余的 env 双头传输（保留 `x-nubase-project-ref`/`x-nubase-function-slug` 专用头）。
22. **（可选，单独 PR）限流器合并**：扩展 `auth.service.RateLimiterService` 出 `checkRate(key, limit, window)` 通用 API 后让 edge functions 复用。涉及 auth 行为，风险大于收益，明确推迟。

---

## 执行顺序与验收

1. 分支：基于当前工作区（变更尚未提交，先 `git add` 现状做一次基线 commit 再修，便于 review diff）。
2. 顺序：P0(1,2) → P1(3→11) → P2(12→17) → P3(18→21)；每完成一项跑对应测试，每个优先级段落结束跑全量：
   - 后端：`mvn test`（含新增 context/集成测试——注意启动级失败只有它们能抓到）；
   - 前端：`pnpm -C frontend test`（mcp-bridge vitest）、`pnpm -C frontend build`（studio 编译）；
   - worker：`node --test cloudflare/functions-dispatcher/`。
3. 验收清单：上方每个编号问题在测试中有对应回归用例；P0 两项额外做一次人工攻击场景走查（换 deployment-id 重放被 401）。
