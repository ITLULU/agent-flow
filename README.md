# agent-flow

自研 Agent 自动化工作流平台：可视化 DAG 编排（采集 → LLM 写作 → 生图 → 发布），Spring Boot 3.5 / Java 21 模块化单体 + Vue 3 画布。设计目标按生产上线标准（初期 10w 注册用户 / 3w DAU / 峰值 100 QPS），生产部署于云服务器。

## 1. 快速开始

前置：JDK 21、Maven 3.9+、curl + jq；改前端另需 Node.js 20+（清单见 `docs/TOOLS-CHECKLIST.md`）。**默认 profile 零外部中间件依赖，克隆即可启动。**

```bash
cd server/agent-flow-server
mvn -B install -DskipTests            # 先把 4 个模块装进本地仓库（单模块 spring-boot:run 依赖它）
mvn -B verify                          # 全模块编译 + 单测/集成测试
mvn -pl server-application spring-boot:run     # 启动（本地默认 profile）
# 或直接跑打包产物：java -jar server-application/target/server-application-0.1.0-SNAPSHOT.jar
```

验证：

```bash
curl -s http://localhost:8080/actuator/health/liveness   # {"status":"UP"}
curl -s http://localhost:8080/api/ping                   # {"code":"0",...,"data":"pong"}
```

跑一条真实创作链路（需注入 `AI_API_KEY`；缺 key 时启动正常、`copy.write` 执行报 A0400 配置错）：

```bash
cd /path/to/agent-flow
scripts/run-creation.sh --mode xhs --topic "秋天适合把书一本本读完" --ratio 3:4
scripts/run-creation.sh --mode wechat --url https://mp.weixin.qq.com/s/xxxx
```

用法、DSL 拆解与成品库 API 见 `docs/CREATION-GUIDE.md`。

### 前端画布（P4）

后端启动后访问 `http://localhost:8080/` 即可用（打包产物已随 `server-application/src/main/resources/static` 入库）。改前端：

```bash
cd web/agent-flow-web
npm install
npm run dev      # http://localhost:5173，/api 代理到 http://localhost:8080（VITE_API_TARGET 可改）
npm run build    # 直接输出到 server-application/src/main/resources/static，单容器交付，无需 nginx
```

画布支持：登录/注册、工作流列表与新建、节点拖拽/连线、检查器改参、保存（升版本）、cron 调度、运行与节点时间线、产物抽屉。`vite.config.js` 的 `base: './'` 让静态资源按相对路径加载，挂在任意前缀下都能用。

生产形态（云服务器）：compose 维护在 `SpringCloudAdmin/docker-compose/agent-flow`（与中间件同一网络），镜像在本仓库 `docker build` 后由 compose 引用，密钥只走 secret 文件，见第 5 节。

## 2. 配置参考表

