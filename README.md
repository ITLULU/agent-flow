# agent-flow

自研 Agent 自动化工作流平台：可视化 DAG 编排（采集 → LLM 写作 → 生图 → 发布），Spring Boot 3.5 / Java 21 模块化单体 + Vue 3 画布。设计目标按生产上线标准（初期 10w 注册用户 / 3w DAU / 峰值 100 QPS），生产部署于云服务器。

## 1. 快速开始

前置：JDK 21、Maven 3.9+、curl + jq（清单见 `docs/TOOLS-CHECKLIST.md`）。**默认 profile 零外部中间件依赖，克隆即可启动。**

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

跑一条真实创作链路（需注入 `BAILIAN_API_KEY`；缺 key 时启动正常、`copy.write` 执行报 A0400 配置错）：

```bash
cd /path/to/agent-flow
scripts/run-creation.sh --mode xhs --topic "秋天适合把书一本本读完" --ratio 3:4
scripts/run-creation.sh --mode wechat --url https://mp.weixin.qq.com/s/xxxx
```

用法、DSL 拆解与成品库 API 见 `docs/CREATION-GUIDE.md`。

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
| `agentflow.security.jwt-secret` | `${AGENTFLOW_JWT_SECRET:}`（空） | env 注入 ≥32B；enabled=true 且未配 → 启动即失败（fail-fast）；prod 必配 |
| `agentflow.security.master-key` | `${AGENTFLOW_MASTER_KEY:}`（空） | env 注入 base64(32B)；凭证库 AES-GCM 主密钥；未配时凭证 API 报 A0400，其余功能不受影响 |
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
| `agentflow.nodes.llm.api-key` / `image.api-key` | `${BAILIAN_API_KEY:}`（空） | env 注入；空凭证不阻塞启动，`copy.write`/`llm.chat`/`image.cover`/`image.wanxiang` 执行时报不可重试配置错 |
| `agentflow.nodes.llm.default-model` | `qwen-plus` | 节点 params.model 可逐次覆盖（多模型可配置） |
| `agentflow.nodes.image.base-url` | `https://dashscope.aliyuncs.com` | 仅测试/代理环境改；节点 `ratio`/`size` 决定出图尺寸 |
| `agentflow.nodes.wechat.app-id` / `app-secret` | `${WECHAT_APPID:}/${WECHAT_SECRET:}` | env 注入；云端需将出网 IP 加进公众号 IP 白名单；单用户可用 `wechat.draft.credentialId` 走凭证库 |
| `agentflow.content.library-dir` | `${AGENTFLOW_LIBRARY_DIR:./data/library}` | 成品库根目录，所有读写被约束在此目录内（`resolveInside` 防穿越）；容器/云主机必须 env 指向持久卷，否则重启丢成品。启动自检做写探针 |

## 3. 架构与关键决策

- **模块单向依赖**：`server-application → node-adapters → workflow-engine → platform-common`；父 pom 只做版本治理。详见 `docs/IMPLEMENTATION-PLAN.md`。
- **只用 Spring AI（+ Alibaba）做模型层**，不引 LangChain4j——编排层已自研 DAG，LangGraph 类抽象与之正面冲突；且其 Java 侧（langgraph4j）为非官方社区移植，违反"禁单点社区依赖"纪律。2026-10 核查：Spring AI 1.0.x/1.1.x/2.0 三线活跃发版，"停维"传言不成立。
- **模型层可替换缝**：节点只依赖纯 Java 接口 `LlmGateway`，Spring AI 仅是其实现 `SpringAiLlmGateway`——换 SDK 成本封顶在单个实现类，引擎/DSL/日志零改动。
- **编排为自研 DAG 引擎**（JSON DSL + `NodeExecutor` SPI），节点四件套契约：超时/捕获/降级/重试，降级必须带 `degraded=true` 显性标志。
- **规模判断**：100 QPS 峰值 → 模块化单体 + 虚拟线程执行池，不上微服务/消息队列；PG 为生产库（prod profile），本地默认 profile 不依赖任何中间件。
- **traceId 口径**：入口过滤器生成或透传（`X-Trace-Id`），进 MDC、进结构化响应体，请求级 RT 以此为唯一统计口径。
- **创作流水线节点集（P4A）**：`hotspot.fetch`（四源热榜，合并去重、整体不可用降级）→ `article.extract`（链接取正文，自动抽标题）→ `copy.write`（`mode=xiaohongshu|wechat`，按【标记】分节解析出标题/摘要/标签/金句/封面文案）→ `image.cover`（比例预设或任意 `a:b`，长边 1024 折算、边长 512–1440；失败降级为无图）→ `publish.local`（成品库 `article.md`/`note.txt` + `meta.json` + `assets/`）→ `xhs.draft` / `wechat.draft`（发布通道永不静默降级）。
- **成品库 API**：`GET /api/artifacts[?kind=&limit=]`、`/{id}`、`/{id}/files`、`/{id}/file?path=`；非属主与不存在同为 404（不泄露"存在但无权"），下载走 `filesOf` 白名单 + `resolveInside` 双重防穿越，MIME 未知一律 `application/octet-stream`（不猜 text/html，防存储型 XSS）。

