# 项目专家评审会议纪要 —— agent-flow（P6 首轮）

## 0. 会议信息

- 输入代码库：`/Users/a/source/agent-flow/server/agent-flow-server`（4 模块：platform-common / workflow-engine / node-adapters / server-application）
- 评审 skill：`.qoder/skills/expert-review-meeting/SKILL.md` + `ROLES.md`
- 线上现状：**无线上流量**。唯一运行形态为本机 macOS Docker（10C/16G [假设：用户提供"就用本机这台评"]）
  单实例 compose：`agent-flow-server-1` + `agent-flow-db-1`（pgvector PG16），当前 0 用户 / 0 工作流 / 3 条迁移。
- 目标量级：**十万 QPS**（用户裁决）
- 待核实数字全部标 `[假设]`，汇总见 §6。
- 本次实测取证（会上作为物证引用）：PG 停机后 `GET /actuator/health/readiness` 仍返回 `{"status":"UP"}`（见 §2 运维 3）。

## 1. 执行摘要（先给结论）

- 发布决议：**双轨**
  - 作为**单作者自用创作工具**（当前实际用途）：**CONDITIONAL GO** —— 无数据正确性破坏，🛑 项集中在"多租户/对外"这一层，自用形态可豁免但需挂账。
  - 作为**对外多用户 SaaS**：**NO-GO** —— 两条 🛑 分别命中"跨租户密钥可读"（安全裸奔）与"健康探针假阳性 + fail-fast 时机错位"（启动可用性）。
- 现状容量水位（口径：单实例、HTTP 读接口 vs 工作流执行两类流量分开计）：
  - 短平快 CRUD：约 **2 万 QPS/实例**（Tomcat 200 线程默认 × 1/RT(5ms[假设]) 与 CPU 10 核 × 1/0.5ms[假设] 取 min）；
  - 创作工作流执行：约 **0.36 次/秒/实例 ≈ 1,300 次/小时**（算式：`16 slots ÷ 45s 端到端 RT[假设：copy.write 15–30s + image.cover 20–60s]`）；
  - 登录/注册（匿名共享桶）：**2 次/秒/实例**（算式：`rate-limit-per-minute 120 ÷ 60`，见 §2 安全 2）。
