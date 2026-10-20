# agent-flow 执行进度台账

> 规则：**每完成一个子任务必须回写本文件**（状态 / 完结时间 / 验证证据）。状态符号：✅ 完成 ｜ 🔄 进行中 ｜ ⬜ 未开始。
> 计划依据：`docs/IMPLEMENTATION-PLAN.md` §7。时间为机器本地时间。

## P0 仓库底座（0.5d）—— ✅ 全部完成，完结 2026-10-19 11:25

| 任务 | 状态 | 完结时间 | 验证证据 |
|---|---|---|---|
| git init + .gitignore（密钥类禁入库） | ✅ | 2026-10-19 11:14 | `git status` 正常，规则覆盖 .env/*secret*/*.pem |
| README 四节结构 | ✅ | 2026-10-19 11:12 | 快速开始/配置表/架构决策/已知限制 |
| 父 pom 版本治理 + 4 模块单向依赖 | ✅ | 2026-10-19 11:23 | `mvn -B verify` BUILD SUCCESS（Boot 3.5.6 / SAA 1.0.0.1） |
| 统一响应体 + 错误码 + TraceIdUtils | ✅ | 2026-10-19 11:23 | platform-common 3 单测过（修掉 slf4j-simple MDC=NOP 问题） |
| NodeExecutor SPI 四件套契约 | ✅ | 2026-10-19 11:23 | NodeExecutorContractTest 钉住默认值 |
| EchoNodeExecutor 样板 | ✅ | 2026-10-19 11:23 | 3 单测过 |
| TraceIdFilter / 全局异常边界 / ApiToken 骨架 / 启动自检 | ✅ | 2026-10-19 11:23 | ApiTokenFilterTest 3 过 + 集成 4 过 |
| 运行时 DoD（零中间件启动） | ✅ | 2026-10-19 11:25 | liveness 200 UP；ping traceId 头/体一致；404 返回 `{code:A0404,traceId}` 无堆栈 |
| compose / CI 文件入库 | ✅ | 2026-10-19 11:12 | 待绑 GitHub 远端后实际执行（用户操作） |

**遗留给用户的动作**：① 初始 commit；② 绑 GitHub 远端激活 CI；③ 装 Docker Desktop（P1 云端 PG 联调时才需要，本地 H2 模式不阻塞）。

## P1 DAG 引擎核心（3d 计划）—— ✅ 全部完成，完结 2026-10-19 13:47

| 任务 | 状态 | 完结时间 | 验证证据 |
|---|---|---|---|
| PROGRESS.md 台账建立 | ✅ | 2026-10-19 11:28 | 本文件 |
| DSL 模型 + DagValidator（环/未知类型/重复id/悬空边/自环） + NodeRegistry | ✅ | 2026-10-19 13:44 | DagValidatorTest 5 个钉住全部拒绝路径；同层字典序保证拓扑确定性 |
| WorkflowEngine：拓扑执行、orTimeout 超时、重试指数退避、降级显性标志、traceId 贯穿虚拟线程 | ✅ | 2026-10-19 13:44 | WorkflowEngineSemanticsTest 5 个：重试恰好 3 次、DEGRADED 续跑、不可重试仅 1 次、超时 100ms 生效不等 2s、非法定义零半截执行 |
| Flyway V1（workflow/execution/execution_node_log 含 node_type）+ H2默认/PG prod 双口径 | ✅ | 2026-10-19 13:45 | 启动日志：H2 上成功迁移 v1；Hikari 就绪；测试隔离用 mem 库 |
| REST API：保存即校验 / 异步触发（Semaphore 16 闸门）/ 执行详情 | ✅ | 2026-10-19 13:44 | 4 端点；保存非法 DSL 直接 400，执行异步秒回 executionId |
| 单测 + 集成测（共 26 个，全模块 mvn verify 全绿） | ✅ | 2026-10-19 13:44 | BUILD SUCCESS，0 失败 0 跳过 |
| 运行时验证：启动→建流→触发→查日志 | ✅ | 2026-10-19 13:46 | workflowId=1 双 echo 链 SUCCESS；traceId 贯穿=True；节点日志含类型/耗时/尝试次数；环拒绝 C0500；畸形 JSON A0400 |