| 键 | 默认值 | 修改时机 |
|---|---|---|
| `server.port` | `8080` | 端口冲突时 |
| `server.shutdown` | `graceful` | 不要改：上线口径排空在途请求 |
| `spring.lifecycle.timeout-per-shutdown-phase` | `20s` | 长工作流节点变多后调大 |
| `server.error.include-stacktrace` | `never` | 不要改：异常不出栈双保险 |
| `agentflow.security.enabled` | 本地 `false` / prod `true` | 对外提供服务的任何环境必须 `true`（yml=代码=文档三处一致） |
| `agentflow.security.api-token` | `${AGENTFLOW_API_TOKEN:}`（空） | 仅环境变量注入，禁止写死进 yml；P3 后作为服务间调用通道（X-API-Token），恒定时间比较。**P7-1 起该通道主体是 uid=0 / role=ops，不是全站管理员**：它只能读写 uid=0 名下的资源，碰他人工作流/凭证一律 404（评审 #2 修复）。用它跑创作脚本时，产出的工作流与成品都归属 uid=0 |
| `agentflow.security.metrics-token` | `${AGENTFLOW_METRICS_TOKEN:}`（空） | **Prometheus 抓取专用身份（P7-2c）**：只对 `GET /actuator/prometheus` 生效，支持 `Authorization: Bearer <token>` 或 `X-Metrics-Token`；同一把 token 调业务 API 一律 401。未配置时该端点保持 401（安全默认）。容器部署走 `AGENTFLOW_METRICS_TOKEN_FILE=/run/secrets/metrics_token`，`gen-secrets.sh` 自动随机生成 |
| `agentflow.security.jwt-secret` | `${AGENTFLOW_JWT_SECRET:}`（空） | env 注入 ≥32B；enabled=true 且未配 → 启动即失败（fail-fast）；prod 必配 |
| `agentflow.security.master-key` | `${AGENTFLOW_MASTER_KEY:}`（空） | env 注入 base64(32B)；凭证库 AES-GCM 主密钥；未配时凭证 API 报 A0400，其余功能不受影响。新密文封装为 `v2:{kid}:base64(iv‖ct)`，启动日志只回显 kid 指纹，便于核对生效密钥 |
| `agentflow.security.master-key-previous` | `${AGENTFLOW_MASTER_KEY_PREVIOUS:}`（空） | **轮换窗口专用（P7-3#16）**：旧主密钥只解密不加密；为空=不启用双读。旧密文在首次被业务读取时自动用新主密钥重封装，全部迁移完成后即可清空此配置 |
| `agentflow.security.token-ttl` | `2h` | JWT 有效期；prod 可改短，改后需同步前端刷新策略 |
| `agentflow.security.rate-limit-per-minute` | `120` | 登录用户（per-uid）与服务通道（独立 `svc` 桶）各自的每分钟固定窗上限；`0`=关掉整套限流；多实例部署时需改网关/Redis 计数（已记账） |
| `agentflow.security.rate-limit-auth-per-minute` | `30` | `/api/auth/**`（注册/登录）单独阈值，**按客户端 IP 分桶**。拆桶原因：原先匿名与服务通道同挤 uid=0 一个桶，刷登录接口就能把两者一起打满（评审 P6 #6） |
| `agentflow.security.trust-forwarded-for` | `false` | 打开后才用 `X-Forwarded-For` 第一段当客户端 IP。**只有前面那层反代会覆盖该头时才能开**，否则攻击者伪造 XFF 即可绕开 IP 桶；不开时全部来源都算反代 IP，auth 桶会退化成单桶 |
| `agentflow.schedule.tick-ms` | `15000` | cron 扫描节拍；测试/多实例调大避免抢发 |
| `spring.datasource.url`（默认 profile） | `jdbc:h2:file:./data/agentflow;MODE=PostgreSQL;...` | 本地不改；删 `data/*.mv.db` 即重建库 |
| `spring.datasource.*`（prod profile） | `${DB_URL}/${DB_USER}/${DB_PASSWORD}` | 云服务器 env 注入 PG16，禁止写死 |
| `spring.datasource.hikari.maximum-pool-size`（prod） | `20` | 多实例部署时按总连接÷PG max_connections 重摊 |
| `spring.flyway.enabled` | `true` | 不要改：schema 只允许迁移文件演进 |
| `management.endpoint.health.probes.enabled` | `true` | 不要改：liveness/readiness 分离是 compose 健康检查依赖 |
| `AGENTFLOW_MASTER_KEY`（环境变量） | 未设置 | 已在 P3 接入（yml 映射到 `agentflow.security.master-key`）；生产上线前必须注入 base64(32B) 主密钥 |
| `AGENTFLOW_MASTER_KEY_PREVIOUS`（环境变量） | 未设置 | 轮换期旧主密钥（base64 32B）；容器走 `AGENTFLOW_MASTER_KEY_PREVIOUS_FILE`，空文件按未配置处理。窗口结束必须清空，否则旧密钥的泄露面一直存在 |
| `agentflow.nodes.llm.api-key` / `image.api-key` | `${AI_API_KEY:${BAILIAN_API_KEY:}}`（空） | env 注入（`AI_API_KEY` 为统一入口，`BAILIAN_API_KEY` 保留兼容；容器走 `AI_API_KEY_FILE`）；空凭证不阻塞启动，`copy.write`/`llm.chat`/`image.cover`/`image.wanxiang` 执行时报不可重试配置错 |
| `agentflow.nodes.llm.default-model` | `qwen-plus` | 节点 params.model 可逐次覆盖（多模型可配置） |
| `agentflow.nodes.image.base-url` | `https://dashscope.aliyuncs.com` | 仅测试/代理环境改；节点 `ratio`/`size` 决定出图尺寸 |
| `agentflow.nodes.wechat.app-id` / `app-secret` | `${WECHAT_APPID:}/${WECHAT_SECRET:}` | env 注入；云端需将出网 IP 加进公众号 IP 白名单；单用户可用 `wechat.draft.credentialId` 走凭证库 |
| `agentflow.content.library-dir` | `${AGENTFLOW_LIBRARY_DIR:./data/library}` | 成品库根目录，所有读写被约束在此目录内（`resolveInside` 防穿越）；容器/云主机必须 env 指向持久卷，否则重启丢成品。启动自检做写探针 |
| `agentflow.exec.max-concurrent` | `${AGENTFLOW_EXEC_MAX_CONCURRENT:16}` | 单实例同时执行的工作流数（P7-3#10 外置）；按节点真实耗时压测后再调，改配置重启即生效，无需重建镜像 |
| `agentflow.exec.max-queued` | `${AGENTFLOW_EXEC_MAX_QUEUED:64}` | 允许等待执行槽的条数；超过即同步 `429/A0429`（不创建执行记录），避免无上限静默积压。`0` = 只跑不排 |
| `agentflow.exec.acquire-timeout` | `${AGENTFLOW_EXEC_ACQUIRE_TIMEOUT:5m}` | 排队等槽上限；超时执行落 `FAILED` 并释放指标 `agentflow.execution.rejected{reason="acquire_timeout"}`，不留永远 `RUNNING` 的记录 |
| `agentflow.resilience.enabled` | `${AGENTFLOW_RESILIENCE_ENABLED:true}` | **下游依赖舱壁/熔断总开关（P7-3#17）**；关闭后节点直连（仅限排障，生产保持开启） |
| `agentflow.resilience.dependencies.<name>.max-concurrent` | llm 8 / image 4 / wechat 4 / xhs 4 / web 8 | 每个依赖独立的进程内并发上限；满舱壁立即 `C0004`（不排队、不打下游），防止一个慢依赖占满全部执行槽 |
| `agentflow.resilience.dependencies.<name>.failure-threshold` | llm/image 5，wechat/xhs 3，web 10 | 连续"依赖侧失败"（仅 C0001 连接拒绝 / C0002 超时 / C0003 协议错误）达到阈值即熔断；A04xx 等客户端错误视为对端可达并重置计数 |
| `agentflow.resilience.dependencies.<name>.open-duration` | 30s（wechat/xhs 60s） | 熔断冷却时长；到期后放行 1 个半开探测，成功闭合、失败重新 open。open 期间节点直接 `C0004`，引擎判不可重试 |
| `agentflow.engine.max-parallel-nodes` | `${AGENTFLOW_ENGINE_MAX_PARALLEL_NODES:8}` | 同一条执行内"同一层（互不依赖）节点"的并发上限（P7-3#18 并行支路）。调大不会让单节点变快，只会让依赖抖动时更容易打穿舱壁；与 `exec.max-concurrent` 相乘才是进程内节点任务的上界。`<1` 启动即失败 |