## 4. 已知限制（延期台账）

> **当前形态口径**：本项目按**单作者/受信小团队自用**交付与验证。首轮专家评审给出对外多用户形态的
> **NO-GO** 决议（🛑 4 项：凭证跨租户可读、`X-API-Token` 即全站 admin、无任何 metrics、DB 宕机时 readiness 仍报 UP），
> 完整算式与证据见 [docs/EXPERT-REVIEW-P6.md](docs/EXPERT-REVIEW-P6.md)。**四条 🛑 已于 P7-1 修完并实机反向验证**（纪要 §7、台账 P7-1），
> 但 🔶 5 项（密钥 fail-fast 时机 / 匿名限流桶 / TIMEOUT 后重复发布 / 抢注即接管 / 无备份）仍未动 ⇒ **决议不因本轮修复自动改变，对外仍 NO-GO**。

| 项 | 状态 | 兑现节点 |
|---|---|---|
| ~~凭证 `credentialId` 无归属校验（他人密钥可被引用发草稿）~~ | ✅ P7-1 已修（端口按 workflow 属主校验，非属主与不存在同为不可用；实机验证"他人引用同一 id 时本地桩一次请求都没收到"） | - |
| ~~无 Prometheus/metrics 端点 + readiness 组不含 db（探针说谎）~~ | ✅ P7-1 已修（5 项业务指标 + readiness `include: readinessState,db,ping`；实机库不可达 liveness 200 / readiness 503） | - |
| ~~metrics 的容器形态 + 真 PG16 复验~~ | ✅ 2026-10-22 已复验（重建镜像后在 pgvector/pg16 + prod profile 上 28 项断言全绿；scratch 库验证后销毁，prod 库仍 0 用户/0 工作流） | - |
| Prometheus 抓取身份未定：`/actuator/prometheus` 未进白名单且 prod 无 `AGENTFLOW_API_TOKEN` ⇒ 现成 Prometheus 抓不到（JWT 2h 过期） | ⚠️ 需拍板：scraper 专用静态 token vs 内网白名单 | P7-2 |
| 密钥 fail-fast 晚于端口绑定；prod 关鉴权只 log | 🔶 见评审 #5 | P7-2（0.5 人日） |
| 匿名请求共享 uid=0 限流桶（登录全局 120/min） | 🔶 见评审 #6 | P7-2 |
| 执行判 TIMEOUT 后节点仍继续跑并真实发布（重复草稿） | 🔶 见评审 #7 | P7-2（1 人日） |
| 执行闸门 `Semaphore(16)` 硬编码、排队无超时 | ⚠️ 见评审 #10 | P7-3 |
| 多实例：本地成品库 / 进程内限流 / 每实例独立 16 槽 | ⚠️ 单实例假设 | 十万档（评审 §5） |
| ~~DAG 引擎/DSL 校验/执行落库/触发 API~~ | ✅ P1 已交付（见 docs/PROGRESS.md） | - |
| ~~PG 真实实例联调（schema/连接池实机验证）~~ | ✅ P5-4 已交付（pgvector/pg16 容器实机：Flyway 3 迁移全成功、Hikari 连接池 + 2.6s 起服、执行与调度落库、重启数据保留，见 docs/PROGRESS.md） | - |
| ~~cron 定时触发（目前仅手动 API 触发）~~ | ✅ P3 已交付（next_fire_at CAS 领发 + 节拍参数化，见 docs/PROGRESS.md） | - |
| 并行分支执行（v1 拓扑序串行） | 设延期 | P4 后评估 |
| ~~LLM/生图/采集/发布节点~~ | ✅ P2 已交付（http.collect/llm.chat/image.wanxiang/wechat.draft，见 docs/PROGRESS.md） | - |
| ~~创作流水线闭环（热榜/链接/话题 → 双渠道文案 → 指定比例主图 → 成品库 → 草稿）~~ | ✅ P4A 已交付（hotspot.fetch/article.extract/copy.write/image.cover/publish.local/xhs.draft/wechat.draft + `/api/artifacts*`，`P4CreationPipelineE2ETests` 两条通道端到端，见 docs/CREATION-GUIDE.md） | - |
| 百炼/万相/公众号**真实链路**联调 | 节点代码与单测已备，开发环境无真 key；注入 BAILIAN_API_KEY/WECHAT_* 后跑 `scripts/run-creation.sh`（或 scripts/smoke-workflow-e2e.ps1） | 用户执行 |
| 小红书**官方草稿 API** | 平台无公开草稿接口：默认只产出图文包落成品库，配 `xhs.draft.endpoint` 才外发；上线第三方 MCN 通道需重评 | P4 后评估 |
| ~~JWT 用户体系、凭证 AES-GCM 加密~~ | ✅ P3 已交付（纯 JDK HS256/PBKDF2/AES-GCM；登录态刷新策略归 P4 前端） | - |
| 多实例横向扩展：限流内存固定窗、cron 领发无分布式锁 | 单实例假设已钉死（台账 P3 决策），多实例前必解 | P5/容量阶段 |
| Vue 画布（LogicFlow） | 未建 | P4 |
| ~~Dockerfile / compose 中 server 服务~~ | ✅ P5 已交付（多阶段镜像 + 非 root + `read_only`/`cap_drop ALL`/`no-new-privileges` + `*_FILE` 秘密垫片）。compose 已并到 `SpringCloudAdmin/docker-compose/agent-flow`（唯一一套，my-custom-network），见第 5 节 | - |
| nacos-client 传递依赖未使用（spring-ai-alibaba 带入，fat jar +7.8MB，启动残留 1 条 `AliyunConfigFilter region` ERROR） | 已消掉启动 ERROR 大头（`-Dnacos.logging.default.config.enabled=false`）；`spring.autoconfigure.exclude` 会让启动失败，故留待评审决定 pom exclusion | P6 |
| 管理员只由 env 注入的 bootstrap 账号产生（`AGENTFLOW_BOOTSTRAP_ADMIN_USERNAME/PASSWORD`，P7-2a） | 注册接口永不授予 admin；留空则系统无管理员，需先配置再重启 | ✅ 已收敛 |
| CI 实际执行 | workflow 文件已入库，需绑定 GitHub 远端后生效 | 立即（push 后） |
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