**P1 过程中修掉的真问题**（均有单测钉住防回归）：PriorityQueue 不是 Deque 编译错；H2 生成键多列返回导致 getKey 炸（改显式 `new String[]{"id"}`）；未继承 boot 父 pom 丢 `-parameters` 导致 @PathVariable 名字解析 500；V1 checksum 变更后本地旧文件库需重建（测试改 mem 隔离）。

## P2 节点适配器（3d）—— ✅ 全部完成，完结 2026-10-19 15:03

依赖前置核查：jsoup 1.10.2、spring-ai-alibaba-core/starter 1.0.0.1、spring-ai 1.0.0 线均已在本地 m2 缓存，**零新增下载**；DashScopeChatModel/DashScopeApi Builder 签名已用 javap 核实。

| 任务 | 状态 | 完结时间 | 验证证据 |
|---|---|---|---|
| P2-0 台账建清单 + 依赖/契约核查 | ✅ | 2026-10-19 13:56 | jsoup/SAA 缓存存在；NodeExecutor 四件套契约复读；SAA BOM 只管 alibaba 构件，core 直依赖避免 autoconfigure 无 key 启动炸 |
| P2-1 http.collect（jsoup 解析 + 三异常 + 降级续跑） | ✅ | 2026-10-19 14:31 | HttpCollectNodeExecutorTest 8/8 绿：选择器/截断/500→C0003/拒绝→C0001/超时→C0002/缺 url→A0400/降级显性/契约数值钉死 |
| P2-2 LlmGateway 门面 + llm.chat（多模型可配置，缺 key 不可重试快败） | ✅ | 2026-10-19 14:31 | LlmChatNodeExecutorTest 9/9 + SpringAiLlmGatewayTest 6/6 绿：模型覆盖透传、上游素材拼接、异常三分类、空回包拦截；节点只依赖纯 Java 接口，ChatModel 类型封在实现类 |
| P2-3 image.wanxiang（异步提交+轮询，noRetry 防重复计费，无图降级） | ✅ | 2026-10-19 14:31 | WanxiangImageNodeExecutorTest 9/9 绿：X-DashScope-Async 头钉住、RUNNING→SUCCEEDED 两轮询、FAILED/400→C0003、等待超限→C0002、**retryPolicy==noRetry 断言钉钱敏感契约**、无图降级 |
| P2-4 wechat.draft（token 缓存+素材+草稿，errcode 映射，发布不降级） | ✅ | 2026-10-19 14:34 | WechatDraftNodeExecutorTest 12/12 绿：完整 API 序列含顺序断言、token 跨执行缓存(2次1取)、40013→A0400/-1→C0002/40001→清缓存重试、上游正文回退、**degrade 为空(发布永不降级)**；过程中修掉真实缺陷：add_material 漏带 access_token 必被真微信拒绝 |
| P2-5 server 装配（4 节点 Bean 无条件注册 + 网关条件化 + yml） | ✅ | 2026-10-19 15:03 | NodeConfigProperties+NodesConfig：无 key 时 LlmGateway 返回 NullBean、节点照常在位；P2NodeWiringTests 2/2（含抹除宿主 env 干扰的测试隔离） |
| P2-6 mvn clean verify 全绿 + 运行时冒烟 | ✅ | 2026-10-19 15:03 | 全量 79 测试 0 失败（3+13+47+16）；冒烟：存 llm.chat DSL 200→触发→SUCCESS_WITH_DEGRADED、attempts=1、原因含 BAILIAN_API_KEY、traceId 贯穿；环外发现：PowerShell UTF8 BOM 会砸坏 curl 文件体（改无 BOM 写法） |

