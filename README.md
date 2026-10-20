# agent-flow

自研 Agent 自动化工作流平台：可视化 DAG 编排（采集 → LLM 写作 → 生图 → 发布），Spring Boot 3.5 / Java 21 模块化单体 + Vue 3 画布。设计目标按生产上线标准（初期 10w 注册用户 / 3w DAU / 峰值 100 QPS），生产部署于云服务器。

## 1. 快速开始

前置：JDK 21、Maven 3.9+（清单见 `docs/TOOLS-CHECKLIST.md`）。**默认 profile 零外部中间件依赖，克隆即可启动。**

```powershell
cd server/agent-flow-server
mvn -B verify                                   # 全模块编译 + 单测/集成测试
mvn -pl server-application spring-boot:run      # 启动（本地默认 profile）
```

验证：

```powershell
curl http://localhost:8080/actuator/health/liveness   # {"status":"UP"}
curl http://localhost:8080/api/ping                    # {"code":"0",...,"data":"pong"}
```

生产形态（云服务器）：`-Dspring-boot.run.profiles=prod` + 环境变量注入密钥 + `docker compose up`（见第 3 节限制）。

## 2. 配置参考表

| 键 | 默认值 | 修改时机 |
|---|---|---|
| `server.port` | `8080` | 端口冲突时 |
| `server.shutdown` | `graceful` | 不要改：上线口径排空在途请求 |
| `spring.lifecycle.timeout-per-shutdown-phase` | `20s` | 长工作流节点变多后调大 |
| `server.error.include-stacktrace` | `never` | 不要改：异常不出栈双保险 |
| `agentflow.security.enabled` | 本地 `false` / prod `true` | 对外提供服务的任何环境必须 `true`（yml=代码=文档三处一致） |
| `agentflow.security.api-token` | `${AGENTFLOW_API_TOKEN:}`（空） | 仅环境变量注入，禁止写死进 yml；P3 后作为服务间调用通道（X-API-Token），恒定时间比较 |
| `agentflow.security.jwt-secret` | `${AGENTFLOW_JWT_SECRET:}`（空） | env 注入 ≥32B；enabled=true 且未配 → 启动即失败（fail-fast）；prod 必配 |
| `agentflow.security.master-key` | `${AGENTFLOW_MASTER_KEY:}`（空） | env 注入 base64(32B)；凭证库 AES-GCM 主密钥；未配时凭证 API 报 A0400，其余功能不受影响 |
| `agentflow.security.token-ttl` | `2h` | JWT 有效期；prod 可改短，改后需同步前端刷新策略 |
| `agentflow.security.rate-limit-per-minute` | `120` | per-用户每分钟固定窗；`0`=关闭；多实例部署时需改网关/Redis 计数（已记账） |
| `agentflow.schedule.tick-ms` | `15000` | cron 扫描节拍；测试/多实例调大避免抢发 |
| `spring.datasource.url`（默认 profile） | `jdbc:h2:file:./data/agentflow;MODE=PostgreSQL;...` | 本地不改；删 `data/*.mv.db` 即重建库 |
| `spring.datasource.*`（prod profile） | `${DB_URL}/${DB_USER}/${DB_PASSWORD}` | 云服务器 env 注入 PG16，禁止写死 |
| `spring.datasource.hikari.maximum-pool-size`（prod） | `20` | 多实例部署时按总连接÷PG max_connections 重摊 |
| `spring.flyway.enabled` | `true` | 不要改：schema 只允许迁移文件演进 |
| `management.endpoint.health.probes.enabled` | `true` | 不要改：liveness/readiness 分离是 compose 健康检查依赖 |
| `AGENTFLOW_MASTER_KEY`（环境变量） | 未设置 | 已在 P3 接入（yml 映射到 `agentflow.security.master-key`）；生产上线前必须注入 base64(32B) 主密钥 |
| `agentflow.nodes.llm.api-key` / `image.api-key` | `${BAILIAN_API_KEY:}`（空） | env 注入；空凭证不阻塞启动，llm.chat/image.wanxiang 执行时报不可重试配置错 |
| `agentflow.nodes.llm.default-model` | `qwen-plus` | 节点 params.model 可逐次覆盖（多模型可配置） |
| `agentflow.nodes.wechat.app-id` / `app-secret` | `${WECHAT_APPID:}/${WECHAT_SECRET:}` | env 注入；云端需将出网 IP 加进公众号 IP 白名单 |