## 3. 架构与关键决策

- **模块单向依赖**：`server-application → node-adapters → workflow-engine → platform-common`；父 pom 只做版本治理。详见 `docs/IMPLEMENTATION-PLAN.md`。
- **只用 Spring AI（+ Alibaba）做模型层**，不引 LangChain4j——编排层已自研 DAG，LangGraph 类抽象与之正面冲突；且其 Java 侧（langgraph4j）为非官方社区移植，违反"禁单点社区依赖"纪律。2026-10 核查：Spring AI 1.0.x/1.1.x/2.0 三线活跃发版，"停维"传言不成立。
- **模型层可替换缝**：节点只依赖纯 Java 接口 `LlmGateway`，Spring AI 仅是其实现 `SpringAiLlmGateway`——换 SDK 成本封顶在单个实现类，引擎/DSL/日志零改动。
- **编排为自研 DAG 引擎**（JSON DSL + `NodeExecutor` SPI），节点四件套契约：超时/捕获/降级/重试，降级必须带 `degraded=true` 显性标志。
- **超时 = 放弃等待 + 中断，不是"发第二条请求"**：`orTimeout` 只让 future 变异常，引擎会中断仍在跑的工作线程；声明 `hasSideEffects()` 的节点（`wechat.draft` / `xhs.draft` / `image.wanxiang` / `image.cover`）在"结果不可知的超时"后**不再重试**，失败理由固定带一句"副作用结果不可知…请人工核对"。真 HTTP 桩测试 `WechatDraftTimeoutTest` 钉住一次执行只允许 1 个 `draft/add`。
- **规模判断**：100 QPS 峰值 → 模块化单体 + 虚拟线程执行池，不上微服务/消息队列；PG 为生产库（prod profile），本地默认 profile 不依赖任何中间件。
- **traceId 口径**：入口过滤器生成或透传（`X-Trace-Id`），进 MDC、进结构化响应体，请求级 RT 以此为唯一统计口径。
- **创作流水线节点集（P4A）**：`hotspot.fetch`（四源热榜，合并去重、整体不可用降级）→ `article.extract`（链接取正文，自动抽标题）→ `copy.write`（`mode=xiaohongshu|wechat`，按【标记】分节解析出标题/摘要/标签/金句/封面文案）→ `image.cover`（比例预设或任意 `a:b`，长边 1024 折算、边长 512–1440；失败降级为无图）→ `publish.local`（成品库 `article.md`/`note.txt` + `meta.json` + `assets/`）→ `xhs.draft` / `wechat.draft`（发布通道永不静默降级）。
- **成品库与列表 API**：`GET /api/workflows?limit=&beforeId=`、`GET /api/credentials?limit=&beforeId=`、`GET /api/artifacts?kind=&limit=&beforeId=` 全部为键集分页（默认 50 条、上限 200），响应 `{items,nextCursor}`（`nextCursor=null` 表示末页）；`/{id}`、`/{id}/files`、`/{id}/file?path=`；非属主与不存在同为 404（不泄露"存在但无权"），下载走 `filesOf` 白名单 + `resolveInside` 双重防穿越，MIME 未知一律 `application/octet-stream`（不猜 text/html，防存储型 XSS）。
- **成品完整性核对与补偿（P7-3#12a）**：`artifact.file_state`（pending/complete/incomplete）+ `file_error`（Flyway V5）。`publish.local` 先插行拿 id 才能定目录名，所以"有行"不等于"有货"——落盘任一步失败会把原因钉在行上（`incomplete` + `file_error`）再照常抛错。`GET /api/artifacts/incomplete` 列出半套成品，`POST /api/artifacts/{id}/repair` 用库里的 title/body/meta 重渲染 `article.md`/`meta.json`（渲染口径与落盘口径同源 `ArticleMarkdown`，逐字节一致），并实测 `assets/*`：**图不在数据库里，缺了照实返回 `missing` 且状态留在 `incomplete`**，补偿绝不把半套数据刷成绿色。指标 `agentflow.artifact.files{state}` / `agentflow.artifact.repair{state}`。注意 `file_state` 是"发布时状态"，不是磁盘实时状态——手工删过的文件不会被 `/incomplete` 看见，用 `/files`（磁盘实况）或 `repair`（实测并重建）核对。