设计决策（本阶段钉死）：
- `LlmGateway` 是纯 Java 接口（node-adapters 内），Spring AI 只是其一个实现——框架可替换缝，引擎/DSL 零感知。
- 节点内部 HTTP 请求超时与 `timeout()` 同口径（兜底线程释放），不另立数值——契约"唯一超时口径"的延伸解释。
- image.wanxiang `noRetry`：引擎重试会二次提交异步生图任务造成重复计费，宁可不重试。
- wechat.draft 不覆盖 degrade：发半截内容比不发更糟，失败必须显性 FAILED。
- 凭证一律 env 注入（BAILIAN_API_KEY/WECHAT_APPID/WECHAT_SECRET），缺失时节点注册但执行报不可重试的配置错误（fail-fast 且不阻塞画布开发）。

**P2 过程中修掉/拿下的真问题**：add_material 漏带 access_token（真实微信必拒，单测驱动发现）；P2DryRun 首版断言把"带 degrade 节点"误写成 FAILED 期望（实测应为 SUCCESS_WITH_DEGRADED，修正断言不改行为）；surefire -Dtest 用 `+` 分隔多类静默零测试假绿（必须逗号+验证 Tests run 行）；ConnectException null message 渲染成"拒绝: null"（causeText 兼容）。

**待用户执行（非阻塞）**：注入真 key（BAILIAN_API_KEY/WECHAT_APPID/WECHAT_SECRET）后联网跑 llm.chat/image.wanxiang/wechat.draft 真实链路；启动日志中 SAA 内置 Nacos 日志组件的 RollingFileAppender ERROR 为噪音不影响服务。

## P3 平台 API（2d）—— ✅ 全部完成（开工 2026-10-19 16:45，完结 17:45）

依赖核查：jjwt/nimbus 均不在 m2 缓存且本会话禁下载 → JWT(HS256)/PBKDF2/AES-GCM 全部纯 JDK 自实现（安全算法标准明确、单测钉死）；cron 用 spring-context 自带 `CronExpression`（6 段，零新依赖）；A0429 错误码 P0 已注册。

| 任务 | 状态 | 完结 | 证据 DoD |
|---|---|---|---|
| P3-0 台账建清单 + 契约/依赖核查 | ✅ | 16:45 | jjwt/nimbus 不在 m2 缓存→纯 JDK 自实现路线钉死；A0429 已存在于 ErrorCode；CronExpression 随 spring-context 6.2 可用 |
| P3-1 platform-common 安全原语：PasswordHasher(PBKDF2)+AesGcmCipher+单测 | ✅ | 17:37 | SecurityPrimitivesTest 10 绿：盐唯一/篘改位拒绝/错密钥失败/IV 不复用；verify 不抛异常 |
| P3-2 V2 迁移：users/user_credentials + workflow owner/调度列 | ✅ | 17:37 | V2__platform_tables.sql；H2-PG 双模（BIGSERIAL/无 ON CONFLICT）；全部 e2e 启动时 Flyway 迁移成功 |
| P3-3 注册/登录 + JwtAuth 过滤器替换骨架 + workflow 归属隔离 | ✅ | 17:37 | JwtCodecTest 8 绿（拒 alg=none/alg 换/篘改/过期/短密钥）+ AuthFilterTest 6 绿 + P3AuthE2ETests 4 绿（首用户 admin/跨用户 404/防枚举同文案）；冒烟：anon→401、首注册→admin uid=1、登录签发 |
| P3-4 凭证库 CRUD + wechat.draft credentialId 接入（CredentialProvider 端口） | ✅ | 17:37 | P3CredentialE2ETests 2 绿：本地微信桩收到 appid=vault-appid（全局 env 凭证置空仍 SUCCESS）、库内无明文、API 不回显秘密、幽灵 credentialId → FAILED attempts=1 |
| P3-5 cron 触发：next_fire_at 乐观领发 + 扫描 + schedule API | ✅ | 17:37 | P3ScheduleTests 2 绿：登记→回拨→fireDue=1→执行落库→再扫=0（CAS）；非法 cron A0400；节拍已参数化 tick-ms/tick-initial-ms；冒烟：PUT schedule 返回 nextFireAt |
| P3-6 限流过滤器（per-uid 固定窗，A0429+Retry-After） | ✅ | 17:37 | P3RateLimitE2ETests 绿：上限=3 时第 4 次 429+Retry-After+A0429；修复 Java 非法逗号表达式后重验 |
| P3-7 全量 verify + 运行时冒烟（注册→登录→建流→鉴权隔离） | ✅ | 17:45 | `mvn -B clean verify` BUILD SUCCESS，109 测试全绿（13+13+47+36）；P1/P2 e2e 在新 AuthFilter/owner 列下零回退；冒烟链 401→注册→登录→建流(workflowId=3)→执行 SUCCESS+traceId 传播→cron 登记→非法 cron 400 |
| P3-8 台账/README/计划三处同步 | ✅ | 17:45 | 本表 + 变更日志；README 配置表补 security 新键；IMPLEMENTATION-PLAN §7 头部 P3→✅ |