`gen-secrets.sh` 必配：db_password / jwt_secret(≥32B) / master_key(base64 32B)；可空：bailian_api_key / wechat_appid / wechat_secret —— 空文件按"未配置"处理，启动不受阻，对应节点执行时才报 A0400 配置错。要跑真实创作链路就在 `secrets/` 里补真 key。

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

升级 / 回滚：先 `git -C /path/to/agent-flow checkout <good-sha>` 并按上面第 1 步重建镜像，再回 compose 目录 `docker compose up -d`（镜像 tag 不变时加 `--force-recreate`）；schema 只由 Flyway 向前迁移，回滚旧镜像前需确认迁移是向后兼容的（破坏性变更要配套备份 `docker compose exec agent-flow-db pg_dump -U agentflow agentflow > backup.sql`）。

备份与恢复演练（评审 P6 #9）：用 `scripts/backup-postgres.sh`，不要手敲 `pg_dump`——口令不落到任何文件，脚本只借 compose 进容器执行。

```bash
bash scripts/backup-postgres.sh backup                 # pg_dump -Fc → <compose>/backups/agentflow-<时间戳>.dump（0600，保留最近 7 份）
bash scripts/backup-postgres.sh verify <dump>          # 只验结构可读，不代表能恢复
bash scripts/backup-postgres.sh drill                  # 恢复进临时库 agentflow_drill_*，逐表计数比对后自动 DROP
```

可覆盖：`--compose DIR`（compose 目录）、`--out DIR`（dump 目录）、`--keep N`、`--file <dump>`，以及环境变量 `SOURCE_DB`（默认 `agentflow`；演练时可指向 scratch 库）。`drill` 建/删临时库，任何失败路径（含 `pg_restore` 报错）都会清掉临时库，不在实例里留垃圾。口径提醒：**没做过恢复演练的备份等于没有备份**；本项目的自动化（cron 定时、异地副本、失败告警）仍未接，"备份存在"≠"每天有新备份"。

已知排查点：本机 `docker` CLI 在 `~/.docker/bin`（不在默认 PATH）；宿主 18080 已被 RocketMQ dashboard 占用，临时探活换端口；启动日志残留 1 条 `AliyunConfigFilter - region is not set up yet` ERROR，来自 spring-ai-alibaba 传递引入、本项目并未使用的 nacos-client（见第 4 节 P6 议程）。