- 到十万 QPS 的第一断裂点：**`ExecutionManager` 执行闸门 + 下游 LLM/图像配额**，不是 Web 容器 ——
  依据 [ExecutionManager.java:26](file:///Users/a/source/agent-flow/server/agent-flow-server/server-application/src/main/java/com/agentflow/server/exec/ExecutionManager.java#L26)、[:43](file:///Users/a/source/agent-flow/server/agent-flow-server/server-application/src/main/java/com/agentflow/server/exec/ExecutionManager.java#L43)。
  十万 QPS 若都是"提交创作"，需 `100000 ÷ 0.36 ≈ 27.7 万实例`——设计目标本身不自洽，必须先做流量分类（读/写/长任务分接口分档位）。
- 分级汇总：🛑 **4** 项 / 🔶 **5** 项 / ⚠️ **7** 项 / ✅ **11** 项。（2026-10-22 P7-1 已把四条 🛑 全部修完并实机验证，见 §7；🔶/⚠️ 未动，故本会议决议维持不变。）

### 1.5 企业级开工基线（`enterprise-project-bootstrap` §1–§10）完成度核对

| skill 节 | 要求 | 现状 | 证据 |
|---|---|---|---|
| §1 仓库底座 | .gitignore / README 四节 / 父 BOM 子自选 | ✅ | [README.md](file:///Users/a/source/agent-flow/README.md)（快速开始/配置参考/架构/已知局限/生产部署五节） |
| §2 配置治理 | 默认安全可跑、三处一致、env 注入 | ✅ | [application.yml:33-57](file:///Users/a/source/agent-flow/server/agent-flow-server/server-application/src/main/resources/application.yml#L33)，`enabled: false` 默认 + prod profile 覆盖，占位 `${VAR:}` 首次调用才报错 |
| §3 外部调用四件套 | 超时+捕获+降级+重试 | ✅（本次推翻侦察报告"无超时"指控） | [HttpJson.java:39,103](file:///Users/a/source/agent-flow/server/agent-flow-server/node-adapters/src/main/java/com/agentflow/nodes/support/HttpJson.java#L103)、[WechatDraftNodeExecutor.java:83,302](file:///Users/a/source/agent-flow/server/agent-flow-server/node-adapters/src/main/java/com/agentflow/nodes/wechat/WechatDraftNodeExecutor.java#L302) 全部 connectTimeout(10s)+per-request timeout |
| §4 异常边界 | advice + code/message/traceId + 路径穿越 | ✅ | `@RestControllerAdvice` + `JdbcContentStore.resolveInside` |
| §5 安全底座 | 鉴权过滤器骨架、密钥 env、**危险端点 SENSITIVE 标记**、日志脱敏 | ⚠️ 部分 | 过滤器/密钥 ✅；全仓 `grep SENSITIVE` **0 命中**：路由上没有敏感标记，鉴权收口靠人工回忆（§4 #7） |
| §6 可观测性 | health + **metrics** + 慢请求日志 + 四金指标 | ⚠️ 部分（P7-1 后） | metrics 侧已补：`micrometer-registry-prometheus` + `exposure.include: health,info,prometheus` + 5 项业务指标（§7 #3/#4 证据）；**慢请求日志仍缺**，readiness 组已含 db |
| §7 存储与数据 | Hikari + 换算注释、Flyway、禁 SELECT * | ✅ | [application.yml:74-79](file:///Users/a/source/agent-flow/server/agent-flow-server/server-application/src/main/resources/application.yml#L74) 含定容注释；Flyway 3 迁移已在 PG16 实跑 |
| §8 CI 与测试对称 | CI + 每模块有测试 | 🟡 文件在、运行未证 | `.github/workflows/ci.yml` 存在，本地 196 测试 0 失败；但 P3 之后的提交仍未推远端 ⇒ **GitHub Actions 从未针对当前代码跑过**，"CI 绿灯"目前只是文件级证据（详见 PROGRESS.md 20:50 变更日志） |
| §9 部署形态 | compose 随仓库、优雅停机、启动自检 | ✅ P7-2b | compose/`stop_grace_period: 30s` ✅；fail-fast 已前移到 `StartupSelfCheck` 的 `@PostConstruct`（端口绑定之前），反向测试 `P7FailFastTimingTests` 断言启动失败后端口连不上 |
| §10 交付自检 drift-check | 落地当天跑 review | ✅ 本次即该项兑现 | 本文件 |

**结论：bootstrap 的 10 节里 7 节已达标，可观测性（§6）与安全标记（§5）两节是企业级意义上的真实欠账，"已完成企业级落地"这句话目前不成立。**

## 2. 四专家发言实录

### 🧑‍💼 资深产品

1. **承诺 vs 交付**：README 与 PROGRESS 声称"企业级生产标准""发布闭环"。发布闭环的最后一站 `publish.local` / `xhs.draft` / `wechat.draft` 已实机跑通（草稿入库、本地归档），这部分**不是虚假承诺** ✅。但"生产标准"里对用户可见的两项没有落地证据：多用户隔离（见研发 1）与运维可观测（见运维 3）。→ ⚠️ 预期管理偏差，需在 README「已知局限」明确写"当前仅单作者/可信小团队形态"。
2. **业务高峰的用户后果（翻译成用户语言）**：闸门 16 且 `slots.acquire()` 无超时、无排队上限 → 第 17 次提交不会失败，而是**静默等待**。用户视角：点了"生成"，页面显示 RUNNING，**排队时间不计入任何指标**；批量 50 篇选题排产时，前 16 篇各 45s，第 50 篇等待 ≈ `(50-16)/0.36 ≈ 95 秒` 才开始跑，全程无法预估完成时间，也没有"前面还有几个"的反馈。定时任务（cron）撞 peak 时同样排队，用户以为"没触发"。
3. **成本推算（按调用计费）**：一次创作 ≈ copy.write（qwen-plus，输入素材 12k 字 ≈ 8k token + 输出 ≈ 1.5k token）+ image.cover（1 张，可选多张）。
   `单次成本 ≈ 9.5k token × 单价 + 图片单价`；0.36 次/秒/实例 ⇒ **单实例日产能上限 ≈ 31,100 篇/天**（`0.36×86400`，口径：满负荷、不含失败重试）。真正的天花板不是本机算力而是**百炼/万相的 QPS 与并发配额** [假设：未申请提额]。十万 QPS 目标若指"提交创作"，token 成本按 `100000×9.5k ≈ 9.5 亿 token/秒` 计算——不存在任何预算能支撑，说明目标量级必须按接口拆分（详见 §5）。
4. **风险披露一致性**：PROGRESS.md 昨天记录"实机 prod 形态已跑通 ✅"，本报告指出同一形态下 readiness 探针在数据库宕机时仍报 UP——两条陈述并不矛盾（跑通≠可运维），但**对外话术必须补一句"单作者自用"**，否则就是承诺漂移。
5. **上线节奏裁决建议**（针对 §4 四条 🛑）：三选一里建议**砍范围**——即明确"本版本只服务作者本人 + 少数受信同事，不开放注册"，从而把 🛑 #1/#2 从"上线阻塞"转为"设计边界"，同时把 #3/#4（可观测、fail-fast 时机）本迭代内修掉。带险上线（开放注册）不建议。

### 👨‍💻 资深研发

1. **单实例容量推算（写死算式）**
   ```
   线程池QPS = 200 线程 / 0.005s(读RT[假设])      = 40,000
   CPU  QPS  = 10 核 × 1 / 0.0005s(单请求CPU秒[假设]) = 20,000
   连接池QPS = 20 conn / 0.02s(单查询RT[假设])     =   1,000   ← 读接口的实际约束
   单实例读QPS ≈ min(40000, 20000, 1000) = 1,000（DB 连接是第一个约束，不是 CPU）
   执行闸门  = 16 / 45s                            =   0.36 次/秒
   十万 QPS（读）需实例数 = 100000×0.005 / 1000 ÷ 0.7 ≈ 8 实例 → 但 8×20=160 conn > PG max_connections 默认 100 → 必须先上 PgBouncer
   ```
   注：`spring.threads.virtual.enabled` 在 yml 未设置 → Web 层走平台线程 200（口径已确认，非虚拟线程）。
2. **每请求必经的串行点**：
   - [ExecutionManager.java:30](file:///Users/a/source/agent-flow/server/agent-flow-server/server-application/src/main/java/com/agentflow/server/exec/ExecutionManager.java#L30) `Semaphore(16, fair)` —— 全局公平队列，**死法：0.36 exec/s 时开始排队，1 QPS 持续提交即永久积压**（进入速率 1 > 服务速率 0.36）。
   - 定时触发：cron CAS 认领 `next_fire_at` 是**跨实例正确的**（✅ 加分项），但触发后仍进同一个 16 闸门，多实例部署时"每实例 16"= 全局 16×N，与注释里"多实例改全局配额归 P3 限流层"一致 —— 该 P3 承诺**未兑现**，记 ⚠️。
   - `PasswordHasher` PBKDF2 **120,000 次迭代**（[PasswordHasher.java:19](file:///Users/a/source/agent-flow/server/agent-flow-server/platform-common/src/main/java/com/agentflow/common/security/PasswordHasher.java#L19)）：单次 ≈ 80–120ms CPU → **10 核 ≈ 83–125 次登录/秒** 的硬顶。这是刻意的安全代价 ✅，但它使"登录接口扛十万 QPS"成为伪命题，且给匿名调用者一个廉价的 CPU 耗尽向量（见安全 2）。
3. **水平扩容正确性（扩容坏正确性 = 🛑）**：
   - **凭证跨租户读取**：`JdbcCredentialProvider.resolve(credentialId)` → `CredentialRepository.findSecretEnc(id)`，SQL 是 `SELECT secret_json_enc FROM user_credentials WHERE id = ?`，**无 owner_id 约束**（[CredentialRepository.java:56-59](file:///Users/a/source/agent-flow/server/agent-flow-server/server-application/src/main/java/com/agentflow/server/persistence/CredentialRepository.java#L56)）；执行链路只从 DSL `params.credentialId` 取一个 long，不做归属校验（P3CredentialE2ETests 亦只测"存在即可用"）。→ **任何登录用户在自己的工作流里写别人的 credentialId，即可用他人微信凭证往他人公众号发草稿**。这是"多实例前必须先修"的正确性缺陷，与扩容无关但同级严重。🛑
   - **uid=0 即全域 admin**：[Principal.java:15-24](file:///Users/a/source/agent-flow/server/agent-flow-server/server-application/src/main/java/com/agentflow/server/security/Principal.java#L15) `isAdmin() = ROLE_ADMIN.equals(role) || uid == SYSTEM_UID`；[AuthFilter.java:48](file:///Users/a/source/agent-flow/server/agent-flow-server/server-application/src/main/java/com/agentflow/server/security/AuthFilter.java#L48) 匿名（enabled=false）与 [:70](file:///Users/a/source/agent-flow/server/agent-flow-server/server-application/src/main/java/com/agentflow/server/security/AuthFilter.java#L70) API-token 主体都是 `SYSTEM_UID`。`canAccess()` 对 uid 0 一律放行 ⇒ **一个 `X-API-Token` 就等于全站所有用户的数据钥匙**，且该 token 是单个静态串、无 kid、无过期、无按人拆分。多实例/多租户形态下这是 🛑。
   - 本地文件成品库 `library-dir`（application.yml:57）：多实例各写各的卷 ⇒ 列表页只看到本机产物，**分片缺失数据**（已记 README 局限，⚠️）。
4. **降级路径真实性**（逐行验证）：HTTP 三类异常（超时/连接拒绝/协议异常）确有覆盖，且降级显性 `NodeOutput.degraded(...)`（[ArticleExtractNodeExecutor.java:132](file:///Users/a/source/agent-flow/server/agent-flow-server/node-adapters/src/main/java/com/agentflow/nodes/ingest/ArticleExtractNodeExecutor.java#L132)、[HttpCollectNodeExecutor.java:40](file:///Users/a/source/agent-flow/server/agent-flow-server/node-adapters/src/main/java/com/agentflow/nodes/http/HttpCollectNodeExecutor.java#L40)）—— **✅ 通过，侦察阶段"无超时=降级永不触发"的指控被推翻**。
   但 `orTimeout` 的语义只放弃等待，不取消任务：[WorkflowEngine.java:137-138](file:///Users/a/source/agent-flow/server/agent-flow-server/workflow-engine/src/main/java/com/agentflow/engine/WorkflowEngine.java#L137) `orTimeout(...).join()`，节点线程池是 `newVirtualThreadPerTaskExecutor`（:43，**无界**），而 `DashScopeImageClient.java:73` / `WanxiangImageNodeExecutor.java:157` 的轮询循环用 `Thread.sleep(pollIntervalMs)` 不响应中断 ⇒ **执行被判 TIMEOUT 后，节点仍在跑完并向微信/万相真实付费、真实提交草稿**。用户看到"失败"点重试 ⇒ 平台出现重复草稿。这是"状态与实际副作用不一致"，🔶（发生概率：图像/微信 API P99 抖动即可触发；影响面：作者账号内容；修复成本：约 1 人日，publish 节点加 execution+node 幂等键）。
5. **异步化边界** ✅：长任务一律 `submit` 返 executionId + 轮询详情，带 traceId，秒级以上不占 HTTP 线程。缺：无断点续跑（重启即在跑的执行变 RUNNING 孤儿——`store` 状态无心跳回收，⚠️）。
6. **静默失败清单**：
   - `JdbcCredentialProvider.java:39,44,54` 三处 `log.warn` 后返回 `Optional.empty()`，调用方按"未配置凭证"报节点失败 —— 有显性错误码，可接受 ✅；
   - [StartupSelfCheck.java:56-58](file:///Users/a/source/agent-flow/server/agent-flow-server/server-application/src/main/java/com/agentflow/server/startup/StartupSelfCheck.java#L56)：`prod` 且 `enabled=false` **只 log.error 不拒绝启动** → 一个 env 拼写错误就得到"全站零鉴权但看起来健康"的实例。🔶
   - [CredentialRepository.java:83](file:///Users/a/source/agent-flow/server/agent-flow-server/server-application/src/main/java/com/agentflow/server/persistence/CredentialRepository.java#L83) `orElseThrow(INTERNAL, "不应发生")` ✅ 未吞。

### 🛠 资深运维

1. **资源换算**：容器 `JAVA_OPTS=-XX:MaxRAMPercentage=75`（[Dockerfile](file:///Users/a/source/agent-flow/server/agent-flow-server/Dockerfile)）在 16G 主机、compose 未设 `mem_limit` [假设] ⇒ 堆上限按宿主可见内存 75% 计 ≈ **12G 堆**，但容器若被限到 2G 则 JVM 读的是宿主内存而非 cgroup 值（JDK 21 默认可感知 cgroup，此处口径需在限额落地后重算）。加上元空间 ~100MB + 200 平台线程栈 ~200MB + 虚拟线程堆外 + PG shared_buffers ⇒ **本机与其他 21 个中间件容器共池，agent-flow 实际可支配内存远小于 16G**。建议 compose 补 `deploy.resources.limits` 并把 `MaxRAMPercentage` 调到 50。⚠️
2. **连接池对 DB 的总账**：`1 实例 × 20 = 20` ≪ PG 默认 100 ✅（现状）；扩到十万 QPS 读需 ≈ 8 实例 ⇒ 160 > 100 ⇒ **必须先 PgBouncer 或把池降到 10/实例**（DBA 域详述）。
3. **可观测性 —— 本次最硬的运维缺陷（🛑）**：
   - 指标：`exposure.include: health,info`（application.yml:30）+ 无 micrometer-registry-prometheus ⇒ **`/actuator/prometheus` 不存在，四金指标（QPS/RT/错误率/饱和度）一个都采不到**；`Semaphore` 排队深度、执行成功率、下游 429 全为暗箱。
   - 探针：**实测**停掉 `agent-flow-db-1` 后 `curl :8080/actuator/health/readiness` 仍返回 `{"status":"UP"}`（默认 readiness 组只含 readinessState，DB 指示器不在其中）⇒ compose 每 15s 的健康检查在数据库全灭时依旧判 healthy，`restart: unless-stopped` 永远等不到重启，编排层也永远不会摘掉这个实例。**健康检查是一个会说谎的绿灯**：故障时用户收到 500，运维侧看到的仍是"健康"。修复成本 ≈ 20 行配置：显式 `management.endpoint.health.group.readiness.include: readinessState,db,ping`。
   - 告警：无任何阈值/告警配置（compose 无 alertmanager，仓库无 runbook）。缺项记 ⚠️。
   - 日志：结构化 pattern + `traceId=%X{traceId}` + 响应头 `X-Trace-Id` ✅；但无采样/无慢请求日志（>1s 不打点），⚠️。
4. **发布与故障恢复**：`server.shutdown=graceful` + `timeout-per-shutdown-phase: 20s` + `stop_grace_period: 30s` ✅，昨天实测优雅停机 1s；依赖重启行为：**PG 重启后应用自动恢复（Hikari 重建连接）✅，但期间 readiness 已谎报 UP**；备份：已有 `scripts/backup-postgres.sh`（backup/verify/drill）并完成一次内容级恢复演练（PROGRESS 10-22 22:25）；**仍未自动定时、未异地** ⇒ 🟡（DBA 域 5，自动化未做）。
5. **单点清单**：单实例服务 + 单实例 PG（无副本、无流复制）+ **本地文件成品库**（`library-dir`，容器卷）+ 进程内限流窗口（`RateLimitFilter.windows`，多实例各自计数 ⇒ 实际限额=120×N，注释已自认）+ 进程内 Semaphore 闸门。前五项目前都接受为"自用形态"，写进 README 局限即加分项。
6. **流量突增防御**：入口有固定窗限流 ✅（但见安全 2 的共享桶副作用）；下游无熔断/无舱壁（LLM 与微信共用同一节点线程模型，无独立配额），无 MQ 削峰 ⇒ 上游一慢，16 个槽位瞬间占满，后续全部排队，**没有任何开关可以丢任务保新任务**。🔶
7. **弹性**：人工扩缩；扩容后无预热（凭证缓存/连接池冷启动）；`MAX_CONCURRENT_EXECUTIONS=16` 是**编译期常量**（ExecutionManager.java:26），不可配置 ⇒ 每次改容量都要重新构建镜像，运维上等于"容量不能应急调"。⚠️ 应外置为 `agentflow.exec.max-concurrent`。

### 🗄 资深 DBA

1. **数据量推算（写算式）**：现状几乎无数据（0 用户/0 工作流）。按产品口径估：`日增 = 作者数 × 每人每日提交 × 行数/次`。设 [假设] 100 作者 × 20 次/日 ×（1 executions + N 节点≈8 node_runs + 1 artifact）≈ **1,000 次执行/日 ⇒ ≈ 1 万行/日**，含索引膨胀系数 2 ⇒ 年增 ≈ 730 万行。`execution_logs/node_runs` 单表 **约 2 年到 5,000 万行**（口径：日均 1k 执行、8 节点、行均 <2KB）。
   → 判定：未到分区门槛，但**必须现在定归档策略**（node_runs 输出正文可达 12k 字符 TEXT，是最快膨胀的列；建议 90 天归档 + 输出列改指对象存储/成品库）。
2. **存储选型对号入座**：当前"单机 PG + 本地文件索引"处于 **≤千级 QPS 档**，配套基线（单实例 PG + 本地索引）已满足 ✅。
   判定句式：**当前实现第一断裂点在"进程内执行闸门 + 无全局配额"，撑到十万档需替换为「MQ 削峰 + 分布式令牌（Redis/DB 全局配额）+ 读写分离 + PgBouncer + 成品库上对象存储」，依据 `16/45s=0.36`、`实例数×20>100`、`多实例各自 Semaphore(16) 与各自 120/min`。**
3. **访问模式审查**：JdbcTemplate 全参数化 ✅ 无拼接；无 `SELECT *`（投影显式，见 CredentialRepository）✅；`listByOwner` 无分页且按 owner 全量返回 —— 成品库/执行历史同类查询在数据量上来后是**无界结果集**，⚠️；批量写：`node_runs` 逐行写（执行链路每节点一次 UPDATE/INSERT），十万档下 ≈ 每执行 8 次往返，需批处理或改异步聚合，⚠️；连接获取全部走 Hikari ✅ 无 DriverManager 裸连。
4. **一致性与迁移**：Flyway 3 条迁移、prod 启动即跑 ✅（**注意：Flyway 在启动阶段执行 DDL，滚动发布时新旧版本共存窗口内的 DDL 兼容性需在加不兼容列前先走 expand-contract**）⚠️；多存储写入顺序：DB 记录 → 文件成品库，**文件写失败仅日志，无补偿入口**（README 挂账）⇒ 半套数据风险 ⚠️；向量/倒排：本项目无，跨档无冲突。
5. **持久性与容灾**：单机 PG、卷 `pgdata`；恢复演练 10-22 已做且验到行级 md5 与"恢复库能被应用直接消费"（`scripts/backup-postgres.sh drill`），但**自动定时与异地副本仍未做** ⇒ 按 skill 判据"单机无备份 = 🛑"，因当前数据可重建（prod 库 0 用户/0 工作流）且属个人创作工具，记 🟡（上线对外即升 🛑）。剩余修复动作：cron 定时 + dump 送对象存储 + 失败告警，成本 0.5 人日。
6. **凭证主密钥治理**：AES-256-GCM 逐条随机 IV ✅；**无 key id、无轮换机制** ⇒ 主密钥泄露/轮换需全量重加密，运维上目前没有该流程 ⚠️。

### 🔒 安全（独立成节）

1. **鉴权与授权**：
   - 🛑 跨租户凭证读取（研发 3 第一条）：`WHERE id = ?` 无 owner，属"无鉴权即可读他人密钥并使用"。
   - 🛑 `X-API-Token` = 超级管理员：单一静态串、恒时比较 ✅（`MessageDigest.isEqual`，AuthFilter.java:66-69 实现正确），但**主体是 uid=0 ⇒ 全站数据可读写、可触发花钱任务**，且无按调用方拆分、无过期、无审计日志。运维脚本 `scripts/run-creation.sh` 依赖它 ⇒ 一旦 token 进入 CI 日志即全站沦陷。
   - ✅（P7-2a 已修，10-22 21:30）开放注册即 admin 引导：原 `/api/auth/` **原始 URI** 前缀白名单（AuthFilter.java:84）+ "首个注册用户自动 admin" ⇒ **谁先注册谁拿到管理员**。**现状**：`AuthService.register()` 一律落 `user`（[AuthService.java:42](file:///Users/a/source/agent-flow/server/agent-flow-server/server-application/src/main/java/com/agentflow/server/security/AuthService.java#L42)），管理员只由 `AdminBootstrap` 按 `AGENTFLOW_BOOTSTRAP_ADMIN_USERNAME` + `..._PASSWORD`(`_FILE`) 显式注入，口令长度越界直接拒绝启动、未配置则 WARN 且**系统里没有管理员**；白名单同步改为归一化后的精确路径集合（P7-1b），`//api/auth`、`/api/auth/../workflows/1` 已在实测中回到鉴权。证据：`P7AdminBootstrapE2ETests` 3/3（bootstrap 得 admin、注册得 user、admin 跨属主可读而他人 404）+ `P3AuthE2ETests` 4/4（无 bootstrap 配置下表空注册仍 `role":"user"`）。
   - 白名单口径 ✅：`/actuator/health` 只放健康，`info` 未对外暴露（exposure 只有 health,info，且 info 无敏感配置）。
2. **注入与外泄链**：SQL 全参数化 ✅；路径穿越有 `resolveInside` 根目录约束 ✅；SSRF：**`article.extract` / `http.collect` 的 url 由 DSL 用户可控**，微信侧有 `safeUri()`（WechatDraftNodeExecutor.java:302 等）✅，但采集类节点需确认对 `169.254.169.254` / `127.0.0.1` / 内网段的黑名单（本次未取到证据 ⇒ 列入待核实清单）；提示注入：外部正文（抓取的文章）会进 prompt（CopyWriteNodeExecutor），且模型输出可再流入 publish 节点写库/发草稿 ⇒ **构成"外部内容→存储→回流响应/平台发布"的组合链路**，目前无"不可信内容标记"，🔶（自用可接受，对外必解）。
3. **密钥管理**：全部 env / `*_FILE` secrets，yml 无明文 ✅；日志只打"present/absent"布尔 ✅（StartupSelfCheck.java:45-50）；🛑 候选但记 🔶：`DEV_FALLBACK_SECRET = "agentflow-dev-jwt-secret-0123456789abcdef"`（[AuthConfig.java:22](file:///Users/a/source/agent-flow/server/agent-flow-server/server-application/src/main/java/com/agentflow/server/security/AuthConfig.java#L22)）是**写进仓库的可用签名密钥**——`enabled=true` 时靠 StartupSelfCheck 兜住，但该兜底是 `ApplicationRunner`，端口已绑定、已可服务（见下）；本地 `enabled=false` 时任何知道该串的人可伪造 token 直接成为 admin。
4. ✅（P7-2b 已修，10-22 21:30）启动时序：原 fail-fast 位于 `implements ApplicationRunner` ⇒ 抛错时机在 **Tomcat 已 bind 端口、context 已就绪之后**，存在"已对外可服务 2–3 秒后才崩"的窗口；prod+`enabled=false` 只 `log.error`。**现状**：基线校验移到 `StartupSelfCheck` 的 `@PostConstruct enforceSecurityBaseline()`（[StartupSelfCheck.java:61](file:///Users/a/source/agent-flow/server/agent-flow-server/server-application/src/main/java/com/agentflow/server/startup/StartupSelfCheck.java#L61)）——eager 单例初始化在 `finishBeanFactoryInitialization` 阶段，早于 `finishRefresh()` 的 `WebServerStartStopLifecycle` 绑定端口；`run()` 只保留信息性自检。**反向验证**（不是"正常路径通过"）：`P7FailFastTimingTests` 以 `enabled=true` + 空密钥起一个 RANDOM_PORT 实例，断言①启动抛错且异常链含 `AGENTFLOW_JWT_SECRET`，②失败后 `new Socket().connect(loopback:port)` **连接被拒** ⇒ 端口从未对外可服务。1/1 绿。（prod+`enabled=false` 仍是 `log.error` 而非拒启，属刻意保留的"本地可跑"口子，见 §4 #5 处置。）
5. ✅（P7-2d 已修，10-22 22:07）限流的可用性副作用：原 [RateLimitFilter.java:59](file:///Users/a/source/agent-flow/server/agent-flow-server/server-application/src/main/java/com/agentflow/server/security/RateLimitFilter.java#L59) 以 `uid+":"+minute` 为键，匿名一律 uid=0 ⇒ 全部未登录流量共享 120 次/分钟/实例。**现状与口径修正**：`AuthFilter`（+10）先于本过滤器（+20），匿名访问业务 API 早已 401，因此真实挤桶者是"服务通道 + `/api/auth/**` 匿名流量"。桶键改为 `u:{uid}` / `svc` / `ip:{client}:auth` 三种，auth 用独立阈值 `rate-limit-auth-per-minute`（默认 30/IP），`trust-forwarded-for` 默认 false（没有会覆盖该头的可信反代时开它就是让攻击者自选桶键）。反向验证：同一 IP 第 3 次登录 429、换 IP 立刻回到 401；auth 桶打满后服务通道仍能用满自己的 4 次额度、第 5 次才 429。多实例仍靠网关/Redis 计数（容量阶段，台账已记）。
6. **供应链**：Spring Boot 3.5.6 + Java 21（ majors 不落后 ✅）；**spring-ai-alibaba 传递带入 nacos-client**，运行期根文件系统 `read_only` 导致启动刷 ERROR（昨天已用 `-Dnacos.logging.default.config.enabled=false` 从 18 降到 1 条残留 `AliyunConfigFilter - region is not set up yet`）⇒ 属"引入了不使用且有攻击面的组件"，建议在 dependencyManagement 显式排除（P6 台账项）⚠️。

## 3. 交叉质询裁决记录

| 争议 | 双方观点 | 裁决 | 依据 |
|---|---|---|---|
| 研发"闸门只是容量问题" vs 运维"排队不可观测" | 容量 vs 可观测 | **两者都成立，且可观测优先**：`acquire()` 无超时→不报错、只变慢，无指标即无人发现 | ExecutionManager.java:43 + exposure.include 无 metrics |
| 侦察报告"外部调用无超时，降级永不触发" vs 研发"有超时" | 超时缺失？ | **侦察指控不成立，撤销该条** | HttpJson.java:39/103、Wechat:83/302、Wanxiang:62/173、HttpCollect:59/127 |
| 运维"readiness 包含 db，够安全" vs DBA"探针说谎" | 探针成员 | **DBA 对**：现场实测 DB 宕机 readiness=UP | 实测 `docker stop agent-flow-db-1` → `{"status":"UP"}` |
| 产品"自用可豁免跨租户读写" vs 安全"密钥外泄不能豁免" | 是否降级 | **保留 🛑 原判**，追加"业务豁免需产品书面签字"；因为 `credentialId` 来自用户可控 DSL，一旦开放注册即现实可利用 | CredentialRepository.java:56–59 |
| 安全"dev fallback 密钥 = 🛑" vs 研发"有 fail-fast 兜底 = ⚠️" | 严重度 | **折中为 🔶**：兜底存在但时机错位（ApplicationRunner），修复成本仅 0.5 人日，修完自动降为 ✅ | AuthConfig.java:22 + StartupSelfCheck.java:22/53 |
| DBA"单机无备份=🛑" vs 产品"数据可重建" | 分级 | **降为 🔶**：现状数据集为 0、可重建，符合"学习/演示项目的诚实边界"，但对外即升 🛑 | PROGRESS.md 交接状态（0 用户/0 工作流） |
| 研发"orTimeout 后线程自然释放" vs DBA"钱照花、草稿照发" | 副作用一致性 | **DBA 对**：`nodePool` 无界 + 轮询循环不响应中断 ⇒ 已判失败仍在提交草稿，重复发布风险成立 | WorkflowEngine.java:43/137、DashScopeImageClient.java:73 |

## 4. 项目说明（四级清单）

| # | 级别 | 维度 | 问题 | 证据 | 责任人角色 | 修复动作与成本 |
|---|---|---|---|---|---|---|
| 1 | 🛑 → ✅ P7-1 | 安全/多租户 | credentialId 无归属校验 → 任意用户可用他人微信/小红书密钥发草稿 | CredentialRepository.java:56-59；JdbcCredentialProvider.java:36-44 | 研发 | resolve 前 `findMeta(id).ownerId()` 与 Principal 比对；+契约测试；1 人日 |
| 2 | 🛑 → ✅ P7-1 | 安全 | `X-API-Token` 主体 uid=0 ⇒ isAdmin ⇒ 全站读写 | AuthFilter.java:70；Principal.java:15-24 | 研发 | 服务主体独立角色 `service`，`canAccess` 不放行；token 分环境/带 kid；0.5–1 人日 |
| 3 | 🛑 → ✅ P7-1 | 可观测 | 无 metrics 端点、无业务指标 ⇒ 高峰盲飞（四金指标全缺） | application.yml:30；无 MeterRegistry/micrometer-prometheus | 运维 | 引 registry-prometheus + 暴露 `metrics,prometheus` + 执行闸门/RT/错误率 3 个自定义指标；1 人日 |
| 4 | 🛑 → ✅ P7-1 | 探针/可用性 | DB 宕机 readiness 仍 UP，compose 健康检查永绿 | 实测（§2 运维 3）；application.yml:21-30 | 运维 | readiness 组显式 include `db,ping`；liveness 不含 db；0.5 人日 |
| 5 | 🔶 → ✅ P7-2b | 安全/启动时序 | 密钥 fail-fast 在端口绑定后；prod 关鉴权只 log | 原 StartupSelfCheck.java:22,53-58；现 :61 `@PostConstruct` | 研发 | ~~前置到 EnvironmentPostProcessor~~ 实际用 `@PostConstruct`（单例初始化早于 `WebServerStartStopLifecycle`）；反向测试 `P7FailFastTimingTests` 断言失败后端口连不上；0.5 人日 ✅ |
| 6 | 🔶 → ✅ P7-2d | 安全/可用性 | 匿名共享 uid=0 桶 ⇒ 120/min 可被打死全站登录；公开登录仅 2 rps | 原 RateLimitFilter.java:59；现按 `u:uid`/`svc`/`ip:x:auth` 三桶 | 研发 | 实际口径修正：`AuthFilter`(+10) 在本过滤器(+20) 之前，匿名进不了业务 API，真正挤在 uid=0 的是**服务通道 + `/api/auth/**`**；已拆 svc 独立桶 + auth 按 IP 独立阈值（`rate-limit-auth-per-minute=30`，`trust-forwarded-for` 默认 false）；`P7RateLimitBucketE2ETests` 反向验证（换 IP 立刻可用、auth 打满不影响 svc 额度）；0.5 人日 ✅ |
| 7 | 🔶 | 正确性 | TIMEOUT 后节点继续跑并真实发布草稿 → 重复内容/重复扣费 | WorkflowEngine.java:43,137；DashScopeImageClient.java:73；Wanxiang:157 | 研发 | publish 节点按 (executionId,nodeId) 幂等键 + 中断响应；1 人日 |
| 8 | 🔶 → ✅ P7-2a | 安全/引导 | 开放注册 + 首用户即 admin ⇒ 抢注即接管；白名单用原始 URI 前缀匹配 | 原 AuthService.java:15；现 :42 恒为 `user` + `AdminBootstrap.java:52` | 研发 | ~~部署即关闭注册或预置管理员~~ 实际：注册永不授 admin，管理员由 `AGENTFLOW_BOOTSTRAP_ADMIN_USERNAME/PASSWORD(_FILE)` 显式引导；白名单精确路径见 P7-1b；0.5 人日 ✅ |
| 9 | 🔶 → 🟡 P7-2d#9 | 容灾 | 单机 PG 无自动备份、无恢复演练（对外即 🛑） | 原 README 仅手工 pg_dump；现 `scripts/backup-postgres.sh`（backup/verify/drill）+ 真实演练记录见 PROGRESS 22:25 | DBA | 恢复演练**已做且验到内容级**：有数据库 dump 20474B ⇒ 逐表计数 7/7、行级 md5 4/4（含密文 blob）⇒ 恢复库起服务后登录/读流/解密凭证全通，反向验证（垃圾文件、截断 dump）能让 verify 与 drill 双双退出 1，并暴露修复了 drill 失败漏临时库的缺陷。**仍未做**：cron 定时、异地/对象存储副本、`artifact` 目录配套备份（演练时 artifact=0）、备份失败告警 ⇒ 故本项只到 🟡，自动化补齐前"备份存在"不等于"每天有新备份" |
| 10 | ⚠️ | 容量 | 执行闸门 16 且硬编码不可配，0.36 exec/s，排队无超时 | ExecutionManager.java:26,43 | 研发/运维 | 外置 `agentflow.exec.max-concurrent` + acquire 超时 + 队列上限（429/503）；1 人日 |
| 11 | ⚠️ | 容量/DB | 多实例后 `实例×20 > PG 100`；无全局配额；限流各自计数 | application.yml:74-79；RateLimitFilter 注释 :22 | DBA | PgBouncer + 全局令牌（Redis）；十万档必做 |
| 12 | ⚠️ | 一致性 | 成品库本地文件 + 列表只读本机；DB 写成功/文件失败无补偿 | application.yml:57；LocalArchiveNodeExecutor | 研发 | 对象存储 + 补偿入口（P7） |
| 13 | ⚠️ | 访问模式 | `listByOwner` 等无分页；node_runs 逐行写 | CredentialRepository.java:40-46 | DBA | 游标分页 + 批量 flush |
| 14 | ⚠️ | 安全标记 | 路由无 SENSITIVE 标记（bootstrap §5 未兑现） | 全仓 grep 0 命中 | 研发 | 端点元数据 + 鉴权收口清单；0.5 人日 |
| 15 | ⚠️ | 供应链 | 传递依赖 nacos-client 未使用仍有攻击面且刷错误日志 | Dockerfile JAVA_OPTS 注释；残留 AliyunConfigFilter ERROR | 运维 | 显式 exclusion + 依赖树回归；0.5 人日（勿用 autoconfigure.exclude，已实测致启动失败） |
| 16 | ⚠️ | 密钥治理 | 主密钥无 kid/轮换流程 | AesGcmCipher 使用点；AuthConfig.java:40-52 | 安全 | 双读单写轮换方案 |
| 17 | ⚠️ | 韧性 | 下游无熔断/舱壁，一慢即占满 16 槽 | ExecutionManager + 各节点共用池 | 研发 | 按依赖类型分舱壁；P7 |
| 18 | ✅ | 配置治理/异常边界/超时降级/Flyway/优雅停机/CAS 认领/traceId/CI+189 测试/参数化 SQL/路径穿越/凭证加密 env 口径 | 见 §1.5 与 §2 各条证据 | — | — | 免修 |

## 5. 分档位演进路线

| 档位 | 必须先解决 | 选型更换点 | 依据算式 |
|---|---|---|---|
| **现状（≤千 QPS，单作者自用）** | #1–#4 中的对外项可豁免，但 **#4（探针说谎）与 #5（时序）现在就修**，#3 至少补 `/actuator/metrics` + 闸门 gauge | 保持单机 PG + 本地文件 | 1,300 exec/日/实例 ≫ 个人用量，余量充足 |
| **千级 → 万级** | #1/#2/#7/#8（多租户正确性）+ #10 闸门可配 + #3 Prometheus + #11 PgBouncer | 成品库 → 对象存储；限流 → Redis 令牌桶 | `16/45s=0.36`；`实例数×20 ≤ max_connections×0.8` |
| **十万 QPS（用户指定目标）** | 流量必须拆类：短读 API 走无状态多实例（≈8 实例，2 万读/实例·折连接后 1k/实例）；**长任务改 MQ + 全局配额（数据库/Redis 令牌）+ 按用户配额**；PG 读写分离 + 执行日志按月分区/归档 | Semaphore(16) → 分布式令牌 + Kafka/RocketMQ 削峰；单机 PG → 主从；本地索引 → 对象存储/CDN | 读：`100000×0.005=500` 并发 → `500/200/0.7≈4` 实例下限；写/执行：`100000 exec/s ÷ 0.36` = 不可能 ⇒ 定义只能是"提交速率 ≤ 服务速率"，十万 QPS 不能是执行 |
| **百万 QPS** | 单元化（按 uid 分片）+ 多层缓存（命中率>99%）+ 专用异步引擎；LLM/图像必须走配额网关与结果缓存/去重 | 单库 → 分片（ShardingSphere）；执行态 → 事件溯源 | 外部模型 API 配额是物理天花板，`9.5k token/次` 决定成本曲线，非工程可解 |

## 6. 待用户核实清单（[假设] 汇总）

1. 部署机型是否就是本机 10C/16G？是否有独立云主机/容器内存限额（compose 现未设 `resources.limits`）？
2. 端到端 P50 RT 45s（copy.write 15–30s + image.cover 20–60s）是否与实际观测一致？有无 LLM/万相的 **QPS/并发配额**数值？
3. 读接口 RT 5ms、单查询 20ms、单请求 CPU 0.5ms —— 需一次真实压测校准（建议先跑 `load-testing-plan` skill 出方案）。
4. 是否存在任何真实多用户/对外开放计划？（决定 #1/#2/#8 是 🛑 还是设计边界）
5. PG `max_connections` 实际值、是否已有备份（外部 pg_dump 记录）？
6. `article.extract` / `http.collect` 的 SSRF 防护：请确认对 `127.0.0.0/8`、`169.254.169.254`、内网段是否有黑名单（本次未取到代码证据）。
7. `AGENTFLOW_API_TOKEN` 是否已进入任何 CI 变量或历史日志（决定 #2 的暴露面）。

## 7. P7-1 修复回写（2026-10-22，四条 🛑 全部落地）

修复范围＝§4 的 #1/#2/#3/#4 四条 🛑；#5–#9 五条 🔶 本轮未动，仍按 P7-2/P7-3 排期。**决议不变**：对外多用户仍 NO-GO，
因为 #8（开放注册 + 首用户即 admin 抢注）与 #7（TIMEOUT 后重复发布）尚未修，改判需重开一次评审。

| # | 落点 | 关键取舍 | 验证 |
|---|---|---|---|
| 1 | `CredentialProvider.resolve(credentialId, workflowId)` 端口签名扩一个参数；`JdbcCredentialProvider` 先 `WorkflowCatalog.ownerOf(workflowId)` 反查工作流归属者，再 `CredentialRepository.findSecretEncOwned(id, ownerId)`（SQL 带 `AND owner_id = ?`） | 归属校验放在**端口实现**里而不是节点里：DSL 的 `credentialId` 是用户可写的，任何"让 executor 自己判"的写法都会被下一个节点忘记。"不存在""不是你的""主密钥没配"三种失败统一回 `Optional.empty()`——区分它们本身就是探测他人凭证是否存在的侧信道；日志只带 id 与异常类型 | 契约测试 `P3CredentialE2ETests#anotherUsersCredentialIsNotResolvable`；实机反向验证见下 |
| 2 | `Principal.isAdmin()` 去掉 `\|\| uid == SYSTEM_UID`，只看 role；服务通道主体 `role=ops`、白名单主体 `role=anonymous`；`AuthFilter` 白名单从 `/api/auth` **前缀匹配**改为"归一化后的精确路径集合" | uid=0 继续当"归属键"用（本地开发/服务通道仍要有个主体），但特权与 uid 解耦；`/api/auth/../workflows/1` 这类跳板路径在过滤器上就归一化后落到集合外 ⇒ 走鉴权 | `AuthFilterTest`（服务主体 `isAdmin=false`、`canAccess(42)=false`、跳板路径 401）+ 实机 404/401 |
| 3 | pom 引 `micrometer-registry-prometheus`；`exposure.include` 加 `prometheus`；`ExecutionManager` 埋 5 项：`agentflow.execution.submitted`、`agentflow.execution.finished{status}`、`executionDuration`、`queueWait`、闸门 `slots.available`/`slots.queued` gauge | Timer 而非 Counter 记 RT，四金指标里"流量/错误"用 `{status}` 标签拆；队列等待单独计时——0.36 exec/s 的瓶颈假设只能靠这条曲线证伪或证实 | 实机 `/actuator/prometheus` 抓全（见下） |
| 4 | `management.endpoint.health.group.readiness.include: readinessState,db,ping`；liveness 只 `livenessState` | liveness 刻意不含 db：依赖抖动要摘流量+等恢复，重启进程解决不了 DB 故障，反而把在途执行打死 | 实机：库可达 readiness=200；库不可达 liveness=200 而 readiness=503 DOWN |

实机反向验证（2026-10-22，打包 jar `server-application-0.1.0-SNAPSHOT.jar`，鉴权开启 + 两个真实注册用户 + 本地微信 API 桩；脚本 `/tmp/p7check/live-checks.sh`）**25 项断言全绿**，其中关键四条：
① 属主自己的工作流确实把 vault 里的 `appid=p7-vault-appid` 带出了网（桩日志留痕），失败原因是微信返回 40013 而非"凭证不可用"；
② 他人工作流引用同一个 `credentialId` 报"凭证不存在或无法解密"，且**桩一次请求都没收到**（拒绝发生在出网之前，不是发出去再拦）；
③ `X-API-Token` 读他人工作流 = 404、列他人凭证 = 空，但服务通道本身仍可用；
④ `/actuator/prometheus` 输出 `agentflow_execution_submitted_total 2.0`、`agentflow_execution_finished_total{status="FAILED"} 2.0`、`agentflow_execution_duration_seconds_*`、`agentflow_exec_slots_available 16.0`、`agentflow_exec_slots_queued 0.0`。

**容器形态 + 真 PG16 复验（2026-10-22 16:17 补做，28 项断言全绿）**：重建 `agentflow/server:0.1.0` 后，用 prod profile + pgvector/pg16 容器（`read_only`/`cap_drop`/`*_FILE` 秘密垫片全部沿用）再跑一遍，验证用 scratch 库 `p7check`，结束后 `DROP DATABASE ... WITH (FORCE)`，**prod 库保持交接状态**（users/workflows/executions/creds/artifacts 均 0）。关键结论：
① 引导语义未误伤——首用户仍拿 `admin`，admin 读他人工作流 200；普通用户读他人工作流 404；
② 普通用户引用他人 `credentialId` 仍是"凭证不存在或无法解密"且桩零请求，属主本人则把 `appid=p7-vault-appid` 真带出网并收到微信 40013；
③ `docker stop agent-flow-db-1` → liveness 200 / readiness 503 DOWN，`docker start` 后 readiness 自动回 200（真 PG16 上的连接池自愈也顺带验了）；
④ prod profile 下 `/actuator/prometheus` 五项 `agentflow_*` 指标齐全（`submitted_total 2.0`、`finished_total{status="FAILED"} 2.0`、RT、闸门 available/queued）。

**复验新发现（⚠️，P7-2 待决策）**：`/actuator/prometheus` 不在白名单，未认证访问 = 401；而 prod compose 未配 `AGENTFLOW_API_TOKEN` ⇒ **现成的 Prometheus 抓不到指标**（JWT 2h 过期，不适合当抓取凭证）。两条路需要产品/运维拍板：给 scraper 配专用静态 token（代价是它同时拥有 uid=0 名下资源写权限），或把该端点放进内网白名单并要求 8080 不直接对公网（当前 compose 确实发布到宿主 8080）。本轮**未擅自改动**这条安全边界——"端点存在"与"能被抓取"是两件事，后者留在 P7-2 处理。