## 4. 已知限制（延期台账）

> **当前形态口径**：本项目按**单作者/受信小团队自用**交付与验证。首轮专家评审给出对外多用户形态的
> **NO-GO** 决议（🛑 4 项：凭证跨租户可读、`X-API-Token` 即全站 admin、无任何 metrics、DB 宕机时 readiness 仍报 UP），
> 完整算式与证据见 [docs/EXPERT-REVIEW-P6.md](docs/EXPERT-REVIEW-P6.md)。**四条 🛑 已于 P7-1 修完并实机反向验证**，
> 🔶 5 项（密钥 fail-fast / 匿名限流桶 / TIMEOUT 重复发布 / 抢注即接管 / 备份演练）已于 P7-2 修复；
> **对外仍 NO-GO** 的剩余理由：多实例全局配额（#11）、真异地副本缺对象存储凭证（#9 的定时/产物归档/告警已于 P7-3 接上，代码与挂点齐了，缺的是桶凭证）、微信真凭证外发未验。主密钥轮换（#16）与下游舱壁/熔断（#17）已于 P7-3 完成。

| 项 | 状态 | 兑现节点 |
|---|---|---|
| ~~凭证 `credentialId` 无归属校验（他人密钥可被引用发草稿）~~ | ✅ P7-1 已修（端口按 workflow 属主校验，非属主与不存在同为不可用；实机验证"他人引用同一 id 时本地桩一次请求都没收到"） | - |
| ~~无 Prometheus/metrics 端点 + readiness 组不含 db（探针说谎）~~ | ✅ P7-1 已修（5 项业务指标 + readiness `include: readinessState,db,ping`；实机库不可达 liveness 200 / readiness 503） | - |
| ~~metrics 的容器形态 + 真 PG16 复验~~ | ✅ 2026-10-22 已复验（重建镜像后在 pgvector/pg16 + prod profile 上 28 项断言全绿；scratch 库验证后销毁，prod 库仍 0 用户/0 工作流） | - |
| Prometheus 抓取身份 | ✅ P7-2c 已修：新增 `AGENTFLOW_METRICS_TOKEN`（仅解锁 `/actuator/prometheus`，同 token 调业务 API 仍 401）；未配置时端点保持 401。同时修掉 `/actuator/health/../prometheus` 借原始前缀白名单绕过鉴权的路径 | - |
| 密钥 fail-fast 晚于端口绑定；prod 关鉴权只 log | 🔶 见评审 #5 | P7-2（0.5 人日） |
| 匿名请求共享 uid=0 限流桶（登录全局 120/min） | 🔶 见评审 #6 | P7-2 |
| 执行判 TIMEOUT 后节点仍继续跑并真实发布（重复草稿） | 🔶 见评审 #7 | P7-2（1 人日） |
| ~~执行闸门 `Semaphore(16)` 硬编码、排队无超时~~ | ✅ P7-3#10 已修：`agentflow.exec.*` 运行期可配（默认 16 槽 / 排队 64 / 等槽 5m），排队满同步 429，超时执行落 FAILED；容器实测 env 覆盖与非法值 fail-fast | - |
| ~~主密钥无 kid/轮换流程（换密钥后旧凭证全废）~~ | ✅ P7-3#16 已修：primary+previous 双读单写，新密文带 kid，旧密文首次读取自动重封装；容器实测轮换前凭证执行成功、清空 previous 后仍成功 | - |
| ~~下游无熔断/舱壁，一个慢依赖可占满执行槽~~ | ✅ P7-3#17 已修：`DependencyGuard` 在节点注册处按依赖（llm/image/wechat/xhs/web）包舱壁+熔断；连续 C0001–C0003 触发 open，期间 `C0004` 快速失败且不打下游，冷却后单探测恢复；A04xx 不算依赖失败。指标 `agentflow.dependency.state/trips/rejected`，真实 HTTP 桩 E2E 验证 open 期间下游零请求 | - |
| ~~成品库"DB 有行、盘上没货"无补偿入口~~ | ✅ P7-3#12a 已修：`file_state`/`file_error` 落库 + `/api/artifacts/incomplete` 核对 + `/api/artifacts/{id}/repair` 补偿（文本按同源渲染逐字节重建，缺图如实报缺并保持 incomplete）；`ArtifactIntegrityE2ETests` 3 条含反向验证（删文件后下载真 404、缺图补偿不翻绿、非属主 repair 404） | - |
| 多实例：本地成品库 / 进程内限流 / 每实例独立 16 槽 / 舱壁与熔断各自计数 | ⚠️ 单实例假设 | 十万档（评审 §5） |
| ~~DAG 引擎/DSL 校验/执行落库/触发 API~~ | ✅ P1 已交付（见 docs/PROGRESS.md） | - |
| ~~PG 真实实例联调（schema/连接池实机验证）~~ | ✅ P5-4 已交付（pgvector/pg16 容器实机：Flyway 3 迁移全成功、Hikari 连接池 + 2.6s 起服、执行与调度落库、重启数据保留，见 docs/PROGRESS.md） | - |
| ~~cron 定时触发（目前仅手动 API 触发）~~ | ✅ P3 已交付（next_fire_at CAS 领发 + 节拍参数化，见 docs/PROGRESS.md） | - |
| ~~并行分支执行（v1 拓扑序串行）~~ | ✅ P7-3#18 已修：引擎改为按依赖分层，同层节点并发（宽度 `agentflow.engine.max-parallel-nodes`，默认 8），层末汇合即止损点；支路失败 ⇒ 下游零放行、同层兄弟不抢占取消、结果仍按拓扑位序返回。`WorkflowEngineParallelTest` 5 条含反向对照（宽度=1 时菱形屏障必然失败，证明"并发"不是假绿） | - |
| ~~LLM/生图/采集/发布节点~~ | ✅ P2 已交付（http.collect/llm.chat/image.wanxiang/wechat.draft，见 docs/PROGRESS.md） | - |
| ~~创作流水线闭环（热榜/链接/话题 → 双渠道文案 → 指定比例主图 → 成品库 → 草稿）~~ | ✅ P4A 已交付（hotspot.fetch/article.extract/copy.write/image.cover/publish.local/xhs.draft/wechat.draft + `/api/artifacts*`，`P4CreationPipelineE2ETests` 两条通道端到端，见 docs/CREATION-GUIDE.md） | - |
| 百炼真实链路 | ✅ key 已注入并实测（`AI_API_KEY` → DashScope `qwen-plus` HTTP 200）；公众号/小红书草稿外发仍需 `WECHAT_*` 真凭证后跑 `scripts/run-creation.sh` | 用户执行 |
| 小红书**官方草稿 API** | 平台无公开草稿接口：默认只产出图文包落成品库，配 `xhs.draft.endpoint` 才外发；上线第三方 MCN 通道需重评 | P4 后评估 |
| ~~JWT 用户体系、凭证 AES-GCM 加密~~ | ✅ P3 已交付（纯 JDK HS256/PBKDF2/AES-GCM；登录态刷新策略归 P4 前端） | - |
| 多实例横向扩展：限流内存固定窗、cron 领发无分布式锁 | 单实例假设已钉死（台账 P3 决策），多实例前必解 | P5/容量阶段 |
| ~~Vue 画布（LogicFlow）~~ | ✅ P4 已交付（`web/agent-flow-web`，Vue 3 + LogicFlow 2.2；登录/列表/拖拽/保存/调度/执行时间线/产物，构建直出 Spring Boot `static/`） | - |
| ~~Dockerfile / compose 中 server 服务~~ | ✅ P5 已交付（多阶段镜像 + 非 root + `read_only`/`cap_drop ALL`/`no-new-privileges` + `*_FILE` 秘密垫片）。compose 已并到 `SpringCloudAdmin/docker-compose/agent-flow`（唯一一套，my-custom-network），见第 5 节 | - |
| nacos 传递依赖（spring-ai-alibaba 带入） | ✅ P7-3#12 收敛：已在 `spring-ai-alibaba-core` 上排除 `spring-alibaba-nacos-config`（健康指标曾把 `/actuator/health` 拉成 DOWN），启动日志 nacos/ERROR 为 0。**`nacos-client` 无法移除**：`DashScopeApi` 构造期调用其 `StringUtils`，排除即 `NoClassDefFoundError`；fat jar 仍含该 7.8MB（上游依赖，等上游修复） | 已收敛 |
| 管理员只由 env 注入的 bootstrap 账号产生（`AGENTFLOW_BOOTSTRAP_ADMIN_USERNAME/PASSWORD`，P7-2a） | 注册接口永不授予 admin；留空则系统无管理员，需先配置再重启 | ✅ 已收敛 |
| ~~CI 实际执行~~ | ✅ 已生效：`459f44b` 的 [run 39880053665](https://github.com/ITLULU/agent-flow/actions/runs/39880053665) `completed success`（全模块 `mvn -B verify`） | - |
| 本机内网穿透仅用于联调演示 | 10w 用户量级必须云服务器部署（4C8G 起） | 上线前 |

## 5. 生产部署（compose，实机已验）

**唯一一套 compose 维护在 `SpringCloudAdmin/docker-compose/agent-flow/docker-compose.yml`**（与 mysql/qdrant/nacos 同目录风格、同挂 `my-custom-network`）。该文件自包含：只消费镜像 `agentflow/server:0.1.0`，秘密与数据都在 compose 目录下，不引用本仓库任何路径。

两个服务：`agent-flow-db`（pgvector/pg16，不暴露宿主端口）+ `agent-flow-server`（prod profile，8080）。数据用目录挂载（compose 目录下 `data/pg`、`data/library`），容器重建不丢。

```bash
# 1) 在本仓库构建镜像（改了 Java 代码才需要重跑）
cd /path/to/agent-flow
DOCKER_BUILDKIT=1 docker build -t agentflow/server:0.1.0 server/agent-flow-server

# 2) 首跑生成秘密，然后起服务
cd /path/to/SpringCloudAdmin/docker-compose/agent-flow
bash gen-secrets.sh                 # 幂等生成 secrets/（目录 700、文件 600、.gitignore 已覆盖）
docker compose up -d
docker compose ps                   # 两个都 healthy 才算起完（server 健康检查用 readiness 探针）
```

`gen-secrets.sh` 必配：db_password / jwt_secret(≥32B) / master_key(base64 32B) / metrics_token（脚本自动随机）；可空：ai_api_key / wechat_appid / wechat_secret —— 空文件按"未配置"处理，启动不受阻，对应节点执行时才报 A0400 配置错。要跑真实创作链路就在 `secrets/ai_api_key` 写入百炼 key（`AI_API_KEY_FILE` 注入），微信凭证写 `secrets/wechat_*`。

Prometheus 抓取（P7-2c）——令牌只对指标端点生效，别复用 JWT 或业务 API Token：

```yaml
scrape_configs:
  - job_name: agent-flow
    metrics_path: /actuator/prometheus
    authorization:
      credentials_file: /etc/prometheus/agentflow_metrics_token   # 600，内容 = secrets/metrics_token
    static_configs:
      - targets: ["agent-flow-server:8080"]
```

未带令牌访问 `/actuator/prometheus` 返回 401；带正确令牌返回 200 且只授予 `role=metrics` 主体。`curl -H "Authorization: Bearer $(cat secrets/metrics_token)" localhost:8080/actuator/prometheus`。

镜像构建在容器内跑 Maven，`server/agent-flow-server/deploy/maven-settings.xml`（bind 挂载，不进镜像层）指阿里云公共仓 + BuildKit 依赖缓存挂载：首次完整构建实测约 2 分钟，之后改代码的增量重建都在 1 分钟内。

验证与冒烟：

```bash
curl -s localhost:8080/actuator/health/readiness          # {"status":"UP"}
curl -s localhost:8080/api/ping                            # prod 下 security.enabled=true → A0401 才对
docker compose logs agent-flow-server | grep -E "启动自检|Flyway|Started"
# 管理员由 bootstrap 配置注入（P7-2a：注册接口永不授予 admin）——先写口令再重启起服：
#   echo -n '<8-64位口令>' > secrets/bootstrap_admin_password
#   AGENTFLOW_BOOTSTRAP_ADMIN_USERNAME=<你> docker compose up -d --force-recreate
# 之后注册出来的都是普通用户，用 bootstrap 账号登录即可建凭证/跑创作：
curl -s -XPOST localhost:8080/api/auth/register -H 'Content-Type: application/json' \
     -d '{"username":"<普通用户>","password":"<8-64位>"}'
# prod 下 security.enabled=true，创作脚本必须带身份：三选一（AF_TOKEN / AF_USER+AF_PASS / AGENTFLOW_API_TOKEN）
AF_USER=<你> AF_PASS=<8-64位> scripts/run-creation.sh --mode xhs --topic "秋天适合把书一本本读完" --ratio 3:4
```

安全边界（实测口径）：容器 `read_only: true` + `cap_drop: [ALL]` + `no-new-privileges` + tmpfs `/tmp`，运行期只在 `/app/data/library` 卷写盘；秘密经镜像内 `deploy/docker-entrypoint.sh` 白名单把 `*_FILE` 解析成同名 env 后 `unset` 掉 `*_FILE`，因此密钥不进 compose 明文、不进命令行、不进 `docker inspect`，日志只打印变量名。停机口径：`docker compose stop agent-flow-server` → `Graceful shutdown complete` 实测 1s 内完成（在途请求排空、Hikari 干净关闭）。

**主密钥轮换（P7-3#16，双读单写）**：

```bash
cd /path/to/SpringCloudAdmin/docker-compose/agent-flow
openssl rand -base64 32 > secrets/master_key.new     # 1) 生成新主密钥
cp secrets/master_key secrets/master_key_previous    # 2) 旧密钥进"上一把"，新密钥上位
mv secrets/master_key.new secrets/master_key
chmod 600 secrets/master_key secrets/master_key_previous
docker compose up -d --force-recreate agent-flow-server
# 3) 启动日志核对：master key kid 已变化，AGENTFLOW_MASTER_KEY_PREVIOUS present: true
# 4) 旧凭证在被工作流首次解密时自动重封装；用 kid 查还剩多少未迁移（把 <newKid> 换成日志里的值）：
docker compose exec agent-flow-db psql -U agentflow -d agentflow -tAc \
  "SELECT count(*) FROM user_credentials WHERE secret_json_enc NOT LIKE 'v2:<newKid>:%'"
# 5) 计数为 0 后清空旧密钥并重启（此后旧密钥彻底失效）：
: > secrets/master_key_previous && docker compose up -d --force-recreate agent-flow-server
```

旧格式（P3 起裸 Base64）与 `v2:{旧kid}` 密文在窗口内都可读；`previous` 只在解密时参与，新写入永远用新主密钥。**未迁移的凭证若提前清空 previous 将不可解**，所以第 4 步的计数必须归零。多实例部署时每个实例必须注入同一对主密钥/旧密钥（当前仍靠部署层保证）。

升级 / 回滚：先 `git -C /path/to/agent-flow checkout <good-sha>` 并按上面第 1 步重建镜像，再回 compose 目录 `docker compose up -d`（镜像 tag 不变时加 `--force-recreate`）；schema 只由 Flyway 向前迁移，回滚旧镜像前需确认迁移是向后兼容的（破坏性变更要配套备份 `docker compose exec agent-flow-db pg_dump -U agentflow agentflow > backup.sql`）。

备份与恢复演练（评审 P6 #9）：用 `scripts/backup-postgres.sh`，不要手敲 `pg_dump`——口令不落到任何文件，脚本只借 compose 进容器执行。

```bash
bash scripts/backup-postgres.sh backup                 # pg_dump -Fc + 产物库归档 → <compose>/backups/agentflow-<时间戳>.{dump,library.tgz}（0600，各保留最近 7 份）
bash scripts/backup-postgres.sh verify <dump>          # 只验结构可读，不代表能恢复
bash scripts/backup-postgres.sh drill                  # 恢复进临时库 agentflow_drill_*，逐表计数比对后自动 DROP
bash scripts/backup-postgres.sh run-daily [--drill]    # 定时入口：backup → verify（--drill 追加恢复演练），失败必落告警
bash scripts/backup-postgres.sh schedule 03:30         # macOS 登记 launchd 每日任务（Linux 会打印等价 crontab 行）
bash scripts/backup-postgres.sh unschedule             # 撤销登记
```

可覆盖：`--compose DIR`（compose 目录）、`--out DIR`（dump 目录）、`--keep N`、`--file <dump>`，以及环境变量 `SOURCE_DB`（默认 `agentflow`；演练时可指向 scratch 库）、`LIBRARY_DIR`（产物库目录，默认 `<compose>/data/library`——artifact 指向的真实文件在磁盘上，只备 PG 会丢产物）。`drill` 建/删临时库，任何失败路径（含 `pg_restore` 报错）都会清掉临时库，不在实例里留垃圾。

异地副本与告警（都是环境变量，不给就不假装做过）：
- `BACKUP_OFFSITE_DIR=<目录>`：dump 与归档 `cp` 过去并 `cmp` 逐字节核对，适合挂载盘/NFS/第二台机器；
- `BACKUP_OFFSITE_CMD=<命令>`：把两个文件路径追加给自定义上传器（`rclone copy` / `aws s3 cp` / `gsutil cp`）。**对象存储凭证只走云 CLI 自身配置或环境变量，绝不写进本仓库**；
- `BACKUP_ALERT_WEBHOOK=<url>` + `BACKUP_ALERT_LOG`（默认 `<out>/backup-alerts.log`）：任一阶段失败（含 `pg_dump` 自身失败、异地副本字节不一致、上传命令非 0）落一条 `FATAL` 行并 POST 一条 JSON，`launchctl`/`cron` 里没人看 stdout，靠这个留痕。

口径提醒：**没做过恢复演练的备份等于没有备份**；登记了定时不等于备份成功——每次接手先看 `backups/logs/backup.out` 与 `backup-alerts.log`。

已知排查点：本机 `docker` CLI 在 `~/.docker/bin`（不在默认 PATH）；宿主 18080 已被 RocketMQ dashboard 占用，临时探活换端口；Docker Desktop 的 socket 偶发 `permission denied`，重试即可（非应用问题）。nacos 启动噪声已在 P7-3#12 消除（见第 4 节）。