设计决策（本阶段钉死）：
- JWT 自实现（Mac HmacSHA256+Base64URL）：验签必拒 alg=none/异 alg/篘改（MessageDigest.isEqual 恒时比较）；密钥 `AGENTFLOW_JWT_SECRET` env，enabled=true 且密钥空 → 启动即失败（fail-fast）。
- 口令 PBKDF2WithHmacSHA256（120k 次，随机 16B 盐），存储格式 `pbkdf2$sha256$iter$salt$hash`，验证恒时。
- 凭证库 AES-256-GCM，主密钥 `AGENTFLOW_MASTER_KEY`（base64 32B）；每条记录随机 12B IV 前缀入密文；GET 永不回显明文；未配主密钥时凭证 API 报 A0400 配置错、其它功能不受影响。
- 首个注册用户 role=admin；enabled=false 时以系统匿名主体（uid=0, admin）通行，兼容存量 P1/P2 e2e。
- 归属隔离：workflow.owner_id；非属主读取/触发一律 404（防资源探测）。
- cron：`CronExpression` 6 段；落库 next_fire_at，CAS UPDATE 领发后才 submit（重启不重复；单实例假设，多实例需分布式锁→容量阶段记账）。
- 限流：per-principal 每分钟固定窗（默认 120，0=关），超限 A0429 + Retry-After；内存态单实例（多实例切网关/Redis，记账）。
- llm/image 节点的 credentialId 接入本轮不做（网关单例改造面大），先闭环 wechat.draft 一例验证端口设计。

## P4 Vue 画布（4d）—— ⬜ 未开始
LogicFlow 2.x 编排画布 ↔ DSL JSON 双向映射、参数面板、执行日志视图。

## P5 部署与首轮评审（1d）—— ⬜ 未开始
Dockerfile（多阶段）、compose server 段启用、云服务器 prod 部署演练、首轮 enterprise-project-review。

---
## 变更日志
- 2026-10-19 11:28 台账创建；P0 九项回填完成；P1 启动。
- 2026-10-19 13:47 P1 七项全部完成并回写证据；26 测试全绿；下一步 P2。
- 2026-10-19 13:56 P2 开工：台账建 7 项清单；依赖缓存核查通过（零下载）；4 条设计决策入档。
- 2026-10-19 14:34 P2-1～P2-4 四节点+44 个单测全绿（含修掉 add_material 缺 access_token 真缺陷）；进入 P2-5 server 装配。
- 2026-10-19 15:03 P2 七项全部完成：全量 79 测试绿，运行时冒烟验降级链/不可重试快败；README 决策记录补 LangChain 评析；下一步 P3。
- 2026-10-19 17:45 P3 九项全部完成：纯 JDK 安全栈（JWT/PBKDF2/AES-GCM）+ V2 迁移 + 归属隔离 + 凭证库闭环 + cron CAS 领发 + 限流；全量 109 测试绿；冒烟验证 401/注册/登录/建流/执行/调度/非法 cron 拒绝；下一步 P4 Vue 画布。