## 3. 架构与关键决策

- **模块单向依赖**：`server-application → node-adapters → workflow-engine → platform-common`；父 pom 只做版本治理。详见 `docs/IMPLEMENTATION-PLAN.md`。
- **只用 Spring AI（+ Alibaba）做模型层**，不引 LangChain4j——编排层已自研 DAG，LangGraph 类抽象与之正面冲突；且其 Java 侧（langgraph4j）为非官方社区移植，违反"禁单点社区依赖"纪律。2026-10 核查：Spring AI 1.0.x/1.1.x/2.0 三线活跃发版，"停维"传言不成立。
- **模型层可替换缝**：节点只依赖纯 Java 接口 `LlmGateway`，Spring AI 仅是其实现 `SpringAiLlmGateway`——换 SDK 成本封顶在单个实现类，引擎/DSL/日志零改动。
- **编排为自研 DAG 引擎**（JSON DSL + `NodeExecutor` SPI），节点四件套契约：超时/捕获/降级/重试，降级必须带 `degraded=true` 显性标志。
- **规模判断**：100 QPS 峰值 → 模块化单体 + 虚拟线程执行池，不上微服务/消息队列；PG 为生产库（prod profile），本地默认 profile 不依赖任何中间件。
- **traceId 口径**：入口过滤器生成或透传（`X-Trace-Id`），进 MDC、进结构化响应体，请求级 RT 以此为唯一统计口径。

## 4. 已知限制（延期台账）

| 项 | 状态 | 兑现节点 |
|---|---|---|
| ~~DAG 引擎/DSL 校验/执行落库/触发 API~~ | ✅ P1 已交付（见 docs/PROGRESS.md） | - |
| PG 真实实例联调（schema/连接池实机验证） | 本地 H2-PG兼容模式已验，云端 PG 待接 | P5 |
| ~~cron 定时触发（目前仅手动 API 触发）~~ | ✅ P3 已交付（next_fire_at CAS 领发 + 节拍参数化，见 docs/PROGRESS.md） | - |
| 并行分支执行（v1 拓扑序串行） | 设延期 | P4 后评估 |
| ~~LLM/生图/采集/发布节点~~ | ✅ P2 已交付（http.collect/llm.chat/image.wanxiang/wechat.draft，见 docs/PROGRESS.md） | - |
| 百炼/万相/公众号**真实链路**联调 | 节点代码与单测已备，开发环境无真 key；注入 BAILIAN_API_KEY/WECHAT_* 后跑 scripts/smoke-workflow-e2e.ps1 延伸项 | 用户执行 |
| ~~JWT 用户体系、凭证 AES-GCM 加密~~ | ✅ P3 已交付（纯 JDK HS256/PBKDF2/AES-GCM；登录态刷新策略归 P4 前端） | - |
| 多实例横向扩展：限流内存固定窗、cron 领发无分布式锁 | 单实例假设已钉死（台账 P3 决策），多实例前必解 | P5/容量阶段 |
| Vue 画布（LogicFlow） | 未建 | P4 |
| Dockerfile / compose 中 server 服务 | compose 已备、镜像构建未落地 | P5 |
| CI 实际执行 | workflow 文件已入库，需绑定 GitHub 远端后生效 | 立即（push 后） |
| 本机内网穿透仅用于联调演示 | 10w 用户量级必须云服务器部署（4C8G 起） | 上线前 |
